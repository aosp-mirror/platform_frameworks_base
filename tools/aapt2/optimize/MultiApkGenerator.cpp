/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "MultiApkGenerator.h"

#include <algorithm>
#include <regex>
#include <string>

#include "androidfw/ConfigDescription.h"
#include "androidfw/StringPiece.h"

#include "LoadedApk.h"
#include "ResourceUtils.h"
#include "ValueVisitor.h"
#include "configuration/ConfigurationParser.h"
#include "cmd/Util.h"
#include "filter/AbiFilter.h"
#include "filter/Filter.h"
#include "format/Archive.h"
#include "format/binary/XmlFlattener.h"
#include "optimize/VersionCollapser.h"
#include "process/IResourceTableConsumer.h"
#include "split/TableSplitter.h"
#include "util/Files.h"
#include "xml/XmlDom.h"
#include "xml/XmlUtil.h"

namespace aapt {

using ::aapt::configuration::AndroidSdk;
using ::aapt::configuration::OutputArtifact;
using ::aapt::xml::kSchemaAndroid;
using ::aapt::xml::XmlResource;
using ::android::ConfigDescription;
using ::android::StringPiece;

/**
 * Context wrapper that allows the min Android SDK value to be overridden.
 */
class ContextWrapper : public IAaptContext {
 public:
  explicit ContextWrapper(IAaptContext* context)
      : context_(context), min_sdk_(context_->GetMinSdkVersion()) {
  }

  PackageType GetPackageType() override {
    return context_->GetPackageType();
  }

  SymbolTable* GetExternalSymbols() override {
    return context_->GetExternalSymbols();
  }

  IDiagnostics* GetDiagnostics() override {
    if (source_diag_) {
      return source_diag_.get();
    }
    return context_->GetDiagnostics();
  }

  const std::string& GetCompilationPackage() override {
    return context_->GetCompilationPackage();
  }

  uint8_t GetPackageId() override {
    return context_->GetPackageId();
  }

  NameMangler* GetNameMangler() override {
    return context_->GetNameMangler();
  }

  bool IsVerbose() override {
    return context_->IsVerbose();
  }

  int GetMinSdkVersion() override {
    return min_sdk_;
  }

  void SetMinSdkVersion(int min_sdk) {
    min_sdk_ = min_sdk;
  }

  void SetSource(const std::string& source) {
    source_diag_ =
        util::make_unique<SourcePathDiagnostics>(Source{source}, context_->GetDiagnostics());
  }

 private:
  IAaptContext* context_;
  std::unique_ptr<SourcePathDiagnostics> source_diag_;

