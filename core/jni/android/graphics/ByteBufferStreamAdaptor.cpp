#include "ByteBufferStreamAdaptor.h"
#include "core_jni_helpers.h"
#include "Utils.h"

#include <SkStream.h>

using namespace android;

static jmethodID gByteBuffer_getMethodID;
static jmethodID gByteBuffer_setPositionMethodID;

class ByteBufferStream : public SkStreamAsset {
private:
    ByteBufferStream(JavaVM* jvm, jobject jbyteBuffer, size_t initialPosition, size_t length,
                     jbyteArray storage)
            : mJvm(jvm)
            , mByteBuffer(jbyteBuffer)
            , mPosition(0)
            , mInitialPosition(initialPosition)
            , mLength(length)
            , mStorage(storage) {}

public:
    static ByteBufferStream* Create(JavaVM* jvm, JNIEnv* env, jobject jbyteBuffer,
                                    size_t position, size_t length) {
        // This object outlives its native method call.
        jbyteBuffer = env->NewGlobalRef(jbyteBuffer);
        if (!jbyteBuffer) {
            return nullptr;
        }

        jbyteArray storage = env->NewByteArray(kStorageSize);
        if (!storage) {
            env->DeleteGlobalRef(jbyteBuffer);
            return nullptr;
        }

        // This object outlives its native method call.
        storage = static_cast<jbyteArray>(env->NewGlobalRef(storage));
        if (!storage) {
            env->DeleteGlobalRef(jbyteBuffer);
            return nullptr;
        }

        return new ByteBufferStream(jvm, jbyteBuffer, position, length, storage);
    }

    ~ByteBufferStream() override {
        auto* env = get_env_or_die(mJvm);
        env->DeleteGlobalRef(mByteBuffer);
        env->DeleteGlobalRef(mStorage);
    }

    size_t read(void* buffer, size_t size) override {
        if (size > mLength - mPosition) {
            size = mLength - mPosition;
        }
        if (!size) {
            return 0;
        }

        if (!buffer) {
            return this->setPosition(mPosition + size) ? size : 0;
        }

        auto* env = get_env_or_die(mJvm);
        size_t bytesRead = 0;
        do {
            const size_t requested = (size > kStorageSize) ? kStorageSize : size;
            const jint jrequested = static_cast<jint>(requested);
            env->CallObjectMethod(mByteBuffer, gByteBuffer_getMethodID, mStorage, 0, jrequested);
            if (env->ExceptionCheck()) {
                ALOGE("Error in ByteBufferStream::read - was the ByteBuffer modified externally?");
                env->ExceptionDescribe();
                env->ExceptionClear();
                mPosition = mLength;
                return bytesRead;
            }

            env->GetByteArrayRegion(mStorage, 0, requested, reinterpret_cast<jbyte*>(buffer));
            if (env->ExceptionCheck()) {
                ALOGE("Internal error in ByteBufferStream::read");
                env->ExceptionDescribe();
                env->ExceptionClear();
                mPosition = mLength;
                return bytesRead;
            }

            mPosition += requested;
            buffer = reinterpret_cast<void*>(reinterpret_cast<char*>(buffer) + requested);
            bytesRead += requested;
            size -= requested;
        } while (size);
        return bytesRead;
    }

    bool isAtEnd() const override { return mLength == mPosition; }

    // SkStreamRewindable overrides
    bool rewind() override { return this->setPosition(0); }

    SkStreamAsset* onDuplicate() const override {
        // SkStreamRewindable requires overriding this, but it is not called by
        // decoders, so does not need a true implementation. A proper
        // implementation would require duplicating the ByteBuffer, which has
        // its own internal position state.
        return nullptr;
    }

    // SkStreamSeekable overrides
    size_t getPosition() const override { return mPosition; }

    bool seek(size_t position) override {
        return this->setPosition(position > mLength ? mLength : position);
    }

    bool move(long offset) override {
        long newPosition = mPosition + offset;
        if (newPosition < 0) {
            return this->setPosition(0);
        }
        return this->seek(static_cast<size_t>(newPosition));
    }

    SkStreamAsset* onFork() const override {
        // SkStreamSeekable requires overriding this, but it is not called by
        // decoders, so does not need a true implementation. A proper
        // implementation would require duplicating the ByteBuffer, which has
        // its own internal position state.
        return nullptr;
    }

    // SkStreamAsset overrides
    size_t getLength() const override { return mLength; }

private:
    JavaVM*          mJvm;
    jobject          mByteBuffer;
    // Logical position of the SkStream, between 0 and mLength.
    size_t           mPosition;
    // Initial position of mByteBuffer, treated as mPosition 0.
    const size_t     mInitialPosition;
    // Logical length of the SkStream, from mInitialPosition to
    // mByteBuffer.limit().
    const size_t     mLength;

    // Range has already been checked by the caller.
    bool setPosition(size_t newPosition) {
        auto* env = get_env_or_die(mJvm);
        env->CallObjectMethod(mByteBuffer, gByteBuffer_setPositionMethodID,
                              newPosition + mInitialPosition);
        if (env->ExceptionCheck()) {
            ALOGE("Internal error in ByteBufferStream::setPosition");
            env->ExceptionDescribe();
            env->ExceptionClear();
            mPosition = mLength;
            return false;
        }
        mPosition = newPosition;
        return true;
    }

