/*
 * Copyright (C) 2006 The Android Open Source Project
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

#define LOG_TAG "JavaBinder"
//#define LOG_NDEBUG 0

#include "android_os_Parcel.h"
#include "android_util_Binder.h"

#include "JNIHelp.h"

#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <utils/Atomic.h>
#include <binder/IInterface.h>
#include <binder/IPCThreadState.h>
#include <utils/Log.h>
#include <utils/SystemClock.h>
#include <utils/List.h>
#include <utils/KeyedVector.h>
#include <cutils/logger.h>
#include <binder/Parcel.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>
#include <utils/threads.h>
#include <utils/String8.h>

#include <ScopedUtfChars.h>
#include <ScopedLocalRef.h>

#include <android_runtime/AndroidRuntime.h>

//#undef ALOGV
//#define ALOGV(...) fprintf(stderr, __VA_ARGS__)

#define DEBUG_DEATH 0
#if DEBUG_DEATH
#define LOGDEATH ALOGD
#else
#define LOGDEATH ALOGV
#endif

using namespace android;

// ----------------------------------------------------------------------------

static struct bindernative_offsets_t
{
    // Class state.
    jclass mClass;
    jmethodID mExecTransact;

    // Object state.
    jfieldID mObject;

} gBinderOffsets;

// ----------------------------------------------------------------------------

static struct binderinternal_offsets_t
{
    // Class state.
    jclass mClass;
    jmethodID mForceGc;

} gBinderInternalOffsets;

// ----------------------------------------------------------------------------

static struct debug_offsets_t
{
    // Class state.
    jclass mClass;

} gDebugOffsets;

// ----------------------------------------------------------------------------

static struct weakreference_offsets_t
{
    // Class state.
    jclass mClass;
    jmethodID mGet;

} gWeakReferenceOffsets;

static struct error_offsets_t
{
    jclass mClass;
} gErrorOffsets;

// ----------------------------------------------------------------------------

static struct binderproxy_offsets_t
{
    // Class state.
    jclass mClass;
    jmethodID mConstructor;
    jmethodID mSendDeathNotice;

    // Object state.
    jfieldID mObject;
    jfieldID mSelf;
    jfieldID mOrgue;

} gBinderProxyOffsets;

static struct class_offsets_t
{
    jmethodID mGetName;
} gClassOffsets;

// ----------------------------------------------------------------------------

static struct log_offsets_t
{
    // Class state.
    jclass mClass;
    jmethodID mLogE;
} gLogOffsets;

static struct parcel_file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
} gParcelFileDescriptorOffsets;

static struct strict_mode_callback_offsets_t
{
    jclass mClass;
    jmethodID mCallback;
} gStrictModeCallbackOffsets;

// ****************************************************************************
// ****************************************************************************
// ****************************************************************************

static volatile int32_t gNumRefsCreated = 0;
static volatile int32_t gNumProxyRefs = 0;
static volatile int32_t gNumLocalRefs = 0;
static volatile int32_t gNumDeathRefs = 0;

static void incRefsCreated(JNIEnv* env)
{
    int old = android_atomic_inc(&gNumRefsCreated);
    if (old == 200) {
        android_atomic_and(0, &gNumRefsCreated);
        env->CallStaticVoidMethod(gBinderInternalOffsets.mClass,
                gBinderInternalOffsets.mForceGc);
    } else {
        ALOGV("Now have %d binder ops", old);
    }
}

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

static void report_exception(JNIEnv* env, jthrowable excep, const char* msg)
{
    env->ExceptionClear();

    jstring tagstr = env->NewStringUTF(LOG_TAG);
    jstring msgstr = env->NewStringUTF(msg);

    if ((tagstr == NULL) || (msgstr == NULL)) {
        env->ExceptionClear();      /* assume exception (OOM?) was thrown */
        ALOGE("Unable to call Log.e()\n");
        ALOGE("%s", msg);
        goto bail;
    }

    env->CallStaticIntMethod(
        gLogOffsets.mClass, gLogOffsets.mLogE, tagstr, msgstr, excep);
    if (env->ExceptionCheck()) {
        /* attempting to log the failure has failed */
        ALOGW("Failed trying to log exception, msg='%s'\n", msg);
        env->ExceptionClear();
    }

    if (env->IsInstanceOf(excep, gErrorOffsets.mClass)) {
        /*
         * It's an Error: Reraise the exception, detach this thread, and
         * wait for the fireworks. Die even more blatantly after a minute
         * if the gentler attempt doesn't do the trick.
         *
         * The GetJavaVM function isn't on the "approved" list of JNI calls
         * that can be made while an exception is pending, so we want to
         * get the VM ptr, throw the exception, and then detach the thread.
         */
        JavaVM* vm = jnienv_to_javavm(env);
        env->Throw(excep);
        vm->DetachCurrentThread();
        sleep(60);
        ALOGE("Forcefully exiting");
        exit(1);
        *((int *) 1) = 1;
    }

bail:
    /* discard local refs created for us by VM */
    env->DeleteLocalRef(tagstr);
    env->DeleteLocalRef(msgstr);
}

class JavaBBinderHolder;

