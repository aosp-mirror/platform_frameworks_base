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


#include <VideoEditorClasses.h>
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
#include <M4OSA_Debug.h>
#include <M4OSA_Thread.h>
#include <M4VSS3GPP_API.h>
#include <M4xVSS_API.h>
#include <M4VSS3GPP_ErrorCodes.h>
#include <M4MCS_ErrorCodes.h>
#include <M4READER_Common.h>
#include <M4WRITER_common.h>
#include <M4DECODER_Common.h>
};

#define VIDEOEDIT_PROP_JAVA_RESULT_STRING_MAX                     (128)

#define VIDEOEDIT_JAVA__RESULT_STRING_MAX                     (128)

VIDEOEDIT_JAVA_DEFINE_CONSTANTS(AudioEffect)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("NONE",     M4VSS3GPP_kAudioEffectType_None),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FADE_IN",  M4VSS3GPP_kAudioEffectType_FadeIn),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FADE_OUT", M4VSS3GPP_kAudioEffectType_FadeOut)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(AudioEffect, AUDIO_EFFECT_CLASS_NAME, M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(AudioFormat)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("NO_AUDIO",          M4VIDEOEDITING_kNoneAudio),
    VIDEOEDIT_JAVA_CONSTANT_INIT("AMR_NB",            M4VIDEOEDITING_kAMR_NB),
    VIDEOEDIT_JAVA_CONSTANT_INIT("AAC",               M4VIDEOEDITING_kAAC),
    VIDEOEDIT_JAVA_CONSTANT_INIT("AAC_PLUS",          M4VIDEOEDITING_kAACplus),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ENHANCED_AAC_PLUS", M4VIDEOEDITING_keAACplus),
    VIDEOEDIT_JAVA_CONSTANT_INIT("MP3",               M4VIDEOEDITING_kMP3),
    VIDEOEDIT_JAVA_CONSTANT_INIT("EVRC",              M4VIDEOEDITING_kEVRC),
    VIDEOEDIT_JAVA_CONSTANT_INIT("PCM",               M4VIDEOEDITING_kPCM),
    VIDEOEDIT_JAVA_CONSTANT_INIT("NULL_AUDIO",        M4VIDEOEDITING_kNullAudio),
    VIDEOEDIT_JAVA_CONSTANT_INIT("UNSUPPORTED_AUDIO", M4VIDEOEDITING_kUnsupportedAudio)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(AudioFormat, AUDIO_FORMAT_CLASS_NAME, M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(AudioSamplingFrequency)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("FREQ_DEFAULT", M4VIDEOEDITING_kDefault_ASF),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FREQ_8000",    M4VIDEOEDITING_k8000_ASF),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FREQ_16000",   M4VIDEOEDITING_k16000_ASF),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FREQ_22050",   M4VIDEOEDITING_k22050_ASF),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FREQ_24000",   M4VIDEOEDITING_k24000_ASF),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FREQ_32000",   M4VIDEOEDITING_k32000_ASF),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FREQ_44100",   M4VIDEOEDITING_k44100_ASF),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FREQ_48000",   M4VIDEOEDITING_k48000_ASF)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(AudioSamplingFrequency,AUDIO_SAMPLING_FREQUENCY_CLASS_NAME,
                                     M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(AudioTransition)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("NONE",       M4VSS3GPP_kAudioTransitionType_None),
    VIDEOEDIT_JAVA_CONSTANT_INIT("CROSS_FADE", M4VSS3GPP_kAudioTransitionType_CrossFade)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(AudioTransition, AUDIO_TRANSITION_CLASS_NAME, M4OSA_NULL,
                                     M4OSA_NULL)


static const char*
videoEditClasses_getUnknownBitrateString(int bitrate)
{
    static char string[VIDEOEDIT_JAVA__RESULT_STRING_MAX] = "";

    M4OSA_chrSPrintf((M4OSA_Char *)string, sizeof(string) - 1, (M4OSA_Char*)"%d", bitrate);

    // Return the bitrate string.
    return(string);
}

