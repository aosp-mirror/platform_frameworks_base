SHADER_SOURCE(gDrawTextureFragmentShader,

varying lowp vec4 outColor;
varying mediump vec2 outTexCoords;

uniform sampler2D sampler;

void main(void) {
    gl_FragColor = texture2D(sampler, outTexCoords) * outColor;
}

);
