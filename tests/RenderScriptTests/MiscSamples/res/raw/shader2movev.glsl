varying vec3 varWorldPos;
varying vec3 varWorldNormal;
varying vec2 varTex0;

// This is where actual shader code begins
void main() {
   vec4 objPos = ATTRIB_position;
   vec3 oldPos = objPos.xyz;
   objPos.xyz += 0.1*sin(objPos.xyz*2.0 + UNI_time);
   objPos.xyz += 0.05*sin(objPos.xyz*4.0 + UNI_time*0.5);
   objPos.xyz += 0.02*sin(objPos.xyz*7.0 + UNI_time*0.75);
   vec4 worldPos = UNI_model * objPos;
   gl_Position = UNI_proj * worldPos;

   mat3 model3 = mat3(UNI_model[0].xyz, UNI_model[1].xyz, UNI_model[2].xyz);
   vec3 worldNorm = model3 * (ATTRIB_normal + oldPos - objPos.xyz);

   varWorldPos = worldPos.xyz;
   varWorldNormal = worldNorm;
   varTex0 = ATTRIB_texture0;
}
