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

#include "jni.h"
#include "JNIHelp.h"
#include "GraphicsJNI.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include <dlfcn.h>

#include <GLES/gl.h>
#include <ETC1/etc1.h>

#include <SkBitmap.h>

#include "core_jni_helpers.h"

#undef LOG_TAG
#define LOG_TAG "OpenGLUtil"
#include <utils/Log.h>
#include "utils/misc.h"

#include "poly.h"

namespace android {

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
    ALOGI("%s: %d verts", label, pPoly->n);
    for(int i = 0; i < pPoly->n; i++) {
        Poly_vert* pV = & pPoly->vert[i];
        ALOGI("[%d] %g, %g, %g %g", i, pV->sx, pV->sy, pV->sz, pV->sw);
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

class ByteArrayGetter {
public:
    static void* Get(JNIEnv* _env, jbyteArray array, jboolean* is_copy) {
        return _env->GetByteArrayElements(array, is_copy);
    }
};
class BooleanArrayGetter {
public:
    static void* Get(JNIEnv* _env, jbooleanArray array, jboolean* is_copy) {
        return _env->GetBooleanArrayElements(array, is_copy);
    }
};
class CharArrayGetter {
public:
    static void* Get(JNIEnv* _env, jcharArray array, jboolean* is_copy) {
        return _env->GetCharArrayElements(array, is_copy);
    }
};
class ShortArrayGetter {
public:
    static void* Get(JNIEnv* _env, jshortArray array, jboolean* is_copy) {
        return _env->GetShortArrayElements(array, is_copy);
    }
};
class IntArrayGetter {
public:
    static void* Get(JNIEnv* _env, jintArray array, jboolean* is_copy) {
        return _env->GetIntArrayElements(array, is_copy);
    }
};
class LongArrayGetter {
public:
    static void* Get(JNIEnv* _env, jlongArray array, jboolean* is_copy) {
        return _env->GetLongArrayElements(array, is_copy);
    }
};
class FloatArrayGetter {
public:
    static void* Get(JNIEnv* _env, jfloatArray array, jboolean* is_copy) {
        return _env->GetFloatArrayElements(array, is_copy);
    }
};
class DoubleArrayGetter {
public:
    static void* Get(JNIEnv* _env, jdoubleArray array, jboolean* is_copy) {
        return _env->GetDoubleArrayElements(array, is_copy);
    }
};

class ByteArrayReleaser {
public:
    static void Release(JNIEnv* _env, jbyteArray array, jbyte* data, jint mode) {
        _env->ReleaseByteArrayElements(array, data, mode);
    }
};
class BooleanArrayReleaser {
public:
    static void Release(JNIEnv* _env, jbooleanArray array, jboolean* data, jint mode) {
        _env->ReleaseBooleanArrayElements(array, data, mode);
    }
};
class CharArrayReleaser {
public:
    static void Release(JNIEnv* _env, jcharArray array, jchar* data, jint mode) {
        _env->ReleaseCharArrayElements(array, data, mode);
    }
};
class ShortArrayReleaser {
public:
    static void Release(JNIEnv* _env, jshortArray array, jshort* data, jint mode) {
        _env->ReleaseShortArrayElements(array, data, mode);
    }
};
class IntArrayReleaser {
public:
    static void Release(JNIEnv* _env, jintArray array, jint* data, jint mode) {
        _env->ReleaseIntArrayElements(array, data, mode);
    }
};
class LongArrayReleaser {
public:
    static void Release(JNIEnv* _env, jlongArray array, jlong* data, jint mode) {
        _env->ReleaseLongArrayElements(array, data, mode);
    }
};
class FloatArrayReleaser {
public:
    static void Release(JNIEnv* _env, jfloatArray array, jfloat* data, jint mode) {
        _env->ReleaseFloatArrayElements(array, data, mode);
    }
};
class DoubleArrayReleaser {
public:
    static void Release(JNIEnv* _env, jdoubleArray array, jdouble* data, jint mode) {
        _env->ReleaseDoubleArrayElements(array, data, mode);
    }
};

template<class JArray, class T, class ArrayGetter, class ArrayReleaser>
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
            ArrayReleaser::Release(mEnv, mRef, mBase, mReleaseParam);
        }
    }

    // We seperate the bounds check from the initialization because we want to
    // be able to bounds-check multiple arrays, and we can't throw an exception
    // after we've called GetPrimitiveArrayCritical.

    // Return true if the bounds check succeeded
    // Else instruct the runtime to throw an exception

    bool check() {
        if ( ! mRef) {
            doThrowIAE(mEnv, "array == null");
            return false;
        }
        if ( mOffset < 0) {
            doThrowIAE(mEnv, "offset < 0");
            return false;
        }
        mLength = mEnv->GetArrayLength(mRef) - mOffset;
        if (mLength < mMinSize ) {
            doThrowIAE(mEnv, "length - offset < n");
            return false;
        }
        return true;
    }

    // Bind the array.

    void bind() {
        mBase = (T*) ArrayGetter::Get(mEnv, mRef, (jboolean *) 0);
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

typedef ArrayHelper<jfloatArray, float, FloatArrayGetter, FloatArrayReleaser> FloatArrayHelper;
typedef ArrayHelper<jcharArray, unsigned short, CharArrayGetter, CharArrayReleaser> UnsignedShortArrayHelper;
typedef ArrayHelper<jintArray, int, IntArrayGetter, IntArrayReleaser> IntArrayHelper;
typedef ArrayHelper<jbyteArray, unsigned char, ByteArrayGetter, ByteArrayReleaser> ByteArrayHelper;

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
        doThrowIAE(env, "positionsCount < 1");
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
jint util_frustumCullSpheres(JNIEnv *env, jclass clazz,
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
jint util_visibilityTest(JNIEnv *env, jclass clazz,
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
        doThrowIAE(env, "length < offset + indexCount");
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
        const float rhs_i0 = rhs[ I(i,0) ];
        float ri0 = lhs[ I(0,0) ] * rhs_i0;
        float ri1 = lhs[ I(0,1) ] * rhs_i0;
        float ri2 = lhs[ I(0,2) ] * rhs_i0;
        float ri3 = lhs[ I(0,3) ] * rhs_i0;
        for (int j=1 ; j<4 ; j++) {
            const float rhs_ij = rhs[ I(i,j) ];
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

static int checkFormat(SkColorType colorType, int format, int type)
{
    switch(colorType) {
        case kIndex_8_SkColorType:
            if (format == GL_PALETTE8_RGBA8_OES)
                return 0;
        case kN32_SkColorType:
        case kAlpha_8_SkColorType:
            if (type == GL_UNSIGNED_BYTE)
                return 0;
        case kARGB_4444_SkColorType:
        case kRGB_565_SkColorType:
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

static int getInternalFormat(SkColorType colorType)
{
    switch(colorType) {
        case kAlpha_8_SkColorType:
            return GL_ALPHA;
        case kARGB_4444_SkColorType:
            return GL_RGBA;
        case kN32_SkColorType:
            return GL_RGBA;
        case kIndex_8_SkColorType:
            return GL_PALETTE8_RGBA8_OES;
        case kRGB_565_SkColorType:
            return GL_RGB;
        default:
            return -1;
    }
}

static int getType(SkColorType colorType)
{
    switch(colorType) {
        case kAlpha_8_SkColorType:
            return GL_UNSIGNED_BYTE;
        case kARGB_4444_SkColorType:
            return GL_UNSIGNED_SHORT_4_4_4_4;
        case kN32_SkColorType:
            return GL_UNSIGNED_BYTE;
        case kIndex_8_SkColorType:
            return -1; // No type for compressed data.
        case kRGB_565_SkColorType:
            return GL_UNSIGNED_SHORT_5_6_5;
        default:
            return -1;
    }
}

static jint util_getInternalFormat(JNIEnv *env, jclass clazz,
        jobject jbitmap)
{
    SkBitmap nativeBitmap;
    GraphicsJNI::getSkBitmap(env, jbitmap, &nativeBitmap);
    return getInternalFormat(nativeBitmap.colorType());
}

static jint util_getType(JNIEnv *env, jclass clazz,
        jobject jbitmap)
{
    SkBitmap nativeBitmap;
    GraphicsJNI::getSkBitmap(env, jbitmap, &nativeBitmap);
    return getType(nativeBitmap.colorType());
}

static jint util_texImage2D(JNIEnv *env, jclass clazz,
        jint target, jint level, jint internalformat,
        jobject jbitmap, jint type, jint border)
{
    SkBitmap bitmap;
    GraphicsJNI::getSkBitmap(env, jbitmap, &bitmap);
    SkColorType colorType = bitmap.colorType();
    if (internalformat < 0) {
        internalformat = getInternalFormat(colorType);
    }
    if (type < 0) {
        type = getType(colorType);
    }
    int err = checkFormat(colorType, internalformat, type);
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
            memcpy(data, ctable->readColors(), ctable->count() * sizeof(SkPMColor));
            memcpy(pixels, p, size);
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
    SkBitmap bitmap;
    GraphicsJNI::getSkBitmap(env, jbitmap, &bitmap);
    SkColorType colorType = bitmap.colorType();
    if (format < 0) {
        format = getInternalFormat(colorType);
        if (format == GL_PALETTE8_RGBA8_OES)
            return -1; // glCompressedTexSubImage2D() not supported
    }
    int err = checkFormat(colorType, format, type);
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
 * ETC1 methods.
 */

static jclass nioAccessClass;
static jclass bufferClass;
static jmethodID getBasePointerID;
static jmethodID getBaseArrayID;
static jmethodID getBaseArrayOffsetID;
static jfieldID positionID;
static jfieldID limitID;
static jfieldID elementSizeShiftID;

/* Cache method IDs each time the class is loaded. */

static void
nativeClassInitBuffer(JNIEnv *env)
{
    jclass nioAccessClassLocal = FindClassOrDie(env, "java/nio/NIOAccess");
    nioAccessClass = MakeGlobalRefOrDie(env, nioAccessClassLocal);
    getBasePointerID = GetStaticMethodIDOrDie(env, nioAccessClass,
            "getBasePointer", "(Ljava/nio/Buffer;)J");
    getBaseArrayID = GetStaticMethodIDOrDie(env, nioAccessClass,
            "getBaseArray", "(Ljava/nio/Buffer;)Ljava/lang/Object;");
    getBaseArrayOffsetID = GetStaticMethodIDOrDie(env, nioAccessClass,
            "getBaseArrayOffset", "(Ljava/nio/Buffer;)I");

    jclass bufferClassLocal = FindClassOrDie(env, "java/nio/Buffer");
    bufferClass = MakeGlobalRefOrDie(env, bufferClassLocal);
    positionID = GetFieldIDOrDie(env, bufferClass, "position", "I");
    limitID = GetFieldIDOrDie(env, bufferClass, "limit", "I");
    elementSizeShiftID = GetFieldIDOrDie(env, bufferClass, "_elementSizeShift", "I");
}

static void *
getPointer(JNIEnv *_env, jobject buffer, jint *remaining)
{
    jint position;
    jint limit;
    jint elementSizeShift;
    jlong pointer;

    position = _env->GetIntField(buffer, positionID);
    limit = _env->GetIntField(buffer, limitID);
    elementSizeShift = _env->GetIntField(buffer, elementSizeShiftID);
    *remaining = (limit - position) << elementSizeShift;
    pointer = _env->CallStaticLongMethod(nioAccessClass,
            getBasePointerID, buffer);
    if (pointer != 0L) {
        return reinterpret_cast<void *>(pointer);
    }
    return NULL;
}

class BufferHelper {
public:
    BufferHelper(JNIEnv *env, jobject buffer) {
        mEnv = env;
        mBuffer = buffer;
        mData = NULL;
        mRemaining = 0;
    }

    bool checkPointer(const char* errorMessage) {
        if (mBuffer) {
            mData = getPointer(mEnv, mBuffer, &mRemaining);
            if (mData == NULL) {
                doThrowIAE(mEnv, errorMessage);
            }
            return mData != NULL;
        } else {
            doThrowIAE(mEnv, errorMessage);
            return false;
        }
    }

    inline void* getData() {
        return mData;
    }

    inline jint remaining() {
        return mRemaining;
    }

private:
    JNIEnv* mEnv;
    jobject mBuffer;
    void* mData;
    jint mRemaining;
};

/**
 * Encode a block of pixels.
 *
 * @param in a pointer to a ETC1_DECODED_BLOCK_SIZE array of bytes that represent a
 * 4 x 4 square of 3-byte pixels in form R, G, B. Byte (3 * (x + 4 * y) is the R
 * value of pixel (x, y).
 *
 * @param validPixelMask is a 16-bit mask where bit (1 << (x + y * 4)) indicates whether
 * the corresponding (x,y) pixel is valid. Invalid pixel color values are ignored when compressing.
 *
 * @param out an ETC1 compressed version of the data.
 *
 */
static void etc1_encodeBlock(JNIEnv *env, jclass clazz,
        jobject in, jint validPixelMask, jobject out) {
    if (validPixelMask < 0 || validPixelMask > 15) {
        doThrowIAE(env, "validPixelMask");
        return;
    }
    BufferHelper inB(env, in);
    BufferHelper outB(env, out);
    if (inB.checkPointer("in") && outB.checkPointer("out")) {
        if (inB.remaining() < ETC1_DECODED_BLOCK_SIZE) {
            doThrowIAE(env, "in's remaining data < DECODED_BLOCK_SIZE");
        } else if (outB.remaining() < ETC1_ENCODED_BLOCK_SIZE) {
            doThrowIAE(env, "out's remaining data < ENCODED_BLOCK_SIZE");
        } else {
            etc1_encode_block((etc1_byte*) inB.getData(), validPixelMask,
                    (etc1_byte*) outB.getData());
        }
    }
}

/**
 * Decode a block of pixels.
 *
 * @param in an ETC1 compressed version of the data.
 *
 * @param out a pointer to a ETC_DECODED_BLOCK_SIZE array of bytes that represent a
 * 4 x 4 square of 3-byte pixels in form R, G, B. Byte (3 * (x + 4 * y) is the R
 * value of pixel (x, y).
 */
static void etc1_decodeBlock(JNIEnv *env, jclass clazz,
        jobject in, jobject out){
    BufferHelper inB(env, in);
    BufferHelper outB(env, out);
    if (inB.checkPointer("in") && outB.checkPointer("out")) {
        if (inB.remaining() < ETC1_ENCODED_BLOCK_SIZE) {
            doThrowIAE(env, "in's remaining data < ENCODED_BLOCK_SIZE");
        } else if (outB.remaining() < ETC1_DECODED_BLOCK_SIZE) {
            doThrowIAE(env, "out's remaining data < DECODED_BLOCK_SIZE");
        } else {
            etc1_decode_block((etc1_byte*) inB.getData(),
                    (etc1_byte*) outB.getData());
        }
    }
}

/**
 * Return the size of the encoded image data (does not include size of PKM header).
 */
static jint etc1_getEncodedDataSize(JNIEnv *env, jclass clazz,
        jint width, jint height) {
    return etc1_get_encoded_data_size(width, height);
}

/**
 * Encode an entire image.
 * @param in pointer to the image data. Formatted such that
 *           pixel (x,y) is at pIn + pixelSize * x + stride * y + redOffset;
 * @param out pointer to encoded data. Must be large enough to store entire encoded image.
 */
static void etc1_encodeImage(JNIEnv *env, jclass clazz,
        jobject in, jint width, jint height,
        jint pixelSize, jint stride, jobject out) {
    if (pixelSize < 2 || pixelSize > 3) {
        doThrowIAE(env, "pixelSize must be 2 or 3");
        return;
    }
    BufferHelper inB(env, in);
    BufferHelper outB(env, out);
    if (inB.checkPointer("in") && outB.checkPointer("out")) {
        jint imageSize = stride * height;
        jint encodedImageSize = etc1_get_encoded_data_size(width, height);
        if (inB.remaining() < imageSize) {
            doThrowIAE(env, "in's remaining data < image size");
        } else if (outB.remaining() < encodedImageSize) {
            doThrowIAE(env, "out's remaining data < encoded image size");
        } else {
            etc1_encode_image((etc1_byte*) inB.getData(), width, height, pixelSize, stride,
                              (etc1_byte*) outB.getData());
        }
    }
}

/**
 * Decode an entire image.
 * @param in the encoded data.
 * @param out pointer to the image data. Will be written such that
 *            pixel (x,y) is at pIn + pixelSize * x + stride * y. Must be
 *            large enough to store entire image.
 */
static void etc1_decodeImage(JNIEnv *env, jclass clazz,
        jobject  in, jobject out,
        jint width, jint height,
        jint pixelSize, jint stride) {
    if (pixelSize < 2 || pixelSize > 3) {
        doThrowIAE(env, "pixelSize must be 2 or 3");
        return;
    }
    BufferHelper inB(env, in);
    BufferHelper outB(env, out);
    if (inB.checkPointer("in") && outB.checkPointer("out")) {
        jint imageSize = stride * height;
        jint encodedImageSize = etc1_get_encoded_data_size(width, height);
        if (inB.remaining() < encodedImageSize) {
            doThrowIAE(env, "in's remaining data < encoded image size");
        } else if (outB.remaining() < imageSize) {
            doThrowIAE(env, "out's remaining data < image size");
        } else {
            etc1_decode_image((etc1_byte*) inB.getData(), (etc1_byte*) outB.getData(),
                              width, height, pixelSize, stride);
        }
    }
}

/**
 * Format a PKM header
 */
static void etc1_formatHeader(JNIEnv *env, jclass clazz,
        jobject header, jint width, jint height) {
    BufferHelper headerB(env, header);
    if (headerB.checkPointer("header") ){
        if (headerB.remaining() < ETC_PKM_HEADER_SIZE) {
            doThrowIAE(env, "header's remaining data < ETC_PKM_HEADER_SIZE");
        } else {
            etc1_pkm_format_header((etc1_byte*) headerB.getData(), width, height);
        }
    }
}

/**
 * Check if a PKM header is correctly formatted.
 */
static jboolean etc1_isValid(JNIEnv *env, jclass clazz,
        jobject header) {
    jboolean result = false;
    BufferHelper headerB(env, header);
    if (headerB.checkPointer("header") ){
        if (headerB.remaining() < ETC_PKM_HEADER_SIZE) {
            doThrowIAE(env, "header's remaining data < ETC_PKM_HEADER_SIZE");
        } else {
            result = etc1_pkm_is_valid((etc1_byte*) headerB.getData());
        }
    }
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Read the image width from a PKM header
 */
static jint etc1_getWidth(JNIEnv *env, jclass clazz,
        jobject header) {
    jint result = 0;
    BufferHelper headerB(env, header);
    if (headerB.checkPointer("header") ){
        if (headerB.remaining() < ETC_PKM_HEADER_SIZE) {
            doThrowIAE(env, "header's remaining data < ETC_PKM_HEADER_SIZE");
        } else {
            result = etc1_pkm_get_width((etc1_byte*) headerB.getData());
        }
    }
    return result;
}

/**
 * Read the image height from a PKM header
 */
static jint etc1_getHeight(JNIEnv *env, jclass clazz,
        jobject header) {
    jint result = 0;
    BufferHelper headerB(env, header);
    if (headerB.checkPointer("header") ){
        if (headerB.remaining() < ETC_PKM_HEADER_SIZE) {
            doThrowIAE(env, "header's remaining data < ETC_PKM_HEADER_SIZE");
        } else {
            result = etc1_pkm_get_height((etc1_byte*) headerB.getData());
        }
    }
    return result;
}

/*
 * JNI registration
 */

static const JNINativeMethod gMatrixMethods[] = {
    { "multiplyMM", "!([FI[FI[FI)V", (void*)util_multiplyMM },
    { "multiplyMV", "!([FI[FI[FI)V", (void*)util_multiplyMV },
};

static const JNINativeMethod gVisibilityMethods[] = {
    { "computeBoundingSphere", "([FII[FI)V", (void*)util_computeBoundingSphere },
    { "frustumCullSpheres", "([FI[FII[III)I", (void*)util_frustumCullSpheres },
    { "visibilityTest", "([FI[FI[CII)I", (void*)util_visibilityTest },
};

static const JNINativeMethod gUtilsMethods[] = {
    { "native_getInternalFormat", "(Landroid/graphics/Bitmap;)I", (void*) util_getInternalFormat },
    { "native_getType", "(Landroid/graphics/Bitmap;)I", (void*) util_getType },
    { "native_texImage2D", "(IIILandroid/graphics/Bitmap;II)I", (void*)util_texImage2D },
    { "native_texSubImage2D", "(IIIILandroid/graphics/Bitmap;II)I", (void*)util_texSubImage2D },
};

static const JNINativeMethod gEtc1Methods[] = {
    { "encodeBlock", "(Ljava/nio/Buffer;ILjava/nio/Buffer;)V", (void*) etc1_encodeBlock },
    { "decodeBlock", "(Ljava/nio/Buffer;Ljava/nio/Buffer;)V", (void*) etc1_decodeBlock },
    { "getEncodedDataSize", "(II)I", (void*) etc1_getEncodedDataSize },
    { "encodeImage", "(Ljava/nio/Buffer;IIIILjava/nio/Buffer;)V", (void*) etc1_encodeImage },
    { "decodeImage", "(Ljava/nio/Buffer;Ljava/nio/Buffer;IIII)V", (void*) etc1_decodeImage },
    { "formatHeader", "(Ljava/nio/Buffer;II)V", (void*) etc1_formatHeader },
    { "isValid", "(Ljava/nio/Buffer;)Z", (void*) etc1_isValid },
    { "getWidth", "(Ljava/nio/Buffer;)I", (void*) etc1_getWidth },
    { "getHeight", "(Ljava/nio/Buffer;)I", (void*) etc1_getHeight },
};

typedef struct _ClassRegistrationInfo {
    const char* classPath;
    const JNINativeMethod* methods;
    size_t methodCount;
} ClassRegistrationInfo;

static const ClassRegistrationInfo gClasses[] = {
    {"android/opengl/Matrix", gMatrixMethods, NELEM(gMatrixMethods)},
    {"android/opengl/Visibility", gVisibilityMethods, NELEM(gVisibilityMethods)},
    {"android/opengl/GLUtils", gUtilsMethods, NELEM(gUtilsMethods)},
    {"android/opengl/ETC1", gEtc1Methods, NELEM(gEtc1Methods)},
};

int register_android_opengl_classes(JNIEnv* env)
{
    nativeClassInitBuffer(env);
    int result = 0;
    for (int i = 0; i < NELEM(gClasses); i++) {
        const ClassRegistrationInfo* cri = &gClasses[i];
        result = RegisterMethodsOrDie(env, cri->classPath, cri->methods, cri->methodCount);
    }
    return result;
}

} // namespace android
