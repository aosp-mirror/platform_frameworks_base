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

#ifndef VIDEO_EDITOR_CLASSES_H
#define VIDEO_EDITOR_CLASSES_H

#include <VideoEditorJava.h>
#include <VideoEditorClasses.h>
/**
 ************************************************************************
 * @file        VideoEditorClasses.h
 * @brief       Interface for JNI methods/defines that have specific
 *              access to class, objects and method Ids defined in Java layer
 ************************************************************************
*/


extern "C" {
#include <M4xVSS_API.h>
#include <M4VSS3GPP_API.h>
#include <M4VSS3GPP_ErrorCodes.h>
#include <M4MCS_ErrorCodes.h>
#include <M4READER_Common.h>
#include <M4WRITER_common.h>
};

/*
 * Java layer class/object name strings
 */
#define PACKAGE_NAME                           "android/media/videoeditor"

#define MANUAL_EDIT_ENGINE_CLASS_NAME          PACKAGE_NAME"/MediaArtistNativeHelper"
#define MEDIA_PROPERTIES_ENGINE_CLASS_NAME     PACKAGE_NAME"/MediaArtistNativeHelper"

#define AUDIO_FORMAT_CLASS_NAME                MANUAL_EDIT_ENGINE_CLASS_NAME"$AudioFormat"
#define RESULTS_CLASS_NAME                     MANUAL_EDIT_ENGINE_CLASS_NAME"$Results"
#define VERSION_CLASS_NAME                     MANUAL_EDIT_ENGINE_CLASS_NAME"$Version"
#define AUDIO_SAMPLING_FREQUENCY_CLASS_NAME    MANUAL_EDIT_ENGINE_CLASS_NAME"$AudioSamplingFrequency"
#define BITRATE_CLASS_NAME                     MANUAL_EDIT_ENGINE_CLASS_NAME"$Bitrate"
#define ERROR_CLASS_NAME                       MANUAL_EDIT_ENGINE_CLASS_NAME"$Result"
#define FILE_TYPE_CLASS_NAME                   MANUAL_EDIT_ENGINE_CLASS_NAME"$FileType"
#define MEDIA_RENDERING_CLASS_NAME             MANUAL_EDIT_ENGINE_CLASS_NAME"$MediaRendering"
#define VIDEO_FORMAT_CLASS_NAME                MANUAL_EDIT_ENGINE_CLASS_NAME"$VideoFormat"
#define VIDEO_FRAME_RATE_CLASS_NAME            MANUAL_EDIT_ENGINE_CLASS_NAME"$VideoFrameRate"
#define VIDEO_FRAME_SIZE_CLASS_NAME            MANUAL_EDIT_ENGINE_CLASS_NAME"$VideoFrameSize"
#define VIDEO_PROFILE_CLASS_NAME               MANUAL_EDIT_ENGINE_CLASS_NAME"$VideoProfile"
#define ALPHA_MAGIC_SETTINGS_CLASS_NAME        MANUAL_EDIT_ENGINE_CLASS_NAME"$AlphaMagicSettings"
#define AUDIO_EFFECT_CLASS_NAME                MANUAL_EDIT_ENGINE_CLASS_NAME"$AudioEffect"
#define AUDIO_TRANSITION_CLASS_NAME            MANUAL_EDIT_ENGINE_CLASS_NAME"$AudioTransition"
#define BACKGROUND_MUSIC_SETTINGS_CLASS_NAME   MANUAL_EDIT_ENGINE_CLASS_NAME"$BackgroundMusicSettings"
#define CLIP_SETTINGS_CLASS_NAME               MANUAL_EDIT_ENGINE_CLASS_NAME"$ClipSettings"
#define EDIT_SETTINGS_CLASS_NAME               MANUAL_EDIT_ENGINE_CLASS_NAME"$EditSettings"
#define EFFECT_SETTINGS_CLASS_NAME             MANUAL_EDIT_ENGINE_CLASS_NAME"$EffectSettings"
#define SLIDE_DIRECTION_CLASS_NAME             MANUAL_EDIT_ENGINE_CLASS_NAME"$SlideDirection"
#define SLIDE_TRANSITION_SETTINGS_CLASS_NAME   MANUAL_EDIT_ENGINE_CLASS_NAME"$SlideTransitionSettings"
#define TRANSITION_BEHAVIOUR_CLASS_NAME        MANUAL_EDIT_ENGINE_CLASS_NAME"$TransitionBehaviour"
#define TRANSITION_SETTINGS_CLASS_NAME         MANUAL_EDIT_ENGINE_CLASS_NAME"$TransitionSettings"
#define VIDEO_EFFECT_CLASS_NAME                MANUAL_EDIT_ENGINE_CLASS_NAME"$VideoEffect"
#define VIDEO_TRANSITION_CLASS_NAME            MANUAL_EDIT_ENGINE_CLASS_NAME"$VideoTransition"
#define PREVIEW_CLIPS_CLASS_NAME               MANUAL_EDIT_ENGINE_CLASS_NAME"$PreviewClips"
#define PREVIEW_SETTING_CLASS_NAME             MANUAL_EDIT_ENGINE_CLASS_NAME"$PreviewSettings"
#define PREVIEW_PROPERTIES_CLASS_NAME          MANUAL_EDIT_ENGINE_CLASS_NAME"$PreviewClipProperties"
#define AUDIO_SETTINGS_CLASS_NAME              MANUAL_EDIT_ENGINE_CLASS_NAME"$AudioSettings"
#define PROPERTIES_CLASS_NAME                  MANUAL_EDIT_ENGINE_CLASS_NAME"$Properties"

