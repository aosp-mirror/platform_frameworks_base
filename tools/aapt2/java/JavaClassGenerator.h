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

#ifndef AAPT_JAVA_CLASS_GENERATOR_H
#define AAPT_JAVA_CLASS_GENERATOR_H

#include <string>

#include "androidfw/StringPiece.h"

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "io/Io.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "text/Printer.h"

namespace aapt {

class AnnotationProcessor;
class ClassDefinition;
class MethodDefinition;

// Options for generating onResourcesLoaded callback in R.java.
struct OnResourcesLoadedCallbackOptions {
  // Other R classes to delegate the same callback to (with the same package ID).
  std::vector<std::string> packages_to_callback;
};

struct JavaClassGeneratorOptions {
  // Specifies whether to use the 'final' modifier on resource entries. Default is true.
  bool use_final = true;

  // If set, generates code to rewrite the package ID of resources.
  // Implies use_final == true. Default is unset.
  Maybe<OnResourcesLoadedCallbackOptions> rewrite_callback_options;

  enum class SymbolTypes {
    kAll,
    kPublicPrivate,
    kPublic,
  };

  SymbolTypes types = SymbolTypes::kAll;

  // A list of JavaDoc annotations to add to the comments of all generated classes.
  std::vector<std::string> javadoc_annotations;
};

// Generates the R.java file for a resource table and optionally an R.txt file.
class JavaClassGenerator {
 public:
  JavaClassGenerator(IAaptContext* context, ResourceTable* table,
                     const JavaClassGeneratorOptions& options);

  // Writes the R.java file to `out`. Only symbols belonging to `package` are written.
  // All symbols technically belong to a single package, but linked libraries will
  // have their names mangled, denoting that they came from a different package.
  // We need to generate these symbols in a separate file. Returns true on success.
  bool Generate(const android::StringPiece& package_name_to_generate, io::OutputStream* out,
                io::OutputStream* out_r_txt = nullptr);

  bool Generate(const android::StringPiece& package_name_to_generate,
                const android::StringPiece& output_package_name, io::OutputStream* out,
                io::OutputStream* out_r_txt = nullptr);

  const std::string& GetError() const;

  static std::string TransformToFieldName(const android::StringPiece& symbol);

 private:
  bool SkipSymbol(Visibility::Level state);
  bool SkipSymbol(const Maybe<SymbolTable::Symbol>& symbol);

  // Returns the unmangled resource entry name if the unmangled package is the same as
  // package_name_to_generate. Returns nothing if the resource should be skipped.
  Maybe<std::string> UnmangleResource(const android::StringPiece& package_name,
                                      const android::StringPiece& package_name_to_generate,
                                      const ResourceEntry& entry);

  bool ProcessType(const android::StringPiece& package_name_to_generate,
                   const ResourceTablePackage& package, const ResourceTableType& type,
                   ClassDefinition* out_type_class_def, MethodDefinition* out_rewrite_method_def,
                   text::Printer* r_txt_printer);

  // Writes a resource to the R.java file, optionally writing out a rewrite rule for its package
  // ID if `out_rewrite_method` is not nullptr.
  void ProcessResource(const ResourceNameRef& name, const ResourceId& id,
                       const ResourceEntry& entry, ClassDefinition* out_class_def,
                       MethodDefinition* out_rewrite_method, text::Printer* r_txt_printer);

  // Writes a styleable resource to the R.java file, optionally writing out a rewrite rule for
  // its package ID if `out_rewrite_method` is not nullptr.
  // `package_name_to_generate` is the package
  void ProcessStyleable(const ResourceNameRef& name, const ResourceId& id,
                        const Styleable& styleable,
                        const android::StringPiece& package_name_to_generate,
                        ClassDefinition* out_class_def, MethodDefinition* out_rewrite_method,
                        text::Printer* r_txt_printer);

  IAaptContext* context_;
  ResourceTable* table_;
  JavaClassGeneratorOptions options_;
  std::string error_;
};

inline const std::string& JavaClassGenerator::GetError() const {
  return error_;
}

}  // namespace aapt

#endif  // AAPT_JAVA_CLASS_GENERATOR_H
