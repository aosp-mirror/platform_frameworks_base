#pragma version(1)

#include "ip.rsh"

void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    uchar4 *output = (uchar4 *)v_out;
    const FilterStruct *fs = (const FilterStruct *)usrData;
    const uchar4 *input = (const uchar4 *)rsGetElementAt(fs->ain, x, 0);

    float3 blurredPixel = 0;
    const float *gPtr = fs->gaussian;
    if ((y > fs->radius) && (y < (fs->height - fs->radius))) {
        const uchar4 *i = input + ((y - fs->radius) * fs->width);
        for(int r = -fs->radius; r <= fs->radius; r ++) {
            blurredPixel += convert_float3(i->xyz) * gPtr[0];
            gPtr++;
            i += fs->width;
        }
    } else {
        for(int r = -fs->radius; r <= fs->radius; r ++) {
            int validH = rsClamp(y + r, (uint)0, (uint)(fs->height - 1));
            const uchar4 *i = input + validH * fs->width;
            blurredPixel += convert_float3(i->xyz) * gPtr[0];
            gPtr++;
        }
    }
    output->xyz = convert_uchar3(blurredPixel);
}

