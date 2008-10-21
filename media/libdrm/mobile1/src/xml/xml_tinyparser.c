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

#include <xml/xml_tinyParser.h>

int32_t xml_errno;

#ifdef XML_DOM_PARSER

#define XML_IS_WHITESPACE(x) ((x) == '\t' || (x) == '\n' || (x) == ' ' || (x) == '\r')
#define XML_IS_NAMECHAR(ch) (isalpha(ch) || isdigit(ch) || ch ==':' || \
                             ch == '_' || ch == '-' || ch =='.')

static uint8_t *xml_ignore_blank(uint8_t *buffer)
{
    if (NULL == buffer)
        return NULL;

    while (XML_IS_WHITESPACE(*buffer))
        buffer++;

    return buffer;
}

static uint8_t *xml_goto_tagend(uint8_t *buffer)
{
    int32_t nameLen, valueLen;
    uint8_t *name, *value;

    if (NULL == buffer)
        return NULL;

    /* Ignore the start-tag */
    if (*buffer == '<') {
        buffer++;
        while (buffer != NULL && XML_IS_NAMECHAR(*buffer))
            buffer++;
        if (NULL == buffer)
            return NULL;
    }

    do {
        if (NULL == (buffer = xml_ignore_blank(buffer)))
            return NULL;

        if (*buffer == '>' || (*buffer == '/' && *(buffer + 1) == '>'))
            return buffer;

        if (NULL ==
            XML_DOM_getAttr(buffer, &name, &nameLen, &value, &valueLen))
            return NULL;

        buffer = value + valueLen + 1;
    } while (*buffer != '\0');

    return NULL;
}

static uint8_t *xml_match_tag(uint8_t *buffer)
{
    int32_t tagLen, tagType, bal;

    if (NULL == buffer)
        return NULL;

    bal = 0;
    do {
        if (NULL == (buffer = XML_DOM_getTag(buffer, &tagLen, &tagType)))
            return NULL;

        switch (tagType) {
        case XML_TAG_SELF:
        case XML_TAG_START:
            if (NULL == (buffer = xml_goto_tagend(buffer + tagLen + 1)))
                return NULL;
            if (strncmp((char *)buffer, "/>", 2) == 0) {
                buffer += 2;
            } else {
                bal++;
            }
            break;

        case XML_TAG_END:
            if (bal <= 0)
                return NULL;
            buffer = buffer + tagLen + 2;
            bal--;
            break;
        }
    } while (bal != 0);

    return buffer;
}

uint8_t *XML_DOM_getAttr(uint8_t *buffer, uint8_t **pName, int32_t *nameLen,
                      uint8_t **pValue, int32_t *valueLen)
{
    uint8_t charQuoted;

    if (NULL == buffer) {
        XML_ERROR(XML_ERROR_BUFFER_NULL);
        return NULL;
    }

    /* Ignore the tag */
    if (*buffer == '<') {
        buffer++;
        /* Ignore the STag */
        while (buffer != NULL && XML_IS_NAMECHAR(*buffer))
            buffer++;
        if (NULL == buffer)
            return NULL;
    }

    if (NULL == (buffer = xml_ignore_blank(buffer))) {
        XML_ERROR(XML_ERROR_BUFFER_NULL);
        return NULL;
    }

    /* Name */
    *pName = buffer;
    while (buffer != NULL && XML_IS_NAMECHAR(*buffer))
        buffer++;
    if (NULL == buffer) {
        XML_ERROR(XML_ERROR_ATTR_NAME);
        return NULL;
    }
    *nameLen = buffer - *pName;
    if (*nameLen <= 0) {
        XML_ERROR(XML_ERROR_ATTR_NAME);
        return NULL;
    }

    /* '=' */
    buffer = xml_ignore_blank(buffer);
    if (NULL == buffer || *buffer != '=') {
        XML_ERROR(XML_ERROR_ATTR_MISSED_EQUAL);
        return NULL;
    }

    /* Value */
    buffer++;
    buffer = xml_ignore_blank(buffer);
    if (NULL == buffer || (*buffer != '"' && *buffer != '\'')) {
        XML_ERROR(XML_ERROR_ATTR_VALUE);
        return NULL;
    }
    charQuoted = *buffer++;
    *pValue = buffer;
    while (*buffer != '\0' && *buffer != charQuoted)
        buffer++;
    if (*buffer != charQuoted) {
        XML_ERROR(XML_ERROR_ATTR_VALUE);
        return NULL;
    }
    *valueLen = buffer - *pValue;

    XML_ERROR(XML_ERROR_OK);

    return buffer + 1;
}

