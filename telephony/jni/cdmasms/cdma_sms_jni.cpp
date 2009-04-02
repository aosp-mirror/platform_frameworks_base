/*
 * Copyright (C) 2008 The Android Open Source Project
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
 * @file cdma_sms_jni.cpp
 *
 * This file implement the Java Native Interface
 * for encoding and decoding of SMS
 */


#include <nativehelper/jni.h>
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <cdma_sms_jni.h>


#ifdef __cplusplus
extern "C" {
#endif //__cplusplus

#include <reference-cdma-sms.h>

#ifdef __cplusplus
}
#endif //__cplusplus

#undef LOG_TAG
#define LOG_TAG "CDMA"
#include <utils/Log.h>

static RIL_CDMA_SMS_ClientBd *clientBdData = NULL;


static jint getObjectIntField(JNIEnv * env, jobject obj, const char *name, jint * value)
{
    jclass clazz;
    jfieldID field;

#ifdef DBG_LOG_LEVEL_B
    LOGD("getObjectIntField():");
#endif

    clazz = env->GetObjectClass(obj);
    if (NULL == clazz) {
        jniThrowException(env, "java/lang/Exception", NULL);
        return JNI_FAILURE;
    }

    field = env->GetFieldID(clazz, name, "I");
    env->DeleteLocalRef(clazz);

    if (NULL == field) {
        jniThrowException(env, "java/lang/NoSuchFieldException", name);
        return JNI_FAILURE;
    }

    *value = env->GetIntField(obj, field);

#ifdef DBG_LOG_LEVEL_B
    LOGD("  %s = %d\n", name, *value);
#endif

    return JNI_SUCCESS;
}

static jint setObjectIntField(JNIEnv * env, jobject obj, const char *name, jint value)
{
    jclass clazz;
    jfieldID field;

#ifdef DBG_LOG_LEVEL_B
    LOGD("setObjectIntField(): %s = %d\n", name, value);
#endif

    clazz = env->GetObjectClass(obj);
    if (NULL == clazz) {
        jniThrowException(env, "java/lang/Exception", NULL);
        return JNI_FAILURE;
    }

    field = env->GetFieldID(clazz, name, "I");
    env->DeleteLocalRef(clazz);

    if (NULL == field) {
        jniThrowException(env, "java/lang/NoSuchFieldException", name);
        return JNI_FAILURE;
    }

    env->SetIntField(obj, field, value);

    return JNI_SUCCESS;
}

static jint getObjectByteField(JNIEnv * env, jobject obj, const char *name, jbyte * value)
{
    jclass clazz;
    jfieldID field;

#ifdef DBG_LOG_LEVEL_B
    LOGD("getObjectByteField():");
#endif

    clazz = env->GetObjectClass(obj);
    if (NULL == clazz) {
        jniThrowException(env, "java/lang/Exception", NULL);
        return JNI_FAILURE;
    }

    field = env->GetFieldID(clazz, name, "B");
    env->DeleteLocalRef(clazz);

    if (NULL == field) {
        jniThrowException(env, "java/lang/NoSuchFieldException", name);
        return JNI_FAILURE;
    }

    *value = env->GetByteField(obj, field);

#ifdef DBG_LOG_LEVEL_B
    LOGD("  %s = %02x\n", name, *value);
#endif

    return JNI_SUCCESS;
}

static jint setObjectByteField(JNIEnv * env, jobject obj, const char *name, jbyte value)
{
    jclass clazz;
    jfieldID field;
#ifdef DBG_LOG_LEVEL_B
    LOGD("setObjectByteField(): %s = 0x%02x\n", name, value);
#endif

    clazz = env->GetObjectClass(obj);
    if (NULL == clazz) {
        jniThrowException(env, "java/lang/Exception", NULL);
        return JNI_FAILURE;
    }

    field = env->GetFieldID(clazz, name, "B");
    env->DeleteLocalRef(clazz);

    if (NULL == field) {
        jniThrowException(env, "java/lang/NoSuchFieldException", name);
        return JNI_FAILURE;
    }

    env->SetByteField(obj, field, value);

    return JNI_SUCCESS;
}

static jint getObjectBooleanField(JNIEnv * env, jobject obj, const char *name, jboolean * value)
{
    jclass clazz;
    jfieldID field;

#ifdef DBG_LOG_LEVEL_B
    LOGD("getObjectBooleanField():");
#endif

    clazz = env->GetObjectClass(obj);
    if (NULL == clazz) {
        jniThrowException(env, "java/lang/Exception", NULL);
        return JNI_FAILURE;
    }

    field = env->GetFieldID(clazz, name, "Z");
    env->DeleteLocalRef(clazz);

    if (NULL == field) {
        jniThrowException(env, "java/lang/NoSuchFieldException", name);
        return JNI_FAILURE;
    }

    *value = env->GetBooleanField(obj, field);

#ifdef DBG_LOG_LEVEL_B
    LOGD("  %s = %d\n", name, *value);
#endif

    return JNI_SUCCESS;
}

static jint setObjectBooleanField(JNIEnv * env, jobject obj, const char *name, jboolean value)
{
    jclass clazz;
    jfieldID field;

#ifdef DBG_LOG_LEVEL_B
    LOGD("setObjectBooleanField(): %s = %d\n", name, value);
#endif

    clazz = env->GetObjectClass(obj);
    if (NULL == clazz) {
        jniThrowException(env, "java/lang/Exception", NULL);
        return JNI_FAILURE;
    }

    field = env->GetFieldID(clazz, name, "Z");
    env->DeleteLocalRef(clazz);

    if (NULL == field) {
        jniThrowException(env, "java/lang/NoSuchFieldException", name);
        return JNI_FAILURE;
    }

    env->SetBooleanField(obj, field, value);

    return JNI_SUCCESS;
}

