/**
 ** Copyright 2007, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

#include <nativehelper/jni.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include <dlfcn.h>

#include <GLES/gl.h>

#include <core/SkBitmap.h>

#include "android_runtime/AndroidRuntime.h"

#undef LOG_TAG
#define LOG_TAG "OpenGLUtil"
#include <utils/Log.h>
#include "utils/misc.h"

#include "poly.h"

namespace android {

static jclass gIAEClass;
static jclass gUOEClass;

static inline
void mx4transform(float x, float y, float z, float w, const float* pM, float* pDest) {
    pDest[0] = pM[0 + 4 * 0] * x + pM[0 + 4 * 1] * y + pM[0 + 4 * 2] * z + pM[0 + 4 * 3] * w;
    pDest[1] = pM[1 + 4 * 0] * x + pM[1 + 4 * 1] * y + pM[1 + 4 * 2] * z + pM[1 + 4 * 3] * w;
    pDest[2] = pM[2 + 4 * 0] * x + pM[2 + 4 * 1] * y + pM[2 + 4 * 2] * z + pM[2 + 4 * 3] * w;
    pDest[3] = pM[3 + 4 * 0] * x + pM[3 + 4 * 1] * y + pM[3 + 4 * 2] * z + pM[3 + 4 * 3] * w;
}

class MallocHelper {
public:
    MallocHelper() {
        mData = 0;
    }

    ~MallocHelper() {
        if (mData != 0) {
            free(mData);
        }
    }

    void* alloc(size_t size) {
        mData = malloc(size);
        return mData;
    }

private:
    void* mData;
};

#if 0
static
void
print_poly(const char* label, Poly* pPoly) {
    LOGI("%s: %d verts", label, pPoly->n);
    for(int i = 0; i < pPoly->n; i++) {
        Poly_vert* pV = & pPoly->vert[i];
        LOGI("[%d] %g, %g, %g %g", i, pV->sx, pV->sy, pV->sz, pV->sw);
    }
}
#endif

static
int visibilityTest(float* pWS, float* pPositions, int positionsLength,
        unsigned short* pIndices, int indexCount) {
    MallocHelper mallocHelper;
    int result = POLY_CLIP_OUT;
    float* pTransformed = 0;
    int transformedIndexCount = 0;

    if ( indexCount < 3 ) {
        return POLY_CLIP_OUT;
    }

    // Find out how many vertices we need to transform
    // We transform every vertex between the min and max indices, inclusive.
    // This is OK for the data sets we expect to use with this function, but
    // for other loads it might be better to use a more sophisticated vertex
    // cache of some sort.

    int minIndex = 65536;
    int maxIndex = -1;
    for(int i = 0; i < indexCount; i++) {
        int index = pIndices[i];
        if ( index < minIndex ) {
            minIndex = index;
        }
        if ( index > maxIndex ) {
            maxIndex = index;
        }
    }

    if ( maxIndex * 3 > positionsLength) {
        return -1;
    }

    transformedIndexCount = maxIndex - minIndex + 1;
    pTransformed = (float*) mallocHelper.alloc(transformedIndexCount * 4 * sizeof(float));

    if (pTransformed == 0 ) {
        return -2;
    }

    // Transform the vertices
    {
        const float* pSrc = pPositions + 3 * minIndex;
        float* pDst = pTransformed;
        for (int i = 0; i < transformedIndexCount; i++, pSrc += 3, pDst += 4) {
            mx4transform(pSrc[0], pSrc[1], pSrc[2], 1.0f, pWS,  pDst);
        }
    }

    // Clip the triangles

    Poly poly;
    float* pDest = & poly.vert[0].sx;
    for (int i = 0; i < indexCount; i += 3) {
        poly.n = 3;
        memcpy(pDest    , pTransformed + 4 * (pIndices[i    ] - minIndex), 4 * sizeof(float));
        memcpy(pDest + 4, pTransformed + 4 * (pIndices[i + 1] - minIndex), 4 * sizeof(float));
        memcpy(pDest + 8, pTransformed + 4 * (pIndices[i + 2] - minIndex), 4 * sizeof(float));
        result = poly_clip_to_frustum(&poly);
        if ( result != POLY_CLIP_OUT) {
            return result;
        }
    }

    return result;
}

template<class JArray, class T>
class ArrayHelper {
public:
    ArrayHelper(JNIEnv* env, JArray ref, jint offset, jint minSize) {
        mEnv = env;
        mRef = ref;
        mOffset = offset;
        mMinSize = minSize;
        mBase = 0;
        mReleaseParam = JNI_ABORT;
    }

    ~ArrayHelper() {
        if (mBase) {
            mEnv->ReleasePrimitiveArrayCritical(mRef, mBase, mReleaseParam);
        }
    }

    // We seperate the bounds check from the initialization because we want to
    // be able to bounds-check multiple arrays, and we can't throw an exception
    // after we've called GetPrimitiveArrayCritical.

    // Return true if the bounds check succeeded
    // Else instruct the runtime to throw an exception

    bool check() {
        if ( ! mRef) {
            mEnv->ThrowNew(gIAEClass, "array == null");
            return false;
        }
        if ( mOffset < 0) {
            mEnv->ThrowNew(gIAEClass, "offset < 0");
            return false;
        }
        mLength = mEnv->GetArrayLength(mRef) - mOffset;
        if (mLength < mMinSize ) {
            mEnv->ThrowNew(gIAEClass, "length - offset < n");
            return false;
        }
        return true;
    }

    // Bind the array.

    void bind() {
        mBase = (T*) mEnv->GetPrimitiveArrayCritical(mRef, (jboolean *) 0);
        mData = mBase + mOffset;
    }

    void commitChanges() {
        mReleaseParam = 0;
    }

    T* mData;
    int mLength;

private:
    T* mBase;
    JNIEnv* mEnv;
    JArray mRef;
    jint mOffset;
    jint mMinSize;
    int mReleaseParam;
};

typedef ArrayHelper<jfloatArray, float> FloatArrayHelper;
typedef ArrayHelper<jcharArray, unsigned short> UnsignedShortArrayHelper;
typedef ArrayHelper<jintArray, int> IntArrayHelper;
typedef ArrayHelper<jbyteArray, unsigned char> ByteArrayHelper;

inline float distance2(float x, float y, float z) {
    return x * x + y * y + z * z;
}

inline float distance(float x, float y, float z) {
    return sqrtf(distance2(x, y, z));
}

static
void util_computeBoundingSphere(JNIEnv *env, jclass clazz,
        jfloatArray positions_ref, jint positionsOffset, jint positionsCount,
        jfloatArray sphere_ref, jint sphereOffset) {
    FloatArrayHelper positions(env, positions_ref, positionsOffset, 0);
    FloatArrayHelper sphere(env, sphere_ref, sphereOffset, 4);

    bool checkOK = positions.check() && sphere.check();
        if (! checkOK) {
        return;
    }

    positions.bind();
    sphere.bind();

    if ( positionsCount < 1 ) {
        env->ThrowNew(gIAEClass, "positionsCount < 1");
        return;
    }

    const float* pSrc = positions.mData;

    // find bounding box
    float x0 = *pSrc++;
    float x1 = x0;
    float y0 = *pSrc++;
    float y1 = y0;
    float z0 = *pSrc++;
    float z1 = z0;

    for(int i = 1; i < positionsCount; i++) {
        {
            float x = *pSrc++;
            if (x < x0) {
                x0 = x;
            }
            else if (x > x1) {
                x1 = x;
            }
        }
        {
            float y = *pSrc++;
            if (y < y0) {
                y0 = y;
            }
            else if (y > y1) {
                y1 = y;
            }
        }
        {
            float z = *pSrc++;
            if (z < z0) {
                z0 = z;
            }
            else if (z > z1) {
                z1 = z;
            }
        }
    }

    // Because we know our input meshes fit pretty well into bounding boxes,
    // just take the diagonal of the box as defining our sphere.
    float* pSphere = sphere.mData;
    float dx = x1 - x0;
    float dy = y1 - y0;
    float dz = z1 - z0;
    *pSphere++ = x0 + dx * 0.5f;
    *pSphere++ = y0 + dy * 0.5f;
    *pSphere++ = z0 + dz * 0.5f;
    *pSphere++ = distance(dx, dy, dz) * 0.5f;

    sphere.commitChanges();
}

static void normalizePlane(float* p) {
    float rdist = 1.0f / distance(p[0], p[1], p[2]);
    for(int i = 0; i < 4; i++) {
        p[i] *= rdist;
    }
}

static inline float dot3(float x0, float y0, float z0, float x1, float y1, float z1) {
    return x0 * x1 + y0 * y1 + z0 * z1;
}

static inline float signedDistance(const float* pPlane, float x, float y, float z) {
    return dot3(pPlane[0], pPlane[1], pPlane[2], x, y, z) + pPlane[3];
}

// Return true if the sphere intersects or is inside the frustum

static bool sphereHitsFrustum(const float* pFrustum, const float* pSphere) {
    float x = pSphere[0];
    float y = pSphere[1];
    float z = pSphere[2];
    float negRadius = -pSphere[3];
    for (int i = 0; i < 6; i++, pFrustum += 4) {
        if (signedDistance(pFrustum, x, y, z) <= negRadius) {
            return false;
        }
    }
    return true;
}

static void computeFrustum(const float* m, float* f) {
    float m3 = m[3];
    float m7 = m[7];
    float m11 = m[11];
    float m15 = m[15];
    // right
    f[0] = m3  - m[0];
    f[1] = m7  - m[4];
    f[2] = m11 - m[8];
    f[3] = m15 - m[12];
    normalizePlane(f);
    f+= 4;

    // left
    f[0] = m3  + m[0];
    f[1] = m7  + m[4];
    f[2] = m11 + m[8];
    f[3] = m15 + m[12];
    normalizePlane(f);
    f+= 4;

    // top
    f[0] = m3  - m[1];
    f[1] = m7  - m[5];
    f[2] = m11 - m[9];
    f[3] = m15 - m[13];
    normalizePlane(f);
    f+= 4;

    // bottom
    f[0] = m3  + m[1];
    f[1] = m7  + m[5];
    f[2] = m11 + m[9];
    f[3] = m15 + m[13];
    normalizePlane(f);
    f+= 4;

    // far
    f[0] = m3  - m[2];
    f[1] = m7  - m[6];
    f[2] = m11 - m[10];
    f[3] = m15 - m[14];
    normalizePlane(f);
    f+= 4;

    // near
    f[0] = m3  + m[2];
    f[1] = m7  + m[6];
    f[2] = m11 + m[10];
    f[3] = m15 + m[14];
    normalizePlane(f);
}

static
int util_frustumCullSpheres(JNIEnv *env, jclass clazz,
        jfloatArray mvp_ref, jint mvpOffset,
        jfloatArray spheres_ref, jint spheresOffset, jint spheresCount,
        jintArray results_ref, jint resultsOffset, jint resultsCapacity) {
    float frustum[6*4];
    int outputCount;
    int* pResults;
    float* pSphere;
    FloatArrayHelper mvp(env, mvp_ref, mvpOffset, 16);
    FloatArrayHelper spheres(env, spheres_ref, spheresOffset, spheresCount * 4);
    IntArrayHelper results(env, results_ref, resultsOffset, resultsCapacity);

    bool initializedOK = mvp.check() && spheres.check() && results.check();
        if (! initializedOK) {
        return -1;
    }

    mvp.bind();
    spheres.bind();
    results.bind();

    computeFrustum(mvp.mData, frustum);

    // Cull the spheres

    pSphere = spheres.mData;
    pResults = results.mData;
    outputCount = 0;
    for(int i = 0; i < spheresCount; i++, pSphere += 4) {
        if (sphereHitsFrustum(frustum, pSphere)) {
            if (outputCount < resultsCapacity) {
                *pResults++ = i;
            }
            outputCount++;
        }
    }
    results.commitChanges();
    return outputCount;
}

/*
 public native int visibilityTest(float[] ws, int wsOffset,
 float[] positions, int positionsOffset,
 char[] indices, int indicesOffset, int indexCount);
 */

