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

#ifndef AAPT2_DUMP_H
#define AAPT2_DUMP_H

#include "Command.h"
#include "Debug.h"
#include "dump/DumpManifest.h"

namespace aapt {

/** Command the contents of files generated from the compilation stage. */
class DumpAPCCommand : public Command {
 public:
  explicit DumpAPCCommand(IDiagnostics* diag) : Command("apc"), diag_(diag) {
    SetDescription("Print the contents of the AAPT2 Container (APC) generated fom compilation.");
    AddOptionalSwitch("--no-values", "Suppresses output of values when displaying resource tables.",
                      &no_values_);
    AddOptionalSwitch("-v", "Enables verbose logging.", &verbose_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  IDiagnostics* diag_;
  bool verbose_ = false;
  bool no_values_ = false;
};

/** Prints every configuration used by a resource in an APK. */
class DumpConfigsCommand : public Command {
 public:
  explicit DumpConfigsCommand(IDiagnostics* diag) : Command("configurations"), diag_(diag) {
    SetDescription("Print every configuration used by a resource in the APK.");
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  IDiagnostics* diag_;
};

/** Prints the contents of the resource table string pool in the APK. */
class DumpStringsCommand : public Command {
  public:
    explicit DumpStringsCommand(IDiagnostics* diag) : Command("strings"), diag_(diag) {
      SetDescription("Print the contents of the resource table string pool in the APK.");
    }

    int Action(const std::vector<std::string>& args) override;

  private:
    IDiagnostics* diag_;
};

/** Prints the contents of the resource table from the APK. */
class DumpTableCommand : public Command {
 public:
  explicit DumpTableCommand(IDiagnostics* diag) : Command("resources"), diag_(diag) {
    SetDescription("Print the contents of the resource table from the APK.");
    AddOptionalSwitch("--no-values", "Suppresses output of values when displaying resource tables.",
                      &no_values_);
    AddOptionalSwitch("-v", "Enables verbose logging.", &verbose_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  IDiagnostics* diag_;
  bool verbose_ = false;
  bool no_values_ = false;
};

/** Prints the string pool of a compiled xml in an APK. */
class DumpXmlStringsCommand : public Command {
public:
    explicit DumpXmlStringsCommand(IDiagnostics* diag) : Command("xmlstrings"), diag_(diag) {
      SetDescription("Print the string pool of a compiled xml in an APK.");
      AddRequiredFlagList("--file", "A compiled xml file to print", &files_);
    }

    int Action(const std::vector<std::string>& args) override;

private:
    IDiagnostics* diag_;
    std::vector<std::string> files_;
};


/** Prints the tree of a compiled xml in an APK. */
class DumpXmlTreeCommand : public Command {
 public:
  explicit DumpXmlTreeCommand(IDiagnostics* diag) : Command("xmltree"), diag_(diag) {
    SetDescription("Print the tree of a compiled xml in an APK.");
    AddRequiredFlagList("--file", "A compiled xml file to print", &files_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  IDiagnostics* diag_;
  std::vector<std::string> files_;
};

/** Prints the contents of the resource table from the APK. */
class DumpPackageNameCommand : public Command {
 public:
  explicit DumpPackageNameCommand(IDiagnostics* diag) : Command("packagename"), diag_(diag) {
    SetDescription("Print the package name of the APK.");
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  IDiagnostics* diag_;
};

/** The default dump command. Performs no action because a subcommand is required. */
class DumpCommand : public Command {
 public:
  explicit DumpCommand(IDiagnostics* diag) : Command("dump", "d"), diag_(diag) {
    AddOptionalSubcommand(util::make_unique<DumpAPCCommand>(diag_));
    AddOptionalSubcommand(util::make_unique<DumpBadgingCommand>(diag_));
    AddOptionalSubcommand(util::make_unique<DumpConfigsCommand>(diag_));
    AddOptionalSubcommand(util::make_unique<DumpPackageNameCommand>(diag_));
    AddOptionalSubcommand(util::make_unique<DumpPermissionsCommand>(diag_));
    AddOptionalSubcommand(util::make_unique<DumpStringsCommand>(diag_));
    AddOptionalSubcommand(util::make_unique<DumpTableCommand>(diag_));
    AddOptionalSubcommand(util::make_unique<DumpXmlStringsCommand>(diag_));
    AddOptionalSubcommand(util::make_unique<DumpXmlTreeCommand>(diag_));
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  IDiagnostics* diag_;
};

}// namespace aapt

#endif //AAPT2_DUMP_H
