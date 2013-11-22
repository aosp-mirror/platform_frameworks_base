#define LOG_TAG "BitmapFactory"

#include "BitmapFactory.h"
#include "NinePatchPeeker.h"
#include "SkData.h"
#include "SkFrontBufferedStream.h"
#include "SkImageDecoder.h"
#include "SkImageRef_ashmem.h"
#include "SkImageRef_GlobalPool.h"
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
#include <netinet/in.h>
#include <sys/mman.h>
#include <sys/stat.h>

jfieldID gOptions_justBoundsFieldID;
jfieldID gOptions_sampleSizeFieldID;
jfieldID gOptions_configFieldID;
jfieldID gOptions_premultipliedFieldID;
jfieldID gOptions_mutableFieldID;
jfieldID gOptions_ditherFieldID;
jfieldID gOptions_purgeableFieldID;
jfieldID gOptions_shareableFieldID;
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
jfieldID gBitmap_layoutBoundsFieldID;

#if 0
    #define TRACE_BITMAP(code)  code
#else
    #define TRACE_BITMAP(code)
#endif

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

static bool optionsPurgeable(JNIEnv* env, jobject options) {
    return options != NULL && env->GetBooleanField(options, gOptions_purgeableFieldID);
}

static bool optionsShareable(JNIEnv* env, jobject options) {
    return options != NULL && env->GetBooleanField(options, gOptions_shareableFieldID);
}

static bool optionsJustBounds(JNIEnv* env, jobject options) {
    return options != NULL && env->GetBooleanField(options, gOptions_justBoundsFieldID);
}

static void scaleNinePatchChunk(android::Res_png_9patch* chunk, float scale) {
    chunk->paddingLeft = int(chunk->paddingLeft * scale + 0.5f);
    chunk->paddingTop = int(chunk->paddingTop * scale + 0.5f);
    chunk->paddingRight = int(chunk->paddingRight * scale + 0.5f);
    chunk->paddingBottom = int(chunk->paddingBottom * scale + 0.5f);

    for (int i = 0; i < chunk->numXDivs; i++) {
        chunk->xDivs[i] = int(chunk->xDivs[i] * scale + 0.5f);
        if (i > 0 && chunk->xDivs[i] == chunk->xDivs[i - 1]) {
            chunk->xDivs[i]++;
        }
    }

    for (int i = 0; i < chunk->numYDivs; i++) {
        chunk->yDivs[i] = int(chunk->yDivs[i] * scale + 0.5f);
        if (i > 0 && chunk->yDivs[i] == chunk->yDivs[i - 1]) {
            chunk->yDivs[i]++;
        }
    }
}

static SkPixelRef* installPixelRef(SkBitmap* bitmap, SkStreamRewindable* stream,
        int sampleSize, bool ditherImage) {

    SkImageRef* pr;
    // only use ashmem for large images, since mmaps come at a price
    if (bitmap->getSize() >= 32 * 1024) {
        pr = new SkImageRef_ashmem(stream, bitmap->config(), sampleSize);
    } else {
        pr = new SkImageRef_GlobalPool(stream, bitmap->config(), sampleSize);
    }
    pr->setDitherImage(ditherImage);
    bitmap->setPixelRef(pr)->unref();
    pr->isOpaque(bitmap);
    return pr;
}

static SkBitmap::Config configForScaledOutput(SkBitmap::Config config) {
    switch (config) {
        case SkBitmap::kNo_Config:
        case SkBitmap::kIndex8_Config:
            return SkBitmap::kARGB_8888_Config;
        default:
            break;
    }
    return config;
}

class ScaleCheckingAllocator : public SkBitmap::HeapAllocator {
public:
    ScaleCheckingAllocator(float scale, int size)
            : mScale(scale), mSize(size) {
    }

