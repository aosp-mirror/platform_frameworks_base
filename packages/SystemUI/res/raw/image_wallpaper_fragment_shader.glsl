precision mediump float;

// The actual wallpaper texture.
uniform sampler2D uTexture;

// The 85th percenile for the luminance histogram of the image (a value between 0 and 1).
// This value represents the point in histogram that includes 85% of the pixels of the image.
uniform float uPer85;

// Reveal is the animation value that goes from 1 (the image is hidden) to 0 (the image is visible).
uniform float uReveal;

// The opacity of locked screen (constant value).
uniform float uAod2Opacity;
varying vec2 vTextureCoordinates;

/*
 * Calculates the relative luminance of the pixel.
 */
vec3 luminosity(vec3 color) {
    float lum = 0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b;
    return vec3(lum);
}

vec4 transform(vec3 diffuse) {
    // Getting the luminance for this pixel
    vec3 lum = luminosity(diffuse);

    /*
     * while the reveal > per85, it shows the luminance image (B&W image)
     * then when moving passed that value, the image gets colored.
     */
    float trans = smoothstep(0., uPer85, uReveal);
    diffuse = mix(diffuse, lum, trans);

    // 'lower' value represents the capped 'reveal' value to the range [0, per85]
    float selector = step(uPer85, uReveal);
    float lower = mix(uReveal, uPer85, selector);

    /*
     * Remaps image:
     * - from reveal=1 to reveal=per85 => lower=per85, diffuse=luminance
     *   That means that remaps black and white image pixel
     *   from a possible values of [0,1] to [per85, 1] (if the pixel is darker than per85,
     *   it's gonna be black, if it's between per85 and 1, it's gonna be gray
     *   and if it's 1 it's gonna be white).
     * - from reveal=per85 to reveal=0 => lower=reveal, 'diffuse' changes from luminance to color
     *   That means that remaps each image pixel color (rgb)
     *   from a possible values of [0,1] to [lower, 1] (if the pixel color is darker than 'lower',
     *   it's gonna be 0, if it's between 'lower' and 1, it's gonna be remap to a value
     *   between 0 and 1 and if it's 1 it's gonna be 1).
     * - if reveal=0 => lower=0, diffuse=color image
     *   The image is shown as it is, colored.
     */
    vec3 remaps = smoothstep(lower, 1., diffuse);

    // Interpolate between diffuse and remaps using reveal to avoid over saturation.
    diffuse = mix(diffuse, remaps, uReveal);

    /*
     * Fades in the pixel value:
     * - if reveal=1 => fadeInOpacity=0
     * - from reveal=1 to reveal=per85 => 0<=fadeInOpacity<=1
     * - if reveal>per85 => fadeInOpacity=1
     */
    float fadeInOpacity = 1. - smoothstep(uPer85, 1., uReveal);
    diffuse *= uAod2Opacity * fadeInOpacity;

    return vec4(diffuse.r, diffuse.g, diffuse.b, 1.);
}

void main() {
    // gets the pixel value of the wallpaper for this uv coordinates on screen.
    vec4 fragColor = texture2D(uTexture, vTextureCoordinates);
    gl_FragColor = transform(fragColor.rgb);
}