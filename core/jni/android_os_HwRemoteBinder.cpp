/*
 * Copyright (C) 2016 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "JHwRemoteBinder"
#include <android-base/logging.h>

#include "android_os_HwRemoteBinder.h"

#include "android_os_HwParcel.h"

#include <android/hidl/base/1.0/IBase.h>
#include <android/hidl/base/1.0/BpHwBase.h>
#include <android/hidl/base/1.0/BnHwBase.h>
#include <android_runtime/AndroidRuntime.h>
#include <hidl/Status.h>
#include <hidl/HidlTransportSupport.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <nativehelper/ScopedLocalRef.h>

#include "core_jni_helpers.h"

using android::AndroidRuntime;

#define PACKAGE_PATH    "android/os"
#define CLASS_NAME      "HwRemoteBinder"
#define CLASS_PATH      PACKAGE_PATH "/" CLASS_NAME

namespace android {

static struct fields_t {
    jclass proxy_class;
    jfieldID contextID;
    jmethodID constructID;
    jmethodID sendDeathNotice;
} gProxyOffsets;

static struct class_offsets_t
{
    jmethodID mGetName;
} gClassOffsets;

static JavaVM* jnienv_to_javavm(JNIEnv* env)
{
    JavaVM* vm;
    return env->GetJavaVM(&vm) >= 0 ? vm : NULL;
}

static JNIEnv* javavm_to_jnienv(JavaVM* vm)
{
    JNIEnv* env;
    return vm->GetEnv((void **)&env, JNI_VERSION_1_4) >= 0 ? env : NULL;
}

// ----------------------------------------------------------------------------
class HwBinderDeathRecipient : public hardware::IBinder::DeathRecipient
{
public:
    HwBinderDeathRecipient(JNIEnv* env, jobject object, jlong cookie, const sp<HwBinderDeathRecipientList>& list)
        : mVM(jnienv_to_javavm(env)), mObject(env->NewGlobalRef(object)),
          mObjectWeak(NULL), mCookie(cookie), mList(list)
    {
        // These objects manage their own lifetimes so are responsible for final bookkeeping.
        // The list holds a strong reference to this object.
        list->add(this);
    }

    void binderDied(const wp<hardware::IBinder>& who)
    {
        if (mObject != NULL) {
            JNIEnv* env = javavm_to_jnienv(mVM);

            env->CallStaticVoidMethod(gProxyOffsets.proxy_class, gProxyOffsets.sendDeathNotice, mObject, mCookie);
            if (env->ExceptionCheck()) {
                ALOGE("Uncaught exception returned from death notification.");
                env->ExceptionClear();
            }

            // Serialize with our containing HwBinderDeathRecipientList so that we can't
            // delete the global ref on mObject while the list is being iterated.
            sp<HwBinderDeathRecipientList> list = mList.promote();
            if (list != NULL) {
                AutoMutex _l(list->lock());

                // Demote from strong ref to weak after binderDied() has been delivered,
                // to allow the DeathRecipient and BinderProxy to be GC'd if no longer needed.
                mObjectWeak = env->NewWeakGlobalRef(mObject);
                env->DeleteGlobalRef(mObject);
                mObject = NULL;
            }
        }
    }

    void clearReference()
    {
        sp<HwBinderDeathRecipientList> list = mList.promote();
        if (list != NULL) {
            list->remove(this);
        } else {
            ALOGE("clearReference() on JDR %p but DRL wp purged", this);
        }
    }

    bool matches(jobject obj) {
        bool result;
        JNIEnv* env = javavm_to_jnienv(mVM);

        if (mObject != NULL) {
            result = env->IsSameObject(obj, mObject);
        } else {
            jobject me = env->NewLocalRef(mObjectWeak);
            result = env->IsSameObject(obj, me);
            env->DeleteLocalRef(me);
        }
        return result;
    }

    void warnIfStillLive() {
        if (mObject != NULL) {
            // Okay, something is wrong -- we have a hard reference to a live death
            // recipient on the VM side, but the list is being torn down.
            JNIEnv* env = javavm_to_jnienv(mVM);
            ScopedLocalRef<jclass> objClassRef(env, env->GetObjectClass(mObject));
            ScopedLocalRef<jstring> nameRef(env,
                    (jstring) env->CallObjectMethod(objClassRef.get(), gClassOffsets.mGetName));
            ScopedUtfChars nameUtf(env, nameRef.get());
            if (nameUtf.c_str() != NULL) {
                ALOGW("BinderProxy is being destroyed but the application did not call "
                        "unlinkToDeath to unlink all of its death recipients beforehand.  "
                        "Releasing leaked death recipient: %s", nameUtf.c_str());
            } else {
                ALOGW("BinderProxy being destroyed; unable to get DR object name");
                env->ExceptionClear();
            }
        }
    }

protected:
    virtual ~HwBinderDeathRecipient()
    {
        JNIEnv* env = javavm_to_jnienv(mVM);
        if (mObject != NULL) {
            env->DeleteGlobalRef(mObject);
        } else {
            env->DeleteWeakGlobalRef(mObjectWeak);
        }
    }

private:
    JavaVM* const mVM;
    jobject mObject;
    jweak mObjectWeak; // will be a weak ref to the same VM-side DeathRecipient after binderDied()
    jlong mCookie;
    wp<HwBinderDeathRecipientList> mList;
};
// ----------------------------------------------------------------------------

HwBinderDeathRecipientList::HwBinderDeathRecipientList() {
}

HwBinderDeathRecipientList::~HwBinderDeathRecipientList() {
    AutoMutex _l(mLock);

    for (const sp<HwBinderDeathRecipient>& deathRecipient : mList) {
        deathRecipient->warnIfStillLive();
    }
}

void HwBinderDeathRecipientList::add(const sp<HwBinderDeathRecipient>& recipient) {
    AutoMutex _l(mLock);

    mList.push_back(recipient);
}

void HwBinderDeathRecipientList::remove(const sp<HwBinderDeathRecipient>& recipient) {
    AutoMutex _l(mLock);

    for (auto iter = mList.begin(); iter != mList.end(); iter++) {
        if (*iter == recipient) {
            mList.erase(iter);
            return;
        }
    }
}

sp<HwBinderDeathRecipient> HwBinderDeathRecipientList::find(jobject recipient) {
    AutoMutex _l(mLock);

    for(auto iter = mList.rbegin(); iter != mList.rend(); iter++) {
        if ((*iter)->matches(recipient)) {
            return (*iter);
        }
    }

    return nullptr;
}

Mutex& HwBinderDeathRecipientList::lock() {
    return mLock;
}

// static
void JHwRemoteBinder::InitClass(JNIEnv *env) {
    jclass clazz = FindClassOrDie(env, CLASS_PATH);

    gProxyOffsets.proxy_class = MakeGlobalRefOrDie(env, clazz);
    gProxyOffsets.contextID =
        GetFieldIDOrDie(env, clazz, "mNativeContext", "J");
    gProxyOffsets.constructID = GetMethodIDOrDie(env, clazz, "<init>", "()V");
    gProxyOffsets.sendDeathNotice = GetStaticMethodIDOrDie(env, clazz, "sendDeathNotice",
            "(Landroid/os/IHwBinder$DeathRecipient;J)V");

    clazz = FindClassOrDie(env, "java/lang/Class");
    gClassOffsets.mGetName = GetMethodIDOrDie(env, clazz, "getName", "()Ljava/lang/String;");
}

// static
sp<JHwRemoteBinder> JHwRemoteBinder::SetNativeContext(
        JNIEnv *env, jobject thiz, const sp<JHwRemoteBinder> &context) {
    sp<JHwRemoteBinder> old =
        (JHwRemoteBinder *)env->GetLongField(thiz, gProxyOffsets.contextID);

    if (context != NULL) {
        context->incStrong(NULL /* id */);
    }

    if (old != NULL) {
        old->decStrong(NULL /* id */);
    }

    env->SetLongField(thiz, gProxyOffsets.contextID, (long)context.get());

    return old;
}

