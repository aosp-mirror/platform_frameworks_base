/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "rsContext.h"
#include "rsScriptC.h"
#include "rsMatrix.h"
#include "rsNoise.h"

#include "acc/acc.h"
#include "utils/String8.h"

#include <GLES/gl.h>
#include <GLES/glext.h>

#include <time.h>
#include <cutils/tztime.h>

using namespace android;
using namespace android::renderscript;

#define GET_TLS()  Context::ScriptTLSStruct * tls = \
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey); \
    Context * rsc = tls->mContext; \
    ScriptC * sc = (ScriptC *) tls->mScript


//////////////////////////////////////////////////////////////////////////////
// IO routines
//////////////////////////////////////////////////////////////////////////////

static float SC_loadF(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const float *f = static_cast<const float *>(vp);
    //LOGE("loadF %i %i = %f %x", bank, offset, f, ((int *)&f)[0]);
    return f[offset];
}

static int32_t SC_loadI32(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const int32_t *i = static_cast<const int32_t *>(vp);
    //LOGE("loadI32 %i %i = %i", bank, offset, t);
    return i[offset];
}

static float* SC_loadArrayF(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    float *f = static_cast<float *>(vp);
    return f + offset;
}

static int32_t* SC_loadArrayI32(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    int32_t *i = static_cast<int32_t *>(vp);
    return i + offset;
}

static float* SC_loadTriangleMeshVerticesF(RsTriangleMesh mesh)
{
    TriangleMesh *tm = static_cast<TriangleMesh *>(mesh);
    void *vp = tm->mVertexData;
    float *f = static_cast<float *>(vp);
    return f;
}

static void SC_updateTriangleMesh(RsTriangleMesh mesh)
{
    TriangleMesh *tm = static_cast<TriangleMesh *>(mesh);
    glBindBuffer(GL_ARRAY_BUFFER, tm->mBufferObjects[0]);
    glBufferData(GL_ARRAY_BUFFER, tm->mVertexDataSize, tm->mVertexData, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, tm->mBufferObjects[1]);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, tm->mIndexDataSize, tm->mIndexData, GL_STATIC_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);    
}

static uint32_t SC_loadU32(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const uint32_t *i = static_cast<const uint32_t *>(vp);
    return i[offset];
}

static void SC_loadVec4(uint32_t bank, uint32_t offset, rsc_Vector4 *v)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const float *f = static_cast<const float *>(vp);
    memcpy(v, &f[offset], sizeof(rsc_Vector4));
}

static void SC_loadMatrix(uint32_t bank, uint32_t offset, rsc_Matrix *m)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const float *f = static_cast<const float *>(vp);
    memcpy(m, &f[offset], sizeof(rsc_Matrix));
}


static void SC_storeF(uint32_t bank, uint32_t offset, float v)
{
    //LOGE("storeF %i %i %f", bank, offset, v);
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    float *f = static_cast<float *>(vp);
    f[offset] = v;
}

static void SC_storeI32(uint32_t bank, uint32_t offset, int32_t v)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    int32_t *f = static_cast<int32_t *>(vp);
    static_cast<int32_t *>(sc->mSlots[bank]->getPtr())[offset] = v;
}

static void SC_storeU32(uint32_t bank, uint32_t offset, uint32_t v)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    uint32_t *f = static_cast<uint32_t *>(vp);
    static_cast<uint32_t *>(sc->mSlots[bank]->getPtr())[offset] = v;
}

static void SC_storeVec4(uint32_t bank, uint32_t offset, const rsc_Vector4 *v)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    float *f = static_cast<float *>(vp);
    memcpy(&f[offset], v, sizeof(rsc_Vector4));
}

static void SC_storeMatrix(uint32_t bank, uint32_t offset, const rsc_Matrix *m)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    float *f = static_cast<float *>(vp);
    memcpy(&f[offset], m, sizeof(rsc_Matrix));
}


//////////////////////////////////////////////////////////////////////////////
// Math routines
//////////////////////////////////////////////////////////////////////////////

