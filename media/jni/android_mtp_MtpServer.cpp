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
    MtpStorage*     mStorage;
    Mutex           mMutex;
    bool            mUsePtp;
    int             mFd;

public:
    MtpThread(MtpDatabase* database, MtpStorage* storage)
        :   mDatabase(database),
            mServer(NULL),
            mStorage(storage),
            mFd(-1)
    {
    }

    virtual ~MtpThread() {
        delete mStorage;
    }

    void setPtpMode(bool usePtp) {
        mMutex.lock();
        mUsePtp = usePtp;
        mMutex.unlock();
    }

    virtual bool threadLoop() {
        mMutex.lock();
        mFd = open("/dev/mtp_usb", O_RDWR);
        if (mFd >= 0) {
            ioctl(mFd, MTP_SET_INTERFACE_MODE,
                    (mUsePtp ? MTP_INTERFACE_MODE_PTP : MTP_INTERFACE_MODE_MTP));

            mServer = new MtpServer(mFd, mDatabase, AID_MEDIA_RW, 0664, 0775);
            mServer->addStorage(mStorage);

            mMutex.unlock();
            mServer->run();
            mMutex.lock();

            close(mFd);
            mFd = -1;
            delete mServer;
            mServer = NULL;
        } else {
            LOGE("could not open MTP driver, errno: %d", errno);
        }
        mMutex.unlock();
        // delay a bit before retrying to avoid excessive spin
        if (!exitPending()) {
            sleep(1);
        }

        return true;
    }

    void sendObjectAdded(MtpObjectHandle handle) {
        mMutex.lock();
        if (mServer)
            mServer->sendObjectAdded(handle);
        mMutex.unlock();
    }

    void sendObjectRemoved(MtpObjectHandle handle) {
        mMutex.lock();
        if (mServer)
            mServer->sendObjectRemoved(handle);
        mMutex.unlock();
    }
};

// This smart pointer is necessary for preventing MtpThread from exiting too early
static sp<MtpThread> sThread;

#endif // HAVE_ANDROID_OS

static void
android_mtp_MtpServer_setup(JNIEnv *env, jobject thiz, jobject javaDatabase,
        jstring storagePath, jlong reserveSpace)
{
#ifdef HAVE_ANDROID_OS
    MtpDatabase* database = getMtpDatabase(env, javaDatabase);
    const char *storagePathStr = env->GetStringUTFChars(storagePath, NULL);

    // create the thread and assign it to the smart pointer
    MtpStorage* storage = new MtpStorage(MTP_FIRST_STORAGE_ID, storagePathStr, reserveSpace);
    sThread = new MtpThread(database, storage);

    env->ReleaseStringUTFChars(storagePath, storagePathStr);
#endif
}

static void
android_mtp_MtpServer_start(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
    MtpThread *thread = sThread.get();
    if (thread)
        thread->run("MtpThread");
#endif // HAVE_ANDROID_OS
}

static void
android_mtp_MtpServer_stop(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
    MtpThread *thread = sThread.get();
    if (thread) {
        thread->requestExitAndWait();
        sThread = NULL;
    }
#endif
}

static void
android_mtp_MtpServer_send_object_added(JNIEnv *env, jobject thiz, jint handle)
{
#ifdef HAVE_ANDROID_OS
    MtpThread *thread = sThread.get();
    if (thread)
        thread->sendObjectAdded(handle);
#endif
}

static void
android_mtp_MtpServer_send_object_removed(JNIEnv *env, jobject thiz, jint handle)
{
#ifdef HAVE_ANDROID_OS
    MtpThread *thread = sThread.get();
    if (thread)
        thread->sendObjectRemoved(handle);
#endif
}

static void
android_mtp_MtpServer_set_ptp_mode(JNIEnv *env, jobject thiz, jboolean usePtp)
{
#ifdef HAVE_ANDROID_OS
    MtpThread *thread = sThread.get();
    if (thread)
        thread->setPtpMode(usePtp);
#endif
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"native_setup",                "(Landroid/mtp/MtpDatabase;Ljava/lang/String;J)V",
                                            (void *)android_mtp_MtpServer_setup},
    {"native_start",                "()V",  (void *)android_mtp_MtpServer_start},
    {"native_stop",                 "()V",  (void *)android_mtp_MtpServer_stop},
    {"native_send_object_added",    "(I)V", (void *)android_mtp_MtpServer_send_object_added},
    {"native_send_object_removed",  "(I)V", (void *)android_mtp_MtpServer_send_object_removed},
    {"native_set_ptp_mode",         "(Z)V", (void *)android_mtp_MtpServer_set_ptp_mode},
};

static const char* const kClassPathName = "android/mtp/MtpServer";

int register_android_mtp_MtpServer(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/mtp/MtpServer");
    if (clazz == NULL) {
        LOGE("Can't find android/mtp/MtpServer");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
                "android/mtp/MtpServer", gMethods, NELEM(gMethods));
}