static
int util_visibilityTest(JNIEnv *env, jclass clazz,
        jfloatArray ws_ref, jint wsOffset,
        jfloatArray positions_ref, jint positionsOffset,
        jcharArray indices_ref, jint indicesOffset, jint indexCount) {

    FloatArrayHelper ws(env, ws_ref, wsOffset, 16);
    FloatArrayHelper positions(env, positions_ref, positionsOffset, 0);
    UnsignedShortArrayHelper indices(env, indices_ref, indicesOffset, 0);

    bool checkOK = ws.check() && positions.check() && indices.check();
    if (! checkOK) {
        // Return value will be ignored, because an exception has been thrown.
        return -1;
    }

    if (indices.mLength < indexCount) {
        env->ThrowNew(gIAEClass, "length < offset + indexCount");
        // Return value will be ignored, because an exception has been thrown.
        return -1;
    }

    ws.bind();
    positions.bind();
    indices.bind();

    return visibilityTest(ws.mData,
            positions.mData, positions.mLength,
            indices.mData, indexCount);
}

#define I(_i, _j) ((_j)+ 4*(_i))

static
void multiplyMM(float* r, const float* lhs, const float* rhs)
{
    for (int i=0 ; i<4 ; i++) {
        register const float rhs_i0 = rhs[ I(i,0) ];
        register float ri0 = lhs[ I(0,0) ] * rhs_i0;
        register float ri1 = lhs[ I(0,1) ] * rhs_i0;
        register float ri2 = lhs[ I(0,2) ] * rhs_i0;
        register float ri3 = lhs[ I(0,3) ] * rhs_i0;
        for (int j=1 ; j<4 ; j++) {
            register const float rhs_ij = rhs[ I(i,j) ];
            ri0 += lhs[ I(j,0) ] * rhs_ij;
            ri1 += lhs[ I(j,1) ] * rhs_ij;
            ri2 += lhs[ I(j,2) ] * rhs_ij;
            ri3 += lhs[ I(j,3) ] * rhs_ij;
        }
        r[ I(i,0) ] = ri0;
        r[ I(i,1) ] = ri1;
        r[ I(i,2) ] = ri2;
        r[ I(i,3) ] = ri3;
    }
}

