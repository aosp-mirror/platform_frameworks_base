#define LOG_TAG "Bitmap"
#include "Bitmap.h"

#include "GraphicBuffer.h"
#include "SkBitmap.h"
#include "SkPixelRef.h"
#include "SkImageEncoder.h"
#include "SkImageInfo.h"
#include "SkColor.h"
#include "SkColorPriv.h"
#include "SkColorSpace.h"
#include "SkColorSpaceXform.h"
#include "SkHalf.h"
#include "SkMatrix44.h"
#include "SkPM4f.h"
#include "SkPM4fPriv.h"
#include "GraphicsJNI.h"
#include "SkDither.h"
#include "SkUnPreMultiply.h"
#include "SkStream.h"

#include <binder/Parcel.h>
#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "android_nio_utils.h"
#include "CreateJavaOutputStreamAdaptor.h"
#include <hwui/Paint.h>
#include <hwui/Bitmap.h>
#include <renderthread/RenderProxy.h>

#include "core_jni_helpers.h"

#include <jni.h>
#include <string.h>
#include <memory>
#include <string>

#define DEBUG_PARCEL 0
#define ASHMEM_BITMAP_MIN_SIZE (128 * (1 << 10))

static jclass   gBitmap_class;
static jfieldID gBitmap_nativePtr;
static jmethodID gBitmap_constructorMethodID;
static jmethodID gBitmap_reinitMethodID;
static jmethodID gBitmap_getAllocationByteCountMethodID;

namespace android {

class BitmapWrapper {
public:
    BitmapWrapper(Bitmap* bitmap)
        : mBitmap(bitmap) { }

    void freePixels() {
        mInfo = mBitmap->info();
        mHasHardwareMipMap = mBitmap->hasHardwareMipMap();
        mAllocationSize = mBitmap->getAllocationByteCount();
        mRowBytes = mBitmap->rowBytes();
        mGenerationId = mBitmap->getGenerationID();
        mIsHardware = mBitmap->isHardware();
        mBitmap.reset();
    }

    bool valid() {
        return mBitmap;
    }

    Bitmap& bitmap() {
        assertValid();
        return *mBitmap;
    }

    void assertValid() {
        LOG_ALWAYS_FATAL_IF(!valid(), "Error, cannot access an invalid/free'd bitmap here!");
    }

    void getSkBitmap(SkBitmap* outBitmap) {
        assertValid();
        mBitmap->getSkBitmap(outBitmap);
    }

    bool hasHardwareMipMap() {
        if (mBitmap) {
            return mBitmap->hasHardwareMipMap();
        }
        return mHasHardwareMipMap;
    }

    void setHasHardwareMipMap(bool hasMipMap) {
        assertValid();
        mBitmap->setHasHardwareMipMap(hasMipMap);
    }

    void setAlphaType(SkAlphaType alphaType) {
        assertValid();
        mBitmap->setAlphaType(alphaType);
    }

    const SkImageInfo& info() {
        if (mBitmap) {
            return mBitmap->info();
        }
        return mInfo;
    }

    size_t getAllocationByteCount() const {
        if (mBitmap) {
            return mBitmap->getAllocationByteCount();
        }
        return mAllocationSize;
    }

    size_t rowBytes() const {
        if (mBitmap) {
            return mBitmap->rowBytes();
        }
        return mRowBytes;
    }

    uint32_t getGenerationID() const {
        if (mBitmap) {
            return mBitmap->getGenerationID();
        }
        return mGenerationId;
    }

    bool isHardware() {
        if (mBitmap) {
            return mBitmap->isHardware();
        }
        return mIsHardware;
    }

    ~BitmapWrapper() { }

private:
    sk_sp<Bitmap> mBitmap;
    SkImageInfo mInfo;
    bool mHasHardwareMipMap;
    size_t mAllocationSize;
    size_t mRowBytes;
    uint32_t mGenerationId;
    bool mIsHardware;
};

// Convenience class that does not take a global ref on the pixels, relying
// on the caller already having a local JNI ref
class LocalScopedBitmap {
public:
    explicit LocalScopedBitmap(jlong bitmapHandle)
            : mBitmapWrapper(reinterpret_cast<BitmapWrapper*>(bitmapHandle)) {}

    BitmapWrapper* operator->() {
        return mBitmapWrapper;
    }

    void* pixels() {
        return mBitmapWrapper->bitmap().pixels();
    }

    bool valid() {
        return mBitmapWrapper && mBitmapWrapper->valid();
    }

private:
    BitmapWrapper* mBitmapWrapper;
};

namespace bitmap {

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

void reinitBitmap(JNIEnv* env, jobject javaBitmap, const SkImageInfo& info,
        bool isPremultiplied)
{
    // The caller needs to have already set the alpha type properly, so the
    // native SkBitmap stays in sync with the Java Bitmap.
    assert_premultiplied(info, isPremultiplied);

    env->CallVoidMethod(javaBitmap, gBitmap_reinitMethodID,
            info.width(), info.height(), isPremultiplied);
}

int getBitmapAllocationByteCount(JNIEnv* env, jobject javaBitmap)
{
    return env->CallIntMethod(javaBitmap, gBitmap_getAllocationByteCountMethodID);
}

jobject createBitmap(JNIEnv* env, Bitmap* bitmap,
        int bitmapCreateFlags, jbyteArray ninePatchChunk, jobject ninePatchInsets,
        int density) {
    bool isMutable = bitmapCreateFlags & kBitmapCreateFlag_Mutable;
    bool isPremultiplied = bitmapCreateFlags & kBitmapCreateFlag_Premultiplied;
    // The caller needs to have already set the alpha type properly, so the
    // native SkBitmap stays in sync with the Java Bitmap.
    assert_premultiplied(bitmap->info(), isPremultiplied);
    BitmapWrapper* bitmapWrapper = new BitmapWrapper(bitmap);
    jobject obj = env->NewObject(gBitmap_class, gBitmap_constructorMethodID,
            reinterpret_cast<jlong>(bitmapWrapper), bitmap->width(), bitmap->height(), density,
            isMutable, isPremultiplied, ninePatchChunk, ninePatchInsets);

    if (env->ExceptionCheck() != 0) {
        ALOGE("*** Uncaught exception returned from Java call!\n");
        env->ExceptionDescribe();
    }
    return obj;
}

void toSkBitmap(jlong bitmapHandle, SkBitmap* outBitmap) {
    LocalScopedBitmap bitmap(bitmapHandle);
    bitmap->getSkBitmap(outBitmap);
}

Bitmap& toBitmap(JNIEnv* env, jobject bitmap) {
    SkASSERT(env);
    SkASSERT(bitmap);
    SkASSERT(env->IsInstanceOf(bitmap, gBitmap_class));
    jlong bitmapHandle = env->GetLongField(bitmap, gBitmap_nativePtr);
    LocalScopedBitmap localBitmap(bitmapHandle);
    return localBitmap->bitmap();
}

Bitmap& toBitmap(JNIEnv* env, jlong bitmapHandle) {
    SkASSERT(env);
    LocalScopedBitmap localBitmap(bitmapHandle);
    return localBitmap->bitmap();
}

void imageInfo(JNIEnv* env, jobject bitmap, AndroidBitmapInfo* info) {
    SkASSERT(info);
    SkASSERT(env);
    SkASSERT(bitmap);
    SkASSERT(env->IsInstanceOf(bitmap, gBitmap_class));
    jlong bitmapHandle = env->GetLongField(bitmap, gBitmap_nativePtr);
    LocalScopedBitmap localBitmap(bitmapHandle);

    const SkImageInfo& imageInfo = localBitmap->info();
    info->width = imageInfo.width();
    info->height = imageInfo.height();
    info->stride = localBitmap->rowBytes();
    info->flags = 0;
    switch (imageInfo.colorType()) {
        case kN32_SkColorType:
            info->format = ANDROID_BITMAP_FORMAT_RGBA_8888;
            break;
        case kRGB_565_SkColorType:
            info->format = ANDROID_BITMAP_FORMAT_RGB_565;
            break;
        case kARGB_4444_SkColorType:
            info->format = ANDROID_BITMAP_FORMAT_RGBA_4444;
            break;
        case kAlpha_8_SkColorType:
            info->format = ANDROID_BITMAP_FORMAT_A_8;
            break;
        default:
            info->format = ANDROID_BITMAP_FORMAT_NONE;
            break;
    }
}

void* lockPixels(JNIEnv* env, jobject bitmap) {
    SkASSERT(env);
    SkASSERT(bitmap);
    SkASSERT(env->IsInstanceOf(bitmap, gBitmap_class));
    jlong bitmapHandle = env->GetLongField(bitmap, gBitmap_nativePtr);

    LocalScopedBitmap localBitmap(bitmapHandle);
    if (!localBitmap->valid()) return nullptr;

    SkPixelRef& pixelRef = localBitmap->bitmap();
    if (!pixelRef.pixels()) {
        return nullptr;
    }
    pixelRef.ref();
    return pixelRef.pixels();
}

bool unlockPixels(JNIEnv* env, jobject bitmap) {
    SkASSERT(env);
    SkASSERT(bitmap);
    SkASSERT(env->IsInstanceOf(bitmap, gBitmap_class));
    jlong bitmapHandle = env->GetLongField(bitmap, gBitmap_nativePtr);

    LocalScopedBitmap localBitmap(bitmapHandle);
    if (!localBitmap->valid()) return false;

    SkPixelRef& pixelRef = localBitmap->bitmap();
    pixelRef.notifyPixelsChanged();
    pixelRef.unref();
    return true;
}

} // namespace bitmap

} // namespace android

