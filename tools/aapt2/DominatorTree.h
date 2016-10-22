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

#ifndef AAPT_DOMINATOR_TREE_H
#define AAPT_DOMINATOR_TREE_H

#include <map>
#include <memory>
#include <string>
#include <vector>

#include "ResourceTable.h"

namespace aapt {

/**
 * A dominator tree of configurations as defined by resolution rules for Android
 * resources.
 *
 * A node in the tree represents a resource configuration.
 *
 * The tree has the following property:
 *
 * Each child of a given configuration defines a strict superset of qualifiers
 * and has a value that is at least as specific as that of its ancestors. A
 * value is "at least as specific" if it is either identical or it represents a
 * stronger requirement.
 * For example, v21 is more specific than v11, and w1200dp is more specific than
 * w800dp.
 *
 * The dominator tree relies on the underlying configurations passed to it. If
 * the configurations passed to the dominator tree go out of scope, the tree
 * will exhibit undefined behavior.
 */
class DominatorTree {
 public:
  explicit DominatorTree(
      const std::vector<std::unique_ptr<ResourceConfigValue>>& configs);

  class Node {
   public:
    explicit Node(ResourceConfigValue* value = nullptr, Node* parent = nullptr)
        : value_(value), parent_(parent) {}

    inline ResourceConfigValue* value() const { return value_; }

    inline Node* parent() const { return parent_; }

    inline bool is_root_node() const { return !value_; }

    inline const std::vector<std::unique_ptr<Node>>& children() const {
      return children_;
    }

    bool TryAddChild(std::unique_ptr<Node> new_child);

   private:
    bool AddChild(std::unique_ptr<Node> new_child);
    bool Dominates(const Node* other) const;

    ResourceConfigValue* value_;
    Node* parent_;
    std::vector<std::unique_ptr<Node>> children_;

    DISALLOW_COPY_AND_ASSIGN(Node);
  };

  struct Visitor {
    virtual ~Visitor() = default;
    virtual void VisitTree(const std::string& product, Node* root) = 0;
  };

  class BottomUpVisitor : public Visitor {
   public:
    virtual ~BottomUpVisitor() = default;

    void VisitTree(const std::string& product, Node* root) override {
      for (auto& child : root->children()) {
        VisitNode(child.get());
      }
    }

    virtual void VisitConfig(Node* node) = 0;

   private:
    void VisitNode(Node* node) {
      for (auto& child : node->children()) {
        VisitNode(child.get());
      }
      VisitConfig(node);
    }
  };

  void Accept(Visitor* visitor);

  inline const std::map<std::string, Node>& product_roots() const {
    return product_roots_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(DominatorTree);

  std::map<std::string, Node> product_roots_;
};

}  // namespace aapt

#endif  // AAPT_DOMINATOR_TREE_H
