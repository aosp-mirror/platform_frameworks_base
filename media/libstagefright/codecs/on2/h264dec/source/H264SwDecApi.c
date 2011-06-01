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

     1. Include headers
     2. External compiler flags
     3. Module defines
     4. Local function prototypes
     5. Functions
          H264SwDecInit
          H264SwDecGetInfo
          H264SwDecRelease
          H264SwDecDecode
          H264SwDecGetAPIVersion
          H264SwDecNextPicture

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/
#include <stdlib.h>
#include "basetype.h"
#include "h264bsd_container.h"
#include "H264SwDecApi.h"
#include "h264bsd_decoder.h"
#include "h264bsd_util.h"

/*------------------------------------------------------------------------------
       Version Information
------------------------------------------------------------------------------*/

#define H264SWDEC_MAJOR_VERSION 2
#define H264SWDEC_MINOR_VERSION 3

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

H264DEC_TRACE           Trace H264 Decoder API function calls.
H264DEC_EVALUATION      Compile evaluation version, restricts number of frames
                        that can be decoded

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

#ifdef H264DEC_TRACE
#include <stdio.h>
#define DEC_API_TRC(str)    H264SwDecTrace(str)
#else
#define DEC_API_TRC(str)
#endif

#ifdef H264DEC_EVALUATION
#define H264DEC_EVALUATION_LIMIT   500
#endif

void H264SwDecTrace(char *string) {
}

void* H264SwDecMalloc(u32 size) {
    return malloc(size);
}

void H264SwDecFree(void *ptr) {
    free(ptr);
}

void H264SwDecMemcpy(void *dest, void *src, u32 count) {
    memcpy(dest, src, count);
}

void H264SwDecMemset(void *ptr, i32 value, u32 count) {
    memset(ptr, value, count);
}


/*------------------------------------------------------------------------------

    Function: H264SwDecInit()

        Functional description:
            Initialize decoder software. Function reserves memory for the
            decoder instance and calls h264bsdInit to initialize the
            instance data.

        Inputs:
            noOutputReordering  flag to indicate decoder that it doesn't have
                                to try to provide output pictures in display
                                order, saves memory

        Outputs:
            decInst             pointer to initialized instance is stored here

        Returns:
            H264SWDEC_OK        successfully initialized the instance
            H264SWDEC_INITFAIL  initialization failed
            H264SWDEC_PARAM_ERR invalid parameters
            H264SWDEC_MEM_FAIL  memory allocation failed

------------------------------------------------------------------------------*/

H264SwDecRet H264SwDecInit(H264SwDecInst *decInst, u32 noOutputReordering)
{
    u32 rv = 0;

    decContainer_t *pDecCont;

    DEC_API_TRC("H264SwDecInit#");

    /* check that right shift on negative numbers is performed signed */
    /*lint -save -e* following check causes multiple lint messages */
    if ( ((-1)>>1) != (-1) )
    {
        DEC_API_TRC("H264SwDecInit# ERROR: Right shift is not signed");
        return(H264SWDEC_INITFAIL);
    }
    /*lint -restore */

    if (decInst == NULL)
    {
        DEC_API_TRC("H264SwDecInit# ERROR: decInst == NULL");
        return(H264SWDEC_PARAM_ERR);
    }

    pDecCont = (decContainer_t *)H264SwDecMalloc(sizeof(decContainer_t));

    if (pDecCont == NULL)
    {
        DEC_API_TRC("H264SwDecInit# ERROR: Memory allocation failed");
        return(H264SWDEC_MEMFAIL);
    }

#ifdef H264DEC_TRACE
    sprintf(pDecCont->str, "H264SwDecInit# decInst %p noOutputReordering %d",
            (void*)decInst, noOutputReordering);
    DEC_API_TRC(pDecCont->str);
#endif

    rv = h264bsdInit(&pDecCont->storage, noOutputReordering);
    if (rv != HANTRO_OK)
    {
        H264SwDecRelease(pDecCont);
        return(H264SWDEC_MEMFAIL);
    }

    pDecCont->decStat  = INITIALIZED;
    pDecCont->picNumber = 0;

#ifdef H264DEC_TRACE
    sprintf(pDecCont->str, "H264SwDecInit# OK: return %p", (void*)pDecCont);
    DEC_API_TRC(pDecCont->str);
#endif

    *decInst = (decContainer_t *)pDecCont;

    return(H264SWDEC_OK);

}

