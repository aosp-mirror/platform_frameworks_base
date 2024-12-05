#include "BitmapFactory.h"

#include <Gainmap.h>
#include <HardwareBitmapUploader.h>
#include <androidfw/Asset.h>
#include <androidfw/ResourceTypes.h>
#include <cutils/compiler.h>
#include <fcntl.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/stat.h>
#include <utils/StatsUtils.h>

#include <memory>

#include "CreateJavaOutputStreamAdaptor.h"
#include "FrontBufferedStream.h"
#include "GraphicsJNI.h"
#include "MimeType.h"
#include "NinePatchPeeker.h"
#include "SkAndroidCodec.h"
#include "SkBitmap.h"
#include "SkBlendMode.h"
#include "SkCanvas.h"
#include "SkColorSpace.h"
#include "SkEncodedImageFormat.h"
#include "SkGainmapInfo.h"
#include "SkImageInfo.h"
#include "SkPaint.h"
#include "SkPixelRef.h"
#include "SkRect.h"
#include "SkRefCnt.h"
#include "SkSamplingOptions.h"
#include "SkSize.h"
#include "SkStream.h"
#include "SkString.h"
#include "Utils.h"

jfieldID gOptions_justBoundsFieldID;
jfieldID gOptions_sampleSizeFieldID;
jfieldID gOptions_configFieldID;
jfieldID gOptions_colorSpaceFieldID;
jfieldID gOptions_premultipliedFieldID;
jfieldID gOptions_mutableFieldID;
jfieldID gOptions_ditherFieldID;
jfieldID gOptions_preferQualityOverSpeedFieldID;
jfieldID gOptions_scaledFieldID;
jfieldID gOptions_densityFieldID;
jfieldID gOptions_screenDensityFieldID;
jfieldID gOptions_targetDensityFieldID;
jfieldID gOptions_widthFieldID;
jfieldID gOptions_heightFieldID;
jfieldID gOptions_mimeFieldID;
jfieldID gOptions_outConfigFieldID;
jfieldID gOptions_outColorSpaceFieldID;
jfieldID gOptions_mCancelID;
jfieldID gOptions_bitmapFieldID;

jfieldID gBitmap_ninePatchInsetsFieldID;

jclass gBitmapConfig_class;
jmethodID gBitmapConfig_nativeToConfigMethodID;

using namespace android;

const char* getMimeType(SkEncodedImageFormat format) {
    switch (format) {
        case SkEncodedImageFormat::kBMP:
            return "image/bmp";
        case SkEncodedImageFormat::kGIF:
            return "image/gif";
        case SkEncodedImageFormat::kICO:
            return "image/x-ico";
        case SkEncodedImageFormat::kJPEG:
            return "image/jpeg";
        case SkEncodedImageFormat::kPNG:
            return "image/png";
        case SkEncodedImageFormat::kWEBP:
            return "image/webp";
        case SkEncodedImageFormat::kHEIF:
            return "image/heif";
        case SkEncodedImageFormat::kAVIF:
            return "image/avif";
        case SkEncodedImageFormat::kWBMP:
            return "image/vnd.wap.wbmp";
        case SkEncodedImageFormat::kDNG:
            return "image/x-adobe-dng";
        default:
            return nullptr;
    }
}

jstring getMimeTypeAsJavaString(JNIEnv* env, SkEncodedImageFormat format) {
    jstring jstr = nullptr;
    const char* mimeType = getMimeType(format);
    if (mimeType) {
        // NOTE: Caller should env->ExceptionCheck() for OOM
        // (can't check for nullptr as it's a valid return value)
        jstr = env->NewStringUTF(mimeType);
    }
    return jstr;
}

class ScaleCheckingAllocator : public SkBitmap::HeapAllocator {
public:
    ScaleCheckingAllocator(float scale, int size)
            : mScale(scale), mSize(size) {
    }

    virtual bool allocPixelRef(SkBitmap* bitmap) {
        // accounts for scale in final allocation, using eventual size and config
        const int bytesPerPixel = SkColorTypeBytesPerPixel(bitmap->colorType());
        const int requestedSize = bytesPerPixel *
                int(bitmap->width() * mScale + 0.5f) *
                int(bitmap->height() * mScale + 0.5f);
        if (requestedSize > mSize) {
            ALOGW("bitmap for alloc reuse (%d bytes) can't fit scaled bitmap (%d bytes)",
                    mSize, requestedSize);
            return false;
        }
        return SkBitmap::HeapAllocator::allocPixelRef(bitmap);
    }
private:
    const float mScale;
    const int mSize;
};

