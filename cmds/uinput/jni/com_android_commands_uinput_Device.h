/*
 * Copyright (C) 2020 The Android Open Source Project
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
#include <vector>

#include <jni.h>
#include <linux/input.h>

#include <android-base/unique_fd.h>
#include "src/com/android/commands/uinput/InputAbsInfo.h"

namespace android {
namespace uinput {

class DeviceCallback {
public:
    DeviceCallback(JNIEnv* env, jobject callback);
    ~DeviceCallback();

    void onDeviceOpen();
    void onDeviceGetReport(uint32_t requestId, uint8_t reportId);
    void onDeviceOutput(const std::vector<uint8_t>& data);
    void onDeviceConfigure(int handle);
    void onDeviceVibrating(int value);
    void onDeviceError();

private:
    JNIEnv* getJNIEnv();
    jobject mCallbackObject;
    JavaVM* mJavaVM;
};

class UinputDevice {
public:
    static std::unique_ptr<UinputDevice> open(int32_t id, const char* name, int32_t vendorId,
                                              int32_t productId, int32_t versionId, uint16_t bus,
                                              uint32_t ff_effects_max, const char* port,
                                              std::unique_ptr<DeviceCallback> callback);

    virtual ~UinputDevice();

    void injectEvent(uint16_t type, uint16_t code, int32_t value);
    int handleEvents(int events);

private:
    UinputDevice(int32_t id, android::base::unique_fd fd, std::unique_ptr<DeviceCallback> callback);

    int32_t mId;
    android::base::unique_fd mFd;
    std::unique_ptr<DeviceCallback> mDeviceCallback;
};

} // namespace uinput
} // namespace android