uint8_t *XML_DOM_getValue(uint8_t *buffer, uint8_t **pValue, int32_t *valueLen)
{
    uint8_t *pEnd;

    if (NULL == buffer) {
        XML_ERROR(XML_ERROR_BUFFER_NULL);
        return NULL;
    }

    /* Ignore the STag */
    if (*buffer == '<') {
        buffer++;
        /* If it's an end_tag, no value should be returned */
        if (*buffer == '/') {
            *valueLen = 0;
            XML_ERROR(XML_ERROR_NOVALUE);
            return NULL;
        }

        while (buffer != NULL && XML_IS_NAMECHAR(*buffer))
            buffer++;
        if (NULL == buffer) {
            XML_ERROR(XML_ERROR_BUFFER_NULL);
            return NULL;
        }

        if (NULL == (buffer = xml_goto_tagend(buffer))) {
            XML_ERROR(XML_ERROR_PROPERTY_END);
            return NULL;
        }
    }

    /* <test/> node found */
    if (*buffer == '/') {
        if (*(buffer + 1) != '>') {
            XML_ERROR(XML_ERROR_PROPERTY_END);
            return NULL;
        }
        XML_ERROR(XML_ERROR_OK);
        *valueLen = 0;
        return buffer;
    }

    if (*buffer == '>')
        buffer++;

    if (NULL == (buffer = xml_ignore_blank(buffer))) {
        XML_ERROR(XML_ERROR_BUFFER_NULL);
        return NULL;
    }

    /* the following is a tag instead of the value */
    if (*buffer == '<') { /* nono value, such as <test></test> */
        buffer++;
        if (*buffer != '/') {
            XML_ERROR(XML_ERROR_ENDTAG);
            return NULL;
        }
        *valueLen = 0;
        XML_ERROR(XML_ERROR_OK);
        return NULL;
    }

    *pValue = buffer;
    pEnd = NULL;
    while (*buffer != '\0' && *buffer != '<') {
        if (!XML_IS_WHITESPACE(*buffer))
            pEnd = buffer;
        buffer++;
    }
    if (*buffer != '<' || pEnd == NULL) {
        XML_ERROR(XML_ERROR_VALUE);
        return NULL;
    }

    *valueLen = pEnd - *pValue + 1;

    buffer++;
    if (*buffer != '/') {
        XML_ERROR(XML_ERROR_ENDTAG);
        return NULL;
    }

    XML_ERROR(XML_ERROR_OK);

    return buffer - 1;
}

