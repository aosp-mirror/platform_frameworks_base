SHADER_SOURCE(gDrawTextFragmentShader,

precision mediump float;

varying vec2 outTexCoords;

uniform vec4 color;
uniform sampler2D sampler;

void main(void) {
    gl_FragColor = color * texture2D(sampler, outTexCoords).a;
}

);
