/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
/*********************************************************************************/
/*  File: cvei.h                                                                */
/*  Purpose:                                                                    */
/*  Date:                                                                       */
/*  Revision History:                                                           */
/** @introduction   Common Video Encoder Interface (CVEI) is intended to be used by
    application developers who want to create a multimedia application with video
    encoding feature. CVEI is designed such that new video encoder algorithms or
    modules can be plugged in seamlessly without user interaction. In other words,
    any changes to the CVEI library are transparent to the users. Users can still
    use the same set of APIs for new encoding tools.

    @requirement    CVEI will take an input frame in one of several format supported
    by PV and encode it to an MPEG4 bitstream. It will also return a reconstructed
    image in YUV 4:2:0 format. Currently the input format supported are YUV 4:2:0,
    RGB24 and UYVY 4:2:2.

    CVEI is designed such that it is simple to use. It should hides implementation
    dependency  from the users. In this version, we decided that the operation will
    be synchronous, i.e., the encoding will be a blocked call. Asynchronous operation
    will be in the level above CVEI, i.e., in Author Engine Video Module which will
    take care of capturing device as well.

    @brief  The following classes are used to interface with codecs. Their names
    are CPVxxxVideoEncoder where xxx is codec specific such as MPEG4, H263, H26L,
    etc. All of them are subclasses of CPVCommonVideoEncoder.
*/
/*********************************************************************************/

#ifndef __CVEI_H
#define __CVEI_H

#include "oscl_scheduler_ao.h"
#include "oscl_base.h"
#include "mp4enc_api.h" /* for MP4HintTrack */

#define MAX_LAYER 2

/** General returned values. */
enum TCVEI_RETVAL
{
    ECVEI_SUCCESS,
    ECVEI_FAIL,
    ECVEI_FLUSH,
    ECVEI_MORE_OUTPUT
} ;

/** Returned events with the callback function. */
enum TCVEI_EVENT
{
    /** Called when a packet or a frame of output bitstream is ready. */
    ECVEI_BUFFER_READY,

    /** Called when the last packet of a frame of output bitstream is ready. */
    ECVEI_FRAME_DONE,

    /** Called when no buffers is available for output bitstream. A buffer can be added thru AddBuffer API. */
    ECVEI_NO_BUFFERS,

    /** Called when there is an error with the encoding operation. */
    ECVEI_ERROR
};

/** Contains supported input format */
enum TPVVideoFormat
{
    ECVEI_RGB24,
    ECVEI_RGB12,
    ECVEI_YUV420,
    ECVEI_UYVY,
    ECVEI_YUV420SEMIPLANAR
};

/** Type of contents for optimal encoding mode. */
enum TPVContentType
{
    /** Content is to be streamed in real-time. */
    ECVEI_STREAMING,

    /** Content is to be downloaded and playbacked later.*/
    ECVEI_DOWNLOAD,

    /** Content is to be 3gpp baseline compliant. */
    ECVEI_H263
};

/** Rate control type. */
enum TMP4RateControlType
{
    /** Constant quality, variable bit rate, fixed quantization level. */
    ECONSTANT_Q,

    /** Short-term constant bit rate control. */
    ECBR_1,

    /** Long-term constant bit rate control. */
    EVBR_1
};

/** Targeted profile and level to encode. */
enum TPVM4VProfileLevel
{
    /* Non-scalable profile */
    ECVEI_SIMPLE_LEVEL0 = 0,
    ECVEI_SIMPLE_LEVEL1,
    ECVEI_SIMPLE_LEVEL2,
    ECVEI_SIMPLE_LEVEL3,
    ECVEI_CORE_LEVEL1,
    ECVEI_CORE_LEVEL2,

    /* Scalable profile */
    ECVEI_SIMPLE_SCALABLE_LEVEL0 = 6,
    ECVEI_SIMPLE_SCALABLE_LEVEL1,
    ECVEI_SIMPLE_SCALABLE_LEVEL2,

    ECVEI_CORE_SCALABLE_LEVEL1 = 10,
    ECVEI_CORE_SCALABLE_LEVEL2,
    ECVEI_CORE_SCALABLE_LEVEL3
};