static jint getObjectByteArrayField(JNIEnv * env, jobject obj, const char *name, jbyte* arrData, int* length)
{
    jclass clazz;
    jfieldID field;
    jbyte * data_buf;

#ifdef DBG_LOG_LEVEL_B
    LOGD("getObjectByteArrayField(): %s\n", name);
#endif

    clazz = env->GetObjectClass(obj);
    if (NULL == clazz) {
        jniThrowException(env, "java/lang/Exception", NULL);
        return JNI_FAILURE;
    }

    field = env->GetFieldID(clazz, name, "[B");
    env->DeleteLocalRef(clazz);

    if (NULL == field) {
        jniThrowException(env, "java/lang/NoSuchFieldException", name);
        return JNI_FAILURE;
    }

    jbyteArray buffer = (jbyteArray)(env->GetObjectField(obj, field));
    if (buffer != NULL) {
        int len = env->GetArrayLength(buffer);
        data_buf = env->GetByteArrayElements(buffer, NULL);
        for (int i=0; i<len; i++) {
            *arrData++ = data_buf[i];
#ifdef DBG_LOG_LEVEL_B
            LOGD("  [%d] = 0x%02x\n", i, data_buf[i]);
#endif
        }
        *length = len;
    } else {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return JNI_FAILURE;
    }

    return JNI_SUCCESS;
}

static jint setObjectByteArrayField(JNIEnv * env, jobject obj, const char *name, jbyte* arrData, int length)
{
    jclass clazz;
    jfieldID field;
    jbyte* byte_buf;

#ifdef DBG_LOG_LEVEL_B
    LOGD("setObjectByteArrayField(): %s\n", name);
#endif

    clazz = env->GetObjectClass(obj);
    if (NULL == clazz) {
        jniThrowException(env, "java/lang/Exception", NULL);
        return JNI_FAILURE;
    }

    field = env->GetFieldID(clazz, name, "[B");
    env->DeleteLocalRef(clazz);

    if (NULL == field) {
        jniThrowException(env, "java/lang/NoSuchFieldException", name);
        return JNI_FAILURE;
    }

    jbyteArray buffer = (jbyteArray)(env->GetObjectField(obj, field));
    if (buffer == NULL) {
#ifdef DBG_LOG_LEVEL_B
        LOGD("setObjectByteArrayField(): %s = null\n", name);
#endif
        buffer = env->NewByteArray(length);
        env->SetObjectField(obj, field, buffer);
    }

    if (buffer != NULL) {
#ifdef DBG_LOG_LEVEL_B
        for (int i=0; i<length; i++) {
            LOGD("  [%d] = 0x%02x\n", i, arrData[i]);
        }
#endif
        env->SetByteArrayRegion(buffer, 0, length, arrData);
    } else {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return JNI_FAILURE;
    }

    return JNI_SUCCESS;
}


/* native interface */
JNIEXPORT jint JNICALL
Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsConstructClientBD
  (JNIEnv * env, jobject obj)
{
#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsConstructClientBD()...\n");
#endif

    clientBdData = (RIL_CDMA_SMS_ClientBd *)malloc(sizeof(RIL_CDMA_SMS_ClientBd));
    if (NULL == clientBdData) {
        jniThrowException(env, "java/lang/OutOfMemoryError", "clientBdData memory allocation failed");
        return JNI_FAILURE;
    }
    memset(clientBdData, 0, sizeof(RIL_CDMA_SMS_ClientBd));
    return JNI_SUCCESS;
}


/* native interface */
JNIEXPORT jint JNICALL
Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsDestructClientBD
  (JNIEnv * env, jobject obj)
{
#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsDestructClientBD()...\n");
#endif

    if (clientBdData == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", "clientBdData is null");
        return JNI_FAILURE;
    }
    free(clientBdData);
    return JNI_SUCCESS;
}


