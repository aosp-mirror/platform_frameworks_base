varying float light0_Diffuse;
varying float light0_Specular;
varying float light1_Diffuse;
varying float light1_Specular;
varying vec2 varTex0;

// This is where actual shader code begins
void main() {
   vec4 worldPos = UNI_model[0] * ATTRIB_position;
   worldPos = UNI_model[1] * worldPos;
   gl_Position = UNI_proj * worldPos;

   mat4 model0 = UNI_model[0];
   mat3 model3 = mat3(model0[0].xyz, model0[1].xyz, model0[2].xyz);
   vec3 worldNorm = model3 * ATTRIB_normal;
   vec3 V = normalize(-worldPos.xyz);

   vec3 light0Vec = normalize(UNI_light_Posision[0].xyz - worldPos.xyz);
   vec3 light0R = -reflect(light0Vec, worldNorm);
   light0_Diffuse = clamp(dot(worldNorm, light0Vec), 0.0, 1.0) * UNI_light_Diffuse[0];
   float light0Spec = clamp(dot(light0R, V), 0.001, 1.0);
   light0_Specular = pow(light0Spec, UNI_light_CosinePower[0]) * UNI_light_Specular[0];

   vec3 light1Vec = normalize(UNI_light_Posision[1].xyz - worldPos.xyz);
   vec3 light1R = reflect(light1Vec, worldNorm);
   light1_Diffuse = clamp(dot(worldNorm, light1Vec), 0.0, 1.0) * UNI_light_Diffuse[1];
   float light1Spec = clamp(dot(light1R, V), 0.001, 1.0);
   light1_Specular = pow(light1Spec, UNI_light_CosinePower[1]) * UNI_light_Specular[1];

   gl_PointSize = 1.0;
   varTex0 = ATTRIB_texture0;
}
