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
#ifndef _MP4ENC_API_H_
#define _MP4ENC_API_H_

#include <string.h>

#ifndef _PV_TYPES_
#define _PV_TYPES_
typedef unsigned char UChar;
typedef char Char;
typedef unsigned int UInt;
typedef int Int;
typedef unsigned short UShort;
typedef short Short;
typedef unsigned int Bool;
typedef unsigned long ULong;

#define PV_CODEC_INIT  0
#define PV_CODEC_STOP  1
#endif

#define PV_TRUE  1
#define PV_FALSE 0

typedef enum
{
    SHORT_HEADER,
    SHORT_HEADER_WITH_ERR_RES,
    H263_MODE,
    H263_MODE_WITH_ERR_RES,
    DATA_PARTITIONING_MODE,
    COMBINE_MODE_NO_ERR_RES,
    COMBINE_MODE_WITH_ERR_RES

} MP4EncodingMode;

typedef enum
{
    CONSTANT_Q,
    CBR_1,
    VBR_1,
    CBR_2,
    VBR_2,
    CBR_LOWDELAY
} MP4RateControlType;

typedef enum
{
    PASS1,
    PASS2
} PassNum;

typedef enum
{
    PV_OFF,
    PV_ON
} ParamEncMode;


/* {SPL0, SPL1, SPL2, SPL3, CPL1, CPL2, CPL2, CPL2} , SPL0: Simple Profile@Level0 , CPL1: Core Profile@Level1 */
/* {SSPL0, SSPL1, SSPL2, SSPL2, CSPL1, CSPL2, CSPL3, CSPL3} , SSPL0: Simple Scalable Profile@Level0, CPL1: Core Scalable Profile@Level1 */

typedef enum
{
    /* Non-scalable profile */
    SIMPLE_PROFILE_LEVEL0 = 0,
    SIMPLE_PROFILE_LEVEL1,
    SIMPLE_PROFILE_LEVEL2,
    SIMPLE_PROFILE_LEVEL3,
    CORE_PROFILE_LEVEL1,
    CORE_PROFILE_LEVEL2,

    /* Scalable profile */
    SIMPLE_SCALABLE_PROFILE_LEVEL0 = 6,
    SIMPLE_SCALABLE_PROFILE_LEVEL1,
    SIMPLE_SCALABLE_PROFILE_LEVEL2,

    CORE_SCALABLE_PROFILE_LEVEL1 = 10,
    CORE_SCALABLE_PROFILE_LEVEL2,
    CORE_SCALABLE_PROFILE_LEVEL3

} ProfileLevelType;


typedef struct tagMP4HintTrack
{
    UChar   MTB;
    UChar   LayerID;
    UChar   CodeType;
    UChar   RefSelCode;
} MP4HintTrack;

typedef struct tagvideoEncControls
{
    void            *videoEncoderData;
    Int             videoEncoderInit;
} VideoEncControls;


typedef struct tagvideoEncFrameIO
{
    UChar   *yChan; /* pointer to Y */
    UChar   *uChan; /* pointer to U */
    UChar   *vChan; /* pointer to V */
    Int     height; /* height for Y */
    Int     pitch;  /* stride  for Y */
    ULong   timestamp; /* modulo timestamp in millisecond*/

}   VideoEncFrameIO  ;