#define PI 3.1415926f
#define DEG_TO_RAD PI / 180.0f
#define RAD_TO_DEG 180.0f / PI

static float SC_randf(float max)
{
    float r = (float)rand();
    return r / RAND_MAX * max;
}

static float SC_randf2(float min, float max)
{
    float r = (float)rand();
    return r / RAND_MAX * (max - min) + min;
}

static float SC_clampf(float amount, float low, float high)
{
    return amount < low ? low : (amount > high ? high : amount);
}

static float SC_maxf(float a, float b)
{
    return a > b ? a : b; 
}

static float SC_minf(float a, float b)
{
    return a < b ? a : b; 
}

static float SC_sqrf(float v)
{
    return v * v; 
}

static float SC_distf2(float x1, float y1, float x2, float y2)
{
    float x = x2 - x1;
    float y = y2 - y1;
    return sqrtf(x * x + y * y); 
}

static float SC_distf3(float x1, float y1, float z1, float x2, float y2, float z2)
{
    float x = x2 - x1;
    float y = y2 - y1;
    float z = z2 - z1;
    return sqrtf(x * x + y * y + z * z); 
}

static float SC_magf2(float a, float b)
{
    return sqrtf(a * a + b * b);
}

static float SC_magf3(float a, float b, float c)
{
    return sqrtf(a * a + b * b + c * c);
}

static float SC_radf(float degrees)
{
    return degrees * DEG_TO_RAD; 
}

static float SC_degf(float radians)
{
    return radians * RAD_TO_DEG; 
}

static float SC_lerpf(float start, float stop, float amount)
{
    return start + (stop - start) * amount;
}

static float SC_normf(float start, float stop, float value)
{
    return (value - start) / (stop - start);
}

static float SC_mapf(float minStart, float minStop, float maxStart, float maxStop, float value)
{
    return maxStart + (maxStart - maxStop) * ((value - minStart) / (minStop - minStart));
}

//////////////////////////////////////////////////////////////////////////////
// Time routines
//////////////////////////////////////////////////////////////////////////////

static uint32_t SC_second()
{
    GET_TLS();

    time_t rawtime;
    time(&rawtime);

    if (sc->mEnviroment.mTimeZone) {
        struct tm timeinfo;
        localtime_tz(&rawtime, &timeinfo, sc->mEnviroment.mTimeZone);
        return timeinfo.tm_sec;
    } else {
        struct tm *timeinfo;
        timeinfo = localtime(&rawtime);
        return timeinfo->tm_sec;
    }
}

static uint32_t SC_minute()
{
    GET_TLS();
    
    time_t rawtime;
    time(&rawtime);
    
    if (sc->mEnviroment.mTimeZone) {
        struct tm timeinfo;
        localtime_tz(&rawtime, &timeinfo, sc->mEnviroment.mTimeZone);
        return timeinfo.tm_min;
    } else {
        struct tm *timeinfo;
        timeinfo = localtime(&rawtime);
        return timeinfo->tm_min;
    }
}   

static uint32_t SC_hour()
{
    GET_TLS();
    
    time_t rawtime;
    time(&rawtime);
    
    if (sc->mEnviroment.mTimeZone) {
        struct tm timeinfo;
        localtime_tz(&rawtime, &timeinfo, sc->mEnviroment.mTimeZone);
        return timeinfo.tm_hour;
    } else {
        struct tm *timeinfo;
        timeinfo = localtime(&rawtime);
        return timeinfo->tm_hour;
    }
}

static uint32_t SC_day()
{
    GET_TLS();
    
    time_t rawtime;
    time(&rawtime);
    
    if (sc->mEnviroment.mTimeZone) {
        struct tm timeinfo;
        localtime_tz(&rawtime, &timeinfo, sc->mEnviroment.mTimeZone);
        return timeinfo.tm_mday;
    } else {
        struct tm *timeinfo;
        timeinfo = localtime(&rawtime);
        return timeinfo->tm_mday;
    }
}   

