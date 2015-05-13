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

#ifndef AAPT_LINKER_H
#define AAPT_LINKER_H

#include "Resolver.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "Source.h"
#include "StringPiece.h"

#include <androidfw/AssetManager.h>
#include <map>
#include <memory>
#include <ostream>
#include <set>
#include <vector>

namespace aapt {

/**
 * The Linker has two jobs. It follows resource references
 * and verifies that their targert exists and that their
 * types are compatible. The Linker will also assign resource
 * IDs and fill in all the dependent references with the newly
 * assigned resource IDs.
 *
 * To do this, the Linker builds a graph of references. This
 * can be useful to do other analysis, like building a
 * dependency graph of source files. The hope is to be able to
 * add functionality that operates on the graph without
 * overcomplicating the Linker.
 *
 * TODO(adamlesinski): Build the graph first then run the separate
 * steps over the graph.
 */
class Linker : ValueVisitor {
public:
    struct Options {
        /**
         * Assign resource Ids to references when linking.
         * When building a static library, set this to false.
         */
        bool linkResourceIds = true;
    };

    /**
     * Create a Linker for the given resource table with the sources available in
     * IResolver. IResolver should contain the ResourceTable as a source too.
     */
    Linker(const std::shared_ptr<ResourceTable>& table,
           const std::shared_ptr<IResolver>& resolver, const Options& options);

    Linker(const Linker&) = delete;

    virtual ~Linker() = default;

    /**
     * Entry point to the linker. Assigns resource IDs, follows references,
     * and validates types. Returns true if all references to defined values
     * are type-compatible. Missing resource references are recorded but do
     * not cause this method to fail.
     */
    bool linkAndValidate();

    /**
     * Returns any references to resources that were not defined in any of the
     * sources.
     */
    using ResourceNameToSourceMap = std::map<ResourceName, std::vector<SourceLine>>;
    const ResourceNameToSourceMap& getUnresolvedReferences() const;

protected:
    virtual void doResolveReference(Reference& reference, const SourceLine& source);
    virtual const Attribute* doResolveAttribute(Reference& attribute, const SourceLine& source);

    std::shared_ptr<IResolver> mResolver;

private:
    struct Args : public ValueVisitorArgs {
        Args(const ResourceNameRef& r, const SourceLine& s);

        const ResourceNameRef& referrer;
        const SourceLine& source;
    };

    //
    // Overrides of ValueVisitor
    //
    void visit(Reference& reference, ValueVisitorArgs& args) override;
    void visit(Attribute& attribute, ValueVisitorArgs& args) override;
    void visit(Styleable& styleable, ValueVisitorArgs& args) override;
    void visit(Style& style, ValueVisitorArgs& args) override;
    void visit(Array& array, ValueVisitorArgs& args) override;
    void visit(Plural& plural, ValueVisitorArgs& args) override;

    void processAttributeValue(const ResourceNameRef& name, const SourceLine& source,
                               const Attribute& attr, std::unique_ptr<Item>& value);

    void addUnresolvedSymbol(const ResourceNameRef& name, const SourceLine& source);

    std::shared_ptr<ResourceTable> mTable;
    std::map<ResourceName, std::vector<SourceLine>> mUnresolvedSymbols;
    Options mOptions;
    bool mError;
};

} // namespace aapt

#endif // AAPT_LINKER_H
