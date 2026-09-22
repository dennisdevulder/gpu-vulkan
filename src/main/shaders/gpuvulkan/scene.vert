#version 450

// Captured OSRS geometry. Positions stay float3 so animated/temp models keep
// stock GPU's sub-unit coordinates; colour is packed HSL plus alpha/bias;
// texture id and UV are signed shorts.
//
// Push layout (the vertex stage owns 0..99, the fragment stage 100..123):
//   offset 0..63  : mat4 mvp
//   offset 64..79 : vec4 fogVtx
//                   .x = cameraX (world)
//                   .y = cameraZ (world)
//                   .z = drawDistance * TILE_SIZE
//                   .w = fogDepth     * TILE_SIZE
//   offset 80..95 : ivec4 misc
//                   .x = current game tick (modulo small enough that
//                        `tick * anim * (1/128)` doesn't overflow visible UV)
//                   .y = sub-worldview translate X (toplevel scene units)
//                   .z = sub-worldview translate Z
//                   .w = sub-worldview yaw (JAU, 0..2047)
//                   .yzw are zero for top-level draws (local == world);
//                   used only to reconstruct world XZ for fog — the clip
//                   position comes from the pre-composed (world*entity) mvp
//   offset 96..99 : int entityTint
//                   the engine's per-scene HSL override as four signed bytes:
//                   hue<<24 | sat<<16 | lum<<8 | amount. Zero (amount 0) is a
//                   no-op, which is what the top level pushes.

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 fogVtx;
    ivec4 misc;
    int entityTint;
} pc;

// Scene uniforms, set 0 binding 1.
//   fogScene.xy = fog window clamp edges in world units: the scene-edge
//                 rectangle scales with the engine's expanded-map-loading
//                 chunks (stock vert.glsl FOG_SCENE_EDGE_MIN/MAX); .zw unused
//   anim[]      = per-texture-layer UV scroll vectors (texels per tick),
//                 std140-padded to vec4 per entry. Layer 0 (white reserve)
//                 and any non-animated texture have (0, 0).
layout(set = 0, binding = 1, std140) uniform SceneUniforms {
    vec4 fogScene;
    vec4 anim[256];
};

layout(location = 0) in vec3 inPosition;
layout(location = 1) in uint inAlphaBiasHsl;
layout(location = 2) in ivec4 inTextureUv;

layout(location = 0) out vec3 vColor;
layout(location = 1) out float vLight;
layout(location = 2) out vec2 vUv;
layout(location = 3) flat out uint vTexLayer;
layout(location = 4) out float vFogAmount;
// Face transparency byte (0 = opaque, 255 = engine invisibility sentinel).
// flat — same value at all 3 vertices.
layout(location = 5) flat out uint vTrans;

const float TILE_SIZE = 128.0;
const float TEXTURE_ANIM_UNIT = 1.0 / 128.0;
const float FOG_CORNER_ROUNDING = 1.5;
const float FOG_CORNER_ROUNDING_SQUARED = FOG_CORNER_ROUNDING * FOG_CORNER_ROUNDING;

float fogFactorLinear(float dist, float start, float end) {
    return 1.0 - clamp((dist - start) / (end - start), 0.0, 1.0);
}

float hueToChannel(float p, float q, float t) {
    if (t > 1.0) {
        t -= 1.0;
    } else if (t < 0.0) {
        t += 1.0;
    }
    if (6.0 * t < 1.0) {
        return p + (q - p) * 6.0 * t;
    }
    if (2.0 * t < 1.0) {
        return q;
    }
    if (3.0 * t < 2.0) {
        return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    }
    return p;
}

// hsl carries the raw component values: h 0..63, s 0..7, l 0..127.
vec3 hslToRgb(vec3 hsl) {
    float hue = hsl.x / 64.0 + 0.0078125;
    float sat = hsl.y / 8.0 + 0.0625;
    float lum = hsl.z / 128.0;
    float q = lum < 0.5 ? lum * (1.0 + sat) : lum + sat - lum * sat;
    float p = 2.0 * lum - q;
    return vec3(
        hueToChannel(p, q, hue + 1.0 / 3.0),
        hueToChannel(p, q, hue),
        hueToChannel(p, q, hue - 1.0 / 3.0));
}