    // FIXME: This is an arbitrary storage size, which should be plenty for
    // some formats (png, gif, many bmps). But for jpeg, the more we can supply
    // in one call the better, and webp really wants all of the data. How to
    // best choose the amount of storage used?
    static constexpr size_t kStorageSize = 4096;
    jbyteArray mStorage;
};

class ByteArrayStream : public SkStreamAsset {
private:
    ByteArrayStream(JavaVM* jvm, jbyteArray jarray, size_t offset, size_t length)
            : mJvm(jvm), mByteArray(jarray), mOffset(offset), mPosition(0), mLength(length) {}

public:
    static ByteArrayStream* Create(JavaVM* jvm, JNIEnv* env, jbyteArray jarray, size_t offset,
                                   size_t length) {
        // This object outlives its native method call.
        jarray = static_cast<jbyteArray>(env->NewGlobalRef(jarray));
        if (!jarray) {
            return nullptr;
        }
        return new ByteArrayStream(jvm, jarray, offset, length);
    }

    ~ByteArrayStream() override {
        auto* env = get_env_or_die(mJvm);
        env->DeleteGlobalRef(mByteArray);
    }

    size_t read(void* buffer, size_t size) override {
        if (size > mLength - mPosition) {
            size = mLength - mPosition;
        }
        if (!size) {
            return 0;
        }

        auto* env = get_env_or_die(mJvm);
        if (buffer) {
            env->GetByteArrayRegion(mByteArray, mPosition + mOffset, size,
                                    reinterpret_cast<jbyte*>(buffer));
            if (env->ExceptionCheck()) {
                ALOGE("Internal error in ByteArrayStream::read");
                env->ExceptionDescribe();
                env->ExceptionClear();
                mPosition = mLength;
                return 0;
            }
        }

        mPosition += size;
        return size;
    }

    bool isAtEnd() const override { return mLength == mPosition; }

    // SkStreamRewindable overrides
    bool rewind() override {
        mPosition = 0;
        return true;
    }
    SkStreamAsset* onDuplicate() const override {
        // SkStreamRewindable requires overriding this, but it is not called by
        // decoders, so does not need a true implementation. Note that a proper
        // implementation is fairly straightforward
        return nullptr;
    }

    // SkStreamSeekable overrides
    size_t getPosition() const override { return mPosition; }

    bool seek(size_t position) override {
        mPosition = (position > mLength) ? mLength : position;
        return true;
    }

    bool move(long offset) override {
        long newPosition = mPosition + offset;
        if (newPosition < 0) {
            return this->seek(0);
        }
        return this->seek(static_cast<size_t>(newPosition));
    }

    SkStreamAsset* onFork() const override {
        // SkStreamSeekable requires overriding this, but it is not called by
        // decoders, so does not need a true implementation. Note that a proper
        // implementation is fairly straightforward
        return nullptr;
    }

    // SkStreamAsset overrides
    size_t getLength() const override { return mLength; }

private:
    JavaVM*      mJvm;
    jbyteArray   mByteArray;
    // Offset in mByteArray. Only used when communicating with Java.
    const size_t mOffset;
    // Logical position of the SkStream, between 0 and mLength.
    size_t       mPosition;
    const size_t mLength;
};

struct release_proc_context {
    JavaVM* jvm;
    jobject jbyteBuffer;
};

std::unique_ptr<SkStream> CreateByteBufferStreamAdaptor(JNIEnv* env, jobject jbyteBuffer,
                                                        size_t position, size_t limit) {
    JavaVM* jvm;
    LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&jvm) != JNI_OK);

    const size_t length = limit - position;
    void* addr = env->GetDirectBufferAddress(jbyteBuffer);
    if (addr) {
        addr = reinterpret_cast<void*>(reinterpret_cast<char*>(addr) + position);
        jbyteBuffer = env->NewGlobalRef(jbyteBuffer);
        if (!jbyteBuffer) {
            return nullptr;
        }

        auto* context = new release_proc_context{jvm, jbyteBuffer};
        auto releaseProc = [](const void*, void* context) {
            auto* c = reinterpret_cast<release_proc_context*>(context);
            JNIEnv* env = get_env_or_die(c->jvm);
            env->DeleteGlobalRef(c->jbyteBuffer);
            delete c;
        };
        auto data = SkData::MakeWithProc(addr, length, releaseProc, context);
        // The new SkMemoryStream will read directly from addr.
        return std::unique_ptr<SkStream>(new SkMemoryStream(std::move(data)));
    }

    // Non-direct, or direct access is not supported.
    return std::unique_ptr<SkStream>(ByteBufferStream::Create(jvm, env, jbyteBuffer, position,
                                                              length));
}

std::unique_ptr<SkStream> CreateByteArrayStreamAdaptor(JNIEnv* env, jbyteArray array, size_t offset,
                                                       size_t length) {
    JavaVM* jvm;
    LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&jvm) != JNI_OK);

    return std::unique_ptr<SkStream>(ByteArrayStream::Create(jvm, env, array, offset, length));
}

int register_android_graphics_ByteBufferStreamAdaptor(JNIEnv* env) {
    jclass byteBuffer_class = FindClassOrDie(env, "java/nio/ByteBuffer");
    gByteBuffer_getMethodID         = GetMethodIDOrDie(env, byteBuffer_class, "get", "([BII)Ljava/nio/ByteBuffer;");
    gByteBuffer_setPositionMethodID = GetMethodIDOrDie(env, byteBuffer_class, "position", "(I)Ljava/nio/Buffer;");
    return true;
}
