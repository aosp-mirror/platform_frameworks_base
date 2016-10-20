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

#include "ResourceTable.h"
#include "link/Linkers.h"

#include <algorithm>
#include <vector>

namespace aapt {

template <typename Iterator, typename Pred>
class FilterIterator {
 public:
  FilterIterator(Iterator begin, Iterator end, Pred pred = Pred())
      : mCurrent(begin), mEnd(end), mPred(pred) {
    advance();
  }

  bool hasNext() { return mCurrent != mEnd; }

  Iterator nextIter() {
    Iterator iter = mCurrent;
    ++mCurrent;
    advance();
    return iter;
  }

  typename Iterator::reference next() { return *nextIter(); }

 private:
  void advance() {
    for (; mCurrent != mEnd; ++mCurrent) {
      if (mPred(*mCurrent)) {
        return;
      }
    }
  }

  Iterator mCurrent, mEnd;
  Pred mPred;
};

template <typename Iterator, typename Pred>
FilterIterator<Iterator, Pred> makeFilterIterator(Iterator begin,
                                                  Iterator end = Iterator(),
                                                  Pred pred = Pred()) {
  return FilterIterator<Iterator, Pred>(begin, end, pred);
}

/**
 * Every Configuration with an SDK version specified that is less than minSdk
 * will be removed.
 * The exception is when there is no exact matching resource for the minSdk. The
 * next smallest
 * one will be kept.
 */
static void collapseVersions(int minSdk, ResourceEntry* entry) {
  // First look for all sdks less than minSdk.
  for (auto iter = entry->values.rbegin(); iter != entry->values.rend();
       ++iter) {
    // Check if the item was already marked for removal.
    if (!(*iter)) {
      continue;
    }

    const ConfigDescription& config = (*iter)->config;
    if (config.sdkVersion <= minSdk) {
      // This is the first configuration we've found with a smaller or equal SDK
      // level
      // to the minimum. We MUST keep this one, but remove all others we find,
      // which get
      // overridden by this one.

      ConfigDescription configWithoutSdk = config;
      configWithoutSdk.sdkVersion = 0;
      auto pred = [&](const std::unique_ptr<ResourceConfigValue>& val) -> bool {
        // Check that the value hasn't already been marked for removal.
        if (!val) {
          return false;
        }

        // Only return Configs that differ in SDK version.
        configWithoutSdk.sdkVersion = val->config.sdkVersion;
        return configWithoutSdk == val->config &&
               val->config.sdkVersion <= minSdk;
      };

      // Remove the rest that match.
      auto filterIter =
          makeFilterIterator(iter + 1, entry->values.rend(), pred);
      while (filterIter.hasNext()) {
        filterIter.next() = {};
      }
    }
  }

  // Now erase the nullptr values.
  entry->values.erase(
      std::remove_if(entry->values.begin(), entry->values.end(),
                     [](const std::unique_ptr<ResourceConfigValue>& val)
                         -> bool { return val == nullptr; }),
      entry->values.end());

  // Strip the version qualifiers for every resource with version <= minSdk.
  // This will ensure
  // that the resource entries are all packed together in the same ResTable_type
  // struct
  // and take up less space in the resources.arsc table.
  bool modified = false;
  for (std::unique_ptr<ResourceConfigValue>& configValue : entry->values) {
    if (configValue->config.sdkVersion != 0 &&
        configValue->config.sdkVersion <= minSdk) {
      // Override the resource with a Configuration without an SDK.
      std::unique_ptr<ResourceConfigValue> newValue =
          util::make_unique<ResourceConfigValue>(
              configValue->config.copyWithoutSdkVersion(),
              configValue->product);
      newValue->value = std::move(configValue->value);
      configValue = std::move(newValue);

      modified = true;
    }
  }

  if (modified) {
    // We've modified the keys (ConfigDescription) by changing the sdkVersion to
    // 0.
    // We MUST re-sort to ensure ordering guarantees hold.
    std::sort(entry->values.begin(), entry->values.end(),
              [](const std::unique_ptr<ResourceConfigValue>& a,
                 const std::unique_ptr<ResourceConfigValue>& b) -> bool {
                return a->config.compare(b->config) < 0;
              });
  }
}

bool VersionCollapser::consume(IAaptContext* context, ResourceTable* table) {
  const int minSdk = context->getMinSdkVersion();
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        collapseVersions(minSdk, entry.get());
      }
    }
  }
  return true;
}

}  // namespace aapt
