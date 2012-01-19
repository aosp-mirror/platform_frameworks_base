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

#define LOG_TAG "NdefRecord"

#include <stdlib.h>

#include "jni.h"
#include "JNIHelp.h"

#include "android_nfc.h"

#include <utils/Log.h>

namespace android {

static jbyteArray android_nfc_NdefRecord_generate(
        JNIEnv *e, jobject o, jshort flags, jshort tnf, jbyteArray type,
        jbyteArray id, jbyteArray payload)
{
    uint32_t status;
    phFriNfc_NdefRecord_t record;
    uint32_t buf_size;
    uint32_t record_size;
    uint8_t *buf = NULL;
    jbyteArray result = NULL;

    /* Prepare NDEF record structure */
    record.Flags = (uint8_t)flags;
    record.Tnf = (uint8_t)tnf;
    record.TypeLength = (uint32_t)e->GetArrayLength(type);
    record.Type = (uint8_t *)e->GetByteArrayElements(type, NULL);
    record.IdLength = (uint32_t)e->GetArrayLength(id);
    record.Id = (uint8_t *)e->GetByteArrayElements(id, NULL);
    record.PayloadLength = (uint32_t)e->GetArrayLength(payload);
    record.PayloadData = (uint8_t *)e->GetByteArrayElements(payload, NULL);

    buf_size = record.PayloadLength + record.IdLength + record.TypeLength + 8;

    buf = (uint8_t*)malloc(buf_size);
    if (buf == NULL)
        goto end;

    TRACE("phFriNfc_NdefRecord_Generate()");

    status = phFriNfc_NdefRecord_Generate(&record, buf, buf_size,
            &record_size);

    if (status) {
        ALOGE("phFriNfc_NdefRecord_Generate() returned 0x%04x", status);
        goto end;
    }
    TRACE("phFriNfc_NdefRecord_Generate() returned 0x%04x", status);

    result = e->NewByteArray(record_size);
    if (result == NULL)
        goto end;

    e->SetByteArrayRegion(result, 0, record_size, (jbyte *)buf);

end:
    e->ReleaseByteArrayElements(type, (jbyte *)record.Type, JNI_ABORT);
    e->ReleaseByteArrayElements(id, (jbyte *)record.Id, JNI_ABORT);
    e->ReleaseByteArrayElements(payload, (jbyte *)record.PayloadData, JNI_ABORT);

    if(buf)
        free(buf);

    return result;
}

static jint android_nfc_NdefRecord_parseNdefRecord(JNIEnv *e, jobject o,
        jbyteArray array)
{
    uint16_t status;
    jbyte *raw_record;
    jsize raw_record_size;
    jint ret = -1;
    phFriNfc_NdefRecord_t record;

    jfieldID mType, mId, mPayload, mTnf, mFlags;
    jbyteArray type = NULL;
    jbyteArray id = NULL;
    jbyteArray payload = NULL;

    jclass record_cls = e->GetObjectClass(o);

    raw_record_size = e->GetArrayLength(array);
    raw_record = e->GetByteArrayElements(array, NULL);
    if (raw_record == NULL) {
        goto clean_and_return;
    }

    TRACE("phFriNfc_NdefRecord_Parse()");
    status = phFriNfc_NdefRecord_Parse(&record, (uint8_t *)raw_record);
    if (status) {
        ALOGE("phFriNfc_NdefRecord_Parse() returned 0x%04x", status);
        goto clean_and_return;
    }
    TRACE("phFriNfc_NdefRecord_Parse() returned 0x%04x", status);

    /* Set TNF field */
    mTnf = e->GetFieldID(record_cls, "mTnf", "S");
    e->SetShortField(o, mTnf, record.Tnf);

    /* Set type field */
    mType = e->GetFieldID(record_cls, "mType", "[B");
    type = e->NewByteArray(record.TypeLength);
    if (type == NULL) {
        goto clean_and_return;
    }
    e->SetByteArrayRegion(type, 0, record.TypeLength,
            (jbyte *)record.Type);
    e->SetObjectField(o, mType, type);

    /* Set id field */
    mId = e->GetFieldID(record_cls, "mId", "[B");
    id = e->NewByteArray(record.IdLength);
    if (id == NULL) {
        goto clean_and_return;
    }
    e->SetByteArrayRegion(id, 0, record.IdLength,
            (jbyte *)record.Id);
    e->SetObjectField(o, mId, id);

    /* Set payload field */
    mPayload = e->GetFieldID(record_cls, "mPayload", "[B");
    payload = e->NewByteArray(record.PayloadLength);
    if (payload == NULL) {
        goto clean_and_return;
    }

    e->SetByteArrayRegion(payload, 0, record.PayloadLength,
            (jbyte *)record.PayloadData);
    e->SetObjectField(o, mPayload, payload);

    /* Set flags field */
    mFlags = e->GetFieldID(record_cls, "mFlags", "B");
    e->SetByteField(o, mFlags, record.Flags);

    ret = 0;

clean_and_return:
    if (type != NULL) {
        e->DeleteLocalRef(type);
    }
    if (id != NULL) {
        e->DeleteLocalRef(id);
    }
    if (payload != NULL) {
        e->DeleteLocalRef(payload);
    }
    if (raw_record != NULL) {
        e->ReleaseByteArrayElements(array, raw_record, JNI_ABORT);
    }

    return ret;
}

static JNINativeMethod gMethods[] = {
    {"generate", "(SS[B[B[B)[B", (void *)android_nfc_NdefRecord_generate},
    {"parseNdefRecord", "([B)I", (void *)android_nfc_NdefRecord_parseNdefRecord},
};

int register_android_nfc_NdefRecord(JNIEnv *e)
{
    return jniRegisterNativeMethods(e, "android/nfc/NdefRecord", gMethods, NELEM(gMethods));
}

} // namespace android
