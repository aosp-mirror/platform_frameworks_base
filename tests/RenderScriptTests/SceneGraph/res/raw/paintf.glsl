varying vec3 varWorldPos;
varying vec3 varWorldNormal;
varying vec2 varTex0;

void main() {

   vec3 V = normalize(UNI_cameraPos.xyz - varWorldPos.xyz);
   vec3 worldNorm = normalize(varWorldNormal);

   vec3 light0Vec = V;
   vec3 light0R = reflect(light0Vec, worldNorm);
   float light0_Diffuse = clamp(dot(worldNorm, light0Vec), 0.01, 0.99);
   float light0Spec = clamp(dot(-light0R, V), 0.001, 1.0);
   float light0_Specular = pow(light0Spec, 150.0) * 0.5;

   vec2 t0 = varTex0.xy;
   lowp vec4 col = texture2D(UNI_diffuse, t0).rgba;
   col.xyz = col.xyz * light0_Diffuse * 1.1;
   col.xyz += light0_Specular * vec3(0.8, 0.8, 1.0);

   float fresnel = mix(pow(1.0 - light0_Diffuse, 15.0), 1.0, 0.1);
   col.xyz = mix(col.xyz, textureCube(UNI_reflection, -light0R).rgb * 2.4, fresnel);
   col.w = 0.8;
   gl_FragColor = col;
}

