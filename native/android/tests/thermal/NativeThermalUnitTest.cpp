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
    MOCK_METHOD(Status, registerThermalHeadroomListener,
                (const ::android::sp<::android::os::IThermalHeadroomListener>& listener,
                 bool* _aidl_return),
                (override));
    MOCK_METHOD(Status, unregisterThermalHeadroomListener,
                (const ::android::sp<::android::os::IThermalHeadroomListener>& listener,
                 bool* _aidl_return),
                (override));
};

struct HeadroomCallbackData {
    void* data;
    float headroom;
    float forecast;
    int32_t forecastSeconds;
    const std::vector<float> thresholds;
};

struct StatusCallbackData {
    void* data;
    AThermalStatus status;
};

static std::optional<HeadroomCallbackData> headroomCalled1;
static std::optional<HeadroomCallbackData> headroomCalled2;
static std::optional<StatusCallbackData> statusCalled1;
static std::optional<StatusCallbackData> statusCalled2;

static std::vector<float> convertThresholds(const AThermalHeadroomThreshold* thresholds,
                                            size_t size) {
    std::vector<float> ret;
    for (int i = 0; i < (int)size; i++) {
        ret.emplace_back(thresholds[i].headroom);
    }
    return ret;
};

static void onHeadroomChange1(void* data, float headroom, float forecast, int32_t forecastSeconds,
                              const AThermalHeadroomThreshold* thresholds, size_t size) {
    headroomCalled1.emplace(data, headroom, forecast, forecastSeconds,
                            convertThresholds(thresholds, size));
}

static void onHeadroomChange2(void* data, float headroom, float forecast, int32_t forecastSeconds,
                              const AThermalHeadroomThreshold* thresholds, size_t size) {
    headroomCalled2.emplace(data, headroom, forecast, forecastSeconds,
                            convertThresholds(thresholds, size));
}

static void onStatusChange1(void* data, AThermalStatus status) {
    statusCalled1.emplace(data, status);
}
static void onStatusChange2(void* data, AThermalStatus status) {
    statusCalled2.emplace(data, status);
}

