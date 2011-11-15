varying vec2 varTex0;

void main() {
   lowp vec3 col0 = texture2D(UNI_Tex0, varTex0).rgb;
   gl_FragColor.xyz = col0;
   gl_FragColor.w = 0.5;
}

