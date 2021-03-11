/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include "permission_utils.h"
#include "core_jni_helpers.h"

static struct {
    jfieldID fieldUid;            // Identity.uid
    jfieldID fieldPid;            // Identity.pid
    jfieldID fieldPackageName;    // Identity.packageName
    jfieldID fieldAttributionTag; // Identity.attributionTag
} javaIdentityFields;

static const JNINativeMethod method_table[] = {
        // no static methods, currently
};

int register_android_media_permission_Identity(JNIEnv* env) {
    jclass identityClass = android::FindClassOrDie(env, "android/media/permission/Identity");
    javaIdentityFields.fieldUid = android::GetFieldIDOrDie(env, identityClass, "uid", "I");
    javaIdentityFields.fieldPid = android::GetFieldIDOrDie(env, identityClass, "pid", "I");
    javaIdentityFields.fieldPackageName =
            android::GetFieldIDOrDie(env, identityClass, "packageName", "Ljava/lang/String;");
    javaIdentityFields.fieldAttributionTag =
            android::GetFieldIDOrDie(env, identityClass, "attributionTag", "Ljava/lang/String;");

    return android::RegisterMethodsOrDie(env, "android/media/permission/Identity", method_table,
                                         NELEM(method_table));
}

namespace android::media::permission {

Identity convertIdentity(JNIEnv* env, const jobject& jIdentity) {
    Identity identity;

    identity.uid = env->GetIntField(jIdentity, javaIdentityFields.fieldUid);
    identity.pid = env->GetIntField(jIdentity, javaIdentityFields.fieldPid);

    jstring packageNameStr = static_cast<jstring>(
            env->GetObjectField(jIdentity, javaIdentityFields.fieldPackageName));
    if (packageNameStr == nullptr) {
        identity.packageName = std::nullopt;
    } else {
        identity.packageName = std::string(ScopedUtfChars(env, packageNameStr).c_str());
    }

    jstring attributionTagStr = static_cast<jstring>(
            env->GetObjectField(jIdentity, javaIdentityFields.fieldAttributionTag));
    if (attributionTagStr == nullptr) {
        identity.attributionTag = std::nullopt;
    } else {
        identity.attributionTag = std::string(ScopedUtfChars(env, attributionTagStr).c_str());
    }

    return identity;
}

} // namespace android::media::permission
