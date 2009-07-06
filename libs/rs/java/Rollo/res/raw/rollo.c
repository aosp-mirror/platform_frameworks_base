#pragma version(1)
#pragma stateVertex(PV)
#pragma stateFragment(PF)
#pragma stateFragmentStore(PFS)

int main(void* con, int ft, int launchID)
{
    int rowCount;
    int x;
    int y;
    int row;
    int col;
    int imageID;
    int tx1;
    int ty1;
    int tz1;
    int tx2;
    int ty2;
    int tz2;
    int rot;
    int rotStep;
    int tmpSin;
    int tmpCos;
    int iconCount;
    int pressure;


    rotStep = 20 * 0x10000;
    pressure = loadI32(0, 2);

    rowCount = 4;

    iconCount = loadI32(0, 1);
    rot = (-20 + loadI32(0, 0)) * 0x10000;
    while (iconCount) {
        tmpSin = sinx(rot);
        tmpCos = cosx(rot);

        tx1 = tmpSin * 8 - tmpCos;
        tx2 = tx1 + tmpCos * 2;

        tz1 = tmpCos * 8 + tmpSin + pressure;
        tz2 = tz1 - tmpSin * 2;

        for (y = 0; (y < rowCount) && iconCount; y++) {
            ty1 = (y * 0x30000) - 0x48000;
            ty2 = ty1 + 0x20000;
            pfBindTexture(NAMED_PF, 0, loadI32(1, y));
            drawQuad(tx1, ty1, tz1,
                     tx2, ty1, tz2,
                     tx2, ty2, tz2,
                     tx1, ty2, tz1);
            iconCount--;
        }
        rot = rot + rotStep;
    }

    return 0;
}

