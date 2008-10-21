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

#ifndef __WBXML_TINYPARSER_H__
#define __WBXML_TINYPARSER_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <drm_common_types.h>

#define REL_TAG_RIGHTS                                       0x05
#define REL_TAG_CONTEXT                                      0x06
#define REL_TAG_VERSION                                      0x07
#define REL_TAG_UID                                          0x08
#define REL_TAG_AGREEMENT                                    0x09
#define REL_TAG_ASSET                                        0x0A
#define REL_TAG_KEYINFO                                      0x0B
#define REL_TAG_KEYVALUE                                     0x0C
#define REL_TAG_PERMISSION                                   0x0D
#define REL_TAG_PLAY                                         0x0E
#define REL_TAG_DISPLAY                                      0x0F
#define REL_TAG_EXECUTE                                      0x10
#define REL_TAG_PRINT                                        0x11
#define REL_TAG_CONSTRAINT                                   0x12
#define REL_TAG_COUNT                                        0x13
#define REL_TAG_DATETIME                                     0x14
#define REL_TAG_START                                        0x15
#define REL_TAG_END                                          0x16
#define REL_TAG_INTERVAL                                     0x17

#define REL_CHECK_WBXML_HEADER(x) ((x != NULL) && (x[0] == 0x03) && (x[1] == 0x0E) && (x[2] == 0x6A))

#ifdef __cplusplus
}
#endif

#endif /* __WBXML_TINYPARSER_H__ */
