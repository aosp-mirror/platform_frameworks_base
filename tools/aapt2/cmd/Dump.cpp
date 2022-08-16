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
#include "androidfw/ConfigDescription.h"
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

  const std::set<std::string>& GetSplitNameDependencies() override {
    UNIMPLEMENTED(FATAL) << "Split Name Dependencies should not be necessary";
    static std::set<std::string> empty;
    return empty;
  }

 private:
  StdErrDiagnostics diagnostics_;
  bool verbose_ = false;
};

}  // namespace

int DumpAPCCommand::Action(const std::vector<std::string>& args) {
  DumpContext context;
  DebugPrintTableOptions print_options;
  print_options.show_sources = true;
  print_options.show_values = !no_values_;

  if (args.size() < 1) {
    diag_->Error(DiagMessage() << "No dump container specified");
    return 1;
  }

  bool error = false;
  for (auto container : args) {
    io::FileInputStream input(container);
    if (input.HadError()) {
      context.GetDiagnostics()->Error(DiagMessage(container)
                                      << "failed to open file: " << input.GetError());
      error = true;
      continue;
    }

    // Try as a compiled file.
    ContainerReader reader(&input);
    if (reader.HadError()) {
      context.GetDiagnostics()->Error(DiagMessage(container)
                                      << "failed to read container: " << reader.GetError());
      error = true;
      continue;
    }

    printer_->Println("AAPT2 Container (APC)");
    ContainerReaderEntry* entry;
    std::string error;
    while ((entry = reader.Next()) != nullptr) {
      if (entry->Type() == ContainerEntryType::kResTable) {
        printer_->Println("kResTable");

        pb::ResourceTable pb_table;
        if (!entry->GetResTable(&pb_table)) {
          context.GetDiagnostics()->Error(DiagMessage(container)
                                          << "failed to parse proto table: " << entry->GetError());
          error = true;
          continue;
        }

        ResourceTable table;
        error.clear();
        if (!DeserializeTableFromPb(pb_table, nullptr /*files*/, &table, &error)) {
          context.GetDiagnostics()->Error(DiagMessage(container)
                                          << "failed to parse table: " << error);
          error = true;
          continue;
        }

        printer_->Indent();
        Debug::PrintTable(table, print_options, printer_);
        printer_->Undent();
      } else if (entry->Type() == ContainerEntryType::kResFile) {
        printer_->Println("kResFile");
        pb::internal::CompiledFile pb_compiled_file;
        off64_t offset;
        size_t length;
        if (!entry->GetResFileOffsets(&pb_compiled_file, &offset, &length)) {
          context.GetDiagnostics()->Error(DiagMessage(container)
                                          << "failed to parse compiled proto file: "
                                          << entry->GetError());
          error = true;
          continue;
        }

        ResourceFile file;
        if (!DeserializeCompiledFileFromPb(pb_compiled_file, &file, &error)) {
          context.GetDiagnostics()->Warn(DiagMessage(container)
                                         << "failed to parse compiled file: " << error);
          error = true;
          continue;
        }

        printer_->Indent();
        DumpCompiledFile(file, Source(container), offset, length, printer_);
        printer_->Undent();
      }
    }
  }

  return (error) ? 1 : 0;
}

int DumpBadgerCommand::Action(const std::vector<std::string>& args) {
  printer_->Print(StringPrintf("%s", kBadgerData));
  printer_->Print("Did you mean \"aapt2 dump badging\"?\n");
  return 1;
}

int DumpConfigsCommand::Dump(LoadedApk* apk) {
  ResourceTable* table = apk->GetResourceTable();
  if (!table) {
    GetDiagnostics()->Error(DiagMessage() << "Failed to retrieve resource table");
    return 1;
  }

  // Comparison function used to order configurations
  auto compare = [](android::ConfigDescription c1, android::ConfigDescription c2) -> bool {
    return c1.compare(c2) < 0;
  };

  // Insert the configurations into a set in order to keep every configuarion seen
  std::set<android::ConfigDescription, decltype(compare)> configs(compare);
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
    GetPrinter()->Print(StringPrintf("%s\n", config.to_string().data()));
  }
  return 0;
}

int DumpPackageNameCommand::Dump(LoadedApk* apk) {
  auto package_name = GetPackageName(apk);
  if (!package_name.has_value()) {
    return 1;
  }
  GetPrinter()->Println(*package_name);
  return 0;
}