/** This structure contains encoder settings. */
struct TPVVideoEncodeParam
{
    /** Specifies an  ID that will be used to specify this encoder while returning
    the bitstream in asynchronous mode. */
    uint32              iEncodeID;

    /** Specifies whether base only (iNumLayer = 1) or base + enhancement layer
    (iNumLayer =2 ) is to be used. */
    int32               iNumLayer;

    /** Specifies the width in pixels of the encoded frames. IFrameWidth[0] is for
    base layer and iFrameWidth[1] is for enhanced layer. */
    int                 iFrameWidth[MAX_LAYER];

    /** Specifies the height in pixels of the encoded frames. IFrameHeight[0] is for
    base layer and iFrameHeight[1] is for enhanced layer. */
    int                 iFrameHeight[MAX_LAYER];

    /** Specifies the cumulative bit rate in bit per second. IBitRate[0] is for base
    layer and iBitRate[1] is for base+enhanced layer.*/
    int                 iBitRate[MAX_LAYER];

    /** Specifies the cumulative frame rate in frame per second. IFrameRate[0] is for
    base layer and iFrameRate[1] is for base+enhanced layer. */
    float               iFrameRate[MAX_LAYER];

    /** Specifies the picture quality factor on the scale of 1 to 10. It trades off
    the picture quality with the frame rate. Higher frame quality means lower frame rate.
    Lower frame quality for higher frame rate.*/
    int32               iFrameQuality;

    /** Enable the use of iFrameQuality to determine the frame rate. If it is false,
    the encoder will try to meet the specified frame rate regardless of the frame quality.*/
    bool                iEnableFrameQuality;

    /** Specifies the maximum number of P-frames between 2 INTRA frames. An INTRA mode is
    forced to a frame once this interval is reached. When there is only one I-frame is present
    at the beginning of the clip, iIFrameInterval should be set to -1. */
    int32               iIFrameInterval;

    /** According to iIFrameInterval setting, the minimum number of intra MB per frame is
    optimally calculated for error resiliency. However, when iIFrameInterval is set to -1,
    iNumIntraMBRefresh must be specified to guarantee the minimum number of intra
    macroblocks per frame.*/
    uint32              iNumIntraMBRefresh;

    /** Specifies the VBV buffer size which determines the end-to-end delay between the
    encoder and the decoder.  The size is in unit of seconds. For download application,
    the buffer size can be larger than the streaming application. For 2-way application,
    this buffer shall be kept minimal. For a special case, in VBR mode, iBufferDelay will
    be set to -1 to allow buffer underflow. */
    float               iBufferDelay;

    /** Specifies the type of the access whether it is streaming, CVEI_STREAMING
    (data partitioning mode) or download, CVEI_DOWNLOAD (combined mode).*/
    TPVContentType      iContentType;

    /** Specifies the rate control algorithm among one of the following constant Q,
    CBR and VBR.  The structure TMP4RateControlType is defined below.*/
    TMP4RateControlType iRateControlType;

    /** Specifies high quality but also high complexity mode for rate control. */
    bool                iRDOptimal;

    /** Specifies the initial quantization parameter for the first I-frame. If constant Q
    rate control is used, this QP will be used for all the I-frames. This number must be
    set between 1 and 31, otherwise, Initialize() will fail. */
    int                 iIquant[2];

    /** Specifies the initial quantization parameter for the first P-frame. If constant Q
    rate control is used, this QP will be used for all the P-frames. This number must be
    set between 1 and 31, otherwise, Initialize() will fail. */
    int                 iPquant[2];

    /** Specifies the initial quantization parameter for the first B-frame. If constant Q
    rate control is used, this QP will be used for all the B-frames. This number must be
    set between 1 and 31, otherwise, Initialize() will fail. */
    int                 iBquant[2];

    /** Specifies the search range in pixel unit for motion vector. The range of the
    motion vector will be of dimension [-iSearchRange.5, +iSearchRange.0]. */
    int32               iSearchRange;

    /** Specifies the use of 8x8 motion vectors. */
    bool                iMV8x8;

    /** Specifies the use of half-pel motion vectors. */
    bool                iMVHalfPel;

    /** Specifies automatic scene detection where I-frame will be used the the first frame
    in a new scene. */
    bool                iSceneDetection;

