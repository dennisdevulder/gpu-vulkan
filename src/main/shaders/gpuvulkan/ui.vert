#version 450

// Fullscreen-quad-via-triangle trick. Three vertices form a triangle that
// fully covers NDC; the part outside [-1, 1] is clipped. UV interpolates
// 0..1 across the visible region. No vertex buffer needed.

layout(location = 0) out vec2 vUv;

void main() {
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    vUv = pos;
}
