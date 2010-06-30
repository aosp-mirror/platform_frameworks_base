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
#include <utils/threads.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include "MtpServer.h"

using namespace android;

// ----------------------------------------------------------------------------

static jfieldID field_context;


// ----------------------------------------------------------------------------

static bool ExceptionCheck(void* env)
{
    return ((JNIEnv *)env)->ExceptionCheck();
}

class MtpThread : public Thread {
private:
    String8 mStoragePath;
    String8 mDatabasePath;
    bool mDone;
    bool mScannedOnce;

public:
    MtpThread(const char* storagePath, const char* databasePath)
        : mStoragePath(storagePath), mDatabasePath(databasePath), mDone(false), mScannedOnce(false)
    {
    }

    virtual bool threadLoop() {
        int fd = open("/dev/mtp_usb", O_RDWR);
        printf("open returned %d\n", fd);
        if (fd < 0) {
            LOGE("could not open MTP driver\n");
            return false;
        }

        MtpServer* server = new MtpServer(fd, mDatabasePath);
        server->addStorage(mStoragePath);

        // temporary
        LOGD("MtpThread server->scanStorage");
        server->scanStorage();
        LOGD("MtpThread server->run");
        server->run();
        close(fd);
        delete server;

        bool done = mDone;
        if (done)
            delete this;
        LOGD("threadLoop returning %s", (done ? "false" : "true"));
        return !done;
    }

    void setDone() { mDone = true; }
};

static void
android_media_MtpServer_setup(JNIEnv *env, jobject thiz, jstring storagePath, jstring databasePath)
{
    LOGD("setup\n");

    const char *storagePathStr = env->GetStringUTFChars(storagePath, NULL);
    const char *databasePathStr = env->GetStringUTFChars(databasePath, NULL);

    MtpThread* thread = new MtpThread(storagePathStr, databasePathStr);
    env->SetIntField(thiz, field_context, (int)thread);

    env->ReleaseStringUTFChars(storagePath, storagePathStr);
    env->ReleaseStringUTFChars(databasePath, databasePathStr);
}

static void
android_media_MtpServer_finalize(JNIEnv *env, jobject thiz)
{
    LOGD("finalize\n");
}


static void
android_media_MtpServer_start(JNIEnv *env, jobject thiz)
{
    LOGD("start\n");
    MtpThread *thread = (MtpThread *)env->GetIntField(thiz, field_context);
    thread->run("MtpThread");
}

static void
android_media_MtpServer_stop(JNIEnv *env, jobject thiz)
{
    LOGD("stop\n");
    MtpThread *thread = (MtpThread *)env->GetIntField(thiz, field_context);
    if (thread) {
        thread->setDone();
        env->SetIntField(thiz, field_context, 0);
    }
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"native_setup",            "(Ljava/lang/String;Ljava/lang/String;)V",  (void *)android_media_MtpServer_setup},
    {"native_finalize",         "()V",  (void *)android_media_MtpServer_finalize},
    {"native_start",            "()V",  (void *)android_media_MtpServer_start},
    {"native_stop",             "()V",  (void *)android_media_MtpServer_stop},
};

static const char* const kClassPathName = "android/media/MtpServer";

int register_android_media_MtpServer(JNIEnv *env)
{
    jclass clazz;

    LOGD("register_android_media_MtpServer\n");

    clazz = env->FindClass("android/media/MtpServer");
    if (clazz == NULL) {
        LOGE("Can't find android/media/MtpServer");
        return -1;
    }
    field_context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (field_context == NULL) {
        LOGE("Can't find MtpServer.mNativeContext");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MtpServer", gMethods, NELEM(gMethods));
}
