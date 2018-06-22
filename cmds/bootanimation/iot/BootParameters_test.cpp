/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "BootParameters.h"

#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <boot_parameters.pb.h>
#include <gtest/gtest.h>

namespace android {

namespace {

TEST(BootParametersTest, TestNoBootParametersIsNotSilent) {
    android::things::proto::BootParameters proto;

    BootParameters bootParameters = BootParameters();
    bootParameters.parseBootParameters(proto.SerializeAsString());

    ASSERT_FALSE(bootParameters.isSilentBoot());
    ASSERT_EQ(0u, bootParameters.getParameters().size());
}

TEST(BootParametersTest, TestParseIsSilent) {
    android::things::proto::BootParameters proto;
    proto.set_silent_boot(true);

    BootParameters bootParameters = BootParameters();
    bootParameters.parseBootParameters(proto.SerializeAsString());

    ASSERT_TRUE(bootParameters.isSilentBoot());
}

TEST(BootParametersTest, TestParseIsNotSilent) {
    android::things::proto::BootParameters proto;
    proto.set_silent_boot(false);

    BootParameters bootParameters = BootParameters();
    bootParameters.parseBootParameters(proto.SerializeAsString());

    ASSERT_FALSE(bootParameters.isSilentBoot());
}

TEST(BootParametersTest, TestParseBootParameters) {
    android::things::proto::BootParameters proto;
    proto.set_silent_boot(false);

    auto userParameter = proto.add_user_parameter();
    userParameter->set_key("key1");
    userParameter->set_value("value1");

    userParameter = proto.add_user_parameter();
    userParameter->set_key("key2");
    userParameter->set_value("value2");

    BootParameters bootParameters = BootParameters();
    bootParameters.parseBootParameters(proto.SerializeAsString());

    auto &parameters = bootParameters.getParameters();
    ASSERT_EQ(2u, parameters.size());
    ASSERT_STREQ(parameters[0].key, "key1");
    ASSERT_STREQ(parameters[0].value, "value1");
    ASSERT_STREQ(parameters[1].key, "key2");
    ASSERT_STREQ(parameters[1].value, "value2");
}

TEST(BootParametersTest, TestParseLegacyDisableBootAnimationIsSilent) {
    BootParameters bootParameters = BootParameters();
    bootParameters.parseLegacyBootParameters(R"(
    {
      "brightness":200,
      "volume":100,
      "boot_animation_disabled":1,
      "param_names":[],
      "param_values":[]
    }
    )");

    ASSERT_TRUE(bootParameters.isSilentBoot());
}

TEST(BootParametersTest, TestParseLegacyZeroVolumeIsSilent) {
    BootParameters bootParameters = BootParameters();
    bootParameters.parseLegacyBootParameters(R"(
    {
      "brightness":200,
      "volume":0,
      "boot_animation_disabled":0,
      "param_names":[],
      "param_values":[]
    }
    )");

    ASSERT_TRUE(bootParameters.isSilentBoot());
}

TEST(BootParametersTest, TestParseLegacyDefaultVolumeIsSilent) {
    BootParameters bootParameters = BootParameters();
    bootParameters.parseLegacyBootParameters(R"(
    {
      "brightness":200,
      "volume":-1000,
      "boot_animation_disabled":0,
      "param_names":[],
      "param_values":[]
    }
    )");

    ASSERT_TRUE(bootParameters.isSilentBoot());
}

TEST(BootParametersTest, TestParseLegacyNotSilent) {
    BootParameters bootParameters = BootParameters();
    bootParameters.parseLegacyBootParameters(R"(
    {
      "brightness":200,
      "volume":500,
      "boot_animation_disabled":0,
      "param_names":[],
      "param_values":[]
    }
    )");

    ASSERT_FALSE(bootParameters.isSilentBoot());
}

TEST(BootParametersTest, TestParseLegacyParameters) {
    BootParameters bootParameters = BootParameters();
    bootParameters.parseLegacyBootParameters(R"(
    {
      "brightness":200,
      "volume":100,
      "boot_animation_disabled":1,
      "param_names":["key1", "key2"],
      "param_values":["value1", "value2"]
    }
    )");

    auto parameters = bootParameters.getParameters();
    ASSERT_EQ(2u, parameters.size());
    ASSERT_STREQ(parameters[0].key, "key1");
    ASSERT_STREQ(parameters[0].value, "value1");
    ASSERT_STREQ(parameters[1].key, "key2");
    ASSERT_STREQ(parameters[1].value, "value2");
}

TEST(BootParametersTest, TestParseLegacyZeroParameters) {
    BootParameters bootParameters = BootParameters();
    bootParameters.parseLegacyBootParameters(R"(
    {
      "brightness":200,
      "volume":100,
      "boot_animation_disabled":1,
      "param_names":[],
      "param_values":[]
    }
    )");

    ASSERT_EQ(0u, bootParameters.getParameters().size());
}

TEST(BootParametersTest, TestMalformedLegacyParametersAreSkipped) {
    BootParameters bootParameters = BootParameters();
    bootParameters.parseLegacyBootParameters(R"(
    {
      "brightness":500,
      "volume":500,
      "boot_animation_disabled":0,
      "param_names":["key1", "key2"],
      "param_values":[1, "value2"]
    }
    )");

    auto parameters = bootParameters.getParameters();
    ASSERT_EQ(1u, parameters.size());
    ASSERT_STREQ(parameters[0].key, "key2");
    ASSERT_STREQ(parameters[0].value, "value2");
}

TEST(BootParametersTest, TestLegacyUnequalParameterSizesAreSkipped) {
    BootParameters bootParameters = BootParameters();
    bootParameters.parseLegacyBootParameters(R"(
    {
      "brightness":500,
      "volume":500,
      "boot_animation_disabled":0,
      "param_names":["key1", "key2"],
      "param_values":["value1"]
    }
    )");

    ASSERT_EQ(0u, bootParameters.getParameters().size());
}

TEST(BootParametersTest, TestMissingLegacyBootParametersIsSilent) {
    BootParameters bootParameters = BootParameters();
    bootParameters.parseLegacyBootParameters(R"(
    {
      "brightness":500
    }
    )");

    EXPECT_TRUE(bootParameters.isSilentBoot());
    ASSERT_EQ(0u, bootParameters.getParameters().size());
}

TEST(BootParametersTest, TestLastFileIsRemovedOnError) {
    TemporaryFile lastFile;
    TemporaryDir tempDir;
    std::string nonExistentFilePath(std::string(tempDir.path) + "/nonexistent");
    std::string contents;

    BootParameters::swapAndLoadBootConfigContents(lastFile.path, nonExistentFilePath.c_str(),
                                                  &contents);

    struct stat buf;
    ASSERT_EQ(-1, lstat(lastFile.path, &buf));
    ASSERT_TRUE(contents.empty());
}

TEST(BootParametersTest, TestNextFileIsRemovedLastFileExistsOnSuccess) {
    TemporaryFile lastFile;
    TemporaryFile nextFile;

    base::WriteStringToFile("foo", nextFile.path);

    std::string contents;
    // Expected side effects:
    // - |next_file| is moved to |last_file|
    // - |contents| is the contents of |next_file| before being moved.
    BootParameters::swapAndLoadBootConfigContents(lastFile.path, nextFile.path, &contents);

    struct stat buf;
    ASSERT_EQ(0, lstat(lastFile.path, &buf));
    ASSERT_EQ(-1, lstat(nextFile.path, &buf));
    ASSERT_EQ(contents, "foo");

    contents.clear();
    ASSERT_TRUE(base::ReadFileToString(lastFile.path, &contents));
    ASSERT_EQ(contents, "foo");
}

}  // namespace

}  // namespace android
