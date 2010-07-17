SHADER_SOURCE(gDrawLinearGradientVertexShader,

attribute vec4 position;

uniform float gradientLength;
uniform vec2 gradient;
uniform mat4 transform;

varying float index;

void main(void) {
    gl_Position = transform * position;
    index = dot(gl_Position.xy, gradient) * gradientLength;
}

);