class NativeThermalUnitTest : public Test {
public:
    void SetUp() override {
        mMockIThermalService = new StrictMock<MockIThermalService>();
        AThermal_setIThermalServiceForTesting(mMockIThermalService);
        mThermalManager = AThermal_acquireManager();
        headroomCalled1.reset();
        headroomCalled2.reset();
        statusCalled1.reset();
        statusCalled2.reset();
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
    // following calls should not be cached
    expected = {10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
    EXPECT_CALL(*mMockIThermalService, getThermalHeadroomThresholds(_))
            .Times(Exactly(1))
            .WillRepeatedly(DoAll(SetArgPointee<0>(expected), Return(Status())));
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

TEST_F(NativeThermalUnitTest, TestRegisterThermalHeadroomListener) {
    EXPECT_CALL(*mMockIThermalService, registerThermalHeadroomListener(_, _))
            .Times(Exactly(2))
            .WillOnce(Return(
                    Status::fromExceptionCode(binder::Status::Exception::EX_TRANSACTION_FAILED)));
    float data1 = 1.0f;
    float data2 = 2.0f;
    ASSERT_EQ(EPIPE,
              AThermal_registerThermalHeadroomListener(mThermalManager, onHeadroomChange1, &data1));
    ASSERT_EQ(EPIPE,
              AThermal_registerThermalHeadroomListener(mThermalManager, onHeadroomChange2, &data2));

    // verify only 1 service call to register a global listener
    sp<IThermalHeadroomListener> capturedServiceListener;
    Mock::VerifyAndClearExpectations(mMockIThermalService);
    EXPECT_CALL(*mMockIThermalService, registerThermalHeadroomListener(_, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(testing::SaveArg<0>(&capturedServiceListener),
                            testing::Invoke([](const sp<IThermalHeadroomListener>&,
                                               bool* aidl_return) { *aidl_return = true; }),
                            Return(Status::ok())));
    ASSERT_EQ(0,
              AThermal_registerThermalHeadroomListener(mThermalManager, onHeadroomChange1, &data1));
    ASSERT_EQ(EINVAL,
              AThermal_registerThermalHeadroomListener(mThermalManager, onHeadroomChange1, &data1));
    ASSERT_EQ(0,
              AThermal_registerThermalHeadroomListener(mThermalManager, onHeadroomChange2, &data2));
    const ::std::vector<float> thresholds = {0.1f, 0.2f};
    capturedServiceListener->onHeadroomChange(0.1f, 0.3f, 20, thresholds);
    ASSERT_TRUE(headroomCalled1.has_value());
    EXPECT_EQ(headroomCalled1->data, &data1);
    EXPECT_EQ(headroomCalled1->headroom, 0.1f);
    EXPECT_EQ(headroomCalled1->forecast, 0.3f);
    EXPECT_EQ(headroomCalled1->forecastSeconds, 20);
    EXPECT_EQ(headroomCalled1->thresholds, thresholds);
    ASSERT_TRUE(headroomCalled2.has_value());
    EXPECT_EQ(headroomCalled2->data, &data2);
    EXPECT_EQ(headroomCalled2->headroom, 0.1f);
    EXPECT_EQ(headroomCalled2->forecast, 0.3f);
    EXPECT_EQ(headroomCalled2->forecastSeconds, 20);
    EXPECT_EQ(headroomCalled2->thresholds, thresholds);

    // after test finished the global service listener should be unregistered
    EXPECT_CALL(*mMockIThermalService, unregisterThermalHeadroomListener(_, _))
            .Times(Exactly(1))
            .WillOnce(Return(binder::Status::ok()));
}

TEST_F(NativeThermalUnitTest, TestUnregisterThermalHeadroomListener) {
    sp<IThermalHeadroomListener> capturedServiceListener;
    EXPECT_CALL(*mMockIThermalService, registerThermalHeadroomListener(_, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(testing::SaveArg<0>(&capturedServiceListener),
                            testing::Invoke([](const sp<IThermalHeadroomListener>&,
                                               bool* aidl_return) { *aidl_return = true; }),
                            Return(Status::ok())));
    float data1 = 1.0f;
    float data2 = 2.0f;
    ASSERT_EQ(0,
              AThermal_registerThermalHeadroomListener(mThermalManager, onHeadroomChange1, &data1));
    ASSERT_EQ(0,
              AThermal_registerThermalHeadroomListener(mThermalManager, onHeadroomChange2, &data2));
    capturedServiceListener->onHeadroomChange(0.1f, 0.3f, 20, {});
    ASSERT_TRUE(headroomCalled1.has_value());
    ASSERT_TRUE(headroomCalled2.has_value());

    EXPECT_CALL(*mMockIThermalService, unregisterThermalHeadroomListener(_, _))
            .Times(Exactly(1))
            .WillRepeatedly(Return(
                    Status::fromExceptionCode(binder::Status::Exception::EX_TRANSACTION_FAILED)));

    // callback 1 should be unregistered and callback 2 unregistration should fail due to service
    // listener unregistration call failure
    ASSERT_EQ(0,
              AThermal_unregisterThermalHeadroomListener(mThermalManager, onHeadroomChange1,
                                                         &data1));
    ASSERT_EQ(EPIPE,
              AThermal_unregisterThermalHeadroomListener(mThermalManager, onHeadroomChange2,
                                                         &data2));
    // verify only callback 2 is called after callback 1 is unregistered
    std::vector<float> thresholds = {0.1f, 0.2f};
    headroomCalled1.reset();
    headroomCalled2.reset();
    capturedServiceListener->onHeadroomChange(0.1f, 0.3f, 20, thresholds);
    ASSERT_TRUE(!headroomCalled1.has_value());
    ASSERT_TRUE(headroomCalled2.has_value());

    // verify only 1 service call to unregister global service listener
    Mock::VerifyAndClearExpectations(mMockIThermalService);
    EXPECT_CALL(*mMockIThermalService, unregisterThermalHeadroomListener(_, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(testing::Invoke([](const sp<IThermalHeadroomListener>&,
                                               bool* aidl_return) { *aidl_return = true; }),
                            Return(Status::ok())));
    ASSERT_EQ(EINVAL,
              AThermal_unregisterThermalHeadroomListener(mThermalManager, onHeadroomChange1,
                                                         &data1));
    ASSERT_EQ(0,
              AThermal_unregisterThermalHeadroomListener(mThermalManager, onHeadroomChange2,
                                                         &data2));
    // verify neither callback is called after global service listener is unregistered
    headroomCalled1.reset();
    headroomCalled2.reset();
    capturedServiceListener->onHeadroomChange(0.1f, 0.3f, 20, thresholds);
    ASSERT_TRUE(!headroomCalled1.has_value());
    ASSERT_TRUE(!headroomCalled2.has_value());

    // verify adding a new callback will still work
    Mock::VerifyAndClearExpectations(mMockIThermalService);
    EXPECT_CALL(*mMockIThermalService, registerThermalHeadroomListener(_, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(testing::SaveArg<0>(&capturedServiceListener),
                            testing::Invoke([](const sp<IThermalHeadroomListener>&,
                                               bool* aidl_return) { *aidl_return = true; }),
                            Return(Status::ok())));
    ASSERT_EQ(0,
              AThermal_registerThermalHeadroomListener(mThermalManager, onHeadroomChange1, &data1));
    headroomCalled1.reset();
    capturedServiceListener->onHeadroomChange(0.1f, 0.3f, 20, thresholds);
    ASSERT_TRUE(headroomCalled1.has_value());
    EXPECT_EQ(headroomCalled1->data, &data1);
    EXPECT_EQ(headroomCalled1->headroom, 0.1f);
    EXPECT_EQ(headroomCalled1->forecast, 0.3f);
    EXPECT_EQ(headroomCalled1->forecastSeconds, 20);
    EXPECT_EQ(headroomCalled1->thresholds, thresholds);

    // after test finished the global service listener should be unregistered
    EXPECT_CALL(*mMockIThermalService, unregisterThermalHeadroomListener(_, _))
            .Times(Exactly(1))
            .WillOnce(Return(binder::Status::ok()));
}

TEST_F(NativeThermalUnitTest, TestRegisterThermalStatusListener) {
    EXPECT_CALL(*mMockIThermalService, registerThermalStatusListener(_, _))
            .Times(Exactly(2))
            .WillOnce(Return(
                    Status::fromExceptionCode(binder::Status::Exception::EX_TRANSACTION_FAILED)));
    int data1 = 1;
    int data2 = 2;
    ASSERT_EQ(EPIPE,
              AThermal_registerThermalStatusListener(mThermalManager, onStatusChange1, &data1));
    ASSERT_EQ(EPIPE,
              AThermal_registerThermalStatusListener(mThermalManager, onStatusChange2, &data2));

    // verify only 1 service call to register a global listener
    sp<IThermalStatusListener> capturedServiceListener;
    Mock::VerifyAndClearExpectations(mMockIThermalService);
    EXPECT_CALL(*mMockIThermalService, registerThermalStatusListener(_, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(testing::SaveArg<0>(&capturedServiceListener),
                            testing::Invoke([](const sp<IThermalStatusListener>&,
                                               bool* aidl_return) { *aidl_return = true; }),
                            Return(Status::ok())));
    ASSERT_EQ(0, AThermal_registerThermalStatusListener(mThermalManager, onStatusChange1, &data1));
    ASSERT_EQ(EINVAL,
              AThermal_registerThermalStatusListener(mThermalManager, onStatusChange1, &data1));
    ASSERT_EQ(0, AThermal_registerThermalStatusListener(mThermalManager, onStatusChange2, &data2));

    capturedServiceListener->onStatusChange(AThermalStatus::ATHERMAL_STATUS_LIGHT);
    ASSERT_TRUE(statusCalled1.has_value());
    EXPECT_EQ(statusCalled1->data, &data1);
    EXPECT_EQ(statusCalled1->status, AThermalStatus::ATHERMAL_STATUS_LIGHT);
    ASSERT_TRUE(statusCalled2.has_value());
    EXPECT_EQ(statusCalled2->data, &data2);
    EXPECT_EQ(statusCalled2->status, AThermalStatus::ATHERMAL_STATUS_LIGHT);

    // after test finished the callback should be unregistered
    EXPECT_CALL(*mMockIThermalService, unregisterThermalStatusListener(_, _))
            .Times(Exactly(1))
            .WillOnce(Return(binder::Status::ok()));
}

TEST_F(NativeThermalUnitTest, TestUnregisterThermalStatusListener) {
    sp<IThermalStatusListener> capturedServiceListener;
    EXPECT_CALL(*mMockIThermalService, registerThermalStatusListener(_, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(testing::SaveArg<0>(&capturedServiceListener),
                            testing::Invoke([](const sp<IThermalStatusListener>&,
                                               bool* aidl_return) { *aidl_return = true; }),
                            Return(Status::ok())));
    int data1 = 1;
    int data2 = 2;
    ASSERT_EQ(0, AThermal_registerThermalStatusListener(mThermalManager, onStatusChange1, &data1));
    ASSERT_EQ(0, AThermal_registerThermalStatusListener(mThermalManager, onStatusChange2, &data2));
    capturedServiceListener->onStatusChange(AThermalStatus::ATHERMAL_STATUS_LIGHT);
    ASSERT_TRUE(statusCalled1.has_value());
    ASSERT_TRUE(statusCalled2.has_value());

    EXPECT_CALL(*mMockIThermalService, unregisterThermalStatusListener(_, _))
            .Times(Exactly(1))
            .WillOnce(Return(
                    Status::fromExceptionCode(binder::Status::Exception::EX_TRANSACTION_FAILED)));
    // callback 1 should be unregistered and callback 2 unregistration should fail due to service
    // listener unregistration call failure
    ASSERT_EQ(0,
              AThermal_unregisterThermalStatusListener(mThermalManager, onStatusChange1, &data1));
    ASSERT_EQ(EPIPE,
              AThermal_unregisterThermalStatusListener(mThermalManager, onStatusChange2, &data2));

    // verify only callback 2 is called after callback 1 is unregistered
    statusCalled1.reset();
    statusCalled2.reset();
    capturedServiceListener->onStatusChange(AThermalStatus::ATHERMAL_STATUS_LIGHT);
    ASSERT_TRUE(!statusCalled1.has_value());
    ASSERT_TRUE(statusCalled2.has_value());

    // verify only 1 service call to unregister global service listener
    Mock::VerifyAndClearExpectations(mMockIThermalService);
    EXPECT_CALL(*mMockIThermalService, unregisterThermalStatusListener(_, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(testing::Invoke([](const sp<IThermalStatusListener>&,
                                               bool* aidl_return) { *aidl_return = true; }),
                            Return(Status::ok())));
    ASSERT_EQ(EINVAL,
              AThermal_unregisterThermalStatusListener(mThermalManager, onStatusChange1, &data1));
    ASSERT_EQ(0,
              AThermal_unregisterThermalStatusListener(mThermalManager, onStatusChange2, &data2));
    // verify neither callback is called after global service listener is unregistered
    statusCalled1.reset();
    statusCalled2.reset();
    capturedServiceListener->onStatusChange(AThermalStatus::ATHERMAL_STATUS_LIGHT);
    ASSERT_TRUE(!statusCalled1.has_value());
    ASSERT_TRUE(!statusCalled2.has_value());

    // verify adding a new callback will still work
    Mock::VerifyAndClearExpectations(mMockIThermalService);
    EXPECT_CALL(*mMockIThermalService, registerThermalStatusListener(_, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(testing::SaveArg<0>(&capturedServiceListener),
                            testing::Invoke([](const sp<IThermalStatusListener>&,
                                               bool* aidl_return) { *aidl_return = true; }),
                            Return(Status::ok())));
    ASSERT_EQ(0, AThermal_registerThermalStatusListener(mThermalManager, onStatusChange1, &data1));
    statusCalled1.reset();
    capturedServiceListener->onStatusChange(AThermalStatus::ATHERMAL_STATUS_LIGHT);
    ASSERT_TRUE(statusCalled1.has_value());
    EXPECT_EQ(statusCalled1->data, &data1);
    EXPECT_EQ(statusCalled1->status, AThermalStatus::ATHERMAL_STATUS_LIGHT);

    // after test finished the global service listener should be unregistered
    EXPECT_CALL(*mMockIThermalService, unregisterThermalStatusListener(_, _))
            .Times(Exactly(1))
            .WillOnce(Return(binder::Status::ok()));
}
