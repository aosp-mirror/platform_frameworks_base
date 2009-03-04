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

#include <parser_rel.h>
#include <parser_dm.h>
#include <xml_tinyParser.h>
#include <wbxml_tinyparser.h>
#include <drm_decoder.h>
#include <svc_drm.h>

/* See parser_rel.h */
int32_t drm_monthDays(int32_t year, int32_t month)
{
    switch (month) {
    case 1:
    case 3:
    case 5:
    case 7:
    case 8:
    case 10:
    case 12:
        return 31;
    case 4:
    case 6:
    case 9:
    case 11:
        return 30;
    case 2:
        if (((year % 4 == 0) && (year % 100 != 0)) || (year % 400 == 0))
            return 29;
        else
            return 28;
    default:
        return -1;
    }
}

int32_t drm_checkDate(int32_t year, int32_t month, int32_t day,
                      int32_t hour, int32_t min, int32_t sec)
{
    if (month >= 1 && month <= 12 &&
        day >= 1 && day <= drm_monthDays(year, month) &&
        hour >= 0 && hour <= 23 &&
        min >= 0 && min <= 59 && sec >= 0 && sec <= 59)
        return 0;
    else
        return -1;
}

static int32_t drm_getStartEndTime(uint8_t * pValue, int32_t valueLen,
                                   T_DRM_DATETIME * dateTime)
{
    int32_t year, mon, day, hour, min, sec;
    uint8_t pTmp[64] = {0};

    strncpy((char *)pTmp, (char *)pValue, valueLen);
    {
        uint8_t * pHead = pTmp;
        uint8_t * pEnd = NULL;
        uint8_t tmpByte;

        /** get year */
        pEnd = (uint8_t *)strstr((char *)pHead, "-");
        if(NULL == pEnd)
            return FALSE;
        tmpByte = *pEnd;
        *pEnd = '\0';
        year = atoi((char *)pHead);
        pHead = pEnd + 1;
        *pEnd = tmpByte;

        /** get month */
        pEnd = (uint8_t *)strstr((char *)pHead, "-");
        if(NULL == pEnd)
            return FALSE;
        tmpByte = *pEnd;
        *pEnd = '\0';
        mon = atoi((char *)pHead);
        pHead = pEnd + 1;
        *pEnd = tmpByte;

        /** get day */
        pEnd = (uint8_t *)strstr((char *)pHead, "T");
        if(NULL == pEnd)
            return FALSE;
        tmpByte = *pEnd;
        *pEnd = '\0';
        day = atoi((char *)pHead);
        pHead = pEnd + 1;
        *pEnd = tmpByte;

        /** get hour */
        pEnd = (uint8_t *)strstr((char *)pHead, ":");
        if(NULL == pEnd)
            return FALSE;
        tmpByte = *pEnd;
        *pEnd = '\0';
        hour = atoi((char *)pHead);
        pHead = pEnd + 1;
        *pEnd = tmpByte;

        /** get minute */
        pEnd = (uint8_t *)strstr((char *)pHead, ":");
        if(NULL == pEnd)
            return FALSE;
        tmpByte = *pEnd;
        *pEnd = '\0';
        min = atoi((char *)pHead);
        pHead = pEnd + 1;
        *pEnd = tmpByte;

        /** get second */
        sec = atoi((char *)pHead);
    }
    if (0 != drm_checkDate(year, mon, day, hour, min, sec))
        return FALSE;

    YMD_HMS_2_INT(year, mon, day, dateTime->date, hour, min, sec,
                  dateTime->time);
    return TRUE;
}

