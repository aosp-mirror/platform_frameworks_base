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
#include "VideoEditorVideoDecoder.h"
#include "VideoEditor3gpReader.h"

#include <utils/Log.h>
#include "VideoBrowserInternal.h"
#include "LVOSA_FileReader_optim.h"

//#define M4OSA_TRACE_LEVEL 1
#if (M4OSA_TRACE_LEVEL >= 1)
#undef M4OSA_TRACE1_0
#undef M4OSA_TRACE1_1
#undef M4OSA_TRACE1_2
#undef M4OSA_TRACE1_3

#define M4OSA_TRACE1_0(a)       __android_log_print(ANDROID_LOG_INFO, "Thumbnail", a);
#define M4OSA_TRACE1_1(a,b)     __android_log_print(ANDROID_LOG_INFO, "Thumbnail", a,b);
#define M4OSA_TRACE1_2(a,b,c)   __android_log_print(ANDROID_LOG_INFO, "Thumbnail", a,b,c);
#define M4OSA_TRACE1_3(a,b,c,d) __android_log_print(ANDROID_LOG_INFO, "Thumbnail", a,b,c,d);
#endif

/******************************************************************************
 * M4OSA_ERR     videoBrowserSetWindow(
 *          M4OSA_Context pContext, M4OSA_UInt32 x,
 *          M4OSA_UInt32 y, M4OSA_UInt32 dx, M4OSA_UInt32 dy);
 * @brief        This function sets the size and the position of the display.
 * @param        pContext       (IN) : Video Browser context
 * @param        pPixelArray    (IN) : Array to hold the video frame.
 * @param        x              (IN) : Horizontal position of the top left
 *                                     corner
 * @param        y              (IN) : Vertical position of the top left corner
 * @param        dx             (IN) : Width of the display window
 * @param        dy             (IN) : Height of the video window
 * @return       M4NO_ERROR / M4ERR_PARAMETER / M4ERR_STATE / M4ERR_ALLOC
 ******************************************************************************/
M4OSA_ERR videoBrowserSetWindow(
        M4OSA_Context pContext,
        M4OSA_Int32 *pPixelArray,
        M4OSA_UInt32 x, M4OSA_UInt32 y,
        M4OSA_UInt32 dx, M4OSA_UInt32 dy)
{
    VideoBrowserContext* pC = (VideoBrowserContext*)pContext;
    M4OSA_ERR err = M4NO_ERROR;

    M4OSA_TRACE2_5("videoBrowserSetWindow: entering with 0x%x %d %d %d %d ",
            pContext, x, y, dx, dy);

    /*--- Sanity checks ---*/
    CHECK_PTR(videoBrowserSetWindow, pContext, err, M4ERR_PARAMETER);
    CHECK_PTR(videoBrowserSetWindow, pPixelArray, err, M4ERR_PARAMETER);
    CHECK_STATE(videoBrowserSetWindow, VideoBrowser_kVBOpened, pC);

    pC->m_outputPlane[0].u_topleft = 0;

    pC->m_outputPlane[0].u_height = dy;
    pC->m_outputPlane[0].u_width = dx;
    pC->m_x = x;
    pC->m_y = y;

    if (pC->m_frameColorType == VideoBrowser_kGB565) {
        pC->m_outputPlane[0].u_stride = pC->m_outputPlane[0].u_width << 1;
        pC->m_outputPlane[0].pac_data = (M4OSA_UInt8*)M4OSA_32bitAlignedMalloc(
            pC->m_outputPlane[0].u_stride * pC->m_outputPlane[0].u_height,
            VIDEOBROWSER, (M4OSA_Char *)"output plane");

        CHECK_PTR(videoBrowserSetWindow,
            pC->m_outputPlane[0].pac_data, err, M4ERR_ALLOC);
    }
    else if (pC->m_frameColorType == VideoBrowser_kYUV420) {
        pC->m_outputPlane[0].u_stride = pC->m_outputPlane[0].u_width;
        pC->m_outputPlane[1].u_height = pC->m_outputPlane[0].u_height >> 1;
        pC->m_outputPlane[1].u_width = pC->m_outputPlane[0].u_width >> 1;
        pC->m_outputPlane[1].u_topleft = 0;
        pC->m_outputPlane[1].u_stride = pC->m_outputPlane[1].u_width;

        pC->m_outputPlane[2].u_height = pC->m_outputPlane[0].u_height >> 1;
        pC->m_outputPlane[2].u_width = pC->m_outputPlane[0].u_width >> 1;
        pC->m_outputPlane[2].u_topleft = 0;
        pC->m_outputPlane[2].u_stride = pC->m_outputPlane[2].u_width;

        pC->m_outputPlane[0].pac_data = (M4OSA_UInt8*)pPixelArray;

        CHECK_PTR(videoBrowserSetWindow,
            pC->m_outputPlane[0].pac_data, err, M4ERR_ALLOC);

        pC->m_outputPlane[1].pac_data =
            pC->m_outputPlane[0].pac_data +
            (pC->m_outputPlane[0].u_stride * pC->m_outputPlane[0].u_height);

        pC->m_outputPlane[2].pac_data =
            pC->m_outputPlane[1].pac_data +
            (pC->m_outputPlane[1].u_stride * pC->m_outputPlane[1].u_height);
    }


    M4OSA_TRACE2_0("videoBrowserSetWindow returned NO ERROR");
    return M4NO_ERROR;

videoBrowserSetWindow_cleanUp:

    M4OSA_TRACE2_1("videoBrowserSetWindow returned 0x%x", err);
    return err;
}