#define TASK_IDLE                                   0
#define TASK_LOADING_SETTINGS                       1
#define TASK_ENCODING                               2

/*
 * File type enum
 */
typedef enum
{
    VideoEditClasses_kFileType_3GPP,
    VideoEditClasses_kFileType_MP4,
    VideoEditClasses_kFileType_AMR,
    VideoEditClasses_kFileType_MP3,
    VideoEditClasses_kFileType_PCM,
    VideoEditClasses_kFileType_JPG,
    VideoEditClasses_kFileType_BMP,
    VideoEditClasses_kFileType_GIF,
    VideoEditClasses_kFileType_PNG,
    VideoEditClasses_kFileType_ARGB8888,
    VideoEditClasses_kFileType_M4V,
    VideoEditClasses_kFileType_Unsupported
} VideoEditClasses_FileType;

/*
 * Alpha magic transition structure
 */
typedef struct
{
    jfieldID file;
    jfieldID blendingPercent;
    jfieldID invertRotation;
    jfieldID rgbWidth;
    jfieldID rgbHeight;
} VideoEditJava_AlphaMagicFieldIds;

typedef struct
{
    jfieldID file;
    jfieldID fileType;
    jfieldID insertionTime;
    jfieldID volumePercent;
    jfieldID beginLoop;
    jfieldID endLoop;
    jfieldID enableDucking;
    jfieldID duckingThreshold;
    jfieldID lowVolume;
    jfieldID isLooping;
} VideoEditJava_BackgroundMusicFieldIds;
/*
 * Structure to hold media properties from native layer
 */
typedef struct {
    M4OSA_UInt32 uiClipDuration;
    VideoEditClasses_FileType  FileType;
    M4VIDEOEDITING_VideoFormat VideoStreamType;
    M4OSA_UInt32 uiClipVideoDuration;
    M4OSA_UInt32 uiVideoBitrate;
    M4OSA_UInt32 uiVideoWidth;
    M4OSA_UInt32 uiVideoHeight;
    M4OSA_Float  fAverageFrameRate;
    M4OSA_UInt32 uiVideoProfile; /**< H263 or MPEG-4 or H264 profile(from core decoder) */
    M4OSA_UInt32 uiVideoLevel;   /**< H263 or MPEG-4 or H264 level*/
    M4OSA_Bool bProfileSupported;
    M4OSA_Bool bLevelSupported;
    M4VIDEOEDITING_AudioFormat AudioStreamType;
    M4OSA_UInt32 uiClipAudioDuration;
    M4OSA_UInt32 uiAudioBitrate;
    M4OSA_UInt32 uiNbChannels;
    M4OSA_UInt32 uiSamplingFrequency;
} VideoEditPropClass_Properties;