class JavaBBinder : public BBinder
{
public:
    JavaBBinder(JNIEnv* env, jobject object)
        : mVM(jnienv_to_javavm(env)), mObject(env->NewGlobalRef(object))
    {
        ALOGV("Creating JavaBBinder %p\n", this);
        android_atomic_inc(&gNumLocalRefs);
        incRefsCreated(env);
    }

    bool    checkSubclass(const void* subclassID) const
    {
        return subclassID == &gBinderOffsets;
    }

    jobject object() const
    {
        return mObject;
    }

protected:
    virtual ~JavaBBinder()
    {
        ALOGV("Destroying JavaBBinder %p\n", this);
        android_atomic_dec(&gNumLocalRefs);
        JNIEnv* env = javavm_to_jnienv(mVM);
        env->DeleteGlobalRef(mObject);
    }

    virtual status_t onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags = 0)
    {
        JNIEnv* env = javavm_to_jnienv(mVM);

        ALOGV("onTransact() on %p calling object %p in env %p vm %p\n", this, mObject, env, mVM);

        IPCThreadState* thread_state = IPCThreadState::self();
        const int strict_policy_before = thread_state->getStrictModePolicy();
        thread_state->setLastTransactionBinderFlags(flags);

        //printf("Transact from %p to Java code sending: ", this);
        //data.print();
        //printf("\n");
        jboolean res = env->CallBooleanMethod(mObject, gBinderOffsets.mExecTransact,
            code, (int32_t)&data, (int32_t)reply, flags);
        jthrowable excep = env->ExceptionOccurred();

        if (excep) {
            report_exception(env, excep,
                "*** Uncaught remote exception!  "
                "(Exceptions are not yet supported across processes.)");
            res = JNI_FALSE;

            /* clean up JNI local ref -- we don't return to Java code */
            env->DeleteLocalRef(excep);
        }

        // Restore the Java binder thread's state if it changed while
        // processing a call (as it would if the Parcel's header had a
        // new policy mask and Parcel.enforceInterface() changed
        // it...)
        const int strict_policy_after = thread_state->getStrictModePolicy();
        if (strict_policy_after != strict_policy_before) {
            // Our thread-local...
            thread_state->setStrictModePolicy(strict_policy_before);
            // And the Java-level thread-local...
            set_dalvik_blockguard_policy(env, strict_policy_before);
        }

        jthrowable excep2 = env->ExceptionOccurred();
        if (excep2) {
            report_exception(env, excep2,
                "*** Uncaught exception in onBinderStrictModePolicyChange");
            /* clean up JNI local ref -- we don't return to Java code */
            env->DeleteLocalRef(excep2);
        }

        // Need to always call through the native implementation of
        // SYSPROPS_TRANSACTION.
        if (code == SYSPROPS_TRANSACTION) {
            BBinder::onTransact(code, data, reply, flags);
        }

        //aout << "onTransact to Java code; result=" << res << endl
        //    << "Transact from " << this << " to Java code returning "
        //    << reply << ": " << *reply << endl;
        return res != JNI_FALSE ? NO_ERROR : UNKNOWN_TRANSACTION;
    }

    virtual status_t dump(int fd, const Vector<String16>& args)
    {
        return 0;
    }

private:
    JavaVM* const   mVM;
    jobject const   mObject;
};

// ----------------------------------------------------------------------------

class JavaBBinderHolder : public RefBase
{
public:
    sp<JavaBBinder> get(JNIEnv* env, jobject obj)
    {
        AutoMutex _l(mLock);
        sp<JavaBBinder> b = mBinder.promote();
        if (b == NULL) {
            b = new JavaBBinder(env, obj);
            mBinder = b;
            ALOGV("Creating JavaBinder %p (refs %p) for Object %p, weakCount=%d\n",
                 b.get(), b->getWeakRefs(), obj, b->getWeakRefs()->getWeakCount());
        }

        return b;
    }

    sp<JavaBBinder> getExisting()
    {
        AutoMutex _l(mLock);
        return mBinder.promote();
    }

private:
    Mutex           mLock;
    wp<JavaBBinder> mBinder;
};

// ----------------------------------------------------------------------------

// Per-IBinder death recipient bookkeeping.  This is how we reconcile local jobject
// death recipient references passed in through JNI with the permanent corresponding
// JavaDeathRecipient objects.

class JavaDeathRecipient;

class DeathRecipientList : public RefBase {
    List< sp<JavaDeathRecipient> > mList;
    Mutex mLock;

public:
    DeathRecipientList();
    ~DeathRecipientList();

    void add(const sp<JavaDeathRecipient>& recipient);
    void remove(const sp<JavaDeathRecipient>& recipient);
    sp<JavaDeathRecipient> find(jobject recipient);
};

// ----------------------------------------------------------------------------

class JavaDeathRecipient : public IBinder::DeathRecipient
{
public:
    JavaDeathRecipient(JNIEnv* env, jobject object, const sp<DeathRecipientList>& list)
        : mVM(jnienv_to_javavm(env)), mObject(env->NewGlobalRef(object)),
          mObjectWeak(NULL), mList(list)
    {
        // These objects manage their own lifetimes so are responsible for final bookkeeping.
        // The list holds a strong reference to this object.
        LOGDEATH("Adding JDR %p to DRL %p", this, list.get());
        list->add(this);

        android_atomic_inc(&gNumDeathRefs);
        incRefsCreated(env);
    }

