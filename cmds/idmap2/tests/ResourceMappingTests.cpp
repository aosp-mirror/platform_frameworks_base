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
#include <string>

#include "R.h"
#include "TestConstants.h"
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

Result<ResourceMapping> TestGetResourceMapping(const std::string& local_target_apk_path,
                                               const std::string& local_overlay_apk_path,
                                               const std::string& overlay_name,
                                               const PolicyBitmask& fulfilled_policies,
                                               bool enforce_overlayable) {
  auto overlay_info =
      ExtractOverlayManifestInfo(GetTestDataPath() + local_overlay_apk_path, overlay_name);
  if (!overlay_info) {
    return overlay_info.GetError();
  }

  const std::string target_apk_path(GetTestDataPath() + local_target_apk_path);
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  if (!target_apk) {
    return Error(R"(Failed to load target apk "%s")", target_apk_path.data());
  }

  const std::string overlay_apk_path(GetTestDataPath() + local_overlay_apk_path);
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  if (!overlay_apk) {
    return Error(R"(Failed to load overlay apk "%s")", overlay_apk_path.data());
  }

  LogInfo log_info;
  return ResourceMapping::FromApkAssets(*target_apk, *overlay_apk, *overlay_info,
                                        fulfilled_policies, enforce_overlayable, log_info);
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
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay-legacy.apk", "",
                                          PolicyFlags::PUBLIC, /* enforce_overlayable */ false);

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
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay.apk", "SwapNames",
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
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay.apk",
                                          "DifferentPackages", PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ false);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 2U);
  ASSERT_EQ(res.GetOverlayToTargetMap().size(), 1U);
  ASSERT_RESULT(MappingExists(res, R::target::string::str1, 0x0104000a,
                              false /* rewrite */));  // -> android:string/ok
  ASSERT_RESULT(
      MappingExists(res, R::target::string::str3, R::overlay::string::str3, true /* rewrite */));
}

TEST(ResourceMappingTests, InlineResources) {
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay.apk", "Inline",
                                          PolicyFlags::PUBLIC, /* enforce_overlayable */ false);

  constexpr size_t overlay_string_pool_size = 10U;
  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 2U);
  ASSERT_EQ(res.GetOverlayToTargetMap().size(), 0U);
  ASSERT_RESULT(MappingExists(res, R::target::string::str1, Res_value::TYPE_STRING,
                              overlay_string_pool_size + 0U));  // -> "Hello World"
  ASSERT_RESULT(MappingExists(res, R::target::integer::int1, Res_value::TYPE_INT_DEC, 73U));
}

TEST(ResourceMappingTests, CreateIdmapFromApkAssetsPolicySystemPublic) {
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay.apk",
                                          TestConstants::OVERLAY_NAME_ALL_POLICIES,
                                          PolicyFlags::SYSTEM_PARTITION | PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ true);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 3U);
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_public,
                              R::overlay::string::policy_public, true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system,
                              R::overlay::string::policy_system, true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system_vendor,
                              R::overlay::string::policy_system_vendor, true /* rewrite */));
}

// Resources that are not declared as overlayable and resources that a protected by policies the
// overlay does not fulfill must not map to overlay resources.
TEST(ResourceMappingTests, CreateIdmapFromApkAssetsPolicySystemPublicInvalid) {
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay.apk",
                                          TestConstants::OVERLAY_NAME_ALL_POLICIES,
                                          PolicyFlags::SYSTEM_PARTITION | PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ true);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 3U);
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_public,
                              R::overlay::string::policy_public, true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system,
                              R::overlay::string::policy_system, true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system_vendor,
                              R::overlay::string::policy_system_vendor, true /* rewrite */));
}

// Resources that are not declared as overlayable and resources that a protected by policies the
// overlay does not fulfilled can map to overlay resources when overlayable enforcement is turned
// off.
TEST(ResourceMappingTests, ResourcesFromApkAssetsPolicySystemPublicInvalidIgnoreOverlayable) {
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay.apk",
                                          TestConstants::OVERLAY_NAME_ALL_POLICIES,
                                          PolicyFlags::SYSTEM_PARTITION | PolicyFlags::PUBLIC,
                                          /* enforce_overlayable */ false);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  auto& res = *resources;
  ASSERT_EQ(res.GetTargetToOverlayMap().size(), 11U);
  ASSERT_RESULT(MappingExists(res, R::target::string::not_overlayable,
                              R::overlay::string::not_overlayable, true /* rewrite */));
  ASSERT_RESULT(
      MappingExists(res, R::target::string::other, R::overlay::string::other, true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_actor,
                              R::overlay::string::policy_actor, true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_odm, R::overlay::string::policy_odm,
                              true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_oem, R::overlay::string::policy_oem,
                              true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_product,
                              R::overlay::string::policy_product, true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_public,
                              R::overlay::string::policy_public, true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_config_signature,
                              R::overlay::string::policy_config_signature, true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_signature,
                              R::overlay::string::policy_signature, true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system,
                              R::overlay::string::policy_system, true /* rewrite */));
  ASSERT_RESULT(MappingExists(res, R::target::string::policy_system_vendor,
                              R::overlay::string::policy_system_vendor, true /* rewrite */));
}

// Overlays that do not target an <overlayable> tag can overlay any resource if overlayable
// enforcement is disabled.
TEST(ResourceMappingTests, ResourcesFromApkAssetsNoDefinedOverlayableAndNoTargetName) {
  auto resources = TestGetResourceMapping("/target/target.apk", "/overlay/overlay-legacy.apk", "",
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
  auto resources =
      TestGetResourceMapping("/target/target-no-overlayable.apk", "/overlay/overlay.apk",
                             "NoTargetName", PolicyFlags::PUBLIC,
                             /* enforce_overlayable */ true);

  ASSERT_TRUE(resources) << resources.GetErrorMessage();
  ASSERT_EQ(resources->GetTargetToOverlayMap().size(), 0U);
}

// Overlays that are pre-installed or are signed with the same signature as the target  or are
// signed with the same signature as the reference package can overlay packages that have not
// defined overlayable resources.
TEST(ResourceMappingTests, ResourcesFromApkAssetsDefaultPolicies) {
  auto CheckEntries = [&](const PolicyBitmask& fulfilled_policies) -> void {
    auto resources =
        TestGetResourceMapping("/target/target-no-overlayable.apk", "/overlay/overlay.apk",
                               TestConstants::OVERLAY_NAME_ALL_POLICIES, fulfilled_policies,
                               /* enforce_overlayable */ true);

    ASSERT_TRUE(resources) << resources.GetErrorMessage();
    auto& res = *resources;
    ASSERT_EQ(resources->GetTargetToOverlayMap().size(), 11U);
    ASSERT_RESULT(MappingExists(res, R::target::string::not_overlayable,
                                R::overlay::string::not_overlayable, true /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::other, R::overlay::string::other,
                                true /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_actor,
                                R::overlay::string::policy_actor, true /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_odm, R::overlay::string::policy_odm,
                                true /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_oem, R::overlay::string::policy_oem,
                                true /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_product,
                                R::overlay::string::policy_product, true /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_public,
                                R::overlay::string::policy_public, true /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_config_signature,
                                R::overlay::string::policy_config_signature, true /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_signature,
                                R::overlay::string::policy_signature, true /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_system,
                                R::overlay::string::policy_system, true /* rewrite */));
    ASSERT_RESULT(MappingExists(res, R::target::string::policy_system_vendor,
                                R::overlay::string::policy_system_vendor, true /* rewrite */));
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