static
void util_multiplyMM(JNIEnv *env, jclass clazz,
    jfloatArray result_ref, jint resultOffset,
    jfloatArray lhs_ref, jint lhsOffset,
    jfloatArray rhs_ref, jint rhsOffset) {

    FloatArrayHelper resultMat(env, result_ref, resultOffset, 16);
    FloatArrayHelper lhs(env, lhs_ref, lhsOffset, 16);
    FloatArrayHelper rhs(env, rhs_ref, rhsOffset, 16);

    bool checkOK = resultMat.check() && lhs.check() && rhs.check();

    if ( !checkOK ) {
        return;
    }

    resultMat.bind();
    lhs.bind();
    rhs.bind();

    multiplyMM(resultMat.mData, lhs.mData, rhs.mData);

    resultMat.commitChanges();
}

static
void multiplyMV(float* r, const float* lhs, const float* rhs)
{
    mx4transform(rhs[0], rhs[1], rhs[2], rhs[3], lhs, r);
}

static
void util_multiplyMV(JNIEnv *env, jclass clazz,
    jfloatArray result_ref, jint resultOffset,
    jfloatArray lhs_ref, jint lhsOffset,
    jfloatArray rhs_ref, jint rhsOffset) {

    FloatArrayHelper resultV(env, result_ref, resultOffset, 4);
    FloatArrayHelper lhs(env, lhs_ref, lhsOffset, 16);
    FloatArrayHelper rhs(env, rhs_ref, rhsOffset, 4);

    bool checkOK = resultV.check() && lhs.check() && rhs.check();

    if ( !checkOK ) {
        return;
    }

    resultV.bind();
    lhs.bind();
    rhs.bind();

    multiplyMV(resultV.mData, lhs.mData, rhs.mData);

    resultV.commitChanges();
}

