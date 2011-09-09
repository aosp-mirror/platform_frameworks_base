/*
 * Copyright (C) 2011 The Android Open Source Project
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
#define LOG_NDEBUG 1
#define LOG_TAG "VideoEditorMain"
#include <dlfcn.h>
#include <stdio.h>
#include <unistd.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include <VideoEditorClasses.h>
#include <VideoEditorJava.h>
#include <VideoEditorOsal.h>
#include <VideoEditorLogging.h>
#include <marker.h>
#include <VideoEditorClasses.h>
#include <VideoEditorThumbnailMain.h>
#include <M4OSA_Debug.h>
#include <M4xVSS_Internal.h>
#include <surfaceflinger/Surface.h>
#include <surfaceflinger/ISurface.h>
#include "VideoEditorPreviewController.h"

#include "VideoEditorMain.h"

extern "C" {
#include <M4OSA_Clock.h>
#include <M4OSA_CharStar.h>
#include <M4OSA_Error.h>
#include <M4OSA_FileCommon.h>
#include <M4OSA_FileReader.h>
#include <M4OSA_FileWriter.h>
#include <M4OSA_Memory.h>
#include <M4OSA_Thread.h>
#include <M4xVSS_API.h>
#include <M4VSS3GPP_ErrorCodes.h>
#include <M4MCS_API.h>
#include <M4MCS_ErrorCodes.h>
#include <M4READER_Common.h>
#include <M4WRITER_common.h>
};


using namespace android;

#define THREAD_STACK_SIZE       (65536)

#define VIDEOEDITOR_VERSION_MAJOR     0
#define VIDEOEDITOR_VERSION_MINOR     0
#define VIDEOEDITOR_VERSION_REVISION  1


typedef enum
{
    ManualEditState_NOT_INITIALIZED,
    ManualEditState_INITIALIZED,
    ManualEditState_ANALYZING,
    ManualEditState_ANALYZING_ERROR,
    ManualEditState_OPENED,
    ManualEditState_SAVING,
    ManualEditState_SAVING_ERROR,
    ManualEditState_SAVED,
    ManualEditState_STOPPING
} ManualEditState;

typedef struct
{
    JavaVM*                        pVM;
    jobject                        engine;
    jmethodID                      onCompletionMethodId;
    jmethodID                      onErrorMethodId;
    jmethodID                      onWarningMethodId;
    jmethodID                      onProgressUpdateMethodId;
    jmethodID                      onPreviewProgressUpdateMethodId;
    jmethodID                      previewFrameEditInfoId;
    M4xVSS_InitParams              initParams;
    void*                          pTextRendererHandle;
    M4xVSS_getTextRgbBufferFct     pTextRendererFunction;
    M4OSA_Context                  engineContext;
    ManualEditState                state;
    M4VSS3GPP_EditSettings*        pEditSettings;
    M4OSA_Context                  threadContext;
    M4OSA_ERR                      threadResult;
    M4OSA_UInt8                    threadProgress;
    VideoEditorPreviewController   *mPreviewController;
    M4xVSS_AudioMixingSettings     *mAudioSettings;
    /* Audio Graph changes */
    M4OSA_Context                   pAudioGraphMCSCtx;
    M4OSA_Bool                      bSkipState;
    jmethodID                       onAudioGraphProgressUpdateMethodId;
    Mutex                           mLock;
    bool                            mIsUpdateOverlay;
    char                            *mOverlayFileName;
    int                             mOverlayRenderingMode;
    M4DECODER_VideoDecoders* decoders;
} ManualEditContext;

extern "C" M4OSA_ERR M4MCS_open_normalMode(
                M4MCS_Context                       pContext,
                M4OSA_Void*                         pFileIn,
                M4VIDEOEDITING_FileType             InputFileType,
                M4OSA_Void*                         pFileOut,
                M4OSA_Void*                         pTempFile);

static M4OSA_ERR videoEditor_toUTF8Fct(
                M4OSA_Void*                         pBufferIn,
                M4OSA_UInt8*                        pBufferOut,
                M4OSA_UInt32*                       bufferOutSize);

static M4OSA_ERR videoEditor_fromUTF8Fct(
                M4OSA_UInt8*                        pBufferIn,
                M4OSA_Void*                         pBufferOut,
                M4OSA_UInt32*                       bufferOutSize);

static M4OSA_ERR videoEditor_getTextRgbBufferFct(
                M4OSA_Void*                         pRenderingData,
                M4OSA_Void*                         pTextBuffer,
                M4OSA_UInt32                        textBufferSize,
                M4VIFI_ImagePlane**                 pOutputPlane);

static void videoEditor_callOnProgressUpdate(
                ManualEditContext*                  pContext,
                int                                 task,
                int                                 progress);

static void videoEditor_freeContext(
                JNIEnv*                             pEnv,
                ManualEditContext**                 ppContext);

static M4OSA_ERR videoEditor_threadProc(
                M4OSA_Void*                         param);

static jobject videoEditor_getVersion(
                JNIEnv*                             pEnv,
                jobject                             thiz);

static void videoEditor_init(
                JNIEnv*                             pEnv,
                jobject                             thiz,
                jstring                             tempPath,
                jstring                             textRendererPath);

static void videoEditor_loadSettings(
                JNIEnv*                             pEnv,
                jobject                             thiz,
                jobject                             settings);

static void videoEditor_unloadSettings(
                JNIEnv*                             pEnv,
                jobject                             thiz);


static void videoEditor_stopEncoding(
                JNIEnv*                             pEnv,
                jobject                             thiz);

static void videoEditor_release(
                JNIEnv*                             pEnv,
                jobject                             thiz);
static int videoEditor_getPixels(
                                 JNIEnv*                  env,
                                 jobject                  thiz,
                                 jstring                  path,
                                 jintArray                pixelArray,
                                 M4OSA_UInt32             width,
                                 M4OSA_UInt32             height,
                                 M4OSA_UInt32             timeMS);
static int videoEditor_getPixelsList(
                                     JNIEnv*                  env,
                                     jobject                  thiz,
                                     jstring                  path,
                                     jintArray                pixelArray,
                                     M4OSA_UInt32             width,
                                     M4OSA_UInt32             height,
                                     M4OSA_UInt32             noOfThumbnails,
                                     jlong                    startTime,
                                     jlong                    endTime,
                                     jintArray                indexArray,
                                     jobject                  callback);

static void
videoEditor_startPreview(
                JNIEnv*                 pEnv,
                jobject                 thiz,
                jobject                 mSurface,
                jlong                   fromMs,
                jlong                   toMs,
                jint                    callbackInterval,
                jboolean                loop);

static void
videoEditor_populateSettings(
                JNIEnv*                 pEnv,
                jobject                 thiz,
                jobject                 settings,
                jobject                 object,
                jobject                 audioSettingObject);

static int videoEditor_stopPreview(JNIEnv*  pEnv,
                              jobject  thiz);

static jobject
videoEditor_getProperties(
                JNIEnv*                             pEnv,
                jobject                             thiz,
                jstring                             file);

static int videoEditor_renderPreviewFrame(JNIEnv* pEnv,
                                    jobject thiz,
                                    jobject    mSurface,
                                    jlong fromMs,
                                    jint  surfaceWidth,
                                    jint  surfaceHeight);

static int videoEditor_registerManualEditMethods(
                JNIEnv*                             pEnv);

static void jniPreviewProgressCallback(void* cookie, M4OSA_UInt32 msgType,
                                        void *argc);

static int videoEditor_renderMediaItemPreviewFrame(JNIEnv* pEnv,
                                                    jobject thiz,
                                                    jobject mSurface,
                                                    jstring filePath,
                                                    jint frameWidth,
                                                    jint frameHeight,
                                                    jint surfaceWidth,
                                                    jint surfaceHeight,
                                                    jlong fromMs);

static int videoEditor_generateAudioWaveFormSync ( JNIEnv*     pEnv,
                                                  jobject     thiz,
                                                  jstring     pcmfilePath,
                                                  jstring     outGraphfilePath,
                                                  jint        frameDuration,
                                                  jint        channels,
                                                  jint        samplesCount);

static int videoEditor_generateAudioRawFile(JNIEnv* pEnv,
                                    jobject thiz,
                                    jstring infilePath,
                                    jstring pcmfilePath );

M4OSA_ERR videoEditor_generateAudio(JNIEnv* pEnv,ManualEditContext* pContext,
                                    M4OSA_Char* infilePath,
                                    M4OSA_Char* pcmfilePath );

static int
videoEditor_generateClip(
                JNIEnv*                             pEnv,
                jobject                             thiz,
                jobject                             settings);

static void videoEditor_clearSurface(JNIEnv* pEnv,
                                    jobject thiz,
                                    jobject surface);

static JNINativeMethod gManualEditMethods[] = {
    {"getVersion",               "()L"VERSION_CLASS_NAME";",
                                (void *)videoEditor_getVersion      },
    {"_init",                    "(Ljava/lang/String;Ljava/lang/String;)V",
                                (void *)videoEditor_init    },
    {"nativeStartPreview",       "(Landroid/view/Surface;JJIZ)V",
                                (void *)videoEditor_startPreview    },
    {"nativePopulateSettings",
            "(L"EDIT_SETTINGS_CLASS_NAME";L"PREVIEW_PROPERTIES_CLASS_NAME";L"
            AUDIO_SETTINGS_CLASS_NAME";)V",
                                (void *)videoEditor_populateSettings    },
    {"nativeRenderPreviewFrame", "(Landroid/view/Surface;JII)I",
                                (int *)videoEditor_renderPreviewFrame     },
    {"nativeRenderMediaItemPreviewFrame",
    "(Landroid/view/Surface;Ljava/lang/String;IIIIJ)I",
                        (int *)videoEditor_renderMediaItemPreviewFrame     },
    {"nativeStopPreview",       "()I",
                                (int *)videoEditor_stopPreview    },
    {"stopEncoding",            "()V",
                                (void *)videoEditor_stopEncoding         },
    {"release",                 "()V",
                                (void *)videoEditor_release            },
    {"nativeGetPixels",         "(Ljava/lang/String;[IIIJ)I",
                                (void*)videoEditor_getPixels               },
    {"nativeGetPixelsList",     "(Ljava/lang/String;[IIIIJJ[ILandroid/media/videoeditor/MediaArtistNativeHelper$NativeGetPixelsListCallback;)I",
                                (void*)videoEditor_getPixelsList           },
    {"getMediaProperties",
    "(Ljava/lang/String;)Landroid/media/videoeditor/MediaArtistNativeHelper$Properties;",
                                (void *)videoEditor_getProperties          },
    {"nativeGenerateAudioGraph","(Ljava/lang/String;Ljava/lang/String;III)I",
                                (int *)videoEditor_generateAudioWaveFormSync },
    {"nativeGenerateRawAudio",  "(Ljava/lang/String;Ljava/lang/String;)I",
                                (int *)videoEditor_generateAudioRawFile      },
    {"nativeGenerateClip",      "(L"EDIT_SETTINGS_CLASS_NAME";)I",
                                (void *)videoEditor_generateClip  },
    {"nativeClearSurface",       "(Landroid/view/Surface;)V",
                                (void *)videoEditor_clearSurface  },
};

// temp file name of VSS out file
#define TEMP_MCS_OUT_FILE_PATH "tmpOut.3gp"

void
getClipSetting(
                JNIEnv*                                       pEnv,
                jobject                                       object,
                M4VSS3GPP_ClipSettings*                       pSettings)
{

    jfieldID fid;
    int field = 0;
    bool needToBeLoaded = true;
    jclass clazz = pEnv->FindClass(PROPERTIES_CLASS_NAME);

    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == clazz),
                                             "not initialized");

    fid = pEnv->GetFieldID(clazz,"duration","I");
    pSettings->ClipProperties.uiClipDuration = pEnv->GetIntField(object,fid);
    M4OSA_TRACE1_1("duration = %d",pSettings->ClipProperties.uiClipDuration);

    fid = pEnv->GetFieldID(clazz,"videoFormat","I");
    pSettings->ClipProperties.VideoStreamType =
        (M4VIDEOEDITING_VideoFormat)pEnv->GetIntField(object,fid);
    M4OSA_TRACE1_1("videoFormat = %d",pSettings->ClipProperties.VideoStreamType);

    fid = pEnv->GetFieldID(clazz,"videoDuration","I");
    pSettings->ClipProperties.uiClipVideoDuration = pEnv->GetIntField(object,fid);
    M4OSA_TRACE1_1("videoDuration = %d",
                    pSettings->ClipProperties.uiClipVideoDuration);

    fid = pEnv->GetFieldID(clazz,"width","I");
    pSettings->ClipProperties.uiVideoWidth = pEnv->GetIntField(object,fid);
    M4OSA_TRACE1_1("width = %d",pSettings->ClipProperties.uiVideoWidth);

    fid = pEnv->GetFieldID(clazz,"height","I");
    pSettings->ClipProperties.uiVideoHeight = pEnv->GetIntField(object,fid);
    M4OSA_TRACE1_1("height = %d",pSettings->ClipProperties.uiVideoHeight);

    fid = pEnv->GetFieldID(clazz,"audioFormat","I");
    pSettings->ClipProperties.AudioStreamType =
        (M4VIDEOEDITING_AudioFormat)pEnv->GetIntField(object,fid);
    M4OSA_TRACE1_1("audioFormat = %d",pSettings->ClipProperties.AudioStreamType);

    fid = pEnv->GetFieldID(clazz,"audioDuration","I");
    pSettings->ClipProperties.uiClipAudioDuration = pEnv->GetIntField(object,fid);
    M4OSA_TRACE1_1("audioDuration = %d",
                    pSettings->ClipProperties.uiClipAudioDuration);

    fid = pEnv->GetFieldID(clazz,"audioBitrate","I");
    pSettings->ClipProperties.uiAudioBitrate = pEnv->GetIntField(object,fid);
    M4OSA_TRACE1_1("audioBitrate = %d",pSettings->ClipProperties.uiAudioBitrate);

    fid = pEnv->GetFieldID(clazz,"audioChannels","I");
    pSettings->ClipProperties.uiNbChannels = pEnv->GetIntField(object,fid);
    M4OSA_TRACE1_1("audioChannels = %d",pSettings->ClipProperties.uiNbChannels);

    fid = pEnv->GetFieldID(clazz,"audioSamplingFrequency","I");
    pSettings->ClipProperties.uiSamplingFrequency = pEnv->GetIntField(object,fid);
    M4OSA_TRACE1_1("audioSamplingFrequency = %d",
                    pSettings->ClipProperties.uiSamplingFrequency);

   fid = pEnv->GetFieldID(clazz,"audioVolumeValue","I");
   pSettings->ClipProperties.uiClipAudioVolumePercentage =
                    pEnv->GetIntField(object,fid);
   M4OSA_TRACE1_1("audioVolumeValue = %d",
                    pSettings->ClipProperties.uiClipAudioVolumePercentage);

   fid = pEnv->GetFieldID(clazz,"videoRotation","I");
   pSettings->ClipProperties.videoRotationDegrees =
                    pEnv->GetIntField(object,fid);
   M4OSA_TRACE1_1("videoRotation = %d",
                    pSettings->ClipProperties.videoRotationDegrees);
}

