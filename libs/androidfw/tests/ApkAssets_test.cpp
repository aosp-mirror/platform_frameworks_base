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

#include "android-base/file.h"
#include "android-base/test_utils.h"
#include "android-base/unique_fd.h"
#include "androidfw/Util.h"

#include "TestHelpers.h"
#include "data/basic/R.h"

using ::android::base::unique_fd;
using ::com::android::basic::R;
using ::testing::Eq;
using ::testing::Ge;
using ::testing::NotNull;
using ::testing::SizeIs;
using ::testing::StrEq;

namespace android {

TEST(ApkAssetsTest, LoadApk) {
  std::unique_ptr<const ApkAssets> loaded_apk =
      ApkAssets::Load(GetTestDataPath() + "/basic/basic.apk");
  ASSERT_THAT(loaded_apk, NotNull());

  const LoadedArsc* loaded_arsc = loaded_apk->GetLoadedArsc();
  ASSERT_THAT(loaded_arsc, NotNull());
  ASSERT_THAT(loaded_arsc->GetPackageById(0x7fu), NotNull());
  ASSERT_THAT(loaded_apk->GetAssetsProvider()->Open("res/layout/main.xml"), NotNull());
}

TEST(ApkAssetsTest, LoadApkFromFd) {
  const std::string path = GetTestDataPath() + "/basic/basic.apk";
  unique_fd fd(::open(path.c_str(), O_RDONLY | O_BINARY));
  ASSERT_THAT(fd.get(), Ge(0));

  std::unique_ptr<const ApkAssets> loaded_apk = ApkAssets::LoadFromFd(std::move(fd), path);
  ASSERT_THAT(loaded_apk, NotNull());

  const LoadedArsc* loaded_arsc = loaded_apk->GetLoadedArsc();
  ASSERT_THAT(loaded_arsc, NotNull());
  ASSERT_THAT(loaded_arsc->GetPackageById(0x7fu), NotNull());
  ASSERT_THAT(loaded_apk->GetAssetsProvider()->Open("res/layout/main.xml"), NotNull());
}

TEST(ApkAssetsTest, LoadApkAsSharedLibrary) {
  std::unique_ptr<const ApkAssets> loaded_apk =
      ApkAssets::Load(GetTestDataPath() + "/appaslib/appaslib.apk");
  ASSERT_THAT(loaded_apk, NotNull());

  const LoadedArsc* loaded_arsc = loaded_apk->GetLoadedArsc();
  ASSERT_THAT(loaded_arsc, NotNull());
  ASSERT_THAT(loaded_arsc->GetPackages(), SizeIs(1u));
  EXPECT_FALSE(loaded_arsc->GetPackages()[0]->IsDynamic());

  loaded_apk = ApkAssets::Load(GetTestDataPath() + "/appaslib/appaslib.apk", PROPERTY_DYNAMIC);
  ASSERT_THAT(loaded_apk, NotNull());

  loaded_arsc = loaded_apk->GetLoadedArsc();
  ASSERT_THAT(loaded_arsc, NotNull());
  ASSERT_THAT(loaded_arsc->GetPackages(), SizeIs(1u));
  EXPECT_TRUE(loaded_arsc->GetPackages()[0]->IsDynamic());
}

TEST(ApkAssetsTest, CreateAndDestroyAssetKeepsApkAssetsOpen) {
  std::unique_ptr<const ApkAssets> loaded_apk =
      ApkAssets::Load(GetTestDataPath() + "/basic/basic.apk");
  ASSERT_THAT(loaded_apk, NotNull());

  { ASSERT_THAT(loaded_apk->GetAssetsProvider()->Open("res/layout/main.xml",
                                                      Asset::ACCESS_BUFFER), NotNull()); }

  { ASSERT_THAT(loaded_apk->GetAssetsProvider()->Open("res/layout/main.xml",
                                                      Asset::ACCESS_BUFFER), NotNull()); }
}

TEST(ApkAssetsTest, OpenUncompressedAssetFd) {
  std::unique_ptr<const ApkAssets> loaded_apk =
      ApkAssets::Load(GetTestDataPath() + "/basic/basic.apk");
  ASSERT_THAT(loaded_apk, NotNull());

  auto asset = loaded_apk->GetAssetsProvider()->Open("assets/uncompressed.txt",
                                                     Asset::ACCESS_UNKNOWN);
  ASSERT_THAT(asset, NotNull());

  off64_t start, length;
  unique_fd fd(asset->openFileDescriptor(&start, &length));
  ASSERT_THAT(fd.get(), Ge(0));

  lseek64(fd.get(), start, SEEK_SET);

  std::string buffer;
  buffer.resize(length);
  ASSERT_TRUE(base::ReadFully(fd.get(), &*buffer.begin(), length));

  EXPECT_THAT(buffer, StrEq("This should be uncompressed.\n\n"));
}

}  // namespace android
