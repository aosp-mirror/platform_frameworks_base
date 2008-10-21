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

#ifndef __DRM_INNER_H__
#define __DRM_INNER_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <drm_common_types.h>

#define INT_2_YMD_HMS(year, mon, day, date, hour, min, sec, time) do{\
    year = date / 10000;\
    mon = date % 10000 / 100;\
    day = date %100;\
    hour = time / 10000;\
    min = time % 10000 / 100;\
    sec = time % 100;\
}while(0)

/**
 * Define the max malloc length for a DRM.
 */
#define DRM_MAX_MALLOC_LEN          (50 * 1024) /* 50K */

#define DRM_ONE_AES_BLOCK_LEN       16
#define DRM_TWO_AES_BLOCK_LEN       32

typedef struct _T_DRM_DM_Binary_Node {
    uint8_t boundary[256];
} T_DRM_DM_Binary_Node;

typedef struct _T_DRM_DM_Base64_Node {
    uint8_t boundary[256];
    uint8_t b64DecodeData[4];
    int32_t b64DecodeDataLen;
} T_DRM_DM_Base64_Node;

typedef struct _T_DRM_Dcf_Node {
    uint8_t rightsIssuer[256];
    int32_t encContentLength;
    uint8_t aesDecData[16];
    int32_t aesDecDataLen;
    int32_t aesDecDataOff;
    uint8_t aesBackupBuf[16];
    int32_t bAesBackupBuf;
} T_DRM_Dcf_Node;

typedef struct _T_DRM_Session_Node {
    int32_t sessionId;
    int32_t inputHandle;
    int32_t mimeType;
    int32_t (*getInputDataLengthFunc)(int32_t inputHandle);
    int32_t (*readInputDataFunc)(int32_t inputHandle, uint8_t* buf, int32_t bufLen);
    int32_t (*seekInputDataFunc)(int32_t inputHandle, int32_t offset);
    int32_t deliveryMethod;
    int32_t transferEncoding;
    uint8_t contentType[64];
    int32_t contentLength;
    int32_t contentOffset;
    uint8_t contentID[256];
    uint8_t* rawContent;
    int32_t rawContentLen;
    int32_t bEndData;
    uint8_t* readBuf;
    int32_t readBufLen;
    int32_t readBufOff;
    void* infoStruct;
    struct _T_DRM_Session_Node* next;
} T_DRM_Session_Node;

#ifdef __cplusplus
}
#endif

#endif /* __DRM_INNER_H__ */