static void jniPreviewProgressCallback (void* cookie, M4OSA_UInt32 msgType,
                                        void *argc)
{
    ManualEditContext *pContext = (ManualEditContext *)cookie;
    JNIEnv*     pEnv = NULL;
    bool        isFinished = false;
    int         currentMs = 0;
    int         error = M4NO_ERROR;
    bool        isUpdateOverlay = false;
    int         overlayEffectIndex;
    char        *extPos;
    bool        isSendProgress = true;
    jstring     tmpFileName;
    VideoEditorCurretEditInfo *pCurrEditInfo;

    // Attach the current thread.
    pContext->pVM->AttachCurrentThread(&pEnv, NULL);
    switch(msgType)
    {
        case MSG_TYPE_PROGRESS_INDICATION:
            currentMs = *(int*)argc;
            break;
        case MSG_TYPE_PLAYER_ERROR:
            currentMs = -1;
            error = *(int*)argc;
            break;
        case MSG_TYPE_PREVIEW_END:
            isFinished = true;
            break;
        case MSG_TYPE_OVERLAY_UPDATE:
        {
            int overlayFileNameLen = 0;
            isSendProgress = false;
            pContext->mIsUpdateOverlay = true;
            pCurrEditInfo = (VideoEditorCurretEditInfo*)argc;
            overlayEffectIndex = pCurrEditInfo->overlaySettingsIndex;
            LOGV("MSG_TYPE_OVERLAY_UPDATE");

            if (pContext->mOverlayFileName != NULL) {
                free(pContext->mOverlayFileName);
                pContext->mOverlayFileName = NULL;
            }

            overlayFileNameLen =
                strlen((const char*)pContext->pEditSettings->Effects[overlayEffectIndex].xVSS.pFramingFilePath);

            pContext->mOverlayFileName =
                (char*)M4OSA_32bitAlignedMalloc(overlayFileNameLen+1,
                                    M4VS, (M4OSA_Char*)"videoEdito JNI overlayFile");
            if (pContext->mOverlayFileName != NULL) {
                strncpy (pContext->mOverlayFileName,
                    (const char*)pContext->pEditSettings->\
                    Effects[overlayEffectIndex].xVSS.pFramingFilePath, overlayFileNameLen);
                //Change the name to png file
                extPos = strstr(pContext->mOverlayFileName, ".rgb");
                if (extPos != NULL) {
                    *extPos = '\0';
                } else {
                    LOGE("ERROR the overlay file is incorrect");
                }

                strcat(pContext->mOverlayFileName, ".png");
                LOGV("Conv string is %s", pContext->mOverlayFileName);
                LOGV("Current Clip index = %d", pCurrEditInfo->clipIndex);

                pContext->mOverlayRenderingMode = pContext->pEditSettings->\
                         pClipList[pCurrEditInfo->clipIndex]->xVSS.MediaRendering;
                LOGV("rendering mode %d ", pContext->mOverlayRenderingMode);

            }

            break;
        }

        case MSG_TYPE_OVERLAY_CLEAR:
            isSendProgress = false;
            if (pContext->mOverlayFileName != NULL) {
                free(pContext->mOverlayFileName);
                pContext->mOverlayFileName = NULL;
            }

            LOGV("MSG_TYPE_OVERLAY_CLEAR");
            //argc is not used
            pContext->mIsUpdateOverlay = true;
            break;
        default:
            break;
    }

    if (isSendProgress) {
        tmpFileName  = pEnv->NewStringUTF(pContext->mOverlayFileName);
        pEnv->CallVoidMethod(pContext->engine,
                pContext->onPreviewProgressUpdateMethodId,
                currentMs,isFinished, pContext->mIsUpdateOverlay,
                tmpFileName, pContext->mOverlayRenderingMode);

        if (pContext->mIsUpdateOverlay) {
            pContext->mIsUpdateOverlay = false;
        }

        if (tmpFileName) {
            pEnv->DeleteLocalRef(tmpFileName);
        }
    }

    // Detach the current thread.
    pContext->pVM->DetachCurrentThread();

}
static M4OSA_ERR checkClipVideoProfileAndLevel(M4DECODER_VideoDecoders *pDecoders,
    M4OSA_Int32 format, M4OSA_UInt32 profile, M4OSA_UInt32 level){

    M4OSA_Int32 codec = 0;
    M4OSA_Bool foundCodec = M4OSA_FALSE;
    M4OSA_ERR  result = M4VSS3GPP_ERR_EDITING_UNSUPPORTED_VIDEO_PROFILE;
    M4OSA_Bool foundProfile = M4OSA_FALSE;
    LOGV("checkClipVideoProfileAndLevel format %d profile;%d level:0x%x",
       format, profile, level);

    switch (format) {
        case M4VIDEOEDITING_kH263:
            codec = M4DA_StreamTypeVideoH263;
            break;
        case M4VIDEOEDITING_kH264:
             codec = M4DA_StreamTypeVideoMpeg4Avc;
            break;
        case M4VIDEOEDITING_kMPEG4:
             codec = M4DA_StreamTypeVideoMpeg4;
            break;
        case M4VIDEOEDITING_kNoneVideo:
        case M4VIDEOEDITING_kNullVideo:
        case M4VIDEOEDITING_kUnsupportedVideo:
             // For these case we do not check the profile and level
             return M4NO_ERROR;
        default :
            LOGE("checkClipVideoProfileAndLevel unsupport Video format %ld", format);
            break;
    }

    if (pDecoders != M4OSA_NULL && pDecoders->decoderNumber > 0) {
        VideoDecoder *pVideoDecoder = pDecoders->decoder;
        for(size_t k =0; k < pDecoders->decoderNumber; k++) {
            if (pVideoDecoder != M4OSA_NULL) {
                if (pVideoDecoder->codec == codec) {
                    foundCodec = M4OSA_TRUE;
                    break;
                }
            }
            pVideoDecoder++;
        }

        if (foundCodec) {
            VideoComponentCapabilities* pComponent = pVideoDecoder->component;
            for (size_t i = 0; i < pVideoDecoder->componentNumber; i++) {
                if (pComponent != M4OSA_NULL) {
                    VideoProfileLevel *pProfileLevel = pComponent->profileLevel;
                    for (size_t j =0; j < pComponent->profileNumber; j++) {
                        // Check the profile and level
                        if (pProfileLevel != M4OSA_NULL) {
                            if (profile == pProfileLevel->mProfile) {
                                foundProfile = M4OSA_TRUE;

                                if (level <= pProfileLevel->mLevel) {
                                    return M4NO_ERROR;
                                }
                            } else {
                                foundProfile = M4OSA_FALSE;
                            }
                        }
                        pProfileLevel++;
                    }
                }
                pComponent++;
            }
        }
    }

    if (foundProfile) {
        result = M4VSS3GPP_ERR_EDITING_UNSUPPORTED_VIDEO_LEVEL;
    } else {
        result = M4VSS3GPP_ERR_EDITING_UNSUPPORTED_VIDEO_PROFILE;
    }

    return result;
}
static int videoEditor_stopPreview(JNIEnv*  pEnv,
                              jobject  thiz)
{
    ManualEditContext* pContext = M4OSA_NULL;
    bool needToBeLoaded = true;
    M4OSA_UInt32 lastProgressTimeMs = 0;

    // Get the context.
    pContext =
            (ManualEditContext*)videoEditClasses_getContext(&needToBeLoaded, pEnv, thiz);

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == pContext),
                                             "not initialized");
    lastProgressTimeMs = pContext->mPreviewController->stopPreview();

    if (pContext->mOverlayFileName != NULL) {
        free(pContext->mOverlayFileName);
        pContext->mOverlayFileName = NULL;
    }

    return lastProgressTimeMs;
}

static void videoEditor_clearSurface(JNIEnv* pEnv,
                                    jobject thiz,
                                    jobject surface)
{
    bool needToBeLoaded = true;
    M4OSA_ERR result = M4NO_ERROR;
    VideoEditor_renderPreviewFrameStr frameStr;
    const char* pMessage = NULL;
    // Let the size be QVGA
    int width = 320;
    int height = 240;
    ManualEditContext* pContext = M4OSA_NULL;

    // Get the context.
    pContext = (ManualEditContext*)videoEditClasses_getContext(&needToBeLoaded, pEnv, thiz);
    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO,
                                "VIDEO_EDITOR","pContext = 0x%x",pContext);

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == pContext),
                                             "not initialized");

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                 (M4OSA_NULL == pContext->mPreviewController),
                                 "not initialized");

    // Validate the surface parameter.
    videoEditJava_checkAndThrowIllegalArgumentException(&needToBeLoaded, pEnv,
                                                (NULL == surface),
                                                "surface is null");

    jclass surfaceClass = pEnv->FindClass("android/view/Surface");
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == surfaceClass),
                                             "not initialized");

    jfieldID surface_native =
            pEnv->GetFieldID(surfaceClass, ANDROID_VIEW_SURFACE_JNI_ID, "I");
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == surface_native),
                                             "not initialized");

    Surface* const p = (Surface*)pEnv->GetIntField(surface, surface_native);
    sp<Surface> previewSurface = sp<Surface>(p);
    // Validate the mSurface's mNativeSurface field
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                                (NULL == previewSurface.get()),
                                                "mNativeSurface is null");

    frameStr.pBuffer = M4OSA_NULL;
    frameStr.timeMs = 0;
    frameStr.uiSurfaceWidth = width;
    frameStr.uiSurfaceHeight = height;
    frameStr.uiFrameWidth = width;
    frameStr.uiFrameHeight = height;
    frameStr.bApplyEffect = M4OSA_FALSE;
    frameStr.clipBeginCutTime = 0;
    frameStr.clipEndCutTime = 0;

    result = pContext->mPreviewController->clearSurface(previewSurface,
                                                              &frameStr);
    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
            (M4NO_ERROR != result), result);

  }

static int videoEditor_renderPreviewFrame(JNIEnv* pEnv,
                                    jobject thiz,
                                    jobject    mSurface,
                                    jlong fromMs,
                                    jint surfaceWidth,
                                    jint surfaceHeight )
{
    bool needToBeLoaded = true;
    M4OSA_ERR result = M4NO_ERROR;
    M4OSA_UInt32 timeMs = (M4OSA_UInt32)fromMs;
    M4OSA_UInt32 i=0,tnTimeMs = 0, framesizeYuv =0;
    M4VIFI_UInt8 *pixelArray = M4OSA_NULL;
    M4OSA_UInt32    iCurrentClipIndex = 0, uiNumberOfClipsInStoryBoard =0,
                    uiClipDuration = 0, uiTotalClipDuration = 0,
                    iIncrementedDuration = 0;
    VideoEditor_renderPreviewFrameStr frameStr;
    M4OSA_Context tnContext = M4OSA_NULL;
    const char* pMessage = NULL;
    M4VIFI_ImagePlane *yuvPlane = NULL;
    VideoEditorCurretEditInfo  currEditInfo;

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO,
        "VIDEO_EDITOR", "surfaceWidth = %d",surfaceWidth);
    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO,
        "VIDEO_EDITOR", "surfaceHeight = %d",surfaceHeight);
    ManualEditContext* pContext = M4OSA_NULL;
    // Get the context.
    pContext =
            (ManualEditContext*)videoEditClasses_getContext(&needToBeLoaded, pEnv, thiz);
    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO,
                                "VIDEO_EDITOR","pContext = 0x%x",pContext);

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == pContext),
                                             "not initialized");

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                 (M4OSA_NULL == pContext->mPreviewController),
                                 "not initialized");

    // Validate the mSurface parameter.
    videoEditJava_checkAndThrowIllegalArgumentException(&needToBeLoaded, pEnv,
                                                (NULL == mSurface),
                                                "mSurface is null");
    jclass surfaceClass = pEnv->FindClass("android/view/Surface");
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == surfaceClass),
                                             "not initialized");

    jfieldID surface_native =
            pEnv->GetFieldID(surfaceClass, ANDROID_VIEW_SURFACE_JNI_ID, "I");
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == surface_native),
                                             "not initialized");

    Surface* const p = (Surface*)pEnv->GetIntField(mSurface, surface_native);
    sp<Surface> previewSurface = sp<Surface>(p);
    // Validate the mSurface's mNativeSurface field
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                                (NULL == previewSurface.get()),
                                                "mNativeSurface is null");

    /* Determine the total number of clips, total duration*/
    uiNumberOfClipsInStoryBoard = pContext->pEditSettings->uiClipNumber;

    for (i = 0; i < uiNumberOfClipsInStoryBoard; i++) {
        uiClipDuration = pContext->pEditSettings->pClipList[i]->uiEndCutTime -
            pContext->pEditSettings->pClipList[i]->uiBeginCutTime;
        uiTotalClipDuration += uiClipDuration;
    }

    /* determine the clip whose thumbnail needs to be rendered*/
    if (timeMs == 0) {
        iCurrentClipIndex = 0;
        i=0;
    } else {
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
            "videoEditor_renderPreviewFrame() timeMs=%d", timeMs);

        if (timeMs > uiTotalClipDuration) {
            VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
                "videoEditor_renderPreviewFrame() timeMs > uiTotalClipDuration");
            pMessage = videoEditJava_getErrorName(M4ERR_PARAMETER);
            jniThrowException(pEnv, "java/lang/IllegalArgumentException", pMessage);
            return -1;
        }

        for (i = 0; i < uiNumberOfClipsInStoryBoard; i++) {
            if (timeMs <= (iIncrementedDuration +
                          (pContext->pEditSettings->pClipList[i]->uiEndCutTime -
                           pContext->pEditSettings->pClipList[i]->uiBeginCutTime)))
            {
                iCurrentClipIndex = i;
                VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
                    "videoEditor_renderPreviewFrame() iCurrentClipIndex=%d for timeMs=%d",
                    iCurrentClipIndex, timeMs);
                break;
            }
            else {
                iIncrementedDuration = iIncrementedDuration +
                    (pContext->pEditSettings->pClipList[i]->uiEndCutTime -
                    pContext->pEditSettings->pClipList[i]->uiBeginCutTime);
            }
        }
    }
    /* If timestamp is beyond story board duration, return*/
    if (i >= uiNumberOfClipsInStoryBoard) {
        if (timeMs == iIncrementedDuration) {
            iCurrentClipIndex = i-1;
        } else {
           return -1;
        }
    }

    /*+ Handle the image files here */
      if (pContext->pEditSettings->pClipList[iCurrentClipIndex]->FileType ==
          /*M4VIDEOEDITING_kFileType_JPG*/ M4VIDEOEDITING_kFileType_ARGB8888 ) {
          VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", " iCurrentClipIndex %d ", iCurrentClipIndex);
          VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
                "  Height = %d",
                pContext->pEditSettings->pClipList[iCurrentClipIndex]->ClipProperties.uiVideoHeight);

          VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
                "  Width = %d",
                pContext->pEditSettings->pClipList[iCurrentClipIndex]->ClipProperties.uiVideoWidth);

          LvGetImageThumbNail((const char *)pContext->pEditSettings->\
          pClipList[iCurrentClipIndex]->pFile,
            pContext->pEditSettings->pClipList[iCurrentClipIndex]->ClipProperties.uiVideoHeight,
            pContext->pEditSettings->pClipList[iCurrentClipIndex]->ClipProperties.uiVideoWidth,
            (M4OSA_Void **)&frameStr.pBuffer);
            tnTimeMs = (M4OSA_UInt32)timeMs;

          frameStr.videoRotationDegree = 0;
    } else {
        /* Handle 3gp/mp4 Clips here */
        /* get thumbnail*/
        result = ThumbnailOpen(&tnContext,
            (const M4OSA_Char*)pContext->pEditSettings->\
            pClipList[iCurrentClipIndex]->pFile, M4OSA_TRUE);
        if (result != M4NO_ERROR || tnContext  == M4OSA_NULL) {
            return -1;
        }

        /* timeMs is relative to storyboard; in this api it shud be relative to this clip */
        if ((i >= uiNumberOfClipsInStoryBoard) &&
            (timeMs == iIncrementedDuration)) {
            tnTimeMs = pContext->pEditSettings->\
            pClipList[iCurrentClipIndex]->uiEndCutTime;
        } else {
            tnTimeMs = pContext->pEditSettings->\
            pClipList[iCurrentClipIndex]->uiBeginCutTime
            + (timeMs - iIncrementedDuration);
        }

        VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR",
            "video width = %d",pContext->pEditSettings->pClipList[iCurrentClipIndex]->\
            ClipProperties.uiVideoWidth);
        VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR",
            "video height = %d",pContext->pEditSettings->pClipList[iCurrentClipIndex]->\
            ClipProperties.uiVideoHeight);
        VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR",
            "current clip index = %d",iCurrentClipIndex);

        M4OSA_UInt32 width = pContext->pEditSettings->pClipList[iCurrentClipIndex]->\
            ClipProperties.uiVideoWidth;
        M4OSA_UInt32 height = pContext->pEditSettings->pClipList[iCurrentClipIndex]->\
            ClipProperties.uiVideoHeight;

        framesizeYuv = width * height * 1.5;

        pixelArray = (M4VIFI_UInt8 *)M4OSA_32bitAlignedMalloc(framesizeYuv, M4VS,
            (M4OSA_Char*)"videoEditor pixelArray");
        if (pixelArray == M4OSA_NULL) {
            VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
                "videoEditor_renderPreviewFrame() malloc error");
            ThumbnailClose(tnContext);
            pMessage = videoEditJava_getErrorName(M4ERR_ALLOC);
            jniThrowException(pEnv, "java/lang/RuntimeException", pMessage);
            return -1;
        }

        result = ThumbnailGetPixels16(tnContext, (M4OSA_Int16 *)pixelArray,
            pContext->pEditSettings->pClipList[iCurrentClipIndex]->\
            ClipProperties.uiVideoWidth,
            pContext->pEditSettings->pClipList[iCurrentClipIndex]->\
            ClipProperties.uiVideoHeight,
            &tnTimeMs, 0);
        if (result != M4NO_ERROR) {
            free(pixelArray);
            ThumbnailClose(tnContext);
            return -1;
        }

        ThumbnailClose(tnContext);
        tnContext = M4OSA_NULL;

#ifdef DUMPTOFILE
        {
            M4OSA_Context fileContext;
            M4OSA_Char* fileName = (M4OSA_Char*)"/mnt/sdcard/FirstRGB565.raw";
            remove((const char *)fileName);
            M4OSA_fileWriteOpen(&fileContext, (M4OSA_Void*) fileName,\
                M4OSA_kFileWrite|M4OSA_kFileCreate);
            M4OSA_fileWriteData(fileContext, (M4OSA_MemAddr8) pixelArray,
                framesizeYuv);
            M4OSA_fileWriteClose(fileContext);
        }
#endif

        /**
        * Allocate output YUV planes
        */
        yuvPlane = (M4VIFI_ImagePlane*)M4OSA_32bitAlignedMalloc(3*sizeof(M4VIFI_ImagePlane), M4VS,
            (M4OSA_Char*)"videoEditor_renderPreviewFrame Output plane YUV");
        if (yuvPlane == M4OSA_NULL) {
            VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
                "videoEditor_renderPreviewFrame() malloc error for yuv plane");
            free(pixelArray);
            pMessage = videoEditJava_getErrorName(M4ERR_ALLOC);
            jniThrowException(pEnv, "java/lang/RuntimeException", pMessage);
            return -1;
        }

        yuvPlane[0].u_width = width;
        yuvPlane[0].u_height = height;
        yuvPlane[0].u_topleft = 0;
        yuvPlane[0].u_stride = width;
        yuvPlane[0].pac_data = (M4VIFI_UInt8*)pixelArray;

        yuvPlane[1].u_width = width>>1;
        yuvPlane[1].u_height = height>>1;
        yuvPlane[1].u_topleft = 0;
        yuvPlane[1].u_stride = width>>1;
        yuvPlane[1].pac_data = yuvPlane[0].pac_data
                    + yuvPlane[0].u_width * yuvPlane[0].u_height;
        yuvPlane[2].u_width = (width)>>1;
        yuvPlane[2].u_height = (height)>>1;
        yuvPlane[2].u_topleft = 0;
        yuvPlane[2].u_stride = (width)>>1;
        yuvPlane[2].pac_data = yuvPlane[1].pac_data
                    + yuvPlane[1].u_width * yuvPlane[1].u_height;

#ifdef DUMPTOFILE
        {
            M4OSA_Context fileContext;
            M4OSA_Char* fileName = (M4OSA_Char*)"/mnt/sdcard/ConvertedYuv.yuv";
            remove((const char *)fileName);
            M4OSA_fileWriteOpen(&fileContext, (M4OSA_Void*) fileName,\
                M4OSA_kFileWrite|M4OSA_kFileCreate);
            M4OSA_fileWriteData(fileContext,
                (M4OSA_MemAddr8) yuvPlane[0].pac_data, framesizeYuv);
            M4OSA_fileWriteClose(fileContext);
        }
