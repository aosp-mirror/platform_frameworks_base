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

#ifndef VIDEOEDITOR_THUMBNAIL_MAIN_H
#define VIDEOEDITOR_THUMBNAIL_MAIN_H

/**
 ************************************************************************
 * @file        VideoEditorThumbnailMain.h
 * @brief       Thumbnail extract interface.
 ************************************************************************
*/

/**
 ************************************************************************
 * @brief    Interface to open a Thumbnail session.
 * @param    pContext   (OUT)   Thumbnail Context.
 * @param    pString    (IN)    File path from which thumbnail will be
 *                              retrieved
 * @param    M4OSA_Bool (IN)    true if this is for rendering at native layer.
 ************************************************************************
*/
M4OSA_ERR ThumbnailOpen(M4OSA_Context *pPContext,
                  const M4OSA_Char *pString,
                  M4OSA_Bool bRender);

/**
 ************************************************************************
 * @brief    Interface to retrieve a RGB888 format thumbnail pixels
 * @param    pContext   (IN)    Thumbnail Context.
 * @param    pixelArray (OUT)   Pointer to array in which pixels data to return
 * @param    width      (IN)    Width of thumbnail
 * @param    height     (IN)    Height of thumbnail
 * @param    pTimeMS    (IN/OUT)Time stamp at which thumbnail is retrieved.
 ************************************************************************
*/
M4OSA_ERR ThumbnailGetPixels32(const M4OSA_Context pContext,
                             M4OSA_Int32* pixelArray, M4OSA_UInt32 width,
                             M4OSA_UInt32 height, M4OSA_UInt32 *timeMS,
                             M4OSA_UInt32 tolerance);

/**
 ************************************************************************
 * @brief    Interface to retrieve a RGB565 format thumbnail pixels
 * @param    pContext   (IN)    Thumbnail Context.
 * @param    pixelArray (OUT)   Pointer to array in which pixcel data to return
 * @param    width      (IN)    Width of thumbnail
 * @param    height     (IN)    Height of thumbnail
 * @param    pTimeMS    (IN/OUT)Time stamp at which thumbnail is retrieved.
 ************************************************************************
*/
M4OSA_ERR ThumbnailGetPixels16(const M4OSA_Context pContext,
                             M4OSA_Int16* pixelArray, M4OSA_UInt32 width,
                             M4OSA_UInt32 height, M4OSA_UInt32 *timeMS,
                             M4OSA_UInt32 tolerance);

/**
 ************************************************************************
 * @brief    Interface to close the Thumbnail session.
 * @param    pContext   (IN)    Thumbnail Context.
 ************************************************************************
*/
void ThumbnailClose(const M4OSA_Context pContext);

#endif // VIDEOEDITOR_THUMBNAIL_MAIN_H
