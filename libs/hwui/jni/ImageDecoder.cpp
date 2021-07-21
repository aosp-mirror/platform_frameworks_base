/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "Bitmap.h"
#include "BitmapFactory.h"
#include "ByteBufferStreamAdaptor.h"
#include "CreateJavaOutputStreamAdaptor.h"
#include "GraphicsJNI.h"
#include "ImageDecoder.h"
#include "NinePatchPeeker.h"
#include "Utils.h"

#include <hwui/Bitmap.h>
#include <hwui/ImageDecoder.h>
#include <HardwareBitmapUploader.h>

#include <FrontBufferedStream.h>
#include <SkAndroidCodec.h>
#include <SkEncodedImageFormat.h>
#include <SkStream.h>

#include <androidfw/Asset.h>
#include <fcntl.h>
#include <sys/stat.h>

using namespace android;

static jclass    gImageDecoder_class;
static jclass    gSize_class;
static jclass    gDecodeException_class;
static jclass    gCanvas_class;
static jmethodID gImageDecoder_constructorMethodID;
static jmethodID gImageDecoder_postProcessMethodID;
static jmethodID gSize_constructorMethodID;
static jmethodID gDecodeException_constructorMethodID;
static jmethodID gCallback_onPartialImageMethodID;
static jmethodID gCanvas_constructorMethodID;
static jmethodID gCanvas_releaseMethodID;

// These need to stay in sync with ImageDecoder.java's Allocator constants.
enum Allocator {
    kDefault_Allocator      = 0,
    kSoftware_Allocator     = 1,
    kSharedMemory_Allocator = 2,
    kHardware_Allocator     = 3,
};

// These need to stay in sync with ImageDecoder.java's Error constants.
enum Error {
    kSourceException     = 1,
    kSourceIncomplete    = 2,
    kSourceMalformedData = 3,
};

// These need to stay in sync with PixelFormat.java's Format constants.
enum PixelFormat {
    kUnknown     =  0,
    kTranslucent = -3,
    kOpaque      = -1,
};

// Clear and return any pending exception for handling other than throwing directly.
static jthrowable get_and_clear_exception(JNIEnv* env) {
    jthrowable jexception = env->ExceptionOccurred();
    if (jexception) {
        env->ExceptionClear();
    }
    return jexception;
}

// Throw a new ImageDecoder.DecodeException. Returns null for convenience.
static jobject throw_exception(JNIEnv* env, Error error, const char* msg,
                               jthrowable cause, jobject source) {
    jstring jstr = nullptr;
    if (msg) {
        jstr = env->NewStringUTF(msg);
        if (!jstr) {
            // Out of memory.
            return nullptr;
        }
    }
    jthrowable exception = (jthrowable) env->NewObject(gDecodeException_class,
            gDecodeException_constructorMethodID, error, jstr, cause, source);
    // Only throw if not out of memory.
    if (exception) {
        env->Throw(exception);
    }
    return nullptr;
}

static jobject native_create(JNIEnv* env, std::unique_ptr<SkStream> stream,
        jobject source, jboolean preferAnimation) {
    if (!stream.get()) {
        return throw_exception(env, kSourceMalformedData, "Failed to create a stream",
                               nullptr, source);
    }
    sk_sp<NinePatchPeeker> peeker(new NinePatchPeeker);
    SkCodec::Result result;
    auto codec = SkCodec::MakeFromStream(
            std::move(stream), &result, peeker.get(),
            preferAnimation ? SkCodec::SelectionPolicy::kPreferAnimation
                            : SkCodec::SelectionPolicy::kPreferStillImage);
    if (jthrowable jexception = get_and_clear_exception(env)) {
        return throw_exception(env, kSourceException, "", jexception, source);
    }
    if (!codec) {
        switch (result) {
            case SkCodec::kIncompleteInput:
                return throw_exception(env, kSourceIncomplete, "", nullptr, source);
            default:
                SkString msg;
                msg.printf("Failed to create image decoder with message '%s'",
                           SkCodec::ResultToString(result));
                return throw_exception(env, kSourceMalformedData,  msg.c_str(),
                                       nullptr, source);

        }
    }

    const bool animated = codec->getFrameCount() > 1;
    if (jthrowable jexception = get_and_clear_exception(env)) {
        return throw_exception(env, kSourceException, "", jexception, source);
    }

    auto androidCodec = SkAndroidCodec::MakeFromCodec(std::move(codec));
    if (!androidCodec.get()) {
        return throw_exception(env, kSourceMalformedData, "", nullptr, source);
    }

    const bool isNinePatch = peeker->mPatch != nullptr;
    ImageDecoder* decoder = new ImageDecoder(std::move(androidCodec), std::move(peeker),
                                             SkCodec::kYes_ZeroInitialized);
    return env->NewObject(gImageDecoder_class, gImageDecoder_constructorMethodID,
                          reinterpret_cast<jlong>(decoder), decoder->width(), decoder->height(),
                          animated, isNinePatch);
}

