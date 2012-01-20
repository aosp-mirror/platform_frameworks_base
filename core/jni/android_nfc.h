/*
 * Copyright (C) 2010 The Android Open Source Project
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

/*
 * Contains the bare minimum header so that framework NFC jni can link
 * against NFC native library
 */

#ifndef __ANDROID_NFC_H__
#define __ANDROID_NFC_H__

#define LOG_TAG "NdefMessage"
#include <utils/Log.h>

extern "C" {

#if 0
  #define TRACE(...) ALOG(LOG_DEBUG, "NdefMessage", __VA_ARGS__)
#else
  #define TRACE(...)
#endif

typedef struct phFriNfc_NdefRecord {
    uint8_t                 Flags;
    uint8_t                 Tnf;
    uint8_t                 TypeLength;
    uint8_t                *Type;
    uint8_t                 IdLength;
    uint8_t                *Id;
    uint32_t                PayloadLength;
    uint8_t                *PayloadData;
} phFriNfc_NdefRecord_t;

uint16_t phFriNfc_NdefRecord_GetRecords(uint8_t*      pBuffer,
                                        uint32_t      BufferLength,
                                        uint8_t*      pRawRecords[ ],
                                        uint8_t       IsChunked[ ],
                                        uint32_t*     pNumberOfRawRecords
                                        );
uint16_t phFriNfc_NdefRecord_Parse(phFriNfc_NdefRecord_t* pRecord,
                                   uint8_t*               pRawRecord);

uint16_t phFriNfc_NdefRecord_Generate(phFriNfc_NdefRecord_t*  pRecord,
                                      uint8_t*                pBuffer,
                                      uint32_t                MaxBufferSize,
                                      uint32_t*               pBytesWritten
                                      );
}

#endif