VIDEOEDIT_JAVA_DEFINE_CONSTANTS(Bitrate)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("VARIABLE",     M4VIDEOEDITING_kVARIABLE_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("UNDEFINED",    M4VIDEOEDITING_kUndefinedBitrate),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_9_2_KBPS",  M4VIDEOEDITING_k9_2_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_12_2_KBPS", M4VIDEOEDITING_k12_2_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_16_KBPS",   M4VIDEOEDITING_k16_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_24_KBPS",   M4VIDEOEDITING_k24_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_32_KBPS",   M4VIDEOEDITING_k32_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_48_KBPS",   M4VIDEOEDITING_k48_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_64_KBPS",   M4VIDEOEDITING_k64_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_96_KBPS",   M4VIDEOEDITING_k96_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_128_KBPS",  M4VIDEOEDITING_k128_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_192_KBPS",  M4VIDEOEDITING_k192_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_256_KBPS",  M4VIDEOEDITING_k256_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_288_KBPS",  M4VIDEOEDITING_k288_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_384_KBPS",  M4VIDEOEDITING_k384_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_512_KBPS",  M4VIDEOEDITING_k512_KBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_800_KBPS",  M4VIDEOEDITING_k800_KBPS),
/*+ New Encoder bitrates */
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_2_MBPS",  M4VIDEOEDITING_k2_MBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_5_MBPS",  M4VIDEOEDITING_k5_MBPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BR_8_MBPS",  M4VIDEOEDITING_k8_MBPS)
/*- New Encoder bitrates */
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(Bitrate, BITRATE_CLASS_NAME,
 videoEditClasses_getUnknownBitrateString, videoEditClasses_getUnknownBitrateString)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(ClipType)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("THREE_GPP",   M4VIDEOEDITING_kFileType_3GPP),
    VIDEOEDIT_JAVA_CONSTANT_INIT("MP4",         M4VIDEOEDITING_kFileType_MP4),
    VIDEOEDIT_JAVA_CONSTANT_INIT("AMR",         M4VIDEOEDITING_kFileType_AMR),
    VIDEOEDIT_JAVA_CONSTANT_INIT("MP3",         M4VIDEOEDITING_kFileType_MP3),
    VIDEOEDIT_JAVA_CONSTANT_INIT("PCM",         M4VIDEOEDITING_kFileType_PCM),
    VIDEOEDIT_JAVA_CONSTANT_INIT("JPG",         M4VIDEOEDITING_kFileType_JPG),
    VIDEOEDIT_JAVA_CONSTANT_INIT("PNG",         M4VIDEOEDITING_kFileType_PNG),
    VIDEOEDIT_JAVA_CONSTANT_INIT("M4V",         M4VIDEOEDITING_kFileType_M4V),
    VIDEOEDIT_JAVA_CONSTANT_INIT("UNSUPPORTED", M4VIDEOEDITING_kFileType_Unsupported)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(ClipType, FILE_TYPE_CLASS_NAME, M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(Engine)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("TASK_LOADING_SETTINGS",    TASK_LOADING_SETTINGS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("TASK_ENCODING",            TASK_ENCODING)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(Engine, MANUAL_EDIT_ENGINE_CLASS_NAME, M4OSA_NULL,
                                     M4OSA_NULL)


static const char*
videoEditClasses_getUnknownErrorName(int error)
{
    static char string[VIDEOEDIT_JAVA__RESULT_STRING_MAX] = "ERR_INTERNAL";

    // Format the unknown error string.
    M4OSA_chrSPrintf((M4OSA_Char *)string, sizeof(string) - 1, (M4OSA_Char*)"ERR_INTERNAL(%s)",
                    videoEditOsal_getResultString(error));

    // Return the error string.
    return(string);
}

static const char*
videoEditClasses_getUnknownErrorString(int error)
{
    // Return the result string.
    return(videoEditOsal_getResultString(error));
}

VIDEOEDIT_JAVA_DEFINE_CONSTANTS(Error)
{
    // M4OSA_Clock.h
    VIDEOEDIT_JAVA_CONSTANT_INIT("WAR_TIMESCALE_TOO_BIG",                   \
          M4WAR_TIMESCALE_TOO_BIG                               ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_CLOCK_BAD_REF_YEAR",                  \
          M4ERR_CLOCK_BAD_REF_YEAR                              ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_FILE_NOT_FOUND",                      \
          M4ERR_FILE_NOT_FOUND                                  ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("WAR_TRANSCODING_NECESSARY",               \
          M4VSS3GPP_WAR_TRANSCODING_NECESSARY                   ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("WAR_MAX_OUTPUT_SIZE_EXCEEDED",            \
          M4VSS3GPP_WAR_OUTPUTFILESIZE_EXCEED                   ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_BUFFER_OUT_TOO_SMALL",                \
          M4xVSSWAR_BUFFER_OUT_TOO_SMALL                        ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_NOMORE_SPACE_FOR_FILE",               \
          M4xVSSERR_NO_MORE_SPACE                               ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INVALID_FILE_TYPE",                   \
          M4VSS3GPP_ERR_INVALID_FILE_TYPE                       ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INVALID_EFFECT_KIND",                 \
          M4VSS3GPP_ERR_INVALID_EFFECT_KIND                     ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INVALID_VIDEO_EFFECT_TYPE",           \
          M4VSS3GPP_ERR_INVALID_VIDEO_EFFECT_TYPE               ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INVALID_AUDIO_EFFECT_TYPE",           \
          M4VSS3GPP_ERR_INVALID_AUDIO_EFFECT_TYPE               ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INVALID_VIDEO_TRANSITION_TYPE",       \
          M4VSS3GPP_ERR_INVALID_VIDEO_TRANSITION_TYPE           ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INVALID_AUDIO_TRANSITION_TYPE",       \
          M4VSS3GPP_ERR_INVALID_AUDIO_TRANSITION_TYPE           ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INVALID_VIDEO_ENCODING_FRAME_RATE",   \
          M4VSS3GPP_ERR_INVALID_VIDEO_ENCODING_FRAME_RATE       ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_EXTERNAL_EFFECT_NULL",                \
          M4VSS3GPP_ERR_EXTERNAL_EFFECT_NULL                    ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_EXTERNAL_TRANSITION_NULL",            \
          M4VSS3GPP_ERR_EXTERNAL_TRANSITION_NULL                ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_BEGIN_CUT_LARGER_THAN_DURATION",      \
          M4VSS3GPP_ERR_BEGIN_CUT_LARGER_THAN_DURATION          ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_BEGIN_CUT_LARGER_THAN_END_CUT",       \
          M4VSS3GPP_ERR_BEGIN_CUT_LARGER_THAN_END_CUT           ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_OVERLAPPING_TRANSITIONS",             \
         M4VSS3GPP_ERR_OVERLAPPING_TRANSITIONS                  ),
#ifdef M4VSS3GPP_ERR_ANALYSIS_DATA_SIZE_TOO_SMALL
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_ANALYSIS_DATA_SIZE_TOO_SMALL",        \
          M4VSS3GPP_ERR_ANALYSIS_DATA_SIZE_TOO_SMALL            ),
#endif
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INVALID_3GPP_FILE",                     \
        M4VSS3GPP_ERR_INVALID_3GPP_FILE                         ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_UNSUPPORTED_INPUT_VIDEO_FORMAT",        \
        M4VSS3GPP_ERR_UNSUPPORTED_INPUT_VIDEO_FORMAT            ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_UNSUPPORTED_INPUT_AUDIO_FORMAT",        \
        M4VSS3GPP_ERR_UNSUPPORTED_INPUT_AUDIO_FORMAT            ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_AMR_EDITING_UNSUPPORTED",               \
        M4VSS3GPP_ERR_AMR_EDITING_UNSUPPORTED                   ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INPUT_VIDEO_AU_TOO_LARGE",              \
        M4VSS3GPP_ERR_INPUT_VIDEO_AU_TOO_LARGE                  ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INPUT_AUDIO_AU_TOO_LARGE",              \
        M4VSS3GPP_ERR_INPUT_AUDIO_AU_TOO_LARGE                  ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INPUT_AUDIO_CORRUPTED_AU",              \
        M4VSS3GPP_ERR_INPUT_AUDIO_CORRUPTED_AU                  ),
#ifdef M4VSS3GPP_ERR_INPUT_AUDIO_CORRUPTED_AMR_AU
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INPUT_AUDIO_CORRUPTED_AU",              \
        M4VSS3GPP_ERR_INPUT_AUDIO_CORRUPTED_AMR_AU              ),
#endif
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_ENCODER_ACCES_UNIT_ERROR",              \
        M4VSS3GPP_ERR_ENCODER_ACCES_UNIT_ERROR                  ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_EDITING_UNSUPPORTED_VIDEO_FORMAT",      \
        M4VSS3GPP_ERR_EDITING_UNSUPPORTED_VIDEO_FORMAT          ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_EDITING_UNSUPPORTED_H263_PROFILE",      \
        M4VSS3GPP_ERR_EDITING_UNSUPPORTED_H263_PROFILE          ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_EDITING_UNSUPPORTED_MPEG4_PROFILE",     \
        M4VSS3GPP_ERR_EDITING_UNSUPPORTED_MPEG4_PROFILE         ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_EDITING_UNSUPPORTED_MPEG4_RVLC",        \
        M4VSS3GPP_ERR_EDITING_UNSUPPORTED_MPEG4_RVLC            ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_EDITING_UNSUPPORTED_AUDIO_FORMAT",      \
        M4VSS3GPP_ERR_EDITING_UNSUPPORTED_AUDIO_FORMAT          ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_EDITING_NO_SUPPORTED_STREAM_IN_FILE",   \
        M4VSS3GPP_ERR_EDITING_NO_SUPPORTED_STREAM_IN_FILE       ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_EDITING_NO_SUPPORTED_VIDEO_STREAM_IN_FILE",\
     M4VSS3GPP_ERR_EDITING_NO_SUPPORTED_VIDEO_STREAM_IN_FILE),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INVALID_CLIP_ANALYSIS_VERSION",        \
         M4VSS3GPP_ERR_INVALID_CLIP_ANALYSIS_VERSION            ),
#ifdef M4VSS3GPP_ERR_INVALID_CLIP_ANALYSIS_PLATFORM
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INVALID_CLIP_ANALYSIS_PLATFORM",       \
        M4VSS3GPP_ERR_INVALID_CLIP_ANALYSIS_PLATFORM            ),
#endif
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INCOMPATIBLE_VIDEO_FORMAT",            \
         M4VSS3GPP_ERR_INCOMPATIBLE_VIDEO_FORMAT                ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INCOMPATIBLE_VIDEO_FRAME_SIZE",        \
         M4VSS3GPP_ERR_INCOMPATIBLE_VIDEO_FRAME_SIZE            ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INCOMPATIBLE_VIDEO_TIME_SCALE",        \
         M4VSS3GPP_ERR_INCOMPATIBLE_VIDEO_TIME_SCALE            ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INCOMPATIBLE_VIDEO_DATA_PARTITIONING", \
         M4VSS3GPP_ERR_INCOMPATIBLE_VIDEO_DATA_PARTITIONING     ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_UNSUPPORTED_MP3_ASSEMBLY",             \
         M4VSS3GPP_ERR_UNSUPPORTED_MP3_ASSEMBLY                 ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_NO_SUPPORTED_STREAM_IN_FILE",          \
         M4VSS3GPP_ERR_NO_SUPPORTED_STREAM_IN_FILE              ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_ADDVOLUME_EQUALS_ZERO",                \
         M4VSS3GPP_ERR_ADDVOLUME_EQUALS_ZERO                    ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_ADDCTS_HIGHER_THAN_VIDEO_DURATION",    \
         M4VSS3GPP_ERR_ADDCTS_HIGHER_THAN_VIDEO_DURATION        ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_UNDEFINED_AUDIO_TRACK_FILE_FORMAT",    \
         M4VSS3GPP_ERR_UNDEFINED_AUDIO_TRACK_FILE_FORMAT        ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_UNSUPPORTED_ADDED_AUDIO_STREAM",       \
         M4VSS3GPP_ERR_UNSUPPORTED_ADDED_AUDIO_STREAM           ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_AUDIO_MIXING_UNSUPPORTED",             \
         M4VSS3GPP_ERR_AUDIO_MIXING_UNSUPPORTED                 ),
#ifdef M4VSS3GPP_ERR_AUDIO_MIXING_MP3_UNSUPPORTED
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_AUDIO_MIXING_MP3_UNSUPPORTED",         \
          M4VSS3GPP_ERR_AUDIO_MIXING_MP3_UNSUPPORTED            ),
#endif
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_FEATURE_UNSUPPORTED_WITH_AUDIO_TRACK", \
      M4VSS3GPP_ERR_FEATURE_UNSUPPORTED_WITH_AUDIO_TRACK        ),
#ifdef M4VSS3GPP_ERR_FEATURE_UNSUPPORTED_WITH_AAC
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_FEATURE_UNSUPPORTED_WITH_AAC",         \
       M4VSS3GPP_ERR_FEATURE_UNSUPPORTED_WITH_AAC               ),
#endif
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_AUDIO_CANNOT_BE_MIXED",                \
        M4VSS3GPP_ERR_AUDIO_CANNOT_BE_MIXED                     ),
#ifdef M4VSS3GPP_ERR_ONLY_AMRNB_INPUT_CAN_BE_MIXED
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_ONLY_AMRNB_INPUT_CAN_BE_MIXED",        \
         M4VSS3GPP_ERR_ONLY_AMRNB_INPUT_CAN_BE_MIXED            ),
#endif
#ifdef M4VSS3GPP_ERR_FEATURE_UNSUPPORTED_WITH_EVRC
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_FEATURE_UNSUPPORTED_WITH_EVRC",        \
          M4VSS3GPP_ERR_FEATURE_UNSUPPORTED_WITH_EVRC           ),
#endif
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_H263_PROFILE_NOT_SUPPORTED",           \
          M4VSS3GPP_ERR_H263_PROFILE_NOT_SUPPORTED              ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_NO_SUPPORTED_VIDEO_STREAM_IN_FILE",    \
          M4VSS3GPP_ERR_NO_SUPPORTED_VIDEO_STREAM_IN_FILE       ),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ERR_INTERNAL",                             \
          M4NO_ERROR                                            ),
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(Error, ERROR_CLASS_NAME,
 videoEditClasses_getUnknownErrorName, videoEditClasses_getUnknownErrorString)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(FileType)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("THREE_GPP",   VideoEditClasses_kFileType_3GPP),
    VIDEOEDIT_JAVA_CONSTANT_INIT("MP4",         VideoEditClasses_kFileType_MP4),
    VIDEOEDIT_JAVA_CONSTANT_INIT("AMR",         VideoEditClasses_kFileType_AMR),
    VIDEOEDIT_JAVA_CONSTANT_INIT("MP3",         VideoEditClasses_kFileType_MP3),
    VIDEOEDIT_JAVA_CONSTANT_INIT("PCM",         VideoEditClasses_kFileType_PCM),
    VIDEOEDIT_JAVA_CONSTANT_INIT("JPG",         VideoEditClasses_kFileType_JPG),
    VIDEOEDIT_JAVA_CONSTANT_INIT("GIF",         VideoEditClasses_kFileType_GIF),
    VIDEOEDIT_JAVA_CONSTANT_INIT("PNG",         VideoEditClasses_kFileType_PNG),
    VIDEOEDIT_JAVA_CONSTANT_INIT("M4V",         VideoEditClasses_kFileType_M4V),
    VIDEOEDIT_JAVA_CONSTANT_INIT("UNSUPPORTED", VideoEditClasses_kFileType_Unsupported)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(FileType, FILE_TYPE_CLASS_NAME, M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(MediaRendering)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("RESIZING",      M4xVSS_kResizing),
    VIDEOEDIT_JAVA_CONSTANT_INIT("CROPPING",      M4xVSS_kCropping),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BLACK_BORDERS", M4xVSS_kBlackBorders)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(MediaRendering, MEDIA_RENDERING_CLASS_NAME,
 M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(SlideDirection)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("RIGHT_OUT_LEFT_IN", M4xVSS_SlideTransition_RightOutLeftIn),
    VIDEOEDIT_JAVA_CONSTANT_INIT("LEFT_OUT_RIGTH_IN", M4xVSS_SlideTransition_LeftOutRightIn),
    VIDEOEDIT_JAVA_CONSTANT_INIT("TOP_OUT_BOTTOM_IN", M4xVSS_SlideTransition_TopOutBottomIn),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BOTTOM_OUT_TOP_IN", M4xVSS_SlideTransition_BottomOutTopIn)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(SlideDirection, SLIDE_DIRECTION_CLASS_NAME,
 M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(TransitionBehaviour)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("SPEED_UP",    M4VSS3GPP_TransitionBehaviour_SpeedUp),
    VIDEOEDIT_JAVA_CONSTANT_INIT("LINEAR",      M4VSS3GPP_TransitionBehaviour_Linear),
    VIDEOEDIT_JAVA_CONSTANT_INIT("SPEED_DOWN",  M4VSS3GPP_TransitionBehaviour_SpeedDown),
    VIDEOEDIT_JAVA_CONSTANT_INIT("SLOW_MIDDLE", M4VSS3GPP_TransitionBehaviour_SlowMiddle),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FAST_MIDDLE", M4VSS3GPP_TransitionBehaviour_FastMiddle)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(TransitionBehaviour, TRANSITION_BEHAVIOUR_CLASS_NAME,
 M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(VideoEffect)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("NONE",            M4VSS3GPP_kVideoEffectType_None),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FADE_FROM_BLACK", M4VSS3GPP_kVideoEffectType_FadeFromBlack),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FADE_TO_BLACK",   M4VSS3GPP_kVideoEffectType_FadeToBlack),
    VIDEOEDIT_JAVA_CONSTANT_INIT("EXTERNAL",        M4VSS3GPP_kVideoEffectType_External),
    VIDEOEDIT_JAVA_CONSTANT_INIT("BLACK_AND_WHITE", M4xVSS_kVideoEffectType_BlackAndWhite),
    VIDEOEDIT_JAVA_CONSTANT_INIT("PINK",            M4xVSS_kVideoEffectType_Pink),
    VIDEOEDIT_JAVA_CONSTANT_INIT("GREEN",           M4xVSS_kVideoEffectType_Green),
    VIDEOEDIT_JAVA_CONSTANT_INIT("SEPIA",           M4xVSS_kVideoEffectType_Sepia),
    VIDEOEDIT_JAVA_CONSTANT_INIT("NEGATIVE",        M4xVSS_kVideoEffectType_Negative),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FRAMING",         M4xVSS_kVideoEffectType_Framing),
    VIDEOEDIT_JAVA_CONSTANT_INIT("TEXT",            M4xVSS_kVideoEffectType_Text),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ZOOM_IN",         M4xVSS_kVideoEffectType_ZoomIn),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ZOOM_OUT",        M4xVSS_kVideoEffectType_ZoomOut),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FIFTIES",         M4xVSS_kVideoEffectType_Fifties),
    VIDEOEDIT_JAVA_CONSTANT_INIT("COLORRGB16",      M4xVSS_kVideoEffectType_ColorRGB16),
    VIDEOEDIT_JAVA_CONSTANT_INIT("GRADIENT",        M4xVSS_kVideoEffectType_Gradient),
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(VideoEffect, VIDEO_EFFECT_CLASS_NAME, M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(VideoFormat)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("NO_VIDEO",    M4VIDEOEDITING_kNoneVideo),
    VIDEOEDIT_JAVA_CONSTANT_INIT("H263",        M4VIDEOEDITING_kH263),
    VIDEOEDIT_JAVA_CONSTANT_INIT("MPEG4",       M4VIDEOEDITING_kMPEG4),
    VIDEOEDIT_JAVA_CONSTANT_INIT("H264",        M4VIDEOEDITING_kH264),
    VIDEOEDIT_JAVA_CONSTANT_INIT("NULL_VIDEO",  M4VIDEOEDITING_kNullVideo),
    VIDEOEDIT_JAVA_CONSTANT_INIT("UNSUPPORTED", M4VIDEOEDITING_kUnsupportedVideo),
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(VideoFormat, VIDEO_FORMAT_CLASS_NAME, M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(VideoFrameRate)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("FR_5_FPS",    M4VIDEOEDITING_k5_FPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FR_7_5_FPS",  M4VIDEOEDITING_k7_5_FPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FR_10_FPS",   M4VIDEOEDITING_k10_FPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FR_12_5_FPS", M4VIDEOEDITING_k12_5_FPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FR_15_FPS",   M4VIDEOEDITING_k15_FPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FR_20_FPS",   M4VIDEOEDITING_k20_FPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FR_25_FPS",   M4VIDEOEDITING_k25_FPS),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FR_30_FPS",   M4VIDEOEDITING_k30_FPS)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(VideoFrameRate, VIDEO_FRAME_RATE_CLASS_NAME,
 M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_CONSTANTS(VideoFrameSize)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("SQCIF", M4VIDEOEDITING_kSQCIF),
    VIDEOEDIT_JAVA_CONSTANT_INIT("QQVGA", M4VIDEOEDITING_kQQVGA),
    VIDEOEDIT_JAVA_CONSTANT_INIT("QCIF",  M4VIDEOEDITING_kQCIF),
    VIDEOEDIT_JAVA_CONSTANT_INIT("QVGA",  M4VIDEOEDITING_kQVGA),
    VIDEOEDIT_JAVA_CONSTANT_INIT("CIF",   M4VIDEOEDITING_kCIF),
    VIDEOEDIT_JAVA_CONSTANT_INIT("VGA",   M4VIDEOEDITING_kVGA),
    VIDEOEDIT_JAVA_CONSTANT_INIT("WVGA", M4VIDEOEDITING_kWVGA),
    VIDEOEDIT_JAVA_CONSTANT_INIT("NTSC", M4VIDEOEDITING_kNTSC),
    VIDEOEDIT_JAVA_CONSTANT_INIT("nHD", M4VIDEOEDITING_k640_360),
    VIDEOEDIT_JAVA_CONSTANT_INIT("WVGA16x9", M4VIDEOEDITING_k854_480),
    VIDEOEDIT_JAVA_CONSTANT_INIT("V720p", M4VIDEOEDITING_k1280_720),
    VIDEOEDIT_JAVA_CONSTANT_INIT("W720p", M4VIDEOEDITING_k1080_720),
    VIDEOEDIT_JAVA_CONSTANT_INIT("S720p", M4VIDEOEDITING_k960_720),
    VIDEOEDIT_JAVA_CONSTANT_INIT("V1080p", M4VIDEOEDITING_k1920_1080)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(VideoFrameSize, VIDEO_FRAME_SIZE_CLASS_NAME,
 M4OSA_NULL, M4OSA_NULL)

VIDEOEDIT_JAVA_DEFINE_CONSTANTS(VideoTransition)
{
    VIDEOEDIT_JAVA_CONSTANT_INIT("NONE",             M4VSS3GPP_kVideoTransitionType_None),
    VIDEOEDIT_JAVA_CONSTANT_INIT("CROSS_FADE",       M4VSS3GPP_kVideoTransitionType_CrossFade),
    VIDEOEDIT_JAVA_CONSTANT_INIT("EXTERNAL",         M4VSS3GPP_kVideoTransitionType_External),
    VIDEOEDIT_JAVA_CONSTANT_INIT("ALPHA_MAGIC",      M4xVSS_kVideoTransitionType_AlphaMagic),
    VIDEOEDIT_JAVA_CONSTANT_INIT("SLIDE_TRANSITION", M4xVSS_kVideoTransitionType_SlideTransition),
    VIDEOEDIT_JAVA_CONSTANT_INIT("FADE_BLACK",       M4xVSS_kVideoTransitionType_FadeBlack)
};

VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(VideoTransition, VIDEO_TRANSITION_CLASS_NAME,
                                     M4OSA_NULL, M4OSA_NULL)


VIDEOEDIT_JAVA_DEFINE_FIELDS(AlphaMagic)
{
    VIDEOEDIT_JAVA_FIELD_INIT("file",            "Ljava/lang/String;"),
    VIDEOEDIT_JAVA_FIELD_INIT("blendingPercent", "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("invertRotation",  "Z"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("rgbWidth",  "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("rgbHeight",  "I"                 )
};

VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(AlphaMagic, ALPHA_MAGIC_SETTINGS_CLASS_NAME)

VIDEOEDIT_JAVA_DEFINE_FIELDS(Properties)
{
    VIDEOEDIT_JAVA_FIELD_INIT("duration",               "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("fileType",               "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("videoFormat",            "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("videoDuration",          "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("videoBitrate",           "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("width",                  "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("height",                 "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("averageFrameRate",       "F"),
    VIDEOEDIT_JAVA_FIELD_INIT("profile",                "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("level",                  "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("profileSupported",       "Z"),
    VIDEOEDIT_JAVA_FIELD_INIT("levelSupported",         "Z"),
    VIDEOEDIT_JAVA_FIELD_INIT("audioFormat",            "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("audioDuration",          "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("audioBitrate",           "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("audioChannels",          "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("audioSamplingFrequency", "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("videoRotation",          "I")
};

VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(Properties, PROPERTIES_CLASS_NAME)

VIDEOEDIT_JAVA_DEFINE_FIELDS(BackgroundMusic)
{
    VIDEOEDIT_JAVA_FIELD_INIT("file",          "Ljava/lang/String;"),
    VIDEOEDIT_JAVA_FIELD_INIT("fileType",      "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("insertionTime", "J"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("volumePercent", "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("beginLoop",     "J"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("endLoop",       "J"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("enableDucking",   "Z"               ),
    VIDEOEDIT_JAVA_FIELD_INIT("duckingThreshold","I"               ),
    VIDEOEDIT_JAVA_FIELD_INIT("lowVolume",         "I"             ),
    VIDEOEDIT_JAVA_FIELD_INIT("isLooping",         "Z"             )
};

VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(BackgroundMusic, BACKGROUND_MUSIC_SETTINGS_CLASS_NAME)

/*
VIDEOEDIT_JAVA_DEFINE_FIELDS(BestEditSettings)
{
    VIDEOEDIT_JAVA_FIELD_INIT("videoFormat",    "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("videoFrameSize", "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("audioFormat",    "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("audioChannels",  "I")
};

VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(BestEditSettings, BEST_EDIT_SETTINGS_CLASS_NAME)
*/

VIDEOEDIT_JAVA_DEFINE_FIELDS(ClipSettings)
{
    VIDEOEDIT_JAVA_FIELD_INIT("clipPath",             "Ljava/lang/String;"),
    VIDEOEDIT_JAVA_FIELD_INIT("fileType",             "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("beginCutTime",         "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("endCutTime",           "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("beginCutPercent",      "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("endCutPercent",        "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("panZoomEnabled",       "Z"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("panZoomPercentStart",  "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("panZoomTopLeftXStart", "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("panZoomTopLeftYStart", "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("panZoomPercentEnd",    "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("panZoomTopLeftXEnd",   "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("panZoomTopLeftYEnd",   "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("mediaRendering",       "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("rgbWidth",           "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("rgbHeight",          "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("rotationDegree",     "I"                 )
};

VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(ClipSettings, CLIP_SETTINGS_CLASS_NAME)


VIDEOEDIT_JAVA_DEFINE_FIELDS(EditSettings)
{
    VIDEOEDIT_JAVA_FIELD_INIT("clipSettingsArray",       "[L"CLIP_SETTINGS_CLASS_NAME";"         ),
    VIDEOEDIT_JAVA_FIELD_INIT("transitionSettingsArray", "[L"TRANSITION_SETTINGS_CLASS_NAME";"   ),
    VIDEOEDIT_JAVA_FIELD_INIT("effectSettingsArray",     "[L"EFFECT_SETTINGS_CLASS_NAME";"       ),
    VIDEOEDIT_JAVA_FIELD_INIT("videoFrameRate",          "I"                                     ),
    VIDEOEDIT_JAVA_FIELD_INIT("outputFile",              "Ljava/lang/String;"                    ),
    VIDEOEDIT_JAVA_FIELD_INIT("videoFrameSize",          "I"                                     ),
    VIDEOEDIT_JAVA_FIELD_INIT("videoFormat",             "I"                                     ),
    VIDEOEDIT_JAVA_FIELD_INIT("videoProfile",            "I"                                     ),
    VIDEOEDIT_JAVA_FIELD_INIT("videoLevel",              "I"                                     ),
    VIDEOEDIT_JAVA_FIELD_INIT("audioFormat",             "I"                                     ),
    VIDEOEDIT_JAVA_FIELD_INIT("audioSamplingFreq",       "I"                                     ),
    VIDEOEDIT_JAVA_FIELD_INIT("maxFileSize",             "I"                                     ),
    VIDEOEDIT_JAVA_FIELD_INIT("audioChannels",           "I"                                     ),
    VIDEOEDIT_JAVA_FIELD_INIT("videoBitrate",            "I"                                     ),
    VIDEOEDIT_JAVA_FIELD_INIT("audioBitrate",            "I"                                     ),
    VIDEOEDIT_JAVA_FIELD_INIT("backgroundMusicSettings",\
    "L"BACKGROUND_MUSIC_SETTINGS_CLASS_NAME";"),
    VIDEOEDIT_JAVA_FIELD_INIT("primaryTrackVolume",            "I"                               )
};

VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(EditSettings, EDIT_SETTINGS_CLASS_NAME)


VIDEOEDIT_JAVA_DEFINE_FIELDS(EffectSettings)
{
    VIDEOEDIT_JAVA_FIELD_INIT("startTime",                       "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("duration",                        "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("videoEffectType",                 "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("audioEffectType",                 "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("startPercent",                    "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("durationPercent",                 "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("framingFile",                     "Ljava/lang/String;"),
    VIDEOEDIT_JAVA_FIELD_INIT("framingBuffer",                   "[I"                ),
    VIDEOEDIT_JAVA_FIELD_INIT("bitmapType",                      "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("width",                           "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("height",                          "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("topLeftX",                        "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("topLeftY",                        "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("framingResize",                   "Z"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("framingScaledSize",               "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("text",                            "Ljava/lang/String;"),
    VIDEOEDIT_JAVA_FIELD_INIT("textRenderingData",               "Ljava/lang/String;"),
    VIDEOEDIT_JAVA_FIELD_INIT("textBufferWidth",                 "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("textBufferHeight",                "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("fiftiesFrameRate",                "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("rgb16InputColor",                 "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("alphaBlendingStartPercent",       "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("alphaBlendingMiddlePercent",      "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("alphaBlendingEndPercent",         "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("alphaBlendingFadeInTimePercent",  "I"                 ),
    VIDEOEDIT_JAVA_FIELD_INIT("alphaBlendingFadeOutTimePercent", "I"                 )
};

VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(EffectSettings, EFFECT_SETTINGS_CLASS_NAME)


VIDEOEDIT_JAVA_DEFINE_FIELDS(Engine)
{
    VIDEOEDIT_JAVA_FIELD_INIT("mManualEditContext", "I")
};

VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(Engine, MANUAL_EDIT_ENGINE_CLASS_NAME)


VIDEOEDIT_JAVA_DEFINE_FIELDS(SlideTransitionSettings)
{
    VIDEOEDIT_JAVA_FIELD_INIT("direction", "I")
};

VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(SlideTransitionSettings, SLIDE_TRANSITION_SETTINGS_CLASS_NAME)


VIDEOEDIT_JAVA_DEFINE_FIELDS(TransitionSettings)
{
    VIDEOEDIT_JAVA_FIELD_INIT("duration",            "I"                                       ),
    VIDEOEDIT_JAVA_FIELD_INIT("videoTransitionType", "I"                                       ),
    VIDEOEDIT_JAVA_FIELD_INIT("audioTransitionType", "I"                                       ),
    VIDEOEDIT_JAVA_FIELD_INIT("transitionBehaviour", "I"                                       ),
    VIDEOEDIT_JAVA_FIELD_INIT("alphaSettings",       "L"ALPHA_MAGIC_SETTINGS_CLASS_NAME";"     ),
    VIDEOEDIT_JAVA_FIELD_INIT("slideSettings",       "L"SLIDE_TRANSITION_SETTINGS_CLASS_NAME";")
};

VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(TransitionSettings, TRANSITION_SETTINGS_CLASS_NAME)


VIDEOEDIT_JAVA_DEFINE_FIELDS(Version)
{
    VIDEOEDIT_JAVA_FIELD_INIT("major",    "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("minor",    "I"),
    VIDEOEDIT_JAVA_FIELD_INIT("revision", "I")
};

VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(Version, VERSION_CLASS_NAME)


VIDEOEDIT_JAVA_DEFINE_METHODS(Engine)
{
    VIDEOEDIT_JAVA_METHOD_INIT("onProgressUpdate", "(II)V")
};

VIDEOEDIT_JAVA_DEFINE_METHOD_CLASS(Engine, MANUAL_EDIT_ENGINE_CLASS_NAME)


static const char*
videoEditClasses_getBrandString(M4OSA_UInt32 brand)
{
    static char         brandString[11] = "0x00000000";
           const char*  pBrandString    = M4OSA_NULL;
           M4OSA_UInt8* pBrand          = (M4OSA_UInt8*)&brand;
           M4OSA_UInt32 brandHost       = 0;

    // Convert the brand from big endian to host.
    brandHost =  pBrand[0];
    brandHost =  brandHost << 8;
    brandHost += pBrand[1];
    brandHost =  brandHost << 8;
    brandHost += pBrand[2];
    brandHost =  brandHost << 8;
    brandHost += pBrand[3];

    switch (brandHost)
    {
    case M4VIDEOEDITING_BRAND_0000:
        pBrandString = "0000";
        break;
    case M4VIDEOEDITING_BRAND_3G2A:
        pBrandString = "3G2A";
        break;
    case M4VIDEOEDITING_BRAND_3GP4:
        pBrandString = "3GP4";
        break;
    case M4VIDEOEDITING_BRAND_3GP5:
        pBrandString = "3GP5";
        break;
    case M4VIDEOEDITING_BRAND_3GP6:
        pBrandString = "3GP6";
        break;
    case M4VIDEOEDITING_BRAND_AVC1:
        pBrandString = "AVC1";
        break;
    case M4VIDEOEDITING_BRAND_EMP:
        pBrandString = "EMP";
        break;
    case M4VIDEOEDITING_BRAND_ISOM:
        pBrandString = "ISOM";
        break;
    case M4VIDEOEDITING_BRAND_MP41:
        pBrandString = "MP41";
        break;
    case M4VIDEOEDITING_BRAND_MP42:
        pBrandString = "MP42";
        break;
    case M4VIDEOEDITING_BRAND_VFJ1:
        pBrandString = "VFJ1";
        break;
    default:
        M4OSA_chrSPrintf((M4OSA_Char *)brandString,
                         sizeof(brandString) - 1,
                         (M4OSA_Char*)"0x%08X", brandHost);
        pBrandString = brandString;
        break;
    }

    // Return the brand string.
    return(pBrandString);
}

#ifdef VIDEOEDIT_LOGGING_ENABLED
static void
videoEditClasses_logFtypBox(
                M4VIDEOEDITING_FtypBox*             pBox,
                int                                 indentation)
{
    // Check if memory was allocated for the FtypBox.
    if (M4OSA_NULL != pBox)
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                "%*c major_brand:        %s",    indentation, ' ',
                 videoEditClasses_getBrandString(pBox->major_brand));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                "%*c minor_version:      %08X",  indentation, ' ',
                (unsigned int)pBox->minor_version);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                "%*c nbCompatibleBrands: %u",    indentation, ' ',
                (unsigned int)pBox->nbCompatibleBrands);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                "%*c compatible_brands:", indentation, ' ');
                indentation += VIDEOEDIT_LOG_INDENTATION;
        for (int i = 0; (i < (int)pBox->nbCompatibleBrands) &&\
         (i < M4VIDEOEDITING_MAX_COMPATIBLE_BRANDS); i++)
        {
            VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                    "%*c compatible_brand[%d]: %s",    indentation, ' ',
                    i, videoEditClasses_getBrandString(pBox->compatible_brands[i]));
        }
        indentation -= VIDEOEDIT_LOG_INDENTATION;
    }
    else
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c <null>",
                 indentation, ' ');
    }
}
#endif


void
videoEditClasses_init(
                bool*                               pResult,
                JNIEnv*                             pEnv)
{
    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",\
        "videoEditClasses_init()");

        // Initialize the constants.
        videoEditJava_initAudioEffectConstants(pResult, pEnv);
        videoEditJava_initAudioFormatConstants(pResult, pEnv);
        videoEditJava_initAudioSamplingFrequencyConstants(pResult, pEnv);
        videoEditJava_initAudioTransitionConstants(pResult, pEnv);
        videoEditJava_initBitrateConstants(pResult, pEnv);
        videoEditJava_initClipTypeConstants(pResult, pEnv);
        videoEditJava_initEngineConstants(pResult, pEnv);
        videoEditJava_initErrorConstants(pResult, pEnv);
        videoEditJava_initFileTypeConstants(pResult, pEnv);
        videoEditJava_initMediaRenderingConstants(pResult, pEnv);
        videoEditJava_initSlideDirectionConstants(pResult, pEnv);
        videoEditJava_initTransitionBehaviourConstants(pResult, pEnv);
        videoEditJava_initVideoEffectConstants(pResult, pEnv);
        videoEditJava_initVideoFormatConstants(pResult, pEnv);
        videoEditJava_initVideoFrameRateConstants(pResult, pEnv);
        videoEditJava_initVideoFrameSizeConstants(pResult, pEnv);
        videoEditJava_initVideoTransitionConstants(pResult, pEnv);

        // Initialize the fields.
        videoEditJava_initAlphaMagicFields(pResult, pEnv);
        videoEditJava_initBackgroundMusicFields(pResult, pEnv);
        videoEditJava_initClipSettingsFields(pResult, pEnv);
        videoEditJava_initEditSettingsFields(pResult, pEnv);
        videoEditJava_initEffectSettingsFields(pResult, pEnv);
        videoEditJava_initEngineFields(pResult, pEnv);
        videoEditJava_initSlideTransitionSettingsFields(pResult, pEnv);
        videoEditJava_initTransitionSettingsFields(pResult, pEnv);
        videoEditJava_initVersionFields(pResult, pEnv);
        // Initialize the methods.
        videoEditJava_initEngineMethods(pResult, pEnv);
    }
}

void
videoEditPropClass_init(
                bool*                               pResult,
                JNIEnv*                             pEnv)
{
    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",\
            "videoEditPropClass_init()");

        // Initialize the constants.
        videoEditJava_initAudioFormatConstants(pResult, pEnv);
        videoEditJava_initErrorConstants(pResult, pEnv);
        videoEditJava_initFileTypeConstants(pResult, pEnv);
        videoEditJava_initVideoFormatConstants(pResult, pEnv);

        // Initialize the fields.
        videoEditJava_initPropertiesFields(pResult, pEnv);
    }
}

void
videoEditClasses_getAlphaMagicSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4xVSS_AlphaMagicSettings**         ppSettings)
{
    VideoEditJava_AlphaMagicFieldIds fieldIds;
    M4xVSS_AlphaMagicSettings* pSettings = M4OSA_NULL;
    memset(&fieldIds, 0, sizeof(VideoEditJava_AlphaMagicFieldIds));

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                   "videoEditClasses_getAlphaMagicSettings()");

        // Retrieve the field ids.
        videoEditJava_getAlphaMagicFieldIds(pResult, pEnv, &fieldIds);
    }

    // Only validate the AlphaMagicSettings if the fields could be located.
    if (*pResult)
    {
        // Check if the clip is set.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                                                    (NULL == object),
                                                    "alphaSettings is null");
    }

    // Only retrieve the AlphaMagicSettings if the fields could be located and validated.
    if (*pResult)
    {
        // Allocate memory for the AlphaMagicSettings.
        pSettings = (M4xVSS_AlphaMagicSettings*)videoEditOsal_alloc(pResult, pEnv,
                sizeof(M4xVSS_AlphaMagicSettings), "AlphaMagicSettings");

        // Check if memory could be allocated for the AlphaMagicSettings.
        if (*pResult)
        {
            // Set the alpha magic file path (JPG file).
            pSettings->pAlphaFilePath = (M4OSA_Char*)videoEditJava_getString(pResult, pEnv, object,
                    fieldIds.file, M4OSA_NULL);

            // Check if the alpha magic file path is valid.
            videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                    (M4OSA_NULL == pSettings->pAlphaFilePath), "alphaSettings.file is null");
        }

        // Check if the alpha file path could be retrieved.
        if (*pResult)
        {
            // Set the blending percentage between 0 and 100.
            pSettings->blendingPercent = (M4OSA_UInt8)pEnv->GetIntField(object,
                    fieldIds.blendingPercent);

            // Set the direct effect or reverse.
            pSettings->isreverse = (M4OSA_Bool)pEnv->GetBooleanField(object,
                    fieldIds.invertRotation);

            // Get the rgb width
            pSettings->width = (M4OSA_UInt32) pEnv->GetIntField(object, fieldIds.rgbWidth );

            pSettings->height = (M4OSA_UInt32) pEnv->GetIntField(object, fieldIds.rgbHeight );

             VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                    "((((((((((path %s", pSettings->pAlphaFilePath);

            VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                    "------- getAlphaMagicSettings width %d", pEnv->GetIntField(object,
                    fieldIds.rgbWidth ));
            VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                   "-------- getAlphaMagicSettings Height %d",
                   pEnv->GetIntField(object, fieldIds.rgbHeight ));
        }

        // Check if settings could be set.
        if (*pResult)
        {
            // Return the settings.
            (*ppSettings) = pSettings;
        }
        else
        {
            // Free the settings.
            videoEditClasses_freeAlphaMagicSettings(&pSettings);
        }
    }
}

void
videoEditClasses_freeAlphaMagicSettings(
                M4xVSS_AlphaMagicSettings**         ppSettings)
{
    // Check if memory was allocated for the AlphaMagicSettings.
    if (M4OSA_NULL != (*ppSettings))
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                  "videoEditClasses_freeAlphaMagicSettings()");

        // Free the alpha file path.
        videoEditOsal_free((*ppSettings)->pAlphaFilePath);
        (*ppSettings)->pAlphaFilePath = M4OSA_NULL;

        // Free the settings structure.
        videoEditOsal_free((*ppSettings));
        (*ppSettings) = M4OSA_NULL;
    }
}

#ifdef VIDEOEDIT_LOGGING_ENABLED
void
videoEditClasses_logAlphaMagicSettings(
                M4xVSS_AlphaMagicSettings*          pSettings,
                int                                 indentation)
{
    // Check if memory was allocated for the AlphaMagicSettings.
    if (M4OSA_NULL != pSettings)
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
             "%*c pAlphaFilePath:  %s",    indentation, ' ',
            (M4OSA_NULL != pSettings->pAlphaFilePath) ? \
            (char *)pSettings->pAlphaFilePath : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
             "%*c blendingPercent: %u %%", indentation, ' ',
            (unsigned int)pSettings->blendingPercent);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c isreverse:       %s",    indentation, ' ',
            pSettings->isreverse ? "true" : "false");
    }
    else
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c <null>", indentation, ' ');
    }
}
#endif


void
videoEditClasses_getBackgroundMusicSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4xVSS_BGMSettings**                ppSettings)
{
    VideoEditJava_BackgroundMusicFieldIds fieldIds;
    M4xVSS_BGMSettings*           pSettings = M4OSA_NULL;
    bool                          converted = true;
    memset(&fieldIds, 0, sizeof(VideoEditJava_BackgroundMusicFieldIds));
    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
               "videoEditClasses_getBackgroundMusicSettings()");

        // Retrieve the field ids.
        videoEditJava_getBackgroundMusicFieldIds(pResult, pEnv, &fieldIds);
    }

    // Only retrieve the BackgroundMusicSettings if the fields could be located.
    if (*pResult)
    {
        // Check if the object is valid.
        if (NULL != object)
        {
            // Allocate memory for the BackgroundMusicSettings.
            pSettings = (M4xVSS_BGMSettings*)videoEditOsal_alloc(pResult, pEnv,
                sizeof(M4xVSS_BGMSettings), "BackgroundMusicSettings");

            // Check if memory could be allocated for the BackgroundMusicSettings.
            if (*pResult)
            {
                // Set the input file path.
                pSettings->pFile = (M4OSA_Char*)videoEditJava_getString(pResult, pEnv, object,
                        fieldIds.file, M4OSA_NULL);

                // Check if the input file path is valid.
                videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                        (M4OSA_NULL == pSettings->pFile), "backgroundMusicSettings.file is null");
            }

            // Check if the input file path could be retrieved.
            if (*pResult)
            {
                // Set the file type .3gp, .amr, .mp3.
                pSettings->FileType = M4VIDEOEDITING_kFileType_PCM;
                /*(M4VIDEOEDITING_FileType)videoEditJava_getClipTypeJavaToC(
                 &converted, pEnv->GetIntField(object, fieldIds.fileType));*/

                // Check if the file type is valid.
                videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                        !converted, "backgroundMusicSettings.fileType is invalid");
            }

            // Check if the file type could be retrieved.
            if (*pResult)
            {
                // Set the time, in milliseconds, at which the added audio track is inserted.
                pSettings->uiAddCts = (M4OSA_UInt32)pEnv->GetLongField(object,
                        fieldIds.insertionTime);

                // Set the volume, in percentage (0..100), of the added audio track.
                pSettings->uiAddVolume = (M4OSA_UInt32)pEnv->GetIntField(object,
                        fieldIds.volumePercent);

                // Set the start time of the loop in milli seconds.
                pSettings->uiBeginLoop = (M4OSA_UInt32)pEnv->GetLongField(object,
                        fieldIds.beginLoop);

                // Set the end time of the loop in milli seconds.
                pSettings->uiEndLoop = (M4OSA_UInt32)pEnv->GetLongField(object,
                        fieldIds.endLoop);
                // Set the end time of the loop in milli seconds.
                pSettings->b_DuckingNeedeed =
                        (M4OSA_Bool)pEnv->GetBooleanField(object, fieldIds.enableDucking);

                // Set the end time of the loop in milli seconds.
                pSettings->InDucking_threshold =
                        (M4OSA_Int32)pEnv->GetIntField(object, fieldIds.duckingThreshold);

                // Set the end time of the loop in milli seconds.
                pSettings->lowVolume =
                        (M4OSA_Float)(((M4OSA_Float)pEnv->GetIntField(object, fieldIds.lowVolume)));

                // Set the end time of the loop in milli seconds.
                pSettings->bLoop = (M4OSA_Bool)pEnv->GetBooleanField(object, fieldIds.isLooping);

                // Set sampling freq and channels
                pSettings->uiSamplingFrequency = M4VIDEOEDITING_k32000_ASF;
                pSettings->uiNumChannels = 2;
            }

            // Check if settings could be set.
            if (*pResult)
            {
                // Return the settings.
                (*ppSettings) = pSettings;
            }
            else
            {
                // Free the settings.
                videoEditClasses_freeBackgroundMusicSettings(&pSettings);
            }
        }
    }
}

void
videoEditClasses_freeBackgroundMusicSettings(
                M4xVSS_BGMSettings**                ppSettings)
{
    // Check if memory was allocated for the BackgroundMusicSettings.
    if (M4OSA_NULL != (*ppSettings))
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
         "videoEditClasses_freeBackgroundMusicSettings()");

        // Free the input file path.
        videoEditOsal_free((*ppSettings)->pFile);
        (*ppSettings)->pFile = M4OSA_NULL;

        // Free the settings structure.
        videoEditOsal_free((*ppSettings));
        (*ppSettings) = M4OSA_NULL;
    }
}

#ifdef VIDEOEDIT_LOGGING_ENABLED
void
videoEditClasses_logBackgroundMusicSettings(
                M4xVSS_BGMSettings*                 pSettings,
                int                                 indentation)
{
    // Check if memory was allocated for the BackgroundMusicSettings.
    if (M4OSA_NULL != pSettings)
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c pFile:       %s",
            indentation, ' ',
            (M4OSA_NULL != pSettings->pFile) ? (char *)pSettings->pFile : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c FileType:    %s",    indentation, ' ',
            videoEditJava_getClipTypeString(pSettings->FileType));

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c uiAddCts:    %u ms",
            indentation, ' ', (unsigned int)pSettings->uiAddCts);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c uiAddVolume: %u %%",
            indentation, ' ', (unsigned int)pSettings->uiAddVolume);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c uiBeginLoop: %u ms",
            indentation, ' ', (unsigned int)pSettings->uiBeginLoop);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c uiEndLoop:   %u ms",
            indentation, ' ', (unsigned int)pSettings->uiEndLoop);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c b_DuckingNeedeed:\
            %u ", indentation, ' ', (bool)pSettings->b_DuckingNeedeed);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c InDucking_threshold: \
            %u ms", indentation, ' ', (unsigned int)pSettings->InDucking_threshold);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c lowVolume:   %2.2f ",\
            indentation, ' ', (float)pSettings->lowVolume);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c bLoop:   %u ms",\
            indentation, ' ', (bool)pSettings->bLoop);
    }
    else
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c <null>",
            indentation, ' ');
    }
}
#endif

