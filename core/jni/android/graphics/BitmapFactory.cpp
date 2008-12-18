#include "SkImageDecoder.h"
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
static jfieldID gOptions_widthFieldID;
static jfieldID gOptions_heightFieldID;
static jfieldID gOptions_mimeFieldID;

static jclass gFileDescriptor_class;
static jfieldID gFileDescriptor_descriptor;

#if 0
    #define TRACE_BITMAP(code)  code
#else
    #define TRACE_BITMAP(code)
#endif

//#define MIN_SIZE_TO_USE_MMAP    (4*1024)

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
public:
    NinePatchPeeker() {
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
        return pos != (off_t)-1;
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
                return 0;
            }
            off_t newOffset = fAsset->seek(size, SEEK_CUR);
            if (-1 == newOffset) {
                return 0;
            }
            amount = newOffset - oldOffset;
        } else {
            amount = fAsset->read(buffer, size);
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

static jobject doDecode(JNIEnv* env, SkStream* stream, jobject padding,
                        jobject options) {

    int sampleSize = 1;
    SkImageDecoder::Mode mode = SkImageDecoder::kDecodePixels_Mode;
    SkBitmap::Config prefConfig = SkBitmap::kNo_Config;
    bool doDither = true;
    
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
        return NULL;
    }
    
    decoder->setSampleSize(sampleSize);
    decoder->setDitherImage(doDither);

    NinePatchPeeker     peeker;
    JavaPixelAllocator  allocator(env);
    SkBitmap*           bitmap = new SkBitmap;
    Res_png_9patch      dummy9Patch;

    SkAutoTDelete<SkImageDecoder>   add(decoder);
    SkAutoTDelete<SkBitmap>         adb(bitmap);

    decoder->setPeeker(&peeker);
    decoder->setAllocator(&allocator);
    
    AutoDecoderCancel   adc(options, decoder);

    if (!decoder->decode(stream, bitmap, prefConfig, mode)) {
        return NULL;
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
            return NULL;
        }
        jbyte* array = (jbyte*)env->GetPrimitiveArrayCritical(ninePatchChunk,
                                                              NULL);
        if (NULL == array) {
            return NULL;
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
    
    // promise we will never change our pixels (great for sharing and pictures)
    SkPixelRef* ref = bitmap->pixelRef();
    SkASSERT(ref);
    ref->setImmutable();

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
        bitmap = doDecode(env, stream, padding, options);
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
    
#ifdef MIN_SIZE_TO_USE_MMAP
    // First try to use mmap
    size_t size = getFDSize(descriptor);
    if (size >= MIN_SIZE_TO_USE_MMAP) {
        void* addr = mmap(NULL, size, PROT_READ, MAP_PRIVATE, descriptor, 0);
//        SkDebugf("-------- mmap returned %p %d\n", addr, size);
        if (MAP_FAILED != addr) {
            SkMemoryStream strm(addr, size);
            jobject obj = doDecode(env, &strm, padding, bitmapFactoryOptions);
            munmap(addr, size);
            return obj;
        }
    }
#endif

    // we pass false for closeWhenDone, since the caller owns the descriptor    
    SkFDStream file(descriptor, false);
    if (!file.isValid()) {
        return NULL;
    }
    
    /* Restore our offset when we leave, so the caller doesn't have to.
       This is a real feature, so we can be called more than once with the
       same descriptor.
    */
    AutoFDSeek as(descriptor);

    return doDecode(env, &file, padding, bitmapFactoryOptions);
}

static jobject nativeDecodeAsset(JNIEnv* env, jobject clazz,
                                 jint native_asset,    // Asset
                                 jobject padding,       // Rect
                                 jobject options) { // BitmapFactory$Options
    AssetStreamAdaptor  mystream((Asset*)native_asset);

    return doDecode(env, &mystream, padding, options);
}

static jobject nativeDecodeByteArray(JNIEnv* env, jobject, jbyteArray byteArray,
                                     int offset, int length, jobject options) {
    AutoJavaByteArray   ar(env, byteArray);
    SkMemoryStream  stream(ar.ptr() + offset, length);

    return doDecode(env, &stream, NULL, options);
}

static void nativeRequestCancel(JNIEnv*, jobject joptions) {
    (void)AutoDecoderCancel::RequestCancel(joptions);
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
    }
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
    gOptions_widthFieldID = getFieldIDCheck(env, gOptions_class, "outWidth", "I");
    gOptions_heightFieldID = getFieldIDCheck(env, gOptions_class, "outHeight", "I");
    gOptions_mimeFieldID = getFieldIDCheck(env, gOptions_class, "outMimeType", "Ljava/lang/String;");

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
