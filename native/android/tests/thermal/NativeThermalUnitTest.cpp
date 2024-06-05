/*
 * Copyright (C) 2023 The Android Open Source Project
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

#define LOG_TAG "NativeThermalUnitTest"

#include <android/os/IThermalService.h>
#include <android/thermal.h>
#include <binder/IBinder.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <thermal_private.h>

using android::binder::Status;

using namespace testing;
using namespace android;
using namespace android::os;

class MockIThermalService : public IThermalService {
public:
    MOCK_METHOD(Status, registerThermalEventListener,
                (const ::android::sp<::android::os::IThermalEventListener>& listener,
                 bool* _aidl_return),
                (override));
    MOCK_METHOD(Status, registerThermalEventListenerWithType,
                (const ::android::sp<::android::os::IThermalEventListener>& listener, int32_t type,
                 bool* _aidl_return),
                (override));
    MOCK_METHOD(Status, unregisterThermalEventListener,
                (const ::android::sp<::android::os::IThermalEventListener>& listener,
                 bool* _aidl_return),
                (override));
    MOCK_METHOD(Status, getCurrentTemperatures,
                (::std::vector<::android::os::Temperature> * _aidl_return), (override));
    MOCK_METHOD(Status, getCurrentTemperaturesWithType,
                (int32_t type, ::std::vector<::android::os::Temperature>* _aidl_return),
                (override));
    MOCK_METHOD(Status, registerThermalStatusListener,
                (const ::android::sp<::android::os::IThermalStatusListener>& listener,
                 bool* _aidl_return),
                (override));
    MOCK_METHOD(Status, unregisterThermalStatusListener,
                (const ::android::sp<::android::os::IThermalStatusListener>& listener,
                 bool* _aidl_return),
                (override));
    MOCK_METHOD(Status, getCurrentThermalStatus, (int32_t * _aidl_return), (override));
    MOCK_METHOD(Status, getCurrentCoolingDevices,
                (::std::vector<::android::os::CoolingDevice> * _aidl_return), (override));
    MOCK_METHOD(Status, getCurrentCoolingDevicesWithType,
                (int32_t type, ::std::vector<::android::os::CoolingDevice>* _aidl_return),
                (override));
    MOCK_METHOD(Status, getThermalHeadroom, (int32_t forecastSeconds, float* _aidl_return),
                (override));
    MOCK_METHOD(Status, getThermalHeadroomThresholds, (::std::vector<float> * _aidl_return),
                (override));
    MOCK_METHOD(IBinder*, onAsBinder, (), (override));
};

class NativeThermalUnitTest : public Test {
public:
    void SetUp() override {
        mMockIThermalService = new StrictMock<MockIThermalService>();
        AThermal_setIThermalServiceForTesting(mMockIThermalService);
        mThermalManager = AThermal_acquireManager();
    }

    void TearDown() override {
        AThermal_setIThermalServiceForTesting(nullptr);
        AThermal_releaseManager(mThermalManager);
    }

    StrictMock<MockIThermalService>* mMockIThermalService = nullptr;
    AThermalManager* mThermalManager = nullptr;
};

static void checkThermalHeadroomThresholds(const std::vector<float>& expected,
                                           const AThermalHeadroomThreshold* thresholds,
                                           size_t size) {
    if (thresholds == nullptr) {
        FAIL() << "Unexpected null thresholds pointer";
    }
    for (int i = 0; i < (int)size; i++) {
        auto t = thresholds[i];
        ASSERT_EQ(i, t.thermalStatus) << "threshold " << i << " should have status " << i;
        ASSERT_EQ(expected[i], t.headroom)
                << "threshold " << i << " should have headroom " << expected[i];
    }
}

TEST_F(NativeThermalUnitTest, TestGetThermalHeadroomThresholds) {
    std::vector<float> expected = {1, 2, 3, 4, 5, 6, 7, 8, 9};
    EXPECT_CALL(*mMockIThermalService, getThermalHeadroomThresholds(_))
            .Times(Exactly(1))
            .WillRepeatedly(DoAll(SetArgPointee<0>(expected), Return(Status())));
    const AThermalHeadroomThreshold* thresholds1 = nullptr;
    size_t size1;
    ASSERT_EQ(OK, AThermal_getThermalHeadroomThresholds(mThermalManager, &thresholds1, &size1));
    checkThermalHeadroomThresholds(expected, thresholds1, size1);
    // following calls should be cached
    EXPECT_CALL(*mMockIThermalService, getThermalHeadroomThresholds(_)).Times(0);

    const AThermalHeadroomThreshold* thresholds2 = nullptr;
    size_t size2;
    ASSERT_EQ(OK, AThermal_getThermalHeadroomThresholds(mThermalManager, &thresholds2, &size2));
    checkThermalHeadroomThresholds(expected, thresholds2, size2);
}

TEST_F(NativeThermalUnitTest, TestGetThermalHeadroomThresholdsFailedWithServerError) {
    const AThermalHeadroomThreshold* thresholds = nullptr;
    size_t size;
    EXPECT_CALL(*mMockIThermalService, getThermalHeadroomThresholds(_))
            .Times(Exactly(1))
            .WillOnce(Return(
                    Status::fromExceptionCode(binder::Status::Exception::EX_ILLEGAL_ARGUMENT)));
    ASSERT_EQ(EPIPE, AThermal_getThermalHeadroomThresholds(mThermalManager, &thresholds, &size));
    ASSERT_EQ(nullptr, thresholds);
}

TEST_F(NativeThermalUnitTest, TestGetThermalHeadroomThresholdsFailedWithFeatureDisabled) {
    const AThermalHeadroomThreshold* thresholds = nullptr;
    size_t size;
    EXPECT_CALL(*mMockIThermalService, getThermalHeadroomThresholds(_))
            .Times(Exactly(1))
            .WillOnce(Return(Status::fromExceptionCode(
                    binder::Status::Exception::EX_UNSUPPORTED_OPERATION)));
    ASSERT_EQ(ENOSYS, AThermal_getThermalHeadroomThresholds(mThermalManager, &thresholds, &size));
    ASSERT_EQ(nullptr, thresholds);
}

TEST_F(NativeThermalUnitTest, TestGetThermalHeadroomThresholdsFailedWithNullPtr) {
    const AThermalHeadroomThreshold* thresholds = nullptr;
    size_t size;
    size_t* nullSize = nullptr;
    ASSERT_EQ(EINVAL,
              AThermal_getThermalHeadroomThresholds(mThermalManager, &thresholds, nullSize));
    ASSERT_EQ(nullptr, thresholds);
    ASSERT_EQ(EINVAL, AThermal_getThermalHeadroomThresholds(mThermalManager, nullptr, &size));
}

TEST_F(NativeThermalUnitTest, TestGetThermalHeadroomThresholdsFailedWithNonEmptyPtr) {
    const AThermalHeadroomThreshold* initialized = new AThermalHeadroomThreshold[1];
    size_t size;
    ASSERT_EQ(EINVAL, AThermal_getThermalHeadroomThresholds(mThermalManager, &initialized, &size));
    delete[] initialized;
}
