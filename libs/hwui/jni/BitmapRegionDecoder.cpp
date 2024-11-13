/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include "BitmapRegionDecoder.h"

#include <HardwareBitmapUploader.h>
#include <androidfw/Asset.h>
#include <sys/stat.h>

#include <memory>

#include "BitmapFactory.h"
#include "CreateJavaOutputStreamAdaptor.h"
#include "Gainmap.h"
#include "GraphicsJNI.h"
#include "SkBitmap.h"
#include "SkCodec.h"
#include "SkColorSpace.h"
#include "SkData.h"
#include "SkGainmapInfo.h"
#include "SkStream.h"
#include "SkStreamPriv.h"
#include "Utils.h"

using namespace android;

namespace android {
class BitmapRegionDecoderWrapper {
public:
    static std::unique_ptr<BitmapRegionDecoderWrapper> Make(sk_sp<SkData> data) {
        std::unique_ptr<skia::BitmapRegionDecoder> mainImageBRD =
                skia::BitmapRegionDecoder::Make(std::move(data));
        if (!mainImageBRD) {
            return nullptr;
        }

        SkGainmapInfo gainmapInfo;
        std::unique_ptr<SkStream> gainmapStream;
        std::unique_ptr<skia::BitmapRegionDecoder> gainmapBRD = nullptr;
        if (mainImageBRD->getAndroidGainmap(&gainmapInfo, &gainmapStream)) {
            sk_sp<SkData> data = nullptr;
            if (gainmapStream->getMemoryBase()) {
                // It is safe to make without copy because we'll hold onto the stream.
                data = SkData::MakeWithoutCopy(gainmapStream->getMemoryBase(),
                                               gainmapStream->getLength());
            } else {
                data = SkCopyStreamToData(gainmapStream.get());
                // We don't need to hold the stream anymore
                gainmapStream = nullptr;
            }
            gainmapBRD = skia::BitmapRegionDecoder::Make(std::move(data));
        }

        return std::unique_ptr<BitmapRegionDecoderWrapper>(
                new BitmapRegionDecoderWrapper(std::move(mainImageBRD), std::move(gainmapBRD),
                                               gainmapInfo, std::move(gainmapStream)));
    }

    SkEncodedImageFormat getEncodedFormat() { return mMainImageBRD->getEncodedFormat(); }

    SkColorType computeOutputColorType(SkColorType requestedColorType) {
        return mMainImageBRD->computeOutputColorType(requestedColorType);
    }

    sk_sp<SkColorSpace> computeOutputColorSpace(SkColorType outputColorType,
                                                sk_sp<SkColorSpace> prefColorSpace = nullptr) {
        return mMainImageBRD->computeOutputColorSpace(outputColorType, prefColorSpace);
    }

    bool decodeRegion(SkBitmap* bitmap, skia::BRDAllocator* allocator, const SkIRect& desiredSubset,
                      int sampleSize, SkColorType colorType, bool requireUnpremul,
                      sk_sp<SkColorSpace> prefColorSpace) {
        return mMainImageBRD->decodeRegion(bitmap, allocator, desiredSubset, sampleSize, colorType,
                                           requireUnpremul, prefColorSpace);
    }

