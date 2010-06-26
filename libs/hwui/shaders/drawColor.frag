SHADER_SOURCE(gDrawColorFragmentShader,

varying lowp vec4 outColor;

void main(void) {
    gl_FragColor = outColor;
}

);
