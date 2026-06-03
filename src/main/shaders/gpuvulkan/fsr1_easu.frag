#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D uScene;

layout(push_constant) uniform Push {
    vec4 params; // x=sourceWidth, y=sourceHeight, z=outputWidth, w=outputHeight
} push;

vec3 tap(vec2 p) {
    return texture(uScene, clamp(p, vec2(0.0), vec2(1.0))).rgb;
}

float lum(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float weight(vec2 d, vec2 dir, float stretch) {
    vec2 n = vec2(dot(d, dir), dot(d, vec2(-dir.y, dir.x)));
    n.y *= stretch;
    float r2 = dot(n, n);
    return max(0.0, 1.0 - r2 * 0.28);
}

void main() {
    vec2 srcSize = max(push.params.xy, vec2(1.0));
    vec2 outSize = max(push.params.zw, vec2(1.0));
    vec2 srcPos = (gl_FragCoord.xy + vec2(0.5)) * srcSize / outSize - vec2(0.5);
    vec2 base = floor(srcPos);
    vec2 fracPos = srcPos - base;
    vec2 texel = 1.0 / srcSize;

    vec2 uv00 = (base + vec2(0.5, 0.5)) * texel;
    vec3 c00 = tap(uv00);
    vec3 c10 = tap(uv00 + vec2(texel.x, 0.0));
    vec3 c01 = tap(uv00 + vec2(0.0, texel.y));
    vec3 c11 = tap(uv00 + texel);

    vec3 n00 = tap(uv00 - vec2(0.0, texel.y));
    vec3 s00 = tap(uv00 + vec2(0.0, texel.y));
    vec3 e00 = tap(uv00 + vec2(texel.x, 0.0));
    vec3 w00 = tap(uv00 - vec2(texel.x, 0.0));
    vec3 ne = tap(uv00 + vec2(texel.x, -texel.y));
    vec3 nw = tap(uv00 + vec2(-texel.x, -texel.y));
    vec3 se = tap(uv00 + vec2(texel.x, texel.y));
    vec3 sw = tap(uv00 + vec2(-texel.x, texel.y));

    float gx = abs(lum(e00) - lum(w00))
        + 0.5 * abs(lum(ne) - lum(nw))
        + 0.5 * abs(lum(se) - lum(sw));
    float gy = abs(lum(s00) - lum(n00))
        + 0.5 * abs(lum(se) - lum(ne))
        + 0.5 * abs(lum(sw) - lum(nw));

    vec2 dir = normalize(vec2(gx, gy) + vec2(0.0001));
    float edge = clamp(abs(gx - gy) * 4.0, 0.0, 1.0);
    float stretch = mix(1.0, 0.45, edge);

    vec3 sum = vec3(0.0);
    float norm = 0.0;

    vec2 p;
    float w;

    p = vec2(0.0, 0.0) - fracPos; w = weight(p, dir, stretch); sum += c00 * w; norm += w;
    p = vec2(1.0, 0.0) - fracPos; w = weight(p, dir, stretch); sum += c10 * w; norm += w;
    p = vec2(0.0, 1.0) - fracPos; w = weight(p, dir, stretch); sum += c01 * w; norm += w;
    p = vec2(1.0, 1.0) - fracPos; w = weight(p, dir, stretch); sum += c11 * w; norm += w;
    p = vec2(0.0, -1.0) - fracPos; w = weight(p, dir, stretch) * 0.55; sum += n00 * w; norm += w;
    p = vec2(0.0, 1.0) - fracPos; w = weight(p, dir, stretch) * 0.55; sum += s00 * w; norm += w;
    p = vec2(1.0, 0.0) - fracPos; w = weight(p, dir, stretch) * 0.55; sum += e00 * w; norm += w;
    p = vec2(-1.0, 0.0) - fracPos; w = weight(p, dir, stretch) * 0.55; sum += w00 * w; norm += w;
    p = vec2(1.0, -1.0) - fracPos; w = weight(p, dir, stretch) * 0.35; sum += ne * w; norm += w;
    p = vec2(-1.0, -1.0) - fracPos; w = weight(p, dir, stretch) * 0.35; sum += nw * w; norm += w;
    p = vec2(1.0, 1.0) - fracPos; w = weight(p, dir, stretch) * 0.35; sum += se * w; norm += w;
    p = vec2(-1.0, 1.0) - fracPos; w = weight(p, dir, stretch) * 0.35; sum += sw * w; norm += w;

    vec3 bilinear = mix(mix(c00, c10, fracPos.x), mix(c01, c11, fracPos.x), fracPos.y);
    vec3 easu = sum / max(norm, 0.0001);
    outColor = vec4(clamp(mix(bilinear, easu, 0.85), 0.0, 1.0), 1.0);
}
