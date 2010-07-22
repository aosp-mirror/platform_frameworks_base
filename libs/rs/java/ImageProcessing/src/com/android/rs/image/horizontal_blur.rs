#pragma version(1)

#include "ip.rsh"

void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    uchar4 *output = (uchar4 *)v_out;
    const FilterStruct *fs = (const FilterStruct *)usrData;
    const uchar4 *input = (const uchar4 *)rsGetElementAt(fs->ain, 0, y);

    float3 blurredPixel = 0;
    float3 currentPixel = 0;

    const float *gPtr = fs->gaussian;
    if ((x > fs->radius) && (x < (fs->width - fs->radius))) {
        const uchar4 *i = input + (x - fs->radius);
        for(int r = -fs->radius; r <= fs->radius; r ++) {
            currentPixel.x = (float)(i->x);
            currentPixel.y = (float)(i->y);
            currentPixel.z = (float)(i->z);
            blurredPixel += currentPixel * gPtr[0];
            gPtr++;
            i++;
        }
    } else {
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

            currentPixel.x = (float)(input[validW].x);
            currentPixel.y = (float)(input[validW].y);
            currentPixel.z = (float)(input[validW].z);

            blurredPixel += currentPixel * gPtr[0];
            gPtr++;
        }
    }

    output->x = (uint8_t)blurredPixel.x;
    output->y = (uint8_t)blurredPixel.y;
    output->z = (uint8_t)blurredPixel.z;
}