class RecyclingPixelAllocator : public SkBitmap::Allocator {
public:
    RecyclingPixelAllocator(android::Bitmap* bitmap, unsigned int size)
            : mBitmap(bitmap), mSize(size) {
    }

    ~RecyclingPixelAllocator() {
    }

    virtual bool allocPixelRef(SkBitmap* bitmap) {
        const SkImageInfo& info = bitmap->info();
        if (info.colorType() == kUnknown_SkColorType) {
            ALOGW("unable to reuse a bitmap as the target has an unknown bitmap configuration");
            return false;
        }

        const size_t size = info.computeByteSize(bitmap->rowBytes());
        if (size > INT32_MAX) {
            ALOGW("bitmap is too large");
            return false;
        }

        if (size > mSize) {
            ALOGW("bitmap marked for reuse (%u bytes) can't fit new bitmap "
                  "(%zu bytes)", mSize, size);
            return false;
        }

        mBitmap->reconfigure(info, bitmap->rowBytes());
        bitmap->setPixelRef(sk_ref_sp(mBitmap), 0, 0);
        return true;
    }

private:
    android::Bitmap* const mBitmap;
    const unsigned int mSize;
};

// Necessary for decodes when the native decoder cannot scale to appropriately match the sampleSize
// (for example, RAW). If the sampleSize divides evenly into the dimension, we require that the
// scale matches exactly. If sampleSize does not divide evenly, we allow the decoder to choose how
// best to round.
static bool needsFineScale(const int fullSize, const int decodedSize, const int sampleSize) {
    if (fullSize % sampleSize == 0 && fullSize / sampleSize != decodedSize) {
        return true;
    } else if ((fullSize / sampleSize + 1) != decodedSize &&
               (fullSize / sampleSize) != decodedSize) {
        return true;
    }
    return false;
}

static bool needsFineScale(const SkISize fullSize, const SkISize decodedSize,
                           const int sampleSize) {
    return needsFineScale(fullSize.width(), decodedSize.width(), sampleSize) ||
           needsFineScale(fullSize.height(), decodedSize.height(), sampleSize);
}

static bool decodeGainmap(std::unique_ptr<SkAndroidCodec> codec, const SkGainmapInfo& gainmapInfo,
                          sp<uirenderer::Gainmap>* outGainmap, const int sampleSize, float scale) {
    SkColorType decodeColorType = kN32_SkColorType;
    if (codec->getInfo().colorType() == kGray_8_SkColorType) {
        decodeColorType = kGray_8_SkColorType;
    }
    decodeColorType = codec->computeOutputColorType(decodeColorType);
    sk_sp<SkColorSpace> decodeColorSpace = codec->computeOutputColorSpace(decodeColorType, nullptr);

    SkISize size = codec->getSampledDimensions(sampleSize);

    int scaledWidth = size.width();
    int scaledHeight = size.height();
    bool willScale = false;

    // Apply a fine scaling step if necessary.
    if (needsFineScale(codec->getInfo().dimensions(), size, sampleSize) || scale != 1.0f) {
        willScale = true;
        // The operation below may loose precision (integer division), but it is put this way to
        // mimic main image scale calculation
        scaledWidth = static_cast<int>((codec->getInfo().width() / sampleSize) * scale + 0.5f);
        scaledHeight = static_cast<int>((codec->getInfo().height() / sampleSize) * scale + 0.5f);
    }

    SkAlphaType alphaType = codec->computeOutputAlphaType(false);

    const SkImageInfo decodeInfo = SkImageInfo::Make(size.width(), size.height(), decodeColorType,
                                                     alphaType, decodeColorSpace);

    SkImageInfo bitmapInfo = decodeInfo;
    if (decodeColorType == kGray_8_SkColorType) {
        // We treat gray8 as alpha8 in Bitmap's API surface
        bitmapInfo = bitmapInfo.makeColorType(kAlpha_8_SkColorType);
    }
    SkBitmap decodeBitmap;
    sk_sp<Bitmap> nativeBitmap = nullptr;

    if (!decodeBitmap.setInfo(bitmapInfo)) {
        ALOGE("Failed to setInfo.");
        return false;
    }

    if (willScale) {
        if (!decodeBitmap.tryAllocPixels(nullptr)) {
            ALOGE("OOM allocating gainmap pixels.");
            return false;
        }
    } else {
        nativeBitmap = android::Bitmap::allocateHeapBitmap(&decodeBitmap);
        if (!nativeBitmap) {
            ALOGE("OOM allocating gainmap pixels.");
            return false;
        }
    }

    // Use SkAndroidCodec to perform the decode.
    SkAndroidCodec::AndroidOptions codecOptions;
    codecOptions.fZeroInitialized = SkCodec::kYes_ZeroInitialized;
    codecOptions.fSampleSize = sampleSize;
    SkCodec::Result result = codec->getAndroidPixels(decodeInfo, decodeBitmap.getPixels(),
                                                     decodeBitmap.rowBytes(), &codecOptions);
    switch (result) {
        case SkCodec::kSuccess:
        case SkCodec::kIncompleteInput:
            break;
        default:
            ALOGE("Error decoding gainmap.");
            return false;
    }

    if (willScale) {
        SkBitmap gainmapBitmap;
        const float scaleX = scaledWidth / float(decodeBitmap.width());
        const float scaleY = scaledHeight / float(decodeBitmap.height());

        SkColorType scaledColorType = decodeBitmap.colorType();
        gainmapBitmap.setInfo(
                bitmapInfo.makeWH(scaledWidth, scaledHeight).makeColorType(scaledColorType));

        nativeBitmap = android::Bitmap::allocateHeapBitmap(&gainmapBitmap);
        if (!nativeBitmap) {
            ALOGE("OOM allocating gainmap pixels.");
            return false;
        }

        SkPaint paint;
        // kSrc_Mode instructs us to overwrite the uninitialized pixels in
        // outputBitmap.  Otherwise we would blend by default, which is not
        // what we want.
        paint.setBlendMode(SkBlendMode::kSrc);

        SkCanvas canvas(gainmapBitmap, SkCanvas::ColorBehavior::kLegacy);
        canvas.scale(scaleX, scaleY);
        decodeBitmap.setImmutable();  // so .asImage() doesn't make a copy
        canvas.drawImage(decodeBitmap.asImage(), 0.0f, 0.0f,
                         SkSamplingOptions(SkFilterMode::kLinear), &paint);
    }

    auto gainmap = sp<uirenderer::Gainmap>::make();
    if (!gainmap) {
        ALOGE("OOM allocating Gainmap");
        return false;
    }

    gainmap->info = gainmapInfo;
    gainmap->bitmap = std::move(nativeBitmap);
    *outGainmap = std::move(gainmap);

    return true;
}