#ifdef VIDEOEDIT_LOGGING_ENABLED
void
videoEditClasses_logClipProperties(
                M4VIDEOEDITING_ClipProperties*      pProperties,
                int                                 indentation)
{
    // Check if memory was allocated for the ClipProperties.
    if (M4OSA_NULL != pProperties)
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c bAnalysed:                        %s",       indentation, ' ',
            pProperties->bAnalysed ? "true" : "false");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c Version:                          %d.%d.%d", indentation, ' ',
            pProperties->Version[0], pProperties->Version[1], pProperties->Version[2]);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiClipDuration:                   %u",       indentation, ' ',
            (unsigned int)pProperties->uiClipDuration);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c FileType:                         %s",       indentation, ' ',
            videoEditJava_getClipTypeString(pProperties->FileType));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c ftyp:",
                                              indentation, ' ');
        videoEditClasses_logFtypBox(&pProperties->ftyp, indentation + VIDEOEDIT_LOG_INDENTATION);

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c VideoStreamType:                  %s",       indentation, ' ',
            videoEditJava_getVideoFormatString(pProperties->VideoStreamType));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiClipVideoDuration:              %u",       indentation, ' ',
            (unsigned int)pProperties->uiClipVideoDuration);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiVideoBitrate:                   %s",       indentation, ' ',
            videoEditJava_getBitrateString(pProperties->uiVideoBitrate));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiVideoMaxAuSize:                 %u",       indentation, ' ',
            (unsigned int)pProperties->uiVideoMaxAuSize);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiVideoWidth:                     %u",       indentation, ' ',
            (unsigned int)pProperties->uiVideoWidth);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiVideoHeight:                    %u",       indentation, ' ',
            (unsigned int)(unsigned int)pProperties->uiVideoHeight);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiVideoTimeScale:                 %u",       indentation, ' ',
            (unsigned int)pProperties->uiVideoTimeScale);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c fAverageFrameRate:                %.3f",     indentation, ' ',
            pProperties->fAverageFrameRate);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c bMPEG4dataPartition:              %s",       indentation, ' ',
            pProperties->bMPEG4dataPartition ? "true" : "false");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c bMPEG4rvlc:                       %s",       indentation, ' ',
            pProperties->bMPEG4rvlc ? "true" : "false");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c bMPEG4resynchMarker:              %s",       indentation, ' ',
            pProperties->bMPEG4resynchMarker ? "true" : "false");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c AudioStreamType:                  %s",       indentation, ' ',
            videoEditJava_getAudioFormatString(pProperties->AudioStreamType));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiClipAudioDuration:              %u",       indentation, ' ',
            (unsigned int)pProperties->uiClipAudioDuration);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiAudioBitrate:                   %s",       indentation, ' ',
            videoEditJava_getBitrateString(pProperties->uiAudioBitrate));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiAudioMaxAuSize:                 %u",       indentation, ' ',
            (unsigned int)pProperties->uiAudioMaxAuSize);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiNbChannels:                     %u",       indentation, ' ',
            (unsigned int)pProperties->uiNbChannels);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiSamplingFrequency:              %u",       indentation, ' ',
            (unsigned int)pProperties->uiSamplingFrequency);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiExtendedSamplingFrequency:      %u",       indentation, ' ',
            (unsigned int)pProperties->uiExtendedSamplingFrequency);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiDecodedPcmSize:                 %u",       indentation, ' ',
            (unsigned int)pProperties->uiDecodedPcmSize);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c bVideoIsEditable:                 %s",       indentation, ' ',
            pProperties->bVideoIsEditable ? "true" : "false");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c bAudioIsEditable:                 %s",       indentation, ' ',
            pProperties->bAudioIsEditable ? "true" : "false");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c bVideoIsCompatibleWithMasterClip: %s",       indentation, ' ',
            pProperties->bVideoIsCompatibleWithMasterClip ? "true" : "false");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c bAudioIsCompatibleWithMasterClip: %s",       indentation, ' ',
            pProperties->bAudioIsCompatibleWithMasterClip ? "true" : "false");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiClipAudioVolumePercentage:      %d",       indentation, ' ',
                        pProperties->uiClipAudioVolumePercentage);
    }
    else
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES", "%*c <null>",
            indentation, ' ');
    }
}
#endif

