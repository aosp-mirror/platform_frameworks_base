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

//#define LOG_NDEBUG 0
#define LOG_TAG "JET_JNI"


#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include "utils/Log.h"
#include "media/JetPlayer.h"


using namespace android;

// ----------------------------------------------------------------------------
static const char* const kClassPathName = "android/media/JetPlayer";

// ----------------------------------------------------------------------------
struct fields_t {
    // these fields provide access from C++ to the...
    jclass    jetClass;              // JetPlayer java class global ref
    jmethodID postNativeEventInJava; // java method to post events to the Java thread from native
    jfieldID  nativePlayerInJavaObj; // stores in Java the native JetPlayer object
};

static fields_t javaJetPlayerFields;


// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------

/*
 * This function is called from JetPlayer instance's render thread
 */
static void
jetPlayerEventCallback(int what, int arg1=0, int arg2=0, void* javaTarget = NULL)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if(env) {
        env->CallStaticVoidMethod(
            javaJetPlayerFields.jetClass, javaJetPlayerFields.postNativeEventInJava,
            javaTarget,
            what, arg1, arg2);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    } else {
        ALOGE("JET jetPlayerEventCallback(): No JNI env for JET event callback, can't post event.");
        return;
    }
}


// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------

static jboolean
android_media_JetPlayer_setup(JNIEnv *env, jobject thiz, jobject weak_this,
    jint maxTracks, jint trackBufferSize)
{
    //ALOGV("android_media_JetPlayer_setup(): entering.");
    JetPlayer* lpJet = new JetPlayer(env->NewGlobalRef(weak_this), maxTracks, trackBufferSize);

    EAS_RESULT result = lpJet->init();

    if(result==EAS_SUCCESS) {
        // save our newly created C++ JetPlayer in the "nativePlayerInJavaObj" field
        // of the Java object (in mNativePlayerInJavaObj)
        env->SetIntField(thiz, javaJetPlayerFields.nativePlayerInJavaObj, (int)lpJet);
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_setup(): initialization failed with EAS error code %d", (int)result);
        delete lpJet;
        env->SetIntField(weak_this, javaJetPlayerFields.nativePlayerInJavaObj, 0);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static void
android_media_JetPlayer_finalize(JNIEnv *env, jobject thiz)
{
    ALOGV("android_media_JetPlayer_finalize(): entering.");
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if(lpJet != NULL) {
        lpJet->release();
        delete lpJet;
    }

    ALOGV("android_media_JetPlayer_finalize(): exiting.");
}


// ----------------------------------------------------------------------------
static void
android_media_JetPlayer_release(JNIEnv *env, jobject thiz)
{
    android_media_JetPlayer_finalize(env, thiz);
    env->SetIntField(thiz, javaJetPlayerFields.nativePlayerInJavaObj, 0);
    ALOGV("android_media_JetPlayer_release() done");
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_loadFromFile(JNIEnv *env, jobject thiz, jstring path)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for openFile()");
    }

    // set up event callback function
    lpJet->setEventCallback(jetPlayerEventCallback);

    const char *pathStr = env->GetStringUTFChars(path, NULL);
    if (pathStr == NULL) {  // Out of memory
        ALOGE("android_media_JetPlayer_openFile(): aborting, out of memory");
        return JNI_FALSE;
    }

    ALOGV("android_media_JetPlayer_openFile(): trying to open %s", pathStr );
    EAS_RESULT result = lpJet->loadFromFile(pathStr);
    env->ReleaseStringUTFChars(path, pathStr);

    if(result==EAS_SUCCESS) {
        //ALOGV("android_media_JetPlayer_openFile(): file successfully opened");
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_openFile(): failed to open file with EAS error %d",
            (int)result);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_loadFromFileD(JNIEnv *env, jobject thiz,
    jobject fileDescriptor, jlong offset, jlong length)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for openFile()");
    }

    // set up event callback function
    lpJet->setEventCallback(jetPlayerEventCallback);

    ALOGV("android_media_JetPlayer_openFileDescr(): trying to load JET file through its fd" );
    EAS_RESULT result = lpJet->loadFromFD(jniGetFDFromFileDescriptor(env, fileDescriptor),
        (long long)offset, (long long)length); // cast params to types used by EAS_FILE

    if(result==EAS_SUCCESS) {
        ALOGV("android_media_JetPlayer_openFileDescr(): file successfully opened");
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_openFileDescr(): failed to open file with EAS error %d",
            (int)result);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_closeFile(JNIEnv *env, jobject thiz)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for closeFile()");
    }

    if( lpJet->closeFile()==EAS_SUCCESS) {
        //ALOGV("android_media_JetPlayer_closeFile(): file successfully closed");
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_closeFile(): failed to close file");
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_play(JNIEnv *env, jobject thiz)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for play()");
    }

    EAS_RESULT result = lpJet->play();
    if( result==EAS_SUCCESS) {
        //ALOGV("android_media_JetPlayer_play(): play successful");
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_play(): failed to play with EAS error code %ld",
            result);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_pause(JNIEnv *env, jobject thiz)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for pause()");
    }

    EAS_RESULT result = lpJet->pause();
    if( result==EAS_SUCCESS) {
        //ALOGV("android_media_JetPlayer_pause(): pause successful");
        return JNI_TRUE;
    } else {
        if(result==EAS_ERROR_QUEUE_IS_EMPTY) {
            ALOGV("android_media_JetPlayer_pause(): paused with an empty queue");
            return JNI_TRUE;
        } else
            ALOGE("android_media_JetPlayer_pause(): failed to pause with EAS error code %ld",
                result);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_queueSegment(JNIEnv *env, jobject thiz,
        jint segmentNum, jint libNum, jint repeatCount, jint transpose, jint muteFlags,
        jbyte userID)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for queueSegment()");
    }

    EAS_RESULT result
        = lpJet->queueSegment(segmentNum, libNum, repeatCount, transpose, muteFlags, userID);
    if(result==EAS_SUCCESS) {
        //ALOGV("android_media_JetPlayer_queueSegment(): segment successfully queued");
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_queueSegment(): failed with EAS error code %ld",
            result);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_queueSegmentMuteArray(JNIEnv *env, jobject thiz,
        jint segmentNum, jint libNum, jint repeatCount, jint transpose, jbooleanArray muteArray,
        jbyte userID)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for queueSegmentMuteArray()");
    }

    EAS_RESULT result=EAS_FAILURE;

    jboolean *muteTracks = NULL;
    muteTracks = env->GetBooleanArrayElements(muteArray, NULL);
    if (muteTracks == NULL) {
        ALOGE("android_media_JetPlayer_queueSegment(): failed to read track mute mask.");
        return JNI_FALSE;
    }

    EAS_U32 muteMask=0;
    int maxTracks = lpJet->getMaxTracks();
    for (jint trackIndex=0; trackIndex<maxTracks; trackIndex++) {
        if(muteTracks[maxTracks-1-trackIndex]==JNI_TRUE)
            muteMask = (muteMask << 1) | 0x00000001;
        else
            muteMask = muteMask << 1;
    }
    //ALOGV("android_media_JetPlayer_queueSegmentMuteArray(): FINAL mute mask =0x%08lX", mask);

    result = lpJet->queueSegment(segmentNum, libNum, repeatCount, transpose, muteMask, userID);

    env->ReleaseBooleanArrayElements(muteArray, muteTracks, 0);
    if(result==EAS_SUCCESS) {
        //ALOGV("android_media_JetPlayer_queueSegmentMuteArray(): segment successfully queued");
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_queueSegmentMuteArray(): failed with EAS error code %ld",
            result);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_setMuteFlags(JNIEnv *env, jobject thiz,
         jint muteFlags /*unsigned?*/, jboolean bSync)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for setMuteFlags()");
    }

    EAS_RESULT result;
    result = lpJet->setMuteFlags(muteFlags, bSync==JNI_TRUE ? true : false);
    if(result==EAS_SUCCESS) {
        //ALOGV("android_media_JetPlayer_setMuteFlags(): mute flags successfully updated");
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_setMuteFlags(): failed with EAS error code %ld", result);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_setMuteArray(JNIEnv *env, jobject thiz,
        jbooleanArray muteArray, jboolean bSync)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for setMuteArray()");
    }

    EAS_RESULT result=EAS_FAILURE;

    jboolean *muteTracks = NULL;
    muteTracks = env->GetBooleanArrayElements(muteArray, NULL);
    if (muteTracks == NULL) {
        ALOGE("android_media_JetPlayer_setMuteArray(): failed to read track mute mask.");
        return JNI_FALSE;
    }

    EAS_U32 muteMask=0;
    int maxTracks = lpJet->getMaxTracks();
    for (jint trackIndex=0; trackIndex<maxTracks; trackIndex++) {
        if(muteTracks[maxTracks-1-trackIndex]==JNI_TRUE)
            muteMask = (muteMask << 1) | 0x00000001;
        else
            muteMask = muteMask << 1;
    }
    //ALOGV("android_media_JetPlayer_setMuteArray(): FINAL mute mask =0x%08lX", muteMask);

    result = lpJet->setMuteFlags(muteMask, bSync==JNI_TRUE ? true : false);

    env->ReleaseBooleanArrayElements(muteArray, muteTracks, 0);
    if(result==EAS_SUCCESS) {
        //ALOGV("android_media_JetPlayer_setMuteArray(): mute flags successfully updated");
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_setMuteArray(): \
            failed to update mute flags with EAS error code %ld", result);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_setMuteFlag(JNIEnv *env, jobject thiz,
         jint trackId, jboolean muteFlag, jboolean bSync)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for setMuteFlag()");
    }

    EAS_RESULT result;
    result = lpJet->setMuteFlag(trackId,
        muteFlag==JNI_TRUE ? true : false, bSync==JNI_TRUE ? true : false);
    if(result==EAS_SUCCESS) {
        //ALOGV("android_media_JetPlayer_setMuteFlag(): mute flag successfully updated for track %d", trackId);
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_setMuteFlag(): failed to update mute flag for track %d with EAS error code %ld",
                trackId, result);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_triggerClip(JNIEnv *env, jobject thiz, jint clipId)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for triggerClip()");
    }

    EAS_RESULT result;
    result = lpJet->triggerClip(clipId);
    if(result==EAS_SUCCESS) {
        //ALOGV("android_media_JetPlayer_triggerClip(): triggerClip successful for clip %d", clipId);
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_triggerClip(): triggerClip for clip %d failed with EAS error code %ld",
                clipId, result);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
