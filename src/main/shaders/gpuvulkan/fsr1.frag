#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D uScene;

layout(push_constant) uniform Push {
    vec4 params; // x=sourceWidth, y=sourceHeight, z=sharpness
} push;

vec3 sampleScene(vec2 uv) {
    return texture(uScene, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
}

void main() {
    vec2 texel = 1.0 / max(push.params.xy, vec2(1.0));
    vec3 c = sampleScene(vUv);

    vec3 n = sampleScene(vUv + vec2(0.0, -texel.y));
    vec3 s = sampleScene(vUv + vec2(0.0,  texel.y));
    vec3 e = sampleScene(vUv + vec2( texel.x, 0.0));
    vec3 w = sampleScene(vUv + vec2(-texel.x, 0.0));

    // Lightweight FSR1-style RCAS pass. The true FSR1 integration uses EASU
    // followed by RCAS; here the sampler performs reconstruction and this
    // pass adds contrast-adaptive sharpening.
    vec3 blur = (n + s + e + w) * 0.25;
    float lumaC = dot(c, vec3(0.299, 0.587, 0.114));
    float lumaB = dot(blur, vec3(0.299, 0.587, 0.114));
    float adapt = clamp(abs(lumaC - lumaB) * 4.0, 0.0, 1.0);
    float amount = clamp(push.params.z, 0.0, 1.0) * mix(0.35, 0.08, adapt);
    vec3 sharpened = c + (c - blur) * amount;

    outColor = vec4(clamp(sharpened, 0.0, 1.0), 1.0);
}
