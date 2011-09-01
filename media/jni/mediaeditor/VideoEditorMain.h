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

#ifndef __VIDEO_EDITOR_API_H__
#define __VIDEO_EDITOR_API_H__

#include "M4OSA_Types.h"

typedef enum
{
    MSG_TYPE_PROGRESS_INDICATION,     // Playback progress indication event
    MSG_TYPE_PLAYER_ERROR,            // Playback error
    MSG_TYPE_PREVIEW_END,             // Preview of clips is complete
    MSG_TYPE_OVERLAY_UPDATE,          // update overlay during preview
    MSG_TYPE_OVERLAY_CLEAR,           // clear the overlay
} progress_callback_msg_type;

typedef struct {
    int overlaySettingsIndex;
    int clipIndex;
} VideoEditorCurretEditInfo;

typedef struct
{
    M4OSA_Void     *pFile;                   /** PCM file path */
    M4OSA_Bool     bRemoveOriginal;          /** If true, the original audio track
                                                 is not taken into account */
    M4OSA_UInt32   uiNbChannels;            /** Number of channels (1=mono, 2=stereo) of BGM clip*/
    M4OSA_UInt32   uiSamplingFrequency;     /** Sampling audio frequency (8000 for amr, 16000 or
                                                more for aac) of BGM clip*/
    M4OSA_UInt32   uiExtendedSamplingFrequency; /** Extended frequency for AAC+,
                                                eAAC+ streams of BGM clip*/
    M4OSA_UInt32   uiAddCts;                /** Time, in milliseconds, at which the added
                                                audio track is inserted */
    M4OSA_UInt32   uiAddVolume;             /** Volume, in percentage, of the added audio track */
    M4OSA_UInt32   beginCutMs;
    M4OSA_UInt32   endCutMs;
    M4OSA_Int32    fileType;
    M4OSA_Bool     bLoop;                   /** Looping on/off **/
    /* Audio ducking */
    M4OSA_UInt32   uiInDucking_threshold;   /** Threshold value at which
                                                background music shall duck */
    M4OSA_UInt32   uiInDucking_lowVolume;   /** lower the background track to
                                                this factor of current level */
    M4OSA_Bool     bInDucking_enable;       /** enable ducking */
    M4OSA_UInt32   uiBTChannelCount;        /** channel count for BT */
    M4OSA_Void     *pPCMFilePath;
} M4xVSS_AudioMixingSettings;

typedef struct
{
    M4OSA_Void      *pBuffer;            /* YUV420 buffer of frame to be rendered*/
    M4OSA_UInt32    timeMs;            /* time stamp of the frame to be rendered*/
    M4OSA_UInt32    uiSurfaceWidth;    /* Surface display width*/
    M4OSA_UInt32    uiSurfaceHeight;    /* Surface display height*/
    M4OSA_UInt32    uiFrameWidth;        /* Frame width*/
    M4OSA_UInt32    uiFrameHeight;        /* Frame height*/
    M4OSA_Bool      bApplyEffect;        /* Apply video effects before render*/
    M4OSA_UInt32    clipBeginCutTime;  /* Clip begin cut time relative to storyboard */
    M4OSA_UInt32    clipEndCutTime;    /* Clip end cut time relative to storyboard */
    M4OSA_UInt32    videoRotationDegree; /* Video rotation degree */

} VideoEditor_renderPreviewFrameStr;
#endif /*__VIDEO_EDITOR_API_H__*/