/*------------------------------------------------------------------------------

    Function: H264SwDecGetInfo()

        Functional description:
            This function provides read access to decoder information. This
            function should not be called before H264SwDecDecode function has
            indicated that headers are ready.

        Inputs:
            decInst     decoder instance

        Outputs:
            pDecInfo    pointer to info struct where data is written

        Returns:
            H264SWDEC_OK            success
            H264SWDEC_PARAM_ERR     invalid parameters
            H264SWDEC_HDRS_NOT_RDY  information not available yet

------------------------------------------------------------------------------*/

H264SwDecRet H264SwDecGetInfo(H264SwDecInst decInst, H264SwDecInfo *pDecInfo)
{

    storage_t *pStorage;

    DEC_API_TRC("H264SwDecGetInfo#");

    if (decInst == NULL || pDecInfo == NULL)
    {
        DEC_API_TRC("H264SwDecGetInfo# ERROR: decInst or pDecInfo is NULL");
        return(H264SWDEC_PARAM_ERR);
    }

    pStorage = &(((decContainer_t *)decInst)->storage);

    if (pStorage->activeSps == NULL || pStorage->activePps == NULL)
    {
        DEC_API_TRC("H264SwDecGetInfo# ERROR: Headers not decoded yet");
        return(H264SWDEC_HDRS_NOT_RDY);
    }

#ifdef H264DEC_TRACE
    sprintf(((decContainer_t*)decInst)->str,
        "H264SwDecGetInfo# decInst %p  pDecInfo %p", decInst, (void*)pDecInfo);
    DEC_API_TRC(((decContainer_t*)decInst)->str);
#endif

    /* h264bsdPicWidth and -Height return dimensions in macroblock units,
     * picWidth and -Height in pixels */
    pDecInfo->picWidth        = h264bsdPicWidth(pStorage) << 4;
    pDecInfo->picHeight       = h264bsdPicHeight(pStorage) << 4;
    pDecInfo->videoRange      = h264bsdVideoRange(pStorage);
    pDecInfo->matrixCoefficients = h264bsdMatrixCoefficients(pStorage);

    h264bsdCroppingParams(pStorage,
        &pDecInfo->croppingFlag,
        &pDecInfo->cropParams.cropLeftOffset,
        &pDecInfo->cropParams.cropOutWidth,
        &pDecInfo->cropParams.cropTopOffset,
        &pDecInfo->cropParams.cropOutHeight);

    /* sample aspect ratio */
    h264bsdSampleAspectRatio(pStorage,
                             &pDecInfo->parWidth,
                             &pDecInfo->parHeight);

    /* profile */
    pDecInfo->profile = h264bsdProfile(pStorage);

    DEC_API_TRC("H264SwDecGetInfo# OK");

    return(H264SWDEC_OK);

}

/*------------------------------------------------------------------------------

    Function: H264SwDecRelease()

        Functional description:
            Release the decoder instance. Function calls h264bsdShutDown to
            release instance data and frees the memory allocated for the
            instance.

        Inputs:
            decInst     Decoder instance

        Outputs:
            none

        Returns:
            none

------------------------------------------------------------------------------*/

void H264SwDecRelease(H264SwDecInst decInst)
{

    decContainer_t *pDecCont;

    DEC_API_TRC("H264SwDecRelease#");

    if (decInst == NULL)
    {
        DEC_API_TRC("H264SwDecRelease# ERROR: decInst == NULL");
        return;
    }

    pDecCont = (decContainer_t*)decInst;

#ifdef H264DEC_TRACE
    sprintf(pDecCont->str, "H264SwDecRelease# decInst %p",decInst);
    DEC_API_TRC(pDecCont->str);
#endif

    h264bsdShutdown(&pDecCont->storage);

    H264SwDecFree(pDecCont);

}

