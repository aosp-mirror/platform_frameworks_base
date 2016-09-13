#define LOG_TAG "Bitmap"
#include "Bitmap.h"

#include "Paint.h"
#include "SkBitmap.h"
#include "SkPixelRef.h"
#include "SkImageEncoder.h"
#include "SkImageInfo.h"
#include "SkColorPriv.h"
#include "GraphicsJNI.h"
#include "SkDither.h"
#include "SkUnPreMultiply.h"
#include "SkStream.h"

#include <binder/Parcel.h>
#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "android_nio_utils.h"
#include "CreateJavaOutputStreamAdaptor.h"
#include <Caches.h>

#include "core_jni_helpers.h"

#include <jni.h>
#include <memory>
#include <string>
#include <sys/mman.h>
#include <cutils/ashmem.h>

#define DEBUG_PARCEL 0
#define ASHMEM_BITMAP_MIN_SIZE (128 * (1 << 10))

namespace android {

class WrappedPixelRef : public SkPixelRef {
public:
    WrappedPixelRef(Bitmap* wrapper, void* storage,
            const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable)
            : SkPixelRef(info)
            , mBitmap(*wrapper)
            , mStorage(storage) {
        reconfigure(info, rowBytes, ctable);
    }

    ~WrappedPixelRef() {
        // Tell SkRefCnt that everything is as it expects by forcing
        // the refcnt to 1
        internal_dispose_restore_refcnt_to_1();
        SkSafeUnref(mColorTable);
    }

    void reconfigure(const SkImageInfo& newInfo, size_t rowBytes, SkColorTable* ctable) {
        if (kIndex_8_SkColorType != newInfo.colorType()) {
            ctable = nullptr;
        }
        mRowBytes = rowBytes;
        if (mColorTable != ctable) {
            SkSafeUnref(mColorTable);
            mColorTable = ctable;
            SkSafeRef(mColorTable);
        }

        // Need to validate the alpha type to filter against the color type
        // to prevent things like a non-opaque RGB565 bitmap
        SkAlphaType alphaType;
        LOG_ALWAYS_FATAL_IF(!SkColorTypeValidateAlphaType(
                newInfo.colorType(), newInfo.alphaType(), &alphaType),
                "Failed to validate alpha type!");

        // Dirty hack is dirty
        // TODO: Figure something out here, Skia's current design makes this
        // really hard to work with. Skia really, really wants immutable objects,
        // but with the nested-ref-count hackery going on that's just not
        // feasible without going insane trying to figure it out
        SkImageInfo* myInfo = const_cast<SkImageInfo*>(&this->info());
        *myInfo = newInfo;
        changeAlphaType(alphaType);

        // Docs say to only call this in the ctor, but we're going to call
        // it anyway even if this isn't always the ctor.
        // TODO: Fix this too as part of the above TODO
        setPreLocked(mStorage, mRowBytes, mColorTable);
    }

    // Can't mark as override since SkPixelRef::rowBytes isn't virtual
    // but that's OK since we just want BitmapWrapper to be able to rely
    // on calling rowBytes() on an unlocked pixelref, which it will be
    // doing on a WrappedPixelRef type, not a SkPixelRef, so static
    // dispatching will do what we want.
    size_t rowBytes() const { return mRowBytes; }
    SkColorTable* colorTable() const { return mColorTable; }

    bool hasHardwareMipMap() const {
        return mHasHardwareMipMap;
    }

    void setHasHardwareMipMap(bool hasMipMap) {
        mHasHardwareMipMap = hasMipMap;
    }

protected:
    virtual bool onNewLockPixels(LockRec* rec) override {
        rec->fPixels = mStorage;
        rec->fRowBytes = mRowBytes;
        rec->fColorTable = mColorTable;
        return true;
    }

    virtual void onUnlockPixels() override {
        // nothing
    }

    virtual size_t getAllocatedSizeInBytes() const override {
        return info().getSafeSize(mRowBytes);
    }

private:
    Bitmap& mBitmap;
    void* mStorage;
    size_t mRowBytes = 0;
    SkColorTable* mColorTable = nullptr;
    bool mHasHardwareMipMap = false;

