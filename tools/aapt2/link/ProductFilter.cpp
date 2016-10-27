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

#include "link/Linkers.h"

#include "ResourceTable.h"

namespace aapt {

ProductFilter::ResourceConfigValueIter ProductFilter::SelectProductToKeep(
    const ResourceNameRef& name, const ResourceConfigValueIter begin,
    const ResourceConfigValueIter end, IDiagnostics* diag) {
  ResourceConfigValueIter default_product_iter = end;
  ResourceConfigValueIter selected_product_iter = end;

  for (ResourceConfigValueIter iter = begin; iter != end; ++iter) {
    ResourceConfigValue* config_value = iter->get();
    if (products_.find(config_value->product) != products_.end()) {
      if (selected_product_iter != end) {
        // We have two possible values for this product!
        diag->Error(DiagMessage(config_value->value->GetSource())
                    << "selection of product '" << config_value->product
                    << "' for resource " << name << " is ambiguous");

        ResourceConfigValue* previously_selected_config_value =
            selected_product_iter->get();
        diag->Note(
            DiagMessage(previously_selected_config_value->value->GetSource())
            << "product '" << previously_selected_config_value->product
            << "' is also a candidate");
        return end;
      }

      // Select this product.
      selected_product_iter = iter;
    }

    if (config_value->product.empty() || config_value->product == "default") {
      if (default_product_iter != end) {
        // We have two possible default values.
        diag->Error(DiagMessage(config_value->value->GetSource())
                    << "multiple default products defined for resource "
                    << name);

        ResourceConfigValue* previously_default_config_value =
            default_product_iter->get();
        diag->Note(
            DiagMessage(previously_default_config_value->value->GetSource())
            << "default product also defined here");
        return end;
      }

      // Mark the default.
      default_product_iter = iter;
    }
  }

  if (default_product_iter == end) {
    diag->Error(DiagMessage() << "no default product defined for resource "
                              << name);
    return end;
  }

  if (selected_product_iter == end) {
    selected_product_iter = default_product_iter;
  }
  return selected_product_iter;
}

bool ProductFilter::Consume(IAaptContext* context, ResourceTable* table) {
  bool error = false;
  for (auto& pkg : table->packages) {
    for (auto& type : pkg->types) {
      for (auto& entry : type->entries) {
        std::vector<std::unique_ptr<ResourceConfigValue>> new_values;

        ResourceConfigValueIter iter = entry->values.begin();
        ResourceConfigValueIter start_range_iter = iter;
        while (iter != entry->values.end()) {
          ++iter;
          if (iter == entry->values.end() ||
              (*iter)->config != (*start_range_iter)->config) {
            // End of the array, or we saw a different config,
            // so this must be the end of a range of products.
            // Select the product to keep from the set of products defined.
            ResourceNameRef name(pkg->name, type->type, entry->name);
            auto value_to_keep = SelectProductToKeep(
                name, start_range_iter, iter, context->GetDiagnostics());
            if (value_to_keep == iter) {
              // An error occurred, we could not pick a product.
              error = true;
            } else {
              // We selected a product to keep. Move it to the new array.
              new_values.push_back(std::move(*value_to_keep));
            }

            // Start the next range of products.
            start_range_iter = iter;
          }
        }

        // Now move the new values in to place.
        entry->values = std::move(new_values);
      }
    }
  }
  return !error;
}

}  // namespace aapt
