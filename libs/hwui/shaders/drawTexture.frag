SHADER_SOURCE(gDrawTextureFragmentShader,

varying mediump vec2 outTexCoords;

uniform vec4 color;
uniform sampler2D sampler;

void main(void) {
    gl_FragColor = texture2D(sampler, outTexCoords) * color;
}

);
