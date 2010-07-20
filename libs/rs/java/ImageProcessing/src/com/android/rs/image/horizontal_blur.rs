#pragma version(1)

#include "rs_types.rsh"
#include "rs_math.rsh"

#include "ip.rsh"

void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    uchar4 *output = (uchar4 *)v_out;
    const FilterStruct *fs = (const FilterStruct *)usrData;
    const uchar4 *input = (const uchar4 *)rsGetElementAt(fs->ain, 0, y);

    float4 blurredPixel = 0;
    float4 currentPixel = 0;

    for(int r = -fs->radius; r <= fs->radius; r ++) {
        // Stepping left and right away from the pixel
        int validW = x + r;
        // Clamp to zero and width max() isn't exposed for ints yet
        if(validW < 0) {
            validW = 0;
        }
        if(validW > fs->width - 1) {
            validW = fs->width - 1;
        }
        //int validW = rsClamp(w + r, 0, width - 1);

        float weight = fs->gaussian[r + fs->radius];
        currentPixel.x = (float)(input[validW].x);
        currentPixel.y = (float)(input[validW].y);
        currentPixel.z = (float)(input[validW].z);
        //currentPixel.w = (float)(input->a);

        blurredPixel += currentPixel * weight;
    }

    output->x = (uint8_t)blurredPixel.x;
    output->y = (uint8_t)blurredPixel.y;
    output->z = (uint8_t)blurredPixel.z;
}
