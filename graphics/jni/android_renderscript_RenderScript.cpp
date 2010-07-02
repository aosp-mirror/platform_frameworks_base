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
nContextFinish(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nContextFinish, con(%p)", con);
    rsContextFinish(con);
}

static void
nAssignName(JNIEnv *_env, jobject _this, jint obj, jbyteArray str)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nAssignName, con(%p), obj(%p)", con, (void *)obj);

    jint len = _env->GetArrayLength(str);
    jbyte * cptr = (jbyte *) _env->GetPrimitiveArrayCritical(str, 0);
    rsAssignName(con, (void *)obj, (const char *)cptr, len);
    _env->ReleasePrimitiveArrayCritical(str, cptr, JNI_ABORT);
}

static void
nObjDestroy(JNIEnv *_env, jobject _this, jint obj)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nObjDestroy, con(%p) obj(%p)", con, (void *)obj);
    rsObjDestroy(con, (void *)obj);
}

static void
nObjDestroyOOB(JNIEnv *_env, jobject _this, jint obj)
{
    // This function only differs from nObjDestroy in that it calls the
    // special Out Of Band version of ObjDestroy which is thread safe.
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nObjDestroyOOB, con(%p) obj(%p)", con, (void *)obj);
    rsObjDestroyOOB(con, (void *)obj);
}

static jint
nFileOpen(JNIEnv *_env, jobject _this, jbyteArray str)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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
nContextSetPriority(JNIEnv *_env, jobject _this, jint p)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("ContextSetPriority, con(%p), priority(%i)", con, p);
    rsContextSetPriority(con, p);
}



static void
nContextSetSurface(JNIEnv *_env, jobject _this, jint width, jint height, jobject wnd)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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
nContextDestroy(JNIEnv *_env, jobject _this, jint con)
{
    LOG_API("nContextDestroy, con(%p)", (RsContext)con);
    rsContextDestroy((RsContext)con);
}

static void
nContextDump(JNIEnv *_env, jobject _this, jint bits)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nContextDump, con(%p)  bits(%i)", (RsContext)con, bits);
    rsContextDump((RsContext)con, bits);
}

static void
nContextPause(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nContextPause, con(%p)", con);
    rsContextPause(con);
}

static void
nContextResume(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nContextResume, con(%p)", con);
    rsContextResume(con);
}

static jint
nContextGetMessage(JNIEnv *_env, jobject _this, jintArray data, jboolean wait)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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

static void nContextInitToClient(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nContextInitToClient, con(%p)", con);
    rsContextInitToClient(con);
}

static void nContextDeinitToClient(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nContextDeinitToClient, con(%p)", con);
    rsContextDeinitToClient(con);
}


static jint
nElementCreate(JNIEnv *_env, jobject _this, jint type, jint kind, jboolean norm, jint size)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nElementCreate, con(%p), type(%i), kind(%i), norm(%i), size(%i)", con, type, kind, norm, size);
    return (jint)rsElementCreate(con, (RsDataType)type, (RsDataKind)kind, norm, size);
}

static jint
nElementCreate2(JNIEnv *_env, jobject _this, jintArray _ids, jobjectArray _names)
{
    int fieldCount = _env->GetArrayLength(_ids);
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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

// -----------------------------------

static void
nTypeBegin(JNIEnv *_env, jobject _this, jint eID)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nTypeBegin, con(%p) e(%p)", con, (RsElement)eID);
    rsTypeBegin(con, (RsElement)eID);
}

static void
nTypeAdd(JNIEnv *_env, jobject _this, jint dim, jint val)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nTypeAdd, con(%p) dim(%i), val(%i)", con, dim, val);
    rsTypeAdd(con, (RsDimension)dim, val);
}

static jint
nTypeCreate(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nTypeCreate, con(%p)", con);
    return (jint)rsTypeCreate(con);
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
nTypeFinalDestroy(JNIEnv *_env, jobject _this, jobject _type)
{
    TypeCache *tc = (TypeCache *)_env->GetIntField(_type, gTypeNativeCache);
    free(tc);
}

// native void nTypeSetupFields(Type t, int[] types, int[] bits, Field[] IDs);
static void
nTypeSetupFields(JNIEnv *_env, jobject _this, jobject _type, jintArray _types, jintArray _bits, jobjectArray _IDs)
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
nAllocationCreateTyped(JNIEnv *_env, jobject _this, jint e)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nAllocationCreateTyped, con(%p), e(%p)", con, (RsElement)e);
    return (jint) rsAllocationCreateTyped(con, (RsElement)e);
}

static void
nAllocationUploadToTexture(JNIEnv *_env, jobject _this, jint a, jboolean genMip, jint mip)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nAllocationUploadToTexture, con(%p), a(%p), genMip(%i), mip(%i)", con, (RsAllocation)a, genMip, mip);
    rsAllocationUploadToTexture(con, (RsAllocation)a, genMip, mip);
}

