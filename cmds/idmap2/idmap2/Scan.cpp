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

#include <dirent.h>

#include <fstream>
#include <memory>
#include <ostream>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "Commands.h"
#include "android-base/properties.h"
#include "idmap2/CommandLineOptions.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"
#include "idmap2/SysTrace.h"
#include "idmap2/Xml.h"
#include "idmap2/ZipFile.h"

using android::idmap2::CommandLineOptions;
using android::idmap2::Error;
using android::idmap2::Idmap;
using android::idmap2::kPolicyOdm;
using android::idmap2::kPolicyOem;
using android::idmap2::kPolicyProduct;
using android::idmap2::kPolicyPublic;
using android::idmap2::kPolicySystem;
using android::idmap2::kPolicyVendor;
using android::idmap2::PolicyBitmask;
using android::idmap2::PolicyFlags;
using android::idmap2::Result;
using android::idmap2::Unit;
using android::idmap2::utils::ExtractOverlayManifestInfo;
using android::idmap2::utils::FindFiles;
using android::idmap2::utils::OverlayManifestInfo;

namespace {

struct InputOverlay {
  bool operator<(InputOverlay const& rhs) const {
    return priority < rhs.priority || (priority == rhs.priority && apk_path < rhs.apk_path);
  }

  std::string apk_path;               // NOLINT(misc-non-private-member-variables-in-classes)
  std::string idmap_path;             // NOLINT(misc-non-private-member-variables-in-classes)
  int priority;                       // NOLINT(misc-non-private-member-variables-in-classes)
  std::vector<std::string> policies;  // NOLINT(misc-non-private-member-variables-in-classes)
  bool ignore_overlayable;            // NOLINT(misc-non-private-member-variables-in-classes)
};

bool VendorIsQOrLater() {
  constexpr int kQSdkVersion = 29;
  constexpr int kBase = 10;
  std::string version_prop = android::base::GetProperty("ro.vndk.version", "29");
  int version = strtol(version_prop.data(), nullptr, kBase);

  // If the string cannot be parsed, it is a development sdk codename.
  return version >= kQSdkVersion || version == 0;
}

Result<std::unique_ptr<std::vector<std::string>>> FindApkFiles(const std::vector<std::string>& dirs,
                                                               bool recursive) {
  SYSTRACE << "FindApkFiles " << dirs << " " << recursive;
  const auto predicate = [](unsigned char type, const std::string& path) -> bool {
    static constexpr size_t kExtLen = 4;  // strlen(".apk")
    return type == DT_REG && path.size() > kExtLen &&
           path.compare(path.size() - kExtLen, kExtLen, ".apk") == 0;
  };
  // pass apk paths through a set to filter out duplicates
  std::set<std::string> paths;
  for (const auto& dir : dirs) {
    const auto apk_paths = FindFiles(dir, recursive, predicate);
    if (!apk_paths) {
      return Error("failed to open directory %s", dir.c_str());
    }
    paths.insert(apk_paths->cbegin(), apk_paths->cend());
  }
  return std::make_unique<std::vector<std::string>>(paths.cbegin(), paths.cend());
}

std::vector<std::string> PoliciesForPath(const std::string& apk_path) {
  static const std::vector<std::pair<std::string, std::string>> values = {
      {"/odm/", kPolicyOdm},
      {"/oem/", kPolicyOem},
      {"/product/", kPolicyProduct},
      {"/system/", kPolicySystem},
      {"/system_ext/", kPolicySystem},
      {"/vendor/", kPolicyVendor},
  };

  std::vector<std::string> fulfilled_policies = {kPolicyPublic};
  for (auto const& pair : values) {
    if (apk_path.compare(0, pair.first.size(), pair.first) == 0) {
      fulfilled_policies.emplace_back(pair.second);
      break;
    }
  }

  return fulfilled_policies;
}

}  // namespace

