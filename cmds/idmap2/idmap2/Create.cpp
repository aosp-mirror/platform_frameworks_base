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

#include <sys/stat.h>   // umask
#include <sys/types.h>  // umask

#include <fstream>
#include <memory>
#include <ostream>
#include <sstream>
#include <string>
#include <vector>

#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/CommandLineOptions.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"
#include "idmap2/Policies.h"
#include "idmap2/SysTrace.h"

using android::ApkAssets;
using android::idmap2::BinaryStreamVisitor;
using android::idmap2::CommandLineOptions;
using android::idmap2::Error;
using android::idmap2::Idmap;
using android::idmap2::PoliciesToBitmask;
using android::idmap2::PolicyBitmask;
using android::idmap2::PolicyFlags;
using android::idmap2::Result;
using android::idmap2::Unit;
using android::idmap2::utils::kIdmapFilePermissionMask;
using android::idmap2::utils::UidHasWriteAccessToPath;

Result<Unit> Create(const std::vector<std::string>& args) {
  SYSTRACE << "Create " << args;
  std::string target_apk_path;
  std::string overlay_apk_path;
  std::string idmap_path;
  std::vector<std::string> policies;
  bool ignore_overlayable = false;

  const CommandLineOptions opts =
      CommandLineOptions("idmap2 create")
          .MandatoryOption("--target-apk-path",
                           "input: path to apk which will have its resources overlaid",
                           &target_apk_path)
          .MandatoryOption("--overlay-apk-path",
                           "input: path to apk which contains the new resource values",
                           &overlay_apk_path)
          .MandatoryOption("--idmap-path", "output: path to where to write idmap file", &idmap_path)
          .OptionalOption("--policy",
                          "input: an overlayable policy this overlay fulfills "
                          "(if none or supplied, the overlay policy will default to \"public\")",
                          &policies)
          .OptionalFlag("--ignore-overlayable", "disables overlayable and policy checks",
                        &ignore_overlayable);
  const auto opts_ok = opts.Parse(args);
  if (!opts_ok) {
    return opts_ok.GetError();
  }

  const uid_t uid = getuid();
  if (!UidHasWriteAccessToPath(uid, idmap_path)) {
    return Error("uid %d does not have write access to %s", uid, idmap_path.c_str());
  }

  PolicyBitmask fulfilled_policies = 0;
  auto conv_result = PoliciesToBitmask(policies);
  if (conv_result) {
    fulfilled_policies |= *conv_result;
  } else {
    return conv_result.GetError();
  }

  if (fulfilled_policies == 0) {
    fulfilled_policies |= PolicyFlags::POLICY_PUBLIC;
  }

  const std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  if (!target_apk) {
    return Error("failed to load apk %s", target_apk_path.c_str());
  }

  const std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  if (!overlay_apk) {
    return Error("failed to load apk %s", overlay_apk_path.c_str());
  }

  const auto idmap = Idmap::FromApkAssets(target_apk_path, *target_apk, overlay_apk_path,
                                          *overlay_apk, fulfilled_policies, !ignore_overlayable);
  if (!idmap) {
    return Error(idmap.GetError(), "failed to create idmap");
  }

  umask(kIdmapFilePermissionMask);
  std::ofstream fout(idmap_path);
  if (fout.fail()) {
    return Error("failed to open idmap path %s", idmap_path.c_str());
  }
  BinaryStreamVisitor visitor(fout);
  (*idmap)->accept(&visitor);
  fout.close();
  if (fout.fail()) {
    return Error("failed to write to idmap path %s", idmap_path.c_str());
  }

  return Unit{};
}