void
videoEditClasses_getClipSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4VSS3GPP_ClipSettings**            ppSettings)
{

    VideoEditJava_ClipSettingsFieldIds fieldIds;
    M4VSS3GPP_ClipSettings*    pSettings = M4OSA_NULL;
    M4OSA_ERR                  result    = M4NO_ERROR;
    bool                       converted = true;
    memset(&fieldIds, 0, sizeof(VideoEditJava_ClipSettingsFieldIds));
    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "videoEditClasses_getClipSettings()");

        // Retrieve the field ids.
        videoEditJava_getClipSettingsFieldIds(pResult, pEnv, &fieldIds);
    }

    // Only validate the ClipSettings if the fields could be located.
    if (*pResult)
    {
        // Check if the clip is set.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                                                    (NULL == object),
                                                    "clip is null");
    }

    // Only retrieve the ClipSettings if the fields could be located and validated.
    if (*pResult)
    {
        // Allocate memory for the ClipSettings.
        pSettings = (M4VSS3GPP_ClipSettings *)videoEditOsal_alloc(pResult, pEnv,
            sizeof(M4VSS3GPP_ClipSettings), "ClipSettings");

        // Check if memory could be allocated for the ClipSettings.
        if (*pResult)
        {
            // Log the API call.
            VIDEOEDIT_LOG_API(ANDROID_LOG_INFO, "VIDEO_EDITOR", "M4xVSS_CreateClipSettings()");

            // Initialize the ClipSettings.
            result = M4xVSS_CreateClipSettings(pSettings, NULL, 0, 0);

            // Log the result.
            VIDEOEDIT_LOG_RESULT(ANDROID_LOG_INFO, "VIDEO_EDITOR",
                videoEditOsal_getResultString(result));

            // Check if the initialization succeeded.
            videoEditJava_checkAndThrowRuntimeException(pResult, pEnv,
                (M4NO_ERROR != result), result);
        }

        // Check if the allocation and initialization succeeded
        //(required because pSettings is dereferenced).
        if (*pResult)
        {
            // Set the input file path.
            pSettings->pFile = (M4OSA_Char*)videoEditJava_getString(pResult, pEnv, object,
                fieldIds.clipPath, &pSettings->filePathSize);

            // Check if the file path is valid.
            videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                    (M4OSA_NULL == pSettings->pFile), "clip.clipPath is null");
        }

        // Check if the input file could be retrieved.
        if (*pResult)
        {
            // Set the file type .3gp, .amr, .mp3.
            pSettings->FileType = (M4VIDEOEDITING_FileType)videoEditJava_getClipTypeJavaToC(
                                        &converted, pEnv->GetIntField(object, fieldIds.fileType));

            if (( pSettings->FileType == M4VIDEOEDITING_kFileType_JPG) ||
                 ( pSettings->FileType == M4VIDEOEDITING_kFileType_PNG)) {
                 pSettings->FileType = M4VIDEOEDITING_kFileType_ARGB8888;
            }

            // Check if the file type is valid.
            videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                    !converted, "clip.fileType is invalid");
        }

        // Check if the file type could be retrieved.
        if (*pResult)
        {
            // Set the begin cut time, in milliseconds.
            pSettings->uiBeginCutTime =
                (M4OSA_UInt32)pEnv->GetIntField(object, fieldIds.beginCutTime);

            // Set the end cut time, in milliseconds.
            pSettings->uiEndCutTime = (M4OSA_UInt32)pEnv->GetIntField(object, fieldIds.endCutTime);

            // Set the begin cut time, in percent of clip duration (only for 3GPP clip !).
            pSettings->xVSS.uiBeginCutPercent =
                (M4OSA_UInt32)pEnv->GetIntField(object, fieldIds.beginCutPercent);

            // Set the end cut time, in percent of clip duration (only for 3GPP clip !).
            pSettings->xVSS.uiEndCutPercent =
                (M4OSA_UInt32)pEnv->GetIntField(object, fieldIds.endCutPercent);

            // Set the duration of the clip, if different from 0,
            // has priority on uiEndCutTime or uiEndCutPercent.
            pSettings->xVSS.uiDuration = 0;

            // Set whether or not the pan and zoom mode is enabled.
            pSettings->xVSS.isPanZoom =
                (M4OSA_Bool)pEnv->GetBooleanField(object, fieldIds.panZoomEnabled);

            // Set the pan and zoom start zoom percentage.
            pSettings->xVSS.PanZoomXa        =
                (M4OSA_UInt16)pEnv->GetIntField(object, fieldIds.panZoomPercentStart);

            // Set the pan and zoom start x.
            pSettings->xVSS.PanZoomTopleftXa =
                (M4OSA_UInt16)pEnv->GetIntField(object, fieldIds.panZoomTopLeftXStart);

            // Set the pan and zoom start y.
            pSettings->xVSS.PanZoomTopleftYa =
                (M4OSA_UInt16)pEnv->GetIntField(object, fieldIds.panZoomTopLeftYStart);

            // Set the pan and zoom end zoom percentage.
            pSettings->xVSS.PanZoomXb        =
                (M4OSA_UInt16)pEnv->GetIntField(object, fieldIds.panZoomPercentEnd);

            // Set the pan and zoom end x.
            pSettings->xVSS.PanZoomTopleftXb =
                (M4OSA_UInt16)pEnv->GetIntField(object, fieldIds.panZoomTopLeftXEnd);

            // Set the pan and zoom end y.
            pSettings->xVSS.PanZoomTopleftYb =
                (M4OSA_UInt16)pEnv->GetIntField(object, fieldIds.panZoomTopLeftYEnd);

            // Set the media rendering mode, only used with JPEG to crop, resize,
            // or render black borders.
            pSettings->xVSS.MediaRendering =
                (M4xVSS_MediaRendering)videoEditJava_getMediaRenderingJavaToC(
                    &converted, pEnv->GetIntField(object,fieldIds.mediaRendering));

            // Check if the media rendering is valid.
            videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv, !converted,
                "clip.mediaRendering is invalid");

             // Capture the rgb file width and height
            pSettings->ClipProperties.uiStillPicWidth =
                (M4OSA_UInt16)pEnv->GetIntField(object, fieldIds.rgbFileWidth);
            pSettings->ClipProperties.uiStillPicHeight  =
                (M4OSA_UInt16)pEnv->GetIntField(object, fieldIds.rgbFileHeight);

            VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", \
                "getClipSettings-- rgbFileWidth %d ",
                pSettings->ClipProperties.uiStillPicWidth);
            VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR", \
                "getClipSettings-- rgbFileHeight %d ",
                pSettings->ClipProperties.uiStillPicHeight);

            // Set the video rotation degree
            pSettings->ClipProperties.videoRotationDegrees =
                (M4OSA_UInt32)pEnv->GetIntField(object, fieldIds.rotationDegree);
        }

        // Check if settings could be set.
        if (*pResult)
        {
            // Return the settings.
            (*ppSettings) = pSettings;
        }
        else
        {
            // Free the settings.
            videoEditClasses_freeClipSettings(&pSettings);
        }
    }
}