uint8_t *XML_DOM_getTag(uint8_t *buffer, int32_t *tagLen, int32_t *tagType)
{
    uint8_t *pStart;

    /* WARNING: <!-- --> comment is not supported in this verison */
    if (NULL == buffer) {
        XML_ERROR(XML_ERROR_BUFFER_NULL);
        return NULL;
    }

    do {
        while (*buffer != '<') {
            if (*buffer == '\0') {
                XML_ERROR(XML_ERROR_BUFFER_NULL);
                return NULL;
            }

            if (*buffer == '\"' || *buffer == '\'') {
                uint8_t charQuoted = *buffer;
                buffer++;
                while (*buffer != '\0' && *buffer != charQuoted)
                    buffer++;
                if (*buffer == '\0') {
                    XML_ERROR(XML_ERROR_BUFFER_NULL);
                    return NULL;
                }
            }
            buffer++;
        }
        buffer++;
    } while (*buffer == '!' || *buffer == '?');

    pStart = buffer - 1;

    if (*buffer == '/') {
        buffer++;
        *tagType = XML_TAG_END;
    } else {
        /* check here if it is self-end-tag */
        uint8_t *pCheck = xml_goto_tagend(pStart);
        if (pCheck == NULL) {
            XML_ERROR(XML_ERROR_PROPERTY_END);
            return NULL;
        }

        if (*pCheck == '>')
            *tagType = XML_TAG_START;
        else if (strncmp((char *)pCheck, "/>", 2) == 0)
            *tagType = XML_TAG_SELF;
        else {
            XML_ERROR(XML_ERROR_PROPERTY_END);
            return NULL;
        }
    }

    while (buffer != NULL && XML_IS_NAMECHAR(*buffer))
        buffer++;
    if (NULL == buffer) {
        XML_ERROR(XML_ERROR_BUFFER_NULL);
        return NULL;
    }

    if (*tagType == XML_TAG_END)
        *tagLen = buffer - pStart - 2;
    else
        *tagLen = buffer - pStart - 1;

    XML_ERROR(XML_ERROR_OK);

    return pStart;
}

uint8_t *XML_DOM_getNode(uint8_t *buffer, const uint8_t *const node)
{
    uint8_t *pStart;
    uint8_t buf[XML_MAX_PROPERTY_LEN + 2];
    uint8_t *nodeStr = buf;
    uint8_t *retPtr = NULL;
    int32_t tagLen, tagType;
    uint8_t *lastNode = (uint8_t *)"";

    if (NULL == buffer) {
        XML_ERROR(XML_ERROR_BUFFER_NULL);
        return NULL;
    }

    strncpy((char *)nodeStr, (char *)node, XML_MAX_PROPERTY_LEN);
    strcat((char *)nodeStr, "\\");
    pStart = (uint8_t *)strchr((char *)nodeStr, '\\');

    while (pStart != NULL) {
        *pStart = '\0';

        /* get the first start_tag from buffer */
        if (NULL == (buffer = XML_DOM_getTag(buffer, &tagLen, &tagType))) {
            XML_ERROR(XML_ERROR_NO_SUCH_NODE);
            return NULL;
        }

        if (tagType == XML_TAG_END) {
            if (0 ==
                strncmp((char *)lastNode, (char *)(buffer + 2), strlen((char *)lastNode)))
                XML_ERROR(XML_ERROR_NO_SUCH_NODE);
            else
                XML_ERROR(XML_ERROR_NO_START_TAG);
            return NULL;
        }

        /* wrong node, contiue to fetch the next node */
        if ((int32_t) strlen((char *)nodeStr) != tagLen
            || strncmp((char *)nodeStr, (char *)(buffer + 1), tagLen) != 0) {
            /* we should ignore all the middle code */
            buffer = xml_match_tag(buffer);
            continue;
        }

        retPtr = buffer;        /* retPtr starts with '<xxx>' */
        buffer += (tagLen + 1);

        if (tagType == XML_TAG_SELF) {
            nodeStr = pStart + 1;
            break;
        }

        lastNode = nodeStr;
        nodeStr = pStart + 1;
        pStart = (uint8_t *)strchr((char *)nodeStr, '\\');
    }

    /* Check 5: nodeStr should be empty here */
    if (*nodeStr != '\0') {
        XML_ERROR(XML_ERROR_NO_SUCH_NODE);
        return NULL;
    }

    XML_ERROR(XML_ERROR_OK);

    return retPtr;
}

