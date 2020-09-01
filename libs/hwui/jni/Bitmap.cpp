#undef LOG_TAG
#define LOG_TAG "Bitmap"
#include "Bitmap.h"

#include "SkBitmap.h"
#include "SkPixelRef.h"
#include "SkImageEncoder.h"
#include "SkImageInfo.h"
#include "SkColor.h"
#include "SkColorSpace.h"
#include "GraphicsJNI.h"
#include "SkStream.h"
#include "SkWebpEncoder.h"

#include "android_nio_utils.h"
#include "CreateJavaOutputStreamAdaptor.h"
#include <hwui/Paint.h>
#include <hwui/Bitmap.h>
#include <utils/Color.h>

#ifdef __ANDROID__ // Layoutlib does not support graphic buffer, parcel or render thread
#include <private/android/AHardwareBufferHelpers.h>
#include <binder/Parcel.h>
#include <dlfcn.h>
#include <renderthread/RenderProxy.h>
#endif

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

#ifdef __ANDROID__ // Layoutlib does not support parcel
static struct parcel_offsets_t
{
  jclass clazz;
  jfieldID mNativePtr;
} gParcelOffsets;

static Parcel* parcelForJavaObject(JNIEnv* env, jobject obj) {
    if (obj) {
        Parcel* p = (Parcel*)env->GetLongField(obj, gParcelOffsets.mNativePtr);
        if (p != NULL) {
            return p;
        }
        jniThrowException(env, "java/lang/IllegalStateException", "Parcel has been finalized!");
    }
    return NULL;
}
#endif

// This is the maximum possible size because the SkColorSpace must be
// representable (and therefore serializable) using a matrix and numerical
// transfer function.  If we allow more color space representations in the
// framework, we may need to update this maximum size.
static constexpr uint32_t kMaxColorSpaceSerializedBytes = 80;

static jobject Bitmap_createFromParcel(JNIEnv* env, jobject, jobject parcel) {
#ifdef __ANDROID__ // Layoutlib does not support parcel
    if (parcel == NULL) {
        SkDebugf("-------- unparcel parcel is NULL\n");
        return NULL;
    }

    android::Parcel* p = parcelForJavaObject(env, parcel);

    const bool        isMutable = p->readInt32() != 0;
    const SkColorType colorType = (SkColorType)p->readInt32();
    const SkAlphaType alphaType = (SkAlphaType)p->readInt32();
    const uint32_t    colorSpaceSize = p->readUint32();
    sk_sp<SkColorSpace> colorSpace;
    if (colorSpaceSize > 0) {
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
    // If the blob is mutable we have ownership of the region and can always use it
    // If the blob is immutable _and_ we're immutable, we can then still use it
    if (blob.fd() >= 0 && (blob.isMutable() || !isMutable)) {
#if DEBUG_PARCEL
        ALOGD("Bitmap.createFromParcel: mapped contents of bitmap from %s blob "
                "(fds %s)",
                blob.isMutable() ? "mutable" : "immutable",
                p->allowFds() ? "allowed" : "forbidden");
#endif
        // Dup the file descriptor so we can keep a reference to it after the Parcel
        // is disposed.
        int dupFd = fcntl(blob.fd(), F_DUPFD_CLOEXEC, 0);
        if (dupFd < 0) {
            ALOGE("Error allocating dup fd. Error:%d", errno);
            blob.release();
            doThrowRE(env, "Could not allocate dup blob fd.");
            return NULL;
        }

        // Map the pixels in place and take ownership of the ashmem region. We must also respect the
        // rowBytes value already set on the bitmap instead of attempting to compute our own.
        nativeBitmap = Bitmap::createFrom(bitmap->info(), bitmap->rowBytes(), dupFd,
                                          const_cast<void*>(blob.data()), size, !isMutable);
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
#else
    doThrowRE(env, "Cannot use parcels outside of Android");
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

    android::Parcel* p = parcelForJavaObject(env, parcel);
    SkBitmap bitmap;

    auto bitmapWrapper = reinterpret_cast<BitmapWrapper*>(bitmapHandle);
    bitmapWrapper->getSkBitmap(&bitmap);

    p->writeInt32(!bitmap.isImmutable());
    p->writeInt32(bitmap.colorType());
    p->writeInt32(bitmap.alphaType());
    SkColorSpace* colorSpace = bitmap.colorSpace();
    if (colorSpace != nullptr) {
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
    if (fd >= 0 && bitmap.isImmutable() && p->allowFds()) {
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
#if DEBUG_PARCEL
    ALOGD("Bitmap.writeToParcel: copying bitmap into new blob (fds %s)",
            p->allowFds() ? "allowed" : "forbidden");
#endif

    const bool mutableCopy = !bitmap.isImmutable();
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
    LOG_ALWAYS_FATAL_IF(!bitmapHandle->isHardware(),
            "Hardware config is only supported config in Bitmap_getHardwareBuffer");

    Bitmap& bitmap = bitmapHandle->bitmap();
    return AHardwareBuffer_toHardwareBuffer(env, bitmap.hardwareBuffer());
#else
    return NULL;
#endif
}

static jboolean Bitmap_isImmutable(CRITICAL_JNI_PARAMS_COMMA jlong bitmapHandle) {
    LocalScopedBitmap bitmapHolder(bitmapHandle);
    if (!bitmapHolder.valid()) return JNI_FALSE;

    return bitmapHolder->bitmap().isImmutable() ? JNI_TRUE : JNI_FALSE;
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
    {   "nativeIsImmutable",        "(J)Z", (void*)Bitmap_isImmutable}

};

const char* const kParcelPathName = "android/os/Parcel";

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

    gParcelOffsets.clazz = MakeGlobalRefOrDie(env, FindClassOrDie(env, kParcelPathName));
    gParcelOffsets.mNativePtr = GetFieldIDOrDie(env, gParcelOffsets.clazz, "mNativePtr", "J");
#endif
    return android::RegisterMethodsOrDie(env, "android/graphics/Bitmap", gBitmapMethods,
                                         NELEM(gBitmapMethods));
}
