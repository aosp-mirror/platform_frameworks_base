/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <aidl/android/se/omapi/BnSecureElementListener.h>
#include <aidl/android/se/omapi/ISecureElementChannel.h>
#include <aidl/android/se/omapi/ISecureElementListener.h>
#include <aidl/android/se/omapi/ISecureElementReader.h>
#include <aidl/android/se/omapi/ISecureElementService.h>
#include <aidl/android/se/omapi/ISecureElementSession.h>

#include <VtsCoreUtil.h>
#include <aidl/Gtest.h>
#include <aidl/Vintf.h>
#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <binder/IServiceManager.h>
#include <cutils/properties.h>
#include <gtest/gtest.h>
#include <hidl/GtestPrinter.h>
#include <hidl/ServiceManagement.h>
#include <utils/String16.h>
#include "utility/ValidateXml.h"

using namespace std;
using namespace ::testing;
using namespace android;

int main(int argc, char** argv) {
    InitGoogleTest(&argc, argv);
    int status = RUN_ALL_TESTS();
    return status;
}

namespace {

class OMAPISEServiceHalTest : public TestWithParam<std::string> {
   protected:
    class SEListener : public ::aidl::android::se::omapi::BnSecureElementListener {};

    void testSelectableAid(
        std::shared_ptr<aidl::android::se::omapi::ISecureElementReader> reader,
        std::vector<uint8_t> aid, std::vector<uint8_t>& selectResponse) {
        std::shared_ptr<aidl::android::se::omapi::ISecureElementSession> session;
        std::shared_ptr<aidl::android::se::omapi::ISecureElementChannel> channel;
        auto seListener = ndk::SharedRefBase::make<::OMAPISEServiceHalTest::SEListener>();

        ASSERT_NE(reader, nullptr) << "reader is null";

        bool status = false;
        auto res = reader->isSecureElementPresent(&status);
        ASSERT_TRUE(res.isOk()) << res.getMessage();
        ASSERT_TRUE(status);

        res = reader->openSession(&session);
        ASSERT_TRUE(res.isOk()) << res.getMessage();
        ASSERT_NE(session, nullptr) << "Could not open session";

        res = session->openLogicalChannel(aid, 0x00, seListener, &channel);
        ASSERT_TRUE(res.isOk()) << res.getMessage();
        ASSERT_NE(channel, nullptr) << "Could not open channel";

        res = channel->getSelectResponse(&selectResponse);
        ASSERT_TRUE(res.isOk()) << "failed to get Select Response";
        ASSERT_GE(selectResponse.size(), 2);

        if (channel != nullptr) channel->close();
        if (session != nullptr) session->close();

        ASSERT_EQ((selectResponse[selectResponse.size() - 1] & 0xFF), (0x00));
        ASSERT_EQ((selectResponse[selectResponse.size() - 2] & 0xFF), (0x90));
    }

    void testNonSelectableAid(
        std::shared_ptr<aidl::android::se::omapi::ISecureElementReader> reader,
        std::vector<uint8_t> aid) {
        std::shared_ptr<aidl::android::se::omapi::ISecureElementSession> session;
        std::shared_ptr<aidl::android::se::omapi::ISecureElementChannel> channel;
        auto seListener = ndk::SharedRefBase::make<::OMAPISEServiceHalTest::SEListener>();

        ASSERT_NE(reader, nullptr) << "reader is null";

        bool status = false;
        auto res = reader->isSecureElementPresent(&status);
        ASSERT_TRUE(res.isOk()) << res.getMessage();
        ASSERT_TRUE(status);

        res = reader->openSession(&session);
        ASSERT_TRUE(res.isOk()) << res.getMessage();
        ASSERT_NE(session, nullptr) << "Could not open session";

        res = session->openLogicalChannel(aid, 0x00, seListener, &channel);
        if (channel != nullptr) channel->close();
        if (session != nullptr) session->close();

        LOG(ERROR) << res.getMessage();
        ASSERT_FALSE(res.isOk()) << "expected to fail to open channel for this test";
    }