static uint32_t SC_month()
{
    GET_TLS();
    
    time_t rawtime;
    time(&rawtime);
    
    if (sc->mEnviroment.mTimeZone) {
        struct tm timeinfo;
        localtime_tz(&rawtime, &timeinfo, sc->mEnviroment.mTimeZone);
        return timeinfo.tm_mon;
    } else {
        struct tm *timeinfo;
        timeinfo = localtime(&rawtime);
        return timeinfo->tm_mon;
    }
} 

static uint32_t SC_year()
{
    GET_TLS();
    
    time_t rawtime;
    time(&rawtime);
    
    if (sc->mEnviroment.mTimeZone) {
        struct tm timeinfo;
        localtime_tz(&rawtime, &timeinfo, sc->mEnviroment.mTimeZone);
        return timeinfo.tm_year;
    } else {
        struct tm *timeinfo;
        timeinfo = localtime(&rawtime);
        return timeinfo->tm_year;
    }
}

//////////////////////////////////////////////////////////////////////////////
// Matrix routines
//////////////////////////////////////////////////////////////////////////////


static void SC_matrixLoadIdentity(rsc_Matrix *mat)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadIdentity();
}

static void SC_matrixLoadFloat(rsc_Matrix *mat, const float *f)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->load(f);
}

static void SC_matrixLoadMat(rsc_Matrix *mat, const rsc_Matrix *newmat)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->load(reinterpret_cast<const Matrix *>(newmat));
}

static void SC_matrixLoadRotate(rsc_Matrix *mat, float rot, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadRotate(rot, x, y, z);
}

static void SC_matrixLoadScale(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadScale(x, y, z);
}

static void SC_matrixLoadTranslate(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadTranslate(x, y, z);
}

static void SC_matrixLoadMultiply(rsc_Matrix *mat, const rsc_Matrix *lhs, const rsc_Matrix *rhs)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadMultiply(reinterpret_cast<const Matrix *>(lhs),
                    reinterpret_cast<const Matrix *>(rhs));
}

static void SC_matrixMultiply(rsc_Matrix *mat, const rsc_Matrix *rhs)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->multiply(reinterpret_cast<const Matrix *>(rhs));
}

static void SC_matrixRotate(rsc_Matrix *mat, float rot, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->rotate(rot, x, y, z);
}

static void SC_matrixScale(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->scale(x, y, z);
}

static void SC_matrixTranslate(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->translate(x, y, z);
}




//////////////////////////////////////////////////////////////////////////////
// Context
//////////////////////////////////////////////////////////////////////////////

static void SC_bindTexture(RsProgramFragment vpf, uint32_t slot, RsAllocation va)
{
    GET_TLS();
    rsi_ProgramFragmentBindTexture(rsc,
                                   static_cast<ProgramFragment *>(vpf),
                                   slot,
                                   static_cast<Allocation *>(va));

}

static void SC_bindSampler(RsProgramFragment vpf, uint32_t slot, RsSampler vs)
{
    GET_TLS();
    rsi_ProgramFragmentBindSampler(rsc,
                                   static_cast<ProgramFragment *>(vpf),
                                   slot,
                                   static_cast<Sampler *>(vs));

}

static void SC_bindProgramFragmentStore(RsProgramFragmentStore pfs)
{
    GET_TLS();
    rsi_ContextBindProgramFragmentStore(rsc, pfs);

}

static void SC_bindProgramFragment(RsProgramFragment pf)
{
    GET_TLS();
    rsi_ContextBindProgramFragment(rsc, pf);

}

static void SC_bindProgramVertex(RsProgramVertex pv)
{
    GET_TLS();
    rsi_ContextBindProgramVertex(rsc, pv);

}

//////////////////////////////////////////////////////////////////////////////
// VP
//////////////////////////////////////////////////////////////////////////////

static void SC_vpLoadModelMatrix(const rsc_Matrix *m)
{
    GET_TLS();
    rsc->getVertex()->setModelviewMatrix(m);
}

static void SC_vpLoadTextureMatrix(const rsc_Matrix *m)
{
    GET_TLS();
    rsc->getVertex()->setTextureMatrix(m);
}



