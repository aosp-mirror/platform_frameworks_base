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

namespace android {

TEST(ApkAssetsTest, LoadApk) {
  std::unique_ptr<const ApkAssets> loaded_apk =
      ApkAssets::Load(GetTestDataPath() + "/basic/basic.apk");
  ASSERT_NE(nullptr, loaded_apk);

  const LoadedArsc* loaded_arsc = loaded_apk->GetLoadedArsc();
  ASSERT_NE(nullptr, loaded_arsc);

  const LoadedPackage* loaded_package = loaded_arsc->GetPackageForId(0x7f010000);
  ASSERT_NE(nullptr, loaded_package);

  std::unique_ptr<Asset> asset = loaded_apk->Open("res/layout/main.xml");
  ASSERT_NE(nullptr, asset);
}

TEST(ApkAssetsTest, LoadApkFromFd) {
  const std::string path = GetTestDataPath() + "/basic/basic.apk";
  unique_fd fd(::open(path.c_str(), O_RDONLY | O_BINARY));
  ASSERT_GE(fd.get(), 0);

  std::unique_ptr<const ApkAssets> loaded_apk =
      ApkAssets::LoadFromFd(std::move(fd), path, false /*system*/, false /*force_shared_lib*/);
  ASSERT_NE(nullptr, loaded_apk);

  const LoadedArsc* loaded_arsc = loaded_apk->GetLoadedArsc();
  ASSERT_NE(nullptr, loaded_arsc);

  const LoadedPackage* loaded_package = loaded_arsc->GetPackageForId(0x7f010000);
  ASSERT_NE(nullptr, loaded_package);

  std::unique_ptr<Asset> asset = loaded_apk->Open("res/layout/main.xml");
  ASSERT_NE(nullptr, asset);
}

TEST(ApkAssetsTest, LoadApkAsSharedLibrary) {
  std::unique_ptr<const ApkAssets> loaded_apk =
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

TEST(ApkAssetsTest, LoadApkWithIdmap) {
  std::string contents;
  ResTable target_table;
  const std::string target_path = GetTestDataPath() + "/basic/basic.apk";
  ASSERT_TRUE(ReadFileFromZipToString(target_path, "resources.arsc", &contents));
  ASSERT_EQ(NO_ERROR, target_table.add(contents.data(), contents.size(), 0, true /*copyData*/));

  ResTable overlay_table;
  const std::string overlay_path = GetTestDataPath() + "/overlay/overlay.apk";
  ASSERT_TRUE(ReadFileFromZipToString(overlay_path, "resources.arsc", &contents));
  ASSERT_EQ(NO_ERROR, overlay_table.add(contents.data(), contents.size(), 0, true /*copyData*/));

  util::unique_cptr<void> idmap_data;
  void* temp_data;
  size_t idmap_len;

  ASSERT_EQ(NO_ERROR, target_table.createIdmap(overlay_table, 0u, 0u, target_path.c_str(),
                                               overlay_path.c_str(), &temp_data, &idmap_len));
  idmap_data.reset(temp_data);

  TemporaryFile tf;
  ASSERT_TRUE(base::WriteFully(tf.fd, idmap_data.get(), idmap_len));
  close(tf.fd);

  // Open something so that the destructor of TemporaryFile closes a valid fd.
  tf.fd = open("/dev/null", O_WRONLY);

  std::unique_ptr<const ApkAssets> loaded_overlay_apk = ApkAssets::LoadOverlay(tf.path);
  ASSERT_NE(nullptr, loaded_overlay_apk);
}

TEST(ApkAssetsTest, CreateAndDestroyAssetKeepsApkAssetsOpen) {
  std::unique_ptr<const ApkAssets> loaded_apk =
      ApkAssets::Load(GetTestDataPath() + "/basic/basic.apk");
  ASSERT_NE(nullptr, loaded_apk);

  {
    std::unique_ptr<Asset> assets = loaded_apk->Open("res/layout/main.xml", Asset::ACCESS_BUFFER);
    ASSERT_NE(nullptr, assets);
  }

  {
    std::unique_ptr<Asset> assets = loaded_apk->Open("res/layout/main.xml", Asset::ACCESS_BUFFER);
    ASSERT_NE(nullptr, assets);
  }
}

TEST(ApkAssetsTest, OpenUncompressedAssetFd) {
  std::unique_ptr<const ApkAssets> loaded_apk =
      ApkAssets::Load(GetTestDataPath() + "/basic/basic.apk");
  ASSERT_NE(nullptr, loaded_apk);

  auto asset = loaded_apk->Open("assets/uncompressed.txt", Asset::ACCESS_UNKNOWN);
  ASSERT_NE(nullptr, asset);

  off64_t start, length;
  unique_fd fd(asset->openFileDescriptor(&start, &length));
  EXPECT_GE(fd.get(), 0);

  lseek64(fd.get(), start, SEEK_SET);

  std::string buffer;
  buffer.resize(length);
  ASSERT_TRUE(base::ReadFully(fd.get(), &*buffer.begin(), length));

  EXPECT_EQ("This should be uncompressed.\n\n", buffer);
}

}  // namespace android