  int min_sdk_ = -1;
};

class SignatureFilter : public IPathFilter {
  bool Keep(const std::string& path) override {
    static std::regex signature_regex(R"regex(^META-INF/.*\.(RSA|DSA|EC|SF)$)regex");
    if (std::regex_search(path, signature_regex)) {
      return false;
    }
    return !(path == "META-INF/MANIFEST.MF");
  }
};

MultiApkGenerator::MultiApkGenerator(LoadedApk* apk, IAaptContext* context)
    : apk_(apk), context_(context) {
}

bool MultiApkGenerator::FromBaseApk(const MultiApkGeneratorOptions& options) {
  std::unordered_set<std::string> artifacts_to_keep = options.kept_artifacts;
  std::unordered_set<std::string> filtered_artifacts;
  std::unordered_set<std::string> kept_artifacts;

  // For now, just write out the stripped APK since ABI splitting doesn't modify anything else.
  for (const OutputArtifact& artifact : options.apk_artifacts) {
    FilterChain filters;

    ContextWrapper wrapped_context{context_};
    wrapped_context.SetSource(artifact.name);

    if (!options.kept_artifacts.empty()) {
      const auto& it = artifacts_to_keep.find(artifact.name);
      if (it == artifacts_to_keep.end()) {
        filtered_artifacts.insert(artifact.name);
        if (context_->IsVerbose()) {
          context_->GetDiagnostics()->Note(DiagMessage(artifact.name) << "skipping artifact");
        }
        continue;
      } else {
        artifacts_to_keep.erase(it);
        kept_artifacts.insert(artifact.name);
      }
    }

    std::unique_ptr<ResourceTable> table =
        FilterTable(context_, artifact, *apk_->GetResourceTable(), &filters);
    if (!table) {
      return false;
    }

    IDiagnostics* diag = wrapped_context.GetDiagnostics();

    std::unique_ptr<XmlResource> manifest;
    if (!UpdateManifest(artifact, &manifest, diag)) {
      diag->Error(DiagMessage() << "could not update AndroidManifest.xml for output artifact");
      return false;
    }

    std::string out = options.out_dir;
    if (!file::mkdirs(out)) {
      diag->Warn(DiagMessage() << "could not create out dir: " << out);
    }
    file::AppendPath(&out, artifact.name);

    if (context_->IsVerbose()) {
      diag->Note(DiagMessage() << "Generating split: " << out);
    }

    std::unique_ptr<IArchiveWriter> writer = CreateZipFileArchiveWriter(diag, out);

    if (context_->IsVerbose()) {
      diag->Note(DiagMessage() << "Writing output: " << out);
    }

    filters.AddFilter(util::make_unique<SignatureFilter>());
    if (!apk_->WriteToArchive(&wrapped_context, table.get(), options.table_flattener_options,
                              &filters, writer.get(), manifest.get())) {
      return false;
    }
  }

  // Make sure all of the requested artifacts were valid. If there are any kept artifacts left,
  // either the config or the command line was wrong.
  if (!artifacts_to_keep.empty()) {
    context_->GetDiagnostics()->Error(
        DiagMessage() << "The configuration and command line to filter artifacts do not match");

    context_->GetDiagnostics()->Error(DiagMessage() << kept_artifacts.size() << " kept:");
    for (const auto& artifact : kept_artifacts) {
      context_->GetDiagnostics()->Error(DiagMessage() << "  " << artifact);
    }

    context_->GetDiagnostics()->Error(DiagMessage() << filtered_artifacts.size() << " filtered:");
    for (const auto& artifact : filtered_artifacts) {
      context_->GetDiagnostics()->Error(DiagMessage() << "  " << artifact);
    }

    context_->GetDiagnostics()->Error(DiagMessage() << artifacts_to_keep.size() << " missing:");
    for (const auto& artifact : artifacts_to_keep) {
      context_->GetDiagnostics()->Error(DiagMessage() << "  " << artifact);
    }

    return false;
  }

  return true;
}

std::unique_ptr<ResourceTable> MultiApkGenerator::FilterTable(IAaptContext* context,
                                                              const OutputArtifact& artifact,
                                                              const ResourceTable& old_table,
                                                              FilterChain* filters) {
  TableSplitterOptions splits;
  AxisConfigFilter axis_filter;
  ContextWrapper wrapped_context{context};
  wrapped_context.SetSource(artifact.name);

  if (!artifact.abis.empty()) {
    filters->AddFilter(AbiFilter::FromAbiList(artifact.abis));
  }

  if (!artifact.screen_densities.empty()) {
    for (const auto& density_config : artifact.screen_densities) {
      splits.preferred_densities.push_back(density_config.density);
    }
  }

  if (!artifact.locales.empty()) {
    for (const auto& locale : artifact.locales) {
      axis_filter.AddConfig(locale);
    }
    splits.config_filter = &axis_filter;
  }

  if (artifact.android_sdk) {
    wrapped_context.SetMinSdkVersion(artifact.android_sdk.value().min_sdk_version);
  }

  std::unique_ptr<ResourceTable> table = old_table.Clone();

  VersionCollapser collapser;
  if (!collapser.Consume(&wrapped_context, table.get())) {
    context->GetDiagnostics()->Error(DiagMessage() << "Failed to strip versioned resources");
    return {};
  }

  TableSplitter splitter{{}, splits};
  splitter.SplitTable(table.get());
  return table;
}

bool MultiApkGenerator::UpdateManifest(const OutputArtifact& artifact,
                                       std::unique_ptr<XmlResource>* updated_manifest,
                                       IDiagnostics* diag) {
  const xml::XmlResource* apk_manifest = apk_->GetManifest();
  if (apk_manifest == nullptr) {
    return false;
  }

  *updated_manifest = apk_manifest->Clone();
  XmlResource* manifest = updated_manifest->get();

  // Make sure the first element is <manifest> with package attribute.
  xml::Element* manifest_el = manifest->root.get();
  if (!manifest_el) {
    return false;
  }

  if (!manifest_el->namespace_uri.empty() || manifest_el->name != "manifest") {
    diag->Error(DiagMessage(manifest->file.source) << "root tag must be <manifest>");
    return false;
  }

  // Retrieve the versionCode attribute.
  auto version_code = manifest_el->FindAttribute(kSchemaAndroid, "versionCode");
  if (!version_code) {
    diag->Error(DiagMessage(manifest->file.source) << "manifest must have a versionCode attribute");
    return false;
  }

  auto version_code_value = ValueCast<BinaryPrimitive>(version_code->compiled_value.get());
  if (!version_code_value) {
    diag->Error(DiagMessage(manifest->file.source) << "versionCode is invalid");
    return false;
  }

  // Retrieve the versionCodeMajor attribute.
  auto version_code_major = manifest_el->FindAttribute(kSchemaAndroid, "versionCodeMajor");
  BinaryPrimitive* version_code_major_value = nullptr;
  if (version_code_major) {
    version_code_major_value = ValueCast<BinaryPrimitive>(version_code_major->compiled_value.get());
    if (!version_code_major_value) {
      diag->Error(DiagMessage(manifest->file.source) << "versionCodeMajor is invalid");
      return false;
    }
  }

  // Calculate and set the updated version code
  uint64_t major = (version_code_major_value)
                  ? ((uint64_t) version_code_major_value->value.data) << 32 : 0;
  uint64_t new_version = (major | version_code_value->value.data) + artifact.version;
  SetLongVersionCode(manifest_el, new_version);

  // Check to see if the minSdkVersion needs to be updated.
  if (artifact.android_sdk) {
    // TODO(safarmer): Handle the rest of the Android SDK.
    const AndroidSdk& android_sdk = artifact.android_sdk.value();

    if (xml::Element* uses_sdk_el = manifest_el->FindChild({}, "uses-sdk")) {
      if (xml::Attribute* min_sdk_attr =
              uses_sdk_el->FindAttribute(xml::kSchemaAndroid, "minSdkVersion")) {
        // Populate with a pre-compiles attribute to we don't need to relink etc.
        const std::string& min_sdk_str = std::to_string(android_sdk.min_sdk_version);
        min_sdk_attr->compiled_value = ResourceUtils::TryParseInt(min_sdk_str);
      } else {
        // There was no minSdkVersion. This is strange since at this point we should have been
        // through the manifest fixer which sets the default minSdkVersion.
        diag->Error(DiagMessage(manifest->file.source) << "missing minSdkVersion from <uses-sdk>");
        return false;
      }
    } else {
      // No uses-sdk present. This is strange since at this point we should have been
      // through the manifest fixer which should have added it.
      diag->Error(DiagMessage(manifest->file.source) << "missing <uses-sdk> from <manifest>");
      return false;
    }
  }

  if (!artifact.screen_densities.empty()) {
    xml::Element* screens_el = manifest_el->FindChild({}, "compatible-screens");
    if (!screens_el) {
      // create a new element.
      std::unique_ptr<xml::Element> new_screens_el = util::make_unique<xml::Element>();
      new_screens_el->name = "compatible-screens";
      screens_el = new_screens_el.get();
      manifest_el->AppendChild(std::move(new_screens_el));
    } else {
      // clear out the old element.
      screens_el->GetChildElements().clear();
    }

    for (const auto& density : artifact.screen_densities) {
      AddScreens(density, screens_el);
    }
  }

  return true;
}

/**
 * Adds a screen element with both screenSize and screenDensity set. Since we only know the density
 * we add it for all screen sizes.
 *
 * This requires the resource IDs for the attributes from the framework library. Since these IDs are
 * a part of the public API (and in public.xml) we hard code the values.
 *
 * The excert from the framework is as follows:
 *    <public type="attr" name="screenSize" id="0x010102ca" />
 *    <public type="attr" name="screenDensity" id="0x010102cb" />
 */
void MultiApkGenerator::AddScreens(const ConfigDescription& config, xml::Element* parent) {
  // Hard coded integer representation of the supported screen sizes:
  //  small   = 200
  //  normal  = 300
  //  large   = 400
  //  xlarge  = 500
  constexpr const uint32_t kScreenSizes[4] = {200, 300, 400, 500,};
  constexpr const uint32_t kScreenSizeResourceId = 0x010102ca;
  constexpr const uint32_t kScreenDensityResourceId = 0x010102cb;

  for (uint32_t screen_size : kScreenSizes) {
    std::unique_ptr<xml::Element> screen = util::make_unique<xml::Element>();
    screen->name = "screen";

    xml::Attribute* size = screen->FindOrCreateAttribute(kSchemaAndroid, "screenSize");
    size->compiled_attribute = xml::AaptAttribute(Attribute(), {kScreenSizeResourceId});
    size->compiled_value = ResourceUtils::MakeInt(screen_size);

    xml::Attribute* density = screen->FindOrCreateAttribute(kSchemaAndroid, "screenDensity");
    density->compiled_attribute = xml::AaptAttribute(Attribute(), {kScreenDensityResourceId});
    density->compiled_value = ResourceUtils::MakeInt(config.density);


    parent->AppendChild(std::move(screen));
  }
}

}  // namespace aapt
