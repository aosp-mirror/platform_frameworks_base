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
#include <vector>

#include "idmap2/CommandLineOptions.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"
#include "idmap2/Xml.h"
#include "idmap2/ZipFile.h"

#include "Commands.h"

using android::idmap2::CommandLineOptions;
using android::idmap2::Idmap;
using android::idmap2::MemoryChunk;
using android::idmap2::Xml;
using android::idmap2::ZipFile;
using android::idmap2::utils::FindFiles;

namespace {
std::unique_ptr<std::vector<std::string>> FindApkFiles(const std::vector<std::string>& dirs,
                                                       bool recursive, std::ostream& out_error) {
  const auto predicate = [](unsigned char type, const std::string& path) -> bool {
    static constexpr size_t kExtLen = 4;  // strlen(".apk")
    return type == DT_REG && path.size() > kExtLen &&
           !path.compare(path.size() - kExtLen, kExtLen, ".apk");
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
  return std::unique_ptr<std::vector<std::string>>(
      new std::vector<std::string>(paths.cbegin(), paths.cend()));
}
}  // namespace

bool Scan(const std::vector<std::string>& args, std::ostream& out_error) {
  std::vector<std::string> input_directories;
  std::string target_package_name, target_apk_path, output_directory;
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
                           &output_directory);
  if (!opts.Parse(args, out_error)) {
    return false;
  }

  const auto apk_paths = FindApkFiles(input_directories, recursive, out_error);
  if (!apk_paths) {
    return false;
  }

  std::vector<std::string> interesting_apks;
  for (const std::string& path : *apk_paths) {
    std::unique_ptr<const ZipFile> zip = ZipFile::Open(path);
    if (!zip) {
      out_error << "error: failed to open " << path << " as a zip file" << std::endl;
      return false;
    }

    std::unique_ptr<const MemoryChunk> entry = zip->Uncompress("AndroidManifest.xml");
    if (!entry) {
      out_error << "error: failed to uncompress AndroidManifest.xml from " << path << std::endl;
      return false;
    }

    std::unique_ptr<const Xml> xml = Xml::Create(entry->buf, entry->size);
    if (!xml) {
      out_error << "error: failed to parse AndroidManifest.xml from " << path << std::endl;
      continue;
    }

    const auto tag = xml->FindTag("overlay");
    if (!tag) {
      continue;
    }

    auto iter = tag->find("isStatic");
    if (iter == tag->end() || std::stoul(iter->second) == 0u) {
      continue;
    }

    iter = tag->find("targetPackage");
    if (iter == tag->end() || iter->second != target_package_name) {
      continue;
    }

    iter = tag->find("priority");
    if (iter == tag->end()) {
      continue;
    }

    const int priority = std::stoi(iter->second);
    if (priority < 0) {
      continue;
    }

    interesting_apks.insert(
        std::lower_bound(interesting_apks.begin(), interesting_apks.end(), path), path);
  }

  std::stringstream stream;
  for (auto iter = interesting_apks.cbegin(); iter != interesting_apks.cend(); ++iter) {
    const std::string idmap_path = Idmap::CanonicalIdmapPathFor(output_directory, *iter);
    std::stringstream dev_null;
    if (!Verify(std::vector<std::string>({"--idmap-path", idmap_path}), dev_null) &&
        !Create(std::vector<std::string>({
                    "--target-apk-path",
                    target_apk_path,
                    "--overlay-apk-path",
                    *iter,
                    "--idmap-path",
                    idmap_path,
                }),
                out_error)) {
      return false;
    }
    stream << idmap_path << std::endl;
  }

  std::cout << stream.str();

  return true;
}