    virtual void internal_dispose() const override {
        mBitmap.onStrongRefDestroyed();
    }
};

Bitmap::Bitmap(JNIEnv* env, jbyteArray storageObj, void* address,
            const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable)
        : mPixelStorageType(PixelStorageType::Java) {
    env->GetJavaVM(&mPixelStorage.java.jvm);
    mPixelStorage.java.jweakRef = env->NewWeakGlobalRef(storageObj);
    mPixelStorage.java.jstrongRef = nullptr;
    mPixelRef.reset(new WrappedPixelRef(this, address, info, rowBytes, ctable));
    // Note: this will trigger a call to onStrongRefDestroyed(), but
    // we want the pixel ref to have a ref count of 0 at this point
    mPixelRef->unref();
}

Bitmap::Bitmap(void* address, void* context, FreeFunc freeFunc,
            const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable)
        : mPixelStorageType(PixelStorageType::External) {
    mPixelStorage.external.address = address;
    mPixelStorage.external.context = context;
    mPixelStorage.external.freeFunc = freeFunc;
    mPixelRef.reset(new WrappedPixelRef(this, address, info, rowBytes, ctable));
    // Note: this will trigger a call to onStrongRefDestroyed(), but
    // we want the pixel ref to have a ref count of 0 at this point
    mPixelRef->unref();
}

Bitmap::Bitmap(void* address, int fd, size_t mappedSize,
            const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable)
        : mPixelStorageType(PixelStorageType::Ashmem) {
    mPixelStorage.ashmem.address = address;
    mPixelStorage.ashmem.fd = fd;
    mPixelStorage.ashmem.size = mappedSize;
    mPixelRef.reset(new WrappedPixelRef(this, address, info, rowBytes, ctable));
    // Note: this will trigger a call to onStrongRefDestroyed(), but
    // we want the pixel ref to have a ref count of 0 at this point
    mPixelRef->unref();
}
Bitmap::~Bitmap() {
    doFreePixels();
}

void Bitmap::freePixels() {
    AutoMutex _lock(mLock);
    if (mPinnedRefCount == 0) {
        doFreePixels();
        mPixelStorageType = PixelStorageType::Invalid;
    }
}

void Bitmap::doFreePixels() {
    switch (mPixelStorageType) {
    case PixelStorageType::Invalid:
        // already free'd, nothing to do
        break;
    case PixelStorageType::External:
        mPixelStorage.external.freeFunc(mPixelStorage.external.address,
                mPixelStorage.external.context);
        break;
    case PixelStorageType::Ashmem:
        munmap(mPixelStorage.ashmem.address, mPixelStorage.ashmem.size);
        close(mPixelStorage.ashmem.fd);
        break;
    case PixelStorageType::Java:
        JNIEnv* env = jniEnv();
        LOG_ALWAYS_FATAL_IF(mPixelStorage.java.jstrongRef,
                "Deleting a bitmap wrapper while there are outstanding strong "
                "references! mPinnedRefCount = %d", mPinnedRefCount);
        env->DeleteWeakGlobalRef(mPixelStorage.java.jweakRef);
        break;
    }

    if (android::uirenderer::Caches::hasInstance()) {
        android::uirenderer::Caches::getInstance().textureCache.releaseTexture(
                mPixelRef->getStableID());
    }
}

bool Bitmap::hasHardwareMipMap() {
    return mPixelRef->hasHardwareMipMap();
}

void Bitmap::setHasHardwareMipMap(bool hasMipMap) {
    mPixelRef->setHasHardwareMipMap(hasMipMap);
}

int Bitmap::getAshmemFd() const {
    switch (mPixelStorageType) {
    case PixelStorageType::Ashmem:
        return mPixelStorage.ashmem.fd;
    default:
        return -1;
    }
}

const SkImageInfo& Bitmap::info() const {
    return mPixelRef->info();
}

size_t Bitmap::rowBytes() const {
    return mPixelRef->rowBytes();
}

SkPixelRef* Bitmap::peekAtPixelRef() const {
    assertValid();
    return mPixelRef.get();
}

SkPixelRef* Bitmap::refPixelRef() {
    assertValid();
    android::AutoMutex _lock(mLock);
    return refPixelRefLocked();
}

SkPixelRef* Bitmap::refPixelRefLocked() {
    mPixelRef->ref();
    if (mPixelRef->unique()) {
        // We just restored this from 0, pin the pixels and inc the strong count
        // Note that there *might be* an incoming onStrongRefDestroyed from whatever
        // last unref'd
        pinPixelsLocked();
        mPinnedRefCount++;
    }
    return mPixelRef.get();
}

void Bitmap::reconfigure(const SkImageInfo& info, size_t rowBytes,
        SkColorTable* ctable) {
    {
        android::AutoMutex _lock(mLock);
        if (mPinnedRefCount) {
            ALOGW("Called reconfigure on a bitmap that is in use! This may"
                    " cause graphical corruption!");
        }
    }
    mPixelRef->reconfigure(info, rowBytes, ctable);
}

void Bitmap::reconfigure(const SkImageInfo& info) {
    reconfigure(info, info.minRowBytes(), nullptr);
}

void Bitmap::setAlphaType(SkAlphaType alphaType) {
    if (!SkColorTypeValidateAlphaType(info().colorType(), alphaType, &alphaType)) {
        return;
    }

    mPixelRef->changeAlphaType(alphaType);
}

void Bitmap::detachFromJava() {
    bool disposeSelf;
    {
        android::AutoMutex _lock(mLock);
        mAttachedToJava = false;
        disposeSelf = shouldDisposeSelfLocked();
    }
    if (disposeSelf) {
        delete this;
    }
}

bool Bitmap::shouldDisposeSelfLocked() {
    return mPinnedRefCount == 0 && !mAttachedToJava;
}

JNIEnv* Bitmap::jniEnv() {
    JNIEnv* env;
    auto success = mPixelStorage.java.jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    LOG_ALWAYS_FATAL_IF(success != JNI_OK,
        "Failed to get JNIEnv* from JVM: %p", mPixelStorage.java.jvm);
    return env;
}

void Bitmap::onStrongRefDestroyed() {
    bool disposeSelf = false;
    {
        android::AutoMutex _lock(mLock);
        if (mPinnedRefCount > 0) {
            mPinnedRefCount--;
            if (mPinnedRefCount == 0) {
                unpinPixelsLocked();
                disposeSelf = shouldDisposeSelfLocked();
            }
        }
    }
    if (disposeSelf) {
        delete this;
    }
}

void Bitmap::pinPixelsLocked() {
    switch (mPixelStorageType) {
    case PixelStorageType::Invalid:
        LOG_ALWAYS_FATAL("Cannot pin invalid pixels!");
        break;
    case PixelStorageType::External:
    case PixelStorageType::Ashmem:
        // Nothing to do
        break;
    case PixelStorageType::Java: {
        JNIEnv* env = jniEnv();
        if (!mPixelStorage.java.jstrongRef) {
            mPixelStorage.java.jstrongRef = reinterpret_cast<jbyteArray>(
                    env->NewGlobalRef(mPixelStorage.java.jweakRef));
            if (!mPixelStorage.java.jstrongRef) {
                LOG_ALWAYS_FATAL("Failed to acquire strong reference to pixels");
            }
        }
        break;
    }
    }
}

void Bitmap::unpinPixelsLocked() {
    switch (mPixelStorageType) {
    case PixelStorageType::Invalid:
        LOG_ALWAYS_FATAL("Cannot unpin invalid pixels!");
        break;
    case PixelStorageType::External:
    case PixelStorageType::Ashmem:
        // Don't need to do anything
        break;
    case PixelStorageType::Java: {
        JNIEnv* env = jniEnv();
        if (mPixelStorage.java.jstrongRef) {
            env->DeleteGlobalRef(mPixelStorage.java.jstrongRef);
            mPixelStorage.java.jstrongRef = nullptr;
        }
        break;
    }
    }
}

void Bitmap::getSkBitmap(SkBitmap* outBitmap) {
    assertValid();
    android::AutoMutex _lock(mLock);
    // Safe because mPixelRef is a WrappedPixelRef type, otherwise rowBytes()
    // would require locking the pixels first.
    outBitmap->setInfo(mPixelRef->info(), mPixelRef->rowBytes());
    outBitmap->setPixelRef(refPixelRefLocked())->unref();
    outBitmap->setHasHardwareMipMap(hasHardwareMipMap());
}

void Bitmap::assertValid() const {
    LOG_ALWAYS_FATAL_IF(mPixelStorageType == PixelStorageType::Invalid,
            "Error, cannot access an invalid/free'd bitmap here!");
}

} // namespace android

