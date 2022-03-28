/*
 * Copyright (C) 2008 The Android Open Source Project
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

#include <stdio.h>

//#define LOG_NDEBUG 0
#define LOG_TAG "SoundPool-JNI"

#include <utils/Log.h>
#include <jni.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <android_runtime/AndroidRuntime.h>
#include "SoundPool.h"

using namespace android;

static struct fields_t {
    jfieldID    mNativeContext;
    jmethodID   mPostEvent;
    jclass      mSoundPoolClass;
} fields;

namespace {

/**
 * ObjectManager creates a native "object" on the heap and stores
 * its pointer in a long field in a Java object.
 *
 * The type T must have 3 properties in the current implementation.
 *    1) A T{} default constructor which represents a nullValue.
 *    2) T::operator bool() const efficient detection of such a nullValue.
 *    3) T must be copyable.
 *
 * Some examples of such a type T are std::shared_ptr<>, android::sp<>,
 * std::optional, std::function<>, etc.
 *
 * Using set() with a nullValue T results in destroying the underlying native
 * "object" if it exists.  A nullValue T is returned by get() if there is
 * no underlying native Object.
 *
 * This class is thread safe for multiple access.
 *
 * Design notes:
 * 1) For objects of type T that do not naturally have an "nullValue",
 *    wrapping with
 *           a) TOpt, where TOpt = std::optional<T>
 *           b) TShared, where TShared = std::shared_ptr<T>
 *
 * 2) An overload for an explicit equality comparable nullValue such as
 *    get(..., const T& nullValue) or set(..., const T& nullValue)
 *    is omitted.  An alternative is to pass a fixed nullValue in the constructor.
 */
template <typename T>
class ObjectManager
{
// Can a jlong hold a pointer?
static_assert(sizeof(jlong) >= sizeof(void*));

public:
    // fieldId is associated with a Java long member variable in the object.
    // ObjectManager will store the native pointer in that field.
    //
    // If a native object is set() in that field, it
    explicit ObjectManager(jfieldID fieldId) : mFieldId(fieldId) {}
    ~ObjectManager() {
        ALOGE_IF(mObjectCount != 0, "%s: mObjectCount: %d should be zero on destruction",
                __func__, mObjectCount.load());
        // Design note: it would be possible to keep a map of the outstanding allocated
        // objects and force a delete on them on ObjectManager destruction.
        // The consequences of that is probably worse than keeping them alive.
    }

    // Retrieves the associated object, returns nullValue T if not available.
    T get(JNIEnv *env, jobject thiz) {
        std::lock_guard lg(mLock);
        // NOLINTNEXTLINE(performance-no-int-to-ptr)
        auto ptr = reinterpret_cast<T*>(env->GetLongField(thiz, mFieldId));
        if (ptr != nullptr) {
            return *ptr;
        }
        return {};
    }

    // Sets the object and returns the old one.
    //
    // If the old object doesn't exist, then nullValue T is returned.
    // If the new object is false by operator bool(), the internal object is destroyed.
    // Note: The old object is returned so if T is a smart pointer, it can be held
    // by the caller to be deleted outside of any external lock.
    //
    // Remember to call set(env, thiz, {}) to destroy the object in the Java
    // object finalize to avoid orphaned objects on the heap.
    T set(JNIEnv *env, jobject thiz, const T& newObject) {
        std::lock_guard lg(mLock);
        // NOLINTNEXTLINE(performance-no-int-to-ptr)
        auto ptr = reinterpret_cast<T*>(env->GetLongField(thiz, mFieldId));
        if (ptr != nullptr) {
            T old = std::move(*ptr);  // *ptr will be replaced or deleted.
            if (newObject) {
                env->SetLongField(thiz, mFieldId, (jlong)0);
                delete ptr;
                --mObjectCount;
            } else {
                *ptr = newObject;
            }
            return old;
        } else {
             if (newObject) {
                 env->SetLongField(thiz, mFieldId, (jlong)new T(newObject));
                 ++mObjectCount;
             }
             return {};
        }
    }

    // Returns the number of outstanding objects.
    //
    // This is purely for debugging purposes and tracks the number of active Java
    // objects that have native T objects; hence represents the number of
    // T heap allocations we have made.
    //
    // When all those Java objects have been finalized we expect this to go to 0.
    int32_t getObjectCount() const {
        return mObjectCount;
    }

private:
    // NOLINTNEXTLINE(misc-misplaced-const)
    const jfieldID mFieldId;  // '_jfieldID *const'