uint8_t *XML_DOM_getNodeValue(uint8_t *buffer, uint8_t *node,
                           uint8_t **value, int32_t *valueLen)
{
    uint8_t *pStart;
    uint8_t *lastTag;

    if (NULL == node || NULL == buffer) {
        XML_ERROR(XML_ERROR_BUFFER_NULL);
        return NULL;
    }

    lastTag = node + strlen((char *)node) - 1;
    while (lastTag >= node && *lastTag != '\\')
        lastTag--;
    lastTag++;

    if (NULL == (pStart = XML_DOM_getNode(buffer, node)))
        return NULL;

    pStart += (strlen((char *)lastTag) + 1);

    if (NULL == (pStart = xml_goto_tagend(pStart))) {
        XML_ERROR(XML_ERROR_PROPERTY_END);
        return NULL;
    }

    if (NULL == (pStart = XML_DOM_getValue(pStart, value, valueLen)))
        return NULL;

    /* Check the end tag */
#ifdef XML_DOM_CHECK_ENDTAG
    if (strncmp((char *)pStart, "/>", 2) == 0) {

    } else if (strncmp((char *)lastTag, (char *)(pStart + 2), strlen((char *)lastTag)) !=
               0) {
        XML_ERROR(XML_ERROR_ENDTAG);
        return NULL;
    }
#endif

    XML_ERROR(XML_ERROR_OK);

    return *value;
}

uint8_t *XML_DOM_getNextNode(uint8_t *buffer, uint8_t **pNodeName, int32_t *nodenameLen)
{
    int32_t tagType;

    if (NULL == buffer)
        return NULL;

    do {
        if (NULL ==
            (buffer = XML_DOM_getTag(buffer + 1, nodenameLen, &tagType))) {
            XML_ERROR(XML_ERROR_NO_SUCH_NODE);
            return NULL;
        }
    } while (tagType == XML_TAG_END);

    *pNodeName = buffer + 1;

    XML_ERROR(XML_ERROR_OK);

    return buffer;
}

#endif /* XML_DOM_PARSER */

#ifdef WBXML_DOM_PARSER

#ifdef WBXML_OLD_VERSION
uint8_t *WBXML_DOM_getNode(uint8_t *buffer, int32_t bufferLen,
                                 uint8_t *node)
{
    int32_t i = 0, j = 0;

    if (NULL == buffer || node == NULL) {
        XML_ERROR(XML_ERROR_BUFFER_NULL);
        return NULL;
    }

    while (i < bufferLen) {
        if (WBXML_GET_TAG(buffer[i]) == WBXML_GET_TAG(node[j])) {
            j++;
            if (node[j] == '\0')
                break;

            /* Check if there is the content(it should have content) */
            if (!WBXML_HAS_CONTENT(buffer[i])) {
                /*XML_ERROR(WBXML_ERROR_MISSED_CONTENT); */
                XML_ERROR(XML_ERROR_NO_SUCH_NODE);
                return NULL;
            }

            /* Ignore the attrib filed */
            if (WBXML_HAS_ATTR(buffer[i])) {
                while (i < bufferLen && buffer[i] != WBXML_ATTR_END)
                    i++;
                if (i >= bufferLen)
                    break;
            }
        }
        i++;

        /* Ignore the content filed */
        if (buffer[i] == WBXML_STR_I) {
            while (i < bufferLen && buffer[i] != WBXML_END)
                i++;
            if (i >= bufferLen)
                break;
            i++;
        }
    }

    if (i >= bufferLen) {
        XML_ERROR(XML_ERROR_NO_SUCH_NODE);
        return NULL;
    }

    XML_ERROR(XML_ERROR_OK);

    return buffer + i + 1;
}

uint8_t *WBXML_DOM_getNodeValue(uint8_t *buffer, int32_t bufferLen,
                                      uint8_t *node,
                                      uint8_t **value, int32_t *valueLen)
{
    int32_t i;
    uint8_t *pEnd;

    *value = NULL;
    *valueLen = 0;

    pEnd = buffer + bufferLen;
    buffer = WBXML_DOM_getNode(buffer, bufferLen, node);
    if (NULL == buffer) {
        XML_ERROR(XML_ERROR_NO_SUCH_NODE);
        return NULL;
    }

    if (*buffer == WBXML_OPAUE) {
        buffer++;
        *valueLen = WBXML_GetUintVar(buffer, &i);
        if (*valueLen < 0) {
            XML_ERROR(WBXML_ERROR_MBUINT32);
            return NULL;
        }
        buffer += i;
        *value = buffer;
        return *value;
    }

    if (*buffer != WBXML_STR_I) {
        XML_ERROR(WBXML_ERROR_MISSED_STARTTAG);
        return NULL;
    }

    buffer++;

    i = 0;
    while ((buffer + i) < pEnd && buffer[i] != WBXML_END)
        i++;

    if (buffer[i] != WBXML_END) {
        XML_ERROR(WBXML_ERROR_MISSED_ENDTAG);
        return NULL;
    }

    *value = buffer;
    *valueLen = i;
    XML_ERROR(XML_ERROR_OK);

    return *value;
}
#endif /* WBXML_OLD_VERSION */

