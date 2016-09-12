#define LOG_TAG "GraphicsJNI"

#include <unistd.h>
#include <sys/mman.h>

#include "jni.h"
#include "JNIHelp.h"
#include "GraphicsJNI.h"

#include "SkCanvas.h"
#include "SkDevice.h"
#include "SkMath.h"
#include "SkRegion.h"
#include <android_runtime/AndroidRuntime.h>
#include <cutils/ashmem.h>
#include <hwui/Canvas.h>

#include <Caches.h>
#include <TextureCache.h>

void doThrowNPE(JNIEnv* env) {
    jniThrowNullPointerException(env, NULL);
}

void doThrowAIOOBE(JNIEnv* env) {
    jniThrowException(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
}

void doThrowRE(JNIEnv* env, const char* msg) {
    jniThrowRuntimeException(env, msg);
}

void doThrowIAE(JNIEnv* env, const char* msg) {
    jniThrowException(env, "java/lang/IllegalArgumentException", msg);
}

void doThrowISE(JNIEnv* env, const char* msg) {
    jniThrowException(env, "java/lang/IllegalStateException", msg);
}

void doThrowOOME(JNIEnv* env, const char* msg) {
    jniThrowException(env, "java/lang/OutOfMemoryError", msg);
}

void doThrowIOE(JNIEnv* env, const char* msg) {
    jniThrowException(env, "java/io/IOException", msg);
}

bool GraphicsJNI::hasException(JNIEnv *env) {
    if (env->ExceptionCheck() != 0) {
        ALOGE("*** Uncaught exception returned from Java call!\n");
        env->ExceptionDescribe();
        return true;
    }
    return false;
}

///////////////////////////////////////////////////////////////////////////////

AutoJavaFloatArray::AutoJavaFloatArray(JNIEnv* env, jfloatArray array,
                                       int minLength, JNIAccess access)
: fEnv(env), fArray(array), fPtr(NULL), fLen(0) {
    SkASSERT(env);
    if (array) {
        fLen = env->GetArrayLength(array);
        if (fLen < minLength) {
            sk_throw();
        }
        fPtr = env->GetFloatArrayElements(array, NULL);
    }
    fReleaseMode = (access == kRO_JNIAccess) ? JNI_ABORT : 0;
}

AutoJavaFloatArray::~AutoJavaFloatArray() {
    if (fPtr) {
        fEnv->ReleaseFloatArrayElements(fArray, fPtr, fReleaseMode);
    }
}

AutoJavaIntArray::AutoJavaIntArray(JNIEnv* env, jintArray array,
                                       int minLength)
: fEnv(env), fArray(array), fPtr(NULL), fLen(0) {
    SkASSERT(env);
    if (array) {
        fLen = env->GetArrayLength(array);
        if (fLen < minLength) {
            sk_throw();
        }
        fPtr = env->GetIntArrayElements(array, NULL);
    }
}

AutoJavaIntArray::~AutoJavaIntArray() {
    if (fPtr) {
        fEnv->ReleaseIntArrayElements(fArray, fPtr, 0);
    }
}

AutoJavaShortArray::AutoJavaShortArray(JNIEnv* env, jshortArray array,
                                       int minLength, JNIAccess access)
: fEnv(env), fArray(array), fPtr(NULL), fLen(0) {
    SkASSERT(env);
    if (array) {
        fLen = env->GetArrayLength(array);
        if (fLen < minLength) {
            sk_throw();
        }
        fPtr = env->GetShortArrayElements(array, NULL);
    }
    fReleaseMode = (access == kRO_JNIAccess) ? JNI_ABORT : 0;
}

AutoJavaShortArray::~AutoJavaShortArray() {
    if (fPtr) {
        fEnv->ReleaseShortArrayElements(fArray, fPtr, fReleaseMode);
    }
}

AutoJavaByteArray::AutoJavaByteArray(JNIEnv* env, jbyteArray array,
                                       int minLength)
: fEnv(env), fArray(array), fPtr(NULL), fLen(0) {
    SkASSERT(env);
    if (array) {
        fLen = env->GetArrayLength(array);
        if (fLen < minLength) {
            sk_throw();
        }
        fPtr = env->GetByteArrayElements(array, NULL);
    }
}

AutoJavaByteArray::~AutoJavaByteArray() {
    if (fPtr) {
        fEnv->ReleaseByteArrayElements(fArray, fPtr, 0);
    }
}

///////////////////////////////////////////////////////////////////////////////

static jclass   gRect_class;
static jfieldID gRect_leftFieldID;
static jfieldID gRect_topFieldID;
static jfieldID gRect_rightFieldID;
static jfieldID gRect_bottomFieldID;

static jclass   gRectF_class;
static jfieldID gRectF_leftFieldID;
static jfieldID gRectF_topFieldID;
static jfieldID gRectF_rightFieldID;
static jfieldID gRectF_bottomFieldID;

static jclass   gPoint_class;
static jfieldID gPoint_xFieldID;
static jfieldID gPoint_yFieldID;

static jclass   gPointF_class;
static jfieldID gPointF_xFieldID;
static jfieldID gPointF_yFieldID;

static jclass   gBitmap_class;
static jfieldID gBitmap_nativePtr;
static jmethodID gBitmap_constructorMethodID;
static jmethodID gBitmap_reinitMethodID;
static jmethodID gBitmap_getAllocationByteCountMethodID;

static jclass   gBitmapConfig_class;
static jfieldID gBitmapConfig_nativeInstanceID;

static jclass   gBitmapRegionDecoder_class;
static jmethodID gBitmapRegionDecoder_constructorMethodID;

static jclass   gCanvas_class;
static jfieldID gCanvas_nativeInstanceID;

static jclass   gPicture_class;
static jfieldID gPicture_nativeInstanceID;

static jclass   gRegion_class;
static jfieldID gRegion_nativeInstanceID;
static jmethodID gRegion_constructorMethodID;

static jclass    gByte_class;
static jobject   gVMRuntime;
static jclass    gVMRuntime_class;
static jmethodID gVMRuntime_newNonMovableArray;
static jmethodID gVMRuntime_addressOf;

///////////////////////////////////////////////////////////////////////////////

void GraphicsJNI::get_jrect(JNIEnv* env, jobject obj, int* L, int* T, int* R, int* B)
{
    SkASSERT(env->IsInstanceOf(obj, gRect_class));

    *L = env->GetIntField(obj, gRect_leftFieldID);
    *T = env->GetIntField(obj, gRect_topFieldID);
    *R = env->GetIntField(obj, gRect_rightFieldID);
    *B = env->GetIntField(obj, gRect_bottomFieldID);
}

void GraphicsJNI::set_jrect(JNIEnv* env, jobject obj, int L, int T, int R, int B)
{
    SkASSERT(env->IsInstanceOf(obj, gRect_class));

    env->SetIntField(obj, gRect_leftFieldID, L);
    env->SetIntField(obj, gRect_topFieldID, T);
    env->SetIntField(obj, gRect_rightFieldID, R);
    env->SetIntField(obj, gRect_bottomFieldID, B);
}

SkIRect* GraphicsJNI::jrect_to_irect(JNIEnv* env, jobject obj, SkIRect* ir)
{
    SkASSERT(env->IsInstanceOf(obj, gRect_class));

    ir->set(env->GetIntField(obj, gRect_leftFieldID),
            env->GetIntField(obj, gRect_topFieldID),
            env->GetIntField(obj, gRect_rightFieldID),
            env->GetIntField(obj, gRect_bottomFieldID));
    return ir;
}

void GraphicsJNI::irect_to_jrect(const SkIRect& ir, JNIEnv* env, jobject obj)
{
    SkASSERT(env->IsInstanceOf(obj, gRect_class));

    env->SetIntField(obj, gRect_leftFieldID, ir.fLeft);
    env->SetIntField(obj, gRect_topFieldID, ir.fTop);
    env->SetIntField(obj, gRect_rightFieldID, ir.fRight);
    env->SetIntField(obj, gRect_bottomFieldID, ir.fBottom);
}

SkRect* GraphicsJNI::jrectf_to_rect(JNIEnv* env, jobject obj, SkRect* r)
{
    SkASSERT(env->IsInstanceOf(obj, gRectF_class));

    r->set(env->GetFloatField(obj, gRectF_leftFieldID),
           env->GetFloatField(obj, gRectF_topFieldID),
           env->GetFloatField(obj, gRectF_rightFieldID),
           env->GetFloatField(obj, gRectF_bottomFieldID));
    return r;
}

SkRect* GraphicsJNI::jrect_to_rect(JNIEnv* env, jobject obj, SkRect* r)
{
    SkASSERT(env->IsInstanceOf(obj, gRect_class));

    r->set(SkIntToScalar(env->GetIntField(obj, gRect_leftFieldID)),
           SkIntToScalar(env->GetIntField(obj, gRect_topFieldID)),
           SkIntToScalar(env->GetIntField(obj, gRect_rightFieldID)),
           SkIntToScalar(env->GetIntField(obj, gRect_bottomFieldID)));
    return r;
}

void GraphicsJNI::rect_to_jrectf(const SkRect& r, JNIEnv* env, jobject obj)
{
    SkASSERT(env->IsInstanceOf(obj, gRectF_class));

    env->SetFloatField(obj, gRectF_leftFieldID, SkScalarToFloat(r.fLeft));
    env->SetFloatField(obj, gRectF_topFieldID, SkScalarToFloat(r.fTop));
    env->SetFloatField(obj, gRectF_rightFieldID, SkScalarToFloat(r.fRight));
    env->SetFloatField(obj, gRectF_bottomFieldID, SkScalarToFloat(r.fBottom));
}

SkIPoint* GraphicsJNI::jpoint_to_ipoint(JNIEnv* env, jobject obj, SkIPoint* point)
{
    SkASSERT(env->IsInstanceOf(obj, gPoint_class));

    point->set(env->GetIntField(obj, gPoint_xFieldID),
               env->GetIntField(obj, gPoint_yFieldID));
    return point;
}

void GraphicsJNI::ipoint_to_jpoint(const SkIPoint& ir, JNIEnv* env, jobject obj)
{
    SkASSERT(env->IsInstanceOf(obj, gPoint_class));

    env->SetIntField(obj, gPoint_xFieldID, ir.fX);
    env->SetIntField(obj, gPoint_yFieldID, ir.fY);
}

SkPoint* GraphicsJNI::jpointf_to_point(JNIEnv* env, jobject obj, SkPoint* point)
{
    SkASSERT(env->IsInstanceOf(obj, gPointF_class));

    point->set(env->GetIntField(obj, gPointF_xFieldID),
               env->GetIntField(obj, gPointF_yFieldID));
    return point;
}

void GraphicsJNI::point_to_jpointf(const SkPoint& r, JNIEnv* env, jobject obj)
{
    SkASSERT(env->IsInstanceOf(obj, gPointF_class));

    env->SetFloatField(obj, gPointF_xFieldID, SkScalarToFloat(r.fX));
    env->SetFloatField(obj, gPointF_yFieldID, SkScalarToFloat(r.fY));
}

// This enum must keep these int values, to match the int values
// in the java Bitmap.Config enum.
enum LegacyBitmapConfig {
    kNo_LegacyBitmapConfig          = 0,
    kA8_LegacyBitmapConfig          = 1,
    kIndex8_LegacyBitmapConfig      = 2,
    kRGB_565_LegacyBitmapConfig     = 3,
    kARGB_4444_LegacyBitmapConfig   = 4,
    kARGB_8888_LegacyBitmapConfig   = 5,

    kLastEnum_LegacyBitmapConfig = kARGB_8888_LegacyBitmapConfig
};

jint GraphicsJNI::colorTypeToLegacyBitmapConfig(SkColorType colorType) {
    switch (colorType) {
        case kN32_SkColorType:
            return kARGB_8888_LegacyBitmapConfig;
        case kARGB_4444_SkColorType:
            return kARGB_4444_LegacyBitmapConfig;
        case kRGB_565_SkColorType:
            return kRGB_565_LegacyBitmapConfig;
        case kIndex_8_SkColorType:
            return kIndex8_LegacyBitmapConfig;
        case kAlpha_8_SkColorType:
            return kA8_LegacyBitmapConfig;
        case kUnknown_SkColorType:
        default:
            break;
    }
    return kNo_LegacyBitmapConfig;
}

SkColorType GraphicsJNI::legacyBitmapConfigToColorType(jint legacyConfig) {
    const uint8_t gConfig2ColorType[] = {
        kUnknown_SkColorType,
        kAlpha_8_SkColorType,
        kIndex_8_SkColorType,
        kRGB_565_SkColorType,
        kARGB_4444_SkColorType,
        kN32_SkColorType
    };

    if (legacyConfig < 0 || legacyConfig > kLastEnum_LegacyBitmapConfig) {
        legacyConfig = kNo_LegacyBitmapConfig;
    }
    return static_cast<SkColorType>(gConfig2ColorType[legacyConfig]);
}

android::Bitmap* GraphicsJNI::getBitmap(JNIEnv* env, jobject bitmap) {
    SkASSERT(env);
    SkASSERT(bitmap);
    SkASSERT(env->IsInstanceOf(bitmap, gBitmap_class));
    jlong bitmapHandle = env->GetLongField(bitmap, gBitmap_nativePtr);
    android::Bitmap* b = reinterpret_cast<android::Bitmap*>(bitmapHandle);
    SkASSERT(b);
    return b;
}

void GraphicsJNI::getSkBitmap(JNIEnv* env, jobject bitmap, SkBitmap* outBitmap) {
    getBitmap(env, bitmap)->getSkBitmap(outBitmap);
}

SkPixelRef* GraphicsJNI::refSkPixelRef(JNIEnv* env, jobject bitmap) {
    return getBitmap(env, bitmap)->refPixelRef();
}

SkColorType GraphicsJNI::getNativeBitmapColorType(JNIEnv* env, jobject jconfig) {
    SkASSERT(env);
    if (NULL == jconfig) {
        return kUnknown_SkColorType;
    }
    SkASSERT(env->IsInstanceOf(jconfig, gBitmapConfig_class));
    int c = env->GetIntField(jconfig, gBitmapConfig_nativeInstanceID);
    return legacyBitmapConfigToColorType(c);
}

android::Canvas* GraphicsJNI::getNativeCanvas(JNIEnv* env, jobject canvas) {
    SkASSERT(env);
    SkASSERT(canvas);
    SkASSERT(env->IsInstanceOf(canvas, gCanvas_class));
    jlong canvasHandle = env->GetLongField(canvas, gCanvas_nativeInstanceID);
    if (!canvasHandle) {
        return NULL;
    }
    return reinterpret_cast<android::Canvas*>(canvasHandle);
}

SkRegion* GraphicsJNI::getNativeRegion(JNIEnv* env, jobject region)
{
    SkASSERT(env);
    SkASSERT(region);
    SkASSERT(env->IsInstanceOf(region, gRegion_class));
    jlong regionHandle = env->GetLongField(region, gRegion_nativeInstanceID);
    SkRegion* r = reinterpret_cast<SkRegion*>(regionHandle);
    SkASSERT(r);
    return r;
}

///////////////////////////////////////////////////////////////////////////////////////////

// Assert that bitmap's SkAlphaType is consistent with isPremultiplied.
static void assert_premultiplied(const SkImageInfo& info, bool isPremultiplied) {
    // kOpaque_SkAlphaType and kIgnore_SkAlphaType mean that isPremultiplied is
    // irrelevant. This just tests to ensure that the SkAlphaType is not
    // opposite of isPremultiplied.
    if (isPremultiplied) {
        SkASSERT(info.alphaType() != kUnpremul_SkAlphaType);
    } else {
        SkASSERT(info.alphaType() != kPremul_SkAlphaType);
    }
}

jobject GraphicsJNI::createBitmap(JNIEnv* env, android::Bitmap* bitmap,
        int bitmapCreateFlags, jbyteArray ninePatchChunk, jobject ninePatchInsets,
        int density) {
    bool isMutable = bitmapCreateFlags & kBitmapCreateFlag_Mutable;
    bool isPremultiplied = bitmapCreateFlags & kBitmapCreateFlag_Premultiplied;
    // The caller needs to have already set the alpha type properly, so the
    // native SkBitmap stays in sync with the Java Bitmap.
    assert_premultiplied(bitmap->info(), isPremultiplied);

    jobject obj = env->NewObject(gBitmap_class, gBitmap_constructorMethodID,
            reinterpret_cast<jlong>(bitmap), bitmap->javaByteArray(),
            bitmap->width(), bitmap->height(), density, isMutable, isPremultiplied,
            ninePatchChunk, ninePatchInsets);
    hasException(env); // For the side effect of logging.
    return obj;
}

void GraphicsJNI::reinitBitmap(JNIEnv* env, jobject javaBitmap, const SkImageInfo& info,
        bool isPremultiplied)
{
    // The caller needs to have already set the alpha type properly, so the
    // native SkBitmap stays in sync with the Java Bitmap.
    assert_premultiplied(info, isPremultiplied);

    env->CallVoidMethod(javaBitmap, gBitmap_reinitMethodID,
            info.width(), info.height(), isPremultiplied);
}

int GraphicsJNI::getBitmapAllocationByteCount(JNIEnv* env, jobject javaBitmap)
{
    return env->CallIntMethod(javaBitmap, gBitmap_getAllocationByteCountMethodID);
}

jobject GraphicsJNI::createBitmapRegionDecoder(JNIEnv* env, SkBitmapRegionDecoder* bitmap)
{
    SkASSERT(bitmap != NULL);

    jobject obj = env->NewObject(gBitmapRegionDecoder_class,
            gBitmapRegionDecoder_constructorMethodID,
            reinterpret_cast<jlong>(bitmap));
    hasException(env); // For the side effect of logging.
    return obj;
}

jobject GraphicsJNI::createRegion(JNIEnv* env, SkRegion* region)
{
    SkASSERT(region != NULL);
    jobject obj = env->NewObject(gRegion_class, gRegion_constructorMethodID,
                                 reinterpret_cast<jlong>(region), 0);
    hasException(env); // For the side effect of logging.
    return obj;
}

static JNIEnv* vm2env(JavaVM* vm)
{
    JNIEnv* env = NULL;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_4) != JNI_OK || NULL == env)
    {
        SkDebugf("------- [%p] vm->GetEnv() failed\n", vm);
        sk_throw();
    }
    return env;
}