    virtual bool allocPixelRef(SkBitmap* bitmap, SkColorTable* ctable) {
        // accounts for scale in final allocation, using eventual size and config
        const int bytesPerPixel = SkBitmap::ComputeBytesPerPixel(
                configForScaledOutput(bitmap->getConfig()));
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
        if (!bitmap->getSize64().is32() || bitmap->getSize() > mSize) {
            ALOGW("bitmap marked for reuse (%d bytes) can't fit new bitmap (%d bytes)",
                    mSize, bitmap->getSize());
            return false;
        }

        // Create a new pixelref with the new ctable that wraps the previous pixelref
        SkPixelRef* pr = new AndroidPixelRef(*static_cast<AndroidPixelRef*>(mPixelRef), ctable);

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

// since we "may" create a purgeable imageref, we require the stream be ref'able
// i.e. dynamically allocated, since its lifetime may exceed the current stack
// frame.
static jobject doDecode(JNIEnv* env, SkStreamRewindable* stream, jobject padding,
        jobject options, bool allowPurgeable, bool forcePurgeable = false) {

    int sampleSize = 1;

    SkImageDecoder::Mode mode = SkImageDecoder::kDecodePixels_Mode;
    SkBitmap::Config prefConfig = SkBitmap::kARGB_8888_Config;

    bool doDither = true;
    bool isMutable = false;
    float scale = 1.0f;
    bool isPurgeable = forcePurgeable || (allowPurgeable && optionsPurgeable(env, options));
    bool preferQualityOverSpeed = false;
    bool requireUnpremultiplied = false;

    jobject javaBitmap = NULL;

    if (options != NULL) {
        sampleSize = env->GetIntField(options, gOptions_sampleSizeFieldID);
        if (optionsJustBounds(env, options)) {
            mode = SkImageDecoder::kDecodeBounds_Mode;
        }

        // initialize these, in case we fail later on
        env->SetIntField(options, gOptions_widthFieldID, -1);
        env->SetIntField(options, gOptions_heightFieldID, -1);
        env->SetObjectField(options, gOptions_mimeFieldID, 0);

        jobject jconfig = env->GetObjectField(options, gOptions_configFieldID);
        prefConfig = GraphicsJNI::getNativeBitmapConfig(env, jconfig);
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
    isPurgeable &= !willScale;

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
        outputBitmap = (SkBitmap*) env->GetIntField(javaBitmap, gBitmap_nativeBitmapFieldID);
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

    SkImageDecoder::Mode decodeMode = isPurgeable ? SkImageDecoder::kDecodeBounds_Mode : mode;

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
    if (!decoder->decode(stream, &decodingBitmap, prefConfig, decodeMode)) {
        return nullObjectReturn("decoder->decode returned false");
    }

    int scaledWidth = decodingBitmap.width();
    int scaledHeight = decodingBitmap.height();

    if (willScale && mode != SkImageDecoder::kDecodeBounds_Mode) {
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
    if (mode == SkImageDecoder::kDecodeBounds_Mode) {
        return NULL;
    }

    jbyteArray ninePatchChunk = NULL;
    if (peeker.fPatch != NULL) {
        if (willScale) {
            scaleNinePatchChunk(peeker.fPatch, scale);
        }

        size_t ninePatchArraySize = peeker.fPatch->serializedSize();
        ninePatchChunk = env->NewByteArray(ninePatchArraySize);
        if (ninePatchChunk == NULL) {
            return nullObjectReturn("ninePatchChunk == null");
        }

        jbyte* array = (jbyte*) env->GetPrimitiveArrayCritical(ninePatchChunk, NULL);
        if (array == NULL) {
            return nullObjectReturn("primitive array == null");
        }

        peeker.fPatch->serialize(array);
        env->ReleasePrimitiveArrayCritical(ninePatchChunk, array, 0);
    }

    jintArray layoutBounds = NULL;
    if (peeker.fLayoutBounds != NULL) {
        layoutBounds = env->NewIntArray(4);
        if (layoutBounds == NULL) {
            return nullObjectReturn("layoutBounds == null");
        }

        jint scaledBounds[4];
        if (willScale) {
            for (int i=0; i<4; i++) {
                scaledBounds[i] = (jint)((((jint*)peeker.fLayoutBounds)[i]*scale) + .5f);
            }
        } else {
            memcpy(scaledBounds, (jint*)peeker.fLayoutBounds, sizeof(scaledBounds));
        }
        env->SetIntArrayRegion(layoutBounds, 0, 4, scaledBounds);
        if (javaBitmap != NULL) {
            env->SetObjectField(javaBitmap, gBitmap_layoutBoundsFieldID, layoutBounds);
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
        SkBitmap::Config config = configForScaledOutput(decodingBitmap.config());
        outputBitmap->setConfig(config, scaledWidth, scaledHeight);
        outputBitmap->setIsOpaque(decodingBitmap.isOpaque());
        if (!outputBitmap->allocPixels(outputAllocator, NULL)) {
            return nullObjectReturn("allocation failed for scaled bitmap");
        }

        // If outputBitmap's pixels are newly allocated by Java, there is no need
        // to erase to 0, since the pixels were initialized to 0.
        if (outputAllocator != &javaAllocator) {
            outputBitmap->eraseColor(0);
        }

        SkPaint paint;
        paint.setFilterBitmap(true);

        SkCanvas canvas(*outputBitmap);
        canvas.scale(sx, sy);
        canvas.drawBitmap(decodingBitmap, 0.0f, 0.0f, &paint);
    } else {
        outputBitmap->swap(decodingBitmap);
    }

    if (padding) {
        if (peeker.fPatch != NULL) {
            GraphicsJNI::set_jrect(env, padding,
                    peeker.fPatch->paddingLeft, peeker.fPatch->paddingTop,
                    peeker.fPatch->paddingRight, peeker.fPatch->paddingBottom);
        } else {
            GraphicsJNI::set_jrect(env, padding, -1, -1, -1, -1);
        }
    }

    SkPixelRef* pr;
    if (isPurgeable) {
        pr = installPixelRef(outputBitmap, stream, sampleSize, doDither);
    } else {
        // if we get here, we're in kDecodePixels_Mode and will therefore
        // already have a pixelref installed.
        pr = outputBitmap->pixelRef();
    }
    if (pr == NULL) {
        return nullObjectReturn("Got null SkPixelRef");
    }

    if (!isMutable && javaBitmap == NULL) {
        // promise we will never change our pixels (great for sharing and pictures)
        pr->setImmutable();
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
            bitmapCreateFlags, ninePatchChunk, layoutBounds, -1);
}

static jobject nativeDecodeStream(JNIEnv* env, jobject clazz, jobject is, jbyteArray storage,
        jobject padding, jobject options) {

    jobject bitmap = NULL;
    SkAutoTUnref<SkStream> stream(CreateJavaInputStreamAdaptor(env, is, storage));

    if (stream.get()) {
        // Need to buffer enough input to be able to rewind as much as might be read by a decoder
        // trying to determine the stream's format. Currently the most is 64, read by
        // SkImageDecoder_libwebp.
        // FIXME: Get this number from SkImageDecoder
        SkAutoTUnref<SkStreamRewindable> bufferedStream(SkFrontBufferedStream::Create(stream, 64));
        SkASSERT(bufferedStream.get() != NULL);
        // for now we don't allow purgeable with java inputstreams
        bitmap = doDecode(env, bufferedStream, padding, options, false, false);
    }
    return bitmap;
}

static jobject nativeDecodeFileDescriptor(JNIEnv* env, jobject clazz, jobject fileDescriptor,
        jobject padding, jobject bitmapFactoryOptions) {

    NPE_CHECK_RETURN_ZERO(env, fileDescriptor);

    jint descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);

    struct stat fdStat;
    if (fstat(descriptor, &fdStat) == -1) {
        doThrowIOE(env, "broken file descriptor");
        return nullObjectReturn("fstat return -1");
    }

    bool isPurgeable = optionsPurgeable(env, bitmapFactoryOptions);
    bool isShareable = optionsShareable(env, bitmapFactoryOptions);
    bool weOwnTheFD = false;
    if (isPurgeable && isShareable) {
        int newFD = ::dup(descriptor);
        if (-1 != newFD) {
            weOwnTheFD = true;
            descriptor = newFD;
        }
    }

    SkAutoTUnref<SkData> data(SkData::NewFromFD(descriptor));
    if (data.get() == NULL) {
        return nullObjectReturn("NewFromFD failed in nativeDecodeFileDescriptor");
    }
    SkAutoTUnref<SkMemoryStream> stream(new SkMemoryStream(data));

    /* Allow purgeable iff we own the FD, i.e., in the puregeable and
       shareable case.
    */
    return doDecode(env, stream, padding, bitmapFactoryOptions, weOwnTheFD);
}

static jobject nativeDecodeAsset(JNIEnv* env, jobject clazz, jint native_asset,
        jobject padding, jobject options) {

    SkStreamRewindable* stream;
    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    bool forcePurgeable = optionsPurgeable(env, options);
    if (forcePurgeable) {
        // if we could "ref/reopen" the asset, we may not need to copy it here
        // and we could assume optionsShareable, since assets are always RO
        stream = CopyAssetToStream(asset);
        if (stream == NULL) {
            return NULL;
        }
    } else {
        // since we know we'll be done with the asset when we return, we can
        // just use a simple wrapper
        stream = new AssetStreamAdaptor(asset);
    }
    SkAutoUnref aur(stream);
    return doDecode(env, stream, padding, options, forcePurgeable, forcePurgeable);
}

static jobject nativeDecodeByteArray(JNIEnv* env, jobject, jbyteArray byteArray,
        int offset, int length, jobject options) {

    /*  If optionsShareable() we could decide to just wrap the java array and
        share it, but that means adding a globalref to the java array object
        and managing its lifetime. For now we just always copy the array's data
        if optionsPurgeable(), unless we're just decoding bounds.
     */
    bool purgeable = optionsPurgeable(env, options) && !optionsJustBounds(env, options);
    AutoJavaByteArray ar(env, byteArray);
    SkMemoryStream* stream = new SkMemoryStream(ar.ptr() + offset, length, purgeable);
    SkAutoUnref aur(stream);
    return doDecode(env, stream, NULL, options, purgeable);
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
        "(ILandroid/graphics/Rect;Landroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;",
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
    gOptions_purgeableFieldID = getFieldIDCheck(env, options_class, "inPurgeable", "Z");
    gOptions_shareableFieldID = getFieldIDCheck(env, options_class, "inInputShareable", "Z");
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
    gBitmap_nativeBitmapFieldID = getFieldIDCheck(env, bitmap_class, "mNativeBitmap", "I");
    gBitmap_layoutBoundsFieldID = getFieldIDCheck(env, bitmap_class, "mLayoutBounds", "[I");
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