#endif

        /* Fill up the render structure*/
        frameStr.pBuffer = (M4OSA_Void*)yuvPlane[0].pac_data;

        frameStr.videoRotationDegree = pContext->pEditSettings->\
            pClipList[iCurrentClipIndex]->ClipProperties.videoRotationDegrees;
    }

    frameStr.timeMs = timeMs;    /* timestamp on storyboard*/
    frameStr.uiSurfaceWidth =
        pContext->pEditSettings->pClipList[iCurrentClipIndex]->\
        ClipProperties.uiVideoWidth;
    frameStr.uiSurfaceHeight =
        pContext->pEditSettings->pClipList[iCurrentClipIndex]->\
        ClipProperties.uiVideoHeight;
    frameStr.uiFrameWidth =
        pContext->pEditSettings->pClipList[iCurrentClipIndex]->\
        ClipProperties.uiVideoWidth;
    frameStr.uiFrameHeight =
        pContext->pEditSettings->pClipList[iCurrentClipIndex]->\
        ClipProperties.uiVideoHeight;
    if (pContext->pEditSettings->nbEffects > 0) {
        frameStr.bApplyEffect = M4OSA_TRUE;
    } else {
        frameStr.bApplyEffect = M4OSA_FALSE;
    }
    frameStr.clipBeginCutTime = iIncrementedDuration;
    frameStr.clipEndCutTime =
        iIncrementedDuration +
        (pContext->pEditSettings->pClipList[iCurrentClipIndex]->uiEndCutTime -\
        pContext->pEditSettings->pClipList[iCurrentClipIndex]->uiBeginCutTime);

    pContext->mPreviewController->setPreviewFrameRenderingMode(
        pContext->pEditSettings->\
        pClipList[iCurrentClipIndex]->xVSS.MediaRendering,
        pContext->pEditSettings->xVSS.outputVideoSize);
    result = pContext->mPreviewController->renderPreviewFrame(previewSurface,
                                                              &frameStr, &currEditInfo);

    if (currEditInfo.overlaySettingsIndex != -1) {
        char tmpOverlayFilename[100];
        char *extPos = NULL;
        jstring tmpOverlayString;
        int tmpRenderingMode = 0;

        strncpy (tmpOverlayFilename,
                (const char*)pContext->pEditSettings->Effects[currEditInfo.overlaySettingsIndex].xVSS.pFramingFilePath, 99);

        //Change the name to png file
        extPos = strstr(tmpOverlayFilename, ".rgb");
        if (extPos != NULL) {
            *extPos = '\0';
        } else {
            LOGE("ERROR the overlay file is incorrect");
        }

        strcat(tmpOverlayFilename, ".png");

        tmpRenderingMode = pContext->pEditSettings->pClipList[iCurrentClipIndex]->xVSS.MediaRendering;
        tmpOverlayString = pEnv->NewStringUTF(tmpOverlayFilename);
        pEnv->CallVoidMethod(pContext->engine,
            pContext->previewFrameEditInfoId,
            tmpOverlayString, tmpRenderingMode);

    }

    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
            (M4NO_ERROR != result), result);

    free(frameStr.pBuffer);
    if (pContext->pEditSettings->pClipList[iCurrentClipIndex]->FileType !=
            M4VIDEOEDITING_kFileType_ARGB8888) {
        free(yuvPlane);
    }

    return tnTimeMs;
}

static int videoEditor_renderMediaItemPreviewFrame(JNIEnv* pEnv,
                                                    jobject thiz,
                                                    jobject mSurface,
                                                    jstring filePath,
                                                    jint    frameWidth,
                                                    jint    frameHeight,
                                                    jint    surfaceWidth,
                                                    jint    surfaceHeight,
                                                    jlong   fromMs)
{
    bool needToBeLoaded = true;
    M4OSA_ERR result = M4NO_ERROR;
    M4OSA_UInt32 timeMs = (M4OSA_UInt32)fromMs;
    M4OSA_UInt32 framesizeYuv =0;
    M4VIFI_UInt8 *pixelArray = M4OSA_NULL;
    VideoEditor_renderPreviewFrameStr frameStr;
    M4OSA_Context tnContext = M4OSA_NULL;
    const char* pMessage = NULL;
    M4VIFI_ImagePlane yuvPlane[3], rgbPlane;

    ManualEditContext* pContext = M4OSA_NULL;
    // Get the context.
    pContext =
            (ManualEditContext*)videoEditClasses_getContext(&needToBeLoaded,
                                                      pEnv, thiz);

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == pContext),
                                             "not initialized");

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                 (M4OSA_NULL == pContext->mPreviewController),
                                 "not initialized");

    // Validate the mSurface parameter.
    videoEditJava_checkAndThrowIllegalArgumentException(&needToBeLoaded, pEnv,
                                                (NULL == mSurface),
                                                "mSurface is null");
    jclass surfaceClass = pEnv->FindClass("android/view/Surface");
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == surfaceClass),
                                             "not initialized");

    jfieldID surface_native =
            pEnv->GetFieldID(surfaceClass, ANDROID_VIEW_SURFACE_JNI_ID, "I");
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == surface_native),
                                             "not initialized");

    Surface* const p = (Surface*)pEnv->GetIntField(mSurface, surface_native);
    sp<Surface> previewSurface = sp<Surface>(p);


    const char *pString = pEnv->GetStringUTFChars(filePath, NULL);
    if (pString == M4OSA_NULL) {
        if (pEnv != NULL) {
            jniThrowException(pEnv, "java/lang/RuntimeException", "Input string null");
        }
    }
    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
        "videoEditor_renderMediaItemPreviewFrame() timeMs=%d", timeMs);
    /* get thumbnail*/
    result = ThumbnailOpen(&tnContext,(const M4OSA_Char*)pString, M4OSA_TRUE);
    if (result != M4NO_ERROR || tnContext  == M4OSA_NULL) {
        return timeMs;
    }

    framesizeYuv = ((frameWidth)*(frameHeight)*1.5);

    pixelArray = (M4VIFI_UInt8 *)M4OSA_32bitAlignedMalloc(framesizeYuv, M4VS,\
        (M4OSA_Char*)"videoEditor pixelArray");
    if (pixelArray == M4OSA_NULL) {
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
            "videoEditor_renderPreviewFrame() malloc error");
        ThumbnailClose(tnContext);
        pMessage = videoEditJava_getErrorName(M4ERR_ALLOC);
        jniThrowException(pEnv, "java/lang/RuntimeException", pMessage);
        return timeMs;
    }

    result = ThumbnailGetPixels16(tnContext, (M4OSA_Int16 *)pixelArray,
                                                frameWidth,
                                                frameHeight, &timeMs, 0);
    if (result != M4NO_ERROR) {
        free(pixelArray);
        ThumbnailClose(tnContext);
        return fromMs;
    }

#ifdef DUMPTOFILESYSTEM
    {
        M4OSA_Context fileContext;
        M4OSA_Char* fileName = (M4OSA_Char*)"/mnt/sdcard/FirstRGB565.rgb";
        M4OSA_fileWriteOpen(&fileContext, (M4OSA_Void*) fileName,\
            M4OSA_kFileWrite|M4OSA_kFileCreate);
        M4OSA_fileWriteData(fileContext, (M4OSA_MemAddr8) pixelArray,
                            framesizeRgb);
        M4OSA_fileWriteClose(fileContext);
    }
#endif

    yuvPlane[0].pac_data = (M4VIFI_UInt8*)pixelArray;
    yuvPlane[0].u_height = frameHeight;
    yuvPlane[0].u_width = frameWidth;
    yuvPlane[0].u_stride = yuvPlane[0].u_width;
    yuvPlane[0].u_topleft = 0;

    yuvPlane[1].u_height = frameHeight/2;
    yuvPlane[1].u_width = frameWidth/2;
    yuvPlane[1].u_stride = yuvPlane[1].u_width;
    yuvPlane[1].u_topleft = 0;
    yuvPlane[1].pac_data = yuvPlane[0].pac_data
                + yuvPlane[0].u_width*yuvPlane[0].u_height;

    yuvPlane[2].u_height = frameHeight/2;
    yuvPlane[2].u_width = frameWidth/2;
    yuvPlane[2].u_stride = yuvPlane[2].u_width;
    yuvPlane[2].u_topleft = 0;
    yuvPlane[2].pac_data = yuvPlane[0].pac_data
        + yuvPlane[0].u_width*yuvPlane[0].u_height + \
        (yuvPlane[0].u_width/2)*(yuvPlane[0].u_height/2);
#ifdef DUMPTOFILESYSTEM
    {
        M4OSA_Context fileContext;
        M4OSA_Char* fileName = (M4OSA_Char*)"/mnt/sdcard/ConvertedYuv.yuv";
        M4OSA_fileWriteOpen(&fileContext, (M4OSA_Void*) fileName,\
            M4OSA_kFileWrite|M4OSA_kFileCreate);
        M4OSA_fileWriteData(fileContext, (M4OSA_MemAddr8) yuvPlane[0].pac_data,
                            framesizeYuv);
        M4OSA_fileWriteClose(fileContext);
    }
#endif

    /* Fill up the render structure*/
    frameStr.pBuffer = (M4OSA_Void*)yuvPlane[0].pac_data;
    frameStr.timeMs = timeMs;    /* timestamp on storyboard*/
    frameStr.uiSurfaceWidth = frameWidth;
    frameStr.uiSurfaceHeight = frameHeight;
    frameStr.uiFrameWidth = frameWidth;
    frameStr.uiFrameHeight = frameHeight;
    frameStr.bApplyEffect = M4OSA_FALSE;
    // clip begin cuttime and end cuttime set to 0
    // as its only required when effect needs to be applied while rendering
    frameStr.clipBeginCutTime = 0;
    frameStr.clipEndCutTime = 0;

    /*  pContext->mPreviewController->setPreviewFrameRenderingMode(M4xVSS_kBlackBorders,
    (M4VIDEOEDITING_VideoFrameSize)(M4VIDEOEDITING_kHD960+1));*/
    result
    = pContext->mPreviewController->renderPreviewFrame(previewSurface,&frameStr, NULL);
    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
                                                (M4NO_ERROR != result), result);

    /* free the pixelArray and yuvPlane[0].pac_data */
    free(yuvPlane[0].pac_data);

    ThumbnailClose(tnContext);

    if (pString != NULL) {
        pEnv->ReleaseStringUTFChars(filePath, pString);
    }

    return timeMs;
}

int videoEditor_generateAudioRawFile(   JNIEnv*     pEnv,
                                        jobject     thiz,
                                        jstring     infilePath,
                                        jstring     pcmfilePath)
{
    M4OSA_ERR result = M4NO_ERROR;
    bool               loaded   = true;
    ManualEditContext* pContext = M4OSA_NULL;



    const char *pInputFile = pEnv->GetStringUTFChars(infilePath, NULL);
    if (pInputFile == M4OSA_NULL) {
        if (pEnv != NULL) {
            jniThrowException(pEnv, "java/lang/RuntimeException", "Input string null");
        }
    }

    const char *pStringOutPCMFilePath = pEnv->GetStringUTFChars(pcmfilePath, NULL);
    if (pStringOutPCMFilePath == M4OSA_NULL) {
        if (pEnv != NULL) {
            jniThrowException(pEnv, "java/lang/RuntimeException", "Input string null");
        }
    }

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO,
        "VIDEO_EDITOR", "videoEditor_generateAudioRawFile infilePath %s",
        pInputFile);
    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO,
        "VIDEO_EDITOR", "videoEditor_generateAudioRawFile pcmfilePath %s",
        pStringOutPCMFilePath);
    // Get the context.
    pContext = (ManualEditContext*)videoEditClasses_getContext(&loaded, pEnv, thiz);

    result = videoEditor_generateAudio( pEnv, pContext, (M4OSA_Char*)pInputFile,
        (M4OSA_Char*)pStringOutPCMFilePath);

    if (pInputFile != NULL) {
        pEnv->ReleaseStringUTFChars(infilePath, pInputFile);
    }
    if (pStringOutPCMFilePath != NULL) {
        pEnv->ReleaseStringUTFChars(pcmfilePath, pStringOutPCMFilePath);
    }

    return result;
}

M4OSA_ERR videoEditor_generateAudio(JNIEnv* pEnv,ManualEditContext* pContext,
                                    M4OSA_Char* infilePath,
                                    M4OSA_Char* pcmfilePath )
{
    bool                            needToBeLoaded = true;
    M4OSA_ERR                       result = M4NO_ERROR;
    M4MCS_Context                   mcsContext = M4OSA_NULL;
    M4OSA_Char*                     pInputFile = M4OSA_NULL;
    M4OSA_Char*                     pOutputFile = M4OSA_NULL;
    M4OSA_Char*                     pTempPath = M4OSA_NULL;
    M4MCS_OutputParams*             pOutputParams = M4OSA_NULL;
    M4MCS_EncodingParams*           pEncodingParams = M4OSA_NULL;
    M4OSA_Int32                     pInputFileType = 0;
    M4OSA_UInt8                     threadProgress = 0;
    M4OSA_Char*                     pTemp3gpFilePath = M4OSA_NULL;

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_generateAudio()");

    videoEditJava_checkAndThrowIllegalArgumentException(&needToBeLoaded, pEnv,
        (NULL == pContext),
        "ManualEditContext is null");

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "M4MCS_init()");

    pOutputParams = (M4MCS_OutputParams *)M4OSA_32bitAlignedMalloc(
        sizeof(M4MCS_OutputParams),0x00,
        (M4OSA_Char *)"M4MCS_OutputParams");
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
        (M4OSA_NULL == pOutputParams),
        "not initialized");
    if (needToBeLoaded == false) {
        return M4ERR_ALLOC;
    }

    pEncodingParams = (M4MCS_EncodingParams *)M4OSA_32bitAlignedMalloc(
        sizeof(M4MCS_EncodingParams),0x00,
        (M4OSA_Char *)"M4MCS_EncodingParams");
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
        (M4OSA_NULL == pEncodingParams),
        "not initialized");
    if (needToBeLoaded == false) {
        free(pEncodingParams);
        pEncodingParams = M4OSA_NULL;
        return M4ERR_ALLOC;
    }

    // Initialize the MCS library.
    result = M4MCS_init(&mcsContext, pContext->initParams.pFileReadPtr,
        pContext->initParams.pFileWritePtr);
    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,\
        (M4NO_ERROR != result), result);
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
        (M4OSA_NULL == mcsContext),
        "not initialized");
     if(needToBeLoaded == false) {
         free(pOutputParams);
         pOutputParams = M4OSA_NULL;
         free(pEncodingParams);
         pEncodingParams = M4OSA_NULL;
         return result;
     }

    // generate the path for temp 3gp output file
    pTemp3gpFilePath = (M4OSA_Char*) M4OSA_32bitAlignedMalloc (
        (strlen((const char*)pContext->initParams.pTempPath)
        + strlen((const char*)TEMP_MCS_OUT_FILE_PATH)) + 1 /* for null termination */ , 0x0,
        (M4OSA_Char*)"Malloc for temp 3gp file");
    if (pTemp3gpFilePath != M4OSA_NULL)
    {
        memset((void *)pTemp3gpFilePath  ,0,
            strlen((const char*)pContext->initParams.pTempPath)
            + strlen((const char*)TEMP_MCS_OUT_FILE_PATH) + 1);
        strncat((char *)pTemp3gpFilePath,
            (const char *)pContext->initParams.pTempPath  ,
            (size_t) ((M4OSA_Char*)pContext->initParams.pTempPath));
        strncat((char *)pTemp3gpFilePath , (const char *)TEMP_MCS_OUT_FILE_PATH,
            (size_t)strlen ((const char*)TEMP_MCS_OUT_FILE_PATH));
    }
    else {
         M4MCS_abort(mcsContext);
         free(pOutputParams);
         pOutputParams = M4OSA_NULL;
         free(pEncodingParams);
         pEncodingParams = M4OSA_NULL;
         return M4ERR_ALLOC;
    }

    pInputFile = (M4OSA_Char *) infilePath; //pContext->mAudioSettings->pFile;
    //Delete this file later
    pOutputFile = (M4OSA_Char *) pTemp3gpFilePath;
    // Temp folder path for VSS use = ProjectPath
    pTempPath = (M4OSA_Char *) pContext->initParams.pTempPath;
    pInputFileType = (M4VIDEOEDITING_FileType)pContext->mAudioSettings->fileType;

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "TEMP_MCS_OUT_FILE_PATH len %d",
        strlen ((const char*)TEMP_MCS_OUT_FILE_PATH));
    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "pTemp3gpFilePath %s",
        pOutputFile);

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "M4MCS_open()");

    result = M4MCS_open(mcsContext, pInputFile,
        (M4VIDEOEDITING_FileType)pInputFileType,
        pOutputFile, pTempPath);
    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
        (M4NO_ERROR != result), result);
    if(needToBeLoaded == false) {
         free(pTemp3gpFilePath);
         pTemp3gpFilePath = M4OSA_NULL;
         M4MCS_abort(mcsContext);
         free(pOutputParams);
         pOutputParams = M4OSA_NULL;
         free(pEncodingParams);
         pEncodingParams = M4OSA_NULL;
         return result;
    }

    pOutputParams->OutputFileType
        = (M4VIDEOEDITING_FileType)M4VIDEOEDITING_kFileType_3GPP;
    // Set the video format.
    pOutputParams->OutputVideoFormat =
        (M4VIDEOEDITING_VideoFormat)M4VIDEOEDITING_kNoneVideo;//M4VIDEOEDITING_kNoneVideo;
    pOutputParams->outputVideoProfile = 1;
    pOutputParams->outputVideoLevel = 1;
    // Set the frame size.
    pOutputParams->OutputVideoFrameSize
        = (M4VIDEOEDITING_VideoFrameSize)M4VIDEOEDITING_kQCIF;
    // Set the frame rate.
    pOutputParams->OutputVideoFrameRate
        = (M4VIDEOEDITING_VideoFramerate)M4VIDEOEDITING_k5_FPS;

    // Set the audio format.
    pOutputParams->OutputAudioFormat
        = (M4VIDEOEDITING_AudioFormat)M4VIDEOEDITING_kAAC;
    // Set the audio sampling frequency.
    pOutputParams->OutputAudioSamplingFrequency =
        (M4VIDEOEDITING_AudioSamplingFrequency)M4VIDEOEDITING_k32000_ASF;
    // Set the audio mono.
    pOutputParams->bAudioMono = false;
    // Set the pcm file; null for now.
    pOutputParams->pOutputPCMfile = (M4OSA_Char *)pcmfilePath;
    //(M4OSA_Char *)"/sdcard/Output/AudioPcm.pcm";
    // Set the audio sampling frequency.
    pOutputParams->MediaRendering = (M4MCS_MediaRendering)M4MCS_kCropping;
    // new params after integrating MCS 2.0
    // Set the number of audio effects; 0 for now.
    pOutputParams->nbEffects = 0;
    // Set the audio effect; null for now.
    pOutputParams->pEffects = NULL;
    // Set the audio effect; null for now.
    pOutputParams->bDiscardExif = M4OSA_FALSE;
    // Set the audio effect; null for now.
    pOutputParams->bAdjustOrientation = M4OSA_FALSE;

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "M4MCS_setOutputParams()");
    result = M4MCS_setOutputParams(mcsContext, pOutputParams);
    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
                                        (M4NO_ERROR != result), result);
    if (needToBeLoaded == false) {
         free(pTemp3gpFilePath);
         pTemp3gpFilePath = M4OSA_NULL;
         M4MCS_abort(mcsContext);
         free(pOutputParams);
         pOutputParams = M4OSA_NULL;
         free(pEncodingParams);
         pEncodingParams = M4OSA_NULL;
        return result;
    }
    // Set the video bitrate.
    pEncodingParams->OutputVideoBitrate =
    (M4VIDEOEDITING_Bitrate)M4VIDEOEDITING_kUndefinedBitrate;
    // Set the audio bitrate.
    pEncodingParams->OutputAudioBitrate
        = (M4VIDEOEDITING_Bitrate)M4VIDEOEDITING_k128_KBPS;
    // Set the end cut time in milliseconds.
    pEncodingParams->BeginCutTime = 0;
    // Set the end cut time in milliseconds.
    pEncodingParams->EndCutTime = 0;
    // Set the output file size in bytes.
    pEncodingParams->OutputFileSize = 0;
    // Set video time scale.
    pEncodingParams->OutputVideoTimescale = 0;

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
                            "M4MCS_setEncodingParams()");
    result = M4MCS_setEncodingParams(mcsContext, pEncodingParams);
    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
        (M4NO_ERROR != result), result);
    if (needToBeLoaded == false) {
         free(pTemp3gpFilePath);
         pTemp3gpFilePath = M4OSA_NULL;
         M4MCS_abort(mcsContext);
         free(pOutputParams);
         pOutputParams = M4OSA_NULL;
         free(pEncodingParams);
         pEncodingParams = M4OSA_NULL;
         return result;
    }

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
                            "M4MCS_checkParamsAndStart()");
    result = M4MCS_checkParamsAndStart(mcsContext);
    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
        (M4NO_ERROR != result), result);
    if (needToBeLoaded == false) {
         free(pTemp3gpFilePath);
         pTemp3gpFilePath = M4OSA_NULL;
         M4MCS_abort(mcsContext);
         free(pOutputParams);
         pOutputParams = M4OSA_NULL;
         free(pEncodingParams);
         pEncodingParams = M4OSA_NULL;
        return result;
    }

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "M4MCS_step()");

    /*+ PROGRESS CB */
    M4OSA_UInt8 curProgress = 0;
    int         lastProgress = 0;

    LOGV("LVME_generateAudio Current progress is =%d", curProgress);
    pEnv->CallVoidMethod(pContext->engine,
            pContext->onProgressUpdateMethodId, 1/*task status*/,
            curProgress/*progress*/);
    do {
        result = M4MCS_step(mcsContext, &curProgress);

        if (result != M4NO_ERROR) {
            LOGV("LVME_generateAudio M4MCS_step returned 0x%x",result);

            if (result == M4MCS_WAR_TRANSCODING_DONE) {
                LOGV("LVME_generateAudio MCS process ended");

                // Send a progress notification.
                curProgress = 100;
                pEnv->CallVoidMethod(pContext->engine,
                    pContext->onProgressUpdateMethodId, 1/*task status*/,
                    curProgress);
                LOGV("LVME_generateAudio Current progress is =%d", curProgress);
            }
        } else {
            // Send a progress notification if needed
            if (curProgress != lastProgress) {
                lastProgress = curProgress;
                pEnv->CallVoidMethod(pContext->engine,
                    pContext->onProgressUpdateMethodId, 0/*task status*/,
                    curProgress/*progress*/);
                LOGV("LVME_generateAudio Current progress is =%d",curProgress);
            }
        }
    } while (result == M4NO_ERROR);
    /*- PROGRESS CB */

    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
        (M4MCS_WAR_TRANSCODING_DONE != result), result);
    if (needToBeLoaded == false) {
         free(pTemp3gpFilePath);
         pTemp3gpFilePath = M4OSA_NULL;
         M4MCS_abort(mcsContext);
         free(pOutputParams);
         pOutputParams = M4OSA_NULL;
         free(pEncodingParams);
         pEncodingParams = M4OSA_NULL;
        return result;
    }

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "M4MCS_abort()");
    result = M4MCS_abort(mcsContext);
    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
        (M4NO_ERROR != result), result);

    //pContext->mAudioSettings->pFile = pOutputParams->pOutputPCMfile;
    remove((const char *) pTemp3gpFilePath);
    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_generateAudio() EXIT ");

    if (pTemp3gpFilePath != M4OSA_NULL) {
        free(pTemp3gpFilePath);
    }
    if (pOutputParams != M4OSA_NULL) {
       free(pOutputParams);
    }
    if(pEncodingParams != M4OSA_NULL) {
       free(pEncodingParams);
    }
    return result;
}

