#include "CreateJavaOutputStreamAdaptor.h"
#include "JNIHelp.h"
#include "SkData.h"
#include "SkRefCnt.h"
#include "SkStream.h"
#include "SkTypes.h"
#include "Utils.h"
#include <androidfw/Asset.h>

static jmethodID    gInputStream_resetMethodID;
static jmethodID    gInputStream_markMethodID;
static jmethodID    gInputStream_markSupportedMethodID;
static jmethodID    gInputStream_readMethodID;
static jmethodID    gInputStream_skipMethodID;

class RewindableJavaStream;

/**
 *  Non-rewindable wrapper for a Java InputStream.
 */
class JavaInputStreamAdaptor : public SkStream {
public:
    JavaInputStreamAdaptor(JNIEnv* env, jobject js, jbyteArray ar)
        : fEnv(env), fJavaInputStream(js), fJavaByteArray(ar) {
        SkASSERT(ar);
        fCapacity = env->GetArrayLength(ar);
        SkASSERT(fCapacity > 0);
        fBytesRead = 0;
        fIsAtEnd = false;
    }

    virtual size_t read(void* buffer, size_t size) {
        JNIEnv* env = fEnv;
        if (NULL == buffer) {
            if (0 == size) {
                return 0;
            } else {
                /*  InputStream.skip(n) can return <=0 but still not be at EOF
                    If we see that value, we need to call read(), which will
                    block if waiting for more data, or return -1 at EOF
                 */
                size_t amountSkipped = 0;
                do {
                    size_t amount = this->doSkip(size - amountSkipped);
                    if (0 == amount) {
                        char tmp;
                        amount = this->doRead(&tmp, 1);
                        if (0 == amount) {
                            // if read returned 0, we're at EOF
                            fIsAtEnd = true;
                            break;
                        }
                    }
                    amountSkipped += amount;
                } while (amountSkipped < size);
                return amountSkipped;
            }
        }
        return this->doRead(buffer, size);
    }

    virtual bool isAtEnd() const {
        return fIsAtEnd;
    }

private:
    // Does not override rewind, since a JavaInputStreamAdaptor's interface
    // does not support rewinding. RewindableJavaStream, which is a friend,
    // will be able to call this method to rewind.
    bool doRewind() {
        JNIEnv* env = fEnv;

        fBytesRead = 0;
        fIsAtEnd = false;

        env->CallVoidMethod(fJavaInputStream, gInputStream_resetMethodID);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            SkDebugf("------- reset threw an exception\n");
            return false;
        }
        return true;
    }

    size_t doRead(void* buffer, size_t size) {
        JNIEnv* env = fEnv;
        size_t bytesRead = 0;
        // read the bytes
        do {
            size_t requested = size;
            if (requested > fCapacity)
                requested = fCapacity;

            jint n = env->CallIntMethod(fJavaInputStream,
                                        gInputStream_readMethodID, fJavaByteArray, 0, requested);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                SkDebugf("---- read threw an exception\n");
                return 0;
            }

            if (n < 0) { // n == 0 should not be possible, see InputStream read() specifications.
                fIsAtEnd = true;
                break;  // eof
            }

            env->GetByteArrayRegion(fJavaByteArray, 0, n,
                                    reinterpret_cast<jbyte*>(buffer));
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                SkDebugf("---- read:GetByteArrayRegion threw an exception\n");
                return 0;
            }

            buffer = (void*)((char*)buffer + n);
            bytesRead += n;
            size -= n;
            fBytesRead += n;
        } while (size != 0);

        return bytesRead;
    }

    size_t doSkip(size_t size) {
        JNIEnv* env = fEnv;

        jlong skipped = env->CallLongMethod(fJavaInputStream,
                                            gInputStream_skipMethodID, (jlong)size);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            SkDebugf("------- skip threw an exception\n");
            return 0;
        }
        if (skipped < 0) {
            skipped = 0;
        }

        return (size_t)skipped;
    }

    JNIEnv*     fEnv;
    jobject     fJavaInputStream;   // the caller owns this object
    jbyteArray  fJavaByteArray;     // the caller owns this object
    size_t      fCapacity;
    size_t      fBytesRead;
    bool        fIsAtEnd;

    // Allows access to doRewind and fBytesRead.
    friend class RewindableJavaStream;
};

SkStream* CreateJavaInputStreamAdaptor(JNIEnv* env, jobject stream,
                                       jbyteArray storage) {
    return new JavaInputStreamAdaptor(env, stream, storage);
}


