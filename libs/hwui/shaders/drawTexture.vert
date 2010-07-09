SHADER_SOURCE(gDrawTextureVertexShader,

attribute vec4 position;
attribute vec2 texCoords;

uniform mat4 projection;
uniform mat4 modelView;
uniform mat4 transform;

varying vec2 outTexCoords;

void main(void) {
    outTexCoords = texCoords;
    gl_Position = projection * transform * modelView * position;
}

);
