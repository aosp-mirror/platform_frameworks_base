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

#include <gtest/gtest.h>

namespace android {

namespace {

TEST(BootParametersTest, TestNoBootParametersIsNotSilent) {
    BootParameters boot_parameters = BootParameters();
    boot_parameters.loadParameters("");

    ASSERT_FALSE(boot_parameters.isSilentBoot());
    ASSERT_EQ(0u, boot_parameters.getParameters().size());
}

TEST(BootParametersTest, TestParseIsSilent) {
    BootParameters boot_parameters = BootParameters();
    boot_parameters.loadParameters(R"(
    {
      "silent_boot":true,
      "params":{}
    }
    )");

    ASSERT_TRUE(boot_parameters.isSilentBoot());
}

TEST(BootParametersTest, TestParseIsNotSilent) {
    BootParameters boot_parameters = BootParameters();
    boot_parameters.loadParameters(R"(
    {
      "silent_boot":false,
      "params":{}
    }
    )");

    ASSERT_FALSE(boot_parameters.isSilentBoot());
}

TEST(BootParametersTest, TestParseBootParameters) {
    BootParameters boot_parameters = BootParameters();
    boot_parameters.loadParameters(R"(
    {
      "silent_boot":false,
      "params":{
        "key1":"value1",
        "key2":"value2"
      }
    }
    )");

    auto &parameters = boot_parameters.getParameters();
    ASSERT_EQ(2u, parameters.size());
    ASSERT_STREQ(parameters[0].key, "key1");
    ASSERT_STREQ(parameters[0].value, "value1");
    ASSERT_STREQ(parameters[1].key, "key2");
    ASSERT_STREQ(parameters[1].value, "value2");
}

TEST(BootParametersTest, TestParseMissingParametersIsNotSilent) {
    BootParameters boot_parameters = BootParameters();
    boot_parameters.loadParameters(R"(
    {
      "params":{}
    }
    )");

    ASSERT_FALSE(boot_parameters.isSilentBoot());
}

TEST(BootParametersTest, TestParseMalformedParametersAreSkipped) {
    BootParameters boot_parameters = BootParameters();
    boot_parameters.loadParameters(R"(
    {
      "silent_boot":false,
      "params":{
        "key1":123,
        "key2":"value2"
      }
    }
    )");

    auto &parameters = boot_parameters.getParameters();
    ASSERT_EQ(1u, parameters.size());
    ASSERT_STREQ(parameters[0].key, "key2");
    ASSERT_STREQ(parameters[0].value, "value2");
}

}  // namespace

}  // namespace android