//////////////////////////////////////////////////////////////////////////////
// Drawing
//////////////////////////////////////////////////////////////////////////////

static void SC_drawTriangleMesh(RsTriangleMesh mesh)
{
    GET_TLS();
    rsi_TriangleMeshRender(rsc, mesh);
}

static void SC_drawTriangleMeshRange(RsTriangleMesh mesh, uint32_t start, uint32_t count)
{
    GET_TLS();
    rsi_TriangleMeshRenderRange(rsc, mesh, start, count);
}

// Assumes (GL_FIXED) x,y,z (GL_UNSIGNED_BYTE)r,g,b,a
static void SC_drawTriangleArray(int ialloc, uint32_t count)
{
    GET_TLS();
    RsAllocation alloc = (RsAllocation)ialloc;

    const Allocation *a = (const Allocation *)alloc;
    const uint32_t *ptr = (const uint32_t *)a->getPtr();

    rsc->setupCheck();

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    //glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, tm->mBufferObjects[1]);

    glEnableClientState(GL_VERTEX_ARRAY);
    glDisableClientState(GL_NORMAL_ARRAY);
    glDisableClientState(GL_TEXTURE_COORD_ARRAY);
    glEnableClientState(GL_COLOR_ARRAY);

    glVertexPointer(2, GL_FIXED, 12, ptr + 1);
    //glTexCoordPointer(2, GL_FIXED, 24, ptr + 1);
    glColorPointer(4, GL_UNSIGNED_BYTE, 12, ptr);

    glDrawArrays(GL_TRIANGLES, 0, count * 3);
}

static void SC_drawQuad(float x1, float y1, float z1,
                        float x2, float y2, float z2,
                        float x3, float y3, float z3,
                        float x4, float y4, float z4)
{
    GET_TLS();

    //LOGE("Quad");
    //LOGE("%4.2f, %4.2f, %4.2f", x1, y1, z1);
    //LOGE("%4.2f, %4.2f, %4.2f", x2, y2, z2);
    //LOGE("%4.2f, %4.2f, %4.2f", x3, y3, z3);
    //LOGE("%4.2f, %4.2f, %4.2f", x4, y4, z4);

    float vtx[] = {x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4};
    static const float tex[] = {0,1, 1,1, 1,0, 0,0};


    rsc->setupCheck();

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    //glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, tm->mBufferObjects[1]);

    glEnableClientState(GL_VERTEX_ARRAY);
    glVertexPointer(3, GL_FLOAT, 0, vtx);

    glClientActiveTexture(GL_TEXTURE0);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glTexCoordPointer(2, GL_FLOAT, 0, tex);
    glClientActiveTexture(GL_TEXTURE1);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glTexCoordPointer(2, GL_FLOAT, 0, tex);
    glClientActiveTexture(GL_TEXTURE0);

    glDisableClientState(GL_NORMAL_ARRAY);
    glDisableClientState(GL_COLOR_ARRAY);

    //glColorPointer(4, GL_UNSIGNED_BYTE, 12, ptr);

    glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
}

static void SC_drawRect(float x1, float y1,
                        float x2, float y2, float z)
{
    SC_drawQuad(x1, y2, z,
                x2, y2, z,
                x2, y1, z,
                x1, y1, z);
}

//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////

static void SC_color(float r, float g, float b, float a)
{
    glColor4f(r, g, b, a);
}

static void SC_ambient(float r, float g, float b, float a)
{
    GLfloat params[] = { r, g, b, a };
    glMaterialfv(GL_FRONT_AND_BACK, GL_AMBIENT, params);
}

static void SC_diffuse(float r, float g, float b, float a)
{
    GLfloat params[] = { r, g, b, a };
    glMaterialfv(GL_FRONT_AND_BACK, GL_DIFFUSE, params);
}

static void SC_specular(float r, float g, float b, float a)
{
    GLfloat params[] = { r, g, b, a };
    glMaterialfv(GL_FRONT_AND_BACK, GL_SPECULAR, params);
}