    // Decodes the gainmap region. If decoding succeeded, returns true and
    // populate outGainmap with the decoded gainmap. Otherwise, returns false.
    //
    // Note that the desiredSubset is the logical region within the source
    // gainmap that we want to decode. This is used for scaling into the final
    // bitmap, since we do not want to include portions of the gainmap outside
    // of this region. desiredSubset is also _not_ guaranteed to be
    // pixel-aligned, so it's not possible to simply resize the resulting
    // bitmap to accomplish this.
    bool decodeGainmapRegion(sp<uirenderer::Gainmap>* outGainmap, SkISize bitmapDimensions,
                             const SkRect& desiredSubset, int sampleSize, bool requireUnpremul) {
        SkColorType decodeColorType = mGainmapBRD->computeOutputColorType(kN32_SkColorType);
        sk_sp<SkColorSpace> decodeColorSpace =
                mGainmapBRD->computeOutputColorSpace(decodeColorType, nullptr);
        SkBitmap bm;
        // Because we must match the dimensions of the base bitmap, we always use a
        // recycling allocator even though we are allocating a new bitmap. This is to ensure
        // that if a recycled bitmap was used for the base image that we match the relative
        // dimensions of that base image. The behavior of BRD here is:
        // if inBitmap is specified -> output dimensions are always equal to the inBitmap's
        // if no bitmap is reused   -> output dimensions are the intersect of the desiredSubset &
        //                           the image bounds
        // The handling of the above conditionals are baked into the desiredSubset, so we
        // simply need to ensure that the resulting bitmap is the exact same width/height as
        // the specified desiredSubset regardless of the intersection to the image bounds.
        // kPremul_SkAlphaType is used just as a placeholder as it doesn't change the underlying
        // allocation type. RecyclingClippingPixelAllocator will populate this with the
        // actual alpha type in either allocPixelRef() or copyIfNecessary()
        sk_sp<Bitmap> nativeBitmap = Bitmap::allocateHeapBitmap(SkImageInfo::Make(
                bitmapDimensions, decodeColorType, kPremul_SkAlphaType, decodeColorSpace));
        if (!nativeBitmap) {
            ALOGE("OOM allocating Bitmap for Gainmap");
            return false;
        }

        // Round out the subset so that we decode a slightly larger region, in
        // case the subset has fractional components.
        SkIRect roundedSubset = desiredSubset.roundOut();

        // Map the desired subset to the space of the decoded gainmap. The
        // subset is repositioned relative to the resulting bitmap, and then
        // scaled to respect the sampleSize.
        // This assumes that the subset will not be modified by the decoder, which is true
        // for existing gainmap formats.
        SkRect logicalSubset = desiredSubset.makeOffset(-std::floorf(desiredSubset.left()),
                                                        -std::floorf(desiredSubset.top()));
        logicalSubset.fLeft /= sampleSize;
        logicalSubset.fTop /= sampleSize;
        logicalSubset.fRight /= sampleSize;
        logicalSubset.fBottom /= sampleSize;

        RecyclingClippingPixelAllocator allocator(nativeBitmap.get(), false, logicalSubset);
        if (!mGainmapBRD->decodeRegion(&bm, &allocator, roundedSubset, sampleSize, decodeColorType,
                                       requireUnpremul, decodeColorSpace)) {
            ALOGE("Error decoding Gainmap region");
            return false;
        }
        allocator.copyIfNecessary();
        auto gainmap = sp<uirenderer::Gainmap>::make();
        if (!gainmap) {
            ALOGE("OOM allocating Gainmap");
            return false;
        }
        gainmap->info = mGainmapInfo;
        gainmap->bitmap = std::move(nativeBitmap);
        *outGainmap = std::move(gainmap);
        return true;
    }

    struct Projection {
        SkRect srcRect;
        SkISize destSize;
    };
    Projection calculateGainmapRegion(const SkIRect& mainImageRegion, SkISize dimensions) {
        const float scaleX = ((float)mGainmapBRD->width()) / mMainImageBRD->width();
        const float scaleY = ((float)mGainmapBRD->height()) / mMainImageBRD->height();

        if (uirenderer::Properties::resampleGainmapRegions) {
            const auto srcRect = SkRect::MakeLTRB(
                    mainImageRegion.left() * scaleX, mainImageRegion.top() * scaleY,
                    mainImageRegion.right() * scaleX, mainImageRegion.bottom() * scaleY);
            // Request a slightly larger destination size so that the gainmap
            // subset we want fits entirely in this size.
            const auto destSize = SkISize::Make(std::ceil(dimensions.width() * scaleX),
                                                std::ceil(dimensions.height() * scaleY));
            return Projection{.srcRect = srcRect, .destSize = destSize};
        } else {
            const auto srcRect = SkRect::Make(SkIRect::MakeLTRB(
                    mainImageRegion.left() * scaleX, mainImageRegion.top() * scaleY,
                    mainImageRegion.right() * scaleX, mainImageRegion.bottom() * scaleY));
            const auto destSize =
                    SkISize::Make(dimensions.width() * scaleX, dimensions.height() * scaleY);
            return Projection{.srcRect = srcRect, .destSize = destSize};
        }
    }

