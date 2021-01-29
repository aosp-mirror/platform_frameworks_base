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

#include <cstdio>  // fclose
#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "R.h"
#include "TestHelpers.h"
#include "androidfw/ResourceTypes.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "idmap2/LogInfo.h"
#include "idmap2/ResourceMapping.h"

using android::Res_value;
using android::idmap2::utils::ExtractOverlayManifestInfo;

using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace android::idmap2 {

#define ASSERT_RESULT(r)                             \
  do {                                               \
    auto result = r;                                 \
    ASSERT_TRUE(result) << result.GetErrorMessage(); \
  } while (0)

Result<ResourceMapping> TestGetResourceMapping(const android::StringPiece& local_target_apk_path,
                                               const android::StringPiece& local_overlay_apk_path,
                                               const OverlayManifestInfo& overlay_info,
                                               const PolicyBitmask& fulfilled_policies,
                                               bool enforce_overlayable) {
  const std::string target_apk_path(GetTestDataPath() + local_target_apk_path.data());
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  if (!target_apk) {
    return Error(R"(Failed to load target apk "%s")", target_apk_path.data());
  }

  const std::string overlay_apk_path(GetTestDataPath() + local_overlay_apk_path.data());
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  if (!overlay_apk) {
    return Error(R"(Failed to load overlay apk "%s")", overlay_apk_path.data());
  }

  LogInfo log_info;
  return ResourceMapping::FromApkAssets(*target_apk, *overlay_apk, overlay_info, fulfilled_policies,
                                        enforce_overlayable, log_info);
}

Result<ResourceMapping> TestGetResourceMapping(const android::StringPiece& local_target_apk_path,
                                               const android::StringPiece& local_overlay_apk_path,
                                               const PolicyBitmask& fulfilled_policies,
                                               bool enforce_overlayable) {
  auto overlay_info = ExtractOverlayManifestInfo(GetTestDataPath() + local_overlay_apk_path.data());
  if (!overlay_info) {
    return overlay_info.GetError();
  }
  return TestGetResourceMapping(local_target_apk_path, local_overlay_apk_path, *overlay_info,
                                fulfilled_policies, enforce_overlayable);
}

Result<Unit> MappingExists(const ResourceMapping& mapping, ResourceId target_resource,
                           ResourceId overlay_resource, bool rewrite) {
  auto target_map = mapping.GetTargetToOverlayMap();
  auto entry_map = target_map.find(target_resource);
  if (entry_map == target_map.end()) {
    return Error("Failed to find mapping for target resource");
  }

  auto actual_overlay_resource = std::get_if<ResourceId>(&entry_map->second);
  if (actual_overlay_resource == nullptr) {
    return Error("Target resource is not mapped to an overlay resource id");
  }

  if (*actual_overlay_resource != overlay_resource) {
    return Error(R"(Expected id: "0x%02x" Actual id: "0x%02x")", overlay_resource,
                 *actual_overlay_resource);
  }

  auto overlay_map = mapping.GetOverlayToTargetMap();
  auto overlay_iter = overlay_map.find(overlay_resource);
  if ((overlay_iter != overlay_map.end()) != rewrite) {
    return Error(R"(Expected rewriting: "%s")", rewrite ? "true" : "false");
  }

  if (rewrite && overlay_iter->second != target_resource) {
    return Error(R"(Expected rewrite id: "0x%02x" Actual id: "0x%02x")", target_resource,
                 overlay_iter->second);
  }

  return Result<Unit>({});
}

Result<Unit> MappingExists(const ResourceMapping& mapping, const ResourceId& target_resource,
                           const uint8_t type, const uint32_t value) {
  auto target_map = mapping.GetTargetToOverlayMap();
  auto entry_map = target_map.find(target_resource);
  if (entry_map == target_map.end()) {
    return Error("Failed to find mapping for target resource");
  }

  auto actual_overlay_value = std::get_if<TargetValue>(&entry_map->second);
  if (actual_overlay_value == nullptr) {
    return Error("Target resource is not mapped to an inline value");
  }

  if (actual_overlay_value->data_type != type) {
    return Error(R"(Expected type: "0x%02x" Actual type: "0x%02x")", type,
                 actual_overlay_value->data_type);
  }

  if (actual_overlay_value->data_value != value) {
    return Error(R"(Expected value: "0x%08x" Actual value: "0x%08x")", type,
                 actual_overlay_value->data_value);
  }

  return Result<Unit>({});
}

TEST(ResourceMappingTests, ResourcesFromApkAssetsLegacy) {
  OverlayManifestInfo info{};
  info.target_package = "test.target";
  info.target_name = "TestResources";
  info.resource_mapping = 0U;  // no xml
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay.apk", info,
                                          PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ false);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 4U);
  ASSERT_RESULT(
      MappingExists(res, R::target::integer::int1, R::overlay::integer::int1, false /* rewrite */));
  ASSERT_RESULT(
      MappingExists(res, R::target::string::str1, R::overlay::string::str1, false /* rewrite */));
  ASSERT_RESULT(
      MappingExists(res, R::target::string::str3, R::overlay::string::str3, false /* rewrite */));
  ASSERT_RESULT(
      MappingExists(res, R::target::string::str4, R::overlay::string::str4, false /* rewrite */));
}

