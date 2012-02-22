varying vec2 varTex0;

void main() {
   vec3 col = texture2D(UNI_color, varTex0).rgb;

   vec3 desat = vec3(0.299, 0.587, 0.114);
   float lum = dot(desat, col);
   float stepVal = step(lum, 0.8);
   col = mix(col, vec3(0.0), stepVal)*0.5;

   gl_FragColor = vec4(col, 0.0);
}

