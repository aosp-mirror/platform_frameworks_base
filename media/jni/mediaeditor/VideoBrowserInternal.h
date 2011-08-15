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


#ifndef VIDEO_BROWSER_INTERNAL_H
#define VIDEO_BROWSER_INTERNAL_H

#include "VideoBrowserMain.h"

#include "M4READER_Common.h"
#include "M4DECODER_Common.h"


#define VIDEO_BROWSER_BGR565

/*---------------------------- MACROS ----------------------------*/
#define CHECK_PTR(fct, p, err, errValue) \
{ \
    if (M4OSA_NULL == p) \
    { \
        err = errValue ; \
        M4OSA_TRACE1_1("" #fct "(L%d): " #p " is NULL, returning " #errValue "", __LINE__) ; \
        goto fct##_cleanUp; \
    } \
}

#define CHECK_ERR(fct, err) \
{ \
    if (M4OSA_ERR_IS_ERROR(err)) \
    { \
        M4OSA_TRACE1_2("" #fct "(L%d): ERROR 0x%.8x returned", __LINE__,err) ; \
        goto fct##_cleanUp; \
    } \
    else if (M4OSA_ERR_IS_WARNING(err)) \
    { \
        M4OSA_TRACE2_2("" #fct "(L%d): WARNING 0x%.8x returned", __LINE__,err) ; \
    } \
}

#define CHECK_STATE(fct, state, pC) \
{ \
    if (state != pC->m_state) \
    { \
        M4OSA_TRACE1_1("" #fct " called in bad state %d", pC->m_state) ; \
        err = M4ERR_STATE ; \
        goto fct##_cleanUp; \
    } \
}

#define SAFE_FREE(p) \
{ \
    if (M4OSA_NULL != p) \
    { \
        free(p) ; \
        p = M4OSA_NULL ; \
    } \
}

/*--- Video Browser state ---*/
typedef enum
{
    VideoBrowser_kVBCreating,
    VideoBrowser_kVBOpened,
    VideoBrowser_kVBBrowsing
} VideoBrowser_videoBrowerState;


/*--- Video Browser execution context. ---*/
typedef struct
{
    VideoBrowser_videoBrowerState       m_state ;
    VideoBrowser_videoBrowerDrawMode    m_drawmode;

    M4OSA_Context                       g_hbmp2;
    M4OSA_Context                       dc;
    M4OSA_Int16*                        g_bmPixels2;

    /*--- Reader parameters ---*/
    M4OSA_FileReadPointer               m_fileReadPtr;
    M4READER_GlobalInterface*           m_3gpReader ;
    M4READER_DataInterface*             m_3gpData ;
    M4READER_MediaType                  m_mediaType ;
    M4OSA_Context                       m_pReaderCtx ;

    M4_StreamHandler*                   m_pStreamHandler ;
    M4_AccessUnit                       m_accessUnit ;

    /*--- Decoder parameters ---*/
    M4DECODER_VideoInterface*           m_pDecoder ;
    M4OSA_Context                       m_pDecoderCtx ;

    /*--- Common display parameters ---*/
    M4OSA_UInt32                        m_x ;
    M4OSA_UInt32                        m_y ;
    M4VIFI_ImagePlane                   m_outputPlane[3] ;

    /*--- Current browsing time ---*/
    M4OSA_UInt32                        m_currentCTS ;

    /*--- Platform dependent display parameters ---*/
    M4OSA_Context                       m_pCoreContext ;

    /*--- Callback function settings ---*/
    videoBrowser_Callback               m_pfCallback;
    M4OSA_Void*                         m_pCallbackUserData;

    /*--- Codec Loader core context ---*/
    M4OSA_Context                       m_pCodecLoaderContext;

    /*--- Required color type ---*/
    VideoBrowser_VideoColorType         m_frameColorType;

} VideoBrowserContext;

#endif /* VIDEO_BROWSER_INTERNAL_H */
