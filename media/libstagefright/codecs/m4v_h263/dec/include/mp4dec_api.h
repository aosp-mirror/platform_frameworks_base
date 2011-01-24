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
#ifndef _MP4DEC_API_H_
#define _MP4DEC_API_H_

#include "m4vh263_decoder_pv_types.h"

#define PV_TOLERATE_VOL_ERRORS
#define PV_MEMORY_POOL

#ifndef _PV_TYPES_
#define _PV_TYPES_

typedef uint Bool;

#define PV_CODEC_INIT  0
#define PV_CODEC_STOP  1
#endif

#define PV_TRUE  1
#define PV_FALSE 0

/* flag for post-processing  4/25/00 */

#ifdef DEC_NOPOSTPROC
#undef PV_POSTPROC_ON   /* enable compilation of post-processing code */
#else
#define PV_POSTPROC_ON
#endif

#define PV_NO_POST_PROC 0
#define PV_DEBLOCK 1
#define PV_DERING  2



#include "visual_header.h" // struct VolInfo is defined


/**@name Structure and Data Types
 * These type definitions specify the input / output from the PVMessage
 * library.
 */

/*@{*/
/* The application has to allocate space for this structure */
typedef struct tagOutputFrame
{
    uint8       *data;          /* pointer to output YUV buffer */
    uint32      timeStamp;      /* time stamp */
} OutputFrame;

typedef struct tagApplicationData
{
    int layer;          /* current video layer */
    void *object;       /* some optional data field */
} applicationData;

/* Application controls, this structed shall be allocated */
/*    and initialized in the application.                 */
typedef struct tagvideoDecControls
{
    /* The following fucntion pointer is copied to BitstreamDecVideo structure  */
    /*    upon initialization and never used again. */
    int (*readBitstreamData)(uint8 *buf, int nbytes_required, void *appData);
    applicationData appData;

    uint8 *outputFrame;
    void *videoDecoderData;     /* this is an internal pointer that is only used */
    /* in the decoder library.   */
#ifdef PV_MEMORY_POOL
    int32 size;
#endif
    int nLayers;
    /* pointers to VOL data for frame-based decoding. */
    uint8 *volbuf[2];           /* maximum of 2 layers for now */
    int32 volbuf_size[2];

} VideoDecControls;

typedef enum
{
    H263_MODE = 0, MPEG4_MODE, UNKNOWN_MODE
} MP4DecodingMode;

typedef enum
{
    MP4_I_FRAME, MP4_P_FRAME, MP4_B_FRAME, MP4_BAD_FRAME
} MP4FrameType;

typedef struct tagVopHeaderInfo
{
    int     currLayer;
    uint32  timestamp;
    MP4FrameType    frameType;
    int     refSelCode;
    int16       quantizer;
} VopHeaderInfo;

/*--------------------------------------------------------------------------*
 * VideoRefCopyInfo:
 * OMAP DSP specific typedef structure, to support the user (ARM) copying
 * of a Reference Frame into the Video Decoder.
 *--------------------------------------------------------------------------*/
typedef struct tagVideoRefCopyInfoPtr
{
    uint8   *yChan;             /* The Y component frame the user can copy a new reference to */
    uint8   *uChan;             /* The U component frame the user can copy a new reference to */
    uint8   *vChan;             /* The V component frame the user can copy a new reference to */
    uint8   *currentVop;        /* The Vop for video the user can copy a new reference to */
} VideoRefCopyInfoPtr;

typedef struct tagVideoRefCopyInfoData
{
    int16   width;              /* Width */
    int16   height;             /* Height */
    int16   realWidth;          /* Non-padded width, not a multiple of 16. */
    int16   realHeight;         /* Non-padded height, not a multiple of 16. */
} VideoRefCopyInfoData;

typedef struct tagVideoRefCopyInfo
{
    VideoRefCopyInfoData data;
    VideoRefCopyInfoPtr ptrs;
} VideoRefCopyInfo;

/*@}*/

#ifdef __cplusplus
extern "C"
{
#endif


    OSCL_IMPORT_REF Bool    PVInitVideoDecoder(VideoDecControls *decCtrl, uint8 *volbuf[], int32 *volbuf_size, int nLayers, int width, int height, MP4DecodingMode mode);
    Bool    PVAllocVideoData(VideoDecControls *decCtrl, int width, int height, int nLayers);
    OSCL_IMPORT_REF Bool    PVCleanUpVideoDecoder(VideoDecControls *decCtrl);
    Bool    PVResetVideoDecoder(VideoDecControls *decCtrl);
    OSCL_IMPORT_REF void    PVSetReferenceYUV(VideoDecControls *decCtrl, uint8 *refYUV);
    Bool    PVDecSetReference(VideoDecControls *decCtrl, uint8 *refYUV, uint32 timestamp);
    Bool    PVDecSetEnhReference(VideoDecControls *decCtrl, uint8 *refYUV, uint32 timestamp);
    OSCL_IMPORT_REF Bool    PVDecodeVideoFrame(VideoDecControls *decCtrl, uint8 *bitstream[], uint32 *timestamp, int32 *buffer_size, uint use_ext_timestamp[], uint8* currYUV);
    Bool    PVDecodeVopHeader(VideoDecControls *decCtrl, uint8 *buffer[], uint32 timestamp[], int32 buffer_size[], VopHeaderInfo *header_info, uint use_ext_timestamp[], uint8 *currYUV);
    Bool    PVDecodeVopBody(VideoDecControls *decCtrl, int32 buffer_size[]);
    void    PVDecPostProcess(VideoDecControls *decCtrl, uint8 *outputYUV);
    OSCL_IMPORT_REF void    PVGetVideoDimensions(VideoDecControls *decCtrl, int32 *display_width, int32 *display_height);
    OSCL_IMPORT_REF void    PVGetBufferDimensions(VideoDecControls *decCtrl, int32 *buf_width, int32 *buf_height);
    OSCL_IMPORT_REF void    PVSetPostProcType(VideoDecControls *decCtrl, int mode);
    uint32  PVGetVideoTimeStamp(VideoDecControls *decoderControl);
    int     PVGetDecBitrate(VideoDecControls *decCtrl);
    int     PVGetDecFramerate(VideoDecControls *decCtrl);
    uint8   *PVGetDecOutputFrame(VideoDecControls *decCtrl);
    int     PVGetLayerID(VideoDecControls *decCtrl);
    int32   PVGetDecMemoryUsage(VideoDecControls *decCtrl);
    OSCL_IMPORT_REF MP4DecodingMode PVGetDecBitstreamMode(VideoDecControls *decCtrl);
    Bool    PVExtractVolHeader(uint8 *video_buffer, uint8 *vol_header, int32 *vol_header_size);
    int32   PVLocateFrameHeader(uint8 *video_buffer, int32 vop_size);
    int32   PVLocateH263FrameHeader(uint8 *video_buffer, int32 vop_size);
    Bool    PVGetVolInfo(VideoDecControls *decCtrl, VolInfo *pVolInfo); // BX 6/24/04
    Bool    IsIntraFrame(VideoDecControls *decoderControl);

#ifdef __cplusplus
}
#endif
#endif /* _MP4DEC_API_H_ */