    /**
     * Verifies TLV data
     *
     * @return true if the data is tlv formatted, false otherwise
     */
    bool verifyBerTlvData(std::vector<uint8_t> tlv) {
        if (tlv.size() == 0) {
            LOG(ERROR) << "Invalid tlv, null";
            return false;
        }
        int i = 0;
        if ((tlv[i++] & 0x1F) == 0x1F) {
            // extra byte for TAG field
            i++;
        }

        int len = tlv[i++] & 0xFF;
        if (len > 127) {
            // more than 1 byte for length
            int bytesLength = len - 128;
            len = 0;
            for (int j = bytesLength; j > 0; j--) {
                len += (len << 8) + (tlv[i++] & 0xFF);
            }
        }
        // Additional 2 bytes for the SW
        return (tlv.size() == (i + len + 2));
    }

    void internalTransmitApdu(
        std::shared_ptr<aidl::android::se::omapi::ISecureElementReader> reader,
        std::vector<uint8_t> apdu, std::vector<uint8_t>& transmitResponse) {
        std::shared_ptr<aidl::android::se::omapi::ISecureElementSession> session;
        std::shared_ptr<aidl::android::se::omapi::ISecureElementChannel> channel;
        auto seListener = ndk::SharedRefBase::make<::OMAPISEServiceHalTest::SEListener>();
        std::vector<uint8_t> selectResponse = {};

        ASSERT_NE(reader, nullptr) << "reader is null";

        bool status = false;
        auto res = reader->isSecureElementPresent(&status);
        ASSERT_TRUE(res.isOk()) << res.getMessage();
        ASSERT_TRUE(status);

        res = reader->openSession(&session);
        ASSERT_TRUE(res.isOk()) << res.getMessage();
        ASSERT_NE(session, nullptr) << "Could not open session";

        res = session->openLogicalChannel(SELECTABLE_AID, 0x00, seListener, &channel);
        ASSERT_TRUE(res.isOk()) << res.getMessage();
        ASSERT_NE(channel, nullptr) << "Could not open channel";

        res = channel->getSelectResponse(&selectResponse);
        ASSERT_TRUE(res.isOk()) << "failed to get Select Response";
        ASSERT_GE(selectResponse.size(), 2);

        res = channel->transmit(apdu, &transmitResponse);
        if (channel != nullptr) channel->close();
        if (session != nullptr) session->close();
        LOG(INFO) << "STATUS OF TRNSMIT: " << res.getExceptionCode()
                  << " Message: " << res.getMessage();
        ASSERT_TRUE(res.isOk()) << "failed to transmit";
    }

    bool supportOMAPIReaders() {
        return (deviceSupportsFeature(FEATURE_SE_OMAPI_ESE.c_str()));
    }

    std::optional<std::string> getUuidMappingFile() {
        char value[PROPERTY_VALUE_MAX] = {0};
        int len = property_get("ro.boot.product.hardware.sku", value, "config");
        std::string uuidMappingConfigFile = UUID_MAPPING_CONFIG_PREFIX
                + std::string(value, len)
                + UUID_MAPPING_CONFIG_EXT;
        std::string uuidMapConfigPath;
        // Search in predefined folders
        for (auto path : UUID_MAPPING_CONFIG_PATHS) {
            uuidMapConfigPath = path + uuidMappingConfigFile;
            auto confFile = fopen(uuidMapConfigPath.c_str(), "r");
            if (confFile) {
                fclose(confFile);
                return uuidMapConfigPath;
            }
        }
        return std::optional<std::string>();
    }

    void SetUp() override {
        LOG(INFO) << "get OMAPI service with name:" << GetParam();
        ::ndk::SpAIBinder ks2Binder(AServiceManager_getService(GetParam().c_str()));
        mOmapiSeService = aidl::android::se::omapi::ISecureElementService::fromBinder(ks2Binder);
        ASSERT_TRUE(mOmapiSeService);

        std::vector<std::string> readers = {};

        if (omapiSecureService() != NULL) {
            auto status = omapiSecureService()->getReaders(&readers);
            ASSERT_TRUE(status.isOk()) << status.getMessage();

            for (auto readerName : readers) {
                // Filter eSE readers only
                if (readerName.find(ESE_READER_PREFIX, 0) != std::string::npos) {
                    std::shared_ptr<::aidl::android::se::omapi::ISecureElementReader> reader;
                    status = omapiSecureService()->getReader(readerName, &reader);
                    ASSERT_TRUE(status.isOk()) << status.getMessage();

                    mVSReaders[readerName] = reader;
                }
            }
        }
    }

