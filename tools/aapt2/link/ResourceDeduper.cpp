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

#include "DominatorTree.h"
#include "ResourceTable.h"
#include "link/Linkers.h"

#include <algorithm>

namespace aapt {

namespace {

/**
 * Remove duplicated key-value entries from dominated resources.
 *
 * Based on the dominator tree, we can remove a value of an entry if:
 *
 * 1. The configuration for the entry's value is dominated by a configuration
 *    with an equivalent entry value.
 * 2. All compatible configurations for the entry (those not in conflict and
 *    unrelated by domination with the configuration for the entry's value) have
 *    an equivalent entry value.
 */
class DominatedKeyValueRemover : public DominatorTree::BottomUpVisitor {
 public:
  using Node = DominatorTree::Node;

  explicit DominatedKeyValueRemover(IAaptContext* context, ResourceEntry* entry)
      : mContext(context), mEntry(entry) {}

  void visitConfig(Node* node) {
    Node* parent = node->parent();
    if (!parent) {
      return;
    }
    ResourceConfigValue* nodeValue = node->value();
    ResourceConfigValue* parentValue = parent->value();
    if (!nodeValue || !parentValue) {
      return;
    }
    if (!nodeValue->value->equals(parentValue->value.get())) {
      return;
    }

    // Compare compatible configs for this entry and ensure the values are
    // equivalent.
    const ConfigDescription& nodeConfiguration = nodeValue->config;
    for (const auto& sibling : mEntry->values) {
      if (!sibling->value) {
        // Sibling was already removed.
        continue;
      }
      if (nodeConfiguration.isCompatibleWith(sibling->config) &&
          !nodeValue->value->equals(sibling->value.get())) {
        // The configurations are compatible, but the value is
        // different, so we can't remove this value.
        return;
      }
    }
    if (mContext->verbose()) {
      mContext->getDiagnostics()->note(
          DiagMessage(nodeValue->value->getSource())
          << "removing dominated duplicate resource with name \""
          << mEntry->name << "\"");
    }
    nodeValue->value = {};
  }

 private:
  IAaptContext* mContext;
  ResourceEntry* mEntry;
};

static void dedupeEntry(IAaptContext* context, ResourceEntry* entry) {
  DominatorTree tree(entry->values);
  DominatedKeyValueRemover remover(context, entry);
  tree.accept(&remover);

  // Erase the values that were removed.
  entry->values.erase(
      std::remove_if(
          entry->values.begin(), entry->values.end(),
          [](const std::unique_ptr<ResourceConfigValue>& val) -> bool {
            return val == nullptr || val->value == nullptr;
          }),
      entry->values.end());
}

}  // namespace

bool ResourceDeduper::consume(IAaptContext* context, ResourceTable* table) {
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        dedupeEntry(context, entry.get());
      }
    }
  }
  return true;
}

}  // aapt
