// Fountain test script

#pragma version(1)
#pragma stateVertex(default)
#pragma stateFragment(PgmFragParts)
#pragma stateFragmentStore(PFSBlend)


int main(int launchID) {
    int ct;
    int count = loadI32(0, OFFSETOF_SomeData_count);
    int touch = loadI32(0, OFFSETOF_SomeData_touch);
    int rate = 4;
    int maxLife = (count / rate) - 1;

    if (touch) {
        int x = loadI32(0, OFFSETOF_SomeData_x);
        int y = loadI32(0, OFFSETOF_SomeData_y);
        int newPart = loadI32(2, 0);
        for (ct=0; ct<rate; ct++) {
            int idx = newPart * 5 + 1;
            storeF(2, idx, randf(1.f) - 0.5f);
            storeF(2, idx + 1, randf(1.f) - 0.5f);
            storeI32(2, idx + 2, maxLife);
            storeF(2, idx + 3, x);
            storeF(2, idx + 4, y);
            newPart++;
            if (newPart >= count) {
                newPart = 0;
            }
        }
        storeI32(2, 0, newPart);
    }

    int drawCount = 0;
    float height = getHeight();
    for (ct=0; ct < count; ct++) {
        int srcIdx = ct * 5 + 1;

        int life = loadI32(2, srcIdx + 2);

        if (life) {
            float posx = loadF(2, srcIdx + 3);
            float posy = loadF(2, srcIdx + 4);
            float dx = loadF(2, srcIdx);
            float dy = loadF(2, srcIdx + 1);
            if (posy < height) {
                int dstIdx = drawCount * 9;
                int c = 0xcfcfcfcf;

                storeI32(1, dstIdx, c);
                storeF(1, dstIdx + 1, posx);
                storeF(1, dstIdx + 2, posy);

                storeI32(1, dstIdx + 3, c);
                storeF(1, dstIdx + 4, posx + 1.f);
                storeF(1, dstIdx + 5, posy + dy);

                storeI32(1, dstIdx + 6, c);
                storeF(1, dstIdx + 7, posx - 1.f);
                storeF(1, dstIdx + 8, posy + dy);
                drawCount ++;
            } else {
                if (dy > 0) {
                    dy *= -0.5f;
                }
            }

            posx = posx + dx;
            posy = posy + dy;
            dy = dy + 0.05f;
            life --;

            //storeI32(2, srcIdx, dx);
            storeF(2, srcIdx + 1, dy);
            storeI32(2, srcIdx + 2, life);
            storeF(2, srcIdx + 3, posx);
            storeF(2, srcIdx + 4, posy);
        }
    }

    //drawTriangleArray(NAMED_PartBuffer, drawCount);
    uploadToBufferObject(NAMED_PartBuffer);
    drawSimpleMeshRange(NAMED_PartMesh, 0, drawCount * 3);
    return 1;
}
