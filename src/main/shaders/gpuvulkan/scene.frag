#version 450

// The hslToRgb function is ported verbatim from RuneLite's
// net.runelite.client.plugins.gpu.hsl_to_rgb.glsl (BSD-2-Clause).
// The applyColorBlind function is ported from RuneLite's colorblind.glsl
// (BSD-2-Clause), implementing the Daltonization algorithm published in
// "Analysis of Color Blindness" by Onur Fidaner, Poliang Lin, and Nevran
// Ozguven (Stanford PSYCH 221, 2005). Stock's preprocessor #if branches
// for the mode are replaced with a runtime `if` so one shader handles
// all four modes. Original copyright + license for both ports:
//
//   Copyright (c) 2018, Adam <Adam@sigterm.info>
//   All rights reserved.
//
//   Redistribution and use in source and binary forms, with or without
//   modification, are permitted provided that the following conditions are met:
//
//   1. Redistributions of source code must retain the above copyright notice, this
//      list of conditions and the following disclaimer.
//   2. Redistributions in binary form must reproduce the above copyright notice,
//      this list of conditions and the following disclaimer in the documentation
//      and/or other materials provided with the distribution.
//
//   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
//   ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
//   WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
//   DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
//   ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
//   (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
//   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
//   ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
//   (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
//   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

layout(set = 0, binding = 0) uniform sampler2DArray uTextureArray;

layout(push_constant) uniform Push {
    layout(offset = 96)  vec4 fogFrag;     // .rgb = fog/sky color, .a = brightness
    layout(offset = 112) vec4 fragExtras;  // .x = textureLightMode (0 = light-only, 1 = full HSL tint)
                                           // .y = colorBlindMode (0 NONE, 1 PROTAN, 2 DEUTERAN, 3 TRITAN)
                                           // .z = colorBlindIntensity (0..1)
                                           // .w = reserved
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

// Daltonization for red/green/blue-deficient viewers. Ported verbatim from
// stock's colorblind.glsl. Stock #ifdefs on the mode at compile time; we
// branch at runtime so a single shader handles all four modes (NONE +
// protan/deuteran/tritan) and the user can toggle without recompiling.
//
// Reference: "Analysis of Color Blindness" — Fidaner, Lin, Ozguven (2005).
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
        // PROTAN — red deficiency
        lms = LMS * mat3(vec3(0.0, 2.02344, -2.52581),
                         vec3(0.0, 1.0,      0.0),
                         vec3(0.0, 0.0,      1.0));
    } else if (mode == 2) {
        // DEUTERAN — green deficiency
        lms = LMS * mat3(vec3(1.0,      0.0, 0.0),
                         vec3(0.494207, 0.0, 1.24827),
                         vec3(0.0,      0.0, 1.0));
    } else {
        // TRITAN — blue deficiency
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
        vec3 tex = texture(uTextureArray, vec3(vUv, float(vTexLayer))).rgb;
        // Stock textureLightMode blends between two ways of modulating the
        // texture by the engine's per-face shading:
        //   0 → multiply by lightness (low 7 bits / 127)  ← stock default
        //   1 → multiply by the full HSL→RGB vertex color ← "Bright textures"
        // Stock's frag.glsl:81 does the same `mix` on a per-fragment basis.
        float light = float(lum) / 127.0;
        vec3 fullColor = hslToRgb(hue, sat, lum);
        vec3 mulRgb = mix(vec3(light), fullColor, push.fragExtras.x);
        rgb = tex * mulRgb;
    }
    // Brightness gamma — stock applies this to ALL fragment output
    // (vert.glsl per-vertex). We do it per-pixel here so untextured terrain,
    // walls, and crystals respond to the brightness slider too. Previously
    // it lived inside the textured branch only, leaving HSL geometry stuck
    // at full brightness regardless of the slider.
    rgb = pow(rgb, vec3(push.fogFrag.a));
    rgb = mix(rgb, push.fogFrag.rgb, vFogAmount);
    // Stock applies the colour-blind correction AFTER fog (`frag.glsl:91-93`)
    // so the fog tint goes through Daltonization too. Mirror that ordering.
    rgb = applyColorBlind(rgb, int(push.fragExtras.y), push.fragExtras.z);
    // Output alpha = 1 - faceTransparency/255. Pipeline has
    // alphaToCoverageEnable, so this drives MSAA per-sample coverage:
    // - vTrans = 0   → alpha = 1.00 → all samples (opaque)
    // - vTrans = 200 → alpha = 0.22 → ~22% samples (drape, banner)
    // - vTrans = 252 → alpha = 0.012 → ~1% samples (fire glow tip, near-invisible)
    float alpha = 1.0 - float(vTrans) / 255.0;
    outColor = vec4(rgb, alpha);
}
