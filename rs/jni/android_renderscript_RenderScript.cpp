/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

#define LOG_TAG "libRS_jni"

#include <stdlib.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <math.h>
#include <utils/misc.h>

#include <core/SkBitmap.h>
#include <core/SkPixelRef.h>
#include <core/SkStream.h>
#include <core/SkTemplates.h>

#include <androidfw/Asset.h>
#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"
#include "android_runtime/android_util_AssetManager.h"

#include <rs.h>
#include <rsEnv.h>
#include <gui/Surface.h>
#include <gui/GLConsumer.h>
#include <gui/Surface.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>

//#define LOG_API ALOGE
#define LOG_API(...)

using namespace android;

#define PER_ARRAY_TYPE(flag, fnc, readonly, ...) {                                      \
    jint len = 0;                                                                       \
    void *ptr = nullptr;                                                                \
    size_t typeBytes = 0;                                                               \
    jint relFlag = 0;                                                                   \
    if (readonly) {                                                                     \
        /* The on-release mode should only be JNI_ABORT for read-only accesses. */      \
        relFlag = JNI_ABORT;                                                            \
    }                                                                                   \
    switch(dataType) {                                                                  \
    case RS_TYPE_FLOAT_32:                                                              \
        len = _env->GetArrayLength((jfloatArray)data);                                  \
        ptr = _env->GetFloatArrayElements((jfloatArray)data, flag);                     \
        typeBytes = 4;                                                                  \
        fnc(__VA_ARGS__);                                                               \
        _env->ReleaseFloatArrayElements((jfloatArray)data, (jfloat *)ptr, relFlag);     \
        return;                                                                         \
    case RS_TYPE_FLOAT_64:                                                              \
        len = _env->GetArrayLength((jdoubleArray)data);                                 \
        ptr = _env->GetDoubleArrayElements((jdoubleArray)data, flag);                   \
        typeBytes = 8;                                                                  \
        fnc(__VA_ARGS__);                                                               \
        _env->ReleaseDoubleArrayElements((jdoubleArray)data, (jdouble *)ptr, relFlag);  \
        return;                                                                         \
    case RS_TYPE_SIGNED_8:                                                              \
    case RS_TYPE_UNSIGNED_8:                                                            \
        len = _env->GetArrayLength((jbyteArray)data);                                   \
        ptr = _env->GetByteArrayElements((jbyteArray)data, flag);                       \
        typeBytes = 1;                                                                  \
        fnc(__VA_ARGS__);                                                               \
        _env->ReleaseByteArrayElements((jbyteArray)data, (jbyte*)ptr, relFlag);         \
        return;                                                                         \
    case RS_TYPE_SIGNED_16:                                                             \
    case RS_TYPE_UNSIGNED_16:                                                           \
        len = _env->GetArrayLength((jshortArray)data);                                  \
        ptr = _env->GetShortArrayElements((jshortArray)data, flag);                     \
        typeBytes = 2;                                                                  \
        fnc(__VA_ARGS__);                                                               \
        _env->ReleaseShortArrayElements((jshortArray)data, (jshort *)ptr, relFlag);     \
        return;                                                                         \
    case RS_TYPE_SIGNED_32:                                                             \
    case RS_TYPE_UNSIGNED_32:                                                           \
        len = _env->GetArrayLength((jintArray)data);                                    \
        ptr = _env->GetIntArrayElements((jintArray)data, flag);                         \
        typeBytes = 4;                                                                  \
        fnc(__VA_ARGS__);                                                               \
        _env->ReleaseIntArrayElements((jintArray)data, (jint *)ptr, relFlag);           \
        return;                                                                         \
    case RS_TYPE_SIGNED_64:                                                             \
    case RS_TYPE_UNSIGNED_64:                                                           \
        len = _env->GetArrayLength((jlongArray)data);                                   \
        ptr = _env->GetLongArrayElements((jlongArray)data, flag);                       \
        typeBytes = 8;                                                                  \
        fnc(__VA_ARGS__);                                                               \
        _env->ReleaseLongArrayElements((jlongArray)data, (jlong *)ptr, relFlag);        \
        return;                                                                         \
    default:                                                                            \
        break;                                                                          \
    }                                                                                   \
}


class AutoJavaStringToUTF8 {
public:
    AutoJavaStringToUTF8(JNIEnv* env, jstring str) : fEnv(env), fJStr(str) {
        fCStr = env->GetStringUTFChars(str, nullptr);
        fLength = env->GetStringUTFLength(str);
    }
    ~AutoJavaStringToUTF8() {
        fEnv->ReleaseStringUTFChars(fJStr, fCStr);
    }
    const char* c_str() const { return fCStr; }
    jsize length() const { return fLength; }

private:
    JNIEnv*     fEnv;
    jstring     fJStr;
    const char* fCStr;
    jsize       fLength;
};

class AutoJavaStringArrayToUTF8 {
public:
    AutoJavaStringArrayToUTF8(JNIEnv* env, jobjectArray strings, jsize stringsLength)
    : mEnv(env), mStrings(strings), mStringsLength(stringsLength) {
        mCStrings = nullptr;
        mSizeArray = nullptr;
        if (stringsLength > 0) {
            mCStrings = (const char **)calloc(stringsLength, sizeof(char *));
            mSizeArray = (size_t*)calloc(stringsLength, sizeof(size_t));
            for (jsize ct = 0; ct < stringsLength; ct ++) {
                jstring s = (jstring)mEnv->GetObjectArrayElement(mStrings, ct);
                mCStrings[ct] = mEnv->GetStringUTFChars(s, nullptr);
                mSizeArray[ct] = mEnv->GetStringUTFLength(s);
            }
        }
    }
    ~AutoJavaStringArrayToUTF8() {
        for (jsize ct=0; ct < mStringsLength; ct++) {
            jstring s = (jstring)mEnv->GetObjectArrayElement(mStrings, ct);
            mEnv->ReleaseStringUTFChars(s, mCStrings[ct]);
        }
        free(mCStrings);
        free(mSizeArray);
    }
    const char **c_str() const { return mCStrings; }
    size_t *c_str_len() const { return mSizeArray; }
    jsize length() const { return mStringsLength; }

private:
    JNIEnv      *mEnv;
    jobjectArray mStrings;
    const char **mCStrings;
    size_t      *mSizeArray;
    jsize        mStringsLength;
};

// ---------------------------------------------------------------------------

static jfieldID gContextId = 0;
static jfieldID gNativeBitmapID = 0;
static jfieldID gTypeNativeCache = 0;

static void _nInit(JNIEnv *_env, jclass _this)
{
    gContextId             = _env->GetFieldID(_this, "mContext", "J");

    jclass bitmapClass = _env->FindClass("android/graphics/Bitmap");
    gNativeBitmapID = _env->GetFieldID(bitmapClass, "mNativeBitmap", "J");
}

// ---------------------------------------------------------------------------

static void
nContextFinish(JNIEnv *_env, jobject _this, jlong con)
{
    LOG_API("nContextFinish, con(%p)", (RsContext)con);
    rsContextFinish((RsContext)con);
}

static void
nAssignName(JNIEnv *_env, jobject _this, jlong con, jlong obj, jbyteArray str)
{
    LOG_API("nAssignName, con(%p), obj(%p)", (RsContext)con, (void *)obj);
    jint len = _env->GetArrayLength(str);
    jbyte * cptr = (jbyte *) _env->GetPrimitiveArrayCritical(str, 0);
    rsAssignName((RsContext)con, (void *)obj, (const char *)cptr, len);
    _env->ReleasePrimitiveArrayCritical(str, cptr, JNI_ABORT);
}

static jstring
nGetName(JNIEnv *_env, jobject _this, jlong con, jlong obj)
{
    LOG_API("nGetName, con(%p), obj(%p)", (RsContext)con, (void *)obj);
    const char *name = nullptr;
    rsaGetName((RsContext)con, (void *)obj, &name);
    if(name == nullptr || strlen(name) == 0) {
        return nullptr;
    }
    return _env->NewStringUTF(name);
}

static void
nObjDestroy(JNIEnv *_env, jobject _this, jlong con, jlong obj)
{
    LOG_API("nObjDestroy, con(%p) obj(%p)", (RsContext)con, (void *)obj);
    rsObjDestroy((RsContext)con, (void *)obj);
}

// ---------------------------------------------------------------------------

static jlong
nDeviceCreate(JNIEnv *_env, jobject _this)
{
    LOG_API("nDeviceCreate");
    return (jlong)rsDeviceCreate();
}

static void
nDeviceDestroy(JNIEnv *_env, jobject _this, jlong dev)
{
    LOG_API("nDeviceDestroy");
    return rsDeviceDestroy((RsDevice)dev);
}

static void
nDeviceSetConfig(JNIEnv *_env, jobject _this, jlong dev, jint p, jint value)
{
    LOG_API("nDeviceSetConfig  dev(%p), param(%i), value(%i)", (void *)dev, p, value);
    return rsDeviceSetConfig((RsDevice)dev, (RsDeviceParam)p, value);
}

static jlong
nContextCreate(JNIEnv *_env, jobject _this, jlong dev, jint ver, jint sdkVer, jint ct)
{
    LOG_API("nContextCreate");
    return (jlong)rsContextCreate((RsDevice)dev, ver, sdkVer, (RsContextType)ct, 0);
}