static int32_t drm_checkWhetherHasUnknowConstraint(uint8_t* drm_constrain)
{
    char* begin_constrain = "<o-ex:constraint>";
    char* end_constrain = "</o-ex:constraint>";
    char* constrain_begin = strstr((char*)drm_constrain,begin_constrain);
    char* constrain_end = strstr((char*)drm_constrain,end_constrain);
    uint32_t constrain_len = 0;

    if(NULL == constrain_begin)
        return FALSE;

    if(NULL == constrain_end)
        return TRUE;

    /* compute valid characters length */
    {
        uint32_t constrain_begin_len = strlen(begin_constrain);
        char* cur_pos = constrain_begin + constrain_begin_len;

        constrain_len = (constrain_end - constrain_begin) - constrain_begin_len;

        while(cur_pos < constrain_end){
            if(isspace(*cur_pos))
                constrain_len--;

            cur_pos++;
        }
    }

    /* check all constraints */
    {
        #define DRM_ALL_CONSTRAINT_COUNT 5

        int32_t i = 0;
        int32_t has_datetime = FALSE;
        int32_t has_start_or_end = FALSE;

        char* all_vaild_constraints[DRM_ALL_CONSTRAINT_COUNT][2] = {
            {"<o-dd:count>","</o-dd:count>"},
            {"<o-dd:interval>","</o-dd:interval>"},
            {"<o-dd:datetime>","</o-dd:datetime>"},
            {"<o-dd:start>","</o-dd:start>"},
            {"<o-dd:end>","</o-dd:end>"}
        };

        for(i = 0; i < DRM_ALL_CONSTRAINT_COUNT; i++){
            char*start = strstr((char*)drm_constrain,all_vaild_constraints[i][0]);

            if(start && (start < constrain_end)){
                char* end = strstr((char*)drm_constrain,all_vaild_constraints[i][1]);

                if(end && (end < constrain_end)){
                    if(0 == strncmp(all_vaild_constraints[i][0],"<o-dd:datetime>",strlen("<o-dd:datetime>"))){
                        constrain_len -= strlen(all_vaild_constraints[i][0]);
                        constrain_len -= strlen(all_vaild_constraints[i][1]);

                        if(0 == constrain_len)
                            return TRUE;

                        has_datetime = TRUE;
                        continue;
                    }

                    if((0 == strncmp(all_vaild_constraints[i][0],"<o-dd:start>",strlen("<o-dd:start>")))
                        || (0 == strncmp(all_vaild_constraints[i][0],"<o-dd:end>",strlen("<o-dd:end>")))){
                        if(FALSE == has_datetime)
                            return TRUE;
                        else
                            has_start_or_end = TRUE;
                    }

                    constrain_len -= (end - start);
                    constrain_len -= strlen(all_vaild_constraints[i][1]);

                    if(0 == constrain_len)
                        if(has_datetime != has_start_or_end)
                            return TRUE;
                        else
                            return FALSE;
                }
                else
                    return TRUE;
            }
        }

        if(has_datetime != has_start_or_end)
            return TRUE;

        if(constrain_len)
            return TRUE;
        else
            return FALSE;
    }
}

