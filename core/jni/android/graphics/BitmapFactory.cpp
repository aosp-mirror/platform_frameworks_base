#define LOG_TAG "BitmapFactory"

#include "SkImageDecoder.h"
#include "SkImageRef_ashmem.h"
#include "SkImageRef_GlobalPool.h"
#include "SkPixelRef.h"
#include "SkStream.h"
#include "GraphicsJNI.h"
#include "SkTemplates.h"
#include "SkUtils.h"
#include "CreateJavaOutputStreamAdaptor.h"

#include <android_runtime/AndroidRuntime.h>
#include <utils/Asset.h>
#include <utils/ResourceTypes.h>
#include <netinet/in.h>
#include <sys/mman.h>

static jclass gOptions_class;
static jfieldID gOptions_justBoundsFieldID;
static jfieldID gOptions_sampleSizeFieldID;
static jfieldID gOptions_configFieldID;
static jfieldID gOptions_ditherFieldID;
static jfieldID gOptions_purgeableFieldID;
static jfieldID gOptions_shareableFieldID;
static jfieldID gOptions_nativeAllocFieldID;
static jfieldID gOptions_widthFieldID;
static jfieldID gOptions_heightFieldID;
static jfieldID gOptions_mimeFieldID;
static jfieldID gOptions_mCancelID;

static jclass gFileDescriptor_class;
static jfieldID gFileDescriptor_descriptor;

#if 0
    #define TRACE_BITMAP(code)  code
#else
    #define TRACE_BITMAP(code)
#endif

///////////////////////////////////////////////////////////////////////////////

class AutoDecoderCancel {
public:
    AutoDecoderCancel(jobject options, SkImageDecoder* decoder);
    ~AutoDecoderCancel();

    static bool RequestCancel(jobject options);
    
private:
    AutoDecoderCancel*  fNext;
    AutoDecoderCancel*  fPrev;
    jobject             fJOptions;  // java options object
    SkImageDecoder*     fDecoder;
    
#ifdef SK_DEBUG
    static void Validate();
#else
    static void Validate() {}
#endif
};

static SkMutex  gAutoDecoderCancelMutex;
static AutoDecoderCancel* gAutoDecoderCancel;
#ifdef SK_DEBUG
    static int gAutoDecoderCancelCount;
#endif

AutoDecoderCancel::AutoDecoderCancel(jobject joptions,
                                       SkImageDecoder* decoder) {
    fJOptions = joptions;
    fDecoder = decoder;

    if (NULL != joptions) {
        SkAutoMutexAcquire ac(gAutoDecoderCancelMutex);

        // Add us as the head of the list
        fPrev = NULL;
        fNext = gAutoDecoderCancel;
        if (gAutoDecoderCancel) {
            gAutoDecoderCancel->fPrev = this;
        }
        gAutoDecoderCancel = this;
        
        SkDEBUGCODE(gAutoDecoderCancelCount += 1;)
        Validate();
    }
}

AutoDecoderCancel::~AutoDecoderCancel() {
    if (NULL != fJOptions) {
        SkAutoMutexAcquire ac(gAutoDecoderCancelMutex);
        
        // take us out of the dllist
        AutoDecoderCancel* prev = fPrev;
        AutoDecoderCancel* next = fNext;
        
        if (prev) {
            SkASSERT(prev->fNext == this);
            prev->fNext = next;
        } else {
            SkASSERT(gAutoDecoderCancel == this);
            gAutoDecoderCancel = next;
        }
        if (next) {
            SkASSERT(next->fPrev == this);
            next->fPrev = prev;
        }

        SkDEBUGCODE(gAutoDecoderCancelCount -= 1;)
        Validate();
    }
}

bool AutoDecoderCancel::RequestCancel(jobject joptions) {
    SkAutoMutexAcquire ac(gAutoDecoderCancelMutex);

    Validate();

    AutoDecoderCancel* pair = gAutoDecoderCancel;
    while (pair != NULL) {
        if (pair->fJOptions == joptions) {
            pair->fDecoder->cancelDecode();
            return true;
        }
        pair = pair->fNext;
    }
    return false;
}

