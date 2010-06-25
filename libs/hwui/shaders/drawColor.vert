SHADER_SOURCE(gDrawColorVertexShader,

attribute vec4 position;
attribute vec4 color;

uniform mat4 projection;
uniform mat4 modelView;

varying vec4 outColor;

void main(void) {
	outColor = color;
	gl_Position = projection * modelView * position;
}

);
