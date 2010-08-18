/*
 * Copyright (C) 2006 The Android Open Source Project
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

#include <surfaceflinger/Surface.h>

#include <core/SkBitmap.h>
#include <core/SkPixelRef.h>
#include <core/SkStream.h>
#include <core/SkTemplates.h>
#include <images/SkImageDecoder.h>

#include <utils/Asset.h>
#include <utils/ResourceTypes.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <RenderScript.h>
#include <RenderScriptEnv.h>

//#define LOG_API LOGE
#define LOG_API(...)

using namespace android;

// ---------------------------------------------------------------------------

static void doThrow(JNIEnv* env, const char* exc, const char* msg = NULL)
{
    jclass npeClazz = env->FindClass(exc);
    env->ThrowNew(npeClazz, msg);
}

static jfieldID gContextId = 0;
static jfieldID gNativeBitmapID = 0;
static jfieldID gTypeNativeCache = 0;

static RsElement g_A_8 = NULL;
static RsElement g_RGBA_4444 = NULL;
static RsElement g_RGBA_8888 = NULL;
static RsElement g_RGB_565 = NULL;

static void _nInit(JNIEnv *_env, jclass _this)
{
    gContextId             = _env->GetFieldID(_this, "mContext", "I");

    jclass bitmapClass = _env->FindClass("android/graphics/Bitmap");
    gNativeBitmapID = _env->GetFieldID(bitmapClass, "mNativeBitmap", "I");

    jclass typeClass = _env->FindClass("android/renderscript/Type");
    gTypeNativeCache = _env->GetFieldID(typeClass, "mNativeCache", "I");
}

static void nInitElements(JNIEnv *_env, jobject _this, jint a8, jint rgba4444, jint rgba8888, jint rgb565)
{
    g_A_8 = reinterpret_cast<RsElement>(a8);
    g_RGBA_4444 = reinterpret_cast<RsElement>(rgba4444);
    g_RGBA_8888 = reinterpret_cast<RsElement>(rgba8888);
    g_RGB_565 = reinterpret_cast<RsElement>(rgb565);
}

// ---------------------------------------------------------------------------

static void
nContextFinish(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextFinish, con(%p)", con);
    rsContextFinish(con);
}

static void
nAssignName(JNIEnv *_env, jobject _this, RsContext con, jint obj, jbyteArray str)
{
    LOG_API("nAssignName, con(%p), obj(%p)", con, (void *)obj);
    jint len = _env->GetArrayLength(str);
    jbyte * cptr = (jbyte *) _env->GetPrimitiveArrayCritical(str, 0);
    rsAssignName(con, (void *)obj, (const char *)cptr, len);
    _env->ReleasePrimitiveArrayCritical(str, cptr, JNI_ABORT);
}

static jstring
nGetName(JNIEnv *_env, jobject _this, RsContext con, jint obj)
{
    LOG_API("nGetName, con(%p), obj(%p)", con, (void *)obj);
    const char *name = NULL;
    rsGetName(con, (void *)obj, &name);
    return _env->NewStringUTF(name);
}

static void
nObjDestroy(JNIEnv *_env, jobject _this, RsContext con, jint obj)
{
    LOG_API("nObjDestroy, con(%p) obj(%p)", con, (void *)obj);
    rsObjDestroy(con, (void *)obj);
}


static jint
nFileOpen(JNIEnv *_env, jobject _this, RsContext con, jbyteArray str)
{
    LOG_API("nFileOpen, con(%p)", con);
    jint len = _env->GetArrayLength(str);
    jbyte * cptr = (jbyte *) _env->GetPrimitiveArrayCritical(str, 0);
    jint ret = (jint)rsFileOpen(con, (const char *)cptr, len);
    _env->ReleasePrimitiveArrayCritical(str, cptr, JNI_ABORT);
    return ret;
}

// ---------------------------------------------------------------------------

static jint
nDeviceCreate(JNIEnv *_env, jobject _this)
{
    LOG_API("nDeviceCreate");
    return (jint)rsDeviceCreate();
}

static void
nDeviceDestroy(JNIEnv *_env, jobject _this, jint dev)
{
    LOG_API("nDeviceDestroy");
    return rsDeviceDestroy((RsDevice)dev);
}

static void
nDeviceSetConfig(JNIEnv *_env, jobject _this, jint dev, jint p, jint value)
{
    LOG_API("nDeviceSetConfig  dev(%p), param(%i), value(%i)", (void *)dev, p, value);
    return rsDeviceSetConfig((RsDevice)dev, (RsDeviceParam)p, value);
}

static jint
nContextCreate(JNIEnv *_env, jobject _this, jint dev, jint ver)
{
    LOG_API("nContextCreate");
    return (jint)rsContextCreate((RsDevice)dev, ver);
}

static jint
nContextCreateGL(JNIEnv *_env, jobject _this, jint dev, jint ver, jboolean useDepth)
{
    LOG_API("nContextCreateGL");
    return (jint)rsContextCreateGL((RsDevice)dev, ver, useDepth);
}

static void
nContextSetPriority(JNIEnv *_env, jobject _this, RsContext con, jint p)
{
    LOG_API("ContextSetPriority, con(%p), priority(%i)", con, p);
    rsContextSetPriority(con, p);
}



static void
nContextSetSurface(JNIEnv *_env, jobject _this, RsContext con, jint width, jint height, jobject wnd)
{
    LOG_API("nContextSetSurface, con(%p), width(%i), height(%i), surface(%p)", con, width, height, (Surface *)wnd);

    Surface * window = NULL;
    if (wnd == NULL) {

    } else {
        jclass surface_class = _env->FindClass("android/view/Surface");
        jfieldID surfaceFieldID = _env->GetFieldID(surface_class, ANDROID_VIEW_SURFACE_JNI_ID, "I");
        window = (Surface*)_env->GetIntField(wnd, surfaceFieldID);
    }

    rsContextSetSurface(con, width, height, window);
}

static void
nContextDestroy(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextDestroy, con(%p)", con);
    rsContextDestroy(con);
}

static void
nContextDump(JNIEnv *_env, jobject _this, RsContext con, jint bits)
{
    LOG_API("nContextDump, con(%p)  bits(%i)", (RsContext)con, bits);
    rsContextDump((RsContext)con, bits);
}

static void
nContextPause(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextPause, con(%p)", con);
    rsContextPause(con);
}

static void
nContextResume(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextResume, con(%p)", con);
    rsContextResume(con);
}

static jint
nContextGetMessage(JNIEnv *_env, jobject _this, RsContext con, jintArray data, jboolean wait)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nContextGetMessage, con(%p), len(%i)", con, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    size_t receiveLen;
    int id = rsContextGetMessage(con, ptr, &receiveLen, len * 4, wait);
    if (!id && receiveLen) {
        LOGE("message receive buffer too small.  %i", receiveLen);
    }
    _env->ReleaseIntArrayElements(data, ptr, 0);
    return id;
}

static void nContextInitToClient(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextInitToClient, con(%p)", con);
    rsContextInitToClient(con);
}

static void nContextDeinitToClient(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextDeinitToClient, con(%p)", con);
    rsContextDeinitToClient(con);
}


static jint
nElementCreate(JNIEnv *_env, jobject _this, RsContext con, jint type, jint kind, jboolean norm, jint size)
{
    LOG_API("nElementCreate, con(%p), type(%i), kind(%i), norm(%i), size(%i)", con, type, kind, norm, size);
    return (jint)rsElementCreate(con, (RsDataType)type, (RsDataKind)kind, norm, size);
}

static jint
nElementCreate2(JNIEnv *_env, jobject _this, RsContext con, jintArray _ids, jobjectArray _names)
{
    int fieldCount = _env->GetArrayLength(_ids);
    LOG_API("nElementCreate2, con(%p)", con);

    jint *ids = _env->GetIntArrayElements(_ids, NULL);
    const char ** nameArray = (const char **)calloc(fieldCount, sizeof(char *));
    size_t* sizeArray = (size_t*)calloc(fieldCount, sizeof(size_t));

    for (int ct=0; ct < fieldCount; ct++) {
        jstring s = (jstring)_env->GetObjectArrayElement(_names, ct);
        nameArray[ct] = _env->GetStringUTFChars(s, NULL);
        sizeArray[ct] = _env->GetStringUTFLength(s);
    }
    jint id = (jint)rsElementCreate2(con, fieldCount, (RsElement *)ids, nameArray, sizeArray);
    for (int ct=0; ct < fieldCount; ct++) {
        jstring s = (jstring)_env->GetObjectArrayElement(_names, ct);
        _env->ReleaseStringUTFChars(s, nameArray[ct]);
    }
    _env->ReleaseIntArrayElements(_ids, ids, JNI_ABORT);
    free(nameArray);
    free(sizeArray);
    return (jint)id;
}

static void
nElementGetNativeData(JNIEnv *_env, jobject _this, RsContext con, jint id, jintArray _elementData)
{
    int dataSize = _env->GetArrayLength(_elementData);
    LOG_API("nElementGetNativeData, con(%p)", con);

    // we will pack mType; mKind; mNormalized; mVectorSize; NumSubElements
    assert(dataSize == 5);

    uint32_t elementData[5];
    rsElementGetNativeData(con, (RsElement)id, elementData, dataSize);

    for(jint i = 0; i < dataSize; i ++) {
        _env->SetIntArrayRegion(_elementData, i, 1, (const jint*)&elementData[i]);
    }
}


static void
nElementGetSubElements(JNIEnv *_env, jobject _this, RsContext con, jint id, jintArray _IDs, jobjectArray _names)
{
    int dataSize = _env->GetArrayLength(_IDs);
    LOG_API("nElementGetSubElements, con(%p)", con);

    uint32_t *ids = (uint32_t *)malloc((uint32_t)dataSize * sizeof(uint32_t));
    const char **names = (const char **)malloc((uint32_t)dataSize * sizeof(const char *));

    rsElementGetSubElements(con, (RsElement)id, ids, names, (uint32_t)dataSize);

    for(jint i = 0; i < dataSize; i ++) {
        _env->SetObjectArrayElement(_names, i, _env->NewStringUTF(names[i]));
        _env->SetIntArrayRegion(_IDs, i, 1, (const jint*)&ids[i]);
    }

    free(ids);
    free(names);
}

// -----------------------------------

static void
nTypeBegin(JNIEnv *_env, jobject _this, RsContext con, jint eID)
{
    LOG_API("nTypeBegin, con(%p) e(%p)", con, (RsElement)eID);
    rsTypeBegin(con, (RsElement)eID);
}

static void
nTypeAdd(JNIEnv *_env, jobject _this, RsContext con, jint dim, jint val)
{
    LOG_API("nTypeAdd, con(%p) dim(%i), val(%i)", con, dim, val);
    rsTypeAdd(con, (RsDimension)dim, val);
}

static jint
nTypeCreate(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nTypeCreate, con(%p)", con);
    return (jint)rsTypeCreate(con);
}

static void
nTypeGetNativeData(JNIEnv *_env, jobject _this, RsContext con, jint id, jintArray _typeData)
{
    // We are packing 6 items: mDimX; mDimY; mDimZ;
    // mDimLOD; mDimFaces; mElement; into typeData
    int elementCount = _env->GetArrayLength(_typeData);

    assert(elementCount == 6);
    LOG_API("nTypeCreate, con(%p)", con);

    uint32_t typeData[6];
    rsTypeGetNativeData(con, (RsType)id, typeData, 6);

    for(jint i = 0; i < elementCount; i ++) {
        _env->SetIntArrayRegion(_typeData, i, 1, (const jint*)&typeData[i]);
    }
}

static void * SF_LoadInt(JNIEnv *_env, jobject _obj, jfieldID _field, void *buffer)
{
    ((int32_t *)buffer)[0] = _env->GetIntField(_obj, _field);
    return ((uint8_t *)buffer) + 4;
}

static void * SF_LoadShort(JNIEnv *_env, jobject _obj, jfieldID _field, void *buffer)
{
    ((int16_t *)buffer)[0] = _env->GetShortField(_obj, _field);
    return ((uint8_t *)buffer) + 2;
}

static void * SF_LoadByte(JNIEnv *_env, jobject _obj, jfieldID _field, void *buffer)
{
    ((int8_t *)buffer)[0] = _env->GetByteField(_obj, _field);
    return ((uint8_t *)buffer) + 1;
}

static void * SF_LoadFloat(JNIEnv *_env, jobject _obj, jfieldID _field, void *buffer)
{
    ((float *)buffer)[0] = _env->GetFloatField(_obj, _field);
    return ((uint8_t *)buffer) + 4;
}

static void * SF_SaveInt(JNIEnv *_env, jobject _obj, jfieldID _field, void *buffer)
{
    _env->SetIntField(_obj, _field, ((int32_t *)buffer)[0]);
    return ((uint8_t *)buffer) + 4;
}

static void * SF_SaveShort(JNIEnv *_env, jobject _obj, jfieldID _field, void *buffer)
{
    _env->SetShortField(_obj, _field, ((int16_t *)buffer)[0]);
    return ((uint8_t *)buffer) + 2;
}

static void * SF_SaveByte(JNIEnv *_env, jobject _obj, jfieldID _field, void *buffer)
{
    _env->SetByteField(_obj, _field, ((int8_t *)buffer)[0]);
    return ((uint8_t *)buffer) + 1;
}

static void * SF_SaveFloat(JNIEnv *_env, jobject _obj, jfieldID _field, void *buffer)
{
    _env->SetFloatField(_obj, _field, ((float *)buffer)[0]);
    return ((uint8_t *)buffer) + 4;
}

struct TypeFieldCache {
    jfieldID field;
    int bits;
    void * (*ptr)(JNIEnv *, jobject, jfieldID, void *buffer);
    void * (*readPtr)(JNIEnv *, jobject, jfieldID, void *buffer);
};

struct TypeCache {
    int fieldCount;
    int size;
    TypeFieldCache fields[1];
};

//{"nTypeFinalDestroy",              "(Landroid/renderscript/Type;)V",       (void*)nTypeFinalDestroy },
static void
nTypeFinalDestroy(JNIEnv *_env, jobject _this, RsContext con, jobject _type)
{
    TypeCache *tc = (TypeCache *)_env->GetIntField(_type, gTypeNativeCache);
    free(tc);
}

// native void nTypeSetupFields(Type t, int[] types, int[] bits, Field[] IDs);
static void
nTypeSetupFields(JNIEnv *_env, jobject _this, RsContext con, jobject _type, jintArray _types, jintArray _bits, jobjectArray _IDs)
{
    int fieldCount = _env->GetArrayLength(_types);
    size_t structSize = sizeof(TypeCache) + (sizeof(TypeFieldCache) * (fieldCount-1));
    TypeCache *tc = (TypeCache *)malloc(structSize);
    memset(tc, 0, structSize);

    TypeFieldCache *tfc = &tc->fields[0];
    tc->fieldCount = fieldCount;
    _env->SetIntField(_type, gTypeNativeCache, (jint)tc);

    jint *fType = _env->GetIntArrayElements(_types, NULL);
    jint *fBits = _env->GetIntArrayElements(_bits, NULL);
    for (int ct=0; ct < fieldCount; ct++) {
        jobject field = _env->GetObjectArrayElement(_IDs, ct);
        tfc[ct].field = _env->FromReflectedField(field);
        tfc[ct].bits = fBits[ct];

        switch(fType[ct]) {
        case RS_TYPE_FLOAT_32:
            tfc[ct].ptr = SF_LoadFloat;
            tfc[ct].readPtr = SF_SaveFloat;
            break;
        case RS_TYPE_UNSIGNED_32:
        case RS_TYPE_SIGNED_32:
            tfc[ct].ptr = SF_LoadInt;
            tfc[ct].readPtr = SF_SaveInt;
            break;
        case RS_TYPE_UNSIGNED_16:
        case RS_TYPE_SIGNED_16:
            tfc[ct].ptr = SF_LoadShort;
            tfc[ct].readPtr = SF_SaveShort;
            break;
        case RS_TYPE_UNSIGNED_8:
        case RS_TYPE_SIGNED_8:
            tfc[ct].ptr = SF_LoadByte;
            tfc[ct].readPtr = SF_SaveByte;
            break;
        }
        tc->size += 4;
    }

    _env->ReleaseIntArrayElements(_types, fType, JNI_ABORT);
    _env->ReleaseIntArrayElements(_bits, fBits, JNI_ABORT);
}


// -----------------------------------

static jint
nAllocationCreateTyped(JNIEnv *_env, jobject _this, RsContext con, jint e)
{
    LOG_API("nAllocationCreateTyped, con(%p), e(%p)", con, (RsElement)e);
    return (jint) rsAllocationCreateTyped(con, (RsElement)e);
}

static void
nAllocationUploadToTexture(JNIEnv *_env, jobject _this, RsContext con, jint a, jboolean genMip, jint mip)
{
    LOG_API("nAllocationUploadToTexture, con(%p), a(%p), genMip(%i), mip(%i)", con, (RsAllocation)a, genMip, mip);
    rsAllocationUploadToTexture(con, (RsAllocation)a, genMip, mip);
}

static void
nAllocationUploadToBufferObject(JNIEnv *_env, jobject _this, RsContext con, jint a)
{
    LOG_API("nAllocationUploadToBufferObject, con(%p), a(%p)", con, (RsAllocation)a);
    rsAllocationUploadToBufferObject(con, (RsAllocation)a);
}

static RsElement SkBitmapToPredefined(SkBitmap::Config cfg)
{
    switch (cfg) {
    case SkBitmap::kA8_Config:
        return g_A_8;
    case SkBitmap::kARGB_4444_Config:
        return g_RGBA_4444;
    case SkBitmap::kARGB_8888_Config:
        return g_RGBA_8888;
    case SkBitmap::kRGB_565_Config:
        return g_RGB_565;

    default:
        break;
    }
    // If we don't have a conversion mark it as a user type.
    LOGE("Unsupported bitmap type");
    return NULL;
}

static int
nAllocationCreateFromBitmap(JNIEnv *_env, jobject _this, RsContext con, jint dstFmt, jboolean genMips, jobject jbitmap)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetIntField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);
    SkBitmap::Config config = bitmap.getConfig();

    RsElement e = SkBitmapToPredefined(config);
    if (e) {
        bitmap.lockPixels();
        const int w = bitmap.width();
        const int h = bitmap.height();
        const void* ptr = bitmap.getPixels();
        jint id = (jint)rsAllocationCreateFromBitmap(con, w, h, (RsElement)dstFmt, e, genMips, ptr);
        bitmap.unlockPixels();
        return id;
    }
    return 0;
}

static void ReleaseBitmapCallback(void *bmp)
{
    SkBitmap const * nativeBitmap = (SkBitmap const *)bmp;
    nativeBitmap->unlockPixels();
}

static int
nAllocationCreateBitmapRef(JNIEnv *_env, jobject _this, RsContext con, jint type, jobject jbitmap)
{
    SkBitmap * nativeBitmap =
            (SkBitmap *)_env->GetIntField(jbitmap, gNativeBitmapID);


    nativeBitmap->lockPixels();
    void* ptr = nativeBitmap->getPixels();
    jint id = (jint)rsAllocationCreateBitmapRef(con, (RsType)type, ptr, nativeBitmap, ReleaseBitmapCallback);
    return id;
}

static int
nAllocationCreateFromAssetStream(JNIEnv *_env, jobject _this, RsContext con, jint dstFmt, jboolean genMips, jint native_asset)
{
    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    SkBitmap bitmap;
    SkImageDecoder::DecodeMemory(asset->getBuffer(false), asset->getLength(),
            &bitmap, SkBitmap::kNo_Config, SkImageDecoder::kDecodePixels_Mode);

    SkBitmap::Config config = bitmap.getConfig();

    RsElement e = SkBitmapToPredefined(config);

    if (e) {
        bitmap.lockPixels();
        const int w = bitmap.width();
        const int h = bitmap.height();
        const void* ptr = bitmap.getPixels();
        jint id = (jint)rsAllocationCreateFromBitmap(con, w, h, (RsElement)dstFmt, e, genMips, ptr);
        bitmap.unlockPixels();
        return id;
    }
    return 0;
}

static int
nAllocationCreateFromBitmapBoxed(JNIEnv *_env, jobject _this, RsContext con, jint dstFmt, jboolean genMips, jobject jbitmap)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetIntField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);
    SkBitmap::Config config = bitmap.getConfig();

    RsElement e = SkBitmapToPredefined(config);

    if (e) {
        bitmap.lockPixels();
        const int w = bitmap.width();
        const int h = bitmap.height();
        const void* ptr = bitmap.getPixels();
        jint id = (jint)rsAllocationCreateFromBitmapBoxed(con, w, h, (RsElement)dstFmt, e, genMips, ptr);
        bitmap.unlockPixels();
        return id;
    }
    return 0;
}


static void
nAllocationSubData1D_i(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint offset, jint count, jintArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DSubData_i, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAllocation1DSubData(con, (RsAllocation)alloc, offset, count, ptr, sizeBytes);
    _env->ReleaseIntArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationSubData1D_s(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint offset, jint count, jshortArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DSubData_s, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jshort *ptr = _env->GetShortArrayElements(data, NULL);
    rsAllocation1DSubData(con, (RsAllocation)alloc, offset, count, ptr, sizeBytes);
    _env->ReleaseShortArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationSubData1D_b(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint offset, jint count, jbyteArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DSubData_b, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsAllocation1DSubData(con, (RsAllocation)alloc, offset, count, ptr, sizeBytes);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationSubData1D_f(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint offset, jint count, jfloatArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DSubData_f, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAllocation1DSubData(con, (RsAllocation)alloc, offset, count, ptr, sizeBytes);
    _env->ReleaseFloatArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationSubData2D_i(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint xoff, jint yoff, jint w, jint h, jintArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation2DSubData_i, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, w, h, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAllocation2DSubData(con, (RsAllocation)alloc, xoff, yoff, w, h, ptr, sizeBytes);
    _env->ReleaseIntArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationSubData2D_f(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint xoff, jint yoff, jint w, jint h, jfloatArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation2DSubData_i, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, w, h, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAllocation2DSubData(con, (RsAllocation)alloc, xoff, yoff, w, h, ptr, sizeBytes);
    _env->ReleaseFloatArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationRead_i(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jintArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocationRead_i, con(%p), alloc(%p), len(%i)", con, (RsAllocation)alloc, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAllocationRead(con, (RsAllocation)alloc, ptr);
    _env->ReleaseIntArrayElements(data, ptr, 0);
}

static void
nAllocationRead_f(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jfloatArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocationRead_f, con(%p), alloc(%p), len(%i)", con, (RsAllocation)alloc, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAllocationRead(con, (RsAllocation)alloc, ptr);
    _env->ReleaseFloatArrayElements(data, ptr, 0);
}


//{"nAllocationDataFromObject",      "(ILandroid/renderscript/Type;Ljava/lang/Object;)V",   (void*)nAllocationDataFromObject },
static void
nAllocationSubDataFromObject(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jobject _type, jint offset, jobject _o)
{
    LOG_API("nAllocationDataFromObject con(%p), alloc(%p)", con, (RsAllocation)alloc);

    const TypeCache *tc = (TypeCache *)_env->GetIntField(_type, gTypeNativeCache);

    void * bufAlloc = malloc(tc->size);
    void * buf = bufAlloc;
    for (int ct=0; ct < tc->fieldCount; ct++) {
        const TypeFieldCache *tfc = &tc->fields[ct];
        buf = tfc->ptr(_env, _o, tfc->field, buf);
    }
    rsAllocation1DSubData(con, (RsAllocation)alloc, offset, 1, bufAlloc, tc->size);
    free(bufAlloc);
}

static void
nAllocationSubReadFromObject(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jobject _type, jint offset, jobject _o)
{
    LOG_API("nAllocationReadFromObject con(%p), alloc(%p)", con, (RsAllocation)alloc);

    assert(offset == 0);

    const TypeCache *tc = (TypeCache *)_env->GetIntField(_type, gTypeNativeCache);

    void * bufAlloc = malloc(tc->size);
    void * buf = bufAlloc;
    rsAllocationRead(con, (RsAllocation)alloc, bufAlloc);

    for (int ct=0; ct < tc->fieldCount; ct++) {
        const TypeFieldCache *tfc = &tc->fields[ct];
        buf = tfc->readPtr(_env, _o, tfc->field, buf);
    }
    free(bufAlloc);
}

static jint
nAllocationGetType(JNIEnv *_env, jobject _this, RsContext con, jint a)
{
    LOG_API("nAllocationGetType, con(%p), a(%p)", con, (RsAllocation)a);
    return (jint) rsAllocationGetType(con, (RsAllocation)a);
}

// -----------------------------------

static int
nFileA3DCreateFromAssetStream(JNIEnv *_env, jobject _this, RsContext con, jint native_asset)
{
    LOGV("______nFileA3D %u", (uint32_t) native_asset);

    Asset* asset = reinterpret_cast<Asset*>(native_asset);

    jint id = (jint)rsFileA3DCreateFromAssetStream(con, asset->getBuffer(false), asset->getLength());
    return id;
}

static int
nFileA3DGetNumIndexEntries(JNIEnv *_env, jobject _this, RsContext con, jint fileA3D)
{
    int32_t numEntries = 0;
    rsFileA3DGetNumIndexEntries(con, &numEntries, (RsFile)fileA3D);
    return numEntries;
}

static void
nFileA3DGetIndexEntries(JNIEnv *_env, jobject _this, RsContext con, jint fileA3D, jint numEntries, jintArray _ids, jobjectArray _entries)
{
    LOGV("______nFileA3D %u", (uint32_t) fileA3D);
    RsFileIndexEntry *fileEntries = (RsFileIndexEntry*)malloc((uint32_t)numEntries * sizeof(RsFileIndexEntry));

    rsFileA3DGetIndexEntries(con, fileEntries, (uint32_t)numEntries, (RsFile)fileA3D);

    for(jint i = 0; i < numEntries; i ++) {
        _env->SetObjectArrayElement(_entries, i, _env->NewStringUTF(fileEntries[i].objectName));
        _env->SetIntArrayRegion(_ids, i, 1, (const jint*)&fileEntries[i].classID);
    }

    free(fileEntries);
}

static int
nFileA3DGetEntryByIndex(JNIEnv *_env, jobject _this, RsContext con, jint fileA3D, jint index)
{
    LOGV("______nFileA3D %u", (uint32_t) fileA3D);
    jint id = (jint)rsFileA3DGetEntryByIndex(con, (uint32_t)index, (RsFile)fileA3D);
    return id;
}

// -----------------------------------

static int
nFontCreateFromFile(JNIEnv *_env, jobject _this, RsContext con, jstring fileName, jint fontSize, jint dpi)
{
    const char* fileNameUTF = _env->GetStringUTFChars(fileName, NULL);

    jint id = (jint)rsFontCreateFromFile(con, fileNameUTF, fontSize, dpi);
    return id;
}


// -----------------------------------

static void
nAdapter1DBindAllocation(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jint alloc)
{
    LOG_API("nAdapter1DBindAllocation, con(%p), adapter(%p), alloc(%p)", con, (RsAdapter1D)adapter, (RsAllocation)alloc);
    rsAdapter1DBindAllocation(con, (RsAdapter1D)adapter, (RsAllocation)alloc);
}

static void
nAdapter1DSetConstraint(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jint dim, jint value)
{
    LOG_API("nAdapter1DSetConstraint, con(%p), adapter(%p), dim(%i), value(%i)", con, (RsAdapter1D)adapter, dim, value);
    rsAdapter1DSetConstraint(con, (RsAdapter1D)adapter, (RsDimension)dim, value);
}

static void
nAdapter1DData_i(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jintArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter1DData_i, con(%p), adapter(%p), len(%i)", con, (RsAdapter1D)adapter, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAdapter1DData(con, (RsAdapter1D)adapter, ptr);
    _env->ReleaseIntArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter1DSubData_i(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jint offset, jint count, jintArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter1DSubData_i, con(%p), adapter(%p), offset(%i), count(%i), len(%i)", con, (RsAdapter1D)adapter, offset, count, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAdapter1DSubData(con, (RsAdapter1D)adapter, offset, count, ptr);
    _env->ReleaseIntArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter1DData_f(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jfloatArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter1DData_f, con(%p), adapter(%p), len(%i)", con, (RsAdapter1D)adapter, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAdapter1DData(con, (RsAdapter1D)adapter, ptr);
    _env->ReleaseFloatArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter1DSubData_f(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jint offset, jint count, jfloatArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter1DSubData_f, con(%p), adapter(%p), offset(%i), count(%i), len(%i)", con, (RsAdapter1D)adapter, offset, count, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAdapter1DSubData(con, (RsAdapter1D)adapter, offset, count, ptr);
    _env->ReleaseFloatArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static jint
nAdapter1DCreate(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nAdapter1DCreate, con(%p)", con);
    return (jint)rsAdapter1DCreate(con);
}

// -----------------------------------

static void
nAdapter2DBindAllocation(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jint alloc)
{
    LOG_API("nAdapter2DBindAllocation, con(%p), adapter(%p), alloc(%p)", con, (RsAdapter2D)adapter, (RsAllocation)alloc);
    rsAdapter2DBindAllocation(con, (RsAdapter2D)adapter, (RsAllocation)alloc);
}

static void
nAdapter2DSetConstraint(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jint dim, jint value)
{
    LOG_API("nAdapter2DSetConstraint, con(%p), adapter(%p), dim(%i), value(%i)", con, (RsAdapter2D)adapter, dim, value);
    rsAdapter2DSetConstraint(con, (RsAdapter2D)adapter, (RsDimension)dim, value);
}

static void
nAdapter2DData_i(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jintArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter2DData_i, con(%p), adapter(%p), len(%i)", con, (RsAdapter2D)adapter, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAdapter2DData(con, (RsAdapter2D)adapter, ptr);
    _env->ReleaseIntArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter2DData_f(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jfloatArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter2DData_f, con(%p), adapter(%p), len(%i)", con, (RsAdapter2D)adapter, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAdapter2DData(con, (RsAdapter2D)adapter, ptr);
    _env->ReleaseFloatArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter2DSubData_i(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jint xoff, jint yoff, jint w, jint h, jintArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter2DSubData_i, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)",
            con, (RsAdapter2D)adapter, xoff, yoff, w, h, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAdapter2DSubData(con, (RsAdapter2D)adapter, xoff, yoff, w, h, ptr);
    _env->ReleaseIntArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter2DSubData_f(JNIEnv *_env, jobject _this, RsContext con, jint adapter, jint xoff, jint yoff, jint w, jint h, jfloatArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter2DSubData_f, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)",
            con, (RsAdapter2D)adapter, xoff, yoff, w, h, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAdapter2DSubData(con, (RsAdapter1D)adapter, xoff, yoff, w, h, ptr);
    _env->ReleaseFloatArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static jint
nAdapter2DCreate(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nAdapter2DCreate, con(%p)", con);
    return (jint)rsAdapter2DCreate(con);
}

// -----------------------------------

static void
nScriptBindAllocation(JNIEnv *_env, jobject _this, RsContext con, jint script, jint alloc, jint slot)
{
    LOG_API("nScriptBindAllocation, con(%p), script(%p), alloc(%p), slot(%i)", con, (RsScript)script, (RsAllocation)alloc, slot);
    rsScriptBindAllocation(con, (RsScript)script, (RsAllocation)alloc, slot);
}

static void
nScriptSetVarI(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, jint val)
{
    LOG_API("nScriptSetVarI, con(%p), s(%p), slot(%i), val(%i), b(%f), a(%f)", con, (void *)script, slot, val);
    rsScriptSetVarI(con, (RsScript)script, slot, val);
}

static void
nScriptSetVarF(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, float val)
{
    LOG_API("nScriptSetVarI, con(%p), s(%p), slot(%i), val(%i), b(%f), a(%f)", con, (void *)script, slot, val);
    rsScriptSetVarF(con, (RsScript)script, slot, val);
}

static void
nScriptSetVarV(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, jbyteArray data)
{
    LOG_API("nScriptSetVarV, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsScriptSetVarV(con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}


static void
nScriptSetTimeZone(JNIEnv *_env, jobject _this, RsContext con, jint script, jbyteArray timeZone)
{
    LOG_API("nScriptCSetTimeZone, con(%p), s(%p), timeZone(%s)", con, (void *)script, (const char *)timeZone);

    jint length = _env->GetArrayLength(timeZone);
    jbyte* timeZone_ptr;
    timeZone_ptr = (jbyte *) _env->GetPrimitiveArrayCritical(timeZone, (jboolean *)0);

    rsScriptSetTimeZone(con, (RsScript)script, (const char *)timeZone_ptr, length);

    if (timeZone_ptr) {
        _env->ReleasePrimitiveArrayCritical(timeZone, timeZone_ptr, 0);
    }
}

static void
nScriptInvoke(JNIEnv *_env, jobject _this, RsContext con, jint obj, jint slot)
{
    LOG_API("nScriptInvoke, con(%p), script(%p)", con, (void *)obj);
    rsScriptInvoke(con, (RsScript)obj, slot);
}

static void
nScriptInvokeV(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, jbyteArray data)
{
    LOG_API("nScriptInvokeV, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsScriptInvokeV(con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}


// -----------------------------------

static void
nScriptCBegin(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nScriptCBegin, con(%p)", con);
    rsScriptCBegin(con);
}

static void
nScriptCSetScript(JNIEnv *_env, jobject _this, RsContext con, jbyteArray scriptRef,
                  jint offset, jint length)
{
    LOG_API("!!! nScriptCSetScript, con(%p)", con);
    jint _exception = 0;
    jint remaining;
    jbyte* script_base = 0;
    jbyte* script_ptr;
    if (!scriptRef) {
        _exception = 1;
        //_env->ThrowNew(IAEClass, "script == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        //_env->ThrowNew(IAEClass, "offset < 0");
        goto exit;
    }
    if (length < 0) {
        _exception = 1;
        //_env->ThrowNew(IAEClass, "length < 0");
        goto exit;
    }
    remaining = _env->GetArrayLength(scriptRef) - offset;
    if (remaining < length) {
        _exception = 1;
        //_env->ThrowNew(IAEClass, "length > script.length - offset");
        goto exit;
    }
    script_base = (jbyte *)
        _env->GetPrimitiveArrayCritical(scriptRef, (jboolean *)0);
    script_ptr = script_base + offset;

    rsScriptCSetText(con, (const char *)script_ptr, length);

exit:
    if (script_base) {
        _env->ReleasePrimitiveArrayCritical(scriptRef, script_base,
                _exception ? JNI_ABORT: 0);
    }
}

static jint
nScriptCCreate(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nScriptCCreate, con(%p)", con);
    return (jint)rsScriptCCreate(con);
}

// ---------------------------------------------------------------------------

static void
nProgramStoreBegin(JNIEnv *_env, jobject _this, RsContext con, jint in, jint out)
{
    LOG_API("nProgramStoreBegin, con(%p), in(%p), out(%p)", con, (RsElement)in, (RsElement)out);
    rsProgramStoreBegin(con, (RsElement)in, (RsElement)out);
}

static void
nProgramStoreDepthFunc(JNIEnv *_env, jobject _this, RsContext con, jint func)
{
    LOG_API("nProgramStoreDepthFunc, con(%p), func(%i)", con, func);
    rsProgramStoreDepthFunc(con, (RsDepthFunc)func);
}

static void
nProgramStoreDepthMask(JNIEnv *_env, jobject _this, RsContext con, jboolean enable)
{
    LOG_API("nProgramStoreDepthMask, con(%p), enable(%i)", con, enable);
    rsProgramStoreDepthMask(con, enable);
}

static void
nProgramStoreColorMask(JNIEnv *_env, jobject _this, RsContext con, jboolean r, jboolean g, jboolean b, jboolean a)
{
    LOG_API("nProgramStoreColorMask, con(%p), r(%i), g(%i), b(%i), a(%i)", con, r, g, b, a);
    rsProgramStoreColorMask(con, r, g, b, a);
}

static void
nProgramStoreBlendFunc(JNIEnv *_env, jobject _this, RsContext con, int src, int dst)
{
    LOG_API("nProgramStoreBlendFunc, con(%p), src(%i), dst(%i)", con, src, dst);
    rsProgramStoreBlendFunc(con, (RsBlendSrcFunc)src, (RsBlendDstFunc)dst);
}

static void
nProgramStoreDither(JNIEnv *_env, jobject _this, RsContext con, jboolean enable)
{
    LOG_API("nProgramStoreDither, con(%p), enable(%i)", con, enable);
    rsProgramStoreDither(con, enable);
}

static jint
nProgramStoreCreate(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nProgramStoreCreate, con(%p)", con);
    return (jint)rsProgramStoreCreate(con);
}

// ---------------------------------------------------------------------------

static void
nProgramBindConstants(JNIEnv *_env, jobject _this, RsContext con, jint vpv, jint slot, jint a)
{
    LOG_API("nProgramBindConstants, con(%p), vpf(%p), sloat(%i), a(%p)", con, (RsProgramVertex)vpv, slot, (RsAllocation)a);
    rsProgramBindConstants(con, (RsProgram)vpv, slot, (RsAllocation)a);
}

static void
nProgramBindTexture(JNIEnv *_env, jobject _this, RsContext con, jint vpf, jint slot, jint a)
{
    LOG_API("nProgramBindTexture, con(%p), vpf(%p), slot(%i), a(%p)", con, (RsProgramFragment)vpf, slot, (RsAllocation)a);
    rsProgramBindTexture(con, (RsProgramFragment)vpf, slot, (RsAllocation)a);
}

static void
nProgramBindSampler(JNIEnv *_env, jobject _this, RsContext con, jint vpf, jint slot, jint a)
{
    LOG_API("nProgramBindSampler, con(%p), vpf(%p), slot(%i), a(%p)", con, (RsProgramFragment)vpf, slot, (RsSampler)a);
    rsProgramBindSampler(con, (RsProgramFragment)vpf, slot, (RsSampler)a);
}

// ---------------------------------------------------------------------------

static jint
nProgramFragmentCreate(JNIEnv *_env, jobject _this, RsContext con, jintArray params)
{
    jint *paramPtr = _env->GetIntArrayElements(params, NULL);
    jint paramLen = _env->GetArrayLength(params);

    LOG_API("nProgramFragmentCreate, con(%p), paramLen(%i)", con, paramLen);

    jint ret = (jint)rsProgramFragmentCreate(con, (uint32_t *)paramPtr, paramLen);
    _env->ReleaseIntArrayElements(params, paramPtr, JNI_ABORT);
    return ret;
}

static jint
nProgramFragmentCreate2(JNIEnv *_env, jobject _this, RsContext con, jstring shader, jintArray params)
{
    const char* shaderUTF = _env->GetStringUTFChars(shader, NULL);
    jint shaderLen = _env->GetStringUTFLength(shader);
    jint *paramPtr = _env->GetIntArrayElements(params, NULL);
    jint paramLen = _env->GetArrayLength(params);

    LOG_API("nProgramFragmentCreate2, con(%p), shaderLen(%i), paramLen(%i)", con, shaderLen, paramLen);

    jint ret = (jint)rsProgramFragmentCreate2(con, shaderUTF, shaderLen, (uint32_t *)paramPtr, paramLen);
    _env->ReleaseStringUTFChars(shader, shaderUTF);
    _env->ReleaseIntArrayElements(params, paramPtr, JNI_ABORT);
    return ret;
}


// ---------------------------------------------------------------------------

static jint
nProgramVertexCreate(JNIEnv *_env, jobject _this, RsContext con, jboolean texMat)
{
    LOG_API("nProgramVertexCreate, con(%p), texMat(%i)", con, texMat);
    return (jint)rsProgramVertexCreate(con, texMat);
}

static jint
nProgramVertexCreate2(JNIEnv *_env, jobject _this, RsContext con, jstring shader, jintArray params)
{
    const char* shaderUTF = _env->GetStringUTFChars(shader, NULL);
    jint shaderLen = _env->GetStringUTFLength(shader);
    jint *paramPtr = _env->GetIntArrayElements(params, NULL);
    jint paramLen = _env->GetArrayLength(params);

    LOG_API("nProgramVertexCreate2, con(%p), shaderLen(%i), paramLen(%i)", con, shaderLen, paramLen);

    jint ret = (jint)rsProgramVertexCreate2(con, shaderUTF, shaderLen, (uint32_t *)paramPtr, paramLen);
    _env->ReleaseStringUTFChars(shader, shaderUTF);
    _env->ReleaseIntArrayElements(params, paramPtr, JNI_ABORT);
    return ret;
}

// ---------------------------------------------------------------------------

static jint
nProgramRasterCreate(JNIEnv *_env, jobject _this, RsContext con, jboolean pointSmooth, jboolean lineSmooth, jboolean pointSprite)
{
    LOG_API("nProgramRasterCreate, con(%p), pointSmooth(%i), lineSmooth(%i), pointSprite(%i)",
            con, pointSmooth, lineSmooth, pointSprite);
    return (jint)rsProgramRasterCreate(con, pointSmooth, lineSmooth, pointSprite);
}

static void
nProgramRasterSetLineWidth(JNIEnv *_env, jobject _this, RsContext con, jint vpr, jfloat v)
{
    LOG_API("nProgramRasterSetLineWidth, con(%p), vpf(%p), value(%f)", con, (RsProgramRaster)vpr, v);
    rsProgramRasterSetLineWidth(con, (RsProgramRaster)vpr, v);
}

static void
nProgramRasterSetCullMode(JNIEnv *_env, jobject _this, RsContext con, jint vpr, jint v)
{
    LOG_API("nProgramRasterSetCullMode, con(%p), vpf(%p), value(%i)", con, (RsProgramRaster)vpr, v);
    rsProgramRasterSetCullMode(con, (RsProgramRaster)vpr, (RsCullMode)v);
}


// ---------------------------------------------------------------------------

static void
nContextBindRootScript(JNIEnv *_env, jobject _this, RsContext con, jint script)
{
    LOG_API("nContextBindRootScript, con(%p), script(%p)", con, (RsScript)script);
    rsContextBindRootScript(con, (RsScript)script);
}

static void
nContextBindProgramStore(JNIEnv *_env, jobject _this, RsContext con, jint pfs)
{
    LOG_API("nContextBindProgramStore, con(%p), pfs(%p)", con, (RsProgramStore)pfs);
    rsContextBindProgramStore(con, (RsProgramStore)pfs);
}

static void
nContextBindProgramFragment(JNIEnv *_env, jobject _this, RsContext con, jint pf)
{
    LOG_API("nContextBindProgramFragment, con(%p), pf(%p)", con, (RsProgramFragment)pf);
    rsContextBindProgramFragment(con, (RsProgramFragment)pf);
}

static void
nContextBindProgramVertex(JNIEnv *_env, jobject _this, RsContext con, jint pf)
{
    LOG_API("nContextBindProgramVertex, con(%p), pf(%p)", con, (RsProgramVertex)pf);
    rsContextBindProgramVertex(con, (RsProgramVertex)pf);
}

static void
nContextBindProgramRaster(JNIEnv *_env, jobject _this, RsContext con, jint pf)
{
    LOG_API("nContextBindProgramRaster, con(%p), pf(%p)", con, (RsProgramRaster)pf);
    rsContextBindProgramRaster(con, (RsProgramRaster)pf);
}


// ---------------------------------------------------------------------------

static void
nSamplerBegin(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nSamplerBegin, con(%p)", con);
    rsSamplerBegin(con);
}

static void
nSamplerSet(JNIEnv *_env, jobject _this, RsContext con, jint p, jint v)
{
    LOG_API("nSamplerSet, con(%p), param(%i), value(%i)", con, p, v);
    rsSamplerSet(con, (RsSamplerParam)p, (RsSamplerValue)v);
}

static jint
nSamplerCreate(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nSamplerCreate, con(%p)", con);
    return (jint)rsSamplerCreate(con);
}

// ---------------------------------------------------------------------------

static jint
nMeshCreate(JNIEnv *_env, jobject _this, RsContext con, jint vtxCount, jint idxCount)
{
    LOG_API("nMeshCreate, con(%p), vtxCount(%i), idxCount(%i)", con, vtxCount, idxCount);
    int id = (int)rsMeshCreate(con, vtxCount, idxCount);
    return id;
}

static void
nMeshBindVertex(JNIEnv *_env, jobject _this, RsContext con, jint mesh, jint alloc, jint slot)
{
    LOG_API("nMeshBindVertex, con(%p), Mesh(%p), Alloc(%p), slot(%i)", con, (RsMesh)mesh, (RsAllocation)alloc, slot);
    rsMeshBindVertex(con, (RsMesh)mesh, (RsAllocation)alloc, slot);
}

static void
nMeshBindIndex(JNIEnv *_env, jobject _this, RsContext con, jint mesh, jint alloc, jint primID, jint slot)
{
    LOG_API("nMeshBindIndex, con(%p), Mesh(%p), Alloc(%p)", con, (RsMesh)mesh, (RsAllocation)alloc);
    rsMeshBindIndex(con, (RsMesh)mesh, (RsAllocation)alloc, primID, slot);
}

static jint
nMeshGetVertexBufferCount(JNIEnv *_env, jobject _this, RsContext con, jint mesh)
{
    LOG_API("nMeshGetVertexBufferCount, con(%p), Mesh(%p)", con, (RsMesh)mesh);
    jint vtxCount = 0;
    rsMeshGetVertexBufferCount(con, (RsMesh)mesh, &vtxCount);
    return vtxCount;
}

static jint
nMeshGetIndexCount(JNIEnv *_env, jobject _this, RsContext con, jint mesh)
{
    LOG_API("nMeshGetIndexCount, con(%p), Mesh(%p)", con, (RsMesh)mesh);
    jint idxCount = 0;
    rsMeshGetIndexCount(con, (RsMesh)mesh, &idxCount);
    return idxCount;
}

static void
nMeshGetVertices(JNIEnv *_env, jobject _this, RsContext con, jint mesh, jintArray _ids, int numVtxIDs)
{
    LOG_API("nMeshGetVertices, con(%p), Mesh(%p)", con, (RsMesh)mesh);

    RsAllocation *allocs = (RsAllocation*)malloc((uint32_t)numVtxIDs * sizeof(RsAllocation));
    rsMeshGetVertices(con, (RsMesh)mesh, allocs, (uint32_t)numVtxIDs);

    for(jint i = 0; i < numVtxIDs; i ++) {
        _env->SetIntArrayRegion(_ids, i, 1, (const jint*)&allocs[i]);
    }

    free(allocs);
}

static void
nMeshGetIndices(JNIEnv *_env, jobject _this, RsContext con, jint mesh, jintArray _idxIds, jintArray _primitives, int numIndices)
{
    LOG_API("nMeshGetVertices, con(%p), Mesh(%p)", con, (RsMesh)mesh);

    RsAllocation *allocs = (RsAllocation*)malloc((uint32_t)numIndices * sizeof(RsAllocation));
    uint32_t *prims= (uint32_t*)malloc((uint32_t)numIndices * sizeof(uint32_t));

    rsMeshGetIndices(con, (RsMesh)mesh, allocs, prims, (uint32_t)numIndices);

    for(jint i = 0; i < numIndices; i ++) {
        _env->SetIntArrayRegion(_idxIds, i, 1, (const jint*)&allocs[i]);
        _env->SetIntArrayRegion(_primitives, i, 1, (const jint*)&prims[i]);
    }

    free(allocs);
    free(prims);
}

// ---------------------------------------------------------------------------


static const char *classPathName = "android/renderscript/RenderScript";

static JNINativeMethod methods[] = {
{"_nInit",                         "()V",                                  (void*)_nInit },
{"nInitElements",                  "(IIII)V",                              (void*)nInitElements },

{"nDeviceCreate",                  "()I",                                  (void*)nDeviceCreate },
{"nDeviceDestroy",                 "(I)V",                                 (void*)nDeviceDestroy },
{"nDeviceSetConfig",               "(III)V",                               (void*)nDeviceSetConfig },
{"nContextGetMessage",             "(I[IZ)I",                               (void*)nContextGetMessage },
{"nContextInitToClient",           "(I)V",                                  (void*)nContextInitToClient },
{"nContextDeinitToClient",         "(I)V",                                  (void*)nContextDeinitToClient },


// All methods below are thread protected in java.
{"rsnContextCreate",                 "(II)I",                                (void*)nContextCreate },
{"rsnContextCreateGL",               "(IIZ)I",                               (void*)nContextCreateGL },
{"rsnContextFinish",                 "(I)V",                                  (void*)nContextFinish },
{"rsnContextSetPriority",            "(II)V",                                 (void*)nContextSetPriority },
{"rsnContextSetSurface",             "(IIILandroid/view/Surface;)V",          (void*)nContextSetSurface },
{"rsnContextDestroy",                "(I)V",                                 (void*)nContextDestroy },
{"rsnContextDump",                   "(II)V",                                 (void*)nContextDump },
{"rsnContextPause",                  "(I)V",                                  (void*)nContextPause },
{"rsnContextResume",                 "(I)V",                                  (void*)nContextResume },
{"rsnAssignName",                    "(II[B)V",                               (void*)nAssignName },
{"rsnGetName",                       "(II)Ljava/lang/String;",               (void*)nGetName },
{"rsnObjDestroy",                    "(II)V",                                 (void*)nObjDestroy },

{"rsnFileOpen",                      "(I[B)I",                                (void*)nFileOpen },
{"rsnFileA3DCreateFromAssetStream",  "(II)I",                                 (void*)nFileA3DCreateFromAssetStream },
{"rsnFileA3DGetNumIndexEntries",     "(II)I",                                 (void*)nFileA3DGetNumIndexEntries },
{"rsnFileA3DGetIndexEntries",        "(III[I[Ljava/lang/String;)V",          (void*)nFileA3DGetIndexEntries },
{"rsnFileA3DGetEntryByIndex",        "(III)I",                                (void*)nFileA3DGetEntryByIndex },

{"rsnFontCreateFromFile",            "(ILjava/lang/String;II)I",             (void*)nFontCreateFromFile },

{"rsnElementCreate",                 "(IIIZI)I",                              (void*)nElementCreate },
{"rsnElementCreate2",                "(I[I[Ljava/lang/String;)I",             (void*)nElementCreate2 },
{"rsnElementGetNativeData",          "(II[I)V",                               (void*)nElementGetNativeData },
{"rsnElementGetSubElements",         "(II[I[Ljava/lang/String;)V",           (void*)nElementGetSubElements },

{"rsnTypeBegin",                     "(II)V",                                 (void*)nTypeBegin },
{"rsnTypeAdd",                       "(III)V",                                (void*)nTypeAdd },
{"rsnTypeCreate",                    "(I)I",                                  (void*)nTypeCreate },
{"rsnTypeFinalDestroy",              "(ILandroid/renderscript/Type;)V",       (void*)nTypeFinalDestroy },
{"rsnTypeSetupFields",               "(ILandroid/renderscript/Type;[I[I[Ljava/lang/reflect/Field;)V", (void*)nTypeSetupFields },
{"rsnTypeGetNativeData",             "(II[I)V",                                (void*)nTypeGetNativeData },

{"rsnAllocationCreateTyped",         "(II)I",                                 (void*)nAllocationCreateTyped },
{"rsnAllocationCreateFromBitmap",    "(IIZLandroid/graphics/Bitmap;)I",       (void*)nAllocationCreateFromBitmap },
{"rsnAllocationCreateBitmapRef",     "(IILandroid/graphics/Bitmap;)I",        (void*)nAllocationCreateBitmapRef },
{"rsnAllocationCreateFromBitmapBoxed","(IIZLandroid/graphics/Bitmap;)I",      (void*)nAllocationCreateFromBitmapBoxed },
{"rsnAllocationCreateFromAssetStream","(IIZI)I",                              (void*)nAllocationCreateFromAssetStream },
{"rsnAllocationUploadToTexture",     "(IIZI)V",                               (void*)nAllocationUploadToTexture },
{"rsnAllocationUploadToBufferObject","(II)V",                                 (void*)nAllocationUploadToBufferObject },
{"rsnAllocationSubData1D",           "(IIII[II)V",                            (void*)nAllocationSubData1D_i },
{"rsnAllocationSubData1D",           "(IIII[SI)V",                            (void*)nAllocationSubData1D_s },
{"rsnAllocationSubData1D",           "(IIII[BI)V",                            (void*)nAllocationSubData1D_b },
{"rsnAllocationSubData1D",           "(IIII[FI)V",                            (void*)nAllocationSubData1D_f },
{"rsnAllocationSubData2D",           "(IIIIII[II)V",                          (void*)nAllocationSubData2D_i },
{"rsnAllocationSubData2D",           "(IIIIII[FI)V",                          (void*)nAllocationSubData2D_f },
{"rsnAllocationRead",                "(II[I)V",                               (void*)nAllocationRead_i },
{"rsnAllocationRead",                "(II[F)V",                               (void*)nAllocationRead_f },
{"rsnAllocationSubDataFromObject",   "(IILandroid/renderscript/Type;ILjava/lang/Object;)V",   (void*)nAllocationSubDataFromObject },
{"rsnAllocationSubReadFromObject",   "(IILandroid/renderscript/Type;ILjava/lang/Object;)V",   (void*)nAllocationSubReadFromObject },
{"rsnAllocationGetType",             "(II)I",                                 (void*)nAllocationGetType},

{"rsnAdapter1DBindAllocation",       "(III)V",                                (void*)nAdapter1DBindAllocation },
{"rsnAdapter1DSetConstraint",        "(IIII)V",                               (void*)nAdapter1DSetConstraint },
{"rsnAdapter1DData",                 "(II[I)V",                               (void*)nAdapter1DData_i },
{"rsnAdapter1DData",                 "(II[F)V",                               (void*)nAdapter1DData_f },
{"rsnAdapter1DSubData",              "(IIII[I)V",                             (void*)nAdapter1DSubData_i },
{"rsnAdapter1DSubData",              "(IIII[F)V",                             (void*)nAdapter1DSubData_f },
{"rsnAdapter1DCreate",               "(I)I",                                  (void*)nAdapter1DCreate },

{"rsnAdapter2DBindAllocation",       "(III)V",                                (void*)nAdapter2DBindAllocation },
{"rsnAdapter2DSetConstraint",        "(IIII)V",                               (void*)nAdapter2DSetConstraint },
{"rsnAdapter2DData",                 "(II[I)V",                               (void*)nAdapter2DData_i },
{"rsnAdapter2DData",                 "(II[F)V",                               (void*)nAdapter2DData_f },
{"rsnAdapter2DSubData",              "(IIIIII[I)V",                           (void*)nAdapter2DSubData_i },
{"rsnAdapter2DSubData",              "(IIIIII[F)V",                           (void*)nAdapter2DSubData_f },
{"rsnAdapter2DCreate",               "(I)I",                                  (void*)nAdapter2DCreate },

{"rsnScriptBindAllocation",          "(IIII)V",                               (void*)nScriptBindAllocation },
{"rsnScriptSetTimeZone",             "(II[B)V",                               (void*)nScriptSetTimeZone },
{"rsnScriptInvoke",                  "(III)V",                                (void*)nScriptInvoke },
{"rsnScriptInvokeV",                 "(III[B)V",                              (void*)nScriptInvokeV },
{"rsnScriptSetVarI",                 "(IIII)V",                               (void*)nScriptSetVarI },
{"rsnScriptSetVarF",                 "(IIIF)V",                               (void*)nScriptSetVarF },
{"rsnScriptSetVarV",                 "(III[B)V",                              (void*)nScriptSetVarV },

{"rsnScriptCBegin",                  "(I)V",                                  (void*)nScriptCBegin },
{"rsnScriptCSetScript",              "(I[BII)V",                              (void*)nScriptCSetScript },
{"rsnScriptCCreate",                 "(I)I",                                  (void*)nScriptCCreate },

{"rsnProgramStoreBegin",             "(III)V",                                (void*)nProgramStoreBegin },
{"rsnProgramStoreDepthFunc",         "(II)V",                                 (void*)nProgramStoreDepthFunc },
{"rsnProgramStoreDepthMask",         "(IZ)V",                                 (void*)nProgramStoreDepthMask },
{"rsnProgramStoreColorMask",         "(IZZZZ)V",                              (void*)nProgramStoreColorMask },
{"rsnProgramStoreBlendFunc",         "(III)V",                                (void*)nProgramStoreBlendFunc },
{"rsnProgramStoreDither",            "(IZ)V",                                 (void*)nProgramStoreDither },
{"rsnProgramStoreCreate",            "(I)I",                                  (void*)nProgramStoreCreate },

{"rsnProgramBindConstants",          "(IIII)V",                               (void*)nProgramBindConstants },
{"rsnProgramBindTexture",            "(IIII)V",                               (void*)nProgramBindTexture },
{"rsnProgramBindSampler",            "(IIII)V",                               (void*)nProgramBindSampler },

{"rsnProgramFragmentCreate",         "(I[I)I",                                (void*)nProgramFragmentCreate },
{"rsnProgramFragmentCreate2",        "(ILjava/lang/String;[I)I",              (void*)nProgramFragmentCreate2 },

{"rsnProgramRasterCreate",           "(IZZZ)I",                             (void*)nProgramRasterCreate },
{"rsnProgramRasterSetLineWidth",     "(IIF)V",                                (void*)nProgramRasterSetLineWidth },
{"rsnProgramRasterSetCullMode",      "(III)V",                                (void*)nProgramRasterSetCullMode },

{"rsnProgramVertexCreate",           "(IZ)I",                                 (void*)nProgramVertexCreate },
{"rsnProgramVertexCreate2",          "(ILjava/lang/String;[I)I",              (void*)nProgramVertexCreate2 },

{"rsnContextBindRootScript",         "(II)V",                                 (void*)nContextBindRootScript },
{"rsnContextBindProgramStore",       "(II)V",                                (void*)nContextBindProgramStore },
{"rsnContextBindProgramFragment",    "(II)V",                                 (void*)nContextBindProgramFragment },
{"rsnContextBindProgramVertex",      "(II)V",                                 (void*)nContextBindProgramVertex },
{"rsnContextBindProgramRaster",      "(II)V",                                 (void*)nContextBindProgramRaster },

{"rsnSamplerBegin",                  "(I)V",                                  (void*)nSamplerBegin },
{"rsnSamplerSet",                    "(III)V",                                (void*)nSamplerSet },
{"rsnSamplerCreate",                 "(I)I",                                  (void*)nSamplerCreate },

{"rsnMeshCreate",                    "(III)I",                                (void*)nMeshCreate },
{"rsnMeshBindVertex",                "(IIII)V",                               (void*)nMeshBindVertex },
{"rsnMeshBindIndex",                 "(IIIII)V",                              (void*)nMeshBindIndex },

{"rsnMeshGetVertexBufferCount",      "(II)I",                                 (void*)nMeshGetVertexBufferCount },
{"rsnMeshGetIndexCount",             "(II)I",                                 (void*)nMeshGetIndexCount },
{"rsnMeshGetVertices",               "(II[II)V",                             (void*)nMeshGetVertices },
{"rsnMeshGetIndices",                "(II[I[II)V",                            (void*)nMeshGetIndices },

};

static int registerFuncs(JNIEnv *_env)
{
    return android::AndroidRuntime::registerNativeMethods(
            _env, classPathName, methods, NELEM(methods));
}

// ---------------------------------------------------------------------------

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        LOGE("ERROR: GetEnv failed\n");
        goto bail;
    }
    assert(env != NULL);

    if (registerFuncs(env) < 0) {
        LOGE("ERROR: MediaPlayer native registration failed\n");
        goto bail;
    }

    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

bail:
    return result;
}