static SkMemoryStream* adaptor_to_mem_stream(SkStream* stream) {
    SkASSERT(stream != NULL);
    size_t bufferSize = 4096;
    size_t streamLen = 0;
    size_t len;
    char* data = (char*)sk_malloc_throw(bufferSize);

    while ((len = stream->read(data + streamLen,
                               bufferSize - streamLen)) != 0) {
        streamLen += len;
        if (streamLen == bufferSize) {
            bufferSize *= 2;
            data = (char*)sk_realloc_throw(data, bufferSize);
        }
    }
    data = (char*)sk_realloc_throw(data, streamLen);

    SkMemoryStream* streamMem = new SkMemoryStream();
    streamMem->setMemoryOwned(data, streamLen);
    return streamMem;
}

SkStreamRewindable* CopyJavaInputStream(JNIEnv* env, jobject stream,
                                        jbyteArray storage) {
    SkAutoTUnref<SkStream> adaptor(CreateJavaInputStreamAdaptor(env, stream, storage));
    if (NULL == adaptor.get()) {
        return NULL;
    }
    return adaptor_to_mem_stream(adaptor.get());
}

/**
 *  Wrapper for a Java InputStream which is rewindable and
 *  has a length.
 */
class RewindableJavaStream : public SkStreamRewindable {
public:
    // RewindableJavaStream takes ownership of adaptor.
    RewindableJavaStream(JavaInputStreamAdaptor* adaptor, size_t length)
        : fAdaptor(adaptor)
        , fLength(length) {
        SkASSERT(fAdaptor != NULL);
    }

    virtual ~RewindableJavaStream() {
        fAdaptor->unref();
    }

    virtual bool rewind() {
        return fAdaptor->doRewind();
    }

    virtual size_t read(void* buffer, size_t size) {
        return fAdaptor->read(buffer, size);
    }

    virtual bool isAtEnd() const {
        return fAdaptor->isAtEnd();
    }

    virtual size_t getLength() const {
        return fLength;
    }

    virtual bool hasLength() const {
        return true;
    }

    virtual SkStreamRewindable* duplicate() const {
        // Duplicating this stream requires rewinding and
        // reading, which modify this Stream (and could
        // fail, leaving this one invalid).
        SkASSERT(false);
        return NULL;
    }

private:
    JavaInputStreamAdaptor* fAdaptor;
    const size_t            fLength;
};

static jclass   gByteArrayInputStream_Clazz;
static jfieldID gCountField;
static jfieldID gPosField;

/**
 *  If jstream is a ByteArrayInputStream, return its remaining length. Otherwise
 *  return 0.
 */
static size_t get_length_from_byte_array_stream(JNIEnv* env, jobject jstream) {
    if (env->IsInstanceOf(jstream, gByteArrayInputStream_Clazz)) {
        // Return the remaining length, to keep the same behavior of using the rest of the
        // stream.
        return env->GetIntField(jstream, gCountField) - env->GetIntField(jstream, gPosField);
    }
    return 0;
}

/**
 *  If jstream is a class that has a length, return it. Otherwise
 *  return 0.
 *  Only checks for a set of subclasses.
 */
static size_t get_length_if_supported(JNIEnv* env, jobject jstream) {
    size_t len = get_length_from_byte_array_stream(env, jstream);
    if (len > 0) {
        return len;
    }
    return 0;
}

SkStreamRewindable* GetRewindableStream(JNIEnv* env, jobject stream,
                                        jbyteArray storage) {
    SkAutoTUnref<SkStream> adaptor(CreateJavaInputStreamAdaptor(env, stream, storage));
    if (NULL == adaptor.get()) {
        return NULL;
    }

    const size_t length = get_length_if_supported(env, stream);
    if (length > 0 && env->CallBooleanMethod(stream, gInputStream_markSupportedMethodID)) {
        // Set the readLimit for mark to the end of the stream, so it can
        // be rewound regardless of how much has been read.
        env->CallVoidMethod(stream, gInputStream_markMethodID, length);
        // RewindableJavaStream will unref adaptor when it is destroyed.
        return new RewindableJavaStream(static_cast<JavaInputStreamAdaptor*>(adaptor.detach()),
                                        length);
    }

    return adaptor_to_mem_stream(adaptor.get());
}

static jclass       gAssetInputStream_Clazz;
static jmethodID    gGetAssetIntMethodID;

android::AssetStreamAdaptor* CheckForAssetStream(JNIEnv* env, jobject jstream) {
    if (!env->IsInstanceOf(jstream, gAssetInputStream_Clazz)) {
        return NULL;
    }

    jint jasset = env->CallIntMethod(jstream, gGetAssetIntMethodID);
    android::Asset* a = reinterpret_cast<android::Asset*>(jasset);
    if (NULL == a) {
        jniThrowNullPointerException(env, "NULL native asset");
        return NULL;
    }
    return new android::AssetStreamAdaptor(a);
}

