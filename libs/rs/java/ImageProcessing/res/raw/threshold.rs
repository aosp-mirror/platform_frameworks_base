#pragma version(1)

#include "../../../../scriptc/rs_types.rsh"
#include "../../../../scriptc/rs_math.rsh"
#include "../../../../scriptc/rs_graphics.rsh"

#define MAX_RADIUS 25

int height;
int width;
int radius;

typedef struct c4u_s {
    uint8_t r, g, b, a;
} c4u_t;

c4u_t * InPixel;
c4u_t * OutPixel;
c4u_t * ScratchPixel;

float inBlack;
float outBlack;
float inWhite;
float outWhite;
float gamma;

float saturation;

static float inWMinInB;
static float outWMinOutB;
static float overInWMinInB;
//static float3 gammaV;

#pragma rs export_var(height, width, radius, InPixel, OutPixel, ScratchPixel, inBlack, outBlack, inWhite, outWhite, gamma, saturation)
#pragma rs export_func(filter, filterBenchmark);

// Store our coefficients here
static float gaussian[MAX_RADIUS * 2 + 1];
static float colorMat[4][4];

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

    rsMatrixLoadIdentity((rs_matrix4x4 *)colorMat);

    colorMat[0][0] = oneMinusS * rWeight + saturation;
    colorMat[0][1] = oneMinusS * rWeight;
    colorMat[0][2] = oneMinusS * rWeight;
    colorMat[1][0] = oneMinusS * gWeight;
    colorMat[1][1] = oneMinusS * gWeight + saturation;
    colorMat[1][2] = oneMinusS * gWeight;
    colorMat[2][0] = oneMinusS * bWeight;
    colorMat[2][1] = oneMinusS * bWeight;
    colorMat[2][2] = oneMinusS * bWeight + saturation;

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
#if 0
    // Color matrix multiply
    float tempX = colorMat[0][0] * currentPixel.x + colorMat[1][0] * currentPixel.y + colorMat[2][0] * currentPixel.z;
    float tempY = colorMat[0][1] * currentPixel.x + colorMat[1][1] * currentPixel.y + colorMat[2][1] * currentPixel.z;
    float tempZ = colorMat[0][2] * currentPixel.x + colorMat[1][2] * currentPixel.y + colorMat[2][2] * currentPixel.z;

    currentPixel.x = tempX;
    currentPixel.y = tempY;
    currentPixel.z = tempZ;

    // Clamp to 0..255
    // Inline the code here to avoid funciton calls
    currentPixel.x = currentPixel.x > 255.0f ? 255.0f : currentPixel.x;
    currentPixel.y = currentPixel.y > 255.0f ? 255.0f : currentPixel.y;
    currentPixel.z = currentPixel.z > 255.0f ? 255.0f : currentPixel.z;

    currentPixel.x = currentPixel.x <= 0.0f ? 0.1f : currentPixel.x;
    currentPixel.y = currentPixel.y <= 0.0f ? 0.1f : currentPixel.y;
    currentPixel.z = currentPixel.z <= 0.0f ? 0.1f : currentPixel.z;

    currentPixel.x = pow( (currentPixel.x - inBlack) * overInWMinInB, gamma) * outWMinOutB + outBlack;
    currentPixel.y = pow( (currentPixel.y - inBlack) * overInWMinInB, gamma) * outWMinOutB + outBlack;
    currentPixel.z = pow( (currentPixel.z - inBlack) * overInWMinInB, gamma) * outWMinOutB + outBlack;

    currentPixel.x = currentPixel.x > 255.0f ? 255.0f : currentPixel.x;
    currentPixel.y = currentPixel.y > 255.0f ? 255.0f : currentPixel.y;
    currentPixel.z = currentPixel.z > 255.0f ? 255.0f : currentPixel.z;

    currentPixel.x = currentPixel.x <= 0.0f ? 0.1f : currentPixel.x;
    currentPixel.y = currentPixel.y <= 0.0f ? 0.1f : currentPixel.y;
    currentPixel.z = currentPixel.z <= 0.0f ? 0.1f : currentPixel.z;
#else
    float3 temp;
    // Color matrix multiply
    temp.x = colorMat[0][0] * currentPixel.x + colorMat[1][0] * currentPixel.y + colorMat[2][0] * currentPixel.z;
    temp.y = colorMat[0][1] * currentPixel.x + colorMat[1][1] * currentPixel.y + colorMat[2][1] * currentPixel.z;
    temp.z = colorMat[0][2] * currentPixel.x + colorMat[1][2] * currentPixel.y + colorMat[2][2] * currentPixel.z;
    temp = (clamp(temp, 0.1f, 255.f) - inBlack) * overInWMinInB;
    temp = pow(temp, (float3)gamma);
    currentPixel.xyz = clamp(temp * outWMinOutB + outBlack, 0.1f, 255.f);
