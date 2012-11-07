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

#include <VideoEditorJava.h>
#include <VideoEditorLogging.h>
#include <VideoEditorOsal.h>

extern "C" {
#include <M4OSA_Clock.h>
#include <M4OSA_CharStar.h>
#include <M4OSA_FileCommon.h>
#include <M4OSA_FileReader.h>
#include <M4OSA_FileWriter.h>
#include <M4OSA_Memory.h>
#include <M4OSA_Thread.h>
#include <M4xVSS_API.h>
#include <M4VSS3GPP_ErrorCodes.h>
#include <M4MCS_ErrorCodes.h>
#include <M4READER_Common.h>
#include <M4WRITER_common.h>
#include <M4VSS3GPP_API.h>
#include <M4DECODER_Common.h>
};


#define VIDEOEDIT_OSAL_RESULT_STRING_MAX     (32)

#define VIDEOEDIT_OSAL_RESULT_INIT(m_result) { m_result, #m_result }


typedef struct
{
    M4OSA_ERR   result;
    const char* pName;
} VideoEdit_Osal_Result;

static const VideoEdit_Osal_Result gkRESULTS[] =
{
    // M4OSA_Clock.h
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_TIMESCALE_TOO_BIG                                ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_CLOCK_BAD_REF_YEAR                               ),

    // M4OSA_Error.h
    VIDEOEDIT_OSAL_RESULT_INIT(M4NO_ERROR                                             ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_PARAMETER                                        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_STATE                                            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_ALLOC                                            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_BAD_CONTEXT                                      ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_CONTEXT_FAILED                                   ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_BAD_STREAM_ID                                    ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_BAD_OPTION_ID                                    ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_WRITE_ONLY                                       ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_READ_ONLY                                        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_NOT_IMPLEMENTED                                  ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_UNSUPPORTED_MEDIA_TYPE                           ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_NO_DATA_YET                                      ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_NO_MORE_STREAM                                   ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_INVALID_TIME                                     ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_NO_MORE_AU                                       ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_TIME_OUT                                         ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_BUFFER_FULL                                      ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_REDIRECT                                         ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_TOO_MUCH_STREAMS                                 ),

    // M4OSA_FileCommon.h
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_FILE_NOT_FOUND                                   ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_FILE_LOCKED                                      ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_FILE_BAD_MODE_ACCESS                             ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_FILE_INVALID_POSITION                            ),

    // M4OSA_Thread.h
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_THREAD_NOT_STARTED                               ),

    // M4xVSS_API.h
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_WAR_ANALYZING_DONE                           ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_WAR_PREVIEW_READY                            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_WAR_SAVING_DONE                              ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_WAR_TRANSCODING_NECESSARY                    ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_WAR_OUTPUTFILESIZE_EXCEED                    ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_JPG_TOO_BIG                              ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4xVSSWAR_BUFFER_OUT_TOO_SMALL                         ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4xVSSERR_NO_MORE_SPACE                                ),

    // M4VSS3GPP_ErrorCodes.h
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INVALID_FILE_TYPE                        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INVALID_EFFECT_KIND                      ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INVALID_VIDEO_EFFECT_TYPE                ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INVALID_AUDIO_EFFECT_TYPE                ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INVALID_VIDEO_TRANSITION_TYPE            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INVALID_AUDIO_TRANSITION_TYPE            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INVALID_VIDEO_ENCODING_FRAME_RATE        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_EXTERNAL_EFFECT_NULL                     ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_EXTERNAL_TRANSITION_NULL                 ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_BEGIN_CUT_LARGER_THAN_DURATION           ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_BEGIN_CUT_LARGER_THAN_END_CUT            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_OVERLAPPING_TRANSITIONS                  ),
#ifdef M4VSS3GPP_ERR_ANALYSIS_DATA_SIZE_TOO_SMALL
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_ANALYSIS_DATA_SIZE_TOO_SMALL             ),
#endif
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INVALID_3GPP_FILE                        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_UNSUPPORTED_INPUT_VIDEO_FORMAT           ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_UNSUPPORTED_INPUT_AUDIO_FORMAT           ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_AMR_EDITING_UNSUPPORTED                  ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INPUT_VIDEO_AU_TOO_LARGE                 ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INPUT_AUDIO_AU_TOO_LARGE                 ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INPUT_AUDIO_CORRUPTED_AU                 ),
#ifdef M4VSS3GPP_ERR_INPUT_AUDIO_CORRUPTED_AMR_AU
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INPUT_AUDIO_CORRUPTED_AMR_AU             ),
#endif
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_ENCODER_ACCES_UNIT_ERROR                 ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_EDITING_UNSUPPORTED_VIDEO_FORMAT         ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_EDITING_UNSUPPORTED_H263_PROFILE         ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_EDITING_UNSUPPORTED_MPEG4_PROFILE        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_EDITING_UNSUPPORTED_MPEG4_RVLC           ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_EDITING_UNSUPPORTED_AUDIO_FORMAT         ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_EDITING_NO_SUPPORTED_STREAM_IN_FILE      ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_EDITING_NO_SUPPORTED_VIDEO_STREAM_IN_FILE),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INVALID_CLIP_ANALYSIS_VERSION            ),
#ifdef M4VSS3GPP_ERR_INVALID_CLIP_ANALYSIS_PLATFORM
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INVALID_CLIP_ANALYSIS_PLATFORM           ),
#endif
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INCOMPATIBLE_VIDEO_FORMAT                ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INCOMPATIBLE_VIDEO_FRAME_SIZE            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INCOMPATIBLE_VIDEO_TIME_SCALE            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INCOMPATIBLE_VIDEO_DATA_PARTITIONING     ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_UNSUPPORTED_MP3_ASSEMBLY                 ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_WAR_INCOMPATIBLE_AUDIO_STREAM_TYPE           ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_WAR_INCOMPATIBLE_AUDIO_NB_OF_CHANNELS        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_WAR_INCOMPATIBLE_AUDIO_SAMPLING_FREQUENCY    ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_NO_SUPPORTED_STREAM_IN_FILE              ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_ADDVOLUME_EQUALS_ZERO                    ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_ADDCTS_HIGHER_THAN_VIDEO_DURATION        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_UNDEFINED_AUDIO_TRACK_FILE_FORMAT        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_UNSUPPORTED_ADDED_AUDIO_STREAM           ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_AUDIO_MIXING_UNSUPPORTED                 ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_FEATURE_UNSUPPORTED_WITH_AUDIO_TRACK     ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_AUDIO_CANNOT_BE_MIXED                    ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INPUT_CLIP_IS_NOT_A_3GPP                 ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_BEGINLOOP_HIGHER_ENDLOOP                 ),
#ifdef M4VSS3GPP_ERR_AUDIO_MIXING_MP3_UNSUPPORTED
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_AUDIO_MIXING_MP3_UNSUPPORTED             ),
#endif
#ifdef M4VSS3GPP_ERR_FEATURE_UNSUPPORTED_WITH_AAC
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_FEATURE_UNSUPPORTED_WITH_AAC             ),
#endif
#ifdef M4VSS3GPP_ERR_ONLY_AMRNB_INPUT_CAN_BE_MIXED
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_ONLY_AMRNB_INPUT_CAN_BE_MIXED            ),
#endif
#ifdef M4VSS3GPP_ERR_FEATURE_UNSUPPORTED_WITH_EVRC
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_FEATURE_UNSUPPORTED_WITH_EVRC            ),
#endif
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_H263_PROFILE_NOT_SUPPORTED               ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_NO_SUPPORTED_VIDEO_STREAM_IN_FILE        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_INTERNAL_STATE                           ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_LUMA_FILTER_ERROR                        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_TRANSITION_FILTER_ERROR                  ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_AUDIO_DECODER_INIT_FAILED                ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_AUDIO_DECODED_PCM_SIZE_ISSUE             ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4VSS3GPP_ERR_OUTPUT_FILE_TYPE_ERROR                   ),

    // M4MCS_ErrorCodes.h
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_WAR_TRANSCODING_DONE                             ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_WAR_MEDIATYPE_NOT_SUPPORTED                      ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_INPUT_FILE_CONTAINS_NO_SUPPORTED_STREAM      ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_INVALID_INPUT_FILE                           ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_UNDEFINED_OUTPUT_VIDEO_FORMAT                ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_UNDEFINED_OUTPUT_VIDEO_FRAME_SIZE            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_UNDEFINED_OUTPUT_VIDEO_FRAME_RATE            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_UNDEFINED_OUTPUT_AUDIO_FORMAT                ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_INVALID_VIDEO_FRAME_SIZE_FOR_H263            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_INVALID_VIDEO_FRAME_RATE_FOR_H263            ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_DURATION_IS_NULL                             ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_H263_FORBIDDEN_IN_MP4_FILE                   ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_H263_PROFILE_NOT_SUPPORTED                   ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_INVALID_AAC_SAMPLING_FREQUENCY               ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_AUDIO_CONVERSION_FAILED                      ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_BEGIN_CUT_LARGER_THAN_DURATION               ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_BEGIN_CUT_EQUALS_END_CUT                     ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_END_CUT_SMALLER_THAN_BEGIN_CUT               ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_MAXFILESIZE_TOO_SMALL                        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_VIDEOBITRATE_TOO_LOW                         ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_AUDIOBITRATE_TOO_LOW                         ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_VIDEOBITRATE_TOO_HIGH                        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_AUDIOBITRATE_TOO_HIGH                        ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_OUTPUT_FILE_SIZE_TOO_SMALL                   ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_NOMORE_SPACE                                 ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4MCS_ERR_FILE_DRM_PROTECTED                           ),

    // M4READER_Common.h
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_READER_UNKNOWN_STREAM_TYPE                       ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_READER_NO_METADATA                               ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_READER_INFORMATION_NOT_PRESENT                   ),

    // M4WRITER_Common.h
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_WRITER_STOP_REQ                                  ),
    // M4DECODER_Common.h
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_VIDEORENDERER_NO_NEW_FRAME                       ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4WAR_DEBLOCKING_FILTER_NOT_IMPLEMENTED                ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_DECODER_H263_PROFILE_NOT_SUPPORTED               ),
    VIDEOEDIT_OSAL_RESULT_INIT(M4ERR_DECODER_H263_NOT_BASELINE                        )
};

