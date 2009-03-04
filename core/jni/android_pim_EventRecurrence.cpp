/* //device/libs/android_runtime/android_pim_EventRecurrence.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#include <pim/EventRecurrence.h>
#include "jni.h"
#include "nativehelper/JNIHelp.h"
#include <utils/String8.h>

namespace android {

struct cached_array_fields_t
{
    jfieldID array;
    jfieldID count;
};

static jclass clazz;
static jfieldID freq_field;
static jfieldID until_field;
static jfieldID count_field;
static jfieldID interval_field;
static jfieldID wkst_field;
static cached_array_fields_t bysecond_fields;
static cached_array_fields_t byminute_fields;
static cached_array_fields_t byhour_fields;
static cached_array_fields_t byday_fields;
static cached_array_fields_t bydayNum_fields;
static cached_array_fields_t bymonthday_fields;
static cached_array_fields_t byyearday_fields;
static cached_array_fields_t byweekno_fields;
static cached_array_fields_t bymonth_fields;
static cached_array_fields_t bysetpos_fields;

static status_t
set_array(JNIEnv* env, int inCount, int* inArray,
            jobject This, const cached_array_fields_t& fields)
{
    if (inCount > 0) {
        jintArray array = (jintArray) env->GetObjectField(This, fields.array);
        if (array == NULL || env->GetArrayLength(array) < inCount) {
            // +4 because it's cheap to allocate a little extra here, and
            // that reduces the chance that we'll come back here again
            array = env->NewIntArray(inCount+4);
            env->SetObjectField(This, fields.array, array);
        }
        if (array == NULL) {
            return NO_MEMORY;
        }
        env->SetIntArrayRegion(array, 0, inCount, inArray);

    }
    env->SetIntField(This, fields.count, inCount);
    return NO_ERROR;
}

/*
 * In class android.pim.EventRecurrence
 *  public native int parse(String str);
 */
#define SET_ARRAY_AND_CHECK(name) \
    /*printf("setting " #name " to %d elements\n", er.name##Count);*/ \
    if (set_array(env, er.name##Count, er.name, This, name##_fields) \
            != NO_ERROR) { \
        jniThrowException(env, "java/lang/RuntimeException", \
                "EventRecurrence.parse error setting field " #name " or " \
                #name "Count."); \
        return ; \
    }
static void
EventRecurrence_parse(JNIEnv* env, jobject This, jstring jstr)
{
    if (jstr == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", 
                "EventRecurrence.parse str parameter null"); 
        return ;
    }
    jboolean isCopy;
    const jchar* jchars = env->GetStringChars(jstr, &isCopy);
    jsize len = env->GetStringLength(jstr);
    String16 str(jchars, len);
    env->ReleaseStringChars(jstr, jchars);

    //printf("the string was '%s'\n", String8(str).string());

    EventRecurrence er;
    if (NO_ERROR != er.parse(str)) {
        String8 msg("Error parsing recurrence: '");
        msg.append(String8(str));
        msg.append("'");

        jniThrowException(env,
                "android/pim/EventRecurrence$InvalidFormatException",
                msg.string());
        return ;
    }

    jstring untilStr;
    if (er.until.size() > 0) {
        untilStr = env->NewString(er.until.string(), er.until.size());
        if (untilStr == NULL) {
            jniThrowException(env, "java/lang/RuntimeException", 
                    "EventRecurrence.parse error setting field 'until'"); 
            return ;
        }
    } else {
        untilStr = NULL;
    }
    env->SetObjectField(This, until_field, untilStr);

    env->SetIntField(This, freq_field, er.freq);
    env->SetIntField(This, count_field, er.count);
    env->SetIntField(This, interval_field, er.interval);
    env->SetIntField(This, wkst_field, er.wkst);

    SET_ARRAY_AND_CHECK(bysecond)
    SET_ARRAY_AND_CHECK(byminute)
    SET_ARRAY_AND_CHECK(byhour)
    SET_ARRAY_AND_CHECK(byday)
    // we'll just set the bydayCount field twice, it'll be less code total
    if (set_array(env, er.bydayCount, er.bydayNum, This, bydayNum_fields)
            != NO_ERROR) { 
        jniThrowException(env, "java/lang/RuntimeException",
                "EventRecurrence.parse error setting field bydayNum or "
                "bydayCount.");
        return ;
    }
    SET_ARRAY_AND_CHECK(bymonthday)
    SET_ARRAY_AND_CHECK(byyearday)
    SET_ARRAY_AND_CHECK(byweekno)
    SET_ARRAY_AND_CHECK(bymonth)
    SET_ARRAY_AND_CHECK(bysetpos)
}

/*
 * JNI registration.
 */
static JNINativeMethod METHODS[] = {
    /* name, signature, funcPtr */
    { "parse", "(Ljava/lang/String;)V", (void*)EventRecurrence_parse }
};

static const char*const CLASS_NAME = "android/pim/EventRecurrence";

int register_android_pim_EventRecurrence(JNIEnv* env)
{
    clazz = env->FindClass(CLASS_NAME);
    if (clazz == NULL) {
        LOGE("Field lookup unable to find class '%s'\n", CLASS_NAME);
        return -1;
    }

    freq_field = env->GetFieldID(clazz, "freq", "I");
    count_field = env->GetFieldID(clazz, "count", "I");
    interval_field = env->GetFieldID(clazz, "interval", "I");
    wkst_field = env->GetFieldID(clazz, "wkst", "I");

    until_field = env->GetFieldID(clazz, "until", "Ljava/lang/String;");

    bysecond_fields.array = env->GetFieldID(clazz, "bysecond", "[I");
    bysecond_fields.count = env->GetFieldID(clazz, "bysecondCount", "I");
    byminute_fields.array = env->GetFieldID(clazz, "byminute", "[I");
    byminute_fields.count = env->GetFieldID(clazz, "byminuteCount", "I");
    byhour_fields.array = env->GetFieldID(clazz, "byhour", "[I");
    byhour_fields.count = env->GetFieldID(clazz, "byhourCount", "I");
    byday_fields.array = env->GetFieldID(clazz, "byday", "[I");
    byday_fields.count = env->GetFieldID(clazz, "bydayCount", "I");
    bydayNum_fields.array = env->GetFieldID(clazz, "bydayNum", "[I");
    bydayNum_fields.count = byday_fields.count;
    bymonthday_fields.array = env->GetFieldID(clazz, "bymonthday", "[I");
    bymonthday_fields.count = env->GetFieldID(clazz, "bymonthdayCount", "I");
    byyearday_fields.array = env->GetFieldID(clazz, "byyearday", "[I");
    byyearday_fields.count = env->GetFieldID(clazz, "byyeardayCount", "I");
    byweekno_fields.array = env->GetFieldID(clazz, "byweekno", "[I");
    byweekno_fields.count = env->GetFieldID(clazz, "byweeknoCount", "I");
    bymonth_fields.array = env->GetFieldID(clazz, "bymonth", "[I");
    bymonth_fields.count = env->GetFieldID(clazz, "bymonthCount", "I");
    bysetpos_fields.array = env->GetFieldID(clazz, "bysetpos", "[I");
    bysetpos_fields.count = env->GetFieldID(clazz, "bysetposCount", "I");

    return jniRegisterNativeMethods(env, CLASS_NAME,
        METHODS, sizeof(METHODS)/sizeof(METHODS[0]));
}

}; // namespace android