    void TearDown() override {
        if (mOmapiSeService != nullptr) {
            if (mVSReaders.size() > 0) {
                for (const auto& [name, reader] : mVSReaders) {
                    reader->closeSessions();
                }
            }
        }
    }

    bool isDebuggableBuild() {
        char value[PROPERTY_VALUE_MAX] = {0};
        property_get("ro.system.build.type", value, "");
        if (strcmp(value, "userdebug") == 0) {
            return true;
        }
        if (strcmp(value, "eng") == 0) {
            return true;
        }
        return false;
    }

    std::shared_ptr<aidl::android::se::omapi::ISecureElementService> omapiSecureService() {
        return mOmapiSeService;
    }

    static inline std::string const ESE_READER_PREFIX = "eSE";
    static inline std::string const FEATURE_SE_OMAPI_ESE = "android.hardware.se.omapi.ese";

    std::vector<uint8_t> SELECTABLE_AID = {0xA0, 0x00, 0x00, 0x04, 0x76, 0x41, 0x6E, 0x64,
                                           0x72, 0x6F, 0x69, 0x64, 0x43, 0x54, 0x53, 0x31};
    std::vector<uint8_t> LONG_SELECT_RESPONSE_AID = {0xA0, 0x00, 0x00, 0x04, 0x76, 0x41,
                                                     0x6E, 0x64, 0x72, 0x6F, 0x69, 0x64,
                                                     0x43, 0x54, 0x53, 0x32};
    std::vector<uint8_t> NON_SELECTABLE_AID = {0xA0, 0x00, 0x00, 0x04, 0x76, 0x41, 0x6E, 0x64,
                                               0x72, 0x6F, 0x69, 0x64, 0x43, 0x54, 0x53, 0xFF};

    std::vector<std::vector<uint8_t>> ILLEGAL_COMMANDS_TRANSMIT = {
        {0x00, 0x70, 0x00, 0x00},
        {0x00, 0x70, 0x80, 0x00},
        {0x00, 0xA4, 0x04, 0x04, 0x10, 0x4A, 0x53, 0x52, 0x31, 0x37, 0x37,
         0x54, 0x65, 0x73, 0x74, 0x65, 0x72, 0x20, 0x31, 0x2E, 0x30}};

    /* OMAPI APDU Test case 1 and 3 */
    std::vector<std::vector<uint8_t>> NO_DATA_APDU = {{0x00, 0x06, 0x00, 0x00},
                                                      {0x80, 0x06, 0x00, 0x00},
                                                      {0xA0, 0x06, 0x00, 0x00},
                                                      {0x94, 0x06, 0x00, 0x00},
                                                      {0x00, 0x0A, 0x00, 0x00, 0x01, 0xAA},
                                                      {0x80, 0x0A, 0x00, 0x00, 0x01, 0xAA},
                                                      {0xA0, 0x0A, 0x00, 0x00, 0x01, 0xAA},
                                                      {0x94, 0x0A, 0x00, 0x00, 0x01, 0xAA}};

    /* OMAPI APDU Test case 2 and 4 */
    std::vector<std::vector<uint8_t>> DATA_APDU = {{0x00, 0x08, 0x00, 0x00, 0x00},
                                                   {0x80, 0x08, 0x00, 0x00, 0x00},
                                                   {0xA0, 0x08, 0x00, 0x00, 0x00},
                                                   {0x94, 0x08, 0x00, 0x00, 0x00},
                                                   {0x00, 0x0C, 0x00, 0x00, 0x01, 0xAA, 0x00},
                                                   {0x80, 0x0C, 0x00, 0x00, 0x01, 0xAA, 0x00},
                                                   {0xA0, 0x0C, 0x00, 0x00, 0x01, 0xAA, 0x00},
                                                   {0x94, 0x0C, 0x00, 0x00, 0x01, 0xAA, 0x00}};

    /* Case 2 APDU command expects the P2 received in the SELECT command as 1-byte outgoing data */
    std::vector<uint8_t> CHECK_SELECT_P2_APDU = {0x00, 0xF4, 0x00, 0x00, 0x00};

