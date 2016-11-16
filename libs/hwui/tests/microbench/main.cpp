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

#include "debug/GlesDriver.h"
#include "debug/NullGlesDriver.h"

#include "hwui/Typeface.h"

#include <benchmark/benchmark.h>

#include <memory>

using namespace android;
using namespace android::uirenderer;

int main(int argc, char** argv) {
    debug::GlesDriver::replace(std::make_unique<debug::NullGlesDriver>());
    benchmark::Initialize(&argc, argv);
    Typeface::setRobotoTypefaceForTest();
    benchmark::RunSpecifiedBenchmarks();
    return 0;
}
