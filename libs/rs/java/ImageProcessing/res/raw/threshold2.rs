#pragma version(1)

#include "../../../../scriptc/rs_types.rsh"
#include "../../../../scriptc/rs_math.rsh"
#include "../../../../scriptc/rs_graphics.rsh"

typedef struct Params_s{
    int inHeight;
    int inWidth;
    int outHeight;
    int outWidth;
    float threshold;
} Params_t;

Params_t * Params;
rs_color4u * InPixel;
rs_color4u * OutPixel;


int main() {
    int t = uptimeMillis();

    rs_color4u *in = InPixel;
    rs_color4u *out = OutPixel;

    int count = Params->inWidth * Params->inHeight;
    int i;
    float threshold = Params->threshold * 255.f;

    for (i = 0; i < count; i++) {
        float luminance = 0.2125f * in->x +
                          0.7154f * in->y +
                          0.0721f * in->z;
        if (luminance > threshold) {
            *out = *in;
        } else {
            *((int *)out) = *((int *)in) & 0xff000000;
        }

        in++;
        out++;
    }

    t= uptimeMillis() - t;
    debugI32("Filter time", t);

    sendToClient(&count, 1, 4, 0);
    return 0;
}
