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

#include "androidfw/ApkAssets.h"

#include "TestHelpers.h"
#include "data/basic/R.h"

using com::android::basic::R;

namespace android {

TEST(ApkAssetsTest, LoadApk) {
  std::unique_ptr<ApkAssets> loaded_apk = ApkAssets::Load(GetTestDataPath() + "/basic/basic.apk");
  ASSERT_NE(nullptr, loaded_apk);

  std::unique_ptr<Asset> asset = loaded_apk->Open("res/layout/main.xml");
  ASSERT_NE(nullptr, asset);
}

}  // namespace android