using namespace android;

// Convenience class that does not take a global ref on the pixels, relying
// on the caller already having a local JNI ref
class LocalScopedBitmap {
public:
    LocalScopedBitmap(jlong bitmapHandle)
            : mBitmap(reinterpret_cast<Bitmap*>(bitmapHandle)) {}

    Bitmap* operator->() {
        return mBitmap;
    }

    void* pixels() {
        return mBitmap->peekAtPixelRef()->pixels();
    }

    bool valid() {
        return mBitmap && mBitmap->valid();
    }

private:
    Bitmap* mBitmap;
};

///////////////////////////////////////////////////////////////////////////////
// Conversions to/from SkColor, for get/setPixels, and the create method, which
// is basically like setPixels

typedef void (*FromColorProc)(void* dst, const SkColor src[], int width,
                              int x, int y);

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
        default:
            break;
    }
    return NULL;
}

bool GraphicsJNI::SetPixels(JNIEnv* env, jintArray srcColors, int srcOffset, int srcStride,
        int x, int y, int width, int height, const SkBitmap& dstBitmap) {
    SkAutoLockPixels alp(dstBitmap);
    void* dst = dstBitmap.getPixels();
    FromColorProc proc = ChooseFromColorProc(dstBitmap);

    if (NULL == dst || NULL == proc) {
        return false;
    }

    const jint* array = env->GetIntArrayElements(srcColors, NULL);
    const SkColor* src = (const SkColor*)array + srcOffset;

    // reset to to actual choice from caller
    dst = dstBitmap.getAddr(x, y);
    // now copy/convert each scanline
    for (int y = 0; y < height; y++) {
        proc(dst, src, width, x, y);
        src += srcStride;
        dst = (char*)dst + dstBitmap.rowBytes();
    }

    dstBitmap.notifyPixelsChanged();

    env->ReleaseIntArrayElements(srcColors, const_cast<jint*>(array),
                                 JNI_ABORT);
    return true;
}

