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

#define LOG_TAG "BitmapRegionDecoder"

#include "BitmapFactory.h"
#include "CreateJavaOutputStreamAdaptor.h"
#include "GraphicsJNI.h"
#include "Utils.h"

#include "SkBitmap.h"
#include "SkBitmapRegionDecoder.h"
#include "SkCodec.h"
#include "SkData.h"
#include "SkUtils.h"
#include "SkPixelRef.h"
#include "SkStream.h"

#include "android_nio_utils.h"
#include "android_util_Binder.h"
#include "core_jni_helpers.h"

#include <nativehelper/JNIHelp.h>
#include <androidfw/Asset.h>
#include <binder/Parcel.h>
#include <jni.h>
#include <sys/stat.h>

#include <memory>

using namespace android;

static jobject createBitmapRegionDecoder(JNIEnv* env, std::unique_ptr<SkStreamRewindable> stream) {
  std::unique_ptr<SkBitmapRegionDecoder> brd(
            SkBitmapRegionDecoder::Create(stream.release(),
                                          SkBitmapRegionDecoder::kAndroidCodec_Strategy));
    if (!brd) {
        doThrowIOE(env, "Image format not supported");
        return nullObjectReturn("CreateBitmapRegionDecoder returned null");
    }

    return GraphicsJNI::createBitmapRegionDecoder(env, brd.release());
}

static jobject nativeNewInstanceFromByteArray(JNIEnv* env, jobject, jbyteArray byteArray,
                                     jint offset, jint length, jboolean isShareable) {
    /*  If isShareable we could decide to just wrap the java array and
        share it, but that means adding a globalref to the java array object
        For now we just always copy the array's data if isShareable.
     */
    AutoJavaByteArray ar(env, byteArray);
    std::unique_ptr<SkMemoryStream> stream(new SkMemoryStream(ar.ptr() + offset, length, true));

    // the decoder owns the stream.
    jobject brd = createBitmapRegionDecoder(env, std::move(stream));
    return brd;
}

static jobject nativeNewInstanceFromFileDescriptor(JNIEnv* env, jobject clazz,
                                          jobject fileDescriptor, jboolean isShareable) {
    NPE_CHECK_RETURN_ZERO(env, fileDescriptor);

    jint descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);

    struct stat fdStat;
    if (fstat(descriptor, &fdStat) == -1) {
        doThrowIOE(env, "broken file descriptor");
        return nullObjectReturn("fstat return -1");
    }

    sk_sp<SkData> data(SkData::MakeFromFD(descriptor));
    std::unique_ptr<SkMemoryStream> stream(new SkMemoryStream(std::move(data)));

    // the decoder owns the stream.
    jobject brd = createBitmapRegionDecoder(env, std::move(stream));
    return brd;
}

static jobject nativeNewInstanceFromStream(JNIEnv* env, jobject clazz,
                                  jobject is,       // InputStream
                                  jbyteArray storage, // byte[]
                                  jboolean isShareable) {
    jobject brd = NULL;
    // for now we don't allow shareable with java inputstreams
    std::unique_ptr<SkStreamRewindable> stream(CopyJavaInputStream(env, is, storage));

    if (stream) {
        // the decoder owns the stream.
        brd = createBitmapRegionDecoder(env, std::move(stream));
    }
    return brd;
}

static jobject nativeNewInstanceFromAsset(JNIEnv* env, jobject clazz,
                                 jlong native_asset, // Asset
                                 jboolean isShareable) {
    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    std::unique_ptr<SkMemoryStream> stream(CopyAssetToStream(asset));
    if (NULL == stream) {
        return NULL;
    }

    // the decoder owns the stream.
    jobject brd = createBitmapRegionDecoder(env, std::move(stream));
    return brd;
}

/*
 * nine patch not supported
 * purgeable not supported
 * reportSizeToVM not supported
 */
