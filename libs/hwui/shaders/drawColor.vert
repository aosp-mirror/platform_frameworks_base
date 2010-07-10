SHADER_SOURCE(gDrawColorVertexShader,

attribute vec4 position;

uniform mat4 transform;

void main(void) {
    gl_Position = transform * position;
}

);
