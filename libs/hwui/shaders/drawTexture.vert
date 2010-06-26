SHADER_SOURCE(gDrawTextureVertexShader,

attribute vec4 position;
attribute vec2 texCoords;
attribute vec4 color;

uniform mat4 projection;
uniform mat4 modelView;
uniform mat4 transform;

varying vec4 outColor;
varying vec2 outTexCoords;

void main(void) {
    outColor = color;
    outTexCoords = texCoords;
    gl_Position = projection * transform * modelView * position;
}

);
