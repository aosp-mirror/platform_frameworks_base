#pragma version(1)

#include "ip.rsh"

int height;
int width;
int radius;

uchar4 * InPixel;
uchar4 * OutPixel;
uchar4 * ScratchPixel;

#pragma rs export_var(height, width, radius, InPixel, OutPixel, ScratchPixel, vBlurScript, hBlurScript, levelsScript)
#pragma rs export_func(filter, filterBenchmark);

rs_script vBlurScript;
rs_script hBlurScript;
rs_script levelsScript;


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


static void blur() {
    computeGaussianWeights();

    FilterStruct fs;
    fs.gaussian = gaussian;
    fs.width = width;
    fs.height = height;
    fs.radius = radius;

    fs.ain = rsGetAllocation(InPixel);
    rsForEach(hBlurScript, fs.ain, rsGetAllocation(ScratchPixel), &fs);

    fs.ain = rsGetAllocation(ScratchPixel);
    rsForEach(vBlurScript, fs.ain, rsGetAllocation(OutPixel), &fs);
}

void filter() {
    //RS_DEBUG(radius);

    if(radius > 0) {
        blur();
        rsForEach(levelsScript, rsGetAllocation(OutPixel), rsGetAllocation(OutPixel), 0);
    } else {
        rsForEach(levelsScript, rsGetAllocation(InPixel), rsGetAllocation(OutPixel), 0);
    }

    int count = 0;
    rsSendToClient(&count, 1, 4, 0);
}

void filterBenchmark() {
    blur();

    int count = 0;
    rsSendToClient(&count, 1, 4, 0);
}

