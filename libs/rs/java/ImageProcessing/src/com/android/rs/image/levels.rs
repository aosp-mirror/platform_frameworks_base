#pragma version(1)

#include "rs_types.rsh"
#include "rs_math.rsh"

#include "ip.rsh"


static float inBlack;
static float outBlack;
static float inWhite;
static float outWhite;
static float3 gamma;
static float saturation;

static float inWMinInB;
static float outWMinOutB;
static float overInWMinInB;
static rs_matrix3x3 colorMat;

//#pragma rs export_var(height, width, radius, InPixel, OutPixel, ScratchPixel, inBlack, outBlack, inWhite, outWhite, gamma, saturation, InPixel, OutPixel, ScratchPixel, vBlurScript, hBlurScript)
#pragma rs export_func(setLevels, setSaturation, setGamma);

void setLevels(float iBlk, float oBlk, float iWht, float oWht) {
    inBlack = iBlk;
    outBlack = oBlk;
    inWhite = iWht;
    outWhite = oWht;

    inWMinInB = inWhite - inBlack;
    outWMinOutB = outWhite - outBlack;
    overInWMinInB = 1.f / inWMinInB;
}

void setSaturation(float sat) {
    saturation = sat;

    // Saturation
    // Linear weights
    //float rWeight = 0.3086f;
    //float gWeight = 0.6094f;
    //float bWeight = 0.0820f;

    // Gamma 2.2 weights (we haven't converted our image to linear space yet for perf reasons)
    float rWeight = 0.299f;
    float gWeight = 0.587f;
    float bWeight = 0.114f;

    float oneMinusS = 1.0f - saturation;
    rsMatrixSet(&colorMat, 0, 0, oneMinusS * rWeight + saturation);
    rsMatrixSet(&colorMat, 0, 1, oneMinusS * rWeight);
    rsMatrixSet(&colorMat, 0, 2, oneMinusS * rWeight);
    rsMatrixSet(&colorMat, 1, 0, oneMinusS * gWeight);
    rsMatrixSet(&colorMat, 1, 1, oneMinusS * gWeight + saturation);
    rsMatrixSet(&colorMat, 1, 2, oneMinusS * gWeight);
    rsMatrixSet(&colorMat, 2, 0, oneMinusS * bWeight);
    rsMatrixSet(&colorMat, 2, 1, oneMinusS * bWeight);
    rsMatrixSet(&colorMat, 2, 2, oneMinusS * bWeight + saturation);
}

void setGamma(float g) {
    gamma = (float3)g;
}


void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    const uchar4 *input = v_in;
    uchar4 *output = v_out;

    float4 currentPixel = 0;

    //currentPixel.xyz = convert_float3(input.xyz);
    currentPixel.x = (float)(input->x);
    currentPixel.y = (float)(input->y);
    currentPixel.z = (float)(input->z);

    float3 temp = rsMatrixMultiply(&colorMat, currentPixel.xyz);
    temp = (clamp(temp, 0.f, 255.f) - inBlack) * overInWMinInB;
    temp = pow(temp, (float3)gamma);
    currentPixel.xyz = clamp(temp * outWMinOutB + outBlack, 0.f, 255.f);

    //output.xyz = convert_uchar3(currentPixel.xyz);
    output->x = (uint8_t)currentPixel.x;
    output->y = (uint8_t)currentPixel.y;
    output->z = (uint8_t)currentPixel.z;
    output->w = input->w;
}

