#include "CreateJavaOutputStreamAdaptor.h"
#include "SkData.h"
#include "SkMalloc.h"
#include "SkRefCnt.h"
#include "SkStream.h"
#include "SkTypes.h"
#include "Utils.h"

#include <nativehelper/JNIHelp.h>
#include <log/log.h>
#include <memory>

static jmethodID    gInputStream_readMethodID;
static jmethodID    gInputStream_skipMethodID;

/**
 *  Wrapper for a Java InputStream.
 */
class JavaInputStreamAdaptor : public SkStream {
    JavaInputStreamAdaptor(JavaVM* jvm, jobject js, jbyteArray ar, jint capacity,
                           bool swallowExceptions)
            : fJvm(jvm)
            , fJavaInputStream(js)
            , fJavaByteArray(ar)
            , fCapacity(capacity)
            , fBytesRead(0)
            , fIsAtEnd(false)
            , fSwallowExceptions(swallowExceptions) {}

public:
    static JavaInputStreamAdaptor* Create(JNIEnv* env, jobject js, jbyteArray ar,
                                          bool swallowExceptions) {
        JavaVM* jvm;
        LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&jvm) != JNI_OK);

        js = env->NewGlobalRef(js);
        if (!js) {
            return nullptr;
        }

        ar = (jbyteArray) env->NewGlobalRef(ar);
        if (!ar) {
            env->DeleteGlobalRef(js);
            return nullptr;
        }

        jint capacity = env->GetArrayLength(ar);
        return new JavaInputStreamAdaptor(jvm, js, ar, capacity, swallowExceptions);
    }

    ~JavaInputStreamAdaptor() override {
        auto* env = android::requireEnv(fJvm);
        env->DeleteGlobalRef(fJavaInputStream);
        env->DeleteGlobalRef(fJavaByteArray);
    }

    size_t read(void* buffer, size_t size) override {
        auto* env = android::requireEnv(fJvm);
        if (!fSwallowExceptions && checkException(env)) {
            // Just in case the caller did not clear from a previous exception.
            return 0;
        }
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
                    size_t amount = this->doSkip(size - amountSkipped, env);
                    if (0 == amount) {
                        char tmp;
                        amount = this->doRead(&tmp, 1, env);
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
        return this->doRead(buffer, size, env);
    }

    bool isAtEnd() const override { return fIsAtEnd; }

private:
    size_t doRead(void* buffer, size_t size, JNIEnv* env) {
        size_t bytesRead = 0;
        // read the bytes
        do {
            jint requested = 0;
            if (size > static_cast<size_t>(fCapacity)) {
                requested = fCapacity;
            } else {
                // This is safe because requested is clamped to (jint)
                // fCapacity.
                requested = static_cast<jint>(size);
            }

            jint n = env->CallIntMethod(fJavaInputStream,
                                        gInputStream_readMethodID, fJavaByteArray, 0, requested);
            if (checkException(env)) {
                SkDebugf("---- read threw an exception\n");
                return bytesRead;
            }

            if (n < 0) { // n == 0 should not be possible, see InputStream read() specifications.
                fIsAtEnd = true;
                break;  // eof
            }

            env->GetByteArrayRegion(fJavaByteArray, 0, n,
                                    reinterpret_cast<jbyte*>(buffer));
            if (checkException(env)) {
                SkDebugf("---- read:GetByteArrayRegion threw an exception\n");
                return bytesRead;
            }

            buffer = (void*)((char*)buffer + n);
            bytesRead += n;
            size -= n;
            fBytesRead += n;
        } while (size != 0);

        return bytesRead;
    }

    size_t doSkip(size_t size, JNIEnv* env) {
        jlong skipped = env->CallLongMethod(fJavaInputStream,
                                            gInputStream_skipMethodID, (jlong)size);
        if (checkException(env)) {
            SkDebugf("------- skip threw an exception\n");
            return 0;
        }
        if (skipped < 0) {
            skipped = 0;
        }

        return (size_t)skipped;
    }

    bool checkException(JNIEnv* env) {
        if (!env->ExceptionCheck()) {
            return false;
        }

        env->ExceptionDescribe();
        if (fSwallowExceptions) {
            env->ExceptionClear();
        }

        // There is no way to recover from the error, so consider the stream
        // to be at the end.
        fIsAtEnd = true;

        return true;
    }

    JavaVM*     fJvm;
    jobject     fJavaInputStream;
    jbyteArray  fJavaByteArray;
    const jint  fCapacity;
    size_t      fBytesRead;
    bool        fIsAtEnd;
    const bool  fSwallowExceptions;
};

SkStream* CreateJavaInputStreamAdaptor(JNIEnv* env, jobject stream, jbyteArray storage,
                                       bool swallowExceptions) {
    return JavaInputStreamAdaptor::Create(env, stream, storage, swallowExceptions);
}

sk_sp<SkData> CopyJavaInputStream(JNIEnv* env, jobject inputStream, jbyteArray storage) {
    std::unique_ptr<SkStream> stream(CreateJavaInputStreamAdaptor(env, inputStream, storage));
    if (!stream) {
        return nullptr;
    }

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

    return SkData::MakeFromMalloc(data, streamLen);
}

///////////////////////////////////////////////////////////////////////////////

static jmethodID    gOutputStream_writeMethodID;
static jmethodID    gOutputStream_flushMethodID;

class SkJavaOutputStream : public SkWStream {
public:
    SkJavaOutputStream(JNIEnv* env, jobject stream, jbyteArray storage)
        : fEnv(env), fJavaOutputStream(stream), fJavaByteArray(storage), fBytesWritten(0) {
        fCapacity = env->GetArrayLength(storage);
    }

    virtual size_t bytesWritten() const {
        return fBytesWritten;
    }

    virtual bool write(const void* buffer, size_t size) {
        JNIEnv* env = fEnv;
        jbyteArray storage = fJavaByteArray;

        while (size > 0) {
            jint requested = 0;
            if (size > static_cast<size_t>(fCapacity)) {
                requested = fCapacity;
            } else {
                // This is safe because requested is clamped to (jint)
                // fCapacity.
                requested = static_cast<jint>(size);
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
            fBytesWritten += requested;
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
    jint        fCapacity;
    size_t      fBytesWritten;
};

SkWStream* CreateJavaOutputStreamAdaptor(JNIEnv* env, jobject stream,
                                         jbyteArray storage) {
    return new SkJavaOutputStream(env, stream, storage);
}

static jclass findClassCheck(JNIEnv* env, const char classname[]) {
    jclass clazz = env->FindClass(classname);
    SkASSERT(!env->ExceptionCheck());
    return clazz;
}

static jmethodID getMethodIDCheck(JNIEnv* env, jclass clazz,
                                  const char methodname[], const char type[]) {
    jmethodID id = env->GetMethodID(clazz, methodname, type);
    SkASSERT(!env->ExceptionCheck());
    return id;
}

int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv* env) {
    jclass inputStream_Clazz = findClassCheck(env, "java/io/InputStream");
    gInputStream_readMethodID = getMethodIDCheck(env, inputStream_Clazz, "read", "([BII)I");
    gInputStream_skipMethodID = getMethodIDCheck(env, inputStream_Clazz, "skip", "(J)J");

    jclass outputStream_Clazz = findClassCheck(env, "java/io/OutputStream");
    gOutputStream_writeMethodID = getMethodIDCheck(env, outputStream_Clazz, "write", "([BII)V");
    gOutputStream_flushMethodID = getMethodIDCheck(env, outputStream_Clazz, "flush", "()V");

    return 0;
}
