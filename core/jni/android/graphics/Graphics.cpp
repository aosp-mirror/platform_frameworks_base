#define LOG_TAG "GraphicsJNI"

#include "jni.h"
#include "JNIHelp.h"
#include "GraphicsJNI.h"

#include "Canvas.h"
#include "SkCanvas.h"
#include "SkDevice.h"
#include "SkMath.h"
#include "SkPicture.h"
#include "SkRegion.h"
#include <android_runtime/AndroidRuntime.h>

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
static jfieldID gBitmap_nativeInstanceID;
static jmethodID gBitmap_constructorMethodID;
static jmethodID gBitmap_reinitMethodID;
static jmethodID gBitmap_getAllocationByteCountMethodID;

static jclass   gBitmapConfig_class;
static jfieldID gBitmapConfig_nativeInstanceID;

static jclass   gBitmapRegionDecoder_class;
static jmethodID gBitmapRegionDecoder_constructorMethodID;

static jclass   gCanvas_class;
static jfieldID gCanvas_nativeInstanceID;

static jclass   gPaint_class;
static jfieldID gPaint_nativeInstanceID;
static jfieldID gPaint_nativeTypefaceID;

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

SkBitmap* GraphicsJNI::getNativeBitmap(JNIEnv* env, jobject bitmap) {
    SkASSERT(env);
    SkASSERT(bitmap);
    SkASSERT(env->IsInstanceOf(bitmap, gBitmap_class));
    jlong bitmapHandle = env->GetLongField(bitmap, gBitmap_nativeInstanceID);
    SkBitmap* b = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkASSERT(b);
    return b;
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

SkCanvas* GraphicsJNI::getNativeCanvas(JNIEnv* env, jobject canvas) {
    SkASSERT(env);
    SkASSERT(canvas);
    SkASSERT(env->IsInstanceOf(canvas, gCanvas_class));
    jlong canvasHandle = env->GetLongField(canvas, gCanvas_nativeInstanceID);
    if (!canvasHandle) {
        return NULL;
    }
    SkCanvas* c = reinterpret_cast<android::Canvas*>(canvasHandle)->getSkCanvas();
    SkASSERT(c);
    return c;
}

android::Paint* GraphicsJNI::getNativePaint(JNIEnv* env, jobject paint) {
    SkASSERT(env);
    SkASSERT(paint);
    SkASSERT(env->IsInstanceOf(paint, gPaint_class));
    jlong paintHandle = env->GetLongField(paint, gPaint_nativeInstanceID);
    android::Paint* p = reinterpret_cast<android::Paint*>(paintHandle);
    SkASSERT(p);
    return p;
}

android::TypefaceImpl* GraphicsJNI::getNativeTypeface(JNIEnv* env, jobject paint) {
    SkASSERT(env);
    SkASSERT(paint);
    SkASSERT(env->IsInstanceOf(paint, gPaint_class));
    jlong typefaceHandle = env->GetLongField(paint, gPaint_nativeTypefaceID);
    android::TypefaceImpl* p = reinterpret_cast<android::TypefaceImpl*>(typefaceHandle);
    return p;
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
static void assert_premultiplied(const SkBitmap& bitmap, bool isPremultiplied) {
    // kOpaque_SkAlphaType and kIgnore_SkAlphaType mean that isPremultiplied is
    // irrelevant. This just tests to ensure that the SkAlphaType is not
    // opposite of isPremultiplied.
    if (isPremultiplied) {
        SkASSERT(bitmap.alphaType() != kUnpremul_SkAlphaType);
    } else {
        SkASSERT(bitmap.alphaType() != kPremul_SkAlphaType);
    }
}

jobject GraphicsJNI::createBitmap(JNIEnv* env, SkBitmap* bitmap, jbyteArray buffer,
        int bitmapCreateFlags, jbyteArray ninePatchChunk, jobject ninePatchInsets, int density)
{
    SkASSERT(bitmap);
    SkASSERT(bitmap->pixelRef());
    SkASSERT(!env->ExceptionCheck());
    bool isMutable = bitmapCreateFlags & kBitmapCreateFlag_Mutable;
    bool isPremultiplied = bitmapCreateFlags & kBitmapCreateFlag_Premultiplied;

    // The caller needs to have already set the alpha type properly, so the
    // native SkBitmap stays in sync with the Java Bitmap.
    assert_premultiplied(*bitmap, isPremultiplied);

    jobject obj = env->NewObject(gBitmap_class, gBitmap_constructorMethodID,
            reinterpret_cast<jlong>(bitmap), buffer,
            bitmap->width(), bitmap->height(), density, isMutable, isPremultiplied,
            ninePatchChunk, ninePatchInsets);
    hasException(env); // For the side effect of logging.
    return obj;
}

void GraphicsJNI::reinitBitmap(JNIEnv* env, jobject javaBitmap, SkBitmap* bitmap,
        bool isPremultiplied)
{
    // The caller needs to have already set the alpha type properly, so the
    // native SkBitmap stays in sync with the Java Bitmap.
    assert_premultiplied(*bitmap, isPremultiplied);

    env->CallVoidMethod(javaBitmap, gBitmap_reinitMethodID,
            bitmap->width(), bitmap->height(), isPremultiplied);
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

AndroidPixelRef::AndroidPixelRef(JNIEnv* env, const SkImageInfo& info, void* storage,
        size_t rowBytes, jbyteArray storageObj, SkColorTable* ctable) :
        SkMallocPixelRef(info, storage, rowBytes, ctable, (storageObj == NULL)),
        fWrappedPixelRef(NULL) {
    SkASSERT(storage);
    SkASSERT(storageObj);
    SkASSERT(env);

    if (env->GetJavaVM(&fVM) != JNI_OK) {
        SkDebugf("------ [%p] env->GetJavaVM failed\n", env);
        sk_throw();
    }

    fStorageObj = (jbyteArray) env->NewGlobalRef(storageObj);
}

AndroidPixelRef::AndroidPixelRef(AndroidPixelRef& wrappedPixelRef, const SkImageInfo& info,
        size_t rowBytes, SkColorTable* ctable) :
        SkMallocPixelRef(info, wrappedPixelRef.getAddr(), rowBytes, ctable, false),
        fWrappedPixelRef(wrappedPixelRef.fWrappedPixelRef ?
                wrappedPixelRef.fWrappedPixelRef : &wrappedPixelRef)
{
    SkASSERT(fWrappedPixelRef);
    SkSafeRef(fWrappedPixelRef);

    // don't need to initialize this, as all the relevant logic delegates to the wrapped ref
    fStorageObj = NULL;
}

AndroidPixelRef::~AndroidPixelRef() {
    if (fWrappedPixelRef) {
        SkSafeUnref(fWrappedPixelRef);
    } else {
        SkASSERT(fStorageObj);
        JNIEnv* env = vm2env(fVM);
        env->DeleteGlobalRef(fStorageObj);
    }
}

///////////////////////////////////////////////////////////////////////////////

jbyteArray GraphicsJNI::allocateJavaPixelRef(JNIEnv* env, SkBitmap* bitmap,
                                             SkColorTable* ctable) {
    const SkImageInfo& info = bitmap->info();
    if (info.fColorType == kUnknown_SkColorType) {
        doThrowIAE(env, "unknown bitmap configuration");
        return NULL;
    }

    const size_t size = bitmap->getSize();
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
    SkPixelRef* pr = new AndroidPixelRef(env, info, (void*) addr,
            bitmap->rowBytes(), arrayObj, ctable);
    bitmap->setPixelRef(pr)->unref();
    // since we're already allocated, we lockPixels right away
    // HeapAllocator behaves this way too
    bitmap->lockPixels();

    return arrayObj;
}

///////////////////////////////////////////////////////////////////////////////

JavaPixelAllocator::JavaPixelAllocator(JNIEnv* env)
    : fStorageObj(NULL),
      fAllocCount(0) {
    if (env->GetJavaVM(&fVM) != JNI_OK) {
        SkDebugf("------ [%p] env->GetJavaVM failed\n", env);
        sk_throw();
    }
}

bool JavaPixelAllocator::allocPixelRef(SkBitmap* bitmap, SkColorTable* ctable) {
    JNIEnv* env = vm2env(fVM);

    fStorageObj = GraphicsJNI::allocateJavaPixelRef(env, bitmap, ctable);
    fAllocCount += 1;
    return fStorageObj != NULL;
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
    gBitmap_nativeInstanceID = getFieldIDCheck(env, gBitmap_class, "mNativeBitmap", "J");
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

    gPaint_class = make_globalref(env, "android/graphics/Paint");
    gPaint_nativeInstanceID = getFieldIDCheck(env, gPaint_class, "mNativePaint", "J");
    gPaint_nativeTypefaceID = getFieldIDCheck(env, gPaint_class, "mNativeTypeface", "J");

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
