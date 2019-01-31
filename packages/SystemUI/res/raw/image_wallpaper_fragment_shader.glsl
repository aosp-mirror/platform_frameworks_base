precision mediump float;

uniform sampler2D uTexture;
uniform float uCenterReveal;
uniform float uReveal;
uniform float uAod2Opacity;
uniform int uAodMode;
varying vec2 vTextureCoordinates;

vec3 luminosity(vec3 color) {
    float lum = 0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b;
    return vec3(lum);
}

vec4 transform(vec3 diffuse) {
    // TODO: Add well comments here, tracking on b/123615467.
    vec3 lum = luminosity(diffuse);
    diffuse = mix(diffuse, lum, smoothstep(0., uCenterReveal, uReveal));
    float val = mix(uReveal, uCenterReveal, step(uCenterReveal, uReveal));
    diffuse = smoothstep(val, 1.0, diffuse);
    diffuse *= uAod2Opacity * (1. - smoothstep(uCenterReveal, 1., uReveal));
    return vec4(diffuse.r, diffuse.g, diffuse.b, 1.);
}

void main() {
    vec4 fragColor = texture2D(uTexture, vTextureCoordinates);
    // TODO: Remove the branch logic here, tracking on b/123615467.
    if (uAodMode != 0) {
        gl_FragColor = transform(fragColor.rgb);
    } else {
        gl_FragColor = fragColor;
    }
}