static int removeAlphafromRGB8888 (
                        M4OSA_Char* pFramingFilePath,
                        M4xVSS_FramingStruct *pFramingCtx)
{
    M4OSA_UInt32 frameSize_argb = (pFramingCtx->width * pFramingCtx->height * 4); // aRGB data
    M4OSA_Context lImageFileFp  = M4OSA_NULL;
    M4OSA_ERR err = M4NO_ERROR;

    LOGV("removeAlphafromRGB8888: width %d", pFramingCtx->width);

    M4OSA_UInt8 *pTmpData = (M4OSA_UInt8*) M4OSA_32bitAlignedMalloc(frameSize_argb, M4VS, (M4OSA_Char*)"Image argb data");
    if (pTmpData == M4OSA_NULL) {
        LOGE("Failed to allocate memory for Image clip");
        return M4ERR_ALLOC;
    }

       /** Read the argb data from the passed file. */
    M4OSA_ERR lerr = M4OSA_fileReadOpen(&lImageFileFp, (M4OSA_Void *) pFramingFilePath, M4OSA_kFileRead);


    if ((lerr != M4NO_ERROR) || (lImageFileFp == M4OSA_NULL))
    {
        LOGE("removeAlphafromRGB8888: Can not open the file ");
        free(pTmpData);
        return M4ERR_FILE_NOT_FOUND;
    }


    lerr = M4OSA_fileReadData(lImageFileFp, (M4OSA_MemAddr8)pTmpData, &frameSize_argb);
    if (lerr != M4NO_ERROR)
    {
        LOGE("removeAlphafromRGB8888: can not read the data ");
        M4OSA_fileReadClose(lImageFileFp);
        free(pTmpData);
        return lerr;
    }
    M4OSA_fileReadClose(lImageFileFp);

    M4OSA_UInt32 frameSize = (pFramingCtx->width * pFramingCtx->height * 3); //Size of RGB 888 data.

    pFramingCtx->FramingRgb = (M4VIFI_ImagePlane*)M4OSA_32bitAlignedMalloc(
             sizeof(M4VIFI_ImagePlane), M4VS, (M4OSA_Char*)"Image clip RGB888 data");
    pFramingCtx->FramingRgb->pac_data = (M4VIFI_UInt8*)M4OSA_32bitAlignedMalloc(
             frameSize, M4VS, (M4OSA_Char*)"Image clip RGB888 data");

    if (pFramingCtx->FramingRgb == M4OSA_NULL)
    {
        LOGE("Failed to allocate memory for Image clip");
        free(pTmpData);
        return M4ERR_ALLOC;
    }

    /** Remove the alpha channel */
    for (size_t i = 0, j = 0; i < frameSize_argb; i++) {
        if ((i % 4) == 0) continue;
        pFramingCtx->FramingRgb->pac_data[j] = pTmpData[i];
        j++;
    }
    free(pTmpData);
    return M4NO_ERROR;
}

