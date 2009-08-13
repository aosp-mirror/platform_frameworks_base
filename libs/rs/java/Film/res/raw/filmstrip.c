// Fountain test script

#pragma version(1)
#pragma stateVertex(PVBackground)
#pragma stateFragment(PFBackground)
#pragma stateFragmentStore(PSBackground)

#define POS_TRANSLATE 0
#define POS_ROTATE 1
#define POS_FOCUS 2

#define STATE_TRIANGLE_OFFSET_COUNT 0
#define STATE_LAST_FOCUS 1


// The script enviroment has 3 env allocations.
// bank0: (r) The enviroment structure
// bank1: (r) The position information
// bank2: (rw) The temporary texture state

int main(int index)
{
    float mat1[16];

    float trans = Pos_translate;
    float rot = Pos_rotate;
    matrixLoadScale(mat1, 2.f, 2.f, 2.f);
    matrixTranslate(mat1, 0.f, 0.f, trans);
    matrixRotate(mat1, 90.f, 0.f, 0.f, 1.f);
    matrixRotate(mat1, rot, 1.f, 0.f, 0.f);
    storeMatrix(3, 0, mat1);

    // Draw the lighting effect in the strip and fill the Z buffer.
    drawTriangleMesh(NAMED_mesh);


    // Start of images.
    bindProgramFragmentStore(NAMED_PSImages);
    bindProgramFragment(NAMED_PFImages);
    bindProgramVertex(NAMED_PVImages);

    float focusPos = Pos_focus;
    int focusID = 0;
    int lastFocusID = loadI32(2, STATE_LAST_FOCUS);
    int imgCount = 13;

    if (trans > (-.3f)) {
        focusID = -1.0f - focusPos;
        if (focusID >= imgCount) {
            focusID = -1;
        }
    } else {
        focusID = -1;
    }

    /*
    if (focusID != lastFocusID) {
        if (lastFocusID >= 0) {
            uploadToTexture(con, env->tex[lastFocusID], 1);
        }
        if (focusID >= 0) {
            uploadToTexture(con, env->tex[focusID], 0);
        }
    }
    */
    storeI32(2, STATE_LAST_FOCUS, focusID);

    int triangleOffsetsCount = Pos_triangleOffsetCount;

    int imgId = 0;
    for (imgId=1; imgId <= imgCount; imgId++) {
        float pos = focusPos + imgId + 0.4f;
        int offset = (int)floorf(pos * 2.f);
        pos = pos - 0.75f;

        offset = offset + triangleOffsetsCount / 2;

    int drawit = 1;
    if (offset < 0) {
        drawit = 0;
    }
    if (offset >= triangleOffsetsCount) {
        drawit = 0;
    }

        //if (!((offset < 0) || (offset >= triangleOffsetsCount))) {
        if (drawit) {
            int start = offset -2;
            int end = offset + 2;

            if (start < 0) {
                start = 0;
            }
            if (end > triangleOffsetsCount) {
                end = triangleOffsetsCount;
            }

            bindTexture(NAMED_PFImages, 0, loadI32(0, imgId - 1));
            matrixLoadTranslate(mat1, -pos - loadF(5, triangleOffsetsCount / 2), 0, 0);
            vpLoadTextureMatrix(mat1);
            drawTriangleMeshRange(NAMED_mesh, loadI32(4, start), loadI32(4, end) - loadI32(4, start));
        }
    }
    return 0;
}

