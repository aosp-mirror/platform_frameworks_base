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
 * @file drm1_jni.c
 *
 * This file implement the Java Native Interface
 * for supporting OMA DRM 1.0
 */

#include <jni/drm1_jni.h>
#include <objmng/svc_drm.h>
#include "log.h"
#include "JNIHelp.h"


#define MS_PER_SECOND 1000                  /* Milliseconds per second */
#define MS_PER_MINUTE 60 * MS_PER_SECOND    /* Milliseconds per minute */
#define MS_PER_HOUR   60 * MS_PER_MINUTE    /* Milliseconds per hour */
#define MS_PER_DAY    24 * MS_PER_HOUR      /* Milliseconds per day */

#define SECONDS_PER_MINUTE 60                       /* Seconds per minute*/
#define SECONDS_PER_HOUR   60 * SECONDS_PER_MINUTE  /* Seconds per hour */
#define SECONDS_PER_DAY    24 * SECONDS_PER_HOUR    /* Seconds per day */

#define DAY_PER_MONTH 30                    /* Days per month */
#define DAY_PER_YEAR  365                   /* Days per year */

/** Nonzero if 'y' is a leap year, else zero. */
#define leap(y) (((y) % 4 == 0 && (y) % 100 != 0) || (y) % 400 == 0)

/** Number of leap years from 1970 to 'y' (not including 'y' itself). */
#define nleap(y) (((y) - 1969) / 4 - ((y) - 1901) / 100 + ((y) - 1601) / 400)

/** Accumulated number of days from 01-Jan up to start of current month. */
static const int32_t ydays[] = {
    0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334
};

#define int64_const(s)          (s)
#define int64_add(dst, s1, s2)  ((void)((dst) = (s1) + (s2)))
#define int64_mul(dst, s1, s2)  ((void)((dst) = (int64_t)(s1) * (int64_t)(s2)))

/**
 * DRM data structure
 */
typedef struct _DrmData {
    /**
     * The id of the DRM content.
     */
    int32_t id;

    /**
     * The pointer of JNI interface.
     */
    JNIEnv* env;

    /**
     * The pointer of DRM raw content InputStream object.
     */
    jobject* pInData;

    /**
     * The len of the InputStream object.
     */
    int32_t len;

    /**
     * The next DRM data.
     */
    struct _DrmData *next;
} DrmData;

/** The table to hold all the DRM data. */
static DrmData *drmTable = NULL;

/**
 * Allocate a new item of DrmData.
 *
 * \return a pointer to a DrmData item if allocate successfully,
 *         otherwise return NULL
 */
static DrmData * newItem(void)
{
    DrmData *d = (DrmData *)malloc(sizeof(DrmData));

    if (d != NULL) {
        d->id = -1;
        d->next = NULL;
    }

    return d;
}

/**
 * Free the memory of the specified DrmData item <code>d</code>.
 *
 * \param d - a pointer to DrmData
 */
static void freeItem(DrmData *d)
{
    assert(d != NULL);

    free(d);
}

/**
 * Insert a DrmData item with given <code>name</code> into the head of
 * the DrmData list.
 *
 * @param d - the pointer of the JNI interface
 * @param pInData - the pointer of the DRM content InputStream object.
 *
 * @return <code>JNI_DRM_SUCCESS</code> if insert successfully, otherwise
 *         return <code>JNI_DRM_FAILURE</code>
 */
static int32_t addItem(DrmData* d)
{
    if (NULL == d)
        return JNI_DRM_FAILURE;

    if (NULL == drmTable) {
        drmTable = d;
        return JNI_DRM_SUCCESS;
    }

    d->next = drmTable;
    drmTable = d;

    return JNI_DRM_SUCCESS;
}

/**
 * Get the item from the DrmData list by the specified <code>
 * id</code>.
 *
 * @param p - the pointer of the DRM content InputStream object.
 *
 * @return a pointer to the DrmData item if find it successfuly,
 *         otherwise return NULL
 */
static DrmData * getItem(int32_t id)
{
    DrmData *d;

    if (NULL == drmTable)
        return NULL;

    for (d = drmTable; d != NULL; d = d->next) {
        if (id == d->id)
            return d;
    }

    return NULL;
}

/**
 * Remove the specified DrmData item <code>d</code>.
 *
 * @param p - the pointer of the DRM content InputStream object.
 *
 * @return <code>JNI_DRM_SUCCESS</code> if remove successfuly,
 *         otherwise return <code>JNI_DRM_FAILURE</code>
 */
