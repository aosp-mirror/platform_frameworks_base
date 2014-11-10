#define LOG_TAG "BitmapFactory"

#include "BitmapFactory.h"
#include "NinePatchPeeker.h"
#include "SkFrontBufferedStream.h"
#include "SkImageDecoder.h"
#include "SkMath.h"
#include "SkPixelRef.h"
#include "SkStream.h"
#include "SkTemplates.h"
#include "SkUtils.h"
#include "CreateJavaOutputStreamAdaptor.h"
#include "AutoDecodeCancel.h"
#include "Utils.h"
#include "JNIHelp.h"
#include "GraphicsJNI.h"

#include <android_runtime/AndroidRuntime.h>
#include <androidfw/Asset.h>
#include <androidfw/ResourceTypes.h>
#include <cutils/compiler.h>
#include <netinet/in.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/stat.h>

jfieldID gOptions_justBoundsFieldID;
jfieldID gOptions_sampleSizeFieldID;
jfieldID gOptions_configFieldID;
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
jfieldID gOptions_mCancelID;
jfieldID gOptions_bitmapFieldID;

jfieldID gBitmap_nativeBitmapFieldID;
jfieldID gBitmap_ninePatchInsetsFieldID;

jclass gInsetStruct_class;
jmethodID gInsetStruct_constructorMethodID;

using namespace android;

static inline int32_t validOrNeg1(bool isValid, int32_t value) {
//    return isValid ? value : -1;
    SkASSERT((int)isValid == 0 || (int)isValid == 1);
    return ((int32_t)isValid - 1) | value;
}

jstring getMimeTypeString(JNIEnv* env, SkImageDecoder::Format format) {
    static const struct {
        SkImageDecoder::Format fFormat;
        const char*            fMimeType;
    } gMimeTypes[] = {
        { SkImageDecoder::kBMP_Format,  "image/bmp" },
        { SkImageDecoder::kGIF_Format,  "image/gif" },
        { SkImageDecoder::kICO_Format,  "image/x-ico" },
        { SkImageDecoder::kJPEG_Format, "image/jpeg" },
        { SkImageDecoder::kPNG_Format,  "image/png" },
        { SkImageDecoder::kWEBP_Format, "image/webp" },
        { SkImageDecoder::kWBMP_Format, "image/vnd.wap.wbmp" }
    };

    const char* cstr = NULL;
    for (size_t i = 0; i < SK_ARRAY_COUNT(gMimeTypes); i++) {
        if (gMimeTypes[i].fFormat == format) {
            cstr = gMimeTypes[i].fMimeType;
            break;
        }
    }

    jstring jstr = 0;
    if (NULL != cstr) {
        jstr = env->NewStringUTF(cstr);
    }
    return jstr;
}

static bool optionsJustBounds(JNIEnv* env, jobject options) {
    return options != NULL && env->GetBooleanField(options, gOptions_justBoundsFieldID);
}

static void scaleDivRange(int32_t* divs, int count, float scale, int maxValue) {
    for (int i = 0; i < count; i++) {
        divs[i] = int32_t(divs[i] * scale + 0.5f);
        if (i > 0 && divs[i] == divs[i - 1]) {
            divs[i]++; // avoid collisions
        }
    }

    if (CC_UNLIKELY(divs[count - 1] > maxValue)) {
        // if the collision avoidance above put some divs outside the bounds of the bitmap,
        // slide outer stretchable divs inward to stay within bounds
        int highestAvailable = maxValue;
        for (int i = count - 1; i >= 0; i--) {
            divs[i] = highestAvailable;
            if (i > 0 && divs[i] <= divs[i-1]){
                // keep shifting
                highestAvailable = divs[i] - 1;
            } else {
                break;
            }
        }
    }
}

static void scaleNinePatchChunk(android::Res_png_9patch* chunk, float scale,
        int scaledWidth, int scaledHeight) {
    chunk->paddingLeft = int(chunk->paddingLeft * scale + 0.5f);
    chunk->paddingTop = int(chunk->paddingTop * scale + 0.5f);
    chunk->paddingRight = int(chunk->paddingRight * scale + 0.5f);
    chunk->paddingBottom = int(chunk->paddingBottom * scale + 0.5f);

    scaleDivRange(chunk->getXDivs(), chunk->numXDivs, scale, scaledWidth);
    scaleDivRange(chunk->getYDivs(), chunk->numYDivs, scale, scaledHeight);
}

