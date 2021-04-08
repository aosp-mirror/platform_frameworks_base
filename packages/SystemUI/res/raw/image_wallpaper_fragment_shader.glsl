precision mediump float;
#define GAMMA 2.2
#define INV_GAMMA 1.0 / GAMMA

// The actual wallpaper texture.
uniform sampler2D uTexture;
uniform float uExposure;

varying vec2 vTextureCoordinates;

// Following the Rec. ITU-R BT.709.
float relativeLuminance(vec3 color) {
    return 0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b;
}

// Adjusts the exposure of some luminance value.
float relativeExposureCompensation(in float lum, in float ev) {
    return lum * pow(2.0, ev);
}

vec4 srgbToLinear(in vec4 color) {
    vec4 linearColor = vec4(color);
    linearColor.rgb = pow(linearColor.rgb, vec3(GAMMA));
    return linearColor;
}

vec4 linearToSrgb(in vec4 color) {
    vec4 srgbColor = vec4(color);
        srgbColor.rgb = pow(srgbColor.rgb, vec3(INV_GAMMA));
        return srgbColor;
}

/*
 * Normalizes a value inside a range to a normalized range [0,1].
 */
float normalizedRange(in float value, in float inMin, in float inMax) {
    float valueClamped = clamp(value, inMin, inMax);
    return (value - inMin) / (inMax - inMin);
}

void main() {
    // Gets the pixel value of the wallpaper for this uv coordinates on screen.
    vec4 color = srgbToLinear(texture2D(uTexture, vTextureCoordinates));
    float lum = relativeLuminance(color.rgb);

    // Transform it using the S curve created by the smoothstep. This will increase the contrast.
    lum = smoothstep(0., 1., lum) + 0.001;

    lum = relativeExposureCompensation(lum, mix(-5., 10., uExposure));
    lum = mix(clamp(lum, 0.0, 1.0), 1.0, normalizedRange(uExposure, 0.55, 1.0));
    color.rgb *= lum;

    gl_FragColor = linearToSrgb(color);
}