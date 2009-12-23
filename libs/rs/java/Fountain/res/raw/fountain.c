// Fountain test script
#pragma version(1)

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
        int color = ((int)(Control->r * 255.f)) |
                    ((int)(Control->g * 255.f)) << 8 |
                    ((int)(Control->b * 255.f)) << 16 |
                    (0xf0 << 24);
        struct point_s * np = &p[newPart];

        while (rate--) {
            vec2Rand((float *)&np->delta.x, rMax);
            np->position.x = x;
            np->position.y = y;
            np->color = color;
            newPart++;
            np++;
            if (newPart >= count) {
                newPart = 0;
                np = &p[newPart];
            }
        }
    }

    for (ct=0; ct < count; ct++) {
        float dy = p->delta.y + 0.15f;
        float posy = p->position.y + dy;
        if ((posy > height) && (dy > 0)) {
            dy *= -0.3f;
        }
        p->delta.y = dy;
        p->position.x += p->delta.x;
        p->position.y = posy;
        p++;
    }

    uploadToBufferObject(NAMED_PartBuffer);
    drawSimpleMesh(NAMED_PartMesh);
    return 1;
}
