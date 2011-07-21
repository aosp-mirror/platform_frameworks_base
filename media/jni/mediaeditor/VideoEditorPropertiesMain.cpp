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

#include <dlfcn.h>
#include <stdio.h>
#include <unistd.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include <VideoEditorClasses.h>
#include <VideoEditorJava.h>
#include <VideoEditorOsal.h>
#include <VideoEditorLogging.h>
#include <VideoEditorOsal.h>
#include <marker.h>

extern "C" {
#include <M4OSA_Clock.h>
#include <M4OSA_CharStar.h>
#include <M4OSA_Error.h>
#include <M4OSA_FileCommon.h>
#include <M4OSA_FileReader.h>
#include <M4OSA_FileWriter.h>
#include <M4OSA_Memory.h>
#include <M4OSA_Thread.h>
#include <M4VSS3GPP_API.h>
#include <M4VSS3GPP_ErrorCodes.h>
#include <M4MCS_API.h>
#include <M4MCS_ErrorCodes.h>
#include <M4READER_Common.h>
#include <M4WRITER_common.h>
#include <M4DECODER_Common.h>
#include <M4AD_Common.h>
};

extern "C" M4OSA_ERR M4MCS_open_normalMode(
                M4MCS_Context                       pContext,
                M4OSA_Void*                         pFileIn,
                M4VIDEOEDITING_FileType             InputFileType,
                M4OSA_Void*                         pFileOut,
                M4OSA_Void*                         pTempFile);

jobject videoEditProp_getProperties(
                JNIEnv*                             pEnv,
                jobject                             thiz,
                jstring                             file);

static void
getFileAndMediaTypeFromExtension (
                M4OSA_Char* pExtension,
                VideoEditClasses_FileType   *pFileType,
                M4VIDEOEDITING_FileType       *pClipType);

static M4OSA_ERR
getClipProperties(  JNIEnv*                         pEnv,
                    jobject                         thiz,
                    M4OSA_Char*                     pFile,
                    M4VIDEOEDITING_FileType         clipType,
                    M4VIDEOEDITING_ClipProperties*  pClipProperties);

M4OSA_UInt32
VideoEdit_chrCompare(M4OSA_Char* pStrIn1,
                     M4OSA_Char* pStrIn2,
                     M4OSA_Int32* pCmpResult);

