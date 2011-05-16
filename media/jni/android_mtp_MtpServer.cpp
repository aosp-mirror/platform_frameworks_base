/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "MtpServerJNI"
#include "utils/Log.h"

#include <stdio.h>
#include <assert.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <utils/threads.h>

#ifdef HAVE_ANDROID_OS
#include <linux/usb/f_mtp.h>
#endif

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "private/android_filesystem_config.h"

#include "MtpServer.h"
#include "MtpStorage.h"

using namespace android;

// MtpStorage class
jclass clazz_MtpStorage;

// MtpStorage fields
static jfieldID field_MtpStorage_storageId;
static jfieldID field_MtpStorage_path;
static jfieldID field_MtpStorage_description;
static jfieldID field_MtpStorage_reserveSpace;
static jfieldID field_MtpStorage_removable;

static Mutex sMutex;

// ----------------------------------------------------------------------------

// in android_mtp_MtpDatabase.cpp
extern MtpDatabase* getMtpDatabase(JNIEnv *env, jobject database);

// ----------------------------------------------------------------------------

#ifdef HAVE_ANDROID_OS

static bool ExceptionCheck(void* env)
{
    return ((JNIEnv *)env)->ExceptionCheck();
}

class MtpThread : public Thread {
private:
    MtpDatabase*    mDatabase;
    MtpServer*      mServer;
    MtpStorageList  mStorageList;
    bool            mUsePtp;
    int             mFd;

public:
    MtpThread(MtpDatabase* database)
        :   mDatabase(database),
            mServer(NULL),
            mUsePtp(false),
            mFd(-1)
    {
    }

    virtual ~MtpThread() {
    }

    void setPtpMode(bool usePtp) {
        mUsePtp = usePtp;
    }

    void addStorage(MtpStorage *storage) {
        mStorageList.push(storage);
        if (mServer)
            mServer->addStorage(storage);
    }

    void removeStorage(MtpStorageID id) {
        MtpStorage* storage = mServer->getStorage(id);
        if (storage) {
            for (size_t i = 0; i < mStorageList.size(); i++) {
                if (mStorageList[i] == storage) {
                    mStorageList.removeAt(i);
                    break;
                }
            }
            if (mServer)
                mServer->removeStorage(storage);
            delete storage;
        }
    }

    void start() {
        run("MtpThread");
    }

    virtual bool threadLoop() {
        sMutex.lock();

        mFd = open("/dev/mtp_usb", O_RDWR);
        if (mFd >= 0) {
            ioctl(mFd, MTP_SET_INTERFACE_MODE,
                    (mUsePtp ? MTP_INTERFACE_MODE_PTP : MTP_INTERFACE_MODE_MTP));

            mServer = new MtpServer(mFd, mDatabase, AID_MEDIA_RW, 0664, 0775);
            for (size_t i = 0; i < mStorageList.size(); i++) {
                mServer->addStorage(mStorageList[i]);
            }
        } else {
            LOGE("could not open MTP driver, errno: %d", errno);
        }

        sMutex.unlock();
        mServer->run();
        sMutex.lock();

        close(mFd);
        mFd = -1;
        delete mServer;
        mServer = NULL;

        sMutex.unlock();
        // delay a bit before retrying to avoid excessive spin
        if (!exitPending()) {
            sleep(1);
        }

        return true;
    }

    void sendObjectAdded(MtpObjectHandle handle) {
        if (mServer)
            mServer->sendObjectAdded(handle);
    }

    void sendObjectRemoved(MtpObjectHandle handle) {
        if (mServer)
            mServer->sendObjectRemoved(handle);
    }
};

// This smart pointer is necessary for preventing MtpThread from exiting too early
static sp<MtpThread> sThread;

#endif // HAVE_ANDROID_OS

static void
android_mtp_MtpServer_setup(JNIEnv *env, jobject thiz, jobject javaDatabase)
{
#ifdef HAVE_ANDROID_OS
    // create the thread and assign it to the smart pointer
    sThread = new MtpThread(getMtpDatabase(env, javaDatabase));
#endif
}

static void
android_mtp_MtpServer_start(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
   sMutex.lock();
    MtpThread *thread = sThread.get();
    if (thread)
        thread->start();
    sMutex.unlock();
#endif // HAVE_ANDROID_OS
}

static void
android_mtp_MtpServer_stop(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
    sMutex.lock();
    MtpThread *thread = sThread.get();
    if (thread) {
        thread->requestExitAndWait();
        sThread = NULL;
    }
    sMutex.unlock();
#endif
}

static void
android_mtp_MtpServer_send_object_added(JNIEnv *env, jobject thiz, jint handle)
{
#ifdef HAVE_ANDROID_OS
    sMutex.lock();
    MtpThread *thread = sThread.get();
    if (thread)
        thread->sendObjectAdded(handle);
    sMutex.unlock();
#endif
}

