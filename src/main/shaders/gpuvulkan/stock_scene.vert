#version 450

// Stock GPU style packed zone vertex:
//   loc0: short local x/y/z
//   loc1: uint [alpha:8 | bias:8 | hsl:16]
//   loc2: short texture/u/v/pad
//
// Push misc.y/misc.z carry the zone/world base x/z in scene units.

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 fogVtx;
    ivec4 misc;
} pc;

layout(set = 0, binding = 1, std140) uniform TextureAnimations {
    vec4 anim[256];
};

layout(location = 0) in ivec3 inLocalPosition;
layout(location = 1) in uint inAlphaBiasHsl;
layout(location = 2) in ivec4 inTextureUv;

layout(location = 0) out vec3 vColor;
layout(location = 1) out float vHslPacked;
layout(location = 2) out vec2 vUv;
layout(location = 3) flat out uint vTexLayer;
layout(location = 4) out float vFogAmount;
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

vec3 hslToRgb(int hue, int sat, int lum) {
    float h = float(hue) / 64.0 + 0.0078125;
    float s = float(sat) / 8.0  + 0.0625;
    float l = float(lum) / 128.0;
    float q = (l < 0.5) ? l * (1.0 + s) : l + s - l * s;
    float p = 2.0 * l - q;
    vec3 t = vec3(h + 1.0/3.0, h, h - 1.0/3.0);
    t = mix(t, t + 1.0, lessThan(t, vec3(0)));
    t = mix(t, t - 1.0, greaterThan(t, vec3(1)));
    vec3 r = mix(vec3(p), vec3(q), step(t, vec3(0.5)));
    r = mix(r, p + (q - p) * (2.0/3.0 - t) * 6.0, step(t, vec3(2.0/3.0)));
    r = mix(r, p + (q - p) * 6.0 * t, step(t, vec3(1.0/6.0)));
    return r;
}

void main() {
    uint hsl = inAlphaBiasHsl & 0xFFFFu;
    uint bias = (inAlphaBiasHsl >> 16) & 0xFFu;
    vTrans = (inAlphaBiasHsl >> 24) & 0xFFu;

    vec3 position = vec3(
        float(pc.misc.y + inLocalPosition.x),
        float(inLocalPosition.y),
        float(pc.misc.z + inLocalPosition.z));

    vec4 clip = pc.mvp * vec4(position, 1.0);
    clip.z += float(bias) / 128.0;
    gl_Position = clip;

    int hslInt = int(hsl);
    int hue = (hslInt >> 10) & 0x3F;
    int sat = (hslInt >> 7) & 0x07;
    int lum = hslInt & 0x7F;
    vColor = hslToRgb(hue, sat, lum);
    vHslPacked = float(hsl);

    int textureId = inTextureUv.x;
    vTexLayer = textureId <= 0 ? 0u : uint(textureId);
    vec2 uv = vec2(float(inTextureUv.y), float(inTextureUv.z)) * TEXTURE_ANIM_UNIT;
    vec2 anim2 = (vTexLayer < 256u) ? anim[vTexLayer].xy : vec2(0.0);
    vUv = uv + float(pc.misc.x) * anim2 * TEXTURE_ANIM_UNIT;

    float cameraX = pc.fogVtx.x;
    float cameraZ = pc.fogVtx.y;
    float drawDistance = pc.fogVtx.z;
    float fogDepth = pc.fogVtx.w;
    float fogWest  = max(FOG_SCENE_EDGE_MIN, cameraX - drawDistance);
    float fogEast  = min(FOG_SCENE_EDGE_MAX, cameraX + drawDistance);
    float fogSouth = max(FOG_SCENE_EDGE_MIN, cameraZ - drawDistance);
    float fogNorth = min(FOG_SCENE_EDGE_MAX, cameraZ + drawDistance);
    float xDist = min(position.x - fogWest, fogEast - position.x);
    float zDist = min(position.z - fogSouth, fogNorth - position.z);
    float nearest = min(xDist, zDist);
    float second = max(xDist, zDist);
    float fogDist = nearest - FOG_CORNER_ROUNDING * TILE_SIZE *
        max(0.0, (nearest + FOG_CORNER_ROUNDING_SQUARED) / (second + FOG_CORNER_ROUNDING_SQUARED));
    vFogAmount = (fogDepth > 0.0) ? fogFactorLinear(fogDist, 0.0, fogDepth) : 0.0;
}
