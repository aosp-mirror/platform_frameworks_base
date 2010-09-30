#define LOG_TAG "GraphicsJNI"

#include "jni.h"
#include "GraphicsJNI.h"
#include "SkPicture.h"
#include "SkRegion.h"
#include <android_runtime/AndroidRuntime.h>

//#define REPORT_SIZE_TO_JVM
//#define TRACK_LOCK_COUNT

void doThrow(JNIEnv* env, const char* exc, const char* msg) {
    // don't throw a new exception if we already have one pending
    if (env->ExceptionCheck() == JNI_FALSE) {
        jclass npeClazz;
        
        npeClazz = env->FindClass(exc);
        LOG_FATAL_IF(npeClazz == NULL, "Unable to find class %s", exc);
        
        env->ThrowNew(npeClazz, msg);
    }
}

void doThrowNPE(JNIEnv* env) {
    doThrow(env, "java/lang/NullPointerException");
}

void doThrowAIOOBE(JNIEnv* env) {
    doThrow(env, "java/lang/ArrayIndexOutOfBoundsException");
}

void doThrowRE(JNIEnv* env, const char* msg) {
    doThrow(env, "java/lang/RuntimeException", msg);
}

void doThrowIAE(JNIEnv* env, const char* msg) {
    doThrow(env, "java/lang/IllegalArgumentException", msg);
}

void doThrowISE(JNIEnv* env, const char* msg) {
    doThrow(env, "java/lang/IllegalStateException", msg);
}

void doThrowOOME(JNIEnv* env, const char* msg) {
    doThrow(env, "java/lang/OutOfMemoryError", msg);
}

void doThrowIOE(JNIEnv* env, const char* msg) {
    doThrow(env, "java/io/IOException", msg);
}