#ifdef SK_DEBUG
// can only call this inside a lock on gAutoDecoderCancelMutex 
void AutoDecoderCancel::Validate() {
    const int gCount = gAutoDecoderCancelCount;

    if (gCount == 0) {
        SkASSERT(gAutoDecoderCancel == NULL);
    } else {
        SkASSERT(gCount > 0);
        
        AutoDecoderCancel* curr = gAutoDecoderCancel;
        SkASSERT(curr);
        SkASSERT(curr->fPrev == NULL);

        int count = 0;
        while (curr) {
            count += 1;
            SkASSERT(count <= gCount);
            if (curr->fPrev) {
                SkASSERT(curr->fPrev->fNext == curr);
            }
            if (curr->fNext) {
                SkASSERT(curr->fNext->fPrev == curr);
            }
            curr = curr->fNext;
        }
        SkASSERT(count == gCount);
    }
}
#endif

///////////////////////////////////////////////////////////////////////////////

using namespace android;

class NinePatchPeeker : public SkImageDecoder::Peeker {
    SkImageDecoder* fHost;
public:
    NinePatchPeeker(SkImageDecoder* host) {
        // the host lives longer than we do, so a raw ptr is safe
        fHost = host;
        fPatchIsValid = false;
    }

    ~NinePatchPeeker() {
        if (fPatchIsValid) {
            free(fPatch);
        }
    }

    bool    fPatchIsValid;
    Res_png_9patch*  fPatch;

    virtual bool peek(const char tag[], const void* data, size_t length) {
        if (strcmp("npTc", tag) == 0 && length >= sizeof(Res_png_9patch)) {
            Res_png_9patch* patch = (Res_png_9patch*) data;
            size_t patchSize = patch->serializedSize();
            assert(length == patchSize);
            // You have to copy the data because it is owned by the png reader
            Res_png_9patch* patchNew = (Res_png_9patch*) malloc(patchSize);
            memcpy(patchNew, patch, patchSize);
            // this relies on deserialization being done in place
            Res_png_9patch::deserialize(patchNew);
            patchNew->fileToDevice();
            if (fPatchIsValid) {
                free(fPatch);
            }
            fPatch = patchNew;
            //printf("9patch: (%d,%d)-(%d,%d)\n",
            //       fPatch.sizeLeft, fPatch.sizeTop,
            //       fPatch.sizeRight, fPatch.sizeBottom);
            fPatchIsValid = true;

            // now update our host to force index or 32bit config
            // 'cause we don't want 565 predithered, since as a 9patch, we know
            // we will be stretched, and therefore we want to dither afterwards.
            static const SkBitmap::Config gNo565Pref[] = {
                SkBitmap::kIndex8_Config,
                SkBitmap::kIndex8_Config,
                SkBitmap::kARGB_8888_Config,
                SkBitmap::kARGB_8888_Config,
                SkBitmap::kARGB_8888_Config,
                SkBitmap::kARGB_8888_Config,
            };
            fHost->setPrefConfigTable(gNo565Pref);
        } else {
            fPatch = NULL;
        }
        return true;    // keep on decoding
    }
};

class AssetStreamAdaptor : public SkStream {
public:
    AssetStreamAdaptor(Asset* a) : fAsset(a) {}
    
    virtual bool rewind() {
        off_t pos = fAsset->seek(0, SEEK_SET);
        if (pos == (off_t)-1) {
            SkDebugf("----- fAsset->seek(rewind) failed\n");
            return false;
        }
        return true;
    }
    
    virtual size_t read(void* buffer, size_t size) {
        ssize_t amount;
        
        if (NULL == buffer) {
            if (0 == size) {  // caller is asking us for our total length
                return fAsset->getLength();
            }
            // asset->seek returns new total offset
            // we want to return amount that was skipped

            off_t oldOffset = fAsset->seek(0, SEEK_CUR);
            if (-1 == oldOffset) {
                SkDebugf("---- fAsset->seek(oldOffset) failed\n");
                return 0;
            }
            off_t newOffset = fAsset->seek(size, SEEK_CUR);
            if (-1 == newOffset) {
                SkDebugf("---- fAsset->seek(%d) failed\n", size);
                return 0;
            }
            amount = newOffset - oldOffset;
        } else {
            amount = fAsset->read(buffer, size);
            if (amount <= 0) {
                SkDebugf("---- fAsset->read(%d) returned %d\n", size, amount);
            }
        }
        
        if (amount < 0) {
            amount = 0;
        }
        return amount;
    }
    
private:
    Asset*  fAsset;
};

///////////////////////////////////////////////////////////////////////////////

static inline int32_t validOrNeg1(bool isValid, int32_t value) {
//    return isValid ? value : -1;
    SkASSERT((int)isValid == 0 || (int)isValid == 1);
    return ((int32_t)isValid - 1) | value;
}