    bool hasGainmap() { return mGainmapBRD != nullptr; }

    int width() const { return mMainImageBRD->width(); }
    int height() const { return mMainImageBRD->height(); }

private:
    BitmapRegionDecoderWrapper(std::unique_ptr<skia::BitmapRegionDecoder> mainImageBRD,
                               std::unique_ptr<skia::BitmapRegionDecoder> gainmapBRD,
                               SkGainmapInfo info, std::unique_ptr<SkStream> stream)
            : mMainImageBRD(std::move(mainImageBRD))
            , mGainmapBRD(std::move(gainmapBRD))
            , mGainmapInfo(info)
            , mGainmapStream(std::move(stream)) {}

    std::unique_ptr<skia::BitmapRegionDecoder> mMainImageBRD;
    std::unique_ptr<skia::BitmapRegionDecoder> mGainmapBRD;
    SkGainmapInfo mGainmapInfo;
    std::unique_ptr<SkStream> mGainmapStream;
};
}  // namespace android

static jobject createBitmapRegionDecoder(JNIEnv* env, sk_sp<SkData> data) {
    auto brd = android::BitmapRegionDecoderWrapper::Make(std::move(data));
    if (!brd) {
        doThrowIOE(env, "Image format not supported");
        return nullObjectReturn("CreateBitmapRegionDecoder returned null");
    }

    return GraphicsJNI::createBitmapRegionDecoder(env, brd.release());
}

static jobject nativeNewInstanceFromByteArray(JNIEnv* env, jobject, jbyteArray byteArray,
                                              jint offset, jint length) {
    AutoJavaByteArray ar(env, byteArray);
    return createBitmapRegionDecoder(env, SkData::MakeWithCopy(ar.ptr() + offset, length));
}

static jobject nativeNewInstanceFromFileDescriptor(JNIEnv* env, jobject clazz,
                                                   jobject fileDescriptor) {
    NPE_CHECK_RETURN_ZERO(env, fileDescriptor);

    jint descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);

    struct stat fdStat;
    if (fstat(descriptor, &fdStat) == -1) {
        doThrowIOE(env, "broken file descriptor");
        return nullObjectReturn("fstat return -1");
    }

    return createBitmapRegionDecoder(env, SkData::MakeFromFD(descriptor));
}

static jobject nativeNewInstanceFromStream(JNIEnv* env, jobject clazz, jobject is, // InputStream
                                           jbyteArray storage) { // byte[]
    jobject brd = nullptr;
    sk_sp<SkData> data = CopyJavaInputStream(env, is, storage);

    if (data) {
        brd = createBitmapRegionDecoder(env, std::move(data));
    }
    return brd;
}

static jobject nativeNewInstanceFromAsset(JNIEnv* env, jobject clazz, jlong native_asset) {
    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    sk_sp<SkData> data = CopyAssetToData(asset);
    if (!data) {
        return nullptr;
    }

    return createBitmapRegionDecoder(env, data);
}

/*
 * nine patch not supported
 * purgeable not supported
 * reportSizeToVM not supported
 */
