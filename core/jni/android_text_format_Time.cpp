/*
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

#define LOG_TAG "Log_println"

#include <utils/Log.h>
#include <utils/String8.h>
#include <assert.h>

#include "jni.h"
#include "utils/misc.h"
#include "android_runtime/AndroidRuntime.h"
#include "TimeUtils.h"
#include <nativehelper/JNIHelp.h>
#include <cutils/tztime.h>

namespace android {

static jfieldID g_allDayField = 0;
static jfieldID g_secField = 0;
static jfieldID g_minField = 0;
static jfieldID g_hourField = 0;
static jfieldID g_mdayField = 0;
static jfieldID g_monField = 0;
static jfieldID g_yearField = 0;
static jfieldID g_wdayField = 0;
static jfieldID g_ydayField = 0;
static jfieldID g_isdstField = 0;
static jfieldID g_gmtoffField = 0;
static jfieldID g_timezoneField = 0;

static jfieldID g_shortMonthsField = 0;
static jfieldID g_longMonthsField = 0;
static jfieldID g_longStandaloneMonthsField = 0;
static jfieldID g_shortWeekdaysField = 0;
static jfieldID g_longWeekdaysField = 0;
static jfieldID g_timeOnlyFormatField = 0;
static jfieldID g_dateOnlyFormatField = 0;
static jfieldID g_dateTimeFormatField = 0;
static jfieldID g_amField = 0;
static jfieldID g_pmField = 0;
static jfieldID g_dateCommandField = 0;
static jfieldID g_localeField = 0;

static jclass g_timeClass = NULL;

static inline bool java2time(JNIEnv* env, Time* t, jobject o)
{
    t->t.tm_sec = env->GetIntField(o, g_secField);
    t->t.tm_min = env->GetIntField(o, g_minField);
    t->t.tm_hour = env->GetIntField(o, g_hourField);
    t->t.tm_mday = env->GetIntField(o, g_mdayField);
    t->t.tm_mon = env->GetIntField(o, g_monField);
    t->t.tm_year = (env->GetIntField(o, g_yearField))-1900;
    t->t.tm_wday = env->GetIntField(o, g_wdayField);
    t->t.tm_yday = env->GetIntField(o, g_ydayField);
    t->t.tm_isdst = env->GetIntField(o, g_isdstField);
    t->t.tm_gmtoff = env->GetLongField(o, g_gmtoffField);
    bool allDay = env->GetIntField(o, g_allDayField);
    if (allDay &&
	((t->t.tm_sec !=0) || (t->t.tm_min != 0) || (t->t.tm_hour != 0))) {
        char msg[100];
	sprintf(msg, "allDay is true but sec, min, hour are not 0.");
	jniThrowException(env, "java/lang/IllegalArgumentException", msg);
	return false;
    }
    return true;
}

static inline void time2java(JNIEnv* env, jobject o, const Time &t)
{
    env->SetIntField(o, g_secField, t.t.tm_sec);
    env->SetIntField(o, g_minField, t.t.tm_min);
    env->SetIntField(o, g_hourField, t.t.tm_hour);
    env->SetIntField(o, g_mdayField, t.t.tm_mday);
    env->SetIntField(o, g_monField, t.t.tm_mon);
    env->SetIntField(o, g_yearField, t.t.tm_year+1900);
    env->SetIntField(o, g_wdayField, t.t.tm_wday);
    env->SetIntField(o, g_ydayField, t.t.tm_yday);
    env->SetIntField(o, g_isdstField, t.t.tm_isdst);
    env->SetLongField(o, g_gmtoffField, t.t.tm_gmtoff);
}

#define ACQUIRE_TIMEZONE(This, t) \
    jstring timezoneString_##This \
            = (jstring) env->GetObjectField(This, g_timezoneField); \
    t.timezone = env->GetStringUTFChars(timezoneString_##This, NULL);

#define RELEASE_TIMEZONE(This, t) \
    env->ReleaseStringUTFChars(timezoneString_##This, t.timezone);


// ============================================================================

static jlong android_text_format_Time_normalize(JNIEnv* env, jobject This,
                                           jboolean ignoreDst)
{
    Time t;
    if (!java2time(env, &t, This)) return 0L;
    ACQUIRE_TIMEZONE(This, t)

    int64_t result = t.toMillis(ignoreDst != 0);

    time2java(env, This, t);
    RELEASE_TIMEZONE(This, t)

    return result;
}

static void android_text_format_Time_switchTimezone(JNIEnv* env, jobject This,
                            jstring timezoneObject)
{
    Time t;
    if (!java2time(env, &t, This)) return;
    ACQUIRE_TIMEZONE(This, t)

    const char* timezone = env->GetStringUTFChars(timezoneObject, NULL);

    t.switchTimezone(timezone);

    time2java(env, This, t);
    env->ReleaseStringUTFChars(timezoneObject, timezone);
    RELEASE_TIMEZONE(This, t)

    // we do this here because there's no point in reallocating the string
    env->SetObjectField(This, g_timezoneField, timezoneObject);
}

static jint android_text_format_Time_compare(JNIEnv* env, jobject clazz,
                            jobject aObject, jobject bObject)
{
    Time a, b;

    if (!java2time(env, &a, aObject)) return 0;
    ACQUIRE_TIMEZONE(aObject, a)

    if (!java2time(env, &b, bObject)) return 0;
    ACQUIRE_TIMEZONE(bObject, b)

    int result = Time::compare(a, b);

    RELEASE_TIMEZONE(aObject, a)
    RELEASE_TIMEZONE(bObject, b)

    return result;
}

static jstring android_text_format_Time_format2445(JNIEnv* env, jobject This)
{
    Time t;
    if (!java2time(env, &t, This)) return env->NewStringUTF("");
    bool allDay = env->GetIntField(This, g_allDayField);
    
    if (!allDay) {
        ACQUIRE_TIMEZONE(This, t)
        bool inUtc = strcmp("UTC", t.timezone) == 0;
        short buf[16];
        t.format2445(buf, true);
        RELEASE_TIMEZONE(This, t)
        if (inUtc) {
            // The letter 'Z' is appended to the end so allow for one
            // more character in the buffer.
            return env->NewString((jchar*)buf, 16);
        } else {
            return env->NewString((jchar*)buf, 15);
        }
    } else {
        short buf[8];
        t.format2445(buf, false);
        return env->NewString((jchar*)buf, 8);
    }
}

static jstring android_text_format_Time_format(JNIEnv* env, jobject This,
                            jstring formatObject)
{
    // We only teardown and setup our 'locale' struct and other state
    // when the Java-side locale changed.  This is safe to do here
    // without locking because we're always called from Java code
    // synchronized on the class instance.
    static jobject js_locale_previous = NULL;
    static struct strftime_locale locale;
    static jstring js_mon[12], js_month[12], js_wday[7], js_weekday[7];
    static jstring js_standalone_month[12];
    static jstring js_X_fmt, js_x_fmt, js_c_fmt, js_am, js_pm, js_date_fmt;

    Time t;
    if (!java2time(env, &t, This)) return env->NewStringUTF("");

    jclass timeClass = g_timeClass;
    jobject js_locale = (jobject) env->GetStaticObjectField(timeClass, g_localeField);
    if (js_locale_previous != js_locale) {
        if (js_locale_previous != NULL) {
            // Free the old one.
            for (int i = 0; i < 12; i++) {
                env->ReleaseStringUTFChars(js_mon[i], locale.mon[i]);
                env->ReleaseStringUTFChars(js_month[i], locale.month[i]);
                env->ReleaseStringUTFChars(js_standalone_month[i], locale.standalone_month[i]);
                env->DeleteGlobalRef(js_mon[i]);
                env->DeleteGlobalRef(js_month[i]);
                env->DeleteGlobalRef(js_standalone_month[i]);
            }

            for (int i = 0; i < 7; i++) {
                env->ReleaseStringUTFChars(js_wday[i], locale.wday[i]);
                env->ReleaseStringUTFChars(js_weekday[i], locale.weekday[i]);
                env->DeleteGlobalRef(js_wday[i]);
                env->DeleteGlobalRef(js_weekday[i]);
            }

            env->ReleaseStringUTFChars(js_X_fmt, locale.X_fmt);
            env->ReleaseStringUTFChars(js_x_fmt, locale.x_fmt);
            env->ReleaseStringUTFChars(js_c_fmt, locale.c_fmt);
            env->ReleaseStringUTFChars(js_am, locale.am);
            env->ReleaseStringUTFChars(js_pm, locale.pm);
            env->ReleaseStringUTFChars(js_date_fmt, locale.date_fmt);
            env->DeleteGlobalRef(js_X_fmt);
            env->DeleteGlobalRef(js_x_fmt);
            env->DeleteGlobalRef(js_c_fmt);
            env->DeleteGlobalRef(js_am);
            env->DeleteGlobalRef(js_pm);
            env->DeleteGlobalRef(js_date_fmt);
        }
        js_locale_previous = js_locale;

        jobjectArray ja;
        ja = (jobjectArray) env->GetStaticObjectField(timeClass, g_shortMonthsField);
        for (int i = 0; i < 12; i++) {
            js_mon[i] = (jstring) env->NewGlobalRef(env->GetObjectArrayElement(ja, i));
            locale.mon[i] = env->GetStringUTFChars(js_mon[i], NULL);
        }

        ja = (jobjectArray) env->GetStaticObjectField(timeClass, g_longMonthsField);
        for (int i = 0; i < 12; i++) {
            js_month[i] = (jstring) env->NewGlobalRef(env->GetObjectArrayElement(ja, i));
            locale.month[i] = env->GetStringUTFChars(js_month[i], NULL);
        }

        ja = (jobjectArray) env->GetStaticObjectField(timeClass, g_longStandaloneMonthsField);
        for (int i = 0; i < 12; i++) {
            js_standalone_month[i] = (jstring) env->NewGlobalRef(env->GetObjectArrayElement(ja, i));
            locale.standalone_month[i] = env->GetStringUTFChars(js_standalone_month[i], NULL);
        }

        ja = (jobjectArray) env->GetStaticObjectField(timeClass, g_shortWeekdaysField);
        for (int i = 0; i < 7; i++) {
            js_wday[i] = (jstring) env->NewGlobalRef(env->GetObjectArrayElement(ja, i));
            locale.wday[i] = env->GetStringUTFChars(js_wday[i], NULL);
        }

        ja = (jobjectArray) env->GetStaticObjectField(timeClass, g_longWeekdaysField);
        for (int i = 0; i < 7; i++) {
            js_weekday[i] = (jstring) env->NewGlobalRef(env->GetObjectArrayElement(ja, i));
            locale.weekday[i] = env->GetStringUTFChars(js_weekday[i], NULL);
        }

        js_X_fmt = (jstring) env->NewGlobalRef(env->GetStaticObjectField(
                                                       timeClass, g_timeOnlyFormatField));
        locale.X_fmt = env->GetStringUTFChars(js_X_fmt, NULL);

        js_x_fmt = (jstring) env->NewGlobalRef(env->GetStaticObjectField(
                                                       timeClass, g_dateOnlyFormatField));
        locale.x_fmt = env->GetStringUTFChars(js_x_fmt, NULL);

        js_c_fmt = (jstring) env->NewGlobalRef(env->GetStaticObjectField(
                                                       timeClass, g_dateTimeFormatField));
        locale.c_fmt = env->GetStringUTFChars(js_c_fmt, NULL);

        js_am = (jstring) env->NewGlobalRef(env->GetStaticObjectField(
                                                    timeClass, g_amField));
        locale.am = env->GetStringUTFChars(js_am, NULL);

        js_pm = (jstring) env->NewGlobalRef(env->GetStaticObjectField(
                                                    timeClass, g_pmField));
        locale.pm = env->GetStringUTFChars(js_pm, NULL);

        js_date_fmt = (jstring) env->NewGlobalRef(env->GetStaticObjectField(
                                                          timeClass, g_dateCommandField));
        locale.date_fmt = env->GetStringUTFChars(js_date_fmt, NULL);
    }

    ACQUIRE_TIMEZONE(This, t)

    const char* format = env->GetStringUTFChars(formatObject, NULL);

    String8 r = t.format(format, &locale);

    env->ReleaseStringUTFChars(formatObject, format);
    RELEASE_TIMEZONE(This, t)

    return env->NewStringUTF(r.string());
}


static jstring android_text_format_Time_toString(JNIEnv* env, jobject This)
{
    Time t;
    if (!java2time(env, &t, This)) return env->NewStringUTF("");;
    ACQUIRE_TIMEZONE(This, t)

    String8 r = t.toString();

    RELEASE_TIMEZONE(This, t)

    return env->NewStringUTF(r.string());
}

static void android_text_format_Time_setToNow(JNIEnv* env, jobject This)
{
    env->SetBooleanField(This, g_allDayField, JNI_FALSE);
    Time t;
    ACQUIRE_TIMEZONE(This, t)

    t.setToNow();

    time2java(env, This, t);
    RELEASE_TIMEZONE(This, t)
}

static jlong android_text_format_Time_toMillis(JNIEnv* env, jobject This,
                                        jboolean ignoreDst)
{
    Time t;
    if (!java2time(env, &t, This)) return 0L;
    ACQUIRE_TIMEZONE(This, t)

    int64_t result = t.toMillis(ignoreDst != 0);

    RELEASE_TIMEZONE(This, t)

    return result;
}

static void android_text_format_Time_set(JNIEnv* env, jobject This, jlong millis)
{
    env->SetBooleanField(This, g_allDayField, JNI_FALSE);
    Time t;
    ACQUIRE_TIMEZONE(This, t)

    t.set(millis);

    time2java(env, This, t);
    RELEASE_TIMEZONE(This, t)
}


// ============================================================================
// Just do this here because it's not worth recreating the strings

static int get_char(JNIEnv* env, const jchar *s, int spos, int mul,
                    bool *thrown)
{
    jchar c = s[spos];
    if (c >= '0' && c <= '9') {
        return (c - '0') * mul;
    } else {
        if (!*thrown) {
            char msg[100];
            sprintf(msg, "Parse error at pos=%d", spos);
            jniThrowException(env, "android/util/TimeFormatException", msg);
            *thrown = true;
        }
        return 0;
    }
}

static bool check_char(JNIEnv* env, const jchar *s, int spos, jchar expected)
{
    jchar c = s[spos];
    if (c != expected) {
        char msg[100];
	sprintf(msg, "Unexpected character 0x%02x at pos=%d.  Expected %c.", c, spos,
		expected);
	jniThrowException(env, "android/util/TimeFormatException", msg);
	return false;
    }
    return true;
}


static jboolean android_text_format_Time_parse(JNIEnv* env, jobject This, jstring strObj)
{
    jsize len = env->GetStringLength(strObj);
    const jchar *s = env->GetStringChars(strObj, NULL);

    bool thrown = false;
    int n;
    jboolean inUtc = false;

    if (len < 8) {
        char msg[100];
        sprintf(msg, "String too short -- expected at least 8 characters.");
	jniThrowException(env, "android/util/TimeFormatException", msg);
	return false;
    }

    // year
    n = get_char(env, s, 0, 1000, &thrown);
    n += get_char(env, s, 1, 100, &thrown);
    n += get_char(env, s, 2, 10, &thrown);
    n += get_char(env, s, 3, 1, &thrown);
    if (thrown) return false;
    env->SetIntField(This, g_yearField, n);

    // month
    n = get_char(env, s, 4, 10, &thrown);
    n += get_char(env, s, 5, 1, &thrown);
    n--;
    if (thrown) return false;
    env->SetIntField(This, g_monField, n);

    // day of month
    n = get_char(env, s, 6, 10, &thrown);
    n += get_char(env, s, 7, 1, &thrown);
    if (thrown) return false;
    env->SetIntField(This, g_mdayField, n);

    if (len > 8) {
        // T
        if (!check_char(env, s, 8, 'T')) return false;
        env->SetBooleanField(This, g_allDayField, JNI_FALSE);

        // hour
        n = get_char(env, s, 9, 10, &thrown);
        n += get_char(env, s, 10, 1, &thrown);
        if (thrown) return false;
        env->SetIntField(This, g_hourField, n);

        // min
        n = get_char(env, s, 11, 10, &thrown);
        n += get_char(env, s, 12, 1, &thrown);
        if (thrown) return false;
        env->SetIntField(This, g_minField, n);

        // sec
        n = get_char(env, s, 13, 10, &thrown);
        n += get_char(env, s, 14, 1, &thrown);
        if (thrown) return false;
        env->SetIntField(This, g_secField, n);

        if (len > 15) {
            // Z
            if (!check_char(env, s, 15, 'Z')) return false;
	    inUtc = true;
        }
    } else {
        env->SetBooleanField(This, g_allDayField, JNI_TRUE);
        env->SetIntField(This, g_hourField, 0);
        env->SetIntField(This, g_minField, 0);
        env->SetIntField(This, g_secField, 0);
    }

    env->SetIntField(This, g_wdayField, 0);
    env->SetIntField(This, g_ydayField, 0);
    env->SetIntField(This, g_isdstField, -1);
    env->SetLongField(This, g_gmtoffField, 0);
    
    env->ReleaseStringChars(strObj, s);
    return inUtc;
}

static jboolean android_text_format_Time_parse3339(JNIEnv* env, 
                                           jobject This, 
                                           jstring strObj)
{
    jsize len = env->GetStringLength(strObj);
    const jchar *s = env->GetStringChars(strObj, NULL);

    bool thrown = false;
    int n;
    jboolean inUtc = false;

    if (len < 10) {
        jniThrowException(env, "android/util/TimeFormatException",
                "Time input is too short; must be at least 10 characters");
        return false;
    }

    // year
    n = get_char(env, s, 0, 1000, &thrown);    
    n += get_char(env, s, 1, 100, &thrown);
    n += get_char(env, s, 2, 10, &thrown);
    n += get_char(env, s, 3, 1, &thrown);
    if (thrown) return false;
    env->SetIntField(This, g_yearField, n);
    
    // -
    if (!check_char(env, s, 4, '-')) return false;
    
    // month
    n = get_char(env, s, 5, 10, &thrown);
    n += get_char(env, s, 6, 1, &thrown);
    --n;
    if (thrown) return false;
    env->SetIntField(This, g_monField, n);

    // -
    if (!check_char(env, s, 7, '-')) return false;

    // day
    n = get_char(env, s, 8, 10, &thrown);
    n += get_char(env, s, 9, 1, &thrown);
    if (thrown) return false;
    env->SetIntField(This, g_mdayField, n);

    if (len >= 19) {
        // T
        if (!check_char(env, s, 10, 'T')) return false;

	env->SetBooleanField(This, g_allDayField, JNI_FALSE);
        // hour
        n = get_char(env, s, 11, 10, &thrown);
        n += get_char(env, s, 12, 1, &thrown);
        if (thrown) return false;
	int hour = n;
        // env->SetIntField(This, g_hourField, n);
	
	// :
	if (!check_char(env, s, 13, ':')) return false;

	// minute
        n = get_char(env, s, 14, 10, &thrown);
        n += get_char(env, s, 15, 1, &thrown);
        if (thrown) return false;
	int minute = n;
        // env->SetIntField(This, g_minField, n);

	// :
	if (!check_char(env, s, 16, ':')) return false;

	// second
        n = get_char(env, s, 17, 10, &thrown);
        n += get_char(env, s, 18, 1, &thrown);
        if (thrown) return false;
        env->SetIntField(This, g_secField, n);

        // skip the '.XYZ' -- we don't care about subsecond precision.
        int tz_index = 19;
        if (tz_index < len && s[tz_index] == '.') {
            do {
                tz_index++;
            } while (tz_index < len
                && s[tz_index] >= '0'
                && s[tz_index] <= '9');
        }

        int offset = 0;
        if (len > tz_index) {
            char c = s[tz_index];

	    // NOTE: the offset is meant to be subtracted to get from local time
	    // to UTC.  we therefore use 1 for '-' and -1 for '+'.
	    switch (c) {
	    case 'Z':
	        // Zulu time -- UTC
	        offset = 0;
		break;
	    case '-': 
                offset = 1;
	        break;
	    case '+': 
                offset = -1;
	        break;
	    default:
	        char msg[100];
	        sprintf(msg, "Unexpected character 0x%02x at position %d.  Expected + or -",
			c, tz_index);
	        jniThrowException(env, "android/util/TimeFormatException", msg);
	        return false;
	    }
            inUtc = true;

	    if (offset != 0) {
	        if (len < tz_index + 6) {
	            char msg[100];
	            sprintf(msg, "Unexpected length; should be %d characters", tz_index + 6);
	            jniThrowException(env, "android/util/TimeFormatException", msg);
	            return false;
	        }

	        // hour
	        n = get_char(env, s, tz_index + 1, 10, &thrown);
		n += get_char(env, s, tz_index + 2, 1, &thrown);
		if (thrown) return false;
		n *= offset;
		hour += n;

		// :
		if (!check_char(env, s, tz_index + 3, ':')) return false;
	    
		// minute
		n = get_char(env, s, tz_index + 4, 10, &thrown);
		n += get_char(env, s, tz_index + 5, 1, &thrown);
		if (thrown) return false;
		n *= offset;
		minute += n;
	    }
	}
	env->SetIntField(This, g_hourField, hour);
        env->SetIntField(This, g_minField, minute);

	if (offset != 0) {
	    // we need to normalize after applying the hour and minute offsets
	    android_text_format_Time_normalize(env, This, false /* use isdst */);
	    // The timezone is set to UTC in the calling Java code.
	}
    } else {
	env->SetBooleanField(This, g_allDayField, JNI_TRUE);
        env->SetIntField(This, g_hourField, 0);
        env->SetIntField(This, g_minField, 0);
        env->SetIntField(This, g_secField, 0);
    }

    env->SetIntField(This, g_wdayField, 0);
    env->SetIntField(This, g_ydayField, 0);
    env->SetIntField(This, g_isdstField, -1);
    env->SetLongField(This, g_gmtoffField, 0);
    
    env->ReleaseStringChars(strObj, s);
    return inUtc;
}

