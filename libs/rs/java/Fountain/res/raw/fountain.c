// Fountain test script
#pragma version(1)
#pragma stateVertex(default)
#pragma stateFragment(default)
#pragma stateFragmentStore(default)

struct PartStruct {float dx; float dy; float x; float y; int c;};
int newPart = 0;

int main(int launchID) {
    int ct;
    int count = Control_count - 1;
    int rate = Control_rate;
    float height = getHeight();
    struct PartStruct * p = (struct PartStruct *)loadArrayF(1, 0);

    if (rate) {
        float rMax = ((float)rate) * 0.005f;
        int x = Control_x;
        int y = Control_y;
        int c = colorFloatRGBAtoUNorm8(Control_r, Control_g, Control_b, 0.99f);

        while (rate--) {
            vec2Rand((float *)(p + newPart), rMax);
            p[newPart].x = x;
            p[newPart].y = y;
            p[newPart].c = c;
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
    drawSimpleMeshRange(NAMED_PartMesh, 0, count);
    return 1;
}
