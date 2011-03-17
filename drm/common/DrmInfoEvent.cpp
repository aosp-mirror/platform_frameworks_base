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

#include <utils/String8.h>
#include <drm/DrmInfoEvent.h>

using namespace android;

DrmInfoEvent::DrmInfoEvent(int uniqueId, int infoType, const String8 message)
    : mUniqueId(uniqueId),
      mInfoType(infoType),
      mMessage(message) {

}

int DrmInfoEvent::getUniqueId() const {
    return mUniqueId;
}

int DrmInfoEvent::getType() const {
    return mInfoType;
}

const String8 DrmInfoEvent::getMessage() const {
    return mMessage;
}

