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

#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include "idmap2/CommandLineOptions.h"
#include "idmap2/Idmap.h"
#include "idmap2/SysTrace.h"

using android::idmap2::CommandLineOptions;
using android::idmap2::IdmapHeader;

bool Verify(const std::vector<std::string>& args, std::ostream& out_error) {
  SYSTRACE << "Verify " << args;
  std::string idmap_path;

  const CommandLineOptions opts =
      CommandLineOptions("idmap2 verify")
          .MandatoryOption("--idmap-path", "input: path to idmap file to verify", &idmap_path);
  if (!opts.Parse(args, out_error)) {
    return false;
  }

  std::ifstream fin(idmap_path);
  const std::unique_ptr<const IdmapHeader> header = IdmapHeader::FromBinaryStream(fin);
  fin.close();
  if (!header) {
    out_error << "error: failed to parse idmap header" << std::endl;
    return false;
  }

  return header->IsUpToDate(out_error);
}