TEST(ResourceMappingTests, ResourcesFromApkAssetsNonMatchingNames) {
  OverlayManifestInfo info{};
  info.target_package = "test.target";
  info.target_name = "TestResources";
  info.resource_mapping = 0x7f030003;  // xml/overlays_swap
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay.apk", info,
                                          PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ false);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 3U);
  ASSERT_RESULT(
      MappingExists(res, R::target::string::str1, R::overlay::string::str4, true /* rewrite */));
  ASSERT_RESULT(
      MappingExists(res, R::target::string::str3, R::overlay::string::str1, true /* rewrite */));
  ASSERT_RESULT(
      MappingExists(res, R::target::string::str4, R::overlay::string::str3, true /* rewrite */));
}

TEST(ResourceMappingTests, DoNotRewriteNonOverlayResourceId) {
  OverlayManifestInfo info{};
  info.target_package = "test.target";
  info.target_name = "TestResources";
  info.resource_mapping = 0x7f030001;  // xml/overlays_different_packages
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay.apk", info,
                                          PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ false);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 2U);
  ASSERT_EQ(res.GetOverlayToTargetMap().size(), 1U);
  ASSERT_RESULT(MappingExists(res, R::target::string::str1, 0x0104000a,
                              false /* rewrite */));  // -> android:string/ok
  ASSERT_RESULT(MappingExists(res, R::target::string::str3, 0x7f020001, true /* rewrite */));
}

TEST(ResourceMappingTests, InlineResources) {
  OverlayManifestInfo info{};
  info.target_package = "test.target";
  info.target_name = "TestResources";
  info.resource_mapping = 0x7f030002;  // xml/overlays_inline
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay.apk", info,
                                          PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ false);

  constexpr size_t overlay_string_pool_size = 8U;
  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 2U);
  ASSERT_EQ(res.GetOverlayToTargetMap().size(), 0U);
  ASSERT_RESULT(MappingExists(res, R::target::string::str1, Res_value::TYPE_STRING,
                              overlay_string_pool_size + 0U));  // -> "Hello World"
  ASSERT_RESULT(MappingExists(res, R::target::integer::int1, Res_value::TYPE_INT_DEC, 73U));
}

TEST(ResourceMappingTests, CreateIdmapFromApkAssetsPolicySystemPublic) {
  auto resources =
      TestGetResourceMapping("/target/target.apk", "/system-overlay/system-overlay.apk",
                             PolicyFlags::SYSTEM_PARTITION | PolicyFlags::PUBLIC,
                             /* enforce_overlayable */ true);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 3U);
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_public,
                              R::system_overlay::string::policy_public, false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system,
                              R::system_overlay::string::policy_system, false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system_vendor,
                              R::system_overlay::string::policy_system_vendor,
                              false /* rewrite */));
}

// Resources that are not declared as overlayable and resources that a protected by policies the
// overlay does not fulfill must not map to overlay resources.
TEST(ResourceMappingTests, CreateIdmapFromApkAssetsPolicySystemPublicInvalid) {
  auto resources = TestGetResourceMapping("/target/target.apk",
                                          "/system-overlay-invalid/system-overlay-invalid.apk",
                                          PolicyFlags::SYSTEM_PARTITION | PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ true);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 3U);
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_public,
                              R::system_overlay_invalid::string::policy_public,
                              false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system,
                              R::system_overlay_invalid::string::policy_system,
                              false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system_vendor,
                              R::system_overlay_invalid::string::policy_system_vendor,
                              false /* rewrite */));
}

