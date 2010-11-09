
varying lowp float light0_Diffuse;
varying lowp float light0_Specular;
varying lowp float light1_Diffuse;
varying lowp float light1_Specular;
varying vec2 varTex0;

void main() {
   vec2 t0 = varTex0.xy;
   lowp vec4 col = texture2D(UNI_Tex0, t0).rgba;
   col.xyz = col.xyz * (light0_Diffuse * UNI_light0_DiffuseColor.xyz + light1_Diffuse * UNI_light1_DiffuseColor.xyz);
   col.xyz += light0_Specular * UNI_light0_SpecularColor.xyz;
   col.xyz += light1_Specular * UNI_light1_SpecularColor.xyz;
   gl_FragColor = col;
}