/*------------------------------------------------------------------------------

    Function: H264SwDecDecode

        Functional description:
            Decode stream data. Calls h264bsdDecode to do the actual decoding.

        Input:
            decInst     decoder instance
            pInput      pointer to input struct

        Outputs:
            pOutput     pointer to output struct

        Returns:
            H264SWDEC_NOT_INITIALIZED   decoder instance not initialized yet
            H264SWDEC_PARAM_ERR         invalid parameters

            H264SWDEC_STRM_PROCESSED    stream buffer decoded
            H264SWDEC_HDRS_RDY_BUFF_NOT_EMPTY   headers decoded,
                                                stream buffer not finished
            H264SWDEC_PIC_RDY                   decoding of a picture finished
            H264SWDEC_PIC_RDY_BUFF_NOT_EMPTY    decoding of a picture finished,
                                                stream buffer not finished
            H264SWDEC_STRM_ERR                  serious error in decoding, no
                                                valid parameter sets available
                                                to decode picture data
            H264SWDEC_EVALUATION_LIMIT_EXCEEDED this can only occur when
                                                evaluation version is used,
                                                max number of frames reached

------------------------------------------------------------------------------*/

H264SwDecRet H264SwDecDecode(H264SwDecInst decInst, H264SwDecInput *pInput,
                  H264SwDecOutput *pOutput)
{

    decContainer_t *pDecCont;
    u32 strmLen;
    u32 numReadBytes;
    u8 *tmpStream;
    u32 decResult = 0;
    H264SwDecRet returnValue = H264SWDEC_STRM_PROCESSED;

    DEC_API_TRC("H264SwDecDecode#");

    /* Check that function input parameters are valid */
    if (pInput == NULL || pOutput == NULL)
    {
        DEC_API_TRC("H264SwDecDecode# ERROR: pInput or pOutput is NULL");
        return(H264SWDEC_PARAM_ERR);
    }

    if ((pInput->pStream == NULL) || (pInput->dataLen == 0))
    {
        DEC_API_TRC("H264SwDecDecode# ERROR: Invalid input parameters");
        return(H264SWDEC_PARAM_ERR);
    }

    pDecCont = (decContainer_t *)decInst;

    /* Check if decoder is in an incorrect mode */
    if (decInst == NULL || pDecCont->decStat == UNINITIALIZED)
    {
        DEC_API_TRC("H264SwDecDecode# ERROR: Decoder not initialized");
        return(H264SWDEC_NOT_INITIALIZED);
    }

#ifdef H264DEC_EVALUATION
    if (pDecCont->picNumber >= H264DEC_EVALUATION_LIMIT)
        return(H264SWDEC_EVALUATION_LIMIT_EXCEEDED);
#endif

#ifdef H264DEC_TRACE
    sprintf(pDecCont->str, "H264SwDecDecode# decInst %p  pInput %p  pOutput %p",
            decInst, (void*)pInput, (void*)pOutput);
    DEC_API_TRC(pDecCont->str);
#endif

    pOutput->pStrmCurrPos   = NULL;

    numReadBytes = 0;
    strmLen = pInput->dataLen;
    tmpStream = pInput->pStream;
    pDecCont->storage.intraConcealmentFlag = pInput->intraConcealmentMethod;

    do
    {
        /* Return HDRS_RDY after DPB flush caused by new SPS */
        if (pDecCont->decStat == NEW_HEADERS)
        {
            decResult = H264BSD_HDRS_RDY;
            pDecCont->decStat = INITIALIZED;
        }
        else /* Continue decoding normally */
        {
            decResult = h264bsdDecode(&pDecCont->storage, tmpStream, strmLen,
                pInput->picId, &numReadBytes);
        }
        tmpStream += numReadBytes;
        /* check if too many bytes are read from stream */
        if ( (i32)(strmLen - numReadBytes) >= 0 )
            strmLen -= numReadBytes;
        else
            strmLen = 0;

        pOutput->pStrmCurrPos = tmpStream;

        switch (decResult)
        {
            case H264BSD_HDRS_RDY:

                if(pDecCont->storage.dpb->flushed &&
                   pDecCont->storage.dpb->numOut !=
                   pDecCont->storage.dpb->outIndex)
                {
                    /* output first all DPB stored pictures
                     * DPB flush caused by new SPS */
                    pDecCont->storage.dpb->flushed = 0;
                    pDecCont->decStat = NEW_HEADERS;
                    returnValue = H264SWDEC_PIC_RDY_BUFF_NOT_EMPTY;
                    strmLen = 0;
                }
                else
                {
                    returnValue = H264SWDEC_HDRS_RDY_BUFF_NOT_EMPTY;
                    strmLen = 0;
                }
                break;

            case H264BSD_PIC_RDY:
                pDecCont->picNumber++;

                if (strmLen == 0)
                    returnValue = H264SWDEC_PIC_RDY;
                else
                    returnValue = H264SWDEC_PIC_RDY_BUFF_NOT_EMPTY;

                strmLen = 0;
                break;

            case H264BSD_PARAM_SET_ERROR:
                if ( !h264bsdCheckValidParamSets(&pDecCont->storage) &&
                     strmLen == 0 )
                {
                    returnValue = H264SWDEC_STRM_ERR;
                }
                break;
            case H264BSD_MEMALLOC_ERROR:
                {
                    returnValue = H264SWDEC_MEMFAIL;
                    strmLen = 0;
                }
                break;
            default:
                break;
        }

    } while (strmLen);

#ifdef H264DEC_TRACE
    sprintf(pDecCont->str, "H264SwDecDecode# OK: DecResult %d",
            returnValue);
    DEC_API_TRC(pDecCont->str);
#endif

    return(returnValue);

}

