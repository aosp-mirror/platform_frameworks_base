/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef _UI_INPUT_APPLICATION_H
#define _UI_INPUT_APPLICATION_H

#include <ui/Input.h>

#include <utils/RefBase.h>
#include <utils/Timers.h>
#include <utils/String8.h>

namespace android {

/*
 * Describes the properties of an application that can receive input.
 *
 * Used by the native input dispatcher as a handle for the window manager objects
 * that describe an application.
 */
class InputApplicationHandle : public RefBase {
public:
    String8 name;
    nsecs_t dispatchingTimeout;

    /**
     * Requests that the state of this object be updated to reflect
     * the most current available information about the application.
     *
     * This method should only be called from within the input dispatcher's
     * critical section.
     *
     * Returns true on success, or false if the handle is no longer valid.
     */
    virtual bool update() = 0;

protected:
    InputApplicationHandle() { }
    virtual ~InputApplicationHandle() { }
};

} // namespace android

#endif // _UI_INPUT_APPLICATION_H
