
varying vec3 worldNormal;

void main() {
   lowp vec4 col = textureCube(UNI_Tex0, worldNormal);
   gl_FragColor = col;
}