static int32_t removeItem(int32_t id)
{
    DrmData *curItem, *preItem, *dstItem;

    if (NULL == drmTable)
        return JNI_DRM_FAILURE;

    preItem = NULL;
    for (curItem = drmTable; curItem != NULL; curItem = curItem->next) {
        if (id == curItem->id) {
            if (curItem == drmTable)
                drmTable = curItem->next;
            else
                preItem->next = curItem->next;

            freeItem(curItem);

            return JNI_DRM_SUCCESS;
        }

        preItem = curItem;
    }

    return JNI_DRM_FAILURE;
}


static int32_t getInputStreamDataLength(int32_t handle)
{
    JNIEnv* env;
    jobject* pInputStream;
    int32_t len;
    DrmData* p;
    jclass cls;
    jmethodID mid;

    p = (DrmData *)handle;

    if (NULL == p)
        return 0;

    env = p->env;
    pInputStream = p->pInData;
    len = p->len;

    if (NULL == env || p->len <= 0 || NULL == pInputStream)
        return 0;

    /* check the original InputStream is available or not */
    cls = (*env)->GetObjectClass(env, *pInputStream);
    mid = (*env)->GetMethodID(env, cls, "available", "()I");
    (*env)->DeleteLocalRef(env, cls);

    if (NULL == mid)
        return 0;

    if (0 > (*env)->CallIntMethod(env, *pInputStream, mid))
        return 0;

    return len;
}

static int32_t readInputStreamData(int32_t handle, uint8_t* buf, int32_t bufLen)
{
    JNIEnv* env;
    jobject* pInputStream;
    int32_t len;
    DrmData* p;
    jclass cls;
    jmethodID mid;
    jbyteArray tmp;
    int tmpLen;
    jbyte* pNativeBuf;

    p = (DrmData *)handle;

    if (NULL == p || NULL == buf || bufLen <- 0)
        return 0;

    env = p->env;
    pInputStream = p->pInData;
    len = p->len;

    if (NULL == env || p->len <= 0 || NULL == pInputStream)
        return 0;

    cls = (*env)->GetObjectClass(env, *pInputStream);
    mid = (*env)->GetMethodID(env, cls, "read", "([BII)I");
    tmp = (*env)->NewByteArray(env, bufLen);
    bufLen = (*env)->CallIntMethod(env, *pInputStream, mid, tmp, 0, bufLen);

    (*env)->DeleteLocalRef(env, cls);

    if (-1 == bufLen)
        return -1;

    pNativeBuf = (*env)->GetByteArrayElements(env, tmp, NULL);
    memcpy(buf, pNativeBuf, bufLen);
    (*env)->ReleaseByteArrayElements(env, tmp, pNativeBuf, 0);
    (*env)->DeleteLocalRef(env, tmp);

    return bufLen;
}

static const T_DRM_Rights_Info_Node *searchRightsObject(const jbyte* roId, const T_DRM_Rights_Info_Node* pRightsList)
{
    const T_DRM_Rights_Info_Node *pTmp;

    if (NULL == roId || NULL == pRightsList)
        return NULL;

    pTmp = pRightsList;

    while (NULL != pTmp) {
        if(0 == strcmp((char *)roId, (char *)pTmp->roInfo.roId))
            break;
        pTmp = pTmp->next;
    }

    return pTmp;
}

/**
 * Returns the difference in seconds between the given GMT time
 * and 1970-01-01 00:00:00 GMT.
 *
 * \param year the year (since 1970)
 * \param month the month (1 - 12)
 * \param day the day (1 - 31)
 * \param hour the hour (0 - 23)
 * \param minute the minute (0 - 59)
 * \param second the second (0 - 59)
 *
 * \return the difference in seconds between the given GMT time
 *         and 1970-01-01 00:00:00 GMT.
 */
static int64_t mkgmtime(
        uint32_t year, uint32_t month, uint32_t day,
        uint32_t hour, uint32_t minute, uint32_t second)
{
    int64_t result;

    /*
     * FIXME: It does not check whether the specified days
     *        is valid based on the specified months.
     */
    assert(year >= 1970
            && month > 0 && month <= 12
            && day > 0 && day <= 31
            && hour < 24 && minute < 60
            && second < 60);

    /* Set 'day' to the number of days into the year. */
    day += ydays[month - 1] + (month > 2 && leap (year)) - 1;

    /* Now calculate 'day' to the number of days since Jan 1, 1970. */
    day = day + 365 * (year - 1970) + nleap(year);

    int64_mul(result, int64_const(day), int64_const(SECONDS_PER_DAY));
    int64_add(result, result, int64_const(
        SECONDS_PER_HOUR * hour + SECONDS_PER_MINUTE * minute + second));

    return result;
}

/**
 * Compute the milliseconds by the specified <code>date</code>
 * and <code>time</code>.
 *
 * @param date - the specified date,
 *               <code>date = year * 10000 + month * 100 + day</code>
 * @param time - the specified time,
 *               <code>time = hour * 10000 + minute * 100 + second</code>
 *
 * @return the related milliseconds
 */