///////////////////////////////////////////////////////////////////////////////

static bool computeAllocationSize(const SkBitmap& bitmap, size_t* size) {
    int32_t rowBytes32 = SkToS32(bitmap.rowBytes());
    int64_t bigSize = (int64_t)bitmap.height() * rowBytes32;
    if (rowBytes32 < 0 || !sk_64_isS32(bigSize)) {
        return false; // allocation will be too large
    }

    *size = sk_64_asS32(bigSize);
    return true;
}

android::Bitmap* GraphicsJNI::allocateJavaPixelRef(JNIEnv* env, SkBitmap* bitmap,
                                             SkColorTable* ctable) {
    const SkImageInfo& info = bitmap->info();
    if (info.colorType() == kUnknown_SkColorType) {
        doThrowIAE(env, "unknown bitmap configuration");
        return NULL;
    }

    size_t size;
    if (!computeAllocationSize(*bitmap, &size)) {
        return NULL;
    }

    // we must respect the rowBytes value already set on the bitmap instead of
    // attempting to compute our own.
    const size_t rowBytes = bitmap->rowBytes();

    jbyteArray arrayObj = (jbyteArray) env->CallObjectMethod(gVMRuntime,
                                                             gVMRuntime_newNonMovableArray,
                                                             gByte_class, size);
    if (env->ExceptionCheck() != 0) {
        return NULL;
    }
    SkASSERT(arrayObj);
    jbyte* addr = (jbyte*) env->CallLongMethod(gVMRuntime, gVMRuntime_addressOf, arrayObj);
    if (env->ExceptionCheck() != 0) {
        return NULL;
    }
    SkASSERT(addr);
    android::Bitmap* wrapper = new android::Bitmap(env, arrayObj, (void*) addr,
            info, rowBytes, ctable);
    wrapper->getSkBitmap(bitmap);
    // since we're already allocated, we lockPixels right away
    // HeapAllocator behaves this way too
    bitmap->lockPixels();

    return wrapper;
}