// Resources that are not declared as overlayable and resources that a protected by policies the
// overlay does not fulfilled can map to overlay resources when overlayable enforcement is turned
// off.
TEST(ResourceMappingTests, ResourcesFromApkAssetsPolicySystemPublicInvalidIgnoreOverlayable) {
  auto resources = TestGetResourceMapping("/target/target.apk",
                                          "/system-overlay-invalid/system-overlay-invalid.apk",
                                          PolicyFlags::SYSTEM_PARTITION | PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ false);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 11U);
  ASSERT_RESULT(MappingExists(res, R::target::string::not_overlayable,
                              R::system_overlay_invalid::string::not_overlayable,
                              false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::other,
                              R::system_overlay_invalid::string::other, false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_actor,
                              R::system_overlay_invalid::string::policy_actor,
                              false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_odm,
                              R::system_overlay_invalid::string::policy_odm, false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_oem,
                              R::system_overlay_invalid::string::policy_oem, false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_product,
                              R::system_overlay_invalid::string::policy_product,
                              false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_public,
                              R::system_overlay_invalid::string::policy_public,
                              false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_config_signature,
                              R::system_overlay_invalid::string::policy_config_signature,
                              false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_signature,
                              R::system_overlay_invalid::string::policy_signature,
                              false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system,
                              R::system_overlay_invalid::string::policy_system,
                              false /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system_vendor,
                              R::system_overlay_invalid::string::policy_system_vendor,
                              false /* rewrite */));
}

// Overlays that do not target an <overlayable> tag can overlay resources defined within any
// <overlayable> tag.
TEST(ResourceMappingTests, ResourcesFromApkAssetsNoDefinedOverlayableAndNoTargetName) {
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay-no-name.apk",
                                          PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ false);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 4U);
  ASSERT_RESULT(
      MappingExists(res, R::target::integer::int1, R::overlay::integer::int1, false /* rewrite */));
  ASSERT_RESULT(
      MappingExists(res, R::target::string::str1, R::overlay::string::str1, false /* rewrite */));
  ASSERT_RESULT(
      MappingExists(res, R::target::string::str3, R::overlay::string::str3, false /* rewrite */));
  ASSERT_RESULT(
      MappingExists(res, R::target::string::str4, R::overlay::string::str4, false /* rewrite */));
}

// Overlays that are neither pre-installed nor signed with the same signature as the target cannot
// overlay packages that have not defined overlayable resources.
TEST(ResourceMappingTests, ResourcesFromApkAssetsDefaultPoliciesPublicFail) {
  auto resources = TestGetResourceMapping("/target/target-no-overlayable.apk",
                                          "/overlay/overlay-no-name.apk", PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ true);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  ASSERT_EQ(resources->GetTargetToOverlayMap().size(), 0U);
}

// Overlays that are pre-installed or are signed with the same signature as the target  or are
// signed with the same signature as the reference package can overlay packages that have not
// defined overlayable resources.
TEST(ResourceMappingTests, ResourcesFromApkAssetsDefaultPolicies) {
  auto CheckEntries = [&](const PolicyBitmask& fulfilled_policies) -> void {
    auto resources = TestGetResourceMapping("/target/target-no-overlayable.apk",
                                            "/system-overlay-invalid/system-overlay-invalid.apk",
                                            fulfilled_policies,
                                            /* enforce_overlayable */ true);

    ASSERT_TRUE(resources) << resources.GetErrorMessage();
    auto& res = *resources;
    ASSERT_EQ(resources->GetTargetToOverlayMap().size(), 11U);
    ASSERT_RESULT(MappingExists(res, R::target::string::not_overlayable,
                                R::system_overlay_invalid::string::not_overlayable,
                                false /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::other,
                                R::system_overlay_invalid::string::other, false /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_actor,
                                R::system_overlay_invalid::string::policy_actor,
                                false /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_odm,
                                R::system_overlay_invalid::string::policy_odm,
                                false /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_oem,
                                R::system_overlay_invalid::string::policy_oem,
                                false /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_product,
                                R::system_overlay_invalid::string::policy_product,
                                false /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_public,
                                R::system_overlay_invalid::string::policy_public,
                                false /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_config_signature,
                                R::system_overlay_invalid::string::policy_config_signature,
                                false /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_signature,
                                R::system_overlay_invalid::string::policy_signature,
                                false /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_system,
                                R::system_overlay_invalid::string::policy_system,
                                false /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_system_vendor,
                                R::system_overlay_invalid::string::policy_system_vendor,
                                false /* rewrite */));
  };

  CheckEntries(PolicyFlags::SIGNATURE);
  CheckEntries(PolicyFlags::CONFIG_SIGNATURE);
  CheckEntries(PolicyFlags::PRODUCT_PARTITION);
  CheckEntries(PolicyFlags::SYSTEM_PARTITION);
  CheckEntries(PolicyFlags::VENDOR_PARTITION);
  CheckEntries(PolicyFlags::ODM_PARTITION);
  CheckEntries(PolicyFlags::OEM_PARTITION);
}

}  // namespace android::idmap2
