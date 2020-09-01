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
#include "LoadedApk.h"
#include "dump/DumpManifest.h"

namespace aapt {

/**
 * The base command for dumping information about apks. When the command is executed, the command
 * performs the DumpApkCommand::Dump() operation on each apk provided as a file argument.
 **/
class DumpApkCommand : public Command {
 public:
  explicit DumpApkCommand(const std::string&& name, text::Printer* printer, IDiagnostics* diag)
      : Command(name), printer_(printer), diag_(diag) {
        SetDescription("Dump information about an APK or APC.");
  }

  text::Printer* GetPrinter() {
    return printer_;
  }

  IDiagnostics* GetDiagnostics() {
    return diag_;
  }

  Maybe<std::string> GetPackageName(LoadedApk* apk) {
    xml::Element* manifest_el = apk->GetManifest()->root.get();
    if (!manifest_el) {
      GetDiagnostics()->Error(DiagMessage() << "No AndroidManifest.");
      return Maybe<std::string>();
    }

    xml::Attribute* attr = manifest_el->FindAttribute({}, "package");
    if (!attr) {
      GetDiagnostics()->Error(DiagMessage() << "No package name.");
      return Maybe<std::string>();
    }
    return attr->value;
  }

  /** Perform the dump operation on the apk. */
  virtual int Dump(LoadedApk* apk) = 0;

  int Action(const std::vector<std::string>& args) final {
    if (args.size() < 1) {
      diag_->Error(DiagMessage() << "No dump apk specified.");
      return 1;
    }

    bool error = false;
    for (auto apk : args) {
      auto loaded_apk = LoadedApk::LoadApkFromPath(apk, diag_);
      if (!loaded_apk) {
        error = true;
        continue;
      }

      error |= Dump(loaded_apk.get());
    }

    return error;
  }

