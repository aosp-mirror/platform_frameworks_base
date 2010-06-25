SHADER_SOURCE(gDrawColorFragmentShader,

precision mediump float;

varying vec4 outColor;

void main(void) {
    gl_FragColor = outColor;
}

);
