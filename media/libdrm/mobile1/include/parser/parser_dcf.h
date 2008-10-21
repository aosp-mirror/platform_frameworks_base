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

#ifndef __PARSER_DCF_H__
#define __PARSER_DCF_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <drm_common_types.h>

#define MAX_ENCRYPTION_METHOD_LEN                            64
#define MAX_RIGHTS_ISSUER_LEN                                256
#define MAX_CONTENT_NAME_LEN                                 64
#define MAX_CONTENT_DESCRIPTION_LEN                          256
#define MAX_CONTENT_VENDOR_LEN                               256
#define MAX_ICON_URI_LEN                                     256
#define MAX_CONTENT_TYPE_LEN                                 64
#define MAX_CONTENT_URI_LEN                                  256

#define HEADER_ENCRYPTION_METHOD                             "Encryption-Method: "
#define HEADER_RIGHTS_ISSUER                                 "Rights-Issuer: "
#define HEADER_CONTENT_NAME                                  "Content-Name: "
#define HEADER_CONTENT_DESCRIPTION                           "Content-Description: "
#define HEADER_CONTENT_VENDOR                                "Content-Vendor: "
#define HEADER_ICON_URI                                      "Icon-Uri: "

#define HEADER_ENCRYPTION_METHOD_LEN                         19
#define HEADER_RIGHTS_ISSUER_LEN                             15
#define HEADER_CONTENT_NAME_LEN                              14
#define HEADER_CONTENT_DESCRIPTION_LEN                       21
#define HEADER_CONTENT_VENDOR_LEN                            16
#define HEADER_ICON_URI_LEN                                  10

#define UINT_VAR_FLAG                                        0x80
#define UINT_VAR_DATA                                        0x7F
#define MAX_UINT_VAR_BYTE                                    5
#define DRM_UINT_VAR_ERR                                     -1

typedef struct _T_DRM_DCF_Info {
    uint8_t Version;
    uint8_t ContentTypeLen;                                  /**< Length of the ContentType field */
    uint8_t ContentURILen;                                   /**< Length of the ContentURI field */
    uint8_t unUsed;
    uint8_t ContentType[MAX_CONTENT_TYPE_LEN];               /**< The MIME media type of the plaintext data */
    uint8_t ContentURI[MAX_CONTENT_URI_LEN];                 /**< The unique identifier of this content object */
    int32_t HeadersLen;                                      /**< Length of the Headers field */
    int32_t EncryptedDataLen;                                /**< Length of the encrypted data field */
    int32_t DecryptedDataLen;                                /**< Length of the decrypted data field */
    uint8_t Encryption_Method[MAX_ENCRYPTION_METHOD_LEN];    /**< Encryption method */
    uint8_t Rights_Issuer[MAX_RIGHTS_ISSUER_LEN];            /**< Rights issuer */
    uint8_t Content_Name[MAX_CONTENT_NAME_LEN];              /**< Content name */
    uint8_t ContentDescription[MAX_CONTENT_DESCRIPTION_LEN]; /**< Content description */
    uint8_t ContentVendor[MAX_CONTENT_VENDOR_LEN];           /**< Content vendor */
    uint8_t Icon_URI[MAX_ICON_URI_LEN];                      /**< Icon URI */
} T_DRM_DCF_Info;

/**
 * Parse the DRM content format data
 *
 * \param buffer            (in)Input the DCF format data
 * \param bufferLen         (in)The input buffer length
 * \param pDcfInfo          (out)A structure pointer which contain information of DCF headers
 * \param ppEncryptedData   (out)The location of encrypted data
 *
 * \return
 *      -TRUE, when success
 *      -FALSE, when failed
 */
int32_t drm_dcfParser(uint8_t *buffer, int32_t bufferLen, T_DRM_DCF_Info *pDcfInfo,
                      uint8_t **ppEncryptedData);

#ifdef __cplusplus
}
#endif

#endif /* __PARSER_DCF_H__ */