struct AndroidPixelRefContext {
    int32_t stableID;
};

static void allocatePixelsReleaseProc(void* ptr, void* ctx) {
    AndroidPixelRefContext* context = (AndroidPixelRefContext*)ctx;
    if (android::uirenderer::Caches::hasInstance()) {
         android::uirenderer::Caches::getInstance().textureCache.releaseTexture(context->stableID);
    }

    sk_free(ptr);
    delete context;
}

bool GraphicsJNI::allocatePixels(JNIEnv* env, SkBitmap* bitmap, SkColorTable* ctable) {
    const SkImageInfo& info = bitmap->info();
    if (info.colorType() == kUnknown_SkColorType) {
        doThrowIAE(env, "unknown bitmap configuration");
        return NULL;
    }

    size_t size;
    if (!computeAllocationSize(*bitmap, &size)) {
        return false;
    }

    // we must respect the rowBytes value already set on the bitmap instead of
    // attempting to compute our own.
    const size_t rowBytes = bitmap->rowBytes();

    void* addr = sk_malloc_flags(size, 0);
    if (NULL == addr) {
        return false;
    }

    AndroidPixelRefContext* context = new AndroidPixelRefContext;
    SkMallocPixelRef* pr = SkMallocPixelRef::NewWithProc(info, rowBytes, ctable, addr,
                                                         &allocatePixelsReleaseProc, context);
    if (!pr) {
        delete context;
        return false;
    }

    // set the stableID in the context so that it can be used later in
    // allocatePixelsReleaseProc to remove the texture from the cache.
    context->stableID = pr->getStableID();

    bitmap->setPixelRef(pr)->unref();
    // since we're already allocated, we can lockPixels right away
    bitmap->lockPixels();

    return true;
}