// ---------------------------------------------------------------------------

static jfieldID nativeBitmapID = 0;

void nativeUtilsClassInit(JNIEnv *env, jclass clazz)
{
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    nativeBitmapID = env->GetFieldID(bitmapClass, "mNativeBitmap", "I");
}

static int checkFormat(SkBitmap::Config config, int format, int type)
{
    switch(config) {
        case SkBitmap::kIndex8_Config:
            if (format == GL_PALETTE8_RGBA8_OES)
                return 0;
        case SkBitmap::kARGB_8888_Config:
        case SkBitmap::kA8_Config:
            if (type == GL_UNSIGNED_BYTE)
                return 0;
        case SkBitmap::kARGB_4444_Config:
        case SkBitmap::kRGB_565_Config:
            switch (type) {
                case GL_UNSIGNED_SHORT_4_4_4_4:
                case GL_UNSIGNED_SHORT_5_6_5:
                case GL_UNSIGNED_SHORT_5_5_5_1:
                    return 0;
                case GL_UNSIGNED_BYTE:
                    if (format == GL_LUMINANCE_ALPHA)
                        return 0;
            }
            break;
        default:
            break;
    }
    return -1;
}

static int getInternalFormat(SkBitmap::Config config)
{
    switch(config) {
        case SkBitmap::kA8_Config:
            return GL_ALPHA;
        case SkBitmap::kARGB_4444_Config:
            return GL_RGBA;
        case SkBitmap::kARGB_8888_Config:
            return GL_RGBA;
        case SkBitmap::kIndex8_Config:
            return GL_PALETTE8_RGBA8_OES;
        case SkBitmap::kRGB_565_Config:
            return GL_RGB;
        default:
            return -1;
    }
}