    // mObjectCount is the number of outstanding native T heap allocations we have
    // made (and thus the number of active Java objects which are associated with them).
    std::atomic_int32_t mObjectCount{};

    mutable std::mutex mLock;
};

// We use SoundPoolManager to associate a native std::shared_ptr<SoundPool>
// object with a field in the Java object.
//
// We can then retrieve the std::shared_ptr<SoundPool> from the object.
//
// Design notes:
// 1) This is based on ObjectManager class.
// 2) An alternative that does not require a field in the Java object
//    is to create an associative map using as a key a NewWeakGlobalRef
//    to the Java object.
//    The problem of this method is that lookup is O(N) because comparison
//    between the WeakGlobalRef to a JNI jobject LocalRef must be done
//    through the JNI IsSameObject() call, hence iterative through the map.
//    One advantage of this method is that manual garbage collection
//    is possible by checking if the WeakGlobalRef is null equivalent.

auto& getSoundPoolManager() {
    static ObjectManager<std::shared_ptr<SoundPool>> soundPoolManager(fields.mNativeContext);
    return soundPoolManager;
}

inline auto getSoundPool(JNIEnv *env, jobject thiz) {
    return getSoundPoolManager().get(env, thiz);
}

// Note: one must call setSoundPool(env, thiz, nullptr) to release any native resources
// somewhere in the Java object finalize().
inline auto setSoundPool(
        JNIEnv *env, jobject thiz, const std::shared_ptr<SoundPool>& soundPool) {
    return getSoundPoolManager().set(env, thiz, soundPool);
}

} // namespace

static const char* const kAudioAttributesClassPathName = "android/media/AudioAttributes";
struct audio_attributes_fields_t {
    jfieldID  fieldUsage;        // AudioAttributes.mUsage
    jfieldID  fieldContentType;  // AudioAttributes.mContentType
    jfieldID  fieldFlags;        // AudioAttributes.mFlags
    jfieldID  fieldFormattedTags;// AudioAttributes.mFormattedTags
};
static audio_attributes_fields_t javaAudioAttrFields;

// ----------------------------------------------------------------------------

static jint
android_media_SoundPool_load_FD(JNIEnv *env, jobject thiz, jobject fileDescriptor,
        jlong offset, jlong length, jint priority)
{
    ALOGV("android_media_SoundPool_load_FD");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return 0;
    return (jint) soundPool->load(jniGetFDFromFileDescriptor(env, fileDescriptor),
            int64_t(offset), int64_t(length), int(priority));
}

static jboolean
android_media_SoundPool_unload(JNIEnv *env, jobject thiz, jint sampleID) {
    ALOGV("android_media_SoundPool_unload\n");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return JNI_FALSE;
    return soundPool->unload(sampleID) ? JNI_TRUE : JNI_FALSE;
}

static jint
android_media_SoundPool_play(JNIEnv *env, jobject thiz, jint sampleID,
        jfloat leftVolume, jfloat rightVolume, jint priority, jint loop,
        jfloat rate)
{
    ALOGV("android_media_SoundPool_play\n");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return 0;
    return (jint) soundPool->play(sampleID, leftVolume, rightVolume, priority, loop, rate);
}

static void
android_media_SoundPool_pause(JNIEnv *env, jobject thiz, jint channelID)
{
    ALOGV("android_media_SoundPool_pause");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return;
    soundPool->pause(channelID);
}

static void
android_media_SoundPool_resume(JNIEnv *env, jobject thiz, jint channelID)
{
    ALOGV("android_media_SoundPool_resume");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return;
    soundPool->resume(channelID);
}

static void
android_media_SoundPool_autoPause(JNIEnv *env, jobject thiz)
{
    ALOGV("android_media_SoundPool_autoPause");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return;
    soundPool->autoPause();
}

static void
android_media_SoundPool_autoResume(JNIEnv *env, jobject thiz)
{
    ALOGV("android_media_SoundPool_autoResume");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return;
    soundPool->autoResume();
}

static void
android_media_SoundPool_stop(JNIEnv *env, jobject thiz, jint channelID)
{
    ALOGV("android_media_SoundPool_stop");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return;
    soundPool->stop(channelID);
}