    /** Specifies the packet size in bytes which represents the number of bytes between two resync markers.
    For ECVEI_DOWNLOAD and ECVEI_H263, if iPacketSize is set to 0, there will be no resync markers in the bitstream.
    For ECVEI_STREAMING is parameter must be set to a value greater than 0.*/
    uint32              iPacketSize;

    /** Specifies whether the current frame skipping decision is allowed after encoding
    the current frame. If there is no memory of what has been coded for the current frame,
    iNoCurrentSkip has to be on. */
    bool                iNoCurrentSkip;

    /** Specifies that no frame skipping is allowed. Frame skipping is a tool used to
    control the average number of bits spent to meet the target bit rate. */
    bool                iNoFrameSkip;

    /** Specifies the duration of the clip in millisecond.*/
    int32               iClipDuration;

    /** Specifies the profile and level used to encode the bitstream. When present,
    other settings will be checked against the range allowable by this target profile
    and level. Fail may be returned from the Initialize call. */
    TPVM4VProfileLevel  iProfileLevel;

    /** Specifies FSI Buffer input */
    uint8*              iFSIBuff;

    /** Specifies FSI Buffer Length */
    int             iFSIBuffLength;


};


/** Structure for input format information */
struct TPVVideoInputFormat
{
    /** Contains the width in pixels of the input frame. */
    int32           iFrameWidth;

    /** Contains the height in pixels of the input frame. */
    int32           iFrameHeight;

    /** Contains the input frame rate in the unit of frame per second. */
    float           iFrameRate;

    /** Contains Frame Orientation. Used for RGB input. 1 means Bottom_UP RGB, 0 means Top_Down RGB, -1 for video formats other than RGB*/
    int             iFrameOrientation;

    /** Contains the format of the input video, e.g., YUV 4:2:0, UYVY, RGB24, etc. */
    TPVVideoFormat  iVideoFormat;
};


/** Contains the input data information */
struct TPVVideoInputData
{
    /** Pointer to an input frame buffer in input source format.*/
    uint8       *iSource;

    /** The corresponding time stamp of the input frame. */
    uint32      iTimeStamp;
};

/** Contains the output data information */
struct TPVVideoOutputData
{
    /** Pointer to the reconstructed frame buffer in YUV 4:2:0 domain. */
    uint8           *iFrame;

    /** The number of layer encoded, 0 for base, 1 for enhanced. */
    int32           iLayerNumber;

    /** Pointer to the encoded bitstream buffer. */
    uint8           *iBitStream;

    /** The size in bytes of iBStream. */
    int32           iBitStreamSize;

    /** The time stamp of the encoded frame according to the bitstream. */
    uint32          iVideoTimeStamp;

    /** The time stamp of the encoded frame as given before the encoding. */
    uint32          iExternalTimeStamp;

    /** The hint track information. */
    MP4HintTrack    iHintTrack;
};

/** An observer class for callbacks to report the status of the CVEI */
class MPVCVEIObserver
{
    public:
        /** The callback funtion with aEvent being one of TCVEIEvent enumeration. */
        virtual void HandlePVCVEIEvent
        (uint32 aId, uint32 aEvent, uint32 aParam1 = 0) = 0;
        virtual ~MPVCVEIObserver() {}
};

/** This class is the base class for codec specific interface class.
The users must maintain an instance of the codec specific class throughout
the encoding session.
*/
class CommonVideoEncoder : public OsclTimerObject
{
    public:
        /** Constructor for CVEI class. */
        CommonVideoEncoder() : OsclTimerObject(OsclActiveObject::EPriorityNominal, "PVEncoder") {};

        /** Initialization function to set the input video format and the
        encoding parameters. This function returns CVEI_ERROR if there is
        any errors. Otherwise, the function returns CVEI_SUCCESS.*/
        virtual  TCVEI_RETVAL Initialize(TPVVideoInputFormat *aVidInFormat, TPVVideoEncodeParam *aEncParam) = 0;

        /** Set the observer for asynchronous encoding mode. */
        virtual  TCVEI_RETVAL SetObserver(MPVCVEIObserver *aObserver) = 0;

        /** Add a buffer to the queue of output buffers for output bitstream in
        asynchronous encoding mode. */
        virtual  TCVEI_RETVAL AddBuffer(TPVVideoOutputData *aVidOut) = 0;

