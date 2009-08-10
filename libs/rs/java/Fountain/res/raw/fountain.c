// Fountain test script

#pragma version(1)
#pragma stateVertex(default)
#pragma stateFragment(PgmFragParts)
#pragma stateFragmentStore(PFSBlend)


int main(int launchID) {
    int count, touch, x, y, rate;
    int ct, ct2;
    int newPart;
    int drawCount;
    int idx;
    float dx, dy;
    float posx,posy;
    int c;
    int srcIdx;
    int dstIdx;

    count = loadI32(0, 1);
    touch = loadI32(0, 2);
    x = loadI32(0, 3);
    y = loadI32(0, 4);

    rate = 4;
    int maxLife = (count / rate) - 1;

    if (touch) {
        newPart = loadI32(2, 0);
        for (ct2=0; ct2<rate; ct2++) {
            dx = randf(1.f) - 0.5f;
            dy = randf(1.f) - 0.5f;

            idx = newPart * 5 + 1;
            storeF(2, idx, dx);
            storeF(2, idx + 1, dy);
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

    drawCount = 0;
    float height = getHeight();
    for (ct=0; ct < count; ct++) {
        srcIdx = ct * 5 + 1;

        dx = loadF(2, srcIdx);
        dy = loadF(2, srcIdx + 1);
        int life = loadI32(2, srcIdx + 2);
        posx = loadF(2, srcIdx + 3);
        posy = loadF(2, srcIdx + 4);

        if (life) {
            if (posy < height) {
                dstIdx = drawCount * 9;
                c = 0xcfcfcfcf;

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
