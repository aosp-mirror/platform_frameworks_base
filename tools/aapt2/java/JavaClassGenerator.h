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

#include <ostream>
#include <string>

#include "androidfw/StringPiece.h"

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {

class AnnotationProcessor;
class ClassDefinition;

struct JavaClassGeneratorOptions {
  /*
   * Specifies whether to use the 'final' modifier
   * on resource entries. Default is true.
   */
  bool use_final = true;

  enum class SymbolTypes {
    kAll,
    kPublicPrivate,
    kPublic,
  };

  SymbolTypes types = SymbolTypes::kAll;

  /**
   * A list of JavaDoc annotations to add to the comments of all generated
   * classes.
   */
  std::vector<std::string> javadoc_annotations;
};

/*
 * Generates the R.java file for a resource table.
 */
class JavaClassGenerator {
 public:
  JavaClassGenerator(IAaptContext* context, ResourceTable* table,
                     const JavaClassGeneratorOptions& options);

  /*
   * Writes the R.java file to `out`. Only symbols belonging to `package` are
   * written.
   * All symbols technically belong to a single package, but linked libraries
   * will
   * have their names mangled, denoting that they came from a different package.
   * We need to generate these symbols in a separate file.
   * Returns true on success.
   */
  bool Generate(const android::StringPiece& packageNameToGenerate, std::ostream* out);

  bool Generate(const android::StringPiece& packageNameToGenerate,
                const android::StringPiece& outputPackageName, std::ostream* out);

  const std::string& getError() const;

 private:
  bool AddMembersToTypeClass(const android::StringPiece& packageNameToGenerate,
                             const ResourceTablePackage* package, const ResourceTableType* type,
                             ClassDefinition* outTypeClassDef);

  void AddMembersToStyleableClass(const android::StringPiece& packageNameToGenerate,
                                  const std::string& entryName, const Styleable* styleable,
                                  ClassDefinition* outStyleableClassDef);

  bool SkipSymbol(SymbolState state);

  IAaptContext* context_;
  ResourceTable* table_;
  JavaClassGeneratorOptions options_;
  std::string error_;
};

inline const std::string& JavaClassGenerator::getError() const {
  return error_;
}

}  // namespace aapt

#endif  // AAPT_JAVA_CLASS_GENERATOR_H
