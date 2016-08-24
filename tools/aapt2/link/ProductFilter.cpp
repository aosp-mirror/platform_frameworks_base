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

#include "link/ProductFilter.h"

namespace aapt {

ProductFilter::ResourceConfigValueIter
ProductFilter::selectProductToKeep(const ResourceNameRef& name,
                                   const ResourceConfigValueIter begin,
                                   const ResourceConfigValueIter end,
                                   IDiagnostics* diag) {
    ResourceConfigValueIter defaultProductIter = end;
    ResourceConfigValueIter selectedProductIter = end;

    for (ResourceConfigValueIter iter = begin; iter != end; ++iter) {
        ResourceConfigValue* configValue = iter->get();
        if (mProducts.find(configValue->product) != mProducts.end()) {
            if (selectedProductIter != end) {
                // We have two possible values for this product!
                diag->error(DiagMessage(configValue->value->getSource())
                            << "selection of product '" << configValue->product
                            << "' for resource " << name << " is ambiguous");

                ResourceConfigValue* previouslySelectedConfigValue = selectedProductIter->get();
                diag->note(DiagMessage(previouslySelectedConfigValue->value->getSource())
                           << "product '" << previouslySelectedConfigValue->product
                           << "' is also a candidate");
                return end;
            }

            // Select this product.
            selectedProductIter = iter;
        }

        if (configValue->product.empty() || configValue->product == "default") {
            if (defaultProductIter != end) {
                // We have two possible default values.
                diag->error(DiagMessage(configValue->value->getSource())
                            << "multiple default products defined for resource " << name);

                ResourceConfigValue* previouslyDefaultConfigValue = defaultProductIter->get();
                diag->note(DiagMessage(previouslyDefaultConfigValue->value->getSource())
                           << "default product also defined here");
                return end;
            }

            // Mark the default.
            defaultProductIter = iter;
        }
    }

    if (defaultProductIter == end) {
        diag->error(DiagMessage() << "no default product defined for resource " << name);
        return end;
    }

    if (selectedProductIter == end) {
        selectedProductIter = defaultProductIter;
    }
    return selectedProductIter;
}

bool ProductFilter::consume(IAaptContext* context, ResourceTable* table) {
    bool error = false;
    for (auto& pkg : table->packages) {
        for (auto& type : pkg->types) {
            for (auto& entry : type->entries) {
                std::vector<std::unique_ptr<ResourceConfigValue>> newValues;

                ResourceConfigValueIter iter = entry->values.begin();
                ResourceConfigValueIter startRangeIter = iter;
                while (iter != entry->values.end()) {
                    ++iter;
                    if (iter == entry->values.end() ||
                            (*iter)->config != (*startRangeIter)->config) {

                        // End of the array, or we saw a different config,
                        // so this must be the end of a range of products.
                        // Select the product to keep from the set of products defined.
                        ResourceNameRef name(pkg->name, type->type, entry->name);
                        auto valueToKeep = selectProductToKeep(name, startRangeIter, iter,
                                                               context->getDiagnostics());
                        if (valueToKeep == iter) {
                            // An error occurred, we could not pick a product.
                            error = true;
                        } else {
                            // We selected a product to keep. Move it to the new array.
                            newValues.push_back(std::move(*valueToKeep));
                        }

                        // Start the next range of products.
                        startRangeIter = iter;
                    }
                }

                // Now move the new values in to place.
                entry->values = std::move(newValues);
            }
        }
    }
    return !error;
}

} // namespace aapt