static int64_t computeTime(int32_t date, int32_t time)
{
    int32_t year, month, day, hour, minute, second;

    year = date / 10000;
    month = (date / 100) % 100;
    day = date % 100;
    hour = time / 10000;
    minute = (time / 100) % 100;
    second = time % 100;

    /* Adjust the invalid parameters. */
    if (year < 1970) year = 1970;
    if (month < 1) month = 1;
    if (month > 12) month = 12;
    if (day < 1) day = 1;
    if (day > 31) day = 31;
    if (hour < 0) hour = 0;
    if (hour > 23) hour = 23;
    if (minute < 0) minute = 0;
    if (minute > 59) minute = 59;
    if (second < 0) second = 0;
    if (second > 59) second = 59;

    return mkgmtime(year, month, day, hour, minute, second) * 1000;
}

/**
 * Compute the milliseconds by the specified <code>date</code>
 * and <code>time</code>.
 * Note that here we always treat 1 year as 365 days and 1 month as 30 days
 * that is not precise. But it should not be a problem since OMA DRM 2.0
 * already restricts the interval representation to be day-based,
 * i.e. there will not be an interval with year or month any more in the
 * future.
 *
 * @param date - the specified date,
 *               <code>date = year * 10000 + month * 100 + day</code>
 * @param time - the specified time,
 *               <code>time = hour * 10000 + minute * 100 + second</code>
 *
 * @return the related milliseconds
 */
static int64_t computeInterval(int32_t date, int32_t time)
{
    int32_t year, month, day, hour, minute, second;
    int64_t milliseconds;

    year = date / 10000;
    month = (date / 100) % 100;
    day = date % 100;
    hour = time / 10000;
    minute = (time / 100) % 100;
    second = time % 100;

    /* milliseconds = ((((year * 365 + month * 30 + day) * 24
     *                + hour) * 60 + minute) * 60 + second) * 1000;
     */
    int64_mul(milliseconds,
        int64_const(year * DAY_PER_YEAR + month * DAY_PER_MONTH + day),
        int64_const(MS_PER_DAY));
    int64_add(milliseconds, milliseconds,
        int64_const(hour * MS_PER_HOUR + minute * MS_PER_MINUTE +
            second * MS_PER_SECOND));

    return milliseconds;
}

static jint getObjectIntField(JNIEnv * env, jobject obj, const char *name, jint * value)
{
    jclass clazz;
    jfieldID field;

    clazz = (*env)->GetObjectClass(env, obj);
    if (NULL == clazz)
        return JNI_DRM_FAILURE;

    field = (*env)->GetFieldID(env, clazz, name, "I");
    (*env)->DeleteLocalRef(env, clazz);

    if (NULL == field)
        return JNI_DRM_FAILURE;

    *value = (*env)->GetIntField(env, obj, field);

    return JNI_DRM_SUCCESS;
}

static jint setObjectIntField(JNIEnv * env, jobject obj, const char *name, jint value)
{
    jclass clazz;
    jfieldID field;

    clazz = (*env)->GetObjectClass(env, obj);
    if (NULL == clazz)
        return JNI_DRM_FAILURE;

    field = (*env)->GetFieldID(env, clazz, name, "I");
    (*env)->DeleteLocalRef(env, clazz);

    if (NULL == field)
        return JNI_DRM_FAILURE;

    (*env)->SetIntField(env, obj, field, value);

    return JNI_DRM_SUCCESS;
}

static jint setObjectLongField(JNIEnv * env, jobject obj, const char *name, jlong value)
{
    jclass clazz;
    jfieldID field;

    clazz = (*env)->GetObjectClass(env, obj);
    if (NULL == clazz)
        return JNI_DRM_FAILURE;

    field = (*env)->GetFieldID(env, clazz, name, "J");
    (*env)->DeleteLocalRef(env, clazz);

    if (NULL == field)
        return JNI_DRM_FAILURE;

    (*env)->SetLongField(env, obj, field, value);

    return JNI_DRM_SUCCESS;
}

