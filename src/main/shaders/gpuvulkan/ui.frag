#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D uTex;

// Engine-driven overlay color (login-screen fade, bank-PIN dim, etc.).
// .rgb is the tint color, .a is the blend factor. When the engine passes
// overlayColor == 0 in DrawCallbacks.draw(int), the host writes all zeros
// and the mix() below is a no-op.
layout(push_constant) uniform Push {
    vec4 overlay;
} push;

void main() {
    vec4 sampled = texture(uTex, vUv);
    outColor = vec4(mix(sampled.rgb, push.overlay.rgb, push.overlay.a), sampled.a);
}