/* native interface */
JNIEXPORT jint JNICALL
Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsSetBearerDataPrimitives
  (JNIEnv * env, jobject obj, jobject bearerData)
{
    jbyteArray mc_time = NULL;
    jbyte mctime_buffer[6];
    int length;
    jint intData;
    jbyte byteData;
    jboolean booleanData;

#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsSetBearerDataPrimitives()...\n");
#endif

    // mask
    if (getObjectIntField(env, bearerData, "mask", &intData) != JNI_SUCCESS)
        return JNI_FAILURE;
    clientBdData->mask = intData;
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->mask = 0x%x\n", clientBdData->mask);
#endif

    // message_id.type
    if (getObjectByteField(env, bearerData, "messageType", &byteData) != JNI_SUCCESS)
        return JNI_FAILURE;
    clientBdData->message_id.type = (RIL_CDMA_SMS_BdMessageType)(byteData);
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->message_id.type = 0x%02x\n", clientBdData->message_id.type);
#endif

    // message_id.id_number
    if ((clientBdData->mask & WMS_MASK_BD_MSG_ID) == WMS_MASK_BD_MSG_ID) {
        if (getObjectIntField(env, bearerData, "messageID", &intData) != JNI_SUCCESS)
            return JNI_FAILURE;
        clientBdData->message_id.id_number = (RIL_CDMA_SMS_MessageNumber)(intData);
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->message_id.id_number = %d\n", clientBdData->message_id.id_number);
#endif
    }

    // message_id.udh_present
    if (getObjectBooleanField(env, bearerData, "hasUserDataHeader", &booleanData) != JNI_SUCCESS)
        return JNI_FAILURE;
    clientBdData->message_id.udh_present = (unsigned char)(booleanData);
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->message_id.udh_present = %d\n", clientBdData->message_id.udh_present);
#endif

    // user_response
    // TODO

    // mc_time
    if ((clientBdData->mask & WMS_MASK_BD_MC_TIME) == WMS_MASK_BD_MC_TIME) {
        if (getObjectByteArrayField(env, bearerData, "timeStamp", mctime_buffer, &length) != JNI_SUCCESS)
            return JNI_FAILURE;
        if (mctime_buffer != NULL) {
            clientBdData->mc_time.year     = mctime_buffer[0];
            clientBdData->mc_time.month    = mctime_buffer[1];
            clientBdData->mc_time.day      = mctime_buffer[2];
            clientBdData->mc_time.hour     = mctime_buffer[3];
            clientBdData->mc_time.minute   = mctime_buffer[4];
            clientBdData->mc_time.second   = mctime_buffer[5];
#ifdef DBG_LOG_LEVEL_A
            LOGD("clientBdData->mc_time.year   = %d\n", clientBdData->mc_time.year);
            LOGD("clientBdData->mc_time.month  = %d\n", clientBdData->mc_time.month);
            LOGD("clientBdData->mc_time.day    = %d\n", clientBdData->mc_time.day);
            LOGD("clientBdData->mc_time.hour   = %d\n", clientBdData->mc_time.hour);
            LOGD("clientBdData->mc_time.minute = %d\n", clientBdData->mc_time.minute);
            LOGD("clientBdData->mc_time.second = %d\n", clientBdData->mc_time.second);
#endif
        }
    }

    // clientBdData->mc_time.timezone
    // TODO

    // validity_absolute;
    // TODO

    // validity_relative;
    // TODO

    // deferred_absolute
    // TODO

    // deferred_relative;
    // TODO

    // priority
    // TODO

    // privacy
    // TODO

    if ((clientBdData->mask & WMS_MASK_BD_REPLY_OPTION) == WMS_MASK_BD_REPLY_OPTION) {
        // reply_option.user_ack_requested
        if (getObjectBooleanField(env, bearerData, "userAckReq", &booleanData) != JNI_SUCCESS)
            return JNI_FAILURE;
        clientBdData->reply_option.user_ack_requested = (unsigned char)(booleanData);
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->reply_option.user_ack_requested = %d\n", clientBdData->reply_option.user_ack_requested);
#endif
        // reply_option.user_ack_requested
        if (getObjectBooleanField(env, bearerData, "deliveryAckReq", &booleanData) != JNI_SUCCESS)
            return JNI_FAILURE;
        clientBdData->reply_option.delivery_ack_requested = (unsigned char)(booleanData);
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->reply_option.delivery_ack_requested = %d\n", clientBdData->reply_option.delivery_ack_requested);
#endif
        // reply_option.user_ack_requested
        if (getObjectBooleanField(env, bearerData, "readAckReq", &booleanData) != JNI_SUCCESS)
            return JNI_FAILURE;
        clientBdData->reply_option.read_ack_requested = (unsigned char)(booleanData);
#ifdef DBG_LOG_LEVEL_A
            LOGD("clientBdData->reply_option.read_ack_requested = %d\n", clientBdData->reply_option.read_ack_requested);
#endif
    }

    // num_messages
    if ((clientBdData->mask & WMS_MASK_BD_NUM_OF_MSGS) == WMS_MASK_BD_NUM_OF_MSGS) {
        if (getObjectIntField(env, bearerData, "numberOfMessages", &intData) != JNI_SUCCESS)
            return JNI_FAILURE;
        clientBdData->num_messages = (unsigned char)(intData);
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->num_messages = %d\n", clientBdData->num_messages);
#endif
    }

    // alert_mode
    // TODO

    // language
    // TODO

    // display_mode
    if ((clientBdData->mask & WMS_MASK_BD_DISPLAY_MODE) == WMS_MASK_BD_DISPLAY_MODE) {
        if (getObjectByteField(env, bearerData, "displayMode", &byteData) != JNI_SUCCESS)
            return JNI_FAILURE;
        clientBdData->display_mode = (RIL_CDMA_SMS_DisplayMode)(byteData);
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->display_mode = 0x%02x\n", clientBdData->display_mode);
#endif
    }

    // delivery_status
    if ((clientBdData->mask & WMS_MASK_BD_DELIVERY_STATUS) == WMS_MASK_BD_DELIVERY_STATUS) {
        // delivery_status.error_class
        if (getObjectIntField(env, bearerData, "errorClass", &intData) != JNI_SUCCESS)
            return JNI_FAILURE;
        clientBdData->delivery_status.error_class = (RIL_CDMA_SMS_ErrorClass)(intData);
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->delivery_status.error_class = %d\n", clientBdData->delivery_status.error_class);
#endif
        // delivery_status.status
        if (getObjectIntField(env, bearerData, "messageStatus", &intData) != JNI_SUCCESS)
            return JNI_FAILURE;
        clientBdData->delivery_status.status = (RIL_CDMA_SMS_DeliveryStatusE)(intData);
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->delivery_status.status = %d\n", clientBdData->delivery_status.status);
#endif
    }

    // deposit_index
    // TODO

    // ip_address
    // TODO

    // rsn_no_notify
    // TODO

    // other
    // TODO

    return JNI_SUCCESS;
}


