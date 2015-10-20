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

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"

#include "link/TableMerger.h"
#include "util/Util.h"

#include <cassert>

namespace aapt {

TableMerger::TableMerger(IAaptContext* context, ResourceTable* outTable) :
        mContext(context), mMasterTable(outTable) {
    // Create the desired package that all tables will be merged into.
    mMasterPackage = mMasterTable->createPackage(
            mContext->getCompilationPackage(), mContext->getPackageId());
    assert(mMasterPackage && "package name or ID already taken");
}

bool TableMerger::merge(const Source& src, ResourceTable* table) {
    const uint8_t desiredPackageId = mContext->getPackageId();

    bool error = false;
    for (auto& package : table->packages) {
        // Warn of packages with an unrelated ID.
        if (package->id && package->id.value() != 0x0 && package->id.value() != desiredPackageId) {
            mContext->getDiagnostics()->warn(DiagMessage(src)
                                             << "ignoring package " << package->name);
            continue;
        }

        bool manglePackage = false;
        if (!package->name.empty() && mContext->getCompilationPackage() != package->name) {
            manglePackage = true;
            mMergedPackages.insert(package->name);
        }

        // Merge here. Once the entries are merged and mangled, any references to
        // them are still valid. This is because un-mangled references are
        // mangled, then looked up at resolution time.
        // Also, when linking, we convert references with no package name to use
        // the compilation package name.
        if (!doMerge(src, table, package.get(), manglePackage)) {
            error = true;
        }
    }
    return !error;
}

bool TableMerger::doMerge(const Source& src, ResourceTable* srcTable,
                          ResourceTablePackage* srcPackage, const bool manglePackage) {
    bool error = false;

    for (auto& srcType : srcPackage->types) {
        ResourceTableType* dstType = mMasterPackage->findOrCreateType(srcType->type);
        if (srcType->publicStatus.isPublic) {
            if (dstType->publicStatus.isPublic && dstType->id && srcType->id
                    && dstType->id.value() == srcType->id.value()) {
                // Both types are public and have different IDs.
                mContext->getDiagnostics()->error(DiagMessage(src)
                                                  << "can not merge type '"
                                                  << srcType->type
                                                  << "': conflicting public IDs");
                error = true;
                continue;
            }

            dstType->publicStatus = std::move(srcType->publicStatus);
            dstType->id = srcType->id;
        }

        for (auto& srcEntry : srcType->entries) {
            ResourceEntry* dstEntry;
            if (manglePackage) {
                dstEntry = dstType->findOrCreateEntry(NameMangler::mangleEntry(
                        srcPackage->name, srcEntry->name));
            } else {
                dstEntry = dstType->findOrCreateEntry(srcEntry->name);
            }

            if (srcEntry->publicStatus.isPublic) {
                if (dstEntry->publicStatus.isPublic && dstEntry->id && srcEntry->id
                        && dstEntry->id.value() != srcEntry->id.value()) {
                    // Both entries are public and have different IDs.
                    mContext->getDiagnostics()->error(DiagMessage(src)
                                                      << "can not merge entry '"
                                                      << srcEntry->name
                                                      << "': conflicting public IDs");
                    error = true;
                    continue;
                }

                dstEntry->publicStatus = std::move(srcEntry->publicStatus);
                dstEntry->id = srcEntry->id;
            }

            for (ResourceConfigValue& srcValue : srcEntry->values) {
                auto cmp = [](const ResourceConfigValue& a,
                              const ConfigDescription& b) -> bool {
                    return a.config < b;
                };

                auto iter = std::lower_bound(dstEntry->values.begin(), dstEntry->values.end(),
                                             srcValue.config, cmp);

                if (iter != dstEntry->values.end() && iter->config == srcValue.config) {
                    const int collisionResult = ResourceTable::resolveValueCollision(
                            iter->value.get(), srcValue.value.get());
                    if (collisionResult == 0) {
                        // Error!
                        ResourceNameRef resourceName =
                                { srcPackage->name, srcType->type, srcEntry->name };
                        mContext->getDiagnostics()->error(DiagMessage(srcValue.source)
                                                          << "resource '" << resourceName
                                                          << "' has a conflicting value for "
                                                          << "configuration ("
                                                          << srcValue.config << ")");
                        mContext->getDiagnostics()->note(DiagMessage(iter->source)
                                                         << "originally defined here");
                        error = true;
                        continue;
                    } else if (collisionResult < 0) {
                        // Keep our existing value.
                        continue;
                    }

                } else {
                    // Insert a new value.
                    iter = dstEntry->values.insert(iter,
                                                   ResourceConfigValue{ srcValue.config });
                }

                iter->source = std::move(srcValue.source);
                iter->comment = std::move(srcValue.comment);
                if (manglePackage) {
                    iter->value = cloneAndMangle(srcTable, srcPackage->name,
                                                 srcValue.value.get());
                } else {
                    iter->value = clone(srcValue.value.get());
                }
            }
        }
    }
    return !error;
}

std::unique_ptr<Value> TableMerger::cloneAndMangle(ResourceTable* table,
                                                   const std::u16string& package,
                                                   Value* value) {
    if (FileReference* f = valueCast<FileReference>(value)) {
        // Mangle the path.
        StringPiece16 prefix, entry, suffix;
        if (util::extractResFilePathParts(*f->path, &prefix, &entry, &suffix)) {
            std::u16string mangledEntry = NameMangler::mangleEntry(package, entry.toString());
            std::u16string newPath = prefix.toString() + mangledEntry + suffix.toString();
            mFilesToMerge.push(FileToMerge{ table, *f->path, newPath });
            return util::make_unique<FileReference>(mMasterTable->stringPool.makeRef(newPath));
        }
    }
    return clone(value);
}

std::unique_ptr<Value> TableMerger::clone(Value* value) {
    return std::unique_ptr<Value>(value->clone(&mMasterTable->stringPool));
}

} // namespace aapt
