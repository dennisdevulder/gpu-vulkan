#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D uUpscaled;

layout(push_constant) uniform Push {
    vec4 params; // x=width, y=height, z=sharpness
} push;

vec3 tap(vec2 uv) {
    return texture(uUpscaled, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
}

void main() {
    vec2 texel = 1.0 / max(push.params.xy, vec2(1.0));
    vec3 c = tap(vUv);
    vec3 n = tap(vUv + vec2(0.0, -texel.y));
    vec3 s = tap(vUv + vec2(0.0,  texel.y));
    vec3 e = tap(vUv + vec2( texel.x, 0.0));
    vec3 w = tap(vUv + vec2(-texel.x, 0.0));

    vec3 mn = min(c, min(min(n, s), min(e, w)));
    vec3 mx = max(c, max(max(n, s), max(e, w)));
    vec3 lobe = n + s + e + w - c * 4.0;

    float peak = max(max(mx.r, mx.g), mx.b);
    float floorv = min(min(mn.r, mn.g), mn.b);
    float contrast = clamp(peak - floorv, 0.0, 1.0);
    float amount = mix(0.05, 0.28, clamp(push.params.z, 0.0, 1.0));
    amount *= mix(1.0, 0.35, contrast);

    vec3 sharpened = c - lobe * amount;
    outColor = vec4(clamp(sharpened, mn, mx), 1.0);
}