static jint setConstraintFields(JNIEnv * env, jobject constraint, T_DRM_Constraint_Info * pConstraint)
{
    /* if no this permission */
    if (pConstraint->indicator == (uint8_t)DRM_NO_RIGHTS) {
        if (JNI_DRM_FAILURE == setObjectIntField(env, constraint, "count", 0))
            return JNI_DRM_FAILURE;

        return JNI_DRM_SUCCESS;
    }

    /* set count field */
    if (pConstraint->indicator & DRM_COUNT_CONSTRAINT) {
        if (JNI_DRM_FAILURE == setObjectIntField(env, constraint, "count", pConstraint->count))
            return JNI_DRM_FAILURE;
    }

    /* set start time field */
    if (pConstraint->indicator & DRM_START_TIME_CONSTRAINT) {
        int64_t startTime;

        startTime = computeTime(pConstraint->startDate, pConstraint->startTime);

        if (JNI_DRM_FAILURE == setObjectLongField(env, constraint, "startDate", startTime))
            return JNI_DRM_FAILURE;
    }

    /* set end time field */
    if (pConstraint->indicator & DRM_END_TIME_CONSTRAINT) {
        int64_t endTime;

        endTime = computeTime(pConstraint->endDate, pConstraint->endTime);

        if (JNI_DRM_FAILURE == setObjectLongField(env, constraint, "endDate", endTime))
            return JNI_DRM_FAILURE;
    }

    /* set interval field */
    if (pConstraint->indicator & DRM_INTERVAL_CONSTRAINT) {
        int64_t interval;

        interval = computeInterval(pConstraint->intervalDate, pConstraint->intervalTime);

        if (JNI_DRM_FAILURE == setObjectLongField(env, constraint, "interval", interval))
            return JNI_DRM_FAILURE;
    }

    return JNI_DRM_SUCCESS;
}

static jint setRightsFields(JNIEnv * env, jobject rights, T_DRM_Rights_Info* pRoInfo)
{
    jclass clazz;
    jfieldID field;
    jstring str;
    jint index;

    clazz = (*env)->GetObjectClass(env, rights);
    if (NULL == clazz)
        return JNI_DRM_FAILURE;

    /* set roId field */
    field = (*env)->GetFieldID(env, clazz, "roId", "Ljava/lang/String;");
    (*env)->DeleteLocalRef(env, clazz);

    if (NULL == field)
        return JNI_DRM_FAILURE;

    str = (*env)->NewStringUTF(env, (char *)pRoInfo->roId);
    if (NULL == str)
        return JNI_DRM_FAILURE;

    (*env)->SetObjectField(env, rights, field, str);
    (*env)->DeleteLocalRef(env, str);

    return JNI_DRM_SUCCESS;
}

/* native interface */
JNIEXPORT jint JNICALL
Java_android_drm_mobile1_DrmRawContent_nativeConstructDrmContent
  (JNIEnv * env, jobject rawContent, jobject data, jint len, jint mimeType)
{
    int32_t id;
    T_DRM_Input_Data inData;
    DrmData* drmInData;

    switch (mimeType) {
    case JNI_DRM_MIMETYPE_MESSAGE:
        mimeType = TYPE_DRM_MESSAGE;
        break;
    case JNI_DRM_MIMETYPE_CONTENT:
        mimeType = TYPE_DRM_CONTENT;
        break;
    default:
        return JNI_DRM_FAILURE;
    }

    drmInData = newItem();
    if (NULL == drmInData)
        return JNI_DRM_FAILURE;

    drmInData->env = env;
    drmInData->pInData = &data;
    drmInData->len = len;

    if (JNI_DRM_FAILURE == addItem(drmInData))
        return JNI_DRM_FAILURE;

    inData.inputHandle = (int32_t)drmInData;
    inData.mimeType = mimeType;
    inData.getInputDataLength = getInputStreamDataLength;
    inData.readInputData = readInputStreamData;

    id = SVC_drm_openSession(inData);
    if (id < 0)
        return JNI_DRM_FAILURE;

    drmInData->id = id;

    return id;
}

/* native interface */
JNIEXPORT jstring JNICALL
Java_android_drm_mobile1_DrmRawContent_nativeGetRightsAddress
  (JNIEnv * env, jobject rawContent)
{
    jint id;
    uint8_t rightsIssuer[256] = {0};
    jstring str = NULL;

    if (JNI_DRM_FAILURE == getObjectIntField(env, rawContent, "id", &id))
        return NULL;

    if (DRM_SUCCESS == SVC_drm_getRightsIssuer(id, rightsIssuer))
        str = (*env)->NewStringUTF(env, (char *)rightsIssuer);

    return str;
}

/* native interface */
JNIEXPORT jint JNICALL
Java_android_drm_mobile1_DrmRawContent_nativeGetDeliveryMethod
  (JNIEnv * env, jobject rawContent)
{
    jint id;
    int32_t res;

    if (JNI_DRM_FAILURE == getObjectIntField(env, rawContent, "id", &id))
        return JNI_DRM_FAILURE;

    res = SVC_drm_getDeliveryMethod(id);

    switch (res) {
    case FORWARD_LOCK:
        return JNI_DRM_FORWARD_LOCK;
    case COMBINED_DELIVERY:
        return JNI_DRM_COMBINED_DELIVERY;
    case SEPARATE_DELIVERY:
        return JNI_DRM_SEPARATE_DELIVERY;
    case SEPARATE_DELIVERY_FL:
        return JNI_DRM_SEPARATE_DELIVERY_DM;
    default:
        return JNI_DRM_FAILURE;
    }
}

