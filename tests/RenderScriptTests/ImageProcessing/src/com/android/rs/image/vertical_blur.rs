#pragma version(1)
#pragma rs_fp_relaxed

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

void root(uchar4 *out, const void *usrData, uint32_t x, uint32_t y) {
    const FilterStruct *fs = (const FilterStruct *)usrData;
    float3 blurredPixel = 0;
    const float *gPtr = fs->gaussian;
    if ((y > fs->radius) && (y < (fs->height - fs->radius))) {
        for (int r = -fs->radius; r <= fs->radius; r ++) {
            const float4 *i = (const float4 *)rsGetElementAt(fs->ain, x, y + r);
            blurredPixel += i->xyz * gPtr[0];
            gPtr++;
        }
    } else {
        for (int r = -fs->radius; r <= fs->radius; r ++) {
            int validH = rsClamp((int)y + r, (int)0, (int)(fs->height - 1));
            const float4 *i = (const float4 *)rsGetElementAt(fs->ain, x, validH);
            blurredPixel += i->xyz * gPtr[0];
            gPtr++;
        }
    }

    float3 temp = rsMatrixMultiply(&colorMat, blurredPixel);
    temp = (clamp(temp, 0.f, 255.f) - inBlack) * overInWMinInB;
    if (gamma.x != 1.0f)
        temp = pow(temp, (float3)gamma);
    temp = clamp(temp * outWMinOutB + outBlack, 0.f, 255.f);

    out->xyz = convert_uchar3(temp);
    //output->w = input->w;
}