jobject videoEditProp_getProperties(
        JNIEnv* pEnv,
        jobject thiz,
        jstring file)
{
    bool                           gotten          = true;
    M4OSA_Char*                    pFile           = M4OSA_NULL;
    M4OSA_Char*                    pExtension      = M4OSA_NULL;
    M4OSA_UInt32                   index           = 0;
    M4OSA_Int32                    cmpResult       = 0;
    VideoEditPropClass_Properties* pProperties     = M4OSA_NULL;
    M4VIDEOEDITING_ClipProperties* pClipProperties = M4OSA_NULL;
    M4OSA_ERR                      result          = M4NO_ERROR;
    M4MCS_Context                  context         = M4OSA_NULL;
    M4OSA_FilePosition             size            = 0;
    M4OSA_UInt32                   width           = 0;
    M4OSA_UInt32                   height          = 0;
    jobject                        properties      = NULL;
    M4OSA_Context                  pOMXContext     = M4OSA_NULL;
    M4DECODER_VideoInterface*      pOMXVidDecoderInterface = M4OSA_NULL;
    M4AD_Interface*                pOMXAudDecoderInterface = M4OSA_NULL;

    bool  initialized = true;
    VideoEditClasses_FileType fileType = VideoEditClasses_kFileType_Unsupported;
    M4VIDEOEDITING_FileType clipType = M4VIDEOEDITING_kFileType_Unsupported;

    VIDEOEDIT_LOG_API(
            ANDROID_LOG_INFO, "VIDEO_EDITOR_PROPERTIES",
            "videoEditProp_getProperties()");

    // Add a text marker (the condition must always be true).
    ADD_TEXT_MARKER_FUN(NULL != pEnv)

    // Initialize the classes.
    videoEditPropClass_init(&initialized, (JNIEnv*)pEnv);

    // Validate the tempPath parameter.
    videoEditJava_checkAndThrowIllegalArgumentException(
            &gotten, pEnv, (NULL == file), "file is null");

    // Get the file path.
    pFile = (M4OSA_Char *)videoEditJava_getString(
            &gotten, pEnv, file, NULL, M4OSA_NULL);

    result = M4OSA_fileReadOpen(&context, (M4OSA_Void*)pFile, M4OSA_kFileRead);

    if(M4NO_ERROR != result) {
        // Free the file path.
        videoEditOsal_free(pFile);
        pFile = M4OSA_NULL;
    }

    videoEditJava_checkAndThrowIllegalArgumentException(&gotten, pEnv,
        (M4NO_ERROR != result), "file not found");

    // Close the file and free the file context
    if (context != NULL) {
        result = M4OSA_fileReadClose(context);
        context = M4OSA_NULL;
    }

    // Return if Error
    if (M4NO_ERROR != result) {
        return (properties); // NULL
    }

    // Check if the file path is valid.
    if (gotten)
    {
        // Retrieve the extension.
        pExtension = (M4OSA_Char *)strrchr((const char *)pFile, (int)'.');
        if (M4OSA_NULL != pExtension)
        {
            // Skip the dot.
            pExtension++;

            // Get the file type and Media type from extension
            getFileAndMediaTypeFromExtension(
                    pExtension ,&fileType, &clipType);
        }
    }

    // Check if the file type could be determined.
    videoEditJava_checkAndThrowIllegalArgumentException(
            &gotten, pEnv,
            (VideoEditClasses_kFileType_Unsupported == fileType),
            "file type is not supported");

    // Allocate a new properties structure.
    pProperties = (VideoEditPropClass_Properties*)videoEditOsal_alloc(
            &gotten, pEnv,
            sizeof(VideoEditPropClass_Properties), "Properties");

    // Check if the context is valid and allocation succeeded
    // (required because of dereferencing of pProperties).
    if (gotten)
    {
        // Check if this type of file needs to be analyzed using MCS.
        if ((VideoEditClasses_kFileType_MP3  == fileType) ||
            (VideoEditClasses_kFileType_MP4  == fileType) ||
            (VideoEditClasses_kFileType_3GPP == fileType) ||
            (VideoEditClasses_kFileType_AMR  == fileType) ||
            (VideoEditClasses_kFileType_PCM  == fileType) ||
            (VideoEditClasses_kFileType_M4V  == fileType))
        {
            // Allocate a new clip properties structure.
            pClipProperties =
                (M4VIDEOEDITING_ClipProperties*)videoEditOsal_alloc(
                    &gotten, pEnv,
                    sizeof(M4VIDEOEDITING_ClipProperties), "ClipProperties");

            // Check if allocation succeeded (required because of
            // dereferencing of pClipProperties).
            if (gotten)
            {
                // Add a code marker (the condition must always be true).
                ADD_CODE_MARKER_FUN(NULL != pClipProperties)

                // Log the API call.
                VIDEOEDIT_LOG_API(
                        ANDROID_LOG_INFO, "VIDEO_EDITOR_PROPERTIES",
                        "getClipProperties");

                // Get Video clip properties
                result = getClipProperties(
                        pEnv, thiz, pFile, clipType, pClipProperties);

                if (M4MCS_ERR_FILE_DRM_PROTECTED == result) {
                    // Check if the creation succeeded.
                    videoEditJava_checkAndThrowIllegalArgumentException(
                            &gotten, pEnv,(M4NO_ERROR != result),
                            "Invalid File - DRM Protected ");
                } else {
                    // Check if the creation succeeded.
                    videoEditJava_checkAndThrowIllegalArgumentException(
                            &gotten, pEnv,(M4NO_ERROR != result),
                            "Invalid File or File not found ");
                }

#ifdef USE_SOFTWARE_DECODER
                /**
                 * Input clip with non-multiples of 16 is not supported.
                 */
                if ( (pClipProperties->uiVideoWidth %16)
                    || (pClipProperties->uiVideoHeight %16) )
                {
                    result = M4MCS_ERR_INPUT_VIDEO_SIZE_NON_X16;
                    videoEditJava_checkAndThrowIllegalArgumentException(
                            &gotten, pEnv, (M4NO_ERROR != result),
                            "non x16 input video frame size is not supported");
                }
#endif /* USE_SOFTWARE_DECODER */
            }

            // Check if the properties could be retrieved.
            if (gotten)
            {
                // Set the properties.
                pProperties->uiClipDuration = pClipProperties->uiClipDuration;
                if (M4VIDEOEDITING_kFileType_Unsupported == pClipProperties->FileType)
                {
                    pProperties->FileType        = VideoEditClasses_kFileType_Unsupported;
                }
                else
                {
                    pProperties->FileType        = fileType;
                }
                pProperties->VideoStreamType     = pClipProperties->VideoStreamType;
                pProperties->uiClipVideoDuration = pClipProperties->uiClipVideoDuration;
                pProperties->uiVideoBitrate      = pClipProperties->uiVideoBitrate;
                pProperties->uiVideoWidth        = pClipProperties->uiVideoWidth;
                pProperties->uiVideoHeight       = pClipProperties->uiVideoHeight;
                pProperties->fAverageFrameRate   = pClipProperties->fAverageFrameRate;
                pProperties->ProfileAndLevel     = pClipProperties->ProfileAndLevel;
                pProperties->AudioStreamType     = pClipProperties->AudioStreamType;
                pProperties->uiClipAudioDuration = pClipProperties->uiClipAudioDuration;
                pProperties->uiAudioBitrate      = pClipProperties->uiAudioBitrate;
                pProperties->uiNbChannels        = pClipProperties->uiNbChannels;
                pProperties->uiSamplingFrequency = pClipProperties->uiSamplingFrequency;
            }

            // Free the clip properties.
            videoEditOsal_free(pClipProperties);
            pClipProperties = M4OSA_NULL;
        }
        else if ((VideoEditClasses_kFileType_JPG == fileType) ||
            (VideoEditClasses_kFileType_GIF == fileType) ||
            (VideoEditClasses_kFileType_PNG == fileType))
        {
            pProperties->uiClipDuration      = 0;
            pProperties->FileType            = fileType;
            pProperties->VideoStreamType     = M4VIDEOEDITING_kNoneVideo;
            pProperties->uiClipVideoDuration = 0;
            pProperties->uiVideoBitrate      = 0;
            pProperties->uiVideoWidth        = width;
            pProperties->uiVideoHeight       = height;
            pProperties->fAverageFrameRate   = 0.0f;
            pProperties->ProfileAndLevel     = M4VIDEOEDITING_kProfile_and_Level_Out_Of_Range;
            pProperties->AudioStreamType     = M4VIDEOEDITING_kNoneAudio;
            pProperties->uiClipAudioDuration = 0;
            pProperties->uiAudioBitrate      = 0;
            pProperties->uiNbChannels        = 0;
            pProperties->uiSamplingFrequency = 0;

            // Added for Handling invalid paths and non existent image files
            // Open the file for reading.
            result = M4OSA_fileReadOpen(&context, (M4OSA_Void*)pFile, M4OSA_kFileRead);
            if (M4NO_ERROR != result)
            {
                pProperties->FileType = VideoEditClasses_kFileType_Unsupported;
            }
            result = M4OSA_fileReadClose(context);
            context = M4OSA_NULL;
        }
    }

    // Create a properties object.
    videoEditPropClass_createProperties(&gotten, pEnv, pProperties, &properties);

    // Log the properties.
    VIDEOEDIT_PROP_LOG_PROPERTIES(pProperties);

    // Free the properties.
    videoEditOsal_free(pProperties);
    pProperties = M4OSA_NULL;

    // Free the file path.
    videoEditOsal_free(pFile);
    pFile = M4OSA_NULL;

    // Add a text marker (the condition must always be true).
    ADD_TEXT_MARKER_FUN(NULL != pEnv)

    // Return the Properties object.
    return(properties);
}

