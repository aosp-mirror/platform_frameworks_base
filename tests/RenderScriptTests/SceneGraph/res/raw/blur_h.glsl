varying vec2 varTex0;

void main() {
   vec2 blurCoord = varTex0;
   blurCoord.x = varTex0.x + UNI_blurOffset0;
   vec3 col = texture2D(UNI_Tex0, blurCoord).rgb;
   blurCoord.x = varTex0.x + UNI_blurOffset1;
   col += texture2D(UNI_Tex0, blurCoord).rgb;
   blurCoord.x = varTex0.x + UNI_blurOffset2;
   col += texture2D(UNI_Tex0, blurCoord).rgb;
   blurCoord.x = varTex0.x + UNI_blurOffset3;
   col += texture2D(UNI_Tex0, blurCoord).rgb;

   gl_FragColor = vec4(col * 0.25, 0.0); //texture2D(UNI_Tex0, varTex0);
}