    void binderDied(const wp<IBinder>& who)
    {
        LOGDEATH("Receiving binderDied() on JavaDeathRecipient %p\n", this);
        if (mObject != NULL) {
            JNIEnv* env = javavm_to_jnienv(mVM);

            env->CallStaticVoidMethod(gBinderProxyOffsets.mClass,
                    gBinderProxyOffsets.mSendDeathNotice, mObject);
            jthrowable excep = env->ExceptionOccurred();
            if (excep) {
                report_exception(env, excep,
                        "*** Uncaught exception returned from death notification!");
            }

            // Demote from strong ref to weak after binderDied() has been delivered,
            // to allow the DeathRecipient and BinderProxy to be GC'd if no longer needed.
            mObjectWeak = env->NewWeakGlobalRef(mObject);
            env->DeleteGlobalRef(mObject);
            mObject = NULL;
        }
    }

    void clearReference()
    {
        sp<DeathRecipientList> list = mList.promote();
        if (list != NULL) {
            LOGDEATH("Removing JDR %p from DRL %p", this, list.get());
            list->remove(this);
        } else {
            LOGDEATH("clearReference() on JDR %p but DRL wp purged", this);
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
    virtual ~JavaDeathRecipient()
    {
        //ALOGI("Removing death ref: recipient=%p\n", mObject);
        android_atomic_dec(&gNumDeathRefs);
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
    wp<DeathRecipientList> mList;
};

// ----------------------------------------------------------------------------

DeathRecipientList::DeathRecipientList() {
    LOGDEATH("New DRL @ %p", this);
}

DeathRecipientList::~DeathRecipientList() {
    LOGDEATH("Destroy DRL @ %p", this);
    AutoMutex _l(mLock);

    // Should never happen -- the JavaDeathRecipient objects that have added themselves
    // to the list are holding references on the list object.  Only when they are torn
    // down can the list header be destroyed.
    if (mList.size() > 0) {
        List< sp<JavaDeathRecipient> >::iterator iter;
        for (iter = mList.begin(); iter != mList.end(); iter++) {
            (*iter)->warnIfStillLive();
        }
    }
}

void DeathRecipientList::add(const sp<JavaDeathRecipient>& recipient) {
    AutoMutex _l(mLock);

    LOGDEATH("DRL @ %p : add JDR %p", this, recipient.get());
    mList.push_back(recipient);
}

void DeathRecipientList::remove(const sp<JavaDeathRecipient>& recipient) {
    AutoMutex _l(mLock);

    List< sp<JavaDeathRecipient> >::iterator iter;
    for (iter = mList.begin(); iter != mList.end(); iter++) {
        if (*iter == recipient) {
            LOGDEATH("DRL @ %p : remove JDR %p", this, recipient.get());
            mList.erase(iter);
            return;
        }
    }
}

sp<JavaDeathRecipient> DeathRecipientList::find(jobject recipient) {
    AutoMutex _l(mLock);

    List< sp<JavaDeathRecipient> >::iterator iter;
    for (iter = mList.begin(); iter != mList.end(); iter++) {
        if ((*iter)->matches(recipient)) {
            return *iter;
        }
    }
    return NULL;
}

// ----------------------------------------------------------------------------

namespace android {

static void proxy_cleanup(const void* id, void* obj, void* cleanupCookie)
{
    android_atomic_dec(&gNumProxyRefs);
    JNIEnv* env = javavm_to_jnienv((JavaVM*)cleanupCookie);
    env->DeleteGlobalRef((jobject)obj);
}

static Mutex mProxyLock;

jobject javaObjectForIBinder(JNIEnv* env, const sp<IBinder>& val)
{
    if (val == NULL) return NULL;

    if (val->checkSubclass(&gBinderOffsets)) {
        // One of our own!
        jobject object = static_cast<JavaBBinder*>(val.get())->object();
        LOGDEATH("objectForBinder %p: it's our own %p!\n", val.get(), object);
        return object;
    }

    // For the rest of the function we will hold this lock, to serialize
    // looking/creation of Java proxies for native Binder proxies.
    AutoMutex _l(mProxyLock);

    // Someone else's...  do we know about it?
    jobject object = (jobject)val->findObject(&gBinderProxyOffsets);
    if (object != NULL) {
        jobject res = env->CallObjectMethod(object, gWeakReferenceOffsets.mGet);
        if (res != NULL) {
            ALOGV("objectForBinder %p: found existing %p!\n", val.get(), res);
            return res;
        }
        LOGDEATH("Proxy object %p of IBinder %p no longer in working set!!!", object, val.get());
        android_atomic_dec(&gNumProxyRefs);
        val->detachObject(&gBinderProxyOffsets);
        env->DeleteGlobalRef(object);
    }

    object = env->NewObject(gBinderProxyOffsets.mClass, gBinderProxyOffsets.mConstructor);
    if (object != NULL) {
        LOGDEATH("objectForBinder %p: created new proxy %p !\n", val.get(), object);
        // The proxy holds a reference to the native object.
        env->SetIntField(object, gBinderProxyOffsets.mObject, (int)val.get());
        val->incStrong(object);

        // The native object needs to hold a weak reference back to the
        // proxy, so we can retrieve the same proxy if it is still active.
        jobject refObject = env->NewGlobalRef(
                env->GetObjectField(object, gBinderProxyOffsets.mSelf));
        val->attachObject(&gBinderProxyOffsets, refObject,
                jnienv_to_javavm(env), proxy_cleanup);

        // Also remember the death recipients registered on this proxy
        sp<DeathRecipientList> drl = new DeathRecipientList;
        drl->incStrong((void*)javaObjectForIBinder);
        env->SetIntField(object, gBinderProxyOffsets.mOrgue, reinterpret_cast<jint>(drl.get()));

        // Note that a new object reference has been created.
        android_atomic_inc(&gNumProxyRefs);
        incRefsCreated(env);
    }

    return object;
}

sp<IBinder> ibinderForJavaObject(JNIEnv* env, jobject obj)
{
    if (obj == NULL) return NULL;

    if (env->IsInstanceOf(obj, gBinderOffsets.mClass)) {
        JavaBBinderHolder* jbh = (JavaBBinderHolder*)
            env->GetIntField(obj, gBinderOffsets.mObject);
        return jbh != NULL ? jbh->get(env, obj) : NULL;
    }

    if (env->IsInstanceOf(obj, gBinderProxyOffsets.mClass)) {
        return (IBinder*)
            env->GetIntField(obj, gBinderProxyOffsets.mObject);
    }

    ALOGW("ibinderForJavaObject: %p is not a Binder object", obj);
    return NULL;
}

jobject newParcelFileDescriptor(JNIEnv* env, jobject fileDesc)
{
    return env->NewObject(
            gParcelFileDescriptorOffsets.mClass, gParcelFileDescriptorOffsets.mConstructor, fileDesc);
}

void set_dalvik_blockguard_policy(JNIEnv* env, jint strict_policy)
{
    // Call back into android.os.StrictMode#onBinderStrictModePolicyChange
    // to sync our state back to it.  See the comments in StrictMode.java.
    env->CallStaticVoidMethod(gStrictModeCallbackOffsets.mClass,
                              gStrictModeCallbackOffsets.mCallback,
                              strict_policy);
}

void signalExceptionForError(JNIEnv* env, jobject obj, status_t err,
        bool canThrowRemoteException)
{
    switch (err) {
        case UNKNOWN_ERROR:
            jniThrowException(env, "java/lang/RuntimeException", "Unknown error");
            break;
        case NO_MEMORY:
            jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
            break;
        case INVALID_OPERATION:
            jniThrowException(env, "java/lang/UnsupportedOperationException", NULL);
            break;
        case BAD_VALUE:
            jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
            break;
        case BAD_INDEX:
            jniThrowException(env, "java/lang/IndexOutOfBoundsException", NULL);
            break;
        case BAD_TYPE:
            jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
            break;
        case NAME_NOT_FOUND:
            jniThrowException(env, "java/util/NoSuchElementException", NULL);
            break;
        case PERMISSION_DENIED:
            jniThrowException(env, "java/lang/SecurityException", NULL);
            break;
        case NOT_ENOUGH_DATA:
            jniThrowException(env, "android/os/ParcelFormatException", "Not enough data");
            break;
        case NO_INIT:
            jniThrowException(env, "java/lang/RuntimeException", "Not initialized");
            break;
        case ALREADY_EXISTS:
            jniThrowException(env, "java/lang/RuntimeException", "Item already exists");
            break;
        case DEAD_OBJECT:
            // DeadObjectException is a checked exception, only throw from certain methods.
            jniThrowException(env, canThrowRemoteException
                    ? "android/os/DeadObjectException"
                            : "java/lang/RuntimeException", NULL);
            break;
        case UNKNOWN_TRANSACTION:
            jniThrowException(env, "java/lang/RuntimeException", "Unknown transaction code");
            break;
        case FAILED_TRANSACTION:
            ALOGE("!!! FAILED BINDER TRANSACTION !!!");
            // TransactionTooLargeException is a checked exception, only throw from certain methods.
            // FIXME: Transaction too large is the most common reason for FAILED_TRANSACTION
            //        but it is not the only one.  The Binder driver can return BR_FAILED_REPLY
            //        for other reasons also, such as if the transaction is malformed or
            //        refers to an FD that has been closed.  We should change the driver
            //        to enable us to distinguish these cases in the future.
            jniThrowException(env, canThrowRemoteException
                    ? "android/os/TransactionTooLargeException"
                            : "java/lang/RuntimeException", NULL);
            break;
        case FDS_NOT_ALLOWED:
            jniThrowException(env, "java/lang/RuntimeException",
                    "Not allowed to write file descriptors here");
            break;
        default:
            ALOGE("Unknown binder error code. 0x%x", err);
            String8 msg;
            msg.appendFormat("Unknown binder error code. 0x%x", err);
            // RemoteException is a checked exception, only throw from certain methods.
            jniThrowException(env, canThrowRemoteException
                    ? "android/os/RemoteException" : "java/lang/RuntimeException", msg.string());
            break;
    }
}

}

// ----------------------------------------------------------------------------

static jint android_os_Binder_getCallingPid(JNIEnv* env, jobject clazz)
{
    return IPCThreadState::self()->getCallingPid();
}

static jint android_os_Binder_getCallingUid(JNIEnv* env, jobject clazz)
{
    return IPCThreadState::self()->getCallingUid();
}

static jlong android_os_Binder_clearCallingIdentity(JNIEnv* env, jobject clazz)
{
    return IPCThreadState::self()->clearCallingIdentity();
}

static void android_os_Binder_restoreCallingIdentity(JNIEnv* env, jobject clazz, jlong token)
{
    // XXX temporary sanity check to debug crashes.
    int uid = (int)(token>>32);
    if (uid > 0 && uid < 999) {
        // In Android currently there are no uids in this range.
        char buf[128];
        sprintf(buf, "Restoring bad calling ident: 0x%Lx", token);
        jniThrowException(env, "java/lang/IllegalStateException", buf);
        return;
    }
    IPCThreadState::self()->restoreCallingIdentity(token);
}

static void android_os_Binder_setThreadStrictModePolicy(JNIEnv* env, jobject clazz, jint policyMask)
{
    IPCThreadState::self()->setStrictModePolicy(policyMask);
}

static jint android_os_Binder_getThreadStrictModePolicy(JNIEnv* env, jobject clazz)
{
    return IPCThreadState::self()->getStrictModePolicy();
}

static void android_os_Binder_flushPendingCommands(JNIEnv* env, jobject clazz)
{
    IPCThreadState::self()->flushCommands();
}

static void android_os_Binder_init(JNIEnv* env, jobject obj)
{
    JavaBBinderHolder* jbh = new JavaBBinderHolder();
    if (jbh == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return;
    }
    ALOGV("Java Binder %p: acquiring first ref on holder %p", obj, jbh);
    jbh->incStrong((void*)android_os_Binder_init);
    env->SetIntField(obj, gBinderOffsets.mObject, (int)jbh);
}

static void android_os_Binder_destroy(JNIEnv* env, jobject obj)
{
    JavaBBinderHolder* jbh = (JavaBBinderHolder*)
        env->GetIntField(obj, gBinderOffsets.mObject);
    if (jbh != NULL) {
        env->SetIntField(obj, gBinderOffsets.mObject, 0);
        ALOGV("Java Binder %p: removing ref on holder %p", obj, jbh);
        jbh->decStrong((void*)android_os_Binder_init);
    } else {
        // Encountering an uninitialized binder is harmless.  All it means is that
        // the Binder was only partially initialized when its finalizer ran and called
        // destroy().  The Binder could be partially initialized for several reasons.
        // For example, a Binder subclass constructor might have thrown an exception before
        // it could delegate to its superclass's constructor.  Consequently init() would
        // not have been called and the holder pointer would remain NULL.
        ALOGV("Java Binder %p: ignoring uninitialized binder", obj);
    }
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gBinderMethods[] = {
     /* name, signature, funcPtr */
    { "getCallingPid", "()I", (void*)android_os_Binder_getCallingPid },
    { "getCallingUid", "()I", (void*)android_os_Binder_getCallingUid },
    { "clearCallingIdentity", "()J", (void*)android_os_Binder_clearCallingIdentity },
    { "restoreCallingIdentity", "(J)V", (void*)android_os_Binder_restoreCallingIdentity },
    { "setThreadStrictModePolicy", "(I)V", (void*)android_os_Binder_setThreadStrictModePolicy },
    { "getThreadStrictModePolicy", "()I", (void*)android_os_Binder_getThreadStrictModePolicy },
    { "flushPendingCommands", "()V", (void*)android_os_Binder_flushPendingCommands },
    { "init", "()V", (void*)android_os_Binder_init },
    { "destroy", "()V", (void*)android_os_Binder_destroy }
};

const char* const kBinderPathName = "android/os/Binder";

static int int_register_android_os_Binder(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass(kBinderPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.Binder");

    gBinderOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gBinderOffsets.mExecTransact
        = env->GetMethodID(clazz, "execTransact", "(IIII)Z");
    assert(gBinderOffsets.mExecTransact);

    gBinderOffsets.mObject
        = env->GetFieldID(clazz, "mObject", "I");
    assert(gBinderOffsets.mObject);

    return AndroidRuntime::registerNativeMethods(
        env, kBinderPathName,
        gBinderMethods, NELEM(gBinderMethods));
}

// ****************************************************************************
// ****************************************************************************
// ****************************************************************************

namespace android {

jint android_os_Debug_getLocalObjectCount(JNIEnv* env, jobject clazz)
{
    return gNumLocalRefs;
}

jint android_os_Debug_getProxyObjectCount(JNIEnv* env, jobject clazz)
{
    return gNumProxyRefs;
}

jint android_os_Debug_getDeathObjectCount(JNIEnv* env, jobject clazz)
{
    return gNumDeathRefs;
}

}

// ****************************************************************************
// ****************************************************************************
// ****************************************************************************

static jobject android_os_BinderInternal_getContextObject(JNIEnv* env, jobject clazz)
{
    sp<IBinder> b = ProcessState::self()->getContextObject(NULL);
    return javaObjectForIBinder(env, b);
}

static void android_os_BinderInternal_joinThreadPool(JNIEnv* env, jobject clazz)
{
    sp<IBinder> b = ProcessState::self()->getContextObject(NULL);
    android::IPCThreadState::self()->joinThreadPool();
}

static void android_os_BinderInternal_disableBackgroundScheduling(JNIEnv* env,
        jobject clazz, jboolean disable)
{
    IPCThreadState::disableBackgroundScheduling(disable ? true : false);
}

static void android_os_BinderInternal_handleGc(JNIEnv* env, jobject clazz)
{
    ALOGV("Gc has executed, clearing binder ops");
    android_atomic_and(0, &gNumRefsCreated);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gBinderInternalMethods[] = {
     /* name, signature, funcPtr */
    { "getContextObject", "()Landroid/os/IBinder;", (void*)android_os_BinderInternal_getContextObject },
    { "joinThreadPool", "()V", (void*)android_os_BinderInternal_joinThreadPool },
    { "disableBackgroundScheduling", "(Z)V", (void*)android_os_BinderInternal_disableBackgroundScheduling },
    { "handleGc", "()V", (void*)android_os_BinderInternal_handleGc }
};

const char* const kBinderInternalPathName = "com/android/internal/os/BinderInternal";

static int int_register_android_os_BinderInternal(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass(kBinderInternalPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class com.android.internal.os.BinderInternal");

    gBinderInternalOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gBinderInternalOffsets.mForceGc
        = env->GetStaticMethodID(clazz, "forceBinderGc", "()V");
    assert(gBinderInternalOffsets.mForceGc);

    return AndroidRuntime::registerNativeMethods(
        env, kBinderInternalPathName,
        gBinderInternalMethods, NELEM(gBinderInternalMethods));
}

// ****************************************************************************
// ****************************************************************************
// ****************************************************************************

static jboolean android_os_BinderProxy_pingBinder(JNIEnv* env, jobject obj)
{
    IBinder* target = (IBinder*)
        env->GetIntField(obj, gBinderProxyOffsets.mObject);
    if (target == NULL) {
        return JNI_FALSE;
    }
    status_t err = target->pingBinder();
    return err == NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

static jstring android_os_BinderProxy_getInterfaceDescriptor(JNIEnv* env, jobject obj)
{
    IBinder* target = (IBinder*) env->GetIntField(obj, gBinderProxyOffsets.mObject);
    if (target != NULL) {
        const String16& desc = target->getInterfaceDescriptor();
        return env->NewString(desc.string(), desc.size());
    }
    jniThrowException(env, "java/lang/RuntimeException",
            "No binder found for object");
    return NULL;
}

static jboolean android_os_BinderProxy_isBinderAlive(JNIEnv* env, jobject obj)
{
    IBinder* target = (IBinder*)
        env->GetIntField(obj, gBinderProxyOffsets.mObject);
    if (target == NULL) {
        return JNI_FALSE;
    }
    bool alive = target->isBinderAlive();
    return alive ? JNI_TRUE : JNI_FALSE;
}

static int getprocname(pid_t pid, char *buf, size_t len) {
    char filename[20];
    FILE *f;

    sprintf(filename, "/proc/%d/cmdline", pid);
    f = fopen(filename, "r");
    if (!f) { *buf = '\0'; return 1; }
    if (!fgets(buf, len, f)) { *buf = '\0'; return 2; }
    fclose(f);
    return 0;
}

static bool push_eventlog_string(char** pos, const char* end, const char* str) {
    jint len = strlen(str);
    int space_needed = 1 + sizeof(len) + len;
    if (end - *pos < space_needed) {
        ALOGW("not enough space for string. remain=%d; needed=%d",
             (end - *pos), space_needed);
        return false;
    }
    **pos = EVENT_TYPE_STRING;
    (*pos)++;
    memcpy(*pos, &len, sizeof(len));
    *pos += sizeof(len);
    memcpy(*pos, str, len);
    *pos += len;
    return true;
}

static bool push_eventlog_int(char** pos, const char* end, jint val) {
    int space_needed = 1 + sizeof(val);
    if (end - *pos < space_needed) {
        ALOGW("not enough space for int.  remain=%d; needed=%d",
             (end - *pos), space_needed);
        return false;
    }
    **pos = EVENT_TYPE_INT;
    (*pos)++;
    memcpy(*pos, &val, sizeof(val));
    *pos += sizeof(val);
    return true;
}

// From frameworks/base/core/java/android/content/EventLogTags.logtags:
#define LOGTAG_BINDER_OPERATION 52004

static void conditionally_log_binder_call(int64_t start_millis,
                                          IBinder* target, jint code) {
    int duration_ms = static_cast<int>(uptimeMillis() - start_millis);

    int sample_percent;
    if (duration_ms >= 500) {
        sample_percent = 100;
    } else {
        sample_percent = 100 * duration_ms / 500;
        if (sample_percent == 0) {
            return;
        }
        if (sample_percent < (random() % 100 + 1)) {
            return;
        }
    }

    char process_name[40];
    getprocname(getpid(), process_name, sizeof(process_name));
    String8 desc(target->getInterfaceDescriptor());

    char buf[LOGGER_ENTRY_MAX_PAYLOAD];
    buf[0] = EVENT_TYPE_LIST;
    buf[1] = 5;
    char* pos = &buf[2];
    char* end = &buf[LOGGER_ENTRY_MAX_PAYLOAD - 1];  // leave room for final \n
    if (!push_eventlog_string(&pos, end, desc.string())) return;
    if (!push_eventlog_int(&pos, end, code)) return;
    if (!push_eventlog_int(&pos, end, duration_ms)) return;
    if (!push_eventlog_string(&pos, end, process_name)) return;
    if (!push_eventlog_int(&pos, end, sample_percent)) return;
    *(pos++) = '\n';   // conventional with EVENT_TYPE_LIST apparently.
    android_bWriteLog(LOGTAG_BINDER_OPERATION, buf, pos - buf);
}

// We only measure binder call durations to potentially log them if
// we're on the main thread.  Unfortunately sim-eng doesn't seem to
// have gettid, so we just ignore this and don't log if we can't
// get the thread id.
static bool should_time_binder_calls() {
#ifdef HAVE_GETTID
  return (getpid() == androidGetTid());
#else
#warning no gettid(), so not logging Binder calls...
  return false;
#endif
}

static jboolean android_os_BinderProxy_transact(JNIEnv* env, jobject obj,
        jint code, jobject dataObj, jobject replyObj, jint flags) // throws RemoteException
{
    if (dataObj == NULL) {
        jniThrowNullPointerException(env, NULL);
        return JNI_FALSE;
    }

    Parcel* data = parcelForJavaObject(env, dataObj);
    if (data == NULL) {
        return JNI_FALSE;
    }
    Parcel* reply = parcelForJavaObject(env, replyObj);
    if (reply == NULL && replyObj != NULL) {
        return JNI_FALSE;
    }

    IBinder* target = (IBinder*)
        env->GetIntField(obj, gBinderProxyOffsets.mObject);
    if (target == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "Binder has been finalized!");
        return JNI_FALSE;
    }

    ALOGV("Java code calling transact on %p in Java object %p with code %d\n",
            target, obj, code);

    // Only log the binder call duration for things on the Java-level main thread.
    // But if we don't
    const bool time_binder_calls = should_time_binder_calls();

    int64_t start_millis;
    if (time_binder_calls) {
        start_millis = uptimeMillis();
    }
    //printf("Transact from Java code to %p sending: ", target); data->print();
    status_t err = target->transact(code, *data, reply, flags);
    //if (reply) printf("Transact from Java code to %p received: ", target); reply->print();
    if (time_binder_calls) {
        conditionally_log_binder_call(start_millis, target, code);
    }

    if (err == NO_ERROR) {
        return JNI_TRUE;
    } else if (err == UNKNOWN_TRANSACTION) {
        return JNI_FALSE;
    }

    signalExceptionForError(env, obj, err, true /*canThrowRemoteException*/);
    return JNI_FALSE;
}

static void android_os_BinderProxy_linkToDeath(JNIEnv* env, jobject obj,
        jobject recipient, jint flags) // throws RemoteException
{
    if (recipient == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    IBinder* target = (IBinder*)
        env->GetIntField(obj, gBinderProxyOffsets.mObject);
    if (target == NULL) {
        ALOGW("Binder has been finalized when calling linkToDeath() with recip=%p)\n", recipient);
        assert(false);
    }

    LOGDEATH("linkToDeath: binder=%p recipient=%p\n", target, recipient);

    if (!target->localBinder()) {
        DeathRecipientList* list = (DeathRecipientList*)
                env->GetIntField(obj, gBinderProxyOffsets.mOrgue);
        sp<JavaDeathRecipient> jdr = new JavaDeathRecipient(env, recipient, list);
        status_t err = target->linkToDeath(jdr, NULL, flags);
        if (err != NO_ERROR) {
            // Failure adding the death recipient, so clear its reference
            // now.
            jdr->clearReference();
            signalExceptionForError(env, obj, err, true /*canThrowRemoteException*/);
        }
    }
}

static jboolean android_os_BinderProxy_unlinkToDeath(JNIEnv* env, jobject obj,
                                                 jobject recipient, jint flags)
{
    jboolean res = JNI_FALSE;
    if (recipient == NULL) {
        jniThrowNullPointerException(env, NULL);
        return res;
    }

    IBinder* target = (IBinder*)
        env->GetIntField(obj, gBinderProxyOffsets.mObject);
    if (target == NULL) {
        ALOGW("Binder has been finalized when calling linkToDeath() with recip=%p)\n", recipient);
        return JNI_FALSE;
    }

    LOGDEATH("unlinkToDeath: binder=%p recipient=%p\n", target, recipient);

    if (!target->localBinder()) {
        status_t err = NAME_NOT_FOUND;

        // If we find the matching recipient, proceed to unlink using that
        DeathRecipientList* list = (DeathRecipientList*)
                env->GetIntField(obj, gBinderProxyOffsets.mOrgue);
        sp<JavaDeathRecipient> origJDR = list->find(recipient);
        LOGDEATH("   unlink found list %p and JDR %p", list, origJDR.get());
        if (origJDR != NULL) {
            wp<IBinder::DeathRecipient> dr;
            err = target->unlinkToDeath(origJDR, NULL, flags, &dr);
            if (err == NO_ERROR && dr != NULL) {
                sp<IBinder::DeathRecipient> sdr = dr.promote();
                JavaDeathRecipient* jdr = static_cast<JavaDeathRecipient*>(sdr.get());
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

static void android_os_BinderProxy_destroy(JNIEnv* env, jobject obj)
{
    IBinder* b = (IBinder*)
            env->GetIntField(obj, gBinderProxyOffsets.mObject);
    DeathRecipientList* drl = (DeathRecipientList*)
            env->GetIntField(obj, gBinderProxyOffsets.mOrgue);

    LOGDEATH("Destroying BinderProxy %p: binder=%p drl=%p\n", obj, b, drl);
    env->SetIntField(obj, gBinderProxyOffsets.mObject, 0);
    env->SetIntField(obj, gBinderProxyOffsets.mOrgue, 0);
    drl->decStrong((void*)javaObjectForIBinder);
    b->decStrong(obj);

    IPCThreadState::self()->flushCommands();
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gBinderProxyMethods[] = {
     /* name, signature, funcPtr */
    {"pingBinder",          "()Z", (void*)android_os_BinderProxy_pingBinder},
    {"isBinderAlive",       "()Z", (void*)android_os_BinderProxy_isBinderAlive},
    {"getInterfaceDescriptor", "()Ljava/lang/String;", (void*)android_os_BinderProxy_getInterfaceDescriptor},
    {"transact",            "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z", (void*)android_os_BinderProxy_transact},
    {"linkToDeath",         "(Landroid/os/IBinder$DeathRecipient;I)V", (void*)android_os_BinderProxy_linkToDeath},
    {"unlinkToDeath",       "(Landroid/os/IBinder$DeathRecipient;I)Z", (void*)android_os_BinderProxy_unlinkToDeath},
    {"destroy",             "()V", (void*)android_os_BinderProxy_destroy},
};

const char* const kBinderProxyPathName = "android/os/BinderProxy";

static int int_register_android_os_BinderProxy(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass("java/lang/ref/WeakReference");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class java.lang.ref.WeakReference");
    gWeakReferenceOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gWeakReferenceOffsets.mGet
        = env->GetMethodID(clazz, "get", "()Ljava/lang/Object;");
    assert(gWeakReferenceOffsets.mGet);

    clazz = env->FindClass("java/lang/Error");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class java.lang.Error");
    gErrorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);

    clazz = env->FindClass(kBinderProxyPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.BinderProxy");

    gBinderProxyOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gBinderProxyOffsets.mConstructor
        = env->GetMethodID(clazz, "<init>", "()V");
    assert(gBinderProxyOffsets.mConstructor);
    gBinderProxyOffsets.mSendDeathNotice
        = env->GetStaticMethodID(clazz, "sendDeathNotice", "(Landroid/os/IBinder$DeathRecipient;)V");
    assert(gBinderProxyOffsets.mSendDeathNotice);

    gBinderProxyOffsets.mObject
        = env->GetFieldID(clazz, "mObject", "I");
    assert(gBinderProxyOffsets.mObject);
    gBinderProxyOffsets.mSelf
        = env->GetFieldID(clazz, "mSelf", "Ljava/lang/ref/WeakReference;");
    assert(gBinderProxyOffsets.mSelf);
    gBinderProxyOffsets.mOrgue
        = env->GetFieldID(clazz, "mOrgue", "I");
    assert(gBinderProxyOffsets.mOrgue);

    clazz = env->FindClass("java/lang/Class");
    LOG_FATAL_IF(clazz == NULL, "Unable to find java.lang.Class");
    gClassOffsets.mGetName = env->GetMethodID(clazz, "getName", "()Ljava/lang/String;");
    assert(gClassOffsets.mGetName);

    return AndroidRuntime::registerNativeMethods(
        env, kBinderProxyPathName,
        gBinderProxyMethods, NELEM(gBinderProxyMethods));
}

// ****************************************************************************
// ****************************************************************************
// ****************************************************************************

int register_android_os_Binder(JNIEnv* env)
{
    if (int_register_android_os_Binder(env) < 0)
        return -1;
    if (int_register_android_os_BinderInternal(env) < 0)
        return -1;
    if (int_register_android_os_BinderProxy(env) < 0)
        return -1;

    jclass clazz;

    clazz = env->FindClass("android/util/Log");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.util.Log");
    gLogOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gLogOffsets.mLogE = env->GetStaticMethodID(
        clazz, "e", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I");
    assert(gLogOffsets.mLogE);

    clazz = env->FindClass("android/os/ParcelFileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gParcelFileDescriptorOffsets.mConstructor
        = env->GetMethodID(clazz, "<init>", "(Ljava/io/FileDescriptor;)V");

    clazz = env->FindClass("android/os/StrictMode");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.StrictMode");
    gStrictModeCallbackOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gStrictModeCallbackOffsets.mCallback = env->GetStaticMethodID(
        clazz, "onBinderStrictModePolicyChange", "(I)V");
    LOG_FATAL_IF(gStrictModeCallbackOffsets.mCallback == NULL,
                 "Unable to find strict mode callback.");

    return 0;
}