/* native interface */
JNIEXPORT jint JNICALL
Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsGetBearerDataPrimitives
  (JNIEnv * env, jobject obj, jobject bearerData)
{
    jclass BearerDataClass;
    jfieldID field;
    jbyte mctime_buffer[6];
    jbyteArray addr_array;
    int length;

#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsGetBearerDataPrimitives()...\n");
#endif

    // mask
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->mask = 0x%x\n", clientBdData->mask);
#endif
    if (setObjectIntField(env, bearerData, "mask", clientBdData->mask) != JNI_SUCCESS)
        return JNI_FAILURE;

    // message_id.type
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->message_id.type = 0x%02x\n", clientBdData->message_id.type);
#endif
    if (setObjectByteField(env, bearerData, "messageType", (jbyte)clientBdData->message_id.type) != JNI_SUCCESS)
        return JNI_FAILURE;

    // message_id.id_number
    if ((clientBdData->mask & WMS_MASK_BD_MSG_ID) == WMS_MASK_BD_MSG_ID) {
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->message_id.id_number = %d\n", clientBdData->message_id.id_number);
#endif
        if (setObjectIntField(env, bearerData, "messageID", clientBdData->message_id.id_number) != JNI_SUCCESS)
            return JNI_FAILURE;
    }

    // message_id.udh_present
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->message_id.udh_present = %d\n", clientBdData->message_id.udh_present);
#endif
    if (setObjectBooleanField(env, bearerData, "hasUserDataHeader", (jboolean)clientBdData->message_id.udh_present) != JNI_SUCCESS)
        return JNI_FAILURE;

    // user_response
    // TODO

    // mc_time
    if ((clientBdData->mask & WMS_MASK_BD_MC_TIME) == WMS_MASK_BD_MC_TIME) {
        jclass clazz= env->GetObjectClass(bearerData);
        if (NULL == clazz)
            return JNI_FAILURE;
        jfieldID field = env->GetFieldID(clazz, "timeStamp", "[B");
        env->DeleteLocalRef(clazz);

        addr_array = env->NewByteArray((jsize)6);
        env->SetObjectField(bearerData, field, addr_array);

#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->mc_time.year   = %d\n", clientBdData->mc_time.year);
        LOGD("clientBdData->mc_time.month  = %d\n", clientBdData->mc_time.month);
        LOGD("clientBdData->mc_time.day    = %d\n", clientBdData->mc_time.day);
        LOGD("clientBdData->mc_time.hour   = %d\n", clientBdData->mc_time.hour);
        LOGD("clientBdData->mc_time.minute = %d\n", clientBdData->mc_time.minute);
        LOGD("clientBdData->mc_time.second = %d\n", clientBdData->mc_time.second);
#endif
        mctime_buffer[0] = clientBdData->mc_time.year;
        mctime_buffer[1] = clientBdData->mc_time.month;
        mctime_buffer[2] = clientBdData->mc_time.day;
        mctime_buffer[3] = clientBdData->mc_time.hour;
        mctime_buffer[4] = clientBdData->mc_time.minute;
        mctime_buffer[5] = clientBdData->mc_time.second;
        length = sizeof(mctime_buffer) / sizeof(jbyte);
        if (setObjectByteArrayField(env, bearerData, "timeStamp", mctime_buffer, length) != JNI_SUCCESS)
            return JNI_FAILURE;
    }

    // clientBdData->mc_time.timezone
    // TODO

    // validity_absolute;
    // TODO

    // validity_relative;
    // TODO

    // deferred_absolute
    // TODO

    // deferred_relative;
    // TODO

    // priority
    // TODO

    // privacy
    // TODO

    if ((clientBdData->mask & WMS_MASK_BD_REPLY_OPTION) == WMS_MASK_BD_REPLY_OPTION) {
        // reply_option.user_ack_requested
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->reply_option.user_ack_requested = %d\n", clientBdData->reply_option.user_ack_requested);
#endif
        if (setObjectBooleanField(env, bearerData, "userAckReq", (jboolean)clientBdData->reply_option.user_ack_requested) != JNI_SUCCESS)
            return JNI_FAILURE;

        // reply_option.user_ack_requested
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->reply_option.delivery_ack_requested = %d\n", clientBdData->reply_option.delivery_ack_requested);
#endif
        if (setObjectBooleanField(env, bearerData, "deliveryAckReq", (jboolean)clientBdData->reply_option.delivery_ack_requested) != JNI_SUCCESS)
            return JNI_FAILURE;

        // reply_option.user_ack_requested
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->reply_option.read_ack_requested = %d\n", clientBdData->reply_option.read_ack_requested);
#endif
        if (setObjectBooleanField(env, bearerData, "readAckReq", (jboolean)clientBdData->reply_option.read_ack_requested) != JNI_SUCCESS)
            return JNI_FAILURE;
    }

    // num_messages
    if ((clientBdData->mask & WMS_MASK_BD_NUM_OF_MSGS) == WMS_MASK_BD_NUM_OF_MSGS) {
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->num_messages = %d\n", clientBdData->num_messages);
#endif
        if (setObjectIntField(env, bearerData, "numberOfMessages", (int)clientBdData->num_messages) != JNI_SUCCESS)
            return JNI_FAILURE;
    }

    // alert_mode
    // TODO

    // language
    // TODO

    // display_mode
    if ((clientBdData->mask & WMS_MASK_BD_DISPLAY_MODE) == WMS_MASK_BD_DISPLAY_MODE) {
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->display_mode = 0x%02x\n", clientBdData->display_mode);
#endif
        if (setObjectByteField(env, bearerData, "displayMode", (jbyte)clientBdData->display_mode) != JNI_SUCCESS)
            return JNI_FAILURE;
    }

    // delivery_status
    if ((clientBdData->mask & WMS_MASK_BD_DELIVERY_STATUS) == WMS_MASK_BD_DELIVERY_STATUS) {
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->delivery_status.error_class = %d\n", clientBdData->delivery_status.error_class);
#endif
        // delivery_status.error_class
        if (setObjectIntField(env, bearerData, "errorClass", (int)clientBdData->delivery_status.error_class) != JNI_SUCCESS)
            return JNI_FAILURE;
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->delivery_status.status = %d\n", clientBdData->delivery_status.status);
#endif
        // delivery_status.status
        if (setObjectIntField(env, bearerData, "messageStatus", (int)clientBdData->delivery_status.status) != JNI_SUCCESS)
            return JNI_FAILURE;
    }

    // deposit_index
    // TODO

    // ip_address
    // TODO

    // rsn_no_notify
    // TODO

    // other
    // TODO

    return JNI_SUCCESS;
}


/* native interface */
JNIEXPORT jint JNICALL
Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsSetUserData
  (JNIEnv * env, jobject obj, jobject userData)
{
    jclass UserDataClass;
    jfieldID field;
    jbyteArray arrData = NULL;
    jbyte data_buf[RIL_CDMA_SMS_USER_DATA_MAX];
    int length;
    jint intData;
    jbyte byteData;
    jboolean booleanData;

#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsSetUserData()...\n");
#endif

    // set num_headers to 0 here, increment later
    clientBdData->user_data.num_headers = 0;

    // user_data.encoding
    if (getObjectIntField(env, userData, "userDataEncoding", &intData) != JNI_SUCCESS)
        return JNI_FAILURE;
    clientBdData->user_data.encoding = (RIL_CDMA_SMS_UserDataEncoding)(intData);
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->user_data.encoding = %d\n", clientBdData->user_data.encoding);
#endif

    // is91ep_type
    // TODO

    // user_data.padding_bits
    if (getObjectIntField(env, userData, "paddingBits", &intData) != JNI_SUCCESS)
        return JNI_FAILURE;
    clientBdData->user_data.padding_bits = (unsigned char)(intData);
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->user_data.padding_bits = %d\n", clientBdData->user_data.padding_bits);
#endif

    // user_data.data
    if (getObjectByteArrayField(env, userData, "userData", data_buf, &length) != JNI_SUCCESS )
        return JNI_FAILURE;
    for (int i = 0; i < length; i++) {
        clientBdData->user_data.data[i] = data_buf[i];
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->user_data.data[%d] = 0x%02x\n", i, clientBdData->user_data.data[i]);
#endif
    }

    // user_data.data_len
    // TODO

    // number_of_digits
    clientBdData->user_data.number_of_digits = (unsigned char)(length);
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->user_data.number_of_digits = %d\n", clientBdData->user_data.number_of_digits);
#endif

    return JNI_SUCCESS;
}


