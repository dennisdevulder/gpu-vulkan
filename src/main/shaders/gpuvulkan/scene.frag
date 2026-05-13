#version 450

layout(set = 0, binding = 0) uniform sampler2DArray uTextureArray;

layout(push_constant) uniform Push {
    layout(offset = 96) vec4 fogFrag;  // .rgb = fog/sky color, .a = brightness
} push;

layout(location = 0) in vec3 vColor;       // CPU-decoded RGB (legacy, unused)
layout(location = 1) in float vHslPacked;  // packed HSL int as float (0..65535)
layout(location = 2) in vec2 vUv;
layout(location = 3) flat in uint vTexLayer;
layout(location = 4) in float vFogAmount;
// Face transparency from the CPU side. 0 = opaque; higher = more transparent.
// We output (1 - vTrans/255) as the fragment alpha and rely on the pipeline's
// alphaToCoverageEnable + MSAA: the GPU emits subsamples in proportion to
// alpha. Tent drapes (transparency ~200) write ~22% of samples and look
// translucent. Fire glow tips (transparency 252+) write ~1% and effectively
// disappear. Stock GpuPlugin sorts these into a separate alpha-blend pass;
// alpha-to-coverage is the order-independent approximation that doesn't
// require us to add a sort + second pass.
layout(location = 5) flat in uint vTrans;

layout(location = 0) out vec4 outColor;

// Stock GpuPlugin's hsl_to_rgb.glsl ported.
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
    // Hard-discard on the engine's "fully invisible" sentinel. Alpha-to-
    // coverage would write zero samples here anyway, but discard is faster
    // and bypasses the depth write so distant cutout faces don't punch
    // visible holes in the depth buffer for things behind them.
    if (vTrans == 255u) discard;
    int hsl = int(vHslPacked);
    int hue = (hsl >> 10) & 0x3F;
    int sat = (hsl >> 7)  & 0x07;
    int lum =  hsl        & 0x7F;

    vec3 rgb;
    if (vTexLayer == 0u) {
        // Per-pixel HSL→RGB decode. Matches stock's `smoothBanding = false`
        // (default) behavior: vHslPacked is interpolated linearly as a float
        // across the face, then bit-decoded into HSL components per fragment.
        // Produces the distinctive faceted-banded shading on multi-face
        // models like crystals — pre-decoding RGB on CPU blurs it away.
        rgb = hslToRgb(hue, sat, lum);
    } else {
        vec4 texSample = textureLod(uTextureArray, vec3(vUv, float(vTexLayer)), 0.0);
        if (texSample.a < 1.0) discard;
        vec3 tex = pow(texture(uTextureArray, vec3(vUv, float(vTexLayer))).rgb,
                       vec3(push.fogFrag.a));
        // Stock textureLightMode=0: light = (low 7 bits / 127). For textured
        // faces the engine bakes pure lightness in the low 7 bits, so this
        // gives proper per-face shading — water, crystals, doors.
        float light = float(lum) / 127.0;
        rgb = tex * light;
    }
    rgb = mix(rgb, push.fogFrag.rgb, vFogAmount);
    // Output alpha = 1 - faceTransparency/255. Pipeline has
    // alphaToCoverageEnable, so this drives MSAA per-sample coverage:
    // - vTrans = 0   → alpha = 1.00 → all samples (opaque)
    // - vTrans = 200 → alpha = 0.22 → ~22% samples (drape, banner)
    // - vTrans = 252 → alpha = 0.012 → ~1% samples (fire glow tip, near-invisible)
    float alpha = 1.0 - float(vTrans) / 255.0;
    outColor = vec4(rgb, alpha);
}
