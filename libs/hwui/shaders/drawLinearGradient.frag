SHADER_SOURCE(gDrawLinearGradientFragmentShader,

precision mediump float;

varying float index;

uniform vec4 color;
uniform sampler2D sampler;

void main(void) {
    gl_FragColor = texture2D(sampler, vec2(index, 0.5)) * color;
}

);
