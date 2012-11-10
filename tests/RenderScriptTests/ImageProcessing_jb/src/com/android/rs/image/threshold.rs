#pragma version(1)
#pragma rs java_package_name(com.android.rs.imagejb)
#pragma rs_fp_relaxed


int height;
int width;
static int radius;

rs_allocation InPixel;
rs_allocation ScratchPixel1;
rs_allocation ScratchPixel2;

const int MAX_RADIUS = 25;

// Store our coefficients here
static float gaussian[MAX_RADIUS * 2 + 1];

void setRadius(int rad) {
    radius = rad;
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
    for (int r = -radius; r <= radius; r ++) {
        floatR = (float)r;
        gaussian[r + radius] = coeff1 * pow(e, floatR * floatR * coeff2);
        normalizeFactor += gaussian[r + radius];
    }

    //Now we need to normalize the weights because all our coefficients need to add up to one
    normalizeFactor = 1.0f / normalizeFactor;
    for (int r = -radius; r <= radius; r ++) {
        floatR = (float)r;
        gaussian[r + radius] *= normalizeFactor;
    }
}

void copyIn(const uchar4 *in, float4 *out) {
    *out = convert_float4(*in);
}

static inline float4 GetElementAt_float4(rs_allocation a, uint32_t x, uint32_t y) {
    return ((float4 *)rsGetElementAt(a, x, y))[0];
}

void vert(uchar4 *out, uint32_t x, uint32_t y) {
    float3 blurredPixel = 0;
    int gi = 0;
    if ((y > radius) && (y < (height - radius))) {
        for (int r = -radius; r <= radius; r ++) {
            float4 i = GetElementAt_float4(ScratchPixel2, x, y + r);
            blurredPixel += i.xyz * gaussian[gi++];
        }
    } else {
        for (int r = -radius; r <= radius; r ++) {
            int validH = rsClamp((int)y + r, (int)0, (int)(height - 1));
            float4 i = GetElementAt_float4(ScratchPixel2, x, validH);
            blurredPixel += i.xyz * gaussian[gi++];
        }
    }

    out->xyz = convert_uchar3(clamp(blurredPixel, 0.f, 255.f));
    out->w = 0xff;
}

void horz(float4 *out, uint32_t x, uint32_t y) {
    float4 blurredPixel = 0;
    int gi = 0;
    if ((x > radius) && (x < (width - radius))) {
        for (int r = -radius; r <= radius; r ++) {
            float4 i = GetElementAt_float4(ScratchPixel1, x + r, y);
            blurredPixel += i * gaussian[gi++];
        }
    } else {
        for (int r = -radius; r <= radius; r ++) {
            // Stepping left and right away from the pixel
            int validX = rsClamp((int)x + r, (int)0, (int)(width - 1));
            float4 i = GetElementAt_float4(ScratchPixel1, validX, y);
            blurredPixel += i * gaussian[gi++];
        }
    }

    *out = blurredPixel;
}

