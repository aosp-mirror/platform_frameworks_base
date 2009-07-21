// Fountain test script

#pragma version(1)
#pragma stateVertex(PVBackground)
#pragma stateFragment(PFBackground)
#pragma stateFragmentStore(PFSBackground)

/*
typedef struct FilmScriptUserEnvRec {
    RsAllocation tex[13];
    int32_t triangleOffsets[64];
    float triangleOffsetsTex[64];
    int32_t triangleOffsetsCount;
} FilmScriptUserEnv; 
*/ 

// The script enviroment has 3 env allocations.
// bank0: (r) The enviroment structure
// bank1: (r) The position information
// bank2: (rw) The temporary texture state

int main(int index) 
{
    int f1,f2,f3,f4, f5,f6,f7,f8, f9,f10,f11,f12, f13,f14,f15,f16;
    int g1,g2,g3,g4, g5,g6,g7,g8, g9,g10,g11,g12, g13,g14,g15,g16;
    float trans;
    float rot;
    int x;
    float focusPos;  // float
    int focusID;
    int lastFocusID;
    int imgCount;

    trans = loadF(1, 0);
    rot = loadF(1, 1);

    matrixLoadScale(&f16, 2.f, 2.f, 2.f);
    matrixTranslate(&f16, 0.f, 0.f, trans);
    matrixRotate(&f16, 90.f, 0.f, 0.f, 1.f);
    matrixRotate(&f16, rot, 1.f, 0.f, 0.f);
    storeMatrix(3, 0, &f16);

    //materialDiffuse(con, 0.0f, 0.0f, 0.0f, 1.0f);
    //materialSpecular(con, 0.5f, 0.5f, 0.5f, 0.5f);
    //materialShininess(intToFloat(20));
    drawTriangleMesh(NAMED_mesh);



    //int imgId = 0;

    bindProgramFragmentStore(NAMED_PFImages);
    bindProgramFragment(NAMED_PFSImages);
    bindProgramVertex(NAMED_PVImages);

    //focusPos = loadF(1, 2);
    //focusID = 0;
    //lastFocusID = loadI32(2, 0);
    //imgCount = 13;

    /*
    disable(GL_LIGHTING);


    if (trans > (-.3)) {
        focusID = -1.0 - focusPos;
        if (focusID >= imgCount) {
            focusID = -1;
        }
    } else {
        focusID = -1;
    }

    if (focusID != lastFocusID) {
        if (lastFocusID >= 0) {
            uploadToTexture(con, env->tex[lastFocusID], 1);
        }
        if (focusID >= 0) {
            uploadToTexture(con, env->tex[focusID], 0);
        }
    }
    storeEnvI32(con, 2, 0, focusID);


    for (imgId=1; imgId <= imgCount; imgId++) {
        float pos = focusPos + imgId + .4f;
        int offset = (int)floor(pos*2);
        pos -= 0.75;
    
        offset += env->triangleOffsetsCount / 2;
    
        if ((offset < 0) || (offset >= env->triangleOffsetsCount)) {
            continue;
        }
    
        int start = offset -2;
        int end = offset + 2;
    
        if (start < 0) {
            start = 0;
        }
        if (end > env->triangleOffsetsCount) {
            end = env->triangleOffsetsCount;
        }
    
        programFragmentBindTexture(con, env->fpImages, 0, env->tex[imgId - 1]);
        matrixLoadTranslate(con, &m, -pos - env->triangleOffsetsTex[env->triangleOffsetsCount / 2], 0, 0); 
        storeEnvMatrix(con, 3, RS_PROGRAM_VERTEX_TEXTURE_OFFSET, &m);
        renderTriangleMeshRange(con, env->mesh, env->triangleOffsets[start], env->triangleOffsets[end] - env->triangleOffsets[start]);
    } 
*/
    return 0;
}

