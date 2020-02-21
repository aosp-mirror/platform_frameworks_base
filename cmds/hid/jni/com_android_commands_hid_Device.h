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
#include <vector>

#include <jni.h>

#include <android-base/unique_fd.h>

namespace android {
namespace uhid {

class DeviceCallback {
public:
    DeviceCallback(JNIEnv* env, jobject callback);
    ~DeviceCallback();

    void onDeviceOpen();
    void onDeviceGetReport(uint32_t requestId, uint8_t reportId);
    void onDeviceOutput(const std::vector<uint8_t>& data);
    void onDeviceError();

private:
    JNIEnv* getJNIEnv();
    jobject mCallbackObject;
    JavaVM* mJavaVM;
};

class Device {
public:
    static std::unique_ptr<Device> open(int32_t id, const char* name, int32_t vid, int32_t pid,
                                        uint16_t bus, const std::vector<uint8_t>& descriptor,
                                        std::unique_ptr<DeviceCallback> callback);

    ~Device();

    void sendReport(const std::vector<uint8_t>& report) const;
    void sendGetFeatureReportReply(uint32_t id, const std::vector<uint8_t>& report) const;
    void close();

    int handleEvents(int events);

private:
    Device(int32_t id, android::base::unique_fd fd, std::unique_ptr<DeviceCallback> callback);
    int32_t mId;
    android::base::unique_fd mFd;
    std::unique_ptr<DeviceCallback> mDeviceCallback;
};


} // namespace uhid
} // namespace android