void main() {
    uint hsl = inAlphaBiasHsl & 0xFFFFu;
    uint biasByte = (inAlphaBiasHsl >> 16) & 0xFFu;
    vTrans = (inAlphaBiasHsl >> 24) & 0xFFu;

    // Per-scene HSL override, ported from stock vert.glsl. Component-space
    // lerp with amount/128; amount stays a signed byte as the engine sends it.
    vec3 hslComponents = vec3(
        float((hsl >> 10) & 0x3Fu),
        float((hsl >> 7) & 0x07u),
        float(hsl & 0x7Fu));
    vec3 tintComponents = vec3(
        float(bitfieldExtract(pc.entityTint, 24, 8)),
        float(bitfieldExtract(pc.entityTint, 16, 8)),
        float(bitfieldExtract(pc.entityTint,  8, 8)));
    float tintAmount = float(bitfieldExtract(pc.entityTint, 0, 8));
    hslComponents += ((tintComponents - hslComponents) * tintAmount) / 128.0;

    vec3 position = inPosition;
    vec4 clip = pc.mvp * vec4(position, 1.0);
    float bias = float(biasByte) / 128.0;
    clip.z += bias;
    gl_Position = clip;

    vColor = hslToRgb(hslComponents);

    int textureId = inTextureUv.x;
    vTexLayer = textureId <= 0 ? 0u : uint(textureId);
    // Texture lighting is not tinted (stock keeps fHsl raw for textured faces);
    // untextured faces repack the tinted components so smooth banding matches.
    vLight = textureId > 0
        ? float(hsl)
        : float(((int(hslComponents.x) & 0x3F) << 10)
              | ((int(hslComponents.y) & 0x07) << 7)
              |  (int(hslComponents.z) & 0x7F));

    // Per-tick UV scroll for animated textures (water, lava, etc.). Anim
    // is in texels per tick; multiply by tick and the texel→UV unit to get
    // a UV offset. Sampler is REPEAT so we don't need to wrap manually.
    uint layer = vTexLayer;
    vec2 anim2 = (layer < 256u) ? anim[layer].xy : vec2(0.0);
    vec2 uv = vec2(float(inTextureUv.y), float(inTextureUv.z)) / 256.0;
    vUv = uv + float(pc.misc.x) * anim2 * TEXTURE_ANIM_UNIT;

    // Sub-worldview placement: rebuild toplevel-scene XZ for the fog window.
    // Rotation matches the CPU capture convention (writeRotatedVertex):
    // rx = x*cos + z*sin, rz = -x*sin + z*cos. Top-level draws push 0/0/0,
    // making this an identity transform — one code path, no branch.
    float entYaw = float(pc.misc.w) * 0.00306796157; // JAU -> radians (2pi/2048)
    float entCos = cos(entYaw);
    float entSin = sin(entYaw);
    float worldX = position.x * entCos + position.z * entSin + float(pc.misc.y);
    float worldZ = -position.x * entSin + position.z * entCos + float(pc.misc.z);

    // Fog from distance-to-scene-edge with corner rounding.
    float cameraX = pc.fogVtx.x;
    float cameraZ = pc.fogVtx.y;
    float drawDistance = pc.fogVtx.z;
    float fogDepth = pc.fogVtx.w;
    float fogWest  = max(fogScene.x, cameraX - drawDistance);
    float fogEast  = min(fogScene.y, cameraX + drawDistance);
    float fogSouth = max(fogScene.x, cameraZ - drawDistance);
    float fogNorth = min(fogScene.y, cameraZ + drawDistance);
    float xDist = min(worldX - fogWest, fogEast - worldX);
    float zDist = min(worldZ - fogSouth, fogNorth - worldZ);
    float nearest = min(xDist, zDist);
    float second  = max(xDist, zDist);
    float fogDist = nearest - FOG_CORNER_ROUNDING * TILE_SIZE *
        max(0.0, (nearest + FOG_CORNER_ROUNDING_SQUARED) / (second + FOG_CORNER_ROUNDING_SQUARED));
    vFogAmount = (fogDepth > 0.0) ? fogFactorLinear(fogDist, 0.0, fogDepth) : 0.0;
}