bool GraphicsJNI::hasException(JNIEnv *env) {
    if (env->ExceptionCheck() != 0) {
        LOGE("*** Uncaught exception returned from Java call!\n");
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
static jmethodID gBitmap_allocBufferMethodID;

static jclass   gBitmapConfig_class;
static jfieldID gBitmapConfig_nativeInstanceID;

static jclass   gBitmapRegionDecoder_class;
static jmethodID gBitmapRegionDecoder_constructorMethodID;

static jclass   gCanvas_class;
static jfieldID gCanvas_nativeInstanceID;

static jclass   gPaint_class;
static jfieldID gPaint_nativeInstanceID;

static jclass   gPicture_class;
static jfieldID gPicture_nativeInstanceID;

static jclass   gRegion_class;
static jfieldID gRegion_nativeInstanceID;
static jmethodID gRegion_constructorMethodID;

static jobject   gVMRuntime_singleton;
static jmethodID gVMRuntime_trackExternalAllocationMethodID;
static jmethodID gVMRuntime_trackExternalFreeMethodID;

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
    
    r->set(SkFloatToScalar(env->GetFloatField(obj, gRectF_leftFieldID)),
           SkFloatToScalar(env->GetFloatField(obj, gRectF_topFieldID)),
           SkFloatToScalar(env->GetFloatField(obj, gRectF_rightFieldID)),
           SkFloatToScalar(env->GetFloatField(obj, gRectF_bottomFieldID)));
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
        
    point->set(SkFloatToScalar(env->GetIntField(obj, gPointF_xFieldID)),
               SkFloatToScalar(env->GetIntField(obj, gPointF_yFieldID)));
    return point;
}

void GraphicsJNI::point_to_jpointf(const SkPoint& r, JNIEnv* env, jobject obj)
{
    SkASSERT(env->IsInstanceOf(obj, gPointF_class));

    env->SetFloatField(obj, gPointF_xFieldID, SkScalarToFloat(r.fX));
    env->SetFloatField(obj, gPointF_yFieldID, SkScalarToFloat(r.fY));
}

SkBitmap* GraphicsJNI::getNativeBitmap(JNIEnv* env, jobject bitmap) {
    SkASSERT(env);
    SkASSERT(bitmap);
    SkASSERT(env->IsInstanceOf(bitmap, gBitmap_class));
    SkBitmap* b = (SkBitmap*)env->GetIntField(bitmap, gBitmap_nativeInstanceID);
    SkASSERT(b);
    return b;
}

SkBitmap::Config GraphicsJNI::getNativeBitmapConfig(JNIEnv* env,
                                                    jobject jconfig) {
    SkASSERT(env);
    if (NULL == jconfig) {
        return SkBitmap::kNo_Config;
    }
    SkASSERT(env->IsInstanceOf(jconfig, gBitmapConfig_class));
    int c = env->GetIntField(jconfig, gBitmapConfig_nativeInstanceID);
    if (c < 0 || c >= SkBitmap::kConfigCount) {
        c = SkBitmap::kNo_Config;
    }
    return static_cast<SkBitmap::Config>(c);
}

SkCanvas* GraphicsJNI::getNativeCanvas(JNIEnv* env, jobject canvas) {
    SkASSERT(env);
    SkASSERT(canvas);
    SkASSERT(env->IsInstanceOf(canvas, gCanvas_class));
    SkCanvas* c = (SkCanvas*)env->GetIntField(canvas, gCanvas_nativeInstanceID);
    SkASSERT(c);
    return c;
}

SkPaint* GraphicsJNI::getNativePaint(JNIEnv* env, jobject paint) {
    SkASSERT(env);
    SkASSERT(paint);
    SkASSERT(env->IsInstanceOf(paint, gPaint_class));
    SkPaint* p = (SkPaint*)env->GetIntField(paint, gPaint_nativeInstanceID);
    SkASSERT(p);
    return p;
}

SkPicture* GraphicsJNI::getNativePicture(JNIEnv* env, jobject picture)
{
    SkASSERT(env);
    SkASSERT(picture);
    SkASSERT(env->IsInstanceOf(picture, gPicture_class));
    SkPicture* p = (SkPicture*)env->GetIntField(picture, gPicture_nativeInstanceID);
    SkASSERT(p);
    return p;
}

SkRegion* GraphicsJNI::getNativeRegion(JNIEnv* env, jobject region)
{
    SkASSERT(env);
    SkASSERT(region);
    SkASSERT(env->IsInstanceOf(region, gRegion_class));
    SkRegion* r = (SkRegion*)env->GetIntField(region, gRegion_nativeInstanceID);
    SkASSERT(r);
    return r;
}

///////////////////////////////////////////////////////////////////////////////////////////

jobject GraphicsJNI::createBitmap(JNIEnv* env, SkBitmap* bitmap, bool isMutable,
                                  jbyteArray ninepatch, int density)
{
    SkASSERT(bitmap != NULL);
    SkASSERT(NULL != bitmap->pixelRef());
    
    jobject obj = env->AllocObject(gBitmap_class);
    if (obj) {
        env->CallVoidMethod(obj, gBitmap_constructorMethodID,
                            (jint)bitmap, isMutable, ninepatch, density);
        if (hasException(env)) {
            obj = NULL;
        }
    }
    return obj;
}

jobject GraphicsJNI::createBitmapRegionDecoder(JNIEnv* env, SkBitmapRegionDecoder* bitmap)
{
    SkASSERT(bitmap != NULL);

    jobject obj = env->AllocObject(gBitmapRegionDecoder_class);
    if (hasException(env)) {
        obj = NULL;
        return obj;
    }
    if (obj) {
        env->CallVoidMethod(obj, gBitmapRegionDecoder_constructorMethodID, (jint)bitmap);
        if (hasException(env)) {
            obj = NULL;
        }
    }
    return obj;
}

jobject GraphicsJNI::createRegion(JNIEnv* env, SkRegion* region)
{
    SkASSERT(region != NULL);
    jobject obj = env->AllocObject(gRegion_class);
    if (obj) {
        env->CallVoidMethod(obj, gRegion_constructorMethodID, (jint)region, 0);
        if (hasException(env)) {
            obj = NULL;
        }
    }
    return obj;
}

#include "SkPixelRef.h"

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

#ifdef TRACK_LOCK_COUNT
    static int gLockCount;
#endif

///////////////////////////////////////////////////////////////////////////////

#include "SkMallocPixelRef.h"

/*  Extend SkMallocPixelRef to inform the VM when we free up the storage
*/
class AndroidPixelRef : public SkMallocPixelRef {
public:
    /** Allocate the specified buffer for pixels. The memory is freed when the
        last owner of this pixelref is gone. Our caller has already informed
        the VM of our allocation.
    */
    AndroidPixelRef(JNIEnv* env, void* storage, size_t size,
            SkColorTable* ctable) : SkMallocPixelRef(storage, size, ctable) {
        SkASSERT(storage);
        SkASSERT(env);

        if (env->GetJavaVM(&fVM) != JNI_OK) {
            SkDebugf("------ [%p] env->GetJavaVM failed\n", env);
            sk_throw();
        }        
    }

    virtual ~AndroidPixelRef() {
        JNIEnv* env = vm2env(fVM);
//        SkDebugf("-------------- inform VM we're releasing %d bytes\n", this->getSize());
        jlong jsize = this->getSize();  // the VM wants longs for the size
        env->CallVoidMethod(gVMRuntime_singleton,
                            gVMRuntime_trackExternalFreeMethodID,
                            jsize);
        if (GraphicsJNI::hasException(env)) {
            env->ExceptionClear();
        }
    }

private:
    JavaVM* fVM;
};

bool GraphicsJNI::setJavaPixelRef(JNIEnv* env, SkBitmap* bitmap,
                                  SkColorTable* ctable, bool reportSizeToVM) {
    Sk64 size64 = bitmap->getSize64();
    if (size64.isNeg() || !size64.is32()) {
        doThrow(env, "java/lang/IllegalArgumentException",
                     "bitmap size exceeds 32bits");
        return false;
    }
    
    size_t size = size64.get32();
    jlong jsize = size;  // the VM wants longs for the size
    if (reportSizeToVM) {
        //    SkDebugf("-------------- inform VM we've allocated %d bytes\n", size);
        bool r = env->CallBooleanMethod(gVMRuntime_singleton,
                                    gVMRuntime_trackExternalAllocationMethodID,
                                    jsize);
        if (GraphicsJNI::hasException(env)) {
            return false;
        }
        if (!r) {
            LOGE("VM won't let us allocate %zd bytes\n", size);
            doThrowOOME(env, "bitmap size exceeds VM budget");
            return false;
        }
    }
    // call the version of malloc that returns null on failure
    void* addr = sk_malloc_flags(size, 0);
    if (NULL == addr) {
        if (reportSizeToVM) {
            //        SkDebugf("-------------- inform VM we're releasing %d bytes which we couldn't allocate\n", size);
            // we didn't actually allocate it, so inform the VM
            env->CallVoidMethod(gVMRuntime_singleton,
                                 gVMRuntime_trackExternalFreeMethodID,
                                 jsize);
            if (!GraphicsJNI::hasException(env)) {
                doThrowOOME(env, "bitmap size too large for malloc");
            }
        }
        return false;
    }
    
    SkPixelRef* pr = reportSizeToVM ?
                        new AndroidPixelRef(env, addr, size, ctable) :
                        new SkMallocPixelRef(addr, size, ctable);
    bitmap->setPixelRef(pr)->unref();
    // since we're already allocated, we lockPixels right away
    // HeapAllocator behaves this way too
    bitmap->lockPixels();
    return true;
}

///////////////////////////////////////////////////////////////////////////////

JavaPixelAllocator::JavaPixelAllocator(JNIEnv* env, bool reportSizeToVM)
    : fReportSizeToVM(reportSizeToVM) {
    if (env->GetJavaVM(&fVM) != JNI_OK) {
        SkDebugf("------ [%p] env->GetJavaVM failed\n", env);
        sk_throw();
    }
}
    
bool JavaPixelAllocator::allocPixelRef(SkBitmap* bitmap, SkColorTable* ctable) {
    JNIEnv* env = vm2env(fVM);
    return GraphicsJNI::setJavaPixelRef(env, bitmap, ctable, fReportSizeToVM);
}

////////////////////////////////////////////////////////////////////////////////

JavaMemoryUsageReporter::JavaMemoryUsageReporter(JNIEnv* env)
    : fTotalSize(0) {
    if (env->GetJavaVM(&fVM) != JNI_OK) {
        SkDebugf("------ [%p] env->GetJavaVM failed\n", env);
        sk_throw();
    }
}

JavaMemoryUsageReporter::~JavaMemoryUsageReporter() {
    JNIEnv* env = vm2env(fVM);
    jlong jtotalSize = fTotalSize;
    env->CallVoidMethod(gVMRuntime_singleton,
            gVMRuntime_trackExternalFreeMethodID,
            jtotalSize);
}

bool JavaMemoryUsageReporter::reportMemory(size_t memorySize) {
    jlong jsize = memorySize;  // the VM wants longs for the size
    JNIEnv* env = vm2env(fVM);
    bool r = env->CallBooleanMethod(gVMRuntime_singleton,
            gVMRuntime_trackExternalAllocationMethodID,
            jsize);
    if (GraphicsJNI::hasException(env)) {
        return false;
    }
    if (!r) {
        LOGE("VM won't let us allocate %zd bytes\n", memorySize);
        doThrowOOME(env, "bitmap size exceeds VM budget");
        return false;
    }
    fTotalSize += memorySize;
    return true;
}

////////////////////////////////////////////////////////////////////////////////

static jclass make_globalref(JNIEnv* env, const char classname[])
{
    jclass c = env->FindClass(classname);
    SkASSERT(c);
    return (jclass)env->NewGlobalRef(c);
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
    gBitmap_nativeInstanceID = getFieldIDCheck(env, gBitmap_class, "mNativeBitmap", "I");    
    gBitmap_constructorMethodID = env->GetMethodID(gBitmap_class, "<init>",
                                            "(IZ[BI)V");

    gBitmapRegionDecoder_class = make_globalref(env, "android/graphics/BitmapRegionDecoder");
    gBitmapRegionDecoder_constructorMethodID = env->GetMethodID(gBitmapRegionDecoder_class, "<init>", "(I)V");

    gBitmapConfig_class = make_globalref(env, "android/graphics/Bitmap$Config");
    gBitmapConfig_nativeInstanceID = getFieldIDCheck(env, gBitmapConfig_class,
                                                     "nativeInt", "I");    

    gCanvas_class = make_globalref(env, "android/graphics/Canvas");
    gCanvas_nativeInstanceID = getFieldIDCheck(env, gCanvas_class, "mNativeCanvas", "I");

    gPaint_class = make_globalref(env, "android/graphics/Paint");
    gPaint_nativeInstanceID = getFieldIDCheck(env, gPaint_class, "mNativePaint", "I");
    
    gPicture_class = make_globalref(env, "android/graphics/Picture");
    gPicture_nativeInstanceID = getFieldIDCheck(env, gPicture_class, "mNativePicture", "I");

    gRegion_class = make_globalref(env, "android/graphics/Region");
    gRegion_nativeInstanceID = getFieldIDCheck(env, gRegion_class, "mNativeRegion", "I");
    gRegion_constructorMethodID = env->GetMethodID(gRegion_class, "<init>",
        "(II)V");
    
    // Get the VMRuntime class.
    c = env->FindClass("dalvik/system/VMRuntime");
    SkASSERT(c);
    // Look up VMRuntime.getRuntime().
    m = env->GetStaticMethodID(c, "getRuntime", "()Ldalvik/system/VMRuntime;");
    SkASSERT(m);
    // Call VMRuntime.getRuntime() and hold onto its result.
    gVMRuntime_singleton = env->CallStaticObjectMethod(c, m);
    SkASSERT(gVMRuntime_singleton);
    gVMRuntime_singleton = (jobject)env->NewGlobalRef(gVMRuntime_singleton);
    // Look up the VMRuntime methods we'll be using.
    gVMRuntime_trackExternalAllocationMethodID =
                        env->GetMethodID(c, "trackExternalAllocation", "(J)Z");
    gVMRuntime_trackExternalFreeMethodID =
                            env->GetMethodID(c, "trackExternalFree", "(J)V");

    return 0;
}
