#define LOG_TAG "BitmapFactory"

#include "BitmapFactory.h"
#include "NinePatchPeeker.h"
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

#include <android_runtime/AndroidRuntime.h>
#include <androidfw/Asset.h>
#include <androidfw/ResourceTypes.h>
#include <netinet/in.h>
#include <sys/mman.h>
#include <sys/stat.h>

jfieldID gOptions_justBoundsFieldID;
jfieldID gOptions_sampleSizeFieldID;
jfieldID gOptions_configFieldID;
jfieldID gOptions_mutableFieldID;
jfieldID gOptions_ditherFieldID;
jfieldID gOptions_purgeableFieldID;
jfieldID gOptions_shareableFieldID;
jfieldID gOptions_preferQualityOverSpeedFieldID;
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

static jbyteArray nativeScaleNinePatch(JNIEnv* env, jobject, jbyteArray chunkObject, jfloat scale,
        jobject padding) {

    jbyte* array = env->GetByteArrayElements(chunkObject, 0);
    if (array != NULL) {
        size_t chunkSize = env->GetArrayLength(chunkObject);
        void* storage = alloca(chunkSize);
        android::Res_png_9patch* chunk = static_cast<android::Res_png_9patch*>(storage);
        memcpy(chunk, array, chunkSize);
        android::Res_png_9patch::deserialize(chunk);

        scaleNinePatchChunk(chunk, scale);
        memcpy(array, chunk, chunkSize);

        if (padding) {
            GraphicsJNI::set_jrect(env, padding, chunk->paddingLeft, chunk->paddingTop,
                    chunk->paddingRight, chunk->paddingBottom);
        }

        env->ReleaseByteArrayElements(chunkObject, array, 0);
    }
    return chunkObject;
}

