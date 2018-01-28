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

#include <android_runtime/AndroidRuntime.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include "android_media_MediaMetricsJNI.h"
#include <media/MediaAnalyticsItem.h>


namespace android {

// place the attributes into a java PersistableBundle object
jobject MediaMetricsJNI::writeMetricsToBundle(JNIEnv* env, MediaAnalyticsItem *item, jobject mybundle) {

    jclass clazzBundle = env->FindClass("android/os/PersistableBundle");
    if (clazzBundle==NULL) {
        ALOGD("can't find android/os/PersistableBundle");
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

};  // namespace android