static void
videoEditor_populateSettings(
                JNIEnv*                 pEnv,
                jobject                 thiz,
                jobject                 settings,
                jobject                 object,
                jobject                 audioSettingObject)
{
    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
            "videoEditor_populateSettings()");

    bool                needToBeLoaded  = true;
    ManualEditContext*  pContext        = M4OSA_NULL;
    M4OSA_ERR           result          = M4NO_ERROR;
    jstring             strPath         = M4OSA_NULL;
    jstring             strPCMPath      = M4OSA_NULL;
    jobjectArray        propertiesClipsArray           = M4OSA_NULL;
    jobject             properties      = M4OSA_NULL;
    jint*               bitmapArray     =  M4OSA_NULL;
    jobjectArray        effectSettingsArray = M4OSA_NULL;
    jobject             effectSettings  = M4OSA_NULL;
    jintArray           pixelArray      = M4OSA_NULL;
    int width = 0;
    int height = 0;
    int nbOverlays = 0;
    int i,j = 0;
    int *pOverlayIndex = M4OSA_NULL;
    M4OSA_Char* pTempChar = M4OSA_NULL;

    // Add a code marker (the condition must always be true).
    ADD_CODE_MARKER_FUN(NULL != pEnv)

    // Validate the settings parameter.
    videoEditJava_checkAndThrowIllegalArgumentException(&needToBeLoaded, pEnv,
                                                (NULL == settings),
                                                "settings is null");
    // Get the context.
    pContext =
            (ManualEditContext*)videoEditClasses_getContext(&needToBeLoaded, pEnv, thiz);

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == pContext),
                                             "not initialized");
    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                 (M4OSA_NULL == pContext->mPreviewController),
                                 "not initialized");
    jclass mPreviewClipPropClazz = pEnv->FindClass(PREVIEW_PROPERTIES_CLASS_NAME);
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                     (M4OSA_NULL == mPreviewClipPropClazz),
                                     "not initialized");

    jfieldID fid = pEnv->GetFieldID(mPreviewClipPropClazz,"clipProperties",
            "[L"PROPERTIES_CLASS_NAME";"  );
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                     (M4OSA_NULL == fid),
                                     "not initialized");

    propertiesClipsArray = (jobjectArray)pEnv->GetObjectField(object, fid);
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                     (M4OSA_NULL == propertiesClipsArray),
                                     "not initialized");

    jclass engineClass = pEnv->FindClass(MANUAL_EDIT_ENGINE_CLASS_NAME);
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                     (M4OSA_NULL == engineClass),
                                     "not initialized");

    pContext->onPreviewProgressUpdateMethodId = pEnv->GetMethodID(engineClass,
            "onPreviewProgressUpdate",     "(IZZLjava/lang/String;I)V");
    // Check if the context is valid (required because the context is dereferenced).
    if (needToBeLoaded) {
        // Make sure that we are in a correct state.
        videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                             (pContext->state != ManualEditState_INITIALIZED),
                             "settings already loaded");
        if (needToBeLoaded) {
            // Retrieve the edit settings.
            if (pContext->pEditSettings != M4OSA_NULL) {
                videoEditClasses_freeEditSettings(&pContext->pEditSettings);
                pContext->pEditSettings = M4OSA_NULL;
            }
            videoEditClasses_getEditSettings(&needToBeLoaded, pEnv,
                settings, &pContext->pEditSettings,false);
        }
    }

    if (needToBeLoaded == false) {
        j = 0;
        while (j < pContext->pEditSettings->nbEffects)
        {
            if (pContext->pEditSettings->Effects[j].xVSS.pFramingFilePath != M4OSA_NULL) {
                if (pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer != M4OSA_NULL) {
                    free(pContext->pEditSettings->\
                    Effects[j].xVSS.pFramingBuffer);
                    pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer = M4OSA_NULL;
                }
            }
          j++;
        }
        return;
    }

    M4OSA_TRACE1_0("videoEditorC_getEditSettings done");

    pContext->previewFrameEditInfoId = pEnv->GetMethodID(engineClass,
        "previewFrameEditInfo", "(Ljava/lang/String;I)V");

    if ( pContext->pEditSettings != NULL )
    {
        // Check if the edit settings could be retrieved.
        jclass mEditClazz = pEnv->FindClass(EDIT_SETTINGS_CLASS_NAME);
        if(mEditClazz == M4OSA_NULL)
        {
            M4OSA_TRACE1_0("cannot find object field for mEditClazz");
            goto videoEditor_populateSettings_cleanup;
        }
        jclass mEffectsClazz = pEnv->FindClass(EFFECT_SETTINGS_CLASS_NAME);
        if(mEffectsClazz == M4OSA_NULL)
        {
            M4OSA_TRACE1_0("cannot find object field for mEffectsClazz");
            goto videoEditor_populateSettings_cleanup;
        }
        fid = pEnv->GetFieldID(mEditClazz,"effectSettingsArray", "[L"EFFECT_SETTINGS_CLASS_NAME";"  );
        if(fid == M4OSA_NULL)
        {
            M4OSA_TRACE1_0("cannot find field for effectSettingsArray Array");
            goto videoEditor_populateSettings_cleanup;
        }
        effectSettingsArray = (jobjectArray)pEnv->GetObjectField(settings, fid);
        if(effectSettingsArray == M4OSA_NULL)
        {
            M4OSA_TRACE1_0("cannot find object field for effectSettingsArray");
            goto videoEditor_populateSettings_cleanup;
        }

        //int overlayIndex[pContext->pEditSettings->nbEffects];
        if (pContext->pEditSettings->nbEffects > 0)
        {
            pOverlayIndex
            = (int*) M4OSA_32bitAlignedMalloc(pContext->pEditSettings->nbEffects * sizeof(int), 0,
                (M4OSA_Char*)"pOverlayIndex");
            if (pOverlayIndex == M4OSA_NULL) {
                videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
                    M4OSA_TRUE, M4ERR_ALLOC);
                goto videoEditor_populateSettings_cleanup;
            }
        }

        i = 0;
        j = 0;
        M4OSA_TRACE1_1("no of effects = %d",pContext->pEditSettings->nbEffects);
        while (j < pContext->pEditSettings->nbEffects)
        {
            if (pContext->pEditSettings->Effects[j].xVSS.pFramingFilePath != M4OSA_NULL)
            {
                pOverlayIndex[nbOverlays] = j;

                M4xVSS_FramingStruct *aFramingCtx = M4OSA_NULL;
                aFramingCtx
                = (M4xVSS_FramingStruct*)M4OSA_32bitAlignedMalloc(sizeof(M4xVSS_FramingStruct), M4VS,
                  (M4OSA_Char*)"M4xVSS_internalDecodeGIF: Context of the framing effect");
                if (aFramingCtx == M4OSA_NULL)
                {
                    M4OSA_TRACE1_0("Allocation error in videoEditor_populateSettings");
                    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
                        M4OSA_TRUE, M4ERR_ALLOC);
                    goto videoEditor_populateSettings_cleanup;
                }

                aFramingCtx->pCurrent = M4OSA_NULL; /* Only used by the first element of the chain */
                aFramingCtx->previousClipTime = -1;
                aFramingCtx->FramingYuv = M4OSA_NULL;
                aFramingCtx->FramingRgb = M4OSA_NULL;
                aFramingCtx->topleft_x
                    = pContext->pEditSettings->Effects[j].xVSS.topleft_x;
                aFramingCtx->topleft_y
                    = pContext->pEditSettings->Effects[j].xVSS.topleft_y;


                 VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "OF u_width %d",
                                        pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->u_width);
                 VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "OF u_height() %d",
                                        pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->u_height);
                 VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "OF rgbType() %d",
                                        pContext->pEditSettings->Effects[j].xVSS.rgbType);

                 aFramingCtx->width = pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->u_width;
                 aFramingCtx->height = pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->u_height;

                result = M4xVSS_internalConvertARGB888toYUV420_FrammingEffect(pContext->engineContext,
                    &(pContext->pEditSettings->Effects[j]),aFramingCtx,
                pContext->pEditSettings->Effects[j].xVSS.framingScaledSize);
                videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
                                            (M4NO_ERROR != result), result);
                if (needToBeLoaded == false) {
                    M4OSA_TRACE1_1("M4xVSS_internalConvertARGB888toYUV420_FrammingEffect returned 0x%x", result);
                    if (aFramingCtx != M4OSA_NULL) {
                        free(aFramingCtx);
                        aFramingCtx = M4OSA_NULL;
                    }
                    goto videoEditor_populateSettings_cleanup;
                }

                //framing buffers are resized to fit the output video resolution.
                pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->u_width =
                    aFramingCtx->FramingRgb->u_width;
                pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->u_height =
                    aFramingCtx->FramingRgb->u_height;

                VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "A framing Context aFramingCtx->width = %d",
                    aFramingCtx->FramingRgb->u_width);

                VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "A framing Context aFramingCtx->height = %d",
                    aFramingCtx->FramingRgb->u_height);


                width = pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->u_width;
                height = pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->u_height;

                //RGB 565
                pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->u_stride = width * 2;

                //for RGB565
                pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->u_topleft = 0;
                pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->pac_data =
                            (M4VIFI_UInt8 *)M4OSA_32bitAlignedMalloc(width*height*2,
                            0x00,(M4OSA_Char *)"pac_data buffer");

                if (pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer->pac_data == M4OSA_NULL) {
                    M4OSA_TRACE1_0("Failed to allocate memory for framing buffer");
                    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
                                            M4OSA_TRUE, M4ERR_ALLOC);
                    goto videoEditor_populateSettings_cleanup;
                }

                memcpy((void *)&pContext->pEditSettings->\
                    Effects[j].xVSS.pFramingBuffer->\
                    pac_data[0],(void *)&aFramingCtx->FramingRgb->pac_data[0],(width*height*2));

                //As of now rgb type is 565
                pContext->pEditSettings->Effects[j].xVSS.rgbType =
                    (M4VSS3GPP_RGBType) M4VSS3GPP_kRGB565;

                if (aFramingCtx->FramingYuv != M4OSA_NULL )
                {
                    if (aFramingCtx->FramingYuv[0].pac_data != M4OSA_NULL) {
                        free(aFramingCtx->FramingYuv[0].pac_data);
                        aFramingCtx->FramingYuv[0].pac_data = M4OSA_NULL;
                    }
                    if (aFramingCtx->FramingYuv[1].pac_data != M4OSA_NULL) {
                        free(aFramingCtx->FramingYuv[1].pac_data);
                        aFramingCtx->FramingYuv[1].pac_data = M4OSA_NULL;
                    }
                    if (aFramingCtx->FramingYuv[2].pac_data != M4OSA_NULL) {
                        free(aFramingCtx->FramingYuv[2].pac_data);
                        aFramingCtx->FramingYuv[2].pac_data = M4OSA_NULL;
                    }

                    free(aFramingCtx->FramingYuv);
                    aFramingCtx->FramingYuv = M4OSA_NULL;
                }
                if (aFramingCtx->FramingRgb->pac_data != M4OSA_NULL) {
                    free(aFramingCtx->FramingRgb->pac_data);
                    aFramingCtx->FramingRgb->pac_data = M4OSA_NULL;
                }
                if (aFramingCtx->FramingRgb != M4OSA_NULL) {
                    free(aFramingCtx->FramingRgb);
                    aFramingCtx->FramingRgb = M4OSA_NULL;
                }
                if (aFramingCtx != M4OSA_NULL) {
                    free(aFramingCtx);
                    aFramingCtx = M4OSA_NULL;
                }
                nbOverlays++;
            }
            j++;
        }

        // Check if the edit settings could be retrieved.
        M4OSA_TRACE1_1("total clips are = %d",pContext->pEditSettings->uiClipNumber);
        for (i = 0; i < pContext->pEditSettings->uiClipNumber; i++) {
            M4OSA_TRACE1_1("clip no = %d",i);
            properties = pEnv->GetObjectArrayElement(propertiesClipsArray, i);
            videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                (M4OSA_NULL == properties),
                "not initialized");
            if (needToBeLoaded) {
                getClipSetting(pEnv,properties, pContext->pEditSettings->pClipList[i]);
            } else {
                goto videoEditor_populateSettings_cleanup;
            }
        }

        if (needToBeLoaded) {
            // Log the edit settings.
            VIDEOEDIT_LOG_EDIT_SETTINGS(pContext->pEditSettings);
        }
    }
    /* free previous allocations , if any */
    if (pContext->mAudioSettings != M4OSA_NULL) {
        if (pContext->mAudioSettings->pFile != NULL) {
            free(pContext->mAudioSettings->pFile);
            pContext->mAudioSettings->pFile = M4OSA_NULL;
        }
        if (pContext->mAudioSettings->pPCMFilePath != NULL) {
            free(pContext->mAudioSettings->pPCMFilePath);
            pContext->mAudioSettings->pPCMFilePath = M4OSA_NULL;
        }
    }

    if (audioSettingObject != M4OSA_NULL) {
        jclass audioSettingClazz = pEnv->FindClass(AUDIO_SETTINGS_CLASS_NAME);
        videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                         (M4OSA_NULL == audioSettingClazz),
                                         "not initialized");

        videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                     (M4OSA_NULL == pContext->mAudioSettings),
                                     "not initialized");

        if (needToBeLoaded == false) {
            goto videoEditor_populateSettings_cleanup;
        }

        fid = pEnv->GetFieldID(audioSettingClazz,"bRemoveOriginal","Z");
        pContext->mAudioSettings->bRemoveOriginal =
            pEnv->GetBooleanField(audioSettingObject,fid);
        M4OSA_TRACE1_1("bRemoveOriginal = %d",pContext->mAudioSettings->bRemoveOriginal);

        fid = pEnv->GetFieldID(audioSettingClazz,"channels","I");
        pContext->mAudioSettings->uiNbChannels = pEnv->GetIntField(audioSettingObject,fid);
        M4OSA_TRACE1_1("uiNbChannels = %d",pContext->mAudioSettings->uiNbChannels);

        fid = pEnv->GetFieldID(audioSettingClazz,"Fs","I");
        pContext->mAudioSettings->uiSamplingFrequency = pEnv->GetIntField(audioSettingObject,fid);
        M4OSA_TRACE1_1("uiSamplingFrequency = %d",pContext->mAudioSettings->uiSamplingFrequency);

        fid = pEnv->GetFieldID(audioSettingClazz,"ExtendedFs","I");
        pContext->mAudioSettings->uiExtendedSamplingFrequency =
         pEnv->GetIntField(audioSettingObject,fid);
        M4OSA_TRACE1_1("uiExtendedSamplingFrequency = %d",
        pContext->mAudioSettings->uiExtendedSamplingFrequency);

        fid = pEnv->GetFieldID(audioSettingClazz,"startMs","J");
        pContext->mAudioSettings->uiAddCts
            = pEnv->GetLongField(audioSettingObject,fid);
        M4OSA_TRACE1_1("uiAddCts = %d",pContext->mAudioSettings->uiAddCts);

        fid = pEnv->GetFieldID(audioSettingClazz,"volume","I");
        pContext->mAudioSettings->uiAddVolume
            = pEnv->GetIntField(audioSettingObject,fid);
        M4OSA_TRACE1_1("uiAddVolume = %d",pContext->mAudioSettings->uiAddVolume);

        fid = pEnv->GetFieldID(audioSettingClazz,"loop","Z");
        pContext->mAudioSettings->bLoop
            = pEnv->GetBooleanField(audioSettingObject,fid);
        M4OSA_TRACE1_1("bLoop = %d",pContext->mAudioSettings->bLoop);

        fid = pEnv->GetFieldID(audioSettingClazz,"beginCutTime","J");
        pContext->mAudioSettings->beginCutMs
            = pEnv->GetLongField(audioSettingObject,fid);
        M4OSA_TRACE1_1("begin cut time = %d",pContext->mAudioSettings->beginCutMs);

        fid = pEnv->GetFieldID(audioSettingClazz,"endCutTime","J");
        pContext->mAudioSettings->endCutMs
            = pEnv->GetLongField(audioSettingObject,fid);
        M4OSA_TRACE1_1("end cut time = %d",pContext->mAudioSettings->endCutMs);

        fid = pEnv->GetFieldID(audioSettingClazz,"fileType","I");
        pContext->mAudioSettings->fileType
            = pEnv->GetIntField(audioSettingObject,fid);
        M4OSA_TRACE1_1("fileType = %d",pContext->mAudioSettings->fileType);

        fid = pEnv->GetFieldID(audioSettingClazz,"pFile","Ljava/lang/String;");
        strPath = (jstring)pEnv->GetObjectField(audioSettingObject,fid);
        pTempChar = (M4OSA_Char*)pEnv->GetStringUTFChars(strPath, M4OSA_NULL);
        if (pTempChar != NULL) {
            pContext->mAudioSettings->pFile = (M4OSA_Char*) M4OSA_32bitAlignedMalloc(
                (M4OSA_UInt32)(strlen((const char*)pTempChar))+1 /* +1 for NULL termination */, 0,
                (M4OSA_Char*)"strPath allocation " );
            if (pContext->mAudioSettings->pFile != M4OSA_NULL) {
                memcpy((void *)pContext->mAudioSettings->pFile ,
                    (void *)pTempChar , strlen((const char*)pTempChar));
                ((M4OSA_Int8 *)(pContext->mAudioSettings->pFile))[strlen((const char*)pTempChar)] = '\0';
                pEnv->ReleaseStringUTFChars(strPath,(const char *)pTempChar);
            } else {
                pEnv->ReleaseStringUTFChars(strPath,(const char *)pTempChar);
                VIDEOEDIT_LOG_ERROR(ANDROID_LOG_INFO, "VIDEO_EDITOR",
                    "regenerateAudio() Malloc failed for pContext->mAudioSettings->pFile ");
                videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
                    M4OSA_TRUE, M4ERR_ALLOC);
                goto videoEditor_populateSettings_cleanup;
            }
        }
        M4OSA_TRACE1_1("file name = %s",pContext->mAudioSettings->pFile);
        VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEOEDITOR", "regenerateAudio() file name = %s",\
        pContext->mAudioSettings->pFile);

        fid = pEnv->GetFieldID(audioSettingClazz,"pcmFilePath","Ljava/lang/String;");
        strPCMPath = (jstring)pEnv->GetObjectField(audioSettingObject,fid);
        pTempChar = (M4OSA_Char*)pEnv->GetStringUTFChars(strPCMPath, M4OSA_NULL);
        if (pTempChar != NULL) {
            pContext->mAudioSettings->pPCMFilePath = (M4OSA_Char*) M4OSA_32bitAlignedMalloc(
                (M4OSA_UInt32)(strlen((const char*)pTempChar))+1 /* +1 for NULL termination */, 0,
                (M4OSA_Char*)"strPCMPath allocation " );
            if (pContext->mAudioSettings->pPCMFilePath != M4OSA_NULL) {
                memcpy((void *)pContext->mAudioSettings->pPCMFilePath ,
                    (void *)pTempChar , strlen((const char*)pTempChar));
                ((M4OSA_Int8 *)(pContext->mAudioSettings->pPCMFilePath))[strlen((const char*)pTempChar)] = '\0';
                pEnv->ReleaseStringUTFChars(strPCMPath,(const char *)pTempChar);
            } else {
                pEnv->ReleaseStringUTFChars(strPCMPath,(const char *)pTempChar);
                VIDEOEDIT_LOG_ERROR(ANDROID_LOG_INFO, "VIDEO_EDITOR",
                    "regenerateAudio() Malloc failed for pContext->mAudioSettings->pPCMFilePath ");
                videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
                    M4OSA_TRUE, M4ERR_ALLOC);
                goto videoEditor_populateSettings_cleanup;
            }
        }
        VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEOEDITOR", "pPCMFilePath -- %s ",\
        pContext->mAudioSettings->pPCMFilePath);

        fid = pEnv->GetFieldID(engineClass,"mRegenerateAudio","Z");
        bool regenerateAudio = pEnv->GetBooleanField(thiz,fid);

        VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEOEDITOR", "regenerateAudio -- %d ",\
        regenerateAudio);

        if (regenerateAudio) {
            M4OSA_TRACE1_0("Calling Generate Audio now");
            result = videoEditor_generateAudio(pEnv,
                        pContext,
                        (M4OSA_Char*)pContext->mAudioSettings->pFile,
                        (M4OSA_Char*)pContext->mAudioSettings->pPCMFilePath);

            videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
                (M4NO_ERROR != result), result);
            if (needToBeLoaded == false) {
                goto videoEditor_populateSettings_cleanup;
            }

            regenerateAudio = false;
            pEnv->SetBooleanField(thiz,fid,regenerateAudio);
        }

        /* Audio mix and duck */
        fid = pEnv->GetFieldID(audioSettingClazz,"ducking_threshold","I");
        pContext->mAudioSettings->uiInDucking_threshold
            = pEnv->GetIntField(audioSettingObject,fid);

        M4OSA_TRACE1_1("ducking threshold = %d",
            pContext->mAudioSettings->uiInDucking_threshold);

        fid = pEnv->GetFieldID(audioSettingClazz,"ducking_lowVolume","I");
        pContext->mAudioSettings->uiInDucking_lowVolume
            = pEnv->GetIntField(audioSettingObject,fid);

        M4OSA_TRACE1_1("ducking lowVolume = %d",
            pContext->mAudioSettings->uiInDucking_lowVolume);

        fid = pEnv->GetFieldID(audioSettingClazz,"bInDucking_enable","Z");
        pContext->mAudioSettings->bInDucking_enable
            = pEnv->GetBooleanField(audioSettingObject,fid);
        M4OSA_TRACE1_1("ducking lowVolume = %d",
            pContext->mAudioSettings->bInDucking_enable);

    } else {
        if (pContext->mAudioSettings != M4OSA_NULL) {
            pContext->mAudioSettings->pFile = M4OSA_NULL;
            pContext->mAudioSettings->pPCMFilePath = M4OSA_NULL;
            pContext->mAudioSettings->bRemoveOriginal = 0;
            pContext->mAudioSettings->uiNbChannels = 0;
            pContext->mAudioSettings->uiSamplingFrequency = 0;
            pContext->mAudioSettings->uiExtendedSamplingFrequency = 0;
            pContext->mAudioSettings->uiAddCts = 0;
            pContext->mAudioSettings->uiAddVolume = 0;
            pContext->mAudioSettings->beginCutMs = 0;
            pContext->mAudioSettings->endCutMs = 0;
            pContext->mAudioSettings->fileType = 0;
            pContext->mAudioSettings->bLoop = 0;
            pContext->mAudioSettings->uiInDucking_lowVolume  = 0;
            pContext->mAudioSettings->bInDucking_enable  = 0;
            pContext->mAudioSettings->uiBTChannelCount  = 0;
            pContext->mAudioSettings->uiInDucking_threshold = 0;

            fid = pEnv->GetFieldID(engineClass,"mRegenerateAudio","Z");
            bool regenerateAudio = pEnv->GetBooleanField(thiz,fid);
            if (!regenerateAudio) {
                regenerateAudio = true;
                pEnv->SetBooleanField(thiz,fid,regenerateAudio);
            }
        }
    }

    if (pContext->pEditSettings != NULL)
    {
        result = pContext->mPreviewController->loadEditSettings(pContext->pEditSettings,
            pContext->mAudioSettings);
        videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
                                            (M4NO_ERROR != result), result);

        if (needToBeLoaded) {
            pContext->mPreviewController->setJniCallback((void*)pContext,
            (jni_progress_callback_fct)jniPreviewProgressCallback);
        }
    }

videoEditor_populateSettings_cleanup:
        j = 0;
        while (j < nbOverlays)
        {
            if (pContext->pEditSettings->Effects[pOverlayIndex[j]].xVSS.pFramingBuffer->pac_data != \
                M4OSA_NULL) {
                free(pContext->pEditSettings->\
                Effects[pOverlayIndex[j]].xVSS.pFramingBuffer->pac_data);
                pContext->pEditSettings->\
                Effects[pOverlayIndex[j]].xVSS.pFramingBuffer->pac_data = M4OSA_NULL;
            }
            j++;
        }

        j = 0;
        while (j < pContext->pEditSettings->nbEffects)
        {
            if (pContext->pEditSettings->Effects[j].xVSS.pFramingFilePath != M4OSA_NULL) {
                if (pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer != M4OSA_NULL) {
                    free(pContext->pEditSettings->\
                    Effects[j].xVSS.pFramingBuffer);
                    pContext->pEditSettings->Effects[j].xVSS.pFramingBuffer = M4OSA_NULL;
                }
            }
          j++;
        }

    if (pOverlayIndex != M4OSA_NULL)
    {
        free(pOverlayIndex);
        pOverlayIndex = M4OSA_NULL;
    }
    return;
}

static void
videoEditor_startPreview(
                JNIEnv*                 pEnv,
                jobject                 thiz,
                jobject                 mSurface,
                jlong                   fromMs,
                jlong                   toMs,
                jint                    callbackInterval,
                jboolean                loop)
{
    bool needToBeLoaded = true;
    M4OSA_ERR result = M4NO_ERROR;
    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_startPreview()");

    ManualEditContext* pContext = M4OSA_NULL;
    // Get the context.
    pContext = (ManualEditContext*)videoEditClasses_getContext(&needToBeLoaded, pEnv, thiz);

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == pContext),
                                             "not initialized");

    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                     (M4OSA_NULL == pContext->mAudioSettings),
                                     "not initialized");
    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                 (M4OSA_NULL == pContext->mPreviewController),
                                 "not initialized");

    // Validate the mSurface parameter.
    videoEditJava_checkAndThrowIllegalArgumentException(&needToBeLoaded, pEnv,
                                                (NULL == mSurface),
                                                "mSurface is null");

    jclass surfaceClass = pEnv->FindClass("android/view/Surface");
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == surfaceClass),
                                             "not initialized");
    //jfieldID surface_native = pEnv->GetFieldID(surfaceClass, "mSurface", "I");
    jfieldID surface_native
        = pEnv->GetFieldID(surfaceClass, ANDROID_VIEW_SURFACE_JNI_ID, "I");

    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == surface_native),
                                             "not initialized");

    Surface* const p = (Surface*)pEnv->GetIntField(mSurface, surface_native);

    sp<Surface> previewSurface = sp<Surface>(p);
    // Validate the mSurface's mNativeSurface field
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                                (NULL == previewSurface.get()),
                                                "mNativeSurface is null");

    result =  pContext->mPreviewController->setSurface(previewSurface);
    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv,
        (M4NO_ERROR != result), result);
    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "fromMs=%ld, toMs=%ld",
        (M4OSA_UInt32)fromMs, (M4OSA_Int32)toMs);

    result = pContext->mPreviewController->startPreview((M4OSA_UInt32)fromMs,
                                                (M4OSA_Int32)toMs,
                                                (M4OSA_UInt16)callbackInterval,
                                                (M4OSA_Bool)loop);
    videoEditJava_checkAndThrowRuntimeException(&needToBeLoaded, pEnv, (M4NO_ERROR != result), result);
}


