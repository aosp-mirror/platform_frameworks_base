#pragma version(1)

#include "../../../../scriptc/rs_types.rsh"
#include "../../../../scriptc/rs_math.rsh"

#include "ip.rsh"

uchar4 * ScratchPixel;

#pragma rs export_var(ScratchPixel)

void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    uchar4 *output = (uchar4 *)v_out;
    const uchar4 *input = (uchar4 *)v_in;
    const FilterStruct *fs = (const FilterStruct *)usrData;

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

        uchar4 *input = ScratchPixel + validH * fs->width + x;

        float weight = fs->gaussian[r + fs->radius];

        currentPixel.x = (float)(input->x);
        currentPixel.y = (float)(input->y);
        currentPixel.z = (float)(input->z);

        blurredPixel.xyz += currentPixel.xyz * weight;
#else
        int validH = rsClamp(y + r, 0, height - 1);
        uchar4 *input = ScratchPixel + validH * width + x;
        blurredPixel.xyz += convert_float3(input->xyz) * gaussian[r + fs->radius];
#endif
    }

    //output->xyz = convert_uchar3(blurredPixel.xyz);
    output->x = (uint8_t)blurredPixel.x;
    output->y = (uint8_t)blurredPixel.y;
    output->z = (uint8_t)blurredPixel.z;
}