void
videoEditClasses_createClipSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                M4VSS3GPP_ClipSettings*             pSettings,
                jobject*                            pObject)
{
    VideoEditJava_ClipSettingsFieldIds fieldIds;
    jclass                     clazz    = NULL;
    jobject                    object   = NULL;
    memset(&fieldIds, 0, sizeof(VideoEditJava_ClipSettingsFieldIds));

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "videoEditClasses_createClipSettings()");

        // Retrieve the class.
        videoEditJava_getClipSettingsClass(pResult, pEnv, &clazz);

        // Retrieve the field ids.
        videoEditJava_getClipSettingsFieldIds(pResult, pEnv, &fieldIds);
    }

    // Only create an object if the class and fields could be located.
    if (*pResult)
    {
        // Allocate a new object.
        object = pEnv->AllocObject(clazz);
        if (NULL != object)
        {
            // Set the clipPath field.
            pEnv->SetObjectField(object, fieldIds.clipPath, NULL);

            // Set the fileType field.
            pEnv->SetIntField(object, fieldIds.fileType, videoEditJava_getClipTypeCToJava(
                                                                    pSettings->FileType));

            // Set the beginCutTime field.
            pEnv->SetIntField(object, fieldIds.beginCutTime, pSettings->uiBeginCutTime);

            // Set the endCutTime field.
            pEnv->SetIntField(object, fieldIds.endCutTime, pSettings->uiEndCutTime);

            // Set the beginCutPercent field.
            pEnv->SetIntField(object, fieldIds.beginCutPercent, pSettings->xVSS.uiBeginCutPercent);

            // Set the endCutPercent field.
            pEnv->SetIntField(object, fieldIds.endCutPercent, pSettings->xVSS.uiEndCutPercent);

            // Set the panZoomEnabled field.
            pEnv->SetBooleanField(object, fieldIds.panZoomEnabled, pSettings->xVSS.isPanZoom);

            // Set the panZoomPercentStart field.
            pEnv->SetIntField(object, fieldIds.panZoomPercentStart,
                (1000 - pSettings->xVSS.PanZoomXa));

            // Set the panZoomTopLeftXStart field.
            pEnv->SetIntField(object, fieldIds.panZoomTopLeftXStart,
                pSettings->xVSS.PanZoomTopleftXa);

            // Set the panZoomTopLeftYStart field.
            pEnv->SetIntField(object, fieldIds.panZoomTopLeftYStart,
                pSettings->xVSS.PanZoomTopleftYa);

            // Set the panZoomPercentEnd field.
            pEnv->SetIntField(object, fieldIds.panZoomPercentEnd,
                (1000 - pSettings->xVSS.PanZoomXb));

            // Set the panZoomTopLeftXEnd field.
            pEnv->SetIntField(object, fieldIds.panZoomTopLeftXEnd,
                pSettings->xVSS.PanZoomTopleftXb);

            // Set the panZoomTopLeftYEnd field.
            pEnv->SetIntField(object, fieldIds.panZoomTopLeftYEnd,
                pSettings->xVSS.PanZoomTopleftYb);

            // Set the mediaRendering field.
            pEnv->SetIntField(object, fieldIds.mediaRendering,
                videoEditJava_getMediaRenderingCToJava(pSettings->xVSS.MediaRendering));

            // Set the rgb file width and height
            pEnv->SetIntField(object, fieldIds.rgbFileWidth,
                pSettings->ClipProperties.uiStillPicWidth );

            pEnv->SetIntField(object, fieldIds.rgbFileHeight,
                pSettings->ClipProperties.uiStillPicHeight );

            VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                "rgbFileWeight %d rgbFileHeight %d ",
                pSettings->ClipProperties.uiStillPicWidth ,
                pSettings->ClipProperties.uiStillPicHeight);

            // Set the video rotation
            pEnv->SetIntField(object, fieldIds.rotationDegree,
                pSettings->ClipProperties.videoRotationDegrees);

            // Return the object.
            (*pObject) = object;
        }
    }
}
void
videoEditPropClass_createProperties(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                VideoEditPropClass_Properties*      pProperties,
                jobject*                            pObject)
{
    VideoEditJava_PropertiesFieldIds fieldIds;
    jclass                   clazz    = NULL;
    jobject                  object   = NULL;
    memset(&fieldIds, 0, sizeof(VideoEditJava_PropertiesFieldIds));
    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
                "videoEditPropClass_createProperties()");

        // Retrieve the class.
        videoEditJava_getPropertiesClass(pResult, pEnv, &clazz);

        // Retrieve the field ids.
        videoEditJava_getPropertiesFieldIds(pResult, pEnv, &fieldIds);
    }

    // Only create an object if the class and fields could be located.
    if (*pResult)
    {
        // Allocate a new object.
        object = pEnv->AllocObject(clazz);
        if (NULL != object)
        {
            // Set the duration field.
            pEnv->SetIntField(object, fieldIds.duration, pProperties->uiClipDuration);

            // Set the fileType field.
            pEnv->SetIntField(object, fieldIds.fileType,
                videoEditJava_getFileTypeCToJava(pProperties->FileType));

            // Set the videoFormat field.
            pEnv->SetIntField(object, fieldIds.videoFormat,
                videoEditJava_getVideoFormatCToJava(pProperties->VideoStreamType));

            // Set the videoDuration field.
            pEnv->SetIntField(object, fieldIds.videoDuration, pProperties->uiClipVideoDuration);

            // Set the videoBitrate field.
            pEnv->SetIntField(object, fieldIds.videoBitrate, pProperties->uiVideoBitrate);

            // Set the width field.
            pEnv->SetIntField(object, fieldIds.width, pProperties->uiVideoWidth);

            // Set the height field.
            pEnv->SetIntField(object, fieldIds.height, pProperties->uiVideoHeight);

            // Set the averageFrameRate field.
            pEnv->SetFloatField(object, fieldIds.averageFrameRate, pProperties->fAverageFrameRate);

            // Set the profile field.
            pEnv->SetIntField(object, fieldIds.profile,
                pProperties->uiVideoProfile);

            // Set the level field.
            pEnv->SetIntField(object, fieldIds.level,
                pProperties->uiVideoLevel);

            // Set whether profile supported
            pEnv->SetBooleanField(object, fieldIds.profileSupported,
                pProperties->bProfileSupported);

            // Set whether level supported
            pEnv->SetBooleanField(object, fieldIds.levelSupported,
                pProperties->bLevelSupported);

            // Set the audioFormat field.
            pEnv->SetIntField(object, fieldIds.audioFormat,
                videoEditJava_getAudioFormatCToJava(pProperties->AudioStreamType));

            // Set the audioDuration field.
            pEnv->SetIntField(object, fieldIds.audioDuration, pProperties->uiClipAudioDuration);

            // Set the audioBitrate field.
            pEnv->SetIntField(object, fieldIds.audioBitrate, pProperties->uiAudioBitrate);

            // Set the audioChannels field.
            pEnv->SetIntField(object, fieldIds.audioChannels, pProperties->uiNbChannels);

            // Set the audioSamplingFrequency field.
            pEnv->SetIntField(object, fieldIds.audioSamplingFrequency,
                pProperties->uiSamplingFrequency);

            // Set the video rotation field.
            pEnv->SetIntField(object, fieldIds.videoRotation, pProperties->uiRotation);

            // Return the object.
            (*pObject) = object;
        }
    }
}

void
videoEditClasses_freeClipSettings(
                M4VSS3GPP_ClipSettings**            ppSettings)
{
    // Check if memory was allocated for the ClipSettings.
    if (M4OSA_NULL != (*ppSettings))
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "videoEditClasses_freeClipSettings()");

        // Free the input file path.
        videoEditOsal_free((*ppSettings)->pFile);
        (*ppSettings)->pFile = M4OSA_NULL;
        (*ppSettings)->filePathSize = 0;

        // Free the clip settings.
        M4xVSS_FreeClipSettings((*ppSettings));

        // Free the settings structure.
        videoEditOsal_free((*ppSettings));
        (*ppSettings) = M4OSA_NULL;
    }
}

#ifdef VIDEOEDIT_LOGGING_ENABLED
void
videoEditClasses_logClipSettings(
                M4VSS3GPP_ClipSettings*             pSettings,
                int                                 indentation)
{
    // Check if memory was allocated for the ClipSettings.
    if (M4OSA_NULL != pSettings)
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pFile:           %s", indentation, ' ',
            (M4OSA_NULL != pSettings->pFile) ? (char*)pSettings->pFile : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c FileType:        %s", indentation, ' ',
            videoEditJava_getClipTypeString(pSettings->FileType));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c filePathSize:    %u", indentation, ' ',
            (unsigned int)pSettings->filePathSize);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c ClipProperties:",  indentation, ' ');
        videoEditClasses_logClipProperties(&pSettings->ClipProperties,
            indentation + VIDEOEDIT_LOG_INDENTATION);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiBeginCutTime:    %u ms", indentation, ' ',
            (unsigned int)pSettings->uiBeginCutTime);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiEndCutTime:      %u ms", indentation, ' ',
            (unsigned int)pSettings->uiEndCutTime);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiBeginCutPercent: %u %%", indentation, ' ',
            (unsigned int)pSettings->xVSS.uiBeginCutPercent);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiEndCutPercent:   %u %%", indentation, ' ',
            (unsigned int)pSettings->xVSS.uiEndCutPercent);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiDuration:        %u ms", indentation, ' ',
            (unsigned int)pSettings->xVSS.uiDuration);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c isPanZoom:         %s",    indentation, ' ',
            pSettings->xVSS.isPanZoom ? "true" : "false");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c PanZoomXa:         %d ms", indentation, ' ',
            pSettings->xVSS.PanZoomXa);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c PanZoomTopleftXa:  %d ms", indentation, ' ',
            pSettings->xVSS.PanZoomTopleftXa);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c PanZoomTopleftYa:  %d ms", indentation, ' ',
            pSettings->xVSS.PanZoomTopleftYa);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c PanZoomXb:         %d ms", indentation, ' ',
            pSettings->xVSS.PanZoomXb);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c PanZoomTopleftXb:  %d ms", indentation, ' ',
            pSettings->xVSS.PanZoomTopleftXb);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c PanZoomTopleftYb:  %d ms", indentation, ' ',
            pSettings->xVSS.PanZoomTopleftYb);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c MediaRendering:    %s",    indentation, ' ',
            videoEditJava_getMediaRenderingString(pSettings->xVSS.MediaRendering));
    }
    else
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c <null>", indentation, ' ');
    }
}
#endif