static jobject doDecode(JNIEnv* env, std::unique_ptr<SkStreamRewindable> stream,
                        jobject padding, jobject options, jlong inBitmapHandle,
                        jlong colorSpaceHandle) {
    // Set default values for the options parameters.
    int sampleSize = 1;
    bool onlyDecodeSize = false;
    SkColorType prefColorType = kN32_SkColorType;
    bool isHardware = false;
    bool isMutable = false;
    float scale = 1.0f;
    bool requireUnpremultiplied = false;
    jobject javaBitmap = NULL;
    sk_sp<SkColorSpace> prefColorSpace = GraphicsJNI::getNativeColorSpace(colorSpaceHandle);

    // Update with options supplied by the client.
    if (options != NULL) {
        sampleSize = env->GetIntField(options, gOptions_sampleSizeFieldID);
        // Correct a non-positive sampleSize.  sampleSize defaults to zero within the
        // options object, which is strange.
        if (sampleSize <= 0) {
            sampleSize = 1;
        }

        if (env->GetBooleanField(options, gOptions_justBoundsFieldID)) {
            onlyDecodeSize = true;
        }

        // initialize these, in case we fail later on
        env->SetIntField(options, gOptions_widthFieldID, -1);
        env->SetIntField(options, gOptions_heightFieldID, -1);
        env->SetObjectField(options, gOptions_mimeFieldID, 0);
        env->SetObjectField(options, gOptions_outConfigFieldID, 0);
        env->SetObjectField(options, gOptions_outColorSpaceFieldID, 0);

        jobject jconfig = env->GetObjectField(options, gOptions_configFieldID);
        prefColorType = GraphicsJNI::getNativeBitmapColorType(env, jconfig);
        isHardware = GraphicsJNI::isHardwareConfig(env, jconfig);
        isMutable = env->GetBooleanField(options, gOptions_mutableFieldID);
        requireUnpremultiplied = !env->GetBooleanField(options, gOptions_premultipliedFieldID);
        javaBitmap = env->GetObjectField(options, gOptions_bitmapFieldID);

        if (env->GetBooleanField(options, gOptions_scaledFieldID)) {
            const int density = env->GetIntField(options, gOptions_densityFieldID);
            const int targetDensity = env->GetIntField(options, gOptions_targetDensityFieldID);
            const int screenDensity = env->GetIntField(options, gOptions_screenDensityFieldID);
            if (density != 0 && targetDensity != 0 && density != screenDensity) {
                scale = (float) targetDensity / density;
            }
        }
    }

    if (isMutable && isHardware) {
        doThrowIAE(env, "Bitmaps with Config.HARDWARE are always immutable");
        return nullObjectReturn("Cannot create mutable hardware bitmap");
    }

    // Create the codec.
    NinePatchPeeker peeker;
    std::unique_ptr<SkAndroidCodec> codec;
    {
        SkCodec::Result result;
        std::unique_ptr<SkCodec> c = SkCodec::MakeFromStream(std::move(stream), &result,
                                                             &peeker);
        if (!c) {
            SkString msg;
            msg.printf("Failed to create image decoder with message '%s'",
                       SkCodec::ResultToString(result));
            return nullObjectReturn(msg.c_str());
        }

        codec = SkAndroidCodec::MakeFromCodec(std::move(c));
        if (!codec) {
            return nullObjectReturn("SkAndroidCodec::MakeFromCodec returned null");
        }
    }

    // Do not allow ninepatch decodes to 565.  In the past, decodes to 565
    // would dither, and we do not want to pre-dither ninepatches, since we
    // know that they will be stretched.  We no longer dither 565 decodes,
    // but we continue to prevent ninepatches from decoding to 565, in order
    // to maintain the old behavior.
    if (peeker.mPatch && kRGB_565_SkColorType == prefColorType) {
        prefColorType = kN32_SkColorType;
    }

    // Determine the output size.
    SkISize size = codec->getSampledDimensions(sampleSize);

    int scaledWidth = size.width();
    int scaledHeight = size.height();
    bool willScale = false;

    // Apply a fine scaling step if necessary.
    if (needsFineScale(codec->getInfo().dimensions(), size, sampleSize)) {
        willScale = true;
        scaledWidth = codec->getInfo().width() / sampleSize;
        scaledHeight = codec->getInfo().height() / sampleSize;
    }

    // Set the decode colorType
    SkColorType decodeColorType = codec->computeOutputColorType(prefColorType);
    if (decodeColorType == kRGBA_F16_SkColorType && isHardware &&
            !uirenderer::HardwareBitmapUploader::hasFP16Support()) {
        decodeColorType = kN32_SkColorType;
    }

    // b/276879147, fallback to RGBA_8888 when decoding HEIF and P010 is not supported.
    if (decodeColorType == kRGBA_1010102_SkColorType &&
        codec->getEncodedFormat() == SkEncodedImageFormat::kHEIF &&
        env->CallStaticBooleanMethod(gImageDecoder_class,
                                     gImageDecoder_isP010SupportedForHEVCMethodID) == JNI_FALSE) {
        decodeColorType = kN32_SkColorType;
    }

    sk_sp<SkColorSpace> decodeColorSpace = codec->computeOutputColorSpace(
            decodeColorType, prefColorSpace);

    // Set the options and return if the client only wants the size.
    if (options != NULL) {
        jstring mimeType = getMimeTypeAsJavaString(env, codec->getEncodedFormat());
        if (env->ExceptionCheck()) {
            return nullObjectReturn("OOM in getMimeTypeAsJavaString()");
        }
        env->SetIntField(options, gOptions_widthFieldID, scaledWidth);
        env->SetIntField(options, gOptions_heightFieldID, scaledHeight);
        env->SetObjectField(options, gOptions_mimeFieldID, mimeType);

        jint configID = GraphicsJNI::colorTypeToLegacyBitmapConfig(decodeColorType);
        if (isHardware) {
            configID = GraphicsJNI::kHardware_LegacyBitmapConfig;
        }
        jobject config = env->CallStaticObjectMethod(gBitmapConfig_class,
                gBitmapConfig_nativeToConfigMethodID, configID);
        env->SetObjectField(options, gOptions_outConfigFieldID, config);

        env->SetObjectField(options, gOptions_outColorSpaceFieldID,
                GraphicsJNI::getColorSpace(env, decodeColorSpace.get(), decodeColorType));

        if (onlyDecodeSize) {
            return nullptr;
        }
    }

    // Scale is necessary due to density differences.
    if (scale != 1.0f) {
        willScale = true;
        scaledWidth = static_cast<int>(scaledWidth * scale + 0.5f);
        scaledHeight = static_cast<int>(scaledHeight * scale + 0.5f);
    }

    android::Bitmap* reuseBitmap = nullptr;
    unsigned int existingBufferSize = 0;
    if (javaBitmap != nullptr) {
        reuseBitmap = &bitmap::toBitmap(inBitmapHandle);
        if (reuseBitmap->isImmutable()) {
            ALOGW("Unable to reuse an immutable bitmap as an image decoder target.");
            javaBitmap = nullptr;
            reuseBitmap = nullptr;
        } else {
            existingBufferSize = reuseBitmap->getAllocationByteCount();
        }
    }

    HeapAllocator defaultAllocator;
    RecyclingPixelAllocator recyclingAllocator(reuseBitmap, existingBufferSize);
    ScaleCheckingAllocator scaleCheckingAllocator(scale, existingBufferSize);
    SkBitmap::HeapAllocator heapAllocator;
    SkBitmap::Allocator* decodeAllocator;
    if (javaBitmap != nullptr && willScale) {
        // This will allocate pixels using a HeapAllocator, since there will be an extra
        // scaling step that copies these pixels into Java memory.  This allocator
        // also checks that the recycled javaBitmap is large enough.
        decodeAllocator = &scaleCheckingAllocator;
    } else if (javaBitmap != nullptr) {
        decodeAllocator = &recyclingAllocator;
    } else if (willScale || isHardware) {
        // This will allocate pixels using a HeapAllocator,
        // for scale case: there will be an extra scaling step.
        // for hardware case: there will be extra swizzling & upload to gralloc step.
        decodeAllocator = &heapAllocator;
    } else {
        decodeAllocator = &defaultAllocator;
    }

    SkAlphaType alphaType = codec->computeOutputAlphaType(requireUnpremultiplied);

    const SkImageInfo decodeInfo = SkImageInfo::Make(size.width(), size.height(),
            decodeColorType, alphaType, decodeColorSpace);

    SkImageInfo bitmapInfo = decodeInfo;
    if (decodeColorType == kGray_8_SkColorType) {
        // The legacy implementation of BitmapFactory used kAlpha8 for
        // grayscale images (before kGray8 existed).  While the codec
        // recognizes kGray8, we need to decode into a kAlpha8 bitmap
        // in order to avoid a behavior change.
        bitmapInfo =
                bitmapInfo.makeColorType(kAlpha_8_SkColorType).makeAlphaType(kPremul_SkAlphaType);
    }
    SkBitmap decodingBitmap;
    if (!decodingBitmap.setInfo(bitmapInfo) ||
            !decodingBitmap.tryAllocPixels(decodeAllocator)) {
        // SkAndroidCodec should recommend a valid SkImageInfo, so setInfo()
        // should only only fail if the calculated value for rowBytes is too
        // large.
        // tryAllocPixels() can fail due to OOM on the Java heap, OOM on the
        // native heap, or the recycled javaBitmap being too small to reuse.
        return nullptr;
    }

    // Use SkAndroidCodec to perform the decode.
    SkAndroidCodec::AndroidOptions codecOptions;
    codecOptions.fZeroInitialized = decodeAllocator == &defaultAllocator ?
            SkCodec::kYes_ZeroInitialized : SkCodec::kNo_ZeroInitialized;
    codecOptions.fSampleSize = sampleSize;
    SkCodec::Result result = codec->getAndroidPixels(decodeInfo, decodingBitmap.getPixels(),
            decodingBitmap.rowBytes(), &codecOptions);
    switch (result) {
        case SkCodec::kSuccess:
        case SkCodec::kIncompleteInput:
            break;
        default:
            return nullObjectReturn("codec->getAndroidPixels() failed.");
    }

    // This is weird so let me explain: we could use the scale parameter
    // directly, but for historical reasons this is how the corresponding
    // Dalvik code has always behaved. We simply recreate the behavior here.
    // The result is slightly different from simply using scale because of
    // the 0.5f rounding bias applied when computing the target image size
    const float scaleX = scaledWidth / float(decodingBitmap.width());
    const float scaleY = scaledHeight / float(decodingBitmap.height());

    jbyteArray ninePatchChunk = NULL;
    if (peeker.mPatch != NULL) {
        if (willScale) {
            peeker.scale(scaleX, scaleY, scaledWidth, scaledHeight);
        }

        size_t ninePatchArraySize = peeker.mPatch->serializedSize();
        ninePatchChunk = env->NewByteArray(ninePatchArraySize);
        if (ninePatchChunk == NULL) {
            return nullObjectReturn("ninePatchChunk == null");
        }

        jbyte* array = (jbyte*) env->GetPrimitiveArrayCritical(ninePatchChunk, NULL);
        if (array == NULL) {
            return nullObjectReturn("primitive array == null");
        }

        memcpy(array, peeker.mPatch, peeker.mPatchSize);
        env->ReleasePrimitiveArrayCritical(ninePatchChunk, array, 0);
    }

    jobject ninePatchInsets = NULL;
    if (peeker.mHasInsets) {
        ninePatchInsets = peeker.createNinePatchInsets(env, scale);
        if (ninePatchInsets == NULL) {
            return nullObjectReturn("nine patch insets == null");
        }
        if (javaBitmap != NULL) {
            env->SetObjectField(javaBitmap, gBitmap_ninePatchInsetsFieldID, ninePatchInsets);
        }
    }

    SkBitmap outputBitmap;
    if (willScale) {
        // Set the allocator for the outputBitmap.
        SkBitmap::Allocator* outputAllocator;
        if (javaBitmap != nullptr) {
            outputAllocator = &recyclingAllocator;
        } else {
            outputAllocator = &defaultAllocator;
        }

        SkColorType scaledColorType = decodingBitmap.colorType();
        // FIXME: If the alphaType is kUnpremul and the image has alpha, the
        // colors may not be correct, since Skia does not yet support drawing
        // to/from unpremultiplied bitmaps.
        outputBitmap.setInfo(
                bitmapInfo.makeWH(scaledWidth, scaledHeight).makeColorType(scaledColorType));
        if (!outputBitmap.tryAllocPixels(outputAllocator)) {
            // This should only fail on OOM.  The recyclingAllocator should have
            // enough memory since we check this before decoding using the
            // scaleCheckingAllocator.
            return nullObjectReturn("allocation failed for scaled bitmap");
        }

        SkPaint paint;
        // kSrc_Mode instructs us to overwrite the uninitialized pixels in
        // outputBitmap.  Otherwise we would blend by default, which is not
        // what we want.
        paint.setBlendMode(SkBlendMode::kSrc);

        SkCanvas canvas(outputBitmap, SkCanvas::ColorBehavior::kLegacy);
        canvas.scale(scaleX, scaleY);
        decodingBitmap.setImmutable(); // so .asImage() doesn't make a copy
        canvas.drawImage(decodingBitmap.asImage(), 0.0f, 0.0f,
                         SkSamplingOptions(SkFilterMode::kLinear), &paint);
    } else {
        outputBitmap.swap(decodingBitmap);
    }

    if (padding) {
        peeker.getPadding(env, padding);
    }

    // If we get here, the outputBitmap should have an installed pixelref.
    if (outputBitmap.pixelRef() == NULL) {
        return nullObjectReturn("Got null SkPixelRef");
    }

    bool hasGainmap = false;
    SkGainmapInfo gainmapInfo;
    std::unique_ptr<SkAndroidCodec> gainmapCodec;
    sp<uirenderer::Gainmap> gainmap = nullptr;
    if (result == SkCodec::kSuccess) {
        hasGainmap = codec->getGainmapAndroidCodec(&gainmapInfo, &gainmapCodec);
    }

    if (hasGainmap) {
        hasGainmap =
                decodeGainmap(std::move(gainmapCodec), gainmapInfo, &gainmap, sampleSize, scale);
    }

    if (!isMutable && javaBitmap == NULL) {
        // promise we will never change our pixels (great for sharing and pictures)
        outputBitmap.setImmutable();
    }

    bool isPremultiplied = !requireUnpremultiplied;
    if (javaBitmap != nullptr) {
        if (hasGainmap) {
            reuseBitmap->setGainmap(std::move(gainmap));
        }
        bitmap::reinitBitmap(env, javaBitmap, outputBitmap.info(), isPremultiplied);
        outputBitmap.notifyPixelsChanged();
        uirenderer::logBitmapDecode(*reuseBitmap);
        // If a java bitmap was passed in for reuse, pass it back
        return javaBitmap;
    }

    int bitmapCreateFlags = 0x0;
    if (isMutable) bitmapCreateFlags |= android::bitmap::kBitmapCreateFlag_Mutable;
    if (isPremultiplied) bitmapCreateFlags |= android::bitmap::kBitmapCreateFlag_Premultiplied;

    if (isHardware) {
        sk_sp<Bitmap> hardwareBitmap = Bitmap::allocateHardwareBitmap(outputBitmap);
        if (!hardwareBitmap.get()) {
            return nullObjectReturn("Failed to allocate a hardware bitmap");
        }
        if (hasGainmap) {
            auto gm = uirenderer::Gainmap::allocateHardwareGainmap(gainmap);
            if (gm) {
                hardwareBitmap->setGainmap(std::move(gm));
            }
        }

        uirenderer::logBitmapDecode(*hardwareBitmap);
        return bitmap::createBitmap(env, hardwareBitmap.release(), bitmapCreateFlags,
                ninePatchChunk, ninePatchInsets, -1);
    }

    Bitmap* heapBitmap = defaultAllocator.getStorageObjAndReset();
    if (hasGainmap && heapBitmap != nullptr) {
        heapBitmap->setGainmap(std::move(gainmap));
    }

    uirenderer::logBitmapDecode(*heapBitmap);
    // now create the java bitmap
    return bitmap::createBitmap(env, heapBitmap, bitmapCreateFlags, ninePatchChunk, ninePatchInsets,
                                -1);
}

