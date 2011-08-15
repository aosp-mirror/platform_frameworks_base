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


#ifndef VIDEO_BROWSER_MAIN_H
#define VIDEO_BROWSER_MAIN_H

/**
 ************************************************************************
 * @file    VideoBrowserMain.h
 * @brief   Video browser Interface functions
 ************************************************************************
*/

#define VIDEOBROWSER    0x423

#include "M4OSA_Memory.h"
#include "M4OSA_CharStar.h"
#include "M4OSA_OptionID.h"
#include "M4OSA_Debug.h"
#include "M4VIFI_FiltersAPI.h"
#include "M4OSA_FileReader.h"


/**
 ************************************************************************
 * @brief    Error codes definition.
 * @note    These value are the Browser engine specific error codes.
 ************************************************************************
*/
#define M4ERR_VB_MEDIATYPE_NOT_SUPPORTED    M4OSA_ERR_CREATE(M4_ERR, VIDEOBROWSER, 0x01)
#define M4ERR_VB_NO_VIDEO                   M4OSA_ERR_CREATE(M4_ERR, VIDEOBROWSER, 0x02)

#ifdef __cplusplus
extern "C" {
#endif

/*
 *  Video Browser draw mode, extension for angle based bliting can be done
 */
typedef enum
{
    VideoBrowser_kVBNormalBliting
} VideoBrowser_videoBrowerDrawMode;


/*--- Video Browser output frame color type ---*/
typedef enum
{
    VideoBrowser_kYUV420,
    VideoBrowser_kGB565
} VideoBrowser_VideoColorType;

/**
 ************************************************************************
 * enumeration  VideoBrowser_Notification
 * @brief       Video Browser notification type.
 * @note        This callback mechanism must be used to wait the completion of an asynchronous
 * operation, before calling another API function.
 ************************************************************************
*/
typedef enum
{
    /**
     * A frame is ready to be displayed, it should be displayed in the callback function
     * pCbData type = M4VIFI_ImagePlane*
     */
    VIDEOBROWSER_DISPLAY_FRAME            = 0x00000001,
    VIDEOBROWSER_NOTIFICATION_NONE        = 0xffffffff
}VideoBrowser_Notification;


/**
 ************************************************************************
 * @brief    videoBrowser_Callback type definition
 * @param    pInstance          (IN) Video Browser context.
 * @param    notificationID     (IN) Id of the callback which generated the error
 * @param    errCode            (IN) Error code from the core
 * @param    pCbData            (IN) pointer to data associated wit the callback.
 * @param    pCbUserData        (IN) pointer to application user data passed in init.
 * @note    This callback mechanism is used to request display of an image
 ************************************************************************
*/
typedef M4OSA_Void (*videoBrowser_Callback) (M4OSA_Context pInstance,
                                        VideoBrowser_Notification notificationID,
                                        M4OSA_ERR errCode,
                                        M4OSA_Void* pCbData,
                                        M4OSA_Void* pCallbackUserData);


/******************************************************************************
* @brief   This function allocates the resources needed for browsing a video file.
* @param   ppContext     (OUT): Pointer on a context filled by this function.
* @param   pURL          (IN) : Path of File to browse
* @param   DrawMode      (IN) : Indicate which method is used to draw (Direct draw etc...)
* @param   pfCallback    (IN) : Callback function to be called when a frame must be displayed
* @param   pCallbackData (IN)  : User defined data that will be passed as parameter of the callback
* @param   clrType       (IN) : Required color type.
* @return  M4NO_ERROR / M4ERR_PARAMETER / M4ERR_STATE / M4ERR_ALLOC
******************************************************************************/
M4OSA_ERR videoBrowserCreate(M4OSA_Context* ppContext, M4OSA_Char* pURL,
                                        M4OSA_UInt32 DrawMode,
                                        M4OSA_FileReadPointer* ptrF,
                                        videoBrowser_Callback pfCallback,
                                        M4OSA_Void* pCallbackData,
                                        VideoBrowser_VideoColorType clrType);

/******************************************************************************
* @brief        This function frees the resources needed for browsing a video file.
* @param        pContext     (IN) : Video browser context
* @return       M4NO_ERROR / M4ERR_PARAMETER / M4ERR_STATE
******************************************************************************/
M4OSA_ERR videoBrowserCleanUp(M4OSA_Context pContext) ;


/******************************************************************************
* @brief        This function allocates the resources needed for browsing a video file.
* @param        pContext  (IN)      : Video browser context
* @param        pTime     (IN/OUT)  : Pointer on the time to reach. Updated by
*                                     this function with the reached time
* @return       M4NO_ERROR / M4ERR_PARAMETER / M4ERR_STATE / M4ERR_ALLOC
******************************************************************************/
M4OSA_ERR videoBrowserPrepareFrame(M4OSA_Context pContext, M4OSA_UInt32* pTime,
        M4OSA_UInt32 tolerance);

/******************************************************************************
* @brief        This function sets the size and the position of the display.
* @param        pContext     (IN) : Video Browser context
* @param        pixelArray   (IN) : Array to hold the video frame.
* @param        x            (IN) : Horizontal position of the top left corner
* @param        y            (IN) : Vertical position of the top left corner
* @param        dx           (IN) : Width of the display window
* @param        dy           (IN) : Height of the video window
* @return       M4NO_ERROR / M4ERR_PARAMETER / M4ERR_STATE / M4ERR_ALLOC
******************************************************************************/
M4OSA_ERR videoBrowserSetWindow(M4OSA_Context pContext, M4OSA_Int32* pixelArray,
                                M4OSA_UInt32 x, M4OSA_UInt32 y,
                                M4OSA_UInt32 dx, M4OSA_UInt32 dy);

/******************************************************************************
* @brief        This function displays the current frame.
* @param        pContext     (IN) : Video browser context
* @return       M4NO_ERROR / M4ERR_PARAMETER / M4ERR_STATE / M4ERR_ALLOC
******************************************************************************/
M4OSA_ERR videoBrowserDisplayCurrentFrame(M4OSA_Context pContext);

#ifdef __cplusplus
}
#endif

#endif /* VIDEO_BROWSER_MAIN_H */
