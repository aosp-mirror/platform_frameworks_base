varying vec3 varWorldPos;
varying vec3 varWorldNormal;
varying vec2 varTex0;

void main() {

   vec3 V = normalize(UNI_cameraPos.xyz - varWorldPos.xyz);
   vec3 worldNorm = normalize(varWorldNormal);

   vec3 light0Vec = normalize(UNI_lightPos_0.xyz - varWorldPos.xyz);
   float light0_Diffuse = clamp(dot(worldNorm, light0Vec), 0.0, 1.0);

   vec3 light1Vec = normalize(UNI_lightPos_1.xyz - varWorldPos.xyz);
   float light1_Diffuse = clamp(dot(worldNorm, light1Vec), 0.0, 1.0);

   vec2 t0 = varTex0.xy;
   lowp vec4 col = UNI_diffuse;
   col.xyz = col.xyz * (light0_Diffuse * UNI_lightColor_0.xyz +
                        light1_Diffuse * UNI_lightColor_1.xyz);
   gl_FragColor = col;
}