static jobject
videoEditor_getProperties(
                JNIEnv*                             pEnv,
                jobject                             thiz,
                jstring                             file)
{
    jobject object = M4OSA_NULL;
    jclass clazz = pEnv->FindClass(PROPERTIES_CLASS_NAME);
    jfieldID fid;
    bool needToBeLoaded = true;
    ManualEditContext* pContext = M4OSA_NULL;
    M4OSA_ERR          result   = M4NO_ERROR;
    int profile = 0;
    int level = 0;
    int videoFormat = 0;

    // Get the context.
    pContext = (ManualEditContext*)videoEditClasses_getContext(&needToBeLoaded, pEnv, thiz);

    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == clazz),
                                             "not initialized");

    object = videoEditProp_getProperties(pEnv,thiz,file);

    if (object != M4OSA_NULL) {
        fid = pEnv->GetFieldID(clazz,"profile","I");
        profile = pEnv->GetIntField(object,fid);
        fid = pEnv->GetFieldID(clazz,"level","I");
        level = pEnv->GetIntField(object,fid);
        fid = pEnv->GetFieldID(clazz,"videoFormat","I");
        videoFormat = pEnv->GetIntField(object,fid);

        result = checkClipVideoProfileAndLevel(pContext->decoders, videoFormat, profile, level);

        fid = pEnv->GetFieldID(clazz,"profileSupported","Z");
        if (M4VSS3GPP_ERR_EDITING_UNSUPPORTED_VIDEO_PROFILE == result) {
            pEnv->SetBooleanField(object,fid,false);
        }

        fid = pEnv->GetFieldID(clazz,"levelSupported","Z");
        if (M4VSS3GPP_ERR_EDITING_UNSUPPORTED_VIDEO_LEVEL == result) {
            pEnv->SetBooleanField(object,fid,false);
        }
    }
    return object;

}
static int videoEditor_getPixels(
                    JNIEnv*                     env,
                    jobject                     thiz,
                    jstring                     path,
                    jintArray                   pixelArray,
                    M4OSA_UInt32                width,
                    M4OSA_UInt32                height,
                    M4OSA_UInt32                timeMS)
{

    M4OSA_ERR       err = M4NO_ERROR;
    M4OSA_Context   mContext = M4OSA_NULL;
    jint*           m_dst32 = M4OSA_NULL;


    // Add a text marker (the condition must always be true).
    ADD_TEXT_MARKER_FUN(NULL != env)

    const char *pString = env->GetStringUTFChars(path, NULL);
    if (pString == M4OSA_NULL) {
        if (env != NULL) {
            jniThrowException(env, "java/lang/RuntimeException", "Input string null");
        }
        return M4ERR_ALLOC;
    }

    err = ThumbnailOpen(&mContext,(const M4OSA_Char*)pString, M4OSA_FALSE);
    if (err != M4NO_ERROR || mContext == M4OSA_NULL) {
        if (pString != NULL) {
            env->ReleaseStringUTFChars(path, pString);
        }
        if (env != NULL) {
            jniThrowException(env, "java/lang/RuntimeException", "ThumbnailOpen failed");
        }
    }

    m_dst32 = env->GetIntArrayElements(pixelArray, NULL);

    err = ThumbnailGetPixels32(mContext, (M4OSA_Int32 *)m_dst32, width,height,&timeMS,0);
    if (err != M4NO_ERROR ) {
        if (env != NULL) {
            jniThrowException(env, "java/lang/RuntimeException",\
                "ThumbnailGetPixels32 failed");
        }
    }
    env->ReleaseIntArrayElements(pixelArray, m_dst32, 0);

    ThumbnailClose(mContext);
    if (pString != NULL) {
        env->ReleaseStringUTFChars(path, pString);
    }

    return timeMS;
}

static int videoEditor_getPixelsList(
                JNIEnv*                 env,
                jobject                 thiz,
                jstring                 path,
                jintArray               pixelArray,
                M4OSA_UInt32            width,
                M4OSA_UInt32            height,
                M4OSA_UInt32            noOfThumbnails,
                jlong                   startTime,
                jlong                   endTime,
                jintArray               indexArray,
                jobject                 callback)
{

    M4OSA_ERR           err = M4NO_ERROR;
    M4OSA_Context       mContext = M4OSA_NULL;

    const char *pString = env->GetStringUTFChars(path, NULL);
    if (pString == M4OSA_NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Input string null");
        return M4ERR_ALLOC;
    }

    err = ThumbnailOpen(&mContext,(const M4OSA_Char*)pString, M4OSA_FALSE);
    if (err != M4NO_ERROR || mContext == M4OSA_NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "ThumbnailOpen failed");
        if (pString != NULL) {
            env->ReleaseStringUTFChars(path, pString);
        }
        return err;
    }

    jlong duration = (endTime - startTime);
    M4OSA_UInt32 tolerance = duration / (2 * noOfThumbnails);
    jint* m_dst32 = env->GetIntArrayElements(pixelArray, NULL);
    jint* indices = env->GetIntArrayElements(indexArray, NULL);
    jsize len = env->GetArrayLength(indexArray);

    jclass cls = env->GetObjectClass(callback);
    jmethodID mid = env->GetMethodID(cls, "onThumbnail", "(I)V");

    for (int i = 0; i < len; i++) {
        int k = indices[i];
        M4OSA_UInt32 timeMS = startTime;
        timeMS += (2 * k + 1) * duration / (2 * noOfThumbnails);
        err = ThumbnailGetPixels32(mContext, ((M4OSA_Int32 *)m_dst32),
            width, height, &timeMS, tolerance);
        if (err != M4NO_ERROR) {
            break;
        }
        env->CallVoidMethod(callback, mid, (jint)k);
        if (env->ExceptionCheck()) {
            err = M4ERR_ALLOC;
            break;
        }
    }

    env->ReleaseIntArrayElements(pixelArray, m_dst32, 0);
    env->ReleaseIntArrayElements(indexArray, indices, 0);

    ThumbnailClose(mContext);
    if (pString != NULL) {
        env->ReleaseStringUTFChars(path, pString);
    }

    if (err != M4NO_ERROR && !env->ExceptionCheck()) {
        jniThrowException(env, "java/lang/RuntimeException",\
                "ThumbnailGetPixels32 failed");
    }

    return err;
}

static M4OSA_ERR
videoEditor_toUTF8Fct(
                M4OSA_Void*                         pBufferIn,
                M4OSA_UInt8*                        pBufferOut,
                M4OSA_UInt32*                       bufferOutSize)
{
    M4OSA_ERR    result = M4NO_ERROR;
    M4OSA_UInt32 length = 0;

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_toUTF8Fct()");

    // Determine the length of the input buffer.
    if (M4OSA_NULL != pBufferIn)
    {
        length = strlen((const char *)pBufferIn);
    }

    // Check if the output buffer is large enough to hold the input buffer.
    if ((*bufferOutSize) > length)
    {
        // Check if the input buffer is not M4OSA_NULL.
        if (M4OSA_NULL != pBufferIn)
        {
            // Copy the temp path, ignore the result.
            M4OSA_chrNCopy((M4OSA_Char *)pBufferOut, (M4OSA_Char *)pBufferIn, length);
        }
        else
        {
            // Set the output buffer to an empty string.
            (*(M4OSA_Char *)pBufferOut) = 0;
        }
    }
    else
    {
        // The buffer is too small.
        result = M4xVSSWAR_BUFFER_OUT_TOO_SMALL;
    }

    // Return the buffer output size.
    (*bufferOutSize) = length + 1;

    // Return the result.
    return(result);
}

static M4OSA_ERR
videoEditor_fromUTF8Fct(
                M4OSA_UInt8*                        pBufferIn,
                M4OSA_Void*                         pBufferOut,
                M4OSA_UInt32*                       bufferOutSize)
{
    M4OSA_ERR    result = M4NO_ERROR;
    M4OSA_UInt32 length = 0;

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_fromUTF8Fct()");

    // Determine the length of the input buffer.
    if (M4OSA_NULL != pBufferIn)
    {
        length = strlen((const char *)pBufferIn);
    }

    // Check if the output buffer is large enough to hold the input buffer.
    if ((*bufferOutSize) > length)
    {
        // Check if the input buffer is not M4OSA_NULL.
        if (M4OSA_NULL != pBufferIn)
        {
            // Copy the temp path, ignore the result.
            M4OSA_chrNCopy((M4OSA_Char *)pBufferOut, (M4OSA_Char *)pBufferIn, length);
        }
        else
        {
            // Set the output buffer to an empty string.
            (*(M4OSA_Char *)pBufferOut) = 0;
        }
    }
    else
    {
        // The buffer is too small.
        result = M4xVSSWAR_BUFFER_OUT_TOO_SMALL;
    }

    // Return the buffer output size.
    (*bufferOutSize) = length + 1;

    // Return the result.
    return(result);
}

static M4OSA_ERR
videoEditor_getTextRgbBufferFct(
                M4OSA_Void*                         pRenderingData,
                M4OSA_Void*                         pTextBuffer,
                M4OSA_UInt32                        textBufferSize,
                M4VIFI_ImagePlane**                 pOutputPlane)
{
    M4OSA_ERR result = M4NO_ERROR;

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_getTextRgbBufferFct()");

    // Return the result.
    return(result);
}

static void
videoEditor_callOnProgressUpdate(
                ManualEditContext*                  pContext,
                int                                 task,
                int                                 progress)
{
    JNIEnv* pEnv = NULL;


    // Attach the current thread.
    pContext->pVM->AttachCurrentThread(&pEnv, NULL);


    // Call the on completion callback.
    pEnv->CallVoidMethod(pContext->engine, pContext->onProgressUpdateMethodId,
     videoEditJava_getEngineCToJava(task), progress);


    // Detach the current thread.
    pContext->pVM->DetachCurrentThread();
}

static void
videoEditor_freeContext(
                JNIEnv*                             pEnv,
                ManualEditContext**                 ppContext)
{
    ManualEditContext* pContext = M4OSA_NULL;

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_freeContext");

    // Set the context pointer.
    pContext = (*ppContext);

    // Check if the context was set.
    if (M4OSA_NULL != pContext)
    {
        // Check if a global reference to the engine object was set.
        if (NULL != pContext->engine)
        {
            // Free the global reference.
            pEnv->DeleteGlobalRef(pContext->engine);
            pContext->engine = NULL;
        }

        // Check if the temp path was set.
        if (M4OSA_NULL != pContext->initParams.pTempPath)
        {
            // Free the memory allocated for the temp path.
            videoEditOsal_free(pContext->initParams.pTempPath);
            pContext->initParams.pTempPath = M4OSA_NULL;
        }

        // Check if the file writer was set.
        if (M4OSA_NULL != pContext->initParams.pFileWritePtr)
        {
            // Free the memory allocated for the file writer.
            videoEditOsal_free(pContext->initParams.pFileWritePtr);
            pContext->initParams.pFileWritePtr = M4OSA_NULL;
        }

        // Check if the file reader was set.
        if (M4OSA_NULL != pContext->initParams.pFileReadPtr)
        {
            // Free the memory allocated for the file reader.
            videoEditOsal_free(pContext->initParams.pFileReadPtr);
            pContext->initParams.pFileReadPtr = M4OSA_NULL;
        }

        // Free the memory allocated for the context.
        videoEditOsal_free(pContext);
        pContext = M4OSA_NULL;

        // Reset the context pointer.
        (*ppContext) = M4OSA_NULL;
    }
}

static jobject
videoEditor_getVersion(
                JNIEnv*                             pEnv,
                jobject                             thiz)
{
    bool           isSuccessful          = true;
    jobject        version         = NULL;
    M4_VersionInfo versionInfo     = {0, 0, 0, 0};
    M4OSA_ERR      result          = M4NO_ERROR;

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_getVersion()");

    versionInfo.m_structSize = sizeof(versionInfo);
    versionInfo.m_major = VIDEOEDITOR_VERSION_MAJOR;
    versionInfo.m_minor = VIDEOEDITOR_VERSION_MINOR;
    versionInfo.m_revision = VIDEOEDITOR_VERSION_REVISION;

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_getVersion() major %d,\
     minor %d, revision %d", versionInfo.m_major, versionInfo.m_minor, versionInfo.m_revision);

    // Create a version object.
    videoEditClasses_createVersion(&isSuccessful, pEnv, &versionInfo, &version);

    // Return the version object.
    return(version);
}

static void
videoEditor_init(
                JNIEnv*                             pEnv,
                jobject                             thiz,
                jstring                             tempPath,
                jstring                             libraryPath)
{
    bool                  initialized            = true;
    ManualEditContext*    pContext               = M4OSA_NULL;
    VideoEditJava_EngineMethodIds methodIds              = {NULL};
    M4OSA_Char*           pLibraryPath           = M4OSA_NULL;
    M4OSA_Char*           pTextRendererPath      = M4OSA_NULL;
    M4OSA_UInt32          textRendererPathLength = 0;
    M4OSA_ERR             result                 = M4NO_ERROR;

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_init()");

    // Add a text marker (the condition must always be true).
    ADD_TEXT_MARKER_FUN(NULL != pEnv)

    // Get the context.
    pContext = (ManualEditContext*)videoEditClasses_getContext(&initialized, pEnv, thiz);

    // Get the engine method ids.
    videoEditJava_getEngineMethodIds(&initialized, pEnv, &methodIds);

    // Validate the tempPath parameter.
    videoEditJava_checkAndThrowIllegalArgumentException(&initialized, pEnv,
                                                (NULL == tempPath),
                                                "tempPath is null");

    // Make sure that the context was not set already.
    videoEditJava_checkAndThrowIllegalStateException(&initialized, pEnv,
                                             (M4OSA_NULL != pContext),
                                             "already initialized");

    // Check if the initialization succeeded (required because of dereferencing of psContext,
    // and freeing when initialization fails).
    if (initialized)
    {
        // Allocate a new context.
        pContext = new ManualEditContext;

        // Check if the initialization succeeded (required because of dereferencing of psContext).
        //if (initialized)
        if (pContext != NULL)
        {
            // Set the state to not initialized.
            pContext->state = ManualEditState_NOT_INITIALIZED;

            // Allocate a file read pointer structure.
            pContext->initParams.pFileReadPtr =
             (M4OSA_FileReadPointer*)videoEditOsal_alloc(&initialized, pEnv,
              sizeof(M4OSA_FileReadPointer), "FileReadPointer");

            // Allocate a file write pointer structure.
            pContext->initParams.pFileWritePtr =
             (M4OSA_FileWriterPointer*)videoEditOsal_alloc(&initialized, pEnv,
              sizeof(M4OSA_FileWriterPointer), "FileWriterPointer");

            // Get the temp path.
            M4OSA_Char* tmpString =
                (M4OSA_Char *)videoEditJava_getString(&initialized, pEnv, tempPath,
                NULL, M4OSA_NULL);
            pContext->initParams.pTempPath = (M4OSA_Char *)
                 M4OSA_32bitAlignedMalloc(strlen((const char *)tmpString) + 1, 0x0,
                                                 (M4OSA_Char *)"tempPath");
            //initialize the first char. so that strcat works.
            M4OSA_Char *ptmpChar = (M4OSA_Char*)pContext->initParams.pTempPath;
            ptmpChar[0] = 0x00;
            strncat((char *)pContext->initParams.pTempPath, (const char *)tmpString,
                (size_t)strlen((const char *)tmpString));
            strncat((char *)pContext->initParams.pTempPath, (const char *)"/", (size_t)1);
            free(tmpString);
            pContext->mIsUpdateOverlay = false;
            pContext->mOverlayFileName = NULL;
            pContext->decoders = NULL;
        }

        // Check if the initialization succeeded
        // (required because of dereferencing of pContext, pFileReadPtr and pFileWritePtr).
        if (initialized)
        {

            // Initialize the OSAL file system function pointers.
            videoEditOsal_getFilePointers(pContext->initParams.pFileReadPtr ,
                                          pContext->initParams.pFileWritePtr);

            // Set the UTF8 conversion functions.
            pContext->initParams.pConvToUTF8Fct   = videoEditor_toUTF8Fct;
            pContext->initParams.pConvFromUTF8Fct = videoEditor_fromUTF8Fct;

            // Set the callback method ids.
            pContext->onProgressUpdateMethodId = methodIds.onProgressUpdate;

            // Set the virtual machine.
            pEnv->GetJavaVM(&(pContext->pVM));

            // Create a global reference to the engine object.
            pContext->engine = pEnv->NewGlobalRef(thiz);

            // Check if the global reference could be created.
            videoEditJava_checkAndThrowRuntimeException(&initialized, pEnv,
             (NULL == pContext->engine), M4NO_ERROR);
        }

        // Check if the initialization succeeded (required because of dereferencing of pContext).
        if (initialized)
        {
            // Log the API call.
            VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "M4xVSS_Init()");

            // Initialize the visual studio library.
            result = M4xVSS_Init(&pContext->engineContext, &pContext->initParams);

            // Log the result.
            VIDEOEDIT_LOG_RESULT(ANDROID_LOG_INFO, "VIDEO_EDITOR",
             videoEditOsal_getResultString(result));

            // Check if the library could be initialized.
            videoEditJava_checkAndThrowRuntimeException(&initialized, pEnv,
             (M4NO_ERROR != result), result);

            // Get platform video decoder capablities.
            result = M4xVSS_getVideoDecoderCapabilities(&pContext->decoders);

            videoEditJava_checkAndThrowRuntimeException(&initialized, pEnv,
             (M4NO_ERROR != result), result);
        }

        if(initialized)
        {
            pContext->mPreviewController = new VideoEditorPreviewController();
            videoEditJava_checkAndThrowIllegalStateException(&initialized, pEnv,
                                 (M4OSA_NULL == pContext->mPreviewController),
                                 "not initialized");
            pContext->mAudioSettings =
             (M4xVSS_AudioMixingSettings *)
             M4OSA_32bitAlignedMalloc(sizeof(M4xVSS_AudioMixingSettings),0x0,
             (M4OSA_Char *)"mAudioSettings");
            videoEditJava_checkAndThrowIllegalStateException(&initialized, pEnv,
                                     (M4OSA_NULL == pContext->mAudioSettings),
                                     "not initialized");
            pContext->mAudioSettings->pFile = M4OSA_NULL;
            pContext->mAudioSettings->pPCMFilePath = M4OSA_NULL;
            pContext->mAudioSettings->bRemoveOriginal = 0;
            pContext->mAudioSettings->uiNbChannels = 0;
            pContext->mAudioSettings->uiSamplingFrequency = 0;
            pContext->mAudioSettings->uiExtendedSamplingFrequency = 0;
            pContext->mAudioSettings->uiAddCts = 0;
            pContext->mAudioSettings->uiAddVolume = 0;
            pContext->mAudioSettings->beginCutMs = 0;
            pContext->mAudioSettings->endCutMs = 0;
            pContext->mAudioSettings->fileType = 0;
            pContext->mAudioSettings->bLoop = 0;
            pContext->mAudioSettings->uiInDucking_lowVolume  = 0;
            pContext->mAudioSettings->bInDucking_enable  = 0;
            pContext->mAudioSettings->uiBTChannelCount  = 0;
            pContext->mAudioSettings->uiInDucking_threshold = 0;
        }
        // Check if the library could be initialized.
        if (initialized)
        {
            // Set the state to initialized.
            pContext->state = ManualEditState_INITIALIZED;
        }

        // Set the context.
        videoEditClasses_setContext(&initialized, pEnv, thiz, (void* )pContext);
        pLibraryPath = M4OSA_NULL;

        pContext->pEditSettings = M4OSA_NULL;
        // Cleanup if anything went wrong during initialization.
        if (!initialized)
        {
            // Free the context.
            videoEditor_freeContext(pEnv, &pContext);
        }
    }
}