android::Bitmap* GraphicsJNI::allocateAshmemPixelRef(JNIEnv* env, SkBitmap* bitmap,
                                                     SkColorTable* ctable) {
    int fd;

    const SkImageInfo& info = bitmap->info();
    if (info.colorType() == kUnknown_SkColorType) {
        doThrowIAE(env, "unknown bitmap configuration");
        return nullptr;
    }

    size_t size;
    if (!computeAllocationSize(*bitmap, &size)) {
        return nullptr;
    }

    // we must respect the rowBytes value already set on the bitmap instead of
    // attempting to compute our own.
    const size_t rowBytes = bitmap->rowBytes();

    // Create new ashmem region with read/write priv
    fd = ashmem_create_region("bitmap", size);
    if (fd < 0) {
        return nullptr;
    }

    void* addr = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (addr == MAP_FAILED) {
        close(fd);
        return nullptr;
    }

    if (ashmem_set_prot_region(fd, PROT_READ) < 0) {
        munmap(addr, size);
        close(fd);
        return nullptr;
    }

    android::Bitmap* wrapper = new android::Bitmap(addr, fd, size, info, rowBytes, ctable);
    wrapper->getSkBitmap(bitmap);
    // since we're already allocated, we lockPixels right away
    // HeapAllocator behaves this way too
    bitmap->lockPixels();

    return wrapper;
}