static jobject ImageDecoder_nCreateFd(JNIEnv* env, jobject /*clazz*/,
        jobject fileDescriptor, jlong length, jboolean preferAnimation, jobject source) {
#ifndef __ANDROID__ // LayoutLib for Windows does not support F_DUPFD_CLOEXEC
    return throw_exception(env, kSourceException, "Only supported on Android", nullptr, source);
#else
    int descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);

    struct stat fdStat;
    if (fstat(descriptor, &fdStat) == -1) {
        return throw_exception(env, kSourceMalformedData,
                               "broken file descriptor; fstat returned -1", nullptr, source);
    }

    int dupDescriptor = fcntl(descriptor, F_DUPFD_CLOEXEC, 0);
    FILE* file = fdopen(dupDescriptor, "r");
    if (file == NULL) {
        close(dupDescriptor);
        return throw_exception(env, kSourceMalformedData, "Could not open file",
                               nullptr, source);
    }

    std::unique_ptr<SkFILEStream> fileStream;
    if (length == -1) {
        // -1 corresponds to AssetFileDescriptor.UNKNOWN_LENGTH. Pass no length
        // so SkFILEStream will figure out the size of the file on its own.
        fileStream.reset(new SkFILEStream(file));
    } else {
        fileStream.reset(new SkFILEStream(file, length));
    }
    return native_create(env, std::move(fileStream), source, preferAnimation);
#endif
}

static jobject ImageDecoder_nCreateInputStream(JNIEnv* env, jobject /*clazz*/,
        jobject is, jbyteArray storage, jboolean preferAnimation, jobject source) {
    std::unique_ptr<SkStream> stream(CreateJavaInputStreamAdaptor(env, is, storage, false));

    if (!stream.get()) {
        return throw_exception(env, kSourceMalformedData, "Failed to create a stream",
                               nullptr, source);
    }

    std::unique_ptr<SkStream> bufferedStream(
            skia::FrontBufferedStream::Make(std::move(stream), SkCodec::MinBufferedBytesNeeded()));
    return native_create(env, std::move(bufferedStream), source, preferAnimation);
}

static jobject ImageDecoder_nCreateAsset(JNIEnv* env, jobject /*clazz*/,
        jlong assetPtr, jboolean preferAnimation, jobject source) {
    Asset* asset = reinterpret_cast<Asset*>(assetPtr);
    std::unique_ptr<SkStream> stream(new AssetStreamAdaptor(asset));
    return native_create(env, std::move(stream), source, preferAnimation);
}

static jobject ImageDecoder_nCreateByteBuffer(JNIEnv* env, jobject /*clazz*/,
        jobject jbyteBuffer, jint initialPosition, jint limit,
        jboolean preferAnimation, jobject source) {
    std::unique_ptr<SkStream> stream = CreateByteBufferStreamAdaptor(env, jbyteBuffer,
                                                                     initialPosition, limit);
    if (!stream) {
        return throw_exception(env, kSourceMalformedData, "Failed to read ByteBuffer",
                               nullptr, source);
    }
    return native_create(env, std::move(stream), source, preferAnimation);
}

static jobject ImageDecoder_nCreateByteArray(JNIEnv* env, jobject /*clazz*/,
        jbyteArray byteArray, jint offset, jint length,
        jboolean preferAnimation, jobject source) {
    std::unique_ptr<SkStream> stream(CreateByteArrayStreamAdaptor(env, byteArray, offset, length));
    return native_create(env, std::move(stream), source, preferAnimation);
}

jint postProcessAndRelease(JNIEnv* env, jobject jimageDecoder, std::unique_ptr<Canvas> canvas) {
    jobject jcanvas = env->NewObject(gCanvas_class, gCanvas_constructorMethodID,
                                     reinterpret_cast<jlong>(canvas.get()));
    if (!jcanvas) {
        doThrowOOME(env, "Failed to create Java Canvas for PostProcess!");
        return kUnknown;
    }

    // jcanvas now owns canvas.
    canvas.release();

    return env->CallIntMethod(jimageDecoder, gImageDecoder_postProcessMethodID, jcanvas);
}