static void
nAllocationUploadToBufferObject(JNIEnv *_env, jobject _this, jint a)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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
nAllocationCreateFromBitmap(JNIEnv *_env, jobject _this, jint dstFmt, jboolean genMips, jobject jbitmap)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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
nAllocationCreateBitmapRef(JNIEnv *_env, jobject _this, jint type, jobject jbitmap)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    SkBitmap * nativeBitmap =
            (SkBitmap *)_env->GetIntField(jbitmap, gNativeBitmapID);


    nativeBitmap->lockPixels();
    void* ptr = nativeBitmap->getPixels();
    jint id = (jint)rsAllocationCreateBitmapRef(con, (RsType)type, ptr, nativeBitmap, ReleaseBitmapCallback);
    return id;
}

static int
nAllocationCreateFromAssetStream(JNIEnv *_env, jobject _this, jint dstFmt, jboolean genMips, jint native_asset)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));

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
nAllocationCreateFromBitmapBoxed(JNIEnv *_env, jobject _this, jint dstFmt, jboolean genMips, jobject jbitmap)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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
nAllocationSubData1D_i(JNIEnv *_env, jobject _this, jint alloc, jint offset, jint count, jintArray data, int sizeBytes)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DSubData_i, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAllocation1DSubData(con, (RsAllocation)alloc, offset, count, ptr, sizeBytes);
    _env->ReleaseIntArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationSubData1D_s(JNIEnv *_env, jobject _this, jint alloc, jint offset, jint count, jshortArray data, int sizeBytes)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DSubData_s, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jshort *ptr = _env->GetShortArrayElements(data, NULL);
    rsAllocation1DSubData(con, (RsAllocation)alloc, offset, count, ptr, sizeBytes);
    _env->ReleaseShortArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationSubData1D_b(JNIEnv *_env, jobject _this, jint alloc, jint offset, jint count, jbyteArray data, int sizeBytes)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DSubData_b, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsAllocation1DSubData(con, (RsAllocation)alloc, offset, count, ptr, sizeBytes);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationSubData1D_f(JNIEnv *_env, jobject _this, jint alloc, jint offset, jint count, jfloatArray data, int sizeBytes)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DSubData_f, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAllocation1DSubData(con, (RsAllocation)alloc, offset, count, ptr, sizeBytes);
    _env->ReleaseFloatArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationSubData2D_i(JNIEnv *_env, jobject _this, jint alloc, jint xoff, jint yoff, jint w, jint h, jintArray data, int sizeBytes)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation2DSubData_i, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, w, h, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAllocation2DSubData(con, (RsAllocation)alloc, xoff, yoff, w, h, ptr, sizeBytes);
    _env->ReleaseIntArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationSubData2D_f(JNIEnv *_env, jobject _this, jint alloc, jint xoff, jint yoff, jint w, jint h, jfloatArray data, int sizeBytes)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation2DSubData_i, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, w, h, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAllocation2DSubData(con, (RsAllocation)alloc, xoff, yoff, w, h, ptr, sizeBytes);
    _env->ReleaseFloatArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationRead_i(JNIEnv *_env, jobject _this, jint alloc, jintArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocationRead_i, con(%p), alloc(%p), len(%i)", con, (RsAllocation)alloc, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAllocationRead(con, (RsAllocation)alloc, ptr);
    _env->ReleaseIntArrayElements(data, ptr, 0);
}

static void
nAllocationRead_f(JNIEnv *_env, jobject _this, jint alloc, jfloatArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocationRead_f, con(%p), alloc(%p), len(%i)", con, (RsAllocation)alloc, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAllocationRead(con, (RsAllocation)alloc, ptr);
    _env->ReleaseFloatArrayElements(data, ptr, 0);
}


//{"nAllocationDataFromObject",      "(ILandroid/renderscript/Type;Ljava/lang/Object;)V",   (void*)nAllocationDataFromObject },
static void
nAllocationSubDataFromObject(JNIEnv *_env, jobject _this, jint alloc, jobject _type, jint offset, jobject _o)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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
nAllocationSubReadFromObject(JNIEnv *_env, jobject _this, jint alloc, jobject _type, jint offset, jobject _o)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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

// -----------------------------------

static int
nFileA3DCreateFromAssetStream(JNIEnv *_env, jobject _this, jint native_asset)
{
    LOGV("______nFileA3D %u", (uint32_t) native_asset);
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));

    Asset* asset = reinterpret_cast<Asset*>(native_asset);

    jint id = (jint)rsFileA3DCreateFromAssetStream(con, asset->getBuffer(false), asset->getLength());
    return id;
}

static int
nFileA3DGetNumIndexEntries(JNIEnv *_env, jobject _this, jint fileA3D)
{
    LOGV("______nFileA3D %u", (uint32_t) fileA3D);
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));

    int32_t numEntries = 0;
    rsFileA3DGetNumIndexEntries(con, &numEntries, (RsFile)fileA3D);
    LOGV("______nFileA3D NumEntries %u", (uint32_t) numEntries);
    return numEntries;
}

