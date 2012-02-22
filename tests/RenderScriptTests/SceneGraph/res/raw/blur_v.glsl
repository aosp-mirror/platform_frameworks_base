varying vec2 varTex0;

void main() {
   vec2 blurCoord = varTex0;
   blurCoord.y = varTex0.y + UNI_blurOffset0;
   vec3 col = texture2D(UNI_color, blurCoord).rgb;
   blurCoord.y = varTex0.y + UNI_blurOffset1;
   col += texture2D(UNI_color, blurCoord).rgb;
   blurCoord.y = varTex0.y + UNI_blurOffset2;
   col += texture2D(UNI_color, blurCoord).rgb;
   blurCoord.y = varTex0.y + UNI_blurOffset3;
   col += texture2D(UNI_color, blurCoord).rgb;

   col = col * 0.25;

   gl_FragColor = vec4(col, 0.0);
}
