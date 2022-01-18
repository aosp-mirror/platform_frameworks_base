#undef LOG_TAG
#define LOG_TAG "Bitmap"
#include "Bitmap.h"

#include "SkBitmap.h"
#include "SkCanvas.h"
#include "SkColor.h"
#include "SkColorSpace.h"
#include "SkPixelRef.h"
#include "SkImageEncoder.h"
#include "SkImageInfo.h"
#include "GraphicsJNI.h"
#include "SkStream.h"
#include "SkWebpEncoder.h"

#include "android_nio_utils.h"
#include "CreateJavaOutputStreamAdaptor.h"
#include <hwui/Paint.h>
#include <hwui/Bitmap.h>
#include <utils/Color.h>

#ifdef __ANDROID__ // Layoutlib does not support graphic buffer, parcel or render thread
#include <android-base/unique_fd.h>
#include <android/binder_parcel.h>
#include <android/binder_parcel_jni.h>
#include <android/binder_parcel_platform.h>
#include <android/binder_parcel_utils.h>
#include <private/android/AHardwareBufferHelpers.h>
#include <cutils/ashmem.h>
#include <dlfcn.h>
#include <renderthread/RenderProxy.h>
#include <sys/mman.h>
#endif

#include <inttypes.h>
#include <string.h>
#include <memory>
#include <string>

#define DEBUG_PARCEL 0

static jclass   gBitmap_class;
static jfieldID gBitmap_nativePtr;
static jmethodID gBitmap_constructorMethodID;
static jmethodID gBitmap_reinitMethodID;

namespace android {

class BitmapWrapper {
public:
    explicit BitmapWrapper(Bitmap* bitmap)
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
        return mBitmap != nullptr;
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

