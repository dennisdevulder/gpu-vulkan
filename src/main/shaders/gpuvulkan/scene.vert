#version 450

// Captured OSRS geometry. World-space positions packed on the CPU, transformed
// here by an MVP push constant. UV + texLayer + light + bias passed through.
//
// Push layout:
//   offset 0..63  : mat4 mvp                  (vertex)
//   offset 64..79 : vec4 fogVtx               (vertex)
//                   .x = cameraX (world)
//                   .y = cameraZ (world)
//                   .z = drawDistance * TILE_SIZE
//                   .w = fogDepth     * TILE_SIZE
//   offset 80..95 : ivec4 misc                (vertex)
//                   .x = current game tick (modulo small enough that
//                        `tick * anim * (1/128)` doesn't overflow visible UV)
//   offset 96..111: vec4 fogFrag              (fragment; not used here)
//                   .rgb = fog color (= skybox)
//                   .a   = brightness

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 fogVtx;
    ivec4 misc;
} pc;

// Per-texture-layer UV scroll vectors (texels per tick). Bound as set 0
// binding 1, std140-padded to vec4 per entry. Layer 0 (white reserve) and
// any non-animated texture have (0, 0).
layout(set = 0, binding = 1, std140) uniform TextureAnimations {
    vec4 anim[256];
};

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inColor;
layout(location = 2) in float inLight;
layout(location = 3) in vec2 inUv;
layout(location = 4) in uint inTexLayer;

layout(location = 0) out vec3 vColor;
layout(location = 1) out float vLight;
layout(location = 2) out vec2 vUv;
layout(location = 3) flat out uint vTexLayer;
layout(location = 4) out float vFogAmount;
// Face transparency byte (0 = opaque, 255 = engine "invisible" sentinel).
// Flat-interpolated because it's the same for all 3 vertices of a face.
// Frag uses it only to discard fully-invisible faces; we render the rest
// opaque since we don't have an alpha-blended pass yet.
layout(location = 5) flat out uint vTrans;

const float TILE_SIZE = 128.0;
const float TEXTURE_ANIM_UNIT = 1.0 / 128.0;
const float FOG_CORNER_ROUNDING = 1.5;
const float FOG_CORNER_ROUNDING_SQUARED = FOG_CORNER_ROUNDING * FOG_CORNER_ROUNDING;
const float FOG_SCENE_EDGE_MIN = 1.0 * TILE_SIZE;
const float FOG_SCENE_EDGE_MAX = 103.0 * TILE_SIZE;

float fogFactorLinear(float dist, float start, float end) {
    return 1.0 - clamp((dist - start) / (end - start), 0.0, 1.0);
}

void main() {
    vec4 clip = pc.mvp * vec4(inPosition, 1.0);
    float bias = float((inTexLayer >> 16) & 0xFFu) / 128.0;
    clip.z += bias;
    gl_Position = clip;
    vColor    = inColor;
    vLight    = inLight;
    vTexLayer = inTexLayer & 0xFFFFu;
    vTrans    = (inTexLayer >> 24) & 0xFFu;

    // Per-tick UV scroll for animated textures (water, lava, etc.). Anim
    // is in texels per tick; multiply by tick and the texel→UV unit to get
    // a UV offset. Sampler is REPEAT so we don't need to wrap manually.
    uint layer = vTexLayer;
    vec2 anim2 = (layer < 256u) ? anim[layer].xy : vec2(0.0);
    vUv = inUv + float(pc.misc.x) * anim2 * TEXTURE_ANIM_UNIT;

    // Fog from distance-to-scene-edge with corner rounding.
    float cameraX = pc.fogVtx.x;
    float cameraZ = pc.fogVtx.y;
    float drawDistance = pc.fogVtx.z;
    float fogDepth = pc.fogVtx.w;
    float fogWest  = max(FOG_SCENE_EDGE_MIN, cameraX - drawDistance);
    float fogEast  = min(FOG_SCENE_EDGE_MAX, cameraX + drawDistance);
    float fogSouth = max(FOG_SCENE_EDGE_MIN, cameraZ - drawDistance);
    float fogNorth = min(FOG_SCENE_EDGE_MAX, cameraZ + drawDistance);
    float xDist = min(inPosition.x - fogWest, fogEast - inPosition.x);
    float zDist = min(inPosition.z - fogSouth, fogNorth - inPosition.z);
    float nearest = min(xDist, zDist);
    float second  = max(xDist, zDist);
    float fogDist = nearest - FOG_CORNER_ROUNDING * TILE_SIZE *
        max(0.0, (nearest + FOG_CORNER_ROUNDING_SQUARED) / (second + FOG_CORNER_ROUNDING_SQUARED));
    vFogAmount = (fogDepth > 0.0) ? fogFactorLinear(fogDist, 0.0, fogDepth) : 0.0;
}
