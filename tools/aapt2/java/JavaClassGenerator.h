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

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "process/IResourceTableConsumer.h"
#include "util/StringPiece.h"

#include <ostream>
#include <string>

namespace aapt {

class AnnotationProcessor;
class ClassDefinition;

struct JavaClassGeneratorOptions {
    /*
     * Specifies whether to use the 'final' modifier
     * on resource entries. Default is true.
     */
    bool useFinal = true;

    enum class SymbolTypes {
        kAll,
        kPublicPrivate,
        kPublic,
    };

    SymbolTypes types = SymbolTypes::kAll;

    /**
     * A list of JavaDoc annotations to add to the comments of all generated classes.
     */
    std::vector<std::string> javadocAnnotations;
};

/*
 * Generates the R.java file for a resource table.
 */
class JavaClassGenerator {
public:
    JavaClassGenerator(IAaptContext* context, ResourceTable* table,
                       const JavaClassGeneratorOptions& options);

    /*
     * Writes the R.java file to `out`. Only symbols belonging to `package` are written.
     * All symbols technically belong to a single package, but linked libraries will
     * have their names mangled, denoting that they came from a different package.
     * We need to generate these symbols in a separate file.
     * Returns true on success.
     */
    bool generate(const StringPiece16& packageNameToGenerate, std::ostream* out);

    bool generate(const StringPiece16& packageNameToGenerate,
                  const StringPiece16& outputPackageName,
                  std::ostream* out);

    const std::string& getError() const;

private:
    bool addMembersToTypeClass(const StringPiece16& packageNameToGenerate,
                               const ResourceTablePackage* package,
                               const ResourceTableType* type,
                               ClassDefinition* outTypeClassDef);

    void addMembersToStyleableClass(const StringPiece16& packageNameToGenerate,
                                    const std::u16string& entryName,
                                    const Styleable* styleable,
                                    ClassDefinition* outStyleableClassDef);

    bool skipSymbol(SymbolState state);

    IAaptContext* mContext;
    ResourceTable* mTable;
    JavaClassGeneratorOptions mOptions;
    std::string mError;
};

inline const std::string& JavaClassGenerator::getError() const {
    return mError;
}

} // namespace aapt

#endif // AAPT_JAVA_CLASS_GENERATOR_H