static int getType(SkBitmap::Config config)
{
    switch(config) {
        case SkBitmap::kA8_Config:
            return GL_UNSIGNED_BYTE;
        case SkBitmap::kARGB_4444_Config:
            return GL_UNSIGNED_SHORT_4_4_4_4;
        case SkBitmap::kARGB_8888_Config:
            return GL_UNSIGNED_BYTE;
        case SkBitmap::kIndex8_Config:
            return -1; // No type for compressed data.
        case SkBitmap::kRGB_565_Config:
            return GL_UNSIGNED_SHORT_5_6_5;
        default:
            return -1;
    }
}

static jint util_getInternalFormat(JNIEnv *env, jclass clazz,
        jobject jbitmap)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)env->GetIntField(jbitmap, nativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);
    SkBitmap::Config config = bitmap.getConfig();
    return getInternalFormat(config);
}

static jint util_getType(JNIEnv *env, jclass clazz,
        jobject jbitmap)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)env->GetIntField(jbitmap, nativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);
    SkBitmap::Config config = bitmap.getConfig();
    return getType(config);
}

static jint util_texImage2D(JNIEnv *env, jclass clazz,
        jint target, jint level, jint internalformat,
        jobject jbitmap, jint type, jint border)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)env->GetIntField(jbitmap, nativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);
    SkBitmap::Config config = bitmap.getConfig();
    if (internalformat < 0) {
        internalformat = getInternalFormat(config);
    }
    if (type < 0) {
        type = getType(config);
    }
    int err = checkFormat(config, internalformat, type);
    if (err)
        return err;
    bitmap.lockPixels();
    const int w = bitmap.width();
    const int h = bitmap.height();
    const void* p = bitmap.getPixels();
    if (internalformat == GL_PALETTE8_RGBA8_OES) {
        if (sizeof(SkPMColor) != sizeof(uint32_t)) {
            err = -1;
            goto error;
        }
        const size_t size = bitmap.getSize();
        const size_t palette_size = 256*sizeof(SkPMColor);
        const size_t imageSize = size + palette_size;
        void* const data = malloc(imageSize);
        if (data) {
            void* const pixels = (char*)data + palette_size;
            SkColorTable* ctable = bitmap.getColorTable();
            memcpy(data, ctable->lockColors(), ctable->count() * sizeof(SkPMColor));
            memcpy(pixels, p, size);
            ctable->unlockColors(false);
            glCompressedTexImage2D(target, level, internalformat, w, h, border, imageSize, data);
            free(data);
        } else {
            err = -1;
        }
    } else {
        glTexImage2D(target, level, internalformat, w, h, border, internalformat, type, p);
    }
