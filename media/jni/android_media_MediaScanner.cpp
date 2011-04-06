/*
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

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaScannerJNI"
#include <utils/Log.h>
#include <utils/threads.h>
#include <media/mediascanner.h>
#include <media/stagefright/StagefrightMediaScanner.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

using namespace android;


static const char* const kClassMediaScannerClient =
        "android/media/MediaScannerClient";

static const char* const kClassMediaScanner =
        "android/media/MediaScanner";

static const char* const kRunTimeException =
        "java/lang/RuntimeException";

static const char* const kIllegalArgumentException =
        "java/lang/IllegalArgumentException";

struct fields_t {
    jfieldID    context;
};
static fields_t fields;
static Mutex sLock;

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
        LOGV("MyMediaScannerClient constructor");
        jclass mediaScannerClientInterface =
                env->FindClass(kClassMediaScannerClient);

        if (mediaScannerClientInterface == NULL) {
            LOGE("Class %s not found", kClassMediaScannerClient);
        } else {
            mScanFileMethodID = env->GetMethodID(
                                    mediaScannerClientInterface,
                                    "scanFile",
                                    "(Ljava/lang/String;JJZ)V");

            mHandleStringTagMethodID = env->GetMethodID(
                                    mediaScannerClientInterface,
                                    "handleStringTag",
                                    "(Ljava/lang/String;Ljava/lang/String;)V");

            mSetMimeTypeMethodID = env->GetMethodID(
                                    mediaScannerClientInterface,
                                    "setMimeType",
                                    "(Ljava/lang/String;)V");

            mAddNoMediaFolderMethodID = env->GetMethodID(
                                    mediaScannerClientInterface,
                                    "addNoMediaFolder",
                                    "(Ljava/lang/String;)V");
        }
    }

    virtual ~MyMediaScannerClient()
    {
        LOGV("MyMediaScannerClient destructor");
        mEnv->DeleteGlobalRef(mClient);
    }

    // Returns true if it succeeded, false if an exception occured
    // in the Java code
    virtual bool scanFile(const char* path, long long lastModified,
            long long fileSize, bool isDirectory)
    {
        LOGV("scanFile: path(%s), time(%lld), size(%lld) and isDir(%d)",
            path, lastModified, fileSize, isDirectory);

        jstring pathStr;
        if ((pathStr = mEnv->NewStringUTF(path)) == NULL) {
            return false;
        }

        mEnv->CallVoidMethod(mClient, mScanFileMethodID, pathStr, lastModified,
                fileSize, isDirectory);

        mEnv->DeleteLocalRef(pathStr);
        return (!mEnv->ExceptionCheck());
    }

    // Returns true if it succeeded, false if an exception occured
    // in the Java code
    virtual bool handleStringTag(const char* name, const char* value)
    {
        LOGV("handleStringTag: name(%s) and value(%s)", name, value);
        jstring nameStr, valueStr;
        if ((nameStr = mEnv->NewStringUTF(name)) == NULL) {
            return false;
        }
        if ((valueStr = mEnv->NewStringUTF(value)) == NULL) {
            return false;
        }

        mEnv->CallVoidMethod(
            mClient, mHandleStringTagMethodID, nameStr, valueStr);

        mEnv->DeleteLocalRef(nameStr);
        mEnv->DeleteLocalRef(valueStr);
        return (!mEnv->ExceptionCheck());
    }

    // Returns true if it succeeded, false if an exception occured
    // in the Java code
    virtual bool setMimeType(const char* mimeType)
    {
        LOGV("setMimeType: %s", mimeType);
        jstring mimeTypeStr;
        if ((mimeTypeStr = mEnv->NewStringUTF(mimeType)) == NULL) {
            return false;
        }

        mEnv->CallVoidMethod(mClient, mSetMimeTypeMethodID, mimeTypeStr);

        mEnv->DeleteLocalRef(mimeTypeStr);
        return (!mEnv->ExceptionCheck());
    }

    // Returns true if it succeeded, false if an exception occured
    // in the Java code
    virtual bool addNoMediaFolder(const char* path)
    {
        LOGV("addNoMediaFolder: path(%s)", path);
        jstring pathStr;
        if ((pathStr = mEnv->NewStringUTF(path)) == NULL) {
            return false;
        }

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


static bool ExceptionCheck(void* env)
{
    LOGV("ExceptionCheck");
    return ((JNIEnv *)env)->ExceptionCheck();
}

// Call this method with sLock hold
static MediaScanner *getNativeScanner_l(JNIEnv* env, jobject thiz)
{
    return (MediaScanner *) env->GetIntField(thiz, fields.context);
}

// Call this method with sLock hold
static void setNativeScanner_l(JNIEnv* env, jobject thiz, MediaScanner *s)
{
    env->SetIntField(thiz, fields.context, (int)s);
}

static void
android_media_MediaScanner_processDirectory(
        JNIEnv *env, jobject thiz, jstring path, jobject client)
{
    LOGV("processDirectory");
    Mutex::Autolock l(sLock);
    MediaScanner *mp = getNativeScanner_l(env, thiz);
    if (mp == NULL) {
        jniThrowException(env, kRunTimeException, "No scanner available");
        return;
    }

    if (path == NULL) {
        jniThrowException(env, kIllegalArgumentException, NULL);
        return;
    }

    const char *pathStr = env->GetStringUTFChars(path, NULL);
    if (pathStr == NULL) {  // Out of memory
        return;
    }

    MyMediaScannerClient myClient(env, client);
    mp->processDirectory(pathStr, myClient, ExceptionCheck, env);
    env->ReleaseStringUTFChars(path, pathStr);
}

static void
android_media_MediaScanner_processFile(
        JNIEnv *env, jobject thiz, jstring path,
        jstring mimeType, jobject client)
{
    LOGV("processFile");

    // Lock already hold by processDirectory
    MediaScanner *mp = getNativeScanner_l(env, thiz);
    if (mp == NULL) {
        jniThrowException(env, kRunTimeException, "No scanner available");
        return;
    }

    if (path == NULL) {
        jniThrowException(env, kIllegalArgumentException, NULL);
        return;
    }

    const char *pathStr = env->GetStringUTFChars(path, NULL);
    if (pathStr == NULL) {  // Out of memory
        return;
    }

    const char *mimeTypeStr =
        (mimeType ? env->GetStringUTFChars(mimeType, NULL) : NULL);
    if (mimeType && mimeTypeStr == NULL) {  // Out of memory
        // ReleaseStringUTFChars can be called with an exception pending.
        env->ReleaseStringUTFChars(path, pathStr);
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
android_media_MediaScanner_setLocale(
        JNIEnv *env, jobject thiz, jstring locale)
{
    LOGV("setLocale");
    Mutex::Autolock l(sLock);
    MediaScanner *mp = getNativeScanner_l(env, thiz);
    if (mp == NULL) {
        jniThrowException(env, kRunTimeException, "No scanner available");
        return;
    }

    if (locale == NULL) {
        jniThrowException(env, kIllegalArgumentException, NULL);
        return;
    }
    const char *localeStr = env->GetStringUTFChars(locale, NULL);
    if (localeStr == NULL) {  // Out of memory
        return;
    }
    mp->setLocale(localeStr);

    env->ReleaseStringUTFChars(locale, localeStr);
}

static jbyteArray
android_media_MediaScanner_extractAlbumArt(
        JNIEnv *env, jobject thiz, jobject fileDescriptor)
{
    LOGV("extractAlbumArt");
    Mutex::Autolock l(sLock);
    MediaScanner *mp = getNativeScanner_l(env, thiz);
    if (mp == NULL) {
        jniThrowException(env, kRunTimeException, "No scanner available");
        return NULL;
    }

    if (fileDescriptor == NULL) {
        jniThrowException(env, kIllegalArgumentException, NULL);
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
    LOGV("native_init");
    jclass clazz = env->FindClass(kClassMediaScanner);
    if (clazz == NULL) {
        const char* err = "Can't find android/media/MediaScanner";
        jniThrowException(env, kRunTimeException, err);
        return;
    }

    fields.context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (fields.context == NULL) {
        const char* err = "Can't find MediaScanner.mNativeContext";
        jniThrowException(env, kRunTimeException, err);
        return;
    }
}

static void
android_media_MediaScanner_native_setup(JNIEnv *env, jobject thiz)
{
    LOGV("native_setup");
    MediaScanner *mp = new StagefrightMediaScanner;

    if (mp == NULL) {
        jniThrowException(env, kRunTimeException, "Out of memory");
        return;
    }

    env->SetIntField(thiz, fields.context, (int)mp);
}

static void
android_media_MediaScanner_native_finalize(JNIEnv *env, jobject thiz)
{
    LOGV("native_finalize");
    Mutex::Autolock l(sLock);
    MediaScanner *mp = getNativeScanner_l(env, thiz);
    if (mp == 0) {
        return;
    }
    delete mp;
    setNativeScanner_l(env, thiz, 0);
}

static JNINativeMethod gMethods[] = {
    {
        "processDirectory",
        "(Ljava/lang/String;Landroid/media/MediaScannerClient;)V",
        (void *)android_media_MediaScanner_processDirectory
    },

    {
        "processFile",
        "(Ljava/lang/String;Ljava/lang/String;Landroid/media/MediaScannerClient;)V",
        (void *)android_media_MediaScanner_processFile
    },

    {
        "setLocale",
        "(Ljava/lang/String;)V",
        (void *)android_media_MediaScanner_setLocale
    },

    {
        "extractAlbumArt",
        "(Ljava/io/FileDescriptor;)[B",
        (void *)android_media_MediaScanner_extractAlbumArt
    },

    {
        "native_init",
        "()V",
        (void *)android_media_MediaScanner_native_init
    },

    {
        "native_setup",
        "()V",
        (void *)android_media_MediaScanner_native_setup
    },

    {
        "native_finalize",
        "()V",
        (void *)android_media_MediaScanner_native_finalize
    },
};

// This function only registers the native methods, and is called from
// JNI_OnLoad in android_media_MediaPlayer.cpp
int register_android_media_MediaScanner(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env,
                kClassMediaScanner, gMethods, NELEM(gMethods));
}