static void getFileAndMediaTypeFromExtension (
        M4OSA_Char *pExtension,
        VideoEditClasses_FileType *pFileType,
        M4VIDEOEDITING_FileType *pClipType)
{
    M4OSA_Char extension[5] = {0, 0, 0, 0, 0};
    VideoEditClasses_FileType fileType =
            VideoEditClasses_kFileType_Unsupported;

    M4VIDEOEDITING_FileType clipType =
            M4VIDEOEDITING_kFileType_Unsupported;

    M4OSA_UInt32 index = 0;
    M4OSA_ERR result = M4NO_ERROR;
    M4OSA_Int32 cmpResult = 0;
    M4OSA_UInt32  extLength = strlen((const char *)pExtension);

    // Assign default
    *pFileType = VideoEditClasses_kFileType_Unsupported;
    *pClipType = M4VIDEOEDITING_kFileType_Unsupported;

    // Check if the length of the extension is valid.
    if ((3 == extLength) || (4 == extLength))
    {
        // Convert the extension to lowercase.
        for (index = 0; index < extLength ; index++)
        {
            extension[index] = tolower((int)pExtension[index]);
        }

        // Check if the extension is ".mp3".
        if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"mp3", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_MP3;
            *pClipType = M4VIDEOEDITING_kFileType_MP3;
        }
        // Check if the extension is ".mp4".
        else if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"mp4", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_MP4;
            *pClipType = M4VIDEOEDITING_kFileType_MP4;
        }
        // Check if the extension is ".3gp".
        else if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"3gp", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_3GPP;
            *pClipType = M4VIDEOEDITING_kFileType_3GPP;
        }
        // Check if the extension is ".m4a".
        else if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"m4a", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_3GPP;
            *pClipType = M4VIDEOEDITING_kFileType_3GPP;
        }
        // Check if the extension is ".3gpp".
        else if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"3gpp", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_3GPP;
            *pClipType = M4VIDEOEDITING_kFileType_3GPP;
        }
        // Check if the extension is ".amr".
        else if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"amr", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_AMR;
            *pClipType = M4VIDEOEDITING_kFileType_AMR;
        }
        // Check if the extension is ".pcm".
        else if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"pcm", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_PCM;
            *pClipType = M4VIDEOEDITING_kFileType_PCM;
        }
        // Check if the extension is ".jpg".
        else if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"jpg", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_JPG;
        }
        // Check if the extension is ".jpeg".
        else if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"jpeg", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_JPG;
        }
        // Check if the extension is ".gif".
        else if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"gif", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_GIF;
        }
        // Check if the extension is ".png".
        else if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"png", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_PNG;
        }
        // Check if the extension is ".m4v".
        else if (!(VideoEdit_chrCompare(extension, (M4OSA_Char*)"m4v", &cmpResult)))
        {
            *pFileType = VideoEditClasses_kFileType_M4V;
            *pClipType = M4VIDEOEDITING_kFileType_M4V;
        }
    }
}

