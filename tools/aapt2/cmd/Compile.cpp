/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include <string>

#include "android-base/errors.h"
#include "android-base/file.h"
#include "android-base/utf8.h"
#include "androidfw/StringPiece.h"
#include "google/protobuf/io/coded_stream.h"
#include "google/protobuf/io/zero_copy_stream_impl_lite.h"

#include "ConfigDescription.h"
#include "Diagnostics.h"
#include "Flags.h"
#include "ResourceParser.h"
#include "ResourceTable.h"
#include "compile/IdAssigner.h"
#include "compile/InlineXmlFormatParser.h"
#include "compile/Png.h"
#include "compile/PseudolocaleGenerator.h"
#include "compile/XmlIdCollector.h"
#include "format/Archive.h"
#include "format/Container.h"
#include "format/proto/ProtoSerialize.h"
#include "io/BigBufferStream.h"
#include "io/FileStream.h"
#include "io/StringStream.h"
#include "io/Util.h"
#include "util/Files.h"
#include "util/Maybe.h"
#include "util/Util.h"
#include "xml/XmlDom.h"
#include "xml/XmlPullParser.h"

using ::aapt::io::FileInputStream;
using ::aapt::text::Printer;
using ::android::StringPiece;
using ::android::base::SystemErrorCodeToString;
using ::google::protobuf::io::CopyingOutputStreamAdaptor;

namespace aapt {

struct ResourcePathData {
  Source source;
  std::string resource_dir;
  std::string name;
  std::string extension;

