/*
 * Copyright (C) 2024 The Android Open Source Project
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

#define LOG_TAG "NativeSystemHealthUnitTest"

#include <aidl/android/os/IHintManager.h>
#include <android/binder_manager.h>
#include <android/binder_status.h>
#include <android/system_health.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <system_health_private.h>

#include <memory>
#include <optional>
#include <vector>

using namespace std::chrono_literals;
namespace hal = aidl::android::hardware::power;
using aidl::android::os::CpuHeadroomParamsInternal;
using aidl::android::os::GpuHeadroomParamsInternal;
using aidl::android::os::IHintManager;
using aidl::android::os::IHintSession;
using aidl::android::os::SessionCreationConfig;
using ndk::ScopedAStatus;
using ndk::SpAIBinder;

using namespace android;
using namespace testing;

class MockIHintManager : public IHintManager {
public:
    MOCK_METHOD(ScopedAStatus, createHintSessionWithConfig,
                (const SpAIBinder& token, hal::SessionTag tag,
                 const SessionCreationConfig& creationConfig, hal::SessionConfig* config,
                 IHintManager::SessionCreationReturn* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, setHintSessionThreads,
                (const std::shared_ptr<IHintSession>& _, const ::std::vector<int32_t>& tids),
                (override));
    MOCK_METHOD(ScopedAStatus, getHintSessionThreadIds,
                (const std::shared_ptr<IHintSession>& _, ::std::vector<int32_t>* tids), (override));
    MOCK_METHOD(ScopedAStatus, getSessionChannel,
                (const ::ndk::SpAIBinder& in_token,
                 std::optional<hal::ChannelConfig>* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, closeSessionChannel, (), (override));
    MOCK_METHOD(ScopedAStatus, getCpuHeadroom,
                (const CpuHeadroomParamsInternal& _,
                 std::optional<hal::CpuHeadroomResult>* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, getCpuHeadroomMinIntervalMillis, (int64_t*), (override));
    MOCK_METHOD(ScopedAStatus, getGpuHeadroom,
                (const GpuHeadroomParamsInternal& _,
                 std::optional<hal::GpuHeadroomResult>* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, getGpuHeadroomMinIntervalMillis, (int64_t* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, passSessionManagerBinder, (const SpAIBinder& sessionManager));
    MOCK_METHOD(ScopedAStatus, registerClient,
                (const std::shared_ptr<aidl::android::os::IHintManager::IHintManagerClient>& _,
                 aidl::android::os::IHintManager::HintManagerClientData* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, getClientData,
                (aidl::android::os::IHintManager::HintManagerClientData * _aidl_return),
                (override));
    MOCK_METHOD(SpAIBinder, asBinder, (), (override));
    MOCK_METHOD(bool, isRemote, (), (override));
};

class NativeSystemHealthUnitTest : public Test {
public:
    void SetUp() override {
        mMockIHintManager = ndk::SharedRefBase::make<NiceMock<MockIHintManager>>();
        ASystemHealth_setIHintManagerForTesting(&mMockIHintManager);
        ON_CALL(*mMockIHintManager, getClientData(_))
                .WillByDefault(
                        DoAll(SetArgPointee<0>(mClientData), [] { return ScopedAStatus::ok(); }));
    }

    void TearDown() override {
        ASystemHealth_setIHintManagerForTesting(nullptr);
    }

    IHintManager::HintManagerClientData mClientData{
            .powerHalVersion = 6,
            .maxCpuHeadroomThreads = 10,
            .supportInfo{.headroom{
                    .isCpuSupported = true,
                    .isGpuSupported = true,
                    .cpuMinIntervalMillis = 999,
                    .gpuMinIntervalMillis = 998,
                    .cpuMinCalculationWindowMillis = 45,
                    .cpuMaxCalculationWindowMillis = 9999,
                    .gpuMinCalculationWindowMillis = 46,
                    .gpuMaxCalculationWindowMillis = 9998,
            }},
    };

    std::shared_ptr<NiceMock<MockIHintManager>> mMockIHintManager = nullptr;
};

TEST_F(NativeSystemHealthUnitTest, headroomParamsValueRange) {
    int64_t minIntervalMillis = 0;
    int minCalculationWindowMillis = 0;
    int maxCalculationWindowMillis = 0;
    ASSERT_EQ(OK, ASystemHealth_getCpuHeadroomMinIntervalMillis(&minIntervalMillis));
    ASSERT_EQ(OK,
              ASystemHealth_getCpuHeadroomCalculationWindowRange(&minCalculationWindowMillis,
                                                                 &maxCalculationWindowMillis));
    ASSERT_EQ(minIntervalMillis, mClientData.supportInfo.headroom.cpuMinIntervalMillis);
    ASSERT_EQ(minCalculationWindowMillis,
              mClientData.supportInfo.headroom.cpuMinCalculationWindowMillis);
    ASSERT_EQ(maxCalculationWindowMillis,
              mClientData.supportInfo.headroom.cpuMaxCalculationWindowMillis);

    ASSERT_EQ(OK, ASystemHealth_getGpuHeadroomMinIntervalMillis(&minIntervalMillis));
    ASSERT_EQ(OK,
              ASystemHealth_getGpuHeadroomCalculationWindowRange(&minCalculationWindowMillis,
                                                                 &maxCalculationWindowMillis));
    ASSERT_EQ(minIntervalMillis, mClientData.supportInfo.headroom.gpuMinIntervalMillis);
    ASSERT_EQ(minCalculationWindowMillis,
              mClientData.supportInfo.headroom.gpuMinCalculationWindowMillis);
    ASSERT_EQ(maxCalculationWindowMillis,
              mClientData.supportInfo.headroom.gpuMaxCalculationWindowMillis);
}

TEST_F(NativeSystemHealthUnitTest, getCpuHeadroom) {
    CpuHeadroomParamsInternal internalParams1;
    ACpuHeadroomParams* params2 = ACpuHeadroomParams_create();
    ACpuHeadroomParams_setCalculationWindowMillis(params2, 200);
    CpuHeadroomParamsInternal internalParams2;
    internalParams2.calculationWindowMillis = 200;
    ACpuHeadroomParams* params3 = ACpuHeadroomParams_create();
    ACpuHeadroomParams_setCalculationType(params3, ACPU_HEADROOM_CALCULATION_TYPE_AVERAGE);
    CpuHeadroomParamsInternal internalParams3;
    internalParams3.calculationType = hal::CpuHeadroomParams::CalculationType::AVERAGE;
    ACpuHeadroomParams* params4 = ACpuHeadroomParams_create();
    int tids[3] = {1, 2, 3};
    ACpuHeadroomParams_setTids(params4, tids, 3);
    CpuHeadroomParamsInternal internalParams4;
    internalParams4.tids = {1, 2, 3};

    EXPECT_CALL(*mMockIHintManager, getCpuHeadroom(internalParams1, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(SetArgPointee<1>(hal::CpuHeadroomResult::make<
                                             hal::CpuHeadroomResult::globalHeadroom>(1.0f)),
                            [] { return ScopedAStatus::ok(); }));
    EXPECT_CALL(*mMockIHintManager, getCpuHeadroom(internalParams2, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(SetArgPointee<1>(hal::CpuHeadroomResult::make<
                                             hal::CpuHeadroomResult::globalHeadroom>(2.0f)),
                            [] { return ScopedAStatus::ok(); }));
    EXPECT_CALL(*mMockIHintManager, getCpuHeadroom(internalParams3, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(SetArgPointee<1>(std::nullopt), [] { return ScopedAStatus::ok(); }));
    EXPECT_CALL(*mMockIHintManager, getCpuHeadroom(internalParams4, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(SetArgPointee<1>(hal::CpuHeadroomResult::make<
                                             hal::CpuHeadroomResult::globalHeadroom>(4.0f)),
                            [] { return ScopedAStatus::ok(); }));

    float headroom1 = 0.0f;
    float headroom2 = 0.0f;
    float headroom3 = 0.0f;
    float headroom4 = 0.0f;
    ASSERT_EQ(OK, ASystemHealth_getCpuHeadroom(nullptr, &headroom1));
    ASSERT_EQ(OK, ASystemHealth_getCpuHeadroom(params2, &headroom2));
    ASSERT_EQ(OK, ASystemHealth_getCpuHeadroom(params3, &headroom3));
    ASSERT_EQ(OK, ASystemHealth_getCpuHeadroom(params4, &headroom4));
    ASSERT_EQ(1.0f, headroom1);
    ASSERT_EQ(2.0f, headroom2);
    ASSERT_TRUE(isnan(headroom3));
    ASSERT_EQ(4.0f, headroom4);

    ACpuHeadroomParams_destroy(params2);
    ACpuHeadroomParams_destroy(params3);
    ACpuHeadroomParams_destroy(params4);
}

TEST_F(NativeSystemHealthUnitTest, getGpuHeadroom) {
    GpuHeadroomParamsInternal internalParams1;
    AGpuHeadroomParams* params2 = AGpuHeadroomParams_create();
    AGpuHeadroomParams_setCalculationWindowMillis(params2, 200);
    GpuHeadroomParamsInternal internalParams2;
    internalParams2.calculationWindowMillis = 200;
    AGpuHeadroomParams* params3 = AGpuHeadroomParams_create();
    AGpuHeadroomParams_setCalculationType(params3, AGPU_HEADROOM_CALCULATION_TYPE_AVERAGE);
    GpuHeadroomParamsInternal internalParams3;
    internalParams3.calculationType = hal::GpuHeadroomParams::CalculationType::AVERAGE;

    EXPECT_CALL(*mMockIHintManager, getGpuHeadroom(internalParams1, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(SetArgPointee<1>(hal::GpuHeadroomResult::make<
                                             hal::GpuHeadroomResult::globalHeadroom>(1.0f)),
                            [] { return ScopedAStatus::ok(); }));
    EXPECT_CALL(*mMockIHintManager, getGpuHeadroom(internalParams2, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(SetArgPointee<1>(hal::GpuHeadroomResult::make<
                                             hal::GpuHeadroomResult::globalHeadroom>(2.0f)),
                            [] { return ScopedAStatus::ok(); }));
    EXPECT_CALL(*mMockIHintManager, getGpuHeadroom(internalParams3, _))
            .Times(Exactly(1))
            .WillOnce(DoAll(SetArgPointee<1>(std::nullopt), [] { return ScopedAStatus::ok(); }));

    float headroom1 = 0.0f;
    float headroom2 = 0.0f;
    float headroom3 = 0.0f;
    ASSERT_EQ(OK, ASystemHealth_getGpuHeadroom(nullptr, &headroom1));
    ASSERT_EQ(OK, ASystemHealth_getGpuHeadroom(params2, &headroom2));
    ASSERT_EQ(OK, ASystemHealth_getGpuHeadroom(params3, &headroom3));
    ASSERT_EQ(1.0f, headroom1);
    ASSERT_EQ(2.0f, headroom2);
    ASSERT_TRUE(isnan(headroom3));

    AGpuHeadroomParams_destroy(params2);
    AGpuHeadroomParams_destroy(params3);
}
