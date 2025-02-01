// Copyright (C) 2025 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <binder/ProcessState.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <gui/SurfaceComposerClient.h>

#include "android/gui/CaptureArgs.h"
#include "gmock/gmock.h"
#include "gui/ScreenCaptureResults.h"
#include "screencap_utils.h"
#include "ui/DisplayId.h"

using ::android::DisplayId;
using ::android::OK;
using ::android::PhysicalDisplayId;
using ::android::ProcessState;
using ::android::SurfaceComposerClient;
using ::android::gui::CaptureArgs;
using ::android::gui::ScreenCaptureResults;
using ::testing::AllOf;
using ::testing::HasSubstr;

class ScreenCapTest : public ::testing::Test {
protected:
    static void SetUpTestSuite() {
        // These lines are copied from screencap.cpp.  They are necessary to call binder.
        ProcessState::self()->setThreadPoolMaxThreadCount(0);
        ProcessState::self()->startThreadPool();
    }
};

TEST_F(ScreenCapTest, Capture_InvalidDisplayNumber) {
    DisplayId display;
    display.value = -1;

    CaptureArgs args;
    auto result = ::android::screencap::capture(display, args);
    EXPECT_FALSE(result.ok());
    EXPECT_THAT(result.error().message(),
                AllOf(HasSubstr("Display Id"), HasSubstr("is not valid.")));
}

TEST_F(ScreenCapTest, Capture_SuccessWithPhysicalDisplay) {
    const std::vector<PhysicalDisplayId> physicalDisplays =
            SurfaceComposerClient::getPhysicalDisplayIds();

    ASSERT_FALSE(physicalDisplays.empty());
    DisplayId display;
    display.value = physicalDisplays.front().value;

    CaptureArgs args;
    auto result = ::android::screencap::capture(display, args);
    EXPECT_TRUE(result.ok());
    // TODO consider verifying actual captured image.
}