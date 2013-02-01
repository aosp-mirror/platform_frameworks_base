
#pragma version(1)
#pragma rs java_package_name(com.android.rs.livepreview)
//#pragma rs_fp_relaxed

static int gWidth;
static int gHeight;
static uchar crossProcess_tableR[256];
static uchar crossProcess_tableG[256];
static uchar crossProcess_tableB[256];
static uchar vignette_table[512];


static float4 crossProcess(float4 color) {
    float4 ncolor = 0.f;
    float v;

    if (color.r < 0.5f) {
        v = color.r;
        ncolor.r = 4.0f * v * v * v;
    } else {
        v = 1.0f - color.r;
        ncolor.r = 1.0f - (4.0f * v * v * v);
    }

    if (color.g < 0.5f) {
        v = color.g;
        ncolor.g = 2.0f * v * v;
    } else {
        v = 1.0f - color.g;
        ncolor.g = 1.0f - (2.0f * v * v);
    }

    ncolor.b = color.b * 0.5f + 0.25f;
    ncolor.a = color.a;
    return ncolor;
}

static uchar4 crossProcess_i(uchar4 color) {
    uchar4 ncolor = color;
    ncolor.r = crossProcess_tableR[color.r];
    ncolor.g = crossProcess_tableG[color.g];
    ncolor.b = crossProcess_tableB[color.b];
    return ncolor;
}


float temp = 0.2f;
static float4 colortemp(float4 color) {
    float4 new_color = color;
    float4 t = color * ((float4)1.0f - color) * temp;

    new_color.r = color.r + t.r;
    new_color.b = color.b - t.b;
    if (temp > 0.0f) {
        color.g = color.g + t.g * 0.25f;
    }
    float max_value = max(new_color.r, max(new_color.g, new_color.b));
    if (max_value > 1.0f) {
        new_color /= max_value;
    }

    return new_color;
}


static float vignette_dist_mod;
int2 vignette_half_dims;
static uchar4 vignette(uchar4 color, uint32_t x, uint32_t y) {
    int2 xy = {x, y};
    xy -= vignette_half_dims;
    xy *= xy;

    float d = vignette_dist_mod * (xy.x + xy.y);
    ushort4 c = convert_ushort4(color);
    c *= vignette_table[(int)d];
    c >>= (ushort4)8;
    return convert_uchar4(c);
}

void root(const uchar4 *in, uchar4 *out, uint32_t x, uint32_t y) {
    uchar4 p;
    p = crossProcess_i(*in);
    p = vignette(p, x, y);

    out->rgba = p;
    out->a = 0xff;
}

float vignetteScale = 0.5f;
float vignetteShade = 0.85f;

static void precompute() {
    for(int i=0; i <256; i++) {
        float4 f = ((float)i) / 255.f;
        float4 res = crossProcess(f);
        res = colortemp(res);
        crossProcess_tableR[i] = (uchar)(res.r * 255.f);
        crossProcess_tableG[i] = (uchar)(res.g * 255.f);
        crossProcess_tableB[i] = (uchar)(res.b * 255.f);
    }

    for(int i=0; i <512; i++) {
        const float slope = 20.0f;
        float f = ((float)i) / 511.f;

        float range = 1.30f - sqrt(vignetteScale) * 0.7f;
        float lumen = vignetteShade / (1.0f + exp((sqrt(f) - range) * slope)) + (1.0f - vignetteShade);
        lumen = clamp(lumen, 0.f, 1.f);

        vignette_table[i] = (uchar)(lumen * 255.f + 0.5f);
    }
}

void init() {
    precompute();
}

void setSize(int w, int h) {
    gWidth = w;
    gHeight = h;
    vignette_half_dims = (int2){w / 2, h / 2};
    vignette_dist_mod = 512.f;
    vignette_dist_mod /= (float)(w*w + h*h) / 4.f;

}