static void
nFileA3DGetIndexEntries(JNIEnv *_env, jobject _this, jint fileA3D, jint numEntries, jintArray _ids, jobjectArray _entries)
{
    LOGV("______nFileA3D %u", (uint32_t) fileA3D);
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));

    RsFileIndexEntry *fileEntries = (RsFileIndexEntry*)malloc((uint32_t)numEntries * sizeof(RsFileIndexEntry));

    rsFileA3DGetIndexEntries(con, fileEntries, (uint32_t)numEntries, (RsFile)fileA3D);

    for(jint i = 0; i < numEntries; i ++) {
        _env->SetObjectArrayElement(_entries, i, _env->NewStringUTF(fileEntries[i].objectName));
        _env->SetIntArrayRegion(_ids, i, 1, (const jint*)&fileEntries[i].classID);
    }

    free(fileEntries);
}

static int
nFileA3DGetEntryByIndex(JNIEnv *_env, jobject _this, jint fileA3D, jint index)
{
    LOGV("______nFileA3D %u", (uint32_t) fileA3D);
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));

    jint id = (jint)rsFileA3DGetEntryByIndex(con, (uint32_t)index, (RsFile)fileA3D);
    return id;
}

// -----------------------------------

static int
nFontCreateFromFile(JNIEnv *_env, jobject _this, jstring fileName, jint fontSize, jint dpi)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    const char* fileNameUTF = _env->GetStringUTFChars(fileName, NULL);

    jint id = (jint)rsFontCreateFromFile(con, fileNameUTF, fontSize, dpi);
    return id;
}


// -----------------------------------

static void
nAdapter1DBindAllocation(JNIEnv *_env, jobject _this, jint adapter, jint alloc)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nAdapter1DBindAllocation, con(%p), adapter(%p), alloc(%p)", con, (RsAdapter1D)adapter, (RsAllocation)alloc);
    rsAdapter1DBindAllocation(con, (RsAdapter1D)adapter, (RsAllocation)alloc);
}

static void
nAdapter1DSetConstraint(JNIEnv *_env, jobject _this, jint adapter, jint dim, jint value)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nAdapter1DSetConstraint, con(%p), adapter(%p), dim(%i), value(%i)", con, (RsAdapter1D)adapter, dim, value);
    rsAdapter1DSetConstraint(con, (RsAdapter1D)adapter, (RsDimension)dim, value);
}