/* native interface */
JNIEXPORT jint JNICALL
Java_android_drm_mobile1_DrmRawContent_nativeReadContent
  (JNIEnv * env, jobject rawContent, jbyteArray buf, jint bufOff, jint len, jint mediaOff)
{
    jint id;
    jbyte *nativeBuf;
    jclass cls;
    jmethodID mid;
    DrmData* p;
    jobject inputStream;
    jfieldID field;

    if (NULL == buf) {
        jniThrowNullPointerException(env, "b == null");
        return JNI_DRM_FAILURE;
    }

    if (len < 0 || bufOff < 0 || len + bufOff > (*env)->GetArrayLength(env, buf)) {
        jniThrowException(env, "java/lang/IndexOutOfBoundsException", NULL);
        return JNI_DRM_FAILURE;
    }

    if (mediaOff < 0 || len == 0)
        return JNI_DRM_FAILURE;

    if (JNI_DRM_FAILURE == getObjectIntField(env, rawContent, "id", &id))
        return JNI_DRM_FAILURE;

    p = getItem(id);
    if (NULL == p)
        return JNI_DRM_FAILURE;

    cls = (*env)->GetObjectClass(env, rawContent);
    if (NULL == cls)
        return JNI_DRM_FAILURE;

    field = (*env)->GetFieldID(env, cls, "inData", "Ljava/io/BufferedInputStream;");
    (*env)->DeleteLocalRef(env, cls);

    if (NULL == field)
        return JNI_DRM_FAILURE;

    inputStream = (*env)->GetObjectField(env, rawContent, field);

    p->env = env;
    p->pInData = &inputStream;

    nativeBuf = (*env)->GetByteArrayElements(env, buf, NULL);

    len = SVC_drm_getContent(id, mediaOff, (uint8_t *)nativeBuf + bufOff, len);

    (*env)->ReleaseByteArrayElements(env, buf, nativeBuf, 0);

    if (DRM_MEDIA_EOF == len)
        return JNI_DRM_EOF;
    if (len <= 0)
        return JNI_DRM_FAILURE;

    return len;
}

/* native interface */
JNIEXPORT jstring JNICALL
Java_android_drm_mobile1_DrmRawContent_nativeGetContentType
  (JNIEnv * env, jobject rawContent)
{
    jint id;
    uint8_t contentType[64] = {0};
    jstring str = NULL;

    if (JNI_DRM_FAILURE == getObjectIntField(env, rawContent, "id", &id))
        return NULL;

    if (DRM_SUCCESS == SVC_drm_getContentType(id, contentType))
        str = (*env)->NewStringUTF(env, (char *)contentType);

    return str;
}

/* native interface */
JNIEXPORT jint JNICALL
Java_android_drm_mobile1_DrmRawContent_nativeGetContentLength
  (JNIEnv * env, jobject rawContent)
{
    jint id;
    int32_t len;

    if (JNI_DRM_FAILURE == getObjectIntField(env, rawContent, "id", &id))
        return JNI_DRM_FAILURE;

    len = SVC_drm_getContentLength(id);

    if (DRM_UNKNOWN_DATA_LEN == len)
        return JNI_DRM_UNKNOWN_DATA_LEN;

    if (0 > len)
        return JNI_DRM_FAILURE;

    return len;
}

/* native interface */
JNIEXPORT void JNICALL
Java_android_drm_mobile1_DrmRawContent_finalize
  (JNIEnv * env, jobject rawContent)
{
    jint id;

    if (JNI_DRM_FAILURE == getObjectIntField(env, rawContent, "id", &id))
        return;

    removeItem(id);

    SVC_drm_closeSession(id);
}