typedef struct
{
    jfieldID duration;
    jfieldID fileType;
    jfieldID videoFormat;
    jfieldID videoDuration;
    jfieldID videoBitrate;
    jfieldID width;
    jfieldID height;
    jfieldID averageFrameRate;
    jfieldID profile;
    jfieldID level;
    jfieldID profileSupported;
    jfieldID levelSupported;
    jfieldID audioFormat;
    jfieldID audioDuration;
    jfieldID audioBitrate;
    jfieldID audioChannels;
    jfieldID audioSamplingFrequency;
} VideoEditJava_PropertiesFieldIds;


typedef struct
{
    jfieldID clipPath;
    jfieldID fileType;
    jfieldID beginCutTime;
    jfieldID endCutTime;
    jfieldID beginCutPercent;
    jfieldID endCutPercent;
    jfieldID panZoomEnabled;
    jfieldID panZoomPercentStart;
    jfieldID panZoomTopLeftXStart;
    jfieldID panZoomTopLeftYStart;
    jfieldID panZoomPercentEnd;
    jfieldID panZoomTopLeftXEnd;
    jfieldID panZoomTopLeftYEnd;
    jfieldID mediaRendering;
    jfieldID rgbFileWidth;
    jfieldID rgbFileHeight;
} VideoEditJava_ClipSettingsFieldIds;

typedef struct
{
    jfieldID clipSettingsArray;
    jfieldID transitionSettingsArray;
    jfieldID effectSettingsArray;
    jfieldID videoFrameRate;
    jfieldID outputFile;
    jfieldID videoFrameSize;
    jfieldID videoFormat;
    jfieldID videoProfile;
    jfieldID videoLevel;
    jfieldID audioFormat;
    jfieldID audioSamplingFreq;
    jfieldID maxFileSize;
    jfieldID audioChannels;
    jfieldID videoBitrate;
    jfieldID audioBitrate;
    jfieldID backgroundMusicSettings;
    jfieldID primaryTrackVolume;
} VideoEditJava_EditSettingsFieldIds;


typedef struct
{
    jfieldID startTime;
    jfieldID duration;
    jfieldID videoEffectType;
    jfieldID audioEffectType;
    jfieldID startPercent;
    jfieldID durationPercent;
    jfieldID framingFile;
    jfieldID framingBuffer;
    jfieldID bitmapType;
    jfieldID width;
    jfieldID height;
    jfieldID topLeftX;
    jfieldID topLeftY;
    jfieldID framingResize;
    jfieldID framingScaledSize;
    jfieldID text;
    jfieldID textRenderingData;
    jfieldID textBufferWidth;
    jfieldID textBufferHeight;
    jfieldID fiftiesFrameRate;
    jfieldID rgb16InputColor;
    jfieldID alphaBlendingStartPercent;
    jfieldID alphaBlendingMiddlePercent;
    jfieldID alphaBlendingEndPercent;
    jfieldID alphaBlendingFadeInTimePercent;
    jfieldID alphaBlendingFadeOutTimePercent;
} VideoEditJava_EffectSettingsFieldIds;

typedef struct
{
    jfieldID context;
} VideoEditJava_EngineFieldIds;

typedef struct
{
    jfieldID direction;
} VideoEditJava_SlideTransitionSettingsFieldIds;

typedef struct
{
    jfieldID duration;
    jfieldID videoTransitionType;
    jfieldID audioTransitionType;
    jfieldID transitionBehaviour;
    jfieldID alphaSettings;
    jfieldID slideSettings;
} VideoEditJava_TransitionSettingsFieldIds;

