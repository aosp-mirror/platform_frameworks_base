/*
 * Copyright (C) 2009 The Android Open Source Project
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

/*------------------------------------------------------------------------------

    Table of contents

    1. Include Headers

    2. Enumerations used as a return value or a parameter.
        2.1. API's return value enumerations.

    3. User Structures
        3.1. Structures for H264SwDecDecode() parameters.
        3.2. Structures for information interchange with
             DEC API and user application.

    4. Prototypes of Decoder API functions

------------------------------------------------------------------------------*/

#ifndef H264SWDECAPI_H
#define H264SWDECAPI_H

#ifdef __cplusplus
extern "C"
{
#endif

/*------------------------------------------------------------------------------
    1. Include Headers
------------------------------------------------------------------------------*/

    #include "basetype.h"

/*------------------------------------------------------------------------------
    2.1. API's return value enumerations.
------------------------------------------------------------------------------*/

    typedef enum
    {
        H264SWDEC_OK = 0,
        H264SWDEC_STRM_PROCESSED = 1,
        H264SWDEC_PIC_RDY,
        H264SWDEC_PIC_RDY_BUFF_NOT_EMPTY,
        H264SWDEC_HDRS_RDY_BUFF_NOT_EMPTY,
        H264SWDEC_PARAM_ERR = -1,
        H264SWDEC_STRM_ERR = -2,
        H264SWDEC_NOT_INITIALIZED = -3,
        H264SWDEC_MEMFAIL = -4,
        H264SWDEC_INITFAIL = -5,
        H264SWDEC_HDRS_NOT_RDY = -6,
        H264SWDEC_EVALUATION_LIMIT_EXCEEDED = -7
    } H264SwDecRet;

/*------------------------------------------------------------------------------
    3.1. Structures for H264SwDecDecode() parameters.
------------------------------------------------------------------------------*/

    /* typedef of the Decoder instance */
    typedef void *H264SwDecInst;

    /* Input structure */
    typedef struct
    {
        u8  *pStream;            /* Pointer to stream to be decoded          */
        u32  dataLen;            /* Number of bytes to be decoded            */
        u32  picId;              /* Identifier for the picture to be decoded */
        u32 intraConcealmentMethod; /* 0 = Gray concealment for intra
                                       1 = Reference concealment for intra */

    } H264SwDecInput;


    /* Output structure */
    typedef struct
    {
        u8  *pStrmCurrPos;      /* Pointer to stream position where decoder
                                   ended up */
    } H264SwDecOutput;

    /* Output structure for H264SwDecNextPicture */
    typedef struct
    {
        u32 *pOutputPicture;    /* Pointer to the picture, YUV format       */
        u32 picId;              /* Identifier of the picture to be displayed*/
        u32 isIdrPicture;       /* Flag to indicate if the picture is an
                                   IDR picture */
        u32 nbrOfErrMBs;        /* Number of concealed MB's in the picture  */
    } H264SwDecPicture;

/*------------------------------------------------------------------------------
    3.2. Structures for information interchange with DEC API
         and user application.
------------------------------------------------------------------------------*/

    typedef struct
    {
        u32 cropLeftOffset;
        u32 cropOutWidth;
        u32 cropTopOffset;
        u32 cropOutHeight;
    } CropParams;

    typedef struct
    {
        u32 profile;
        u32 picWidth;
        u32 picHeight;
        u32 videoRange;
        u32 matrixCoefficients;
        u32 parWidth;
        u32 parHeight;
        u32 croppingFlag;
        CropParams cropParams;
    } H264SwDecInfo;

    /* Version information */
    typedef struct
    {
        u32 major;    /* Decoder API major version */
        u32 minor;    /* Dncoder API minor version */
    } H264SwDecApiVersion;

/*------------------------------------------------------------------------------
    4. Prototypes of Decoder API functions
------------------------------------------------------------------------------*/

    H264SwDecRet H264SwDecDecode(H264SwDecInst      decInst,
                                 H264SwDecInput     *pInput,
                                 H264SwDecOutput    *pOutput);

    H264SwDecRet H264SwDecInit(H264SwDecInst *decInst,
                               u32            noOutputReordering);

    H264SwDecRet H264SwDecNextPicture(H264SwDecInst     decInst,
                                      H264SwDecPicture *pOutput,
                                      u32               endOfStream);

    H264SwDecRet H264SwDecGetInfo(H264SwDecInst decInst,
                                  H264SwDecInfo *pDecInfo);

    void  H264SwDecRelease(H264SwDecInst decInst);

    H264SwDecApiVersion H264SwDecGetAPIVersion(void);

    /* function prototype for API trace */
    void H264SwDecTrace(char *);

    /* function prototype for memory allocation */
    void* H264SwDecMalloc(u32 size);

    /* function prototype for memory free */
    void H264SwDecFree(void *ptr);

    /* function prototype for memory copy */
    void H264SwDecMemcpy(void *dest, void *src, u32 count);

    /* function prototype for memset */
    void H264SwDecMemset(void *ptr, i32 value, u32 count);


#ifdef __cplusplus
}
#endif

#endif /* H264SWDECAPI_H */












