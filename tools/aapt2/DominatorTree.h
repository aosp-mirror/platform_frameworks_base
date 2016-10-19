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

#include "ResourceTable.h"

#include <map>
#include <memory>
#include <string>
#include <vector>

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
        : mValue(value), mParent(parent) {}

    inline ResourceConfigValue* value() const { return mValue; }

    inline Node* parent() const { return mParent; }

    inline bool isRootNode() const { return !mValue; }

    inline const std::vector<std::unique_ptr<Node>>& children() const {
      return mChildren;
    }

    bool tryAddChild(std::unique_ptr<Node> newChild);

   private:
    bool addChild(std::unique_ptr<Node> newChild);
    bool dominates(const Node* other) const;

    ResourceConfigValue* mValue;
    Node* mParent;
    std::vector<std::unique_ptr<Node>> mChildren;

    DISALLOW_COPY_AND_ASSIGN(Node);
  };

  struct Visitor {
    virtual ~Visitor() = default;
    virtual void visitTree(const std::string& product, Node* root) = 0;
  };

  class BottomUpVisitor : public Visitor {
   public:
    virtual ~BottomUpVisitor() = default;

    void visitTree(const std::string& product, Node* root) override {
      for (auto& child : root->children()) {
        visitNode(child.get());
      }
    }

    virtual void visitConfig(Node* node) = 0;

   private:
    void visitNode(Node* node) {
      for (auto& child : node->children()) {
        visitNode(child.get());
      }
      visitConfig(node);
    }
  };

  void accept(Visitor* visitor);

  inline const std::map<std::string, Node>& getProductRoots() const {
    return mProductRoots;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(DominatorTree);

  std::map<std::string, Node> mProductRoots;
};

}  // namespace aapt

#endif  // AAPT_DOMINATOR_TREE_H