  // Original config str. We keep this because when we parse the config, we may add on
  // version qualifiers. We want to preserve the original input so the output is easily
  // computed before hand.
  std::string config_str;
  ConfigDescription config;
};

// Resource file paths are expected to look like: [--/res/]type[-config]/name
static Maybe<ResourcePathData> ExtractResourcePathData(const std::string& path,
                                                       std::string* out_error) {
  std::vector<std::string> parts = util::Split(path, file::sDirSep);
  if (parts.size() < 2) {
    if (out_error) *out_error = "bad resource path";
    return {};
  }

  std::string& dir = parts[parts.size() - 2];
  StringPiece dir_str = dir;

  StringPiece config_str;
  ConfigDescription config;
  size_t dash_pos = dir.find('-');
  if (dash_pos != std::string::npos) {
    config_str = dir_str.substr(dash_pos + 1, dir.size() - (dash_pos + 1));
    if (!ConfigDescription::Parse(config_str, &config)) {
      if (out_error) {
        std::stringstream err_str;
        err_str << "invalid configuration '" << config_str << "'";
        *out_error = err_str.str();
      }
      return {};
    }
    dir_str = dir_str.substr(0, dash_pos);
  }

  std::string& filename = parts[parts.size() - 1];
  StringPiece name = filename;
  StringPiece extension;

  const std::string kNinePng = ".9.png";
  if (filename.size() > kNinePng.size()
      && std::equal(kNinePng.rbegin(), kNinePng.rend(), filename.rbegin())) {
    // Split on .9.png if this extension is present at the end of the file path
    name = name.substr(0, filename.size() - kNinePng.size());
    extension = "9.png";
  } else {
    // Split on the last period occurrence
    size_t dot_pos = filename.rfind('.');
    if (dot_pos != std::string::npos) {
      extension = name.substr(dot_pos + 1, filename.size() - (dot_pos + 1));
      name = name.substr(0, dot_pos);
    }
  }

  return ResourcePathData{Source(path),          dir_str.to_string(),    name.to_string(),
                          extension.to_string(), config_str.to_string(), config};
}

struct CompileOptions {
  std::string output_path;
  Maybe<std::string> res_dir;
  Maybe<std::string> generate_text_symbols_path;
  bool pseudolocalize = false;
  bool no_png_crunch = false;
  bool legacy_mode = false;
  bool verbose = false;
};

static std::string BuildIntermediateContainerFilename(const ResourcePathData& data) {
  std::stringstream name;
  name << data.resource_dir;
  if (!data.config_str.empty()) {
    name << "-" << data.config_str;
  }
  name << "_" << data.name;
  if (!data.extension.empty()) {
    name << "." << data.extension;
  }
  name << ".flat";
  return name.str();
}

static bool IsHidden(const StringPiece& filename) {
  return util::StartsWith(filename, ".");
}

// Walks the res directory structure, looking for resource files.
static bool LoadInputFilesFromDir(IAaptContext* context, const CompileOptions& options,
                                  std::vector<ResourcePathData>* out_path_data) {
  const std::string& root_dir = options.res_dir.value();
  std::unique_ptr<DIR, decltype(closedir)*> d(opendir(root_dir.data()), closedir);
  if (!d) {
    context->GetDiagnostics()->Error(DiagMessage(root_dir) << "failed to open directory: "
                                                           << SystemErrorCodeToString(errno));
    return false;
  }

  while (struct dirent* entry = readdir(d.get())) {
    if (IsHidden(entry->d_name)) {
      continue;
    }

    std::string prefix_path = root_dir;
    file::AppendPath(&prefix_path, entry->d_name);

    if (file::GetFileType(prefix_path) != file::FileType::kDirectory) {
      continue;
    }

    std::unique_ptr<DIR, decltype(closedir)*> subdir(opendir(prefix_path.data()), closedir);
    if (!subdir) {
      context->GetDiagnostics()->Error(DiagMessage(prefix_path) << "failed to open directory: "
                                                                << SystemErrorCodeToString(errno));
      return false;
    }

    while (struct dirent* leaf_entry = readdir(subdir.get())) {
      if (IsHidden(leaf_entry->d_name)) {
        continue;
      }

      std::string full_path = prefix_path;
      file::AppendPath(&full_path, leaf_entry->d_name);

      std::string err_str;
      Maybe<ResourcePathData> path_data = ExtractResourcePathData(full_path, &err_str);
      if (!path_data) {
        context->GetDiagnostics()->Error(DiagMessage(full_path) << err_str);
        return false;
      }

      out_path_data->push_back(std::move(path_data.value()));
    }
  }

  // File-system directory enumeration order is platform-dependent. Sort the result to remove any
  // inconsistencies between platforms.
  std::sort(
      out_path_data->begin(), out_path_data->end(),
      [](const ResourcePathData& a, const ResourcePathData& b) { return a.source < b.source; });
  return true;
}

static bool CompileTable(IAaptContext* context, const CompileOptions& options,
                         const ResourcePathData& path_data, IArchiveWriter* writer,
                         const std::string& output_path) {
  ResourceTable table;
  {
    FileInputStream fin(path_data.source.path);
    if (fin.HadError()) {
      context->GetDiagnostics()->Error(DiagMessage(path_data.source)
                                       << "failed to open file: " << fin.GetError());
      return false;
    }

    // Parse the values file from XML.
    xml::XmlPullParser xml_parser(&fin);

    ResourceParserOptions parser_options;
    parser_options.error_on_positional_arguments = !options.legacy_mode;

    // If the filename includes donottranslate, then the default translatable is false.
    parser_options.translatable = path_data.name.find("donottranslate") == std::string::npos;

    ResourceParser res_parser(context->GetDiagnostics(), &table, path_data.source, path_data.config,
                              parser_options);
    if (!res_parser.Parse(&xml_parser)) {
      return false;
    }
  }

  if (options.pseudolocalize) {
    // Generate pseudo-localized strings (en-XA and ar-XB).
    // These are created as weak symbols, and are only generated from default
    // configuration
    // strings and plurals.
    PseudolocaleGenerator pseudolocale_generator;
    if (!pseudolocale_generator.Consume(context, &table)) {
      return false;
    }
  }

  // Ensure we have the compilation package at least.
  table.CreatePackage(context->GetCompilationPackage());

  // Assign an ID to any package that has resources.
  for (auto& pkg : table.packages) {
    if (!pkg->id) {
      // If no package ID was set while parsing (public identifiers), auto assign an ID.
      pkg->id = context->GetPackageId();
    }
  }

  // Create the file/zip entry.
  if (!writer->StartEntry(output_path, 0)) {
    context->GetDiagnostics()->Error(DiagMessage(output_path) << "failed to open");
    return false;
  }

  // Make sure CopyingOutputStreamAdaptor is deleted before we call writer->FinishEntry().
  {
    // Wrap our IArchiveWriter with an adaptor that implements the ZeroCopyOutputStream interface.
    CopyingOutputStreamAdaptor copying_adaptor(writer);
    ContainerWriter container_writer(&copying_adaptor, 1u);

    pb::ResourceTable pb_table;
    SerializeTableToPb(table, &pb_table, context->GetDiagnostics());
    if (!container_writer.AddResTableEntry(pb_table)) {
      context->GetDiagnostics()->Error(DiagMessage(output_path) << "failed to write");
      return false;
    }
  }

  if (!writer->FinishEntry()) {
    context->GetDiagnostics()->Error(DiagMessage(output_path) << "failed to finish entry");
    return false;
  }

  if (options.generate_text_symbols_path) {
    io::FileOutputStream fout_text(options.generate_text_symbols_path.value());

    if (fout_text.HadError()) {
      context->GetDiagnostics()->Error(DiagMessage()
                                       << "failed writing to'"
                                       << options.generate_text_symbols_path.value()
                                       << "': " << fout_text.GetError());
      return false;
    }

    Printer r_txt_printer(&fout_text);
    for (const auto& package : table.packages) {
      for (const auto& type : package->types) {
        for (const auto& entry : type->entries) {
          // Check access modifiers.
          switch(entry->visibility.level) {
            case Visibility::Level::kUndefined :
              r_txt_printer.Print("default ");
              break;
            case Visibility::Level::kPublic :
              r_txt_printer.Print("public ");
              break;
            case Visibility::Level::kPrivate :
              r_txt_printer.Print("private ");
          }

          if (type->type != ResourceType::kStyleable) {
            r_txt_printer.Print("int ");
            r_txt_printer.Print(to_string(type->type));
            r_txt_printer.Print(" ");
            r_txt_printer.Println(entry->name);
          } else {
            r_txt_printer.Print("int[] styleable ");
            r_txt_printer.Println(entry->name);

            if (!entry->values.empty()) {
              auto styleable = static_cast<const Styleable*>(entry->values.front()->value.get());
              for (const auto& attr : styleable->entries) {
                r_txt_printer.Print("default int styleable ");
                r_txt_printer.Print(entry->name);
                r_txt_printer.Print("_");
                r_txt_printer.Println(attr.name.value().entry);
              }
            }
          }
        }
      }
    }
  }

  return true;
}

static bool WriteHeaderAndDataToWriter(const StringPiece& output_path, const ResourceFile& file,
                                       io::KnownSizeInputStream* in, IArchiveWriter* writer,
                                       IDiagnostics* diag) {
  // Start the entry so we can write the header.
  if (!writer->StartEntry(output_path, 0)) {
    diag->Error(DiagMessage(output_path) << "failed to open file");
    return false;
  }

  // Make sure CopyingOutputStreamAdaptor is deleted before we call writer->FinishEntry().
  {
    // Wrap our IArchiveWriter with an adaptor that implements the ZeroCopyOutputStream interface.
    CopyingOutputStreamAdaptor copying_adaptor(writer);
    ContainerWriter container_writer(&copying_adaptor, 1u);

    pb::internal::CompiledFile pb_compiled_file;
    SerializeCompiledFileToPb(file, &pb_compiled_file);

    if (!container_writer.AddResFileEntry(pb_compiled_file, in)) {
      diag->Error(DiagMessage(output_path) << "failed to write entry data");
      return false;
    }
  }

  if (!writer->FinishEntry()) {
    diag->Error(DiagMessage(output_path) << "failed to finish writing data");
    return false;
  }
  return true;
}

static bool FlattenXmlToOutStream(const StringPiece& output_path, const xml::XmlResource& xmlres,
                                  ContainerWriter* container_writer, IDiagnostics* diag) {
  pb::internal::CompiledFile pb_compiled_file;
  SerializeCompiledFileToPb(xmlres.file, &pb_compiled_file);

  pb::XmlNode pb_xml_node;
  SerializeXmlToPb(*xmlres.root, &pb_xml_node);

  std::string serialized_xml = pb_xml_node.SerializeAsString();
  io::StringInputStream serialized_in(serialized_xml);

  if (!container_writer->AddResFileEntry(pb_compiled_file, &serialized_in)) {
    diag->Error(DiagMessage(output_path) << "failed to write entry data");
    return false;
  }
  return true;
}

static bool IsValidFile(IAaptContext* context, const std::string& input_path) {
  const file::FileType file_type = file::GetFileType(input_path);
  if (file_type != file::FileType::kRegular && file_type != file::FileType::kSymlink) {
    if (file_type == file::FileType::kDirectory) {
      context->GetDiagnostics()->Error(DiagMessage(input_path)
                                       << "resource file cannot be a directory");
    } else if (file_type == file::FileType::kNonexistant) {
      context->GetDiagnostics()->Error(DiagMessage(input_path) << "file not found");
    } else {
      context->GetDiagnostics()->Error(DiagMessage(input_path)
                                       << "not a valid resource file");
    }
    return false;
  }
  return true;
}

static bool CompileXml(IAaptContext* context, const CompileOptions& options,
                       const ResourcePathData& path_data, IArchiveWriter* writer,
                       const std::string& output_path) {
  if (context->IsVerbose()) {
    context->GetDiagnostics()->Note(DiagMessage(path_data.source) << "compiling XML");
  }

  std::unique_ptr<xml::XmlResource> xmlres;
  {
    FileInputStream fin(path_data.source.path);
    if (fin.HadError()) {
      context->GetDiagnostics()->Error(DiagMessage(path_data.source)
                                       << "failed to open file: " << fin.GetError());
      return false;
    }

    xmlres = xml::Inflate(&fin, context->GetDiagnostics(), path_data.source);
  }

  if (!xmlres) {
    return false;
  }

  xmlres->file.name = ResourceName({}, *ParseResourceType(path_data.resource_dir), path_data.name);
  xmlres->file.config = path_data.config;
  xmlres->file.source = path_data.source;
  xmlres->file.type = ResourceFile::Type::kProtoXml;

  // Collect IDs that are defined here.
  XmlIdCollector collector;
  if (!collector.Consume(context, xmlres.get())) {
    return false;
  }

  // Look for and process any <aapt:attr> tags and create sub-documents.
  InlineXmlFormatParser inline_xml_format_parser;
  if (!inline_xml_format_parser.Consume(context, xmlres.get())) {
    return false;
  }

  // Start the entry so we can write the header.
  if (!writer->StartEntry(output_path, 0)) {
    context->GetDiagnostics()->Error(DiagMessage(output_path) << "failed to open file");
    return false;
  }

  std::vector<std::unique_ptr<xml::XmlResource>>& inline_documents =
      inline_xml_format_parser.GetExtractedInlineXmlDocuments();

  // Make sure CopyingOutputStreamAdaptor is deleted before we call writer->FinishEntry().
  {
    // Wrap our IArchiveWriter with an adaptor that implements the ZeroCopyOutputStream interface.
    CopyingOutputStreamAdaptor copying_adaptor(writer);
    ContainerWriter container_writer(&copying_adaptor, 1u + inline_documents.size());

    if (!FlattenXmlToOutStream(output_path, *xmlres, &container_writer,
                               context->GetDiagnostics())) {
      return false;
    }

    for (const std::unique_ptr<xml::XmlResource>& inline_xml_doc : inline_documents) {
      if (!FlattenXmlToOutStream(output_path, *inline_xml_doc, &container_writer,
                                 context->GetDiagnostics())) {
        return false;
      }
    }
  }

  if (!writer->FinishEntry()) {
    context->GetDiagnostics()->Error(DiagMessage(output_path) << "failed to finish writing data");
    return false;
  }

  if (options.generate_text_symbols_path) {
    io::FileOutputStream fout_text(options.generate_text_symbols_path.value());

    if (fout_text.HadError()) {
      context->GetDiagnostics()->Error(DiagMessage()
                                       << "failed writing to'"
                                       << options.generate_text_symbols_path.value()
                                       << "': " << fout_text.GetError());
      return false;
    }

    Printer r_txt_printer(&fout_text);
    for (const auto res : xmlres->file.exported_symbols) {
      r_txt_printer.Print("default int id ");
      r_txt_printer.Println(res.name.entry);
    }

    // And print ourselves.
    r_txt_printer.Print("default int ");
    r_txt_printer.Print(path_data.resource_dir);
    r_txt_printer.Print(" ");
    r_txt_printer.Println(path_data.name);
  }

  return true;
}

static bool CompilePng(IAaptContext* context, const CompileOptions& options,
                       const ResourcePathData& path_data, IArchiveWriter* writer,
                       const std::string& output_path) {
  if (context->IsVerbose()) {
    context->GetDiagnostics()->Note(DiagMessage(path_data.source) << "compiling PNG");
  }

  BigBuffer buffer(4096);
  ResourceFile res_file;
  res_file.name = ResourceName({}, *ParseResourceType(path_data.resource_dir), path_data.name);
  res_file.config = path_data.config;
  res_file.source = path_data.source;
  res_file.type = ResourceFile::Type::kPng;

  {
    std::string content;
    if (!android::base::ReadFileToString(path_data.source.path, &content,
                                         true /*follow_symlinks*/)) {
      context->GetDiagnostics()->Error(DiagMessage(path_data.source)
                                       << "failed to open file: "
                                       << SystemErrorCodeToString(errno));
      return false;
    }

    BigBuffer crunched_png_buffer(4096);
    io::BigBufferOutputStream crunched_png_buffer_out(&crunched_png_buffer);

    // Ensure that we only keep the chunks we care about if we end up
    // using the original PNG instead of the crunched one.
    PngChunkFilter png_chunk_filter(content);
    std::unique_ptr<Image> image = ReadPng(context, path_data.source, &png_chunk_filter);
    if (!image) {
      return false;
    }

    std::unique_ptr<NinePatch> nine_patch;
    if (path_data.extension == "9.png") {
      std::string err;
      nine_patch = NinePatch::Create(image->rows.get(), image->width, image->height, &err);
      if (!nine_patch) {
        context->GetDiagnostics()->Error(DiagMessage() << err);
        return false;
      }

      // Remove the 1px border around the NinePatch.
      // Basically the row array is shifted up by 1, and the length is treated
      // as height - 2.
      // For each row, shift the array to the left by 1, and treat the length as
      // width - 2.
      image->width -= 2;
      image->height -= 2;
      memmove(image->rows.get(), image->rows.get() + 1, image->height * sizeof(uint8_t**));
      for (int32_t h = 0; h < image->height; h++) {
        memmove(image->rows[h], image->rows[h] + 4, image->width * 4);
      }

      if (context->IsVerbose()) {
        context->GetDiagnostics()->Note(DiagMessage(path_data.source) << "9-patch: "
                                                                      << *nine_patch);
      }
    }

    // Write the crunched PNG.
    if (!WritePng(context, image.get(), nine_patch.get(), &crunched_png_buffer_out, {})) {
      return false;
    }

    if (nine_patch != nullptr ||
        crunched_png_buffer_out.ByteCount() <= png_chunk_filter.ByteCount()) {
      // No matter what, we must use the re-encoded PNG, even if it is larger.
      // 9-patch images must be re-encoded since their borders are stripped.
      buffer.AppendBuffer(std::move(crunched_png_buffer));
    } else {
      // The re-encoded PNG is larger than the original, and there is
      // no mandatory transformation. Use the original.
      if (context->IsVerbose()) {
        context->GetDiagnostics()->Note(DiagMessage(path_data.source)
                                        << "original PNG is smaller than crunched PNG"
                                        << ", using original");
      }

      png_chunk_filter.Rewind();
      BigBuffer filtered_png_buffer(4096);
      io::BigBufferOutputStream filtered_png_buffer_out(&filtered_png_buffer);
      io::Copy(&filtered_png_buffer_out, &png_chunk_filter);
      buffer.AppendBuffer(std::move(filtered_png_buffer));
    }

    if (context->IsVerbose()) {
      // For debugging only, use the legacy PNG cruncher and compare the resulting file sizes.
      // This will help catch exotic cases where the new code may generate larger PNGs.
      std::stringstream legacy_stream(content);
      BigBuffer legacy_buffer(4096);
      Png png(context->GetDiagnostics());
      if (!png.process(path_data.source, &legacy_stream, &legacy_buffer, {})) {
        return false;
      }

      context->GetDiagnostics()->Note(DiagMessage(path_data.source)
                                      << "legacy=" << legacy_buffer.size()
                                      << " new=" << buffer.size());
    }
  }

  io::BigBufferInputStream buffer_in(&buffer);
  if (!WriteHeaderAndDataToWriter(output_path, res_file, &buffer_in, writer,
                                  context->GetDiagnostics())) {
    return false;
  }
  return true;
}

static bool CompileFile(IAaptContext* context, const CompileOptions& options,
                        const ResourcePathData& path_data, IArchiveWriter* writer,
                        const std::string& output_path) {
  if (context->IsVerbose()) {
    context->GetDiagnostics()->Note(DiagMessage(path_data.source) << "compiling file");
  }

  BigBuffer buffer(256);
  ResourceFile res_file;
  res_file.name = ResourceName({}, *ParseResourceType(path_data.resource_dir), path_data.name);
  res_file.config = path_data.config;
  res_file.source = path_data.source;
  res_file.type = ResourceFile::Type::kUnknown;

  std::string error_str;
  Maybe<android::FileMap> f = file::MmapPath(path_data.source.path, &error_str);
  if (!f) {
    context->GetDiagnostics()->Error(DiagMessage(path_data.source) << "failed to mmap file: "
                                     << error_str);
    return false;
  }

  io::MmappedData mmapped_in(std::move(f.value()));
  if (!WriteHeaderAndDataToWriter(output_path, res_file, &mmapped_in, writer,
                                  context->GetDiagnostics())) {
    return false;
  }
  return true;
}

class CompileContext : public IAaptContext {
 public:
  CompileContext(IDiagnostics* diagnostics) : diagnostics_(diagnostics) {
  }

