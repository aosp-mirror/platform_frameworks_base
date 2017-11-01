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

#include <algorithm>

#include "android-base/logging.h"

#include "ConfigDescription.h"

namespace aapt {

DominatorTree::DominatorTree(
    const std::vector<std::unique_ptr<ResourceConfigValue>>& configs) {
  for (const auto& config : configs) {
    product_roots_[config->product].TryAddChild(
        util::make_unique<Node>(config.get(), nullptr));
  }
}

void DominatorTree::Accept(Visitor* visitor) {
  for (auto& entry : product_roots_) {
    visitor->VisitTree(entry.first, &entry.second);
  }
}

bool DominatorTree::Node::TryAddChild(std::unique_ptr<Node> new_child) {
  CHECK(new_child->value_) << "cannot add a root or empty node as a child";
  if (value_ && !Dominates(new_child.get())) {
    // This is not the root and the child dominates us.
    return false;
  }
  return AddChild(std::move(new_child));
}

bool DominatorTree::Node::AddChild(std::unique_ptr<Node> new_child) {
  bool has_dominated_children = false;
  // Demote children dominated by the new config.
  for (auto& child : children_) {
    if (new_child->Dominates(child.get())) {
      child->parent_ = new_child.get();
      new_child->children_.push_back(std::move(child));
      child = {};
      has_dominated_children = true;
    }
  }
  // Remove dominated children.
  if (has_dominated_children) {
    children_.erase(
        std::remove_if(children_.begin(), children_.end(),
                       [](const std::unique_ptr<Node>& child) -> bool {
                         return child == nullptr;
                       }),
        children_.end());
  }
  // Add the new config to a child if a child dominates the new config.
  for (auto& child : children_) {
    if (child->Dominates(new_child.get())) {
      child->AddChild(std::move(new_child));
      return true;
    }
  }
  // The new config is not dominated by a child, so add it here.
  new_child->parent_ = this;
  children_.push_back(std::move(new_child));
  return true;
}

bool DominatorTree::Node::Dominates(const Node* other) const {
  // Check root node dominations.
  if (other->is_root_node()) {
    return is_root_node();
  } else if (is_root_node()) {
    return true;
  }
  // Neither node is a root node; compare the configurations.
  return value_->config.Dominates(other->value_->config);
}

}  // namespace aapt
