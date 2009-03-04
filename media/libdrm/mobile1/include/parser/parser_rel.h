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

#ifndef __PARSER_REL_H__
#define __PARSER_REL_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <drm_common_types.h>

#define WRITE_RO_FLAG(whoIsAble, boolValue, Indicator, RIGHTS) do{\
    whoIsAble = boolValue;\
    Indicator |= RIGHTS;\
}while(0)

#define CHECK_VALIDITY(ret) do{\
    if(ret == NULL){\
        if(XML_ERROR_NO_SUCH_NODE != xml_errno)\
            return FALSE;\
    }\
    else\
    {\
        if(XML_ERROR_OK != xml_errno)\
            return FALSE;\
    }\
}while(0)

#define YMD_HMS_2_INT(year, mon, day, date, hour, min, sec, time) do{\
    date = year * 10000 + mon * 100 + day;\
    time = hour * 10000 + min * 100 + sec;\
}while(0)

#define DRM_UID_LEN         256
#define DRM_KEY_LEN         16

#define XML_DOM_PARSER

typedef struct _T_DRM_DATETIME {
    int32_t date; /**< year * 10000 + mon *100 + day */
    int32_t time; /**< hour * 10000 + min *100 + sec */
} T_DRM_DATETIME;

typedef struct _T_DRM_Rights_Constraint {
    uint8_t Indicator;          /**< Indicate which is constrainted, the first one indicate 0001, second one indicate 0010 */
    uint8_t unUsed[3];
    int32_t Count;              /**< The times that can be used */
    T_DRM_DATETIME StartTime;   /**< The starting time */
    T_DRM_DATETIME EndTime;     /**< The ending time */
    T_DRM_DATETIME Interval;    /**< The interval time */
} T_DRM_Rights_Constraint;

typedef struct _T_DRM_Rights {
    uint8_t Version[8];                         /**< Version number */
    uint8_t uid[256];                           /**< record the rights object name */
    uint8_t KeyValue[16];                       /**< Decode base64 */
    int32_t bIsPlayable;                        /**< Is playable */
    int32_t bIsDisplayable;                     /**< Is displayable */
    int32_t bIsExecuteable;                     /**< Is executeable */
    int32_t bIsPrintable;                       /**< Is printable */
    T_DRM_Rights_Constraint PlayConstraint;     /**< Play constraint */
    T_DRM_Rights_Constraint DisplayConstraint;  /**< Display constraint */
    T_DRM_Rights_Constraint ExecuteConstraint;  /**< Execute constraint */
    T_DRM_Rights_Constraint PrintConstraint;    /**< Print constraint */
} T_DRM_Rights;

/**
 * Input year and month, return how many days that month have
 * \param year          (in)Input the year
 * \param month         (in)Input the month
 * \return
 *      -A positive integer, which is how many days that month have
 *      -When wrong input, return -1
 */
int32_t drm_monthDays(int32_t year, int32_t month);

/**
 * Check whether the date and time is valid.
 * \param year          year of the date
 * \param month         month of the date
 * \param day           day of the date
 * \param hour          hour of the time
 * \param min           minute of the time
 * \param sec           second of the time
 * \return
 *      -when it is a valid time, return 0
 *      -when it is a invalid time, return -1
 */
int32_t drm_checkDate(int32_t year, int32_t month, int32_t day, int32_t hour, int32_t min, int32_t sec);

/**
 * Parse the rights object include xml format and wbxml format data
 *
 * \param buffer        (in)Input the DRM rights object data
 * \param bufferLen     (in)The buffer length
 * \param format        (in)Which format, xml or wbxml
 * \param pRights       (out)A structure pointer which save the rights information
 *
 * \return
 *      -TRUE, when success
 *      -FALSE, when failed
 */
int32_t drm_relParser(uint8_t* buffer, int32_t bufferLen, int32_t Format, T_DRM_Rights* pRights);

#ifdef __cplusplus
}
#endif

#endif /* __PARSER_REL_H__ */
