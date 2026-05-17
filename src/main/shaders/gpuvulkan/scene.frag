#version 450

// hslToRgb ported verbatim from RuneLite's hsl_to_rgb.glsl;
// applyColorBlind ported from colorblind.glsl. Both BSD-2-Clause:
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
//
// applyColorBlind implements Daltonization (Fidaner, Lin, Ozguven,
// Stanford PSYCH 221, 2005); branched at runtime so one shader covers
// all modes.

layout(set = 0, binding = 0) uniform sampler2DArray uTextureArray;

layout(push_constant) uniform Push {
    layout(offset = 96)  vec4 fogFrag;     // .rgb = fog/sky color, .a = brightness
    layout(offset = 112) vec4 fragExtras;  // .x = textureLightMode (0 = light-only, 1 = full HSL tint)
                                           // .y = colorBlindMode (0 NONE, 1 PROTAN, 2 DEUTERAN, 3 TRITAN)
                                           // .z = colorBlindIntensity (0..1)
                                           // .w = smoothBanding (0 = per-fragment HSL decode, 1 = per-vertex RGB interp)
} push;

layout(location = 0) in vec3 vColor;       // CPU-decoded per-vertex RGB; smooth-banding term
layout(location = 1) in float vHslPacked;  // packed HSL int as float (0..65535)
layout(location = 2) in vec2 vUv;
layout(location = 3) flat in uint vTexLayer;
layout(location = 4) in float vFogAmount;
// Face transparency (0 = opaque, 255 = engine "invisible" sentinel). The
// pipeline's alphaToCoverageEnable + MSAA turns the output alpha into
// per-sample coverage — approximates alpha blending without a separate
// sorted pass.
layout(location = 5) flat in uint vTrans;

layout(location = 0) out vec4 outColor;

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
    // Engine invisibility sentinel — discard rather than letting
    // alpha-to-coverage write zero samples (cheaper, skips depth write).
    if (vTrans == 255u) discard;
    int hsl = int(vHslPacked);
    int hue = (hsl >> 10) & 0x3F;
    int sat = (hsl >> 7)  & 0x07;
    int lum =  hsl        & 0x7F;

    vec3 rgb;
    if (vTexLayer == 0u) {
        // smoothBanding mixes per-fragment HSL decode (faceted, stock's
        // default-off look) with rasterizer-interpolated per-vertex RGB
        // (smooth gradients, stock's default-on look).
        vec3 perFragment = hslToRgb(hue, sat, lum);
        rgb = mix(perFragment, vColor, push.fragExtras.w);
    } else {
        // Sampler is NEAREST-mag / LINEAR-min with mipmaps + anisotropy.
        // Threshold 0.5 lets partial-alpha mip texels render; level 0
        // stays binary thanks to NEAREST mag.
        vec4 texSample = texture(uTextureArray, vec3(vUv, float(vTexLayer)));
        if (texSample.a < 0.5) discard;
        vec3 tex = texSample.rgb;
        // textureLightMode: 0 = lightness-only tint (stock default),
        // 1 = full HSL→RGB tint (stock's "Bright textures").
        float light = float(lum) / 127.0;
        vec3 fullColor = hslToRgb(hue, sat, lum);
        vec3 mulRgb = mix(vec3(light), fullColor, push.fragExtras.x);
        rgb = tex * mulRgb;
    }
    // Brightness gamma applies to both branches so untextured terrain
    // responds to the brightness slider too.
    rgb = pow(rgb, vec3(push.fogFrag.a));
    rgb = mix(rgb, push.fogFrag.rgb, vFogAmount);
    // Colour-blind correction runs after fog so the fog tint is also
    // Daltonized (matches stock ordering).
    rgb = applyColorBlind(rgb, int(push.fragExtras.y), push.fragExtras.z);
    // alpha = 1 - vTrans/255; drives MSAA coverage via the pipeline's
    // alphaToCoverageEnable.
    float alpha = 1.0 - float(vTrans) / 255.0;
    outColor = vec4(rgb, alpha);
}