static SkColorType colorTypeForScaledOutput(SkColorType colorType) {
    switch (colorType) {
        case kUnknown_SkColorType:
        case kIndex_8_SkColorType:
            return kN32_SkColorType;
        default:
            break;
    }
    return colorType;
}

class ScaleCheckingAllocator : public SkBitmap::HeapAllocator {
public:
    ScaleCheckingAllocator(float scale, int size)
            : mScale(scale), mSize(size) {
    }

    virtual bool allocPixelRef(SkBitmap* bitmap, SkColorTable* ctable) {
        // accounts for scale in final allocation, using eventual size and config
        const int bytesPerPixel = SkColorTypeBytesPerPixel(
                colorTypeForScaledOutput(bitmap->colorType()));
        const int requestedSize = bytesPerPixel *
                int(bitmap->width() * mScale + 0.5f) *
                int(bitmap->height() * mScale + 0.5f);
        if (requestedSize > mSize) {
            ALOGW("bitmap for alloc reuse (%d bytes) can't fit scaled bitmap (%d bytes)",
                    mSize, requestedSize);
            return false;
        }
        return SkBitmap::HeapAllocator::allocPixelRef(bitmap, ctable);
    }
private:
    const float mScale;
    const int mSize;
};

class RecyclingPixelAllocator : public SkBitmap::Allocator {
public:
    RecyclingPixelAllocator(SkPixelRef* pixelRef, unsigned int size)
            : mPixelRef(pixelRef), mSize(size) {
        SkSafeRef(mPixelRef);
    }

    ~RecyclingPixelAllocator() {
        SkSafeUnref(mPixelRef);
    }

    virtual bool allocPixelRef(SkBitmap* bitmap, SkColorTable* ctable) {
        const SkImageInfo& info = bitmap->info();
        if (info.fColorType == kUnknown_SkColorType) {
            ALOGW("unable to reuse a bitmap as the target has an unknown bitmap configuration");
            return false;
        }

        const int64_t size64 = info.getSafeSize64(bitmap->rowBytes());
        if (!sk_64_isS32(size64)) {
            ALOGW("bitmap is too large");
            return false;
        }

        const size_t size = sk_64_asS32(size64);
        if (size > mSize) {
            ALOGW("bitmap marked for reuse (%d bytes) can't fit new bitmap (%d bytes)",
                    mSize, size);
            return false;
        }

        // Create a new pixelref with the new ctable that wraps the previous pixelref
        SkPixelRef* pr = new AndroidPixelRef(*static_cast<AndroidPixelRef*>(mPixelRef),
                info, bitmap->rowBytes(), ctable);

        bitmap->setPixelRef(pr)->unref();
        // since we're already allocated, we lockPixels right away
        // HeapAllocator/JavaPixelAllocator behaves this way too
        bitmap->lockPixels();
        return true;
    }

private:
    SkPixelRef* const mPixelRef;
    const unsigned int mSize;
};