static jobject nativeDecodeRegion(JNIEnv* env, jobject, jlong brdHandle, jint inputX,
        jint inputY, jint inputWidth, jint inputHeight, jobject options, jlong inBitmapHandle,
        jlong colorSpaceHandle) {

    // Set default options.
    int sampleSize = 1;
    SkColorType colorType = kN32_SkColorType;
    bool requireUnpremul = false;
    jobject javaBitmap = nullptr;
    bool isHardware = false;
    sk_sp<SkColorSpace> colorSpace = GraphicsJNI::getNativeColorSpace(colorSpaceHandle);
    // Update the default options with any options supplied by the client.
    if (NULL != options) {
        sampleSize = env->GetIntField(options, gOptions_sampleSizeFieldID);
        jobject jconfig = env->GetObjectField(options, gOptions_configFieldID);
        colorType = GraphicsJNI::getNativeBitmapColorType(env, jconfig);
        isHardware = GraphicsJNI::isHardwareConfig(env, jconfig);
        requireUnpremul = !env->GetBooleanField(options, gOptions_premultipliedFieldID);
        javaBitmap = env->GetObjectField(options, gOptions_bitmapFieldID);
        // The Java options of ditherMode and preferQualityOverSpeed are deprecated.  We will
        // ignore the values of these fields.

        // Initialize these fields to indicate a failure.  If the decode succeeds, we
        // will update them later on.
        env->SetIntField(options, gOptions_widthFieldID, -1);
        env->SetIntField(options, gOptions_heightFieldID, -1);
        env->SetObjectField(options, gOptions_mimeFieldID, 0);
        env->SetObjectField(options, gOptions_outConfigFieldID, 0);
        env->SetObjectField(options, gOptions_outColorSpaceFieldID, 0);
    }

    // Recycle a bitmap if possible.
    android::Bitmap* recycledBitmap = nullptr;
    if (javaBitmap) {
        recycledBitmap = &bitmap::toBitmap(inBitmapHandle);
        if (recycledBitmap->isImmutable()) {
            ALOGW("Warning: Reusing an immutable bitmap as an image decoder target.");
        }
    }

    auto* brd = reinterpret_cast<BitmapRegionDecoderWrapper*>(brdHandle);
    SkColorType decodeColorType = brd->computeOutputColorType(colorType);

    if (isHardware) {
        if (decodeColorType == kRGBA_F16_SkColorType &&
            !uirenderer::HardwareBitmapUploader::hasFP16Support()) {
            decodeColorType = kN32_SkColorType;
        }
        if (decodeColorType == kRGBA_1010102_SkColorType &&
            !uirenderer::HardwareBitmapUploader::has1010102Support()) {
            decodeColorType = kN32_SkColorType;
        }
    }

    // Set up the pixel allocator
    skia::BRDAllocator* allocator = nullptr;
    RecyclingClippingPixelAllocator recycleAlloc(recycledBitmap);
    HeapAllocator heapAlloc;
    if (javaBitmap) {
        allocator = &recycleAlloc;
        // We are required to match the color type of the recycled bitmap.
        decodeColorType = recycledBitmap->info().colorType();
    } else {
        allocator = &heapAlloc;
    }

    sk_sp<SkColorSpace> decodeColorSpace = brd->computeOutputColorSpace(
            decodeColorType, colorSpace);

    // Decode the region.
    const SkIRect subset = SkIRect::MakeXYWH(inputX, inputY, inputWidth, inputHeight);
    SkBitmap bitmap;
    if (!brd->decodeRegion(&bitmap, allocator, subset, sampleSize,
            decodeColorType, requireUnpremul, decodeColorSpace)) {
        return nullObjectReturn("Failed to decode region.");
    }

    // If the client provided options, indicate that the decode was successful.
    if (NULL != options) {
        env->SetIntField(options, gOptions_widthFieldID, bitmap.width());
        env->SetIntField(options, gOptions_heightFieldID, bitmap.height());

        env->SetObjectField(options, gOptions_mimeFieldID,
                getMimeTypeAsJavaString(env, brd->getEncodedFormat()));
        if (env->ExceptionCheck()) {
            return nullObjectReturn("OOM in encodedFormatToString()");
        }

        jint configID = GraphicsJNI::colorTypeToLegacyBitmapConfig(decodeColorType);
        if (isHardware) {
            configID = GraphicsJNI::kHardware_LegacyBitmapConfig;
        }
        jobject config = env->CallStaticObjectMethod(gBitmapConfig_class,
                gBitmapConfig_nativeToConfigMethodID, configID);
        env->SetObjectField(options, gOptions_outConfigFieldID, config);

        env->SetObjectField(options, gOptions_outColorSpaceFieldID,
                GraphicsJNI::getColorSpace(env, decodeColorSpace.get(), decodeColorType));
    }

    if (javaBitmap) {
        recycleAlloc.copyIfNecessary();
    }

    sp<uirenderer::Gainmap> gainmap;
    bool hasGainmap = brd->hasGainmap();
    if (hasGainmap) {
        SkISize gainmapDims = SkISize::Make(bitmap.width(), bitmap.height());
        if (javaBitmap) {
            // If we are recycling we must match the inBitmap's relative dimensions
            gainmapDims.fWidth = recycledBitmap->width();
            gainmapDims.fHeight = recycledBitmap->height();
        }
        BitmapRegionDecoderWrapper::Projection gainmapProjection =
                brd->calculateGainmapRegion(subset, gainmapDims);
        if (!brd->decodeGainmapRegion(&gainmap, gainmapProjection.destSize,
                                      gainmapProjection.srcRect, sampleSize, requireUnpremul)) {
            // If there is an error decoding Gainmap - we don't fail. We just don't provide Gainmap
            hasGainmap = false;
        }
    }

    // If we may have reused a bitmap, we need to indicate that the pixels have changed.
    if (javaBitmap) {
        if (hasGainmap) {
            recycledBitmap->setGainmap(std::move(gainmap));
        }
        bitmap::reinitBitmap(env, javaBitmap, recycledBitmap->info(), !requireUnpremul);
        return javaBitmap;
    }

    int bitmapCreateFlags = 0;
    if (!requireUnpremul) {
        bitmapCreateFlags |= android::bitmap::kBitmapCreateFlag_Premultiplied;
    }

    if (isHardware) {
        sk_sp<Bitmap> hardwareBitmap = Bitmap::allocateHardwareBitmap(bitmap);
        if (hasGainmap) {
            auto gm = uirenderer::Gainmap::allocateHardwareGainmap(gainmap);
            if (gm) {
                hardwareBitmap->setGainmap(std::move(gm));
            }
        }
        return bitmap::createBitmap(env, hardwareBitmap.release(), bitmapCreateFlags);
    }
    Bitmap* heapBitmap = heapAlloc.getStorageObjAndReset();
    if (hasGainmap && heapBitmap != nullptr) {
        heapBitmap->setGainmap(std::move(gainmap));
    }
    return android::bitmap::createBitmap(env, heapBitmap, bitmapCreateFlags);
}

