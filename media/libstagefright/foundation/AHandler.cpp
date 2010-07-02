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

//#define LOG_NDEBUG 0
#define LOG_TAG "AHandler"
#include <utils/Log.h>

#include <media/stagefright/foundation/AHandler.h>

#include <media/stagefright/foundation/ALooperRoster.h>

namespace android {

sp<ALooper> AHandler::looper() {
    extern ALooperRoster gLooperRoster;

    return gLooperRoster.findLooper(id());
}

}  // namespace android