static SkPixelRef* installPixelRef(SkBitmap* bitmap, SkStream* stream,
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

// since we "may" create a purgeable imageref, we require the stream be ref'able
// i.e. dynamically allocated, since its lifetime may exceed the current stack
// frame.
static jobject doDecode(JNIEnv* env, SkStream* stream, jobject padding,
        jobject options, bool allowPurgeable, bool forcePurgeable = false,
        bool applyScale = false, float scale = 1.0f) {

    int sampleSize = 1;

    SkImageDecoder::Mode mode = SkImageDecoder::kDecodePixels_Mode;
    SkBitmap::Config prefConfig = SkBitmap::kARGB_8888_Config;

    bool doDither = true;
    bool isMutable = false;
    bool willScale = applyScale && scale != 1.0f;
    bool isPurgeable = !willScale &&
            (forcePurgeable || (allowPurgeable && optionsPurgeable(env, options)));
    bool preferQualityOverSpeed = false;

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
        javaBitmap = env->GetObjectField(options, gOptions_bitmapFieldID);
    }

    if (willScale && javaBitmap != NULL) {
        return nullObjectReturn("Cannot pre-scale a reused bitmap");
    }

    SkImageDecoder* decoder = SkImageDecoder::Factory(stream);
    if (decoder == NULL) {
        return nullObjectReturn("SkImageDecoder::Factory returned null");
    }

    decoder->setSampleSize(sampleSize);
    decoder->setDitherImage(doDither);
    decoder->setPreferQualityOverSpeed(preferQualityOverSpeed);

    NinePatchPeeker peeker(decoder);
    JavaPixelAllocator javaAllocator(env);

    SkBitmap* bitmap;
    if (javaBitmap == NULL) {
        bitmap = new SkBitmap;
    } else {
        if (sampleSize != 1) {
            return nullObjectReturn("SkImageDecoder: Cannot reuse bitmap with sampleSize != 1");
        }
        bitmap = (SkBitmap*) env->GetIntField(javaBitmap, gBitmap_nativeBitmapFieldID);
        // config of supplied bitmap overrules config set in options
        prefConfig = bitmap->getConfig();
    }

    SkAutoTDelete<SkImageDecoder> add(decoder);
    SkAutoTDelete<SkBitmap> adb(bitmap, javaBitmap == NULL);

    decoder->setPeeker(&peeker);
    if (!isPurgeable) {
        decoder->setAllocator(&javaAllocator);
    }

    AutoDecoderCancel adc(options, decoder);

    // To fix the race condition in case "requestCancelDecode"
    // happens earlier than AutoDecoderCancel object is added
    // to the gAutoDecoderCancelMutex linked list.
    if (options != NULL && env->GetBooleanField(options, gOptions_mCancelID)) {
        return nullObjectReturn("gOptions_mCancelID");
    }

    SkImageDecoder::Mode decodeMode = mode;
    if (isPurgeable) {
        decodeMode = SkImageDecoder::kDecodeBounds_Mode;
    }

    SkBitmap* decoded;
    if (willScale) {
        decoded = new SkBitmap;
    } else {
        decoded = bitmap;
    }
    SkAutoTDelete<SkBitmap> adb2(willScale ? decoded : NULL);

    if (!decoder->decode(stream, decoded, prefConfig, decodeMode, javaBitmap != NULL)) {
        return nullObjectReturn("decoder->decode returned false");
    }

    int scaledWidth = decoded->width();
    int scaledHeight = decoded->height();

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
        const float sx = scaledWidth / float(decoded->width());
        const float sy = scaledHeight / float(decoded->height());

        SkBitmap::Config config = decoded->config();
        switch (config) {
            case SkBitmap::kNo_Config:
            case SkBitmap::kIndex8_Config:
            case SkBitmap::kRLE_Index8_Config:
                config = SkBitmap::kARGB_8888_Config;
                break;
            default:
                break;
        }

        bitmap->setConfig(config, scaledWidth, scaledHeight);
        bitmap->setIsOpaque(decoded->isOpaque());
        if (!bitmap->allocPixels(&javaAllocator, NULL)) {
            return nullObjectReturn("allocation failed for scaled bitmap");
        }
        bitmap->eraseColor(0);

        SkPaint paint;
        paint.setFilterBitmap(true);

        SkCanvas canvas(*bitmap);
        canvas.scale(sx, sy);
        canvas.drawBitmap(*decoded, 0.0f, 0.0f, &paint);
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
        pr = installPixelRef(bitmap, stream, sampleSize, doDither);
    } else {
        // if we get here, we're in kDecodePixels_Mode and will therefore
        // already have a pixelref installed.
        pr = bitmap->pixelRef();
    }
    if (pr == NULL) {
        return nullObjectReturn("Got null SkPixelRef");
    }

    if (!isMutable) {
        // promise we will never change our pixels (great for sharing and pictures)
        pr->setImmutable();
    }

    // detach bitmap from its autodeleter, since we want to own it now
    adb.detach();

    if (javaBitmap != NULL) {
        // If a java bitmap was passed in for reuse, pass it back
        return javaBitmap;
    }
    // now create the java bitmap
    return GraphicsJNI::createBitmap(env, bitmap, javaAllocator.getStorageObj(),
            isMutable, ninePatchChunk, layoutBounds, -1);
}

static jobject nativeDecodeStreamScaled(JNIEnv* env, jobject clazz, jobject is, jbyteArray storage,
        jobject padding, jobject options, jboolean applyScale, jfloat scale) {

    jobject bitmap = NULL;
    SkStream* stream = CreateJavaInputStreamAdaptor(env, is, storage, 0);

    if (stream) {
        // for now we don't allow purgeable with java inputstreams
        bitmap = doDecode(env, stream, padding, options, false, false, applyScale, scale);
        stream->unref();
    }
    return bitmap;
}

static jobject nativeDecodeStream(JNIEnv* env, jobject clazz, jobject is, jbyteArray storage,
        jobject padding, jobject options) {

    return nativeDecodeStreamScaled(env, clazz, is, storage, padding, options, false, 1.0f);
}

static ssize_t getFDSize(int fd) {
    off64_t curr = ::lseek64(fd, 0, SEEK_CUR);
    if (curr < 0) {
        return 0;
    }
    size_t size = ::lseek(fd, 0, SEEK_END);
    ::lseek64(fd, curr, SEEK_SET);
    return size;
}