void
videoEditClasses_getEditSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4VSS3GPP_EditSettings**            ppSettings,
                bool                                flag)
{
    VideoEditJava_EditSettingsFieldIds fieldIds;
    jobjectArray               clipSettingsArray           = NULL;
    jsize                      clipSettingsArraySize       = 0;
    jobject                    clipSettings                = NULL;
    jobjectArray               transitionSettingsArray     = NULL;
    jsize                      transitionSettingsArraySize = 0;
    jobject                    transitionSettings          = NULL;
    jobjectArray               effectSettingsArray         = NULL;
    jsize                      effectSettingsArraySize     = 0;
    jobject                    effectSettings              = NULL;
    jobject                    backgroundMusicSettings     = NULL;
    int                        audioChannels               = 0;
    M4VSS3GPP_EditSettings*    pSettings                   = M4OSA_NULL;
    bool                       converted                   = true;
    memset(&fieldIds, 0, sizeof(VideoEditJava_EditSettingsFieldIds));
    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                "videoEditClasses_getEditSettings()");

        // Retrieve the field ids.
        videoEditJava_getEditSettingsFieldIds(pResult, pEnv, &fieldIds);
    }

    // Only retrieve the EditSettings if the previous action succeeded.
    if (*pResult)
    {
        // Check if the object is valid.
        if (NULL != object)
        {
            // Retrieve the clipSettingsArray.
            videoEditJava_getArray(pResult, pEnv, object,
                           fieldIds.clipSettingsArray,
                           &clipSettingsArray,
                           &clipSettingsArraySize);

            // Retrieve the transitionSettingsArray.
            videoEditJava_getArray(pResult, pEnv, object,
                           fieldIds.transitionSettingsArray,
                           &transitionSettingsArray,
                           &transitionSettingsArraySize);

            // Retrieve the effectSettingsArray.
            videoEditJava_getArray(pResult, pEnv, object,
                           fieldIds.effectSettingsArray,
                           &effectSettingsArray,
                           &effectSettingsArraySize);

            // Retrieve the backgroundMusicSettings.
            videoEditJava_getObject(pResult, pEnv, object, fieldIds.backgroundMusicSettings,
                    &backgroundMusicSettings);

            // Check if the arrays and background music settings object could be retrieved.
            if (*pResult)
            {
                // Retrieve the number of channels.
                audioChannels = pEnv->GetIntField(object, fieldIds.audioChannels);
            }
        }
    }

    // Only validate the EditSettings if the fields could be located.
    if (*pResult)
    {
        // Check if there is at least one clip.
        //videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
         //                                          (clipSettingsArraySize < 1),
         //                                          "there should be at least one clip");
        if(clipSettingsArraySize < 1) {
            return;
        }
        if(flag)
        {
            // Check if there are clips.
            if ((clipSettingsArraySize != 0) || (transitionSettingsArraySize != 0))
            {
                // The number of transitions must be equal to the number of clips - 1.
                videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                         (clipSettingsArraySize != (transitionSettingsArraySize + 1)),
                         "the number of transitions should be equal to the number of clips - 1");
            }
        }
    }

    // Only retrieve the EditSettings if the fields could be located.
    if (*pResult)
    {
        // Check if the object is valid.
        if (NULL != object)
        {
            // Allocate memory for the EditSettings.
            pSettings = (M4VSS3GPP_EditSettings*)videoEditOsal_alloc(pResult, pEnv,
                    sizeof(M4VSS3GPP_EditSettings), "EditSettings");

            // Check if memory could be allocated for the EditSettings.
            if (*pResult)
            {
                // Set the number of clips that will be edited.
                pSettings->uiClipNumber = clipSettingsArraySize;

                // Check if the clip settings array contains items.
                if (clipSettingsArraySize > 0)
                {
                    // Allocate memory for the clip settings array.
                    pSettings->pClipList = (M4VSS3GPP_ClipSettings **)videoEditOsal_alloc(pResult,
                                pEnv,
                                clipSettingsArraySize * sizeof(M4VSS3GPP_ClipSettings *),
                                "ClipSettingsArray");
                    if (*pResult)
                    {
                        // Loop over all clip settings objects.
                        for (int i = 0; ((*pResult) && (i < clipSettingsArraySize)); i++)
                        {
                            // Get the clip settings object.
                            clipSettings = pEnv->GetObjectArrayElement(clipSettingsArray, i);

                            // Get the clip settings.
                            videoEditClasses_getClipSettings(pResult, pEnv, clipSettings,
                                &pSettings->pClipList[i]);

                            // Free the local references to avoid memory leaks
                            pEnv->DeleteLocalRef(clipSettings);
                        }
                    }
                }

                // Check if the transition settings array contains items.
                if (transitionSettingsArraySize > 0)
                {
                    // Allocate memory for the transition settings array.
                    pSettings->pTransitionList =
                            (M4VSS3GPP_TransitionSettings **)videoEditOsal_alloc(pResult,
                                pEnv, transitionSettingsArraySize * sizeof(M4VSS3GPP_TransitionSettings *),
                                "TransitionSettingsArray");
                    if (*pResult)
                    {
                        // Loop over all transition settings objects.
                        for (int i = 0; ((*pResult) && (i < transitionSettingsArraySize)); i++)
                        {
                            // Get the transition settings object.
                            transitionSettings =
                                    pEnv->GetObjectArrayElement(transitionSettingsArray, i);

                            // Get the transition settings.
                            videoEditClasses_getTransitionSettings(pResult, pEnv,
                                    transitionSettings, &pSettings->pTransitionList[i]);

                            // Free the local references to avoid memory leaks
                            pEnv->DeleteLocalRef(transitionSettings);
                        }
                    }
                }

                // Check if the effect settings array contains items.
                if (effectSettingsArraySize > 0)
                {
                    // Allocate memory for the effect settings array.
                    pSettings->Effects = (M4VSS3GPP_EffectSettings*)videoEditOsal_alloc(pResult,
                                pEnv,
                                effectSettingsArraySize * sizeof(M4VSS3GPP_EffectSettings),
                                "EffectSettingsArray");
                    if (*pResult)
                    {
                        // Loop over all effect settings objects.
                        for (int i = 0; ((*pResult) && (i < effectSettingsArraySize)); i++)
                        {
                            // Get the effect settings object.
                            effectSettings = pEnv->GetObjectArrayElement(effectSettingsArray, i);

                            // Get the effect settings.
                            videoEditClasses_getEffectSettings(pResult, pEnv, effectSettings,
                                    &pSettings->Effects[i]);

                            // Free the local references to avoid memory leaks
                            pEnv->DeleteLocalRef(effectSettings);
                        }
                    }
                }

                // Check if the clips, transitions and effects could be set.
                if (*pResult)
                {
                    // Set the number of effects in the clip.
                    pSettings->nbEffects = (M4OSA_UInt8)effectSettingsArraySize;

                    // Set the frame rate of the output video.
                    pSettings->videoFrameRate =
                        (M4VIDEOEDITING_VideoFramerate)videoEditJava_getVideoFrameRateJavaToC(
                             &converted, pEnv->GetIntField(object, fieldIds.videoFrameRate));

                    // Check if the frame rate is valid.
                    videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                        !converted, "editSettings.videoFrameRate is invalid");
                }

                // Check if the frame rate could be set.
                if (*pResult)
                {
                    // Set the path of the output file.
                    pSettings->pOutputFile = (M4OSA_Char*)videoEditJava_getString(pResult, pEnv,
                        object, fieldIds.outputFile, &pSettings->uiOutputPathSize);
                }

                // Check if path of the output file could be set.
                if (*pResult)
                {
                    // Set the path of the temporary file produced when using
                    // the constant memory 3gp writer.
                    pSettings->pTemporaryFile = M4OSA_NULL;

                    // Set the output video size.
                    pSettings->xVSS.outputVideoSize =
                        (M4VIDEOEDITING_VideoFrameSize)videoEditJava_getVideoFrameSizeJavaToC(
                                &converted, pEnv->GetIntField(object, fieldIds.videoFrameSize));

                    // Check if the output video size is valid.
                    videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                        !converted, "editSettings.videoFrameSize is invalid");
                }

                // Check if the output video size could be set.
                if (*pResult)
                {
                    // Set the output video format.
                    pSettings->xVSS.outputVideoFormat =
                        (M4VIDEOEDITING_VideoFormat)videoEditJava_getVideoFormatJavaToC(
                               &converted, pEnv->GetIntField(object, fieldIds.videoFormat));

                    // Check if the output video format is valid.
                    videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                        !converted, "editSettings.videoFormat is invalid");
                }

                // Check if the output video format could be set.
                if (*pResult)
                {
                    // Set the output audio format.
                    pSettings->xVSS.outputAudioFormat =
                            (M4VIDEOEDITING_AudioFormat)videoEditJava_getAudioFormatJavaToC(
                                  &converted, pEnv->GetIntField(object, fieldIds.audioFormat));

                    // Check if the output audio format is valid.
                    videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                            !converted, "editSettings.audioFormat is invalid");
                }

                // Check if the output audio format could be set.
                if (*pResult)
                {
                    // Set the output audio sampling frequency when not replacing the audio,
                    // or replacing it with MP3 audio.
                    pSettings->xVSS.outputAudioSamplFreq =
                        (M4VIDEOEDITING_AudioSamplingFrequency)\
                            videoEditJava_getAudioSamplingFrequencyJavaToC(
                                &converted, pEnv->GetIntField(object, fieldIds.audioSamplingFreq));

                    // Check if the output audio sampling frequency is valid.
                    videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                            !converted, "editSettings.audioSamplingFreq is invalid");
                }

                // Check if the output audio sampling frequency could be set.
                if (*pResult)
                {
                    // Check if the number of audio channels is valid.
                    videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                        ((0 != audioChannels ) ||
                        ((M4VIDEOEDITING_kNoneAudio != pSettings->xVSS.outputAudioFormat) &&
                        (M4VIDEOEDITING_kNullAudio != pSettings->xVSS.outputAudioFormat) ) ) &&
                        (1 != audioChannels ) &&
                        (2 != audioChannels ),
                        "editSettings.audioChannels must be set to 0, 1 or 2");
                }

                // Check if the number of audio channels is valid.
                if (*pResult)
                {
                    // Set the maximum output file size (MMS usecase).
                    pSettings->xVSS.outputFileSize = (M4OSA_UInt32)pEnv->GetIntField(object,
                            fieldIds.maxFileSize);

                    // Whether or not the audio is mono, only valid for AAC.
                    pSettings->xVSS.bAudioMono = (M4OSA_Bool)(1 == audioChannels);

                    // Set the output video bitrate.
                    pSettings->xVSS.outputVideoBitrate = (M4OSA_UInt32)pEnv->GetIntField(object,
                            fieldIds.videoBitrate);

                    // Set the output video profile.
                    pSettings->xVSS.outputVideoProfile = (M4OSA_UInt32)pEnv->GetIntField(object,
                            fieldIds.videoProfile);

                    // Set the output video level.
                    pSettings->xVSS.outputVideoLevel = (M4OSA_UInt32)pEnv->GetIntField(object,
                            fieldIds.videoLevel);

                    // Set the output audio bitrate.
                    pSettings->xVSS.outputAudioBitrate = (M4OSA_UInt32)pEnv->GetIntField(object,
                            fieldIds.audioBitrate);

                    // Set the background music settings.
                    videoEditClasses_getBackgroundMusicSettings(pResult, pEnv,
                            backgroundMusicSettings, &pSettings->xVSS.pBGMtrack);

                    // Set the text rendering function (will be set elsewhere).
                    pSettings->xVSS.pTextRenderingFct = M4OSA_NULL;
                    pSettings->PTVolLevel =
                            (M4OSA_Float)pEnv->GetIntField(object, fieldIds.primaryTrackVolume);
                }
            }

            // Check if settings could be set.
            if (*pResult)
            {
                // Return the settings.
                (*ppSettings) = pSettings;
            }
            else
            {
                // Free the settings.
                videoEditClasses_freeEditSettings(&pSettings);
            }
        }
    }
}

void
videoEditClasses_freeEditSettings(
                M4VSS3GPP_EditSettings**            ppSettings)
{
    // Check if memory was allocated for the EditSettings.
    if (M4OSA_NULL != (*ppSettings))
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                "videoEditClasses_freeEditSettings()");

        // Free the background music settings.
        videoEditClasses_freeBackgroundMusicSettings(&(*ppSettings)->xVSS.pBGMtrack);

        // Free the path of the output file.
        videoEditOsal_free((*ppSettings)->pOutputFile);
        (*ppSettings)->pOutputFile = M4OSA_NULL;
        (*ppSettings)->uiOutputPathSize = 0;

        // Check if the EffectSettings should be freed.
        if (M4OSA_NULL != (*ppSettings)->Effects)
        {
            // Loop over all effect settings.
            for (int i = 0; i < (*ppSettings)->nbEffects; i++)
            {
                // Free the effect settings.
                videoEditClasses_freeEffectSettings(&(*ppSettings)->Effects[i]);
            }

            // Free the memory for the effect settings array.
            videoEditOsal_free((*ppSettings)->Effects);
            (*ppSettings)->Effects = M4OSA_NULL;
        }

        // Reset the number of effects in the clip.
        (*ppSettings)->nbEffects = 0;

        // Check if there are clips.
        if (0 < (*ppSettings)->uiClipNumber)
        {
            // Check if the TransitionSettings should be freed.
            if (M4OSA_NULL != (*ppSettings)->pTransitionList)
            {
                // Loop over all transition settings.
                for (int i = 0; i < ((*ppSettings)->uiClipNumber - 1); i++)
                {
                    // Free the transition settings.
                    videoEditClasses_freeTransitionSettings(&(*ppSettings)->pTransitionList[i]);
                }

                // Free the memory for the transition settings array.
                videoEditOsal_free((*ppSettings)->pTransitionList);
                (*ppSettings)->pTransitionList = M4OSA_NULL;
            }

            // Check if the ClipSettings should be freed.
            if (M4OSA_NULL != (*ppSettings)->pClipList)
            {
                // Loop over all clip settings.
                for (int i = 0; i < (*ppSettings)->uiClipNumber; i++)
                {
                    // Free the clip settings.
                    videoEditClasses_freeClipSettings(&(*ppSettings)->pClipList[i]);
                }

                // Free the memory for the clip settings array.
                videoEditOsal_free((*ppSettings)->pClipList);
                (*ppSettings)->pClipList = M4OSA_NULL;
            }
        }

        // Reset the number of clips.
        (*ppSettings)->uiClipNumber = 0;

        // Free the settings structure.
        videoEditOsal_free((*ppSettings));
        (*ppSettings) = M4OSA_NULL;
    }
}

#ifdef VIDEOEDIT_LOGGING_ENABLED
void
videoEditClasses_logEditSettings(
                M4VSS3GPP_EditSettings*             pSettings,
                int                                 indentation)
{
    // Check if memory was allocated for the EditSettings.
    if (M4OSA_NULL != pSettings)
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiClipNumber:         %d", indentation, ' ',
            pSettings->uiClipNumber);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiMasterClip:         %d", indentation, ' ',
            pSettings->uiMasterClip);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pClipList:            %s", indentation, ' ',
            (M4OSA_NULL != pSettings->pClipList) ? " " : "<null>");
        if (M4OSA_NULL != pSettings->pClipList)
        {
            indentation += VIDEOEDIT_LOG_INDENTATION;
            for (int i = 0; i < pSettings->uiClipNumber; i++)
            {
                VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                    "%*c pClipList[%d]:", indentation, ' ',
                    i);
                videoEditClasses_logClipSettings(pSettings->pClipList[i],
                    indentation + VIDEOEDIT_LOG_INDENTATION);
            }
            indentation -= VIDEOEDIT_LOG_INDENTATION;
        }
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pTransitionList:      %s", indentation, ' ',
            (M4OSA_NULL != pSettings->pTransitionList) ? " " : "<null>");
        if (M4OSA_NULL != pSettings->pTransitionList)
        {
            indentation += VIDEOEDIT_LOG_INDENTATION;
            for (int i = 0; i < (pSettings->uiClipNumber - 1); i++)
            {
                VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                    "%*c pTransitionList[%d]:", indentation, ' ', i);
                videoEditClasses_logTransitionSettings(pSettings->pTransitionList[i],
                    indentation + VIDEOEDIT_LOG_INDENTATION);
            }
            indentation -= VIDEOEDIT_LOG_INDENTATION;
        }
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c Effects:              %s", indentation, ' ',
            (M4OSA_NULL != pSettings->Effects)   ? " " : "<null>");
        if (M4OSA_NULL != pSettings->Effects)
        {
            indentation += VIDEOEDIT_LOG_INDENTATION;
            for (int i = 0; i < pSettings->nbEffects; i++)
            {
                VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                    "%*c Effects[%d]:", indentation, ' ',  i);
                videoEditClasses_logEffectSettings(&pSettings->Effects[i],
                    indentation + VIDEOEDIT_LOG_INDENTATION);
            }
            indentation -= VIDEOEDIT_LOG_INDENTATION;
        }
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c nbEffects:            %d", indentation, ' ',
            pSettings->nbEffects);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c videoFrameRate:       %s", indentation, ' ',
            videoEditJava_getVideoFrameRateString(pSettings->videoFrameRate));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pOutputFile:          %s", indentation, ' ',
            (M4OSA_NULL != pSettings->pOutputFile) ? (char*)pSettings->pOutputFile : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiOutputPathSize:     %u", indentation, ' ',
            (unsigned int)pSettings->uiOutputPathSize);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pTemporaryFile:       %s", indentation, ' ',
            (M4OSA_NULL != pSettings->pTemporaryFile) ?\
             (char*)pSettings->pTemporaryFile : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c outputVideoSize:      %s", indentation, ' ',
            videoEditJava_getVideoFrameSizeString(pSettings->xVSS.outputVideoSize));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c outputVideoFormat:    %s", indentation, ' ',
            videoEditJava_getVideoFormatString(pSettings->xVSS.outputVideoFormat));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c outputVideoProfile:    %u", indentation, ' ',
            videoEditJava_getVideoFormatString(pSettings->xVSS.outputVideoProfile));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c outputVideoLevel:    %u", indentation, ' ',
            videoEditJava_getVideoFormatString(pSettings->xVSS.outputVideoLevel));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c outputAudioFormat:    %s", indentation, ' ',
            videoEditJava_getAudioFormatString(pSettings->xVSS.outputAudioFormat));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c outputAudioSamplFreq: %s", indentation, ' ',
            videoEditJava_getAudioSamplingFrequencyString(pSettings->xVSS.outputAudioSamplFreq));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c outputFileSize:       %u", indentation, ' ',
            (unsigned int)pSettings->xVSS.outputFileSize);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c bAudioMono:           %s", indentation, ' ',
            pSettings->xVSS.bAudioMono ? "true" : "false");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c outputVideoBitrate:   %s", indentation, ' ',
            videoEditJava_getBitrateString(pSettings->xVSS.outputVideoBitrate));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c outputAudioBitrate:   %s", indentation, ' ',
            videoEditJava_getBitrateString(pSettings->xVSS.outputAudioBitrate));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pBGMtrack:",               indentation, ' ');
        videoEditClasses_logBackgroundMusicSettings(pSettings->xVSS.pBGMtrack,
            indentation + VIDEOEDIT_LOG_INDENTATION);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pTextRenderingFct:    %s", indentation, ' ',
            (M4OSA_NULL != pSettings->xVSS.pTextRenderingFct) ? "set" : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c PTVolLevel:       %u", indentation, ' ',
            (unsigned int)pSettings->PTVolLevel);
    }
    else
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c <null>", indentation, ' ');
    }
}
#endif