static void
android_mtp_MtpServer_send_object_removed(JNIEnv *env, jobject thiz, jint handle)
{
#ifdef HAVE_ANDROID_OS
    sMutex.lock();
    MtpThread *thread = sThread.get();
    if (thread)
        thread->sendObjectRemoved(handle);
    sMutex.unlock();
#endif
}

static void
android_mtp_MtpServer_set_ptp_mode(JNIEnv *env, jobject thiz, jboolean usePtp)
{
#ifdef HAVE_ANDROID_OS
    sMutex.lock();
    MtpThread *thread = sThread.get();
    if (thread)
        thread->setPtpMode(usePtp);
    sMutex.unlock();
#endif
}

static void
android_mtp_MtpServer_add_storage(JNIEnv *env, jobject thiz, jobject jstorage)
{
#ifdef HAVE_ANDROID_OS
    sMutex.lock();
    MtpThread *thread = sThread.get();
    if (thread) {
        jint storageID = env->GetIntField(jstorage, field_MtpStorage_storageId);
        jstring path = (jstring)env->GetObjectField(jstorage, field_MtpStorage_path);
        jstring description = (jstring)env->GetObjectField(jstorage, field_MtpStorage_description);
        jlong reserveSpace = env->GetLongField(jstorage, field_MtpStorage_reserveSpace);
        jboolean removable = env->GetBooleanField(jstorage, field_MtpStorage_removable);

        const char *pathStr = env->GetStringUTFChars(path, NULL);
        if (pathStr != NULL) {
            const char *descriptionStr = env->GetStringUTFChars(description, NULL);
            if (descriptionStr != NULL) {
                MtpStorage* storage = new MtpStorage(storageID, pathStr, descriptionStr, reserveSpace, removable);
                thread->addStorage(storage);
                env->ReleaseStringUTFChars(path, pathStr);
                env->ReleaseStringUTFChars(description, descriptionStr);
            } else {
                env->ReleaseStringUTFChars(path, pathStr);
            }
        }
    } else {
        LOGE("MtpThread is null in add_storage");
    }
    sMutex.unlock();
#endif
}

static void
android_mtp_MtpServer_remove_storage(JNIEnv *env, jobject thiz, jint storageId)
{
#ifdef HAVE_ANDROID_OS
    sMutex.lock();
    MtpThread *thread = sThread.get();
    if (thread)
        thread->removeStorage(storageId);
    else
        LOGE("MtpThread is null in remove_storage");
    sMutex.unlock();
#endif
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"native_setup",                "(Landroid/mtp/MtpDatabase;)V",
                                            (void *)android_mtp_MtpServer_setup},
    {"native_start",                "()V",  (void *)android_mtp_MtpServer_start},
    {"native_stop",                 "()V",  (void *)android_mtp_MtpServer_stop},
    {"native_send_object_added",    "(I)V", (void *)android_mtp_MtpServer_send_object_added},
    {"native_send_object_removed",  "(I)V", (void *)android_mtp_MtpServer_send_object_removed},
    {"native_set_ptp_mode",         "(Z)V", (void *)android_mtp_MtpServer_set_ptp_mode},
    {"native_add_storage",          "(Landroid/mtp/MtpStorage;)V",
                                            (void *)android_mtp_MtpServer_add_storage},
    {"native_remove_storage",       "(I)V", (void *)android_mtp_MtpServer_remove_storage},
};

static const char* const kClassPathName = "android/mtp/MtpServer";

int register_android_mtp_MtpServer(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/mtp/MtpStorage");
    if (clazz == NULL) {
        LOGE("Can't find android/mtp/MtpStorage");
        return -1;
    }
    field_MtpStorage_storageId = env->GetFieldID(clazz, "mStorageId", "I");
    if (field_MtpStorage_storageId == NULL) {
        LOGE("Can't find MtpStorage.mStorageId");
        return -1;
    }
    field_MtpStorage_path = env->GetFieldID(clazz, "mPath", "Ljava/lang/String;");
    if (field_MtpStorage_path == NULL) {
        LOGE("Can't find MtpStorage.mPath");
        return -1;
    }
    field_MtpStorage_description = env->GetFieldID(clazz, "mDescription", "Ljava/lang/String;");
    if (field_MtpStorage_description == NULL) {
        LOGE("Can't find MtpStorage.mDescription");
        return -1;
    }
    field_MtpStorage_reserveSpace = env->GetFieldID(clazz, "mReserveSpace", "J");
    if (field_MtpStorage_reserveSpace == NULL) {
        LOGE("Can't find MtpStorage.mReserveSpace");
        return -1;
    }
    field_MtpStorage_removable = env->GetFieldID(clazz, "mRemovable", "Z");
    if (field_MtpStorage_removable == NULL) {
        LOGE("Can't find MtpStorage.mRemovable");
        return -1;
    }
    clazz_MtpStorage = (jclass)env->NewGlobalRef(clazz);

    clazz = env->FindClass("android/mtp/MtpServer");
    if (clazz == NULL) {
        LOGE("Can't find android/mtp/MtpServer");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
                "android/mtp/MtpServer", gMethods, NELEM(gMethods));
}