static jboolean
android_media_JetPlayer_clearQueue(JNIEnv *env, jobject thiz)
{
    JetPlayer *lpJet = (JetPlayer *)env->GetIntField(
        thiz, javaJetPlayerFields.nativePlayerInJavaObj);
    if (lpJet == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "Unable to retrieve JetPlayer pointer for clearQueue()");
    }

    EAS_RESULT result = lpJet->clearQueue();
    if(result==EAS_SUCCESS) {
        //ALOGV("android_media_JetPlayer_clearQueue(): clearQueue successful");
        return JNI_TRUE;
    } else {
        ALOGE("android_media_JetPlayer_clearQueue(): clearQueue failed with EAS error code %ld",
                result);
        return JNI_FALSE;
    }
}


// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
static JNINativeMethod gMethods[] = {
    // name,               signature,               funcPtr
    {"native_setup",       "(Ljava/lang/Object;II)Z", (void *)android_media_JetPlayer_setup},
    {"native_finalize",    "()V",                   (void *)android_media_JetPlayer_finalize},
    {"native_release",     "()V",                   (void *)android_media_JetPlayer_release},
    {"native_loadJetFromFile",
                           "(Ljava/lang/String;)Z", (void *)android_media_JetPlayer_loadFromFile},
    {"native_loadJetFromFileD", "(Ljava/io/FileDescriptor;JJ)Z",
                                                    (void *)android_media_JetPlayer_loadFromFileD},
    {"native_closeJetFile","()Z",                   (void *)android_media_JetPlayer_closeFile},
    {"native_playJet",     "()Z",                   (void *)android_media_JetPlayer_play},
    {"native_pauseJet",    "()Z",                   (void *)android_media_JetPlayer_pause},
    {"native_queueJetSegment",
                           "(IIIIIB)Z",             (void *)android_media_JetPlayer_queueSegment},
    {"native_queueJetSegmentMuteArray",
                           "(IIII[ZB)Z",     (void *)android_media_JetPlayer_queueSegmentMuteArray},
    {"native_setMuteFlags","(IZ)Z",                 (void *)android_media_JetPlayer_setMuteFlags},
    {"native_setMuteArray","([ZZ)Z",                (void *)android_media_JetPlayer_setMuteArray},
    {"native_setMuteFlag", "(IZZ)Z",                (void *)android_media_JetPlayer_setMuteFlag},
    {"native_triggerClip", "(I)Z",                  (void *)android_media_JetPlayer_triggerClip},
    {"native_clearQueue",  "()Z",                   (void *)android_media_JetPlayer_clearQueue},
};

