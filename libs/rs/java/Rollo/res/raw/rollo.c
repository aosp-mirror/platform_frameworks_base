#pragma version(1)
#pragma stateVertex(PV)
#pragma stateFragment(PF)
#pragma stateFragmentStore(PFS)

// Scratch buffer layout
#define SCRATCH_FADE 0
#define SCRATCH_ZOOM 1
#define SCRATCH_ROT 2

#define STATE_POS_X             0
#define STATE_POS_Y             1
#define STATE_PRESSURE          2
#define STATE_ZOOM              3
#define STATE_WARP              4
#define STATE_ORIENTATION       5
#define STATE_SELECTION         6
#define STATE_FIRST_VISIBLE     7
#define STATE_COUNT             8
#define STATE_TOUCH             9

float filter(float val, float target, float str)
{
    float delta = (target - val);
    return val + delta * str;
}

int main(void* con, int ft, int launchID)
{
    int rowCount;
    int row;
    int col;
    int imageID;
    int iconCount;
    int pressure;

    float f = loadF(2, 0);
    pfClearColor(0.0f, 0.0f, 0.0f, f);
    if (f < 0.8f) {
        f = f + 0.02f;
        storeF(2, 0, f);
    }

    float touchCut = 1.f;
    if (loadI32(0, STATE_TOUCH)) {
        touchCut = 5.f;
    }

    float targetZoom = ((float)loadI32(0, STATE_ZOOM)) / 10.f;
    float zoom = filter(loadF(2, SCRATCH_ZOOM), targetZoom, 0.15 * touchCut);
    storeF(2, SCRATCH_ZOOM, zoom);

    float targetRot = loadI32(0, STATE_FIRST_VISIBLE) / 180.0f * 3.14f;
    float rot = filter(loadF(2, SCRATCH_ROT), targetRot, 0.1f * touchCut);
    storeF(2, SCRATCH_ROT, rot);

    float diam = 8.f;// + curve * 2.f;
    float scale = 1.0f / zoom;

    pressure = loadI32(0, STATE_PRESSURE);
    if (pressure) {
        contextBindProgramFragmentStore(NAMED_PFSShadow);

        // compute the projected shadow
        float x = loadI32(0, STATE_POS_X) / 1000.f;
        float y = loadI32(0, STATE_POS_Y) / 1000.f;
        float s = loadI32(0, STATE_PRESSURE) / 1000.f;

        s = s * 3.f;

        float dxdy1 = (x - 0.5f - s) / (1.001f - y);
        float dxdy2 = (x - 0.5f + s) / (1.001f - y);

        float xlt = y * dxdy1 + x;
        float xrt = y * dxdy2 + x;

        float yb = (0.5f - y) * 5.f + 0.2f;

        drawQuad(xlt, 5.f, 1,
                 xrt, 5.f, 1,
                 x + s, yb, 1,
                 x - s, yb, 1);

        contextBindProgramFragmentStore(NAMED_PFS);
    }


    rot = rot * scale;
    float rotStep = 20.0f / 180.0f * 3.14f * scale;
    //pressure = loadI32(0, 2);
    rowCount = 4;
    iconCount = 32;//loadI32(0, 1);
    while (iconCount) {
        float tmpSin = sinf(rot);
        float tmpCos = cosf(rot);

        //tmpCos = tmpCos * curve;

        float tx1 = tmpSin * diam - (tmpCos * scale);
        float tx2 = tx1 + (tmpCos * scale * 2.f);

        float tz1 = tmpCos * diam + (tmpSin * scale);
        float tz2 = tz1 - (tmpSin * scale * 2.f);

        int y;
        for (y = 0; (y < rowCount) && iconCount; y++) {
            float ty1 = ((y * 3.0f) - 4.5f) * scale;
            float ty2 = ty1 + scale * 2.f;
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


