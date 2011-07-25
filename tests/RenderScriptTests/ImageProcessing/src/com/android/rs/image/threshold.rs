#pragma version(1)

#include "ip.rsh"

int height;
int width;
int radius;

uchar4 * InPixel;
uchar4 * OutPixel;
float4 * ScratchPixel1;
float4 * ScratchPixel2;

rs_script vBlurScript;
rs_script hBlurScript;

const int CMD_FINISHED = 1;

// Store our coefficients here
static float gaussian[MAX_RADIUS * 2 + 1];


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
    for (r = -radius; r <= radius; r ++) {
        floatR = (float)r;
        gaussian[r + radius] = coeff1 * pow(e, floatR * floatR * coeff2);
        normalizeFactor += gaussian[r + radius];
    }

    //Now we need to normalize the weights because all our coefficients need to add up to one
    normalizeFactor = 1.0f / normalizeFactor;
    for (r = -radius; r <= radius; r ++) {
        floatR = (float)r;
        gaussian[r + radius] *= normalizeFactor;
    }
}


static void copyInput() {
    rs_allocation ain;
    ain = rsGetAllocation(InPixel);
    uint32_t dimx = rsAllocationGetDimX(ain);
    uint32_t dimy = rsAllocationGetDimY(ain);
    for (uint32_t y = 0; y < dimy; y++) {
        for (uint32_t x = 0; x < dimx; x++) {
            ScratchPixel1[x + y * dimx] = convert_float4(InPixel[x + y * dimx]);
        }
    }
}

void filter() {
    copyInput();
    computeGaussianWeights();

    FilterStruct fs;
    fs.gaussian = gaussian;
    fs.width = width;
    fs.height = height;
    fs.radius = radius;

    fs.ain = rsGetAllocation(ScratchPixel1);
    rsForEach(hBlurScript, fs.ain, rsGetAllocation(ScratchPixel2), &fs, sizeof(fs));

    fs.ain = rsGetAllocation(ScratchPixel2);
    rsForEach(vBlurScript, fs.ain, rsGetAllocation(OutPixel), &fs, sizeof(fs));
    rsSendToClientBlocking(CMD_FINISHED);
}

