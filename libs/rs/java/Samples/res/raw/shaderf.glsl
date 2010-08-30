
varying lowp float light0_Diffuse;
varying lowp float light0_Specular;
varying lowp float light1_Diffuse;
varying lowp float light1_Specular;

void main() {
   vec2 t0 = varTex0.xy;
   lowp vec4 col = texture2D(uni_Tex0, t0).rgba;
   /*col = col * (light0_Diffuse * UNI_light0_DiffuseColor + light1_Diffuse * UNI_light1_DiffuseColor);
   col += light0_Specular * UNI_light0_SpecularColor;
   col += light1_Specular * UNI_light1_SpecularColor;*/
   col = col * (light0_Diffuse + light1_Diffuse);
   col += light0_Specular;
   col += light1_Specular;
   gl_FragColor = col;
}

