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

#ifndef __XML_TINYPARSER_H__
#define __XML_TINYPARSER_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <drm_common_types.h>

#define XML_DOM_PARSER
#define WBXML_DOM_PARSER
#define XML_DOM_CHECK_ENDTAG
#define XML_ENABLE_ERRNO
#define WBXML_OLD_VERSION /* for drm only */

#ifdef DEBUG_MODE
void XML_PrintMallocInfo();
#endif /* DEBUG_MODE */

#define XML_TRUE                                             1
#define XML_FALSE                                            0
#define XML_EOF                                              0
#define XML_TAG_START                                        0
#define XML_TAG_END                                          1
#define XML_TAG_SELF                                         2

#define XML_MAX_PROPERTY_LEN                                 256
#define XML_MAX_ATTR_NAME_LEN                                256
#define XML_MAX_ATTR_VALUE_LEN                               256
#define XML_MAX_VALUE_LEN                                    256

#define XML_ERROR_OK                                         0
#define XML_ERROR_BUFFER_NULL                                -1
#define XML_ERROR_ATTR_NAME                                  -2
#define XML_ERROR_ATTR_MISSED_EQUAL                          -3
#define XML_ERROR_PROPERTY_NAME                              -4
#define XML_ERROR_ATTR_VALUE                                 -5
#define XML_ERROR_ENDTAG                                     -6
#define XML_ERROR_NO_SUCH_NODE                               -7
#define XML_ERROR_PROPERTY_END                               -8
#define XML_ERROR_VALUE                                      -9
#define XML_ERROR_NO_START_TAG                               -14
#define XML_ERROR_NOVALUE                                    -15

#define WBXML_ERROR_MISSED_CONTENT                           -10
#define WBXML_ERROR_MBUINT32                                 -11
#define WBXML_ERROR_MISSED_STARTTAG                          -12
#define WBXML_ERROR_MISSED_ENDTAG                            -13

#ifdef XML_ENABLE_ERRNO
extern int32_t xml_errno;
#define XML_ERROR(x) do { xml_errno = x; } while (0)
#else  /* XML_ENABLE_ERRNO */
#define XML_ERROR
#endif /* XML_ENABLE_ERRNO */

#ifdef XML_DOM_PARSER
uint8_t *XML_DOM_getNode(uint8_t *buffer, const uint8_t *const node);
uint8_t *XML_DOM_getNodeValue(uint8_t *buffer, uint8_t *node,
                           uint8_t **value, int32_t *valueLen);

uint8_t *XML_DOM_getValue(uint8_t *buffer, uint8_t **pValue, int32_t *valueLen);
uint8_t *XML_DOM_getAttr(uint8_t *buffer, uint8_t **pName, int32_t *nameLen,
                      uint8_t **pValue, int32_t *valueLen);

uint8_t *XML_DOM_getNextNode(uint8_t *buffer, uint8_t **pNodeName,
                          int32_t *nodenameLen);

uint8_t *XML_DOM_getTag(uint8_t *buffer, int32_t *tagLen, int32_t *tagType);
#endif /* XML_DOM_PARSER */

#ifdef WBXML_DOM_PARSER

#define WBXML_WITH_ATTR                                      0x80
#define WBXML_WITH_CONTENT                                   0x40
#define WBXML_ATTR_END                                       0x01
#define WBXML_CONTENT_END                                    0x01

#define WBXML_SWITCH_PAGE                                    0x00
#define WBXML_STR_I                                          0x03
#define WBXML_END                                            0x00
#define WBXML_OPAUE                                          0xC3
#define WBXML_STR_T                                          0x83
#define WBXML_OPAQUE                                         0xC3

#define WBXML_GET_TAG(x) ((x) & 0x3F) /* get 6-digits */
#define WBXML_HAS_ATTR(x) ((x) & WBXML_WITH_ATTR)
#define WBXML_HAS_CONTENT(x) ((x) & WBXML_WITH_CONTENT)

typedef struct _WBXML {
    uint8_t version;
    uint8_t unUsed[3];
    uint32_t publicid;
    uint32_t charset;
    int32_t strTableLen;
    uint8_t *strTable;
    uint8_t *Content;
    uint8_t *End;
    uint8_t *curPtr;
    int32_t depth;
} WBXML;

typedef int32_t XML_BOOL;

#ifdef WBXML_OLD_VERSION
uint8_t *WBXML_DOM_getNode(uint8_t *buffer, int32_t bufferLen,
                                 uint8_t *node);
uint8_t *WBXML_DOM_getNodeValue(uint8_t *buffer, int32_t bufferLen,
                                      uint8_t *node,
                                      uint8_t **value,
                                      int32_t *valueLen);
#endif /* WBXML_OLD_VERSION */

XML_BOOL WBXML_DOM_Init(WBXML * pWbxml, uint8_t *buffer,
                        int32_t bufferLen);
XML_BOOL WBXML_DOM_Eof(WBXML * pWbxml);
uint8_t WBXML_DOM_GetTag(WBXML * pWbxml);
uint8_t WBXML_DOM_GetChar(WBXML * pWbxml);
uint8_t WBXML_DOM_GetUIntVar(WBXML * pWbxml);
void WBXML_DOM_Rewind(WBXML * pWbxml);
void WBXML_DOM_Seek(WBXML * pWbxml, int32_t offset);
int32_t WBXML_GetUintVar(const uint8_t *const buffer, int32_t *len);

#endif /* WBXML_DOM_PARSER */

#ifdef XML_TREE_STRUCTURE

typedef struct _XML_TREE_ATTR XML_TREE_ATTR;
struct _XML_TREE_ATTR {
    uint8_t name[XML_MAX_ATTR_VALUE_LEN];
    uint8_t value[XML_MAX_ATTR_VALUE_LEN];
    XML_TREE_ATTR *next;
};

typedef struct _XML_TREE XML_TREE;
struct _XML_TREE {
    uint8_t tag[XML_MAX_PROPERTY_LEN];
    uint8_t value[XML_MAX_VALUE_LEN];
    XML_TREE_ATTR *attr;
    XML_TREE_ATTR *last_attr;
    XML_TREE *brother;
    XML_TREE *last_brother;
    XML_TREE *child;
};

XML_TREE *XML_makeTree(uint8_t **buf);
void XML_freeTree(XML_TREE * pTree);

#endif /* XML_TREE_STRUCTURE */

#ifdef __cplusplus
}
#endif

#endif /* __XML_TINYPARSER_H__ */