  PackageType GetPackageType() override {
    // Every compilation unit starts as an app and then gets linked as potentially something else.
    return PackageType::kApp;
  }

  void SetVerbose(bool val) {
    verbose_ = val;
  }

  bool IsVerbose() override {
    return verbose_;
  }

  IDiagnostics* GetDiagnostics() override {
    return diagnostics_;
  }

  NameMangler* GetNameMangler() override {
    UNIMPLEMENTED(FATAL) << "No name mangling should be needed in compile phase";
    return nullptr;
  }

  const std::string& GetCompilationPackage() override {
    static std::string empty;
    return empty;
  }

  uint8_t GetPackageId() override {
    return 0x0;
  }

  SymbolTable* GetExternalSymbols() override {
    UNIMPLEMENTED(FATAL) << "No symbols should be needed in compile phase";
    return nullptr;
  }

  int GetMinSdkVersion() override {
    return 0;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(CompileContext);

  IDiagnostics* diagnostics_;
  bool verbose_ = false;
};

// Entry point for compilation phase. Parses arguments and dispatches to the correct steps.
int Compile(const std::vector<StringPiece>& args, IDiagnostics* diagnostics) {
  CompileContext context(diagnostics);
  CompileOptions options;

  bool verbose = false;
  Flags flags =
      Flags()
          .RequiredFlag("-o", "Output path", &options.output_path)
          .OptionalFlag("--dir", "Directory to scan for resources", &options.res_dir)
          .OptionalFlag("--output-text-symbols",
                        "Generates a text file containing the resource symbols in the\n"
                        "specified file",
                        &options.generate_text_symbols_path)
          .OptionalSwitch("--pseudo-localize",
                          "Generate resources for pseudo-locales "
                          "(en-XA and ar-XB)",
                          &options.pseudolocalize)
          .OptionalSwitch("--no-crunch", "Disables PNG processing", &options.no_png_crunch)
          .OptionalSwitch("--legacy", "Treat errors that used to be valid in AAPT as warnings",
                          &options.legacy_mode)
          .OptionalSwitch("-v", "Enables verbose logging", &verbose);
  if (!flags.Parse("aapt2 compile", args, &std::cerr)) {
    return 1;
  }

  context.SetVerbose(verbose);

  std::unique_ptr<IArchiveWriter> archive_writer;

  std::vector<ResourcePathData> input_data;
  if (options.res_dir) {
    if (!flags.GetArgs().empty()) {
      // Can't have both files and a resource directory.
      context.GetDiagnostics()->Error(DiagMessage() << "files given but --dir specified");
      flags.Usage("aapt2 compile", &std::cerr);
      return 1;
    }

    if (!LoadInputFilesFromDir(&context, options, &input_data)) {
      return 1;
    }

    archive_writer = CreateZipFileArchiveWriter(context.GetDiagnostics(), options.output_path);

  } else {
    input_data.reserve(flags.GetArgs().size());

    // Collect data from the path for each input file.
    for (const std::string& arg : flags.GetArgs()) {
      std::string error_str;
      if (Maybe<ResourcePathData> path_data = ExtractResourcePathData(arg, &error_str)) {
        input_data.push_back(std::move(path_data.value()));
      } else {
        context.GetDiagnostics()->Error(DiagMessage() << error_str << " (" << arg << ")");
        return 1;
      }
    }

    archive_writer = CreateDirectoryArchiveWriter(context.GetDiagnostics(), options.output_path);
  }

  if (!archive_writer) {
    return 1;
  }

  bool error = false;
  for (ResourcePathData& path_data : input_data) {
    if (options.verbose) {
      context.GetDiagnostics()->Note(DiagMessage(path_data.source) << "processing");
    }

    if (!IsValidFile(&context, path_data.source.path)) {
      error = true;
      continue;
    }

    // Determine how to compile the file based on its type.
    auto compile_func = &CompileFile;
    if (path_data.resource_dir == "values" && path_data.extension == "xml") {
      compile_func = &CompileTable;
      // We use a different extension (not necessary anymore, but avoids altering the existing
      // build system logic).
      path_data.extension = "arsc";

    } else if (const ResourceType* type = ParseResourceType(path_data.resource_dir)) {
      if (*type != ResourceType::kRaw) {
        if (path_data.extension == "xml") {
          compile_func = &CompileXml;
        } else if ((!options.no_png_crunch && path_data.extension == "png")
            || path_data.extension == "9.png") {
          compile_func = &CompilePng;
        }
      }
    } else {
      context.GetDiagnostics()->Error(DiagMessage()
                                      << "invalid file path '" << path_data.source << "'");
      error = true;
      continue;
    }

    // Treat periods as a reserved character that should not be present in a file name
    // Legacy support for AAPT which did not reserve periods
    if (compile_func != &CompileFile && !options.legacy_mode
        && std::count(path_data.name.begin(), path_data.name.end(), '.') != 0) {
      error = true;
      context.GetDiagnostics()->Error(DiagMessage() << "resource file '" << path_data.source.path
                                                    << "' name cannot contain '.' other than for"
                                                    << "specifying the extension");
      continue;
    }

    // Compile the file.
    const std::string out_path = BuildIntermediateContainerFilename(path_data);
    error |= !compile_func(&context, options, path_data, archive_writer.get(), out_path);
  }
  return error ? 1 : 0;
}

}  // namespace aapt