static const int gkRESULTS_COUNT = (sizeof(gkRESULTS) / sizeof(VideoEdit_Osal_Result));

#ifdef OSAL_MEM_LEAK_DEBUG
static int gAllocatedBlockCount = 0;
#endif

const char*
videoEditOsal_getResultString(
                M4OSA_ERR                           result)
{
    static char string[VIDEOEDIT_OSAL_RESULT_STRING_MAX] = "";
    const char* pString                         = M4OSA_NULL;
    int         index                           = 0;

    // Loop over the list with constants.
    for (index = 0;
         ((M4OSA_NULL == pString) && (index < gkRESULTS_COUNT));
         index++)
    {
        // Check if the specified result matches.
        if (result == gkRESULTS[index].result)
        {
            // Set the description.
            pString = gkRESULTS[index].pName;
        }
    }

    // Check if no result was found.
    if (M4OSA_NULL == pString)
    {
        // Set the description to a default value.
        M4OSA_chrSPrintf((M4OSA_Char *)string, sizeof(string) - 1,
         (M4OSA_Char*)"<unknown(0x%08X)>", result);
        pString = string;
    }

    // Return the result.
    return(pString);
}

void *
videoEditOsal_alloc(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                size_t                              size,
                const char*                         pDescription)
{
    void *pData = M4OSA_NULL;

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Allocate memory for the settings.
        pData = (M4VSS3GPP_EditSettings*)M4OSA_32bitAlignedMalloc(size, 0, (M4OSA_Char*)pDescription);
        if (M4OSA_NULL != pData)
        {
            // Reset the allocated memory.
            memset((void *)pData, 0,size);
#ifdef OSAL_MEM_LEAK_DEBUG
            // Update the allocated block count.
            gAllocatedBlockCount++;
#endif
        }
        else
        {
            // Reset the result flag.
            (*pResult) = false;

            // Log the error.
            VIDEOEDIT_LOG_ERROR(ANDROID_LOG_ERROR, "VIDEO_EDITOR_OSAL", "videoEditOsal_alloc,\
             error: unable to allocate memory for %s", pDescription);

            // Throw an exception.
            jniThrowException(pEnv, "java/lang/OutOfMemoryError", "unable to allocate memory");
        }
    }

    // Return the allocated memory.
    return(pData);
}

