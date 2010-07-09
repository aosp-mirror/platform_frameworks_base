SHADER_SOURCE(gDrawColorVertexShader,

attribute vec4 position;

uniform mat4 projection;
uniform mat4 modelView;
uniform mat4 transform;

void main(void) {
    gl_Position = projection * transform * modelView * position;
}

);
