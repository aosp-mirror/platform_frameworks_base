/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "Dump.h"

#include <cinttypes>
#include <vector>

#include "android-base/stringprintf.h"
#include "androidfw/StringPiece.h"

#include "Debug.h"
#include "Diagnostics.h"
#include "LoadedApk.h"
#include "Util.h"
#include "format/Container.h"
#include "format/binary/BinaryResourceParser.h"
#include "format/binary/XmlFlattener.h"
#include "format/proto/ProtoDeserialize.h"
#include "io/FileStream.h"
#include "io/ZipArchive.h"
#include "process/IResourceTableConsumer.h"
#include "text/Printer.h"
#include "util/Files.h"

using ::aapt::text::Printer;
using ::android::StringPiece;
using ::android::base::StringPrintf;

namespace aapt {

static const char* ResourceFileTypeToString(const ResourceFile::Type& type) {
  switch (type) {
    case ResourceFile::Type::kPng:
      return "PNG";
    case ResourceFile::Type::kBinaryXml:
      return "BINARY_XML";
    case ResourceFile::Type::kProtoXml:
      return "PROTO_XML";
    default:
      break;
  }
  return "UNKNOWN";
}

static void DumpCompiledFile(const ResourceFile& file, const Source& source, off64_t offset,
                             size_t len, Printer* printer) {
  printer->Print("Resource: ");
  printer->Println(file.name.to_string());

  printer->Print("Config:   ");
  printer->Println(file.config.to_string());

  printer->Print("Source:   ");
  printer->Println(file.source.to_string());

  printer->Print("Type:     ");
  printer->Println(ResourceFileTypeToString(file.type));

  printer->Println(StringPrintf("Data:     offset=%" PRIi64 " length=%zd", offset, len));
}

namespace {

class DumpContext : public IAaptContext {
 public:
  PackageType GetPackageType() override {
    // Doesn't matter.
    return PackageType::kApp;
  }

  IDiagnostics* GetDiagnostics() override {
    return &diagnostics_;
  }

  NameMangler* GetNameMangler() override {
    UNIMPLEMENTED(FATAL);
    return nullptr;
  }

  const std::string& GetCompilationPackage() override {
    static std::string empty;
    return empty;
  }

  uint8_t GetPackageId() override {
    return 0;
  }

  SymbolTable* GetExternalSymbols() override {
    UNIMPLEMENTED(FATAL);
    return nullptr;
  }

  bool IsVerbose() override {
    return verbose_;
  }

  void SetVerbose(bool val) {
    verbose_ = val;
  }

  int GetMinSdkVersion() override {
    return 0;
  }