///////////////////////////////////////////////////////////////////////////////

static jmethodID    gOutputStream_writeMethodID;
static jmethodID    gOutputStream_flushMethodID;

class SkJavaOutputStream : public SkWStream {
public:
    SkJavaOutputStream(JNIEnv* env, jobject stream, jbyteArray storage)
        : fEnv(env), fJavaOutputStream(stream), fJavaByteArray(storage) {
        fCapacity = env->GetArrayLength(storage);
    }

	virtual bool write(const void* buffer, size_t size) {
        JNIEnv* env = fEnv;
        jbyteArray storage = fJavaByteArray;

        while (size > 0) {
            size_t requested = size;
            if (requested > fCapacity) {
                requested = fCapacity;
            }

            env->SetByteArrayRegion(storage, 0, requested,
                                    reinterpret_cast<const jbyte*>(buffer));
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                SkDebugf("--- write:SetByteArrayElements threw an exception\n");
                return false;
            }

            fEnv->CallVoidMethod(fJavaOutputStream, gOutputStream_writeMethodID,
                                 storage, 0, requested);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                SkDebugf("------- write threw an exception\n");
                return false;
            }

            buffer = (void*)((char*)buffer + requested);
            size -= requested;
        }
        return true;
    }

    virtual void flush() {
        fEnv->CallVoidMethod(fJavaOutputStream, gOutputStream_flushMethodID);
    }

private:
    JNIEnv*     fEnv;
    jobject     fJavaOutputStream;  // the caller owns this object
    jbyteArray  fJavaByteArray;     // the caller owns this object
    size_t      fCapacity;
};

SkWStream* CreateJavaOutputStreamAdaptor(JNIEnv* env, jobject stream,
                                         jbyteArray storage) {
    static bool gInited;

    if (!gInited) {

        gInited = true;
    }

    return new SkJavaOutputStream(env, stream, storage);
}

static jclass findClassCheck(JNIEnv* env, const char classname[]) {
    jclass clazz = env->FindClass(classname);
    SkASSERT(!env->ExceptionCheck());
    return clazz;
}

static jfieldID getFieldIDCheck(JNIEnv* env, jclass clazz,
                                const char fieldname[], const char type[]) {
    jfieldID id = env->GetFieldID(clazz, fieldname, type);
    SkASSERT(!env->ExceptionCheck());
    return id;
}

static jmethodID getMethodIDCheck(JNIEnv* env, jclass clazz,
                                  const char methodname[], const char type[]) {
    jmethodID id = env->GetMethodID(clazz, methodname, type);
    SkASSERT(!env->ExceptionCheck());
    return id;
}

int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv* env) {
    jclass inputStream_Clazz = findClassCheck(env, "java/io/InputStream");
    gInputStream_resetMethodID = getMethodIDCheck(env, inputStream_Clazz, "reset", "()V");
    gInputStream_markMethodID = getMethodIDCheck(env, inputStream_Clazz, "mark", "(I)V");
    gInputStream_markSupportedMethodID = getMethodIDCheck(env, inputStream_Clazz, "markSupported", "()Z");
    gInputStream_readMethodID = getMethodIDCheck(env, inputStream_Clazz, "read", "([BII)I");
    gInputStream_skipMethodID = getMethodIDCheck(env, inputStream_Clazz, "skip", "(J)J");

    gByteArrayInputStream_Clazz = findClassCheck(env, "java/io/ByteArrayInputStream");
    // Ref gByteArrayInputStream_Clazz so we can continue to refer to it when
    // calling IsInstance.
    gByteArrayInputStream_Clazz = (jclass) env->NewGlobalRef(gByteArrayInputStream_Clazz);
    gCountField = getFieldIDCheck(env, gByteArrayInputStream_Clazz, "count", "I");
    gPosField = getFieldIDCheck(env, gByteArrayInputStream_Clazz, "pos", "I");

    gAssetInputStream_Clazz = findClassCheck(env, "android/content/res/AssetManager$AssetInputStream");
    // Ref gAssetInputStream_Clazz so we can continue to refer to it when
    // calling IsInstance.
    gAssetInputStream_Clazz = (jclass) env->NewGlobalRef(gAssetInputStream_Clazz);
    gGetAssetIntMethodID = getMethodIDCheck(env, gAssetInputStream_Clazz, "getAssetInt", "()I");

    jclass outputStream_Clazz = findClassCheck(env, "java/io/OutputStream");
    gOutputStream_writeMethodID = getMethodIDCheck(env, outputStream_Clazz, "write", "([BII)V");
    gOutputStream_flushMethodID = getMethodIDCheck(env, outputStream_Clazz, "flush", "()V");

    return 0;
}