/*+ PROGRESS CB */
static
M4OSA_ERR videoEditor_processClip(
                            JNIEnv*  pEnv,
                            jobject  thiz,
                            int      unuseditemID) {

    bool               loaded           = true;
    ManualEditContext* pContext         = NULL;
    M4OSA_UInt8        progress         = 0;
    M4OSA_UInt8        progressBase     = 0;
    M4OSA_UInt8        lastProgress     = 0;
    M4OSA_ERR          result           = M4NO_ERROR;

    // Get the context.
    pContext = (ManualEditContext*)videoEditClasses_getContext(&loaded, pEnv, thiz);

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&loaded, pEnv,
                                             (M4OSA_NULL == pContext),
                                             "not initialized");

    // We start in Analyzing state
    pContext->state = ManualEditState_INITIALIZED;
    M4OSA_ERR          completionResult = M4VSS3GPP_WAR_ANALYZING_DONE;
    ManualEditState    completionState  = ManualEditState_OPENED;
    ManualEditState    errorState       = ManualEditState_ANALYZING_ERROR;

    // While analyzing progress goes from 0 to 10 (except Kenburn clip
    // generation, which goes from 0 to 50)
    progressBase     = 0;

    // Set the text rendering function.
    if (M4OSA_NULL != pContext->pTextRendererFunction)
    {
        // Use the text renderer function in the library.
        pContext->pEditSettings->xVSS.pTextRenderingFct = pContext->pTextRendererFunction;
    }
    else
    {
        // Use the internal text renderer function.
        pContext->pEditSettings->xVSS.pTextRenderingFct = videoEditor_getTextRgbBufferFct;
    }

    // Send the command.
    LOGV("videoEditor_processClip ITEM %d Calling M4xVSS_SendCommand()", unuseditemID);
    result = M4xVSS_SendCommand(pContext->engineContext, pContext->pEditSettings);
    LOGV("videoEditor_processClip ITEM %d M4xVSS_SendCommand() returned 0x%x",
        unuseditemID, (unsigned int) result);

    // Remove warnings indications (we only care about errors here)
    if ((result == M4VSS3GPP_WAR_TRANSCODING_NECESSARY)
        || (result == M4VSS3GPP_WAR_OUTPUTFILESIZE_EXCEED)) {
        result = M4NO_ERROR;
    }

    // Send the first progress indication (=0)
    LOGV("VERY FIRST PROGRESS videoEditor_processClip ITEM %d Progress indication %d",
        unuseditemID, progress);
    pEnv->CallVoidMethod(pContext->engine, pContext->onProgressUpdateMethodId,
        unuseditemID, progress);

    // Check if a task is being performed.
    // ??? ADD STOPPING MECHANISM
    LOGV("videoEditor_processClip Entering processing loop");
    M4OSA_UInt8 prevReportedProgress = 0;
    while((result == M4NO_ERROR)
        &&(pContext->state!=ManualEditState_SAVED)
        &&(pContext->state!=ManualEditState_STOPPING)) {

            // Perform the next processing step.
            //LOGV("LVME_processClip Entering M4xVSS_Step()");
            result = M4xVSS_Step(pContext->engineContext, &progress);

            if (progress != prevReportedProgress) {
                prevReportedProgress = progress;
                // Log the 1 % .. 100 % progress after processing.
                if (M4OSA_TRUE ==
                    pContext->pEditSettings->pClipList[0]->xVSS.isPanZoom) {
                    // For KenBurn clip generation, return 0 to 50
                    // for Analysis phase and 50 to 100 for Saving phase
                    progress = progressBase + progress/2;
                } else {
                    // For export/transition clips, 0 to 10 for Analysis phase
                    // and 10 to 100 for Saving phase
                    if (ManualEditState_INITIALIZED == pContext->state) {
                        progress = 0.1*progress;
                    } else {
                        progress = progressBase + 0.9*progress;
                    }
                }

                if (progress > lastProgress)
                {
                    // Send a progress notification.
                    LOGV("videoEditor_processClip ITEM %d Progress indication %d",
                        unuseditemID, progress);
                    pEnv->CallVoidMethod(pContext->engine,
                        pContext->onProgressUpdateMethodId,
                        unuseditemID, progress);
                    lastProgress = progress;
                }
            }

            // Check if processing has been completed.
            if (result == completionResult)
            {
                // Set the state to the completions state.
                pContext->state = completionState;
                LOGV("videoEditor_processClip ITEM %d STATE changed to %d",
                    unuseditemID, pContext->state);

                // Reset progress indication, as we switch to next state
                lastProgress = 0;

                // Reset error code, as we start a new round of processing
                result = M4NO_ERROR;

                // Check if we are analyzing input
                if (pContext->state == ManualEditState_OPENED) {
                    // File is opened, we must start saving it
                    LOGV("videoEditor_processClip Calling M4xVSS_SaveStart()");
                    result = M4xVSS_SaveStart(pContext->engineContext,
                        (M4OSA_Char*)pContext->pEditSettings->pOutputFile,
                        (M4OSA_UInt32)pContext->pEditSettings->uiOutputPathSize);
                    LOGV("videoEditor_processClip ITEM %d SaveStart() returned 0x%x",
                        unuseditemID, (unsigned int) result);

                    // Set the state to saving.
                    pContext->state  = ManualEditState_SAVING;
                    completionState  = ManualEditState_SAVED;
                    completionResult = M4VSS3GPP_WAR_SAVING_DONE;
                    errorState       = ManualEditState_SAVING_ERROR;

                    // While saving, progress goes from 10 to 100
                    // except for Kenburn clip which goes from 50 to 100
                    if (M4OSA_TRUE ==
                            pContext->pEditSettings->pClipList[0]->xVSS.isPanZoom) {
                        progressBase = 50;
                    } else {
                        progressBase     = 10;
                    }
                }
                // Check if we encoding is ongoing
                else if (pContext->state == ManualEditState_SAVED) {

                    // Send a progress notification.
                    progress = 100;
                    LOGV("videoEditor_processClip ITEM %d Last progress indication %d",
                        unuseditemID, progress);
                    pEnv->CallVoidMethod(pContext->engine,
                        pContext->onProgressUpdateMethodId,
                        unuseditemID, progress);


                    // Stop the encoding.
                    LOGV("videoEditor_processClip Calling M4xVSS_SaveStop()");
                    result = M4xVSS_SaveStop(pContext->engineContext);
                    LOGV("videoEditor_processClip M4xVSS_SaveStop() returned 0x%x", result);
                }
                // Other states are unexpected
                else {
                    result = M4ERR_STATE;
                    LOGE("videoEditor_processClip ITEM %d State ERROR 0x%x",
                        unuseditemID, (unsigned int) result);
                }
            }

            // Check if an error occurred.
            if (result != M4NO_ERROR)
            {
                // Set the state to the error state.
                pContext->state = errorState;

                // Log the result.
                LOGE("videoEditor_processClip ITEM %d Processing ERROR 0x%x",
                    unuseditemID, (unsigned int) result);
            }
    }

    // Return the error result
    LOGE("videoEditor_processClip ITEM %d END 0x%x", unuseditemID, (unsigned int) result);
    return result;
}
/*+ PROGRESS CB */

static int
videoEditor_generateClip(
                JNIEnv*                             pEnv,
                jobject                             thiz,
                jobject                             settings) {
    bool               loaded   = true;
    ManualEditContext* pContext = M4OSA_NULL;
    M4OSA_ERR          result   = M4NO_ERROR;

    LOGV("videoEditor_generateClip START");

    // Get the context.
    pContext = (ManualEditContext*)videoEditClasses_getContext(&loaded, pEnv, thiz);

    Mutex::Autolock autoLock(pContext->mLock);

    // Validate the settings parameter.
    videoEditJava_checkAndThrowIllegalArgumentException(&loaded, pEnv,
                                                (NULL == settings),
                                                "settings is null");

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&loaded, pEnv,
                                             (M4OSA_NULL == pContext),
                                             "not initialized");

    // Load the clip settings
    LOGV("videoEditor_generateClip Calling videoEditor_loadSettings");
    videoEditor_loadSettings(pEnv, thiz, settings);
    LOGV("videoEditor_generateClip videoEditor_loadSettings returned");

    // Generate the clip
    LOGV("videoEditor_generateClip Calling LVME_processClip");
    result = videoEditor_processClip(pEnv, thiz, 0 /*item id is unused*/);
    LOGV("videoEditor_generateClip videoEditor_processClip returned 0x%x", result);

    if (pContext->state != ManualEditState_INITIALIZED) {
        // Free up memory (whatever the result)
        videoEditor_unloadSettings(pEnv, thiz);
    }

    LOGV("videoEditor_generateClip END 0x%x", (unsigned int) result);
    return result;
}

static void
videoEditor_loadSettings(
                JNIEnv*                             pEnv,
                jobject                             thiz,
                jobject                             settings)
{
    bool               needToBeLoaded   = true;
    ManualEditContext* pContext = M4OSA_NULL;

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_loadSettings()");

    // Add a code marker (the condition must always be true).
    ADD_CODE_MARKER_FUN(NULL != pEnv)

    // Get the context.
    pContext = (ManualEditContext*)videoEditClasses_getContext(&needToBeLoaded,
                                                                pEnv, thiz);

    // Validate the settings parameter.
    videoEditJava_checkAndThrowIllegalArgumentException(&needToBeLoaded, pEnv,
                                                (NULL == settings),
                                                "settings is null");

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                                             (M4OSA_NULL == pContext),
                                             "not initialized");

    // Check if the context is valid (required because the context is dereferenced).
    if (needToBeLoaded)
    {
        // Make sure that we are in a correct state.
        videoEditJava_checkAndThrowIllegalStateException(&needToBeLoaded, pEnv,
                             (pContext->state != ManualEditState_INITIALIZED),
                             "settings already loaded");

        // Retrieve the edit settings.
        if(pContext->pEditSettings != M4OSA_NULL) {
            videoEditClasses_freeEditSettings(&pContext->pEditSettings);
            pContext->pEditSettings = M4OSA_NULL;
        }
        videoEditClasses_getEditSettings(&needToBeLoaded, pEnv, settings,
            &pContext->pEditSettings,true);
    }

    // Check if the edit settings could be retrieved.
    if (needToBeLoaded)
    {
        // Log the edit settings.
        VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "inside load settings");
        VIDEOEDIT_LOG_EDIT_SETTINGS(pContext->pEditSettings);
    }
    LOGV("videoEditor_loadSettings END");
}



static void
videoEditor_unloadSettings(
                JNIEnv*                             pEnv,
                jobject                             thiz)
{
    bool               needToBeUnLoaded = true;
    ManualEditContext* pContext = M4OSA_NULL;
    M4OSA_ERR          result   = M4NO_ERROR;

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_unloadSettings()");

    // Get the context.
    pContext = (ManualEditContext*)videoEditClasses_getContext(&needToBeUnLoaded, pEnv, thiz);

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&needToBeUnLoaded, pEnv,
                                             (M4OSA_NULL == pContext),
                                             "not initialized");

    // Check if the context is valid (required because the context is dereferenced).
    if (needToBeUnLoaded)
    {
        LOGV("videoEditor_unloadSettings state %d", pContext->state);
        // Make sure that we are in a correct state.
        videoEditJava_checkAndThrowIllegalStateException(&needToBeUnLoaded, pEnv,
                     ((pContext->state != ManualEditState_ANALYZING      ) &&
                      (pContext->state != ManualEditState_ANALYZING_ERROR) &&
                      (pContext->state != ManualEditState_OPENED         ) &&
                      (pContext->state != ManualEditState_SAVING_ERROR   ) &&
                      (pContext->state != ManualEditState_SAVED          ) &&
                      (pContext->state != ManualEditState_STOPPING       ) ),
                     "videoEditor_unloadSettings no load settings in progress");
    }

    // Check if we are in a correct state.
    if (needToBeUnLoaded)
    {
        // Check if the thread could be stopped.
        if (needToBeUnLoaded)
        {
            // Close the command.
            LOGV("videoEditor_unloadSettings Calling M4xVSS_CloseCommand()");
            result = M4xVSS_CloseCommand(pContext->engineContext);
            LOGV("videoEditor_unloadSettings M4xVSS_CloseCommand() returned 0x%x",
                (unsigned int)result);

            // Check if the command could be closed.
            videoEditJava_checkAndThrowRuntimeException(&needToBeUnLoaded, pEnv,
             (M4NO_ERROR != result), result);
        }

        // Check if the command could be closed.
        if (needToBeUnLoaded)
        {
            // Free the edit settings.
            //videoEditClasses_freeEditSettings(&pContext->pEditSettings);

            // Reset the thread result.
            pContext->threadResult = M4NO_ERROR;

            // Reset the thread progress.
            pContext->threadProgress = 0;

            // Set the state to initialized.
            pContext->state = ManualEditState_INITIALIZED;
        }
    }
}

static void
videoEditor_stopEncoding(
                JNIEnv*                             pEnv,
                jobject                             thiz)
{
    bool               stopped  = true;
    ManualEditContext* pContext = M4OSA_NULL;
    M4OSA_ERR          result   = M4NO_ERROR;

    LOGV("videoEditor_stopEncoding START");

    // Get the context.
    pContext = (ManualEditContext*)videoEditClasses_getContext(&stopped, pEnv, thiz);

    // Change state and get Lock
    // This will ensure the generateClip function exits
    pContext->state = ManualEditState_STOPPING;
    Mutex::Autolock autoLock(pContext->mLock);

    // Make sure that the context was set.
    videoEditJava_checkAndThrowIllegalStateException(&stopped, pEnv,
                                             (M4OSA_NULL == pContext),
                                             "not initialized");

    if (stopped) {

        // Check if the command should be closed.
        if (pContext->state != ManualEditState_INITIALIZED)
        {
            // Close the command.
            LOGV("videoEditor_stopEncoding Calling M4xVSS_CloseCommand()");
            result = M4xVSS_CloseCommand(pContext->engineContext);
            LOGV("videoEditor_stopEncoding M4xVSS_CloseCommand() returned 0x%x",
                (unsigned int)result);
        }

        // Check if the command could be closed.
        videoEditJava_checkAndThrowRuntimeException(&stopped, pEnv,
            (M4NO_ERROR != result), result);

        // Free the edit settings.
        videoEditClasses_freeEditSettings(&pContext->pEditSettings);

        // Set the state to initialized.
        pContext->state = ManualEditState_INITIALIZED;
    }

}

static void
videoEditor_release(
                JNIEnv*                             pEnv,
                jobject                             thiz)
{
    bool               released = true;
    ManualEditContext* pContext = M4OSA_NULL;
    M4OSA_ERR          result   = M4NO_ERROR;

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "videoEditor_release()");

    // Add a text marker (the condition must always be true).
    ADD_TEXT_MARKER_FUN(NULL != pEnv)

    // Get the context.
    pContext = (ManualEditContext*)videoEditClasses_getContext(&released, pEnv, thiz);

    // If context is not set, return (we consider release already happened)
    if (pContext == NULL) {
        LOGV("videoEditor_release Nothing to do, context is aleady NULL");
        return;
    }


    // Check if the context is valid (required because the context is dereferenced).
    if (released)
    {
        if (pContext->state != ManualEditState_INITIALIZED)
        {
            // Change state and get Lock
            // This will ensure the generateClip function exits if it is running
            pContext->state = ManualEditState_STOPPING;
            Mutex::Autolock autoLock(pContext->mLock);
        }

        // Reset the context.
        videoEditClasses_setContext(&released, pEnv, thiz, (void *)M4OSA_NULL);

        // Check if the command should be closed.
        if (pContext->state != ManualEditState_INITIALIZED)
        {
            // Close the command.
            LOGV("videoEditor_release Calling M4xVSS_CloseCommand() state =%d",
                pContext->state);
            result = M4xVSS_CloseCommand(pContext->engineContext);
            LOGV("videoEditor_release M4xVSS_CloseCommand() returned 0x%x",
                (unsigned int)result);

            // Check if the command could be closed.
            videoEditJava_checkAndThrowRuntimeException(&released, pEnv,
                (M4NO_ERROR != result), result);
        }

        // Cleanup the engine.
        LOGV("videoEditor_release Calling M4xVSS_CleanUp()");
        result = M4xVSS_CleanUp(pContext->engineContext);
        LOGV("videoEditor_release M4xVSS_CleanUp() returned 0x%x", (unsigned int)result);

        // Check if the cleanup succeeded.
        videoEditJava_checkAndThrowRuntimeException(&released, pEnv,
            (M4NO_ERROR != result), result);

        // Free the edit settings.
        videoEditClasses_freeEditSettings(&pContext->pEditSettings);
        pContext->pEditSettings = M4OSA_NULL;


        if(pContext->mPreviewController != M4OSA_NULL)
        {
            delete pContext->mPreviewController;
            pContext->mPreviewController = M4OSA_NULL;
        }

        // Free the mAudioSettings context.
        if(pContext->mAudioSettings != M4OSA_NULL)
        {
            if (pContext->mAudioSettings->pFile != NULL) {
                free(pContext->mAudioSettings->pFile);
                pContext->mAudioSettings->pFile = M4OSA_NULL;
            }
            if (pContext->mAudioSettings->pPCMFilePath != NULL) {
                free(pContext->mAudioSettings->pPCMFilePath);
                pContext->mAudioSettings->pPCMFilePath = M4OSA_NULL;
            }

            free(pContext->mAudioSettings);
            pContext->mAudioSettings = M4OSA_NULL;
        }
        // Free video Decoders capabilities
        if (pContext->decoders != M4OSA_NULL) {
            VideoDecoder *pDecoder = NULL;
            VideoComponentCapabilities *pComponents = NULL;
            int32_t decoderNumber = pContext->decoders->decoderNumber;
            if (pContext->decoders->decoder != NULL &&
                decoderNumber > 0) {
                pDecoder = pContext->decoders->decoder;
                for (int32_t k = 0; k < decoderNumber; k++) {
                    // free each component
                    LOGV("decoder index :%d",k);
                    if (pDecoder != NULL &&
                        pDecoder->component != NULL &&
                        pDecoder->componentNumber > 0) {
                        LOGV("component number %d",pDecoder->componentNumber);
                        int32_t componentNumber =
                           pDecoder->componentNumber;

                        pComponents = pDecoder->component;
                        for (int32_t i = 0; i< componentNumber; i++) {
                            LOGV("component index :%d",i);
                            if (pComponents != NULL &&
                                pComponents->profileLevel != NULL) {
                                free(pComponents->profileLevel);
                                pComponents->profileLevel = NULL;
                            }
                            pComponents++;
                        }
                        free(pDecoder->component);
                        pDecoder->component = NULL;
                    }

                    pDecoder++;
                }
                free(pContext->decoders->decoder);
                pContext->decoders->decoder = NULL;
            }
            free(pContext->decoders);
            pContext->decoders = NULL;
        }

        videoEditor_freeContext(pEnv, &pContext);
    }
}

