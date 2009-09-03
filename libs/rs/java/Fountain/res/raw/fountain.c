// Fountain test script
#pragma version(1)
#pragma stateVertex(default)
#pragma stateFragment(default)
#pragma stateFragmentStore(default)

int newPart = 0;

int main(int launchID) {
    int ct;
    int count = Control->count;
    int rate = Control->rate;
    float height = getHeight();
    struct point_s * p = (struct point_s *)point;

    if (rate) {
        float rMax = ((float)rate) * 0.005f;
        int x = Control->x;
        int y = Control->y;
        char r = Control->r * 255.f;
        char g = Control->g * 255.f;
        char b = Control->b * 255.f;
        char a = 0xf0;

        while (rate--) {
            vec2Rand((float *)(p + newPart), rMax);
            p[newPart].x = x;
            p[newPart].y = y;
            p[newPart].r = r;
            p[newPart].g = g;
            p[newPart].b = b;
            p[newPart].a = a;
            newPart++;
            if (newPart >= count) {
                newPart = 0;
            }
        }
    }

    for (ct=0; ct < count; ct++) {
        float dy = p->dy + 0.15f;
        float posy = p->y + dy;
        if ((posy > height) && (dy > 0)) {
            dy *= -0.3f;
        }
        p->dy = dy;
        p->x += p->dx;
        p->y = posy;
        p++;
    }

    uploadToBufferObject(NAMED_PartBuffer);
    drawSimpleMesh(NAMED_PartMesh);
    return 1;
}