/* native interface */
JNIEXPORT jint JNICALL
Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsGetUserData
  (JNIEnv * env, jobject obj, jobject userData)
{
    jclass UserDataClass;
    jfieldID field;
    jbyte *data_buf;
    int length;

#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsGetUserData()...\n");
#endif

    // user_data.num_headers
//    if (setObjectIntField(env, userData, "mNumberOfHeaders", (int)clientBdData->user_data.num_headers) != JNI_SUCCESS)
//        return JNI_FAILURE;

    // user_data.encoding
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->user_data.encoding = %d\n", clientBdData->user_data.encoding);
#endif
    if (setObjectIntField(env, userData, "userDataEncoding", clientBdData->user_data.encoding) != JNI_SUCCESS)
        return JNI_FAILURE;

    // is91ep_type
    // TODO

    // user_data.data_len
//    if (setObjectIntField(env, userData, "mDataLength", (int)clientBdData->user_data.data_len) != JNI_SUCCESS)
//        return JNI_FAILURE;

    // user_data.padding_bits
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->user_data.padding_bits = %d\n", clientBdData->user_data.padding_bits);
#endif
    if (setObjectIntField(env, userData, "paddingBits", (int)clientBdData->user_data.padding_bits) != JNI_SUCCESS)
        return JNI_FAILURE;

    // user_data.data
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->user_data.data_len = %d\n", clientBdData->user_data.data_len);
#endif
    length = clientBdData->user_data.data_len;
#ifdef DBG_LOG_LEVEL_A
    for (int i = 0; i < length; i++) {
        LOGD("clientBdData->user_data.data[%d] = 0x%02x\n", i, clientBdData->user_data.data[i]);
    }
#endif
    data_buf = (jbyte*)clientBdData->user_data.data;
    if (setObjectByteArrayField(env, userData, "userData", data_buf, length) != JNI_SUCCESS)
        return JNI_FAILURE;

    // number_of_digits
    // TODO

    return JNI_SUCCESS;
}


/* native interface */
JNIEXPORT jint JNICALL Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsSetUserDataHeader
  (JNIEnv * env, jobject obj, jint ID, jbyteArray data, jint length, jint index)
{
    jbyte data_buf[length];

#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsSetUserDataHeader()...\n");
#endif

    env->GetByteArrayRegion(data, 0, length, data_buf);

    // user_data.headers[index].header_id
    clientBdData->user_data.headers[index].header_id = (RIL_CDMA_SMS_UdhId)(ID);

    // user_data.headers[index].u
    // TODO: add support for all udh id's
    switch(clientBdData->user_data.headers[index].header_id)
    {
        case RIL_CDMA_SMS_UDH_CONCAT_8:
            clientBdData->user_data.headers[index].u.concat_8.msg_ref  = data_buf[0];
            clientBdData->user_data.headers[index].u.concat_8.total_sm = data_buf[1];
            clientBdData->user_data.headers[index].u.concat_8.seq_num  = data_buf[2];
#ifdef DBG_LOG_LEVEL_A
            LOGD("clientBdData->user_data.headers[%d].u.concat_8.msg_ref  = 0x%02x\n", index, clientBdData->user_data.headers[index].u.concat_8.msg_ref);
            LOGD("clientBdData->user_data.headers[%d].u.concat_8.total_sm = 0x%02x\n", index, clientBdData->user_data.headers[index].u.concat_8.total_sm);
            LOGD("clientBdData->user_data.headers[%d].u.concat_8.seq_num  = 0x%02x\n", index, clientBdData->user_data.headers[index].u.concat_8.seq_num);
#endif
        break;
        case RIL_CDMA_SMS_UDH_SPECIAL_SM:
            clientBdData->user_data.headers[index].u.special_sm.msg_waiting      = (RIL_CDMA_SMS_GWMsgWaiting)(
                                                                                   (data_buf[0] << 23) | (data_buf[1] << 15) |
                                                                                   (data_buf[2] << 7)  |  data_buf[3]);
            clientBdData->user_data.headers[index].u.special_sm.msg_waiting_kind = (RIL_CDMA_SMS_GWMsgWaitingKind)(
                                                                                   (data_buf[4] << 23) | (data_buf[5] << 15) |
                                                                                   (data_buf[6] << 7)  |  data_buf[7]);
            clientBdData->user_data.headers[index].u.special_sm.message_count    =  data_buf[8];
#ifdef DBG_LOG_LEVEL_A
            LOGD("clientBdData->user_data.headers[%d].u.special_sm.msg_waiting      = 0x%04x\n", index, clientBdData->user_data.headers[index].u.special_sm.msg_waiting);
            LOGD("clientBdData->user_data.headers[%d].u.special_sm.msg_waiting_kind = 0x%04x\n", index, clientBdData->user_data.headers[index].u.special_sm.msg_waiting_kind);
            LOGD("clientBdData->user_data.headers[%d].u.special_sm.message_count    = 0x%02x\n", index, clientBdData->user_data.headers[index].u.special_sm.message_count);
#endif
        break;
        case RIL_CDMA_SMS_UDH_PORT_8:
            clientBdData->user_data.headers[index].u.wap_8.dest_port = data_buf[0];
            clientBdData->user_data.headers[index].u.wap_8.orig_port = data_buf[1];
#ifdef DBG_LOG_LEVEL_A
            LOGD("clientBdData->user_data.headers[%d].u.wap_8.dest_port = 0x%02x\n", index, clientBdData->user_data.headers[index].u.wap_8.dest_port);
            LOGD("clientBdData->user_data.headers[%d].u.wap_8.orig_port = 0x%02x\n", index, clientBdData->user_data.headers[index].u.wap_8.orig_port);
#endif
        break;
        case RIL_CDMA_SMS_UDH_PORT_16:
            clientBdData->user_data.headers[index].u.wap_16.dest_port = (data_buf[0] << 7) | data_buf[1]; // unsigned short
            clientBdData->user_data.headers[index].u.wap_16.orig_port = (data_buf[2] << 7) | data_buf[3]; // unsigned short
#ifdef DBG_LOG_LEVEL_A
            LOGD("clientBdData->user_data.headers[%d].u.wap_16.dest_port = 0x%04x\n", index, clientBdData->user_data.headers[index].u.wap_16.dest_port);
            LOGD("clientBdData->user_data.headers[%d].u.wap_16.orig_port = 0x%04x\n", index, clientBdData->user_data.headers[index].u.wap_16.orig_port);
#endif
        break;
        case RIL_CDMA_SMS_UDH_CONCAT_16:
            clientBdData->user_data.headers[index].u.concat_16.msg_ref  = (data_buf[0] << 7) | data_buf[1]; // unsigned short
            clientBdData->user_data.headers[index].u.concat_16.total_sm =  data_buf[2];
            clientBdData->user_data.headers[index].u.concat_16.seq_num  =  data_buf[3];
#ifdef DBG_LOG_LEVEL_A
            LOGD("clientBdData->user_data.headers[%d].u.concat_16.msg_ref  = 0x%04x\n", index, clientBdData->user_data.headers[index].u.concat_16.msg_ref);
            LOGD("clientBdData->user_data.headers[%d].u.concat_16.total_sm = 0x%04x\n", index, clientBdData->user_data.headers[index].u.concat_16.total_sm);
            LOGD("clientBdData->user_data.headers[%d].u.concat_16.seq_num  = 0x%04x\n", index, clientBdData->user_data.headers[index].u.concat_16.seq_num);
#endif
        break;
        default:
        break;
    }

    // increment num_of_headers
    clientBdData->user_data.num_headers++;

    return JNI_SUCCESS;
}


