/*
// block of defines matching what RS will insert at runtime.
struct Params_s{
    int inHeight;
    int inWidth;
    int outHeight;
    int outWidth;
    float threshold;
};
struct Params_s * Params;
struct InPixel_s{
    char a;
    char b;
    char g;
    char r;
};
struct InPixel_s * InPixel;
struct OutPixel_s{
    char a;
    char b;
    char g;
    char r;
};
struct OutPixel_s * OutPixel;
*/

struct color_s {
    char b;
    char g;
    char r;
    char a;
};

void main() {
    int t = uptimeMillis();

    struct color_s *in = (struct color_s *) InPixel;
    struct color_s *out = (struct color_s *) OutPixel;

    int count = Params->inWidth * Params->inHeight;
    int i;
    float threshold = (Params->threshold * 255.f);

    for (i = 0; i < count; i++) {
        float luminance = 0.2125f * in->r +
                          0.7154f * in->g +
                          0.0721f * in->b;
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
}
