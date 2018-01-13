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
#include "NinePatchPeeker.h"
#include "Utils.h"
#include "core_jni_helpers.h"

#include <hwui/Bitmap.h>
#include <hwui/Canvas.h>

#include <SkAndroidCodec.h>
#include <SkEncodedImageFormat.h>
#include <SkFrontBufferedStream.h>
#include <SkStream.h>

#include <androidfw/Asset.h>
#include <jni.h>
#include <sys/stat.h>

using namespace android;

static jclass    gImageDecoder_class;
static jclass    gPoint_class;
static jclass    gIncomplete_class;
static jclass    gCorrupt_class;
static jclass    gCanvas_class;
static jmethodID gImageDecoder_constructorMethodID;
static jmethodID gPoint_constructorMethodID;
static jmethodID gIncomplete_constructorMethodID;
static jmethodID gCorrupt_constructorMethodID;
static jmethodID gCallback_onPartialImageMethodID;
static jmethodID gPostProcess_postProcessMethodID;
static jmethodID gCanvas_constructorMethodID;
static jmethodID gCanvas_releaseMethodID;

struct ImageDecoder {
    // These need to stay in sync with ImageDecoder.java's Allocator constants.
    enum Allocator {
        kDefault_Allocator      = 0,
        kSoftware_Allocator     = 1,
        kSharedMemory_Allocator = 2,
        kHardware_Allocator     = 3,
    };

    // These need to stay in sync with PixelFormat.java's Format constants.
    enum PixelFormat {
        kUnknown     =  0,
        kTranslucent = -3,
        kOpaque      = -1,
    };

    NinePatchPeeker mPeeker;
    std::unique_ptr<SkAndroidCodec> mCodec;
};

static jobject native_create(JNIEnv* env, std::unique_ptr<SkStream> stream) {
    if (!stream.get()) {
        doThrowIOE(env, "Failed to create a stream");
        return nullptr;
    }
    std::unique_ptr<ImageDecoder> decoder(new ImageDecoder);
    decoder->mCodec = SkAndroidCodec::MakeFromStream(std::move(stream), &decoder->mPeeker);
    if (!decoder->mCodec.get()) {
        // FIXME: (b/71578461) Use the error message from
        // SkCodec::MakeFromStream to report a more informative error message.
        doThrowIOE(env, "Failed to create an SkCodec");
        return nullptr;
    }

    const auto& info = decoder->mCodec->getInfo();
    const int width = info.width();
    const int height = info.height();
    return env->NewObject(gImageDecoder_class, gImageDecoder_constructorMethodID,
                          reinterpret_cast<jlong>(decoder.release()), width, height);
}

static jobject ImageDecoder_nCreateFd(JNIEnv* env, jobject /*clazz*/,
        jobject fileDescriptor) {
    int descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);

    struct stat fdStat;
    if (fstat(descriptor, &fdStat) == -1) {
        doThrowIOE(env, "broken file descriptor; fstat returned -1");
        return nullptr;
    }

    int dupDescriptor = dup(descriptor);
    FILE* file = fdopen(dupDescriptor, "r");
    if (file == NULL) {
        close(dupDescriptor);
        doThrowIOE(env, "Could not open file");
        return nullptr;
    }
    std::unique_ptr<SkFILEStream> fileStream(new SkFILEStream(file));

    if (::lseek(descriptor, 0, SEEK_CUR) == 0) {
        return native_create(env, std::move(fileStream));
    }

    // FIXME: This allows us to pretend the current location is the beginning,
    // but it would be better if SkFILEStream allowed treating its starting
    // point as the beginning.
    std::unique_ptr<SkStream> stream(SkFrontBufferedStream::Make(std::move(fileStream),
                SkCodec::MinBufferedBytesNeeded()));
    return native_create(env, std::move(stream));
}

static jobject ImageDecoder_nCreateInputStream(JNIEnv* env, jobject /*clazz*/,
        jobject is, jbyteArray storage) {
    std::unique_ptr<SkStream> stream(CreateJavaInputStreamAdaptor(env, is, storage, false));

    if (!stream.get()) {
        doThrowIOE(env, "Failed to create stream!");
        return nullptr;
    }
    std::unique_ptr<SkStream> bufferedStream(
        SkFrontBufferedStream::Make(std::move(stream),
        SkCodec::MinBufferedBytesNeeded()));
    return native_create(env, std::move(bufferedStream));
}