/*------------------------------------------------------------------------------

    Function: H264SwDecGetAPIVersion

        Functional description:
            Return version information of the API

        Inputs:
            none

        Outputs:
            none

        Returns:
            API version

------------------------------------------------------------------------------*/

H264SwDecApiVersion H264SwDecGetAPIVersion()
{
    H264SwDecApiVersion ver;

    ver.major = H264SWDEC_MAJOR_VERSION;
    ver.minor = H264SWDEC_MINOR_VERSION;

    return(ver);
}

/*------------------------------------------------------------------------------

    Function: H264SwDecNextPicture

        Functional description:
            Get next picture in display order if any available.

        Input:
            decInst     decoder instance.
            flushBuffer force output of all buffered pictures

        Output:
            pOutput     pointer to output structure

        Returns:
            H264SWDEC_OK            no pictures available for display
            H264SWDEC_PIC_RDY       picture available for display
            H264SWDEC_PARAM_ERR     invalid parameters

------------------------------------------------------------------------------*/

H264SwDecRet H264SwDecNextPicture(H264SwDecInst decInst,
    H264SwDecPicture *pOutput, u32 flushBuffer)
{

    decContainer_t *pDecCont;
    u32 numErrMbs, isIdrPic, picId;
    u32 *pOutPic;

    DEC_API_TRC("H264SwDecNextPicture#");

    if (decInst == NULL || pOutput == NULL)
    {
        DEC_API_TRC("H264SwDecNextPicture# ERROR: decInst or pOutput is NULL");
        return(H264SWDEC_PARAM_ERR);
    }

    pDecCont = (decContainer_t*)decInst;

#ifdef H264DEC_TRACE
    sprintf(pDecCont->str, "H264SwDecNextPicture# decInst %p pOutput %p %s %d",
            decInst, (void*)pOutput, "flushBuffer", flushBuffer);
    DEC_API_TRC(pDecCont->str);
#endif

    if (flushBuffer)
        h264bsdFlushBuffer(&pDecCont->storage);

    pOutPic = (u32*)h264bsdNextOutputPicture(&pDecCont->storage, &picId,
                                             &isIdrPic, &numErrMbs);

    if (pOutPic == NULL)
    {
        DEC_API_TRC("H264SwDecNextPicture# OK: return H264SWDEC_OK");
        return(H264SWDEC_OK);
    }
    else
    {
        pOutput->pOutputPicture = pOutPic;
        pOutput->picId          = picId;
        pOutput->isIdrPicture   = isIdrPic;
        pOutput->nbrOfErrMBs    = numErrMbs;
        DEC_API_TRC("H264SwDecNextPicture# OK: return H264SWDEC_PIC_RDY");
        return(H264SWDEC_PIC_RDY);
    }

}