 private:
  StdErrDiagnostics diagnostics_;
  bool verbose_ = false;
};

}  // namespace

// Use a smaller buffer so that there is less latency for dumping to stdout.
constexpr size_t kStdOutBufferSize = 1024u;

int DumpAPCCommand::Action(const std::vector<std::string>& args) {
  DumpContext context;
  DebugPrintTableOptions print_options;
  print_options.show_sources = true;
  print_options.show_values = !no_values_;

  if (args.size() < 1) {
    diag_->Error(DiagMessage() << "No dump container specified.");
    return 1;
  }

  io::FileOutputStream fout(STDOUT_FILENO, kStdOutBufferSize);
  Printer printer(&fout);

  for (auto container : args) {
    io::FileInputStream input(container);
    if (input.HadError()) {
      context.GetDiagnostics()->Error(DiagMessage(container)
                                          << "failed to open file: " << input.GetError());
      return false;
    }

    // Try as a compiled file.
    ContainerReader reader(&input);
    if (reader.HadError()) {
      context.GetDiagnostics()->Error(DiagMessage(container)
                                           << "failed to read container: " << reader.GetError());
      return false;
    }

    printer.Println("AAPT2 Container (APC)");
    ContainerReaderEntry* entry;
    std::string error;
    while ((entry = reader.Next()) != nullptr) {
      if (entry->Type() == ContainerEntryType::kResTable) {
        printer.Println("kResTable");

        pb::ResourceTable pb_table;
        if (!entry->GetResTable(&pb_table)) {
          context.GetDiagnostics()->Error(DiagMessage(container)
                                               << "failed to parse proto table: "
                                               << entry->GetError());
          continue;
        }

        ResourceTable table;
        error.clear();
        if (!DeserializeTableFromPb(pb_table, nullptr /*files*/, &table, &error)) {
          context.GetDiagnostics()->Error(DiagMessage(container)
                                               << "failed to parse table: " << error);
          continue;
        }

        printer.Indent();
        Debug::PrintTable(table, print_options, &printer);
        printer.Undent();
      } else if (entry->Type() == ContainerEntryType::kResFile) {
        printer.Println("kResFile");
        pb::internal::CompiledFile pb_compiled_file;
        off64_t offset;
        size_t length;
        if (!entry->GetResFileOffsets(&pb_compiled_file, &offset, &length)) {
          context.GetDiagnostics()->Error(
              DiagMessage(container) << "failed to parse compiled proto file: "
                                     << entry->GetError());
          continue;
        }

        ResourceFile file;
        if (!DeserializeCompiledFileFromPb(pb_compiled_file, &file, &error)) {
          context.GetDiagnostics()->Warn(DiagMessage(container)
                                              << "failed to parse compiled file: " << error);
          continue;
        }

        printer.Indent();
        DumpCompiledFile(file, Source(container), offset, length, &printer);
        printer.Undent();
      }
    }
  }

  return 0;
}

int DumpConfigsCommand::Action(const std::vector<std::string>& args) {
  if (args.size() < 1) {
    diag_->Error(DiagMessage() << "No dump apk specified.");
    return 1;
  }

  auto loaded_apk = LoadedApk::LoadApkFromPath(args[0], diag_);
  if (!loaded_apk) {
    return 1;
  }

  ResourceTable* table = loaded_apk->GetResourceTable();
  if (!table) {
    diag_->Error(DiagMessage() << "Failed to retrieve resource table.");
    return 1;
  }

  io::FileOutputStream fout(STDOUT_FILENO, kStdOutBufferSize);
  Printer printer(&fout);

  // Comparison function used to order configurations
  auto compare = [](ConfigDescription c1, ConfigDescription c2) -> bool {
      return c1.compare(c2) < 0;
  };

  // Insert the configurations into a set in order to keep every configuarion seen
  std::set<ConfigDescription, decltype(compare)> configs(compare);
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        for (auto& value : entry->values) {
          configs.insert(value->config);
        }
      }
    }
  }

  // Print the configurations in order
  for (auto& config : configs) {
    printer.Print(StringPrintf("%s\n", config.to_string().data()));
  }

  return 0;
}

int DumpStringsCommand::Action(const std::vector<std::string>& args) {
  DumpContext context;
  if (args.size() < 1) {
    diag_->Error(DiagMessage() << "No dump apk specified.");
    return 1;
  }

  io::FileOutputStream fout(STDOUT_FILENO, kStdOutBufferSize);
  Printer printer(&fout);

  for (auto apk : args) {
    auto loaded_apk = LoadedApk::LoadApkFromPath(apk, diag_);
    if (!loaded_apk) {
      return 1;
    }

    ResourceTable* table = loaded_apk->GetResourceTable();
    if (!table) {
      diag_->Error(DiagMessage() << "Failed to retrieve resource table.");
      return 1;
    }

    // Load the run-time xml string pool using the flattened data
    BigBuffer buffer(4096);
    StringPool::FlattenUtf8(&buffer, table->string_pool, context.GetDiagnostics());
    auto data = buffer.to_string();
    android::ResStringPool pool(data.data(), data.size(), false);
    Debug::DumpResStringPool(&pool, &printer);
  }

  return 0;
}

int DumpTableCommand::Action(const std::vector<std::string>& args) {
  if (args.size() < 1) {
    diag_->Error(DiagMessage() << "No dump apk specified.");
    return 1;
  }

  io::FileOutputStream fout(STDOUT_FILENO, kStdOutBufferSize);
  Printer printer(&fout);

  DebugPrintTableOptions print_options;
  print_options.show_sources = true;
  print_options.show_values = !no_values_;

  for (auto apk : args) {
    auto loaded_apk = LoadedApk::LoadApkFromPath(apk, diag_);
    if (!loaded_apk) {
      return 1;
    }

    if (loaded_apk->GetApkFormat() == ApkFormat::kProto) {
      printer.Println("Proto APK");
    } else {
      printer.Println("Binary APK");
    }

    ResourceTable* table = loaded_apk->GetResourceTable();
    if (!table) {
      diag_->Error(DiagMessage() << "Failed to retrieve resource table.");
      return 1;
    }

    Debug::PrintTable(*table, print_options, &printer);
  }

  return 0;
}