static jobject ImageDecoder_nCreateAsset(JNIEnv* env, jobject /*clazz*/, jlong assetPtr) {
    Asset* asset = reinterpret_cast<Asset*>(assetPtr);
    std::unique_ptr<SkStream> stream(new AssetStreamAdaptor(asset));
    return native_create(env, std::move(stream));
}

static jobject ImageDecoder_nCreateByteBuffer(JNIEnv* env, jobject /*clazz*/, jobject jbyteBuffer,
                                              jint initialPosition, jint limit) {
    std::unique_ptr<SkStream> stream = CreateByteBufferStreamAdaptor(env, jbyteBuffer,
                                                                     initialPosition, limit);
    if (!stream) {
        doThrowIOE(env, "Failed to read ByteBuffer");
        return nullptr;
    }
    return native_create(env, std::move(stream));
}

static jobject ImageDecoder_nCreateByteArray(JNIEnv* env, jobject /*clazz*/, jbyteArray byteArray,
                                             jint offset, jint length) {
    std::unique_ptr<SkStream> stream(CreateByteArrayStreamAdaptor(env, byteArray, offset, length));
    return native_create(env, std::move(stream));
}

static jobject ImageDecoder_nDecodeBitmap(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                          jobject jcallback, jobject jpostProcess,
                                          jint desiredWidth, jint desiredHeight, jobject jsubset,
                                          jboolean requireMutable, jint allocator,
                                          jboolean requireUnpremul, jboolean preferRamOverQuality,
                                          jboolean asAlphaMask) {
    auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
    SkAndroidCodec* codec = decoder->mCodec.get();
    const SkISize desiredSize = SkISize::Make(desiredWidth, desiredHeight);
    SkISize decodeSize = desiredSize;
    const int sampleSize = codec->computeSampleSize(&decodeSize);
    const bool scale = desiredSize != decodeSize;
    SkImageInfo decodeInfo = codec->getInfo().makeWH(decodeSize.width(), decodeSize.height());
    if (scale && requireUnpremul && kOpaque_SkAlphaType != decodeInfo.alphaType()) {
        doThrowISE(env, "Cannot scale unpremultiplied pixels!");
        return nullptr;
    }

    switch (decodeInfo.alphaType()) {
        case kUnpremul_SkAlphaType:
            if (!requireUnpremul) {
                decodeInfo = decodeInfo.makeAlphaType(kPremul_SkAlphaType);
            }
            break;
        case kPremul_SkAlphaType:
            if (requireUnpremul) {
                decodeInfo = decodeInfo.makeAlphaType(kUnpremul_SkAlphaType);
            }
            break;
        case kOpaque_SkAlphaType:
            break;
        case kUnknown_SkAlphaType:
            doThrowIOE(env, "Unknown alpha type");
            return nullptr;
    }

    SkColorType colorType = kN32_SkColorType;
    if (asAlphaMask && decodeInfo.colorType() == kGray_8_SkColorType) {
        // We have to trick Skia to decode this to a single channel.
        colorType = kGray_8_SkColorType;
    } else if (preferRamOverQuality) {
        // FIXME: The post-process might add alpha, which would make a 565
        // result incorrect. If we call the postProcess before now and record
        // to a picture, we can know whether alpha was added, and if not, we
        // can still use 565.
        if (decodeInfo.alphaType() == kOpaque_SkAlphaType && !jpostProcess) {
            // If the final result will be hardware, decoding to 565 and then
            // uploading to the gpu as 8888 will not save memory. This still
            // may save us from using F16, but do not go down to 565.
            if (allocator != ImageDecoder::kHardware_Allocator &&
               (allocator != ImageDecoder::kDefault_Allocator || requireMutable)) {
                colorType = kRGB_565_SkColorType;
            }
        }
        // Otherwise, stick with N32
    } else {
        // This is currently the only way to know that we should decode to F16.
        colorType = codec->computeOutputColorType(colorType);
    }
    sk_sp<SkColorSpace> colorSpace = codec->computeOutputColorSpace(colorType);
    decodeInfo = decodeInfo.makeColorType(colorType).makeColorSpace(colorSpace);

    SkBitmap bm;
    auto bitmapInfo = decodeInfo;
    if (asAlphaMask && colorType == kGray_8_SkColorType) {
        bitmapInfo = bitmapInfo.makeColorType(kAlpha_8_SkColorType);
    }
    if (!bm.setInfo(bitmapInfo)) {
        doThrowIOE(env, "Failed to setInfo properly");
        return nullptr;
    }

    sk_sp<Bitmap> nativeBitmap;
    // If we are going to scale or subset, we will create a new bitmap later on,
    // so use the heap for the temporary.
    // FIXME: Use scanline decoding on only a couple lines to save memory. b/70709380.
    if (allocator == ImageDecoder::kSharedMemory_Allocator && !scale && !jsubset) {
        nativeBitmap = Bitmap::allocateAshmemBitmap(&bm);
    } else {
        nativeBitmap = Bitmap::allocateHeapBitmap(&bm);
    }
    if (!nativeBitmap) {
        SkString msg;
        msg.printf("OOM allocating Bitmap with dimensions %i x %i",
                decodeInfo.width(), decodeInfo.height());
        doThrowOOME(env, msg.c_str());
        return nullptr;
    }

    SkAndroidCodec::AndroidOptions options;
    options.fSampleSize = sampleSize;
    auto result = codec->getAndroidPixels(decodeInfo, bm.getPixels(), bm.rowBytes(), &options);
    jthrowable jexception = env->ExceptionOccurred();
    if (jexception) {
        env->ExceptionClear();
    }
    switch (result) {
        case SkCodec::kSuccess:
            // Ignore the exception, since the decode was successful anyway.
            jexception = nullptr;
            break;
        case SkCodec::kIncompleteInput:
            if (jcallback && !jexception) {
                jexception = (jthrowable) env->NewObject(gIncomplete_class,
                                                         gIncomplete_constructorMethodID);
            }
            break;
        case SkCodec::kErrorInInput:
            if (jcallback && !jexception) {
                jexception = (jthrowable) env->NewObject(gCorrupt_class,
                                                         gCorrupt_constructorMethodID);
            }
            break;
        default:
            SkString msg;
            msg.printf("getPixels failed with error %i", result);
            doThrowIOE(env, msg.c_str());
            return nullptr;
    }

    if (jexception) {
        bool throwException = !env->CallBooleanMethod(jcallback, gCallback_onPartialImageMethodID,
                                                      jexception);
        if (env->ExceptionCheck()) {
            return nullptr;
        }

        if (throwException) {
            env->Throw(jexception);
            return nullptr;
        }
    }

    float scaleX = 1.0f;
    float scaleY = 1.0f;
    if (scale) {
        scaleX = (float) desiredWidth  / decodeInfo.width();
        scaleY = (float) desiredHeight / decodeInfo.height();
    }

    jbyteArray ninePatchChunk = nullptr;
    jobject ninePatchInsets = nullptr;

    // Ignore ninepatch when post-processing.
    if (!jpostProcess) {
        // FIXME: Share more code with BitmapFactory.cpp.
        if (decoder->mPeeker.mPatch != nullptr) {
            if (scale) {
                decoder->mPeeker.scale(scaleX, scaleY, desiredWidth, desiredHeight);
            }
            size_t ninePatchArraySize = decoder->mPeeker.mPatch->serializedSize();
            ninePatchChunk = env->NewByteArray(ninePatchArraySize);
            if (ninePatchChunk == nullptr) {
                doThrowOOME(env, "Failed to allocate nine patch chunk.");
                return nullptr;
            }

            env->SetByteArrayRegion(ninePatchChunk, 0, decoder->mPeeker.mPatchSize,
                                    reinterpret_cast<jbyte*>(decoder->mPeeker.mPatch));
        }

        if (decoder->mPeeker.mHasInsets) {
            ninePatchInsets = decoder->mPeeker.createNinePatchInsets(env, 1.0f);
            if (ninePatchInsets == nullptr) {
                doThrowOOME(env, "Failed to allocate nine patch insets.");
                return nullptr;
            }
        }
    }

    if (scale || jsubset) {
        int translateX = 0;
        int translateY = 0;
        if (jsubset) {
            SkIRect subset;
            GraphicsJNI::jrect_to_irect(env, jsubset, &subset);

            // FIXME: If there is no scale, should this instead call
            // SkBitmap::extractSubset? If we could upload a subset
            // (b/70626068), this would save memory and time. Even for a
            // software Bitmap, the extra speed might be worth the memory
            // tradeoff if the subset is large?
            translateX    = -subset.fLeft;
            translateY    = -subset.fTop;
            desiredWidth  =  subset.width();
            desiredHeight =  subset.height();
        }
        SkImageInfo scaledInfo = bitmapInfo.makeWH(desiredWidth, desiredHeight);
        SkBitmap scaledBm;
        if (!scaledBm.setInfo(scaledInfo)) {
            doThrowIOE(env, "Failed scaled setInfo");
            return nullptr;
        }

        sk_sp<Bitmap> scaledPixelRef;
        if (allocator == ImageDecoder::kSharedMemory_Allocator) {
            scaledPixelRef = Bitmap::allocateAshmemBitmap(&scaledBm);
        } else {
            scaledPixelRef = Bitmap::allocateHeapBitmap(&scaledBm);
        }
        if (!scaledPixelRef) {
            SkString msg;
            msg.printf("OOM allocating scaled Bitmap with dimensions %i x %i",
                    desiredWidth, desiredHeight);
            doThrowOOME(env, msg.c_str());
            return nullptr;
        }

        SkPaint paint;
        paint.setBlendMode(SkBlendMode::kSrc);
        paint.setFilterQuality(kLow_SkFilterQuality);  // bilinear filtering

        SkCanvas canvas(scaledBm, SkCanvas::ColorBehavior::kLegacy);
        canvas.translate(translateX, translateY);
        canvas.scale(scaleX, scaleY);
        canvas.drawBitmap(bm, 0.0f, 0.0f, &paint);

        bm.swap(scaledBm);
        nativeBitmap = scaledPixelRef;
    }

    if (jpostProcess) {
        std::unique_ptr<Canvas> canvas(Canvas::create_canvas(bm));
        jobject jcanvas = env->NewObject(gCanvas_class, gCanvas_constructorMethodID,
                                         reinterpret_cast<jlong>(canvas.get()));
        if (!jcanvas) {
            doThrowOOME(env, "Failed to create Java Canvas for PostProcess!");
            return nullptr;
        }
        // jcanvas will now own canvas.
        canvas.release();

        jint pixelFormat = env->CallIntMethod(jpostProcess, gPostProcess_postProcessMethodID,
                                              jcanvas, bm.width(), bm.height());
        if (env->ExceptionCheck()) {
            return nullptr;
        }

        // The Canvas objects are no longer needed, and will not remain valid.
        env->CallVoidMethod(jcanvas, gCanvas_releaseMethodID);
        if (env->ExceptionCheck()) {
            return nullptr;
        }

        SkAlphaType newAlphaType = bm.alphaType();
        switch (pixelFormat) {
            case ImageDecoder::kUnknown:
                break;
            case ImageDecoder::kTranslucent:
                newAlphaType = kPremul_SkAlphaType;
                break;
            case ImageDecoder::kOpaque:
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
        if ((allocator == ImageDecoder::kDefault_Allocator ||
             allocator == ImageDecoder::kHardware_Allocator)
            && bm.colorType() != kAlpha_8_SkColorType)
        {
            sk_sp<Bitmap> hwBitmap = Bitmap::allocateHardwareBitmap(bm);
            if (hwBitmap) {
                hwBitmap->setImmutable();
                return bitmap::createBitmap(env, hwBitmap.release(), bitmapCreateFlags,
                                            ninePatchChunk, ninePatchInsets);
            }
            if (allocator == ImageDecoder::kHardware_Allocator) {
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
    SkISize size = decoder->mCodec->getSampledDimensions(sampleSize);
    return env->NewObject(gPoint_class, gPoint_constructorMethodID, size.width(), size.height());
}

static void ImageDecoder_nGetPadding(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                     jobject outPadding) {
    auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
    decoder->mPeeker.getPadding(env, outPadding);
}

static void ImageDecoder_nClose(JNIEnv* /*env*/, jobject /*clazz*/, jlong nativePtr) {
    delete reinterpret_cast<ImageDecoder*>(nativePtr);
}

static jstring ImageDecoder_nGetMimeType(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
    return encodedFormatToString(env, decoder->mCodec->getEncodedFormat());
}

static const JNINativeMethod gImageDecoderMethods[] = {
    { "nCreate",        "(J)Landroid/graphics/ImageDecoder;",    (void*) ImageDecoder_nCreateAsset },
    { "nCreate",        "(Ljava/nio/ByteBuffer;II)Landroid/graphics/ImageDecoder;", (void*) ImageDecoder_nCreateByteBuffer },
    { "nCreate",        "([BII)Landroid/graphics/ImageDecoder;", (void*) ImageDecoder_nCreateByteArray },
    { "nCreate",        "(Ljava/io/InputStream;[B)Landroid/graphics/ImageDecoder;", (void*) ImageDecoder_nCreateInputStream },
    { "nCreate",        "(Ljava/io/FileDescriptor;)Landroid/graphics/ImageDecoder;", (void*) ImageDecoder_nCreateFd },
    { "nDecodeBitmap",  "(JLandroid/graphics/ImageDecoder$OnPartialImageListener;Landroid/graphics/PostProcess;IILandroid/graphics/Rect;ZIZZZ)Landroid/graphics/Bitmap;",
                                                                 (void*) ImageDecoder_nDecodeBitmap },
    { "nGetSampledSize","(JI)Landroid/graphics/Point;",          (void*) ImageDecoder_nGetSampledSize },
    { "nGetPadding",    "(JLandroid/graphics/Rect;)V",           (void*) ImageDecoder_nGetPadding },
    { "nClose",         "(J)V",                                  (void*) ImageDecoder_nClose},
    { "nGetMimeType",   "(J)Ljava/lang/String;",                 (void*) ImageDecoder_nGetMimeType },
};

int register_android_graphics_ImageDecoder(JNIEnv* env) {
    gImageDecoder_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/ImageDecoder"));
    gImageDecoder_constructorMethodID = GetMethodIDOrDie(env, gImageDecoder_class, "<init>", "(JII)V");

    gPoint_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Point"));
    gPoint_constructorMethodID = GetMethodIDOrDie(env, gPoint_class, "<init>", "(II)V");

    gIncomplete_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/ImageDecoder$IncompleteException"));
    gIncomplete_constructorMethodID = GetMethodIDOrDie(env, gIncomplete_class, "<init>", "()V");

    gCorrupt_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/ImageDecoder$CorruptException"));
    gCorrupt_constructorMethodID = GetMethodIDOrDie(env, gCorrupt_class, "<init>", "()V");

    jclass callback_class = FindClassOrDie(env, "android/graphics/ImageDecoder$OnPartialImageListener");
    gCallback_onPartialImageMethodID = GetMethodIDOrDie(env, callback_class, "onPartialImage", "(Ljava/io/IOException;)Z");

    jclass postProcess_class = FindClassOrDie(env, "android/graphics/PostProcess");
    gPostProcess_postProcessMethodID = GetMethodIDOrDie(env, postProcess_class, "postProcess", "(Landroid/graphics/Canvas;II)I");

    gCanvas_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Canvas"));
    gCanvas_constructorMethodID = GetMethodIDOrDie(env, gCanvas_class, "<init>", "(J)V");
    gCanvas_releaseMethodID = GetMethodIDOrDie(env, gCanvas_class, "release", "()V");

    return android::RegisterMethodsOrDie(env, "android/graphics/ImageDecoder", gImageDecoderMethods,
                                         NELEM(gImageDecoderMethods));
}