void
videoEditClasses_getEffectSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4VSS3GPP_EffectSettings*           pSettings)
{

    VideoEditJava_EffectSettingsFieldIds fieldIds;
    bool                         converted = true;
    memset(&fieldIds, 0, sizeof(VideoEditJava_EffectSettingsFieldIds));

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
         "videoEditClasses_getEffectSettings()");

        // Retrieve the field ids.
        videoEditJava_getEffectSettingsFieldIds(pResult, pEnv, &fieldIds);
    }

    // Only validate the EffectSettings if the fields could be located.
    if (*pResult)
    {
        // Check if the effect is set.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                                                    (NULL == object),
                                                    "effect is null");
    }

    // Only retrieve the EffectSettings if the fields could be located and validated.
    if (*pResult)
    {
        // Set the start time in milliseconds.
        pSettings->uiStartTime = (M4OSA_UInt32)pEnv->GetIntField(object, fieldIds.startTime);

        // Set the duration in milliseconds.
        pSettings->uiDuration = (M4OSA_UInt32)pEnv->GetIntField(object, fieldIds.duration);

        // Set the video effect type, None, FadeIn, FadeOut, etc.
        pSettings->VideoEffectType =
                (M4VSS3GPP_VideoEffectType)videoEditJava_getVideoEffectJavaToC(
                              &converted, pEnv->GetIntField(object, fieldIds.videoEffectType));

        // Check if the video effect type is valid.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                !converted, "effect.videoEffectType is invalid");
    }

    // Check if the video effect type could be set.
    if (*pResult)
    {
        // Set the external effect function.
        pSettings->ExtVideoEffectFct = M4OSA_NULL;

        // Set the context given to the external effect function.
        pSettings->pExtVideoEffectFctCtxt = M4OSA_NULL;

        // Set the audio effect type, None, FadeIn, FadeOut.
        pSettings->AudioEffectType =
                (M4VSS3GPP_AudioEffectType)videoEditJava_getAudioEffectJavaToC(
                        &converted, pEnv->GetIntField(object, fieldIds.audioEffectType));

        // Check if the audio effect type is valid.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                !converted, "effect.audioEffectType is invalid");
    }

    // Check if the audio effect type could be set.
    if (*pResult)
    {
        // Set the start in percentage of the cut clip duration.
        pSettings->xVSS.uiStartPercent = (M4OSA_UInt32)pEnv->GetIntField(object,
                fieldIds.startPercent);

        // Set the duration in percentage of the ((clip duration) - (effect starttime)).
        pSettings->xVSS.uiDurationPercent = (M4OSA_UInt32)pEnv->GetIntField(object,
                fieldIds.durationPercent);

        // Set the framing file path (GIF/PNG file).
        pSettings->xVSS.pFramingFilePath = (M4OSA_Char*)videoEditJava_getString(pResult, pEnv,
                object, fieldIds.framingFile, M4OSA_NULL);

        // Check if this is a framing effect.
        if (M4xVSS_kVideoEffectType_Framing == (M4xVSS_VideoEffectType)pSettings->VideoEffectType)
        {
            // Check if the framing file path is valid.
            videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                    (M4OSA_NULL == pSettings->xVSS.pFramingFilePath), "effect.framingFile is null");
        }
    }

    // Check if the framing file path could be retrieved.
    if (*pResult)
    {
        // Set the Framing RGB565 buffer.
        pSettings->xVSS.pFramingBuffer = M4OSA_NULL;

        // Set the top-left X coordinate in the output picture
        // where the added frame will be displayed.
        pSettings->xVSS.topleft_x = (M4OSA_UInt32)pEnv->GetIntField(object, fieldIds.topLeftX);

        // Set the top-left Y coordinate in the output picture
        // where the added frame will be displayed.
        pSettings->xVSS.topleft_y = (M4OSA_UInt32)pEnv->GetIntField(object, fieldIds.topLeftY);

        // Set whether or not the framing image is resized to output video size.
        pSettings->xVSS.bResize =
                (M4OSA_Bool)pEnv->GetBooleanField(object, fieldIds.framingResize);

        // Set the new size to which framing buffer needs to be resized to
        pSettings->xVSS.framingScaledSize =
                (M4VIDEOEDITING_VideoFrameSize)pEnv->GetIntField(object, fieldIds.framingScaledSize);

        // Set the text buffer.
        pSettings->xVSS.pTextBuffer = (M4OSA_Char*)videoEditJava_getString(pResult, pEnv, object,
                fieldIds.text, &pSettings->xVSS.textBufferSize);
    }

    // Check if the text buffer could be retrieved.
    if (*pResult)
    {
        // Set the data used by the font engine (size, color...).
        pSettings->xVSS.pRenderingData = (M4OSA_Char*)videoEditJava_getString(pResult, pEnv,
                object, fieldIds.textRenderingData, M4OSA_NULL);
    }

    // Check if the text rendering data could be retrieved.
    if (*pResult)
    {
        // Set the text plane width.
        pSettings->xVSS.uiTextBufferWidth = (M4OSA_UInt32)pEnv->GetIntField(object,
                fieldIds.textBufferWidth);

        // Set the text plane height.
        pSettings->xVSS.uiTextBufferHeight = (M4OSA_UInt32)pEnv->GetIntField(object,
                fieldIds.textBufferHeight);

        // Set the processing rate of the effect added when using the Fifties effect.
        pSettings->xVSS.uiFiftiesOutFrameRate = (M4OSA_UInt32)pEnv->GetIntField(object,
                fieldIds.fiftiesFrameRate);

        // Set the RGB16 input color of the effect added when using the rgb16 color effect.
        pSettings->xVSS.uiRgb16InputColor = (M4OSA_UInt16)pEnv->GetIntField(object,
                fieldIds.rgb16InputColor);

        // Set the start percentage of Alpha blending.
        pSettings->xVSS.uialphaBlendingStart = (M4OSA_UInt8)pEnv->GetIntField(object,
                fieldIds.alphaBlendingStartPercent);

        // Set the middle percentage of Alpha blending.
        pSettings->xVSS.uialphaBlendingMiddle = (M4OSA_UInt8)pEnv->GetIntField(object,
                fieldIds.alphaBlendingMiddlePercent);

        // Set the end percentage of Alpha blending.
        pSettings->xVSS.uialphaBlendingEnd = (M4OSA_UInt8)pEnv->GetIntField(object,
                fieldIds.alphaBlendingEndPercent);

        // Set the duration, in percentage of effect duration, of the FadeIn phase.
        pSettings->xVSS.uialphaBlendingFadeInTime = (M4OSA_UInt8)pEnv->GetIntField(object,
                fieldIds.alphaBlendingFadeInTimePercent);

        // Set the duration, in percentage of effect duration, of the FadeOut phase.
        pSettings->xVSS.uialphaBlendingFadeOutTime = (M4OSA_UInt8)pEnv->GetIntField(object,
                fieldIds.alphaBlendingFadeOutTimePercent);

        if (pSettings->xVSS.pFramingFilePath != M4OSA_NULL)
        {
            pSettings->xVSS.pFramingBuffer =
                (M4VIFI_ImagePlane *)M4OSA_32bitAlignedMalloc(sizeof(M4VIFI_ImagePlane),
                0x00,(M4OSA_Char *)"framing buffer");
        }

        if (pSettings->xVSS.pFramingBuffer != M4OSA_NULL)
        {
             // OverFrame height and width
            pSettings->xVSS.pFramingBuffer->u_width = pEnv->GetIntField(object,
             fieldIds.width);

            pSettings->xVSS.pFramingBuffer->u_height = pEnv->GetIntField(object,
             fieldIds.height);

            pSettings->xVSS.width = pSettings->xVSS.pFramingBuffer->u_width;
            pSettings->xVSS.height = pSettings->xVSS.pFramingBuffer->u_height;
            pSettings->xVSS.rgbType = M4VSS3GPP_kRGB565;

            VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                    "pFramingBuffer u_width %d ", pSettings->xVSS.pFramingBuffer->u_width);
            VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                    "pFramingBuffer u_height %d", pSettings->xVSS.pFramingBuffer->u_height);

        }

        // Check if settings could be set.
        if (!(*pResult))
        {
            // Free the settings.
            videoEditClasses_freeEffectSettings(pSettings);
        }
    }
}

void
videoEditClasses_freeEffectSettings(
                M4VSS3GPP_EffectSettings*           pSettings)
{
    // Check if memory was allocated for the EffectSettings.
    if (M4OSA_NULL != pSettings)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                "videoEditClasses_freeEffectSettings()");

        // Free the data used by the font engine (size, color...).
        videoEditOsal_free(pSettings->xVSS.pRenderingData);
        pSettings->xVSS.pRenderingData = M4OSA_NULL;

        // Free the text buffer.
        videoEditOsal_free(pSettings->xVSS.pTextBuffer);
        pSettings->xVSS.pTextBuffer = M4OSA_NULL;
        pSettings->xVSS.textBufferSize = 0;

        // Free the framing file path.
        videoEditOsal_free(pSettings->xVSS.pFramingFilePath);
        pSettings->xVSS.pFramingFilePath = M4OSA_NULL;
    }
}

#ifdef VIDEOEDIT_LOGGING_ENABLED
void
videoEditClasses_logEffectSettings(
                M4VSS3GPP_EffectSettings*           pSettings,
                int                                 indentation)
{
    // Check if memory was allocated for the EffectSettings.
    if (M4OSA_NULL != pSettings)
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiStartTime:                %u ms", indentation, ' ',
            (unsigned int)pSettings->uiStartTime);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiDuration:                 %u ms", indentation, ' ',
            (unsigned int)pSettings->uiDuration);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c VideoEffectType:            %s",    indentation, ' ',
            videoEditJava_getVideoEffectString(pSettings->VideoEffectType));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
             "%*c ExtVideoEffectFct:          %s",    indentation, ' ',
            (M4OSA_NULL != pSettings->ExtVideoEffectFct) ? "set" : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pExtVideoEffectFctCtxt:     %s",    indentation, ' ',
            (M4OSA_NULL != pSettings->pExtVideoEffectFctCtxt) ? "set" : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c AudioEffectType:            %s",    indentation, ' ',
            videoEditJava_getAudioEffectString(pSettings->AudioEffectType));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiStartPercent:             %u %%", indentation, ' ',
            (unsigned int)pSettings->xVSS.uiStartPercent);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiDurationPercent:          %u %%", indentation, ' ',
            (unsigned int)pSettings->xVSS.uiDurationPercent);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pFramingFilePath:           %s",    indentation, ' ',
            (M4OSA_NULL != pSettings->xVSS.pFramingFilePath) ?\
             (char*)pSettings->xVSS.pFramingFilePath : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pFramingBuffer:             %s",    indentation, ' ',
            (M4OSA_NULL != pSettings->xVSS.pFramingBuffer) ? "set" : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c topleft_x:                  %u",    indentation, ' ',
            (unsigned int)pSettings->xVSS.topleft_x);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c topleft_y:                  %u",    indentation, ' ',
            (unsigned int)pSettings->xVSS.topleft_y);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c bResize:                    %s",    indentation, ' ',
            pSettings->xVSS.bResize ? "true" : "false");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pTextBuffer:                %s",    indentation, ' ',
            (M4OSA_NULL != pSettings->xVSS.pTextBuffer) ?\
             (char*)pSettings->xVSS.pTextBuffer : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c textBufferSize:             %u",    indentation, ' ',
            (unsigned int)pSettings->xVSS.textBufferSize);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c pRenderingData:             %s",    indentation, ' ',
            (M4OSA_NULL != pSettings->xVSS.pRenderingData) ?\
             (char*)pSettings->xVSS.pRenderingData : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiTextBufferWidth:          %u",    indentation, ' ',
            (unsigned int)pSettings->xVSS.uiTextBufferWidth);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
             "%*c uiTextBufferHeight:         %u",    indentation, ' ',
            (unsigned int)pSettings->xVSS.uiTextBufferHeight);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiFiftiesOutFrameRate:      %u",    indentation, ' ',
            (unsigned int)pSettings->xVSS.uiFiftiesOutFrameRate);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uiRgb16InputColor:          %d",    indentation, ' ',
            pSettings->xVSS.uiRgb16InputColor);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uialphaBlendingStart:       %d %%", indentation, ' ',
            pSettings->xVSS.uialphaBlendingStart);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uialphaBlendingMiddle:      %d %%", indentation, ' ',
            pSettings->xVSS.uialphaBlendingMiddle);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uialphaBlendingEnd:         %d %%", indentation, ' ',
            pSettings->xVSS.uialphaBlendingEnd);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uialphaBlendingFadeInTime:  %d %%", indentation, ' ',
            pSettings->xVSS.uialphaBlendingFadeInTime);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c uialphaBlendingFadeOutTime: %d %%", indentation, ' ',
            pSettings->xVSS.uialphaBlendingFadeOutTime);
    }
    else
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c <null>", indentation, ' ');
    }
}
#endif