//////////////////// ToColor procs

typedef void (*ToColorProc)(SkColor dst[], const void* src, int width,
                            SkColorTable*);

static void ToColor_S32_Alpha(SkColor dst[], const void* src, int width,
                              SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor* s = (const SkPMColor*)src;
    do {
        *dst++ = SkUnPreMultiply::PMColorToColor(*s++);
    } while (--width != 0);
}

static void ToColor_S32_Raw(SkColor dst[], const void* src, int width,
                              SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor* s = (const SkPMColor*)src;
    do {
        SkPMColor c = *s++;
        *dst++ = SkColorSetARGB(SkGetPackedA32(c), SkGetPackedR32(c),
                                SkGetPackedG32(c), SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S32_Opaque(SkColor dst[], const void* src, int width,
                               SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor* s = (const SkPMColor*)src;
    do {
        SkPMColor c = *s++;
        *dst++ = SkColorSetRGB(SkGetPackedR32(c), SkGetPackedG32(c),
                               SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S4444_Alpha(SkColor dst[], const void* src, int width,
                                SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor16* s = (const SkPMColor16*)src;
    do {
        *dst++ = SkUnPreMultiply::PMColorToColor(SkPixel4444ToPixel32(*s++));
    } while (--width != 0);
}

static void ToColor_S4444_Raw(SkColor dst[], const void* src, int width,
                                SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor16* s = (const SkPMColor16*)src;
    do {
        SkPMColor c = SkPixel4444ToPixel32(*s++);
        *dst++ = SkColorSetARGB(SkGetPackedA32(c), SkGetPackedR32(c),
                                SkGetPackedG32(c), SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S4444_Opaque(SkColor dst[], const void* src, int width,
                                 SkColorTable*) {
    SkASSERT(width > 0);
    const SkPMColor16* s = (const SkPMColor16*)src;
    do {
        SkPMColor c = SkPixel4444ToPixel32(*s++);
        *dst++ = SkColorSetRGB(SkGetPackedR32(c), SkGetPackedG32(c),
                               SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_S565(SkColor dst[], const void* src, int width,
                         SkColorTable*) {
    SkASSERT(width > 0);
    const uint16_t* s = (const uint16_t*)src;
    do {
        uint16_t c = *s++;
        *dst++ =  SkColorSetRGB(SkPacked16ToR32(c), SkPacked16ToG32(c),
                                SkPacked16ToB32(c));
    } while (--width != 0);
}

static void ToColor_SI8_Alpha(SkColor dst[], const void* src, int width,
                              SkColorTable* ctable) {
    SkASSERT(width > 0);
    const uint8_t* s = (const uint8_t*)src;
    const SkPMColor* colors = ctable->readColors();
    do {
        *dst++ = SkUnPreMultiply::PMColorToColor(colors[*s++]);
    } while (--width != 0);
}

static void ToColor_SI8_Raw(SkColor dst[], const void* src, int width,
                              SkColorTable* ctable) {
    SkASSERT(width > 0);
    const uint8_t* s = (const uint8_t*)src;
    const SkPMColor* colors = ctable->readColors();
    do {
        SkPMColor c = colors[*s++];
        *dst++ = SkColorSetARGB(SkGetPackedA32(c), SkGetPackedR32(c),
                                SkGetPackedG32(c), SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_SI8_Opaque(SkColor dst[], const void* src, int width,
                               SkColorTable* ctable) {
    SkASSERT(width > 0);
    const uint8_t* s = (const uint8_t*)src;
    const SkPMColor* colors = ctable->readColors();
    do {
        SkPMColor c = colors[*s++];
        *dst++ = SkColorSetRGB(SkGetPackedR32(c), SkGetPackedG32(c),
                               SkGetPackedB32(c));
    } while (--width != 0);
}

static void ToColor_SA8(SkColor dst[], const void* src, int width, SkColorTable*) {
    SkASSERT(width > 0);
    const uint8_t* s = (const uint8_t*)src;
    do {
        uint8_t c = *s++;
        *dst++ = SkColorSetARGB(c, c, c, c);
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
        case kIndex_8_SkColorType:
            if (src.getColorTable() == NULL) {
                return NULL;
            }
            switch (src.alphaType()) {
                case kOpaque_SkAlphaType:
                    return ToColor_SI8_Opaque;
                case kPremul_SkAlphaType:
                    return ToColor_SI8_Alpha;
                case kUnpremul_SkAlphaType:
                    return ToColor_SI8_Raw;
                default:
                    return NULL;
            }
        case kAlpha_8_SkColorType:
            return ToColor_SA8;
        default:
            break;
    }
    return NULL;
}

///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////

static int getPremulBitmapCreateFlags(bool isMutable) {
    int flags = GraphicsJNI::kBitmapCreateFlag_Premultiplied;
    if (isMutable) flags |= GraphicsJNI::kBitmapCreateFlag_Mutable;
    return flags;
}

static jobject Bitmap_creator(JNIEnv* env, jobject, jintArray jColors,
                              jint offset, jint stride, jint width, jint height,
                              jint configHandle, jboolean isMutable) {
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
    bitmap.setInfo(SkImageInfo::Make(width, height, colorType, kPremul_SkAlphaType));

    Bitmap* nativeBitmap = GraphicsJNI::allocateJavaPixelRef(env, &bitmap, NULL);
    if (!nativeBitmap) {
        return NULL;
    }

    if (jColors != NULL) {
        GraphicsJNI::SetPixels(env, jColors, offset, stride,
                0, 0, width, height, bitmap);
    }

    return GraphicsJNI::createBitmap(env, nativeBitmap,
            getPremulBitmapCreateFlags(isMutable));
}

static jobject Bitmap_copy(JNIEnv* env, jobject, jlong srcHandle,
                           jint dstConfigHandle, jboolean isMutable) {
    SkBitmap src;
    reinterpret_cast<Bitmap*>(srcHandle)->getSkBitmap(&src);
    SkColorType dstCT = GraphicsJNI::legacyBitmapConfigToColorType(dstConfigHandle);
    SkBitmap            result;
    JavaPixelAllocator  allocator(env);

    if (!src.copyTo(&result, dstCT, &allocator)) {
        return NULL;
    }
    Bitmap* bitmap = allocator.getStorageObjAndReset();
    return GraphicsJNI::createBitmap(env, bitmap,
            getPremulBitmapCreateFlags(isMutable));
}

static jobject Bitmap_copyAshmem(JNIEnv* env, jobject, jlong srcHandle) {
    SkBitmap src;
    reinterpret_cast<Bitmap*>(srcHandle)->getSkBitmap(&src);
    SkBitmap result;

    AshmemPixelAllocator allocator(env);
    if (!src.copyTo(&result, &allocator)) {
        return NULL;
    }
    Bitmap* bitmap = allocator.getStorageObjAndReset();
    bitmap->peekAtPixelRef()->setImmutable();
    jobject ret = GraphicsJNI::createBitmap(env, bitmap, getPremulBitmapCreateFlags(false));
    return ret;
}

static void Bitmap_destructor(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    bitmap->detachFromJava();
}

static jboolean Bitmap_recycle(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    bitmap->freePixels();
    return JNI_TRUE;
}

static void Bitmap_reconfigure(JNIEnv* env, jobject clazz, jlong bitmapHandle,
        jint width, jint height, jint configHandle, jint allocSize,
        jboolean requestPremul) {
    LocalScopedBitmap bitmap(bitmapHandle);
    SkColorType colorType = GraphicsJNI::legacyBitmapConfigToColorType(configHandle);

    // ARGB_4444 is a deprecated format, convert automatically to 8888
    if (colorType == kARGB_4444_SkColorType) {
        colorType = kN32_SkColorType;
    }

    if (width * height * SkColorTypeBytesPerPixel(colorType) > allocSize) {
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
    bitmap->reconfigure(SkImageInfo::Make(width, height, colorType, alphaType));
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

    LocalScopedBitmap bitmap(bitmapHandle);
    SkImageEncoder::Type fm;

    switch (format) {
    case kJPEG_JavaEncodeFormat:
        fm = SkImageEncoder::kJPEG_Type;
        break;
    case kPNG_JavaEncodeFormat:
        fm = SkImageEncoder::kPNG_Type;
        break;
    case kWEBP_JavaEncodeFormat:
        fm = SkImageEncoder::kWEBP_Type;
        break;
    default:
        return JNI_FALSE;
    }

    if (!bitmap.valid()) {
        return JNI_FALSE;
    }

    bool success = false;

    std::unique_ptr<SkWStream> strm(CreateJavaOutputStreamAdaptor(env, jstream, jstorage));
    if (!strm.get()) {
        return JNI_FALSE;
    }

    std::unique_ptr<SkImageEncoder> encoder(SkImageEncoder::Create(fm));
    if (encoder.get()) {
        SkBitmap skbitmap;
        bitmap->getSkBitmap(&skbitmap);
        success = encoder->encodeStream(strm.get(), skbitmap, quality);
    }
    return success ? JNI_TRUE : JNI_FALSE;
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
    return GraphicsJNI::colorTypeToLegacyBitmapConfig(bitmap->info().colorType());
}

static jint Bitmap_getGenerationId(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    return static_cast<jint>(bitmap->peekAtPixelRef()->getGenerationID());
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

static jobject Bitmap_createFromParcel(JNIEnv* env, jobject, jobject parcel) {
    if (parcel == NULL) {
        SkDebugf("-------- unparcel parcel is NULL\n");
        return NULL;
    }

    android::Parcel* p = android::parcelForJavaObject(env, parcel);

    const bool        isMutable = p->readInt32() != 0;
    const SkColorType colorType = (SkColorType)p->readInt32();
    const SkAlphaType alphaType = (SkAlphaType)p->readInt32();
    const int         width = p->readInt32();
    const int         height = p->readInt32();
    const int         rowBytes = p->readInt32();
    const int         density = p->readInt32();

    if (kN32_SkColorType != colorType &&
            kRGB_565_SkColorType != colorType &&
            kARGB_4444_SkColorType != colorType &&
            kIndex_8_SkColorType != colorType &&
            kAlpha_8_SkColorType != colorType) {
        SkDebugf("Bitmap_createFromParcel unknown colortype: %d\n", colorType);
        return NULL;
    }

    std::unique_ptr<SkBitmap> bitmap(new SkBitmap);

    if (!bitmap->setInfo(SkImageInfo::Make(width, height, colorType, alphaType), rowBytes)) {
        return NULL;
    }

    SkColorTable* ctable = NULL;
    if (colorType == kIndex_8_SkColorType) {
        int count = p->readInt32();
        if (count < 0 || count > 256) {
            // The data is corrupt, since SkColorTable enforces a value between 0 and 256,
            // inclusive.
            return NULL;
        }
        if (count > 0) {
            size_t size = count * sizeof(SkPMColor);
            const SkPMColor* src = (const SkPMColor*)p->readInplace(size);
            if (src == NULL) {
                return NULL;
            }
            ctable = new SkColorTable(src, count);
        }
    }

    // Read the bitmap blob.
    size_t size = bitmap->getSize();
    android::Parcel::ReadableBlob blob;
    android::status_t status = p->readBlob(size, &blob);
    if (status) {
        SkSafeUnref(ctable);
        doThrowRE(env, "Could not read bitmap blob.");
        return NULL;
    }

    // Map the bitmap in place from the ashmem region if possible otherwise copy.
    Bitmap* nativeBitmap;
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
            SkSafeUnref(ctable);
            doThrowRE(env, "Could not allocate dup blob fd.");
            return NULL;
        }

        // Map the pixels in place and take ownership of the ashmem region.
        nativeBitmap = GraphicsJNI::mapAshmemPixelRef(env, bitmap.get(),
                ctable, dupFd, const_cast<void*>(blob.data()), size, !isMutable);
        SkSafeUnref(ctable);
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
        nativeBitmap = GraphicsJNI::allocateJavaPixelRef(env, bitmap.get(), ctable);
        SkSafeUnref(ctable);
        if (!nativeBitmap) {
            blob.release();
            doThrowRE(env, "Could not allocate java pixel ref.");
            return NULL;
        }
        bitmap->lockPixels();
        memcpy(bitmap->getPixels(), blob.data(), size);
        bitmap->unlockPixels();

        // Release the blob handle.
        blob.release();
    }

    return GraphicsJNI::createBitmap(env, nativeBitmap,
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

    android::Bitmap* androidBitmap = reinterpret_cast<Bitmap*>(bitmapHandle);
    androidBitmap->getSkBitmap(&bitmap);

    p->writeInt32(isMutable);
    p->writeInt32(bitmap.colorType());
    p->writeInt32(bitmap.alphaType());
    p->writeInt32(bitmap.width());
    p->writeInt32(bitmap.height());
    p->writeInt32(bitmap.rowBytes());
    p->writeInt32(density);

    if (bitmap.colorType() == kIndex_8_SkColorType) {
        SkColorTable* ctable = bitmap.getColorTable();
        if (ctable != NULL) {
            int count = ctable->count();
            p->writeInt32(count);
            memcpy(p->writeInplace(count * sizeof(SkPMColor)),
                   ctable->readColors(), count * sizeof(SkPMColor));
        } else {
            p->writeInt32(0);   // indicate no ctable
        }
    }

    // Transfer the underlying ashmem region if we have one and it's immutable.
    android::status_t status;
    int fd = androidBitmap->getAshmemFd();
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

    size_t size = bitmap.getSize();
    android::Parcel::WritableBlob blob;
    status = p->writeBlob(size, mutableCopy, &blob);
    if (status) {
        doThrowRE(env, "Could not copy bitmap to parcel blob.");
        return JNI_FALSE;
    }

    bitmap.lockPixels();
    const void* pSrc =  bitmap.getPixels();
    if (pSrc == NULL) {
        memset(blob.data(), 0, size);
    } else {
        memcpy(blob.data(), pSrc, size);
    }
    bitmap.unlockPixels();

    blob.release();
    return JNI_TRUE;
}

static jobject Bitmap_extractAlpha(JNIEnv* env, jobject clazz,
                                   jlong srcHandle, jlong paintHandle,
                                   jintArray offsetXY) {
    SkBitmap src;
    reinterpret_cast<Bitmap*>(srcHandle)->getSkBitmap(&src);
    const android::Paint* paint = reinterpret_cast<android::Paint*>(paintHandle);
    SkIPoint  offset;
    SkBitmap dst;
    JavaPixelAllocator allocator(env);

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

    return GraphicsJNI::createBitmap(env, allocator.getStorageObjAndReset(),
            getPremulBitmapCreateFlags(true));
}

///////////////////////////////////////////////////////////////////////////////

static jint Bitmap_getPixel(JNIEnv* env, jobject, jlong bitmapHandle,
        jint x, jint y) {
    SkBitmap bitmap;
    reinterpret_cast<Bitmap*>(bitmapHandle)->getSkBitmap(&bitmap);
    SkAutoLockPixels alp(bitmap);

    ToColorProc proc = ChooseToColorProc(bitmap);
    if (NULL == proc) {
        return 0;
    }
    const void* src = bitmap.getAddr(x, y);
    if (NULL == src) {
        return 0;
    }

    SkColor dst[1];
    proc(dst, src, 1, bitmap.getColorTable());
    return static_cast<jint>(dst[0]);
}

static void Bitmap_getPixels(JNIEnv* env, jobject, jlong bitmapHandle,
        jintArray pixelArray, jint offset, jint stride,
        jint x, jint y, jint width, jint height) {
    SkBitmap bitmap;
    reinterpret_cast<Bitmap*>(bitmapHandle)->getSkBitmap(&bitmap);
    SkAutoLockPixels alp(bitmap);

    ToColorProc proc = ChooseToColorProc(bitmap);
    if (NULL == proc) {
        return;
    }
    const void* src = bitmap.getAddr(x, y);
    if (NULL == src) {
        return;
    }

    SkColorTable* ctable = bitmap.getColorTable();
    jint* dst = env->GetIntArrayElements(pixelArray, NULL);
    SkColor* d = (SkColor*)dst + offset;
    while (--height >= 0) {
        proc(d, src, width, ctable);
        d += stride;
        src = (void*)((const char*)src + bitmap.rowBytes());
    }
    env->ReleaseIntArrayElements(pixelArray, dst, 0);
}

///////////////////////////////////////////////////////////////////////////////

static void Bitmap_setPixel(JNIEnv* env, jobject, jlong bitmapHandle,
        jint x, jint y, jint colorHandle) {
    SkBitmap bitmap;
    reinterpret_cast<Bitmap*>(bitmapHandle)->getSkBitmap(&bitmap);
    SkColor color = static_cast<SkColor>(colorHandle);
    SkAutoLockPixels alp(bitmap);
    if (NULL == bitmap.getPixels()) {
        return;
    }

    FromColorProc proc = ChooseFromColorProc(bitmap);
    if (NULL == proc) {
        return;
    }

    proc(bitmap.getAddr(x, y), &color, 1, x, y);
    bitmap.notifyPixelsChanged();
}

static void Bitmap_setPixels(JNIEnv* env, jobject, jlong bitmapHandle,
        jintArray pixelArray, jint offset, jint stride,
        jint x, jint y, jint width, jint height) {
    SkBitmap bitmap;
    reinterpret_cast<Bitmap*>(bitmapHandle)->getSkBitmap(&bitmap);
    GraphicsJNI::SetPixels(env, pixelArray, offset, stride,
            x, y, width, height, bitmap);
}

static void Bitmap_copyPixelsToBuffer(JNIEnv* env, jobject,
                                      jlong bitmapHandle, jobject jbuffer) {
    SkBitmap bitmap;
    reinterpret_cast<Bitmap*>(bitmapHandle)->getSkBitmap(&bitmap);
    SkAutoLockPixels alp(bitmap);
    const void* src = bitmap.getPixels();

    if (NULL != src) {
        android::AutoBufferPointer abp(env, jbuffer, JNI_TRUE);

        // the java side has already checked that buffer is large enough
        memcpy(abp.pointer(), src, bitmap.getSize());
    }
}

static void Bitmap_copyPixelsFromBuffer(JNIEnv* env, jobject,
                                        jlong bitmapHandle, jobject jbuffer) {
    SkBitmap bitmap;
    reinterpret_cast<Bitmap*>(bitmapHandle)->getSkBitmap(&bitmap);
    SkAutoLockPixels alp(bitmap);
    void* dst = bitmap.getPixels();

    if (NULL != dst) {
        android::AutoBufferPointer abp(env, jbuffer, JNI_FALSE);
        // the java side has already checked that buffer is large enough
        memcpy(dst, abp.pointer(), bitmap.getSize());
        bitmap.notifyPixelsChanged();
    }
}

static jboolean Bitmap_sameAs(JNIEnv* env, jobject, jlong bm0Handle,
                              jlong bm1Handle) {
    SkBitmap bm0;
    SkBitmap bm1;
    reinterpret_cast<Bitmap*>(bm0Handle)->getSkBitmap(&bm0);
    reinterpret_cast<Bitmap*>(bm1Handle)->getSkBitmap(&bm1);
    if (bm0.width() != bm1.width() ||
        bm0.height() != bm1.height() ||
        bm0.colorType() != bm1.colorType()) {
        return JNI_FALSE;
    }

    SkAutoLockPixels alp0(bm0);
    SkAutoLockPixels alp1(bm1);

    // if we can't load the pixels, return false
    if (NULL == bm0.getPixels() || NULL == bm1.getPixels()) {
        return JNI_FALSE;
    }

    if (bm0.colorType() == kIndex_8_SkColorType) {
        SkColorTable* ct0 = bm0.getColorTable();
        SkColorTable* ct1 = bm1.getColorTable();
        if (NULL == ct0 || NULL == ct1) {
            return JNI_FALSE;
        }
        if (ct0->count() != ct1->count()) {
            return JNI_FALSE;
        }

        const size_t size = ct0->count() * sizeof(SkPMColor);
        if (memcmp(ct0->readColors(), ct1->readColors(), size) != 0) {
            return JNI_FALSE;
        }
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

static jlong Bitmap_refPixelRef(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    SkPixelRef* pixelRef = bitmap.valid() ? bitmap->peekAtPixelRef() : nullptr;
    SkSafeRef(pixelRef);
    return reinterpret_cast<jlong>(pixelRef);
}

///////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gBitmapMethods[] = {
    {   "nativeCreate",             "([IIIIIIZ)Landroid/graphics/Bitmap;",
        (void*)Bitmap_creator },
    {   "nativeCopy",               "(JIZ)Landroid/graphics/Bitmap;",
        (void*)Bitmap_copy },
    {   "nativeCopyAshmem",         "(J)Landroid/graphics/Bitmap;",
        (void*)Bitmap_copyAshmem },
    {   "nativeDestructor",         "(J)V", (void*)Bitmap_destructor },
    {   "nativeRecycle",            "(J)Z", (void*)Bitmap_recycle },
    {   "nativeReconfigure",        "(JIIIIZ)V", (void*)Bitmap_reconfigure },
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
    {   "nativeRefPixelRef",        "(J)J", (void*)Bitmap_refPixelRef },
};

int register_android_graphics_Bitmap(JNIEnv* env)
{
    return android::RegisterMethodsOrDie(env, "android/graphics/Bitmap", gBitmapMethods,
                                         NELEM(gBitmapMethods));
}