/**
@brief  Encoding options structure */
typedef struct tagvideoEncOptions
{
    /** @brief Sets the encoding mode, defined by the above enumaration. If there are conflicts between the encoding mode
    *   and subsequent encoding options, encoding mode take precedent over encoding options. */
    MP4EncodingMode     encMode;

    /** @brief Sets the number of bytes per packet, only used in DATA_PARTITIONING_MODE or COMBINE_MODE_WITH_ERR_RES mode.
    *           The resync marker will be inserted as often as the size of the packet.*/
    Int                 packetSize;

    /** @brief Selects MPEG-4/H.263 profile and level, if specified other encoding options must conform with it. */
    ProfileLevelType    profile_level;

    /** @brief Enables reversible variable length code (RVLC) mode. Normally it is set to PV_OFF.*/
    ParamEncMode        rvlcEnable;

    /** @brief Set the frequency of GOB header interval */
    Int                 gobHeaderInterval;

    /** @brief Sets the number of bitstream layers: 1 is base only: 2 is base + enhancement */
    Int                 numLayers;

    /** @brief Sets the number of ticks per second used for timing information encoded in MPEG4 bitstream.*/
    Int                 timeIncRes;

    /** @brief Sets the number of ticks in time increment resolution between 2 source frames (equivalent to source frame rate). */
    Int                 tickPerSrc;

    /** @brief Specifies encoded heights in pixels, height[n] represents the n-th layer's height. */
    Int                 encHeight[2];

    /** @brief Specifies encoded widths in pixels, width[n] represents the n-th layer's width.*/
    Int                 encWidth[2];

    /** @brief Specifies target frame rates in frames per second, frameRate[n] represents the n-th layer's target frame rate.*/
    float               encFrameRate[2];

    /** @brief Specifies target bit rates in bits per second unit, bitRate[n] represents the n-th layer's target bit rate. */
    Int                 bitRate[2];

    /** @brief Specifies default quantization parameters for I-Vop. Iquant[n] represents the n-th layer default quantization parameter. The default is Iquant[0]=12.*/
    Int                 iQuant[2];

    /** @brief Specifies default quantization parameters for P-Vop. Pquant[n] represents the n-th layer default quantization parameter. The default is Pquant[0]=10.*/
    Int                 pQuant[2];

    /** @brief  specifies quantization mode (H263 mode or MPEG mode) of the encoded base and enhance layer (if any).
    *           In Simple and Simple Scalable profile, we use only H263 mode.*/
    Int                 quantType[2];

    /** @brief Sets rate control algorithm, one of (CONSTANT_Q, CBR_1, or VBR_1).
    *           CONSTANT_Q uses the default quantization values to encode the sequence.
    *           CBR_1 (constant bit rate) controls the output at a desired bit rate
    *           VBR_1 (variable bit rate) gives better picture quality at the expense of bit rate fluctuation
    *           Note:   type=CONSTANT_Q produces sequences with arbitrary bit rate.
    *                   type=CBR_1 produces sequences suitable for streaming.
    *                   type=VBR_1 produces sequences suitable for download. */
    MP4RateControlType  rcType;

    /** @brief  Sets the VBV buffer size (in the unit of second delay) used to prevent buffer overflow and underflow
    *           on the decoder side. This function is redundant to PVSetVBVSize. Either one of them is used at a time. */
    float               vbvDelay;

    /** @brief  Specifies whether frame skipping is permitted or not. When rate control type is set to CONSTANT_Q
    *           frame skipping is automatically banned.  In CBR_1 and VBR_1 rate control, frame skipping is allowed by default.
    *           However, users can force no frame skipping with this flag, but buffer constraint may be violated.*/
    ParamEncMode        noFrameSkipped;

    /** @brief Sets the maximum number of P-frames between two I-frames. I-frame mode is periodically forced
    *           if no I-frame is encoded after the specified period to add error resiliency and help resynchronize in case of errors.
    *           If scene change detection can add additional I-frame if new scenes are detected.
    *           intraPeriod is the I frame interval in terms of second.
    *           intraPeriod =0 indicates I-frame encoding only;
    *           intraPeriod = -1  indicates I-frame followed by all P-frames; (default)
    *           intraPeriod = N, indicates the number of P-frames between 2 I-frames.*/
    Int                 intraPeriod;


    /** @brief  Specifies the number Intra MBs to be refreshed in a P-frame. */
    Int                 numIntraMB;

    /**
    *   @brief  Specifies whether the scene change detection (SCD) is enabled or disabled.
    *           With SCD enable, when a new scene is detected, I-Vop mode will be used for the first frame of
    *           the new scene resulting in better picture quality. An insertion of an I-VOP resets the intraPeriod
    *           specified by the IntraPeriodAPI().*/
    ParamEncMode        sceneDetect;

    /** @brief  Specifies the search range of motion estimation search.  Larger value implies
    *           larger search range, better motion vector match, but more complexity.
    *           If searchRange=n, the motion vector search is in the range of [-n,n-1] pixels.
    *           If half-pel  mode is on, the range is [-n, (n-1)+1/2] pixels. The default value is 16.*/
    Int                 searchRange;

    /** @brief  Turns on/off 8x8 block motion estimation and compensation.
    *           If on, four motion vectors may be used for motion estimation and compensation of a macroblock,
    *           otherwise one motion vector per macroblock is used. When the 8x8 MV is off, the total encoding complexity
    *           is less but the image quality is also worse. Therefore, it can be used in complexity limited environment.*/
    ParamEncMode        mv8x8Enable;


    /** @brief Set the threshold for using intra DC VLC.
    *           Value must range from 0-7.*/
    Int                 intraDCVlcTh;

    /** @brief This flag turns on the use of AC prediction */
    Bool                useACPred;

} VideoEncOptions;