static jobject nativeDecodeStream(JNIEnv* env, jobject clazz, jobject is, jbyteArray storage,
        jobject padding, jobject options, jlong inBitmapHandle, jlong colorSpaceHandle) {

    jobject bitmap = NULL;
    std::unique_ptr<SkStream> stream(CreateJavaInputStreamAdaptor(env, is, storage));

    if (stream.get()) {
        std::unique_ptr<SkStreamRewindable> bufferedStream(skia::FrontBufferedStream::Make(
                std::move(stream), SkCodec::MinBufferedBytesNeeded()));
        SkASSERT(bufferedStream.get() != NULL);
        bitmap = doDecode(env, std::move(bufferedStream), padding, options, inBitmapHandle,
                          colorSpaceHandle);
    }
    return bitmap;
}

static jobject nativeDecodeFileDescriptor(JNIEnv* env, jobject clazz, jobject fileDescriptor,
        jobject padding, jobject bitmapFactoryOptions, jlong inBitmapHandle, jlong colorSpaceHandle) {
#ifdef _WIN32  // LayoutLib for Windows does not support F_DUPFD_CLOEXEC
    return nullObjectReturn("Not supported on Windows");
#else
    NPE_CHECK_RETURN_ZERO(env, fileDescriptor);

    int descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);

    struct stat fdStat;
    if (fstat(descriptor, &fdStat) == -1) {
        doThrowIOE(env, "broken file descriptor");
        return nullObjectReturn("fstat return -1");
    }

    // Restore the descriptor's offset on exiting this function. Even though
    // we dup the descriptor, both the original and dup refer to the same open
    // file description and changes to the file offset in one impact the other.
    AutoFDSeek autoRestore(descriptor);

    // Duplicate the descriptor here to prevent leaking memory. A leak occurs
    // if we only close the file descriptor and not the file object it is used to
    // create.  If we don't explicitly clean up the file (which in turn closes the
    // descriptor) the buffers allocated internally by fseek will be leaked.
    int dupDescriptor = fcntl(descriptor, F_DUPFD_CLOEXEC, 0);

    FILE* file = fdopen(dupDescriptor, "r");
    if (file == NULL) {
        // cleanup the duplicated descriptor since it will not be closed when the
        // file is cleaned up (fclose).
        close(dupDescriptor);
        return nullObjectReturn("Could not open file");
    }

    std::unique_ptr<SkFILEStream> fileStream(new SkFILEStream(file));

    // If there is no offset for the file descriptor, we use SkFILEStream directly.
    if (::lseek(descriptor, 0, SEEK_CUR) == 0) {
        assert(isSeekable(dupDescriptor));
        return doDecode(env, std::move(fileStream), padding, bitmapFactoryOptions,
                        inBitmapHandle, colorSpaceHandle);
    }

    // Use a buffered stream. Although an SkFILEStream can be rewound, this
    // ensures that SkImageDecoder::Factory never rewinds beyond the
    // current position of the file descriptor.
    std::unique_ptr<SkStreamRewindable> stream(skia::FrontBufferedStream::Make(
            std::move(fileStream), SkCodec::MinBufferedBytesNeeded()));

    return doDecode(env, std::move(stream), padding, bitmapFactoryOptions, inBitmapHandle,
                    colorSpaceHandle);
