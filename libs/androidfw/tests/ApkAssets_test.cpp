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
  EXPECT_NE(nullptr, loaded_apk->GetLoadedArsc());

  std::unique_ptr<Asset> asset = loaded_apk->Open("res/layout/main.xml");
  ASSERT_NE(nullptr, asset);
}

TEST(ApkAssetsTest, LoadApkAsSharedLibrary) {
  std::unique_ptr<ApkAssets> loaded_apk =
      ApkAssets::Load(GetTestDataPath() + "/appaslib/appaslib.apk");
  ASSERT_NE(nullptr, loaded_apk);
  const LoadedArsc* loaded_arsc = loaded_apk->GetLoadedArsc();
  ASSERT_NE(nullptr, loaded_arsc);
  ASSERT_EQ(1u, loaded_arsc->GetPackages().size());
  EXPECT_FALSE(loaded_arsc->GetPackages()[0]->IsDynamic());

  loaded_apk = ApkAssets::LoadAsSharedLibrary(GetTestDataPath() + "/appaslib/appaslib.apk");
  ASSERT_NE(nullptr, loaded_apk);

  loaded_arsc = loaded_apk->GetLoadedArsc();
  ASSERT_NE(nullptr, loaded_arsc);
  ASSERT_EQ(1u, loaded_arsc->GetPackages().size());
  EXPECT_TRUE(loaded_arsc->GetPackages()[0]->IsDynamic());
}

}  // namespace android