/* native interface */
JNIEXPORT jbyteArray JNICALL
Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsGetUserDataHeader
  (JNIEnv * env, jobject obj)
{
    jbyteArray arrData = NULL;
    jbyte data_buf[sizeof(clientBdData->user_data.headers)];
    int length = 0;

#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsGetUserDataHeader()...\n");
#endif

#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->user_data.num_headers = %d, size = %d\n", clientBdData->user_data.num_headers, sizeof(clientBdData->user_data.headers));
#endif

    for (int index = 0; index < clientBdData->user_data.num_headers; index++) {
        // user_data.headers[index].header_id
        data_buf[length++] = (jbyte)clientBdData->user_data.headers[index].header_id;
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->user_data.headers[%d].header_id = %d", index, clientBdData->user_data.headers[index].header_id);
#endif

        // user_data.headers[index].u
        // TODO: add support for all udh id's
        switch(clientBdData->user_data.headers[index].header_id)
        {
            case RIL_CDMA_SMS_UDH_CONCAT_8:
#ifdef DBG_LOG_LEVEL_A
                LOGD("clientBdData->user_data.headers[%d].u.concat_8.msg_ref  = 0x%02x\n", index, clientBdData->user_data.headers[index].u.concat_8.msg_ref);
                LOGD("clientBdData->user_data.headers[%d].u.concat_8.total_sm = 0x%02x\n", index, clientBdData->user_data.headers[index].u.concat_8.total_sm);
                LOGD("clientBdData->user_data.headers[%d].u.concat_8.seq_num  = 0x%02x\n", index, clientBdData->user_data.headers[index].u.concat_8.seq_num);
#endif
                data_buf[length++] = 3;
                data_buf[length++] = clientBdData->user_data.headers[index].u.concat_8.msg_ref;
                data_buf[length++] = clientBdData->user_data.headers[index].u.concat_8.total_sm;
                data_buf[length++] = clientBdData->user_data.headers[index].u.concat_8.seq_num;
            break;
            case RIL_CDMA_SMS_UDH_SPECIAL_SM:
#ifdef DBG_LOG_LEVEL_A
                LOGD("clientBdData->user_data.headers[%d].u.special_sm.msg_waiting      = 0x%04x\n", index, clientBdData->user_data.headers[index].u.special_sm.msg_waiting);
                LOGD("clientBdData->user_data.headers[%d].u.special_sm.msg_waiting_kind = 0x%04x\n", index, clientBdData->user_data.headers[index].u.special_sm.msg_waiting_kind);
                LOGD("clientBdData->user_data.headers[%d].u.special_sm.message_count    = 0x%02x\n", index, clientBdData->user_data.headers[index].u.special_sm.message_count);
#endif
                data_buf[length++] = 9;
                data_buf[length++] = (clientBdData->user_data.headers[index].u.special_sm.msg_waiting & 0xFF000000) >> 23; // int
                data_buf[length++] = (clientBdData->user_data.headers[index].u.special_sm.msg_waiting & 0x00FF0000) >> 15;
                data_buf[length++] = (clientBdData->user_data.headers[index].u.special_sm.msg_waiting & 0x0000FF00) >> 7;
                data_buf[length++] =  clientBdData->user_data.headers[index].u.special_sm.msg_waiting & 0x000000FF;
                data_buf[length++] = (clientBdData->user_data.headers[index].u.special_sm.msg_waiting_kind & 0xFF000000) >> 23; // int
                data_buf[length++] = (clientBdData->user_data.headers[index].u.special_sm.msg_waiting_kind & 0x00FF0000) >> 15;
                data_buf[length++] = (clientBdData->user_data.headers[index].u.special_sm.msg_waiting_kind & 0x0000FF00) >> 7;
                data_buf[length++] =  clientBdData->user_data.headers[index].u.special_sm.msg_waiting_kind & 0x000000FF;
                data_buf[length++] =  clientBdData->user_data.headers[index].u.special_sm.message_count;
            break;
            case RIL_CDMA_SMS_UDH_PORT_8:
#ifdef DBG_LOG_LEVEL_A
                LOGD("clientBdData->user_data.headers[%d].u.wap_8.dest_port = 0x%02x\n", index, clientBdData->user_data.headers[index].u.wap_8.dest_port);
                LOGD("clientBdData->user_data.headers[%d].u.wap_8.orig_port = 0x%02x\n", index, clientBdData->user_data.headers[index].u.wap_8.orig_port);
#endif
                data_buf[length++] = 2;
                data_buf[length++] = clientBdData->user_data.headers[index].u.wap_8.dest_port;
                data_buf[length++] = clientBdData->user_data.headers[index].u.wap_8.orig_port;
            break;
            case RIL_CDMA_SMS_UDH_PORT_16:
#ifdef DBG_LOG_LEVEL_A
                LOGD("clientBdData->user_data.headers[%d].u.wap_16.dest_port = 0x%04x\n", index, clientBdData->user_data.headers[index].u.wap_16.dest_port);
                LOGD("clientBdData->user_data.headers[%d].u.wap_16.orig_port = 0x%04x\n", index, clientBdData->user_data.headers[index].u.wap_16.orig_port);
#endif
                data_buf[length++] = 4;
                data_buf[length++] = (clientBdData->user_data.headers[index].u.wap_16.dest_port & 0xFF00) >> 7; // unsigned short
                data_buf[length++] =  clientBdData->user_data.headers[index].u.wap_16.dest_port & 0x00FF;
                data_buf[length++] = (clientBdData->user_data.headers[index].u.wap_16.orig_port & 0xFF00) >> 7; // unsigned short
                data_buf[length++] =  clientBdData->user_data.headers[index].u.wap_16.orig_port & 0x00FF;
            break;
            case RIL_CDMA_SMS_UDH_CONCAT_16:
#ifdef DBG_LOG_LEVEL_A
                LOGD("clientBdData->user_data.headers[%d].u.concat_16.msg_ref  = 0x%04x\n", index, clientBdData->user_data.headers[index].u.concat_16.msg_ref);
                LOGD("clientBdData->user_data.headers[%d].u.concat_16.total_sm = 0x%04x\n", index, clientBdData->user_data.headers[index].u.concat_16.total_sm);
                LOGD("clientBdData->user_data.headers[%d].u.concat_16.seq_num  = 0x%04x\n", index, clientBdData->user_data.headers[index].u.concat_16.seq_num);
#endif
                data_buf[length++] = 4;
                data_buf[length++] = (clientBdData->user_data.headers[index].u.concat_16.msg_ref & 0xFF00) >> 7; // unsigned short
                data_buf[length++] =  clientBdData->user_data.headers[index].u.concat_16.msg_ref & 0x00FF;
                data_buf[length++] =  clientBdData->user_data.headers[index].u.concat_16.total_sm;
                data_buf[length++] =  clientBdData->user_data.headers[index].u.concat_16.seq_num;
            break;
            default:
            break;
        }
    }

    if (length != 0) {
        arrData = env->NewByteArray((jsize)length);
        env->SetByteArrayRegion(arrData, 0, length, data_buf);
    }

    return arrData;
}


