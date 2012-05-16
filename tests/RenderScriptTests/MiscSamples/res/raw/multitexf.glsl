varying vec2 varTex0;

void main() {
   vec2 t0 = varTex0.xy;
   lowp vec4 col0 = texture2D(UNI_Tex0, t0).rgba;
   lowp vec4 col1 = texture2D(UNI_Tex1, t0*4.0).rgba;
   lowp vec4 col2 = texture2D(UNI_Tex2, t0).rgba;
   col0.xyz = col0.xyz*col1.xyz*1.5;
   col0.xyz = mix(col0.xyz, col2.xyz, col2.w);
   col0.w = 0.5;
   gl_FragColor = col0;
}

