#pragma version(1)

#include "../../../../scriptc/rs_types.rsh"
#include "../../../../scriptc/rs_math.rsh"
#include "../../../../scriptc/rs_graphics.rsh"

int height;
int width;
float threshold;

typedef struct c4u_s {
    char r, g, b, a;
} c4u_t;

//rs_color4u * InPixel;
//rs_color4u * OutPixel;
c4u_t * InPixel;
c4u_t * OutPixel;

#pragma rs export_var(height, width, threshold, InPixel, OutPixel)

void filter() {
    debugP(0, (void *)height);
    debugP(0, (void *)width);
    debugP(0, (void *)((int)threshold));
    debugP(0, (void *)InPixel);
    debugP(0, (void *)OutPixel);

    rs_color4u *in = (rs_color4u *)InPixel;
    rs_color4u *out = (rs_color4u *)OutPixel;
    //const rs_color4u mask = {0,0,0,0xff};

    int count = width * height;
    int tf = threshold * 255 * 255;
    int masks[2] = {0xffffffff, 0xff000000};

    while (count--) {
        int luminance = 54 * in->x +
                        182 * in->y +
                        18 * in->z;
        int idx = ((uint32_t)(luminance - tf)) >> 31;
        *((int *)out) = *((int *)in) & masks[idx];
        in++;
        out++;
    }

    sendToClient(&count, 1, 4, 0);
}