// static
sp<JHwRemoteBinder> JHwRemoteBinder::GetNativeContext(
        JNIEnv *env, jobject thiz) {
    return (JHwRemoteBinder *)env->GetLongField(thiz, gProxyOffsets.contextID);
}

// static
jobject JHwRemoteBinder::NewObject(
        JNIEnv *env, const sp<hardware::IBinder> &binder) {
    ScopedLocalRef<jclass> clazz(env, FindClassOrDie(env, CLASS_PATH));

    // XXX Have to look up the constructor here because otherwise that static
    // class initializer isn't called and gProxyOffsets.constructID is undefined :(

    jmethodID constructID = GetMethodIDOrDie(env, clazz.get(), "<init>", "()V");

    jobject obj = env->NewObject(clazz.get(), constructID);
    JHwRemoteBinder::GetNativeContext(env, obj)->setBinder(binder);

    return obj;
}

JHwRemoteBinder::JHwRemoteBinder(
        JNIEnv *env, jobject thiz, const sp<hardware::IBinder> &binder)
    : mBinder(binder) {
    mDeathRecipientList = new HwBinderDeathRecipientList();
    jclass clazz = env->GetObjectClass(thiz);
    CHECK(clazz != NULL);

    mObject = env->NewWeakGlobalRef(thiz);
}