android::Bitmap* GraphicsJNI::mapAshmemPixelRef(JNIEnv* env, SkBitmap* bitmap,
        SkColorTable* ctable, int fd, void* addr, size_t size, bool readOnly) {
    const SkImageInfo& info = bitmap->info();
    if (info.colorType() == kUnknown_SkColorType) {
        doThrowIAE(env, "unknown bitmap configuration");
        return nullptr;
    }

    if (!addr) {
        // Map existing ashmem region if not already mapped.
        int flags = readOnly ? (PROT_READ) : (PROT_READ | PROT_WRITE);
        size = ashmem_get_size_region(fd);
        addr = mmap(NULL, size, flags, MAP_SHARED, fd, 0);
        if (addr == MAP_FAILED) {
            return nullptr;
        }
    }

    // we must respect the rowBytes value already set on the bitmap instead of
    // attempting to compute our own.
    const size_t rowBytes = bitmap->rowBytes();

    android::Bitmap* wrapper = new android::Bitmap(addr, fd, size, info, rowBytes, ctable);
    wrapper->getSkBitmap(bitmap);
    if (readOnly) {
        bitmap->pixelRef()->setImmutable();
    }
    // since we're already allocated, we lockPixels right away
    // HeapAllocator behaves this way too
    bitmap->lockPixels();

    return wrapper;
}