#endif
}

static jobject nativeDecodeAsset(JNIEnv* env, jobject clazz, jlong native_asset,
        jobject padding, jobject options, jlong inBitmapHandle, jlong colorSpaceHandle) {

    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    // since we know we'll be done with the asset when we return, we can
    // just use a simple wrapper
    return doDecode(env, std::make_unique<AssetStreamAdaptor>(asset), padding, options,
                    inBitmapHandle, colorSpaceHandle);
}

static jobject nativeDecodeByteArray(JNIEnv* env, jobject, jbyteArray byteArray,
        jint offset, jint length, jobject options, jlong inBitmapHandle, jlong colorSpaceHandle) {

    AutoJavaByteArray ar(env, byteArray);
    return doDecode(env, std::make_unique<SkMemoryStream>(ar.ptr() + offset, length, false),
                    nullptr, options, inBitmapHandle, colorSpaceHandle);
}

static jboolean nativeIsSeekable(JNIEnv* env, jobject, jobject fileDescriptor) {
    jint descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);
    return isSeekable(descriptor) ? JNI_TRUE : JNI_FALSE;
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gMethods[] = {
    {   "nativeDecodeStream",
        "(Ljava/io/InputStream;[BLandroid/graphics/Rect;Landroid/graphics/BitmapFactory$Options;JJ)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeStream
    },

    {   "nativeDecodeFileDescriptor",
        "(Ljava/io/FileDescriptor;Landroid/graphics/Rect;Landroid/graphics/BitmapFactory$Options;JJ)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeFileDescriptor
    },

    {   "nativeDecodeAsset",
        "(JLandroid/graphics/Rect;Landroid/graphics/BitmapFactory$Options;JJ)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeAsset
    },

    {   "nativeDecodeByteArray",
        "([BIILandroid/graphics/BitmapFactory$Options;JJ)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeByteArray
    },

    {   "nativeIsSeekable",
        "(Ljava/io/FileDescriptor;)Z",
        (void*)nativeIsSeekable
    },
};

