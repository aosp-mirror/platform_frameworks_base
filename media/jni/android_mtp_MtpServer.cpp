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

using namespace android;

// ----------------------------------------------------------------------------

static jfieldID field_context;
static Mutex    sMutex;

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
    String8         mStoragePath;
    uint64_t        mReserveSpace;
    jobject         mJavaServer;
    int             mFd;

public:
    MtpThread(MtpDatabase* database, const char* storagePath, uint64_t reserveSpace,
                jobject javaServer)
        :   mDatabase(database),
            mServer(NULL),
            mStoragePath(storagePath),
            mReserveSpace(reserveSpace),
            mJavaServer(javaServer),
            mFd(-1)
    {
    }

    void setPtpMode(bool usePtp) {
        sMutex.lock();
        if (mFd >= 0) {
            ioctl(mFd, MTP_SET_INTERFACE_MODE,
                    (usePtp ? MTP_INTERFACE_MODE_PTP : MTP_INTERFACE_MODE_MTP));
        } else {
            int fd = open("/dev/mtp_usb", O_RDWR);
            if (fd >= 0) {
                ioctl(fd, MTP_SET_INTERFACE_MODE,
                        (usePtp ? MTP_INTERFACE_MODE_PTP : MTP_INTERFACE_MODE_MTP));
                close(fd);
            }
        }
        sMutex.unlock();
    }

    virtual bool threadLoop() {
        sMutex.lock();
        mFd = open("/dev/mtp_usb", O_RDWR);
        printf("open returned %d\n", mFd);
        if (mFd < 0) {
            LOGE("could not open MTP driver\n");
            sMutex.unlock();
            return false;
        }

        mServer = new MtpServer(mFd, mDatabase, AID_MEDIA_RW, 0664, 0775);
        mServer->addStorage(mStoragePath, mReserveSpace);
        sMutex.unlock();

        LOGD("MtpThread mServer->run");
        mServer->run();

        sMutex.lock();
        close(mFd);
        mFd = -1;
        delete mServer;
        mServer = NULL;

        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->SetIntField(mJavaServer, field_context, 0);
        env->DeleteGlobalRef(mJavaServer);
        sMutex.unlock();

        LOGD("threadLoop returning");
        return false;
    }

    void sendObjectAdded(MtpObjectHandle handle) {
        sMutex.lock();
        if (mServer)
            mServer->sendObjectAdded(handle);
        sMutex.unlock();
    }

    void sendObjectRemoved(MtpObjectHandle handle) {
        sMutex.lock();
        if (mServer)
            mServer->sendObjectRemoved(handle);
        sMutex.unlock();
    }
};

#endif // HAVE_ANDROID_OS

static void
android_mtp_MtpServer_setup(JNIEnv *env, jobject thiz, jobject javaDatabase,
        jstring storagePath, jlong reserveSpace)
{
#ifdef HAVE_ANDROID_OS
    LOGD("setup\n");

    MtpDatabase* database = getMtpDatabase(env, javaDatabase);
    const char *storagePathStr = env->GetStringUTFChars(storagePath, NULL);

    MtpThread* thread = new MtpThread(database, storagePathStr,
            reserveSpace, env->NewGlobalRef(thiz));
    env->SetIntField(thiz, field_context, (int)thread);

    env->ReleaseStringUTFChars(storagePath, storagePathStr);
#endif
}

static void
android_mtp_MtpServer_finalize(JNIEnv *env, jobject thiz)
{
    LOGD("finalize\n");
}


static void
android_mtp_MtpServer_start(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
    LOGD("start\n");
    MtpThread *thread = (MtpThread *)env->GetIntField(thiz, field_context);
    thread->run("MtpThread");
#endif // HAVE_ANDROID_OS
}

static void
android_mtp_MtpServer_stop(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
    LOGD("stop\n");
#endif
}

static void
android_mtp_MtpServer_send_object_added(JNIEnv *env, jobject thiz, jint handle)
{
#ifdef HAVE_ANDROID_OS
    MtpThread *thread = (MtpThread *)env->GetIntField(thiz, field_context);
    if (thread)
        thread->sendObjectAdded(handle);
#endif
}

static void
android_mtp_MtpServer_send_object_removed(JNIEnv *env, jobject thiz, jint handle)
{
#ifdef HAVE_ANDROID_OS
    MtpThread *thread = (MtpThread *)env->GetIntField(thiz, field_context);
    if (thread)
        thread->sendObjectRemoved(handle);
#endif
}

static void
android_mtp_MtpServer_set_ptp_mode(JNIEnv *env, jobject thiz, jboolean usePtp)
{
#ifdef HAVE_ANDROID_OS
    LOGD("set_ptp_mode\n");
    MtpThread *thread = (MtpThread *)env->GetIntField(thiz, field_context);
    if (thread)
        thread->setPtpMode(usePtp);
 #endif
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"native_setup",                "(Landroid/mtp/MtpDatabase;Ljava/lang/String;J)V",
                                            (void *)android_mtp_MtpServer_setup},
    {"native_finalize",             "()V",  (void *)android_mtp_MtpServer_finalize},
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

    LOGD("register_android_mtp_MtpServer\n");

    clazz = env->FindClass("android/mtp/MtpServer");
    if (clazz == NULL) {
        LOGE("Can't find android/mtp/MtpServer");
        return -1;
    }
    field_context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (field_context == NULL) {
        LOGE("Can't find MtpServer.mNativeContext");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
                "android/mtp/MtpServer", gMethods, NELEM(gMethods));
}
