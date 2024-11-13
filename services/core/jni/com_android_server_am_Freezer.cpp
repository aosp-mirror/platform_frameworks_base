/*
 * Copyright (C) 2024 The Android Open Source Project
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

#define LOG_TAG "Freezer"
//#define LOG_NDEBUG 0
#define ATRACE_TAG ATRACE_TAG_ACTIVITY_MANAGER

#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

#include <android-base/logging.h>
#include <android-base/unique_fd.h>
#include <binder/IPCThreadState.h>
#include <nativehelper/JNIHelp.h>
#include <processgroup/processgroup.h>

namespace android {
namespace {

// Binder status bit flags.
static const int SYNC_RECEIVED_WHILE_FROZEN = 1;
static const int ASYNC_RECEIVED_WHILE_FROZEN = 2;
static const int TXNS_PENDING_WHILE_FROZEN = 4;

jint freezeBinder(JNIEnv* env, jobject, jint pid, jboolean freeze, jint timeout_ms) {
    jint retVal = IPCThreadState::freeze(pid, freeze, timeout_ms);
    if (retVal != 0 && retVal != -EAGAIN) {
        jniThrowException(env, "java/lang/RuntimeException", "Unable to freeze/unfreeze binder");
    }

    return retVal;
}

jint getBinderFreezeInfo(JNIEnv *env, jobject, jint pid) {
    uint32_t syncReceived = 0, asyncReceived = 0;

    int error = IPCThreadState::getProcessFreezeInfo(pid, &syncReceived, &asyncReceived);

    if (error < 0) {
        jniThrowException(env, "java/lang/RuntimeException", strerror(error));
    }

    jint retVal = 0;

    // bit 0 of sync_recv goes to bit 0 of retVal
    retVal |= syncReceived & SYNC_RECEIVED_WHILE_FROZEN;
    // bit 0 of async_recv goes to bit 1 of retVal
    retVal |= (asyncReceived << 1) & ASYNC_RECEIVED_WHILE_FROZEN;
    // bit 1 of sync_recv goes to bit 2 of retVal
    retVal |= (syncReceived << 1) & TXNS_PENDING_WHILE_FROZEN;

    return retVal;
}

bool isFreezerSupported(JNIEnv *env, jclass) {
    std::string path;
    if (!getAttributePathForTask("FreezerState", getpid(), &path)) {
        ALOGI("No attribute for FreezerState");
        return false;
    }
    base::unique_fd fid(open(path.c_str(), O_RDONLY));
    if (fid < 0) {
        ALOGI("Cannot open freezer path \"%s\": %s", path.c_str(), strerror(errno));
        return false;
    }

    char state;
    if (::read(fid, &state, 1) != 1) {
        ALOGI("Failed to read freezer state: %s", strerror(errno));
        return false;
    }
    if (state != '1' && state != '0') {
        ALOGE("Unexpected value in cgroup.freeze: %d", state);
        return false;
    }

    uid_t uid = getuid();
    pid_t pid = getpid();

    uint32_t syncReceived = 0, asyncReceived = 0;
    int error = IPCThreadState::getProcessFreezeInfo(pid, &syncReceived, &asyncReceived);
    if (error < 0) {
        ALOGE("Unable to read freezer info: %s", strerror(errno));
        return false;
    }

    if (!isProfileValidForProcess("Frozen", uid, pid)
            || !isProfileValidForProcess("Unfrozen", uid, pid)) {
        ALOGE("Missing freezer profiles");
        return false;
    }

    return true;
}

static const JNINativeMethod sMethods[] = {
    {"nativeIsFreezerSupported",    "()Z",       (void*) isFreezerSupported },
    {"nativeFreezeBinder",          "(IZI)I",    (void*) freezeBinder },
    {"nativeGetBinderFreezeInfo",   "(I)I",      (void*) getBinderFreezeInfo },
};

} // end of anonymous namespace

int register_android_server_am_Freezer(JNIEnv* env)
{
    char const *className = "com/android/server/am/Freezer";
    return jniRegisterNativeMethods(env, className, sMethods, NELEM(sMethods));
}

} // end of namespace android