int register_android_graphics_BitmapFactory(JNIEnv* env) {
    jclass options_class = FindClassOrDie(env, "android/graphics/BitmapFactory$Options");
    gOptions_bitmapFieldID = GetFieldIDOrDie(env, options_class, "inBitmap",
            "Landroid/graphics/Bitmap;");
    gOptions_justBoundsFieldID = GetFieldIDOrDie(env, options_class, "inJustDecodeBounds", "Z");
    gOptions_sampleSizeFieldID = GetFieldIDOrDie(env, options_class, "inSampleSize", "I");
    gOptions_configFieldID = GetFieldIDOrDie(env, options_class, "inPreferredConfig",
            "Landroid/graphics/Bitmap$Config;");
    gOptions_colorSpaceFieldID = GetFieldIDOrDie(env, options_class, "inPreferredColorSpace",
            "Landroid/graphics/ColorSpace;");
    gOptions_premultipliedFieldID = GetFieldIDOrDie(env, options_class, "inPremultiplied", "Z");
    gOptions_mutableFieldID = GetFieldIDOrDie(env, options_class, "inMutable", "Z");
    gOptions_ditherFieldID = GetFieldIDOrDie(env, options_class, "inDither", "Z");
    gOptions_preferQualityOverSpeedFieldID = GetFieldIDOrDie(env, options_class,
            "inPreferQualityOverSpeed", "Z");
    gOptions_scaledFieldID = GetFieldIDOrDie(env, options_class, "inScaled", "Z");
    gOptions_densityFieldID = GetFieldIDOrDie(env, options_class, "inDensity", "I");
    gOptions_screenDensityFieldID = GetFieldIDOrDie(env, options_class, "inScreenDensity", "I");
    gOptions_targetDensityFieldID = GetFieldIDOrDie(env, options_class, "inTargetDensity", "I");
    gOptions_widthFieldID = GetFieldIDOrDie(env, options_class, "outWidth", "I");
    gOptions_heightFieldID = GetFieldIDOrDie(env, options_class, "outHeight", "I");
    gOptions_mimeFieldID = GetFieldIDOrDie(env, options_class, "outMimeType", "Ljava/lang/String;");
    gOptions_outConfigFieldID = GetFieldIDOrDie(env, options_class, "outConfig",
             "Landroid/graphics/Bitmap$Config;");
    gOptions_outColorSpaceFieldID = GetFieldIDOrDie(env, options_class, "outColorSpace",
             "Landroid/graphics/ColorSpace;");
    gOptions_mCancelID = GetFieldIDOrDie(env, options_class, "mCancel", "Z");

    jclass bitmap_class = FindClassOrDie(env, "android/graphics/Bitmap");
    gBitmap_ninePatchInsetsFieldID = GetFieldIDOrDie(env, bitmap_class, "mNinePatchInsets",
            "Landroid/graphics/NinePatch$InsetStruct;");

    gBitmapConfig_class = MakeGlobalRefOrDie(env, FindClassOrDie(env,
            "android/graphics/Bitmap$Config"));
    gBitmapConfig_nativeToConfigMethodID = GetStaticMethodIDOrDie(env, gBitmapConfig_class,
            "nativeToConfig", "(I)Landroid/graphics/Bitmap$Config;");

    return android::RegisterMethodsOrDie(env, "android/graphics/BitmapFactory",
                                         gMethods, NELEM(gMethods));
}
