#pragma version(1)

#include "ip.rsh"

void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    float4 *output = (float4 *)v_out;
    const FilterStruct *fs = (const FilterStruct *)usrData;
    const float4 *input = (const float4 *)rsGetElementAt(fs->ain, 0, y);

    float3 blurredPixel = 0;
    const float *gPtr = fs->gaussian;
    if ((x > fs->radius) && (x < (fs->width - fs->radius))) {
        const float4 *i = input + (x - fs->radius);
        for(int r = -fs->radius; r <= fs->radius; r ++) {
            blurredPixel += i->xyz * gPtr[0];
            gPtr++;
            i++;
        }
    } else {
        for(int r = -fs->radius; r <= fs->radius; r ++) {
            // Stepping left and right away from the pixel
            int validW = rsClamp(x + r, (uint)0, (uint)(fs->width - 1));
            blurredPixel += input[validW].xyz * gPtr[0];
            gPtr++;
        }
    }

    output->xyz = blurredPixel;
}