typedef struct
{
    jfieldID major;
    jfieldID minor;
    jfieldID revision;
} VideoEditJava_VersionFieldIds;


typedef struct
{
    jmethodID onProgressUpdate;
} VideoEditJava_EngineMethodIds;


VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(AudioEffect           )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(AudioFormat           )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(AudioSamplingFrequency)
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(AudioTransition       )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(Bitrate               )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(Engine                )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(Error                 )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(FileType              )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(MediaRendering        )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(SlideDirection        )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(TransitionBehaviour   )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(VideoEffect           )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(VideoFormat           )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(VideoFrameRate        )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(VideoFrameSize        )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(VideoProfile          )
VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(VideoTransition       )


VIDEOEDIT_JAVA_DECLARE_FIELD_CLASS(AlphaMagic               )
VIDEOEDIT_JAVA_DECLARE_FIELD_CLASS(BackgroundMusic          )
VIDEOEDIT_JAVA_DECLARE_FIELD_CLASS(ClipSettings             )
VIDEOEDIT_JAVA_DECLARE_FIELD_CLASS(ClipSettings             )
VIDEOEDIT_JAVA_DECLARE_FIELD_CLASS(EditSettings             )
VIDEOEDIT_JAVA_DECLARE_FIELD_CLASS(EffectSettings           )
VIDEOEDIT_JAVA_DECLARE_FIELD_CLASS(Engine                   )
VIDEOEDIT_JAVA_DECLARE_FIELD_CLASS(SlideTransitionSettings  )
VIDEOEDIT_JAVA_DECLARE_FIELD_CLASS(TransitionSettings       )
VIDEOEDIT_JAVA_DECLARE_FIELD_CLASS(Version                  )

VIDEOEDIT_JAVA_DECLARE_METHOD_CLASS(Engine                  )

/*
 * Init all Edit settings related structures
 */
void
videoEditClasses_init(
                bool*                               pResult,
                JNIEnv*                             pEnv);
/**
 ************************************************************************
 * @brief    Media Properties init function.
 * @param    pResult    (OUT) Pointer to hold result
 * @param    pEnv       (IN)  JVM Interface pointer
 ************************************************************************
*/
void
videoEditPropClass_init(
                bool*                               pResult,
                JNIEnv*                             pEnv);
/**
 ************************************************************************
 * @brief    Interface to populate Media Properties.
 * @param    pResult        (IN/OUT)    Pointer to hold result
 * @param    pEnv           (IN)        JVM Interface pointer
 * @param    pProperties    (IN)        Media propeties structure pointer
 * @param    pObject        (OUT)       Java object to hold media
 *                                      properties for java layer.
 ************************************************************************
*/
void
videoEditPropClass_createProperties(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                VideoEditPropClass_Properties*      pProperties,
                jobject*                            pObject);

/**
 ************************************************************************
 * @brief    Interface to log/display media properties.
 * @param    pProperties    (IN) Pointer holding media properties
 * @param    indentation    (IN) Indentation to follow in display
 ************************************************************************
*/
void
videoEditPropClass_logProperties(
                VideoEditPropClass_Properties*      pProperties,
                int                                 indentation);

/*
 * Get alpha magic transition settings
 */
void
videoEditClasses_getAlphaMagicSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4xVSS_AlphaMagicSettings**         ppSettings);

/*
 * Free alpha magic transition settings structure
 */
void
videoEditClasses_freeAlphaMagicSettings(
                M4xVSS_AlphaMagicSettings**         ppSettings);

/*
 * Log alpha magic transition settings
 */
void
videoEditClasses_logAlphaMagicSettings(
                M4xVSS_AlphaMagicSettings*          pSettings,
                int                                 indentation);

/*
 * Get Background Track settings
 */
void
videoEditClasses_getBackgroundMusicSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4xVSS_BGMSettings**                ppSettings);

/*
 * Free Background Track settings structure
 */
void
videoEditClasses_freeBackgroundMusicSettings(
                M4xVSS_BGMSettings**                ppSettings);

/*
 * Log Background Track settings
 */
