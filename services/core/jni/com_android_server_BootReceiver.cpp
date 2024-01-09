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

#include <libdebuggerd/tombstone.h>
#include <nativehelper/JNIHelp.h>

#include <sstream>

#include "jni.h"
#include "tombstone.pb.h"

namespace android {

static void writeToString(std::stringstream& ss, const std::string& line, bool should_log) {
    ss << line << std::endl;
}

static jstring com_android_server_BootReceiver_getTombstoneText(JNIEnv* env, jobject,
                                                                jbyteArray tombstoneBytes) {
    Tombstone tombstone;
    tombstone.ParseFromArray(env->GetByteArrayElements(tombstoneBytes, 0),
                             env->GetArrayLength(tombstoneBytes));

    std::stringstream tombstoneString;

    tombstone_proto_to_text(tombstone,
                            std::bind(&writeToString, std::ref(tombstoneString),
                                      std::placeholders::_1, std::placeholders::_2));

    return env->NewStringUTF(tombstoneString.str().c_str());
}

static const JNINativeMethod sMethods[] = {
        /* name, signature, funcPtr */
        {"getTombstoneText", "([B)Ljava/lang/String;",
         (jstring*)com_android_server_BootReceiver_getTombstoneText},
};

int register_com_android_server_BootReceiver(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/BootReceiver", sMethods,
                                    NELEM(sMethods));
}

} // namespace android