JHwRemoteBinder::~JHwRemoteBinder() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteWeakGlobalRef(mObject);
    mObject = NULL;
}

sp<hardware::IBinder> JHwRemoteBinder::getBinder() const {
    return mBinder;
}

void JHwRemoteBinder::setBinder(const sp<hardware::IBinder> &binder) {
    mBinder = binder;
}

sp<HwBinderDeathRecipientList> JHwRemoteBinder::getDeathRecipientList() const {
    return mDeathRecipientList;
}

}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static void releaseNativeContext(void *nativeContext) {
    sp<JHwRemoteBinder> binder = (JHwRemoteBinder *)nativeContext;

    if (binder != NULL) {
        binder->decStrong(NULL /* id */);
    }
}

static jlong JHwRemoteBinder_native_init(JNIEnv *env) {
    JHwRemoteBinder::InitClass(env);

    return reinterpret_cast<jlong>(&releaseNativeContext);
}

static void JHwRemoteBinder_native_setup_empty(JNIEnv *env, jobject thiz) {
    sp<JHwRemoteBinder> context =
        new JHwRemoteBinder(env, thiz, NULL /* service */);

    JHwRemoteBinder::SetNativeContext(env, thiz, context);
}

static void JHwRemoteBinder_native_transact(
        JNIEnv *env,
        jobject thiz,
        jint code,
        jobject requestObj,
        jobject replyObj,
        jint flags) {
    sp<hardware::IBinder> binder =
        JHwRemoteBinder::GetNativeContext(env, thiz)->getBinder();

    if (requestObj == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    const hardware::Parcel *request =
        JHwParcel::GetNativeContext(env, requestObj)->getParcel();

    hardware::Parcel *reply =
        JHwParcel::GetNativeContext(env, replyObj)->getParcel();

    status_t err = binder->transact(code, *request, reply, flags);
    signalExceptionForError(env, err, true /* canThrowRemoteException */);
}

static jboolean JHwRemoteBinder_linkToDeath(JNIEnv* env, jobject thiz,
        jobject recipient, jlong cookie)
{
    if (recipient == NULL) {
        jniThrowNullPointerException(env, NULL);
        return JNI_FALSE;
    }

    sp<JHwRemoteBinder> context = JHwRemoteBinder::GetNativeContext(env, thiz);
    sp<hardware::IBinder> binder = context->getBinder();

    if (!binder->localBinder()) {
        HwBinderDeathRecipientList* list = (context->getDeathRecipientList()).get();
        sp<HwBinderDeathRecipient> jdr = new HwBinderDeathRecipient(env, recipient, cookie, list);
        status_t err = binder->linkToDeath(jdr, NULL, 0);
        if (err != NO_ERROR) {
            // Failure adding the death recipient, so clear its reference
            // now.
            jdr->clearReference();
            return JNI_FALSE;
        }
    }

    return JNI_TRUE;
}

static jboolean JHwRemoteBinder_unlinkToDeath(JNIEnv* env, jobject thiz,
                                                 jobject recipient)
{
    jboolean res = JNI_FALSE;
    if (recipient == NULL) {
        jniThrowNullPointerException(env, NULL);
        return res;
    }

    sp<JHwRemoteBinder> context = JHwRemoteBinder::GetNativeContext(env, thiz);
    sp<hardware::IBinder> binder = context->getBinder();

    if (!binder->localBinder()) {
        status_t err = NAME_NOT_FOUND;

        // If we find the matching recipient, proceed to unlink using that
        HwBinderDeathRecipientList* list = (context->getDeathRecipientList()).get();
        sp<HwBinderDeathRecipient> origJDR = list->find(recipient);
        if (origJDR != NULL) {
            wp<hardware::IBinder::DeathRecipient> dr;
            err = binder->unlinkToDeath(origJDR, NULL, 0, &dr);
            if (err == NO_ERROR && dr != NULL) {
                sp<hardware::IBinder::DeathRecipient> sdr = dr.promote();
                HwBinderDeathRecipient* jdr = static_cast<HwBinderDeathRecipient*>(sdr.get());
                if (jdr != NULL) {
                    jdr->clearReference();
                }
            }
        }

        if (err == NO_ERROR || err == DEAD_OBJECT) {
            res = JNI_TRUE;
        } else {
            jniThrowException(env, "java/util/NoSuchElementException",
                              "Death link does not exist");
        }
    }

    return res;
}

static sp<hidl::base::V1_0::IBase> toIBase(JNIEnv* env, jclass hwRemoteBinderClazz, jobject jbinder)
{
    if (jbinder == nullptr) {
        return nullptr;
    }
    if (!env->IsInstanceOf(jbinder, hwRemoteBinderClazz)) {
        return nullptr;
    }
    sp<JHwRemoteBinder> context = JHwRemoteBinder::GetNativeContext(env, jbinder);
    sp<hardware::IBinder> cbinder = context->getBinder();
    return hardware::fromBinder<hidl::base::V1_0::IBase, hidl::base::V1_0::BpHwBase,
                                hidl::base::V1_0::BnHwBase>(cbinder);
}

// equals iff other is also a non-null android.os.HwRemoteBinder object
// and getBinder() returns the same object.
// In particular, if other is an android.os.HwBinder object (for stubs) then
// it returns false.
static jboolean JHwRemoteBinder_equals(JNIEnv* env, jobject thiz, jobject other)
{
    if (env->IsSameObject(thiz, other)) {
        return true;
    }
    if (other == NULL) {
        return false;
    }

    ScopedLocalRef<jclass> clazz(env, FindClassOrDie(env, CLASS_PATH));

    return hardware::interfacesEqual(toIBase(env, clazz.get(), thiz), toIBase(env, clazz.get(), other));
}

static jint JHwRemoteBinder_hashCode(JNIEnv* env, jobject thiz) {
    jlong longHash = reinterpret_cast<jlong>(
            JHwRemoteBinder::GetNativeContext(env, thiz)->getBinder().get());
    return static_cast<jint>(longHash ^ (longHash >> 32)); // See Long.hashCode()
}

static const JNINativeMethod gMethods[] = {
    { "native_init", "()J", (void *)JHwRemoteBinder_native_init },

    { "native_setup_empty", "()V",
        (void *)JHwRemoteBinder_native_setup_empty },

    { "transact",
        "(IL" PACKAGE_PATH "/HwParcel;L" PACKAGE_PATH "/HwParcel;I)V",
        (void *)JHwRemoteBinder_native_transact },

    {"linkToDeath",
        "(Landroid/os/IHwBinder$DeathRecipient;J)Z",
        (void*)JHwRemoteBinder_linkToDeath},

    {"unlinkToDeath",
        "(Landroid/os/IHwBinder$DeathRecipient;)Z",
        (void*)JHwRemoteBinder_unlinkToDeath},

    {"equals", "(Ljava/lang/Object;)Z",
        (void*)JHwRemoteBinder_equals},

    {"hashCode", "()I", (void*)JHwRemoteBinder_hashCode},
};

namespace android {

int register_android_os_HwRemoteBinder(JNIEnv *env) {
    return RegisterMethodsOrDie(env, CLASS_PATH, gMethods, NELEM(gMethods));
}

}  // namespace android