static void
nAdapter1DData_i(JNIEnv *_env, jobject _this, jint adapter, jintArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter1DData_i, con(%p), adapter(%p), len(%i)", con, (RsAdapter1D)adapter, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAdapter1DData(con, (RsAdapter1D)adapter, ptr);
    _env->ReleaseIntArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter1DSubData_i(JNIEnv *_env, jobject _this, jint adapter, jint offset, jint count, jintArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter1DSubData_i, con(%p), adapter(%p), offset(%i), count(%i), len(%i)", con, (RsAdapter1D)adapter, offset, count, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAdapter1DSubData(con, (RsAdapter1D)adapter, offset, count, ptr);
    _env->ReleaseIntArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter1DData_f(JNIEnv *_env, jobject _this, jint adapter, jfloatArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter1DData_f, con(%p), adapter(%p), len(%i)", con, (RsAdapter1D)adapter, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAdapter1DData(con, (RsAdapter1D)adapter, ptr);
    _env->ReleaseFloatArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter1DSubData_f(JNIEnv *_env, jobject _this, jint adapter, jint offset, jint count, jfloatArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter1DSubData_f, con(%p), adapter(%p), offset(%i), count(%i), len(%i)", con, (RsAdapter1D)adapter, offset, count, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAdapter1DSubData(con, (RsAdapter1D)adapter, offset, count, ptr);
    _env->ReleaseFloatArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static jint
nAdapter1DCreate(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nAdapter1DCreate, con(%p)", con);
    return (jint)rsAdapter1DCreate(con);
}

// -----------------------------------

static void
nAdapter2DBindAllocation(JNIEnv *_env, jobject _this, jint adapter, jint alloc)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nAdapter2DBindAllocation, con(%p), adapter(%p), alloc(%p)", con, (RsAdapter2D)adapter, (RsAllocation)alloc);
    rsAdapter2DBindAllocation(con, (RsAdapter2D)adapter, (RsAllocation)alloc);
}

static void
nAdapter2DSetConstraint(JNIEnv *_env, jobject _this, jint adapter, jint dim, jint value)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nAdapter2DSetConstraint, con(%p), adapter(%p), dim(%i), value(%i)", con, (RsAdapter2D)adapter, dim, value);
    rsAdapter2DSetConstraint(con, (RsAdapter2D)adapter, (RsDimension)dim, value);
}

static void
nAdapter2DData_i(JNIEnv *_env, jobject _this, jint adapter, jintArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter2DData_i, con(%p), adapter(%p), len(%i)", con, (RsAdapter2D)adapter, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAdapter2DData(con, (RsAdapter2D)adapter, ptr);
    _env->ReleaseIntArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter2DData_f(JNIEnv *_env, jobject _this, jint adapter, jfloatArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter2DData_f, con(%p), adapter(%p), len(%i)", con, (RsAdapter2D)adapter, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAdapter2DData(con, (RsAdapter2D)adapter, ptr);
    _env->ReleaseFloatArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter2DSubData_i(JNIEnv *_env, jobject _this, jint adapter, jint xoff, jint yoff, jint w, jint h, jintArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter2DSubData_i, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)",
            con, (RsAdapter2D)adapter, xoff, yoff, w, h, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAdapter2DSubData(con, (RsAdapter2D)adapter, xoff, yoff, w, h, ptr);
    _env->ReleaseIntArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static void
nAdapter2DSubData_f(JNIEnv *_env, jobject _this, jint adapter, jint xoff, jint yoff, jint w, jint h, jfloatArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint len = _env->GetArrayLength(data);
    LOG_API("nAdapter2DSubData_f, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)",
            con, (RsAdapter2D)adapter, xoff, yoff, w, h, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAdapter2DSubData(con, (RsAdapter1D)adapter, xoff, yoff, w, h, ptr);
    _env->ReleaseFloatArrayElements(data, ptr, 0/*JNI_ABORT*/);
}

static jint
nAdapter2DCreate(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nAdapter2DCreate, con(%p)", con);
    return (jint)rsAdapter2DCreate(con);
}

// -----------------------------------

static void
nScriptBindAllocation(JNIEnv *_env, jobject _this, jint script, jint alloc, jint slot)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nScriptBindAllocation, con(%p), script(%p), alloc(%p), slot(%i)", con, (RsScript)script, (RsAllocation)alloc, slot);
    rsScriptBindAllocation(con, (RsScript)script, (RsAllocation)alloc, slot);
}

static void
nScriptSetVarI(JNIEnv *_env, jobject _this, jint script, jint slot, jint val)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nScriptSetVarI, con(%p), s(%p), slot(%i), val(%i), b(%f), a(%f)", con, (void *)script, slot, val);
    rsScriptSetVarI(con, (RsScript)script, slot, val);
}

static void
nScriptSetVarF(JNIEnv *_env, jobject _this, jint script, jint slot, float val)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nScriptSetVarI, con(%p), s(%p), slot(%i), val(%i), b(%f), a(%f)", con, (void *)script, slot, val);
    rsScriptSetVarF(con, (RsScript)script, slot, val);
}

static void
nScriptSetVarV(JNIEnv *_env, jobject _this, jint script, jint slot, jbyteArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nScriptSetVarV, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsScriptSetVarV(con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}


static void
nScriptSetTimeZone(JNIEnv *_env, jobject _this, jint script, jbyteArray timeZone)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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
nScriptInvoke(JNIEnv *_env, jobject _this, jint obj, jint slot)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nScriptInvoke, con(%p), script(%p)", con, (void *)obj);
    rsScriptInvoke(con, (RsScript)obj, slot);
}

static void
nScriptInvokeV(JNIEnv *_env, jobject _this, jint script, jint slot, jbyteArray data)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nScriptInvokeV, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsScriptInvokeV(con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}


// -----------------------------------

static void
nScriptCBegin(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nScriptCBegin, con(%p)", con);
    rsScriptCBegin(con);
}

static void
nScriptCSetScript(JNIEnv *_env, jobject _this, jbyteArray scriptRef,
                  jint offset, jint length)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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
nScriptCCreate(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nScriptCCreate, con(%p)", con);
    return (jint)rsScriptCCreate(con);
}

// ---------------------------------------------------------------------------

static void
nProgramStoreBegin(JNIEnv *_env, jobject _this, jint in, jint out)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramStoreBegin, con(%p), in(%p), out(%p)", con, (RsElement)in, (RsElement)out);
    rsProgramStoreBegin(con, (RsElement)in, (RsElement)out);
}

static void
nProgramStoreDepthFunc(JNIEnv *_env, jobject _this, jint func)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramStoreDepthFunc, con(%p), func(%i)", con, func);
    rsProgramStoreDepthFunc(con, (RsDepthFunc)func);
}

static void
nProgramStoreDepthMask(JNIEnv *_env, jobject _this, jboolean enable)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramStoreDepthMask, con(%p), enable(%i)", con, enable);
    rsProgramStoreDepthMask(con, enable);
}

static void
nProgramStoreColorMask(JNIEnv *_env, jobject _this, jboolean r, jboolean g, jboolean b, jboolean a)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramStoreColorMask, con(%p), r(%i), g(%i), b(%i), a(%i)", con, r, g, b, a);
    rsProgramStoreColorMask(con, r, g, b, a);
}

