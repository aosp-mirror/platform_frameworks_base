SHADER_SOURCE(gDrawLinearGradientVertexShader,

attribute vec4 position;

uniform mat4 transform;
uniform float gradientLength;
uniform vec2 gradient;
uniform vec2 start;
uniform mat4 screenSpace;

varying float index;

void main(void) {
    vec4 location = screenSpace * position;
    index = dot(location.xy - start, gradient) * gradientLength;

    gl_Position = transform * position;
}

);
