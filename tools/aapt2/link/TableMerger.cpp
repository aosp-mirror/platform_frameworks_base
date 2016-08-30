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
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "link/TableMerger.h"
#include "util/Util.h"

#include <cassert>

namespace aapt {

TableMerger::TableMerger(IAaptContext* context, ResourceTable* outTable,
                         const TableMergerOptions& options) :
        mContext(context), mMasterTable(outTable), mOptions(options) {
    // Create the desired package that all tables will be merged into.
    mMasterPackage = mMasterTable->createPackage(
            mContext->getCompilationPackage(), mContext->getPackageId());
    assert(mMasterPackage && "package name or ID already taken");
}

bool TableMerger::merge(const Source& src, ResourceTable* table,
                        io::IFileCollection* collection) {
    return mergeImpl(src, table, collection, false /* overlay */, true /* allow new */);
}

bool TableMerger::mergeOverlay(const Source& src, ResourceTable* table,
                               io::IFileCollection* collection) {
    return mergeImpl(src, table, collection, true /* overlay */, mOptions.autoAddOverlay);
}

/**
 * Ignore packages with an ID that is not our desired package ID or 0x0, or if the name
 * is not equal to the package we are compiling.
 */
static bool shouldIgnorePackage(IAaptContext* context, ResourceTablePackage* package) {
    const Maybe<ResourceId>& id = package->id;
    const std::string& packageName = package->name;
    return (id && id.value() != 0x0 && id.value() != context->getPackageId())
            || (!packageName.empty() && packageName != context->getCompilationPackage());
}

/**
 * This will merge packages with the same package name (or no package name).
 */
bool TableMerger::mergeImpl(const Source& src, ResourceTable* table,
                            io::IFileCollection* collection,
                            bool overlay, bool allowNew) {
    bool error = false;
    for (auto& package : table->packages) {
        // Warn of packages with an unrelated ID or name.
        if (shouldIgnorePackage(mContext, package.get())) {
            mContext->getDiagnostics()->warn(DiagMessage(src)
                                             << "ignoring package " << package->name);
            continue;
        }

        FileMergeCallback callback;
        if (collection) {
            callback = [&](const ResourceNameRef& name, const ConfigDescription& config,
                           FileReference* newFile, FileReference* oldFile) -> bool {
                // The old file's path points inside the APK, so we can use it as is.
                io::IFile* f = collection->findFile(*oldFile->path);
                if (!f) {
                    mContext->getDiagnostics()->error(DiagMessage(src) << "file '"
                                                      << *oldFile->path
                                                      << "' not found");
                    return false;
                }

                newFile->file = f;
                return true;
            };
        }

        // Merge here. Once the entries are merged and mangled, any references to
        // them are still valid. This is because un-mangled references are
        // mangled, then looked up at resolution time.
        // Also, when linking, we convert references with no package name to use
        // the compilation package name.
        error |= !doMerge(src, table, package.get(), false /* mangle */, overlay, allowNew,
                          callback);
    }
    return !error;
}

/**
 * This will merge and mangle resources from a static library.
 */
bool TableMerger::mergeAndMangle(const Source& src, const StringPiece& packageName,
                                 ResourceTable* table, io::IFileCollection* collection) {
    bool error = false;
    for (auto& package : table->packages) {
        // Warn of packages with an unrelated ID.
        if (packageName != package->name) {
            mContext->getDiagnostics()->warn(DiagMessage(src)
                                             << "ignoring package " << package->name);
            continue;
        }

        bool mangle = packageName != mContext->getCompilationPackage();
        mMergedPackages.insert(package->name);

        auto callback = [&](const ResourceNameRef& name, const ConfigDescription& config,
                            FileReference* newFile, FileReference* oldFile) -> bool {
            // The old file's path points inside the APK, so we can use it as is.
            io::IFile* f = collection->findFile(*oldFile->path);
            if (!f) {
                mContext->getDiagnostics()->error(DiagMessage(src) << "file '" << *oldFile->path
                                                  << "' not found");
                return false;
            }

            newFile->file = f;
            return true;
        };

        error |= !doMerge(src, table, package.get(),
                          mangle, false /* overlay */, true /* allow new */, callback);
    }
    return !error;
}

static bool mergeType(IAaptContext* context, const Source& src, ResourceTableType* dstType,
                      ResourceTableType* srcType) {
    if (dstType->symbolStatus.state < srcType->symbolStatus.state) {
        // The incoming type's visibility is stronger, so we should override
        // the visibility.
        if (srcType->symbolStatus.state == SymbolState::kPublic) {
            // Only copy the ID if the source is public, or else the ID is meaningless.
            dstType->id = srcType->id;
        }
        dstType->symbolStatus = std::move(srcType->symbolStatus);
    } else if (dstType->symbolStatus.state == SymbolState::kPublic
            && srcType->symbolStatus.state == SymbolState::kPublic
            && dstType->id && srcType->id
            && dstType->id.value() != srcType->id.value()) {
        // Both types are public and have different IDs.
        context->getDiagnostics()->error(DiagMessage(src)
                                         << "cannot merge type '" << srcType->type
                                         << "': conflicting public IDs");
        return false;
    }
    return true;
}

static bool mergeEntry(IAaptContext* context, const Source& src, ResourceEntry* dstEntry,
                       ResourceEntry* srcEntry) {
    if (dstEntry->symbolStatus.state < srcEntry->symbolStatus.state) {
        // The incoming type's visibility is stronger, so we should override
        // the visibility.
        if (srcEntry->symbolStatus.state == SymbolState::kPublic) {
            // Only copy the ID if the source is public, or else the ID is meaningless.
            dstEntry->id = srcEntry->id;
        }
        dstEntry->symbolStatus = std::move(srcEntry->symbolStatus);
    } else if (srcEntry->symbolStatus.state == SymbolState::kPublic
            && dstEntry->symbolStatus.state == SymbolState::kPublic
            && dstEntry->id && srcEntry->id
            && dstEntry->id.value() != srcEntry->id.value()) {
        // Both entries are public and have different IDs.
        context->getDiagnostics()->error(DiagMessage(src)
                                         << "cannot merge entry '" << srcEntry->name
                                         << "': conflicting public IDs");
        return false;
    }
    return true;
}

/**
 * Modified CollisionResolver which will merge Styleables. Used with overlays.
 *
 * Styleables are not actual resources, but they are treated as such during the
 * compilation phase. Styleables don't simply overlay each other, their definitions merge
 * and accumulate. If both values are Styleables, we just merge them into the existing value.
 */
static ResourceTable::CollisionResult resolveMergeCollision(Value* existing, Value* incoming) {
    if (Styleable* existingStyleable = valueCast<Styleable>(existing)) {
        if (Styleable* incomingStyleable = valueCast<Styleable>(incoming)) {
            // Styleables get merged.
            existingStyleable->mergeWith(incomingStyleable);
            return ResourceTable::CollisionResult::kKeepOriginal;
        }
    }
    // Delegate to the default handler.
    return ResourceTable::resolveValueCollision(existing, incoming);
}

static ResourceTable::CollisionResult mergeConfigValue(IAaptContext* context,
                                                       const ResourceNameRef& resName,
                                                       const bool overlay,
                                                       ResourceConfigValue* dstConfigValue,
                                                       ResourceConfigValue* srcConfigValue) {
    using CollisionResult = ResourceTable::CollisionResult;

    Value* dstValue = dstConfigValue->value.get();
    Value* srcValue = srcConfigValue->value.get();

    CollisionResult collisionResult;
    if (overlay) {
        collisionResult = resolveMergeCollision(dstValue, srcValue);
    } else {
        collisionResult = ResourceTable::resolveValueCollision(dstValue, srcValue);
    }

    if (collisionResult == CollisionResult::kConflict) {
        if (overlay) {
            return CollisionResult::kTakeNew;
        }

        // Error!
        context->getDiagnostics()->error(DiagMessage(srcValue->getSource())
                                         << "resource '" << resName
                                         << "' has a conflicting value for "
                                         << "configuration ("
                                         << srcConfigValue->config << ")");
        context->getDiagnostics()->note(DiagMessage(dstValue->getSource())
                                        << "originally defined here");
        return CollisionResult::kConflict;
    }
    return collisionResult;
}

bool TableMerger::doMerge(const Source& src,
                          ResourceTable* srcTable,
                          ResourceTablePackage* srcPackage,
                          const bool manglePackage,
                          const bool overlay,
                          const bool allowNewResources,
                          const FileMergeCallback& callback) {
    bool error = false;

    for (auto& srcType : srcPackage->types) {
        ResourceTableType* dstType = mMasterPackage->findOrCreateType(srcType->type);
        if (!mergeType(mContext, src, dstType, srcType.get())) {
            error = true;
            continue;
        }

        for (auto& srcEntry : srcType->entries) {
            std::string entryName = srcEntry->name;
            if (manglePackage) {
                entryName = NameMangler::mangleEntry(srcPackage->name, srcEntry->name);
            }

            ResourceEntry* dstEntry;
            if (allowNewResources) {
                dstEntry = dstType->findOrCreateEntry(entryName);
            } else {
                dstEntry = dstType->findEntry(entryName);
            }

            const ResourceNameRef resName(srcPackage->name, srcType->type, srcEntry->name);

            if (!dstEntry) {
                mContext->getDiagnostics()->error(DiagMessage(src)
                                                  << "resource " << resName
                                                  << " does not override an existing resource");
                mContext->getDiagnostics()->note(DiagMessage(src)
                                                 << "define an <add-resource> tag or use "
                                                 << "--auto-add-overlay");
                error = true;
                continue;
            }

            if (!mergeEntry(mContext, src, dstEntry, srcEntry.get())) {
                error = true;
                continue;
            }

            for (auto& srcConfigValue : srcEntry->values) {
                using CollisionResult = ResourceTable::CollisionResult;

                ResourceConfigValue* dstConfigValue = dstEntry->findValue(srcConfigValue->config,
                                                                          srcConfigValue->product);
                if (dstConfigValue) {
                    CollisionResult collisionResult = mergeConfigValue(
                            mContext, resName, overlay, dstConfigValue, srcConfigValue.get());
                    if (collisionResult == CollisionResult::kConflict) {
                        error = true;
                        continue;
                    } else if (collisionResult == CollisionResult::kKeepOriginal) {
                        continue;
                    }
                } else {
                    dstConfigValue = dstEntry->findOrCreateValue(srcConfigValue->config,
                                                                 srcConfigValue->product);
                }

                // Continue if we're taking the new resource.

                if (FileReference* f = valueCast<FileReference>(srcConfigValue->value.get())) {
                    std::unique_ptr<FileReference> newFileRef;
                    if (manglePackage) {
                        newFileRef = cloneAndMangleFile(srcPackage->name, *f);
                    } else {
                        newFileRef = std::unique_ptr<FileReference>(f->clone(
                                &mMasterTable->stringPool));
                    }

                    if (callback) {
                        if (!callback(resName, srcConfigValue->config, newFileRef.get(), f)) {
                            error = true;
                            continue;
                        }
                    }
                    dstConfigValue->value = std::move(newFileRef);

                } else {
                    dstConfigValue->value = std::unique_ptr<Value>(srcConfigValue->value->clone(
                            &mMasterTable->stringPool));
                }
            }
        }
    }
    return !error;
}

std::unique_ptr<FileReference> TableMerger::cloneAndMangleFile(const std::string& package,
                                                               const FileReference& fileRef) {
    StringPiece prefix, entry, suffix;
    if (util::extractResFilePathParts(*fileRef.path, &prefix, &entry, &suffix)) {
        std::string mangledEntry = NameMangler::mangleEntry(package, entry.toString());
        std::string newPath = prefix.toString() + mangledEntry + suffix.toString();
        std::unique_ptr<FileReference> newFileRef = util::make_unique<FileReference>(
                mMasterTable->stringPool.makeRef(newPath));
        newFileRef->setComment(fileRef.getComment());
        newFileRef->setSource(fileRef.getSource());
        return newFileRef;
    }
    return std::unique_ptr<FileReference>(fileRef.clone(&mMasterTable->stringPool));
}

bool TableMerger::mergeFileImpl(const ResourceFile& fileDesc, io::IFile* file, bool overlay) {
    ResourceTable table;
    std::string path = ResourceUtils::buildResourceFileName(fileDesc, nullptr);
    std::unique_ptr<FileReference> fileRef = util::make_unique<FileReference>(
            table.stringPool.makeRef(path));
    fileRef->setSource(fileDesc.source);
    fileRef->file = file;

    ResourceTablePackage* pkg = table.createPackage(fileDesc.name.package, 0x0);
    pkg->findOrCreateType(fileDesc.name.type)
            ->findOrCreateEntry(fileDesc.name.entry)
            ->findOrCreateValue(fileDesc.config, {})
            ->value = std::move(fileRef);

    return doMerge(file->getSource(), &table, pkg,
                   false /* mangle */, overlay /* overlay */, true /* allow new */, {});
}

bool TableMerger::mergeFile(const ResourceFile& fileDesc, io::IFile* file) {
    return mergeFileImpl(fileDesc, file, false /* overlay */);
}

bool TableMerger::mergeFileOverlay(const ResourceFile& fileDesc, io::IFile* file) {
    return mergeFileImpl(fileDesc, file, true /* overlay */);
}

} // namespace aapt