static jlong
nContextCreateGL(JNIEnv *_env, jobject _this, jlong dev, jint ver, jint sdkVer,
                 jint colorMin, jint colorPref,
                 jint alphaMin, jint alphaPref,
                 jint depthMin, jint depthPref,
                 jint stencilMin, jint stencilPref,
                 jint samplesMin, jint samplesPref, jfloat samplesQ,
                 jint dpi)
{
    RsSurfaceConfig sc;
    sc.alphaMin = alphaMin;
    sc.alphaPref = alphaPref;
    sc.colorMin = colorMin;
    sc.colorPref = colorPref;
    sc.depthMin = depthMin;
    sc.depthPref = depthPref;
    sc.samplesMin = samplesMin;
    sc.samplesPref = samplesPref;
    sc.samplesQ = samplesQ;

    LOG_API("nContextCreateGL");
    return (jlong)rsContextCreateGL((RsDevice)dev, ver, sdkVer, sc, dpi);
}

static void
nContextSetPriority(JNIEnv *_env, jobject _this, jlong con, jint p)
{
    LOG_API("ContextSetPriority, con(%p), priority(%i)", (RsContext)con, p);
    rsContextSetPriority((RsContext)con, p);
}



static void
nContextSetSurface(JNIEnv *_env, jobject _this, jlong con, jint width, jint height, jobject wnd)
{
    LOG_API("nContextSetSurface, con(%p), width(%i), height(%i), surface(%p)", (RsContext)con, width, height, (Surface *)wnd);

    ANativeWindow * window = nullptr;
    if (wnd == nullptr) {

    } else {
        window = android_view_Surface_getNativeWindow(_env, wnd).get();
    }

    rsContextSetSurface((RsContext)con, width, height, window);
}

static void
nContextDestroy(JNIEnv *_env, jobject _this, jlong con)
{
    LOG_API("nContextDestroy, con(%p)", (RsContext)con);
    rsContextDestroy((RsContext)con);
}

static void
nContextDump(JNIEnv *_env, jobject _this, jlong con, jint bits)
{
    LOG_API("nContextDump, con(%p)  bits(%i)", (RsContext)con, bits);
    rsContextDump((RsContext)con, bits);
}

static void
nContextPause(JNIEnv *_env, jobject _this, jlong con)
{
    LOG_API("nContextPause, con(%p)", (RsContext)con);
    rsContextPause((RsContext)con);
}

static void
nContextResume(JNIEnv *_env, jobject _this, jlong con)
{
    LOG_API("nContextResume, con(%p)", (RsContext)con);
    rsContextResume((RsContext)con);
}


static jstring
nContextGetErrorMessage(JNIEnv *_env, jobject _this, jlong con)
{
    LOG_API("nContextGetErrorMessage, con(%p)", (RsContext)con);
    char buf[1024];

    size_t receiveLen;
    uint32_t subID;
    int id = rsContextGetMessage((RsContext)con,
                                 buf, sizeof(buf),
                                 &receiveLen, sizeof(receiveLen),
                                 &subID, sizeof(subID));
    if (!id && receiveLen) {
        ALOGV("message receive buffer too small.  %i", receiveLen);
    }
    return _env->NewStringUTF(buf);
}

static jint
nContextGetUserMessage(JNIEnv *_env, jobject _this, jlong con, jintArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nContextGetMessage, con(%p), len(%i)", (RsContext)con, len);
    jint *ptr = _env->GetIntArrayElements(data, nullptr);
    size_t receiveLen;
    uint32_t subID;
    int id = rsContextGetMessage((RsContext)con,
                                 ptr, len * 4,
                                 &receiveLen, sizeof(receiveLen),
                                 &subID, sizeof(subID));
    if (!id && receiveLen) {
        ALOGV("message receive buffer too small.  %i", receiveLen);
    }
    _env->ReleaseIntArrayElements(data, ptr, 0);
    return (jint)id;
}

static jint
nContextPeekMessage(JNIEnv *_env, jobject _this, jlong con, jintArray auxData)
{
    LOG_API("nContextPeekMessage, con(%p)", (RsContext)con);
    jint *auxDataPtr = _env->GetIntArrayElements(auxData, nullptr);
    size_t receiveLen;
    uint32_t subID;
    int id = rsContextPeekMessage((RsContext)con, &receiveLen, sizeof(receiveLen),
                                  &subID, sizeof(subID));
    auxDataPtr[0] = (jint)subID;
    auxDataPtr[1] = (jint)receiveLen;
    _env->ReleaseIntArrayElements(auxData, auxDataPtr, 0);
    return (jint)id;
}

static void nContextInitToClient(JNIEnv *_env, jobject _this, jlong con)
{
    LOG_API("nContextInitToClient, con(%p)", (RsContext)con);
    rsContextInitToClient((RsContext)con);
}

static void nContextDeinitToClient(JNIEnv *_env, jobject _this, jlong con)
{
    LOG_API("nContextDeinitToClient, con(%p)", (RsContext)con);
    rsContextDeinitToClient((RsContext)con);
}

static void
nContextSendMessage(JNIEnv *_env, jobject _this, jlong con, jint id, jintArray data)
{
    jint *ptr = nullptr;
    jint len = 0;
    if (data) {
        len = _env->GetArrayLength(data);
        jint *ptr = _env->GetIntArrayElements(data, nullptr);
    }
    LOG_API("nContextSendMessage, con(%p), id(%i), len(%i)", (RsContext)con, id, len);
    rsContextSendMessage((RsContext)con, id, (const uint8_t *)ptr, len * sizeof(int));
    if (data) {
        _env->ReleaseIntArrayElements(data, ptr, JNI_ABORT);
    }
}



static jlong
nElementCreate(JNIEnv *_env, jobject _this, jlong con, jlong type, jint kind, jboolean norm, jint size)
{
    LOG_API("nElementCreate, con(%p), type(%i), kind(%i), norm(%i), size(%i)", (RsContext)con, type, kind, norm, size);
    return (jlong)rsElementCreate((RsContext)con, (RsDataType)type, (RsDataKind)kind, norm, size);
}

static jlong
nElementCreate2(JNIEnv *_env, jobject _this, jlong con,
                jlongArray _ids, jobjectArray _names, jintArray _arraySizes)
{
    int fieldCount = _env->GetArrayLength(_ids);
    LOG_API("nElementCreate2, con(%p)", (RsContext)con);

    jlong *jIds = _env->GetLongArrayElements(_ids, nullptr);
    jint *jArraySizes = _env->GetIntArrayElements(_arraySizes, nullptr);

    RsElement *ids = (RsElement*)malloc(fieldCount * sizeof(RsElement));
    uint32_t *arraySizes = (uint32_t *)malloc(fieldCount * sizeof(uint32_t));

    for(int i = 0; i < fieldCount; i ++) {
        ids[i] = (RsElement)jIds[i];
        arraySizes[i] = (uint32_t)jArraySizes[i];
    }

    AutoJavaStringArrayToUTF8 names(_env, _names, fieldCount);

    const char **nameArray = names.c_str();
    size_t *sizeArray = names.c_str_len();

    jlong id = (jlong)rsElementCreate2((RsContext)con,
                                     (const RsElement *)ids, fieldCount,
                                     nameArray, fieldCount * sizeof(size_t),  sizeArray,
                                     (const uint32_t *)arraySizes, fieldCount);

    free(ids);
    free(arraySizes);
    _env->ReleaseLongArrayElements(_ids, jIds, JNI_ABORT);
    _env->ReleaseIntArrayElements(_arraySizes, jArraySizes, JNI_ABORT);

    return (jlong)id;
}

static void
nElementGetNativeData(JNIEnv *_env, jobject _this, jlong con, jlong id, jintArray _elementData)
{
    int dataSize = _env->GetArrayLength(_elementData);
    LOG_API("nElementGetNativeData, con(%p)", (RsContext)con);

    // we will pack mType; mKind; mNormalized; mVectorSize; NumSubElements
    assert(dataSize == 5);

    uintptr_t elementData[5];
    rsaElementGetNativeData((RsContext)con, (RsElement)id, elementData, dataSize);

    for(jint i = 0; i < dataSize; i ++) {
        const jint data = (jint)elementData[i];
        _env->SetIntArrayRegion(_elementData, i, 1, &data);
    }
}


static void
nElementGetSubElements(JNIEnv *_env, jobject _this, jlong con, jlong id,
                       jlongArray _IDs,
                       jobjectArray _names,
                       jintArray _arraySizes)
{
    uint32_t dataSize = _env->GetArrayLength(_IDs);
    LOG_API("nElementGetSubElements, con(%p)", (RsContext)con);

    uintptr_t *ids = (uintptr_t*)malloc(dataSize * sizeof(uintptr_t));
    const char **names = (const char **)malloc(dataSize * sizeof(const char *));
    uint32_t *arraySizes = (uint32_t *)malloc(dataSize * sizeof(uint32_t));

    rsaElementGetSubElements((RsContext)con, (RsElement)id, ids, names, arraySizes, (uint32_t)dataSize);

    for(uint32_t i = 0; i < dataSize; i++) {
        const jlong id = (jlong)ids[i];
        const jint arraySize = (jint)arraySizes[i];
        _env->SetObjectArrayElement(_names, i, _env->NewStringUTF(names[i]));
        _env->SetLongArrayRegion(_IDs, i, 1, &id);
        _env->SetIntArrayRegion(_arraySizes, i, 1, &arraySize);
    }

    free(ids);
    free(names);
    free(arraySizes);
}

// -----------------------------------

static jlong
nTypeCreate(JNIEnv *_env, jobject _this, jlong con, jlong eid,
            jint dimx, jint dimy, jint dimz, jboolean mips, jboolean faces, jint yuv)
{
    LOG_API("nTypeCreate, con(%p) eid(%p), x(%i), y(%i), z(%i), mips(%i), faces(%i), yuv(%i)",
            (RsContext)con, eid, dimx, dimy, dimz, mips, faces, yuv);

    return (jlong)rsTypeCreate((RsContext)con, (RsElement)eid, dimx, dimy, dimz, mips, faces, yuv);
}

