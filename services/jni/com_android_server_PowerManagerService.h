/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef _ANDROID_SERVER_POWER_MANAGER_SERVICE_H
#define _ANDROID_SERVER_POWER_MANAGER_SERVICE_H

#include "JNIHelp.h"
#include "jni.h"

namespace android {

enum {
    POWER_MANAGER_OTHER_EVENT = 0,
    POWER_MANAGER_CHEEK_EVENT = 1,
    POWER_MANAGER_TOUCH_EVENT = 2, // touch events are TOUCH for 300ms, and then either
                                   // up events or LONG_TOUCH events.
    POWER_MANAGER_LONG_TOUCH_EVENT = 3,
    POWER_MANAGER_TOUCH_UP_EVENT = 4,
    POWER_MANAGER_BUTTON_EVENT = 5, // Button and trackball events.

    POWER_MANAGER_LAST_EVENT = POWER_MANAGER_BUTTON_EVENT, // Last valid event code.
};

extern bool android_server_PowerManagerService_isScreenOn();
extern bool android_server_PowerManagerService_isScreenBright();
extern void android_server_PowerManagerService_userActivity(nsecs_t eventTime, int32_t eventType);
extern void android_server_PowerManagerService_goToSleep(nsecs_t eventTime);

} // namespace android

#endif // _ANDROID_SERVER_POWER_MANAGER_SERVICE_H