static int32_t drm_getRightValue(uint8_t * buffer, int32_t bufferLen,
                                 T_DRM_Rights * ro, uint8_t * operation,
                                 uint8_t oper_char)
{
    uint8_t *pBuf, *pValue;
    uint8_t sProperty[256];
    int32_t valueLen;
    int32_t year, mon, day, hour, min, sec;
    T_DRM_Rights_Constraint *pConstraint;
    int32_t *bIsAble;
    uint8_t *ret = NULL;
    int32_t flag = 0;

    if (operation == NULL) {
        switch (oper_char) {
        case REL_TAG_PLAY:
            pConstraint = &(ro->PlayConstraint);
            bIsAble = &(ro->bIsPlayable);
            break;
        case REL_TAG_DISPLAY:
            pConstraint = &(ro->DisplayConstraint);
            bIsAble = &(ro->bIsDisplayable);
            break;
        case REL_TAG_EXECUTE:
            pConstraint = &(ro->ExecuteConstraint);
            bIsAble = &(ro->bIsExecuteable);
            break;
        case REL_TAG_PRINT:
            pConstraint = &(ro->PrintConstraint);
            bIsAble = &(ro->bIsPrintable);
            break;
        default:
            return FALSE; /* The input parm is err */
        }
    } else {
        if (strcmp((char *)operation, "play") == 0) {
            pConstraint = &(ro->PlayConstraint);
            bIsAble = &(ro->bIsPlayable);
        } else if (strcmp((char *)operation, "display") == 0) {
            pConstraint = &(ro->DisplayConstraint);
            bIsAble = &(ro->bIsDisplayable);
        } else if (strcmp((char *)operation, "execute") == 0) {
            pConstraint = &(ro->ExecuteConstraint);
            bIsAble = &(ro->bIsExecuteable);
        } else if (strcmp((char *)operation, "print") == 0) {
            pConstraint = &(ro->PrintConstraint);
            bIsAble = &(ro->bIsPrintable);
        } else
            return FALSE; /* The input parm is err */
    }

    if (operation == NULL) {
        sprintf((char *)sProperty, "%c%c%c%c", REL_TAG_RIGHTS,
                     REL_TAG_AGREEMENT, REL_TAG_PERMISSION, oper_char);
        ret = WBXML_DOM_getNode(buffer, bufferLen, sProperty);
    } else {
        sprintf((char *)sProperty,
                     "o-ex:rights\\o-ex:agreement\\o-ex:permission\\o-dd:%s",
                     operation);
        ret = XML_DOM_getNode(buffer, sProperty);
    }
    CHECK_VALIDITY(ret);
    if (NULL == ret)
        return TRUE;
    WRITE_RO_FLAG(*bIsAble, 1, pConstraint->Indicator, DRM_NO_CONSTRAINT); /* If exit first assume have utter rights */
    flag = 1;

    if (operation == NULL) { /* If father element node is not exit then return */
        sprintf((char *)sProperty, "%c%c%c%c%c", REL_TAG_RIGHTS,
                     REL_TAG_AGREEMENT, REL_TAG_PERMISSION, oper_char,
                     REL_TAG_CONSTRAINT);
        ret = WBXML_DOM_getNode(buffer, bufferLen, sProperty);
    } else {
        sprintf((char *)sProperty,
                     "o-ex:rights\\o-ex:agreement\\o-ex:permission\\o-dd:%s\\o-ex:constraint",
                     operation);
        ret = XML_DOM_getNode(buffer, sProperty);
    }

    CHECK_VALIDITY(ret);
    if (ret == NULL)
        return TRUE;

    if(TRUE == drm_checkWhetherHasUnknowConstraint(ret))
        return FALSE;

    *bIsAble = 0;
    pConstraint->Indicator = DRM_NO_PERMISSION; /* If exit constraint assume have no rights */
    flag = 2;

    if (operation == NULL) {
        sprintf((char *)sProperty, "%c%c%c%c%c%c", REL_TAG_RIGHTS,
                     REL_TAG_AGREEMENT, REL_TAG_PERMISSION, oper_char,
                     REL_TAG_CONSTRAINT, REL_TAG_INTERVAL);
        pBuf =
            WBXML_DOM_getNodeValue(buffer, bufferLen, sProperty, (uint8_t **)&pValue,
                                   &valueLen);
    } else {
        sprintf((char *)sProperty,
                     "o-ex:rights\\o-ex:agreement\\o-ex:permission\\o-dd:%s\\o-ex:constraint\\o-dd:interval",
                     operation);
        pBuf = XML_DOM_getNodeValue(buffer, sProperty, &pValue, &valueLen);
    }
    CHECK_VALIDITY(pBuf);
    if (pBuf) { /* If interval element exit then get the value */
        uint8_t pTmp[64] = {0};

        strncpy((char *)pTmp, (char *)pValue, valueLen);
        {
            uint8_t * pHead = pTmp + 1;
            uint8_t * pEnd = NULL;
            uint8_t tmpChar;

            /** get year */
            pEnd = (uint8_t *)strstr((char *)pHead, "Y");
            if(NULL == pEnd)
                return FALSE;
            tmpChar = *pEnd;
            *pEnd = '\0';
            year = atoi((char *)pHead);
            pHead = pEnd + 1;
            *pEnd = tmpChar;

            /** get month */
            pEnd = (uint8_t *)strstr((char *)pHead, "M");
            if(NULL == pEnd)
                return FALSE;
            tmpChar = *pEnd;
            *pEnd = '\0';
            mon = atoi((char *)pHead);
            pHead = pEnd + 1;
            *pEnd = tmpChar;

            /** get day */
            pEnd = (uint8_t *)strstr((char *)pHead, "D");
            if(NULL == pEnd)
                return FALSE;
            tmpChar = *pEnd;
            *pEnd = '\0';
            day = atoi((char *)pHead);
            pHead = pEnd + 2;
            *pEnd = tmpChar;

            /** get hour */
            pEnd = (uint8_t *)strstr((char *)pHead, "H");
            if(NULL == pEnd)
                return FALSE;
            tmpChar = *pEnd;
            *pEnd = '\0';
            hour = atoi((char *)pHead);
            pHead = pEnd + 1;
            *pEnd = tmpChar;

            /** get minute */
            pEnd = (uint8_t *)strstr((char *)pHead, "M");
            if(NULL == pEnd)
                return FALSE;
            tmpChar = *pEnd;
            *pEnd = '\0';
            min = atoi((char *)pHead);
            pHead = pEnd + 1;
            *pEnd = tmpChar;

            /** get second */
            pEnd = (uint8_t *)strstr((char *)pHead, "S");
            if(NULL == pEnd)
                return FALSE;
            tmpChar = *pEnd;
            *pEnd = '\0';
            sec = atoi((char *)pHead);
            pHead = pEnd + 1;
            *pEnd = tmpChar;
        }

        if (year < 0 || mon < 0 || day < 0 || hour < 0
            || min < 0 || sec < 0)
            return FALSE;
        YMD_HMS_2_INT(year, mon, day, pConstraint->Interval.date, hour,
                      min, sec, pConstraint->Interval.time);
        WRITE_RO_FLAG(*bIsAble, 1, pConstraint->Indicator,
                      DRM_INTERVAL_CONSTRAINT);
        flag = 3;
    }

    if (operation == NULL) {
        sprintf((char *)sProperty, "%c%c%c%c%c%c", REL_TAG_RIGHTS,
                     REL_TAG_AGREEMENT, REL_TAG_PERMISSION, oper_char,
                     REL_TAG_CONSTRAINT, REL_TAG_COUNT);
        pBuf =
            WBXML_DOM_getNodeValue(buffer, bufferLen, sProperty, (uint8_t **)&pValue,
                                   &valueLen);
    } else {
        sprintf((char *)sProperty,
                     "o-ex:rights\\o-ex:agreement\\o-ex:permission\\o-dd:%s\\o-ex:constraint\\o-dd:count",
                     operation);
        pBuf = XML_DOM_getNodeValue(buffer, sProperty, &pValue, &valueLen);
    }
    CHECK_VALIDITY(pBuf);
    if (pBuf) { /* If count element exit the  get the value */
        uint8_t pTmp[16] = {0};
        int32_t i;

        for (i = 0; i < valueLen; i++) { /* Check the count format */
            if (0 == isdigit(*(pValue + i)))
                return FALSE;
        }

        strncpy((char *)pTmp, (char *)pValue, valueLen);
        pConstraint->Count = atoi((char *)pTmp);

    if(0 == pConstraint->Count)
    {
      WRITE_RO_FLAG(*bIsAble, 0, pConstraint->Indicator, DRM_NO_PERMISSION);
    }
    else if( pConstraint->Count > 0)
    {
      WRITE_RO_FLAG(*bIsAble, 1, pConstraint->Indicator, DRM_COUNT_CONSTRAINT);
    }
    else  /* < 0 */
    {
       return FALSE;
    }

        flag = 3;
    }

    if (operation == NULL) {
        sprintf((char *)sProperty, "%c%c%c%c%c%c%c", REL_TAG_RIGHTS,
                     REL_TAG_AGREEMENT, REL_TAG_PERMISSION, oper_char,
                     REL_TAG_CONSTRAINT, REL_TAG_DATETIME, REL_TAG_START);
        pBuf =
            WBXML_DOM_getNodeValue(buffer, bufferLen, sProperty, (uint8_t **)&pValue,
                                   &valueLen);
    } else {
        sprintf((char *)sProperty,
                     "o-ex:rights\\o-ex:agreement\\o-ex:permission\\o-dd:%s\\o-ex:constraint\\o-dd:datetime\\o-dd:start",
                     operation);
        pBuf = XML_DOM_getNodeValue(buffer, sProperty, &pValue, &valueLen);
    }
    CHECK_VALIDITY(pBuf);
    if (pBuf) { /* If start element exit then get the value */
        if (FALSE ==
            drm_getStartEndTime(pValue, valueLen, &pConstraint->StartTime))
            return FALSE;
        WRITE_RO_FLAG(*bIsAble, 1, pConstraint->Indicator, DRM_START_TIME_CONSTRAINT);
        flag = 3;
    }

    if (operation == NULL) {
        sprintf((char *)sProperty, "%c%c%c%c%c%c%c", REL_TAG_RIGHTS,
                     REL_TAG_AGREEMENT, REL_TAG_PERMISSION, oper_char,
                     REL_TAG_CONSTRAINT, REL_TAG_DATETIME, REL_TAG_END);
        pBuf =
            WBXML_DOM_getNodeValue(buffer, bufferLen, sProperty, (uint8_t **)&pValue,
                                   &valueLen);
    } else {
        sprintf((char *)sProperty,
                     "o-ex:rights\\o-ex:agreement\\o-ex:permission\\o-dd:%s\\o-ex:constraint\\o-dd:datetime\\o-dd:end",
                     operation);
        pBuf = XML_DOM_getNodeValue(buffer, sProperty, &pValue, &valueLen);
    }
    CHECK_VALIDITY(pBuf);
    if (pBuf) {
        if (FALSE ==
            drm_getStartEndTime(pValue, valueLen, &pConstraint->EndTime))
            return FALSE;
        WRITE_RO_FLAG(*bIsAble, 1, pConstraint->Indicator, DRM_END_TIME_CONSTRAINT);
        flag = 3;
    }

    if (2 == flag)
        WRITE_RO_FLAG(*bIsAble, 1, pConstraint->Indicator, DRM_NO_CONSTRAINT); /* If exit first assume have utter rights */
    return TRUE;
}

