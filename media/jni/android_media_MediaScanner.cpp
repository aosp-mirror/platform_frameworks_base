/* //device/libs/media_jni/MediaScanner.cpp
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "MediaScanner"
#include "utils/Log.h"

#include <media/mediascanner.h>
#include <stdio.h>
#include <assert.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>
#include <cutils/properties.h>
#include <utils/threads.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <media/stagefright/StagefrightMediaScanner.h>

// ----------------------------------------------------------------------------

using namespace android;

// ----------------------------------------------------------------------------

struct fields_t {
    jfieldID    context;
};
static fields_t fields;

// ----------------------------------------------------------------------------

class MyMediaScannerClient : public MediaScannerClient
{
public:
    MyMediaScannerClient(JNIEnv *env, jobject client)
        :   mEnv(env),
            mClient(env->NewGlobalRef(client)),
            mScanFileMethodID(0),
            mHandleStringTagMethodID(0),
            mSetMimeTypeMethodID(0)
    {
        jclass mediaScannerClientInterface = env->FindClass("android/media/MediaScannerClient");
        if (mediaScannerClientInterface == NULL) {
            fprintf(stderr, "android/media/MediaScannerClient not found\n");
        }
        else {
            mScanFileMethodID = env->GetMethodID(mediaScannerClientInterface, "scanFile",
                                                     "(Ljava/lang/String;JJ)V");
            mHandleStringTagMethodID = env->GetMethodID(mediaScannerClientInterface, "handleStringTag",
                                                     "(Ljava/lang/String;Ljava/lang/String;)V");
            mSetMimeTypeMethodID = env->GetMethodID(mediaScannerClientInterface, "setMimeType",
                                                     "(Ljava/lang/String;)V");
            mAddNoMediaFolderMethodID = env->GetMethodID(mediaScannerClientInterface, "addNoMediaFolder",
                                                     "(Ljava/lang/String;)V");
        }
    }
    
    virtual ~MyMediaScannerClient()
    {
        mEnv->DeleteGlobalRef(mClient);
    }
    
    // returns true if it succeeded, false if an exception occured in the Java code
    virtual bool scanFile(const char* path, long long lastModified, long long fileSize)
    {
        jstring pathStr;
        if ((pathStr = mEnv->NewStringUTF(path)) == NULL) return false;

        mEnv->CallVoidMethod(mClient, mScanFileMethodID, pathStr, lastModified, fileSize);

        mEnv->DeleteLocalRef(pathStr);
        return (!mEnv->ExceptionCheck());
    }

    // returns true if it succeeded, false if an exception occured in the Java code
    virtual bool handleStringTag(const char* name, const char* value)
    {
        jstring nameStr, valueStr;
        if ((nameStr = mEnv->NewStringUTF(name)) == NULL) return false;
        if ((valueStr = mEnv->NewStringUTF(value)) == NULL) return false;

        mEnv->CallVoidMethod(mClient, mHandleStringTagMethodID, nameStr, valueStr);

        mEnv->DeleteLocalRef(nameStr);
        mEnv->DeleteLocalRef(valueStr);
        return (!mEnv->ExceptionCheck());
    }

    // returns true if it succeeded, false if an exception occured in the Java code
    virtual bool setMimeType(const char* mimeType)
    {
        jstring mimeTypeStr;
        if ((mimeTypeStr = mEnv->NewStringUTF(mimeType)) == NULL) return false;

        mEnv->CallVoidMethod(mClient, mSetMimeTypeMethodID, mimeTypeStr);

        mEnv->DeleteLocalRef(mimeTypeStr);
        return (!mEnv->ExceptionCheck());
    }

    // returns true if it succeeded, false if an exception occured in the Java code
    virtual bool addNoMediaFolder(const char* path)
    {
        jstring pathStr;
        if ((pathStr = mEnv->NewStringUTF(path)) == NULL) return false;

        mEnv->CallVoidMethod(mClient, mAddNoMediaFolderMethodID, pathStr);

        mEnv->DeleteLocalRef(pathStr);
        return (!mEnv->ExceptionCheck());
    }


private:
    JNIEnv *mEnv;
    jobject mClient;
    jmethodID mScanFileMethodID; 
    jmethodID mHandleStringTagMethodID; 
    jmethodID mSetMimeTypeMethodID;
    jmethodID mAddNoMediaFolderMethodID;
};


// ----------------------------------------------------------------------------

static bool ExceptionCheck(void* env)
{
    return ((JNIEnv *)env)->ExceptionCheck();
}

static void
android_media_MediaScanner_processDirectory(JNIEnv *env, jobject thiz, jstring path, jstring extensions, jobject client)
{
    MediaScanner *mp = (MediaScanner *)env->GetIntField(thiz, fields.context);

    if (path == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    if (extensions == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    
    const char *pathStr = env->GetStringUTFChars(path, NULL);
    if (pathStr == NULL) {  // Out of memory
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }
    const char *extensionsStr = env->GetStringUTFChars(extensions, NULL);
    if (extensionsStr == NULL) {  // Out of memory
        env->ReleaseStringUTFChars(path, pathStr);
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }

    MyMediaScannerClient myClient(env, client);
    mp->processDirectory(pathStr, extensionsStr, myClient, ExceptionCheck, env);
    env->ReleaseStringUTFChars(path, pathStr);
    env->ReleaseStringUTFChars(extensions, extensionsStr);
}

static void
android_media_MediaScanner_processFile(JNIEnv *env, jobject thiz, jstring path, jstring mimeType, jobject client)
{
    MediaScanner *mp = (MediaScanner *)env->GetIntField(thiz, fields.context);

    if (path == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    
    const char *pathStr = env->GetStringUTFChars(path, NULL);
    if (pathStr == NULL) {  // Out of memory
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }
    const char *mimeTypeStr = (mimeType ? env->GetStringUTFChars(mimeType, NULL) : NULL);
    if (mimeType && mimeTypeStr == NULL) {  // Out of memory
        env->ReleaseStringUTFChars(path, pathStr);
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }

    MyMediaScannerClient myClient(env, client);
    mp->processFile(pathStr, mimeTypeStr, myClient);
    env->ReleaseStringUTFChars(path, pathStr);
    if (mimeType) {
        env->ReleaseStringUTFChars(mimeType, mimeTypeStr);
    }
}

static void
android_media_MediaScanner_setLocale(JNIEnv *env, jobject thiz, jstring locale)
{
    MediaScanner *mp = (MediaScanner *)env->GetIntField(thiz, fields.context);

    if (locale == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    const char *localeStr = env->GetStringUTFChars(locale, NULL);
    if (localeStr == NULL) {  // Out of memory
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }
    mp->setLocale(localeStr);

    env->ReleaseStringUTFChars(locale, localeStr);
}

static jbyteArray
android_media_MediaScanner_extractAlbumArt(JNIEnv *env, jobject thiz, jobject fileDescriptor)
{
    MediaScanner *mp = (MediaScanner *)env->GetIntField(thiz, fields.context);

    if (fileDescriptor == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }

    int fd = getParcelFileDescriptorFD(env, fileDescriptor);
    char* data = mp->extractAlbumArt(fd);
    if (!data) {
        return NULL;
    }
    long len = *((long*)data);
    
    jbyteArray array = env->NewByteArray(len);
    if (array != NULL) {
        jbyte* bytes = env->GetByteArrayElements(array, NULL);
        memcpy(bytes, data + 4, len);
        env->ReleaseByteArrayElements(array, bytes, 0);
    }
    
done:
    free(data);
    // if NewByteArray() returned NULL, an out-of-memory
    // exception will have been raised. I just want to
    // return null in that case.
    env->ExceptionClear();
    return array;
}

// This function gets a field ID, which in turn causes class initialization.
// It is called from a static block in MediaScanner, which won't run until the
// first time an instance of this class is used.
static void
android_media_MediaScanner_native_init(JNIEnv *env)
{
     jclass clazz;

    clazz = env->FindClass("android/media/MediaScanner");
    if (clazz == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find android/media/MediaScanner");
        return;
    }

    fields.context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (fields.context == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find MediaScanner.mNativeContext");
        return;
    }
}

static void
android_media_MediaScanner_native_setup(JNIEnv *env, jobject thiz)
{
    MediaScanner *mp = new StagefrightMediaScanner;

    if (mp == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }

    env->SetIntField(thiz, fields.context, (int)mp);
}

static void
android_media_MediaScanner_native_finalize(JNIEnv *env, jobject thiz)
{
    MediaScanner *mp = (MediaScanner *)env->GetIntField(thiz, fields.context);

    //printf("##### android_media_MediaScanner_native_finalize: ctx=0x%p\n", ctx);

    if (mp == 0)
        return;

    delete mp;
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"processDirectory",  "(Ljava/lang/String;Ljava/lang/String;Landroid/media/MediaScannerClient;)V",    
                                                        (void *)android_media_MediaScanner_processDirectory},
    {"processFile",       "(Ljava/lang/String;Ljava/lang/String;Landroid/media/MediaScannerClient;)V",    
                                                        (void *)android_media_MediaScanner_processFile},
    {"setLocale",         "(Ljava/lang/String;)V",      (void *)android_media_MediaScanner_setLocale},
    {"extractAlbumArt",   "(Ljava/io/FileDescriptor;)[B",     (void *)android_media_MediaScanner_extractAlbumArt},
    {"native_init",        "()V",                      (void *)android_media_MediaScanner_native_init},
    {"native_setup",        "()V",                      (void *)android_media_MediaScanner_native_setup},
    {"native_finalize",     "()V",                      (void *)android_media_MediaScanner_native_finalize},
};

static const char* const kClassPathName = "android/media/MediaScanner";

// This function only registers the native methods, and is called from
// JNI_OnLoad in android_media_MediaPlayer.cpp
int register_android_media_MediaScanner(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaScanner", gMethods, NELEM(gMethods));
}


