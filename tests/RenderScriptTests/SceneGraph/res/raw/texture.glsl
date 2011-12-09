varying vec2 varTex0;

void main() {
   lowp vec4 col = texture2D(UNI_Tex0, varTex0).rgba;
   gl_FragColor = col;
}

