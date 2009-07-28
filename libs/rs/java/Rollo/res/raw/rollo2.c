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
    int imageID;
    int done = loadI32(0, STATE_DONE);
    int selectedID = loadI32(0, STATE_SELECTION);
    int iconCount = loadI32(0, STATE_COUNT);

    float f = loadF(2, 0);

    float iconSize = 1.f;
    float iconSpacing = 0.2f;
    float z = 4.f;

    pfClearColor(0.0f, 0.0f, 0.0f, f);
    if (done) {
    } else {
        if (f < 0.8f) {
            f = f + 0.02f;
            storeF(2, 0, f);
        }
    }

    float touchCut = 1.f;
    if (loadI32(0, STATE_TOUCH)) {
        touchCut = 5.f;
    }


    float targetZoom = ((float)loadI32(0, STATE_ZOOM)) / 1000.f;
    float zoom = filter(loadF(2, SCRATCH_ZOOM), targetZoom, 0.15 * touchCut);
    storeF(2, SCRATCH_ZOOM, zoom);

    float targetPos = loadI32(0, STATE_FIRST_VISIBLE) / (-20.0f);
    float pos = filter(loadF(2, SCRATCH_ROT), targetPos, 0.1f * touchCut);
    storeF(2, SCRATCH_ROT, pos);
    pos = pos - 1.f;

    color(1.0f, 1.0f, 1.0f, 1.0f);


    // Draw flat icons first
    int index = ((int)pos) * 4;
    int row;
    int col;
    float xoffset = -0.3f;
    float gridSize = iconSize * 4.f + iconSpacing * 3.f;
    float yoffset = (pos - ((int)pos));
    for (row = 0; row < 4; row ++) {
        float ty1 = (gridSize / 2.f) - ((float)row - yoffset) * (iconSize + iconSpacing) - iconSize;
        float ty2 = ty1 + iconSize;

        for (col = 0; (col < 4) && (index < iconCount); col ++) {
            if (index >= 0) {
                bindTexture(NAMED_PF, 0, loadI32(1, index));
                float fcol = col;
                float tx1 = xoffset + (-gridSize / 2.f) + (fcol * (iconSize + iconSpacing));
                float tx2 = tx1 + iconSize;

                drawQuad(tx1, ty1, z,
                         tx2, ty1, z,
                         tx2, ty2, z,
                         tx1, ty2, z);
            }
            index++;
        }
    }

    // bottom roller
    {
        float roll = (1.f - yoffset) * 0.5f * 3.14f;
        float tmpSin = sinf(roll);
        float tmpCos = cosf(roll);

        for (col = 0; (col < 4) && (index < iconCount) && (index >= 0); col ++) {
            float ty2 = (gridSize / 2.f) - ((float)row - yoffset) * (iconSize + iconSpacing);
            float ty1 = ty2 - tmpCos * iconSize;

            float tz1 = z + tmpSin * iconSize;
            float tz2 = z;

            float tx1 = xoffset + (-gridSize / 2.f) + ((float)col * (iconSize + iconSpacing));
            float tx2 = tx1 + iconSize;

            bindTexture(NAMED_PF, 0, loadI32(1, index));
            drawQuad(tx1, ty1, tz1,
                     tx2, ty1, tz1,
                     tx2, ty2, tz2,
                     tx1, ty2, tz2);
            index++;
        }
    }

    // Top roller
    {
        index = (((int)pos) * 4) - 4;
        float roll = yoffset * 0.5f * 3.14f;
        float tmpSin = sinf(roll);
        float tmpCos = cosf(roll);

        for (col = 0; (col < 4) && (index < iconCount) && (index >= 0); col ++) {
            float ty1 = (gridSize / 2.f) - ((float)-1.f - yoffset) * (iconSize + iconSpacing) - iconSize;
            float ty2 = ty1 + tmpCos * iconSize;

            float tz1 = z;
            float tz2 = z + tmpSin * iconSize;

            float tx1 = xoffset + (-gridSize / 2.f) + ((float)col * (iconSize + iconSpacing));
            float tx2 = tx1 + iconSize;

            bindTexture(NAMED_PF, 0, loadI32(1, index));
            drawQuad(tx1, ty1, tz1,
                     tx2, ty1, tz1,
                     tx2, ty2, tz2,
                     tx1, ty2, tz2);
            index++;
        }
    }




    return 1;
}