static void SC_emission(float r, float g, float b, float a)
{
    GLfloat params[] = { r, g, b, a };
    glMaterialfv(GL_FRONT_AND_BACK, GL_EMISSION, params);
}

static void SC_shininess(float r, float g, float b, float a)
{
    GLfloat params[] = { r, g, b, a };
    glMaterialfv(GL_FRONT_AND_BACK, GL_SHININESS, params);
}

static void SC_hsb(float h, float s, float b, float a)
{
    float red = 0.0f;
    float green = 0.0f;
    float blue = 0.0f;
    
    float x = h;
    float y = s;
    float z = b;
    
    float hf = (x - (int) x) * 6.0f;
    int ihf = (int) hf;
    float f = hf - ihf;
    float pv = z * (1.0f - y);
    float qv = z * (1.0f - y * f);
    float tv = z * (1.0f - y * (1.0f - f));
    
    switch (ihf) {
        case 0:         // Red is the dominant color
            red = z;
            green = tv;
            blue = pv;
            break;
        case 1:         // Green is the dominant color
            red = qv;
            green = z;
            blue = pv;
            break;
        case 2:
            red = pv;
            green = z;
            blue = tv;
            break;
        case 3:         // Blue is the dominant color
            red = pv;
            green = qv;
            blue = z;
            break;
        case 4:
            red = tv;
            green = pv;
            blue = z;
            break;
        case 5:         // Red is the dominant color
            red = z;
            green = pv;
            blue = qv;
            break;
    }
    
    glColor4f(red, green, blue, a);
}

/*
extern "C" void materialDiffuse(float r, float g, float b, float a)
{
    float v[] = {r, g, b, a};
    glMaterialfv(GL_FRONT_AND_BACK, GL_DIFFUSE, v);
}

extern "C" void materialSpecular(float r, float g, float b, float a)
{
    float v[] = {r, g, b, a};
    glMaterialfv(GL_FRONT_AND_BACK, GL_SPECULAR, v);
}

extern "C" void materialShininess(float s)
{
    glMaterialfv(GL_FRONT_AND_BACK, GL_SHININESS, &s);
}
*/

static void SC_uploadToTexture(RsAllocation va, uint32_t baseMipLevel)
{
    GET_TLS();
    rsi_AllocationUploadToTexture(rsc, va, baseMipLevel);
}

static void SC_ClearColor(float r, float g, float b, float a)
{
    //LOGE("c %f %f %f %f", r, g, b, a);
    GET_TLS();
    sc->mEnviroment.mClearColor[0] = r;
    sc->mEnviroment.mClearColor[1] = g;
    sc->mEnviroment.mClearColor[2] = b;
    sc->mEnviroment.mClearColor[3] = a;
}

static void SC_debugF(const char *s, float f)
{
    LOGE("%s %f", s, f);
}

static void SC_debugI32(const char *s, int32_t i)
{
    LOGE("%s %i", s, i);
}



//////////////////////////////////////////////////////////////////////////////
// Class implementation
//////////////////////////////////////////////////////////////////////////////