static int
videoEditor_registerManualEditMethods(
                JNIEnv*                             pEnv)
{
    int result = -1;

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
     "videoEditor_registerManualEditMethods()");

    // Look up the engine class
    jclass engineClazz = pEnv->FindClass(MANUAL_EDIT_ENGINE_CLASS_NAME);

    // Clear any resulting exceptions.
    pEnv->ExceptionClear();

    // Check if the engine class was found.
    if (NULL != engineClazz)
    {
        // Register all the methods.
        if (pEnv->RegisterNatives(engineClazz, gManualEditMethods,
                sizeof(gManualEditMethods) / sizeof(gManualEditMethods[0])) == JNI_OK)
        {
            // Success.
            result = 0;
        }
    }

    // Return the result.
    return(result);
}

/*******Audio Graph*******/

static M4OSA_UInt32 getDecibelSound(M4OSA_UInt32 value)
{
    int dbSound = 1;

    if (value == 0) return 0;

    if (value > 0x4000 && value <= 0x8000) // 32768
        dbSound = 90;
    else if (value > 0x2000 && value <= 0x4000) // 16384
        dbSound = 84;
    else if (value > 0x1000 && value <= 0x2000) // 8192
        dbSound = 78;
    else if (value > 0x0800 && value <= 0x1000) // 4028
        dbSound = 72;
    else if (value > 0x0400 && value <= 0x0800) // 2048
        dbSound = 66;
    else if (value > 0x0200 && value <= 0x0400) // 1024
        dbSound = 60;
    else if (value > 0x0100 && value <= 0x0200) // 512
        dbSound = 54;
    else if (value > 0x0080 && value <= 0x0100) // 256
        dbSound = 48;
    else if (value > 0x0040 && value <= 0x0080) // 128
        dbSound = 42;
    else if (value > 0x0020 && value <= 0x0040) // 64
        dbSound = 36;
    else if (value > 0x0010 && value <= 0x0020) // 32
        dbSound = 30;
    else if (value > 0x0008 && value <= 0x0010) //16
        dbSound = 24;
    else if (value > 0x0007 && value <= 0x0008) //8
        dbSound = 24;
    else if (value > 0x0003 && value <= 0x0007) // 4
        dbSound = 18;
    else if (value > 0x0001 && value <= 0x0003) //2
        dbSound = 12;
    else if (value > 0x000 && value == 0x0001) // 1
        dbSound = 6;
    else
        dbSound = 0;

    return dbSound;
}

typedef struct
{
    M4OSA_UInt8      *m_dataAddress;
    M4OSA_UInt32    m_bufferSize;
} M4AM_Buffer;


M4OSA_UInt8 logLookUp[256] = {
0,120,137,146,154,159,163,167,171,173,176,178,181,182,184,186,188,189,190,192,193,
194,195,196,198,199,199,200,201,202,203,204,205,205,206,207,207,208,209,209,210,
211,211,212,212,213,213,214,215,215,216,216,216,217,217,218,218,219,219,220,220,
220,221,221,222,222,222,223,223,223,224,224,224,225,225,225,226,226,226,227,227,
227,228,228,228,229,229,229,229,230,230,230,230,231,231,231,232,232,232,232,233,
233,233,233,233,234,234,234,234,235,235,235,235,236,236,236,236,236,237,237,237,
237,237,238,238,238,238,238,239,239,239,239,239,240,240,240,240,240,240,241,241,
241,241,241,241,242,242,242,242,242,242,243,243,243,243,243,243,244,244,244,244,
244,244,245,245,245,245,245,245,245,246,246,246,246,246,246,246,247,247,247,247,
247,247,247,247,248,248,248,248,248,248,248,249,249,249,249,249,249,249,249,250,
250,250,250,250,250,250,250,250,251,251,251,251,251,251,251,251,252,252,252,252,
252,252,252,252,252,253,253,253,253,253,253,253,253,253,253,254,254,254,254,254,
254,254,254,254,255,255,255,255,255,255,255,255,255,255,255};

M4OSA_ERR M4MA_generateAudioGraphFile(JNIEnv* pEnv, M4OSA_Char* pInputFileURL,
                     M4OSA_Char* pOutFileURL,
                     M4OSA_UInt32 samplesPerValue,
                     M4OSA_UInt32 channels,
                     M4OSA_UInt32 frameDuration,
                     ManualEditContext* pContext)
{
    M4OSA_ERR           err;
    M4OSA_Context       outFileHandle = M4OSA_NULL;
    M4OSA_Context       inputFileHandle = M4OSA_NULL;
    M4AM_Buffer         bufferIn = {0, 0};
    M4OSA_UInt32        peakVolumeDbValue = 0;
    M4OSA_UInt32        samplesCountInBytes= 0 , numBytesToRead = 0, index = 0;
    M4OSA_UInt32        writeCount = 0, samplesCountBigEndian = 0, volumeValuesCount = 0;
    M4OSA_Int32         seekPos = 0;
    M4OSA_UInt32        fileSize = 0;
    M4OSA_UInt32        totalBytesRead = 0;
    M4OSA_UInt32        prevProgress = 0;
    bool                threadStarted = true;

    int dbValue = 0;
    M4OSA_Int16 *ptr16 ;

    jclass engineClass = pEnv->FindClass(MANUAL_EDIT_ENGINE_CLASS_NAME);
    videoEditJava_checkAndThrowIllegalStateException(&threadStarted, pEnv,
                                             (M4OSA_NULL == engineClass),
                                             "not initialized");

    /* register the call back function pointer */
    pContext->onAudioGraphProgressUpdateMethodId =
            pEnv->GetMethodID(engineClass, "onAudioGraphExtractProgressUpdate", "(IZ)V");


    /* ENTER */
    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "ENTER - M4MA_generateAudioGraphFile");
    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR",
            "Audio Graph samplesPerValue %d channels %d", samplesPerValue, channels);

    /******************************************************************************
        OPEN INPUT AND OUTPUT FILES
    *******************************************************************************/
    err = M4OSA_fileReadOpen (&inputFileHandle, pInputFileURL, M4OSA_kFileRead);
    if (inputFileHandle == M4OSA_NULL) {
        VIDEOEDIT_LOG_ERROR(ANDROID_LOG_INFO, "VIDEO_EDITOR",
            "M4MA_generateAudioGraphFile: Cannot open input file 0x%lx", err);
        return err;
    }

    /* get the file size for progress */
    err = M4OSA_fileReadGetOption(inputFileHandle, M4OSA_kFileReadGetFileSize,
                                (M4OSA_Void**)&fileSize);
    if ( err != M4NO_ERROR) {
        //LVMEL_LOG_ERROR("M4MA_generateAudioGraphFile : File write failed \n");
        jniThrowException(pEnv, "java/lang/IOException", "file size get option failed");
        //return -1;
    }

    err = M4OSA_fileWriteOpen (&outFileHandle,(M4OSA_Char*) pOutFileURL,
        M4OSA_kFileCreate | M4OSA_kFileWrite);
    if (outFileHandle == M4OSA_NULL) {
        if (inputFileHandle != NULL)
        {
            M4OSA_fileReadClose(inputFileHandle);
        }
        return err;
    }

    /******************************************************************************
        PROCESS THE SAMPLES
    *******************************************************************************/
    samplesCountInBytes = (samplesPerValue * sizeof(M4OSA_UInt16) * channels);

    bufferIn.m_dataAddress = (M4OSA_UInt8*)M4OSA_32bitAlignedMalloc(samplesCountInBytes*sizeof(M4OSA_UInt16), 0,
    (M4OSA_Char*)"AudioGraph" );
    if ( bufferIn.m_dataAddress != M4OSA_NULL) {
        bufferIn.m_bufferSize = samplesCountInBytes*sizeof(M4OSA_UInt16);
    } else {
        VIDEOEDIT_LOG_ERROR(ANDROID_LOG_INFO, "VIDEO_EDITOR",
            "M4MA_generateAudioGraphFile: Malloc failed for bufferIn.m_dataAddress 0x%lx",
            M4ERR_ALLOC);
        return M4ERR_ALLOC;
    }
    /* sample to be converted to BIG endian ; store the frame duration */
    samplesCountBigEndian = ((frameDuration>>24)&0xff) | // move byte 3 to byte 0
                    ((frameDuration<<8)&0xff0000) | // move byte 1 to byte 2
                    ((frameDuration>>8)&0xff00) | // move byte 2 to byte 1
                    ((frameDuration<<24)&0xff000000); // byte 0 to byte 3

    /* write the samples per value supplied to out file */
    err = M4OSA_fileWriteData (outFileHandle, (M4OSA_MemAddr8)&samplesCountBigEndian,
        sizeof(M4OSA_UInt32) );
    if (err != M4NO_ERROR) {
        jniThrowException(pEnv, "java/lang/IOException", "file write failed");
    }


    /* write UIn32 value 0 for no of values as place holder */
    samplesCountBigEndian = 0; /* reusing local var */
    err = M4OSA_fileWriteData (outFileHandle, (M4OSA_MemAddr8)&samplesCountBigEndian,
        sizeof(M4OSA_UInt32) );
    if (err != M4NO_ERROR) {
        jniThrowException(pEnv, "java/lang/IOException", "file write failed");
    }

    /* loop until EOF */
    do
    {
        memset((void *)bufferIn.m_dataAddress,0,bufferIn.m_bufferSize);

        numBytesToRead = samplesCountInBytes;

        err =  M4OSA_fileReadData(  inputFileHandle,
                                    (M4OSA_MemAddr8)bufferIn.m_dataAddress,
                                    &numBytesToRead );

        if (err != M4NO_ERROR) {
            // if out value of bytes-read is 0, break
            if ( numBytesToRead == 0) {
                VIDEOEDIT_LOG_ERROR(ANDROID_LOG_INFO, "VIDEO_EDITOR", "numBytesToRead 0x%lx",
                numBytesToRead);
                break; /* stop if file is empty or EOF */
            }
        }

        ptr16 = (M4OSA_Int16*)bufferIn.m_dataAddress;

        peakVolumeDbValue = 0;
        index = 0;

        // loop through half the lenght frame bytes read 'cause its 16 bits samples
        while (index < (numBytesToRead / 2)) {
            /* absolute values of 16 bit sample */
            if (ptr16[index] < 0) {
                ptr16[index] = -(ptr16[index]);
            }
            peakVolumeDbValue = (peakVolumeDbValue > (M4OSA_UInt32)ptr16[index] ?\
             peakVolumeDbValue : (M4OSA_UInt32)ptr16[index]);
            index++;
        }

        // move 7 bits , ignore sign bit
        dbValue = (peakVolumeDbValue >> 7);
        dbValue = logLookUp[(M4OSA_UInt8)dbValue];

        err = M4OSA_fileWriteData (outFileHandle, (M4OSA_MemAddr8)&dbValue, sizeof(M4OSA_UInt8) );
        if (err != M4NO_ERROR) {
            VIDEOEDIT_LOG_ERROR(ANDROID_LOG_INFO, "VIDEO_EDITOR",
             "M4MA_generateAudioGraphFile : File write failed");
            break;
        }

        volumeValuesCount ++;
        totalBytesRead += numBytesToRead;

        if ((((totalBytesRead*100)/fileSize)) != prevProgress) {
            if ( (pContext->threadProgress != prevProgress) && (prevProgress != 0 )) {
                //pContext->threadProgress = prevProgress;
                //onWveformProgressUpdateMethodId(prevProgress, 0);
                //LVME_callAudioGraphOnProgressUpdate(pContext, 0, prevProgress);
            pEnv->CallVoidMethod(pContext->engine,
                                 pContext->onAudioGraphProgressUpdateMethodId,
                                 prevProgress, 0);
            VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "pContext->threadProgress %d",
                             prevProgress);
            }
        }
        prevProgress = (((totalBytesRead*100)/fileSize));

    } while (numBytesToRead != 0);

    VIDEOEDIT_LOG_ERROR(ANDROID_LOG_INFO, "VIDEO_EDITOR", "loop 0x%lx", volumeValuesCount);

    /* if some error occured in fwrite */
    if (numBytesToRead != 0) {
        //err = -1;
        jniThrowException(pEnv, "java/lang/IOException", "numBytesToRead != 0 ; file write failed");
    }

    /* write the count in place holder after seek */
    seekPos = sizeof(M4OSA_UInt32);
    err = M4OSA_fileWriteSeek(outFileHandle, M4OSA_kFileSeekBeginning,
            &seekPos /* after samples per value */);
    if ( err != M4NO_ERROR) {
        jniThrowException(pEnv, "java/lang/IOException", "file seek failed");
    } else {
        volumeValuesCount = ((volumeValuesCount>>24)&0xff) | // move byte 3 to byte 0
                    ((volumeValuesCount<<8)&0xff0000) | // move byte 1 to byte 2
                    ((volumeValuesCount>>8)&0xff00) |  // move byte 2 to byte 1
                    ((volumeValuesCount<<24)&0xff000000); // byte 0 to byte 3

        err = M4OSA_fileWriteData (outFileHandle, (M4OSA_MemAddr8)&volumeValuesCount,
                                    sizeof(M4OSA_UInt32) );
        if ( err != M4NO_ERROR) {
            jniThrowException(pEnv, "java/lang/IOException", "file write failed");
        }
    }

    /******************************************************************************
    CLOSE AND FREE ALLOCATIONS
    *******************************************************************************/
    free(bufferIn.m_dataAddress);
    M4OSA_fileReadClose(inputFileHandle);
    M4OSA_fileWriteClose(outFileHandle);
    /* final finish callback */
    pEnv->CallVoidMethod(pContext->engine, pContext->onAudioGraphProgressUpdateMethodId, 100, 0);

    /* EXIT */
    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "EXIT - M4MA_generateAudioGraphFile");

    return err;
}

static int videoEditor_generateAudioWaveFormSync (JNIEnv*  pEnv, jobject thiz,
                                                  jstring pcmfilePath,
                                                  jstring outGraphfilePath,
                                                  jint frameDuration, jint channels,
                                                  jint samplesCount)
{
    M4OSA_ERR result = M4NO_ERROR;
    ManualEditContext* pContext = M4OSA_NULL;
    bool needToBeLoaded = true;
    const char *pPCMFilePath, *pStringOutAudioGraphFile;

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR",
        "videoEditor_generateAudioWaveFormSync() ");

    /* Get the context. */
    pContext = (ManualEditContext*)videoEditClasses_getContext(&needToBeLoaded, pEnv, thiz);
    if (pContext == M4OSA_NULL) {
        VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR",
            "videoEditor_generateAudioWaveFormSync() - pContext is NULL ");
    }

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR",
        "videoEditor_generateAudioWaveFormSync Retrieving pStringOutAudioGraphFile");

    pPCMFilePath = pEnv->GetStringUTFChars(pcmfilePath, NULL);
    if (pPCMFilePath == M4OSA_NULL) {
        jniThrowException(pEnv, "java/lang/RuntimeException",
            "Input string PCMFilePath is null");
        result = M4ERR_PARAMETER;
        goto out;
    }

    pStringOutAudioGraphFile = pEnv->GetStringUTFChars(outGraphfilePath, NULL);
    if (pStringOutAudioGraphFile == M4OSA_NULL) {
        jniThrowException(pEnv, "java/lang/RuntimeException",
            "Input string outGraphfilePath is null");
        result = M4ERR_PARAMETER;
        goto out2;
    }

    VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR",
        "videoEditor_generateAudioWaveFormSync Generate the waveform data %s %d %d %d",
        pStringOutAudioGraphFile, frameDuration, channels, samplesCount);

    /* Generate the waveform */
    result = M4MA_generateAudioGraphFile(pEnv, (M4OSA_Char*) pPCMFilePath,
        (M4OSA_Char*) pStringOutAudioGraphFile,
        (M4OSA_UInt32) samplesCount,
        (M4OSA_UInt32) channels,
        (M4OSA_UInt32)frameDuration,
        pContext);

    pEnv->ReleaseStringUTFChars(outGraphfilePath, pStringOutAudioGraphFile);

out2:
    if (pPCMFilePath != NULL) {
        pEnv->ReleaseStringUTFChars(pcmfilePath, pPCMFilePath);
    }

out:
    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR",
        "videoEditor_generateAudioWaveFormSync pContext->bSkipState ");

    return result;
}

/******** End Audio Graph *******/
jint JNI_OnLoad(
                JavaVM*                             pVm,
                void*                               pReserved)
{
    void* pEnv         = NULL;
    bool  needToBeInitialized = true;
    jint  result      = -1;

    VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", "JNI_OnLoad()");

    // Add a text marker (the condition must always be true).
    ADD_TEXT_MARKER_FUN(NULL != pVm)

    // Check the JNI version.
    if (pVm->GetEnv(&pEnv, JNI_VERSION_1_4) == JNI_OK)
    {
        // Add a code marker (the condition must always be true).
        ADD_CODE_MARKER_FUN(NULL != pEnv)

        // Register the manual edit JNI methods.
        if (videoEditor_registerManualEditMethods((JNIEnv*)pEnv) == 0)
        {
            // Initialize the classes.
            videoEditClasses_init(&needToBeInitialized, (JNIEnv*)pEnv);
            if (needToBeInitialized)
            {
                // Success, return valid version number.
                result = JNI_VERSION_1_4;
            }
        }
    }

    // Return the result.
    return(result);
}

