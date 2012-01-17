varying vec3 varWorldPos;
varying vec3 varWorldNormal;
varying vec2 varTex0;

void main() {

   vec3 V = normalize(UNI_cameraPos.xyz - varWorldPos.xyz);
   vec3 worldNorm = normalize(varWorldNormal);

   vec3 light0Vec = normalize(UNI_lightPos_0.xyz - varWorldPos.xyz);
   vec3 light0R = reflect(light0Vec, worldNorm);
   float light0_Diffuse = clamp(dot(worldNorm, light0Vec), 0.0, 1.0);
   float light0Spec = clamp(dot(-light0R, V), 0.001, 1.0);
   float light0_Specular = pow(light0Spec, 10.0) * 0.7;

   vec3 light1Vec = normalize(UNI_lightPos_1.xyz - varWorldPos.xyz);
   vec3 light1R = reflect(light1Vec, worldNorm);
   float light1_Diffuse = clamp(dot(worldNorm, light1Vec), 0.0, 1.0);
   float light1Spec = clamp(dot(-light1R, V), 0.001, 1.0);
   float light1_Specular = pow(light1Spec, 10.0) * 0.7;

   vec2 t0 = varTex0.xy;
   lowp vec4 col = UNI_diffuse;
   col.xyz = col.xyz * (light0_Diffuse * UNI_lightColor_0.xyz +
                        light1_Diffuse * UNI_lightColor_1.xyz);
   col.xyz += (light0_Specular + light1_Specular);
   gl_FragColor = col;
}