void
videoEditClasses_logBackgroundMusicSettings(
                M4xVSS_BGMSettings*                 pSettings,
                int                                 indentation);

/*
 * Log clip properties
 */
void
videoEditClasses_logClipProperties(
                M4VIDEOEDITING_ClipProperties*      pProperties,
                int                                 indentation);

/*
 * Get clip settings from Java
 */
void
videoEditClasses_getClipSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4VSS3GPP_ClipSettings**            ppSettings);
/**
 ************************************************************************
 * @brief   Interface function to retrieve media properties for a given
 *          file.
 * @param   pEnv    (IN)    Pointer holding media properties
 * @param   thiz    (IN)    Indentation to follow in display
 * @param   file    (IN)    File path for which media properties has
 *                          to be retrieved.
 ************************************************************************
*/
jobject
videoEditProp_getProperties(
                JNIEnv*                             pEnv,
                jobject                             thiz,
                jstring                             file);

/*
 * Create/Set the clip settings to java Object
 */
void
videoEditClasses_createClipSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                M4VSS3GPP_ClipSettings*             pSettings,
                jobject*                            pObject);

/*
 * Free clip settings structure
 */
void
videoEditClasses_freeClipSettings(
                M4VSS3GPP_ClipSettings**            ppSettings);

/*
 * Log clip settings structure
 */
void
videoEditClasses_logClipSettings(
                M4VSS3GPP_ClipSettings*             pSettings,
                int                                 indentation);

/*
 * Get Edit settings from Java
 */
void
videoEditClasses_getEditSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4VSS3GPP_EditSettings**            ppSettings,
                bool                                flag);

/*
 * Free Edit Settings structure
 */
void
videoEditClasses_freeEditSettings(
                M4VSS3GPP_EditSettings**            ppSettings);

/*
 * Log Edit settings structure
 */
void
videoEditClasses_logEditSettings(
                M4VSS3GPP_EditSettings*             pSettings,
                int                                 indentation);

/*
 * Get Effect settings from Java
 */
void
videoEditClasses_getEffectSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4VSS3GPP_EffectSettings*           pSettings);

/*
 * Free Effect settings structure
 */
void
videoEditClasses_freeEffectSettings(
                M4VSS3GPP_EffectSettings*           pSettings);

/*
 * Log Effect settings
 */
void
videoEditClasses_logEffectSettings(
                M4VSS3GPP_EffectSettings*           pSettings,
                int                                 indentation);

/*
 * Get Transition-Sliding settings from Java
 */
void
videoEditClasses_getSlideTransitionSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4xVSS_SlideTransitionSettings**    ppSettings);

/*
 * Free Transition-Sliding structure
 */
void
videoEditClasses_freeSlideTransitionSettings(
                M4xVSS_SlideTransitionSettings**    ppSettings);

/*
 * Free Transition-Sliding structure
 */
void
videoEditClasses_logSlideTransitionSettings(
                M4xVSS_SlideTransitionSettings*     pSettings,
                int                                 indentation);

/*
 * Get Transition settings from Java
 */
void
videoEditClasses_getTransitionSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4VSS3GPP_TransitionSettings**      ppSettings);

/*
 * Free Transition settings structure
 */
void
videoEditClasses_freeTransitionSettings(
                M4VSS3GPP_TransitionSettings**      ppSettings);

/*
 * Log Transition settings
 */
void
videoEditClasses_logTransitionSettings(
                M4VSS3GPP_TransitionSettings*       pSettings,
                int                                 indentation);

/*
 * Set version information to Java object
 */
void
videoEditClasses_createVersion(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                M4_VersionInfo*                     pVersionInfo,
                jobject*                            pObject);

/*
 * Log Version information
 */
void
videoEditClasses_logVersion(
                M4_VersionInfo*                     pVersionInfo,
                int                                 indentation);


void*
videoEditClasses_getContext(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object);

void
videoEditClasses_setContext(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                void*                               pContext);


#endif // VIDEO_EDITOR_CLASSES_H