int DumpStringsCommand::Dump(LoadedApk* apk) {
  ResourceTable* table = apk->GetResourceTable();
  if (!table) {
    GetDiagnostics()->Error(DiagMessage() << "Failed to retrieve resource table");
    return 1;
  }

  // Load the run-time xml string pool using the flattened data
  BigBuffer buffer(4096);
  StringPool::FlattenUtf8(&buffer, table->string_pool, GetDiagnostics());
  auto data = buffer.to_string();
  android::ResStringPool pool(data.data(), data.size(), false);
  Debug::DumpResStringPool(&pool, GetPrinter());
  return 0;
}

int DumpStyleParentCommand::Dump(LoadedApk* apk) {
  auto package_name = GetPackageName(apk);
  if (!package_name.has_value()) {
    return 1;
  }

  const auto target_style = ResourceName(*package_name, ResourceType::kStyle, style_);
  const auto table = apk->GetResourceTable();

  if (!table) {
    GetDiagnostics()->Error(DiagMessage() << "Failed to retrieve resource table");
    return 1;
  }

  std::optional<ResourceTable::SearchResult> target = table->FindResource(target_style);
  if (!target) {
    GetDiagnostics()->Error(
        DiagMessage() << "Target style \"" << target_style.entry << "\" does not exist");
    return 1;
  }

  Debug::PrintStyleGraph(table, target_style);
  return 0;
}

int DumpTableCommand::Dump(LoadedApk* apk) {
  if (apk->GetApkFormat() == ApkFormat::kProto) {
    GetPrinter()->Println("Proto APK");
  } else {
    GetPrinter()->Println("Binary APK");
  }

  ResourceTable* table = apk->GetResourceTable();
  if (!table) {
    GetDiagnostics()->Error(DiagMessage() << "Failed to retrieve resource table");
    return 1;
  }

  DebugPrintTableOptions print_options;
  print_options.show_sources = true;
  print_options.show_values = !no_values_;
  Debug::PrintTable(*table, print_options, GetPrinter());
  return 0;
}

int DumpXmlStringsCommand::Dump(LoadedApk* apk) {
  DumpContext context;
  bool error = false;
  for (auto xml_file : files_) {
    android::ResXMLTree tree;

    if (apk->GetApkFormat() == ApkFormat::kProto) {
      auto xml = apk->LoadXml(xml_file, GetDiagnostics());
      if (!xml) {
        error = true;
        continue;
      }

      // Flatten the xml document to get a binary representation of the proto xml file
      BigBuffer buffer(4096);
      XmlFlattenerOptions options = {};
      options.keep_raw_values = true;
      XmlFlattener flattener(&buffer, options);
      if (!flattener.Consume(&context, xml.get())) {
        error = true;
        continue;
      }

      // Load the run-time xml tree using the flattened data
      std::string data = buffer.to_string();
      tree.setTo(data.data(), data.size(), /** copyData */ true);

    } else if (apk->GetApkFormat() == ApkFormat::kBinary) {
      io::IFile* file = apk->GetFileCollection()->FindFile(xml_file);
      if (!file) {
        GetDiagnostics()->Error(DiagMessage(xml_file)
                                << "File '" << xml_file << "' not found in APK");
        error = true;
        continue;
      }

      std::unique_ptr<io::IData> data = file->OpenAsData();
      if (!data) {
        GetDiagnostics()->Error(DiagMessage() << "Failed to open " << xml_file);
        error = true;
        continue;
      }

      // Load the run-time xml tree from the file data
      tree.setTo(data->data(), data->size(), /** copyData */ true);
    } else {
      GetDiagnostics()->Error(DiagMessage(apk->GetSource()) << "Unknown APK format");
      error = true;
      continue;
    }

    Debug::DumpResStringPool(&tree.getStrings(), GetPrinter());
  }
  return (error) ? 1 : 0;
}

int DumpXmlTreeCommand::Dump(LoadedApk* apk) {
  for (auto file : files_) {
    auto xml = apk->LoadXml(file, GetDiagnostics());
    if (!xml) {
      return 1;
    }
    Debug::DumpXml(*xml, GetPrinter());
  }
  return 0;
}

int DumpOverlayableCommand::Dump(LoadedApk* apk) {
  ResourceTable* table = apk->GetResourceTable();
  if (!table) {
    GetDiagnostics()->Error(DiagMessage() << "Failed to retrieve resource table");
    return 1;
  }

  Debug::DumpOverlayable(*table, GetPrinter());
  return 0;
}

