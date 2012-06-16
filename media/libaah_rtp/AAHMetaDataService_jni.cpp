/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "AAHMetaDataServiceJNI"

#include <android_runtime/AndroidRuntime.h>
#include <binder/IServiceManager.h>
#include <utils/Log.h>
#include <utils/misc.h>

#include "jni.h"
#include "JNIHelp.h"

#include "IAAHMetaData.h"

// error code, synced with MetaDataServiceRtp.java
enum {
    SUCCESS = 0,
    ERROR = -1,
    ALREADY_EXISTS = -2,
};

namespace android {

static const char* kAAHMetaDataServiceBinderName =
        "android.media.IAAHMetaDataService";

static const char* kAAHMetaDataServiceClassName =
        "android/media/libaah/MetaDataServiceRtp";

static struct {
    jmethodID postEventFromNativeId;
    jmethodID flushFromNativeId;
    jfieldID mCookieId;
    jclass clazz;
} jnireflect;

static void ensureArraySize(JNIEnv *env, jbyteArray *array, uint32_t size) {
    if (NULL != *array) {
        uint32_t len = env->GetArrayLength(*array);
        if (len >= size)
            return;

        env->DeleteGlobalRef(*array);
        *array = NULL;
    }

    jbyteArray localRef = env->NewByteArray(size);
    if (NULL != localRef) {
        // Promote to global ref.
        *array = (jbyteArray) env->NewGlobalRef(localRef);

        // Release our (now pointless) local ref.
        env->DeleteLocalRef(localRef);
    }
}

// JNIMetaDataService acts as IAAHMetaDataClient, propagates message to java.
// It also starts a background thread that querying and monitoring life cycle
// of IAAHMetaDataService
// JNIMetaDataService will shoot itself when the related java object is garbage
// collected. This might not be important if java program is using singleton
// pattern;  but it's also safe if java program create and destroy the object
// repeatedly.
class JNIMetaDataService : virtual public BnAAHMetaDataClient,
        virtual public android::IBinder::DeathRecipient,
        virtual public Thread {
 public:
    JNIMetaDataService();

    // start working, must be called only once during initialize
    bool start(jobject ref);
    // stop thread and unref this object, you should never access the object
    // after calling destroy()
    void destroy();

    // override BnAAHMetaDataClient
    virtual void notify(uint16_t typeId, uint32_t item_len, const void* data);

    virtual void flush();

    // enable / disable the searching service
    void setEnabled(bool e);

    // override Thread
    virtual bool threadLoop();

    // override android::IBinder::DeathRecipient
    virtual void binderDied(const wp<IBinder>& who);

 private:
    virtual ~JNIMetaDataService();

    sp<JNIMetaDataService> self_strongref;
    sp<JNIMetaDataService> thread_strongref;
    jobject metadataservice_ref;
    jbyteArray metadata_buffer;
    Mutex lock;
    Condition cond;
    volatile bool remote_service_invalid;
    volatile bool exitThread;
    volatile bool enabled;
};

void JNIMetaDataService::notify(uint16_t typeId, uint32_t item_len,
                                const void* data) {
    LOGV("notify received type=%d item_len=%d", typeId, item_len);
    if (!enabled) {
        return;
    }
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    // ensureArraySize provides some simple optimization of reusing
    // the byte array object.  If in the future that different types
    // of metadata hit client, then more sophisticated strategy is needed.
    ensureArraySize(env, &metadata_buffer, item_len);
    if (metadata_buffer) {
        jbyte *nArray = env->GetByteArrayElements(metadata_buffer, NULL);
        memcpy(nArray, data, item_len);
        env->ReleaseByteArrayElements(metadata_buffer, nArray, 0);
    }
    env->CallStaticVoidMethod(jnireflect.clazz,
                              jnireflect.postEventFromNativeId,
                              metadataservice_ref, typeId, item_len,
                              metadata_buffer);
}

void JNIMetaDataService::flush() {
    if (!enabled) {
        return;
    }
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->CallStaticVoidMethod(jnireflect.clazz,
                              jnireflect.flushFromNativeId,
                              metadataservice_ref);
}

JNIMetaDataService::JNIMetaDataService()
        : metadataservice_ref(NULL),
          metadata_buffer(NULL),
          remote_service_invalid(true),
          exitThread(false),
          enabled(false) {
    // Holds strong reference to myself, because the way that binder works
    // requires to use RefBase,  we cannot explicitly delete this object,
    // otherwise, access from service manager might cause segfault.
    // So we hold this reference until destroy() is called.
    // Alternative solution is to create another JNIMetaDataServiceCookie class
    // which holds the strong reference but that adds more memory fragmentation
    self_strongref = this;
}

JNIMetaDataService::~JNIMetaDataService() {
    LOGV("~JNIMetaDataService");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (metadata_buffer) {
        env->DeleteGlobalRef(metadata_buffer);
        metadata_buffer = NULL;
    }
    if (metadataservice_ref) {
        env->DeleteGlobalRef(metadataservice_ref);
        metadataservice_ref = NULL;
    }
}

bool JNIMetaDataService::threadLoop() {
    LOGV("Enter JNIMetaDataService::threadLoop");
    sp < IServiceManager > sm = defaultServiceManager();
    sp < IBinder > binder;
    sp<IAAHMetaDataService> remote_service;
    lock.lock();
    while (true) {
        if (exitThread) {
            break;
        } else if (remote_service_invalid && enabled) {
            // getService() may block 10s, so we do this not holding lock
            lock.unlock();
            binder = sm->getService(
                    String16(kAAHMetaDataServiceBinderName));
            lock.lock();
            if (binder != NULL) {
                LOGD("found remote %s", kAAHMetaDataServiceBinderName);
                if (remote_service.get()) {
                    remote_service->asBinder()->unlinkToDeath(this);
                    remote_service->removeClient(thread_strongref);
                    remote_service = NULL;
                }
                remote_service = interface_cast < IAAHMetaDataService
                        > (binder);
                remote_service->asBinder()->linkToDeath(this);
                remote_service->addClient(thread_strongref);
                remote_service_invalid = false;
            }
        }
        if (!exitThread && !(remote_service_invalid && enabled)) {
            // if exitThread flag is not set and we are not searching remote
            // service, wait next signal to be triggered either
            //   - destroy() being called
            //   - enabled or remote_service_invalid changed
            cond.wait(lock);
        }
    }
    if (remote_service.get()) {
        remote_service->removeClient(thread_strongref);
        remote_service->asBinder()->unlinkToDeath(this);
        remote_service = NULL;
    }
    lock.unlock();
    binder = NULL;
    sm = NULL;
    // cleanup the thread reference
    thread_strongref = NULL;
    LOGV("Exit JNIMetaDataService::threadLoop");
    return false;
}

bool JNIMetaDataService::start(jobject ref) {
    metadataservice_ref = ref;
    // now add a strong ref, used in threadLoop()
    thread_strongref = this;
    if (NO_ERROR
            != run("aah_metadataservice_monitor", ANDROID_PRIORITY_NORMAL)) {
        thread_strongref = NULL;
        return false;
    }
    return true;
}

void JNIMetaDataService::destroy() {
    lock.lock();
    exitThread = true;
    lock.unlock();
    cond.signal();
    // unref JNIMetaDataService, JNIMetaDataService will not be deleted for now;
    // it will be deleted when thread exits and cleans thread_strongref.
    self_strongref = NULL;
}

void JNIMetaDataService::setEnabled(bool e) {
    bool sendSignal;
    lock.lock();
    sendSignal = e && !enabled;
    enabled = e;
    lock.unlock();
    if (sendSignal) {
        cond.signal();
    }
}

void JNIMetaDataService::binderDied(const wp<IBinder>& who) {
    LOGD("remote %s died, re-searching...", kAAHMetaDataServiceBinderName);
    bool sendSignal;
    lock.lock();
    remote_service_invalid = true;
    sendSignal = enabled;
    lock.unlock();
    if (sendSignal) {
        cond.signal();
    }
}

// called by java object to initialize the native part
static jint aahmetadataservice_native_setup(JNIEnv* env, jobject thiz,
                                            jobject weak_this) {

    jint retvalue = SUCCESS;
    jobject ref;
    JNIMetaDataService* lpJniService = new JNIMetaDataService();
    if (lpJniService == NULL) {
        LOGE("setup: Error in allocating JNIMetaDataService");
        retvalue = ERROR;
        goto setup_failure;
    }

    // we use a weak reference so the java object can be garbage collected.
    ref = env->NewGlobalRef(weak_this);
    if (ref == NULL) {
        LOGE("setup: Error in NewGlobalRef");
        retvalue = ERROR;
        goto setup_failure;
    }

    LOGV("setup: lpJniService: %p metadataservice_ref %p", lpJniService, ref);

    env->SetIntField(thiz, jnireflect.mCookieId,
                     reinterpret_cast<jint>(lpJniService));

    if (!lpJniService->start(ref)) {
        retvalue = ERROR;
        LOGE("setup: Error in starting JNIMetaDataService");
        goto setup_failure;
    }

    return retvalue;

    // failures:
    setup_failure:

    if (lpJniService != NULL) {
        lpJniService->destroy();
    }

    return retvalue;
}

inline JNIMetaDataService* get_service(JNIEnv* env, jobject thiz) {
    return reinterpret_cast<JNIMetaDataService*>(env->GetIntField(
            thiz, jnireflect.mCookieId));
}

// called when the java object is garbaged collected
static void aahmetadataservice_native_finalize(JNIEnv* env, jobject thiz) {
    JNIMetaDataService* pService = get_service(env, thiz);
    if (pService == NULL) {
        return;
    }
    LOGV("finalize jni object");
    // clean up the service object
    pService->destroy();
    env->SetIntField(thiz, jnireflect.mCookieId, 0);
}

static void aahmetadataservice_native_enable(JNIEnv* env, jobject thiz) {
    JNIMetaDataService* pService = get_service(env, thiz);
    if (pService == NULL) {
        LOGD("native service already deleted");
        return;
    }
    pService->setEnabled(true);
}

static void aahmetadataservice_native_disable(JNIEnv* env, jobject thiz) {
    JNIMetaDataService* pService = get_service(env, thiz);
    if (pService == NULL) {
        LOGD("native service already deleted");
        return;
    }
    pService->setEnabled(false);
}

static JNINativeMethod kAAHMetaDataServiceMethods[] = {
    { "native_setup", "(Ljava/lang/Object;)I",
            (void *) aahmetadataservice_native_setup },
    { "native_enable", "()V", (void *) aahmetadataservice_native_enable },
    { "native_disable", "()V", (void *) aahmetadataservice_native_disable },
    { "native_finalize", "()V", (void *) aahmetadataservice_native_finalize },
};

static jint jniOnLoad(JavaVM* vm, void* reserved) {
    LOGV("jniOnLoad");
    JNIEnv* env = NULL;
    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        LOGE("ERROR: GetEnv failed\n");
        return -1;
    }