// ============================================================================
/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "normalize",               "(Z)J",                                        (void*)android_text_format_Time_normalize },
    { "switchTimezone",          "(Ljava/lang/String;)V",                       (void*)android_text_format_Time_switchTimezone },
    { "nativeCompare",           "(Landroid/text/format/Time;Landroid/text/format/Time;)I",     (void*)android_text_format_Time_compare },
    { "format1",                 "(Ljava/lang/String;)Ljava/lang/String;",      (void*)android_text_format_Time_format },
    { "format2445",              "()Ljava/lang/String;",                        (void*)android_text_format_Time_format2445 },
    { "toString",                "()Ljava/lang/String;",                        (void*)android_text_format_Time_toString },
    { "nativeParse",             "(Ljava/lang/String;)Z",                       (void*)android_text_format_Time_parse },
    { "nativeParse3339",         "(Ljava/lang/String;)Z",                       (void*)android_text_format_Time_parse3339 },
    { "setToNow",                "()V",                                         (void*)android_text_format_Time_setToNow },
    { "toMillis",                "(Z)J",                                        (void*)android_text_format_Time_toMillis },
    { "set",                     "(J)V",                                        (void*)android_text_format_Time_set }
};

int register_android_text_format_Time(JNIEnv* env)
{
    jclass timeClass = env->FindClass("android/text/format/Time");

    g_timeClass = (jclass) env->NewGlobalRef(timeClass);

    g_allDayField = env->GetFieldID(timeClass, "allDay", "Z");
    g_secField = env->GetFieldID(timeClass, "second", "I");
    g_minField = env->GetFieldID(timeClass, "minute", "I");
    g_hourField = env->GetFieldID(timeClass, "hour", "I");
    g_mdayField = env->GetFieldID(timeClass, "monthDay", "I");
    g_monField = env->GetFieldID(timeClass, "month", "I");
    g_yearField = env->GetFieldID(timeClass, "year", "I");
    g_wdayField = env->GetFieldID(timeClass, "weekDay", "I");
    g_ydayField = env->GetFieldID(timeClass, "yearDay", "I");
    g_isdstField = env->GetFieldID(timeClass, "isDst", "I");
    g_gmtoffField = env->GetFieldID(timeClass, "gmtoff", "J");
    g_timezoneField = env->GetFieldID(timeClass, "timezone", "Ljava/lang/String;");

    g_shortMonthsField = env->GetStaticFieldID(timeClass, "sShortMonths", "[Ljava/lang/String;");
    g_longMonthsField = env->GetStaticFieldID(timeClass, "sLongMonths", "[Ljava/lang/String;");
    g_longStandaloneMonthsField = env->GetStaticFieldID(timeClass, "sLongStandaloneMonths", "[Ljava/lang/String;");
    g_shortWeekdaysField = env->GetStaticFieldID(timeClass, "sShortWeekdays", "[Ljava/lang/String;");
    g_longWeekdaysField = env->GetStaticFieldID(timeClass, "sLongWeekdays", "[Ljava/lang/String;");
    g_timeOnlyFormatField = env->GetStaticFieldID(timeClass, "sTimeOnlyFormat", "Ljava/lang/String;");
    g_dateOnlyFormatField = env->GetStaticFieldID(timeClass, "sDateOnlyFormat", "Ljava/lang/String;");
    g_dateTimeFormatField = env->GetStaticFieldID(timeClass, "sDateTimeFormat", "Ljava/lang/String;");
    g_amField = env->GetStaticFieldID(timeClass, "sAm", "Ljava/lang/String;");
    g_pmField = env->GetStaticFieldID(timeClass, "sPm", "Ljava/lang/String;");
    g_dateCommandField = env->GetStaticFieldID(timeClass, "sDateCommand", "Ljava/lang/String;");
    g_localeField = env->GetStaticFieldID(timeClass, "sLocale", "Ljava/util/Locale;");

    return AndroidRuntime::registerNativeMethods(env, "android/text/format/Time", gMethods, NELEM(gMethods));
}

}; // namespace android