    /* OMAPI APDU Test case 1 and 3 */
    std::vector<std::vector<uint8_t>> SW_62xx_NO_DATA_APDU = {{0x00, 0xF3, 0x00, 0x06},
                                                              {0x00, 0xF3, 0x00, 0x0A, 0x01, 0xAA}};

    /* OMAPI APDU Test case 2 and 4 */
    std::vector<uint8_t> SW_62xx_DATA_APDU = {0x00, 0xF3, 0x00, 0x08, 0x00};
    std::vector<uint8_t> SW_62xx_VALIDATE_DATA_APDU = {0x00, 0xF3, 0x00, 0x0C, 0x01, 0xAA, 0x00};
    std::vector<std::vector<uint8_t>> SW_62xx = {
        {0x62, 0x00}, {0x62, 0x81}, {0x62, 0x82}, {0x62, 0x83}, {0x62, 0x85}, {0x62, 0xF1},
        {0x62, 0xF2}, {0x63, 0xF1}, {0x63, 0xF2}, {0x63, 0xC2}, {0x62, 0x02}, {0x62, 0x80},
        {0x62, 0x84}, {0x62, 0x86}, {0x63, 0x00}, {0x63, 0x81}};

    std::vector<std::vector<uint8_t>> SEGMENTED_RESP_APDU = {
        // Get response Case2 61FF+61XX with answer length (P1P2) of 0x0800, 2048 bytes
        {0x00, 0xC2, 0x08, 0x00, 0x00},
        // Get response Case4 61FF+61XX with answer length (P1P2) of 0x0800, 2048 bytes
        {0x00, 0xC4, 0x08, 0x00, 0x02, 0x12, 0x34, 0x00},
        // Get response Case2 6100+61XX with answer length (P1P2) of 0x0800, 2048 bytes
        {0x00, 0xC6, 0x08, 0x00, 0x00},
        // Get response Case4 6100+61XX with answer length (P1P2) of 0x0800, 2048 bytes
        {0x00, 0xC8, 0x08, 0x00, 0x02, 0x12, 0x34, 0x00},
        // Test device buffer capacity 7FFF data
        {0x00, 0xC2, 0x7F, 0xFF, 0x00},
        // Get response 6CFF+61XX with answer length (P1P2) of 0x0800, 2048 bytes
        {0x00, 0xCF, 0x08, 0x00, 0x00},
        // Get response with another CLA  with answer length (P1P2) of 0x0800, 2048 bytes
        {0x94, 0xC2, 0x08, 0x00, 0x00}};
    long SERVICE_CONNECTION_TIME_OUT = 3000;

    std::shared_ptr<aidl::android::se::omapi::ISecureElementService> mOmapiSeService;

    std::map<std::string, std::shared_ptr<aidl::android::se::omapi::ISecureElementReader>>
        mVSReaders = {};