///////////////////////////////////////////////////////////////////////////////

JavaPixelAllocator::JavaPixelAllocator(JNIEnv* env) {
    LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&mJavaVM) != JNI_OK,
            "env->GetJavaVM failed");
}

JavaPixelAllocator::~JavaPixelAllocator() {
    if (mStorage) {
        mStorage->detachFromJava();
    }
}

bool JavaPixelAllocator::allocPixelRef(SkBitmap* bitmap, SkColorTable* ctable) {
    JNIEnv* env = vm2env(mJavaVM);

    mStorage = GraphicsJNI::allocateJavaPixelRef(env, bitmap, ctable);
    return mStorage != nullptr;
}

////////////////////////////////////////////////////////////////////////////////

RecyclingClippingPixelAllocator::RecyclingClippingPixelAllocator(
        android::Bitmap* recycledBitmap, size_t recycledBytes)
    : mRecycledBitmap(recycledBitmap)
    , mRecycledBytes(recycledBytes)
    , mSkiaBitmap(nullptr)
    , mNeedsCopy(false)
{}

RecyclingClippingPixelAllocator::~RecyclingClippingPixelAllocator() {}

bool RecyclingClippingPixelAllocator::allocPixelRef(SkBitmap* bitmap, SkColorTable* ctable) {
    // Ensure that the caller did not pass in a NULL bitmap to the constructor or this
    // function.
    LOG_ALWAYS_FATAL_IF(!mRecycledBitmap);
    LOG_ALWAYS_FATAL_IF(!bitmap);
    mSkiaBitmap = bitmap;

    // This behaves differently than the RecyclingPixelAllocator.  For backwards
    // compatibility, the original color type of the recycled bitmap must be maintained.
    if (mRecycledBitmap->info().colorType() != bitmap->colorType()) {
        return false;
    }

    // The Skia bitmap specifies the width and height needed by the decoder.
    // mRecycledBitmap specifies the width and height of the bitmap that we
    // want to reuse.  Neither can be changed.  We will try to find a way
    // to reuse the memory.
    const int maxWidth = SkTMax(bitmap->width(), mRecycledBitmap->info().width());
    const int maxHeight = SkTMax(bitmap->height(), mRecycledBitmap->info().height());
    const SkImageInfo maxInfo = bitmap->info().makeWH(maxWidth, maxHeight);
    const size_t rowBytes = maxInfo.minRowBytes();
    const size_t bytesNeeded = maxInfo.getSafeSize(rowBytes);
    if (bytesNeeded <= mRecycledBytes) {
        // Here we take advantage of reconfigure() to reset the rowBytes and ctable
        // of mRecycledBitmap.  It is very important that we pass in
        // mRecycledBitmap->info() for the SkImageInfo.  According to the
        // specification for BitmapRegionDecoder, we are not allowed to change
        // the SkImageInfo.
        mRecycledBitmap->reconfigure(mRecycledBitmap->info(), rowBytes, ctable);

        // This call will give the bitmap the same pixelRef as mRecycledBitmap.
        bitmap->setPixelRef(mRecycledBitmap->refPixelRef())->unref();

        // Make sure that the recycled bitmap has the correct alpha type.
        mRecycledBitmap->setAlphaType(bitmap->alphaType());

        bitmap->notifyPixelsChanged();
        bitmap->lockPixels();
        mNeedsCopy = false;

        // TODO: If the dimensions of the SkBitmap are smaller than those of
        // mRecycledBitmap, should we zero the memory in mRecycledBitmap?
        return true;
    }

    // In the event that mRecycledBitmap is not large enough, allocate new memory
    // on the heap.
    SkBitmap::HeapAllocator heapAllocator;

    // We will need to copy from heap memory to mRecycledBitmap's memory after the
    // decode is complete.
    mNeedsCopy = true;

    return heapAllocator.allocPixelRef(bitmap, ctable);
}

