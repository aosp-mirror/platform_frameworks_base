/*
 * Copyright (C) 2023 The Android Open Source Project
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
#pragma once

#include <nativehelper/JNIHelp.h>
#include <binder/Parcel.h>

namespace android {
    static jclass gParcelClazz;
    static jfieldID gParcelDataFieldID;
    static jmethodID gParcelObtainMethodID;

    static inline jclass FindClassOrDie(JNIEnv* env, const char* class_name) {
        jclass clazz = env->FindClass(class_name);
        LOG_ALWAYS_FATAL_IF(clazz == NULL, "Unable to find class %s", class_name);
        return clazz;
    }

    static inline jfieldID GetFieldIDOrDie(JNIEnv* env, jclass clazz, const char* field_name,
                                           const char* field_signature) {
        jfieldID res = env->GetFieldID(clazz, field_name, field_signature);
        LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find field %s with signature %s", field_name,
                            field_signature);
        return res;
    }

    static inline jmethodID GetStaticMethodIDOrDie(JNIEnv* env, jclass clazz,
                                                   const char* method_name,
                                                   const char* method_signature) {
        jmethodID res = env->GetStaticMethodID(clazz, method_name, method_signature);
        LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find method %s with signature %s", method_name,
                            method_signature);
        return res;
    }

    static jobject nativeObtainParcel(JNIEnv* env) {
        jobject parcel = env->CallStaticObjectMethod(gParcelClazz, gParcelObtainMethodID);
        if (parcel == nullptr) {
            jniThrowException(env, "java/lang/IllegalArgumentException", "Obtain parcel failed.");
        }
        return parcel;
    }

    static Parcel* nativeGetParcelData(JNIEnv* env, jobject obj) {
        Parcel* parcel = reinterpret_cast<Parcel*>(env->GetLongField(obj, gParcelDataFieldID));
        if (parcel && parcel->objectsCount() != 0) {
            jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid parcel object.");
        }
        parcel->setDataPosition(0);
        return parcel;
    }

    static void loadParcelClass(JNIEnv* env) {
        gParcelClazz = FindClassOrDie(env, "android/os/Parcel");
        gParcelDataFieldID = GetFieldIDOrDie(env, gParcelClazz, "mNativePtr", "J");
        gParcelObtainMethodID =
                GetStaticMethodIDOrDie(env, gParcelClazz, "obtain", "()Landroid/os/Parcel;");
    }

}