static void
nTypeGetNativeData(JNIEnv *_env, jobject _this, jlong con, jlong id, jlongArray _typeData)
{
    // We are packing 6 items: mDimX; mDimY; mDimZ;
    // mDimLOD; mDimFaces; mElement; into typeData
    int elementCount = _env->GetArrayLength(_typeData);

    assert(elementCount == 6);
    LOG_API("nTypeGetNativeData, con(%p)", (RsContext)con);

    uintptr_t typeData[6];
    rsaTypeGetNativeData((RsContext)con, (RsType)id, typeData, 6);

    for(jint i = 0; i < elementCount; i ++) {
        const jlong data = (jlong)typeData[i];
        _env->SetLongArrayRegion(_typeData, i, 1, &data);
    }
}

// -----------------------------------

static jlong
nAllocationCreateTyped(JNIEnv *_env, jobject _this, jlong con, jlong type, jint mips, jint usage, jlong pointer)
{
    LOG_API("nAllocationCreateTyped, con(%p), type(%p), mip(%i), usage(%i), ptr(%p)", (RsContext)con, (RsElement)type, mips, usage, (void *)pointer);
    return (jlong) rsAllocationCreateTyped((RsContext)con, (RsType)type, (RsAllocationMipmapControl)mips, (uint32_t)usage, (uintptr_t)pointer);
}

static void
nAllocationSyncAll(JNIEnv *_env, jobject _this, jlong con, jlong a, jint bits)
{
    LOG_API("nAllocationSyncAll, con(%p), a(%p), bits(0x%08x)", (RsContext)con, (RsAllocation)a, bits);
    rsAllocationSyncAll((RsContext)con, (RsAllocation)a, (RsAllocationUsageType)bits);
}

static jobject
nAllocationGetSurface(JNIEnv *_env, jobject _this, jlong con, jlong a)
{
    LOG_API("nAllocationGetSurface, con(%p), a(%p)", (RsContext)con, (RsAllocation)a);

    IGraphicBufferProducer *v = (IGraphicBufferProducer *)rsAllocationGetSurface((RsContext)con, (RsAllocation)a);
    sp<IGraphicBufferProducer> bp = v;
    v->decStrong(nullptr);

    jobject o = android_view_Surface_createFromIGraphicBufferProducer(_env, bp);
    return o;
}

static void
nAllocationSetSurface(JNIEnv *_env, jobject _this, jlong con, jlong alloc, jobject sur)
{
    LOG_API("nAllocationSetSurface, con(%p), alloc(%p), surface(%p)",
            (RsContext)con, (RsAllocation)alloc, (Surface *)sur);

    sp<Surface> s;
    if (sur != 0) {
        s = android_view_Surface_getSurface(_env, sur);
    }

    rsAllocationSetSurface((RsContext)con, (RsAllocation)alloc, static_cast<ANativeWindow *>(s.get()));
}

static void
nAllocationIoSend(JNIEnv *_env, jobject _this, jlong con, jlong alloc)
{
    LOG_API("nAllocationIoSend, con(%p), alloc(%p)", (RsContext)con, alloc);
    rsAllocationIoSend((RsContext)con, (RsAllocation)alloc);
}

static void
nAllocationIoReceive(JNIEnv *_env, jobject _this, jlong con, jlong alloc)
{
    LOG_API("nAllocationIoReceive, con(%p), alloc(%p)", (RsContext)con, alloc);
    rsAllocationIoReceive((RsContext)con, (RsAllocation)alloc);
}


static void
nAllocationGenerateMipmaps(JNIEnv *_env, jobject _this, jlong con, jlong alloc)
{
    LOG_API("nAllocationGenerateMipmaps, con(%p), a(%p)", (RsContext)con, (RsAllocation)alloc);
    rsAllocationGenerateMipmaps((RsContext)con, (RsAllocation)alloc);
}

static jlong
nAllocationCreateFromBitmap(JNIEnv *_env, jobject _this, jlong con, jlong type, jint mip, jobject jbitmap, jint usage)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetLongField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    jlong id = (jlong)rsAllocationCreateFromBitmap((RsContext)con,
                                                  (RsType)type, (RsAllocationMipmapControl)mip,
                                                  ptr, bitmap.getSize(), usage);
    bitmap.unlockPixels();
    return id;
}

static jlong
nAllocationCreateBitmapBackedAllocation(JNIEnv *_env, jobject _this, jlong con, jlong type, jint mip, jobject jbitmap, jint usage)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetLongField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    jlong id = (jlong)rsAllocationCreateTyped((RsContext)con,
                                            (RsType)type, (RsAllocationMipmapControl)mip,
                                            (uint32_t)usage, (uintptr_t)ptr);
    bitmap.unlockPixels();
    return id;
}

static jlong
nAllocationCubeCreateFromBitmap(JNIEnv *_env, jobject _this, jlong con, jlong type, jint mip, jobject jbitmap, jint usage)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetLongField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    jlong id = (jlong)rsAllocationCubeCreateFromBitmap((RsContext)con,
                                                      (RsType)type, (RsAllocationMipmapControl)mip,
                                                      ptr, bitmap.getSize(), usage);
    bitmap.unlockPixels();
    return id;
}

static void
nAllocationCopyFromBitmap(JNIEnv *_env, jobject _this, jlong con, jlong alloc, jobject jbitmap)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetLongField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);
    int w = bitmap.width();
    int h = bitmap.height();

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    rsAllocation2DData((RsContext)con, (RsAllocation)alloc, 0, 0,
                       0, RS_ALLOCATION_CUBEMAP_FACE_POSITIVE_X,
                       w, h, ptr, bitmap.getSize(), 0);
    bitmap.unlockPixels();
}

static void
nAllocationCopyToBitmap(JNIEnv *_env, jobject _this, jlong con, jlong alloc, jobject jbitmap)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetLongField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);

    bitmap.lockPixels();
    void* ptr = bitmap.getPixels();
    rsAllocationCopyToBitmap((RsContext)con, (RsAllocation)alloc, ptr, bitmap.getSize());
    bitmap.unlockPixels();
    bitmap.notifyPixelsChanged();
}

static void ReleaseBitmapCallback(void *bmp)
{
    SkBitmap const * nativeBitmap = (SkBitmap const *)bmp;
    nativeBitmap->unlockPixels();
}


// Copies from the Java object data into the Allocation pointed to by _alloc.
static void
nAllocationData1D(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jint offset, jint lod,
                  jint count, jobject data, jint sizeBytes, jint dataType)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    LOG_API("nAllocation1DData, con(%p), adapter(%p), offset(%i), count(%i), sizeBytes(%i), dataType(%i)",
            (RsContext)con, (RsAllocation)alloc, offset, count, sizeBytes, dataType);
    PER_ARRAY_TYPE(nullptr, rsAllocation1DData, true, (RsContext)con, alloc, offset, lod, count, ptr, sizeBytes);
}

// Copies from the Java array data into the Allocation pointed to by alloc.
static void
//    native void rsnAllocationElementData1D(long con, long id, int xoff, int compIdx, byte[] d, int sizeBytes);
nAllocationElementData1D(JNIEnv *_env, jobject _this, jlong con, jlong alloc, jint offset, jint lod, jint compIdx, jbyteArray data, jint sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocationElementData1D, con(%p), alloc(%p), offset(%i), comp(%i), len(%i), sizeBytes(%i)", (RsContext)con, (RsAllocation)alloc, offset, compIdx, len, sizeBytes);
    jbyte *ptr = _env->GetByteArrayElements(data, nullptr);
    rsAllocation1DElementData((RsContext)con, (RsAllocation)alloc, offset, lod, ptr, sizeBytes, compIdx);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

// Copies from the Java object data into the Allocation pointed to by _alloc.
static void
nAllocationData2D(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jint xoff, jint yoff, jint lod, jint _face,
                  jint w, jint h, jobject data, jint sizeBytes, jint dataType)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    RsAllocationCubemapFace face = (RsAllocationCubemapFace)_face;
    LOG_API("nAllocation2DData, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i) type(%i)",
            (RsContext)con, alloc, xoff, yoff, w, h, sizeBytes, dataType);
    PER_ARRAY_TYPE(nullptr, rsAllocation2DData, true, (RsContext)con, alloc, xoff, yoff, lod, face, w, h, ptr, sizeBytes, 0);
}

// Copies from the Allocation pointed to by srcAlloc into the Allocation
// pointed to by dstAlloc.
static void
nAllocationData2D_alloc(JNIEnv *_env, jobject _this, jlong con,
                        jlong dstAlloc, jint dstXoff, jint dstYoff,
                        jint dstMip, jint dstFace,
                        jint width, jint height,
                        jlong srcAlloc, jint srcXoff, jint srcYoff,
                        jint srcMip, jint srcFace)
{
    LOG_API("nAllocation2DData_s, con(%p), dstAlloc(%p), dstXoff(%i), dstYoff(%i),"
            " dstMip(%i), dstFace(%i), width(%i), height(%i),"
            " srcAlloc(%p), srcXoff(%i), srcYoff(%i), srcMip(%i), srcFace(%i)",
            (RsContext)con, (RsAllocation)dstAlloc, dstXoff, dstYoff, dstMip, dstFace,
            width, height, (RsAllocation)srcAlloc, srcXoff, srcYoff, srcMip, srcFace);

    rsAllocationCopy2DRange((RsContext)con,
                            (RsAllocation)dstAlloc,
                            dstXoff, dstYoff,
                            dstMip, dstFace,
                            width, height,
                            (RsAllocation)srcAlloc,
                            srcXoff, srcYoff,
                            srcMip, srcFace);
}

