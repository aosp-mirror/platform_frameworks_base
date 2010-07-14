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

#ifndef ANDROID_GUI_SENSOR_CHANNEL_H
#define ANDROID_GUI_SENSOR_CHANNEL_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/RefBase.h>


namespace android {
// ----------------------------------------------------------------------------
class Parcel;

class SensorChannel : public RefBase
{
public:

            SensorChannel();
            SensorChannel(const Parcel& data);
    virtual ~SensorChannel();

    int getFd() const;
    ssize_t write(void const* vaddr, size_t size);
    ssize_t read(void* vaddr, size_t size);

    status_t writeToParcel(Parcel* reply) const;

private:
    int mSendFd;
    mutable int mReceiveFd;
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GUI_SENSOR_CHANNEL_H