/* native interface */
JNIEXPORT jint JNICALL
Java_android_drm_mobile1_DrmRights_nativeGetConstraintInfo
  (JNIEnv * env, jobject rights, jint permission, jobject constraint)
{
    jclass clazz;
    jfieldID field;
    jstring str;
    uint8_t *nativeStr;
    T_DRM_Rights_Info_Node *pRightsList;
    T_DRM_Rights_Info_Node *pCurNode;
    T_DRM_Constraint_Info *pConstraint;

    clazz = (*env)->GetObjectClass(env, rights);
    if (NULL == clazz)
        return JNI_DRM_FAILURE;

    field = (*env)->GetFieldID(env, clazz, "roId", "Ljava/lang/String;");
    (*env)->DeleteLocalRef(env, clazz);

    if (NULL == field)
        return JNI_DRM_FAILURE;

    str = (*env)->GetObjectField(env, rights, field);

    nativeStr = (uint8_t *)(*env)->GetStringUTFChars(env, str, NULL);
    if (NULL == nativeStr)
        return JNI_DRM_FAILURE;

    /* this means forward-lock rights */
    if (0 == strcmp((char *)nativeStr, "ForwardLock")) {
        (*env)->ReleaseStringUTFChars(env, str, (char *)nativeStr);
        return JNI_DRM_SUCCESS;
    }

    if (DRM_FAILURE == SVC_drm_viewAllRights(&pRightsList)) {
        (*env)->ReleaseStringUTFChars(env, str, (char *)nativeStr);
        return JNI_DRM_FAILURE;
    }

    pCurNode = searchRightsObject((jbyte *)nativeStr, pRightsList);
    if (NULL == pCurNode) {
        (*env)->ReleaseStringUTFChars(env, str, (char *)nativeStr);
        SVC_drm_freeRightsInfoList(pRightsList);
        return JNI_DRM_FAILURE;
    }
    (*env)->ReleaseStringUTFChars(env, str, (char *)nativeStr);

    switch (permission) {
    case JNI_DRM_PERMISSION_PLAY:
        pConstraint = &(pCurNode->roInfo.playRights);
        break;
    case JNI_DRM_PERMISSION_DISPLAY:
        pConstraint = &(pCurNode->roInfo.displayRights);
        break;
    case JNI_DRM_PERMISSION_EXECUTE:
        pConstraint = &(pCurNode->roInfo.executeRights);
        break;
    case JNI_DRM_PERMISSION_PRINT:
        pConstraint = &(pCurNode->roInfo.printRights);
        break;
    default:
        SVC_drm_freeRightsInfoList(pRightsList);
        return JNI_DRM_FAILURE;
    }

    /* set constraint field */
    if (JNI_DRM_FAILURE == setConstraintFields(env, constraint, pConstraint)) {
        SVC_drm_freeRightsInfoList(pRightsList);
        return JNI_DRM_FAILURE;
    }

    SVC_drm_freeRightsInfoList(pRightsList);

    return JNI_DRM_SUCCESS;
}

/* native interface */
JNIEXPORT jint JNICALL
Java_android_drm_mobile1_DrmRights_nativeConsumeRights
  (JNIEnv * env, jobject rights, jint permission)
{
    jclass clazz;
    jfieldID field;
    jstring str;
    uint8_t *nativeStr;
    int32_t id;

    switch (permission) {
    case JNI_DRM_PERMISSION_PLAY:
        permission = DRM_PERMISSION_PLAY;
        break;
    case JNI_DRM_PERMISSION_DISPLAY:
        permission = DRM_PERMISSION_DISPLAY;
        break;
    case JNI_DRM_PERMISSION_EXECUTE:
        permission = DRM_PERMISSION_EXECUTE;
        break;
    case JNI_DRM_PERMISSION_PRINT:
        permission = DRM_PERMISSION_PRINT;
        break;
    default:
        return JNI_DRM_FAILURE;
    }

    clazz = (*env)->GetObjectClass(env, rights);
    if (NULL == clazz)
        return JNI_DRM_FAILURE;

    field = (*env)->GetFieldID(env, clazz, "roId", "Ljava/lang/String;");
    (*env)->DeleteLocalRef(env, clazz);

    if (NULL == field)
        return JNI_DRM_FAILURE;

    str = (*env)->GetObjectField(env, rights, field);

    nativeStr = (uint8_t *)(*env)->GetStringUTFChars(env, str, NULL);
    if (NULL == nativeStr)
        return JNI_DRM_FAILURE;

    if (0 == strcmp("ForwardLock", (char *)nativeStr)) {
        (*env)->ReleaseStringUTFChars(env, str, (char *)nativeStr);
        return JNI_DRM_SUCCESS;
    }

    if (DRM_SUCCESS != SVC_drm_updateRights(nativeStr, permission)) {
        (*env)->ReleaseStringUTFChars(env, str, (char *)nativeStr);
        return JNI_DRM_FAILURE;
    }

    (*env)->ReleaseStringUTFChars(env, str, (char *)nativeStr);

    return JNI_DRM_SUCCESS;
}

