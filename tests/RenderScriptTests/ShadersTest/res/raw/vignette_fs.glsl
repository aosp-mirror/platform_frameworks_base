#define CRT_MASK

varying vec2 varTex0;

void main() {
    lowp vec4 color = texture2D(UNI_Tex0, varTex0);
    
    vec2 powers = pow(abs((gl_FragCoord.xy / vec2(UNI_width, UNI_height)) - 0.5), vec2(2.0));
    float gradient = smoothstep(UNI_size - UNI_feather, UNI_size + UNI_feather,
            powers.x + powers.y);

    color = vec4(mix(color.rgb, vec3(0.0), gradient), 1.0);

#ifdef CRT_MASK
    float vShift = gl_FragCoord.y;
    if (mod(gl_FragCoord.x, 6.0) >= 3.0) {
        vShift += 2.0;
    }

    lowp vec3 r = vec3(0.95, 0.0, 0.2);
    lowp vec3 g = vec3(0.2, 0.95, 0.0);
    lowp vec3 b = vec3(0.0, 0.2, 0.95);
    int channel = int(floor(mod(gl_FragCoord.x, 3.0)));
    lowp vec4 crt = vec4(r[channel], g[channel], b[channel], 1.0);
    crt *= clamp(floor(mod(vShift, 4.0)), 0.0, 1.0);
    
    color = (crt * color * 1.25) + 0.05;
#endif

    gl_FragColor = color;
}
