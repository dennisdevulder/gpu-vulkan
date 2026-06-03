#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D uTex;

// Engine overlay tint (login-screen fade, bank-PIN dim).
// .rgb tint, .a blend factor. Zero engine overlayColor → all-zero
// push → mix() is a no-op.
layout(push_constant) uniform Push {
    vec4 overlay;
    vec4 colorBlind; // .x = mode, .y = intensity (0..1)
} push;

vec3 applyColorBlind(vec3 color, int mode, float intensity)
{
    if (mode == 0) return color;
    const mat3 rgb2lms = mat3(
        vec3(17.8824,   43.5161,  4.11935),
        vec3(3.45565,   27.1554,  3.86714),
        vec3(0.0299566, 0.184309, 1.46709));
    const mat3 corrections = mat3(
        vec3(0.0, 0.0, 0.0),
        vec3(0.7, 1.0, 0.0),
        vec3(0.7, 0.0, 1.0));

    vec3 LMS = color * rgb2lms;
    vec3 lms;
    if (mode == 1) {
        lms = LMS * mat3(vec3(0.0, 2.02344, -2.52581),
                         vec3(0.0, 1.0,      0.0),
                         vec3(0.0, 0.0,      1.0));
    } else if (mode == 2) {
        lms = LMS * mat3(vec3(1.0,      0.0, 0.0),
                         vec3(0.494207, 0.0, 1.24827),
                         vec3(0.0,      0.0, 1.0));
    } else {
        lms = LMS * mat3(vec3(1.0,       0.0,      0.0),
                         vec3(0.0,       1.0,      0.0),
                         vec3(-0.395913, 0.801109, 0.0));
    }

    mat3 lms2rgb = inverse(rgb2lms);
    vec3 error = color - (lms * lms2rgb);
    vec3 correction = error * corrections;
    correction *= clamp(intensity, 0.0, 1.0);
    return color + correction;
}

void main() {
    vec4 sampled = texture(uTex, vUv);
    vec3 rgb = mix(sampled.rgb, push.overlay.rgb, push.overlay.a);
    if (push.colorBlind.x > 0.5) {
        rgb = applyColorBlind(rgb, int(push.colorBlind.x), push.colorBlind.y);
    }
    outColor = vec4(rgb, sampled.a);
}
