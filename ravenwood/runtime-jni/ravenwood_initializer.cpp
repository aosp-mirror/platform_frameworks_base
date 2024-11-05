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

 /*
  * This file is compiled into a single SO file, which we load at the very first.
  * We can do process-wide initialization here.
  */

#include <fcntl.h>
#include <unistd.h>

#include "jni_helper.h"

static void maybeRedirectLog() {
    auto ravenwoodLogOut = getenv("RAVENWOOD_LOG_OUT");
    if (ravenwoodLogOut == NULL) {
        return;
    }
    ALOGI("RAVENWOOD_LOG_OUT set. Redirecting output to %s", ravenwoodLogOut);

    // Redirect stdin / stdout to /dev/tty.
    int ttyFd = open(ravenwoodLogOut, O_WRONLY | O_APPEND);
    if (ttyFd == -1) {
        ALOGW("$RAVENWOOD_LOG_OUT is set to %s, but failed to open: %s ", ravenwoodLogOut,
                strerror(errno));
        return;
    }
    dup2(ttyFd, 1);
    dup2(ttyFd, 2);
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    ALOGI("%s: JNI_OnLoad", __FILE__);

    maybeRedirectLog();
    return JNI_VERSION_1_4;
}