static jobject nativeDecodeFileDescriptor(JNIEnv* env, jobject clazz, jobject fileDescriptor,
        jobject padding, jobject bitmapFactoryOptions) {

    NPE_CHECK_RETURN_ZERO(env, fileDescriptor);

    jint descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);

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

    SkFDStream* stream = new SkFDStream(descriptor, weOwnTheFD);
    SkAutoUnref aur(stream);
    if (!stream->isValid()) {
        return NULL;
    }

    /* Restore our offset when we leave, so we can be called more than once
       with the same descriptor. This is only required if we didn't dup the
       file descriptor, but it is OK to do it all the time.
    */
    AutoFDSeek as(descriptor);

    /* Allow purgeable iff we own the FD, i.e., in the puregeable and
       shareable case.
    */
    return doDecode(env, stream, padding, bitmapFactoryOptions, weOwnTheFD);
}

/*  make a deep copy of the asset, and return it as a stream, or NULL if there
    was an error.
 */
static SkStream* copyAssetToStream(Asset* asset) {
    // if we could "ref/reopen" the asset, we may not need to copy it here
    off64_t size = asset->seek(0, SEEK_SET);
    if ((off64_t)-1 == size) {
        SkDebugf("---- copyAsset: asset rewind failed\n");
        return NULL;
    }

    size = asset->getLength();
    if (size <= 0) {
        SkDebugf("---- copyAsset: asset->getLength() returned %d\n", size);
        return NULL;
    }

    SkStream* stream = new SkMemoryStream(size);
    void* data = const_cast<void*>(stream->getMemoryBase());
    off64_t len = asset->read(data, size);
    if (len != size) {
        SkDebugf("---- copyAsset: asset->read(%d) returned %d\n", size, len);
        delete stream;
        stream = NULL;
    }
    return stream;
}

static jobject nativeDecodeAssetScaled(JNIEnv* env, jobject clazz, jint native_asset,
        jobject padding, jobject options, jboolean applyScale, jfloat scale) {

    SkStream* stream;
    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    bool forcePurgeable = optionsPurgeable(env, options);
    if (forcePurgeable) {
        // if we could "ref/reopen" the asset, we may not need to copy it here
        // and we could assume optionsShareable, since assets are always RO
        stream = copyAssetToStream(asset);
        if (stream == NULL) {
            return NULL;
        }
    } else {
        // since we know we'll be done with the asset when we return, we can
        // just use a simple wrapper
        stream = new AssetStreamAdaptor(asset);
    }
    SkAutoUnref aur(stream);
    return doDecode(env, stream, padding, options, true, forcePurgeable, applyScale, scale);
}

static jobject nativeDecodeAsset(JNIEnv* env, jobject clazz, jint native_asset,
        jobject padding, jobject options) {

    return nativeDecodeAssetScaled(env, clazz, native_asset, padding, options, false, 1.0f);
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
    SkStream* stream = new SkMemoryStream(ar.ptr() + offset, length, purgeable);
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
    {   "nativeDecodeStream",
        "(Ljava/io/InputStream;[BLandroid/graphics/Rect;Landroid/graphics/BitmapFactory$Options;ZF)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeStreamScaled
    },

    {   "nativeDecodeFileDescriptor",
        "(Ljava/io/FileDescriptor;Landroid/graphics/Rect;Landroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeFileDescriptor
    },

    {   "nativeDecodeAsset",
        "(ILandroid/graphics/Rect;Landroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeAsset
    },

    {   "nativeDecodeAsset",
        "(ILandroid/graphics/Rect;Landroid/graphics/BitmapFactory$Options;ZF)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeAssetScaled
    },

    {   "nativeDecodeByteArray",
        "([BIILandroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeByteArray
    },

    {   "nativeScaleNinePatch",
        "([BFLandroid/graphics/Rect;)[B",
        (void*)nativeScaleNinePatch
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
    gOptions_mutableFieldID = getFieldIDCheck(env, options_class, "inMutable", "Z");
    gOptions_ditherFieldID = getFieldIDCheck(env, options_class, "inDither", "Z");
    gOptions_purgeableFieldID = getFieldIDCheck(env, options_class, "inPurgeable", "Z");
    gOptions_shareableFieldID = getFieldIDCheck(env, options_class, "inInputShareable", "Z");
    gOptions_preferQualityOverSpeedFieldID = getFieldIDCheck(env, options_class,
            "inPreferQualityOverSpeed", "Z");
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
