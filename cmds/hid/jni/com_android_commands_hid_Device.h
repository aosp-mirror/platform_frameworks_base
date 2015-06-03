/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include <memory>

#include <jni.h>
#include <utils/Looper.h>
#include <utils/StrongPointer.h>

namespace android {
namespace uhid {

class DeviceCallback {
public:
    DeviceCallback(JNIEnv* env, jobject callback);
    ~DeviceCallback();

    void onDeviceOpen();
    void onDeviceError();

private:
    jobject mCallbackObject;
};

class Device {
public:
    static Device* open(int32_t id, const char* name, int32_t vid, int32_t pid,
            std::unique_ptr<uint8_t[]> descriptor, size_t descriptorSize,
            std::unique_ptr<DeviceCallback> callback, sp<Looper> looper);

    Device(int32_t id, int fd, std::unique_ptr<DeviceCallback> callback, sp<Looper> looper);
    ~Device();

    void sendReport(uint8_t* report, size_t reportSize);
    void close();

    int handleEvents(int events);

private:
    int32_t mId;
    int mFd;
    std::unique_ptr<DeviceCallback> mDeviceCallback;
    sp<Looper> mLooper;
};


} // namespace uhid
} // namespace android