/* native interface */
JNIEXPORT jint JNICALL
Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsSetSmsAddress
  (JNIEnv * env, jobject obj, jobject smsAddress)
{
    jclass SmsAddressClass;
    jfieldID field;
    jbyteArray arrData = NULL;
    jbyte byte_buf[RIL_CDMA_SMS_ADDRESS_MAX];
    int length;
    jint intData;
    jbyte byteData;
    jboolean booleanData;

#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsSetSmsAddress()...\n");
#endif

    // callback.digit_mode
    if (getObjectByteField(env, smsAddress, "digitMode", &byteData) != JNI_SUCCESS)
        return JNI_FAILURE;
    clientBdData->callback.digit_mode = (RIL_CDMA_SMS_DigitMode)(byteData);
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->callback.digit_mode = 0x%02x\n", clientBdData->callback.digit_mode);
#endif

    // callback.number_mode
    if (getObjectByteField(env, smsAddress, "numberMode", &byteData) != JNI_SUCCESS)
        return JNI_FAILURE;
    clientBdData->callback.number_mode = (RIL_CDMA_SMS_NumberMode)(byteData);
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->callback.number_mode = 0x%02x\n", clientBdData->callback.number_mode);
#endif

    // callback.number_type
    if (getObjectIntField(env, smsAddress, "ton", &intData) != JNI_SUCCESS)
        return JNI_FAILURE;
    clientBdData->callback.number_type = (RIL_CDMA_SMS_NumberType)(intData);
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->callback.number_type = %d\n", clientBdData->callback.number_type);
#endif

    // callback.number_plan
    if (getObjectByteField(env, smsAddress, "numberPlan", &byteData) != JNI_SUCCESS)
        return JNI_FAILURE;
    clientBdData->callback.number_plan = (RIL_CDMA_SMS_NumberPlan)(byteData);
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->callback.number_plan = 0x%02x\n", clientBdData->callback.number_plan);
#endif

    // callback.number_of_digits
    if (getObjectByteField(env, smsAddress, "numberOfDigits", &byteData) != JNI_SUCCESS)
        return JNI_FAILURE;
    clientBdData->callback.number_of_digits = byteData;
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->callback.number_of_digits = %d\n",clientBdData->callback.number_of_digits);
#endif

    // callback.digits
    if (getObjectByteArrayField(env, smsAddress, "origBytes", byte_buf, &length) != JNI_SUCCESS)
        return JNI_FAILURE;
    for (int i = 0; i < clientBdData->callback.number_of_digits; i++) {
        clientBdData->callback.digits[i] = byte_buf[i];
#ifdef DBG_LOG_LEVEL_A
        LOGD("clientBdData->callback.digits[%d] = 0x%02x\n", i, clientBdData->callback.digits[i]);
#endif
    }

    return JNI_SUCCESS;
}


/* native interface */
JNIEXPORT jint JNICALL
Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsGetSmsAddress
  (JNIEnv * env, jobject obj, jobject smsAddress)
{
    jclass SmsAddressClass;
    jfieldID field;
    jbyteArray arrData = NULL;
    jbyte *byte_buf;
    int length;

#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsGetSmsAddress()...\n");
#endif

    // callback.digit_mode
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->callback.digit_mode = 0x%02x\n", clientBdData->callback.digit_mode);
#endif
    if (setObjectByteField(env, smsAddress, "digitMode", (jbyte)clientBdData->callback.digit_mode) != JNI_SUCCESS)
        return JNI_FAILURE;

    // callback.number_mode
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->callback.number_mode = 0x%02x\n", clientBdData->callback.number_mode);
#endif
    if (setObjectByteField(env, smsAddress, "numberMode", (jbyte)clientBdData->callback.number_mode) != JNI_SUCCESS)
        return JNI_FAILURE;

    // callback.number_type
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->callback.number_type = %d\n", clientBdData->callback.number_type);
#endif
    if (setObjectIntField(env, smsAddress, "ton", (jint)clientBdData->callback.number_type) != JNI_SUCCESS)
        return JNI_FAILURE;

    // callback.number_plan
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->callback.number_plan = 0x%02x\n", clientBdData->callback.number_plan);
#endif
    if (setObjectByteField(env, smsAddress, "numberPlan", (jbyte)clientBdData->callback.number_plan) != JNI_SUCCESS)
        return JNI_FAILURE;

    // callback.number_of_digits
#ifdef DBG_LOG_LEVEL_A
    LOGD("clientBdData->callback.number_of_digits = %d\n", clientBdData->callback.number_of_digits);
#endif
    if (setObjectByteField(env, smsAddress, "numberOfDigits", (jbyte)clientBdData->callback.number_of_digits) != JNI_SUCCESS)
        return JNI_FAILURE;

    // callback.digits
    byte_buf = (jbyte*)clientBdData->callback.digits;
    length = clientBdData->callback.number_of_digits;
#ifdef DBG_LOG_LEVEL_A
    for (int i = 0; i < length; i++) {
        LOGD("clientBdData->callback.digits[%d] = 0x%02x\n", i, clientBdData->callback.digits[i]);
    }
#endif

    if (setObjectByteArrayField(env, smsAddress, "origBytes", byte_buf, length) != JNI_SUCCESS)
        return JNI_FAILURE;

    return JNI_SUCCESS;
}