static jobject doDecode(JNIEnv* env, SkStreamRewindable* stream, jobject padding, jobject options) {

    int sampleSize = 1;

    SkImageDecoder::Mode decodeMode = SkImageDecoder::kDecodePixels_Mode;
    SkColorType prefColorType = kN32_SkColorType;

    bool doDither = true;
    bool isMutable = false;
    float scale = 1.0f;
    bool preferQualityOverSpeed = false;
    bool requireUnpremultiplied = false;

    jobject javaBitmap = NULL;

    if (options != NULL) {
        sampleSize = env->GetIntField(options, gOptions_sampleSizeFieldID);
        if (optionsJustBounds(env, options)) {
            decodeMode = SkImageDecoder::kDecodeBounds_Mode;
        }

        // initialize these, in case we fail later on
        env->SetIntField(options, gOptions_widthFieldID, -1);
        env->SetIntField(options, gOptions_heightFieldID, -1);
        env->SetObjectField(options, gOptions_mimeFieldID, 0);

        jobject jconfig = env->GetObjectField(options, gOptions_configFieldID);
        prefColorType = GraphicsJNI::getNativeBitmapColorType(env, jconfig);
        isMutable = env->GetBooleanField(options, gOptions_mutableFieldID);
        doDither = env->GetBooleanField(options, gOptions_ditherFieldID);
        preferQualityOverSpeed = env->GetBooleanField(options,
                gOptions_preferQualityOverSpeedFieldID);
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

    const bool willScale = scale != 1.0f;

    SkImageDecoder* decoder = SkImageDecoder::Factory(stream);
    if (decoder == NULL) {
        return nullObjectReturn("SkImageDecoder::Factory returned null");
    }

    decoder->setSampleSize(sampleSize);
    decoder->setDitherImage(doDither);
    decoder->setPreferQualityOverSpeed(preferQualityOverSpeed);
    decoder->setRequireUnpremultipliedColors(requireUnpremultiplied);

    SkBitmap* outputBitmap = NULL;
    unsigned int existingBufferSize = 0;
    if (javaBitmap != NULL) {
        outputBitmap = (SkBitmap*) env->GetLongField(javaBitmap, gBitmap_nativeBitmapFieldID);
        if (outputBitmap->isImmutable()) {
            ALOGW("Unable to reuse an immutable bitmap as an image decoder target.");
            javaBitmap = NULL;
            outputBitmap = NULL;
        } else {
            existingBufferSize = GraphicsJNI::getBitmapAllocationByteCount(env, javaBitmap);
        }
    }

    SkAutoTDelete<SkBitmap> adb(outputBitmap == NULL ? new SkBitmap : NULL);
    if (outputBitmap == NULL) outputBitmap = adb.get();

    NinePatchPeeker peeker(decoder);
    decoder->setPeeker(&peeker);

    JavaPixelAllocator javaAllocator(env);
    RecyclingPixelAllocator recyclingAllocator(outputBitmap->pixelRef(), existingBufferSize);
    ScaleCheckingAllocator scaleCheckingAllocator(scale, existingBufferSize);
    SkBitmap::Allocator* outputAllocator = (javaBitmap != NULL) ?
            (SkBitmap::Allocator*)&recyclingAllocator : (SkBitmap::Allocator*)&javaAllocator;
    if (decodeMode != SkImageDecoder::kDecodeBounds_Mode) {
        if (!willScale) {
            // If the java allocator is being used to allocate the pixel memory, the decoder
            // need not write zeroes, since the memory is initialized to 0.
            decoder->setSkipWritingZeroes(outputAllocator == &javaAllocator);
            decoder->setAllocator(outputAllocator);
        } else if (javaBitmap != NULL) {
            // check for eventual scaled bounds at allocation time, so we don't decode the bitmap
            // only to find the scaled result too large to fit in the allocation
            decoder->setAllocator(&scaleCheckingAllocator);
        }
    }

    // Only setup the decoder to be deleted after its stack-based, refcounted
    // components (allocators, peekers, etc) are declared. This prevents RefCnt
    // asserts from firing due to the order objects are deleted from the stack.
    SkAutoTDelete<SkImageDecoder> add(decoder);

    AutoDecoderCancel adc(options, decoder);

    // To fix the race condition in case "requestCancelDecode"
    // happens earlier than AutoDecoderCancel object is added
    // to the gAutoDecoderCancelMutex linked list.
    if (options != NULL && env->GetBooleanField(options, gOptions_mCancelID)) {
        return nullObjectReturn("gOptions_mCancelID");
    }

    SkBitmap decodingBitmap;
    if (!decoder->decode(stream, &decodingBitmap, prefColorType, decodeMode)) {
        return nullObjectReturn("decoder->decode returned false");
    }

    int scaledWidth = decodingBitmap.width();
    int scaledHeight = decodingBitmap.height();

    if (willScale && decodeMode != SkImageDecoder::kDecodeBounds_Mode) {
        scaledWidth = int(scaledWidth * scale + 0.5f);
        scaledHeight = int(scaledHeight * scale + 0.5f);
    }

    // update options (if any)
    if (options != NULL) {
        env->SetIntField(options, gOptions_widthFieldID, scaledWidth);
        env->SetIntField(options, gOptions_heightFieldID, scaledHeight);
        env->SetObjectField(options, gOptions_mimeFieldID,
                getMimeTypeString(env, decoder->getFormat()));
    }

    // if we're in justBounds mode, return now (skip the java bitmap)
    if (decodeMode == SkImageDecoder::kDecodeBounds_Mode) {
        return NULL;
    }

    jbyteArray ninePatchChunk = NULL;
    if (peeker.mPatch != NULL) {
        if (willScale) {
            scaleNinePatchChunk(peeker.mPatch, scale, scaledWidth, scaledHeight);
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
        ninePatchInsets = env->NewObject(gInsetStruct_class, gInsetStruct_constructorMethodID,
                peeker.mOpticalInsets[0], peeker.mOpticalInsets[1], peeker.mOpticalInsets[2], peeker.mOpticalInsets[3],
                peeker.mOutlineInsets[0], peeker.mOutlineInsets[1], peeker.mOutlineInsets[2], peeker.mOutlineInsets[3],
                peeker.mOutlineRadius, peeker.mOutlineAlpha, scale);
        if (ninePatchInsets == NULL) {
            return nullObjectReturn("nine patch insets == null");
        }
        if (javaBitmap != NULL) {
            env->SetObjectField(javaBitmap, gBitmap_ninePatchInsetsFieldID, ninePatchInsets);
        }
    }

    if (willScale) {
        // This is weird so let me explain: we could use the scale parameter
        // directly, but for historical reasons this is how the corresponding
        // Dalvik code has always behaved. We simply recreate the behavior here.
        // The result is slightly different from simply using scale because of
        // the 0.5f rounding bias applied when computing the target image size
        const float sx = scaledWidth / float(decodingBitmap.width());
        const float sy = scaledHeight / float(decodingBitmap.height());

        // TODO: avoid copying when scaled size equals decodingBitmap size
        SkColorType colorType = colorTypeForScaledOutput(decodingBitmap.colorType());
        // FIXME: If the alphaType is kUnpremul and the image has alpha, the
        // colors may not be correct, since Skia does not yet support drawing
        // to/from unpremultiplied bitmaps.
        outputBitmap->setInfo(SkImageInfo::Make(scaledWidth, scaledHeight,
                colorType, decodingBitmap.alphaType()));
        if (!outputBitmap->allocPixels(outputAllocator, NULL)) {
            return nullObjectReturn("allocation failed for scaled bitmap");
        }

        // If outputBitmap's pixels are newly allocated by Java, there is no need
        // to erase to 0, since the pixels were initialized to 0.
        if (outputAllocator != &javaAllocator) {
            outputBitmap->eraseColor(0);
        }

        SkPaint paint;
        paint.setFilterLevel(SkPaint::kLow_FilterLevel);

        SkCanvas canvas(*outputBitmap);
        canvas.scale(sx, sy);
        canvas.drawBitmap(decodingBitmap, 0.0f, 0.0f, &paint);
    } else {
        outputBitmap->swap(decodingBitmap);
    }

    if (padding) {
        if (peeker.mPatch != NULL) {
            GraphicsJNI::set_jrect(env, padding,
                    peeker.mPatch->paddingLeft, peeker.mPatch->paddingTop,
                    peeker.mPatch->paddingRight, peeker.mPatch->paddingBottom);
        } else {
            GraphicsJNI::set_jrect(env, padding, -1, -1, -1, -1);
        }
    }

    // if we get here, we're in kDecodePixels_Mode and will therefore
    // already have a pixelref installed.
    if (outputBitmap->pixelRef() == NULL) {
        return nullObjectReturn("Got null SkPixelRef");
    }

    if (!isMutable && javaBitmap == NULL) {
        // promise we will never change our pixels (great for sharing and pictures)
        outputBitmap->setImmutable();
    }

    // detach bitmap from its autodeleter, since we want to own it now
    adb.detach();

    if (javaBitmap != NULL) {
        bool isPremultiplied = !requireUnpremultiplied;
        GraphicsJNI::reinitBitmap(env, javaBitmap, outputBitmap, isPremultiplied);
        outputBitmap->notifyPixelsChanged();
        // If a java bitmap was passed in for reuse, pass it back
        return javaBitmap;
    }

    int bitmapCreateFlags = 0x0;
    if (isMutable) bitmapCreateFlags |= GraphicsJNI::kBitmapCreateFlag_Mutable;
    if (!requireUnpremultiplied) bitmapCreateFlags |= GraphicsJNI::kBitmapCreateFlag_Premultiplied;

    // now create the java bitmap
    return GraphicsJNI::createBitmap(env, outputBitmap, javaAllocator.getStorageObj(),
            bitmapCreateFlags, ninePatchChunk, ninePatchInsets, -1);
}

// Need to buffer enough input to be able to rewind as much as might be read by a decoder
// trying to determine the stream's format. Currently the most is 64, read by
// SkImageDecoder_libwebp.
// FIXME: Get this number from SkImageDecoder
#define BYTES_TO_BUFFER 64

static jobject nativeDecodeStream(JNIEnv* env, jobject clazz, jobject is, jbyteArray storage,
        jobject padding, jobject options) {

    jobject bitmap = NULL;
    SkAutoTUnref<SkStream> stream(CreateJavaInputStreamAdaptor(env, is, storage));

    if (stream.get()) {
        SkAutoTUnref<SkStreamRewindable> bufferedStream(
                SkFrontBufferedStream::Create(stream, BYTES_TO_BUFFER));
        SkASSERT(bufferedStream.get() != NULL);
        bitmap = doDecode(env, bufferedStream, padding, options);
    }
    return bitmap;
}

static jobject nativeDecodeFileDescriptor(JNIEnv* env, jobject clazz, jobject fileDescriptor,
        jobject padding, jobject bitmapFactoryOptions) {

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
    int dupDescriptor = dup(descriptor);

    FILE* file = fdopen(dupDescriptor, "r");
    if (file == NULL) {
        // cleanup the duplicated descriptor since it will not be closed when the
        // file is cleaned up (fclose).
        close(dupDescriptor);
        return nullObjectReturn("Could not open file");
    }

    SkAutoTUnref<SkFILEStream> fileStream(new SkFILEStream(file,
                         SkFILEStream::kCallerPasses_Ownership));

    // Use a buffered stream. Although an SkFILEStream can be rewound, this
    // ensures that SkImageDecoder::Factory never rewinds beyond the
    // current position of the file descriptor.
    SkAutoTUnref<SkStreamRewindable> stream(SkFrontBufferedStream::Create(fileStream,
            BYTES_TO_BUFFER));

    return doDecode(env, stream, padding, bitmapFactoryOptions);
}

static jobject nativeDecodeAsset(JNIEnv* env, jobject clazz, jlong native_asset,
        jobject padding, jobject options) {

    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    // since we know we'll be done with the asset when we return, we can
    // just use a simple wrapper
    SkAutoTUnref<SkStreamRewindable> stream(new AssetStreamAdaptor(asset,
            AssetStreamAdaptor::kNo_OwnAsset, AssetStreamAdaptor::kNo_HasMemoryBase));
    return doDecode(env, stream, padding, options);
}

static jobject nativeDecodeByteArray(JNIEnv* env, jobject, jbyteArray byteArray,
        jint offset, jint length, jobject options) {

    AutoJavaByteArray ar(env, byteArray);
    SkMemoryStream* stream = new SkMemoryStream(ar.ptr() + offset, length, false);
    SkAutoUnref aur(stream);
    return doDecode(env, stream, NULL, options);
}

static void nativeRequestCancel(JNIEnv*, jobject joptions) {
    (void)AutoDecoderCancel::RequestCancel(joptions);
}

static jboolean nativeIsSeekable(JNIEnv* env, jobject, jobject fileDescriptor) {
    jint descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);
    return ::lseek64(descriptor, 0, SEEK_CUR) != -1 ? JNI_TRUE : JNI_FALSE;
}

///////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gMethods[] = {
    {   "nativeDecodeStream",
        "(Ljava/io/InputStream;[BLandroid/graphics/Rect;Landroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeStream
    },

    {   "nativeDecodeFileDescriptor",
        "(Ljava/io/FileDescriptor;Landroid/graphics/Rect;Landroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeFileDescriptor
    },

    {   "nativeDecodeAsset",
        "(JLandroid/graphics/Rect;Landroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeAsset
    },

    {   "nativeDecodeByteArray",
        "([BIILandroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeByteArray
    },

    {   "nativeIsSeekable",
        "(Ljava/io/FileDescriptor;)Z",
        (void*)nativeIsSeekable
    },
};