#define MAX_UINT_VAR_BYTE                                    4
#define UINTVAR_INVALID                                      -1
int32_t WBXML_GetUintVar(const uint8_t *const buffer, int32_t *len)
{
    int32_t i, byteLen;
    int32_t sum;

    byteLen = 0;
    while ((buffer[byteLen] & 0x80) > 0 && byteLen < MAX_UINT_VAR_BYTE)
        byteLen++;

    if (byteLen > MAX_UINT_VAR_BYTE)
        return UINTVAR_INVALID;

    *len = byteLen + 1;
    sum = buffer[byteLen];
    for (i = byteLen - 1; i >= 0; i--)
        sum += ((buffer[i] & 0x7F) << 7 * (byteLen - i));

    return sum;
}

XML_BOOL WBXML_DOM_Init(WBXML * pWbxml, uint8_t *buffer,
                        int32_t bufferLen)
{
    int32_t num, len;

    pWbxml->End = buffer + bufferLen;
    pWbxml->version = *buffer++;
    if (UINTVAR_INVALID == (num = WBXML_GetUintVar(buffer, &len)))
        return XML_FALSE;
    buffer += len;
    pWbxml->publicid = num;
    if (UINTVAR_INVALID == (num = WBXML_GetUintVar(buffer, &len)))
        return XML_FALSE;
    buffer += len;
    pWbxml->charset = num;
    if (UINTVAR_INVALID == (num = WBXML_GetUintVar(buffer, &len)))
        return XML_FALSE;
    buffer += len;
    pWbxml->strTable = buffer;
    pWbxml->strTableLen = num;
    buffer += num;
    pWbxml->curPtr = pWbxml->Content = buffer;
    pWbxml->depth = 0;

    return XML_TRUE;
}

void WBXML_DOM_Rewind(WBXML * pWbxml)
{
    pWbxml->curPtr = pWbxml->Content;
}

XML_BOOL WBXML_DOM_Eof(WBXML * pWbxml)
{
    if (pWbxml->curPtr > pWbxml->End)
        return XML_TRUE;

    return XML_FALSE;
}

uint8_t WBXML_DOM_GetTag(WBXML * pWbxml)
{
    uint8_t tagChar;

    if (pWbxml->curPtr > pWbxml->End)
        return XML_EOF;

    tagChar = *pWbxml->curPtr;
    pWbxml->curPtr++;

    if (WBXML_GET_TAG(tagChar) == WBXML_CONTENT_END)
        pWbxml->depth--;
    else
        pWbxml->depth++;

    return tagChar;
}

uint8_t WBXML_DOM_GetChar(WBXML * pWbxml)
{
    return *pWbxml->curPtr++;
}

void WBXML_DOM_Seek(WBXML * pWbxml, int32_t offset)
{
    pWbxml->curPtr += offset;
}

uint8_t WBXML_DOM_GetUIntVar(WBXML * pWbxml)
{
    int32_t num, len;

    num = WBXML_GetUintVar(pWbxml->curPtr, &len);
    pWbxml->curPtr += len;

    return (uint8_t)num;
}

#ifdef XML_TREE_STRUCTURE

#ifdef DEBUG_MODE
static int32_t malloc_times = 0;
static int32_t free_times = 0;
void XML_PrintMallocInfo()
{
    printf("====XML_PrintMallocInfo====\n");
    printf(" Total malloc times:%d\n", malloc_times);
    printf(" Total free   times:%d\n", free_times);
    printf("===========================\n");
}
#endif

void *xml_malloc(int32_t size)
{
#ifdef DEBUG_MODE
    malloc_times++;
#endif
    return malloc(size);
}

