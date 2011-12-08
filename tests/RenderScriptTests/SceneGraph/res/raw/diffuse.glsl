varying vec3 varWorldPos;
varying vec3 varWorldNormal;
varying vec2 varTex0;

void main() {

   vec3 V = normalize(UNI_cameraPos.xyz - varWorldPos.xyz);
   vec3 worldNorm = (varWorldNormal);

   vec3 light0Vec = V;
   vec3 light0R = reflect(light0Vec, worldNorm);
   float light0_Diffuse = dot(worldNorm, light0Vec);

   vec2 t0 = varTex0.xy;
   lowp vec4 col = texture2D(UNI_Tex0, t0).rgba;
   col.xyz = col.xyz * light0_Diffuse * 1.2;
   gl_FragColor = col; //vec4(0.0, 1.0, 0.0, 0.0);
}

