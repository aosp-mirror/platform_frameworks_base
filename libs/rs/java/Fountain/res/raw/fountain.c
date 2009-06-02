// Fountain test script

main(con, ft, launchID) {
    int count, touch, x, y, rate, maxLife, lifeShift;
    int life;
    int ct, ct2;
    int newPart;
    int drawCount;
    int dx, dy, idx;
    int partPtr;
    int vertPtr;
    int posx,posy;
    int c;

    count = loadI32(con, 0, 1);
    touch = loadI32(con, 0, 2);
    x = loadI32(con, 0, 3);
    y = loadI32(con, 0, 4);

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

    contextBindProgramFragment(con, loadI32(con, 0, 7));
    drawRect(con, 0, 256, 0, 512);
    contextBindProgramFragment(con, loadI32(con, 0, 6));

    if (touch) {
        newPart = loadI32(con, 2, 0);
        for (ct2=0; ct2<rate; ct2++) {
            dx = scriptRand(con, 0x10000) - 0x8000;
            dy = scriptRand(con, 0x10000) - 0x8000;

            idx = newPart * 5 + 1;
            storeI32(con, 2, idx, dx);
            storeI32(con, 2, idx + 1, dy);
            storeI32(con, 2, idx + 2, maxLife);
            storeI32(con, 2, idx + 3, x << 16);
            storeI32(con, 2, idx + 4, y << 16);

            newPart++;
            if (newPart >= count) {
                newPart = 0;
            }
        }
        storeI32(con, 2, 0, newPart);
    }

    // Emulate intrinsic perf...
    partPtr = loadVp(con, 2, 4);
    vertPtr = loadVp(con, 1, 0);

    drawCount = 0;
    for (ct=0; ct < count; ct++) {
        //int srcIdx = ct * 5 + 1;
        //int dstIdx = ct * 3 * 3;

        dx = * (int* )(partPtr + 0); //loadEnvI32(con, 2, srcIdx);
        dy = * (int* )(partPtr + 4); //loadEnvI32(con, 2, srcIdx + 1);
        life = * (int* )(partPtr + 8); //loadEnvI32(con, 2, srcIdx + 2);
        posx = * (int* )(partPtr + 12); //loadEnvI32(con, 2, srcIdx + 3);
        posy = * (int* )(partPtr + 16); //loadEnvI32(con, 2, srcIdx + 4);

        if (life) {
            if (posy < (480 << 16)) {
                c = 0xffafcf | ((life >> lifeShift) << 24);

                * (int* )(vertPtr) = c; //storeEnvU32(con, 1, dstIdx, c);
                * (int* )(vertPtr + 4) = posx; //storeEnvI32(con, 1, dstIdx + 1, posx);
                * (int* )(vertPtr + 8) = posy; //storeEnvI32(con, 1, dstIdx + 2, posy);

                * (int* )(vertPtr + 12) = c; //storeEnvU32(con, 1, dstIdx + 3, c);
                * (int* )(vertPtr + 16) = posx + 0x10000; //storeEnvI32(con, 1, dstIdx + 4, posx + 0x10000);
                * (int* )(vertPtr + 20) = posy + dy * 4; //storeEnvI32(con, 1, dstIdx + 5, posy);

                * (int* )(vertPtr + 24) = c; //storeEnvU32(con, 1, dstIdx + 6, c);
                * (int* )(vertPtr + 28) = posx - 0x10000; //storeEnvI32(con, 1, dstIdx + 7, posx + 0x0800);
                * (int* )(vertPtr + 32) = posy + dy * 4; //storeEnvI32(con, 1, dstIdx + 8, posy + 0x10000);

                vertPtr = vertPtr + 36;
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

            * (int* )(partPtr + 0) = dx; //storeEnvI32(con, 2, srcIdx, dx);
            * (int* )(partPtr + 4) = dy; //storeEnvI32(con, 2, srcIdx + 1, dy);
            * (int* )(partPtr + 8) = life; //storeEnvI32(con, 2, srcIdx + 2, life);
            * (int* )(partPtr + 12) = posx; //storeEnvI32(con, 2, srcIdx + 3, posx);
            * (int* )(partPtr + 16) = posy; //storeEnvI32(con, 2, srcIdx + 4, posy);
        }

        partPtr = partPtr + 20;
    }

    drawTriangleArray(con, loadI32(con, 0, 5), drawCount);
}
