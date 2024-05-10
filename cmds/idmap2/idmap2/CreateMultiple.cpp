/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <sys/stat.h>   // umask
#include <sys/types.h>  // umask

#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "Commands.h"
#include "android-base/stringprintf.h"
#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/CommandLineOptions.h"
#include "idmap2/CommandUtils.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"
#include "idmap2/Policies.h"
#include "idmap2/PolicyUtils.h"
#include "idmap2/SysTrace.h"

using android::base::StringPrintf;
using android::idmap2::BinaryStreamVisitor;
using android::idmap2::CommandLineOptions;
using android::idmap2::Error;
using android::idmap2::Idmap;
using android::idmap2::OverlayResourceContainer;
using android::idmap2::Result;
using android::idmap2::TargetResourceContainer;
using android::idmap2::Unit;
using android::idmap2::utils::kIdmapCacheDir;
using android::idmap2::utils::kIdmapFilePermissionMask;
using android::idmap2::utils::PoliciesToBitmaskResult;
using android::idmap2::utils::UidHasWriteAccessToPath;

Result<Unit> CreateMultiple(const std::vector<std::string>& args) {
  SYSTRACE << "CreateMultiple " << args;
  std::string target_apk_path;
  std::string idmap_dir{kIdmapCacheDir};
  std::vector<std::string> overlay_apk_paths;
  std::vector<std::string> policies;
  bool ignore_overlayable = false;

  const CommandLineOptions opts =
      CommandLineOptions("idmap2 create-multiple")
          .MandatoryOption("--target-apk-path",
                           "input: path to apk which will have its resources overlaid",
                           &target_apk_path)
          .MandatoryOption("--overlay-apk-path",
                           "input: path to apk which contains the new resource values",
                           &overlay_apk_paths)
          .OptionalOption("--idmap-dir",
                          StringPrintf("output: path to the directory in which to write idmap file"
                                       " (defaults to %s)",
                                       kIdmapCacheDir.data()),
                          &idmap_dir)
          .OptionalOption("--policy",
                          "input: an overlayable policy this overlay fulfills"
                          " (if none or supplied, the overlay policy will default to \"public\")",
                          &policies)
          .OptionalFlag("--ignore-overlayable", "disables overlayable and policy checks",
                        &ignore_overlayable);
  const auto opts_ok = opts.Parse(args);
  if (!opts_ok) {
    return opts_ok.GetError();
  }

  PolicyBitmask fulfilled_policies = 0;
  auto conv_result = PoliciesToBitmaskResult(policies);
  if (conv_result) {
    fulfilled_policies |= *conv_result;
  } else {
    return conv_result.GetError();
  }

  if (fulfilled_policies == 0) {
    fulfilled_policies |= PolicyFlags::PUBLIC;
  }

  const auto target = TargetResourceContainer::FromPath(target_apk_path);
  if (!target) {
    return Error("failed to load target '%s'", target_apk_path.c_str());
  }

  std::vector<std::string> idmap_paths;
  for (const std::string& overlay_apk_path : overlay_apk_paths) {
    const std::string idmap_path = Idmap::CanonicalIdmapPathFor(idmap_dir, overlay_apk_path);
    const uid_t uid = getuid();
    if (!UidHasWriteAccessToPath(uid, idmap_path)) {
      LOG(WARNING) << "uid " << uid << "does not have write access to " << idmap_path.c_str();
      continue;
    }

    // TODO(b/175014391): Support multiple overlay tags in OverlayConfig
    if (!Verify(idmap_path, target_apk_path, overlay_apk_path, "", fulfilled_policies,
                !ignore_overlayable)) {
      const auto overlay = OverlayResourceContainer::FromPath(overlay_apk_path);
      if (!overlay) {
        LOG(WARNING) << "failed to load apk " << overlay_apk_path.c_str();
        continue;
      }

      const auto idmap =
          Idmap::FromContainers(**target, **overlay, "", fulfilled_policies, !ignore_overlayable);
      if (!idmap) {
        LOG(WARNING) << "failed to create idmap";
        continue;
      }

      umask(kIdmapFilePermissionMask);
      std::ofstream fout(idmap_path);
      if (fout.fail()) {
        LOG(WARNING) << "failed to open idmap path " << idmap_path.c_str();
        continue;
      }

      BinaryStreamVisitor visitor(fout);
      (*idmap)->accept(&visitor);
      fout.close();
      if (fout.fail()) {
        LOG(WARNING) << "failed to write to idmap path %s" << idmap_path.c_str();
        continue;
      }
    }

    idmap_paths.emplace_back(idmap_path);
  }

  for (const std::string& idmap_path : idmap_paths) {
    std::cout << idmap_path << '\n';
  }

  return Unit{};
}
