/*
 * Copyright (C) 2007 The Android Open Source Project
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

/**
 * @file drm_decoder.h
 *
 * provide service to decode base64 data.
 *
 * <!-- #interface list begin -->
 * \section drm decoder interface
 * - drm_decodeBase64()
 * <!-- #interface list end -->
 */

#ifndef __DRM_DECODER_H__
#define __DRM_DECODER_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <drm_common_types.h>

/**
 * Decode base64
 * \param dest          dest buffer to save decode base64 data
 * \param destLen       dest buffer length
 * \param src           source data to be decoded
 * \param srcLen        source buffer length, and when return, give out how many bytes has been decoded
 * \return
 *        -when success, return a positive integer of dest buffer length,
 *                       if input dest buffer is NULL or destLen is 0,
 *                       return dest buffer length that user should allocate to save decoding data
 *        -when failed, return -1
 */
int32_t drm_decodeBase64(uint8_t * dest, int32_t destLen, uint8_t * src, int32_t * srcLen);

#ifdef __cplusplus
}
#endif

#endif /* __DRM_DECODER_H__ */