/******************************************************************************
* @brief  This function allocates the resources needed for browsing a video file
* @param   ppContext     (OUT): Pointer on a context filled by this function.
* @param   pURL          (IN) : Path of File to browse
* @param   DrawMode      (IN) : Indicate which method is used to draw (Direct draw etc...)
* @param   pfCallback    (IN) : Callback function to be called when a frame must be displayed
* @param   pCallbackData (IN) : User defined data that will be passed as parameter of the callback
* @param   clrType       (IN) : Required color type.
* @return  M4NO_ERROR / M4ERR_PARAMETER / M4ERR_STATE / M4ERR_ALLOC
******************************************************************************/
M4OSA_ERR videoBrowserCreate(
        M4OSA_Context* ppContext,
        M4OSA_Char* pURL,
        M4OSA_UInt32 DrawMode,
        M4OSA_FileReadPointer* ptrF,
        videoBrowser_Callback pfCallback,
        M4OSA_Void* pCallbackData,
        VideoBrowser_VideoColorType clrType)
{
    VideoBrowserContext* pContext = M4OSA_NULL;
    M4READER_MediaFamily mediaFamily = M4READER_kMediaFamilyUnknown;
    M4_StreamHandler* pStreamHandler = M4OSA_NULL;
    M4_VideoStreamHandler* pVideoStreamHandler = M4OSA_NULL;
    M4DECODER_VideoType decoderType;
    M4DECODER_OutputFilter FilterOption;

    M4OSA_Bool deb = M4OSA_TRUE;
    M4OSA_ERR err = M4NO_ERROR;

    M4OSA_TRACE1_2(
        "videoBrowserCreate: entering with 0x%x 0x%x", ppContext, pURL);

    /*--- Sanity checks ---*/
    CHECK_PTR(videoBrowserCreate, ppContext, err, M4ERR_PARAMETER);
    *ppContext = M4OSA_NULL ;
    CHECK_PTR(videoBrowserCreate, pURL,  err, M4ERR_PARAMETER);

    /*--- Create context ---*/
    pContext = (VideoBrowserContext*)M4OSA_32bitAlignedMalloc(
            sizeof(VideoBrowserContext),
            VIDEOBROWSER, (M4OSA_Char*)"Video browser context");

    CHECK_PTR(videoBrowserCreate, pContext,err, M4ERR_ALLOC);
    memset((void *)pContext, 0,sizeof(VideoBrowserContext));

    /*--- Initialize the context parameters ---*/
    pContext->m_state = VideoBrowser_kVBCreating ;
    pContext->m_frameColorType = clrType;

    /*--- Copy the file reader functions ---*/
    memcpy((void *)&pContext->m_fileReadPtr,
                 (void *)ptrF,
                 sizeof(M4OSA_FileReadPointer)) ;

    /* PR#SP00013 DGR bug 13 : first frame is not visible */
    pContext->m_drawmode = DrawMode;


    /* Retrieve the 3gp reader interface */
    VideoEditor3gpReader_getInterface(&pContext->m_mediaType,
        &pContext->m_3gpReader, &pContext->m_3gpData);

    CHECK_PTR(videoBrowserCreate, pContext->m_3gpReader,  err, M4ERR_ALLOC);
    CHECK_PTR(videoBrowserCreate, pContext->m_3gpData,    err, M4ERR_ALLOC);

    /*--- Create the file reader ---*/
    err = pContext->m_3gpReader->m_pFctCreate(&pContext->m_pReaderCtx);
    CHECK_ERR(videoBrowserCreate, err);
    CHECK_PTR(videoBrowserCreate, pContext->m_pReaderCtx, err, M4ERR_ALLOC);
    pContext->m_3gpData->m_readerContext = pContext->m_pReaderCtx;

    /*--- Set the OSAL file reader functions ---*/
    err = pContext->m_3gpReader->m_pFctSetOption(
            pContext->m_pReaderCtx,
            M4READER_kOptionID_SetOsaFileReaderFctsPtr,
            (M4OSA_DataOption)(&pContext->m_fileReadPtr));

    CHECK_ERR(videoBrowserCreate, err) ;

    /*--- Open the file ---*/
    err = pContext->m_3gpReader->m_pFctOpen(pContext->m_pReaderCtx, pURL);
    CHECK_ERR(videoBrowserCreate, err) ;

    /*--- Try to find a video stream ---*/
    while (err == M4NO_ERROR)
    {
        err = pContext->m_3gpReader->m_pFctGetNextStream(
                pContext->m_pReaderCtx, &mediaFamily, &pStreamHandler);

        /*in case we found a bifs stream or something else...*/
        if ((err == (M4OSA_UInt32)M4ERR_READER_UNKNOWN_STREAM_TYPE) ||
            (err == (M4OSA_UInt32)M4WAR_TOO_MUCH_STREAMS))
        {
            err = M4NO_ERROR;
            continue;
        }

        if (err != M4WAR_NO_MORE_STREAM)
        {
            if (M4READER_kMediaFamilyVideo != mediaFamily)
            {
                err = M4NO_ERROR;
                continue;
            }

            pContext->m_pStreamHandler = pStreamHandler;

            err = pContext->m_3gpReader->m_pFctReset(
                    pContext->m_pReaderCtx, pContext->m_pStreamHandler);

            CHECK_ERR(videoBrowserCreate, err);

            err = pContext->m_3gpReader->m_pFctFillAuStruct(
                    pContext->m_pReaderCtx,
                    pContext->m_pStreamHandler,
                    &pContext->m_accessUnit);

            CHECK_ERR(videoBrowserCreate, err);

            pVideoStreamHandler =
                (M4_VideoStreamHandler*)pContext->m_pStreamHandler;

            switch (pContext->m_pStreamHandler->m_streamType)
            {
                case M4DA_StreamTypeVideoMpeg4:
                case M4DA_StreamTypeVideoH263:
                {
                    pContext->m_pCodecLoaderContext = M4OSA_NULL;
                    decoderType = M4DECODER_kVideoTypeMPEG4;

#ifdef USE_SOFTWARE_DECODER
                        err = VideoEditorVideoDecoder_getSoftwareInterface_MPEG4(
                            &decoderType, &pContext->m_pDecoder);
#else
                        err = VideoEditorVideoDecoder_getInterface_MPEG4(
                            &decoderType, (void **)&pContext->m_pDecoder);
#endif
                    CHECK_ERR(videoBrowserCreate, err) ;

                    err = pContext->m_pDecoder->m_pFctCreate(
                            &pContext->m_pDecoderCtx,
                            pContext->m_pStreamHandler,
                            pContext->m_3gpReader,
                            pContext->m_3gpData,
                            &pContext->m_accessUnit,
                            pContext->m_pCodecLoaderContext) ;

                    CHECK_ERR(videoBrowserCreate, err) ;
                }
                break;

                case M4DA_StreamTypeVideoMpeg4Avc:
                {
                    pContext->m_pCodecLoaderContext = M4OSA_NULL;

                    decoderType = M4DECODER_kVideoTypeAVC;

#ifdef USE_SOFTWARE_DECODER
                        err = VideoEditorVideoDecoder_getSoftwareInterface_H264(
                            &decoderType, &pContext->m_pDecoder);
#else
                        err = VideoEditorVideoDecoder_getInterface_H264(
                            &decoderType, (void **)&pContext->m_pDecoder);
#endif
                   CHECK_ERR(videoBrowserCreate, err) ;

                    err = pContext->m_pDecoder->m_pFctCreate(
                            &pContext->m_pDecoderCtx,
                            pContext->m_pStreamHandler,
                            pContext->m_3gpReader,
                            pContext->m_3gpData,
                            &pContext->m_accessUnit,
                            pContext->m_pCodecLoaderContext) ;

                    CHECK_ERR(videoBrowserCreate, err) ;
                }
                break;

                default:
                    err = M4ERR_VB_MEDIATYPE_NOT_SUPPORTED;
                    goto videoBrowserCreate_cleanUp;
            }
        }
    }

    if (err == M4WAR_NO_MORE_STREAM)
    {
        err = M4NO_ERROR ;
    }

    if (M4OSA_NULL == pContext->m_pStreamHandler)
    {
        err = M4ERR_VB_NO_VIDEO ;
        goto videoBrowserCreate_cleanUp ;
    }

    err = pContext->m_pDecoder->m_pFctSetOption(
            pContext->m_pDecoderCtx,
            M4DECODER_kOptionID_DeblockingFilter,
            (M4OSA_DataOption)&deb);

    if (err == M4WAR_DEBLOCKING_FILTER_NOT_IMPLEMENTED)
    {
        err = M4NO_ERROR;
    }
    CHECK_ERR(videoBrowserCreate, err);

    FilterOption.m_pFilterUserData = M4OSA_NULL;


    if (pContext->m_frameColorType == VideoBrowser_kGB565) {
        FilterOption.m_pFilterFunction =
            (M4OSA_Void*)M4VIFI_ResizeBilinearYUV420toBGR565;
    }
    else if (pContext->m_frameColorType == VideoBrowser_kYUV420) {
        FilterOption.m_pFilterFunction =
            (M4OSA_Void*)M4VIFI_ResizeBilinearYUV420toYUV420;
    }
    else {
        err = M4ERR_PARAMETER;
        goto videoBrowserCreate_cleanUp;
    }

    err = pContext->m_pDecoder->m_pFctSetOption(
            pContext->m_pDecoderCtx,
            M4DECODER_kOptionID_OutputFilter,
            (M4OSA_DataOption)&FilterOption);

    CHECK_ERR(videoBrowserCreate, err);

    /* store the callback details */
    pContext->m_pfCallback = pfCallback;
    pContext->m_pCallbackUserData = pCallbackData;
    /* store the callback details */

    pContext->m_state = VideoBrowser_kVBOpened;
    *ppContext = pContext;

    M4OSA_TRACE1_0("videoBrowserCreate returned NO ERROR");
    return M4NO_ERROR;

videoBrowserCreate_cleanUp:

    if (M4OSA_NULL != pContext)
    {
        if (M4OSA_NULL != pContext->m_pDecoderCtx)
        {
            pContext->m_pDecoder->m_pFctDestroy(pContext->m_pDecoderCtx);
            pContext->m_pDecoderCtx = M4OSA_NULL;
        }

        if (M4OSA_NULL != pContext->m_pReaderCtx)
        {
            pContext->m_3gpReader->m_pFctClose(pContext->m_pReaderCtx);
            pContext->m_3gpReader->m_pFctDestroy(pContext->m_pReaderCtx);
            pContext->m_pReaderCtx = M4OSA_NULL;
        }
        SAFE_FREE(pContext->m_pDecoder);
        SAFE_FREE(pContext->m_3gpReader);
        SAFE_FREE(pContext->m_3gpData);
        SAFE_FREE(pContext);
    }

    M4OSA_TRACE2_1("videoBrowserCreate returned 0x%x", err);
    return err;
}