static jobject ImageDecoder_nDecodeBitmap(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                          jobject jdecoder, jboolean jpostProcess,
                                          jint targetWidth, jint targetHeight, jobject jsubset,
                                          jboolean requireMutable, jint allocator,
                                          jboolean requireUnpremul, jboolean preferRamOverQuality,
                                          jboolean asAlphaMask, jlong colorSpaceHandle,
                                          jboolean extended) {
    auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
    if (!decoder->setTargetSize(targetWidth, targetHeight)) {
        doThrowISE(env, "Could not scale to target size!");
        return nullptr;
    }
    if (requireUnpremul && !decoder->setUnpremultipliedRequired(true)) {
        doThrowISE(env, "Cannot scale unpremultiplied pixels!");
        return nullptr;
    }

    SkColorType colorType = kN32_SkColorType;
    if (asAlphaMask && decoder->gray()) {
        // We have to trick Skia to decode this to a single channel.
        colorType = kGray_8_SkColorType;
    } else if (preferRamOverQuality) {
        // FIXME: The post-process might add alpha, which would make a 565
        // result incorrect. If we call the postProcess before now and record
        // to a picture, we can know whether alpha was added, and if not, we
        // can still use 565.
        if (decoder->opaque() && !jpostProcess) {
            // If the final result will be hardware, decoding to 565 and then
            // uploading to the gpu as 8888 will not save memory. This still
            // may save us from using F16, but do not go down to 565.
            if (allocator != kHardware_Allocator &&
               (allocator != kDefault_Allocator || requireMutable)) {
                colorType = kRGB_565_SkColorType;
            }
        }
        // Otherwise, stick with N32
    } else if (extended) {
        colorType = kRGBA_F16_SkColorType;
    } else {
        colorType = decoder->mCodec->computeOutputColorType(colorType);
    }

    const bool isHardware = !requireMutable
        && (allocator == kDefault_Allocator ||
            allocator == kHardware_Allocator)
        && colorType != kGray_8_SkColorType;

    if (colorType == kRGBA_F16_SkColorType && isHardware &&
            !uirenderer::HardwareBitmapUploader::hasFP16Support()) {
        colorType = kN32_SkColorType;
    }

    if (!decoder->setOutColorType(colorType)) {
        doThrowISE(env, "Failed to set out color type!");
        return nullptr;
    }

    {
        sk_sp<SkColorSpace> colorSpace = GraphicsJNI::getNativeColorSpace(colorSpaceHandle);
        colorSpace = decoder->mCodec->computeOutputColorSpace(colorType, colorSpace);
        decoder->setOutColorSpace(std::move(colorSpace));
    }

    if (jsubset) {
        SkIRect subset;
        GraphicsJNI::jrect_to_irect(env, jsubset, &subset);
        if (!decoder->setCropRect(&subset)) {
            doThrowISE(env, "Invalid crop rect!");
            return nullptr;
        }
    }

    SkImageInfo bitmapInfo = decoder->getOutputInfo();
    if (asAlphaMask && colorType == kGray_8_SkColorType) {
        bitmapInfo = bitmapInfo.makeColorType(kAlpha_8_SkColorType);
    }

    SkBitmap bm;
    if (!bm.setInfo(bitmapInfo)) {
        doThrowIOE(env, "Failed to setInfo properly");
        return nullptr;
    }

    sk_sp<Bitmap> nativeBitmap;
    if (allocator == kSharedMemory_Allocator) {
        nativeBitmap = Bitmap::allocateAshmemBitmap(&bm);
    } else {
        nativeBitmap = Bitmap::allocateHeapBitmap(&bm);
    }
    if (!nativeBitmap) {
        SkString msg;
        msg.printf("OOM allocating Bitmap with dimensions %i x %i",
                bitmapInfo.width(), bitmapInfo.height());
        doThrowOOME(env, msg.c_str());
        return nullptr;
    }

    SkCodec::Result result = decoder->decode(bm.getPixels(), bm.rowBytes());
    jthrowable jexception = get_and_clear_exception(env);
    int onPartialImageError = jexception ? kSourceException
                                         : 0; // No error.
    switch (result) {
        case SkCodec::kSuccess:
            // Ignore the exception, since the decode was successful anyway.
            jexception = nullptr;
            onPartialImageError = 0;
            break;
        case SkCodec::kIncompleteInput:
            if (!jexception) {
                onPartialImageError = kSourceIncomplete;
            }
            break;
        case SkCodec::kErrorInInput:
            if (!jexception) {
                onPartialImageError = kSourceMalformedData;
            }
            break;
        default:
            SkString msg;
            msg.printf("getPixels failed with error %s", SkCodec::ResultToString(result));
            doThrowIOE(env, msg.c_str());
            return nullptr;
    }

    if (onPartialImageError) {
        env->CallVoidMethod(jdecoder, gCallback_onPartialImageMethodID, onPartialImageError,
                jexception);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
    }

    jbyteArray ninePatchChunk = nullptr;
    jobject ninePatchInsets = nullptr;

    // Ignore ninepatch when post-processing.
    if (!jpostProcess) {
        // FIXME: Share more code with BitmapFactory.cpp.
        auto* peeker = reinterpret_cast<NinePatchPeeker*>(decoder->mPeeker.get());
        if (peeker->mPatch != nullptr) {
            size_t ninePatchArraySize = peeker->mPatch->serializedSize();
            ninePatchChunk = env->NewByteArray(ninePatchArraySize);
            if (ninePatchChunk == nullptr) {
                doThrowOOME(env, "Failed to allocate nine patch chunk.");
                return nullptr;
            }

            env->SetByteArrayRegion(ninePatchChunk, 0, peeker->mPatchSize,
                                    reinterpret_cast<jbyte*>(peeker->mPatch));
        }

        if (peeker->mHasInsets) {
            ninePatchInsets = peeker->createNinePatchInsets(env, 1.0f);
            if (ninePatchInsets == nullptr) {
                doThrowOOME(env, "Failed to allocate nine patch insets.");
                return nullptr;
            }
        }
    }

    if (jpostProcess) {
        std::unique_ptr<Canvas> canvas(Canvas::create_canvas(bm));

        jint pixelFormat = postProcessAndRelease(env, jdecoder, std::move(canvas));
        if (env->ExceptionCheck()) {
            return nullptr;
        }

        SkAlphaType newAlphaType = bm.alphaType();
        switch (pixelFormat) {
            case kUnknown:
                break;
            case kTranslucent:
                newAlphaType = kPremul_SkAlphaType;
                break;
            case kOpaque:
                newAlphaType = kOpaque_SkAlphaType;
                break;
            default:
                SkString msg;
                msg.printf("invalid return from postProcess: %i", pixelFormat);
                doThrowIAE(env, msg.c_str());
                return nullptr;
        }

        if (newAlphaType != bm.alphaType()) {
            if (!bm.setAlphaType(newAlphaType)) {
                SkString msg;
                msg.printf("incompatible return from postProcess: %i", pixelFormat);
                doThrowIAE(env, msg.c_str());
                return nullptr;
            }
            nativeBitmap->setAlphaType(newAlphaType);
        }
    }

    int bitmapCreateFlags = 0x0;
    if (!requireUnpremul) {
        // Even if the image is opaque, setting this flag means that
        // if alpha is added (e.g. by PostProcess), it will be marked as
        // premultiplied.
        bitmapCreateFlags |= bitmap::kBitmapCreateFlag_Premultiplied;
    }

    if (requireMutable) {
        bitmapCreateFlags |= bitmap::kBitmapCreateFlag_Mutable;
    } else {
        if (isHardware) {
            sk_sp<Bitmap> hwBitmap = Bitmap::allocateHardwareBitmap(bm);
            if (hwBitmap) {
                hwBitmap->setImmutable();
                return bitmap::createBitmap(env, hwBitmap.release(), bitmapCreateFlags,
                                            ninePatchChunk, ninePatchInsets);
            }
            if (allocator == kHardware_Allocator) {
                doThrowOOME(env, "failed to allocate hardware Bitmap!");
                return nullptr;
            }
            // If we failed to create a hardware bitmap, go ahead and create a
            // software one.
        }

        nativeBitmap->setImmutable();
    }
    return bitmap::createBitmap(env, nativeBitmap.release(), bitmapCreateFlags, ninePatchChunk,
                                ninePatchInsets);
}