#endif

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
            c4u_t *input = InPixel + h*width + w;

            currentPixel.x = (float)(input->r);
            currentPixel.y = (float)(input->g);
            currentPixel.z = (float)(input->b);

            currentPixel = levelsSaturation(currentPixel);

            c4u_t *output = OutPixel + h*width + w;
            output->r = (uint8_t)currentPixel.x;
            output->g = (uint8_t)currentPixel.y;
            output->b = (uint8_t)currentPixel.z;
            output->a = input->a;
        }
    }
    rsSendToClient(&count, 1, 4, 0);
}

static void horizontalBlur() {
    float4 blurredPixel = 0;
    float4 currentPixel = 0;
    // Horizontal blur
    int w, h, r;
    for(h = 0; h < height; h ++) {
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

                c4u_t *input = InPixel + h*width + validW;

                float weight = gaussian[r + radius];
                currentPixel.x = (float)(input->r);
                currentPixel.y = (float)(input->g);
                currentPixel.z = (float)(input->b);
                //currentPixel.w = (float)(input->a);

                blurredPixel += currentPixel*weight;
            }

            c4u_t *output = ScratchPixel + h*width + w;
            output->r = (uint8_t)blurredPixel.x;
            output->g = (uint8_t)blurredPixel.y;
            output->b = (uint8_t)blurredPixel.z;
            //output->a = (uint8_t)blurredPixel.w;
        }
    }
}

static void horizontalBlurLevels() {
    float4 blurredPixel = 0;
    float4 currentPixel = 0;
    // Horizontal blur
    int w, h, r;
    for(h = 0; h < height; h ++) {
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

                c4u_t *input = InPixel + h*width + validW;

                float weight = gaussian[r + radius];
                currentPixel.x = (float)(input->r);
                currentPixel.y = (float)(input->g);
                currentPixel.z = (float)(input->b);
                //currentPixel.w = (float)(input->a);

                blurredPixel += currentPixel*weight;
            }

            blurredPixel = levelsSaturation(blurredPixel);

            c4u_t *output = ScratchPixel + h*width + w;
            output->r = (uint8_t)blurredPixel.x;
            output->g = (uint8_t)blurredPixel.y;
            output->b = (uint8_t)blurredPixel.z;
            //output->a = (uint8_t)blurredPixel.w;
        }
    }
}

static void verticalBlur() {
    float4 blurredPixel = 0;
    float4 currentPixel = 0;
    // Vertical blur
    int w, h, r;
    for(h = 0; h < height; h ++) {
        for(w = 0; w < width; w ++) {

            blurredPixel = 0;
            for(r = -radius; r <= radius; r ++) {
                int validH = h + r;
                // Clamp to zero and width
                if(validH < 0) {
                    validH = 0;
                }
                if(validH > height - 1) {
                    validH = height - 1;
                }

                c4u_t *input = ScratchPixel + validH*width + w;

                float weight = gaussian[r + radius];

                currentPixel.x = (float)(input->r);
                currentPixel.y = (float)(input->g);
                currentPixel.z = (float)(input->b);
                //currentPixel.w = (float)(input->a);

                blurredPixel += currentPixel*weight;
            }

            c4u_t *output = OutPixel + h*width + w;

            output->r = (uint8_t)blurredPixel.x;
            output->g = (uint8_t)blurredPixel.y;
            output->b = (uint8_t)blurredPixel.z;
            //output->a = (uint8_t)blurredPixel.w;
        }
    }
}

void filter() {
    RS_DEBUG(height);
    RS_DEBUG(width);
    RS_DEBUG(radius);
    RS_DEBUG(inBlack);
    RS_DEBUG(outBlack);
    RS_DEBUG(inWhite);
    RS_DEBUG(outWhite);
    RS_DEBUG(gamma);
    RS_DEBUG(saturation);

    computeColorMatrix();

    if(radius == 0) {
        processNoBlur();
        return;
    }

    computeGaussianWeights();

    horizontalBlurLevels();
    verticalBlur();

    int count = 0;
    rsSendToClient(&count, 1, 4, 0);
}

void filterBenchmark() {

    computeGaussianWeights();

    horizontalBlur();
    verticalBlur();

    int count = 0;
    rsSendToClient(&count, 1, 4, 0);
}

