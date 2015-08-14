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

#ifndef AAPT_TABLEMERGER_H
#define AAPT_TABLEMERGER_H

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "util/Util.h"

#include "process/IResourceTableConsumer.h"

#include <queue>
#include <set>

namespace aapt {

struct FileToMerge {
    ResourceTable* srcTable;
    std::u16string srcPath;
    std::u16string dstPath;
};

/**
 * TableMerger takes resource tables and merges all packages within the tables that have the same
 * package ID.
 *
 * If a package has a different name, all the entries in that table have their names mangled
 * to include the package name. This way there are no collisions. In order to do this correctly,
 * the TableMerger needs to also mangle any FileReference paths. Once these are mangled,
 * the original source path of the file, along with the new destination path is recorded in the
 * queue returned from getFileMergeQueue().
 *
 * Once the merging is complete, a separate process can go collect the files from the various
 * source APKs and either copy or process their XML and put them in the correct location in
 * the final APK.
 */
class TableMerger {
public:
    TableMerger(IAaptContext* context, ResourceTable* outTable);

    inline std::queue<FileToMerge>* getFileMergeQueue() {
        return &mFilesToMerge;
    }

    inline const std::set<std::u16string>& getMergedPackages() const {
        return mMergedPackages;
    }

    bool merge(const Source& src, ResourceTable* table);

private:
    IAaptContext* mContext;
    ResourceTable* mMasterTable;
    ResourceTablePackage* mMasterPackage;

    std::set<std::u16string> mMergedPackages;
    std::queue<FileToMerge> mFilesToMerge;

    bool doMerge(const Source& src, ResourceTable* srcTable, ResourceTablePackage* srcPackage,
                 const bool manglePackage);

    std::unique_ptr<Value> cloneAndMangle(ResourceTable* table, const std::u16string& package,
                                          Value* value);
    std::unique_ptr<Value> clone(Value* value);
};

} // namespace aapt

#endif /* AAPT_TABLEMERGER_H */