void RecyclingClippingPixelAllocator::copyIfNecessary() {
    if (mNeedsCopy) {
        SkPixelRef* recycledPixels = mRecycledBitmap->refPixelRef();
        void* dst = recycledPixels->pixels();
        const size_t dstRowBytes = mRecycledBitmap->rowBytes();
        const size_t bytesToCopy = std::min(mRecycledBitmap->info().minRowBytes(),
                mSkiaBitmap->info().minRowBytes());
        const int rowsToCopy = std::min(mRecycledBitmap->info().height(),
                mSkiaBitmap->info().height());
        for (int y = 0; y < rowsToCopy; y++) {
            memcpy(dst, mSkiaBitmap->getAddr(0, y), bytesToCopy);
            dst = SkTAddOffset<void>(dst, dstRowBytes);
        }
        recycledPixels->notifyPixelsChanged();
        recycledPixels->unref();
    }
    mRecycledBitmap = nullptr;
    mSkiaBitmap = nullptr;
}

////////////////////////////////////////////////////////////////////////////////

AshmemPixelAllocator::AshmemPixelAllocator(JNIEnv *env) {
    LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&mJavaVM) != JNI_OK,
            "env->GetJavaVM failed");
}

AshmemPixelAllocator::~AshmemPixelAllocator() {
    if (mStorage) {
        mStorage->detachFromJava();
    }
}

bool AshmemPixelAllocator::allocPixelRef(SkBitmap* bitmap, SkColorTable* ctable) {
    JNIEnv* env = vm2env(mJavaVM);
    mStorage = GraphicsJNI::allocateAshmemPixelRef(env, bitmap, ctable);
    return mStorage != nullptr;
}

////////////////////////////////////////////////////////////////////////////////

static jclass make_globalref(JNIEnv* env, const char classname[])
{
    jclass c = env->FindClass(classname);
    SkASSERT(c);
    return (jclass) env->NewGlobalRef(c);
}