int DumpXmlTreeCommand::Action(const std::vector<std::string>& args) {
  if (args.size() < 1) {
    diag_->Error(DiagMessage() << "No dump apk specified");
    return 1;
  }

  auto loaded_apk = LoadedApk::LoadApkFromPath(args[0], diag_);
  if (!loaded_apk) {
    return 1;
  }

  io::FileOutputStream fout(STDOUT_FILENO, kStdOutBufferSize);
  Printer printer(&fout);

  // Dump the xml tree of every passed in file
  for (auto file : files_) {
    auto xml = loaded_apk->LoadXml(file, diag_);
    if (!xml) {
      return 1;
    }

    Debug::DumpXml(*xml, &printer);
  }

  return 0;
}

int DumpXmlStringsCommand::Action(const std::vector<std::string>& args) {
  DumpContext context;
  if (args.size() < 1) {
    diag_->Error(DiagMessage() << "No dump apk specified.");
    return 1;
  }

  auto loaded_apk = LoadedApk::LoadApkFromPath(args[0], diag_);
  if (!loaded_apk) {
    return 1;
  }

  io::FileOutputStream fout(STDOUT_FILENO, kStdOutBufferSize);
  Printer printer(&fout);

  // Dump the xml strings of every passed in file
  for (auto xml_file : files_) {
    android::ResXMLTree tree;

    if (loaded_apk->GetApkFormat() == ApkFormat::kProto) {
      auto xml = loaded_apk->LoadXml(xml_file, diag_);
      if (!xml) {
        return 1;
      }

      // Flatten the xml document to get a binary representation of the proto xml file
      BigBuffer buffer(4096);
      XmlFlattenerOptions options = {};
      options.keep_raw_values = true;
      XmlFlattener flattener(&buffer, options);
      if (!flattener.Consume(&context, xml.get())) {
        return 1;
      }

      // Load the run-time xml tree using the flattened data
      std::string data = buffer.to_string();
      tree.setTo(data.data(), data.size(), /** copyData */ true);

    } else if (loaded_apk->GetApkFormat() == ApkFormat::kBinary) {
      io::IFile* file = loaded_apk->GetFileCollection()->FindFile(xml_file);
      if (!file) {
        diag_->Error(DiagMessage(xml_file) << "file '" << xml_file << "' not found in APK");
        return 1;
      }

      std::unique_ptr<io::IData> data = file->OpenAsData();
      if (!data) {
        diag_->Error(DiagMessage() << "failed to open file");
        return 1;
      }

      // Load the run-time xml tree from the file data
      tree.setTo(data->data(), data->size(), /** copyData */ true);
    }

    Debug::DumpResStringPool(&tree.getStrings(), &printer);
  }

  return 0;
}

int DumpPackageNameCommand::Action(const std::vector<std::string>& args) {
  if (args.size() < 1) {
    diag_->Error(DiagMessage() << "No dump apk specified.");
    return 1;
  }

  auto loaded_apk = LoadedApk::LoadApkFromPath(args[0], diag_);
  if (!loaded_apk) {
    return 1;
  }

  io::FileOutputStream fout(STDOUT_FILENO, kStdOutBufferSize);
  Printer printer(&fout);

  xml::Element* manifest_el = loaded_apk->GetManifest()->root.get();
  if (!manifest_el) {
    diag_->Error(DiagMessage() << "No AndroidManifest.");
    return 1;
  }

  xml::Attribute* attr = manifest_el->FindAttribute({}, "package");
  if (!attr) {
    diag_->Error(DiagMessage() << "No package name.");
    return 1;
  }
  printer.Println(StringPrintf("%s", attr->value.c_str()));

  return 0;
}

/** Preform no action because a subcommand is required. */
int DumpCommand::Action(const std::vector<std::string>& args) {
  if (args.size() == 0) {
    diag_->Error(DiagMessage() << "no subcommand specified");
  } else {
    diag_->Error(DiagMessage() << "unknown subcommand '" << args[0] << "'");
  }

  Usage(&std::cerr);
  return 1;
}

}  // namespace aapt