using namespace android;
using namespace android::bitmap;

///////////////////////////////////////////////////////////////////////////////
// Conversions to/from SkColor, for get/setPixels, and the create method, which
// is basically like setPixels

typedef void (*FromColorProc)(void* dst, const SkColor src[], int width,
                              int x, int y);

static void FromColor_F16(void* dst, const SkColor src[], int width,
                          int, int) {
    uint64_t* d = (uint64_t*)dst;

    for (int i = 0; i < width; i++) {
        *d++ = SkColor4f::FromColor(*src++).premul().toF16();
    }
}

static void FromColor_F16_Raw(void* dst, const SkColor src[], int width,
                          int, int) {
    uint64_t* d = (uint64_t*)dst;

    for (int i = 0; i < width; i++) {
        const SkColor4f color = SkColor4f::FromColor(*src++);
        uint16_t* scratch = reinterpret_cast<uint16_t*>(d++);
        scratch[0] = SkFloatToHalf(color.fR);
        scratch[1] = SkFloatToHalf(color.fG);
        scratch[2] = SkFloatToHalf(color.fB);
        scratch[3] = SkFloatToHalf(color.fA);
    }
}

static void FromColor_D32(void* dst, const SkColor src[], int width,
                          int, int) {
    SkPMColor* d = (SkPMColor*)dst;

    for (int i = 0; i < width; i++) {
        *d++ = SkPreMultiplyColor(*src++);
    }
}

static void FromColor_D32_Raw(void* dst, const SkColor src[], int width,
                          int, int) {
    // Needed to thwart the unreachable code detection from clang.
    static const bool sk_color_ne_zero = SK_COLOR_MATCHES_PMCOLOR_BYTE_ORDER;

    // SkColor's ordering may be different from SkPMColor
    if (sk_color_ne_zero) {
        memcpy(dst, src, width * sizeof(SkColor));
        return;
    }

    // order isn't same, repack each pixel manually
    SkPMColor* d = (SkPMColor*)dst;
    for (int i = 0; i < width; i++) {
        SkColor c = *src++;
        *d++ = SkPackARGB32NoCheck(SkColorGetA(c), SkColorGetR(c),
                                   SkColorGetG(c), SkColorGetB(c));
    }
}

static void FromColor_D565(void* dst, const SkColor src[], int width,
                           int x, int y) {
    uint16_t* d = (uint16_t*)dst;

    DITHER_565_SCAN(y);
    for (int stop = x + width; x < stop; x++) {
        SkColor c = *src++;
        *d++ = SkDitherRGBTo565(SkColorGetR(c), SkColorGetG(c), SkColorGetB(c),
                                DITHER_VALUE(x));
    }
}

static void FromColor_D4444(void* dst, const SkColor src[], int width,
                            int x, int y) {
    SkPMColor16* d = (SkPMColor16*)dst;

    DITHER_4444_SCAN(y);
    for (int stop = x + width; x < stop; x++) {
        SkPMColor pmc = SkPreMultiplyColor(*src++);
        *d++ = SkDitherARGB32To4444(pmc, DITHER_VALUE(x));
//        *d++ = SkPixel32ToPixel4444(pmc);
    }
}

static void FromColor_D4444_Raw(void* dst, const SkColor src[], int width,
                            int x, int y) {
    SkPMColor16* d = (SkPMColor16*)dst;

    DITHER_4444_SCAN(y);
    for (int stop = x + width; x < stop; x++) {
        SkColor c = *src++;

        // SkPMColor is used because the ordering is ARGB32, even though the target actually premultiplied
        SkPMColor pmc = SkPackARGB32NoCheck(SkColorGetA(c), SkColorGetR(c),
                                            SkColorGetG(c), SkColorGetB(c));
        *d++ = SkDitherARGB32To4444(pmc, DITHER_VALUE(x));
//        *d++ = SkPixel32ToPixel4444(pmc);
    }
}

static void FromColor_DA8(void* dst, const SkColor src[], int width, int x, int y) {
    uint8_t* d = (uint8_t*)dst;

    for (int stop = x + width; x < stop; x++) {
        *d++ = SkColorGetA(*src++);
    }
}

// can return NULL
static FromColorProc ChooseFromColorProc(const SkBitmap& bitmap) {
    switch (bitmap.colorType()) {
        case kN32_SkColorType:
            return bitmap.alphaType() == kPremul_SkAlphaType ? FromColor_D32 : FromColor_D32_Raw;
        case kARGB_4444_SkColorType:
            return bitmap.alphaType() == kPremul_SkAlphaType ? FromColor_D4444 :
                    FromColor_D4444_Raw;
        case kRGB_565_SkColorType:
            return FromColor_D565;
        case kAlpha_8_SkColorType:
            return FromColor_DA8;
        case kRGBA_F16_SkColorType:
            return bitmap.alphaType() == kPremul_SkAlphaType ? FromColor_F16 : FromColor_F16_Raw;
        default:
            break;
    }
    return NULL;
}

bool GraphicsJNI::SetPixels(JNIEnv* env, jintArray srcColors, int srcOffset, int srcStride,
        int x, int y, int width, int height, const SkBitmap& dstBitmap) {
    void* dst = dstBitmap.getPixels();
    FromColorProc proc = ChooseFromColorProc(dstBitmap);

    if (NULL == dst || NULL == proc) {
        return false;
    }

    const jint* array = env->GetIntArrayElements(srcColors, NULL);
    const SkColor* src = (const SkColor*)array + srcOffset;

    // reset to to actual choice from caller
    dst = dstBitmap.getAddr(x, y);

    SkColorSpace* colorSpace = dstBitmap.colorSpace();
    if (dstBitmap.colorType() == kRGBA_F16_SkColorType ||
            GraphicsJNI::isColorSpaceSRGB(colorSpace)) {
        // now copy/convert each scanline
        for (int y = 0; y < height; y++) {
            proc(dst, src, width, x, y);
            src += srcStride;
            dst = (char*)dst + dstBitmap.rowBytes();
        }
    } else {
        auto sRGB = SkColorSpace::MakeSRGB();
        auto xform = SkColorSpaceXform::New(sRGB.get(), colorSpace);

        std::unique_ptr<SkColor[]> row(new SkColor[width]);

        // now copy/convert each scanline
        for (int y = 0; y < height; y++) {
            memcpy(row.get(), src, sizeof(SkColor) * width);
            xform->apply(SkColorSpaceXform::kBGRA_8888_ColorFormat, row.get(),
                    SkColorSpaceXform::kBGRA_8888_ColorFormat, row.get(), width,
                    SkAlphaType::kUnpremul_SkAlphaType);

            proc(dst, row.get(), width, x, y);
            src += srcStride;
            dst = (char*)dst + dstBitmap.rowBytes();
        }
    }

    dstBitmap.notifyPixelsChanged();

    env->ReleaseIntArrayElements(srcColors, const_cast<jint*>(array), JNI_ABORT);
    return true;
}