#ifdef __cplusplus
extern "C"
{
#endif


    /* API's */
    /* Always start with this one !!*/
    /**
    *   @brief  Gets default encoding options. This way users only have to set relevant encoding options and leave the one
    *           they are unsure of.
    *   @encOption  Pointer to VideoEncOption structure.
    *   @encUseCase This value determines the set of default encoding options, for example, different encoding options
    *            are assigned to streaming use-case as compared to download use-case. It can be project dependent too.
    *   @return true for correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool PVGetDefaultEncOption(VideoEncOptions *encOption, Int encUseCase);

    /**
    *   @brief  Verifies the consistency of encoding parameters, allocates memory needed and set necessary internal variables.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @return true for correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVInitVideoEncoder(VideoEncControls *encCtrl, VideoEncOptions *encOption);

    /* acquiring encoder info APIs */
    /**
    *   @brief  This function returns VOL header. It has to be called before the frame is encoded.  If so,
    *           then the VOL Header is passed back to the application. Then all frames that are encoded do not contain the VOL Header.
    *           If you do not call the API then the VOL Header is passed within the first frame that is encoded.
    *           The behavior is unknown if it is called after the first frame is encoded. It is mainly used for MP4 file format authoring.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs.
    *   @param  volHeader is the Buffer for VOL header.
    *   @param  size is the size of VOL header in bytes.
    *   @param  layer is the layer of the requested VOL header.
    *   @return true for correct operation; false if error happens.
    */
    OSCL_IMPORT_REF Bool    PVGetVolHeader(VideoEncControls *encCtrl, UChar *volHeader, Int *size, Int layer);

    /**
    *   @brief  This function returns the profile and level in H.263 coding when the encoding parameters are set
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs.
    *   @param  profileID is the pointer of the profile ID. Right now we only support profile 0
    *   @param  levelID is the pointer of the level ID that could be 10-70.
    *   @return true for correct operation; false if error happens.
    */
    OSCL_IMPORT_REF Bool    PVGetH263ProfileLevelID(VideoEncControls *encCtrl, Int *profileID, Int *levelID);

    /**
    *   @brief  This function returns the profile and level of MPEG4 when the encoding parameters are set
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs.
    *   @param  profile_level is the pointer of the profile enumeration
    *   @param  nLayer is the index of the layer of interest
    *   @return true for correct operation; false if error happens.
    */
    OSCL_IMPORT_REF Bool    PVGetMPEG4ProfileLevelID(VideoEncControls *encCtrl, Int *profile_level, Int nLayer);

    /**
    *   @brief  This function returns maximum frame size in bytes
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @param  maxVideoFrameSize is the pointer of the maximum frame size
    *   @return true for correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVGetMaxVideoFrameSize(VideoEncControls *encCtrl, Int *maxVideoFrameSize);

#ifndef LIMITED_API
    /**
    *   @brief  This function returns the total amount of memory (in bytes) allocated by the encoder library.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @return true for correct operation; false if error happens
    */
    OSCL_IMPORT_REF Int     PVGetEncMemoryUsage(VideoEncControls *encCtrl);

    /**
    *   @brief  This function is used by PVAuthor to get the size of the VBV buffer.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @param  VBVSize is the pointer of The size of the VBV buffer in bytes.
    *   @return true for correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVGetVBVSize(VideoEncControls *encCtrl, Int *VBVSize);
#endif

    /**
    *   @brief  This function encodes a frame in YUV 4:2:0 format from the *video_in input frame and put the result in YUV
    *           for reconstructed frame and bstream for MPEG4 bitstream. The application is required to allocate memory for
    *           bitstream buffer.The size of the input bitstream memory and the returned output buffer are specified in the
    *           size field. The encoded layer is specified by the nLayer field. If the current frame is not encoded, size=0 and nLayer=-1.
    *           Note: If the allocated buffer size is too small to fit a bitstream of a frame, then those extra bits will be left out
    *                 which can cause syntactic error at the decoder side.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @param  vid_in is the pointer to VideoEncFrameIO structure containing the YUV input data
    *   @param  vid_out is the pointer to VideoEncFrameIO structure containing the reconstructed YUV output data after encoding
    *   @param  nextModTime is the timestamp encoder expects from the next input
    *   @param  bstream is the pointer to MPEG4 bitstream buffer
    *   @param  size is the size of bitstream buffer allocated (input) and size of the encoded bitstream (output).
    *   @param  nLayer is the layer of the encoded frame either 0 for base or 1 for enhancement layer. The value -1 indicates skipped frame due to buffer overflow.
    *   @return true newfor correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVEncodeVideoFrame(VideoEncControls *encCtrl, VideoEncFrameIO *vid_in, VideoEncFrameIO *vid_out,
            ULong *nextModTime, UChar *bstream, Int *size, Int *nLayer);


    /**
    *   @brief  This function is used to query overrun buffer. It is used when PVEncodeVideoFrame.returns size that is
    *           larger than the input size.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @return Pointer to the overrun buffer. NULL if overrun buffer is not used.
    */
    OSCL_IMPORT_REF UChar* PVGetOverrunBuffer(VideoEncControls *encCtrl);

#ifndef NO_SLICE_ENCODE   /* This set of APIs are not working. This functionality has been partially 
    replaced by the introduction of overrun buffer. */

    /* slice-based coding */
    /**
    *   @brief  This function sets the input YUV frame and timestamp to be encoded by the slice-based encoding function PVEncodeSlice().
    *           It also return the memory address the reconstructed frame will be copied to (in advance) and the coded layer number.
    *           The encoder library processes the timestamp and determine if this frame is to be encoded or not. If the current frame
    *           is not encoded, nLayer=-1. For frame-based motion estimation, the motion estimation of the entire frame is also performed
    *           in this function. For MB-based motion estimation, the motion vector is searched while coding each MB in PVEncodeSlice().
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @param  vid_in is the pointer to VideoEncFrameIO structure containing the YUV input data
    *   @param  nextModTime is the timestamp encoder expects from the next input if this input is rejected and nLayer is set to -1.
    *   @param  nLayer is the layer of the encoded frame either 0 for base or 1 for enhancement layer. The value -1 indicates skipped frame due to buffer overflow.
    *   @return true newfor correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVEncodeFrameSet(VideoEncControls *encCtrl, VideoEncFrameIO *vid_in, ULong *nextModTime, Int *nLayer);
    /**
    *   @brief  This function encodes a GOB (short header mode) or a packet (data partitioning mode or combined mode with resync marker)
    *           and output the reconstructed frame and MPEG4 bitstream. The application is required to allocate memory for the bitstream buffer.
    *           The size of the input bitstream memory and the returned output buffer are specified in the size field.  If the buffer size is
    *           smaller than the requested packet size, user has to call PVEncodeSlice again to get the rest of that pending packet before moving
    *           on to the next packet. For the combined mode without resync marker, the function returns when the buffer is full.
    *           The end-of-frame flag  indicates the completion of the frame encoding.  Next frame must be sent in with PVEncodeFrameSet().
    *           At the end-of-frame, the next video input address and the next video modulo timestamp will be set.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @param  bstream is the pointer to MPEG4 bitstream buffer.
    *   @param  size is the size of bitstream buffer allocated (input) and size of the encoded bitstream (output).
    *   @param  endofFrame is a flag indicating the end-of-frame, '1'. Otherwise, '0'.  When PVSetNoCurrentFrameSkip is OFF,
    *           end-of-frame '-1' indicates current frame bitstream must be disregarded.
    *   @param  vid_out is the pointer to VideoEncFrameIO structure containing the reconstructed YUV output data after encoding
    *   @param  nextModTime is the timestamp encoder expects from the next input
    *   @return true newfor correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVEncodeSlice(VideoEncControls *encCtrl, UChar *bstream, Int *size,
                                          Int *endofFrame, VideoEncFrameIO *vid_out, ULong *nextModTime);
#endif

    /**
    *   @brief  This function returns MP4 file format hint track information.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @param  info is the structure for MP4 hint track information
    *   @return true for correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVGetHintTrack(VideoEncControls *encCtrl, MP4HintTrack *info);

#ifndef LIMITED_API
    /**
    *   @brief  updates target frame rates of the encoded base and enhance layer (if any) while encoding operation is ongoing.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @param  frameRate is the pointers to array of target frame rates in frames per second,
    *           frameRate[n] represents the n-th layer's target frame rate.
    *   @return true for correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVUpdateEncFrameRate(VideoEncControls *encCtrl, float *frameRate); /* for 2-way */


    /**
    *   @brief  updates target bit rates of the encoded base and enhance layer (if any) while encoding operation is ongoing.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @param  bitRate is the pointers to array of target bit rates in bits per second unit,
    *           bitRate[n] represents the n-th layer's target bit rate.
    *   @return true for correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVUpdateBitRate(VideoEncControls *encCtrl, Int *bitRate);           /* for 2-way */


    /**
    *   @brief  updates the INTRA frame refresh interval while encoding operation is ongoing.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @param  aIFramePeriod is a new value of INTRA frame interval in the unit of number of coded frames.
    *   @return true for correct operation; false if error happens
    */

    OSCL_IMPORT_REF Bool    PVUpdateIFrameInterval(VideoEncControls *encCtrl, Int aIFramePeriod);/* for 2-way */

    /**
    *   @brief  specifies the number Intra MBs to be refreshed
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @param  numMB is the number of Intra MBs to be refreshed
    *   @return true for correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVUpdateNumIntraMBRefresh(VideoEncControls *encCtrl, Int numMB);  /* for 2-way */

    /**
    *   @brief  This function is called whenever users want the next base frame to be encoded as an I-Vop.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @return true for correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVIFrameRequest(VideoEncControls *encCtrl);                         /* for 2-way */

#endif // LIMITED_API

    /* finishing encoder */
    /**
    *   @brief  This function frees up all the memory allocated by the encoder library.
    *   @param  encCtrl is video encoder control structure that is always passed as input in all APIs
    *   @return true for correct operation; false if error happens
    */
    OSCL_IMPORT_REF Bool    PVCleanUpVideoEncoder(VideoEncControls *encCtrl);

#ifdef __cplusplus
}
#endif
#endif /* _MP4ENC_API_H_ */

