SHADER_SOURCE(gDrawColorFragmentShader,

uniform vec4 color;

void main(void) {
    gl_FragColor = color;
}

);
