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
 * This will merge packages with the same package name (or no package name).
 */
bool TableMerger::mergeImpl(const Source& src, ResourceTable* table,
                            io::IFileCollection* collection,
                            bool overlay, bool allowNew) {
    const uint8_t desiredPackageId = mContext->getPackageId();

    bool error = false;
    for (auto& package : table->packages) {
        // Warn of packages with an unrelated ID.
        if (package->id && package->id.value() != 0x0 && package->id.value() != desiredPackageId) {
            mContext->getDiagnostics()->warn(DiagMessage(src)
                                             << "ignoring package " << package->name);
            continue;
        }

        if (package->name.empty() || mContext->getCompilationPackage() == package->name) {
            FileMergeCallback callback;
            if (collection) {
                callback = [&](const ResourceNameRef& name, const ConfigDescription& config,
                               FileReference* newFile, FileReference* oldFile) -> bool {
                    // The old file's path points inside the APK, so we can use it as is.
                    io::IFile* f = collection->findFile(util::utf16ToUtf8(*oldFile->path));
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
            error |= !doMerge(src, table, package.get(),
                              false /* mangle */, overlay, allowNew, callback);
        }
    }
    return !error;
}

/**
 * This will merge and mangle resources from a static library.
 */
bool TableMerger::mergeAndMangle(const Source& src, const StringPiece16& packageName,
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
            io::IFile* f = collection->findFile(util::utf16ToUtf8(*oldFile->path));
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

bool TableMerger::doMerge(const Source& src,
                          ResourceTable* srcTable,
                          ResourceTablePackage* srcPackage,
                          const bool manglePackage,
                          const bool overlay,
                          const bool allowNewResources,
                          FileMergeCallback callback) {
    bool error = false;

    for (auto& srcType : srcPackage->types) {
        ResourceTableType* dstType = mMasterPackage->findOrCreateType(srcType->type);
        if (srcType->symbolStatus.state == SymbolState::kPublic) {
            if (dstType->symbolStatus.state == SymbolState::kPublic && dstType->id && srcType->id
                    && dstType->id.value() == srcType->id.value()) {
                // Both types are public and have different IDs.
                mContext->getDiagnostics()->error(DiagMessage(src)
                                                  << "can not merge type '"
                                                  << srcType->type
                                                  << "': conflicting public IDs");
                error = true;
                continue;
            }

            dstType->symbolStatus = std::move(srcType->symbolStatus);
            dstType->id = srcType->id;
        }

        for (auto& srcEntry : srcType->entries) {
            ResourceEntry* dstEntry;
            if (manglePackage) {
                std::u16string mangledName = NameMangler::mangleEntry(srcPackage->name,
                                                                      srcEntry->name);
                if (allowNewResources) {
                    dstEntry = dstType->findOrCreateEntry(mangledName);
                } else {
                    dstEntry = dstType->findEntry(mangledName);
                }
            } else {
                if (allowNewResources) {
                    dstEntry = dstType->findOrCreateEntry(srcEntry->name);
                } else {
                    dstEntry = dstType->findEntry(srcEntry->name);
                }
            }

            if (!dstEntry) {
                mContext->getDiagnostics()->error(DiagMessage(src)
                                                  << "resource "
                                                  << ResourceNameRef(srcPackage->name,
                                                                     srcType->type,
                                                                     srcEntry->name)
                                                  << " does not override an existing resource");
                mContext->getDiagnostics()->note(DiagMessage(src)
                                                 << "define an <add-resource> tag or use "
                                                    "--auto-add-overlay");
                error = true;
                continue;
            }

            if (srcEntry->symbolStatus.state != SymbolState::kUndefined) {
                if (srcEntry->symbolStatus.state == SymbolState::kPublic) {
                    if (dstEntry->symbolStatus.state == SymbolState::kPublic &&
                            dstEntry->id && srcEntry->id &&
                            dstEntry->id.value() != srcEntry->id.value()) {
                        // Both entries are public and have different IDs.
                        mContext->getDiagnostics()->error(DiagMessage(src)
                                                          << "can not merge entry '"
                                                          << srcEntry->name
                                                          << "': conflicting public IDs");
                        error = true;
                        continue;
                    }

                    if (srcEntry->id) {
                        dstEntry->id = srcEntry->id;
                    }
                }

                if (dstEntry->symbolStatus.state != SymbolState::kPublic &&
                        dstEntry->symbolStatus.state != srcEntry->symbolStatus.state) {
                    dstEntry->symbolStatus = std::move(srcEntry->symbolStatus);
                }
            }

            ResourceNameRef resName(mMasterPackage->name, dstType->type, dstEntry->name);

            for (auto& srcValue : srcEntry->values) {
                ResourceConfigValue* dstValue = dstEntry->findValue(srcValue->config,
                                                                    srcValue->product);
                if (dstValue) {
                    const int collisionResult = ResourceTable::resolveValueCollision(
                            dstValue->value.get(), srcValue->value.get());
                    if (collisionResult == 0 && !overlay) {
                        // Error!
                        ResourceNameRef resourceName(srcPackage->name,
                                                     srcType->type,
                                                     srcEntry->name);

                        mContext->getDiagnostics()->error(DiagMessage(srcValue->value->getSource())
                                                          << "resource '" << resourceName
                                                          << "' has a conflicting value for "
                                                          << "configuration ("
                                                          << srcValue->config << ")");
                        mContext->getDiagnostics()->note(DiagMessage(dstValue->value->getSource())
                                                         << "originally defined here");
                        error = true;
                        continue;
                    } else if (collisionResult < 0) {
                        // Keep our existing value.
                        continue;
                    }

                }

                if (!dstValue) {
                    // Force create the entry if we didn't have it.
                    dstValue = dstEntry->findOrCreateValue(srcValue->config, srcValue->product);
                }

                if (FileReference* f = valueCast<FileReference>(srcValue->value.get())) {
                    std::unique_ptr<FileReference> newFileRef;
                    if (manglePackage) {
                        newFileRef = cloneAndMangleFile(srcPackage->name, *f);
                    } else {
                        newFileRef = std::unique_ptr<FileReference>(f->clone(
                                &mMasterTable->stringPool));
                    }

                    if (callback) {
                        if (!callback(resName, srcValue->config, newFileRef.get(), f)) {
                            error = true;
                            continue;
                        }
                    }
                    dstValue->value = std::move(newFileRef);

                } else {
                    dstValue->value = std::unique_ptr<Value>(srcValue->value->clone(
                            &mMasterTable->stringPool));
                }
            }
        }
    }
    return !error;
}

std::unique_ptr<FileReference> TableMerger::cloneAndMangleFile(const std::u16string& package,
                                                               const FileReference& fileRef) {

    StringPiece16 prefix, entry, suffix;
    if (util::extractResFilePathParts(*fileRef.path, &prefix, &entry, &suffix)) {
        std::u16string mangledEntry = NameMangler::mangleEntry(package, entry.toString());
        std::u16string newPath = prefix.toString() + mangledEntry + suffix.toString();
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
    std::u16string path = util::utf8ToUtf16(ResourceUtils::buildResourceFileName(fileDesc,
                                                                                 nullptr));
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
