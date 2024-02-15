#pragma once

#include <memory>
#include <optional>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

#include "Resource.h"
#include "android-base/macros.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/IDiagnostics.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {

class ResourceConfigValue;

class ProductFilter : public IResourceTableConsumer {
 public:
  using ResourceConfigValueIter = std::vector<std::unique_ptr<ResourceConfigValue>>::iterator;

  // Setting remove_default_config_values will remove all values other than
  // specified product, including default. For example, if the following table
  //
  //     <string name="foo" product="default">foo_default</string>
  //     <string name="foo" product="tablet">foo_tablet</string>
  //     <string name="bar">bar</string>
  //
  // is consumed with tablet, it will result in
  //
  //     <string name="foo">foo_tablet</string>
  //
  // removing foo_default and bar. This option is to generate an RRO package
  // with given product.
  explicit ProductFilter(std::unordered_set<std::string> products,
                         bool remove_default_config_values)
      : products_(std::move(products)),
        remove_default_config_values_(remove_default_config_values) {
  }

  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(ProductFilter);

  // SelectProductToKeep returns an iterator for the selected value.
  //
  // Returns std::nullopt in case of failure (e.g. ambiguous values, missing or duplicated default
  // values).
  // Returns `end` if keep_as_default_product is set and no value for the specified product was
  // found.
  std::optional<ResourceConfigValueIter> SelectProductToKeep(const ResourceNameRef& name,
                                                             ResourceConfigValueIter begin,
                                                             ResourceConfigValueIter end,
                                                             android::IDiagnostics* diag);

  void ClearEmptyValues(ResourceTable* table);

  std::unordered_set<std::string> products_;
  bool remove_default_config_values_;
};

}  // namespace aapt
