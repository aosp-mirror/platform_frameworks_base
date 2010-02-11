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
        luminance = maxf(0.0f, luminance - threshold);
        vec3Scale(&pixel, luminance > 0);
        out->a = in->a;
        out->r = pixel.x;
        out->g = pixel.y;
        out->b = pixel.z;

        in++;
        out++;
    }

    t= uptimeMillis() - t;
    debugI32("Filter time", t);

    sendToClient(&count, 1, 4, 0);
}