/* See parser_rel.h */
int32_t drm_relParser(uint8_t* buffer, int32_t bufferLen, int32_t Format, T_DRM_Rights* pRights)
{
    uint8_t *pBuf, *pValue;
    uint8_t sProperty[256];
    int32_t valueLen;

    if (TYPE_DRM_RIGHTS_WBXML != Format && TYPE_DRM_RIGHTS_XML != Format) /* It is not the support parse format */
        return FALSE;

    if (TYPE_DRM_RIGHTS_XML == Format) {
        /* Check whether it is a CD, and parse it using TYPE_DRM_RIGHTS_XML */
        if (NULL != drm_strnstr(buffer, (uint8_t *)HEADERS_CONTENT_ID, bufferLen))
            return FALSE;

        pBuf =
            XML_DOM_getNodeValue(buffer,
                                 (uint8_t *)"o-ex:rights\\o-ex:context\\o-dd:version",
                                 &pValue, &valueLen);
        CHECK_VALIDITY(pBuf);

        if (pBuf) {
            if (valueLen > 8) /* Check version lenth */
                return FALSE;

           /* error version */
           if(strncmp(pValue,"1.0",valueLen))
                return FALSE;

            strncpy((char *)pRights->Version, (char *)pValue, valueLen);
        } else
            return FALSE;

        /* this means there is more than one version label in rights */
        if(strstr((char*)pBuf, "<o-dd:version>"))
            return FALSE;

        pBuf =
            XML_DOM_getNodeValue(buffer,
                                 (uint8_t *)"o-ex:rights\\o-ex:agreement\\o-ex:asset\\ds:KeyInfo\\ds:KeyValue",
                                 &pValue, &valueLen);
        CHECK_VALIDITY(pBuf);
        if (pBuf) { /* Get keyvalue */
            int32_t keyLen;

            if (24 != valueLen)
                return FALSE;

            keyLen = drm_decodeBase64(NULL, 0, pValue, &valueLen);
            if (keyLen < 0)
                return FALSE;

            if (DRM_KEY_LEN != drm_decodeBase64(pRights->KeyValue, keyLen, pValue, &valueLen))
                return FALSE;
        }

        pBuf =
            XML_DOM_getNodeValue(buffer,
                                 (uint8_t *)"o-ex:rights\\o-ex:agreement\\o-ex:asset\\o-ex:context\\o-dd:uid",
                                 &pValue, &valueLen);
        CHECK_VALIDITY(pBuf);
        if (pBuf) {
            if (valueLen > DRM_UID_LEN)
                return FALSE;
            strncpy((char *)pRights->uid, (char *)pValue, valueLen);
            pRights->uid[valueLen] = '\0';
        } else
            return FALSE;

        /* this means there is more than one uid label in rights */
        if(strstr((char*)pBuf, "<o-dd:uid>"))
            return FALSE;

        if (FALSE ==
            drm_getRightValue(buffer, bufferLen, pRights, (uint8_t *)"play", 0))
            return FALSE;

        if (FALSE ==
            drm_getRightValue(buffer, bufferLen, pRights, (uint8_t *)"display", 0))
            return FALSE;

        if (FALSE ==
            drm_getRightValue(buffer, bufferLen, pRights, (uint8_t *)"execute", 0))
            return FALSE;

        if (FALSE ==
            drm_getRightValue(buffer, bufferLen, pRights, (uint8_t *)"print", 0))
            return FALSE;
    } else if (TYPE_DRM_RIGHTS_WBXML == Format) {
        if (!REL_CHECK_WBXML_HEADER(buffer))
            return FALSE;

        sprintf((char *)sProperty, "%c%c%c", REL_TAG_RIGHTS, REL_TAG_CONTEXT,
                     REL_TAG_VERSION);
        pBuf =
            WBXML_DOM_getNodeValue(buffer, bufferLen, sProperty, (uint8_t **)&pValue,
                                   &valueLen);
        CHECK_VALIDITY(pBuf);

        if (pBuf) {
            if (valueLen > 8) /* Check version lenth */
                return FALSE;
            strncpy((char *)pRights->Version, (char *)pValue, valueLen);
        } else
            return FALSE;

        sprintf((char *)sProperty, "%c%c%c%c%c",
                     REL_TAG_RIGHTS, REL_TAG_AGREEMENT, REL_TAG_ASSET,
                     REL_TAG_KEYINFO, REL_TAG_KEYVALUE);
        pBuf =
            WBXML_DOM_getNodeValue(buffer, bufferLen, sProperty, (uint8_t **)&pValue,
                                   &valueLen);
        CHECK_VALIDITY(pBuf);
        if (pBuf) {
            if (DRM_KEY_LEN != valueLen)
                return FALSE;
            memcpy(pRights->KeyValue, pValue, DRM_KEY_LEN);
            memset(pValue, 0, DRM_KEY_LEN); /* Clean the KeyValue */
        }

        sprintf((char *)sProperty, "%c%c%c%c%c",
                     REL_TAG_RIGHTS, REL_TAG_AGREEMENT, REL_TAG_ASSET,
                     REL_TAG_CONTEXT, REL_TAG_UID);
        pBuf =
            WBXML_DOM_getNodeValue(buffer, bufferLen, sProperty, (uint8_t **)&pValue,
                                   &valueLen);
        CHECK_VALIDITY(pBuf);
        if (pBuf) {
            if (valueLen > DRM_UID_LEN)
                return FALSE;
            strncpy((char *)pRights->uid, (char *)pValue, valueLen);
            pRights->uid[valueLen] = '\0';
        } else
            return FALSE;

        if (FALSE ==
            drm_getRightValue(buffer, bufferLen, pRights, NULL,
                              REL_TAG_PLAY))
            return FALSE;

        if (FALSE ==
            drm_getRightValue(buffer, bufferLen, pRights, NULL,
                              REL_TAG_DISPLAY))
            return FALSE;

        if (FALSE ==
            drm_getRightValue(buffer, bufferLen, pRights, NULL,
                              REL_TAG_EXECUTE))
            return FALSE;

        if (FALSE ==
            drm_getRightValue(buffer, bufferLen, pRights, NULL,
                              REL_TAG_PRINT))
            return FALSE;
    }

    return TRUE;
}
