#include "CreateJavaOutputStreamAdaptor.h"
#include "JNIHelp.h"
#include "SkData.h"
#include "SkRefCnt.h"
#include "SkStream.h"
#include "SkTypes.h"
#include "Utils.h"
#include <androidfw/Asset.h>

#define RETURN_NULL_IF_NULL(value) \
    do { if (!(value)) { SkASSERT(0); return NULL; } } while (false)

#define RETURN_ZERO_IF_NULL(value) \
    do { if (!(value)) { SkASSERT(0); return 0; } } while (false)

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
    static bool gInited;

    if (!gInited) {
        jclass inputStream_Clazz = env->FindClass("java/io/InputStream");
        RETURN_NULL_IF_NULL(inputStream_Clazz);

        gInputStream_resetMethodID      = env->GetMethodID(inputStream_Clazz,
                                                           "reset", "()V");
        gInputStream_markMethodID       = env->GetMethodID(inputStream_Clazz,
                                                           "mark", "(I)V");
        gInputStream_markSupportedMethodID = env->GetMethodID(inputStream_Clazz,
                                                              "markSupported", "()Z");
        gInputStream_readMethodID       = env->GetMethodID(inputStream_Clazz,
                                                           "read", "([BII)I");
        gInputStream_skipMethodID       = env->GetMethodID(inputStream_Clazz,
                                                           "skip", "(J)J");

        RETURN_NULL_IF_NULL(gInputStream_resetMethodID);
        RETURN_NULL_IF_NULL(gInputStream_markMethodID);
        RETURN_NULL_IF_NULL(gInputStream_markSupportedMethodID);
        RETURN_NULL_IF_NULL(gInputStream_readMethodID);
        RETURN_NULL_IF_NULL(gInputStream_skipMethodID);

        gInited = true;
    }

    return new JavaInputStreamAdaptor(env, stream, storage);
}


static SkMemoryStream* adaptor_to_mem_stream(SkStream* adaptor) {
    SkASSERT(adaptor != NULL);
    SkDynamicMemoryWStream wStream;
    const int bufferSize = 256 * 1024; // 256 KB, same as ViewStateSerializer.
    uint8_t buffer[bufferSize];
    do {
        size_t bytesRead = adaptor->read(buffer, bufferSize);
        wStream.write(buffer, bytesRead);
    } while (!adaptor->isAtEnd());
    SkAutoTUnref<SkData> data(wStream.copyToData());
    return new SkMemoryStream(data.get());
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

/**
 *  If jstream is a ByteArrayInputStream, return its remaining length. Otherwise
 *  return 0.
 */
static size_t get_length_from_byte_array_stream(JNIEnv* env, jobject jstream) {
    static jclass byteArrayInputStream_Clazz;
    static jfieldID countField;
    static jfieldID posField;

    byteArrayInputStream_Clazz = env->FindClass("java/io/ByteArrayInputStream");
    RETURN_ZERO_IF_NULL(byteArrayInputStream_Clazz);

    countField = env->GetFieldID(byteArrayInputStream_Clazz, "count", "I");
    RETURN_ZERO_IF_NULL(byteArrayInputStream_Clazz);
    posField = env->GetFieldID(byteArrayInputStream_Clazz, "pos", "I");
    RETURN_ZERO_IF_NULL(byteArrayInputStream_Clazz);

    if (env->IsInstanceOf(jstream, byteArrayInputStream_Clazz)) {
        // Return the remaining length, to keep the same behavior of using the rest of the
        // stream.
        return env->GetIntField(jstream, countField) - env->GetIntField(jstream, posField);
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

android::AssetStreamAdaptor* CheckForAssetStream(JNIEnv* env, jobject jstream) {
    static jclass assetInputStream_Clazz;
    static jmethodID getAssetIntMethodID;

    assetInputStream_Clazz = env->FindClass("android/content/res/AssetManager$AssetInputStream");
    RETURN_NULL_IF_NULL(assetInputStream_Clazz);

    getAssetIntMethodID = env->GetMethodID(assetInputStream_Clazz, "getAssetInt", "()I");
    RETURN_NULL_IF_NULL(getAssetIntMethodID);

    if (!env->IsInstanceOf(jstream, assetInputStream_Clazz)) {
        return NULL;
    }

    jint jasset = env->CallIntMethod(jstream, getAssetIntMethodID);
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
        jclass outputStream_Clazz = env->FindClass("java/io/OutputStream");
        RETURN_NULL_IF_NULL(outputStream_Clazz);

        gOutputStream_writeMethodID = env->GetMethodID(outputStream_Clazz,
                                                       "write", "([BII)V");
        RETURN_NULL_IF_NULL(gOutputStream_writeMethodID);
        gOutputStream_flushMethodID = env->GetMethodID(outputStream_Clazz,
                                                       "flush", "()V");
        RETURN_NULL_IF_NULL(gOutputStream_flushMethodID);

        gInited = true;
    }

    return new SkJavaOutputStream(env, stream, storage);
}
