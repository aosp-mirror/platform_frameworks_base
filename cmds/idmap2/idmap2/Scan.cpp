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

#include "android-base/properties.h"

#include "idmap2/CommandLineOptions.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/SysTrace.h"
#include "idmap2/Xml.h"
#include "idmap2/ZipFile.h"

#include "Commands.h"

using android::idmap2::CommandLineOptions;
using android::idmap2::Idmap;
using android::idmap2::PoliciesToBitmask;
using android::idmap2::PolicyBitmask;
using android::idmap2::PolicyFlags;
using android::idmap2::Result;
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
  // STOPSHIP(b/119390857): Check api version once Q sdk version is finalized
  std::string version = android::base::GetProperty("ro.vndk.version", "Q");
  return version == "Q" || version == "q";
}

std::unique_ptr<std::vector<std::string>> FindApkFiles(const std::vector<std::string>& dirs,
                                                       bool recursive, std::ostream& out_error) {
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
      out_error << "error: failed to open directory " << dir << std::endl;
      return nullptr;
    }
    paths.insert(apk_paths->cbegin(), apk_paths->cend());
  }
  return std::make_unique<std::vector<std::string>>(paths.cbegin(), paths.cend());
}

PolicyBitmask PolicyForPath(const std::string& apk_path) {
  static const std::vector<std::pair<std::string, PolicyBitmask>> values = {
      {"/product/", PolicyFlags::POLICY_PRODUCT_PARTITION},
      {"/system/", PolicyFlags::POLICY_SYSTEM_PARTITION},
      {"/vendor/", PolicyFlags::POLICY_VENDOR_PARTITION},
  };

  for (auto const& pair : values) {
    if (apk_path.compare(0, pair.first.size(), pair.first) == 0) {
      return pair.second | PolicyFlags::POLICY_PUBLIC;
    }
  }

  return PolicyFlags::POLICY_PUBLIC;
}

}  // namespace

bool Scan(const std::vector<std::string>& args, std::ostream& out_error) {
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
  if (!opts.Parse(args, out_error)) {
    return false;
  }

  const auto apk_paths = FindApkFiles(input_directories, recursive, out_error);
  if (!apk_paths) {
    return false;
  }

  std::vector<InputOverlay> interesting_apks;
  for (const std::string& path : *apk_paths) {
    Result<OverlayManifestInfo> overlay_info =
        ExtractOverlayManifestInfo(path, /* assert_overlay */ false);
    if (!overlay_info) {
      out_error << "error: " << overlay_info.GetErrorMessage() << std::endl;
      return false;
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

    PolicyBitmask fulfilled_policies;
    if (!override_policies.empty()) {
      auto conv_result = PoliciesToBitmask(override_policies);
      if (conv_result) {
        fulfilled_policies = *conv_result;
      } else {
        out_error << "error: " << conv_result.GetErrorMessage() << std::endl;
        return false;
      }
    } else {
      fulfilled_policies = PolicyForPath(path);
    }

    bool ignore_overlayable = false;
    if ((fulfilled_policies & PolicyFlags::POLICY_VENDOR_PARTITION) != 0 && !VendorIsQOrLater()) {
      // If the overlay is on a pre-Q vendor partition, do not enforce overlayable
      // restrictions on this overlay because the pre-Q platform has no understanding of
      // overlayable.
      ignore_overlayable = true;
    }

    std::string idmap_path = Idmap::CanonicalIdmapPathFor(output_directory, path);

    // Sort the static overlays in ascending priority order
    InputOverlay input{path, idmap_path, overlay_info->priority, override_policies,
                       ignore_overlayable};
    interesting_apks.insert(
        std::lower_bound(interesting_apks.begin(), interesting_apks.end(), input), input);
  }

  std::stringstream stream;
  for (const auto& overlay : interesting_apks) {
    // Create the idmap for the overlay if it currently does not exist or if it is not up to date.
    std::stringstream dev_null;

    std::vector<std::string> verify_args = {"--idmap-path", overlay.idmap_path};
    for (const std::string& policy : overlay.policies) {
      verify_args.emplace_back("--policy");
      verify_args.emplace_back(policy);
    }

    if (!Verify(std::vector<std::string>(verify_args), dev_null)) {
      std::vector<std::string> create_args = {"--target-apk-path",  target_apk_path,
                                              "--overlay-apk-path", overlay.apk_path,
                                              "--idmap-path",       overlay.idmap_path};
      if (overlay.ignore_overlayable) {
        create_args.emplace_back("--ignore-overlayable");
      }

      for (const std::string& policy : overlay.policies) {
        verify_args.emplace_back("--policy");
        verify_args.emplace_back(policy);
      }

      if (!Create(create_args, out_error)) {
        return false;
      }
    }

    stream << overlay.idmap_path << std::endl;
  }

  std::cout << stream.str();

  return true;
}
