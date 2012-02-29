/*
    rs_matrix4x4 model;
    rs_matrix4x4 viewProj;
*/

varying vec3 varWorldPos;
varying vec3 varWorldNormal;
varying vec2 varTex0;

// This is where actual shader code begins
void main() {
   vec4 objPos = ATTRIB_position;
   vec4 worldPos = UNI_model * objPos;
   gl_Position = UNI_viewProj * worldPos;

   mat3 model3 = mat3(UNI_model[0].xyz, UNI_model[1].xyz, UNI_model[2].xyz);
   vec3 worldNorm = model3 * ATTRIB_normal;

   varWorldPos = worldPos.xyz;
   varWorldNormal = worldNorm;
   varTex0 = ATTRIB_texture0;
}
