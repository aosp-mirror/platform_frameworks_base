/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef AAPT_SPLIT_TABLESPLITTER_H
#define AAPT_SPLIT_TABLESPLITTER_H

#include "ConfigDescription.h"
#include "ResourceTable.h"
#include "filter/ConfigFilter.h"
#include "process/IResourceTableConsumer.h"

#include <android-base/macros.h>
#include <set>
#include <vector>

namespace aapt {

struct SplitConstraints {
    std::set<ConfigDescription> configs;
};

struct TableSplitterOptions {
    /**
     * The preferred density to keep in the table, stripping out all others.
     */
    Maybe<uint16_t> preferredDensity;

    /**
     * Configuration filter that determines which resource configuration values end up in
     * the final table.
     */
    IConfigFilter* configFilter = nullptr;
};

class TableSplitter {
public:
    TableSplitter(const std::vector<SplitConstraints>& splits,
                  const TableSplitterOptions& options) :
            mSplitConstraints(splits), mPreferredDensity(options.preferredDensity),
            mConfigFilter(options.configFilter) {
        for (size_t i = 0; i < mSplitConstraints.size(); i++) {
            mSplits.push_back(util::make_unique<ResourceTable>());
        }
    }

    bool verifySplitConstraints(IAaptContext* context);

    void splitTable(ResourceTable* originalTable);

    const std::vector<std::unique_ptr<ResourceTable>>& getSplits() {
        return mSplits;
    }

private:
    std::vector<SplitConstraints> mSplitConstraints;
    std::vector<std::unique_ptr<ResourceTable>> mSplits;
    Maybe<uint16_t> mPreferredDensity;
    IConfigFilter* mConfigFilter;

    DISALLOW_COPY_AND_ASSIGN(TableSplitter);
};

}

#endif /* AAPT_SPLIT_TABLESPLITTER_H */
