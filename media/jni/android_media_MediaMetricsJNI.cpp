/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "MediaMetricsJNI"

#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include "android_media_MediaMetricsJNI.h"
#include <media/MediaAnalyticsItem.h>


// This source file is compiled and linked into both:
// core/jni/ (libandroid_runtime.so)
// media/jni (libmedia2_jni.so)

namespace android {

// place the attributes into a java PersistableBundle object
jobject MediaMetricsJNI::writeMetricsToBundle(JNIEnv* env, MediaAnalyticsItem *item, jobject mybundle) {

    jclass clazzBundle = env->FindClass("android/os/PersistableBundle");
    if (clazzBundle==NULL) {
        ALOGE("can't find android/os/PersistableBundle");
        return NULL;
    }
    // sometimes the caller provides one for us to fill
    if (mybundle == NULL) {
        // create the bundle
        jmethodID constructID = env->GetMethodID(clazzBundle, "<init>", "()V");
        mybundle = env->NewObject(clazzBundle, constructID);
        if (mybundle == NULL) {
            return NULL;
        }
    }

    // grab methods that we can invoke
    jmethodID setIntID = env->GetMethodID(clazzBundle, "putInt", "(Ljava/lang/String;I)V");
    jmethodID setLongID = env->GetMethodID(clazzBundle, "putLong", "(Ljava/lang/String;J)V");
    jmethodID setDoubleID = env->GetMethodID(clazzBundle, "putDouble", "(Ljava/lang/String;D)V");
    jmethodID setStringID = env->GetMethodID(clazzBundle, "putString", "(Ljava/lang/String;Ljava/lang/String;)V");

    // env, class, method, {parms}
    //env->CallVoidMethod(env, mybundle, setIntID, jstr, jint);

    // iterate through my attributes
    // -- get name, get type, get value
    // -- insert appropriately into the bundle
    for (size_t i = 0 ; i < item->mPropCount; i++ ) {
            MediaAnalyticsItem::Prop *prop = &item->mProps[i];
            // build the key parameter from prop->mName
            jstring keyName = env->NewStringUTF(prop->mName);
            // invoke the appropriate method to insert
            switch (prop->mType) {
                case MediaAnalyticsItem::kTypeInt32:
                    env->CallVoidMethod(mybundle, setIntID,
                                        keyName, (jint) prop->u.int32Value);
                    break;
                case MediaAnalyticsItem::kTypeInt64:
                    env->CallVoidMethod(mybundle, setLongID,
                                        keyName, (jlong) prop->u.int64Value);
                    break;
                case MediaAnalyticsItem::kTypeDouble:
                    env->CallVoidMethod(mybundle, setDoubleID,
                                        keyName, (jdouble) prop->u.doubleValue);
                    break;
                case MediaAnalyticsItem::kTypeCString:
                    env->CallVoidMethod(mybundle, setStringID, keyName,
                                        env->NewStringUTF(prop->u.CStringValue));
                    break;
                default:
                        ALOGE("to_String bad item type: %d for %s",
                              prop->mType, prop->mName);
                        break;
            }
    }

    return mybundle;
}

// convert the specified batch  metrics attributes to a persistent bundle.
// The encoding of the byte array is specified in
//     frameworks/av/media/libmediametrics/MediaAnalyticsItem.cpp
//
// type encodings; matches frameworks/av/media/libmediametrics/MediaAnalyticsItem.cpp
enum { kInt32 = 0, kInt64, kDouble, kRate, kCString};

jobject MediaMetricsJNI::writeAttributesToBundle(JNIEnv* env, jobject mybundle, char *buffer, size_t length) {
    ALOGV("writeAttributes()");

    if (buffer == NULL || length <= 0) {
        ALOGW("bad parameters to writeAttributesToBundle()");
        return NULL;
    }

    jclass clazzBundle = env->FindClass("android/os/PersistableBundle");
    if (clazzBundle==NULL) {
        ALOGE("can't find android/os/PersistableBundle");
        return NULL;
    }
    // sometimes the caller provides one for us to fill
    if (mybundle == NULL) {
        // create the bundle
        jmethodID constructID = env->GetMethodID(clazzBundle, "<init>", "()V");
        mybundle = env->NewObject(clazzBundle, constructID);
        if (mybundle == NULL) {
            ALOGD("unable to create mybundle");
            return NULL;
        }
    }

    int left = length;
    char *buf = buffer;

    // grab methods that we can invoke
    jmethodID setIntID = env->GetMethodID(clazzBundle, "putInt", "(Ljava/lang/String;I)V");
    jmethodID setLongID = env->GetMethodID(clazzBundle, "putLong", "(Ljava/lang/String;J)V");
    jmethodID setDoubleID = env->GetMethodID(clazzBundle, "putDouble", "(Ljava/lang/String;D)V");
    jmethodID setStringID = env->GetMethodID(clazzBundle, "putString", "(Ljava/lang/String;Ljava/lang/String;)V");


#define _EXTRACT(size, val) \
    { if ((size) > left) goto badness; memcpy(&val, buf, (size)); buf += (size); left -= (size);}
#define _SKIP(size) \
    { if ((size) > left) goto badness; buf += (size); left -= (size);}

    int32_t bufsize;
    _EXTRACT(sizeof(int32_t), bufsize);
    if (bufsize != length) {
        goto badness;
    }
    int32_t proto;
    _EXTRACT(sizeof(int32_t), proto);
    if (proto != 0) {
        ALOGE("unsupported wire protocol %d", proto);
        goto badness;
    }

    int32_t count;
    _EXTRACT(sizeof(int32_t), count);

    // iterate through my attributes
    // -- get name, get type, get value, insert into bundle appropriately.
    for (int i = 0 ; i < count; i++ ) {
            // prop name len (int16)
            int16_t keylen;
            _EXTRACT(sizeof(int16_t), keylen);
            if (keylen <= 0) goto badness;
            // prop name itself
            char *key = buf;
            jstring keyName = env->NewStringUTF(buf);
            _SKIP(keylen);

            // prop type (int8_t)
            int8_t attrType;
            _EXTRACT(sizeof(int8_t), attrType);

	    int16_t attrSize;
            _EXTRACT(sizeof(int16_t), attrSize);

            switch (attrType) {
                case kInt32:
                    {
                        int32_t i32;
                        _EXTRACT(sizeof(int32_t), i32);
                        env->CallVoidMethod(mybundle, setIntID,
                                            keyName, (jint) i32);
                        break;
                    }
                case kInt64:
                    {
                        int64_t i64;
                        _EXTRACT(sizeof(int64_t), i64);
                        env->CallVoidMethod(mybundle, setLongID,
                                            keyName, (jlong) i64);
                        break;
                    }
                case kDouble:
                    {
                        double d64;
                        _EXTRACT(sizeof(double), d64);
                        env->CallVoidMethod(mybundle, setDoubleID,
                                            keyName, (jdouble) d64);
                        break;
                    }
                case kCString:
                    {
                        jstring value = env->NewStringUTF(buf);
                        env->CallVoidMethod(mybundle, setStringID,
                                            keyName, value);
                        _SKIP(attrSize);
                        break;
                    }
                default:
                        ALOGW("ignoring Attribute '%s' unknown type: %d",
                              key, attrType);
			_SKIP(attrSize);
                        break;
            }
    }

    // should have consumed it all
    if (left != 0) {
        ALOGW("did not consume entire buffer; left(%d) != 0", left);
	goto badness;
    }

    return mybundle;

  badness:
    return NULL;
}

};  // namespace android