void xml_free(void *buffer)
{
#ifdef DEBUG_MODE
    free_times++;
#endif
    free(buffer);
}

XML_TREE *xml_tree_fillnode(uint8_t **buf, int32_t tagLen)
{
    XML_TREE *Tree;
    uint8_t *pAttr, *pName, *pValue;
    int32_t nameLen, valueLen;
    uint8_t *buffer = *buf;

    if (NULL == (Tree = (XML_TREE *) xml_malloc(sizeof(XML_TREE))))
        return NULL;
    memset(Tree, 0, sizeof(XML_TREE));

    strncpy((char *)Tree->tag, (char *)++buffer, tagLen);
    buffer += tagLen;
    pAttr = buffer;

    /* attribute */
    while (NULL !=
           (pAttr =
            XML_DOM_getAttr(pAttr, &pName, &nameLen, &pValue,
                            &valueLen))) {
        XML_TREE_ATTR *attr;
        if (NULL ==
            (attr = (XML_TREE_ATTR *) xml_malloc(sizeof(XML_TREE_ATTR))))
            return NULL;
        memset(attr, 0, sizeof(XML_TREE_ATTR));
        strncpy((char *)attr->name, (char *)pName, nameLen);
        strncpy((char *)attr->value, (char *)pValue, valueLen);
        buffer = pValue + valueLen + 1;

        if (NULL != Tree->attr) // no attribute now
            Tree->last_attr->next = attr;
        else
            Tree->attr = attr;
        Tree->last_attr = attr;
    }

    /* value */
    pAttr = XML_DOM_getValue(buffer, &pValue, &valueLen);
    if (pAttr != NULL && valueLen > 0) {
        strncpy((char *)Tree->value, (char *)pValue, valueLen);
        buffer = pValue + valueLen;
    }

    *buf = buffer;
    return Tree;
}

XML_TREE *XML_makeTree(uint8_t **buf)
{
    uint8_t *pBuf;
    int32_t valueLen, tagType;
    uint8_t *buffer = *buf;
    XML_TREE *TreeHead = NULL;

    if (NULL == (buffer = XML_DOM_getTag(buffer, &valueLen, &tagType)))
        return NULL;
    if (XML_TAG_END == tagType)
        return NULL;
    if (NULL == (TreeHead = xml_tree_fillnode(&buffer, valueLen)))
        return NULL;
    if (XML_TAG_SELF == tagType) {
        *buf = buffer;
        return TreeHead;
    }

    do {
        if (NULL == (pBuf = XML_DOM_getTag(buffer, &valueLen, &tagType)))
            return NULL;

        switch (tagType) {
        case XML_TAG_SELF:
        case XML_TAG_START:
            if (NULL == TreeHead->child)
                TreeHead->child = XML_makeTree(&buffer);
            else if (NULL == TreeHead->child->last_brother) {
                TreeHead->child->brother = XML_makeTree(&buffer);
                TreeHead->child->last_brother = TreeHead->child->brother;
            } else {
                TreeHead->child->last_brother->brother =
                    XML_makeTree(&buffer);
                TreeHead->child->last_brother =
                    TreeHead->child->last_brother->brother;
            }
            break;
        case XML_TAG_END:
            *buf = pBuf;
            return TreeHead;
        }
        buffer++;
    } while (1);
}

void XML_freeTree(XML_TREE * pTree)
{
    XML_TREE *p, *pNext;
    XML_TREE_ATTR *pa, *lastpa;

    if (NULL == pTree)
        return;

    p = pTree->brother;
    while (NULL != p) {
        pNext = p->brother;
        p->brother = NULL;
        XML_freeTree(p);
        p = pNext;
    }

    if (NULL != pTree->child)
        XML_freeTree(pTree->child);

    pa = pTree->attr;
    while (NULL != pa) {
        lastpa = pa;
        pa = pa->next;
        xml_free(lastpa);
    }
    xml_free(pTree);
}

#endif /* XML_TREE_STRUCTURE */

#endif /* WBXML_DOM_PARSER */