 private:
  text::Printer* printer_;
  IDiagnostics* diag_;
};

/** Command that prints contents of files generated from the compilation stage. */
class DumpAPCCommand : public Command {
 public:
  explicit DumpAPCCommand(text::Printer* printer, IDiagnostics* diag)
      : Command("apc"), printer_(printer), diag_(diag) {
    SetDescription("Print the contents of the AAPT2 Container (APC) generated fom compilation.");
    AddOptionalSwitch("--no-values", "Suppresses output of values when displaying resource tables.",
                      &no_values_);
    AddOptionalSwitch("-v", "Enables verbose logging.", &verbose_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  text::Printer* printer_;
  IDiagnostics* diag_;
  bool no_values_ = false;
  bool verbose_ = false;
};

/** Easter egg command shown when users enter "badger" instead of "badging". */
class DumpBadgerCommand : public Command {
 public:
  explicit DumpBadgerCommand(text::Printer* printer) : Command("badger"), printer_(printer) {
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  text::Printer* printer_;
  const static char kBadgerData[2925];
};

class DumpBadgingCommand : public DumpApkCommand {
 public:
  explicit DumpBadgingCommand(text::Printer* printer, IDiagnostics* diag)
      : DumpApkCommand("badging", printer, diag) {
    SetDescription("Print information extracted from the manifest of the APK.");
    AddOptionalSwitch("--include-meta-data", "Include meta-data information.",
                      &options_.include_meta_data);
  }

  int Dump(LoadedApk* apk) override {
    return DumpManifest(apk, options_, GetPrinter(), GetDiagnostics());
  }

 private:
  DumpManifestOptions options_;
};

class DumpConfigsCommand : public DumpApkCommand {
 public:
  explicit DumpConfigsCommand(text::Printer* printer, IDiagnostics* diag)
      : DumpApkCommand("configurations", printer, diag) {
    SetDescription("Print every configuration used by a resource in the APK.");
  }

  int Dump(LoadedApk* apk) override;
};

class DumpPackageNameCommand : public DumpApkCommand {
 public:
  explicit DumpPackageNameCommand(text::Printer* printer, IDiagnostics* diag)
      : DumpApkCommand("packagename", printer, diag) {
    SetDescription("Print the package name of the APK.");
  }

  int Dump(LoadedApk* apk) override;
};

class DumpPermissionsCommand : public DumpApkCommand {
 public:
  explicit DumpPermissionsCommand(text::Printer* printer, IDiagnostics* diag)
      : DumpApkCommand("permissions", printer, diag) {
    SetDescription("Print the permissions extracted from the manifest of the APK.");
  }

  int Dump(LoadedApk* apk) override {
    DumpManifestOptions options;
    options.only_permissions = true;
    return DumpManifest(apk, options, GetPrinter(), GetDiagnostics());
  }
};

class DumpStringsCommand : public DumpApkCommand {
 public:
  explicit DumpStringsCommand(text::Printer* printer, IDiagnostics* diag)
      : DumpApkCommand("strings", printer, diag) {
    SetDescription("Print the contents of the resource table string pool in the APK.");
  }

  int Dump(LoadedApk* apk) override;
};

/** Prints the graph of parents of a style in an APK. */
class DumpStyleParentCommand : public DumpApkCommand {
 public:
  explicit DumpStyleParentCommand(text::Printer* printer, IDiagnostics* diag)
      : DumpApkCommand("styleparents", printer, diag) {
    SetDescription("Print the parents of a style in an APK.");
    AddRequiredFlag("--style", "The name of the style to print", &style_);
  }

  int Dump(LoadedApk* apk) override;

 private:
  std::string style_;
};

class DumpTableCommand : public DumpApkCommand {
 public:
  explicit DumpTableCommand(text::Printer* printer, IDiagnostics* diag)
      : DumpApkCommand("resources", printer, diag) {
    SetDescription("Print the contents of the resource table from the APK.");
    AddOptionalSwitch("--no-values", "Suppresses output of values when displaying resource tables.",
                      &no_values_);
    AddOptionalSwitch("-v", "Enables verbose logging.", &verbose_);
  }

  int Dump(LoadedApk* apk) override;

 private:
  bool no_values_ = false;
  bool verbose_ = false;
};

class DumpXmlStringsCommand : public DumpApkCommand {
 public:
  explicit DumpXmlStringsCommand(text::Printer* printer, IDiagnostics* diag)
      : DumpApkCommand("xmlstrings", printer, diag) {
    SetDescription("Print the string pool of a compiled xml in an APK.");
    AddRequiredFlagList("--file", "A compiled xml file to print", &files_);
  }

  int Dump(LoadedApk* apk) override;

 private:
  std::vector<std::string> files_;
};

class DumpXmlTreeCommand : public DumpApkCommand {
 public:
  explicit DumpXmlTreeCommand(text::Printer* printer, IDiagnostics* diag)
      : DumpApkCommand("xmltree", printer, diag) {
    SetDescription("Print the tree of a compiled xml in an APK.");
    AddRequiredFlagList("--file", "A compiled xml file to print", &files_);
  }

  int Dump(LoadedApk* apk) override;

 private:
  std::vector<std::string> files_;
};

class DumpOverlayableCommand : public DumpApkCommand {
 public:
  explicit DumpOverlayableCommand(text::Printer* printer, IDiagnostics* diag)
      : DumpApkCommand("overlayable", printer, diag) {
    SetDescription("Print the <overlayable> resources of an APK.");
  }

  int Dump(LoadedApk* apk) override;
};

/** The default dump command. Performs no action because a subcommand is required. */
class DumpCommand : public Command {
 public:
  explicit DumpCommand(text::Printer* printer, IDiagnostics* diag)
      : Command("dump", "d"), diag_(diag) {
    AddOptionalSubcommand(util::make_unique<DumpAPCCommand>(printer, diag_));
    AddOptionalSubcommand(util::make_unique<DumpBadgingCommand>(printer, diag_));
    AddOptionalSubcommand(util::make_unique<DumpConfigsCommand>(printer, diag_));
    AddOptionalSubcommand(util::make_unique<DumpPackageNameCommand>(printer, diag_));
    AddOptionalSubcommand(util::make_unique<DumpPermissionsCommand>(printer, diag_));
    AddOptionalSubcommand(util::make_unique<DumpStringsCommand>(printer, diag_));
    AddOptionalSubcommand(util::make_unique<DumpStyleParentCommand>(printer, diag_));
    AddOptionalSubcommand(util::make_unique<DumpTableCommand>(printer, diag_));
    AddOptionalSubcommand(util::make_unique<DumpXmlStringsCommand>(printer, diag_));
    AddOptionalSubcommand(util::make_unique<DumpXmlTreeCommand>(printer, diag_));
    AddOptionalSubcommand(util::make_unique<DumpOverlayableCommand>(printer, diag_));
    AddOptionalSubcommand(util::make_unique<DumpBadgerCommand>(printer), /* hidden */ true);
  }

  int Action(const std::vector<std::string>& args) override {
    if (args.size() == 0) {
      diag_->Error(DiagMessage() << "no subcommand specified");
    } else {
      diag_->Error(DiagMessage() << "unknown subcommand '" << args[0] << "'");
    }
    Usage(&std::cerr);
    return 1;
  }

 private:
  IDiagnostics* diag_;
};

}  // namespace aapt

#endif  // AAPT2_DUMP_H