static jstring getMimeTypeString(JNIEnv* env, SkImageDecoder::Format format) {
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
    return options != NULL &&
            env->GetBooleanField(options, gOptions_purgeableFieldID);
}

static bool optionsShareable(JNIEnv* env, jobject options) {
    return options != NULL &&
            env->GetBooleanField(options, gOptions_shareableFieldID);
}

static bool optionsReportSizeToVM(JNIEnv* env, jobject options) {
    return NULL == options ||
            !env->GetBooleanField(options, gOptions_nativeAllocFieldID);
}

static jobject nullObjectReturn(const char msg[]) {
    if (msg) {
        SkDebugf("--- %s\n", msg);
    }
    return NULL;
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
    return pr;
}

// since we "may" create a purgeable imageref, we require the stream be ref'able
// i.e. dynamically allocated, since its lifetime may exceed the current stack
// frame.
static jobject doDecode(JNIEnv* env, SkStream* stream, jobject padding,
                        jobject options, bool allowPurgeable,
                        bool forcePurgeable = false) {
    int sampleSize = 1;
    SkImageDecoder::Mode mode = SkImageDecoder::kDecodePixels_Mode;
    SkBitmap::Config prefConfig = SkBitmap::kNo_Config;
    bool doDither = true;
    bool isPurgeable = forcePurgeable ||
                        (allowPurgeable && optionsPurgeable(env, options));
    bool reportSizeToVM = optionsReportSizeToVM(env, options);
    
    if (NULL != options) {
        sampleSize = env->GetIntField(options, gOptions_sampleSizeFieldID);
        if (env->GetBooleanField(options, gOptions_justBoundsFieldID)) {
            mode = SkImageDecoder::kDecodeBounds_Mode;
        }
        // initialize these, in case we fail later on
        env->SetIntField(options, gOptions_widthFieldID, -1);
        env->SetIntField(options, gOptions_heightFieldID, -1);
        env->SetObjectField(options, gOptions_mimeFieldID, 0);
        
        jobject jconfig = env->GetObjectField(options, gOptions_configFieldID);
        prefConfig = GraphicsJNI::getNativeBitmapConfig(env, jconfig);
        doDither = env->GetBooleanField(options, gOptions_ditherFieldID);
    }

    SkImageDecoder* decoder = SkImageDecoder::Factory(stream);
    if (NULL == decoder) {
        return nullObjectReturn("SkImageDecoder::Factory returned null");
    }
    
    decoder->setSampleSize(sampleSize);
    decoder->setDitherImage(doDither);

    NinePatchPeeker     peeker(decoder);
    JavaPixelAllocator  javaAllocator(env, reportSizeToVM);
    SkBitmap*           bitmap = new SkBitmap;
    Res_png_9patch      dummy9Patch;

    SkAutoTDelete<SkImageDecoder>   add(decoder);
    SkAutoTDelete<SkBitmap>         adb(bitmap);

    decoder->setPeeker(&peeker);
    if (!isPurgeable) {
        decoder->setAllocator(&javaAllocator);
    }

    AutoDecoderCancel   adc(options, decoder);

    // To fix the race condition in case "requestCancelDecode"
    // happens earlier than AutoDecoderCancel object is added
    // to the gAutoDecoderCancelMutex linked list.
    if (NULL != options && env->GetBooleanField(options, gOptions_mCancelID)) {
        return nullObjectReturn("gOptions_mCancelID");;
    }

    SkImageDecoder::Mode decodeMode = mode;
    if (isPurgeable) {
        decodeMode = SkImageDecoder::kDecodeBounds_Mode;
    }
    if (!decoder->decode(stream, bitmap, prefConfig, decodeMode)) {
        return nullObjectReturn("decoder->decode returned false");
    }

    // update options (if any)
    if (NULL != options) {
        env->SetIntField(options, gOptions_widthFieldID, bitmap->width());
        env->SetIntField(options, gOptions_heightFieldID, bitmap->height());
        // TODO: set the mimeType field with the data from the codec.
        // but how to reuse a set of strings, rather than allocating new one
        // each time?
        env->SetObjectField(options, gOptions_mimeFieldID,
                            getMimeTypeString(env, decoder->getFormat()));
    }

    // if we're in justBounds mode, return now (skip the java bitmap)
    if (SkImageDecoder::kDecodeBounds_Mode == mode) {
        return NULL;
    }

    jbyteArray ninePatchChunk = NULL;
    if (peeker.fPatchIsValid) {
        size_t ninePatchArraySize = peeker.fPatch->serializedSize();
        ninePatchChunk = env->NewByteArray(ninePatchArraySize);
        if (NULL == ninePatchChunk) {
            return nullObjectReturn("ninePatchChunk == null");
        }
        jbyte* array = (jbyte*)env->GetPrimitiveArrayCritical(ninePatchChunk,
                                                              NULL);
        if (NULL == array) {
            return nullObjectReturn("primitive array == null");
        }
        peeker.fPatch->serialize(array);
        env->ReleasePrimitiveArrayCritical(ninePatchChunk, array, 0);
    }

    // detach bitmap from its autotdeleter, since we want to own it now
    adb.detach();

    if (padding) {
        if (peeker.fPatchIsValid) {
            GraphicsJNI::set_jrect(env, padding,
                                   peeker.fPatch->paddingLeft,
                                   peeker.fPatch->paddingTop,
                                   peeker.fPatch->paddingRight,
                                   peeker.fPatch->paddingBottom);
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
    // promise we will never change our pixels (great for sharing and pictures)
    pr->setImmutable();
    // now create the java bitmap
    return GraphicsJNI::createBitmap(env, bitmap, false, ninePatchChunk);
}

static jobject nativeDecodeStream(JNIEnv* env, jobject clazz,
                                  jobject is,       // InputStream
                                  jbyteArray storage,   // byte[]
                                  jobject padding,
                                  jobject options) {  // BitmapFactory$Options
    jobject bitmap = NULL;
    SkStream* stream = CreateJavaInputStreamAdaptor(env, is, storage);

    if (stream) {
        // for now we don't allow purgeable with java inputstreams
        bitmap = doDecode(env, stream, padding, options, false);
        stream->unref();
    }
    return bitmap;
}

static ssize_t getFDSize(int fd) {
    off_t curr = ::lseek(fd, 0, SEEK_CUR);
    if (curr < 0) {
        return 0;
    }
    size_t size = ::lseek(fd, 0, SEEK_END);
    ::lseek(fd, curr, SEEK_SET);
    return size;
}

/** Restore the file descriptor's offset in our destructor
 */
class AutoFDSeek {
public:
    AutoFDSeek(int fd) : fFD(fd) {
        fCurr = ::lseek(fd, 0, SEEK_CUR);
    }
    ~AutoFDSeek() {
        if (fCurr >= 0) {
            ::lseek(fFD, fCurr, SEEK_SET);
        }
    }
private:
    int     fFD;
    off_t   fCurr;
};

static jobject nativeDecodeFileDescriptor(JNIEnv* env, jobject clazz,
                                          jobject fileDescriptor,
                                          jobject padding,
                                          jobject bitmapFactoryOptions) {
    NPE_CHECK_RETURN_ZERO(env, fileDescriptor);

    jint descriptor = env->GetIntField(fileDescriptor,
                                       gFileDescriptor_descriptor);

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
    off_t size = asset->seek(0, SEEK_SET);
    if ((off_t)-1 == size) {
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
    off_t len = asset->read(data, size);
    if (len != size) {
        SkDebugf("---- copyAsset: asset->read(%d) returned %d\n", size, len);
        delete stream;
        stream = NULL;
    }
    return stream;
}

static jobject nativeDecodeAsset(JNIEnv* env, jobject clazz,
                                 jint native_asset,    // Asset
                                 jobject padding,       // Rect
                                 jobject options) { // BitmapFactory$Options
    SkStream* stream;
    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    // assets can always be rebuilt, so force this
    bool forcePurgeable = true;

    if (forcePurgeable || optionsPurgeable(env, options)) {
        // if we could "ref/reopen" the asset, we may not need to copy it here
        // and we could assume optionsShareable, since assets are always RO
        stream = copyAssetToStream(asset);
        if (NULL == stream) {
            return NULL;
        }
    } else {
        // since we know we'll be done with the asset when we return, we can
        // just use a simple wrapper
        stream = new AssetStreamAdaptor(asset);
    }
    SkAutoUnref aur(stream);
    return doDecode(env, stream, padding, options, true, forcePurgeable);
}

static jobject nativeDecodeByteArray(JNIEnv* env, jobject, jbyteArray byteArray,
                                     int offset, int length, jobject options) {
    /*  If optionsShareable() we could decide to just wrap the java array and
        share it, but that means adding a globalref to the java array object
        and managing its lifetime. For now we just always copy the array's data
        if optionsPurgeable().
     */
    AutoJavaByteArray ar(env, byteArray);
    SkStream* stream = new SkMemoryStream(ar.ptr() + offset, length,
                                          optionsPurgeable(env, options));
    SkAutoUnref aur(stream);
    return doDecode(env, stream, NULL, options, true);
}

static void nativeRequestCancel(JNIEnv*, jobject joptions) {
    (void)AutoDecoderCancel::RequestCancel(joptions);
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

        memcpy(array, chunk, chunkSize);

        if (padding) {
            GraphicsJNI::set_jrect(env, padding, chunk->paddingLeft, chunk->paddingTop,
                    chunk->paddingRight, chunk->paddingBottom);
        }

        env->ReleaseByteArrayElements(chunkObject, array, 0);
    }
    return chunkObject;
}

static void nativeSetDefaultConfig(JNIEnv* env, jobject, int nativeConfig) {
    SkBitmap::Config config = static_cast<SkBitmap::Config>(nativeConfig);

    // these are the only default configs that make sense for codecs right now
    static const SkBitmap::Config gValidDefConfig[] = {
        SkBitmap::kRGB_565_Config,
        SkBitmap::kARGB_8888_Config,
    };

    for (size_t i = 0; i < SK_ARRAY_COUNT(gValidDefConfig); i++) {
        if (config == gValidDefConfig[i]) {
            SkImageDecoder::SetDeviceConfig(config);
            break;
        }
    }
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

    {   "nativeScaleNinePatch",
        "([BFLandroid/graphics/Rect;)[B",
        (void*)nativeScaleNinePatch
    },

    {   "nativeSetDefaultConfig", "(I)V", (void*)nativeSetDefaultConfig },
};

static JNINativeMethod gOptionsMethods[] = {
    {   "requestCancel", "()V", (void*)nativeRequestCancel }
};

static jclass make_globalref(JNIEnv* env, const char classname[]) {
    jclass c = env->FindClass(classname);
    SkASSERT(c);
    return (jclass)env->NewGlobalRef(c);
}

static jfieldID getFieldIDCheck(JNIEnv* env, jclass clazz,
                                const char fieldname[], const char type[]) {
    jfieldID id = env->GetFieldID(clazz, fieldname, type);
    SkASSERT(id);
    return id;
}

#define kClassPathName  "android/graphics/BitmapFactory"

#define RETURN_ERR_IF_NULL(value) \
    do { if (!(value)) { assert(0); return -1; } } while (false)

int register_android_graphics_BitmapFactory(JNIEnv* env);
int register_android_graphics_BitmapFactory(JNIEnv* env) {
    gOptions_class = make_globalref(env, "android/graphics/BitmapFactory$Options");
    gOptions_justBoundsFieldID = getFieldIDCheck(env, gOptions_class, "inJustDecodeBounds", "Z");
    gOptions_sampleSizeFieldID = getFieldIDCheck(env, gOptions_class, "inSampleSize", "I");
    gOptions_configFieldID = getFieldIDCheck(env, gOptions_class, "inPreferredConfig",
            "Landroid/graphics/Bitmap$Config;");
    gOptions_ditherFieldID = getFieldIDCheck(env, gOptions_class, "inDither", "Z");
    gOptions_purgeableFieldID = getFieldIDCheck(env, gOptions_class, "inPurgeable", "Z");
    gOptions_shareableFieldID = getFieldIDCheck(env, gOptions_class, "inInputShareable", "Z");
    gOptions_nativeAllocFieldID = getFieldIDCheck(env, gOptions_class, "inNativeAlloc", "Z");
    gOptions_widthFieldID = getFieldIDCheck(env, gOptions_class, "outWidth", "I");
    gOptions_heightFieldID = getFieldIDCheck(env, gOptions_class, "outHeight", "I");
    gOptions_mimeFieldID = getFieldIDCheck(env, gOptions_class, "outMimeType", "Ljava/lang/String;");
    gOptions_mCancelID = getFieldIDCheck(env, gOptions_class, "mCancel", "Z");

    gFileDescriptor_class = make_globalref(env, "java/io/FileDescriptor");
    gFileDescriptor_descriptor = getFieldIDCheck(env, gFileDescriptor_class, "descriptor", "I");

    int ret = AndroidRuntime::registerNativeMethods(env,
                                    "android/graphics/BitmapFactory$Options",
                                    gOptionsMethods,
                                    SK_ARRAY_COUNT(gOptionsMethods));
    if (ret) {
        return ret;
    }
    return android::AndroidRuntime::registerNativeMethods(env, kClassPathName,
                                         gMethods, SK_ARRAY_COUNT(gMethods));
}