/* native interface */
JNIEXPORT jint JNICALL
Java_android_drm_mobile1_DrmRightsManager_nativeInstallDrmRights
  (JNIEnv * env, jobject rightsManager, jobject data, jint len, jint mimeType, jobject rights)
{
    int32_t id;
    T_DRM_Input_Data inData;
    DrmData* drmInData;
    jclass cls;
    jmethodID mid;
    T_DRM_Rights_Info rightsInfo;

    switch (mimeType) {
    case JNI_DRM_MIMETYPE_RIGHTS_XML:
        mimeType = TYPE_DRM_RIGHTS_XML;
        break;
    case JNI_DRM_MIMETYPE_RIGHTS_WBXML:
        mimeType = TYPE_DRM_RIGHTS_WBXML;
        break;
    case JNI_DRM_MIMETYPE_MESSAGE:
        mimeType = TYPE_DRM_MESSAGE;
        break;
    default:
        return JNI_DRM_FAILURE;
    }

    drmInData = newItem();
    if (NULL == drmInData)
        return JNI_DRM_FAILURE;

    drmInData->env = env;
    drmInData->pInData = &data;
    drmInData->len = len;

    inData.inputHandle = (int32_t)drmInData;
    inData.mimeType = mimeType;
    inData.getInputDataLength = getInputStreamDataLength;
    inData.readInputData = readInputStreamData;

    memset(&rightsInfo, 0, sizeof(T_DRM_Rights_Info));
    if (DRM_FAILURE == SVC_drm_installRights(inData, &rightsInfo))
        return JNI_DRM_FAILURE;

    freeItem(drmInData);

    return setRightsFields(env, rights, &rightsInfo);
}

/* native interface */
JNIEXPORT jint JNICALL
Java_android_drm_mobile1_DrmRightsManager_nativeQueryRights
  (JNIEnv * env, jobject rightsManager, jobject rawContent, jobject rights)
{
    jint id;
    T_DRM_Rights_Info rightsInfo;

    if (JNI_DRM_FAILURE == getObjectIntField(env, rawContent, "id", &id))
        return JNI_DRM_FAILURE;

    memset(&rightsInfo, 0, sizeof(T_DRM_Rights_Info));
    if (DRM_SUCCESS != SVC_drm_getRightsInfo(id, &rightsInfo))
        return JNI_DRM_FAILURE;

    return setRightsFields(env, rights, &rightsInfo);
}

/* native interface */
JNIEXPORT jint JNICALL
Java_android_drm_mobile1_DrmRightsManager_nativeGetNumOfRights
  (JNIEnv * env, jobject rightsManager)
{
    T_DRM_Rights_Info_Node *pRightsList;
    T_DRM_Rights_Info_Node *pCurNode;
    int32_t num = 0;

    if (DRM_FAILURE == SVC_drm_viewAllRights(&pRightsList))
        return JNI_DRM_FAILURE;

    pCurNode = pRightsList;
    while (pCurNode != NULL) {
        num++;
        pCurNode = pCurNode->next;
    }

    SVC_drm_freeRightsInfoList(pRightsList);

    return num;
}

/* native interface */
JNIEXPORT jint JNICALL
Java_android_drm_mobile1_DrmRightsManager_nativeGetRightsList
  (JNIEnv * env, jobject rightsManager, jobjectArray rightsArray, jint num)
{
    T_DRM_Rights_Info_Node *pRightsList;
    T_DRM_Rights_Info_Node *pCurNode;
    int32_t index;

    if (DRM_FAILURE == SVC_drm_viewAllRights(&pRightsList))
        return JNI_DRM_FAILURE;

    pCurNode = pRightsList;
    for (index = 0; NULL != pCurNode; index++) {
        jobject rights = (*env)->GetObjectArrayElement(env, rightsArray, index);
        if (NULL == rights)
            break;

        if (JNI_DRM_FAILURE == setRightsFields(env, rights, &(pCurNode->roInfo)))
            break;

        (*env)->SetObjectArrayElement(env, rightsArray, index, rights);

        pCurNode = pCurNode->next;
    }

    SVC_drm_freeRightsInfoList(pRightsList);

    return index;
}

/* native interface */
JNIEXPORT jint JNICALL
Java_android_drm_mobile1_DrmRightsManager_nativeDeleteRights
  (JNIEnv * env, jobject rightsManager, jobject rights)
{
    jclass clazz;
    jfieldID field;
    jstring str;
    uint8_t *nativeStr;

    clazz = (*env)->GetObjectClass(env, rights);
    if (NULL == clazz)
        return JNI_DRM_FAILURE;

    field = (*env)->GetFieldID(env, clazz, "roId", "Ljava/lang/String;");
    if (NULL == field)
        return JNI_DRM_FAILURE;

    str = (*env)->GetObjectField(env, rights, field);

    nativeStr = (uint8_t *)(*env)->GetStringUTFChars(env, str, NULL);
    if (NULL == nativeStr)
        return JNI_DRM_FAILURE;

    if (0 == strcmp("ForwardLock", (char *)nativeStr))
        return JNI_DRM_SUCCESS;

    if (DRM_SUCCESS != SVC_drm_deleteRights(nativeStr)) {
        (*env)->ReleaseStringUTFChars(env, str, (char *)nativeStr);
        return JNI_DRM_FAILURE;
    }

    (*env)->ReleaseStringUTFChars(env, str, (char *)nativeStr);
    return JNI_DRM_SUCCESS;
}