//////////////////// ToColor procs

typedef void (*ToColorProc)(SkColor dst[], const void* src, int width);

static void ToColor_F16_Alpha(SkColor dst[], const void* src, int width) {
    SkASSERT(width > 0);
    uint64_t* s = (uint64_t*)src;
    do {
        *dst++ = SkPM4f::FromF16((const uint16_t*) s++).unpremul().toSkColor();
    } while (--width != 0);
}

static void ToColor_F16_Raw(SkColor dst[], const void* src, int width) {
    SkASSERT(width > 0);
    uint64_t* s = (uint64_t*)src;
    do {
        *dst++ = Sk4f_toS32(swizzle_rb(SkHalfToFloat_finite_ftz(*s++)));
    } while (--width != 0);
}

static void ToColor_S32_Alpha(SkColor dst[], const void* src, int width) {
    SkASSERT(width > 0);
    const SkPMColor* s = (const SkPMColor*)src;
    do {
        *dst++ = SkUnPreMultiply::PMColorToColor(*s++);
    } while (--width != 0);
}

static void ToColor_S32_Raw(SkColor dst[], const void* src, int width) {
    SkASSERT(width > 0);
    const SkPMColor* s = (const SkPMColor*)src;
    do {
        SkPMColor c = *s++;
        *dst++ = SkColorSetARGB(SkGetPackedA32(c), SkGetPackedR32(c),
                                SkGetPackedG32(c), SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S32_Opaque(SkColor dst[], const void* src, int width) {
    SkASSERT(width > 0);
    const SkPMColor* s = (const SkPMColor*)src;
    do {
        SkPMColor c = *s++;
        *dst++ = SkColorSetRGB(SkGetPackedR32(c), SkGetPackedG32(c),
                               SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S4444_Alpha(SkColor dst[], const void* src, int width) {
    SkASSERT(width > 0);
    const SkPMColor16* s = (const SkPMColor16*)src;
    do {
        *dst++ = SkUnPreMultiply::PMColorToColor(SkPixel4444ToPixel32(*s++));
    } while (--width != 0);
}

static void ToColor_S4444_Raw(SkColor dst[], const void* src, int width) {
    SkASSERT(width > 0);
    const SkPMColor16* s = (const SkPMColor16*)src;
    do {
        SkPMColor c = SkPixel4444ToPixel32(*s++);
        *dst++ = SkColorSetARGB(SkGetPackedA32(c), SkGetPackedR32(c),
                                SkGetPackedG32(c), SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S4444_Opaque(SkColor dst[], const void* src, int width) {
    SkASSERT(width > 0);
    const SkPMColor16* s = (const SkPMColor16*)src;
    do {
        SkPMColor c = SkPixel4444ToPixel32(*s++);
        *dst++ = SkColorSetRGB(SkGetPackedR32(c), SkGetPackedG32(c),
                               SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S565(SkColor dst[], const void* src, int width) {
    SkASSERT(width > 0);
    const uint16_t* s = (const uint16_t*)src;
    do {
        uint16_t c = *s++;
        *dst++ =  SkColorSetRGB(SkPacked16ToR32(c), SkPacked16ToG32(c),
                                SkPacked16ToB32(c));
    } while (--width != 0);
}

static void ToColor_SA8(SkColor dst[], const void* src, int width) {
    SkASSERT(width > 0);
    const uint8_t* s = (const uint8_t*)src;
    do {
        uint8_t c = *s++;
        *dst++ = SkColorSetARGB(c, 0, 0, 0);
    } while (--width != 0);
}

// can return NULL
static ToColorProc ChooseToColorProc(const SkBitmap& src) {
    switch (src.colorType()) {
        case kN32_SkColorType:
            switch (src.alphaType()) {
                case kOpaque_SkAlphaType:
                    return ToColor_S32_Opaque;
                case kPremul_SkAlphaType:
                    return ToColor_S32_Alpha;
                case kUnpremul_SkAlphaType:
                    return ToColor_S32_Raw;
                default:
                    return NULL;
            }
        case kARGB_4444_SkColorType:
            switch (src.alphaType()) {
                case kOpaque_SkAlphaType:
                    return ToColor_S4444_Opaque;
                case kPremul_SkAlphaType:
                    return ToColor_S4444_Alpha;
                case kUnpremul_SkAlphaType:
                    return ToColor_S4444_Raw;
                default:
                    return NULL;
            }
        case kRGB_565_SkColorType:
            return ToColor_S565;
        case kAlpha_8_SkColorType:
            return ToColor_SA8;
        case kRGBA_F16_SkColorType:
            switch (src.alphaType()) {
                case kOpaque_SkAlphaType:
                    return ToColor_F16_Raw;
                case kPremul_SkAlphaType:
                    return ToColor_F16_Alpha;
                case kUnpremul_SkAlphaType:
                    return ToColor_F16_Raw;
                default:
                    return NULL;
            }
        default:
            break;
    }
    return NULL;
}

static void ToF16_SA8(void* dst, const void* src, int width) {
    SkASSERT(width > 0);
    uint64_t* d = (uint64_t*)dst;
    const uint8_t* s = (const uint8_t*)src;

    for (int i = 0; i < width; i++) {
        uint8_t c = *s++;
        SkPM4f a;
        a.fVec[SkPM4f::R] = 0.0f;
        a.fVec[SkPM4f::G] = 0.0f;
        a.fVec[SkPM4f::B] = 0.0f;
        a.fVec[SkPM4f::A] = c / 255.0f;
        *d++ = a.toF16();
    }
}

///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////

static int getPremulBitmapCreateFlags(bool isMutable) {
    int flags = android::bitmap::kBitmapCreateFlag_Premultiplied;
    if (isMutable) flags |= android::bitmap::kBitmapCreateFlag_Mutable;
    return flags;
}

static jobject Bitmap_creator(JNIEnv* env, jobject, jintArray jColors,
                              jint offset, jint stride, jint width, jint height,
                              jint configHandle, jboolean isMutable,
                              jfloatArray xyzD50, jobject transferParameters) {
    SkColorType colorType = GraphicsJNI::legacyBitmapConfigToColorType(configHandle);
    if (NULL != jColors) {
        size_t n = env->GetArrayLength(jColors);
        if (n < SkAbs32(stride) * (size_t)height) {
            doThrowAIOOBE(env);
            return NULL;
        }
    }

    // ARGB_4444 is a deprecated format, convert automatically to 8888
    if (colorType == kARGB_4444_SkColorType) {
        colorType = kN32_SkColorType;
    }

    SkBitmap bitmap;
    sk_sp<SkColorSpace> colorSpace;

    if (colorType != kN32_SkColorType || xyzD50 == nullptr || transferParameters == nullptr) {
        colorSpace = GraphicsJNI::colorSpaceForType(colorType);
    } else {
        SkColorSpaceTransferFn p = GraphicsJNI::getNativeTransferParameters(env, transferParameters);
        SkMatrix44 xyzMatrix = GraphicsJNI::getNativeXYZMatrix(env, xyzD50);
        colorSpace = SkColorSpace::MakeRGB(p, xyzMatrix);
    }

    bitmap.setInfo(SkImageInfo::Make(width, height, colorType, kPremul_SkAlphaType, colorSpace));

    sk_sp<Bitmap> nativeBitmap = Bitmap::allocateHeapBitmap(&bitmap);
    if (!nativeBitmap) {
        ALOGE("OOM allocating Bitmap with dimensions %i x %i", width, height);
        doThrowOOME(env);
        return NULL;
    }

    if (jColors != NULL) {
        GraphicsJNI::SetPixels(env, jColors, offset, stride, 0, 0, width, height, bitmap);
    }

    return createBitmap(env, nativeBitmap.release(), getPremulBitmapCreateFlags(isMutable));
}

static bool bitmapCopyTo(SkBitmap* dst, SkColorType dstCT, const SkBitmap& src,
        SkBitmap::Allocator* alloc) {
    SkPixmap srcPM;
    if (!src.peekPixels(&srcPM)) {
        return false;
    }

    SkImageInfo dstInfo = srcPM.info().makeColorType(dstCT);
    switch (dstCT) {
        case kRGB_565_SkColorType:
            // copyTo() has never been strict on alpha type.  Here we set the src to opaque to
            // allow the call to readPixels() to succeed and preserve this lenient behavior.
            if (kOpaque_SkAlphaType != srcPM.alphaType()) {
                srcPM = SkPixmap(srcPM.info().makeAlphaType(kOpaque_SkAlphaType), srcPM.addr(),
                                 srcPM.rowBytes());
                dstInfo = dstInfo.makeAlphaType(kOpaque_SkAlphaType);
            }
            break;
        case kRGBA_F16_SkColorType:
            // The caller does not have an opportunity to pass a dst color space.  Assume that
            // they want linear sRGB.
            dstInfo = dstInfo.makeColorSpace(SkColorSpace::MakeSRGBLinear());

            if (!srcPM.colorSpace()) {
                // Skia needs a color space to convert to F16.  nullptr should be treated as sRGB.
                srcPM.setColorSpace(SkColorSpace::MakeSRGB());
            }
            break;
        default:
            break;
    }

    if (!dst->setInfo(dstInfo)) {
        return false;
    }
    if (!dst->tryAllocPixels(alloc)) {
        return false;
    }

    // Skia does not support copying from kAlpha8 to types that are not alpha only.
    // We will handle this case here.
    if (kAlpha_8_SkColorType == srcPM.colorType() && kAlpha_8_SkColorType != dstCT) {
        switch (dstCT) {
            case kRGBA_8888_SkColorType:
            case kBGRA_8888_SkColorType: {
                for (int y = 0; y < src.height(); y++) {
                    const uint8_t* srcRow = srcPM.addr8(0, y);
                    uint32_t* dstRow = dst->getAddr32(0, y);
                    ToColor_SA8(dstRow, srcRow, src.width());
                }
                return true;
            }
            case kRGB_565_SkColorType: {
                for (int y = 0; y < src.height(); y++) {
                    uint16_t* dstRow = dst->getAddr16(0, y);
                    memset(dstRow, 0, sizeof(uint16_t) * src.width());
                }
                return true;
            }
            case kRGBA_F16_SkColorType: {
               for (int y = 0; y < src.height(); y++) {
                   const uint8_t* srcRow = srcPM.addr8(0, y);
                   void* dstRow = dst->getAddr(0, y);
                   ToF16_SA8(dstRow, srcRow, src.width());
               }
               return true;
           }
            default:
                return false;
        }
    }

    SkPixmap dstPM;
    if (!dst->peekPixels(&dstPM)) {
        return false;
    }

    // Skia needs a color space to convert from F16.  nullptr should be treated as sRGB.
    if (kRGBA_F16_SkColorType == srcPM.colorType() && !dstPM.colorSpace()) {
        dstPM.setColorSpace(SkColorSpace::MakeSRGB());
    }

    // readPixels does not support color spaces with parametric transfer functions.  This
    // works around that restriction when the color spaces are equal.
    if (kRGBA_F16_SkColorType != dstCT && kRGBA_F16_SkColorType != srcPM.colorType() &&
            dstPM.colorSpace() == srcPM.colorSpace()) {
        dstPM.setColorSpace(nullptr);
        srcPM.setColorSpace(nullptr);
    }

    return srcPM.readPixels(dstPM);
}

static jobject Bitmap_copy(JNIEnv* env, jobject, jlong srcHandle,
                           jint dstConfigHandle, jboolean isMutable) {
    SkBitmap src;
    reinterpret_cast<BitmapWrapper*>(srcHandle)->getSkBitmap(&src);
    if (dstConfigHandle == GraphicsJNI::hardwareLegacyBitmapConfig()) {
        sk_sp<Bitmap> bitmap(Bitmap::allocateHardwareBitmap(src));
        if (!bitmap.get()) {
            return NULL;
        }
        return createBitmap(env, bitmap.release(), getPremulBitmapCreateFlags(isMutable));
    }

    SkColorType dstCT = GraphicsJNI::legacyBitmapConfigToColorType(dstConfigHandle);
    SkBitmap result;
    HeapAllocator allocator;

    if (!bitmapCopyTo(&result, dstCT, src, &allocator)) {
        return NULL;
    }
    auto bitmap = allocator.getStorageObjAndReset();
    return createBitmap(env, bitmap, getPremulBitmapCreateFlags(isMutable));
}

static Bitmap* Bitmap_copyAshmemImpl(JNIEnv* env, SkBitmap& src, SkColorType& dstCT) {
    SkBitmap result;

    AshmemPixelAllocator allocator(env);
    if (!bitmapCopyTo(&result, dstCT, src, &allocator)) {
        return NULL;
    }
    auto bitmap = allocator.getStorageObjAndReset();
    bitmap->setImmutable();
    return bitmap;
}

static jobject Bitmap_copyAshmem(JNIEnv* env, jobject, jlong srcHandle) {
    SkBitmap src;
    reinterpret_cast<BitmapWrapper*>(srcHandle)->getSkBitmap(&src);
    SkColorType dstCT = src.colorType();
    auto bitmap = Bitmap_copyAshmemImpl(env, src, dstCT);
    jobject ret = createBitmap(env, bitmap, getPremulBitmapCreateFlags(false));
    return ret;
}

static jobject Bitmap_copyAshmemConfig(JNIEnv* env, jobject, jlong srcHandle, jint dstConfigHandle) {
    SkBitmap src;
    reinterpret_cast<BitmapWrapper*>(srcHandle)->getSkBitmap(&src);
    SkColorType dstCT = GraphicsJNI::legacyBitmapConfigToColorType(dstConfigHandle);
    auto bitmap = Bitmap_copyAshmemImpl(env, src, dstCT);
    jobject ret = createBitmap(env, bitmap, getPremulBitmapCreateFlags(false));
    return ret;
}

static void Bitmap_destruct(BitmapWrapper* bitmap) {
    delete bitmap;
}

static jlong Bitmap_getNativeFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&Bitmap_destruct));
}

static jboolean Bitmap_recycle(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    bitmap->freePixels();
    return JNI_TRUE;
}

static void Bitmap_reconfigure(JNIEnv* env, jobject clazz, jlong bitmapHandle,
        jint width, jint height, jint configHandle, jboolean requestPremul) {
    LocalScopedBitmap bitmap(bitmapHandle);
    bitmap->assertValid();
    SkColorType colorType = GraphicsJNI::legacyBitmapConfigToColorType(configHandle);

    // ARGB_4444 is a deprecated format, convert automatically to 8888
    if (colorType == kARGB_4444_SkColorType) {
        colorType = kN32_SkColorType;
    }
    size_t requestedSize = width * height * SkColorTypeBytesPerPixel(colorType);
    if (requestedSize > bitmap->getAllocationByteCount()) {
        // done in native as there's no way to get BytesPerPixel in Java
        doThrowIAE(env, "Bitmap not large enough to support new configuration");
        return;
    }
    SkAlphaType alphaType;
    if (bitmap->info().colorType() != kRGB_565_SkColorType
            && bitmap->info().alphaType() == kOpaque_SkAlphaType) {
        // If the original bitmap was set to opaque, keep that setting, unless it
        // was 565, which is required to be opaque.
        alphaType = kOpaque_SkAlphaType;
    } else {
        // Otherwise respect the premultiplied request.
        alphaType = requestPremul ? kPremul_SkAlphaType : kUnpremul_SkAlphaType;
    }
    bitmap->bitmap().reconfigure(SkImageInfo::Make(width, height, colorType, alphaType,
            sk_ref_sp(bitmap->info().colorSpace())));
}

// These must match the int values in Bitmap.java
enum JavaEncodeFormat {
    kJPEG_JavaEncodeFormat = 0,
    kPNG_JavaEncodeFormat = 1,
    kWEBP_JavaEncodeFormat = 2
};

static jboolean Bitmap_compress(JNIEnv* env, jobject clazz, jlong bitmapHandle,
                                jint format, jint quality,
                                jobject jstream, jbyteArray jstorage) {
    SkEncodedImageFormat fm;
    switch (format) {
    case kJPEG_JavaEncodeFormat:
        fm = SkEncodedImageFormat::kJPEG;
        break;
    case kPNG_JavaEncodeFormat:
        fm = SkEncodedImageFormat::kPNG;
        break;
    case kWEBP_JavaEncodeFormat:
        fm = SkEncodedImageFormat::kWEBP;
        break;
    default:
        return JNI_FALSE;
    }

    LocalScopedBitmap bitmap(bitmapHandle);
    if (!bitmap.valid()) {
        return JNI_FALSE;
    }

    std::unique_ptr<SkWStream> strm(CreateJavaOutputStreamAdaptor(env, jstream, jstorage));
    if (!strm.get()) {
        return JNI_FALSE;
    }

    SkBitmap skbitmap;
    bitmap->getSkBitmap(&skbitmap);
    if (skbitmap.colorType() == kRGBA_F16_SkColorType) {
        // Convert to P3 before encoding. This matches SkAndroidCodec::computeOutputColorSpace
        // for wide gamuts.
        auto cs = SkColorSpace::MakeRGB(SkColorSpace::kSRGB_RenderTargetGamma,
                                        SkColorSpace::kDCIP3_D65_Gamut);
        auto info = skbitmap.info().makeColorType(kRGBA_8888_SkColorType)
                                   .makeColorSpace(std::move(cs));
        SkBitmap p3;
        if (!p3.tryAllocPixels(info)) {
            return JNI_FALSE;
        }
        auto xform = SkColorSpaceXform::New(skbitmap.colorSpace(), info.colorSpace());
        if (!xform) {
            return JNI_FALSE;
        }
        if (!xform->apply(SkColorSpaceXform::kRGBA_8888_ColorFormat, p3.getPixels(),
                          SkColorSpaceXform::kRGBA_F16_ColorFormat, skbitmap.getPixels(),
                          info.width() * info.height(), kUnpremul_SkAlphaType)) {
            return JNI_FALSE;
        }
        skbitmap = p3;
    }
    return SkEncodeImage(strm.get(), skbitmap, fm, quality) ? JNI_TRUE : JNI_FALSE;
}

static void Bitmap_erase(JNIEnv* env, jobject, jlong bitmapHandle, jint color) {
    LocalScopedBitmap bitmap(bitmapHandle);
    SkBitmap skBitmap;
    bitmap->getSkBitmap(&skBitmap);
    skBitmap.eraseColor(color);
}

static jint Bitmap_rowBytes(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    return static_cast<jint>(bitmap->rowBytes());
}

static jint Bitmap_config(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    if (bitmap->isHardware()) {
        return GraphicsJNI::hardwareLegacyBitmapConfig();
    }
    return GraphicsJNI::colorTypeToLegacyBitmapConfig(bitmap->info().colorType());
}

static jint Bitmap_getGenerationId(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    return static_cast<jint>(bitmap->getGenerationID());
}

static jboolean Bitmap_isPremultiplied(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    if (bitmap->info().alphaType() == kPremul_SkAlphaType) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean Bitmap_hasAlpha(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    return !bitmap->info().isOpaque() ? JNI_TRUE : JNI_FALSE;
}

static void Bitmap_setHasAlpha(JNIEnv* env, jobject, jlong bitmapHandle,
        jboolean hasAlpha, jboolean requestPremul) {
    LocalScopedBitmap bitmap(bitmapHandle);
    if (hasAlpha) {
        bitmap->setAlphaType(
                requestPremul ? kPremul_SkAlphaType : kUnpremul_SkAlphaType);
    } else {
        bitmap->setAlphaType(kOpaque_SkAlphaType);
    }
}

static void Bitmap_setPremultiplied(JNIEnv* env, jobject, jlong bitmapHandle,
        jboolean isPremul) {
    LocalScopedBitmap bitmap(bitmapHandle);
    if (!bitmap->info().isOpaque()) {
        if (isPremul) {
            bitmap->setAlphaType(kPremul_SkAlphaType);
        } else {
            bitmap->setAlphaType(kUnpremul_SkAlphaType);
        }
    }
}

static jboolean Bitmap_hasMipMap(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    return bitmap->hasHardwareMipMap() ? JNI_TRUE : JNI_FALSE;
}

static void Bitmap_setHasMipMap(JNIEnv* env, jobject, jlong bitmapHandle,
                                jboolean hasMipMap) {
    LocalScopedBitmap bitmap(bitmapHandle);
    bitmap->setHasHardwareMipMap(hasMipMap);
}

///////////////////////////////////////////////////////////////////////////////

// This is the maximum possible size because the SkColorSpace must be
// representable (and therefore serializable) using a matrix and numerical
// transfer function.  If we allow more color space representations in the
// framework, we may need to update this maximum size.
static constexpr uint32_t kMaxColorSpaceSerializedBytes = 80;

static jobject Bitmap_createFromParcel(JNIEnv* env, jobject, jobject parcel) {
    if (parcel == NULL) {
        SkDebugf("-------- unparcel parcel is NULL\n");
        return NULL;
    }

    android::Parcel* p = android::parcelForJavaObject(env, parcel);

    const bool        isMutable = p->readInt32() != 0;
    const SkColorType colorType = (SkColorType)p->readInt32();
    const SkAlphaType alphaType = (SkAlphaType)p->readInt32();
    const uint32_t    colorSpaceSize = p->readUint32();
    sk_sp<SkColorSpace> colorSpace;
    if (kRGBA_F16_SkColorType == colorType) {
        colorSpace = SkColorSpace::MakeSRGBLinear();
    } else if (colorSpaceSize > 0) {
        if (colorSpaceSize > kMaxColorSpaceSerializedBytes) {
            ALOGD("Bitmap_createFromParcel: Serialized SkColorSpace is larger than expected: "
                    "%d bytes\n", colorSpaceSize);
        }

        const void* data = p->readInplace(colorSpaceSize);
        if (data) {
            colorSpace = SkColorSpace::Deserialize(data, colorSpaceSize);
        } else {
            ALOGD("Bitmap_createFromParcel: Unable to read serialized SkColorSpace data\n");
        }
    }
    const int         width = p->readInt32();
    const int         height = p->readInt32();
    const int         rowBytes = p->readInt32();
    const int         density = p->readInt32();

    if (kN32_SkColorType != colorType &&
            kRGBA_F16_SkColorType != colorType &&
            kRGB_565_SkColorType != colorType &&
            kARGB_4444_SkColorType != colorType &&
            kAlpha_8_SkColorType != colorType) {
        SkDebugf("Bitmap_createFromParcel unknown colortype: %d\n", colorType);
        return NULL;
    }

    std::unique_ptr<SkBitmap> bitmap(new SkBitmap);
    if (!bitmap->setInfo(SkImageInfo::Make(width, height, colorType, alphaType, colorSpace),
            rowBytes)) {
        return NULL;
    }

    // Read the bitmap blob.
    size_t size = bitmap->computeByteSize();
    android::Parcel::ReadableBlob blob;
    android::status_t status = p->readBlob(size, &blob);
    if (status) {
        doThrowRE(env, "Could not read bitmap blob.");
        return NULL;
    }

    // Map the bitmap in place from the ashmem region if possible otherwise copy.
    sk_sp<Bitmap> nativeBitmap;
    if (blob.fd() >= 0 && (blob.isMutable() || !isMutable) && (size >= ASHMEM_BITMAP_MIN_SIZE)) {
#if DEBUG_PARCEL
        ALOGD("Bitmap.createFromParcel: mapped contents of %s bitmap from %s blob "
                "(fds %s)",
                isMutable ? "mutable" : "immutable",
                blob.isMutable() ? "mutable" : "immutable",
                p->allowFds() ? "allowed" : "forbidden");
#endif
        // Dup the file descriptor so we can keep a reference to it after the Parcel
        // is disposed.
        int dupFd = dup(blob.fd());
        if (dupFd < 0) {
            ALOGE("Error allocating dup fd. Error:%d", errno);
            blob.release();
            doThrowRE(env, "Could not allocate dup blob fd.");
            return NULL;
        }

        // Map the pixels in place and take ownership of the ashmem region.
        nativeBitmap = sk_sp<Bitmap>(GraphicsJNI::mapAshmemBitmap(env, bitmap.get(),
                dupFd, const_cast<void*>(blob.data()), size, !isMutable));
        if (!nativeBitmap) {
            close(dupFd);
            blob.release();
            doThrowRE(env, "Could not allocate ashmem pixel ref.");
            return NULL;
        }

        // Clear the blob handle, don't release it.
        blob.clear();
    } else {
#if DEBUG_PARCEL
        if (blob.fd() >= 0) {
            ALOGD("Bitmap.createFromParcel: copied contents of mutable bitmap "
                    "from immutable blob (fds %s)",
                    p->allowFds() ? "allowed" : "forbidden");
        } else {
            ALOGD("Bitmap.createFromParcel: copied contents from %s blob "
                    "(fds %s)",
                    blob.isMutable() ? "mutable" : "immutable",
                    p->allowFds() ? "allowed" : "forbidden");
        }
#endif

        // Copy the pixels into a new buffer.
        nativeBitmap = Bitmap::allocateHeapBitmap(bitmap.get());
        if (!nativeBitmap) {
            blob.release();
            doThrowRE(env, "Could not allocate java pixel ref.");
            return NULL;
        }
        memcpy(bitmap->getPixels(), blob.data(), size);

        // Release the blob handle.
        blob.release();
    }

    return createBitmap(env, nativeBitmap.release(),
            getPremulBitmapCreateFlags(isMutable), NULL, NULL, density);
}

static jboolean Bitmap_writeToParcel(JNIEnv* env, jobject,
                                     jlong bitmapHandle,
                                     jboolean isMutable, jint density,
                                     jobject parcel) {
    if (parcel == NULL) {
        SkDebugf("------- writeToParcel null parcel\n");
        return JNI_FALSE;
    }

    android::Parcel* p = android::parcelForJavaObject(env, parcel);
    SkBitmap bitmap;

    auto bitmapWrapper = reinterpret_cast<BitmapWrapper*>(bitmapHandle);
    bitmapWrapper->getSkBitmap(&bitmap);

    p->writeInt32(isMutable);
    p->writeInt32(bitmap.colorType());
    p->writeInt32(bitmap.alphaType());
    SkColorSpace* colorSpace = bitmap.colorSpace();
    if (colorSpace != nullptr && bitmap.colorType() != kRGBA_F16_SkColorType) {
        sk_sp<SkData> data = colorSpace->serialize();
        size_t size = data->size();
        p->writeUint32(size);
        if (size > 0) {
            if (size > kMaxColorSpaceSerializedBytes) {
                ALOGD("Bitmap_writeToParcel: Serialized SkColorSpace is larger than expected: "
                        "%zu bytes\n", size);
            }

            p->write(data->data(), size);
        }
    } else {
        p->writeUint32(0);
    }
    p->writeInt32(bitmap.width());
    p->writeInt32(bitmap.height());
    p->writeInt32(bitmap.rowBytes());
    p->writeInt32(density);

    // Transfer the underlying ashmem region if we have one and it's immutable.
    android::status_t status;
    int fd = bitmapWrapper->bitmap().getAshmemFd();
    if (fd >= 0 && !isMutable && p->allowFds()) {
#if DEBUG_PARCEL
        ALOGD("Bitmap.writeToParcel: transferring immutable bitmap's ashmem fd as "
                "immutable blob (fds %s)",
                p->allowFds() ? "allowed" : "forbidden");
#endif

        status = p->writeDupImmutableBlobFileDescriptor(fd);
        if (status) {
            doThrowRE(env, "Could not write bitmap blob file descriptor.");
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }

    // Copy the bitmap to a new blob.
    bool mutableCopy = isMutable;
#if DEBUG_PARCEL
    ALOGD("Bitmap.writeToParcel: copying %s bitmap into new %s blob (fds %s)",
            isMutable ? "mutable" : "immutable",
            mutableCopy ? "mutable" : "immutable",
            p->allowFds() ? "allowed" : "forbidden");
#endif

    size_t size = bitmap.computeByteSize();
    android::Parcel::WritableBlob blob;
    status = p->writeBlob(size, mutableCopy, &blob);
    if (status) {
        doThrowRE(env, "Could not copy bitmap to parcel blob.");
        return JNI_FALSE;
    }

    const void* pSrc =  bitmap.getPixels();
    if (pSrc == NULL) {
        memset(blob.data(), 0, size);
    } else {
        memcpy(blob.data(), pSrc, size);
    }

    blob.release();
    return JNI_TRUE;
}

static jobject Bitmap_extractAlpha(JNIEnv* env, jobject clazz,
                                   jlong srcHandle, jlong paintHandle,
                                   jintArray offsetXY) {
    SkBitmap src;
    reinterpret_cast<BitmapWrapper*>(srcHandle)->getSkBitmap(&src);
    const android::Paint* paint = reinterpret_cast<android::Paint*>(paintHandle);
    SkIPoint  offset;
    SkBitmap dst;
    HeapAllocator allocator;

    src.extractAlpha(&dst, paint, &allocator, &offset);
    // If Skia can't allocate pixels for destination bitmap, it resets
    // it, that is set its pixels buffer to NULL, and zero width and height.
    if (dst.getPixels() == NULL && src.getPixels() != NULL) {
        doThrowOOME(env, "failed to allocate pixels for alpha");
        return NULL;
    }
    if (offsetXY != 0 && env->GetArrayLength(offsetXY) >= 2) {
        int* array = env->GetIntArrayElements(offsetXY, NULL);
        array[0] = offset.fX;
        array[1] = offset.fY;
        env->ReleaseIntArrayElements(offsetXY, array, 0);
    }

    return createBitmap(env, allocator.getStorageObjAndReset(),
            getPremulBitmapCreateFlags(true));
}

///////////////////////////////////////////////////////////////////////////////

static jboolean Bitmap_isSRGB(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmapHolder(bitmapHandle);
    if (!bitmapHolder.valid()) return JNI_TRUE;

    SkColorSpace* colorSpace = bitmapHolder->info().colorSpace();
    return GraphicsJNI::isColorSpaceSRGB(colorSpace);
}

static jboolean Bitmap_isSRGBLinear(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmapHolder(bitmapHandle);
    if (!bitmapHolder.valid()) return JNI_FALSE;

    SkColorSpace* colorSpace = bitmapHolder->info().colorSpace();
    sk_sp<SkColorSpace> srgbLinear = SkColorSpace::MakeSRGBLinear();
    return colorSpace == srgbLinear.get() ? JNI_TRUE : JNI_FALSE;
}

static jboolean Bitmap_getColorSpace(JNIEnv* env, jobject, jlong bitmapHandle,
        jfloatArray xyzArray, jfloatArray paramsArray) {

    LocalScopedBitmap bitmapHolder(bitmapHandle);
    if (!bitmapHolder.valid()) return JNI_FALSE;

    SkColorSpace* colorSpace = bitmapHolder->info().colorSpace();
    if (colorSpace == nullptr) return JNI_FALSE;

    SkMatrix44 xyzMatrix(SkMatrix44::kUninitialized_Constructor);
    if (!colorSpace->toXYZD50(&xyzMatrix)) return JNI_FALSE;

    jfloat* xyz = env->GetFloatArrayElements(xyzArray, NULL);
    xyz[0] = xyzMatrix.getFloat(0, 0);
    xyz[1] = xyzMatrix.getFloat(1, 0);
    xyz[2] = xyzMatrix.getFloat(2, 0);
    xyz[3] = xyzMatrix.getFloat(0, 1);
    xyz[4] = xyzMatrix.getFloat(1, 1);
    xyz[5] = xyzMatrix.getFloat(2, 1);
    xyz[6] = xyzMatrix.getFloat(0, 2);
    xyz[7] = xyzMatrix.getFloat(1, 2);
    xyz[8] = xyzMatrix.getFloat(2, 2);
    env->ReleaseFloatArrayElements(xyzArray, xyz, 0);

    SkColorSpaceTransferFn transferParams;
    if (!colorSpace->isNumericalTransferFn(&transferParams)) return JNI_FALSE;

    jfloat* params = env->GetFloatArrayElements(paramsArray, NULL);
    params[0] = transferParams.fA;
    params[1] = transferParams.fB;
    params[2] = transferParams.fC;
    params[3] = transferParams.fD;
    params[4] = transferParams.fE;
    params[5] = transferParams.fF;
    params[6] = transferParams.fG;
    env->ReleaseFloatArrayElements(paramsArray, params, 0);

    return JNI_TRUE;
}

///////////////////////////////////////////////////////////////////////////////

static jint Bitmap_getPixel(JNIEnv* env, jobject, jlong bitmapHandle,
        jint x, jint y) {
    SkBitmap bitmap;
    reinterpret_cast<BitmapWrapper*>(bitmapHandle)->getSkBitmap(&bitmap);

    ToColorProc proc = ChooseToColorProc(bitmap);
    if (NULL == proc) {
        return 0;
    }
    const void* src = bitmap.getAddr(x, y);
    if (NULL == src) {
        return 0;
    }

    SkColor dst[1];
    proc(dst, src, 1);

    SkColorSpace* colorSpace = bitmap.colorSpace();
    if (bitmap.colorType() != kRGBA_F16_SkColorType &&
            !GraphicsJNI::isColorSpaceSRGB(colorSpace)) {
        auto sRGB = SkColorSpace::MakeSRGB();
        auto xform = SkColorSpaceXform::New(colorSpace, sRGB.get());
        xform->apply(SkColorSpaceXform::kBGRA_8888_ColorFormat, &dst[0],
                SkColorSpaceXform::kBGRA_8888_ColorFormat, &dst[0], 1,
                SkAlphaType::kUnpremul_SkAlphaType);
    }

    return static_cast<jint>(dst[0]);
}

static void Bitmap_getPixels(JNIEnv* env, jobject, jlong bitmapHandle,
        jintArray pixelArray, jint offset, jint stride,
        jint x, jint y, jint width, jint height) {
    SkBitmap bitmap;
    reinterpret_cast<BitmapWrapper*>(bitmapHandle)->getSkBitmap(&bitmap);

    ToColorProc proc = ChooseToColorProc(bitmap);
    if (NULL == proc) {
        return;
    }
    const void* src = bitmap.getAddr(x, y);
    if (NULL == src) {
        return;
    }

    jint* dst = env->GetIntArrayElements(pixelArray, NULL);
    SkColor* d = (SkColor*)dst + offset;

    SkColorSpace* colorSpace = bitmap.colorSpace();
    if (bitmap.colorType() == kRGBA_F16_SkColorType ||
            GraphicsJNI::isColorSpaceSRGB(colorSpace)) {
        while (--height >= 0) {
            proc(d, src, width);
            d += stride;
            src = (void*)((const char*)src + bitmap.rowBytes());
        }
    } else {
        auto sRGB = SkColorSpace::MakeSRGB();
        auto xform = SkColorSpaceXform::New(colorSpace, sRGB.get());

        while (--height >= 0) {
            proc(d, src, width);

            xform->apply(SkColorSpaceXform::kBGRA_8888_ColorFormat, d,
                    SkColorSpaceXform::kBGRA_8888_ColorFormat, d, width,
                    SkAlphaType::kUnpremul_SkAlphaType);

            d += stride;
            src = (void*)((const char*)src + bitmap.rowBytes());
        }
    }

    env->ReleaseIntArrayElements(pixelArray, dst, 0);
}

///////////////////////////////////////////////////////////////////////////////

static void Bitmap_setPixel(JNIEnv* env, jobject, jlong bitmapHandle,
        jint x, jint y, jint colorHandle) {
    SkBitmap bitmap;
    reinterpret_cast<BitmapWrapper*>(bitmapHandle)->getSkBitmap(&bitmap);
    SkColor color = static_cast<SkColor>(colorHandle);
    if (NULL == bitmap.getPixels()) {
        return;
    }

    FromColorProc proc = ChooseFromColorProc(bitmap);
    if (NULL == proc) {
        return;
    }

    SkColorSpace* colorSpace = bitmap.colorSpace();
    if (bitmap.colorType() != kRGBA_F16_SkColorType &&
            !GraphicsJNI::isColorSpaceSRGB(colorSpace)) {
        auto sRGB = SkColorSpace::MakeSRGB();
        auto xform = SkColorSpaceXform::New(sRGB.get(), colorSpace);
        xform->apply(SkColorSpaceXform::kBGRA_8888_ColorFormat, &color,
                SkColorSpaceXform::kBGRA_8888_ColorFormat, &color, 1,
                SkAlphaType::kUnpremul_SkAlphaType);
    }

    proc(bitmap.getAddr(x, y), &color, 1, x, y);
    bitmap.notifyPixelsChanged();
}

static void Bitmap_setPixels(JNIEnv* env, jobject, jlong bitmapHandle,
        jintArray pixelArray, jint offset, jint stride,
        jint x, jint y, jint width, jint height) {
    SkBitmap bitmap;
    reinterpret_cast<BitmapWrapper*>(bitmapHandle)->getSkBitmap(&bitmap);
    GraphicsJNI::SetPixels(env, pixelArray, offset, stride,
            x, y, width, height, bitmap);
}

static void Bitmap_copyPixelsToBuffer(JNIEnv* env, jobject,
                                      jlong bitmapHandle, jobject jbuffer) {
    SkBitmap bitmap;
    reinterpret_cast<BitmapWrapper*>(bitmapHandle)->getSkBitmap(&bitmap);
    const void* src = bitmap.getPixels();

    if (NULL != src) {
        android::AutoBufferPointer abp(env, jbuffer, JNI_TRUE);

        // the java side has already checked that buffer is large enough
        memcpy(abp.pointer(), src, bitmap.computeByteSize());
    }
}

static void Bitmap_copyPixelsFromBuffer(JNIEnv* env, jobject,
                                        jlong bitmapHandle, jobject jbuffer) {
    SkBitmap bitmap;
    reinterpret_cast<BitmapWrapper*>(bitmapHandle)->getSkBitmap(&bitmap);
    void* dst = bitmap.getPixels();

    if (NULL != dst) {
        android::AutoBufferPointer abp(env, jbuffer, JNI_FALSE);
        // the java side has already checked that buffer is large enough
        memcpy(dst, abp.pointer(), bitmap.computeByteSize());
        bitmap.notifyPixelsChanged();
    }
}

static jboolean Bitmap_sameAs(JNIEnv* env, jobject, jlong bm0Handle, jlong bm1Handle) {
    SkBitmap bm0;
    SkBitmap bm1;

    LocalScopedBitmap bitmap0(bm0Handle);
    LocalScopedBitmap bitmap1(bm1Handle);

    // Paying the price for making Hardware Bitmap as Config:
    // later check for colorType will pass successfully,
    // because Hardware Config internally may be RGBA8888 or smth like that.
    if (bitmap0->isHardware() != bitmap1->isHardware()) {
        return JNI_FALSE;
    }

    bitmap0->bitmap().getSkBitmap(&bm0);
    bitmap1->bitmap().getSkBitmap(&bm1);
    if (bm0.width() != bm1.width()
            || bm0.height() != bm1.height()
            || bm0.colorType() != bm1.colorType()
            || bm0.alphaType() != bm1.alphaType()
            || !SkColorSpace::Equals(bm0.colorSpace(), bm1.colorSpace())) {
        return JNI_FALSE;
    }

    // if we can't load the pixels, return false
    if (NULL == bm0.getPixels() || NULL == bm1.getPixels()) {
        return JNI_FALSE;
    }

    // now compare each scanline. We can't do the entire buffer at once,
    // since we don't care about the pixel values that might extend beyond
    // the width (since the scanline might be larger than the logical width)
    const int h = bm0.height();
    const size_t size = bm0.width() * bm0.bytesPerPixel();
    for (int y = 0; y < h; y++) {
        // SkBitmap::getAddr(int, int) may return NULL due to unrecognized config
        // (ex: kRLE_Index8_Config). This will cause memcmp method to crash. Since bm0
        // and bm1 both have pixel data() (have passed NULL == getPixels() check),
        // those 2 bitmaps should be valid (only unrecognized), we return JNI_FALSE
        // to warn user those 2 unrecognized config bitmaps may be different.
        void *bm0Addr = bm0.getAddr(0, y);
        void *bm1Addr = bm1.getAddr(0, y);

        if(bm0Addr == NULL || bm1Addr == NULL) {
            return JNI_FALSE;
        }

        if (memcmp(bm0Addr, bm1Addr, size) != 0) {
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}

static void Bitmap_prepareToDraw(JNIEnv* env, jobject, jlong bitmapPtr) {
    LocalScopedBitmap bitmapHandle(bitmapPtr);
    if (!bitmapHandle.valid()) return;
    android::uirenderer::renderthread::RenderProxy::prepareToDraw(bitmapHandle->bitmap());
}

static jint Bitmap_getAllocationByteCount(JNIEnv* env, jobject, jlong bitmapPtr) {
    LocalScopedBitmap bitmapHandle(bitmapPtr);
    return static_cast<jint>(bitmapHandle->getAllocationByteCount());
}

static jobject Bitmap_copyPreserveInternalConfig(JNIEnv* env, jobject, jlong bitmapPtr) {
    LocalScopedBitmap bitmapHandle(bitmapPtr);
    LOG_ALWAYS_FATAL_IF(!bitmapHandle->isHardware(),
            "Hardware config is only supported config in Bitmap_nativeCopyPreserveInternalConfig");
    Bitmap& hwuiBitmap = bitmapHandle->bitmap();
    SkBitmap src;
    hwuiBitmap.getSkBitmap(&src);

    SkBitmap result;
    HeapAllocator allocator;
    if (!bitmapCopyTo(&result, hwuiBitmap.info().colorType(), src, &allocator)) {
        doThrowRE(env, "Could not copy a hardware bitmap.");
        return NULL;
    }
    return createBitmap(env, allocator.getStorageObjAndReset(), getPremulBitmapCreateFlags(false));
}

static jobject Bitmap_createHardwareBitmap(JNIEnv* env, jobject, jobject graphicBuffer) {
    sp<GraphicBuffer> buffer(graphicBufferForJavaObject(env, graphicBuffer));
    sk_sp<Bitmap> bitmap = Bitmap::createFrom(buffer);
    if (!bitmap.get()) {
        ALOGW("failed to create hardware bitmap from graphic buffer");
        return NULL;
    }
    return bitmap::createBitmap(env, bitmap.release(), getPremulBitmapCreateFlags(false));
}

static jobject Bitmap_createGraphicBufferHandle(JNIEnv* env, jobject, jlong bitmapPtr) {
    LocalScopedBitmap bitmapHandle(bitmapPtr);
    LOG_ALWAYS_FATAL_IF(!bitmapHandle->isHardware(),
            "Hardware config is only supported config in Bitmap_getGraphicBuffer");

    Bitmap& hwuiBitmap = bitmapHandle->bitmap();
    sp<GraphicBuffer> buffer(hwuiBitmap.graphicBuffer());
    return createJavaGraphicBuffer(env, buffer);
}

static void Bitmap_copyColorSpace(JNIEnv* env, jobject, jlong srcBitmapPtr, jlong dstBitmapPtr) {
    LocalScopedBitmap srcBitmapHandle(srcBitmapPtr);
    LocalScopedBitmap dstBitmapHandle(dstBitmapPtr);

    dstBitmapHandle->bitmap().setColorSpace(srcBitmapHandle->bitmap().info().refColorSpace());
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gBitmapMethods[] = {
    {   "nativeCreate",             "([IIIIIIZ[FLandroid/graphics/ColorSpace$Rgb$TransferParameters;)Landroid/graphics/Bitmap;",
        (void*)Bitmap_creator },
    {   "nativeCopy",               "(JIZ)Landroid/graphics/Bitmap;",
        (void*)Bitmap_copy },
    {   "nativeCopyAshmem",         "(J)Landroid/graphics/Bitmap;",
        (void*)Bitmap_copyAshmem },
    {   "nativeCopyAshmemConfig",   "(JI)Landroid/graphics/Bitmap;",
        (void*)Bitmap_copyAshmemConfig },
    {   "nativeGetNativeFinalizer", "()J", (void*)Bitmap_getNativeFinalizer },
    {   "nativeRecycle",            "(J)Z", (void*)Bitmap_recycle },
    {   "nativeReconfigure",        "(JIIIZ)V", (void*)Bitmap_reconfigure },
    {   "nativeCompress",           "(JIILjava/io/OutputStream;[B)Z",
        (void*)Bitmap_compress },
    {   "nativeErase",              "(JI)V", (void*)Bitmap_erase },
    {   "nativeRowBytes",           "(J)I", (void*)Bitmap_rowBytes },
    {   "nativeConfig",             "(J)I", (void*)Bitmap_config },
    {   "nativeHasAlpha",           "(J)Z", (void*)Bitmap_hasAlpha },
    {   "nativeIsPremultiplied",    "(J)Z", (void*)Bitmap_isPremultiplied},
    {   "nativeSetHasAlpha",        "(JZZ)V", (void*)Bitmap_setHasAlpha},
    {   "nativeSetPremultiplied",   "(JZ)V", (void*)Bitmap_setPremultiplied},
    {   "nativeHasMipMap",          "(J)Z", (void*)Bitmap_hasMipMap },
    {   "nativeSetHasMipMap",       "(JZ)V", (void*)Bitmap_setHasMipMap },
    {   "nativeCreateFromParcel",
        "(Landroid/os/Parcel;)Landroid/graphics/Bitmap;",
        (void*)Bitmap_createFromParcel },
    {   "nativeWriteToParcel",      "(JZILandroid/os/Parcel;)Z",
        (void*)Bitmap_writeToParcel },
    {   "nativeExtractAlpha",       "(JJ[I)Landroid/graphics/Bitmap;",
        (void*)Bitmap_extractAlpha },
    {   "nativeGenerationId",       "(J)I", (void*)Bitmap_getGenerationId },
    {   "nativeGetPixel",           "(JII)I", (void*)Bitmap_getPixel },
    {   "nativeGetPixels",          "(J[IIIIIII)V", (void*)Bitmap_getPixels },
    {   "nativeSetPixel",           "(JIII)V", (void*)Bitmap_setPixel },
    {   "nativeSetPixels",          "(J[IIIIIII)V", (void*)Bitmap_setPixels },
    {   "nativeCopyPixelsToBuffer", "(JLjava/nio/Buffer;)V",
                                            (void*)Bitmap_copyPixelsToBuffer },
    {   "nativeCopyPixelsFromBuffer", "(JLjava/nio/Buffer;)V",
                                            (void*)Bitmap_copyPixelsFromBuffer },
    {   "nativeSameAs",             "(JJ)Z", (void*)Bitmap_sameAs },
    {   "nativePrepareToDraw",      "(J)V", (void*)Bitmap_prepareToDraw },
    {   "nativeGetAllocationByteCount", "(J)I", (void*)Bitmap_getAllocationByteCount },
    {   "nativeCopyPreserveInternalConfig", "(J)Landroid/graphics/Bitmap;",
        (void*)Bitmap_copyPreserveInternalConfig },
    {   "nativeCreateHardwareBitmap", "(Landroid/graphics/GraphicBuffer;)Landroid/graphics/Bitmap;",
        (void*) Bitmap_createHardwareBitmap },
    {   "nativeCreateGraphicBufferHandle", "(J)Landroid/graphics/GraphicBuffer;",
        (void*) Bitmap_createGraphicBufferHandle },
    {   "nativeGetColorSpace",      "(J[F[F)Z", (void*)Bitmap_getColorSpace },
    {   "nativeIsSRGB",             "(J)Z", (void*)Bitmap_isSRGB },
    {   "nativeIsSRGBLinear",       "(J)Z", (void*)Bitmap_isSRGBLinear},
    {   "nativeCopyColorSpace",     "(JJ)V",
        (void*)Bitmap_copyColorSpace },
};

int register_android_graphics_Bitmap(JNIEnv* env)
{
    gBitmap_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Bitmap"));
    gBitmap_nativePtr = GetFieldIDOrDie(env, gBitmap_class, "mNativePtr", "J");
    gBitmap_constructorMethodID = GetMethodIDOrDie(env, gBitmap_class, "<init>", "(JIIIZZ[BLandroid/graphics/NinePatch$InsetStruct;)V");
    gBitmap_reinitMethodID = GetMethodIDOrDie(env, gBitmap_class, "reinit", "(IIZ)V");
    gBitmap_getAllocationByteCountMethodID = GetMethodIDOrDie(env, gBitmap_class, "getAllocationByteCount", "()I");
    return android::RegisterMethodsOrDie(env, "android/graphics/Bitmap", gBitmapMethods,
                                         NELEM(gBitmapMethods));
}