    void setColorSpace(sk_sp<SkColorSpace> colorSpace) {
        assertValid();
        mBitmap->setColorSpace(colorSpace);
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

jobject createBitmap(JNIEnv* env, Bitmap* bitmap,
        int bitmapCreateFlags, jbyteArray ninePatchChunk, jobject ninePatchInsets,
        int density) {
    bool isMutable = bitmapCreateFlags & kBitmapCreateFlag_Mutable;
    bool isPremultiplied = bitmapCreateFlags & kBitmapCreateFlag_Premultiplied;
    // The caller needs to have already set the alpha type properly, so the
    // native SkBitmap stays in sync with the Java Bitmap.
    assert_premultiplied(bitmap->info(), isPremultiplied);
    bool fromMalloc = bitmap->pixelStorageType() == PixelStorageType::Heap;
    BitmapWrapper* bitmapWrapper = new BitmapWrapper(bitmap);
    if (!isMutable) {
        bitmapWrapper->bitmap().setImmutable();
    }
    jobject obj = env->NewObject(gBitmap_class, gBitmap_constructorMethodID,
            reinterpret_cast<jlong>(bitmapWrapper), bitmap->width(), bitmap->height(), density,
            isPremultiplied, ninePatchChunk, ninePatchInsets, fromMalloc);

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

Bitmap& toBitmap(jlong bitmapHandle) {
    LocalScopedBitmap localBitmap(bitmapHandle);
    return localBitmap->bitmap();
}

} // namespace bitmap

} // namespace android

using namespace android;
using namespace android::bitmap;

Bitmap* GraphicsJNI::getNativeBitmap(JNIEnv* env, jobject bitmap) {
    SkASSERT(env);
    SkASSERT(bitmap);
    SkASSERT(env->IsInstanceOf(bitmap, gBitmap_class));
    jlong bitmapHandle = env->GetLongField(bitmap, gBitmap_nativePtr);
    LocalScopedBitmap localBitmap(bitmapHandle);
    return localBitmap.valid() ? &localBitmap->bitmap() : nullptr;
}

SkImageInfo GraphicsJNI::getBitmapInfo(JNIEnv* env, jobject bitmap, uint32_t* outRowBytes,
                                       bool* isHardware) {
    SkASSERT(env);
    SkASSERT(bitmap);
    SkASSERT(env->IsInstanceOf(bitmap, gBitmap_class));
    jlong bitmapHandle = env->GetLongField(bitmap, gBitmap_nativePtr);
    LocalScopedBitmap localBitmap(bitmapHandle);
    if (outRowBytes) {
        *outRowBytes = localBitmap->rowBytes();
    }
    if (isHardware) {
        *isHardware = localBitmap->isHardware();
    }
    return localBitmap->info();
}

bool GraphicsJNI::SetPixels(JNIEnv* env, jintArray srcColors, int srcOffset, int srcStride,
        int x, int y, int width, int height, SkBitmap* dstBitmap) {
    const jint* array = env->GetIntArrayElements(srcColors, NULL);
    const SkColor* src = (const SkColor*)array + srcOffset;

    auto sRGB = SkColorSpace::MakeSRGB();
    SkImageInfo srcInfo = SkImageInfo::Make(
            width, height, kBGRA_8888_SkColorType, kUnpremul_SkAlphaType, sRGB);
    SkPixmap srcPM(srcInfo, src, srcStride * 4);

    dstBitmap->writePixels(srcPM, x, y);

    env->ReleaseIntArrayElements(srcColors, const_cast<jint*>(array), JNI_ABORT);
    return true;
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
                              jlong colorSpacePtr) {
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

    sk_sp<SkColorSpace> colorSpace;
    if (colorType == kAlpha_8_SkColorType) {
        colorSpace = nullptr;
    } else {
        colorSpace = GraphicsJNI::getNativeColorSpace(colorSpacePtr);
    }

    SkBitmap bitmap;
    bitmap.setInfo(SkImageInfo::Make(width, height, colorType, kPremul_SkAlphaType,
                colorSpace));

    sk_sp<Bitmap> nativeBitmap = Bitmap::allocateHeapBitmap(&bitmap);
    if (!nativeBitmap) {
        ALOGE("OOM allocating Bitmap with dimensions %i x %i", width, height);
        doThrowOOME(env);
        return NULL;
    }

    if (jColors != NULL) {
        GraphicsJNI::SetPixels(env, jColors, offset, stride, 0, 0, width, height, &bitmap);
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
            dstInfo = dstInfo.makeAlphaType(kOpaque_SkAlphaType);
            break;
        case kAlpha_8_SkColorType:
            dstInfo = dstInfo.makeColorSpace(nullptr);
            break;
        default:
            break;
    }

    if (!dstInfo.colorSpace() && dstCT != kAlpha_8_SkColorType) {
        dstInfo = dstInfo.makeColorSpace(SkColorSpace::MakeSRGB());
    }

    if (!dst->setInfo(dstInfo)) {
        return false;
    }
    if (!dst->tryAllocPixels(alloc)) {
        return false;
    }

    SkPixmap dstPM;
    if (!dst->peekPixels(&dstPM)) {
        return false;
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

static void Bitmap_recycle(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmap(bitmapHandle);
    bitmap->freePixels();
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

static jboolean Bitmap_compress(JNIEnv* env, jobject clazz, jlong bitmapHandle,
                                jint format, jint quality,
                                jobject jstream, jbyteArray jstorage) {
    LocalScopedBitmap bitmap(bitmapHandle);
    if (!bitmap.valid()) {
        return JNI_FALSE;
    }

    std::unique_ptr<SkWStream> strm(CreateJavaOutputStreamAdaptor(env, jstream, jstorage));
    if (!strm.get()) {
        return JNI_FALSE;
    }

    auto fm = static_cast<Bitmap::JavaCompressFormat>(format);
    return bitmap->bitmap().compress(fm, quality, strm.get()) ? JNI_TRUE : JNI_FALSE;
}

static inline void bitmapErase(SkBitmap bitmap, const SkColor4f& color,
        const sk_sp<SkColorSpace>& colorSpace) {
    SkPaint p;
    p.setColor4f(color, colorSpace.get());
    p.setBlendMode(SkBlendMode::kSrc);
    SkCanvas canvas(bitmap);
    canvas.drawPaint(p);
}

static void Bitmap_erase(JNIEnv* env, jobject, jlong bitmapHandle, jint color) {
    LocalScopedBitmap bitmap(bitmapHandle);
    SkBitmap skBitmap;
    bitmap->getSkBitmap(&skBitmap);
    bitmapErase(skBitmap, SkColor4f::FromColor(color), SkColorSpace::MakeSRGB());
}

static void Bitmap_eraseLong(JNIEnv* env, jobject, jlong bitmapHandle,
        jlong colorSpaceHandle, jlong colorLong) {
    LocalScopedBitmap bitmap(bitmapHandle);
    SkBitmap skBitmap;
    bitmap->getSkBitmap(&skBitmap);

    SkColor4f color = GraphicsJNI::convertColorLong(colorLong);
    sk_sp<SkColorSpace> cs = GraphicsJNI::getNativeColorSpace(colorSpaceHandle);
    bitmapErase(skBitmap, color, cs);
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

// TODO: Move somewhere else
#ifdef __ANDROID__ // Layoutlib does not support parcel

class ScopedParcel {
public:
    explicit ScopedParcel(JNIEnv* env, jobject parcel) {
        mParcel = AParcel_fromJavaParcel(env, parcel);
    }

    ~ScopedParcel() { AParcel_delete(mParcel); }

    int32_t readInt32() {
        int32_t temp = 0;
        // TODO: This behavior-matches what android::Parcel does
        // but this should probably be better
        if (AParcel_readInt32(mParcel, &temp) != STATUS_OK) {
            temp = 0;
        }
        return temp;
    }

    uint32_t readUint32() {
        uint32_t temp = 0;
        // TODO: This behavior-matches what android::Parcel does
        // but this should probably be better
        if (AParcel_readUint32(mParcel, &temp) != STATUS_OK) {
            temp = 0;
        }
        return temp;
    }

    void writeInt32(int32_t value) { AParcel_writeInt32(mParcel, value); }

    void writeUint32(uint32_t value) { AParcel_writeUint32(mParcel, value); }

    bool allowFds() const { return AParcel_getAllowFds(mParcel); }

    std::optional<sk_sp<SkData>> readData() {
        struct Data {
            void* ptr = nullptr;
            size_t size = 0;
        } data;
        auto error = AParcel_readByteArray(mParcel, &data,
                                           [](void* arrayData, int32_t length,
                                              int8_t** outBuffer) -> bool {
                                               Data* data = reinterpret_cast<Data*>(arrayData);
                                               if (length > 0) {
                                                   data->ptr = sk_malloc_canfail(length);
                                                   if (!data->ptr) {
                                                       return false;
                                                   }
                                                   *outBuffer =
                                                           reinterpret_cast<int8_t*>(data->ptr);
                                                   data->size = length;
                                               }
                                               return true;
                                           });
        if (error != STATUS_OK || data.size <= 0) {
            sk_free(data.ptr);
            return std::nullopt;
        } else {
            return SkData::MakeFromMalloc(data.ptr, data.size);
        }
    }

    void writeData(const std::optional<sk_sp<SkData>>& optData) {
        if (optData) {
            const auto& data = *optData;
            AParcel_writeByteArray(mParcel, reinterpret_cast<const int8_t*>(data->data()),
                                   data->size());
        } else {
            AParcel_writeByteArray(mParcel, nullptr, -1);
        }
    }

    AParcel* get() { return mParcel; }

private:
    AParcel* mParcel;
};

enum class BlobType : int32_t {
    IN_PLACE,
    ASHMEM,
};

#define ON_ERROR_RETURN(X) \
    if ((error = (X)) != STATUS_OK) return error

template <typename T, typename U>
static binder_status_t readBlob(AParcel* parcel, T inPlaceCallback, U ashmemCallback) {
    binder_status_t error = STATUS_OK;
    BlobType type;
    static_assert(sizeof(BlobType) == sizeof(int32_t));
    ON_ERROR_RETURN(AParcel_readInt32(parcel, (int32_t*)&type));
    if (type == BlobType::IN_PLACE) {
        struct Data {
            std::unique_ptr<int8_t[]> ptr = nullptr;
            int32_t size = 0;
        } data;
        ON_ERROR_RETURN(
                AParcel_readByteArray(parcel, &data,
                                      [](void* arrayData, int32_t length, int8_t** outBuffer) {
                                          Data* data = reinterpret_cast<Data*>(arrayData);
                                          if (length > 0) {
                                              data->ptr = std::make_unique<int8_t[]>(length);
                                              data->size = length;
                                              *outBuffer = data->ptr.get();
                                          }
                                          return data->ptr != nullptr;
                                      }));
        return inPlaceCallback(std::move(data.ptr), data.size);
    } else if (type == BlobType::ASHMEM) {
        int rawFd = -1;
        int32_t size = 0;
        ON_ERROR_RETURN(AParcel_readInt32(parcel, &size));
        ON_ERROR_RETURN(AParcel_readParcelFileDescriptor(parcel, &rawFd));
        android::base::unique_fd fd(rawFd);
        return ashmemCallback(std::move(fd), size);
    } else {
        // Although the above if/else was "exhaustive" guard against unknown types
        return STATUS_UNKNOWN_ERROR;
    }
}

static constexpr size_t BLOB_INPLACE_LIMIT = 12 * 1024;
// Fail fast if we can't use ashmem and the size exceeds this limit - the binder transaction
// wouldn't go through, anyway
// TODO: Can we get this from somewhere?
static constexpr size_t BLOB_MAX_INPLACE_LIMIT = 1 * 1024 * 1024;
static constexpr bool shouldUseAshmem(AParcel* parcel, int32_t size) {
    return size > BLOB_INPLACE_LIMIT && AParcel_getAllowFds(parcel);
}

static binder_status_t writeBlobFromFd(AParcel* parcel, int32_t size, int fd) {
    binder_status_t error = STATUS_OK;
    ON_ERROR_RETURN(AParcel_writeInt32(parcel, static_cast<int32_t>(BlobType::ASHMEM)));
    ON_ERROR_RETURN(AParcel_writeInt32(parcel, size));
    ON_ERROR_RETURN(AParcel_writeParcelFileDescriptor(parcel, fd));
    return STATUS_OK;
}

static binder_status_t writeBlob(AParcel* parcel, const int32_t size, const void* data, bool immutable) {
    if (size <= 0 || data == nullptr) {
        return STATUS_NOT_ENOUGH_DATA;
    }
    binder_status_t error = STATUS_OK;
    if (shouldUseAshmem(parcel, size)) {
        // Create new ashmem region with read/write priv
        base::unique_fd fd(ashmem_create_region("bitmap", size));
        if (fd.get() < 0) {
            return STATUS_NO_MEMORY;
        }

        {
            void* dest = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd.get(), 0);
            if (dest == MAP_FAILED) {
                return STATUS_NO_MEMORY;
            }
            memcpy(dest, data, size);
            munmap(dest, size);
        }

        if (immutable && ashmem_set_prot_region(fd.get(), PROT_READ) < 0) {
            return STATUS_UNKNOWN_ERROR;
        }
        // Workaround b/149851140 in AParcel_writeParcelFileDescriptor
        int rawFd = fd.release();
        error = writeBlobFromFd(parcel, size, rawFd);
        close(rawFd);
        return error;
    } else {
        if (size > BLOB_MAX_INPLACE_LIMIT) {
            return STATUS_FAILED_TRANSACTION;
        }
        ON_ERROR_RETURN(AParcel_writeInt32(parcel, static_cast<int32_t>(BlobType::IN_PLACE)));
        ON_ERROR_RETURN(AParcel_writeByteArray(parcel, static_cast<const int8_t*>(data), size));
        return STATUS_OK;
    }
}

#undef ON_ERROR_RETURN

#endif // __ANDROID__ // Layoutlib does not support parcel

// This is the maximum possible size because the SkColorSpace must be
// representable (and therefore serializable) using a matrix and numerical
// transfer function.  If we allow more color space representations in the
// framework, we may need to update this maximum size.
static constexpr size_t kMaxColorSpaceSerializedBytes = 80;

static constexpr auto BadParcelableException = "android/os/BadParcelableException";

static bool validateImageInfo(const SkImageInfo& info, int32_t rowBytes) {
    // TODO: Can we avoid making a SkBitmap for this?
    return SkBitmap().setInfo(info, rowBytes);
}

static jobject Bitmap_createFromParcel(JNIEnv* env, jobject, jobject parcel) {
#ifdef __ANDROID__ // Layoutlib does not support parcel
    if (parcel == NULL) {
        jniThrowNullPointerException(env, "parcel cannot be null");
        return NULL;
    }

    ScopedParcel p(env, parcel);

    const bool isMutable = p.readInt32();
    const SkColorType colorType = static_cast<SkColorType>(p.readInt32());
    const SkAlphaType alphaType = static_cast<SkAlphaType>(p.readInt32());
    sk_sp<SkColorSpace> colorSpace;
    const auto optColorSpaceData = p.readData();
    if (optColorSpaceData) {
        const auto& colorSpaceData = *optColorSpaceData;
        if (colorSpaceData->size() > kMaxColorSpaceSerializedBytes) {
            ALOGD("Bitmap_createFromParcel: Serialized SkColorSpace is larger than expected: "
                  "%zu bytes (max: %zu)\n",
                  colorSpaceData->size(), kMaxColorSpaceSerializedBytes);
        }

        colorSpace = SkColorSpace::Deserialize(colorSpaceData->data(), colorSpaceData->size());
    }
    const int32_t width = p.readInt32();
    const int32_t height = p.readInt32();
    const int32_t rowBytes = p.readInt32();
    const int32_t density = p.readInt32();

    if (kN32_SkColorType != colorType &&
            kRGBA_F16_SkColorType != colorType &&
            kRGB_565_SkColorType != colorType &&
            kARGB_4444_SkColorType != colorType &&
            kAlpha_8_SkColorType != colorType) {
        jniThrowExceptionFmt(env, BadParcelableException,
                             "Bitmap_createFromParcel unknown colortype: %d\n", colorType);
        return NULL;
    }

    auto imageInfo = SkImageInfo::Make(width, height, colorType, alphaType, colorSpace);
    size_t allocationSize = 0;
    if (!validateImageInfo(imageInfo, rowBytes)) {
        jniThrowRuntimeException(env, "Received bad SkImageInfo");
        return NULL;
    }
    if (!Bitmap::computeAllocationSize(rowBytes, height, &allocationSize)) {
        jniThrowExceptionFmt(env, BadParcelableException,
                             "Received bad bitmap size: width=%d, height=%d, rowBytes=%d", width,
                             height, rowBytes);
        return NULL;
    }
    sk_sp<Bitmap> nativeBitmap;
    binder_status_t error = readBlob(
            p.get(),
            // In place callback
            [&](std::unique_ptr<int8_t[]> buffer, int32_t size) {
                if (allocationSize > size) {
                    android_errorWriteLog(0x534e4554, "213169612");
                    return STATUS_BAD_VALUE;
                }
                nativeBitmap = Bitmap::allocateHeapBitmap(allocationSize, imageInfo, rowBytes);
                if (nativeBitmap) {
                    memcpy(nativeBitmap->pixels(), buffer.get(), allocationSize);
                    return STATUS_OK;
                }
                return STATUS_NO_MEMORY;
            },
            // Ashmem callback
            [&](android::base::unique_fd fd, int32_t size) {
                if (allocationSize > size) {
                    android_errorWriteLog(0x534e4554, "213169612");
                    return STATUS_BAD_VALUE;
                }
                int flags = PROT_READ;
                if (isMutable) {
                    flags |= PROT_WRITE;
                }
                void* addr = mmap(nullptr, size, flags, MAP_SHARED, fd.get(), 0);
                if (addr == MAP_FAILED) {
                    const int err = errno;
                    ALOGW("mmap failed, error %d (%s)", err, strerror(err));
                    return STATUS_NO_MEMORY;
                }
                nativeBitmap =
                        Bitmap::createFrom(imageInfo, rowBytes, fd.release(), addr, size, !isMutable);
                return STATUS_OK;
            });

    if (error != STATUS_OK && error != STATUS_NO_MEMORY) {
        // TODO: Stringify the error, see signalExceptionForError in android_util_Binder.cpp
        jniThrowExceptionFmt(env, BadParcelableException, "Failed to read from Parcel, error=%d",
                             error);
        return nullptr;
    }
    if (error == STATUS_NO_MEMORY || !nativeBitmap) {
        jniThrowRuntimeException(env, "Could not allocate bitmap data.");
        return nullptr;
    }

    return createBitmap(env, nativeBitmap.release(), getPremulBitmapCreateFlags(isMutable), nullptr,
                        nullptr, density);
#else
    jniThrowRuntimeException(env, "Cannot use parcels outside of Android");
    return NULL;
#endif
}

static jboolean Bitmap_writeToParcel(JNIEnv* env, jobject,
                                     jlong bitmapHandle, jint density, jobject parcel) {
#ifdef __ANDROID__ // Layoutlib does not support parcel
    if (parcel == NULL) {
        SkDebugf("------- writeToParcel null parcel\n");
        return JNI_FALSE;
    }

    ScopedParcel p(env, parcel);
    SkBitmap bitmap;

    auto bitmapWrapper = reinterpret_cast<BitmapWrapper*>(bitmapHandle);
    bitmapWrapper->getSkBitmap(&bitmap);

    p.writeInt32(!bitmap.isImmutable());
    p.writeInt32(bitmap.colorType());
    p.writeInt32(bitmap.alphaType());
    SkColorSpace* colorSpace = bitmap.colorSpace();
    if (colorSpace != nullptr) {
        p.writeData(colorSpace->serialize());
    } else {
        p.writeData(std::nullopt);
    }
    p.writeInt32(bitmap.width());
    p.writeInt32(bitmap.height());
    p.writeInt32(bitmap.rowBytes());
    p.writeInt32(density);

    // Transfer the underlying ashmem region if we have one and it's immutable.
    binder_status_t status;
    int fd = bitmapWrapper->bitmap().getAshmemFd();
    if (fd >= 0 && p.allowFds() && bitmap.isImmutable()) {
#if DEBUG_PARCEL
        ALOGD("Bitmap.writeToParcel: transferring immutable bitmap's ashmem fd as "
              "immutable blob (fds %s)",
              p.allowFds() ? "allowed" : "forbidden");
#endif

        status = writeBlobFromFd(p.get(), bitmapWrapper->bitmap().getAllocationByteCount(), fd);
        if (status != STATUS_OK) {
            doThrowRE(env, "Could not write bitmap blob file descriptor.");
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }

    // Copy the bitmap to a new blob.
#if DEBUG_PARCEL
    ALOGD("Bitmap.writeToParcel: copying bitmap into new blob (fds %s)",
          p.allowFds() ? "allowed" : "forbidden");
#endif

    size_t size = bitmap.computeByteSize();
    status = writeBlob(p.get(), size, bitmap.getPixels(), bitmap.isImmutable());
    if (status) {
        doThrowRE(env, "Could not copy bitmap to parcel blob.");
        return JNI_FALSE;
    }
    return JNI_TRUE;
#else
    doThrowRE(env, "Cannot use parcels outside of Android");
    return JNI_FALSE;
#endif
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
    return colorSpace == nullptr || colorSpace->isSRGB();
}

static jboolean Bitmap_isSRGBLinear(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmapHolder(bitmapHandle);
    if (!bitmapHolder.valid()) return JNI_FALSE;

    SkColorSpace* colorSpace = bitmapHolder->info().colorSpace();
    sk_sp<SkColorSpace> srgbLinear = SkColorSpace::MakeSRGBLinear();
    return colorSpace == srgbLinear.get() ? JNI_TRUE : JNI_FALSE;
}

static jobject Bitmap_computeColorSpace(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmapHolder(bitmapHandle);
    if (!bitmapHolder.valid()) return nullptr;

    SkColorSpace* colorSpace = bitmapHolder->info().colorSpace();
    if (colorSpace == nullptr) return nullptr;

    return GraphicsJNI::getColorSpace(env, colorSpace, bitmapHolder->info().colorType());
}

static void Bitmap_setColorSpace(JNIEnv* env, jobject, jlong bitmapHandle, jlong colorSpacePtr) {
    LocalScopedBitmap bitmapHolder(bitmapHandle);
    sk_sp<SkColorSpace> cs = GraphicsJNI::getNativeColorSpace(colorSpacePtr);
    bitmapHolder->setColorSpace(cs);
}

///////////////////////////////////////////////////////////////////////////////

static jint Bitmap_getPixel(JNIEnv* env, jobject, jlong bitmapHandle,
        jint x, jint y) {
    SkBitmap bitmap;
    reinterpret_cast<BitmapWrapper*>(bitmapHandle)->getSkBitmap(&bitmap);

    auto sRGB = SkColorSpace::MakeSRGB();
    SkImageInfo dstInfo = SkImageInfo::Make(
            1, 1, kBGRA_8888_SkColorType, kUnpremul_SkAlphaType, sRGB);

    SkColor dst;
    bitmap.readPixels(dstInfo, &dst, dstInfo.minRowBytes(), x, y);
    return static_cast<jint>(dst);
}

static jlong Bitmap_getColor(JNIEnv* env, jobject, jlong bitmapHandle,
        jint x, jint y) {
    SkBitmap bitmap;
    reinterpret_cast<BitmapWrapper*>(bitmapHandle)->getSkBitmap(&bitmap);

    SkImageInfo dstInfo = SkImageInfo::Make(
            1, 1, kRGBA_F16_SkColorType, kUnpremul_SkAlphaType, bitmap.refColorSpace());

    uint64_t dst;
    bitmap.readPixels(dstInfo, &dst, dstInfo.minRowBytes(), x, y);
    return static_cast<jlong>(dst);
}

static void Bitmap_getPixels(JNIEnv* env, jobject, jlong bitmapHandle,
        jintArray pixelArray, jint offset, jint stride,
        jint x, jint y, jint width, jint height) {
    SkBitmap bitmap;
    reinterpret_cast<BitmapWrapper*>(bitmapHandle)->getSkBitmap(&bitmap);

    auto sRGB = SkColorSpace::MakeSRGB();
    SkImageInfo dstInfo = SkImageInfo::Make(
            width, height, kBGRA_8888_SkColorType, kUnpremul_SkAlphaType, sRGB);

    jint* dst = env->GetIntArrayElements(pixelArray, NULL);
    bitmap.readPixels(dstInfo, dst + offset, stride * 4, x, y);
    env->ReleaseIntArrayElements(pixelArray, dst, 0);
}

///////////////////////////////////////////////////////////////////////////////

static void Bitmap_setPixel(JNIEnv* env, jobject, jlong bitmapHandle,
        jint x, jint y, jint colorHandle) {
    SkBitmap bitmap;
    reinterpret_cast<BitmapWrapper*>(bitmapHandle)->getSkBitmap(&bitmap);
    SkColor color = static_cast<SkColor>(colorHandle);

    auto sRGB = SkColorSpace::MakeSRGB();
    SkImageInfo srcInfo = SkImageInfo::Make(
            1, 1, kBGRA_8888_SkColorType, kUnpremul_SkAlphaType, sRGB);
    SkPixmap srcPM(srcInfo, &color, srcInfo.minRowBytes());

    bitmap.writePixels(srcPM, x, y);
}

static void Bitmap_setPixels(JNIEnv* env, jobject, jlong bitmapHandle,
        jintArray pixelArray, jint offset, jint stride,
        jint x, jint y, jint width, jint height) {
    SkBitmap bitmap;
    reinterpret_cast<BitmapWrapper*>(bitmapHandle)->getSkBitmap(&bitmap);
    GraphicsJNI::SetPixels(env, pixelArray, offset, stride,
            x, y, width, height, &bitmap);
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
#ifdef __ANDROID__ // Layoutlib does not support render thread
    LocalScopedBitmap bitmapHandle(bitmapPtr);
    if (!bitmapHandle.valid()) return;
    android::uirenderer::renderthread::RenderProxy::prepareToDraw(bitmapHandle->bitmap());
#endif
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

    if (src.pixelRef() == nullptr) {
        doThrowRE(env, "Could not copy a hardware bitmap.");
        return NULL;
    }

    sk_sp<Bitmap> bitmap = Bitmap::createFrom(src.info(), *src.pixelRef());
    return createBitmap(env, bitmap.release(), getPremulBitmapCreateFlags(false));
}

#ifdef __ANDROID__ // Layoutlib does not support graphic buffer
typedef AHardwareBuffer* (*AHB_from_HB)(JNIEnv*, jobject);
AHB_from_HB AHardwareBuffer_fromHardwareBuffer;

typedef jobject (*AHB_to_HB)(JNIEnv*, AHardwareBuffer*);
AHB_to_HB AHardwareBuffer_toHardwareBuffer;
#endif

static jobject Bitmap_wrapHardwareBufferBitmap(JNIEnv* env, jobject, jobject hardwareBuffer,
                                               jlong colorSpacePtr) {
#ifdef __ANDROID__ // Layoutlib does not support graphic buffer
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    sk_sp<Bitmap> bitmap = Bitmap::createFrom(buffer,
                                              GraphicsJNI::getNativeColorSpace(colorSpacePtr));
    if (!bitmap.get()) {
        ALOGW("failed to create hardware bitmap from hardware buffer");
        return NULL;
    }
    return bitmap::createBitmap(env, bitmap.release(), getPremulBitmapCreateFlags(false));
#else
    return NULL;
#endif
}

static jobject Bitmap_getHardwareBuffer(JNIEnv* env, jobject, jlong bitmapPtr) {
#ifdef __ANDROID__ // Layoutlib does not support graphic buffer
    LocalScopedBitmap bitmapHandle(bitmapPtr);
    if (!bitmapHandle->isHardware()) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Hardware config is only supported config in Bitmap_getHardwareBuffer");
        return nullptr;
    }

    Bitmap& bitmap = bitmapHandle->bitmap();
    return AHardwareBuffer_toHardwareBuffer(env, bitmap.hardwareBuffer());
#else
    return nullptr;
#endif
}

static jboolean Bitmap_isImmutable(CRITICAL_JNI_PARAMS_COMMA jlong bitmapHandle) {
    LocalScopedBitmap bitmapHolder(bitmapHandle);
    if (!bitmapHolder.valid()) return JNI_FALSE;

    return bitmapHolder->bitmap().isImmutable() ? JNI_TRUE : JNI_FALSE;
}

static jboolean Bitmap_isBackedByAshmem(CRITICAL_JNI_PARAMS_COMMA jlong bitmapHandle) {
    LocalScopedBitmap bitmapHolder(bitmapHandle);
    if (!bitmapHolder.valid()) return JNI_FALSE;

    return bitmapHolder->bitmap().pixelStorageType() == PixelStorageType::Ashmem ? JNI_TRUE
                                                                                 : JNI_FALSE;
}

static void Bitmap_setImmutable(JNIEnv* env, jobject, jlong bitmapHandle) {
    LocalScopedBitmap bitmapHolder(bitmapHandle);
    if (!bitmapHolder.valid()) return;

    return bitmapHolder->bitmap().setImmutable();
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gBitmapMethods[] = {
    {   "nativeCreate",             "([IIIIIIZJ)Landroid/graphics/Bitmap;",
        (void*)Bitmap_creator },
    {   "nativeCopy",               "(JIZ)Landroid/graphics/Bitmap;",
        (void*)Bitmap_copy },
    {   "nativeCopyAshmem",         "(J)Landroid/graphics/Bitmap;",
        (void*)Bitmap_copyAshmem },
    {   "nativeCopyAshmemConfig",   "(JI)Landroid/graphics/Bitmap;",
        (void*)Bitmap_copyAshmemConfig },
    {   "nativeGetNativeFinalizer", "()J", (void*)Bitmap_getNativeFinalizer },
    {   "nativeRecycle",            "(J)V", (void*)Bitmap_recycle },
    {   "nativeReconfigure",        "(JIIIZ)V", (void*)Bitmap_reconfigure },
    {   "nativeCompress",           "(JIILjava/io/OutputStream;[B)Z",
        (void*)Bitmap_compress },
    {   "nativeErase",              "(JI)V", (void*)Bitmap_erase },
    {   "nativeErase",              "(JJJ)V", (void*)Bitmap_eraseLong },
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
    {   "nativeWriteToParcel",      "(JILandroid/os/Parcel;)Z",
        (void*)Bitmap_writeToParcel },
    {   "nativeExtractAlpha",       "(JJ[I)Landroid/graphics/Bitmap;",
        (void*)Bitmap_extractAlpha },
    {   "nativeGenerationId",       "(J)I", (void*)Bitmap_getGenerationId },
    {   "nativeGetPixel",           "(JII)I", (void*)Bitmap_getPixel },
    {   "nativeGetColor",           "(JII)J", (void*)Bitmap_getColor },
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
    {   "nativeWrapHardwareBufferBitmap", "(Landroid/hardware/HardwareBuffer;J)Landroid/graphics/Bitmap;",
        (void*) Bitmap_wrapHardwareBufferBitmap },
    {   "nativeGetHardwareBuffer", "(J)Landroid/hardware/HardwareBuffer;",
        (void*) Bitmap_getHardwareBuffer },
    {   "nativeComputeColorSpace",  "(J)Landroid/graphics/ColorSpace;", (void*)Bitmap_computeColorSpace },
    {   "nativeSetColorSpace",      "(JJ)V", (void*)Bitmap_setColorSpace },
    {   "nativeIsSRGB",             "(J)Z", (void*)Bitmap_isSRGB },
    {   "nativeIsSRGBLinear",       "(J)Z", (void*)Bitmap_isSRGBLinear},
    {   "nativeSetImmutable",       "(J)V", (void*)Bitmap_setImmutable},

    // ------------ @CriticalNative ----------------
    {   "nativeIsImmutable",        "(J)Z", (void*)Bitmap_isImmutable},
    {   "nativeIsBackedByAshmem",   "(J)Z", (void*)Bitmap_isBackedByAshmem}

};

int register_android_graphics_Bitmap(JNIEnv* env)
{
    gBitmap_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Bitmap"));
    gBitmap_nativePtr = GetFieldIDOrDie(env, gBitmap_class, "mNativePtr", "J");
    gBitmap_constructorMethodID = GetMethodIDOrDie(env, gBitmap_class, "<init>", "(JIIIZ[BLandroid/graphics/NinePatch$InsetStruct;Z)V");
    gBitmap_reinitMethodID = GetMethodIDOrDie(env, gBitmap_class, "reinit", "(IIZ)V");

#ifdef __ANDROID__ // Layoutlib does not support graphic buffer or parcel
    void* handle_ = dlopen("libandroid.so", RTLD_NOW | RTLD_NODELETE);
    AHardwareBuffer_fromHardwareBuffer =
            (AHB_from_HB)dlsym(handle_, "AHardwareBuffer_fromHardwareBuffer");
    LOG_ALWAYS_FATAL_IF(AHardwareBuffer_fromHardwareBuffer == nullptr,
                        "Failed to find required symbol AHardwareBuffer_fromHardwareBuffer!");

    AHardwareBuffer_toHardwareBuffer = (AHB_to_HB)dlsym(handle_, "AHardwareBuffer_toHardwareBuffer");
    LOG_ALWAYS_FATAL_IF(AHardwareBuffer_toHardwareBuffer == nullptr,
                        " Failed to find required symbol AHardwareBuffer_toHardwareBuffer!");
#endif
    return android::RegisterMethodsOrDie(env, "android/graphics/Bitmap", gBitmapMethods,
                                         NELEM(gBitmapMethods));
}