/* native interface */
JNIEXPORT jbyteArray JNICALL
Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsEncodeSms
  (JNIEnv * env, jobject obj)
{
    RIL_CDMA_Encoded_SMS *encoded_sms = (RIL_CDMA_Encoded_SMS *)malloc(sizeof(RIL_CDMA_Encoded_SMS));
    jbyte* data_buf;
    jint result = JNI_SUCCESS;
    jbyteArray encodedSMS;

#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsEncodeSms(): entry\n");
#endif

    if (NULL == encoded_sms) {
        jniThrowException(env, "java/lang/NullPointerException", "encoded_sms is null");
        return NULL;
    }
    memset(encoded_sms, 0, sizeof(RIL_CDMA_Encoded_SMS));

    // call CDMA SMS encode function
    if(wmsts_ril_cdma_encode_sms(clientBdData, encoded_sms) != RIL_E_SUCCESS) {
        jniThrowException(env, "java/lang/Exception", "CDMA SMS Encoding failed");
        return NULL;
    }

#ifdef DBG_LOG_LEVEL_A
    LOGD("  EncodeSMS: length = %i\n", encoded_sms->length);
#endif
    encodedSMS = env->NewByteArray((jsize)encoded_sms->length);
    env->SetByteArrayRegion(encodedSMS, 0, encoded_sms->length, (jbyte*)encoded_sms->data);
    free(encoded_sms);

    return encodedSMS;
}


/* native interface */
JNIEXPORT jint JNICALL
Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsDecodeSms
  (JNIEnv * env, jobject obj, jbyteArray encodedSMS)
{
    RIL_CDMA_Encoded_SMS *encoded_sms = (RIL_CDMA_Encoded_SMS *)malloc(sizeof(RIL_CDMA_Encoded_SMS));
    jbyte* data_buf;
    jint result = JNI_SUCCESS;
    jsize length;

#ifdef DBG_LOG_LEVEL_B
    LOGD("nativeCdmaSmsDecodeSms(): entry\n");
#endif

    if (NULL == encoded_sms) {
        jniThrowException(env, "java/lang/NullPointerException", "encoded_sms is null");
        return JNI_FAILURE;
    }
    memset(encoded_sms, 0, sizeof(RIL_CDMA_Encoded_SMS));

    length = env->GetArrayLength(encodedSMS);
    if (length < 0 || length > 255) {
        jniThrowException(env, "java/lang/ArrayIndexOutOfBounds", "wrong encoded SMS data length");
        return JNI_FAILURE;
    }
    encoded_sms->length = length;
#ifdef DBG_LOG_LEVEL_A
    LOGD("  DecodeSMS: arrayLength = %d\n", encoded_sms->length);
#endif
    data_buf = env->GetByteArrayElements(encodedSMS, NULL);
    encoded_sms->data = (unsigned char*)data_buf;
    env->ReleaseByteArrayElements(encodedSMS, data_buf, 0);

    // call CDMA SMS decode function
    if(wmsts_ril_cdma_decode_sms(encoded_sms, clientBdData) != RIL_E_SUCCESS) {
        jniThrowException(env, "java/lang/Exception", "CDMA SMS Decoding failed");
        result = JNI_FAILURE;
    }

    free(encoded_sms);

    return result;
}


// ---------------------------------------------------------------------------

static const char *classPathName = "com/android/internal/telephony/cdma/sms/SmsDataCoding";

static JNINativeMethod methods[] = {
    /* name, signature, funcPtr */
    {"nativeCdmaSmsConstructClientBD", "()I",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsConstructClientBD },
    {"nativeCdmaSmsDestructClientBD", "()I",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsDestructClientBD },
    {"nativeCdmaSmsSetBearerDataPrimitives", "(Lcom/android/internal/telephony/cdma/sms/BearerData;)I",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsSetBearerDataPrimitives },
    {"nativeCdmaSmsGetBearerDataPrimitives", "(Lcom/android/internal/telephony/cdma/sms/BearerData;)I",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsGetBearerDataPrimitives },
    {"nativeCdmaSmsSetUserData", "(Lcom/android/internal/telephony/cdma/sms/UserData;)I",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsSetUserData },
    {"nativeCdmaSmsGetUserData", "(Lcom/android/internal/telephony/cdma/sms/UserData;)I",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsGetUserData },
    {"nativeCdmaSmsSetUserDataHeader", "(I[BII)I",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsSetUserDataHeader },
    {"nativeCdmaSmsGetUserDataHeader", "()[B",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsGetUserDataHeader },
    {"nativeCdmaSmsSetSmsAddress", "(Lcom/android/internal/telephony/cdma/sms/CdmaSmsAddress;)I",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsSetSmsAddress },
    {"nativeCdmaSmsGetSmsAddress", "(Lcom/android/internal/telephony/cdma/sms/CdmaSmsAddress;)I",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsGetSmsAddress },
    {"nativeCdmaSmsEncodeSms", "()[B",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsEncodeSms },
    {"nativeCdmaSmsDecodeSms", "([B)I",
      (void*)Java_com_android_internal_telephony_cdma_sms_SmsDataCoding_nativeCdmaSmsDecodeSms },
};

int register_android_cdma_sms_methods(JNIEnv *_env)
{
    return android::AndroidRuntime::registerNativeMethods(
            _env, classPathName, methods, NELEM(methods));
}

// ---------------------------------------------------------------------------

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        LOGE("ERROR: GetEnv failed\n");
        goto bail;
    }
    assert(env != NULL);

    if (register_android_cdma_sms_methods(env) < 0) {
        LOGE("ERROR: CDMA SMS native registration failed\n");
        goto bail;
    }

    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

bail:
    return result;
}
