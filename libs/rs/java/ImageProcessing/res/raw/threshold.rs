struct color_s {
    char b;
    char g;
    char r;
    char a;
};

void filter(struct color_s *in, struct color_s *out, struct vecF32_3_s *luminanceVector) {
    struct vecF32_3_s pixel;
    pixel.x = (in->r & 0xFF) / 255.0f;
    pixel.y = (in->g & 0xFF) / 255.0f;
    pixel.z = (in->b & 0xFF) / 255.0f;

    float luminance = vec3Dot(luminanceVector, &pixel);
    luminance = maxf(0.0f, luminance - Params->threshold);
    vec3Scale(&pixel, signf(luminance));

    out->a = in->a;
    out->r = pixel.x * 255.0f;
    out->g = pixel.y * 255.0f;
    out->b = pixel.z * 255.0f;
}

void main() {
    int t = uptimeMillis();

    struct color_s *in = (struct color_s *) InPixel;
    struct color_s *out = (struct color_s *) OutPixel;

    struct vecF32_3_s luminanceVector;
    luminanceVector.x = 0.2125f;
    luminanceVector.y = 0.7154f;
    luminanceVector.z = 0.0721f;

    int count = Params->inWidth * Params->inHeight;
    int i;

    for (i = 0; i < count; i++) {
        filter(in, out, &luminanceVector);

        in++;
        out++;
    }

    t= uptimeMillis() - t;
    debugI32("Filter time", t);

    sendToClient(&count, 1, 4, 0);
}