void
videoEditClasses_getSlideTransitionSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4xVSS_SlideTransitionSettings**    ppSettings)
{
    VideoEditJava_SlideTransitionSettingsFieldIds fieldIds  = {NULL};
    M4xVSS_SlideTransitionSettings*       pSettings = M4OSA_NULL;
    bool                                  converted = true;

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                "videoEditClasses_getSlideTransitionSettings()");

        // Retrieve the field ids.
        videoEditJava_getSlideTransitionSettingsFieldIds(pResult, pEnv, &fieldIds);
    }


    // Only validate the SlideTransitionSettings if the fields could be located.
    if (*pResult)
    {
        // Check if the clip is set.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                                                    (NULL == object),
                                                    "slideSettings is null");
    }

    // Only retrieve the SlideTransitionSettings if the fields could be located and validated.
    if (*pResult)
    {
        // Allocate memory for the SlideTransitionSettings.
        pSettings = (M4xVSS_SlideTransitionSettings*)videoEditOsal_alloc(pResult, pEnv,
                sizeof(M4xVSS_SlideTransitionSettings), "SlideTransitionSettings");

        // Check if memory could be allocated for the SlideTransitionSettings.
        if (*pResult)
        {
            // Set the direction of the slide.
            pSettings->direction =
                    (M4xVSS_SlideTransition_Direction)videoEditJava_getSlideDirectionJavaToC(
                            &converted, pEnv->GetIntField(object, fieldIds.direction));

            // Check if the direction is valid.
            videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                    !converted, "slideSettings.direction is invalid");
        }

        // Check if settings could be set.
        if (*pResult)
        {
            // Return the settings.
            (*ppSettings) = pSettings;
        }
        else
        {
            // Free the settings.
            videoEditClasses_freeSlideTransitionSettings(&pSettings);
        }
    }
}

void
videoEditClasses_freeSlideTransitionSettings(
                M4xVSS_SlideTransitionSettings**    ppSettings)
{
    // Check if memory was allocated for the SlideTransitionSettings.
    if (M4OSA_NULL != (*ppSettings))
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                "videoEditClasses_freeSlideTransitionSettings()");

        // Free the settings structure.
        videoEditOsal_free((*ppSettings));
        (*ppSettings) = M4OSA_NULL;
    }
}

#ifdef VIDEOEDIT_LOGGING_ENABLED
void
videoEditClasses_logSlideTransitionSettings(
                M4xVSS_SlideTransitionSettings*     pSettings,
                int                                 indentation)
{
    // Check if memory was allocated for the SlideTransitionSettings.
    if (M4OSA_NULL != pSettings)
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c direction: %s", indentation, ' ',
            videoEditJava_getSlideDirectionString(pSettings->direction));
    }
    else
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
            "%*c <null>", indentation, ' ');
    }
}
#endif


void
videoEditClasses_getTransitionSettings(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                M4VSS3GPP_TransitionSettings**      ppSettings)
{

    VideoEditJava_TransitionSettingsFieldIds fieldIds;
    jobject                          alphaSettings = NULL;
    jobject                          slideSettings = NULL;
    M4VSS3GPP_TransitionSettings*    pSettings     = M4OSA_NULL;
    bool                             converted     = true;
    memset(&fieldIds, 0, sizeof(VideoEditJava_TransitionSettingsFieldIds));

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
               "videoEditClasses_getTransitionSettings()");

        // Retrieve the field ids.
        videoEditJava_getTransitionSettingsFieldIds(pResult, pEnv, &fieldIds);
    }

    // Only validate the TransitionSettings if the fields could be located.
    if (*pResult)
    {
        // Check if the transition is set.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                                                    (NULL == object),
                                                    "transition is null");
    }

    // Check if the field ids could be located and validated.
    if (*pResult)
    {
        // Retrieve the alphaSettings.
        videoEditJava_getObject(pResult, pEnv, object, fieldIds.alphaSettings, &alphaSettings);

        // Retrieve the slideSettings.
        videoEditJava_getObject(pResult, pEnv, object, fieldIds.slideSettings, &slideSettings);
    }

    // Only retrieve the TransitionSettings if the fields could be located.
    if (*pResult)
    {
        // Allocate memory for the TransitionSettings.
        pSettings = (M4VSS3GPP_TransitionSettings*)videoEditOsal_alloc(pResult,
                pEnv, sizeof(M4VSS3GPP_TransitionSettings), "TransitionSettings");

        // Check if memory could be allocated for the TransitionSettings.
        if (*pResult)
        {
            // Set the duration of the transition, in milliseconds (set to 0 to get no transition).
            pSettings->uiTransitionDuration = (M4OSA_UInt32)pEnv->GetIntField(object,
                    fieldIds.duration);

            // Set the type of the video transition.
            pSettings->VideoTransitionType =
                    (M4VSS3GPP_VideoTransitionType)videoEditJava_getVideoTransitionJavaToC(
                             &converted, pEnv->GetIntField(object, fieldIds.videoTransitionType));

            // Check if the video transition type is valid.
            videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv, !converted,
                            "transition.videoTransitionType is invalid");
        }

        // Check if the video transition type could be set.
        if (*pResult)
        {
            // Set the external transition video effect function.
            pSettings->ExtVideoTransitionFct = M4OSA_NULL;

            // Set the context of the external transition video effect function.
            pSettings->pExtVideoTransitionFctCtxt = M4OSA_NULL;

            // Set the type of the audio transition.
            pSettings->AudioTransitionType =
                    (M4VSS3GPP_AudioTransitionType)videoEditJava_getAudioTransitionJavaToC(
                            &converted, pEnv->GetIntField(object, fieldIds.audioTransitionType));

            // Check if the audio transition type is valid.
            videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv, !converted,
                             "transition.audioTransitionType is invalid");
        }

        // Check if the audio transition type could be set.
        if (*pResult)
        {
            // Set the transition behaviour.
            pSettings->TransitionBehaviour =
                    (M4VSS3GPP_TransitionBehaviour)videoEditJava_getTransitionBehaviourJavaToC(
                            &converted, pEnv->GetIntField(object, fieldIds.transitionBehaviour));

            // Check if the transition behaviour is valid.
            videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv, !converted,
                                                    "transition.transitionBehaviour is invalid");
        }

        // Check if the audio transition behaviour could be set.
        if (*pResult)
        {
            // Check if a slide transition or alpha magic setting object is expected.
            if ((int)pSettings->VideoTransitionType == M4xVSS_kVideoTransitionType_SlideTransition)
            {
                // Set the slide transition settings.
                videoEditClasses_getSlideTransitionSettings(pResult, pEnv, slideSettings,
                                     &pSettings->xVSS.transitionSpecific.pSlideTransitionSettings);
            }
            else if ((int)pSettings->VideoTransitionType == M4xVSS_kVideoTransitionType_AlphaMagic)
            {
                // Set the alpha magic settings.
                videoEditClasses_getAlphaMagicSettings(pResult, pEnv, alphaSettings,
                                  &pSettings->xVSS.transitionSpecific.pAlphaMagicSettings);
            }
        }

        // Check if settings could be set.
        if (*pResult)
        {
            // Return the settings.
            (*ppSettings) = pSettings;
        }
        else
        {
            // Free the settings.
            videoEditClasses_freeTransitionSettings(&pSettings);
        }
    }
}

void
videoEditClasses_freeTransitionSettings(
                M4VSS3GPP_TransitionSettings**      ppSettings)
{
    // Check if memory was allocated for the TransitionSettings.
    if (M4OSA_NULL != (*ppSettings))
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                               "videoEditClasses_freeTransitionSettings()");

        // Check if a slide transition or alpha magic setting structure is expected.
        if ((int)(*ppSettings)->VideoTransitionType == M4xVSS_kVideoTransitionType_SlideTransition)
        {
            // Free the slide transition settings.
            videoEditClasses_freeSlideTransitionSettings(
                               &(*ppSettings)->xVSS.transitionSpecific.pSlideTransitionSettings);
        }
        else
        {
            // Free the alpha magic settings.
            videoEditClasses_freeAlphaMagicSettings(
                              &(*ppSettings)->xVSS.transitionSpecific.pAlphaMagicSettings);
        }

        // Free the settings structure.
        videoEditOsal_free((*ppSettings));
        (*ppSettings) = M4OSA_NULL;
    }
}

#ifdef VIDEOEDIT_LOGGING_ENABLED
void
videoEditClasses_logTransitionSettings(
                M4VSS3GPP_TransitionSettings*       pSettings,
                int                                 indentation)
{
    // Check if memory was allocated for the TransitionSettings.
    if (M4OSA_NULL != pSettings)
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                               "%*c uiTransitionDuration:       %u ms", indentation, ' ',
                                  (unsigned int)pSettings->uiTransitionDuration);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                           "%*c VideoTransitionType:        %s",    indentation, ' ',
                           videoEditJava_getVideoTransitionString(pSettings->VideoTransitionType));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                              "%*c ExtVideoTransitionFct:      %s",    indentation, ' ',
                              (M4OSA_NULL != pSettings->ExtVideoTransitionFct) ? "set" : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                         "%*c pExtVideoTransitionFctCtxt: %s",    indentation, ' ',
                         (M4OSA_NULL != pSettings->pExtVideoTransitionFctCtxt) ? "set" : "<null>");
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                         "%*c AudioTransitionType:        %s",    indentation, ' ',
                          videoEditJava_getAudioTransitionString(pSettings->AudioTransitionType));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                      "%*c TransitionBehaviour:        %s",    indentation, ' ',
                      videoEditJava_getTransitionBehaviourString(pSettings->TransitionBehaviour));

        // Check if a slide transition or alpha magic setting structure is expected.
        if ((int)pSettings->VideoTransitionType == M4xVSS_kVideoTransitionType_SlideTransition)
        {
            // Log the slide transition settings.
            VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                                   "%*c pSlideTransitionSettings:", indentation, ' ');
            videoEditClasses_logSlideTransitionSettings\
            (pSettings->xVSS.transitionSpecific.pSlideTransitionSettings,
            indentation + VIDEOEDIT_LOG_INDENTATION);
        }
        else
        {
            // Log the alpha magic settings.
            VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                                   "%*c pAlphaMagicSettings:", indentation, ' ');
            videoEditClasses_logAlphaMagicSettings\
            (pSettings->xVSS.transitionSpecific.pAlphaMagicSettings,
            indentation + VIDEOEDIT_LOG_INDENTATION);
        }
    }
    else
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                               "%*c <null>", indentation, ' ');
    }
}
#endif
#ifdef VIDEOEDIT_LOGGING_ENABLED
void
videoEditPropClass_logProperties(
                VideoEditPropClass_Properties*                   pProperties,
                int                                 indentation)
{
    // Check if memory was allocated for the Properties.
    if (M4OSA_NULL != pProperties)
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c uiClipDuration:                   %u",       indentation, ' ',
            (unsigned int)pProperties->uiClipDuration);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c FileType:                         %s",       indentation, ' ',
            videoEditJava_getFileTypeString(pProperties->FileType));

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c VideoStreamType:                  %s",       indentation, ' ',
            videoEditJava_getVideoFormatString(pProperties->VideoStreamType));
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c uiClipVideoDuration:              %u",       indentation, ' ',
            (unsigned int)pProperties->uiClipVideoDuration);

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c uiVideoBitrate:                   %s",       indentation, ' ',
            videoEditJava_getBitrateString(pProperties->uiVideoBitrate));

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c uiVideoWidth:                     %u",       indentation, ' ',
            (unsigned int)pProperties->uiVideoWidth);

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c uiVideoHeight:                    %u",       indentation, ' ',
            (unsigned int)(unsigned int)pProperties->uiVideoHeight);

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c fAverageFrameRate:                %.3f",     indentation, ' ',
            pProperties->fAverageFrameRate);

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c AudioStreamType:                  %s",       indentation, ' ',
            videoEditJava_getAudioFormatString(pProperties->AudioStreamType));

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c uiClipAudioDuration:              %u",       indentation, ' ',
            (unsigned int)pProperties->uiClipAudioDuration);

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c uiAudioBitrate:                   %s",       indentation, ' ',
            videoEditJava_getBitrateString(pProperties->uiAudioBitrate));

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c uiNbChannels:                     %u",       indentation, ' ',
            (unsigned int)pProperties->uiNbChannels);

        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
             "%*c uiSamplingFrequency:              %u",       indentation, ' ',
            (unsigned int)pProperties->uiSamplingFrequency);
    }
    else
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_PROP_CLASSES",
            "%*c <null>", indentation, ' ');
    }
}
#endif


void
videoEditClasses_createVersion(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                M4_VersionInfo*                     pVersionInfo,
                jobject*                            pObject)
{

    VideoEditJava_VersionFieldIds fieldIds;
    jclass                clazz    = NULL;
    jobject               object   = NULL;
    memset(&fieldIds, 0, sizeof(VideoEditJava_VersionFieldIds));
    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                               "videoEditClasses_createVersion()");

        // Retrieve the class.
        videoEditJava_getVersionClass(pResult, pEnv, &clazz);

        // Retrieve the field ids.
        videoEditJava_getVersionFieldIds(pResult, pEnv, &fieldIds);
    }

    // Only create an object if the class and fields could be located.
    if (*pResult)
    {
        // Allocate a new object.
        object = pEnv->AllocObject(clazz);

        // check if alloc is done
        videoEditJava_checkAndThrowRuntimeException(pResult, pEnv,
                                                    (NULL == object),
                                                    M4ERR_ALLOC);
        if (NULL != object)
        {
            // Set the major field.
            pEnv->SetIntField(object, fieldIds.major, pVersionInfo->m_major);

            // Set the minor field.
            pEnv->SetIntField(object, fieldIds.minor, pVersionInfo->m_minor);

            // Set the revision field.
            pEnv->SetIntField(object, fieldIds.revision, pVersionInfo->m_revision);

            // Return the object.
            (*pObject) = object;
        }
    }
}

#ifdef VIDEOEDIT_LOGGING_ENABLED
void
videoEditClasses_logVersion(
                M4_VersionInfo*                     pVersionInfo,
                int                                 indentation)
{
    // Check if memory was allocated for the Version.
    if (M4OSA_NULL != pVersionInfo)
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                             "%*c major:    %u ms", indentation, ' ',
                             (unsigned int)pVersionInfo->m_major);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                              "%*c minor:    %u",    indentation, ' ',
                              (unsigned int)pVersionInfo->m_minor);
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                             "%*c revision: %u",    indentation, ' ',
                             (unsigned int)pVersionInfo->m_revision);
    }
    else
    {
        VIDEOEDIT_LOG_SETTING(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                              "%*c <null>", indentation, ' ');
    }
}
#endif


void*
videoEditClasses_getContext(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object)
{
    void*                pContext = M4OSA_NULL;
    jclass               clazz    = NULL;
    VideoEditJava_EngineFieldIds fieldIds = {NULL};

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                               "videoEditClasses_getContext()");

        // Retrieve the class.
        videoEditJava_getEngineClass(pResult, pEnv, &clazz);

        // Retrieve the field ids.
        videoEditJava_getEngineFieldIds(pResult, pEnv, &fieldIds);
    }

    // Check if the class and field ids could be located.
    if (*pResult)
    {
        // Retrieve the context pointer.
        pContext = (void *)pEnv->GetIntField(object, fieldIds.context);
    }

    // Return the context pointer.
    return(pContext);
}

void
videoEditClasses_setContext(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                void*                               pContext)
{
    jclass               clazz    = NULL;
    VideoEditJava_EngineFieldIds fieldIds = {NULL};

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                               "videoEditClasses_setContext()");

        // Retrieve the class.
        videoEditJava_getEngineClass(pResult, pEnv, &clazz);

        // Retrieve the field ids.
        videoEditJava_getEngineFieldIds(pResult, pEnv, &fieldIds);
    }

    // Check if the class and field ids could be located.
    if (*pResult)
    {
        // Set the context field.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                        "The context value from JAVA before setting is = 0x%x",
                        pEnv->GetIntField(object, fieldIds.context));

        pEnv->SetIntField(object, fieldIds.context, (int)pContext);
        M4OSA_TRACE1_1("The context value in JNI is = 0x%x",pContext);

        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_CLASSES",
                         "The context value from JAVA after setting is = 0x%x",
                         pEnv->GetIntField(object, fieldIds.context));
    }
}