    std::string UUID_MAPPING_CONFIG_PREFIX = "hal_uuid_map_";
    std::string UUID_MAPPING_CONFIG_EXT = ".xml";
    std::string UUID_MAPPING_CONFIG_PATHS[3] = {"/odm/etc/", "/vendor/etc/", "/etc/"};
};

/** Tests getReaders API */
TEST_P(OMAPISEServiceHalTest, TestGetReaders) {
    std::vector<std::shared_ptr<aidl::android::se::omapi::ISecureElementReader>> eseReaders =
        {};

    for (const auto& [name, reader] : mVSReaders) {
        bool status = false;
        LOG(INFO) << "Name of the reader: " << name;

        if (reader) {
            auto res = reader->isSecureElementPresent(&status);
            ASSERT_TRUE(res.isOk()) << res.getMessage();
        }
        ASSERT_TRUE(status);

        if (name.find(ESE_READER_PREFIX) == std::string::npos) {
            LOG(ERROR) << "Incorrect Reader name";
            FAIL();
        }

        if (name.find(ESE_READER_PREFIX, 0) != std::string::npos) {
            eseReaders.push_back(reader);
        } else {
            LOG(INFO) << "Reader not supported: " << name;
            FAIL();
        }
    }

    if (deviceSupportsFeature(FEATURE_SE_OMAPI_ESE.c_str())) {
        ASSERT_GE(eseReaders.size(), 1);
    } else {
        ASSERT_TRUE(eseReaders.size() == 0);
    }
}

/** Tests OpenBasicChannel API when aid is null */
TEST_P(OMAPISEServiceHalTest, TestOpenBasicChannelNullAid) {
    ASSERT_TRUE(supportOMAPIReaders() == true);
    std::vector<uint8_t> aid = {};
    auto seListener = ndk::SharedRefBase::make<::OMAPISEServiceHalTest::SEListener>();

    if (mVSReaders.size() > 0) {
        for (const auto& [name, reader] : mVSReaders) {
            std::shared_ptr<aidl::android::se::omapi::ISecureElementSession> session;
            std::shared_ptr<aidl::android::se::omapi::ISecureElementChannel> channel;
            bool result = false;

            auto status = reader->openSession(&session);
            ASSERT_TRUE(status.isOk()) << status.getMessage();
            if (!session) {
                LOG(ERROR) << "Could not open session";
                FAIL();
            }

            status = session->openBasicChannel(aid, 0x00, seListener, &channel);
            ASSERT_TRUE(status.isOk()) << status.getMessage();

            if (channel != nullptr) channel->close();
            if (session != nullptr) session->close();

            if (channel != nullptr) {
                status = channel->isBasicChannel(&result);
                ASSERT_TRUE(status.isOk()) << "Basic Channel cannot be opened";
            }
        }
    }
}

/** Tests OpenBasicChannel API when aid is provided */
TEST_P(OMAPISEServiceHalTest, TestOpenBasicChannelNonNullAid) {
    ASSERT_TRUE(supportOMAPIReaders() == true);
    auto seListener = ndk::SharedRefBase::make<::OMAPISEServiceHalTest::SEListener>();

    if (mVSReaders.size() > 0) {
        for (const auto& [name, reader] : mVSReaders) {
            std::shared_ptr<aidl::android::se::omapi::ISecureElementSession> session;
            std::shared_ptr<aidl::android::se::omapi::ISecureElementChannel> channel;
            bool result = false;

            auto status = reader->openSession(&session);
            ASSERT_TRUE(status.isOk()) << status.getMessage();
            if (!session) {
                LOG(ERROR) << "Could not open session";
                FAIL();
            }

            status = session->openBasicChannel(SELECTABLE_AID, 0x00, seListener, &channel);
            ASSERT_TRUE(status.isOk()) << status.getMessage();

            if (channel != nullptr) channel->close();
            if (session != nullptr) session->close();

            if (channel != nullptr) {
                status = channel->isBasicChannel(&result);
                ASSERT_TRUE(status.isOk()) << "Basic Channel cannot be opened";
            }
        }
    }
}

/** Tests Select API */
TEST_P(OMAPISEServiceHalTest, TestSelectableAid) {
    ASSERT_TRUE(supportOMAPIReaders() == true);
    if (mVSReaders.size() > 0) {
        for (const auto& [name, reader] : mVSReaders) {
            std::vector<uint8_t> selectResponse = {};
            testSelectableAid(reader, SELECTABLE_AID, selectResponse);
        }
    }
}

/** Tests Select API */
TEST_P(OMAPISEServiceHalTest, TestLongSelectResponse) {
    ASSERT_TRUE(supportOMAPIReaders() == true);
    if (mVSReaders.size() > 0) {
        for (const auto& [name, reader] : mVSReaders) {
            std::vector<uint8_t> selectResponse = {};
            testSelectableAid(reader, LONG_SELECT_RESPONSE_AID, selectResponse);
            ASSERT_TRUE(verifyBerTlvData(selectResponse)) << "Select Response is not complete";
        }
    }
}

/** Test to fail open channel with wrong aid */
TEST_P(OMAPISEServiceHalTest, TestWrongAid) {
    ASSERT_TRUE(supportOMAPIReaders() == true);
    if (mVSReaders.size() > 0) {
        for (const auto& [name, reader] : mVSReaders) {
            testNonSelectableAid(reader, NON_SELECTABLE_AID);
        }
    }
}

/** Tests with invalid cmds in Transmit */
TEST_P(OMAPISEServiceHalTest, TestSecurityExceptionInTransmit) {
    ASSERT_TRUE(supportOMAPIReaders() == true);
    if (mVSReaders.size() > 0) {
        for (const auto& [name, reader] : mVSReaders) {
            std::shared_ptr<aidl::android::se::omapi::ISecureElementSession> session;
            std::shared_ptr<aidl::android::se::omapi::ISecureElementChannel> channel;
            auto seListener = ndk::SharedRefBase::make<::OMAPISEServiceHalTest::SEListener>();
            std::vector<uint8_t> selectResponse = {};

            ASSERT_NE(reader, nullptr) << "reader is null";

            bool status = false;
            auto res = reader->isSecureElementPresent(&status);
            ASSERT_TRUE(res.isOk()) << res.getMessage();
            ASSERT_TRUE(status);

            res = reader->openSession(&session);
            ASSERT_TRUE(res.isOk()) << res.getMessage();
            ASSERT_NE(session, nullptr) << "Could not open session";

            res = session->openLogicalChannel(SELECTABLE_AID, 0x00, seListener, &channel);
            ASSERT_TRUE(res.isOk()) << res.getMessage();
            ASSERT_NE(channel, nullptr) << "Could not open channel";

            res = channel->getSelectResponse(&selectResponse);
            ASSERT_TRUE(res.isOk()) << "failed to get Select Response";
            ASSERT_GE(selectResponse.size(), 2);

            ASSERT_EQ((selectResponse[selectResponse.size() - 1] & 0xFF), (0x00));
            ASSERT_EQ((selectResponse[selectResponse.size() - 2] & 0xFF), (0x90));

            for (auto cmd : ILLEGAL_COMMANDS_TRANSMIT) {
                std::vector<uint8_t> response = {};
                res = channel->transmit(cmd, &response);
                ASSERT_EQ(res.getExceptionCode(), EX_SECURITY);
                ASSERT_FALSE(res.isOk()) << "expected failed status for this test";
            }
            if (channel != nullptr) channel->close();
            if (session != nullptr) session->close();
        }
    }
}

/**
 * Tests Transmit API for all readers.
 *
 * Checks the return status and verifies the size of the
 * response.
 */
TEST_P(OMAPISEServiceHalTest, TestTransmitApdu) {
    ASSERT_TRUE(supportOMAPIReaders() == true);
    if (mVSReaders.size() > 0) {
        for (const auto& [name, reader] : mVSReaders) {
            for (auto apdu : NO_DATA_APDU) {
                std::vector<uint8_t> response = {};
                internalTransmitApdu(reader, apdu, response);
                ASSERT_GE(response.size(), 2);
                ASSERT_EQ((response[response.size() - 1] & 0xFF), (0x00));
                ASSERT_EQ((response[response.size() - 2] & 0xFF), (0x90));
            }

            for (auto apdu : DATA_APDU) {
                std::vector<uint8_t> response = {};
                internalTransmitApdu(reader, apdu, response);
                /* 256 byte data and 2 bytes of status word */
                ASSERT_GE(response.size(), 258);
                ASSERT_EQ((response[response.size() - 1] & 0xFF), (0x00));
                ASSERT_EQ((response[response.size() - 2] & 0xFF), (0x90));
            }
        }
    }
}

/**
 * Tests if underlying implementations returns the correct Status Word
 *
 * TO verify that :
 * - the device does not modify the APDU sent to the Secure Element
 * - the warning code is properly received by the application layer as SW answer
 * - the verify that the application layer can fetch the additionnal data (when present)
 */
TEST_P(OMAPISEServiceHalTest, testStatusWordTransmit) {
    ASSERT_TRUE(supportOMAPIReaders() == true);
    if (mVSReaders.size() > 0) {
        for (const auto& [name, reader] : mVSReaders) {
            for (auto apdu : SW_62xx_NO_DATA_APDU) {
                for (uint8_t i = 0x00; i < SW_62xx.size(); i++) {
                    apdu[2] = i + 1;
                    std::vector<uint8_t> response = {};
                    internalTransmitApdu(reader, apdu, response);
                    std::vector<uint8_t> SW = SW_62xx[i];
                    ASSERT_GE(response.size(), 2);
                    ASSERT_EQ(response[response.size() - 1], SW[1]);
                    ASSERT_EQ(response[response.size() - 2], SW[0]);
                }
            }

            for (uint8_t i = 0x00; i < SW_62xx.size(); i++) {
                std::vector<uint8_t> apdu = SW_62xx_DATA_APDU;
                apdu[2] = i + 1;
                std::vector<uint8_t> response = {};
                internalTransmitApdu(reader, apdu, response);
                std::vector<uint8_t> SW = SW_62xx[i];
                ASSERT_GE(response.size(), 3);
                ASSERT_EQ(response[response.size() - 1], SW[1]);
                ASSERT_EQ(response[response.size() - 2], SW[0]);
            }

            for (uint8_t i = 0x00; i < SW_62xx.size(); i++) {
                std::vector<uint8_t> apdu = SW_62xx_VALIDATE_DATA_APDU;
                apdu[2] = i + 1;
                std::vector<uint8_t> response = {};
                internalTransmitApdu(reader, apdu, response);
                ASSERT_GE(response.size(), apdu.size() + 2);
                std::vector<uint8_t> responseSubstring((response.begin() + 0),
                                                       (response.begin() + apdu.size()));
                // We should not care about which channel number is actually assigned.
                responseSubstring[0] = apdu[0];
                ASSERT_TRUE((responseSubstring == apdu));
                std::vector<uint8_t> SW = SW_62xx[i];
                ASSERT_EQ(response[response.size() - 1], SW[1]);
                ASSERT_EQ(response[response.size() - 2], SW[0]);
            }
        }
    }
}

/** Test if the responses are segmented by the underlying implementation */
TEST_P(OMAPISEServiceHalTest, TestSegmentedResponseTransmit) {
    ASSERT_TRUE(supportOMAPIReaders() == true);
    if (mVSReaders.size() > 0) {
        for (const auto& [name, reader] : mVSReaders) {
            for (auto apdu : SEGMENTED_RESP_APDU) {
                std::vector<uint8_t> response = {};
                internalTransmitApdu(reader, apdu, response);
                int expectedLength = (0x00 << 24) | (0x00 << 16) | (apdu[2] << 8) | apdu[3];
                ASSERT_EQ(response.size(), (expectedLength + 2));
                ASSERT_EQ((response[response.size() - 1] & 0xFF), (0x00));
                ASSERT_EQ((response[response.size() - 2] & 0xFF), (0x90));
                ASSERT_EQ((response[response.size() - 3] & 0xFF), (0xFF));
            }
        }
    }
}

/**
 * Tests the P2 value of the select command.
 *
 * Verifies that the default P2 value (0x00) is not modified by the underlying implementation.
 */
TEST_P(OMAPISEServiceHalTest, TestP2Value) {
    ASSERT_TRUE(supportOMAPIReaders() == true);
    if (mVSReaders.size() > 0) {
        for (const auto& [name, reader] : mVSReaders) {
            std::vector<uint8_t> response = {};
            internalTransmitApdu(reader, CHECK_SELECT_P2_APDU, response);
            ASSERT_GE(response.size(), 3);
            ASSERT_EQ((response[response.size() - 1] & 0xFF), (0x00));
            ASSERT_EQ((response[response.size() - 2] & 0xFF), (0x90));
            ASSERT_EQ((response[response.size() - 3] & 0xFF), (0x00));
        }
    }
}

TEST_P(OMAPISEServiceHalTest, TestUuidMappingConfig) {
    constexpr const char* xsd = "/data/local/tmp/omapi_uuid_map_config.xsd";
    auto uuidMappingFile = getUuidMappingFile();
    ASSERT_TRUE(uuidMappingFile.has_value()) << "Unable to determine UUID mapping config file path";
    LOG(INFO) << "UUID Mapping config file: " << uuidMappingFile.value();
    EXPECT_VALID_XML(uuidMappingFile->c_str(), xsd);
}

INSTANTIATE_TEST_SUITE_P(PerInstance, OMAPISEServiceHalTest,
                         testing::ValuesIn(::android::getAidlHalInstanceNames(
                             aidl::android::se::omapi::ISecureElementService::descriptor)),
                         android::hardware::PrintInstanceNameToString);
GTEST_ALLOW_UNINSTANTIATED_PARAMETERIZED_TEST(OMAPISEServiceHalTest);

}  // namespace