static JNINativeMethod gOptionsMethods[] = {
    {   "requestCancel", "()V", (void*)nativeRequestCancel }
};

static jfieldID getFieldIDCheck(JNIEnv* env, jclass clazz,
                                const char fieldname[], const char type[]) {
    jfieldID id = env->GetFieldID(clazz, fieldname, type);
    SkASSERT(id);
    return id;
}

int register_android_graphics_BitmapFactory(JNIEnv* env) {
    jclass options_class = env->FindClass("android/graphics/BitmapFactory$Options");
    SkASSERT(options_class);
    gOptions_bitmapFieldID = getFieldIDCheck(env, options_class, "inBitmap",
            "Landroid/graphics/Bitmap;");
    gOptions_justBoundsFieldID = getFieldIDCheck(env, options_class, "inJustDecodeBounds", "Z");
    gOptions_sampleSizeFieldID = getFieldIDCheck(env, options_class, "inSampleSize", "I");
    gOptions_configFieldID = getFieldIDCheck(env, options_class, "inPreferredConfig",
            "Landroid/graphics/Bitmap$Config;");
    gOptions_premultipliedFieldID = getFieldIDCheck(env, options_class, "inPremultiplied", "Z");
    gOptions_mutableFieldID = getFieldIDCheck(env, options_class, "inMutable", "Z");
    gOptions_ditherFieldID = getFieldIDCheck(env, options_class, "inDither", "Z");
    gOptions_preferQualityOverSpeedFieldID = getFieldIDCheck(env, options_class,
            "inPreferQualityOverSpeed", "Z");
    gOptions_scaledFieldID = getFieldIDCheck(env, options_class, "inScaled", "Z");
    gOptions_densityFieldID = getFieldIDCheck(env, options_class, "inDensity", "I");
    gOptions_screenDensityFieldID = getFieldIDCheck(env, options_class, "inScreenDensity", "I");
    gOptions_targetDensityFieldID = getFieldIDCheck(env, options_class, "inTargetDensity", "I");
    gOptions_widthFieldID = getFieldIDCheck(env, options_class, "outWidth", "I");
    gOptions_heightFieldID = getFieldIDCheck(env, options_class, "outHeight", "I");
    gOptions_mimeFieldID = getFieldIDCheck(env, options_class, "outMimeType", "Ljava/lang/String;");
    gOptions_mCancelID = getFieldIDCheck(env, options_class, "mCancel", "Z");

    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    SkASSERT(bitmap_class);
    gBitmap_nativeBitmapFieldID = getFieldIDCheck(env, bitmap_class, "mNativeBitmap", "J");
    gBitmap_ninePatchInsetsFieldID = getFieldIDCheck(env, bitmap_class, "mNinePatchInsets",
            "Landroid/graphics/NinePatch$InsetStruct;");

    gInsetStruct_class = (jclass) env->NewGlobalRef(env->FindClass("android/graphics/NinePatch$InsetStruct"));
    gInsetStruct_constructorMethodID = env->GetMethodID(gInsetStruct_class, "<init>", "(IIIIIIIIFIF)V");

    int ret = AndroidRuntime::registerNativeMethods(env,
                                    "android/graphics/BitmapFactory$Options",
                                    gOptionsMethods,
                                    SK_ARRAY_COUNT(gOptionsMethods));
    if (ret) {
        return ret;
    }
    return android::AndroidRuntime::registerNativeMethods(env, "android/graphics/BitmapFactory",
                                         gMethods, SK_ARRAY_COUNT(gMethods));
}
