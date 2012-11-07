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


#include <jni.h>
#include <JNIHelp.h>
#include <utils/Log.h>
#include "VideoBrowserMain.h"
#include "VideoBrowserInternal.h"

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

/*
 * Memory format of 'ARGB8888' in skia is RGBA, so ABGR in 32bit little-endian packed format
 * bitmap format is rgb565
 */
//                                RED                 GREEN               BLUE            ALPHA
#define RGB565toSKCOLOR(c) ( (((c)&0xF800)>>8) | (((c)&0x7E0)<<5) | (((c)&0x1F)<<19) | 0xFF000000)

#define GetIntField(env, obj, name) env->GetIntField(obj,\
env->GetFieldID(env->GetObjectClass(obj), name, "I"))

extern "C" M4OSA_ERR NXPSW_FileReaderOptim_init(M4OSA_Void *lowLevel_functionPointers,
        M4OSA_Void *optimized_functionPointers);

/*
 * Video Browser execution context.
 * Based on request for RGB565 or RGB888, m_dst16 or m_dst32
 * will be initialized and used
 */
typedef struct
{
    M4OSA_Context       m_pVideoBrowser;
    M4OSA_UInt32        m_previousTime;
    M4OSA_Int32*        m_dst32;
    M4OSA_Int16*        m_dst16;
    unsigned int        m_width;
    unsigned int        m_height;
    M4OSA_Bool          m_bRender;
} ThumbnailContext;

/**
 ************************************************************************
 * @brief    Interface to retrieve the thumbnail pixels
 * @param    pContext   (IN)    Thumbnail Context.
 * @param    width      (IN)    Width of thumbnail
 * @param    height     (IN)    Height of thumbnail
 * @param    pTimeMS    (IN/OUT)Time stamp at which thumbnail is retrieved.
 ************************************************************************
*/
M4OSA_ERR ThumbnailGetPixels(const M4OSA_Context pContext,
                             M4OSA_Int32* pixelArray,
                             M4OSA_UInt32 width, M4OSA_UInt32 height,
                             M4OSA_UInt32* pTimeMS, M4OSA_UInt32 tolerance);


/**
 ************************************************************************
 * @brief    Video browser callback, called when a frame must be displayed
 * @param    pInstance          (IN) Thumbnail context.
 * @param    notificationID     (IN) Id of the callback which generated the error
 * @param    errCode            (IN) Error code from the Core
 * @param    pCbData            (IN) pointer to data associated wit the callback.
 * @param    pCbUserData        (IN) pointer to application user data passed in init.
 * @note     This callback mechanism is used to request display of an image
 ************************************************************************
*/
M4OSA_Void VBcallback(  M4OSA_Context  pInstance,
                        VideoBrowser_Notification notificationID,
                        M4OSA_ERR errCode, M4OSA_Void* pCbData,
                        M4OSA_Void* pCallbackUserData)
{
    M4OSA_UInt32 i, j;
    M4OSA_ERR err;

    M4OSA_TRACE3_0("inside VBcallback");
    M4VIFI_ImagePlane* pPlane=NULL;
    M4OSA_UInt16* src=NULL;
    ThumbnailContext* pC = NULL;

    CHECK_PTR(VBcallback, pCbData, err, M4ERR_PARAMETER);
    CHECK_PTR(VBcallback, pInstance,err, M4ERR_PARAMETER);

    pC = (ThumbnailContext*)pCallbackUserData ;
    CHECK_PTR(VBcallback, pC->m_pVideoBrowser, err, M4ERR_PARAMETER);

    pPlane = (M4VIFI_ImagePlane*)pCbData;
    src = (M4OSA_UInt16*)pPlane->pac_data;

    if (pC->m_dst32 != NULL)
    {
        M4OSA_Int32* dst = pC->m_dst32;

        for (j = 0; j < pPlane->u_height; j++)
        {
            for (i = 0; i < pPlane->u_width; i++)
            {
                dst[i] = RGB565toSKCOLOR(src[i]);
            }
            for (i = pPlane->u_width; i < pC->m_width; i++)
            {
                dst[i] = 0;
            }
            src = (M4OSA_UInt16*)((M4OSA_UInt8*)src + pPlane->u_stride);
            dst += pC->m_width;
        }
    }
    else if (pC->m_dst16 != NULL)
    {
        M4OSA_Int16* dst = pC->m_dst16;

        for (j = 0; j < pPlane->u_height; j++)
        {
            memcpy((void * )dst, (void * )src, pPlane->u_stride);
            for (i = pPlane->u_width; i < pC->m_width; i++)
            {
                dst[i] = 0;
            }
            src = (M4OSA_UInt16*)((M4OSA_UInt8*)src + pPlane->u_stride);
            dst += pC->m_width;
        }
    }
    else
    {
        CHECK_PTR(VBcallback, NULL, err, M4ERR_PARAMETER);
    }

VBcallback_cleanUp:

    return;
}