static void
nProgramStoreBlendFunc(JNIEnv *_env, jobject _this, int src, int dst)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramStoreBlendFunc, con(%p), src(%i), dst(%i)", con, src, dst);
    rsProgramStoreBlendFunc(con, (RsBlendSrcFunc)src, (RsBlendDstFunc)dst);
}

static void
nProgramStoreDither(JNIEnv *_env, jobject _this, jboolean enable)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramStoreDither, con(%p), enable(%i)", con, enable);
    rsProgramStoreDither(con, enable);
}

static jint
nProgramStoreCreate(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramStoreCreate, con(%p)", con);

    return (jint)rsProgramStoreCreate(con);
}

// ---------------------------------------------------------------------------

static void
nProgramBindConstants(JNIEnv *_env, jobject _this, jint vpv, jint slot, jint a)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramBindConstants, con(%p), vpf(%p), sloat(%i), a(%p)", con, (RsProgramVertex)vpv, slot, (RsAllocation)a);
    rsProgramBindConstants(con, (RsProgram)vpv, slot, (RsAllocation)a);
}

static void
nProgramBindTexture(JNIEnv *_env, jobject _this, jint vpf, jint slot, jint a)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramBindTexture, con(%p), vpf(%p), slot(%i), a(%p)", con, (RsProgramFragment)vpf, slot, (RsAllocation)a);
    rsProgramBindTexture(con, (RsProgramFragment)vpf, slot, (RsAllocation)a);
}

static void
nProgramBindSampler(JNIEnv *_env, jobject _this, jint vpf, jint slot, jint a)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramBindSampler, con(%p), vpf(%p), slot(%i), a(%p)", con, (RsProgramFragment)vpf, slot, (RsSampler)a);
    rsProgramBindSampler(con, (RsProgramFragment)vpf, slot, (RsSampler)a);
}

// ---------------------------------------------------------------------------

static jint
nProgramFragmentCreate(JNIEnv *_env, jobject _this, jintArray params)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    jint *paramPtr = _env->GetIntArrayElements(params, NULL);
    jint paramLen = _env->GetArrayLength(params);

    LOG_API("nProgramFragmentCreate, con(%p), paramLen(%i)", con, paramLen);

    jint ret = (jint)rsProgramFragmentCreate(con, (uint32_t *)paramPtr, paramLen);
    _env->ReleaseIntArrayElements(params, paramPtr, JNI_ABORT);
    return ret;
}

static jint
nProgramFragmentCreate2(JNIEnv *_env, jobject _this, jstring shader, jintArray params)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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
nProgramVertexCreate(JNIEnv *_env, jobject _this, jboolean texMat)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramVertexCreate, con(%p), texMat(%i)", con, texMat);
    return (jint)rsProgramVertexCreate(con, texMat);
}

static jint
nProgramVertexCreate2(JNIEnv *_env, jobject _this, jstring shader, jintArray params)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
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
nProgramRasterCreate(JNIEnv *_env, jobject _this, jint in, jint out,
                     jboolean pointSmooth, jboolean lineSmooth, jboolean pointSprite)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramRasterCreate, con(%p), in(%p), out(%p), pointSmooth(%i), lineSmooth(%i), pointSprite(%i)",
            con, (RsElement)in, (RsElement)out, pointSmooth, lineSmooth, pointSprite);
    return (jint)rsProgramRasterCreate(con, (RsElement)in, (RsElement)out, pointSmooth, lineSmooth, pointSprite);
}

static void
nProgramRasterSetPointSize(JNIEnv *_env, jobject _this, jint vpr, jfloat v)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramRasterSetPointSize, con(%p), vpf(%p), value(%f)", con, (RsProgramRaster)vpr, v);
    rsProgramRasterSetPointSize(con, (RsProgramFragment)vpr, v);
}

static void
nProgramRasterSetLineWidth(JNIEnv *_env, jobject _this, jint vpr, jfloat v)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nProgramRasterSetLineWidth, con(%p), vpf(%p), value(%f)", con, (RsProgramRaster)vpr, v);
    rsProgramRasterSetLineWidth(con, (RsProgramFragment)vpr, v);
}


// ---------------------------------------------------------------------------

static void
nContextBindRootScript(JNIEnv *_env, jobject _this, jint script)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nContextBindRootScript, con(%p), script(%p)", con, (RsScript)script);
    rsContextBindRootScript(con, (RsScript)script);
}

static void
nContextBindProgramStore(JNIEnv *_env, jobject _this, jint pfs)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nContextBindProgramStore, con(%p), pfs(%p)", con, (RsProgramStore)pfs);
    rsContextBindProgramStore(con, (RsProgramStore)pfs);
}

static void
nContextBindProgramFragment(JNIEnv *_env, jobject _this, jint pf)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nContextBindProgramFragment, con(%p), pf(%p)", con, (RsProgramFragment)pf);
    rsContextBindProgramFragment(con, (RsProgramFragment)pf);
}