/******************************************************************************
* M4OSA_ERR     videoBrowserCleanUp(M4OSA_Context pContext);
* @brief        This function frees the resources needed for browsing a
*               video file.
* @param        pContext     (IN) : Video browser context
* @return       M4NO_ERROR / M4ERR_PARAMETER / M4ERR_STATE
******************************************************************************/
M4OSA_ERR videoBrowserCleanUp(M4OSA_Context pContext)
{
    VideoBrowserContext* pC = (VideoBrowserContext*)pContext;
    M4OSA_ERR err = M4NO_ERROR;

    M4OSA_TRACE2_1("videoBrowserCleanUp: entering with 0x%x", pContext);

    /*--- Sanity checks ---*/
    CHECK_PTR(videoBrowserCleanUp, pContext, err, M4ERR_PARAMETER);

    if (M4OSA_NULL != pC->m_pDecoderCtx)
    {
        pC->m_pDecoder->m_pFctDestroy(pC->m_pDecoderCtx);
        pC->m_pDecoderCtx = M4OSA_NULL ;
    }

    if (M4OSA_NULL != pC->m_pReaderCtx)
    {
        pC->m_3gpReader->m_pFctClose(pC->m_pReaderCtx) ;
        pC->m_3gpReader->m_pFctDestroy(pC->m_pReaderCtx);
        pC->m_pReaderCtx = M4OSA_NULL;
    }

    SAFE_FREE(pC->m_pDecoder);
    SAFE_FREE(pC->m_3gpReader);
    SAFE_FREE(pC->m_3gpData);

    if (pC->m_frameColorType != VideoBrowser_kYUV420) {
        SAFE_FREE(pC->m_outputPlane[0].pac_data);
    }
    SAFE_FREE(pC);

    M4OSA_TRACE2_0("videoBrowserCleanUp returned NO ERROR");
    return M4NO_ERROR;

videoBrowserCleanUp_cleanUp:

    M4OSA_TRACE2_1("videoBrowserCleanUp returned 0x%x", err);
    return err;
}
/******************************************************************************
* M4OSA_ERR     videoBrowserPrepareFrame(
*       M4OSA_Context pContext, M4OSA_UInt32* pTime);
* @brief        This function prepares the frame.
* @param        pContext     (IN) : Video browser context
* @param        pTime        (IN/OUT) : Pointer on the time to reach. Updated
*                                       by this function with the reached time
* @param        tolerance    (IN) :  We may decode an earlier frame within the tolerance.
*                                    The time difference is specified in milliseconds.
* @return       M4NO_ERROR / M4ERR_PARAMETER / M4ERR_STATE / M4ERR_ALLOC
******************************************************************************/
M4OSA_ERR videoBrowserPrepareFrame(M4OSA_Context pContext, M4OSA_UInt32* pTime,
    M4OSA_UInt32 tolerance)
{
    VideoBrowserContext* pC = (VideoBrowserContext*)pContext;
    M4OSA_ERR err = M4NO_ERROR;
    M4OSA_UInt32 targetTime = 0;
    M4OSA_UInt32 jumpTime = 0;
    M4_MediaTime timeMS = 0;
    M4OSA_Int32 rapTime = 0;
    M4OSA_Bool isBackward = M4OSA_FALSE;
    M4OSA_Bool bJumpNeeded = M4OSA_FALSE;


    /*--- Sanity checks ---*/
    CHECK_PTR(videoBrowserPrepareFrame, pContext, err, M4ERR_PARAMETER);
    CHECK_PTR(videoBrowserPrepareFrame, pTime,  err, M4ERR_PARAMETER);

    targetTime = *pTime ;

    /*--- Check the state, if this is the first call to this function
          we move to the state "browsing" ---*/
    if (VideoBrowser_kVBOpened == pC->m_state)
    {
        pC->m_state = VideoBrowser_kVBBrowsing;
    }
    else if (VideoBrowser_kVBBrowsing != pC->m_state)
    {
        err = M4ERR_STATE ;
        goto videoBrowserPrepareFrame_cleanUp;
    }

    /*--- Check the duration ---*/
    /*--- If we jump backward, we need to jump ---*/
    if (targetTime < pC->m_currentCTS)
    {
        isBackward = M4OSA_TRUE;
        bJumpNeeded = M4OSA_TRUE;
    }
    /*--- If we jumpt to a time greater than "currentTime" + "predecodeTime"
          we need to jump ---*/
    else if (targetTime > (pC->m_currentCTS + VIDEO_BROWSER_PREDECODE_TIME))
    {
        bJumpNeeded = M4OSA_TRUE;
    }

    timeMS = (M4_MediaTime)targetTime;
    err = pC->m_pDecoder->m_pFctDecode(
        pC->m_pDecoderCtx, &timeMS, bJumpNeeded, tolerance);

    if ((err != M4NO_ERROR) && (err != M4WAR_NO_MORE_AU))
    {
        return err;
    }

    err = pC->m_pDecoder->m_pFctRender(
        pC->m_pDecoderCtx, &timeMS, pC->m_outputPlane, M4OSA_TRUE);

    if (M4WAR_VIDEORENDERER_NO_NEW_FRAME == err)
    {
        return err;
    }
    CHECK_ERR(videoBrowserPrepareFrame, err) ;

    pC->m_currentCTS = (M4OSA_UInt32)timeMS;

    *pTime = pC->m_currentCTS;

    return M4NO_ERROR;

videoBrowserPrepareFrame_cleanUp:

    if ((M4WAR_INVALID_TIME == err) || (M4WAR_NO_MORE_AU == err))
    {
        err = M4NO_ERROR;
    }
    else if (M4OSA_NULL != pC)
    {
        pC->m_currentCTS = 0;
    }

    M4OSA_TRACE2_1("videoBrowserPrepareFrame returned 0x%x", err);
    return err;
}