// Copies from the Java object data into the Allocation pointed to by _alloc.
static void
nAllocationData3D(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jint xoff, jint yoff, jint zoff, jint lod,
                    jint w, jint h, jint d, jobject data, int sizeBytes, int dataType)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    LOG_API("nAllocation3DData, con(%p), alloc(%p), xoff(%i), yoff(%i), zoff(%i), lod(%i), w(%i), h(%i), d(%i), sizeBytes(%i)",
            (RsContext)con, (RsAllocation)alloc, xoff, yoff, zoff, lod, w, h, d, sizeBytes);
    PER_ARRAY_TYPE(nullptr, rsAllocation3DData, true, (RsContext)con, alloc, xoff, yoff, zoff, lod, w, h, d, ptr, sizeBytes, 0);
}

// Copies from the Allocation pointed to by srcAlloc into the Allocation
// pointed to by dstAlloc.
static void
nAllocationData3D_alloc(JNIEnv *_env, jobject _this, jlong con,
                        jlong dstAlloc, jint dstXoff, jint dstYoff, jint dstZoff,
                        jint dstMip,
                        jint width, jint height, jint depth,
                        jlong srcAlloc, jint srcXoff, jint srcYoff, jint srcZoff,
                        jint srcMip)
{
    LOG_API("nAllocationData3D_alloc, con(%p), dstAlloc(%p), dstXoff(%i), dstYoff(%i),"
            " dstMip(%i), width(%i), height(%i),"
            " srcAlloc(%p), srcXoff(%i), srcYoff(%i), srcMip(%i)",
            (RsContext)con, (RsAllocation)dstAlloc, dstXoff, dstYoff, dstMip,
            width, height, (RsAllocation)srcAlloc, srcXoff, srcYoff, srcMip);

    rsAllocationCopy3DRange((RsContext)con,
                            (RsAllocation)dstAlloc,
                            dstXoff, dstYoff, dstZoff, dstMip,
                            width, height, depth,
                            (RsAllocation)srcAlloc,
                            srcXoff, srcYoff, srcZoff, srcMip);
}


// Copies from the Allocation pointed to by _alloc into the Java object data.
static void
nAllocationRead(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jobject data, int dataType)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    LOG_API("nAllocationRead, con(%p), alloc(%p)", (RsContext)con, (RsAllocation)alloc);
    PER_ARRAY_TYPE(0, rsAllocationRead, false, (RsContext)con, alloc, ptr, len * typeBytes);
}

// Copies from the Allocation pointed to by _alloc into the Java object data.
static void
nAllocationRead1D(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jint offset, jint lod,
                  jint count, jobject data, int sizeBytes, int dataType)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    LOG_API("nAllocation1DRead, con(%p), adapter(%p), offset(%i), count(%i), sizeBytes(%i), dataType(%i)",
            (RsContext)con, alloc, offset, count, sizeBytes, dataType);
    PER_ARRAY_TYPE(0, rsAllocation1DRead, false, (RsContext)con, alloc, offset, lod, count, ptr, sizeBytes);
}

// Copies from the Allocation pointed to by _alloc into the Java object data.
static void
nAllocationRead2D(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jint xoff, jint yoff, jint lod, jint _face,
                  jint w, jint h, jobject data, int sizeBytes, int dataType)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    RsAllocationCubemapFace face = (RsAllocationCubemapFace)_face;
    LOG_API("nAllocation2DRead, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i) type(%i)",
            (RsContext)con, alloc, xoff, yoff, w, h, sizeBytes, dataType);
    PER_ARRAY_TYPE(0, rsAllocation2DRead, false, (RsContext)con, alloc, xoff, yoff, lod, face, w, h, ptr, sizeBytes, 0);
}

static jlong
nAllocationGetType(JNIEnv *_env, jobject _this, jlong con, jlong a)
{
    LOG_API("nAllocationGetType, con(%p), a(%p)", (RsContext)con, (RsAllocation)a);
    return (jlong) rsaAllocationGetType((RsContext)con, (RsAllocation)a);
}

static void
nAllocationResize1D(JNIEnv *_env, jobject _this, jlong con, jlong alloc, jint dimX)
{
    LOG_API("nAllocationResize1D, con(%p), alloc(%p), sizeX(%i)", (RsContext)con, (RsAllocation)alloc, dimX);
    rsAllocationResize1D((RsContext)con, (RsAllocation)alloc, dimX);
}

// -----------------------------------

static jlong
nFileA3DCreateFromAssetStream(JNIEnv *_env, jobject _this, jlong con, jlong native_asset)
{
    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    ALOGV("______nFileA3D %p", asset);

    jlong id = (jlong)rsaFileA3DCreateFromMemory((RsContext)con, asset->getBuffer(false), asset->getLength());
    return id;
}

static jlong
nFileA3DCreateFromAsset(JNIEnv *_env, jobject _this, jlong con, jobject _assetMgr, jstring _path)
{
    AssetManager* mgr = assetManagerForJavaObject(_env, _assetMgr);
    if (mgr == nullptr) {
        return 0;
    }

    AutoJavaStringToUTF8 str(_env, _path);
    Asset* asset = mgr->open(str.c_str(), Asset::ACCESS_BUFFER);
    if (asset == nullptr) {
        return 0;
    }

    jlong id = (jlong)rsaFileA3DCreateFromAsset((RsContext)con, asset);
    return id;
}

static jlong
nFileA3DCreateFromFile(JNIEnv *_env, jobject _this, jlong con, jstring fileName)
{
    AutoJavaStringToUTF8 fileNameUTF(_env, fileName);
    jlong id = (jlong)rsaFileA3DCreateFromFile((RsContext)con, fileNameUTF.c_str());

    return id;
}

static jint
nFileA3DGetNumIndexEntries(JNIEnv *_env, jobject _this, jlong con, jlong fileA3D)
{
    int32_t numEntries = 0;
    rsaFileA3DGetNumIndexEntries((RsContext)con, &numEntries, (RsFile)fileA3D);
    return (jint)numEntries;
}

static void
nFileA3DGetIndexEntries(JNIEnv *_env, jobject _this, jlong con, jlong fileA3D, jint numEntries, jintArray _ids, jobjectArray _entries)
{
    ALOGV("______nFileA3D %p", (RsFile) fileA3D);
    RsFileIndexEntry *fileEntries = (RsFileIndexEntry*)malloc((uint32_t)numEntries * sizeof(RsFileIndexEntry));

    rsaFileA3DGetIndexEntries((RsContext)con, fileEntries, (uint32_t)numEntries, (RsFile)fileA3D);

    for(jint i = 0; i < numEntries; i ++) {
        _env->SetObjectArrayElement(_entries, i, _env->NewStringUTF(fileEntries[i].objectName));
        _env->SetIntArrayRegion(_ids, i, 1, (const jint*)&fileEntries[i].classID);
    }

    free(fileEntries);
}

static jlong
nFileA3DGetEntryByIndex(JNIEnv *_env, jobject _this, jlong con, jlong fileA3D, jint index)
{
    ALOGV("______nFileA3D %p", (RsFile) fileA3D);
    jlong id = (jlong)rsaFileA3DGetEntryByIndex((RsContext)con, (uint32_t)index, (RsFile)fileA3D);
    return id;
}

// -----------------------------------

static jlong
nFontCreateFromFile(JNIEnv *_env, jobject _this, jlong con,
                    jstring fileName, jfloat fontSize, jint dpi)
{
    AutoJavaStringToUTF8 fileNameUTF(_env, fileName);
    jlong id = (jlong)rsFontCreateFromFile((RsContext)con,
                                         fileNameUTF.c_str(), fileNameUTF.length(),
                                         fontSize, dpi);

    return id;
}

static jlong
nFontCreateFromAssetStream(JNIEnv *_env, jobject _this, jlong con,
                           jstring name, jfloat fontSize, jint dpi, jlong native_asset)
{
    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    AutoJavaStringToUTF8 nameUTF(_env, name);

    jlong id = (jlong)rsFontCreateFromMemory((RsContext)con,
                                           nameUTF.c_str(), nameUTF.length(),
                                           fontSize, dpi,
                                           asset->getBuffer(false), asset->getLength());
    return id;
}

static jlong
nFontCreateFromAsset(JNIEnv *_env, jobject _this, jlong con, jobject _assetMgr, jstring _path,
                     jfloat fontSize, jint dpi)
{
    AssetManager* mgr = assetManagerForJavaObject(_env, _assetMgr);
    if (mgr == nullptr) {
        return 0;
    }

    AutoJavaStringToUTF8 str(_env, _path);
    Asset* asset = mgr->open(str.c_str(), Asset::ACCESS_BUFFER);
    if (asset == nullptr) {
        return 0;
    }

    jlong id = (jlong)rsFontCreateFromMemory((RsContext)con,
                                           str.c_str(), str.length(),
                                           fontSize, dpi,
                                           asset->getBuffer(false), asset->getLength());
    delete asset;
    return id;
}

// -----------------------------------

static void
nScriptBindAllocation(JNIEnv *_env, jobject _this, jlong con, jlong script, jlong alloc, jint slot)
{
    LOG_API("nScriptBindAllocation, con(%p), script(%p), alloc(%p), slot(%i)", (RsContext)con, (RsScript)script, (RsAllocation)alloc, slot);
    rsScriptBindAllocation((RsContext)con, (RsScript)script, (RsAllocation)alloc, slot);
}

static void
nScriptSetVarI(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jint val)
{
    LOG_API("nScriptSetVarI, con(%p), s(%p), slot(%i), val(%i)", (RsContext)con, (void *)script, slot, val);
    rsScriptSetVarI((RsContext)con, (RsScript)script, slot, val);
}

