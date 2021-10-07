/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef IDMAP2_INCLUDE_IDMAP2_XMLPARSER_H_
#define IDMAP2_INCLUDE_IDMAP2_XMLPARSER_H_

#include <iostream>
#include <map>
#include <memory>
#include <string>

#include "ResourceUtils.h"
#include "Result.h"
#include "android-base/macros.h"
#include "androidfw/ResourceTypes.h"
#include "utils/String16.h"

namespace android::idmap2 {

struct XmlParser {
  using Event = ResXMLParser::event_code_t;
  class iterator;

  class Node {
   public:
    Event event() const;
    std::string name() const;

    Result<Res_value> GetAttributeValue(const std::string& name) const;
    Result<Res_value> GetAttributeValue(ResourceId attr, const std::string& label) const;

    Result<std::string> GetAttributeStringValue(const std::string& name) const;
    Result<std::string> GetAttributeStringValue(ResourceId attr, const std::string& label) const;

    bool operator==(const Node& rhs) const;
    bool operator!=(const Node& rhs) const;

   private:
    explicit Node(const ResXMLTree& tree);
    Node(const ResXMLTree& tree, const ResXMLParser::ResXMLPosition& pos);

    // Retrieves/Sets the position of the position of the xml parser in the xml tree.
    ResXMLParser::ResXMLPosition get_position() const;
    void set_position(const ResXMLParser::ResXMLPosition& pos);

    // If `inner_child` is true, seek advances the parser to the first inner child of the current
    // node. Otherwise, seek advances the parser to the following node. Returns false if there is
    // no node to seek to.
    bool Seek(bool inner_child);

    ResXMLParser parser_;
    friend iterator;
  };

  class iterator {
   public:
    iterator(const iterator& other) : iterator(other.tree_, other.iter_) {
    }

    inline iterator& operator=(const iterator& rhs) {
      iter_.set_position(rhs.iter_.get_position());
      return *this;
    }

    inline bool operator==(const iterator& rhs) const {
      return iter_ == rhs.iter_;
    }

    inline bool operator!=(const iterator& rhs) const {
      return !(*this == rhs);
    }

    inline iterator operator++() {
      // Seek to the following xml node.
      iter_.Seek(false /* inner_child */);
      return *this;
    }

    iterator begin() const {
      iterator child_it(*this);
      // Seek to the first inner child of the current node.
      child_it.iter_.Seek(true /* inner_child */);
      return child_it;
    }

    iterator end() const {
      iterator child_it = begin();
      while (child_it.iter_.Seek(false /* inner_child */)) {
        // Continue iterating until the end tag is found.
      }

      return child_it;
    }

    inline const Node operator*() {
      return Node(tree_, iter_.get_position());
    }

    inline const Node* operator->() {
      return &iter_;
    }

   private:
    explicit iterator(const ResXMLTree& tree) : tree_(tree), iter_(Node(tree)) {
    }
    iterator(const ResXMLTree& tree, const Node& node)
        : tree_(tree), iter_(Node(tree, node.get_position())) {
    }

    const ResXMLTree& tree_;
    Node iter_;
    friend XmlParser;
  };

  // Creates a new xml parser beginning at the first tag.
  static Result<XmlParser> Create(const void* data, size_t size, bool copy_data = false);

  inline iterator tree_iterator() const {
    return iterator(*tree_);
  }

  inline const ResStringPool& get_strings() const {
    return tree_->getStrings();
  }

 private:
  explicit XmlParser(std::unique_ptr<ResXMLTree> tree);
  mutable std::unique_ptr<ResXMLTree> tree_;
};

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_XMLPARSER_H_
