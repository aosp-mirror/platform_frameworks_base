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
#include <string>
#include <vector>

#include "idmap2/CommandLineOptions.h"
#include "idmap2/Idmap.h"
#include "idmap2/PrettyPrintVisitor.h"
#include "idmap2/RawPrintVisitor.h"
#include "idmap2/SysTrace.h"

using android::idmap2::CommandLineOptions;
using android::idmap2::Idmap;
using android::idmap2::PrettyPrintVisitor;
using android::idmap2::RawPrintVisitor;

bool Dump(const std::vector<std::string>& args, std::ostream& out_error) {
  SYSTRACE << "Dump " << args;
  std::string idmap_path;
  bool verbose;

  const CommandLineOptions opts =
      CommandLineOptions("idmap2 dump")
          .MandatoryOption("--idmap-path", "input: path to idmap file to pretty-print", &idmap_path)
          .OptionalFlag("--verbose", "annotate every byte of the idmap", &verbose);
  if (!opts.Parse(args, out_error)) {
    return false;
  }
  std::ifstream fin(idmap_path);
  const std::unique_ptr<const Idmap> idmap = Idmap::FromBinaryStream(fin, out_error);
  fin.close();
  if (!idmap) {
    return false;
  }

  if (verbose) {
    RawPrintVisitor visitor(std::cout);
    idmap->accept(&visitor);
  } else {
    PrettyPrintVisitor visitor(std::cout);
    idmap->accept(&visitor);
  }

  return true;
}