static jint
nScriptGetVarI(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot)
{
    LOG_API("nScriptGetVarI, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    int value = 0;
    rsScriptGetVarV((RsContext)con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarObj(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jlong val)
{
    LOG_API("nScriptSetVarObj, con(%p), s(%p), slot(%i), val(%i)", (RsContext)con, (void *)script, slot, val);
    rsScriptSetVarObj((RsContext)con, (RsScript)script, slot, (RsObjectBase)val);
}

static void
nScriptSetVarJ(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jlong val)
{
    LOG_API("nScriptSetVarJ, con(%p), s(%p), slot(%i), val(%lli)", (RsContext)con, (void *)script, slot, val);
    rsScriptSetVarJ((RsContext)con, (RsScript)script, slot, val);
}

static jlong
nScriptGetVarJ(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot)
{
    LOG_API("nScriptGetVarJ, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    jlong value = 0;
    rsScriptGetVarV((RsContext)con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarF(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, float val)
{
    LOG_API("nScriptSetVarF, con(%p), s(%p), slot(%i), val(%f)", (RsContext)con, (void *)script, slot, val);
    rsScriptSetVarF((RsContext)con, (RsScript)script, slot, val);
}

static jfloat
nScriptGetVarF(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot)
{
    LOG_API("nScriptGetVarF, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    jfloat value = 0;
    rsScriptGetVarV((RsContext)con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarD(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, double val)
{
    LOG_API("nScriptSetVarD, con(%p), s(%p), slot(%i), val(%lf)", (RsContext)con, (void *)script, slot, val);
    rsScriptSetVarD((RsContext)con, (RsScript)script, slot, val);
}

static jdouble
nScriptGetVarD(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot)
{
    LOG_API("nScriptGetVarD, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    jdouble value = 0;
    rsScriptGetVarV((RsContext)con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarV(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jbyteArray data)
{
    LOG_API("nScriptSetVarV, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, nullptr);
    rsScriptSetVarV((RsContext)con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nScriptGetVarV(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jbyteArray data)
{
    LOG_API("nScriptSetVarV, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, nullptr);
    rsScriptGetVarV((RsContext)con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, 0);
}

static void
nScriptSetVarVE(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jbyteArray data, jlong elem, jintArray dims)
{
    LOG_API("nScriptSetVarVE, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, nullptr);
    jint dimsLen = _env->GetArrayLength(dims) * sizeof(int);
    jint *dimsPtr = _env->GetIntArrayElements(dims, nullptr);
    rsScriptSetVarVE((RsContext)con, (RsScript)script, slot, ptr, len, (RsElement)elem,
                     (const uint32_t*) dimsPtr, dimsLen);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
    _env->ReleaseIntArrayElements(dims, dimsPtr, JNI_ABORT);
}


static void
nScriptSetTimeZone(JNIEnv *_env, jobject _this, jlong con, jlong script, jbyteArray timeZone)
{
    LOG_API("nScriptCSetTimeZone, con(%p), s(%p)", (RsContext)con, (void *)script);

    jint length = _env->GetArrayLength(timeZone);
    jbyte* timeZone_ptr;
    timeZone_ptr = (jbyte *) _env->GetPrimitiveArrayCritical(timeZone, (jboolean *)0);

    rsScriptSetTimeZone((RsContext)con, (RsScript)script, (const char *)timeZone_ptr, length);

    if (timeZone_ptr) {
        _env->ReleasePrimitiveArrayCritical(timeZone, timeZone_ptr, 0);
    }
}

static void
nScriptInvoke(JNIEnv *_env, jobject _this, jlong con, jlong obj, jint slot)
{
    LOG_API("nScriptInvoke, con(%p), script(%p)", (RsContext)con, (void *)obj);
    rsScriptInvoke((RsContext)con, (RsScript)obj, slot);
}

static void
nScriptInvokeV(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jbyteArray data)
{
    LOG_API("nScriptInvokeV, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, nullptr);
    rsScriptInvokeV((RsContext)con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nScriptForEach(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot,
               jlongArray ains, jlong aout, jbyteArray params,
               jintArray limits)
{
    LOG_API("nScriptForEach, con(%p), s(%p), slot(%i)", (RsContext)con,
            (void *)script, slot);

    jint   in_len = 0;
    jlong *in_ptr = nullptr;

    RsAllocation *in_allocs = nullptr;

    if (ains != nullptr) {
        in_len = _env->GetArrayLength(ains);
        in_ptr = _env->GetLongArrayElements(ains, nullptr);

        if (sizeof(RsAllocation) == sizeof(jlong)) {
            in_allocs = (RsAllocation*)in_ptr;

        } else {
            // Convert from 64-bit jlong types to the native pointer type.

            in_allocs = (RsAllocation*)alloca(in_len * sizeof(RsAllocation));

            for (int index = in_len; --index >= 0;) {
                in_allocs[index] = (RsAllocation)in_ptr[index];
            }
        }
    }

    jint   param_len = 0;
    jbyte *param_ptr = nullptr;

    if (params != nullptr) {
        param_len = _env->GetArrayLength(params);
        param_ptr = _env->GetByteArrayElements(params, nullptr);
    }

    RsScriptCall sc, *sca = nullptr;
    uint32_t sc_size = 0;

    jint  limit_len = 0;
    jint *limit_ptr = nullptr;

    if (limits != nullptr) {
        limit_len = _env->GetArrayLength(limits);
        limit_ptr = _env->GetIntArrayElements(limits, nullptr);

        assert(limit_len == 6);

        sc.xStart     = limit_ptr[0];
        sc.xEnd       = limit_ptr[1];
        sc.yStart     = limit_ptr[2];
        sc.yEnd       = limit_ptr[3];
        sc.zStart     = limit_ptr[4];
        sc.zEnd       = limit_ptr[5];
        sc.strategy   = RS_FOR_EACH_STRATEGY_DONT_CARE;

        sca = &sc;
    }

    rsScriptForEachMulti((RsContext)con, (RsScript)script, slot,
                         in_allocs, in_len, (RsAllocation)aout,
                         param_ptr, param_len, sca, sc_size);

    if (ains != nullptr) {
        _env->ReleaseLongArrayElements(ains, in_ptr, JNI_ABORT);
    }

    if (params != nullptr) {
        _env->ReleaseByteArrayElements(params, param_ptr, JNI_ABORT);
    }

    if (limits != nullptr) {
        _env->ReleaseIntArrayElements(limits, limit_ptr, JNI_ABORT);
    }
}

// -----------------------------------

static jlong
nScriptCCreate(JNIEnv *_env, jobject _this, jlong con,
               jstring resName, jstring cacheDir,
               jbyteArray scriptRef, jint length)
{
    LOG_API("nScriptCCreate, con(%p)", (RsContext)con);

    AutoJavaStringToUTF8 resNameUTF(_env, resName);
    AutoJavaStringToUTF8 cacheDirUTF(_env, cacheDir);
    jlong ret = 0;
    jbyte* script_ptr = nullptr;
    jint _exception = 0;
    jint remaining;
    if (!scriptRef) {
        _exception = 1;
        //jniThrowException(_env, "java/lang/IllegalArgumentException", "script == null");
        goto exit;
    }
    if (length < 0) {
        _exception = 1;
        //jniThrowException(_env, "java/lang/IllegalArgumentException", "length < 0");
        goto exit;
    }
    remaining = _env->GetArrayLength(scriptRef);
    if (remaining < length) {
        _exception = 1;
        //jniThrowException(_env, "java/lang/IllegalArgumentException",
        //        "length > script.length - offset");
        goto exit;
    }
    script_ptr = (jbyte *)
        _env->GetPrimitiveArrayCritical(scriptRef, (jboolean *)0);

    //rsScriptCSetText((RsContext)con, (const char *)script_ptr, length);

    ret = (jlong)rsScriptCCreate((RsContext)con,
                                resNameUTF.c_str(), resNameUTF.length(),
                                cacheDirUTF.c_str(), cacheDirUTF.length(),
                                (const char *)script_ptr, length);

exit:
    if (script_ptr) {
        _env->ReleasePrimitiveArrayCritical(scriptRef, script_ptr,
                _exception ? JNI_ABORT: 0);
    }

    return (jlong)ret;
}

static jlong
nScriptIntrinsicCreate(JNIEnv *_env, jobject _this, jlong con, jint id, jlong eid)
{
    LOG_API("nScriptIntrinsicCreate, con(%p) id(%i) element(%p)", (RsContext)con, id, (void *)eid);
    return (jlong)rsScriptIntrinsicCreate((RsContext)con, id, (RsElement)eid);
}

static jlong
nScriptKernelIDCreate(JNIEnv *_env, jobject _this, jlong con, jlong sid, jint slot, jint sig)
{
    LOG_API("nScriptKernelIDCreate, con(%p) script(%p), slot(%i), sig(%i)", (RsContext)con, (void *)sid, slot, sig);
    return (jlong)rsScriptKernelIDCreate((RsContext)con, (RsScript)sid, slot, sig);
}

static jlong
nScriptFieldIDCreate(JNIEnv *_env, jobject _this, jlong con, jlong sid, jint slot)
{
    LOG_API("nScriptFieldIDCreate, con(%p) script(%p), slot(%i)", (RsContext)con, (void *)sid, slot);
    return (jlong)rsScriptFieldIDCreate((RsContext)con, (RsScript)sid, slot);
}

static jlong
nScriptGroupCreate(JNIEnv *_env, jobject _this, jlong con, jlongArray _kernels, jlongArray _src,
    jlongArray _dstk, jlongArray _dstf, jlongArray _types)
{
    LOG_API("nScriptGroupCreate, con(%p)", (RsContext)con);

    jint kernelsLen = _env->GetArrayLength(_kernels);
    jlong *jKernelsPtr = _env->GetLongArrayElements(_kernels, nullptr);
    RsScriptKernelID* kernelsPtr = (RsScriptKernelID*) malloc(sizeof(RsScriptKernelID) * kernelsLen);
    for(int i = 0; i < kernelsLen; ++i) {
        kernelsPtr[i] = (RsScriptKernelID)jKernelsPtr[i];
    }

    jint srcLen = _env->GetArrayLength(_src);
    jlong *jSrcPtr = _env->GetLongArrayElements(_src, nullptr);
    RsScriptKernelID* srcPtr = (RsScriptKernelID*) malloc(sizeof(RsScriptKernelID) * srcLen);
    for(int i = 0; i < srcLen; ++i) {
        srcPtr[i] = (RsScriptKernelID)jSrcPtr[i];
    }

    jint dstkLen = _env->GetArrayLength(_dstk);
    jlong *jDstkPtr = _env->GetLongArrayElements(_dstk, nullptr);
    RsScriptKernelID* dstkPtr = (RsScriptKernelID*) malloc(sizeof(RsScriptKernelID) * dstkLen);
    for(int i = 0; i < dstkLen; ++i) {
        dstkPtr[i] = (RsScriptKernelID)jDstkPtr[i];
    }

    jint dstfLen = _env->GetArrayLength(_dstf);
    jlong *jDstfPtr = _env->GetLongArrayElements(_dstf, nullptr);
    RsScriptKernelID* dstfPtr = (RsScriptKernelID*) malloc(sizeof(RsScriptKernelID) * dstfLen);
    for(int i = 0; i < dstfLen; ++i) {
        dstfPtr[i] = (RsScriptKernelID)jDstfPtr[i];
    }

    jint typesLen = _env->GetArrayLength(_types);
    jlong *jTypesPtr = _env->GetLongArrayElements(_types, nullptr);
    RsType* typesPtr = (RsType*) malloc(sizeof(RsType) * typesLen);
    for(int i = 0; i < typesLen; ++i) {
        typesPtr[i] = (RsType)jTypesPtr[i];
    }

    jlong id = (jlong)rsScriptGroupCreate((RsContext)con,
                               (RsScriptKernelID *)kernelsPtr, kernelsLen * sizeof(RsScriptKernelID),
                               (RsScriptKernelID *)srcPtr, srcLen * sizeof(RsScriptKernelID),
                               (RsScriptKernelID *)dstkPtr, dstkLen * sizeof(RsScriptKernelID),
                               (RsScriptFieldID *)dstfPtr, dstfLen * sizeof(RsScriptKernelID),
                               (RsType *)typesPtr, typesLen * sizeof(RsType));

    free(kernelsPtr);
    free(srcPtr);
    free(dstkPtr);
    free(dstfPtr);
    free(typesPtr);
    _env->ReleaseLongArrayElements(_kernels, jKernelsPtr, 0);
    _env->ReleaseLongArrayElements(_src, jSrcPtr, 0);
    _env->ReleaseLongArrayElements(_dstk, jDstkPtr, 0);
    _env->ReleaseLongArrayElements(_dstf, jDstfPtr, 0);
    _env->ReleaseLongArrayElements(_types, jTypesPtr, 0);
    return id;
}

static void
nScriptGroupSetInput(JNIEnv *_env, jobject _this, jlong con, jlong gid, jlong kid, jlong alloc)
{
    LOG_API("nScriptGroupSetInput, con(%p) group(%p), kernelId(%p), alloc(%p)", (RsContext)con,
        (void *)gid, (void *)kid, (void *)alloc);
    rsScriptGroupSetInput((RsContext)con, (RsScriptGroup)gid, (RsScriptKernelID)kid, (RsAllocation)alloc);
}

static void
nScriptGroupSetOutput(JNIEnv *_env, jobject _this, jlong con, jlong gid, jlong kid, jlong alloc)
{
    LOG_API("nScriptGroupSetOutput, con(%p) group(%p), kernelId(%p), alloc(%p)", (RsContext)con,
        (void *)gid, (void *)kid, (void *)alloc);
    rsScriptGroupSetOutput((RsContext)con, (RsScriptGroup)gid, (RsScriptKernelID)kid, (RsAllocation)alloc);
}

static void
nScriptGroupExecute(JNIEnv *_env, jobject _this, jlong con, jlong gid)
{
    LOG_API("nScriptGroupSetOutput, con(%p) group(%p)", (RsContext)con, (void *)gid);
    rsScriptGroupExecute((RsContext)con, (RsScriptGroup)gid);
}

// ---------------------------------------------------------------------------

static jlong
nProgramStoreCreate(JNIEnv *_env, jobject _this, jlong con,
                    jboolean colorMaskR, jboolean colorMaskG, jboolean colorMaskB, jboolean colorMaskA,
                    jboolean depthMask, jboolean ditherEnable,
                    jint srcFunc, jint destFunc,
                    jint depthFunc)
{
    LOG_API("nProgramStoreCreate, con(%p)", (RsContext)con);
    return (jlong)rsProgramStoreCreate((RsContext)con, colorMaskR, colorMaskG, colorMaskB, colorMaskA,
                                      depthMask, ditherEnable, (RsBlendSrcFunc)srcFunc,
                                      (RsBlendDstFunc)destFunc, (RsDepthFunc)depthFunc);
}

// ---------------------------------------------------------------------------

static void
nProgramBindConstants(JNIEnv *_env, jobject _this, jlong con, jlong vpv, jint slot, jlong a)
{
    LOG_API("nProgramBindConstants, con(%p), vpf(%p), sloat(%i), a(%p)", (RsContext)con, (RsProgramVertex)vpv, slot, (RsAllocation)a);
    rsProgramBindConstants((RsContext)con, (RsProgram)vpv, slot, (RsAllocation)a);
}

static void
nProgramBindTexture(JNIEnv *_env, jobject _this, jlong con, jlong vpf, jint slot, jlong a)
{
    LOG_API("nProgramBindTexture, con(%p), vpf(%p), slot(%i), a(%p)", (RsContext)con, (RsProgramFragment)vpf, slot, (RsAllocation)a);
    rsProgramBindTexture((RsContext)con, (RsProgramFragment)vpf, slot, (RsAllocation)a);
}

static void
nProgramBindSampler(JNIEnv *_env, jobject _this, jlong con, jlong vpf, jint slot, jlong a)
{
    LOG_API("nProgramBindSampler, con(%p), vpf(%p), slot(%i), a(%p)", (RsContext)con, (RsProgramFragment)vpf, slot, (RsSampler)a);
    rsProgramBindSampler((RsContext)con, (RsProgramFragment)vpf, slot, (RsSampler)a);
}

// ---------------------------------------------------------------------------

static jlong
nProgramFragmentCreate(JNIEnv *_env, jobject _this, jlong con, jstring shader,
                       jobjectArray texNames, jlongArray params)
{
    AutoJavaStringToUTF8 shaderUTF(_env, shader);
    jlong *jParamPtr = _env->GetLongArrayElements(params, nullptr);
    jint paramLen = _env->GetArrayLength(params);

    int texCount = _env->GetArrayLength(texNames);
    AutoJavaStringArrayToUTF8 names(_env, texNames, texCount);
    const char ** nameArray = names.c_str();
    size_t* sizeArray = names.c_str_len();

    LOG_API("nProgramFragmentCreate, con(%p), paramLen(%i)", (RsContext)con, paramLen);

    uintptr_t * paramPtr = (uintptr_t*) malloc(sizeof(uintptr_t) * paramLen);
    for(int i = 0; i < paramLen; ++i) {
        paramPtr[i] = (uintptr_t)jParamPtr[i];
    }
    jlong ret = (jlong)rsProgramFragmentCreate((RsContext)con, shaderUTF.c_str(), shaderUTF.length(),
                                             nameArray, texCount, sizeArray,
                                             paramPtr, paramLen);

    free(paramPtr);
    _env->ReleaseLongArrayElements(params, jParamPtr, JNI_ABORT);
    return ret;
}


// ---------------------------------------------------------------------------

static jlong
nProgramVertexCreate(JNIEnv *_env, jobject _this, jlong con, jstring shader,
                     jobjectArray texNames, jlongArray params)
{
    AutoJavaStringToUTF8 shaderUTF(_env, shader);
    jlong *jParamPtr = _env->GetLongArrayElements(params, nullptr);
    jint paramLen = _env->GetArrayLength(params);

    LOG_API("nProgramVertexCreate, con(%p), paramLen(%i)", (RsContext)con, paramLen);

    int texCount = _env->GetArrayLength(texNames);
    AutoJavaStringArrayToUTF8 names(_env, texNames, texCount);
    const char ** nameArray = names.c_str();
    size_t* sizeArray = names.c_str_len();

    uintptr_t * paramPtr = (uintptr_t*) malloc(sizeof(uintptr_t) * paramLen);
    for(int i = 0; i < paramLen; ++i) {
        paramPtr[i] = (uintptr_t)jParamPtr[i];
    }

    jlong ret = (jlong)rsProgramVertexCreate((RsContext)con, shaderUTF.c_str(), shaderUTF.length(),
                                           nameArray, texCount, sizeArray,
                                           paramPtr, paramLen);

    free(paramPtr);
    _env->ReleaseLongArrayElements(params, jParamPtr, JNI_ABORT);
    return ret;
}

// ---------------------------------------------------------------------------

static jlong
nProgramRasterCreate(JNIEnv *_env, jobject _this, jlong con, jboolean pointSprite, jint cull)
{
    LOG_API("nProgramRasterCreate, con(%p), pointSprite(%i), cull(%i)", (RsContext)con, pointSprite, cull);
    return (jlong)rsProgramRasterCreate((RsContext)con, pointSprite, (RsCullMode)cull);
}


// ---------------------------------------------------------------------------

static void
nContextBindRootScript(JNIEnv *_env, jobject _this, jlong con, jlong script)
{
    LOG_API("nContextBindRootScript, con(%p), script(%p)", (RsContext)con, (RsScript)script);
    rsContextBindRootScript((RsContext)con, (RsScript)script);
}

static void
nContextBindProgramStore(JNIEnv *_env, jobject _this, jlong con, jlong pfs)
{
    LOG_API("nContextBindProgramStore, con(%p), pfs(%p)", (RsContext)con, (RsProgramStore)pfs);
    rsContextBindProgramStore((RsContext)con, (RsProgramStore)pfs);
}

static void
nContextBindProgramFragment(JNIEnv *_env, jobject _this, jlong con, jlong pf)
{
    LOG_API("nContextBindProgramFragment, con(%p), pf(%p)", (RsContext)con, (RsProgramFragment)pf);
    rsContextBindProgramFragment((RsContext)con, (RsProgramFragment)pf);
}

static void
nContextBindProgramVertex(JNIEnv *_env, jobject _this, jlong con, jlong pf)
{
    LOG_API("nContextBindProgramVertex, con(%p), pf(%p)", (RsContext)con, (RsProgramVertex)pf);
    rsContextBindProgramVertex((RsContext)con, (RsProgramVertex)pf);
}

static void
nContextBindProgramRaster(JNIEnv *_env, jobject _this, jlong con, jlong pf)
{
    LOG_API("nContextBindProgramRaster, con(%p), pf(%p)", (RsContext)con, (RsProgramRaster)pf);
    rsContextBindProgramRaster((RsContext)con, (RsProgramRaster)pf);
}


// ---------------------------------------------------------------------------

static jlong
nSamplerCreate(JNIEnv *_env, jobject _this, jlong con, jint magFilter, jint minFilter,
               jint wrapS, jint wrapT, jint wrapR, jfloat aniso)
{
    LOG_API("nSamplerCreate, con(%p)", (RsContext)con);
    return (jlong)rsSamplerCreate((RsContext)con,
                                 (RsSamplerValue)magFilter,
                                 (RsSamplerValue)minFilter,
                                 (RsSamplerValue)wrapS,
                                 (RsSamplerValue)wrapT,
                                 (RsSamplerValue)wrapR,
                                 aniso);
}

// ---------------------------------------------------------------------------

static jlong
nPathCreate(JNIEnv *_env, jobject _this, jlong con, jint prim, jboolean isStatic, jlong _vtx, jlong _loop, jfloat q) {
    LOG_API("nPathCreate, con(%p)", (RsContext)con);

    jlong id = (jlong)rsPathCreate((RsContext)con, (RsPathPrimitive)prim, isStatic,
                                   (RsAllocation)_vtx,
                                   (RsAllocation)_loop, q);
    return id;
}

static jlong
nMeshCreate(JNIEnv *_env, jobject _this, jlong con, jlongArray _vtx, jlongArray _idx, jintArray _prim)
{
    LOG_API("nMeshCreate, con(%p)", (RsContext)con);

    jint vtxLen = _env->GetArrayLength(_vtx);
    jlong *jVtxPtr = _env->GetLongArrayElements(_vtx, nullptr);
    RsAllocation* vtxPtr = (RsAllocation*) malloc(sizeof(RsAllocation) * vtxLen);
    for(int i = 0; i < vtxLen; ++i) {
        vtxPtr[i] = (RsAllocation)(uintptr_t)jVtxPtr[i];
    }

    jint idxLen = _env->GetArrayLength(_idx);
    jlong *jIdxPtr = _env->GetLongArrayElements(_idx, nullptr);
    RsAllocation* idxPtr = (RsAllocation*) malloc(sizeof(RsAllocation) * idxLen);
    for(int i = 0; i < idxLen; ++i) {
        idxPtr[i] = (RsAllocation)(uintptr_t)jIdxPtr[i];
    }

    jint primLen = _env->GetArrayLength(_prim);
    jint *primPtr = _env->GetIntArrayElements(_prim, nullptr);

    jlong id = (jlong)rsMeshCreate((RsContext)con,
                               (RsAllocation *)vtxPtr, vtxLen,
                               (RsAllocation *)idxPtr, idxLen,
                               (uint32_t *)primPtr, primLen);

    free(vtxPtr);
    free(idxPtr);
    _env->ReleaseLongArrayElements(_vtx, jVtxPtr, 0);
    _env->ReleaseLongArrayElements(_idx, jIdxPtr, 0);
    _env->ReleaseIntArrayElements(_prim, primPtr, 0);
    return id;
}

static jint
nMeshGetVertexBufferCount(JNIEnv *_env, jobject _this, jlong con, jlong mesh)
{
    LOG_API("nMeshGetVertexBufferCount, con(%p), Mesh(%p)", (RsContext)con, (RsMesh)mesh);
    jint vtxCount = 0;
    rsaMeshGetVertexBufferCount((RsContext)con, (RsMesh)mesh, &vtxCount);
    return vtxCount;
}

static jint
nMeshGetIndexCount(JNIEnv *_env, jobject _this, jlong con, jlong mesh)
{
    LOG_API("nMeshGetIndexCount, con(%p), Mesh(%p)", (RsContext)con, (RsMesh)mesh);
    jint idxCount = 0;
    rsaMeshGetIndexCount((RsContext)con, (RsMesh)mesh, &idxCount);
    return idxCount;
}

static void
nMeshGetVertices(JNIEnv *_env, jobject _this, jlong con, jlong mesh, jlongArray _ids, jint numVtxIDs)
{
    LOG_API("nMeshGetVertices, con(%p), Mesh(%p)", (RsContext)con, (RsMesh)mesh);

    RsAllocation *allocs = (RsAllocation*)malloc((uint32_t)numVtxIDs * sizeof(RsAllocation));
    rsaMeshGetVertices((RsContext)con, (RsMesh)mesh, allocs, (uint32_t)numVtxIDs);

    for(jint i = 0; i < numVtxIDs; i ++) {
        const jlong alloc = (jlong)allocs[i];
        _env->SetLongArrayRegion(_ids, i, 1, &alloc);
    }

    free(allocs);
}

static void
nMeshGetIndices(JNIEnv *_env, jobject _this, jlong con, jlong mesh, jlongArray _idxIds, jintArray _primitives, jint numIndices)
{
    LOG_API("nMeshGetVertices, con(%p), Mesh(%p)", (RsContext)con, (RsMesh)mesh);

    RsAllocation *allocs = (RsAllocation*)malloc((uint32_t)numIndices * sizeof(RsAllocation));
    uint32_t *prims= (uint32_t*)malloc((uint32_t)numIndices * sizeof(uint32_t));

    rsaMeshGetIndices((RsContext)con, (RsMesh)mesh, allocs, prims, (uint32_t)numIndices);

    for(jint i = 0; i < numIndices; i ++) {
        const jlong alloc = (jlong)allocs[i];
        const jint prim = (jint)prims[i];
        _env->SetLongArrayRegion(_idxIds, i, 1, &alloc);
        _env->SetIntArrayRegion(_primitives, i, 1, &prim);
    }

    free(allocs);
    free(prims);
}

static jint
nSystemGetPointerSize(JNIEnv *_env, jobject _this) {
    return (jint)sizeof(void*);
}


// ---------------------------------------------------------------------------


static const char *classPathName = "android/renderscript/RenderScript";

static JNINativeMethod methods[] = {
{"_nInit",                         "()V",                                     (void*)_nInit },

{"nDeviceCreate",                  "()J",                                     (void*)nDeviceCreate },
{"nDeviceDestroy",                 "(J)V",                                    (void*)nDeviceDestroy },
{"nDeviceSetConfig",               "(JII)V",                                  (void*)nDeviceSetConfig },
{"nContextGetUserMessage",         "(J[I)I",                                  (void*)nContextGetUserMessage },
{"nContextGetErrorMessage",        "(J)Ljava/lang/String;",                   (void*)nContextGetErrorMessage },
{"nContextPeekMessage",            "(J[I)I",                                  (void*)nContextPeekMessage },

{"nContextInitToClient",           "(J)V",                                    (void*)nContextInitToClient },
{"nContextDeinitToClient",         "(J)V",                                    (void*)nContextDeinitToClient },


// All methods below are thread protected in java.
{"rsnContextCreate",                 "(JIII)J",                               (void*)nContextCreate },
{"rsnContextCreateGL",               "(JIIIIIIIIIIIIFI)J",                    (void*)nContextCreateGL },
{"rsnContextFinish",                 "(J)V",                                  (void*)nContextFinish },
{"rsnContextSetPriority",            "(JI)V",                                 (void*)nContextSetPriority },
{"rsnContextSetSurface",             "(JIILandroid/view/Surface;)V",          (void*)nContextSetSurface },
{"rsnContextDestroy",                "(J)V",                                  (void*)nContextDestroy },
{"rsnContextDump",                   "(JI)V",                                 (void*)nContextDump },
{"rsnContextPause",                  "(J)V",                                  (void*)nContextPause },
{"rsnContextResume",                 "(J)V",                                  (void*)nContextResume },
{"rsnContextSendMessage",            "(JI[I)V",                               (void*)nContextSendMessage },
{"rsnAssignName",                    "(JJ[B)V",                               (void*)nAssignName },
{"rsnGetName",                       "(JJ)Ljava/lang/String;",                (void*)nGetName },
{"rsnObjDestroy",                    "(JJ)V",                                 (void*)nObjDestroy },

{"rsnFileA3DCreateFromFile",         "(JLjava/lang/String;)J",                (void*)nFileA3DCreateFromFile },
{"rsnFileA3DCreateFromAssetStream",  "(JJ)J",                                 (void*)nFileA3DCreateFromAssetStream },
{"rsnFileA3DCreateFromAsset",        "(JLandroid/content/res/AssetManager;Ljava/lang/String;)J",            (void*)nFileA3DCreateFromAsset },
{"rsnFileA3DGetNumIndexEntries",     "(JJ)I",                                 (void*)nFileA3DGetNumIndexEntries },
{"rsnFileA3DGetIndexEntries",        "(JJI[I[Ljava/lang/String;)V",           (void*)nFileA3DGetIndexEntries },
{"rsnFileA3DGetEntryByIndex",        "(JJI)J",                                (void*)nFileA3DGetEntryByIndex },

{"rsnFontCreateFromFile",            "(JLjava/lang/String;FI)J",              (void*)nFontCreateFromFile },
{"rsnFontCreateFromAssetStream",     "(JLjava/lang/String;FIJ)J",             (void*)nFontCreateFromAssetStream },
{"rsnFontCreateFromAsset",        "(JLandroid/content/res/AssetManager;Ljava/lang/String;FI)J",            (void*)nFontCreateFromAsset },

{"rsnElementCreate",                 "(JJIZI)J",                              (void*)nElementCreate },
{"rsnElementCreate2",                "(J[J[Ljava/lang/String;[I)J",           (void*)nElementCreate2 },
{"rsnElementGetNativeData",          "(JJ[I)V",                               (void*)nElementGetNativeData },
{"rsnElementGetSubElements",         "(JJ[J[Ljava/lang/String;[I)V",          (void*)nElementGetSubElements },

{"rsnTypeCreate",                    "(JJIIIZZI)J",                           (void*)nTypeCreate },
{"rsnTypeGetNativeData",             "(JJ[J)V",                               (void*)nTypeGetNativeData },

{"rsnAllocationCreateTyped",         "(JJIIJ)J",                               (void*)nAllocationCreateTyped },
{"rsnAllocationCreateFromBitmap",    "(JJILandroid/graphics/Bitmap;I)J",      (void*)nAllocationCreateFromBitmap },
{"rsnAllocationCreateBitmapBackedAllocation",    "(JJILandroid/graphics/Bitmap;I)J",      (void*)nAllocationCreateBitmapBackedAllocation },
{"rsnAllocationCubeCreateFromBitmap","(JJILandroid/graphics/Bitmap;I)J",      (void*)nAllocationCubeCreateFromBitmap },

{"rsnAllocationCopyFromBitmap",      "(JJLandroid/graphics/Bitmap;)V",        (void*)nAllocationCopyFromBitmap },
{"rsnAllocationCopyToBitmap",        "(JJLandroid/graphics/Bitmap;)V",        (void*)nAllocationCopyToBitmap },

{"rsnAllocationSyncAll",             "(JJI)V",                                (void*)nAllocationSyncAll },
{"rsnAllocationGetSurface",          "(JJ)Landroid/view/Surface;",            (void*)nAllocationGetSurface },
{"rsnAllocationSetSurface",          "(JJLandroid/view/Surface;)V",           (void*)nAllocationSetSurface },
{"rsnAllocationIoSend",              "(JJ)V",                                 (void*)nAllocationIoSend },
{"rsnAllocationIoReceive",           "(JJ)V",                                 (void*)nAllocationIoReceive },
{"rsnAllocationData1D",              "(JJIIILjava/lang/Object;II)V",          (void*)nAllocationData1D },
{"rsnAllocationElementData1D",       "(JJIII[BI)V",                           (void*)nAllocationElementData1D },
{"rsnAllocationData2D",              "(JJIIIIIILjava/lang/Object;II)V",       (void*)nAllocationData2D },
{"rsnAllocationData2D",              "(JJIIIIIIJIIII)V",                      (void*)nAllocationData2D_alloc },
{"rsnAllocationData3D",              "(JJIIIIIIILjava/lang/Object;II)V",      (void*)nAllocationData3D },
{"rsnAllocationData3D",              "(JJIIIIIIIJIIII)V",                     (void*)nAllocationData3D_alloc },
{"rsnAllocationRead",                "(JJLjava/lang/Object;I)V",              (void*)nAllocationRead },
{"rsnAllocationRead1D",              "(JJIIILjava/lang/Object;II)V",          (void*)nAllocationRead1D },
{"rsnAllocationRead2D",              "(JJIIIIIILjava/lang/Object;II)V",       (void*)nAllocationRead2D },
{"rsnAllocationGetType",             "(JJ)J",                                 (void*)nAllocationGetType},
{"rsnAllocationResize1D",            "(JJI)V",                                (void*)nAllocationResize1D },
{"rsnAllocationGenerateMipmaps",     "(JJ)V",                                 (void*)nAllocationGenerateMipmaps },

{"rsnScriptBindAllocation",          "(JJJI)V",                               (void*)nScriptBindAllocation },
{"rsnScriptSetTimeZone",             "(JJ[B)V",                               (void*)nScriptSetTimeZone },
{"rsnScriptInvoke",                  "(JJI)V",                                (void*)nScriptInvoke },
{"rsnScriptInvokeV",                 "(JJI[B)V",                              (void*)nScriptInvokeV },

{"rsnScriptForEach",                 "(JJI[JJ[B[I)V",                         (void*)nScriptForEach },

{"rsnScriptSetVarI",                 "(JJII)V",                               (void*)nScriptSetVarI },
{"rsnScriptGetVarI",                 "(JJI)I",                                (void*)nScriptGetVarI },
{"rsnScriptSetVarJ",                 "(JJIJ)V",                               (void*)nScriptSetVarJ },
{"rsnScriptGetVarJ",                 "(JJI)J",                                (void*)nScriptGetVarJ },
{"rsnScriptSetVarF",                 "(JJIF)V",                               (void*)nScriptSetVarF },
{"rsnScriptGetVarF",                 "(JJI)F",                                (void*)nScriptGetVarF },
{"rsnScriptSetVarD",                 "(JJID)V",                               (void*)nScriptSetVarD },
{"rsnScriptGetVarD",                 "(JJI)D",                                (void*)nScriptGetVarD },
{"rsnScriptSetVarV",                 "(JJI[B)V",                              (void*)nScriptSetVarV },
{"rsnScriptGetVarV",                 "(JJI[B)V",                              (void*)nScriptGetVarV },
{"rsnScriptSetVarVE",                "(JJI[BJ[I)V",                           (void*)nScriptSetVarVE },
{"rsnScriptSetVarObj",               "(JJIJ)V",                               (void*)nScriptSetVarObj },

{"rsnScriptCCreate",                 "(JLjava/lang/String;Ljava/lang/String;[BI)J",  (void*)nScriptCCreate },
{"rsnScriptIntrinsicCreate",         "(JIJ)J",                                (void*)nScriptIntrinsicCreate },
{"rsnScriptKernelIDCreate",          "(JJII)J",                               (void*)nScriptKernelIDCreate },
{"rsnScriptFieldIDCreate",           "(JJI)J",                                (void*)nScriptFieldIDCreate },
{"rsnScriptGroupCreate",             "(J[J[J[J[J[J)J",                        (void*)nScriptGroupCreate },
{"rsnScriptGroupSetInput",           "(JJJJ)V",                               (void*)nScriptGroupSetInput },
{"rsnScriptGroupSetOutput",          "(JJJJ)V",                               (void*)nScriptGroupSetOutput },
{"rsnScriptGroupExecute",            "(JJ)V",                                 (void*)nScriptGroupExecute },

{"rsnProgramStoreCreate",            "(JZZZZZZIII)J",                         (void*)nProgramStoreCreate },

{"rsnProgramBindConstants",          "(JJIJ)V",                               (void*)nProgramBindConstants },
{"rsnProgramBindTexture",            "(JJIJ)V",                               (void*)nProgramBindTexture },
{"rsnProgramBindSampler",            "(JJIJ)V",                               (void*)nProgramBindSampler },

{"rsnProgramFragmentCreate",         "(JLjava/lang/String;[Ljava/lang/String;[J)J",              (void*)nProgramFragmentCreate },
{"rsnProgramRasterCreate",           "(JZI)J",                                (void*)nProgramRasterCreate },
{"rsnProgramVertexCreate",           "(JLjava/lang/String;[Ljava/lang/String;[J)J",              (void*)nProgramVertexCreate },

{"rsnContextBindRootScript",         "(JJ)V",                                 (void*)nContextBindRootScript },
{"rsnContextBindProgramStore",       "(JJ)V",                                 (void*)nContextBindProgramStore },
{"rsnContextBindProgramFragment",    "(JJ)V",                                 (void*)nContextBindProgramFragment },
{"rsnContextBindProgramVertex",      "(JJ)V",                                 (void*)nContextBindProgramVertex },
{"rsnContextBindProgramRaster",      "(JJ)V",                                 (void*)nContextBindProgramRaster },

{"rsnSamplerCreate",                 "(JIIIIIF)J",                            (void*)nSamplerCreate },

{"rsnPathCreate",                    "(JIZJJF)J",                             (void*)nPathCreate },
{"rsnMeshCreate",                    "(J[J[J[I)J",                            (void*)nMeshCreate },

{"rsnMeshGetVertexBufferCount",      "(JJ)I",                                 (void*)nMeshGetVertexBufferCount },
{"rsnMeshGetIndexCount",             "(JJ)I",                                 (void*)nMeshGetIndexCount },
{"rsnMeshGetVertices",               "(JJ[JI)V",                              (void*)nMeshGetVertices },
{"rsnMeshGetIndices",                "(JJ[J[II)V",                            (void*)nMeshGetIndices },

{"rsnSystemGetPointerSize",          "()I",                                   (void*)nSystemGetPointerSize },
};

static int registerFuncs(JNIEnv *_env)
{
    return android::AndroidRuntime::registerNativeMethods(
            _env, classPathName, methods, NELEM(methods));
}

// ---------------------------------------------------------------------------

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = nullptr;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed\n");
        goto bail;
    }
    assert(env != nullptr);

    if (registerFuncs(env) < 0) {
        ALOGE("ERROR: Renderscript native registration failed\n");
        goto bail;
    }

    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

bail:
    return result;
}
