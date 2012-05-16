varying vec3 worldNormal;

// This is where actual shader code begins
void main() {
   vec4 worldPos = UNI_model * ATTRIB_position;
   gl_Position = UNI_proj * worldPos;

   mat3 model3 = mat3(UNI_model[0].xyz, UNI_model[1].xyz, UNI_model[2].xyz);
   worldNormal = model3 * ATTRIB_normal;
}
