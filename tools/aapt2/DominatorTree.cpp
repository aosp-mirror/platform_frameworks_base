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

#include "ConfigDescription.h"
#include "DominatorTree.h"

#include <algorithm>

namespace aapt {

DominatorTree::DominatorTree(
        const std::vector<std::unique_ptr<ResourceConfigValue>>& configs) {
    for (const auto& config : configs) {
        mProductRoots[config->product].tryAddChild(
                util::make_unique<Node>(config.get(), nullptr));
    }
}

void DominatorTree::accept(Visitor* visitor) {
    for (auto& entry : mProductRoots) {
        visitor->visitTree(entry.first, &entry.second);
    }
}

bool DominatorTree::Node::tryAddChild(std::unique_ptr<Node> newChild) {
    assert(newChild->mValue && "cannot add a root or empty node as a child");
    if (mValue && !dominates(newChild.get())) {
        // This is not the root and the child dominates us.
        return false;
    }
    return addChild(std::move(newChild));
}

bool DominatorTree::Node::addChild(std::unique_ptr<Node> newChild) {
    bool hasDominatedChildren = false;
    // Demote children dominated by the new config.
    for (auto& child : mChildren) {
        if (newChild->dominates(child.get())) {
            child->mParent = newChild.get();
            newChild->mChildren.push_back(std::move(child));
            child = {};
            hasDominatedChildren = true;
        }
    }
    // Remove dominated children.
    if (hasDominatedChildren) {
        mChildren.erase(std::remove_if(mChildren.begin(), mChildren.end(),
                [](const std::unique_ptr<Node>& child) -> bool {
            return child == nullptr;
        }), mChildren.end());
    }
    // Add the new config to a child if a child dominates the new config.
    for (auto& child : mChildren) {
        if (child->dominates(newChild.get())) {
            child->addChild(std::move(newChild));
            return true;
        }
    }
    // The new config is not dominated by a child, so add it here.
    newChild->mParent = this;
    mChildren.push_back(std::move(newChild));
    return true;
}

bool DominatorTree::Node::dominates(const Node* other) const {
    // Check root node dominations.
    if (other->isRootNode()) {
        return isRootNode();
    } else if (isRootNode()) {
        return true;
    }
    // Neither node is a root node; compare the configurations.
    return mValue->config.dominates(other->mValue->config);
}

} // namespace aapt