#define JAVA_NATIVEJETPLAYERINJAVAOBJ_FIELD_NAME "mNativePlayerInJavaObj"
#define JAVA_NATIVEJETPOSTEVENT_CALLBACK_NAME "postEventFromNative"


int register_android_media_JetPlayer(JNIEnv *env)
{
    jclass jetPlayerClass = NULL;
    javaJetPlayerFields.jetClass = NULL;
    javaJetPlayerFields.postNativeEventInJava = NULL;
    javaJetPlayerFields.nativePlayerInJavaObj = NULL;

    // Get the JetPlayer java class
    jetPlayerClass = env->FindClass(kClassPathName);
    if (jetPlayerClass == NULL) {
        ALOGE("Can't find %s", kClassPathName);
        return -1;
    }
    javaJetPlayerFields.jetClass = (jclass)env->NewGlobalRef(jetPlayerClass);

    // Get the mNativePlayerInJavaObj variable field
    javaJetPlayerFields.nativePlayerInJavaObj = env->GetFieldID(
            jetPlayerClass,
            JAVA_NATIVEJETPLAYERINJAVAOBJ_FIELD_NAME, "I");
    if (javaJetPlayerFields.nativePlayerInJavaObj == NULL) {
        ALOGE("Can't find JetPlayer.%s", JAVA_NATIVEJETPLAYERINJAVAOBJ_FIELD_NAME);
        return -1;
    }

    // Get the callback to post events from this native code to Java
    javaJetPlayerFields.postNativeEventInJava = env->GetStaticMethodID(javaJetPlayerFields.jetClass,
            JAVA_NATIVEJETPOSTEVENT_CALLBACK_NAME, "(Ljava/lang/Object;III)V");
    if (javaJetPlayerFields.postNativeEventInJava == NULL) {
        ALOGE("Can't find Jet.%s", JAVA_NATIVEJETPOSTEVENT_CALLBACK_NAME);
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}