static void
android_media_SoundPool_setVolume(JNIEnv *env, jobject thiz, jint channelID,
        jfloat leftVolume, jfloat rightVolume)
{
    ALOGV("android_media_SoundPool_setVolume");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return;
    soundPool->setVolume(channelID, (float) leftVolume, (float) rightVolume);
}

static void
android_media_SoundPool_mute(JNIEnv *env, jobject thiz, jboolean muting)
{
    ALOGV("android_media_SoundPool_mute(%d)", muting);
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return;
    soundPool->mute(muting == JNI_TRUE);
}

static void
android_media_SoundPool_setPriority(JNIEnv *env, jobject thiz, jint channelID,
        jint priority)
{
    ALOGV("android_media_SoundPool_setPriority");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return;
    soundPool->setPriority(channelID, (int) priority);
}

static void
android_media_SoundPool_setLoop(JNIEnv *env, jobject thiz, jint channelID,
        int loop)
{
    ALOGV("android_media_SoundPool_setLoop");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return;
    soundPool->setLoop(channelID, loop);
}

static void
android_media_SoundPool_setRate(JNIEnv *env, jobject thiz, jint channelID,
       jfloat rate)
{
    ALOGV("android_media_SoundPool_setRate");
    auto soundPool = getSoundPool(env, thiz);
    if (soundPool == nullptr) return;
    soundPool->setRate(channelID, (float) rate);
}

static void android_media_callback(SoundPoolEvent event, SoundPool* soundPool, void* user)
{
    ALOGV("callback: (%d, %d, %d, %p, %p)", event.mMsg, event.mArg1, event.mArg2, soundPool, user);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->CallStaticVoidMethod(
            fields.mSoundPoolClass, fields.mPostEvent, user, event.mMsg, event.mArg1, event.mArg2,
            nullptr /* object */);
}

static jint
android_media_SoundPool_native_setup(JNIEnv *env, jobject thiz, jobject weakRef,
        jint maxChannels, jobject jaa, jstring opPackageName)
{
    if (jaa == nullptr) {
        ALOGE("Error creating SoundPool: invalid audio attributes");
        return -1;
    }

    audio_attributes_t *paa = nullptr;
    // read the AudioAttributes values
    paa = (audio_attributes_t *) calloc(1, sizeof(audio_attributes_t));
    const auto jtags =
            (jstring) env->GetObjectField(jaa, javaAudioAttrFields.fieldFormattedTags);
    const char* tags = env->GetStringUTFChars(jtags, nullptr);
    // copying array size -1, char array for tags was calloc'd, no need to NULL-terminate it
    strncpy(paa->tags, tags, AUDIO_ATTRIBUTES_TAGS_MAX_SIZE - 1);
    env->ReleaseStringUTFChars(jtags, tags);
    paa->usage = (audio_usage_t) env->GetIntField(jaa, javaAudioAttrFields.fieldUsage);
    paa->content_type =
            (audio_content_type_t) env->GetIntField(jaa, javaAudioAttrFields.fieldContentType);
    paa->flags = (audio_flags_mask_t) env->GetIntField(jaa, javaAudioAttrFields.fieldFlags);

    ALOGV("android_media_SoundPool_native_setup");
    ScopedUtfChars opPackageNameStr(env, opPackageName);
    auto soundPool = std::make_shared<SoundPool>(maxChannels, paa, opPackageNameStr.c_str());

    // set callback with weak reference
    jobject globalWeakRef = env->NewGlobalRef(weakRef);
    soundPool->setCallback(android_media_callback, globalWeakRef);

    // register with SoundPoolManager.
    auto oldSoundPool = setSoundPool(env, thiz, soundPool);
    ALOGW_IF(oldSoundPool != nullptr, "%s: Aliased SoundPool object %p",
            __func__, oldSoundPool.get());

    // audio attributes were copied in SoundPool creation
    free(paa);

    return 0;
}

static void
android_media_SoundPool_release(JNIEnv *env, jobject thiz)
{
    ALOGV("android_media_SoundPool_release");

    // Remove us from SoundPoolManager.
    auto oldSoundPool = setSoundPool(env, thiz, nullptr);

    // Caution: Deleting the weakRef is not race free from invoking
    // the Java callback because we may not have the last remaining
    // reference to the SoundPool object - another method could still
    // be in progress.
    if (oldSoundPool != nullptr) {
        // release weak reference and clear callback
        auto weakRef = (jobject) oldSoundPool->getUserData();
        oldSoundPool->setCallback(nullptr /* callback */, nullptr /* user */);
        if (weakRef != nullptr) {
            env->DeleteGlobalRef(weakRef);
        }
    }
    // destructor to oldSoundPool should occur at exit.
}

