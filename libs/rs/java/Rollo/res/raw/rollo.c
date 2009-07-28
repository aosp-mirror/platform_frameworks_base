#pragma version(1)
#pragma stateVertex(PV)
#pragma stateFragment(PF)
#pragma stateFragmentStore(PFS)

// Scratch buffer layout
#define SCRATCH_FADE 0
#define SCRATCH_ZOOM 1
#define SCRATCH_ROT 2

//#define STATE_POS_X             0
#define STATE_DONE              1
//#define STATE_PRESSURE          2
#define STATE_ZOOM              3
//#define STATE_WARP              4
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
    int done = loadI32(0, STATE_DONE);
    int selectedID = loadI32(0, STATE_SELECTION);

    float f = loadF(2, 0);

    pfClearColor(0.0f, 0.0f, 0.0f, f);
    if (done) {
        if (f > 0.02f) {
            //f = f - 0.02f;
            //storeF(2, 0, f);
        }
    } else {
        if (f < 0.8f) {
            f = f + 0.02f;
            storeF(2, 0, f);
        }
    }

    float touchCut = 1.f;
    if (loadI32(0, STATE_TOUCH)) {
        touchCut = 4.f;
    }


    float targetZoom = ((float)loadI32(0, STATE_ZOOM)) / 1000.f;
    float zoom = filter(loadF(2, SCRATCH_ZOOM), targetZoom, 0.15 * touchCut);
    storeF(2, SCRATCH_ZOOM, zoom);

    float targetRot = loadI32(0, STATE_FIRST_VISIBLE) / 180.0f * 3.14f;
    float drawRot = filter(loadF(2, SCRATCH_ROT), targetRot, 0.1f * touchCut);
    storeF(2, SCRATCH_ROT, drawRot);

    float diam = 10.f;
    float scale = 1.0f / zoom;

    // Bug makes 1.0f alpha fail.
    color(1.0f, 1.0f, 1.0f, 0.99f);

    float rot = drawRot * scale;
    float rotStep = 16.0f / 180.0f * 3.14f * scale;
    rowCount = 4;
    int index = 0;
    int iconCount = loadI32(0, STATE_COUNT);
    while (iconCount) {
        float tmpSin = sinf(rot);
        float tmpCos = cosf(rot);

        float tx1 = tmpSin * diam - (tmpCos * scale);
        float tx2 = tx1 + (tmpCos * scale * 2.f);
        float tz1 = tmpCos * diam + (tmpSin * scale);
        float tz2 = tz1 - (tmpSin * scale * 2.f);

        int y;
        for (y = rowCount -1; (y >= 0) && iconCount; y--) {
            float ty1 = ((y * 3.5f) - 6.f) * scale;
            float ty2 = ty1 + scale * 2.f;
            bindTexture(NAMED_PF, 0, loadI32(1, index));
            //if (done && (index != selectedID)) {
                //color(0.4f, 0.4f, 0.4f, 1.0f);
            //}
            drawQuad(tx1, ty1, tz1,
                     tx2, ty1, tz2,
                     tx2, ty2, tz2,
                     tx1, ty2, tz1);

            iconCount--;
            index++;
        }
        rot = rot + rotStep;
    }

    // Draw the selected icon
    color(1.0f, 1.0f, 1.0f, 0.9f);
    rot = drawRot * scale;
    index = 0;
    iconCount = loadI32(0, STATE_COUNT);
    while (iconCount) {
        int y;
        for (y = rowCount -1; (y >= 0) && iconCount; y--) {
            if (index == selectedID) {

                float tmpSin = sinf(rot) * scale;
                float tmpCos = cosf(rot) * scale;
                float tx1 = tmpSin * diam * 0.9f - tmpCos * 2.f;
                float tx2 = tx1 + (tmpCos * 4.f);
                float tz1 = tmpCos * diam * 0.9f + tmpSin * 2.f;
                float tz2 = tz1 - (tmpSin * 4.f);

                float ty1 = ((y * 3.5f) - 5.f) * scale;
                float ty2 = ty1 + scale * 4.f;
                bindTexture(NAMED_PF, 0, loadI32(1, index));
                drawQuad(tx1, ty1, tz1,
                         tx2, ty1, tz2,
                         tx2, ty2, tz2,
                         tx1, ty2, tz1);
            }
            iconCount--;
            index++;
        }
        rot = rot + rotStep;
    }

    return 1;
}