const char DumpBadgerCommand::kBadgerData[2925] = {
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  95,  46,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  61,  63,  86,  35,  40,  46,  46,
    95,  95,  95,  95,  97,  97,  44,  32,  46,  124, 42,  33,  83,  62,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  58,  46,  58,  59,  61,  59,  61,  81,  81,  81,  81,  66,  96,  61,  61,  58,  46,
    46,  46,  58,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  46,  61,  59,  59,  59,  58,  106, 81,  81,
    81,  81,  102, 59,  61,  59,  59,  61,  61,  61,  58,  46,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    61,  59,  59,  59,  58,  109, 81,  81,  81,  81,  61,  59,  59,  59,  59,  59,  58,  59,  59,
    46,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  46,  61,  59,  59,  59,  60,  81,  81,  81,  81,  87,  58,
    59,  59,  59,  59,  59,  59,  61,  119, 44,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  46,  47,  61,  59,  59,
    58,  100, 81,  81,  81,  81,  35,  58,  59,  59,  59,  59,  59,  58,  121, 81,  91,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  46,  109, 58,  59,  59,  61,  81,  81,  81,  81,  81,  109, 58,  59,  59,  59,
    59,  61,  109, 81,  81,  76,  46,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  41,  87,  59,  61,  59,  41,  81,  81,
    81,  81,  81,  81,  59,  61,  59,  59,  58,  109, 81,  81,  87,  39,  46,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    60,  81,  91,  59,  59,  61,  81,  81,  81,  81,  81,  87,  43,  59,  58,  59,  60,  81,  81,
    81,  76,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  52,  91,  58,  45,  59,  87,  81,  81,  81,  81,
    70,  58,  58,  58,  59,  106, 81,  81,  81,  91,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  93,  40,
    32,  46,  59,  100, 81,  81,  81,  81,  40,  58,  46,  46,  58,  100, 81,  81,  68,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  46,  46,  46,  32,  46,
    46,  46,  32,  46,  32,  46,  45,  91,  59,  61,  58,  109, 81,  81,  81,  87,  46,  58,  61,
    59,  60,  81,  81,  80,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  46,  46,
    61,  59,  61,  61,  61,  59,  61,  61,  59,  59,  59,  58,  58,  46,  46,  41,  58,  59,  58,
    81,  81,  81,  81,  69,  58,  59,  59,  60,  81,  81,  68,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,
    32,  32,  32,  32,  58,  59,  61,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,
    59,  59,  61,  61,  46,  61,  59,  93,  81,  81,  81,  81,  107, 58,  59,  58,  109, 87,  68,
    96,  32,  32,  32,  46,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  10,  32,  32,  32,  46,  60,  61,  61,  59,  59,  59,  59,  59,  59,
    59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  58,  58,  58,  115, 109, 68,  41,  36,
    81,  109, 46,  61,  61,  81,  69,  96,  46,  58,  58,  46,  58,  46,  46,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  46,  32,  95,  81,  67,
    61,  61,  58,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,
    59,  59,  58,  68,  39,  61,  105, 61,  63,  81,  119, 58,  106, 80,  32,  58,  61,  59,  59,
    61,  59,  61,  59,  61,  46,  95,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  10,  32,  32,  36,  81,  109, 105, 59,  61,  59,  59,  59,  59,  59,  59,  59,  59,
    59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  46,  58,  37,  73,  108, 108, 62,  52,  81,
    109, 34,  32,  61,  59,  59,  59,  59,  59,  59,  59,  59,  59,  61,  59,  61,  61,  46,  46,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  46,  45,  57,  101, 43,  43,  61,
    61,  59,  59,  59,  59,  59,  59,  61,  59,  59,  59,  59,  59,  59,  59,  59,  59,  58,  97,
    46,  61,  108, 62,  126, 58,  106, 80,  96,  46,  61,  61,  59,  59,  59,  59,  59,  59,  59,
    59,  59,  59,  59,  59,  59,  61,  61,  97,  103, 97,  32,  32,  32,  32,  32,  32,  32,  10,
    32,  32,  32,  32,  45,  46,  32,  46,  32,  32,  32,  32,  32,  32,  32,  32,  45,  45,  45,
    58,  59,  59,  59,  59,  61,  119, 81,  97,  124, 105, 124, 124, 39,  126, 95,  119, 58,  61,
    58,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  61,  119, 81,  81,
    99,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  58,  59,  59,  58,  106, 81,  81,  81,  109, 119,
    119, 119, 109, 109, 81,  81,  122, 58,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,
    59,  59,  59,  58,  115, 81,  87,  81,  102, 32,  32,  32,  32,  32,  32,  10,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  61,  58,
    59,  61,  81,  81,  81,  81,  81,  81,  87,  87,  81,  81,  81,  81,  81,  58,  59,  59,  59,
    59,  59,  59,  59,  59,  58,  45,  45,  45,  59,  59,  59,  41,  87,  66,  33,  32,  32,  32,
    32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  58,  59,  59,  93,  81,  81,  81,  81,  81,  81,  81,  81,  81,
    81,  81,  81,  81,  40,  58,  59,  59,  59,  58,  45,  32,  46,  32,  32,  32,  32,  32,  46,
    32,  126, 96,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  58,  61,  59,  58,  81,
    81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  40,  58,  59,  59,  59,  58,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  58,  59,  59,  58,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,
    81,  40,  58,  59,  59,  59,  46,  46,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  58,  61,  59,  60,  81,  81,  81,  81,
    81,  81,  81,  81,  81,  81,  81,  81,  81,  59,  61,  59,  59,  61,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    58,  59,  59,  93,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  40,  59,
    59,  59,  59,  32,  46,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  58,  61,  58,  106, 81,  81,  81,  81,  81,  81,  81,
    81,  81,  81,  81,  81,  81,  76,  58,  59,  59,  59,  32,  46,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  61,  58,  58,
    81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  87,  58,  59,  59,  59,  59,
    32,  46,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  58,  59,  61,  41,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,
    81,  81,  87,  59,  61,  58,  59,  59,  46,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  58,  61,  58,  61,  81,  81,
    81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  107, 58,  59,  59,  59,  59,  58,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  58,  59,  59,  58,  51,  81,  81,  81,  81,  81,  81,  81,  81,  81,  81,  102, 94,
    59,  59,  59,  59,  59,  61,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  58,  61,  59,  59,  59,  43,  63,  36,  81,
    81,  81,  87,  64,  86,  102, 58,  59,  59,  59,  59,  59,  59,  59,  46,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  46,  61,
    59,  59,  59,  59,  59,  59,  59,  43,  33,  58,  126, 126, 58,  59,  59,  59,  59,  59,  59,
    59,  59,  59,  59,  32,  46,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  46,  61,  59,  59,  59,  58,  45,  58,  61,  59,  58,  58,  58,  61,
    59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  59,  58,  32,  46,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  46,  61,  59,  59,  59,  59,  59,
    58,  95,  32,  45,  61,  59,  61,  59,  59,  59,  59,  59,  59,  59,  45,  58,  59,  59,  59,
    59,  61,  58,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  58,  61,  59,  59,  59,  59,  59,  61,  59,  61,  46,  46,  32,  45,  45,  45,  59,  58,
    45,  45,  46,  58,  59,  59,  59,  59,  59,  59,  61,  46,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  46,  58,  59,  59,  59,  59,  59,  59,  59,  59,  59,
    61,  59,  46,  32,  32,  46,  32,  46,  32,  58,  61,  59,  59,  59,  59,  59,  59,  59,  59,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  45,
    59,  59,  59,  59,  59,  59,  59,  59,  58,  32,  32,  32,  32,  32,  32,  32,  32,  32,  61,
    59,  59,  59,  59,  59,  59,  59,  58,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  46,  61,  59,  59,  59,  59,  59,  59,  59,  32,  46,  32,
    32,  32,  32,  32,  32,  61,  46,  61,  59,  59,  59,  59,  59,  59,  58,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  61,  59,  59,  59,
    59,  59,  59,  59,  59,  32,  46,  32,  32,  32,  32,  32,  32,  32,  46,  61,  58,  59,  59,
    59,  59,  59,  58,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  58,  59,  59,  59,  59,  59,  59,  59,  59,  46,  46,  32,  32,  32,  32,
    32,  32,  32,  61,  59,  59,  59,  59,  59,  59,  59,  45,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  46,  32,  45,  61,  59,  59,  59,  59,
    59,  58,  32,  46,  32,  32,  32,  32,  32,  32,  32,  58,  59,  59,  59,  59,  59,  58,  45,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  45,  45,  45,  45,  32,  46,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  45,  61,  59,  58,  45,  45,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  10,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  46,  32,  32,  46,  32,  32,  32,  32,  32,  32,
    32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  32,  10};

int DumpChunks::Dump(LoadedApk* apk) {
  auto file = apk->GetFileCollection()->FindFile("resources.arsc");
  if (!file) {
    GetDiagnostics()->Error(DiagMessage() << "Failed to find resources.arsc in APK");
    return 1;
  }

  auto data = file->OpenAsData();
  if (!data) {
    GetDiagnostics()->Error(DiagMessage() << "Failed to open resources.arsc ");
    return 1;
  }

  Debug::DumpChunks(data->data(), data->size(), GetPrinter(), GetDiagnostics());
  return 0;
}

}  // namespace aapt