Result<Unit> Scan(const std::vector<std::string>& args) {
  SYSTRACE << "Scan " << args;
  std::vector<std::string> input_directories;
  std::string target_package_name;
  std::string target_apk_path;
  std::string output_directory;
  std::vector<std::string> override_policies;
  bool recursive = false;

  const CommandLineOptions opts =
      CommandLineOptions("idmap2 scan")
          .MandatoryOption("--input-directory", "directory containing overlay apks to scan",
                           &input_directories)
          .OptionalFlag("--recursive", "also scan subfolders of overlay-directory", &recursive)
          .MandatoryOption("--target-package-name", "package name of target package",
                           &target_package_name)
          .MandatoryOption("--target-apk-path", "path to target apk", &target_apk_path)
          .MandatoryOption("--output-directory",
                           "directory in which to write artifacts (idmap files and overlays.list)",
                           &output_directory)
          .OptionalOption(
              "--override-policy",
              "input: an overlayable policy this overlay fulfills "
              "(if none or supplied, the overlays will not have their policies overriden",
              &override_policies);
  const auto opts_ok = opts.Parse(args);
  if (!opts_ok) {
    return opts_ok.GetError();
  }

  const auto apk_paths = FindApkFiles(input_directories, recursive);
  if (!apk_paths) {
    return Error(apk_paths.GetError(), "failed to find apk files");
  }

  std::vector<InputOverlay> interesting_apks;
  for (const std::string& path : **apk_paths) {
    Result<OverlayManifestInfo> overlay_info =
        ExtractOverlayManifestInfo(path, /* assert_overlay */ false);
    if (!overlay_info) {
      return overlay_info.GetError();
    }

    if (!overlay_info->is_static) {
      continue;
    }

    if (overlay_info->target_package.empty() ||
        overlay_info->target_package != target_package_name) {
      continue;
    }

    if (overlay_info->priority < 0) {
      continue;
    }

    std::vector<std::string> fulfilled_policies;
    if (!override_policies.empty()) {
      fulfilled_policies = override_policies;
    } else {
      fulfilled_policies = PoliciesForPath(path);
    }

    bool ignore_overlayable = false;
    if (std::find(fulfilled_policies.begin(), fulfilled_policies.end(), kPolicyVendor) !=
            fulfilled_policies.end() &&
        !VendorIsQOrLater()) {
      // If the overlay is on a pre-Q vendor partition, do not enforce overlayable
      // restrictions on this overlay because the pre-Q platform has no understanding of
      // overlayable.
      ignore_overlayable = true;
    }

    std::string idmap_path = Idmap::CanonicalIdmapPathFor(output_directory, path);

    // Sort the static overlays in ascending priority order
    InputOverlay input{path, idmap_path, overlay_info->priority, fulfilled_policies,
                       ignore_overlayable};
    interesting_apks.insert(
        std::lower_bound(interesting_apks.begin(), interesting_apks.end(), input), input);
  }

  std::stringstream stream;
  for (const auto& overlay : interesting_apks) {
    if (!Verify(std::vector<std::string>({"--idmap-path", overlay.idmap_path}))) {
      std::vector<std::string> create_args = {"--target-apk-path",  target_apk_path,
                                              "--overlay-apk-path", overlay.apk_path,
                                              "--idmap-path",       overlay.idmap_path};
      if (overlay.ignore_overlayable) {
        create_args.emplace_back("--ignore-overlayable");
      }

      for (const std::string& policy : overlay.policies) {
        create_args.emplace_back("--policy");
        create_args.emplace_back(policy);
      }

      const auto create_ok = Create(create_args);
      if (!create_ok) {
        LOG(WARNING) << "failed to create idmap for overlay apk path \"" << overlay.apk_path
                     << "\": " << create_ok.GetError().GetMessage();
        continue;
      }
    }

    stream << overlay.idmap_path << std::endl;
  }

  std::cout << stream.str();

  return Unit{};
}