static void
nContextBindProgramVertex(JNIEnv *_env, jobject _this, jint pf)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nContextBindProgramVertex, con(%p), pf(%p)", con, (RsProgramVertex)pf);
    rsContextBindProgramVertex(con, (RsProgramVertex)pf);
}

static void
nContextBindProgramRaster(JNIEnv *_env, jobject _this, jint pf)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nContextBindProgramRaster, con(%p), pf(%p)", con, (RsProgramRaster)pf);
    rsContextBindProgramRaster(con, (RsProgramRaster)pf);
}


// ---------------------------------------------------------------------------

static void
nSamplerBegin(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nSamplerBegin, con(%p)", con);
    rsSamplerBegin(con);
}

static void
nSamplerSet(JNIEnv *_env, jobject _this, jint p, jint v)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nSamplerSet, con(%p), param(%i), value(%i)", con, p, v);
    rsSamplerSet(con, (RsSamplerParam)p, (RsSamplerValue)v);
}

static jint
nSamplerCreate(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nSamplerCreate, con(%p)", con);
    return (jint)rsSamplerCreate(con);
}

// ---------------------------------------------------------------------------

static void
nLightBegin(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nLightBegin, con(%p)", con);
    rsLightBegin(con);
}

static void
nLightSetIsMono(JNIEnv *_env, jobject _this, jboolean isMono)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nLightSetIsMono, con(%p), isMono(%i)", con, isMono);
    rsLightSetMonochromatic(con, isMono);
}

static void
nLightSetIsLocal(JNIEnv *_env, jobject _this, jboolean isLocal)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nLightSetIsLocal, con(%p), isLocal(%i)", con, isLocal);
    rsLightSetLocal(con, isLocal);
}

static jint
nLightCreate(JNIEnv *_env, jobject _this)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nLightCreate, con(%p)", con);
    return (jint)rsLightCreate(con);
}

static void
nLightSetColor(JNIEnv *_env, jobject _this, jint light, float r, float g, float b)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nLightSetColor, con(%p), light(%p), r(%f), g(%f), b(%f)", con, (RsLight)light, r, g, b);
    rsLightSetColor(con, (RsLight)light, r, g, b);
}

static void
nLightSetPosition(JNIEnv *_env, jobject _this, jint light, float x, float y, float z)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nLightSetPosition, con(%p), light(%p), x(%f), y(%f), z(%f)", con, (RsLight)light, x, y, z);
    rsLightSetPosition(con, (RsLight)light, x, y, z);
}

// ---------------------------------------------------------------------------

static jint
nMeshCreate(JNIEnv *_env, jobject _this, jint vtxCount, jint idxCount)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nMeshCreate, con(%p), vtxCount(%i), idxCount(%i)", con, vtxCount, idxCount);
    int id = (int)rsMeshCreate(con, vtxCount, idxCount);
    return id;
}

static void
nMeshBindVertex(JNIEnv *_env, jobject _this, jint s, jint alloc, jint slot)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nMeshBindVertex, con(%p), Mesh(%p), Alloc(%p), slot(%i)", con, (RsMesh)s, (RsAllocation)alloc, slot);
    rsMeshBindVertex(con, (RsMesh)s, (RsAllocation)alloc, slot);
}

static void
nMeshBindIndex(JNIEnv *_env, jobject _this, jint s, jint alloc, jint primID, jint slot)
{
    RsContext con = (RsContext)(_env->GetIntField(_this, gContextId));
    LOG_API("nMeshBindIndex, con(%p), Mesh(%p), Alloc(%p)", con, (RsMesh)s, (RsAllocation)alloc);
    rsMeshBindIndex(con, (RsMesh)s, (RsAllocation)alloc, primID, slot);
}

// ---------------------------------------------------------------------------


static const char *classPathName = "android/renderscript/RenderScript";