static jfieldID getFieldIDCheck(JNIEnv* env, jclass clazz,
                                const char fieldname[], const char type[])
{
    jfieldID id = env->GetFieldID(clazz, fieldname, type);
    SkASSERT(id);
    return id;
}

int register_android_graphics_Graphics(JNIEnv* env)
{
    jmethodID m;
    jclass c;

    gRect_class = make_globalref(env, "android/graphics/Rect");
    gRect_leftFieldID = getFieldIDCheck(env, gRect_class, "left", "I");
    gRect_topFieldID = getFieldIDCheck(env, gRect_class, "top", "I");
    gRect_rightFieldID = getFieldIDCheck(env, gRect_class, "right", "I");
    gRect_bottomFieldID = getFieldIDCheck(env, gRect_class, "bottom", "I");

    gRectF_class = make_globalref(env, "android/graphics/RectF");
    gRectF_leftFieldID = getFieldIDCheck(env, gRectF_class, "left", "F");
    gRectF_topFieldID = getFieldIDCheck(env, gRectF_class, "top", "F");
    gRectF_rightFieldID = getFieldIDCheck(env, gRectF_class, "right", "F");
    gRectF_bottomFieldID = getFieldIDCheck(env, gRectF_class, "bottom", "F");

    gPoint_class = make_globalref(env, "android/graphics/Point");
    gPoint_xFieldID = getFieldIDCheck(env, gPoint_class, "x", "I");
    gPoint_yFieldID = getFieldIDCheck(env, gPoint_class, "y", "I");

    gPointF_class = make_globalref(env, "android/graphics/PointF");
    gPointF_xFieldID = getFieldIDCheck(env, gPointF_class, "x", "F");
    gPointF_yFieldID = getFieldIDCheck(env, gPointF_class, "y", "F");

    gBitmap_class = make_globalref(env, "android/graphics/Bitmap");
    gBitmap_nativePtr = getFieldIDCheck(env, gBitmap_class, "mNativePtr", "J");
    gBitmap_constructorMethodID = env->GetMethodID(gBitmap_class, "<init>", "(J[BIIIZZ[BLandroid/graphics/NinePatch$InsetStruct;)V");
    gBitmap_reinitMethodID = env->GetMethodID(gBitmap_class, "reinit", "(IIZ)V");
    gBitmap_getAllocationByteCountMethodID = env->GetMethodID(gBitmap_class, "getAllocationByteCount", "()I");
    gBitmapRegionDecoder_class = make_globalref(env, "android/graphics/BitmapRegionDecoder");
    gBitmapRegionDecoder_constructorMethodID = env->GetMethodID(gBitmapRegionDecoder_class, "<init>", "(J)V");

    gBitmapConfig_class = make_globalref(env, "android/graphics/Bitmap$Config");
    gBitmapConfig_nativeInstanceID = getFieldIDCheck(env, gBitmapConfig_class,
                                                     "nativeInt", "I");

    gCanvas_class = make_globalref(env, "android/graphics/Canvas");
    gCanvas_nativeInstanceID = getFieldIDCheck(env, gCanvas_class, "mNativeCanvasWrapper", "J");

    gPicture_class = make_globalref(env, "android/graphics/Picture");
    gPicture_nativeInstanceID = getFieldIDCheck(env, gPicture_class, "mNativePicture", "J");

    gRegion_class = make_globalref(env, "android/graphics/Region");
    gRegion_nativeInstanceID = getFieldIDCheck(env, gRegion_class, "mNativeRegion", "J");
    gRegion_constructorMethodID = env->GetMethodID(gRegion_class, "<init>",
        "(JI)V");

    c = env->FindClass("java/lang/Byte");
    gByte_class = (jclass) env->NewGlobalRef(
        env->GetStaticObjectField(c, env->GetStaticFieldID(c, "TYPE", "Ljava/lang/Class;")));

    gVMRuntime_class = make_globalref(env, "dalvik/system/VMRuntime");
    m = env->GetStaticMethodID(gVMRuntime_class, "getRuntime", "()Ldalvik/system/VMRuntime;");
    gVMRuntime = env->NewGlobalRef(env->CallStaticObjectMethod(gVMRuntime_class, m));
    gVMRuntime_newNonMovableArray = env->GetMethodID(gVMRuntime_class, "newNonMovableArray",
                                                     "(Ljava/lang/Class;I)Ljava/lang/Object;");
    gVMRuntime_addressOf = env->GetMethodID(gVMRuntime_class, "addressOf", "(Ljava/lang/Object;)J");

    return 0;
}