static jobject ImageDecoder_nGetSampledSize(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                            jint sampleSize) {
    auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
    SkISize size = decoder->getSampledDimensions(sampleSize);
    return env->NewObject(gSize_class, gSize_constructorMethodID, size.width(), size.height());
}

static void ImageDecoder_nGetPadding(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                     jobject outPadding) {
    auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
    reinterpret_cast<NinePatchPeeker*>(decoder->mPeeker.get())->getPadding(env, outPadding);
}

static void ImageDecoder_nClose(JNIEnv* /*env*/, jobject /*clazz*/, jlong nativePtr) {
    delete reinterpret_cast<ImageDecoder*>(nativePtr);
}

static jstring ImageDecoder_nGetMimeType(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
    return getMimeTypeAsJavaString(env, decoder->mCodec->getEncodedFormat());
}

static jobject ImageDecoder_nGetColorSpace(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* codec = reinterpret_cast<ImageDecoder*>(nativePtr)->mCodec.get();
    auto colorType = codec->computeOutputColorType(kN32_SkColorType);
    sk_sp<SkColorSpace> colorSpace = codec->computeOutputColorSpace(colorType);
    return GraphicsJNI::getColorSpace(env, colorSpace.get(), colorType);
}

static const JNINativeMethod gImageDecoderMethods[] = {
    { "nCreate",        "(JZLandroid/graphics/ImageDecoder$Source;)Landroid/graphics/ImageDecoder;",    (void*) ImageDecoder_nCreateAsset },
    { "nCreate",        "(Ljava/nio/ByteBuffer;IIZLandroid/graphics/ImageDecoder$Source;)Landroid/graphics/ImageDecoder;", (void*) ImageDecoder_nCreateByteBuffer },
    { "nCreate",        "([BIIZLandroid/graphics/ImageDecoder$Source;)Landroid/graphics/ImageDecoder;", (void*) ImageDecoder_nCreateByteArray },
    { "nCreate",        "(Ljava/io/InputStream;[BZLandroid/graphics/ImageDecoder$Source;)Landroid/graphics/ImageDecoder;", (void*) ImageDecoder_nCreateInputStream },
    { "nCreate",        "(Ljava/io/FileDescriptor;JZLandroid/graphics/ImageDecoder$Source;)Landroid/graphics/ImageDecoder;", (void*) ImageDecoder_nCreateFd },
    { "nDecodeBitmap",  "(JLandroid/graphics/ImageDecoder;ZIILandroid/graphics/Rect;ZIZZZJZ)Landroid/graphics/Bitmap;",
                                                                 (void*) ImageDecoder_nDecodeBitmap },
    { "nGetSampledSize","(JI)Landroid/util/Size;",               (void*) ImageDecoder_nGetSampledSize },
    { "nGetPadding",    "(JLandroid/graphics/Rect;)V",           (void*) ImageDecoder_nGetPadding },
    { "nClose",         "(J)V",                                  (void*) ImageDecoder_nClose},
    { "nGetMimeType",   "(J)Ljava/lang/String;",                 (void*) ImageDecoder_nGetMimeType },
    { "nGetColorSpace", "(J)Landroid/graphics/ColorSpace;",      (void*) ImageDecoder_nGetColorSpace },
};

