varying vec3 varWorldPos;
varying vec3 varWorldNormal;
varying vec2 varTex0;

void main() {

   vec3 V = normalize(-varWorldPos.xyz);
   vec3 worldNorm = normalize(varWorldNormal);

   vec3 light0Vec = normalize(UNI_light0_Posision.xyz - varWorldPos);
   vec3 light0R = -reflect(light0Vec, worldNorm);
   float light0_Diffuse = clamp(dot(worldNorm, light0Vec), 0.0, 1.0) * UNI_light0_Diffuse;
   float light0Spec = clamp(dot(light0R, V), 0.001, 1.0);
   float light0_Specular = pow(light0Spec, UNI_light0_CosinePower) * UNI_light0_Specular;

   vec3 light1Vec = normalize(UNI_light1_Posision.xyz - varWorldPos);
   vec3 light1R = reflect(light1Vec, worldNorm);
   float light1_Diffuse = clamp(dot(worldNorm, light1Vec), 0.0, 1.0) * UNI_light1_Diffuse;
   float light1Spec = clamp(dot(light1R, V), 0.001, 1.0);
   float light1_Specular = pow(light1Spec, UNI_light1_CosinePower) * UNI_light1_Specular;

   vec2 t0 = varTex0.xy;
   lowp vec4 col = texture2D(UNI_Tex0, t0).rgba;
   col.xyz = col.xyz * (light0_Diffuse * UNI_light0_DiffuseColor.xyz + light1_Diffuse * UNI_light1_DiffuseColor.xyz);
   col.xyz += light0_Specular * UNI_light0_SpecularColor.xyz;
   col.xyz += light1_Specular * UNI_light1_SpecularColor.xyz;
   gl_FragColor = col;
}