        /** This function sends in an input video data structure containing a source
        frame and the associated timestamp. The encoded bitstream will be returned by
        observer callback.
        The above 3 APIs only replace EncodeFrame() API. Other APIs such as initialization
        and update parameters remain the same. */
        virtual  TCVEI_RETVAL Encode(TPVVideoInputData *aVidIn) = 0;

        /** This function returns the maximum VBV buffer size such that the
            application can allocate a buffer that guarantees to fit one frame.*/
        virtual  int32 GetBufferSize() = 0;

        /** This function returns the VOL header part (starting from the VOS header)
        of the encoded bitstream. This function must be called after Initialize.
        The output is written to the memory (volHeader) allocated by the users.*/
        virtual  TCVEI_RETVAL GetVolHeader(uint8 *volHeader, int32 *size, int32 layer) = 0;

        /** This function sends in an input video data structure containing a source
        frame and the associated timestamp. It returns an output video data structure
        containing coded bit stream, reconstructed frame in YUV 4:2:0 (can be changed
        to source format) and the timestamp associated with the coded frame.
        The input timestamp may not correspond to the output timestamp. User can send
        an input structure in without getting any encoded data back or getting an encoded
        frame in the past. This function returns ECVEI_ERROR if there is any errors.
        Otherwise, the function returns ECVEI_SUCCESS.
        In case of Overrun Buffer usage, it is possible that return value is ECVEI_MORE_OUTPUT
        which indicates that frame cannot fit in the current buffer*/
        virtual  TCVEI_RETVAL EncodeFrame(TPVVideoInputData  *aVidIn, TPVVideoOutputData *aVidOut, int *aRemainingBytes
#ifdef PVAUTHOR_PROFILING
                                          , void *aParam1 = 0
#endif
                                         ) = 0;

        /** Before the termination of the encoding process, the users have to query
        whether there are any encoded frame pending inside the CVEI. The returned value
        will indicate whether there are more frames to be flushed (ECVEI_FLUSH).
        FlushOutput has to be called until there are no more frames, i.e., it returns
        ECVEI_SUCCESS. This function may be called during the encoding operation if
        there is no input frame and the application does not want to waste the time
        waiting for input frame. It can call this function to flush encoded frame
        out of the memory. */
        virtual  TCVEI_RETVAL FlushOutput(TPVVideoOutputData *aVidOut) = 0;

        /** This function cleanup the CVEI allocated resources. */
        virtual  TCVEI_RETVAL Terminate() = 0;

        /**This function dynamically changes the target bit rate of the encoder
        while encoding. aBitRate[n] is the new accumulate target bit rate of layer n.
        Successful update is returned with ECVEI_SUCCESS.*/
        virtual  TCVEI_RETVAL UpdateBitRate(int32 aNumLayer, int32 *aBitRate) = 0;

        /** This function dynamically changes the target frame rate of the encoder
        while encoding. aFrameRate[n] is the new accumulate target frame rate of
        layer n. Successful update is returned with ECVEI_SUCCESS. */
        virtual  TCVEI_RETVAL UpdateFrameRate(int32 aNumLayer, float *aFrameRate) = 0;

        /** This function dynamically changes the I-Vop update interval while
        encoding to a new value, aIFrameInterval. */
        virtual  TCVEI_RETVAL UpdateIFrameInterval(int32 aIFrameInterval) = 0;

        /** This function forces an I-Vop mode to the next frame to be encoded. */
        virtual  TCVEI_RETVAL IFrameRequest() = 0;

        /** This function returns the input width of a specific layer
        (not necessarily multiple of 16). */
        virtual  int32 GetEncodeWidth(int32 aLayer) = 0;

        /** This function returns the input height of a specific layer
        (not necessarily multiple of 16). */
        virtual  int32 GetEncodeHeight(int32 aLayer) = 0;

        /** This function returns the target encoded frame rate of a specific layer. */
        virtual  float GetEncodeFrameRate(int32 aLayer) = 0;
    protected:
        virtual void Run(void) = 0;
        virtual void DoCancel(void) = 0;
        /* internal enum */
        enum TCVEIState
        {
            EIdle,
            EEncode
        };

        TCVEIState  iState;
        uint32      iId;
};

#endif