M4OSA_ERR ThumbnailOpen(M4OSA_Context *pPContext,
                  const M4OSA_Char *pString,
                  M4OSA_Bool bRender)
{

    M4OSA_ERR err;
    ThumbnailContext *pContext = M4OSA_NULL;
    VideoBrowser_VideoColorType vbColorType;

    CHECK_PTR(ThumbnailOpen, pString, err, M4ERR_BAD_CONTEXT);

    /*--- Create context ---*/
    pContext = (ThumbnailContext*)M4OSA_32bitAlignedMalloc(sizeof(ThumbnailContext), VIDEOBROWSER,
        (M4OSA_Char*)"Thumbnail context") ;
    M4OSA_TRACE3_1("context value is = %d",pContext);
    CHECK_PTR(ThumbnailOpen, pContext, err, M4ERR_ALLOC);

    memset((void *)pContext, 0,sizeof(ThumbnailContext));

    M4OSA_FileReadPointer optFP;
    M4OSA_FileReadPointer llFP;

    NXPSW_FileReaderOptim_init(&llFP, &optFP);
    M4OSA_TRACE1_2("ThumbnailOpen: entering videoBrowserCreate with 0x%x %s",
        &pContext->m_pVideoBrowser, pString) ;

    pContext->m_bRender = bRender;
    if (bRender == M4OSA_TRUE) {
        //Open is called for rendering the frame.
        //So set YUV420 as the output color format.
        vbColorType = VideoBrowser_kYUV420;
    } else {
        //Open is called for thumbnail Extraction
        //So set BGR565 as the output.
        vbColorType = VideoBrowser_kGB565;
    }

    err = videoBrowserCreate(&pContext->m_pVideoBrowser, (M4OSA_Char*)pString,
        VideoBrowser_kVBNormalBliting, &optFP, VBcallback, pContext, vbColorType);

    M4OSA_TRACE1_1("err value is = 0x%x",err);
    CHECK_ERR(ThumbnailOpen, err);
    CHECK_PTR(ThumbnailOpen, pContext->m_pVideoBrowser, err, M4ERR_ALLOC);

    *pPContext = pContext;
    M4OSA_TRACE1_1("context value is = %d",*pPContext);

    return M4NO_ERROR;

ThumbnailOpen_cleanUp:

    M4OSA_TRACE1_0("i am inside cleanUP");
    if (M4OSA_NULL != pContext)
    {
        if (M4OSA_NULL != pContext->m_pVideoBrowser)
        {
            videoBrowserCleanUp(pContext->m_pVideoBrowser) ;
        }
        free(pContext) ;
    }
    return err;
}

