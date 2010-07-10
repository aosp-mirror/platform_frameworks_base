SHADER_SOURCE(gDrawTextureVertexShader,

attribute vec4 position;
attribute vec2 texCoords;

uniform mat4 transform;

varying vec2 outTexCoords;

void main(void) {
    outTexCoords = texCoords;
    gl_Position = transform * position;
}

);
