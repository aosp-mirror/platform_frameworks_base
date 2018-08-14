#extension GL_OES_EGL_image_external : require

precision mediump float;
uniform samplerExternalOES texUnit;
uniform float opacity;
uniform float gamma;
varying vec2 UV;

void main()
{
    vec4 color = texture2D(texUnit, UV);
    vec3 rgb = pow(color.rgb * opacity, vec3(gamma));
    gl_FragColor = vec4(rgb, 1.0);
}
