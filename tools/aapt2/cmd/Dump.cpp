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

#include <cinttypes>
#include <vector>

#include "android-base/stringprintf.h"
#include "androidfw/StringPiece.h"

#include "Debug.h"
#include "Diagnostics.h"
#include "Flags.h"
#include "format/Container.h"
#include "format/binary/BinaryResourceParser.h"
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

static bool TryDumpFile(IAaptContext* context, const std::string& file_path) {
  // Use a smaller buffer so that there is less latency for dumping to stdout.
  constexpr size_t kStdOutBufferSize = 1024u;
  io::FileOutputStream fout(STDOUT_FILENO, kStdOutBufferSize);
  Printer printer(&fout);

  DebugPrintTableOptions print_options;
  print_options.show_sources = true;

  std::string err;
  std::unique_ptr<io::ZipFileCollection> zip = io::ZipFileCollection::Create(file_path, &err);
  if (zip) {
    ResourceTable table;
    if (io::IFile* file = zip->FindFile("resources.pb")) {
      std::unique_ptr<io::IData> data = file->OpenAsData();
      if (data == nullptr) {
        context->GetDiagnostics()->Error(DiagMessage(file_path) << "failed to open resources.pb");
        return false;
      }

      pb::ResourceTable pb_table;
      if (!pb_table.ParseFromArray(data->data(), data->size())) {
        context->GetDiagnostics()->Error(DiagMessage(file_path) << "invalid resources.pb");
        return false;
      }

      if (!DeserializeTableFromPb(pb_table, zip.get(), &table, &err)) {
        context->GetDiagnostics()->Error(DiagMessage(file_path)
                                         << "failed to parse table: " << err);
        return false;
      }

      printer.Println("Proto APK");
    } else if (io::IFile* file = zip->FindFile("resources.arsc")) {
      std::unique_ptr<io::IData> data = file->OpenAsData();
      if (!data) {
        context->GetDiagnostics()->Error(DiagMessage(file_path) << "failed to open resources.arsc");
        return false;
      }

      BinaryResourceParser parser(context->GetDiagnostics(), &table, Source(file_path),
                                  data->data(), data->size());
      if (!parser.Parse()) {
        return false;
      }

      printer.Println("Binary APK");
    }

    Debug::PrintTable(table, print_options, &printer);
    return true;
  }

  err.clear();

  io::FileInputStream input(file_path);
  if (input.HadError()) {
    context->GetDiagnostics()->Error(DiagMessage(file_path)
                                     << "failed to open file: " << input.GetError());
    return false;
  }

  // Try as a compiled file.
  ContainerReader reader(&input);
  if (reader.HadError()) {
    context->GetDiagnostics()->Error(DiagMessage(file_path)
                                     << "failed to read container: " << reader.GetError());
    return false;
  }

  printer.Println("AAPT2 Container (APC)");
  ContainerReaderEntry* entry;
  while ((entry = reader.Next()) != nullptr) {
    if (entry->Type() == ContainerEntryType::kResTable) {
      printer.Println("kResTable");

      pb::ResourceTable pb_table;
      if (!entry->GetResTable(&pb_table)) {
        context->GetDiagnostics()->Error(DiagMessage(file_path)
                                         << "failed to parse proto table: " << entry->GetError());
        continue;
      }

      ResourceTable table;
      err.clear();
      if (!DeserializeTableFromPb(pb_table, nullptr /*files*/, &table, &err)) {
        context->GetDiagnostics()->Error(DiagMessage(file_path)
                                         << "failed to parse table: " << err);
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
        context->GetDiagnostics()->Error(
            DiagMessage(file_path) << "failed to parse compiled proto file: " << entry->GetError());
        continue;
      }

      ResourceFile file;
      std::string error;
      if (!DeserializeCompiledFileFromPb(pb_compiled_file, &file, &error)) {
        context->GetDiagnostics()->Warn(DiagMessage(file_path)
                                        << "failed to parse compiled file: " << error);
        continue;
      }

      printer.Indent();
      DumpCompiledFile(file, Source(file_path), offset, length, &printer);
      printer.Undent();
    }
  }
  return true;
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

// Entry point for dump command.
int Dump(const std::vector<StringPiece>& args) {
  bool verbose = false;
  Flags flags = Flags().OptionalSwitch("-v", "increase verbosity of output", &verbose);
  if (!flags.Parse("aapt2 dump", args, &std::cerr)) {
    return 1;
  }

  DumpContext context;
  context.SetVerbose(verbose);

  for (const std::string& arg : flags.GetArgs()) {
    if (!TryDumpFile(&context, arg)) {
      return 1;
    }
  }
  return 0;
}

}  // namespace aapt