static jobject nativeDecodeRegion(JNIEnv* env, jobject, jlong brdHandle, jint inputX,
        jint inputY, jint inputWidth, jint inputHeight, jobject options) {

    // Set default options.
    int sampleSize = 1;
    SkColorType colorType = kN32_SkColorType;
    bool requireUnpremul = false;
    jobject javaBitmap = NULL;
    bool isHardware = false;
    sk_sp<SkColorSpace> colorSpace = nullptr;
    // Update the default options with any options supplied by the client.
    if (NULL != options) {
        sampleSize = env->GetIntField(options, gOptions_sampleSizeFieldID);
        jobject jconfig = env->GetObjectField(options, gOptions_configFieldID);
        colorType = GraphicsJNI::getNativeBitmapColorType(env, jconfig);
        jobject jcolorSpace = env->GetObjectField(options, gOptions_colorSpaceFieldID);
        colorSpace = GraphicsJNI::getNativeColorSpace(env, jcolorSpace);
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
    size_t recycledBytes = 0;
    if (javaBitmap) {
        recycledBitmap = &bitmap::toBitmap(env, javaBitmap);
        if (recycledBitmap->isImmutable()) {
            ALOGW("Warning: Reusing an immutable bitmap as an image decoder target.");
        }
        recycledBytes = bitmap::getBitmapAllocationByteCount(env, javaBitmap);
    }

    SkBitmapRegionDecoder* brd = reinterpret_cast<SkBitmapRegionDecoder*>(brdHandle);
    SkColorType decodeColorType = brd->computeOutputColorType(colorType);

    // Set up the pixel allocator
    SkBRDAllocator* allocator = nullptr;
    RecyclingClippingPixelAllocator recycleAlloc(recycledBitmap, recycledBytes);
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
    SkIRect subset = SkIRect::MakeXYWH(inputX, inputY, inputWidth, inputHeight);
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
                encodedFormatToString(env, (SkEncodedImageFormat)brd->getEncodedFormat()));
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
                GraphicsJNI::getColorSpace(env, decodeColorSpace, decodeColorType));
    }

    // If we may have reused a bitmap, we need to indicate that the pixels have changed.
    if (javaBitmap) {
        recycleAlloc.copyIfNecessary();
        bitmap::reinitBitmap(env, javaBitmap, recycledBitmap->info(), !requireUnpremul);
        return javaBitmap;
    }

    int bitmapCreateFlags = 0;
    if (!requireUnpremul) {
        bitmapCreateFlags |= android::bitmap::kBitmapCreateFlag_Premultiplied;
    }
    if (isHardware) {
        sk_sp<Bitmap> hardwareBitmap = Bitmap::allocateHardwareBitmap(bitmap);
        return bitmap::createBitmap(env, hardwareBitmap.release(), bitmapCreateFlags);
    }
    return android::bitmap::createBitmap(env, heapAlloc.getStorageObjAndReset(), bitmapCreateFlags);
}

static jint nativeGetHeight(JNIEnv* env, jobject, jlong brdHandle) {
    SkBitmapRegionDecoder* brd =
            reinterpret_cast<SkBitmapRegionDecoder*>(brdHandle);
    return static_cast<jint>(brd->height());
}

static jint nativeGetWidth(JNIEnv* env, jobject, jlong brdHandle) {
    SkBitmapRegionDecoder* brd =
            reinterpret_cast<SkBitmapRegionDecoder*>(brdHandle);
    return static_cast<jint>(brd->width());
}

static void nativeClean(JNIEnv* env, jobject, jlong brdHandle) {
    SkBitmapRegionDecoder* brd =
            reinterpret_cast<SkBitmapRegionDecoder*>(brdHandle);
    delete brd;
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gBitmapRegionDecoderMethods[] = {
    {   "nativeDecodeRegion",
        "(JIIIILandroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeRegion},

    {   "nativeGetHeight", "(J)I", (void*)nativeGetHeight},

    {   "nativeGetWidth", "(J)I", (void*)nativeGetWidth},

    {   "nativeClean", "(J)V", (void*)nativeClean},

    {   "nativeNewInstance",
        "([BIIZ)Landroid/graphics/BitmapRegionDecoder;",
        (void*)nativeNewInstanceFromByteArray
    },

    {   "nativeNewInstance",
        "(Ljava/io/InputStream;[BZ)Landroid/graphics/BitmapRegionDecoder;",
        (void*)nativeNewInstanceFromStream
    },

    {   "nativeNewInstance",
        "(Ljava/io/FileDescriptor;Z)Landroid/graphics/BitmapRegionDecoder;",
        (void*)nativeNewInstanceFromFileDescriptor
    },

    {   "nativeNewInstance",
        "(JZ)Landroid/graphics/BitmapRegionDecoder;",
        (void*)nativeNewInstanceFromAsset
    },
};

int register_android_graphics_BitmapRegionDecoder(JNIEnv* env)
{
    return android::RegisterMethodsOrDie(env, "android/graphics/BitmapRegionDecoder",
            gBitmapRegionDecoderMethods, NELEM(gBitmapRegionDecoderMethods));
}
