// Fountain test script

#pragma version(1)
#pragma stateVertex(default)
#pragma stateFragment(PgmFragParts)
#pragma stateFragmentStore(PFSBlend)


int main(int launchID) {
    int count, touch, x, y, rate, maxLife, lifeShift;
    int life;
    int ct, ct2;
    int newPart;
    int drawCount;
    int dx, dy, idx;
    int posx,posy;
    int c;
    int srcIdx;
    int dstIdx;

    count = loadI32(0, 1);
    touch = loadI32(0, 2);
    x = loadI32(0, 3);
    y = loadI32(0, 4);

    rate = 4;
    maxLife = (count / rate) - 1;
    lifeShift = 0;
    {
        life = maxLife;
        while (life > 255) {
            life = life >> 1;
            lifeShift ++;
        }
    }

    if (touch) {
        newPart = loadI32(2, 0);
        for (ct2=0; ct2<rate; ct2++) {
            dx = (int)((randf(1.f) - 0.5f) * 0x10000);
            dy = (int)((randf(1.f) - 0.5f) * 0x10000);

            idx = newPart * 5 + 1;
            storeI32(2, idx, dx);
            storeI32(2, idx + 1, dy);
            storeI32(2, idx + 2, maxLife);
            storeI32(2, idx + 3, x << 16);
            storeI32(2, idx + 4, y << 16);

            newPart++;
            if (newPart >= count) {
                newPart = 0;
            }
        }
        storeI32(2, 0, newPart);
    }

    drawCount = 0;
    for (ct=0; ct < count; ct++) {
        srcIdx = ct * 5 + 1;

        dx = loadI32(2, srcIdx);
        dy = loadI32(2, srcIdx + 1);
        life = loadI32(2, srcIdx + 2);
        posx = loadI32(2, srcIdx + 3);
        posy = loadI32(2, srcIdx + 4);

        if (life) {
            if (posy < (480 << 16)) {
                dstIdx = drawCount * 9;
                c = 0xffafcf | ((life >> lifeShift) << 24);

                storeI32(1, dstIdx, c);
                storeI32(1, dstIdx + 1, posx);
                storeI32(1, dstIdx + 2, posy);

                storeI32(1, dstIdx + 3, c);
                storeI32(1, dstIdx + 4, posx + 0x10000);
                storeI32(1, dstIdx + 5, posy + dy * 4);

                storeI32(1, dstIdx + 6, c);
                storeI32(1, dstIdx + 7, posx - 0x10000);
                storeI32(1, dstIdx + 8, posy + dy * 4);
                drawCount ++;
            } else {
                if (dy > 0) {
                    dy = (-dy) >> 1;
                }
            }

            posx = posx + dx;
            posy = posy + dy;
            dy = dy + 0x400;
            life --;

            //storeI32(2, srcIdx, dx);
            storeI32(2, srcIdx + 1, dy);
            storeI32(2, srcIdx + 2, life);
            storeI32(2, srcIdx + 3, posx);
            storeI32(2, srcIdx + 4, posy);
        }
    }

    drawTriangleArray(NAMED_PartBuffer, drawCount);
    return 1;
}