    jclass clazz = env->FindClass(kAAHMetaDataServiceClassName);
    if (!clazz) {
        LOGE("ERROR: FindClass failed\n");
        return -1;
    }

    jnireflect.clazz = (jclass) env->NewGlobalRef(clazz);

    if (env->RegisterNatives(jnireflect.clazz, kAAHMetaDataServiceMethods,
                             NELEM(kAAHMetaDataServiceMethods)) < 0) {
        LOGE("ERROR: RegisterNatives failed\n");
        return -1;
    }

    jnireflect.postEventFromNativeId = env->GetStaticMethodID(
            jnireflect.clazz, "postMetaDataFromNative",
            "(Ljava/lang/Object;SI[B)V");
    if (!jnireflect.postEventFromNativeId) {
        LOGE("Can't find %s", "postMetaDataFromNative");
        return -1;
    }
    jnireflect.flushFromNativeId = env->GetStaticMethodID(
            jnireflect.clazz, "flushFromNative",
            "(Ljava/lang/Object;)V");
    if (!jnireflect.flushFromNativeId) {
        LOGE("Can't find %s", "flushFromNative");
        return -1;
    }

    jnireflect.mCookieId = env->GetFieldID(jnireflect.clazz, "mCookie", "I");
    if (!jnireflect.mCookieId) {
        LOGE("Can't find %s", "mCookie");
        return -1;
    }

    return JNI_VERSION_1_4;
}

}

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    return android::jniOnLoad(vm, reserved);
}

