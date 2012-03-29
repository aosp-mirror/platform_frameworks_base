#pragma version(1)
#pragma rs_fp_relaxed

#include "ip.rsh"

void root(float4 *out, const void *usrData, uint32_t x, uint32_t y) {
    const FilterStruct *fs = (const FilterStruct *)usrData;
    float3 blurredPixel = 0;
    const float *gPtr = fs->gaussian;
    if ((x > fs->radius) && (x < (fs->width - fs->radius))) {
        for (int r = -fs->radius; r <= fs->radius; r ++) {
            const float4 *i = (const float4 *)rsGetElementAt(fs->ain, x + r, y);
            blurredPixel += i->xyz * gPtr[0];
            gPtr++;
        }
    } else {
        for (int r = -fs->radius; r <= fs->radius; r ++) {
            // Stepping left and right away from the pixel
            int validX = rsClamp((int)x + r, (int)0, (int)(fs->width - 1));
            const float4 *i = (const float4 *)rsGetElementAt(fs->ain, validX, y);
            blurredPixel += i->xyz * gPtr[0];
            gPtr++;
        }
    }

    out->xyz = blurredPixel;
}

