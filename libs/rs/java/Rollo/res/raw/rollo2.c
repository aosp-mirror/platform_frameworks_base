#pragma version(1)
#pragma stateVertex(PV)
#pragma stateFragment(PF)
#pragma stateFragmentStore(PFS)

void drawLoop(int x, int y, int z, int rot)
{
    int ct;
    int tx;
    int ty;
    int tmpSin;
    int tmpCos;
    int sz;

    for (ct = 0; ct < 10; ct ++) {
        tmpSin = sinx((ct * 36 + rot) * 0x10000);
        tmpCos = cosx((ct * 36 + rot) * 0x10000);

        ty = y + tmpCos * 4;
        tx = x + tmpSin * 4;
        pfBindTexture(NAMED_PF, 0, loadI32(1, ct & 3));

        sz = 0xc000;
        drawQuad(tx - sz, ty - sz, z,
                 tx + sz, ty - sz, z,
                 tx + sz, ty + sz, z,
                 tx - sz, ty + sz, z);
    }
}

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
    int tmpSin;
    int tmpCos;
    int iconCount;
    int pressure;

    int ringCount;



    rotStep = 16 * 0x10000;
    pressure = loadI32(0, 2);
    rowCount = 4;

    iconCount = loadI32(0, 1);
    rot = (-20 + loadI32(0, 0)) * 0x10000;

    for (ringCount = 0; ringCount < 5; ringCount++) {
        drawLoop(0, 0, 0x90000 + (ringCount * 0x80000));
    }

    return 0;
}