static M4OSA_ERR getClipProperties(
        JNIEnv* pEnv,
        jobject thiz,
        M4OSA_Char* pFile,
        M4VIDEOEDITING_FileType clipType,
        M4VIDEOEDITING_ClipProperties* pClipProperties)
{
    bool                      gotten          = true;
    M4OSA_ERR                 result          = M4NO_ERROR;
    M4OSA_ERR                 resultAbort     = M4NO_ERROR;
    M4MCS_Context             context         = M4OSA_NULL;

    M4OSA_FileReadPointer fileReadPtr =
            { M4OSA_NULL, M4OSA_NULL, M4OSA_NULL,
              M4OSA_NULL, M4OSA_NULL, M4OSA_NULL };

    M4OSA_FileWriterPointer fileWritePtr =
            { M4OSA_NULL, M4OSA_NULL, M4OSA_NULL,
              M4OSA_NULL, M4OSA_NULL, M4OSA_NULL, M4OSA_NULL };

    // Initialize the OSAL file system function pointers.
    videoEditOsal_getFilePointers(&fileReadPtr , &fileWritePtr);

    // Log the API call.
    VIDEOEDIT_LOG_API(
            ANDROID_LOG_INFO, "VIDEO_EDITOR_PROPERTIES",\
            "getClipProperties - M4MCS_init()");

    // Initialize the MCS context.
    result = M4MCS_init(&context, &fileReadPtr, &fileWritePtr);

    // Log the result.
    VIDEOEDIT_PROP_LOG_RESULT(
            ANDROID_LOG_INFO, "VIDEO_EDITOR_PROPERTIES", "%s",
            videoEditOsal_getResultString(result));

    // Check if the creation succeeded.
    videoEditJava_checkAndThrowRuntimeException(
            &gotten, pEnv, (M4NO_ERROR != result), result);

    // Check if opening the MCS context succeeded.
    if (gotten)
    {
        // Log the API call.
        VIDEOEDIT_LOG_API(
                ANDROID_LOG_INFO, "VIDEO_EDITOR_PROPERTIES",
                "getClipProperties - M4MCS_open_normalMode()");

        // Open the MCS in the normal opening mode to
        // retrieve the exact duration
        result = M4MCS_open_normalMode(
                context, pFile, clipType, M4OSA_NULL, M4OSA_NULL);

        // Log the result.
        VIDEOEDIT_PROP_LOG_RESULT(
                ANDROID_LOG_INFO, "VIDEO_EDITOR_PROPERTIES", "%s",
                videoEditOsal_getResultString(result));

        // Check if the creation succeeded.
        videoEditJava_checkAndThrowRuntimeException(
                &gotten, pEnv, (M4NO_ERROR != result), result);

        // Check if the MCS could be opened.
        if (gotten)
        {
            // Log the API call.
            VIDEOEDIT_LOG_API(
                    ANDROID_LOG_INFO, "VIDEO_EDITOR_PROPERTIES",
                    "getClipProperties - M4MCS_getInputFileProperties()");

            // Get the properties.
            result = M4MCS_getInputFileProperties(context, pClipProperties);

            // Log the result.
            VIDEOEDIT_PROP_LOG_RESULT(
                    ANDROID_LOG_INFO, "VIDEO_EDITOR_PROPERTIES", "%s",
                    videoEditOsal_getResultString(result));

            // Check if the creation succeeded.
            videoEditJava_checkAndThrowRuntimeException(
                    &gotten, pEnv, (M4NO_ERROR != result), result);
        }

        // Log the API call.
        VIDEOEDIT_LOG_API(
                ANDROID_LOG_INFO, "VIDEO_EDITOR_PROPERTIES",
                "getClipProperties - M4MCS_abort()");

        // Close the MCS session.
        resultAbort = M4MCS_abort(context);

       if (result == M4NO_ERROR) {
            // Log the result.
            VIDEOEDIT_PROP_LOG_RESULT(
                    ANDROID_LOG_INFO, "VIDEO_EDITOR_PROPERTIES", "%s",
                    videoEditOsal_getResultString(resultAbort));

            // Check if the abort succeeded.
            videoEditJava_checkAndThrowRuntimeException(
                    &gotten, pEnv, (M4NO_ERROR != resultAbort), resultAbort);
            result = resultAbort;
        }
    }

    return result;
}

M4OSA_UInt32
VideoEdit_chrCompare(M4OSA_Char* pStrIn1,
                     M4OSA_Char* pStrIn2,
                      M4OSA_Int32* pCmpResult)
{
    *pCmpResult = strcmp((const char *)pStrIn1, (const char *)pStrIn2);
    return *pCmpResult;
}


