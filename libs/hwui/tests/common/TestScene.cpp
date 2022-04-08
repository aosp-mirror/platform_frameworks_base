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

#include "tests/common/TestScene.h"

namespace android {
namespace uirenderer {
namespace test {

// Not a static global because we need to force the map to be constructed
// before we try to add things to it.
std::unordered_map<std::string, TestScene::Info>& TestScene::testMap() {
    static std::unordered_map<std::string, TestScene::Info> testMap;
    return testMap;
}

void TestScene::registerScene(const TestScene::Info& info) {
    testMap()[info.name] = info;
}

} /* namespace test */
} /* namespace uirenderer */
} /* namespace android */
