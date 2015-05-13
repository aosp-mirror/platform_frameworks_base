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

#ifndef AAPT_MOCK_RESOLVER_H
#define AAPT_MOCK_RESOLVER_H

#include "Maybe.h"
#include "Resolver.h"
#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceTableResolver.h"
#include "ResourceValues.h"
#include "StringPiece.h"

#include <map>
#include <string>

namespace aapt {

struct MockResolver : public IResolver {
    MockResolver(const std::shared_ptr<ResourceTable>& table,
                 const std::map<ResourceName, ResourceId>& items) :
            mResolver(std::make_shared<ResourceTableResolver>(
                    table, std::vector<std::shared_ptr<const android::AssetManager>>())),
            mAttr(false, android::ResTable_map::TYPE_ANY), mItems(items) {
    }

    virtual Maybe<ResourceId> findId(const ResourceName& name) override {
        Maybe<ResourceId> result = mResolver->findId(name);
        if (result) {
            return result;
        }

        const auto iter = mItems.find(name);
        if (iter != mItems.end()) {
            return iter->second;
        }
        return {};
    }

    virtual Maybe<Entry> findAttribute(const ResourceName& name) override {
        Maybe<Entry> tableResult = mResolver->findAttribute(name);
        if (tableResult) {
            return tableResult;
        }

        Maybe<ResourceId> result = findId(name);
        if (result) {
            if (name.type == ResourceType::kAttr) {
                return Entry{ result.value(), &mAttr };
            } else {
                return Entry{ result.value() };
            }
        }
        return {};
    }

    virtual Maybe<ResourceName> findName(ResourceId resId) override {
        Maybe<ResourceName> result = mResolver->findName(resId);
        if (result) {
            return result;
        }

        for (auto& p : mItems) {
            if (p.second == resId) {
                return p.first;
            }
        }
        return {};
    }

private:
    std::shared_ptr<ResourceTableResolver> mResolver;
    Attribute mAttr;
    std::map<ResourceName, ResourceId> mItems;
};

} // namespace aapt

#endif // AAPT_MOCK_RESOLVER_H