/******************************************************************************
* M4OSA_ERR     videoBrowserDisplayCurrentFrame(M4OSA_Context pContext);
* @brief        This function displays the current frame.
* @param        pContext     (IN) : Video browser context
* @return       M4NO_ERROR / M4ERR_PARAMETER / M4ERR_STATE / M4ERR_ALLOC
******************************************************************************/
M4OSA_ERR videoBrowserDisplayCurrentFrame(M4OSA_Context pContext)
{
    VideoBrowserContext* pC = (VideoBrowserContext*)pContext ;
    M4OSA_ERR err = M4NO_ERROR ;

    /*--- Sanity checks ---*/
    CHECK_PTR(videoBrowserDisplayCurrentFrame, pContext, err, M4ERR_PARAMETER);

    // Request display of the frame
    pC->m_pfCallback((M4OSA_Context) pC,             // VB context
        VIDEOBROWSER_DISPLAY_FRAME,                  // action requested
        M4NO_ERROR,                                  // error code
        (M4OSA_Void*) &(pC->m_outputPlane[0]),       // image to be displayed
        (M4OSA_Void*) pC->m_pCallbackUserData);      // user-provided data

#ifdef DUMPTOFILE
    {
        M4OSA_Context fileContext;
        M4OSA_Char* fileName = "/sdcard/textBuffer_RGB565.rgb";
        M4OSA_fileWriteOpen(&fileContext, (M4OSA_Void*) fileName,
            M4OSA_kFileWrite | M4OSA_kFileCreate);

        M4OSA_fileWriteData(fileContext,
            (M4OSA_MemAddr8) pC->m_outputPlane[0].pac_data,
            pC->m_outputPlane[0].u_height*pC->m_outputPlane[0].u_width*2);

        M4OSA_fileWriteClose(fileContext);
    }
#endif

    M4OSA_TRACE2_0("videoBrowserDisplayCurrentFrame returned NO ERROR") ;
    return M4NO_ERROR;

videoBrowserDisplayCurrentFrame_cleanUp:

    M4OSA_TRACE2_1("videoBrowserDisplayCurrentFrame returned 0x%x", err) ;
    return err;
}
