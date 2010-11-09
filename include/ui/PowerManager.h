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

#ifndef _UI_POWER_MANAGER_H
#define _UI_POWER_MANAGER_H


namespace android {

enum {
    POWER_MANAGER_OTHER_EVENT = 0,
    POWER_MANAGER_BUTTON_EVENT = 1,
    POWER_MANAGER_TOUCH_EVENT = 2,

    POWER_MANAGER_LAST_EVENT = POWER_MANAGER_TOUCH_EVENT, // Last valid event code.
};

} // namespace android

#endif // _UI_POWER_MANAGER_H