static jint nativeGetHeight(JNIEnv* env, jobject, jlong brdHandle) {
    auto* brd = reinterpret_cast<BitmapRegionDecoderWrapper*>(brdHandle);
    return static_cast<jint>(brd->height());
}

static jint nativeGetWidth(JNIEnv* env, jobject, jlong brdHandle) {
    auto* brd = reinterpret_cast<BitmapRegionDecoderWrapper*>(brdHandle);
    return static_cast<jint>(brd->width());
}

static void nativeClean(JNIEnv* env, jobject, jlong brdHandle) {
    auto* brd = reinterpret_cast<BitmapRegionDecoderWrapper*>(brdHandle);
    delete brd;
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gBitmapRegionDecoderMethods[] = {
    {   "nativeDecodeRegion",
        "(JIIIILandroid/graphics/BitmapFactory$Options;JJ)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeRegion},

    {   "nativeGetHeight", "(J)I", (void*)nativeGetHeight},

    {   "nativeGetWidth", "(J)I", (void*)nativeGetWidth},

    {   "nativeClean", "(J)V", (void*)nativeClean},

    {   "nativeNewInstance",
        "([BII)Landroid/graphics/BitmapRegionDecoder;",
        (void*)nativeNewInstanceFromByteArray
    },

    {   "nativeNewInstance",
        "(Ljava/io/InputStream;[B)Landroid/graphics/BitmapRegionDecoder;",
        (void*)nativeNewInstanceFromStream
    },

    {   "nativeNewInstance",
        "(Ljava/io/FileDescriptor;)Landroid/graphics/BitmapRegionDecoder;",
        (void*)nativeNewInstanceFromFileDescriptor
    },

    {   "nativeNewInstance",
        "(J)Landroid/graphics/BitmapRegionDecoder;",
        (void*)nativeNewInstanceFromAsset
    },
};

int register_android_graphics_BitmapRegionDecoder(JNIEnv* env)
{
    return android::RegisterMethodsOrDie(env, "android/graphics/BitmapRegionDecoder",
            gBitmapRegionDecoderMethods, NELEM(gBitmapRegionDecoderMethods));
}
