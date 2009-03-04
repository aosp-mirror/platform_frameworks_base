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

#ifndef __PARSER_DM_H__
#define __PARSER_DM_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <drm_common_types.h>

#define MAX_CONTENT_TYPE_LEN                                64
#define MAX_CONTENT_ID                                      256
#define MAX_CONTENT_BOUNDARY_LEN                            256
#define MAX_RIGHTS_ISSUER_LEN                               256

#define DRM_MIME_TYPE_RIGHTS_XML                            "application/vnd.oma.drm.rights+xml"
#define DRM_MIME_TYPE_CONTENT                               "application/vnd.oma.drm.content"

#define HEADERS_TRANSFER_CODING                             "Content-Transfer-Encoding:"
#define HEADERS_CONTENT_TYPE                                "Content-Type:"
#define HEADERS_CONTENT_ID                                  "Content-ID:"

#define TRANSFER_CODING_TYPE_7BIT                           "7bit"
#define TRANSFER_CODING_TYPE_8BIT                           "8bit"
#define TRANSFER_CODING_TYPE_BINARY                         "binary"
#define TRANSFER_CODING_TYPE_BASE64                         "base64"

#define DRM_UID_TYPE_FORWORD_LOCK                           "forwardlock"
#define DRM_NEW_LINE_CRLF                                   "\r\n"

#define HEADERS_TRANSFER_CODING_LEN                         26
#define HEADERS_CONTENT_TYPE_LEN                            13
#define HEADERS_CONTENT_ID_LEN                              11

#define DRM_MESSAGE_CODING_7BIT                             0  /* default */
#define DRM_MESSAGE_CODING_8BIT                             1
#define DRM_MESSAGE_CODING_BINARY                           2
#define DRM_MESSAGE_CODING_BASE64                           3

#define DRM_B64_DEC_BLOCK                                   3
#define DRM_B64_ENC_BLOCK                                   4

typedef struct _T_DRM_DM_Info {
    uint8_t contentType[MAX_CONTENT_TYPE_LEN];  /**< Content type */
    uint8_t contentID[MAX_CONTENT_ID];          /**< Content ID */
    uint8_t boundary[MAX_CONTENT_BOUNDARY_LEN]; /**< DRM message's boundary */
    uint8_t deliveryType;                       /**< The Delivery type */
    uint8_t transferEncoding;                   /**< Transfer encoding type */
    int32_t contentOffset;                      /**< The offset of the media content from the original DRM data */
    int32_t contentLen;                         /**< The length of the media content */
    int32_t rightsOffset;                       /**< The offset of the rights object in case of combined delivery */
    int32_t rightsLen;                          /**< The length of the rights object in case of combined delivery */
    uint8_t rightsIssuer[MAX_RIGHTS_ISSUER_LEN];/**< The rights issuer address in case of separate delivery */
} T_DRM_DM_Info;

/**
 * Search the string in a limited length.
 *
 * \param str           The original string
 * \param strSearch     The sub-string to be searched
 * \param len           The length limited
 *
 * \return
 *      -NULL, when there is not the searched string in length
 *      -The pointer of this sub-string
 */
const uint8_t* drm_strnstr(const uint8_t* str, const uint8_t* strSearch, int32_t len);

/**
 * Parse the DRM message format data.
 *
 * \param buffer        (in)Input the DRM message format data
 * \param bufferLen     (in)The input buffer length
 * \param pDmInfo       (out)A structure pointer which contain information of DRM message headers
 *
 * \return
 *      -TRUE, when success
 *      -FALSE, when failed
 */
int32_t drm_parseDM(const uint8_t* buffer, int32_t bufferLen, T_DRM_DM_Info* pDmInfo);

#ifdef __cplusplus
}
#endif

#endif /* __PARSER_DM_H__ */
