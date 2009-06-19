// Fountain test script

#pragma version(1)
#pragma stateVertex(PV)
#pragma stateFragment(default)
#pragma stateFragmentStore(default)

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

int main(void* con, int ft, int index) 
{
    int f1;
    int f2;
    int f3;
    int f4;
    int f5;
    int f6;
    int f7;
    int f8;
    int f9;
    int f10;
    int f11;
    int f12;
    int f13;
    int f14;
    int f15;
    int f16;

    int trans;  // float
    int rot;   // float


    //trans = loadEnvF(con, 1, 0);
    //rot = loadEnvF(con, 1, 1);

    //matrixLoadTranslate(con, &f1, 0, 0, trans);
    //matrixRotate(con, &f1, rot, 1, 0, 0);
    //matrixScale(con, &f1, 3.0f, 3.0f, 3.0f);
    //storeEnvMatrix(con, 3, RS_PROGRAM_VERTEX_MODELVIEW_OFFSET, &f1);

    //rsc_Matrix m;
    //int imgId = 0;

    // This should be replaced in the compiler with a 
    // smart load of a structure.
    //const FilmScriptUserEnv *env = loadEnvVp(con, 0,0);

    //materialDiffuse(con, 0.0f, 0.0f, 0.0f, 1.0f);
    //materialSpecular(con, 0.5f, 0.5f, 0.5f, 0.5f);
    //materialShininess(con, 20.0f);



    //lightPosition(con, 0.2f, -0.2f, -2.0f, 0.0f);

    //contextBindProgramFragmentStore(con, NAMED_PFSBackground);
    //contextBindProgramFragment(con, NAMED_PFBackground);
    //enable(con, GL_LIGHTING);
    renderTriangleMesh(con, NAMED_mesh);

/*
    contextBindProgramFragmentStore(con, env->fsImages);
    contextBindProgramFragment(con, env->fpImages);
    disable(con, GL_LIGHTING);

    float focusPos = loadEnvF(con, 1, 2);
    int32_t focusID = 0;
    int32_t lastFocusID = loadEnvI32(con, 2, 0);
    int32_t imgCount = 13;

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
}