int register_android_graphics_ImageDecoder(JNIEnv* env) {
    gImageDecoder_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/ImageDecoder"));
    gImageDecoder_constructorMethodID = GetMethodIDOrDie(env, gImageDecoder_class, "<init>", "(JIIZZ)V");
    gImageDecoder_postProcessMethodID = GetMethodIDOrDie(env, gImageDecoder_class, "postProcessAndRelease", "(Landroid/graphics/Canvas;)I");

    gSize_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/util/Size"));
    gSize_constructorMethodID = GetMethodIDOrDie(env, gSize_class, "<init>", "(II)V");

    gDecodeException_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/ImageDecoder$DecodeException"));
    gDecodeException_constructorMethodID = GetMethodIDOrDie(env, gDecodeException_class, "<init>", "(ILjava/lang/String;Ljava/lang/Throwable;Landroid/graphics/ImageDecoder$Source;)V");

    gCallback_onPartialImageMethodID = GetMethodIDOrDie(env, gImageDecoder_class, "onPartialImage", "(ILjava/lang/Throwable;)V");

    gCanvas_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Canvas"));
    gCanvas_constructorMethodID = GetMethodIDOrDie(env, gCanvas_class, "<init>", "(J)V");
    gCanvas_releaseMethodID = GetMethodIDOrDie(env, gCanvas_class, "release", "()V");

    return android::RegisterMethodsOrDie(env, "android/graphics/ImageDecoder", gImageDecoderMethods,
                                         NELEM(gImageDecoderMethods));
}
