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

#ifndef AAPT_LINK_PRODUCTFILTER_H
#define AAPT_LINK_PRODUCTFILTER_H

#include "ResourceTable.h"
#include "process/IResourceTableConsumer.h"

#include <android-base/macros.h>
#include <unordered_set>

namespace aapt {

class ProductFilter {
public:
    using ResourceConfigValueIter = std::vector<std::unique_ptr<ResourceConfigValue>>::iterator;

    ProductFilter(std::unordered_set<std::string> products) : mProducts(products) { }

    ResourceConfigValueIter selectProductToKeep(const ResourceNameRef& name,
                                                const ResourceConfigValueIter begin,
                                                const ResourceConfigValueIter end,
                                                IDiagnostics* diag);

    bool consume(IAaptContext* context, ResourceTable* table);

private:
    std::unordered_set<std::string> mProducts;

    DISALLOW_COPY_AND_ASSIGN(ProductFilter);
};

} // namespace aapt

#endif /* AAPT_LINK_PRODUCTFILTER_H */
