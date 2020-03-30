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
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "idmap2/CommandLineOptions.h"
#include "idmap2/Idmap.h"
#include "idmap2/PrettyPrintVisitor.h"
#include "idmap2/RawPrintVisitor.h"
#include "idmap2/Result.h"
#include "idmap2/SysTrace.h"

using android::idmap2::CommandLineOptions;
using android::idmap2::Error;
using android::idmap2::Idmap;
using android::idmap2::PrettyPrintVisitor;
using android::idmap2::RawPrintVisitor;
using android::idmap2::Result;
using android::idmap2::Unit;

Result<Unit> Dump(const std::vector<std::string>& args) {
  SYSTRACE << "Dump " << args;
  std::string idmap_path;
  bool verbose = false;

  const CommandLineOptions opts =
      CommandLineOptions("idmap2 dump")
          .MandatoryOption("--idmap-path", "input: path to idmap file to pretty-print", &idmap_path)
          .OptionalFlag("--verbose", "annotate every byte of the idmap", &verbose);
  const auto opts_ok = opts.Parse(args);
  if (!opts_ok) {
    return opts_ok.GetError();
  }
  std::ifstream fin(idmap_path);
  const auto idmap = Idmap::FromBinaryStream(fin);
  fin.close();
  if (!idmap) {
    return Error(idmap.GetError(), "failed to load idmap");
  }

  if (verbose) {
    RawPrintVisitor visitor(std::cout);
    (*idmap)->accept(&visitor);
  } else {
    PrettyPrintVisitor visitor(std::cout);
    (*idmap)->accept(&visitor);
  }

  return Unit{};
}