error:
    bitmap.unlockPixels();
    return err;
}

static jint util_texSubImage2D(JNIEnv *env, jclass clazz,
        jint target, jint level, jint xoffset, jint yoffset,
        jobject jbitmap, jint format, jint type)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)env->GetIntField(jbitmap, nativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);
    SkBitmap::Config config = bitmap.getConfig();
    if (format < 0) {
        format = getInternalFormat(config);
        if (format == GL_PALETTE8_RGBA8_OES)
            return -1; // glCompressedTexSubImage2D() not supported
    }
    int err = checkFormat(config, format, type);
    if (err)
        return err;
    bitmap.lockPixels();
    const int w = bitmap.width();
    const int h = bitmap.height();
    const void* p = bitmap.getPixels();
    glTexSubImage2D(target, level, xoffset, yoffset, w, h, format, type, p);
    bitmap.unlockPixels();
    return 0;
}

/*
 * JNI registration
 */

static void
lookupClasses(JNIEnv* env) {
    gIAEClass = (jclass) env->NewGlobalRef(
            env->FindClass("java/lang/IllegalArgumentException"));
    gUOEClass = (jclass) env->NewGlobalRef(
            env->FindClass("java/lang/UnsupportedOperationException"));
}

static JNINativeMethod gMatrixMethods[] = {
    { "multiplyMM", "([FI[FI[FI)V", (void*)util_multiplyMM },
    { "multiplyMV", "([FI[FI[FI)V", (void*)util_multiplyMV },
};

static JNINativeMethod gVisiblityMethods[] = {
    { "computeBoundingSphere", "([FII[FI)V", (void*)util_computeBoundingSphere },
    { "frustumCullSpheres", "([FI[FII[III)I", (void*)util_frustumCullSpheres },
    { "visibilityTest", "([FI[FI[CII)I", (void*)util_visibilityTest },
};

static JNINativeMethod gUtilsMethods[] = {
    {"nativeClassInit", "()V",                          (void*)nativeUtilsClassInit },
    { "native_getInternalFormat", "(Landroid/graphics/Bitmap;)I", (void*) util_getInternalFormat },
    { "native_getType", "(Landroid/graphics/Bitmap;)I", (void*) util_getType },
    { "native_texImage2D", "(IIILandroid/graphics/Bitmap;II)I", (void*)util_texImage2D },
    { "native_texSubImage2D", "(IIIILandroid/graphics/Bitmap;II)I", (void*)util_texSubImage2D },
};

typedef struct _ClassRegistrationInfo {
    const char* classPath;
    JNINativeMethod* methods;
    size_t methodCount;
} ClassRegistrationInfo;

static ClassRegistrationInfo gClasses[] = {
        {"android/opengl/Matrix", gMatrixMethods, NELEM(gMatrixMethods)},
        {"android/opengl/Visibility", gVisiblityMethods, NELEM(gVisiblityMethods)},
        {"android/opengl/GLUtils", gUtilsMethods, NELEM(gUtilsMethods)},
};

int register_android_opengl_classes(JNIEnv* env)
{
    lookupClasses(env);
    int result = 0;
    for (int i = 0; i < NELEM(gClasses); i++) {
        ClassRegistrationInfo* cri = &gClasses[i];
        result = AndroidRuntime::registerNativeMethods(env,
                cri->classPath, cri->methods, cri->methodCount);
        if (result < 0) {
            LOGE("Failed to register %s: %d", cri->classPath, result);
            break;
        }
    }
    return result;
}

} // namespace android