ScriptCState::SymbolTable_t ScriptCState::gSyms[] = {
    // IO
    { "loadI32", (void *)&SC_loadI32,
        "int", "(int, int)" },
    //{ "loadU32", (void *)&SC_loadU32, "unsigned int", "(int, int)" },
    { "loadF", (void *)&SC_loadF,
        "float", "(int, int)" },
    { "loadArrayF", (void *)&SC_loadArrayF,
        "float*", "(int, int)" },
    { "loadArrayI32", (void *)&SC_loadArrayI32,
        "int*", "(int, int)" },
    { "loadVec4", (void *)&SC_loadVec4,
        "void", "(int, int, float *)" },
    { "loadMatrix", (void *)&SC_loadMatrix,
        "void", "(int, int, float *)" },
    { "storeI32", (void *)&SC_storeI32,
        "void", "(int, int, int)" },
    //{ "storeU32", (void *)&SC_storeU32, "void", "(int, int, unsigned int)" },
    { "storeF", (void *)&SC_storeF,
        "void", "(int, int, float)" },
    { "storeVec4", (void *)&SC_storeVec4,
        "void", "(int, int, float *)" },
    { "storeMatrix", (void *)&SC_storeMatrix,
        "void", "(int, int, float *)" },
    { "loadTriangleMeshVerticesF", (void *)&SC_loadTriangleMeshVerticesF,
        "float*", "(int)" },
    { "updateTriangleMesh", (void *)&SC_updateTriangleMesh,
        "void", "(int)" },

    // math
    { "sinf", (void *)&sinf,
        "float", "(float)" },
    { "cosf", (void *)&cosf,
        "float", "(float)" },
    { "asinf", (void *)&asinf,
        "float", "(float)" },
    { "acosf", (void *)&acosf,
        "float", "(float)" },
    { "atanf", (void *)&atanf,
        "float", "(float)" },
    { "atan2f", (void *)&atan2f,
        "float", "(float, float)" },
    { "fabsf", (void *)&fabsf,
        "float", "(float)" },
    { "randf", (void *)&SC_randf,
        "float", "(float)" },
    { "randf2", (void *)&SC_randf2,
        "float", "(float, float)" },
    { "floorf", (void *)&floorf,
        "float", "(float)" },
    { "ceilf", (void *)&ceilf,
        "float", "(float)" },
    { "expf", (void *)&expf,
        "float", "(float)" },
    { "logf", (void *)&logf,
        "float", "(float)" },
    { "powf", (void *)&powf,
        "float", "(float, float)" },
    { "maxf", (void *)&SC_maxf,
        "float", "(float, float)" },
    { "minf", (void *)&SC_minf,
        "float", "(float, float)" },
    { "sqrtf", (void *)&sqrtf,
        "float", "(float)" },
    { "sqrf", (void *)&SC_sqrf,
        "float", "(float)" },
    { "clampf", (void *)&SC_clampf,
        "float", "(float, float, float)" },
    { "distf2", (void *)&SC_distf2,
        "float", "(float, float, float, float)" },
    { "distf3", (void *)&SC_distf3,
        "float", "(float, float, float, float, float, float)" },
    { "magf2", (void *)&SC_magf2,
        "float", "(float, float)" },
    { "magf3", (void *)&SC_magf3,
        "float", "(float, float, float)" },
    { "radf", (void *)&SC_radf,
        "float", "(float)" },
    { "degf", (void *)&SC_degf,
        "float", "(float)" },
    { "lerpf", (void *)&SC_lerpf,
        "float", "(float, float, float)" },
    { "normf", (void *)&SC_normf,
        "float", "(float, float, float)" },
    { "mapf", (void *)&SC_mapf,
        "float", "(float, float, float, float, float)" },
    { "noisef", (void *)&SC_noisef,
        "float", "(float)" },
    { "noisef2", (void *)&SC_noisef2,
        "float", "(float, float)" },
    { "noisef3", (void *)&SC_noisef3,
        "float", "(float, float, float)" },
    { "turbulencef2", (void *)&SC_turbulencef2,
        "float", "(float, float, float)" },
    { "turbulencef3", (void *)&SC_turbulencef3,
        "float", "(float, float, float, float)" },

    // time
    { "second", (void *)&SC_second,
        "int", "()" },
    { "minute", (void *)&SC_minute,
        "int", "()" },
    { "hour", (void *)&SC_hour,
        "int", "()" },
    { "day", (void *)&SC_day,
        "int", "()" },
    { "month", (void *)&SC_month,
        "int", "()" },
    { "year", (void *)&SC_year,
        "int", "()" },

    // matrix
    { "matrixLoadIdentity", (void *)&SC_matrixLoadIdentity,
        "void", "(float *mat)" },
    { "matrixLoadFloat", (void *)&SC_matrixLoadFloat,
        "void", "(float *mat, float *f)" },
    { "matrixLoadMat", (void *)&SC_matrixLoadMat,
        "void", "(float *mat, float *newmat)" },
    { "matrixLoadRotate", (void *)&SC_matrixLoadRotate,
        "void", "(float *mat, float rot, float x, float y, float z)" },
    { "matrixLoadScale", (void *)&SC_matrixLoadScale,
        "void", "(float *mat, float x, float y, float z)" },
    { "matrixLoadTranslate", (void *)&SC_matrixLoadTranslate,
        "void", "(float *mat, float x, float y, float z)" },
    { "matrixLoadMultiply", (void *)&SC_matrixLoadMultiply,
        "void", "(float *mat, float *lhs, float *rhs)" },
    { "matrixMultiply", (void *)&SC_matrixMultiply,
        "void", "(float *mat, float *rhs)" },
    { "matrixRotate", (void *)&SC_matrixRotate,
        "void", "(float *mat, float rot, float x, float y, float z)" },
    { "matrixScale", (void *)&SC_matrixScale,
        "void", "(float *mat, float x, float y, float z)" },
    { "matrixTranslate", (void *)&SC_matrixTranslate,
        "void", "(float *mat, float x, float y, float z)" },

    // context
    { "bindProgramFragment", (void *)&SC_bindProgramFragment,
        "void", "(int)" },
    { "bindProgramFragmentStore", (void *)&SC_bindProgramFragmentStore,
        "void", "(int)" },
    { "bindProgramVertex", (void *)&SC_bindProgramVertex,
        "void", "(int)" },
    { "bindSampler", (void *)&SC_bindSampler,
        "void", "(int, int, int)" },
    { "bindTexture", (void *)&SC_bindTexture,
        "void", "(int, int, int)" },

    // vp
    { "vpLoadModelMatrix", (void *)&SC_vpLoadModelMatrix,
        "void", "(void *)" },
    { "vpLoadTextureMatrix", (void *)&SC_vpLoadTextureMatrix,
        "void", "(void *)" },



    // drawing
    { "drawRect", (void *)&SC_drawRect,
        "void", "(float x1, float y1, float x2, float y2, float z)" },
    { "drawQuad", (void *)&SC_drawQuad,
        "void", "(float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4)" },
    { "drawTriangleArray", (void *)&SC_drawTriangleArray,
        "void", "(int ialloc, int count)" },
    { "drawTriangleMesh", (void *)&SC_drawTriangleMesh,
        "void", "(int mesh)" },
    { "drawTriangleMeshRange", (void *)&SC_drawTriangleMeshRange,
        "void", "(int mesh, int start, int count)" },


    // misc
    { "pfClearColor", (void *)&SC_ClearColor,
        "void", "(float, float, float, float)" },
    { "color", (void *)&SC_color,
        "void", "(float, float, float, float)" },
    { "hsb", (void *)&SC_hsb,
        "void", "(float, float, float, float)" },
    { "ambient", (void *)&SC_ambient,
        "void", "(float, float, float, float)" },
    { "diffuse", (void *)&SC_diffuse,
        "void", "(float, float, float, float)" },
    { "specular", (void *)&SC_specular,
        "void", "(float, float, float, float)" },
    { "emission", (void *)&SC_emission,
        "void", "(float, float, float, float)" },
    { "shininess", (void *)&SC_shininess,
        "void", "(float, float, float, float)" },

    { "uploadToTexture", (void *)&SC_uploadToTexture,
        "void", "(int, int)" },


    { "debugF", (void *)&SC_debugF,
        "void", "(void *, float)" },
    { "debugI32", (void *)&SC_debugI32,
        "void", "(void *, int)" },


    { NULL, NULL, NULL, NULL }
};

const ScriptCState::SymbolTable_t * ScriptCState::lookupSymbol(const char *sym)
{
    ScriptCState::SymbolTable_t *syms = gSyms;

    while (syms->mPtr) {
        if (!strcmp(syms->mName, sym)) {
            return syms;
        }
        syms++;
    }
    return NULL;
}

void ScriptCState::appendDecls(String8 *str)
{
    ScriptCState::SymbolTable_t *syms = gSyms;
    while (syms->mPtr) {
        str->append(syms->mRet);
        str->append(" ");
        str->append(syms->mName);
        str->append(syms->mParam);
        str->append(";\n");
        syms++;
    }
}


