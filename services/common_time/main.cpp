/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * A service that exchanges time synchronization information between
 * a master that defines a timeline and clients that follow the timeline.
 */

#define LOG_TAG "common_time"
#include <utils/Log.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>

#include "common_time_server.h"

int main(int argc, char *argv[]) {
    using namespace android;

    sp<CommonTimeServer> service = new CommonTimeServer();
    if (service == NULL)
        return 1;

    ProcessState::self()->startThreadPool();
    service->run("CommonTimeServer", ANDROID_PRIORITY_NORMAL);

    IPCThreadState::self()->joinThreadPool();
    return 0;
}

