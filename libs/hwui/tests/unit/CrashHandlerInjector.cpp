/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "tests/common/TestUtils.h"

#include <gtest/gtest.h>
#include <cstdio>

using namespace android::uirenderer;

static void gunitCrashHandler() {
    auto testinfo = ::testing::UnitTest::GetInstance()->current_test_info();
    printf("[  FAILED  ] %s.%s\n", testinfo->test_case_name(),
            testinfo->name());
    printf("[  FATAL!  ] RenderThread crashed, aborting tests!\n");
    fflush(stdout);
}

static void hookError() {
    TestUtils::setRenderThreadCrashHandler(gunitCrashHandler);
}

class HookErrorInit {
public:
    HookErrorInit() { hookError(); }
};

static HookErrorInit sInit;