static JNINativeMethod methods[] = {
{"_nInit",                         "()V",                                  (void*)_nInit },
{"nInitElements",                  "(IIII)V",                              (void*)nInitElements },

{"nDeviceCreate",                  "()I",                                  (void*)nDeviceCreate },
{"nDeviceDestroy",                 "(I)V",                                 (void*)nDeviceDestroy },
{"nDeviceSetConfig",               "(III)V",                               (void*)nDeviceSetConfig },
{"nContextCreate",                 "(II)I",                                (void*)nContextCreate },
{"nContextCreateGL",               "(IIZ)I",                               (void*)nContextCreateGL },
{"nContextFinish",                 "()V",                                  (void*)nContextFinish },
{"nContextSetPriority",            "(I)V",                                 (void*)nContextSetPriority },
{"nContextSetSurface",             "(IILandroid/view/Surface;)V",          (void*)nContextSetSurface },
{"nContextDestroy",                "(I)V",                                 (void*)nContextDestroy },
{"nContextDump",                   "(I)V",                                 (void*)nContextDump },
{"nContextPause",                  "()V",                                  (void*)nContextPause },
{"nContextResume",                 "()V",                                  (void*)nContextResume },
{"nAssignName",                    "(I[B)V",                               (void*)nAssignName },
{"nObjDestroy",                    "(I)V",                                 (void*)nObjDestroy },
{"nObjDestroyOOB",                 "(I)V",                                 (void*)nObjDestroyOOB },
{"nContextGetMessage",             "([IZ)I",                               (void*)nContextGetMessage },
{"nContextInitToClient",           "()V",                                  (void*)nContextInitToClient },
{"nContextDeinitToClient",         "()V",                                  (void*)nContextDeinitToClient },

{"nFileOpen",                      "([B)I",                                (void*)nFileOpen },
{"nFileA3DCreateFromAssetStream", "(I)I",                                 (void*)nFileA3DCreateFromAssetStream },
{"nFileA3DGetNumIndexEntries",     "(I)I",                                 (void*)nFileA3DGetNumIndexEntries },
{"nFileA3DGetIndexEntries",        "(II[I[Ljava/lang/String;)V",          (void*)nFileA3DGetIndexEntries },
{"nFileA3DGetEntryByIndex",        "(II)I",                                (void*)nFileA3DGetEntryByIndex },

{"nFontCreateFromFile",           "(Ljava/lang/String;II)I",             (void*)nFontCreateFromFile },

{"nElementCreate",                 "(IIZI)I",                              (void*)nElementCreate },
{"nElementCreate2",                "([I[Ljava/lang/String;)I",             (void*)nElementCreate2 },

{"nTypeBegin",                     "(I)V",                                 (void*)nTypeBegin },
{"nTypeAdd",                       "(II)V",                                (void*)nTypeAdd },
{"nTypeCreate",                    "()I",                                  (void*)nTypeCreate },
{"nTypeFinalDestroy",              "(Landroid/renderscript/Type;)V",       (void*)nTypeFinalDestroy },
{"nTypeSetupFields",               "(Landroid/renderscript/Type;[I[I[Ljava/lang/reflect/Field;)V", (void*)nTypeSetupFields },

{"nAllocationCreateTyped",         "(I)I",                                 (void*)nAllocationCreateTyped },
{"nAllocationCreateFromBitmap",    "(IZLandroid/graphics/Bitmap;)I",       (void*)nAllocationCreateFromBitmap },
{"nAllocationCreateBitmapRef",     "(ILandroid/graphics/Bitmap;)I",        (void*)nAllocationCreateBitmapRef },
{"nAllocationCreateFromBitmapBoxed","(IZLandroid/graphics/Bitmap;)I",      (void*)nAllocationCreateFromBitmapBoxed },
{"nAllocationCreateFromAssetStream","(IZI)I",                              (void*)nAllocationCreateFromAssetStream },
{"nAllocationUploadToTexture",     "(IZI)V",                               (void*)nAllocationUploadToTexture },
{"nAllocationUploadToBufferObject","(I)V",                                 (void*)nAllocationUploadToBufferObject },
{"nAllocationSubData1D",           "(III[II)V",                            (void*)nAllocationSubData1D_i },
{"nAllocationSubData1D",           "(III[SI)V",                            (void*)nAllocationSubData1D_s },
{"nAllocationSubData1D",           "(III[BI)V",                            (void*)nAllocationSubData1D_b },
{"nAllocationSubData1D",           "(III[FI)V",                            (void*)nAllocationSubData1D_f },
{"nAllocationSubData2D",           "(IIIII[II)V",                          (void*)nAllocationSubData2D_i },
{"nAllocationSubData2D",           "(IIIII[FI)V",                          (void*)nAllocationSubData2D_f },
{"nAllocationRead",                "(I[I)V",                               (void*)nAllocationRead_i },
{"nAllocationRead",                "(I[F)V",                               (void*)nAllocationRead_f },
{"nAllocationSubDataFromObject",   "(ILandroid/renderscript/Type;ILjava/lang/Object;)V",   (void*)nAllocationSubDataFromObject },
{"nAllocationSubReadFromObject",   "(ILandroid/renderscript/Type;ILjava/lang/Object;)V",   (void*)nAllocationSubReadFromObject },

{"nAdapter1DBindAllocation",       "(II)V",                                (void*)nAdapter1DBindAllocation },
{"nAdapter1DSetConstraint",        "(III)V",                               (void*)nAdapter1DSetConstraint },
{"nAdapter1DData",                 "(I[I)V",                               (void*)nAdapter1DData_i },
{"nAdapter1DData",                 "(I[F)V",                               (void*)nAdapter1DData_f },
{"nAdapter1DSubData",              "(III[I)V",                             (void*)nAdapter1DSubData_i },
{"nAdapter1DSubData",              "(III[F)V",                             (void*)nAdapter1DSubData_f },
{"nAdapter1DCreate",               "()I",                                  (void*)nAdapter1DCreate },

{"nAdapter2DBindAllocation",       "(II)V",                                (void*)nAdapter2DBindAllocation },
{"nAdapter2DSetConstraint",        "(III)V",                               (void*)nAdapter2DSetConstraint },
{"nAdapter2DData",                 "(I[I)V",                               (void*)nAdapter2DData_i },
{"nAdapter2DData",                 "(I[F)V",                               (void*)nAdapter2DData_f },
{"nAdapter2DSubData",              "(IIIII[I)V",                           (void*)nAdapter2DSubData_i },
{"nAdapter2DSubData",              "(IIIII[F)V",                           (void*)nAdapter2DSubData_f },
{"nAdapter2DCreate",               "()I",                                  (void*)nAdapter2DCreate },

{"nScriptBindAllocation",          "(III)V",                               (void*)nScriptBindAllocation },
{"nScriptSetTimeZone",             "(I[B)V",                               (void*)nScriptSetTimeZone },
{"nScriptInvoke",                  "(II)V",                                (void*)nScriptInvoke },
{"nScriptInvokeV",                 "(II[B)V",                              (void*)nScriptInvokeV },
{"nScriptSetVarI",                 "(III)V",                               (void*)nScriptSetVarI },
{"nScriptSetVarF",                 "(IIF)V",                               (void*)nScriptSetVarF },
{"nScriptSetVarV",                 "(II[B)V",                              (void*)nScriptSetVarV },

{"nScriptCBegin",                  "()V",                                  (void*)nScriptCBegin },
{"nScriptCSetScript",              "([BII)V",                              (void*)nScriptCSetScript },
{"nScriptCCreate",                 "()I",                                  (void*)nScriptCCreate },

{"nProgramStoreBegin",             "(II)V",                                (void*)nProgramStoreBegin },
{"nProgramStoreDepthFunc",         "(I)V",                                 (void*)nProgramStoreDepthFunc },
{"nProgramStoreDepthMask",         "(Z)V",                                 (void*)nProgramStoreDepthMask },
{"nProgramStoreColorMask",         "(ZZZZ)V",                              (void*)nProgramStoreColorMask },
{"nProgramStoreBlendFunc",         "(II)V",                                (void*)nProgramStoreBlendFunc },
{"nProgramStoreDither",            "(Z)V",                                 (void*)nProgramStoreDither },
{"nProgramStoreCreate",            "()I",                                  (void*)nProgramStoreCreate },

{"nProgramBindConstants",          "(III)V",                               (void*)nProgramBindConstants },
{"nProgramBindTexture",            "(III)V",                               (void*)nProgramBindTexture },
{"nProgramBindSampler",            "(III)V",                               (void*)nProgramBindSampler },

{"nProgramFragmentCreate",         "([I)I",                                (void*)nProgramFragmentCreate },
{"nProgramFragmentCreate2",        "(Ljava/lang/String;[I)I",              (void*)nProgramFragmentCreate2 },

{"nProgramRasterCreate",           "(IIZZZ)I",                             (void*)nProgramRasterCreate },
{"nProgramRasterSetPointSize",     "(IF)V",                                (void*)nProgramRasterSetPointSize },
{"nProgramRasterSetLineWidth",     "(IF)V",                                (void*)nProgramRasterSetLineWidth },

{"nProgramVertexCreate",           "(Z)I",                                 (void*)nProgramVertexCreate },
{"nProgramVertexCreate2",          "(Ljava/lang/String;[I)I",              (void*)nProgramVertexCreate2 },

{"nLightBegin",                    "()V",                                  (void*)nLightBegin },
{"nLightSetIsMono",                "(Z)V",                                 (void*)nLightSetIsMono },
{"nLightSetIsLocal",               "(Z)V",                                 (void*)nLightSetIsLocal },
{"nLightCreate",                   "()I",                                  (void*)nLightCreate },
{"nLightSetColor",                 "(IFFF)V",                              (void*)nLightSetColor },
{"nLightSetPosition",              "(IFFF)V",                              (void*)nLightSetPosition },

{"nContextBindRootScript",         "(I)V",                                 (void*)nContextBindRootScript },
{"nContextBindProgramStore",       "(I)V",                                (void*)nContextBindProgramStore },
{"nContextBindProgramFragment",    "(I)V",                                 (void*)nContextBindProgramFragment },
{"nContextBindProgramVertex",      "(I)V",                                 (void*)nContextBindProgramVertex },
{"nContextBindProgramRaster",      "(I)V",                                 (void*)nContextBindProgramRaster },

{"nSamplerBegin",                  "()V",                                  (void*)nSamplerBegin },
{"nSamplerSet",                    "(II)V",                                (void*)nSamplerSet },
{"nSamplerCreate",                 "()I",                                  (void*)nSamplerCreate },

{"nMeshCreate",                    "(II)I",                                (void*)nMeshCreate },
{"nMeshBindVertex",                "(III)V",                               (void*)nMeshBindVertex },
{"nMeshBindIndex",                 "(IIII)V",                              (void*)nMeshBindIndex },

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
