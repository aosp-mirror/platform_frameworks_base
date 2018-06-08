#define LOG_TAG "GraphicsJNI"

#include <unistd.h>
#include <sys/mman.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "GraphicsJNI.h"
#include "core_jni_helpers.h"

#include "SkCanvas.h"
#include "SkMath.h"
#include "SkRegion.h"
#include <android_runtime/AndroidRuntime.h>
#include <cutils/ashmem.h>
#include <hwui/Canvas.h>

#include <Caches.h>
#include <TextureCache.h>

using namespace android;

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
    ALOG_ASSERT(env);
    if (array) {
        fLen = env->GetArrayLength(array);
        if (fLen < minLength) {
            LOG_ALWAYS_FATAL("bad length");
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
    ALOG_ASSERT(env);
    if (array) {
        fLen = env->GetArrayLength(array);
        if (fLen < minLength) {
            LOG_ALWAYS_FATAL("bad length");
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
    ALOG_ASSERT(env);
    if (array) {
        fLen = env->GetArrayLength(array);
        if (fLen < minLength) {
            LOG_ALWAYS_FATAL("bad length");
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
    ALOG_ASSERT(env);
    if (array) {
        fLen = env->GetArrayLength(array);
        if (fLen < minLength) {
            LOG_ALWAYS_FATAL("bad length");
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

static jfieldID gTransferParams_aFieldID;
static jfieldID gTransferParams_bFieldID;
static jfieldID gTransferParams_cFieldID;
static jfieldID gTransferParams_dFieldID;
static jfieldID gTransferParams_eFieldID;
static jfieldID gTransferParams_fFieldID;
static jfieldID gTransferParams_gFieldID;

static jclass gColorSpace_class;
static jfieldID gColorSpace_IlluminantD50FieldID;
static jmethodID gColorSpace_adaptMethodID;
static jmethodID gColorSpace_getMethodID;
static jmethodID gColorSpace_matchMethodID;

static jclass gColorSpaceRGB_class;
static jmethodID gColorSpaceRGB_getTransferParametersMethodID;
static jmethodID gColorSpaceRGB_getTransformMethodID;
static jmethodID gColorSpaceRGB_constructorMethodID;

static jclass gColorSpace_Named_class;
static jfieldID gColorSpace_Named_sRGBFieldID;
static jfieldID gColorSpace_Named_LinearExtendedSRGBFieldID;

static jclass gTransferParameters_class;
static jmethodID gTransferParameters_constructorMethodID;

///////////////////////////////////////////////////////////////////////////////

void GraphicsJNI::get_jrect(JNIEnv* env, jobject obj, int* L, int* T, int* R, int* B)
{
    ALOG_ASSERT(env->IsInstanceOf(obj, gRect_class));

    *L = env->GetIntField(obj, gRect_leftFieldID);
    *T = env->GetIntField(obj, gRect_topFieldID);
    *R = env->GetIntField(obj, gRect_rightFieldID);
    *B = env->GetIntField(obj, gRect_bottomFieldID);
}

void GraphicsJNI::set_jrect(JNIEnv* env, jobject obj, int L, int T, int R, int B)
{
    ALOG_ASSERT(env->IsInstanceOf(obj, gRect_class));

    env->SetIntField(obj, gRect_leftFieldID, L);
    env->SetIntField(obj, gRect_topFieldID, T);
    env->SetIntField(obj, gRect_rightFieldID, R);
    env->SetIntField(obj, gRect_bottomFieldID, B);
}

SkIRect* GraphicsJNI::jrect_to_irect(JNIEnv* env, jobject obj, SkIRect* ir)
{
    ALOG_ASSERT(env->IsInstanceOf(obj, gRect_class));

    ir->set(env->GetIntField(obj, gRect_leftFieldID),
            env->GetIntField(obj, gRect_topFieldID),
            env->GetIntField(obj, gRect_rightFieldID),
            env->GetIntField(obj, gRect_bottomFieldID));
    return ir;
}

void GraphicsJNI::irect_to_jrect(const SkIRect& ir, JNIEnv* env, jobject obj)
{
    ALOG_ASSERT(env->IsInstanceOf(obj, gRect_class));

    env->SetIntField(obj, gRect_leftFieldID, ir.fLeft);
    env->SetIntField(obj, gRect_topFieldID, ir.fTop);
    env->SetIntField(obj, gRect_rightFieldID, ir.fRight);
    env->SetIntField(obj, gRect_bottomFieldID, ir.fBottom);
}

SkRect* GraphicsJNI::jrectf_to_rect(JNIEnv* env, jobject obj, SkRect* r)
{
    ALOG_ASSERT(env->IsInstanceOf(obj, gRectF_class));

    r->set(env->GetFloatField(obj, gRectF_leftFieldID),
           env->GetFloatField(obj, gRectF_topFieldID),
           env->GetFloatField(obj, gRectF_rightFieldID),
           env->GetFloatField(obj, gRectF_bottomFieldID));
    return r;
}

SkRect* GraphicsJNI::jrect_to_rect(JNIEnv* env, jobject obj, SkRect* r)
{
    ALOG_ASSERT(env->IsInstanceOf(obj, gRect_class));

    r->set(SkIntToScalar(env->GetIntField(obj, gRect_leftFieldID)),
           SkIntToScalar(env->GetIntField(obj, gRect_topFieldID)),
           SkIntToScalar(env->GetIntField(obj, gRect_rightFieldID)),
           SkIntToScalar(env->GetIntField(obj, gRect_bottomFieldID)));
    return r;
}

void GraphicsJNI::rect_to_jrectf(const SkRect& r, JNIEnv* env, jobject obj)
{
    ALOG_ASSERT(env->IsInstanceOf(obj, gRectF_class));

    env->SetFloatField(obj, gRectF_leftFieldID, SkScalarToFloat(r.fLeft));
    env->SetFloatField(obj, gRectF_topFieldID, SkScalarToFloat(r.fTop));
    env->SetFloatField(obj, gRectF_rightFieldID, SkScalarToFloat(r.fRight));
    env->SetFloatField(obj, gRectF_bottomFieldID, SkScalarToFloat(r.fBottom));
}

SkIPoint* GraphicsJNI::jpoint_to_ipoint(JNIEnv* env, jobject obj, SkIPoint* point)
{
    ALOG_ASSERT(env->IsInstanceOf(obj, gPoint_class));

    point->set(env->GetIntField(obj, gPoint_xFieldID),
               env->GetIntField(obj, gPoint_yFieldID));
    return point;
}

void GraphicsJNI::ipoint_to_jpoint(const SkIPoint& ir, JNIEnv* env, jobject obj)
{
    ALOG_ASSERT(env->IsInstanceOf(obj, gPoint_class));

    env->SetIntField(obj, gPoint_xFieldID, ir.fX);
    env->SetIntField(obj, gPoint_yFieldID, ir.fY);
}

SkPoint* GraphicsJNI::jpointf_to_point(JNIEnv* env, jobject obj, SkPoint* point)
{
    ALOG_ASSERT(env->IsInstanceOf(obj, gPointF_class));

    point->set(env->GetIntField(obj, gPointF_xFieldID),
               env->GetIntField(obj, gPointF_yFieldID));
    return point;
}

void GraphicsJNI::point_to_jpointf(const SkPoint& r, JNIEnv* env, jobject obj)
{
    ALOG_ASSERT(env->IsInstanceOf(obj, gPointF_class));

    env->SetFloatField(obj, gPointF_xFieldID, SkScalarToFloat(r.fX));
    env->SetFloatField(obj, gPointF_yFieldID, SkScalarToFloat(r.fY));
}

// See enum values in GraphicsJNI.h
jint GraphicsJNI::colorTypeToLegacyBitmapConfig(SkColorType colorType) {
    switch (colorType) {
        case kRGBA_F16_SkColorType:
            return kRGBA_16F_LegacyBitmapConfig;
        case kN32_SkColorType:
            return kARGB_8888_LegacyBitmapConfig;
        case kARGB_4444_SkColorType:
            return kARGB_4444_LegacyBitmapConfig;
        case kRGB_565_SkColorType:
            return kRGB_565_LegacyBitmapConfig;
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
        kUnknown_SkColorType, // Previously kIndex_8_SkColorType,
        kRGB_565_SkColorType,
        kARGB_4444_SkColorType,
        kN32_SkColorType,
        kRGBA_F16_SkColorType,
        kN32_SkColorType
    };

    if (legacyConfig < 0 || legacyConfig > kLastEnum_LegacyBitmapConfig) {
        legacyConfig = kNo_LegacyBitmapConfig;
    }
    return static_cast<SkColorType>(gConfig2ColorType[legacyConfig]);
}

void GraphicsJNI::getSkBitmap(JNIEnv* env, jobject bitmap, SkBitmap* outBitmap) {
    bitmap::toBitmap(env, bitmap).getSkBitmap(outBitmap);
}

SkPixelRef* GraphicsJNI::refSkPixelRef(JNIEnv* env, jobject jbitmap) {
    android::Bitmap& bitmap = android::bitmap::toBitmap(env, jbitmap);
    bitmap.ref();
    return &bitmap;
}
SkColorType GraphicsJNI::getNativeBitmapColorType(JNIEnv* env, jobject jconfig) {
    ALOG_ASSERT(env);
    if (NULL == jconfig) {
        return kUnknown_SkColorType;
    }
    ALOG_ASSERT(env->IsInstanceOf(jconfig, gBitmapConfig_class));
    int c = env->GetIntField(jconfig, gBitmapConfig_nativeInstanceID);
    return legacyBitmapConfigToColorType(c);
}

bool GraphicsJNI::isHardwareConfig(JNIEnv* env, jobject jconfig) {
    ALOG_ASSERT(env);
    if (NULL == jconfig) {
        return false;
    }
    int c = env->GetIntField(jconfig, gBitmapConfig_nativeInstanceID);
    return c == kHardware_LegacyBitmapConfig;
}

jint GraphicsJNI::hardwareLegacyBitmapConfig() {
    return kHardware_LegacyBitmapConfig;
}

android::Canvas* GraphicsJNI::getNativeCanvas(JNIEnv* env, jobject canvas) {
    ALOG_ASSERT(env);
    ALOG_ASSERT(canvas);
    ALOG_ASSERT(env->IsInstanceOf(canvas, gCanvas_class));
    jlong canvasHandle = env->GetLongField(canvas, gCanvas_nativeInstanceID);
    if (!canvasHandle) {
        return NULL;
    }
    return reinterpret_cast<android::Canvas*>(canvasHandle);
}

SkRegion* GraphicsJNI::getNativeRegion(JNIEnv* env, jobject region)
{
    ALOG_ASSERT(env);
    ALOG_ASSERT(region);
    ALOG_ASSERT(env->IsInstanceOf(region, gRegion_class));
    jlong regionHandle = env->GetLongField(region, gRegion_nativeInstanceID);
    SkRegion* r = reinterpret_cast<SkRegion*>(regionHandle);
    ALOG_ASSERT(r);
    return r;
}

///////////////////////////////////////////////////////////////////////////////////////////

jobject GraphicsJNI::createBitmapRegionDecoder(JNIEnv* env, SkBitmapRegionDecoder* bitmap)
{
    ALOG_ASSERT(bitmap != NULL);

    jobject obj = env->NewObject(gBitmapRegionDecoder_class,
            gBitmapRegionDecoder_constructorMethodID,
            reinterpret_cast<jlong>(bitmap));
    hasException(env); // For the side effect of logging.
    return obj;
}

jobject GraphicsJNI::createRegion(JNIEnv* env, SkRegion* region)
{
    ALOG_ASSERT(region != NULL);
    jobject obj = env->NewObject(gRegion_class, gRegion_constructorMethodID,
                                 reinterpret_cast<jlong>(region), 0);
    hasException(env); // For the side effect of logging.
    return obj;
}

///////////////////////////////////////////////////////////////////////////////

android::Bitmap* GraphicsJNI::mapAshmemBitmap(JNIEnv* env, SkBitmap* bitmap,
        int fd, void* addr, size_t size, bool readOnly) {
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

    auto wrapper = new android::Bitmap(addr, fd, size, info, rowBytes);
    wrapper->getSkBitmap(bitmap);
    if (readOnly) {
        bitmap->pixelRef()->setImmutable();
    }
    return wrapper;
}

sk_sp<SkColorSpace> GraphicsJNI::defaultColorSpace() {
#ifdef ANDROID_ENABLE_LINEAR_BLENDING
    return SkColorSpace::MakeSRGB();
#else
    return nullptr;
#endif
}

sk_sp<SkColorSpace> GraphicsJNI::linearColorSpace() {
    return SkColorSpace::MakeSRGBLinear();
}

sk_sp<SkColorSpace> GraphicsJNI::colorSpaceForType(SkColorType type) {
    switch (type) {
        case kRGBA_F16_SkColorType:
            return linearColorSpace();
        default:
            return defaultColorSpace();
    }
}

bool GraphicsJNI::isColorSpaceSRGB(SkColorSpace* colorSpace) {
    return colorSpace == nullptr || colorSpace->isSRGB();
}

SkColorSpaceTransferFn GraphicsJNI::getNativeTransferParameters(JNIEnv* env, jobject transferParams) {
    SkColorSpaceTransferFn p;
    p.fA = (float) env->GetDoubleField(transferParams, gTransferParams_aFieldID);
    p.fB = (float) env->GetDoubleField(transferParams, gTransferParams_bFieldID);
    p.fC = (float) env->GetDoubleField(transferParams, gTransferParams_cFieldID);
    p.fD = (float) env->GetDoubleField(transferParams, gTransferParams_dFieldID);
    p.fE = (float) env->GetDoubleField(transferParams, gTransferParams_eFieldID);
    p.fF = (float) env->GetDoubleField(transferParams, gTransferParams_fFieldID);
    p.fG = (float) env->GetDoubleField(transferParams, gTransferParams_gFieldID);
    return p;
}

SkMatrix44 GraphicsJNI::getNativeXYZMatrix(JNIEnv* env, jfloatArray xyzD50) {
    SkMatrix44 xyzMatrix(SkMatrix44::kIdentity_Constructor);
    jfloat* array = env->GetFloatArrayElements(xyzD50, NULL);
    xyzMatrix.setFloat(0, 0, array[0]);
    xyzMatrix.setFloat(1, 0, array[1]);
    xyzMatrix.setFloat(2, 0, array[2]);
    xyzMatrix.setFloat(0, 1, array[3]);
    xyzMatrix.setFloat(1, 1, array[4]);
    xyzMatrix.setFloat(2, 1, array[5]);
    xyzMatrix.setFloat(0, 2, array[6]);
    xyzMatrix.setFloat(1, 2, array[7]);
    xyzMatrix.setFloat(2, 2, array[8]);
    env->ReleaseFloatArrayElements(xyzD50, array, 0);
    return xyzMatrix;
}

sk_sp<SkColorSpace> GraphicsJNI::getNativeColorSpace(JNIEnv* env, jobject colorSpace) {
    if (colorSpace == nullptr) return nullptr;
    if (!env->IsInstanceOf(colorSpace, gColorSpaceRGB_class)) {
        doThrowIAE(env, "The color space must be an RGB color space");
    }

    jobject transferParams = env->CallObjectMethod(colorSpace,
            gColorSpaceRGB_getTransferParametersMethodID);
    if (transferParams == nullptr) {
        doThrowIAE(env, "The color space must use an ICC parametric transfer function");
    }

    jfloatArray illuminantD50 = (jfloatArray) env->GetStaticObjectField(gColorSpace_class,
            gColorSpace_IlluminantD50FieldID);
    jobject colorSpaceD50 = env->CallStaticObjectMethod(gColorSpace_class,
            gColorSpace_adaptMethodID, colorSpace, illuminantD50);

    jfloatArray xyzD50 = (jfloatArray) env->CallObjectMethod(colorSpaceD50,
            gColorSpaceRGB_getTransformMethodID);

    SkMatrix44 xyzMatrix = getNativeXYZMatrix(env, xyzD50);
    SkColorSpaceTransferFn transferFunction = getNativeTransferParameters(env, transferParams);

    return SkColorSpace::MakeRGB(transferFunction, xyzMatrix);
}


jobject GraphicsJNI::getColorSpace(JNIEnv* env, sk_sp<SkColorSpace>& decodeColorSpace,
        SkColorType decodeColorType) {
    jobject colorSpace = nullptr;

    // No need to match, we know what the output color space will be
    if (decodeColorType == kRGBA_F16_SkColorType) {
        jobject linearExtendedSRGB = env->GetStaticObjectField(
                gColorSpace_Named_class, gColorSpace_Named_LinearExtendedSRGBFieldID);
        colorSpace = env->CallStaticObjectMethod(gColorSpace_class,
                gColorSpace_getMethodID, linearExtendedSRGB);
    } else {
        // Same here, no need to match
        if (decodeColorSpace->isSRGB()) {
            jobject sRGB = env->GetStaticObjectField(
                    gColorSpace_Named_class, gColorSpace_Named_sRGBFieldID);
            colorSpace = env->CallStaticObjectMethod(gColorSpace_class,
                    gColorSpace_getMethodID, sRGB);
        } else if (decodeColorSpace.get() != nullptr) {
            // Try to match against known RGB color spaces using the CIE XYZ D50
            // conversion matrix and numerical transfer function parameters
            SkMatrix44 xyzMatrix(SkMatrix44::kUninitialized_Constructor);
            LOG_ALWAYS_FATAL_IF(!decodeColorSpace->toXYZD50(&xyzMatrix));

            SkColorSpaceTransferFn transferParams;
            // We can only handle numerical transfer functions at the moment
            LOG_ALWAYS_FATAL_IF(!decodeColorSpace->isNumericalTransferFn(&transferParams));

            jobject params = env->NewObject(gTransferParameters_class,
                    gTransferParameters_constructorMethodID,
                    transferParams.fA, transferParams.fB, transferParams.fC,
                    transferParams.fD, transferParams.fE, transferParams.fF,
                    transferParams.fG);

            jfloatArray xyzArray = env->NewFloatArray(9);
            jfloat xyz[9] = {
                    xyzMatrix.getFloat(0, 0),
                    xyzMatrix.getFloat(1, 0),
                    xyzMatrix.getFloat(2, 0),
                    xyzMatrix.getFloat(0, 1),
                    xyzMatrix.getFloat(1, 1),
                    xyzMatrix.getFloat(2, 1),
                    xyzMatrix.getFloat(0, 2),
                    xyzMatrix.getFloat(1, 2),
                    xyzMatrix.getFloat(2, 2)
            };
            env->SetFloatArrayRegion(xyzArray, 0, 9, xyz);

            colorSpace = env->CallStaticObjectMethod(gColorSpace_class,
                    gColorSpace_matchMethodID, xyzArray, params);

            if (colorSpace == nullptr) {
                // We couldn't find an exact match, let's create a new color space
                // instance with the 3x3 conversion matrix and transfer function
                colorSpace = env->NewObject(gColorSpaceRGB_class,
                        gColorSpaceRGB_constructorMethodID,
                        env->NewStringUTF("Unknown"), xyzArray, params);
            }

            env->DeleteLocalRef(xyzArray);
        }
    }
    return colorSpace;
}

///////////////////////////////////////////////////////////////////////////////
bool HeapAllocator::allocPixelRef(SkBitmap* bitmap) {
    mStorage = android::Bitmap::allocateHeapBitmap(bitmap);
    return !!mStorage;
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

bool RecyclingClippingPixelAllocator::allocPixelRef(SkBitmap* bitmap) {
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
    const size_t bytesNeeded = maxInfo.computeByteSize(rowBytes);
    if (bytesNeeded <= mRecycledBytes) {
        // Here we take advantage of reconfigure() to reset the rowBytes
        // of mRecycledBitmap.  It is very important that we pass in
        // mRecycledBitmap->info() for the SkImageInfo.  According to the
        // specification for BitmapRegionDecoder, we are not allowed to change
        // the SkImageInfo.
        // We can (must) preserve the color space since it doesn't affect the
        // storage needs
        mRecycledBitmap->reconfigure(
                mRecycledBitmap->info().makeColorSpace(bitmap->refColorSpace()),
                rowBytes);

        // Give the bitmap the same pixelRef as mRecycledBitmap.
        // skbug.com/4538: We also need to make sure that the rowBytes on the pixel ref
        //                 match the rowBytes on the bitmap.
        bitmap->setInfo(bitmap->info(), rowBytes);
        bitmap->setPixelRef(sk_ref_sp(mRecycledBitmap), 0, 0);

        // Make sure that the recycled bitmap has the correct alpha type.
        mRecycledBitmap->setAlphaType(bitmap->alphaType());

        bitmap->notifyPixelsChanged();
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

    return heapAllocator.allocPixelRef(bitmap);
}

void RecyclingClippingPixelAllocator::copyIfNecessary() {
    if (mNeedsCopy) {
        mRecycledBitmap->ref();
        SkPixelRef* recycledPixels = mRecycledBitmap;
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

bool AshmemPixelAllocator::allocPixelRef(SkBitmap* bitmap) {
    mStorage = android::Bitmap::allocateAshmemBitmap(bitmap);
    return !!mStorage;
}

////////////////////////////////////////////////////////////////////////////////

int register_android_graphics_Graphics(JNIEnv* env)
{
    jmethodID m;
    jclass c;

    gRect_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Rect"));
    gRect_leftFieldID = GetFieldIDOrDie(env, gRect_class, "left", "I");
    gRect_topFieldID = GetFieldIDOrDie(env, gRect_class, "top", "I");
    gRect_rightFieldID = GetFieldIDOrDie(env, gRect_class, "right", "I");
    gRect_bottomFieldID = GetFieldIDOrDie(env, gRect_class, "bottom", "I");

    gRectF_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/RectF"));
    gRectF_leftFieldID = GetFieldIDOrDie(env, gRectF_class, "left", "F");
    gRectF_topFieldID = GetFieldIDOrDie(env, gRectF_class, "top", "F");
    gRectF_rightFieldID = GetFieldIDOrDie(env, gRectF_class, "right", "F");
    gRectF_bottomFieldID = GetFieldIDOrDie(env, gRectF_class, "bottom", "F");

    gPoint_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Point"));
    gPoint_xFieldID = GetFieldIDOrDie(env, gPoint_class, "x", "I");
    gPoint_yFieldID = GetFieldIDOrDie(env, gPoint_class, "y", "I");

    gPointF_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/PointF"));
    gPointF_xFieldID = GetFieldIDOrDie(env, gPointF_class, "x", "F");
    gPointF_yFieldID = GetFieldIDOrDie(env, gPointF_class, "y", "F");

    gBitmapRegionDecoder_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/BitmapRegionDecoder"));
    gBitmapRegionDecoder_constructorMethodID = GetMethodIDOrDie(env, gBitmapRegionDecoder_class, "<init>", "(J)V");

    gBitmapConfig_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Bitmap$Config"));
    gBitmapConfig_nativeInstanceID = GetFieldIDOrDie(env, gBitmapConfig_class, "nativeInt", "I");

    gCanvas_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Canvas"));
    gCanvas_nativeInstanceID = GetFieldIDOrDie(env, gCanvas_class, "mNativeCanvasWrapper", "J");

    gPicture_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Picture"));
    gPicture_nativeInstanceID = GetFieldIDOrDie(env, gPicture_class, "mNativePicture", "J");

    gRegion_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Region"));
    gRegion_nativeInstanceID = GetFieldIDOrDie(env, gRegion_class, "mNativeRegion", "J");
    gRegion_constructorMethodID = GetMethodIDOrDie(env, gRegion_class, "<init>", "(JI)V");

    c = env->FindClass("java/lang/Byte");
    gByte_class = (jclass) env->NewGlobalRef(
        env->GetStaticObjectField(c, env->GetStaticFieldID(c, "TYPE", "Ljava/lang/Class;")));

    gVMRuntime_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "dalvik/system/VMRuntime"));
    m = env->GetStaticMethodID(gVMRuntime_class, "getRuntime", "()Ldalvik/system/VMRuntime;");
    gVMRuntime = env->NewGlobalRef(env->CallStaticObjectMethod(gVMRuntime_class, m));
    gVMRuntime_newNonMovableArray = GetMethodIDOrDie(env, gVMRuntime_class, "newNonMovableArray",
                                                     "(Ljava/lang/Class;I)Ljava/lang/Object;");
    gVMRuntime_addressOf = GetMethodIDOrDie(env, gVMRuntime_class, "addressOf", "(Ljava/lang/Object;)J");

    jclass transfer_params_class = FindClassOrDie(env, "android/graphics/ColorSpace$Rgb$TransferParameters");
    gTransferParams_aFieldID = GetFieldIDOrDie(env, transfer_params_class, "a", "D");
    gTransferParams_bFieldID = GetFieldIDOrDie(env, transfer_params_class, "b", "D");
    gTransferParams_cFieldID = GetFieldIDOrDie(env, transfer_params_class, "c", "D");
    gTransferParams_dFieldID = GetFieldIDOrDie(env, transfer_params_class, "d", "D");
    gTransferParams_eFieldID = GetFieldIDOrDie(env, transfer_params_class, "e", "D");
    gTransferParams_fFieldID = GetFieldIDOrDie(env, transfer_params_class, "f", "D");
    gTransferParams_gFieldID = GetFieldIDOrDie(env, transfer_params_class, "g", "D");

    gColorSpace_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/ColorSpace"));
    gColorSpace_IlluminantD50FieldID = GetStaticFieldIDOrDie(env,
            gColorSpace_class, "ILLUMINANT_D50", "[F");
    gColorSpace_adaptMethodID = GetStaticMethodIDOrDie(env, gColorSpace_class, "adapt",
            "(Landroid/graphics/ColorSpace;[F)Landroid/graphics/ColorSpace;");
    gColorSpace_getMethodID = GetStaticMethodIDOrDie(env, gColorSpace_class,
            "get", "(Landroid/graphics/ColorSpace$Named;)Landroid/graphics/ColorSpace;");
    gColorSpace_matchMethodID = GetStaticMethodIDOrDie(env, gColorSpace_class, "match",
            "([FLandroid/graphics/ColorSpace$Rgb$TransferParameters;)Landroid/graphics/ColorSpace;");

    gColorSpaceRGB_class = MakeGlobalRefOrDie(env,
            FindClassOrDie(env, "android/graphics/ColorSpace$Rgb"));
    gColorSpaceRGB_constructorMethodID = GetMethodIDOrDie(env, gColorSpaceRGB_class,
            "<init>", "(Ljava/lang/String;[FLandroid/graphics/ColorSpace$Rgb$TransferParameters;)V");
    gColorSpaceRGB_getTransferParametersMethodID = GetMethodIDOrDie(env, gColorSpaceRGB_class,
            "getTransferParameters", "()Landroid/graphics/ColorSpace$Rgb$TransferParameters;");
    gColorSpaceRGB_getTransformMethodID = GetMethodIDOrDie(env, gColorSpaceRGB_class,
            "getTransform", "()[F");

    gColorSpace_Named_class = MakeGlobalRefOrDie(env,
            FindClassOrDie(env, "android/graphics/ColorSpace$Named"));
    gColorSpace_Named_sRGBFieldID = GetStaticFieldIDOrDie(env,
            gColorSpace_Named_class, "SRGB", "Landroid/graphics/ColorSpace$Named;");
    gColorSpace_Named_LinearExtendedSRGBFieldID = GetStaticFieldIDOrDie(env,
            gColorSpace_Named_class, "LINEAR_EXTENDED_SRGB", "Landroid/graphics/ColorSpace$Named;");

    gTransferParameters_class = MakeGlobalRefOrDie(env, FindClassOrDie(env,
            "android/graphics/ColorSpace$Rgb$TransferParameters"));
    gTransferParameters_constructorMethodID = GetMethodIDOrDie(env, gTransferParameters_class,
            "<init>", "(DDDDDDD)V");

    return 0;
}
