#pragma version(1)

#include "../../../../scriptc/rs_types.rsh"
#include "../../../../scriptc/rs_math.rsh"

#include "ip.rsh"

int height;
int width;
int radius;

uchar4 * InPixel;
uchar4 * OutPixel;
uchar4 * ScratchPixel;

float inBlack;
float outBlack;
float inWhite;
float outWhite;
float gamma;

float saturation;

static float inWMinInB;
static float outWMinOutB;
static float overInWMinInB;
static FilterStruct filterStruct;

#pragma rs export_var(height, width, radius, InPixel, OutPixel, ScratchPixel, inBlack, outBlack, inWhite, outWhite, gamma, saturation, InPixel, OutPixel, ScratchPixel, vBlurScript, hBlurScript)
#pragma rs export_func(filter, filterBenchmark);

rs_script vBlurScript;
rs_script hBlurScript;


// Store our coefficients here
static float gaussian[MAX_RADIUS * 2 + 1];
static rs_matrix3x3 colorMat;

static void computeColorMatrix() {
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

    inWMinInB = inWhite - inBlack;
    outWMinOutB = outWhite - outBlack;
    overInWMinInB = 1.f / inWMinInB;
}

static void computeGaussianWeights() {
    // Compute gaussian weights for the blur
    // e is the euler's number
    float e = 2.718281828459045f;
    float pi = 3.1415926535897932f;
    // g(x) = ( 1 / sqrt( 2 * pi ) * sigma) * e ^ ( -x^2 / 2 * sigma^2 )
    // x is of the form [-radius .. 0 .. radius]
    // and sigma varies with radius.
    // Based on some experimental radius values and sigma's
    // we approximately fit sigma = f(radius) as
    // sigma = radius * 0.4  + 0.6
    // The larger the radius gets, the more our gaussian blur
    // will resemble a box blur since with large sigma
    // the gaussian curve begins to lose its shape
    float sigma = 0.4f * (float)radius + 0.6f;

    // Now compute the coefficints
    // We will store some redundant values to save some math during
    // the blur calculations
    // precompute some values
    float coeff1 = 1.0f / (sqrt( 2.0f * pi ) * sigma);
    float coeff2 = - 1.0f / (2.0f * sigma * sigma);

    float normalizeFactor = 0.0f;
    float floatR = 0.0f;
    int r;
    for(r = -radius; r <= radius; r ++) {
        floatR = (float)r;
        gaussian[r + radius] = coeff1 * pow(e, floatR * floatR * coeff2);
        normalizeFactor += gaussian[r + radius];
    }

    //Now we need to normalize the weights because all our coefficients need to add up to one
    normalizeFactor = 1.0f / normalizeFactor;
    for(r = -radius; r <= radius; r ++) {
        floatR = (float)r;
        gaussian[r + radius] *= normalizeFactor;
    }
}

// This needs to be inline
static float4 levelsSaturation(float4 currentPixel) {
    float3 temp = rsMatrixMultiply(&colorMat, currentPixel.xyz);
    temp = (clamp(temp, 0.1f, 255.f) - inBlack) * overInWMinInB;
    temp = pow(temp, (float3)gamma);
    currentPixel.xyz = clamp(temp * outWMinOutB + outBlack, 0.1f, 255.f);
    return currentPixel;
}

static void processNoBlur() {
    int w, h, r;
    int count = 0;

    float inWMinInB = inWhite - inBlack;
    float outWMinOutB = outWhite - outBlack;
    float4 currentPixel = 0;

    for(h = 0; h < height; h ++) {
        for(w = 0; w < width; w ++) {
            uchar4 *input = InPixel + h*width + w;

            //currentPixel.xyz = convert_float3(input.xyz);
            currentPixel.x = (float)(input->x);
            currentPixel.y = (float)(input->y);
            currentPixel.z = (float)(input->z);

            currentPixel = levelsSaturation(currentPixel);

            uchar4 *output = OutPixel + h*width + w;
            //output.xyz = convert_uchar3(currentPixel.xyz);
            output->x = (uint8_t)currentPixel.x;
            output->y = (uint8_t)currentPixel.y;
            output->z = (uint8_t)currentPixel.z;
            output->w = input->w;
        }
    }
    rsSendToClient(&count, 1, 4, 0);
}

static void horizontalBlurLevels() {
    float4 blurredPixel = 0;
    float4 currentPixel = 0;
    // Horizontal blur
    int w, h, r;
    for(h = 0; h < height; h ++) {
        uchar4 *output = OutPixel + h*width;

        for(w = 0; w < width; w ++) {
            blurredPixel = 0;

            for(r = -radius; r <= radius; r ++) {
                // Stepping left and right away from the pixel
                int validW = w + r;
                // Clamp to zero and width max() isn't exposed for ints yet
                if(validW < 0) {
                    validW = 0;
                }
                if(validW > width - 1) {
                    validW = width - 1;
                }
                //int validW = rsClamp(w + r, 0, width - 1);

                uchar4 *input = InPixel + h*width + validW;

                float weight = gaussian[r + radius];
                currentPixel.x = (float)(input->x);
                currentPixel.y = (float)(input->y);
                currentPixel.z = (float)(input->z);
                //currentPixel.w = (float)(input->a);

                blurredPixel.xyz += currentPixel.xyz * weight;
            }

            blurredPixel = levelsSaturation(blurredPixel);

            output->x = (uint8_t)blurredPixel.x;
            output->y = (uint8_t)blurredPixel.y;
            output->z = (uint8_t)blurredPixel.z;
            //output->a = (uint8_t)blurredPixel.w;
            output++;
        }
    }
}

static void initStructs() {
    filterStruct.gaussian = gaussian;
    filterStruct.width = width;
    filterStruct.height = height;
    filterStruct.radius = radius;
}

void filter() {
    RS_DEBUG(height);
    RS_DEBUG(width);
    RS_DEBUG(radius);

    initStructs();

    computeColorMatrix();

    if(radius == 0) {
        processNoBlur();
        return;
    }

    computeGaussianWeights();

    horizontalBlurLevels();

    rsForEach(vBlurScript,
              rsGetAllocation(InPixel),
              rsGetAllocation(OutPixel),
              &filterStruct);

    int count = 0;
    rsSendToClient(&count, 1, 4, 0);
}

void filterBenchmark() {
    initStructs();

    computeGaussianWeights();

    rsForEach(hBlurScript,
              rsGetAllocation(InPixel),
              rsGetAllocation(OutPixel),
              &filterStruct);

    rsForEach(vBlurScript,
              rsGetAllocation(InPixel),
              rsGetAllocation(OutPixel),
              &filterStruct);

    int count = 0;
    rsSendToClient(&count, 1, 4, 0);
}