// ----------------------------------------------------------------------------

// Dalvik VM type signatures
static JNINativeMethod gMethods[] = {
    {   "_load",
        "(Ljava/io/FileDescriptor;JJI)I",
        (void *)android_media_SoundPool_load_FD
    },
    {   "unload",
        "(I)Z",
        (void *)android_media_SoundPool_unload
    },
    {   "_play",
        "(IFFIIF)I",
        (void *)android_media_SoundPool_play
    },
    {   "pause",
        "(I)V",
        (void *)android_media_SoundPool_pause
    },
    {   "resume",
        "(I)V",
        (void *)android_media_SoundPool_resume
    },
    {   "autoPause",
        "()V",
        (void *)android_media_SoundPool_autoPause
    },
    {   "autoResume",
        "()V",
        (void *)android_media_SoundPool_autoResume
    },
    {   "stop",
        "(I)V",
        (void *)android_media_SoundPool_stop
    },
    {   "_setVolume",
        "(IFF)V",
        (void *)android_media_SoundPool_setVolume
    },
    {   "_mute",
        "(Z)V",
        (void *)android_media_SoundPool_mute
    },
    {   "setPriority",
        "(II)V",
        (void *)android_media_SoundPool_setPriority
    },
    {   "setLoop",
        "(II)V",
        (void *)android_media_SoundPool_setLoop
    },
    {   "setRate",
        "(IF)V",
        (void *)android_media_SoundPool_setRate
    },
    {   "native_setup",
        "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/String;)I",
        (void*)android_media_SoundPool_native_setup
    },
    {   "native_release",
        "()V",
        (void*)android_media_SoundPool_release
    }
};

static const char* const kClassPathName = "android/media/SoundPool";

jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)
{
    JNIEnv* env = nullptr;
    jint result = -1;
    jclass clazz;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed\n");
        return result;
    }
    assert(env != nullptr);

    clazz = env->FindClass(kClassPathName);
    if (clazz == nullptr) {
        ALOGE("Can't find %s", kClassPathName);
        return result;
    }

    fields.mNativeContext = env->GetFieldID(clazz, "mNativeContext", "J");
    if (fields.mNativeContext == nullptr) {
        ALOGE("Can't find SoundPool.mNativeContext");
        return result;
    }

    fields.mPostEvent = env->GetStaticMethodID(clazz, "postEventFromNative",
                                               "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    if (fields.mPostEvent == nullptr) {
        ALOGE("Can't find android/media/SoundPool.postEventFromNative");
        return result;
    }

    // create a reference to class. Technically, we're leaking this reference
    // since it's a static object.
    fields.mSoundPoolClass = (jclass) env->NewGlobalRef(clazz);

    if (AndroidRuntime::registerNativeMethods(
                env, kClassPathName, gMethods, NELEM(gMethods)) < 0) {
        return result;
    }

    // Get the AudioAttributes class and fields
    jclass audioAttrClass = env->FindClass(kAudioAttributesClassPathName);
    if (audioAttrClass == nullptr) {
        ALOGE("Can't find %s", kAudioAttributesClassPathName);
        return result;
    }
    auto audioAttributesClassRef = (jclass)env->NewGlobalRef(audioAttrClass);
    javaAudioAttrFields.fieldUsage = env->GetFieldID(audioAttributesClassRef, "mUsage", "I");
    javaAudioAttrFields.fieldContentType
                                   = env->GetFieldID(audioAttributesClassRef, "mContentType", "I");
    javaAudioAttrFields.fieldFlags = env->GetFieldID(audioAttributesClassRef, "mFlags", "I");
    javaAudioAttrFields.fieldFormattedTags =
            env->GetFieldID(audioAttributesClassRef, "mFormattedTags", "Ljava/lang/String;");
    env->DeleteGlobalRef(audioAttributesClassRef);
    if (javaAudioAttrFields.fieldUsage == nullptr
            || javaAudioAttrFields.fieldContentType == nullptr
            || javaAudioAttrFields.fieldFlags == nullptr
            || javaAudioAttrFields.fieldFormattedTags == nullptr) {
        ALOGE("Can't initialize AudioAttributes fields");
        return result;
    }

    /* success -- return valid version number */
    return JNI_VERSION_1_4;
}