/*
 * Table of methods associated with the DrmRawContent class.
 */
static JNINativeMethod gDrmRawContentMethods[] = {
    /* name, signature, funcPtr */
    {"nativeConstructDrmContent", "(Ljava/io/InputStream;II)I",
        (void*)Java_android_drm_mobile1_DrmRawContent_nativeConstructDrmContent},
    {"nativeGetRightsAddress", "()Ljava/lang/String;",
        (void*)Java_android_drm_mobile1_DrmRawContent_nativeGetRightsAddress},
    {"nativeGetDeliveryMethod", "()I",
        (void*)Java_android_drm_mobile1_DrmRawContent_nativeGetDeliveryMethod},
    {"nativeReadContent", "([BIII)I",
        (void*)Java_android_drm_mobile1_DrmRawContent_nativeReadContent},
    {"nativeGetContentType", "()Ljava/lang/String;",
        (void*)Java_android_drm_mobile1_DrmRawContent_nativeGetContentType},
    {"nativeGetContentLength", "()I",
        (void*)Java_android_drm_mobile1_DrmRawContent_nativeGetContentLength},
    {"finalize", "()V",
        (void*)Java_android_drm_mobile1_DrmRawContent_finalize},
};

/*
 * Table of methods associated with the DrmRights class.
 */
static JNINativeMethod gDrmRightsMethods[] = {
    /* name, signature, funcPtr */
    {"nativeGetConstraintInfo", "(ILandroid/drm/mobile1/DrmConstraintInfo;)I",
        (void*)Java_android_drm_mobile1_DrmRights_nativeGetConstraintInfo},
    {"nativeConsumeRights", "(I)I",
        (void*)Java_android_drm_mobile1_DrmRights_nativeConsumeRights},
};

/*
 * Table of methods associated with the DrmRightsManager class.
 */
static JNINativeMethod gDrmRightsManagerMethods[] = {
    /* name, signature, funcPtr */
    {"nativeInstallDrmRights", "(Ljava/io/InputStream;IILandroid/drm/mobile1/DrmRights;)I",
        (void*)Java_android_drm_mobile1_DrmRightsManager_nativeInstallDrmRights},
    {"nativeQueryRights", "(Landroid/drm/mobile1/DrmRawContent;Landroid/drm/mobile1/DrmRights;)I",
        (void*)Java_android_drm_mobile1_DrmRightsManager_nativeQueryRights},
    {"nativeGetNumOfRights", "()I",
        (void*)Java_android_drm_mobile1_DrmRightsManager_nativeGetNumOfRights},
    {"nativeGetRightsList", "([Landroid/drm/mobile1/DrmRights;I)I",
        (void*)Java_android_drm_mobile1_DrmRightsManager_nativeGetRightsList},
    {"nativeDeleteRights", "(Landroid/drm/mobile1/DrmRights;)I",
        (void*)Java_android_drm_mobile1_DrmRightsManager_nativeDeleteRights},
};

/*
 * Register several native methods for one class.
 */
static int registerNativeMethods(JNIEnv* env, const char* className,
    JNINativeMethod* gMethods, int numMethods)
{
    jclass clazz;

    clazz = (*env)->FindClass(env, className);
    if (clazz == NULL)
        return JNI_FALSE;

    if ((*env)->RegisterNatives(env, clazz, gMethods, numMethods) < 0)
        return JNI_FALSE;

    return JNI_TRUE;
}

/*
 * Register native methods for all classes we know about.
 */
static int registerNatives(JNIEnv* env)
{
    if (!registerNativeMethods(env, "android/drm/mobile1/DrmRawContent",
            gDrmRawContentMethods, sizeof(gDrmRawContentMethods) / sizeof(gDrmRawContentMethods[0])))
        return JNI_FALSE;

    if (!registerNativeMethods(env, "android/drm/mobile1/DrmRights",
            gDrmRightsMethods, sizeof(gDrmRightsMethods) / sizeof(gDrmRightsMethods[0])))
        return JNI_FALSE;

    if (!registerNativeMethods(env, "android/drm/mobile1/DrmRightsManager",
            gDrmRightsManagerMethods, sizeof(gDrmRightsManagerMethods) / sizeof(gDrmRightsManagerMethods[0])))
        return JNI_FALSE;

    return JNI_TRUE;
}

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = NULL;
    jint result = -1;

    printf("Entering JNI_OnLoad\n");

    if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_4) != JNI_OK)
        goto bail;

    assert(env != NULL);

    if (!registerNatives(env))
        goto bail;

    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

bail:
    printf("Leaving JNI_OnLoad (result=0x%x)\n", result);
    return result;
}
