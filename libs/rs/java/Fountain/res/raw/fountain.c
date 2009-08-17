// Fountain test script
#pragma version(1)
#pragma stateVertex(default)
#pragma stateFragment(default)
#pragma stateFragmentStore(default)

int main(int launchID) {
    int ct;
    int count = Control_count - 1;
    int rate = Control_rate;
    float *dataF = loadArrayF(1, 0);
    float height = getHeight();

    if (rate) {
        debugI32("rate", rate);
        int *dataI = loadArrayI32(1, 0);
        float rMax = ((float)rate) * 0.005f;
        int x = Control_x;
        int y = Control_y;
        int newPart = loadI32(1, count * 5);
        int c = colorFloatRGBAtoUNorm8(Control_r, Control_g, Control_b, 0.99f);

        while (rate--) {
            int idx = newPart * 5;
            vec2Rand(dataF + idx, rMax);
            dataF[idx + 2] = x;
            dataF[idx + 3] = y;
            dataI[idx + 4] = c;
            newPart++;
            if (newPart >= count) {
                newPart = 0;
            }
        }
        storeI32(1, count * 5, newPart);
    }

    for (ct=0; ct < count; ct++) {
        float dy = dataF[1] + 0.15f;
        float posy = dataF[3] + dy;
        if ((posy > height) && (dy > 0)) {
            dy *= -0.3f;
        }
        dataF[1] = dy;
        dataF[2] += dataF[0];
        dataF[3] = posy;
        dataF += 5;
    }

    uploadToBufferObject(NAMED_PartBuffer);
    drawSimpleMeshRange(NAMED_PartMesh, 0, count);
    return 1;
}