M4OSA_ERR ThumbnailGetPixels(const M4OSA_Context pContext,
                             M4OSA_Int32* pixelArray,
                             M4OSA_UInt32 width, M4OSA_UInt32 height,
                             M4OSA_UInt32* pTimeMS, M4OSA_UInt32 tolerance)
{
    M4OSA_ERR err;

    ThumbnailContext* pC = (ThumbnailContext*)pContext;

    if ((pC->m_width != width) || (pC->m_height != height))
    {
        err = videoBrowserSetWindow(pC->m_pVideoBrowser, pixelArray,
                                      0, 0, width, height);
        CHECK_ERR(ThumbnailGetPixels, err);
        pC->m_width  = width;
        pC->m_height = height;
    }

    // Alter the pTimeMS to a valid value at which a frame is found
    // m_currentCTS has the actual frame time stamp just ahead of the
    // pTimeMS supplied.
    if ((((VideoBrowserContext*)pC->m_pVideoBrowser)->m_currentCTS != 0) &&
        (*pTimeMS >= pC->m_previousTime) &&
        (*pTimeMS < ((VideoBrowserContext*)pC->m_pVideoBrowser)->m_currentCTS))
    {
        pC->m_previousTime = *pTimeMS;
        *pTimeMS = ((VideoBrowserContext*)pC->m_pVideoBrowser)->m_currentCTS;
    }
    else
    {
        pC->m_previousTime = *pTimeMS;
    }

    err = videoBrowserPrepareFrame(pC->m_pVideoBrowser, pTimeMS, tolerance);
    CHECK_ERR(ThumbnailGetPixels, err);

    if (pC->m_bRender != M4OSA_TRUE) {
        err = videoBrowserDisplayCurrentFrame(pC->m_pVideoBrowser);
        CHECK_ERR(ThumbnailGetPixels, err);
    }

ThumbnailGetPixels_cleanUp:

    return err;
}

M4OSA_ERR ThumbnailGetPixels32(const M4OSA_Context pContext,
                         M4OSA_Int32* pixelArray, M4OSA_UInt32 width,
                         M4OSA_UInt32 height, M4OSA_UInt32* timeMS,
                         M4OSA_UInt32 tolerance)
{

    M4OSA_ERR err = M4NO_ERROR;

    ThumbnailContext* pC = (ThumbnailContext*)pContext;

    CHECK_PTR(ThumbnailGetPixels32, pC->m_pVideoBrowser, err, M4ERR_ALLOC) ;
    CHECK_PTR(ThumbnailGetPixels32, pixelArray, err, M4ERR_ALLOC) ;

    pC->m_dst16 = NULL;
    pC->m_dst32 = pixelArray;

    err = ThumbnailGetPixels(pContext, pixelArray, width, height, timeMS, tolerance);

ThumbnailGetPixels32_cleanUp:

    return err;
}

M4OSA_ERR ThumbnailGetPixels16(const M4OSA_Context pContext,
                         M4OSA_Int16* pixelArray, M4OSA_UInt32 width,
                         M4OSA_UInt32 height, M4OSA_UInt32* timeMS,
                         M4OSA_UInt32 tolerance)
{
    M4OSA_ERR err = M4NO_ERROR;

    ThumbnailContext* pC = (ThumbnailContext*)pContext;

    CHECK_PTR(ThumbnailGetPixels16, pC->m_pVideoBrowser, err, M4ERR_ALLOC);
    CHECK_PTR(ThumbnailGetPixels16, pixelArray, err, M4ERR_ALLOC);

    pC->m_dst16 = pixelArray;
    pC->m_dst32 = NULL;

    err = ThumbnailGetPixels(pContext, (M4OSA_Int32*)pixelArray, width, height,
            timeMS, tolerance);

ThumbnailGetPixels16_cleanUp:

    return err;
}


void ThumbnailClose(const M4OSA_Context pContext)
{
    M4OSA_ERR err;

    ThumbnailContext* pC = (ThumbnailContext*)pContext;

    CHECK_PTR(ThumbnailClose, pC, err, M4ERR_ALLOC);

    if (M4OSA_NULL != pC)
    {
        if (M4OSA_NULL != pC->m_pVideoBrowser)
        {
            videoBrowserCleanUp(pC->m_pVideoBrowser);
        }
        free(pC);
    }

ThumbnailClose_cleanUp:

    return;
}

