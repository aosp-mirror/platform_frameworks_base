#pragma version(1)

#include "../../../../scriptc/rs_types.rsh"
#include "../../../../scriptc/rs_math.rsh"

#include "ip.rsh"

void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    uchar4 *output = (uchar4 *)v_out;
    const FilterStruct *fs = (const FilterStruct *)usrData;
    const uchar4 *input = (const uchar4 *)rsGetElementAt(fs->ain, x, 0);

    float4 blurredPixel = 0;
    float4 currentPixel = 0;
    for(int r = -fs->radius; r <= fs->radius; r ++) {
#if 1
        int validH = y + r;
        // Clamp to zero and width
        if(validH < 0) {
            validH = 0;
        }
        if(validH > fs->height - 1) {
            validH = fs->height - 1;
        }

        const uchar4 *i = input + validH * fs->width;
        //const uchar4 *i = (const uchar4 *)rsGetElementAt(fs->ain, x, validH);

        float weight = fs->gaussian[r + fs->radius];

        currentPixel.x = (float)(i->x);
        currentPixel.y = (float)(i->y);
        currentPixel.z = (float)(i->z);

        blurredPixel.xyz += currentPixel.xyz * weight;
#else
        int validH = rsClamp(y + r, 0, height - 1);
        validH -= y;
        uchar4 *i = input + validH * width + x;
        blurredPixel.xyz += convert_float3(i->xyz) * gaussian[r + fs->radius];
#endif
    }

    //output->xyz = convert_uchar3(blurredPixel.xyz);
    output->x = (uint8_t)blurredPixel.x;
    output->y = (uint8_t)blurredPixel.y;
    output->z = (uint8_t)blurredPixel.z;
}