void
videoEditOsal_free(
                void*                               pData)
{
    // Check if memory was allocated.
    if (M4OSA_NULL != pData)
    {
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_OSAL", "videoEditOsal_free()");

        // Log the API call.
        VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR_OSAL", "free");

        // Free the memory.
        free(pData);
#ifdef OSAL_MEM_LEAK_DEBUG
        // Update the allocated block count.
        gAllocatedBlockCount--;

        // Log the number of allocated blocks.
        VIDEOEDIT_LOG_ALLOCATION(ANDROID_LOG_ERROR, "VIDEO_EDITOR_OSAL", "allocated, %d blocks",\
         gAllocatedBlockCount);
#endif
    }
}


void
videoEditOsal_getFilePointers ( M4OSA_FileReadPointer *pOsaFileReadPtr,
                                M4OSA_FileWriterPointer *pOsaFileWritePtr)
{
    if (pOsaFileReadPtr != M4OSA_NULL)
    {
        // Initialize the filereader function pointers.
        pOsaFileReadPtr->openRead  = M4OSA_fileReadOpen;
        pOsaFileReadPtr->readData  = M4OSA_fileReadData;
        pOsaFileReadPtr->seek      = M4OSA_fileReadSeek;
        pOsaFileReadPtr->closeRead = M4OSA_fileReadClose;
        pOsaFileReadPtr->setOption = M4OSA_fileReadSetOption;
        pOsaFileReadPtr->getOption = M4OSA_fileReadGetOption;
    }

    if (pOsaFileWritePtr != M4OSA_NULL)
    {
        // Initialize the filewriter function pointers.
        pOsaFileWritePtr->openWrite  = M4OSA_fileWriteOpen;
        pOsaFileWritePtr->writeData  = M4OSA_fileWriteData;
        pOsaFileWritePtr->seek       = M4OSA_fileWriteSeek;
        pOsaFileWritePtr->Flush      = M4OSA_fileWriteFlush;
        pOsaFileWritePtr->closeWrite = M4OSA_fileWriteClose;
        pOsaFileWritePtr->setOption  = M4OSA_fileWriteSetOption;
        pOsaFileWritePtr->getOption  = M4OSA_fileWriteGetOption;
    }
}

