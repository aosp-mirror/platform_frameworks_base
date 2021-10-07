/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_STRING_POOL_H
#define AAPT_STRING_POOL_H

#include <functional>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "android-base/macros.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/StringPiece.h"

#include "Diagnostics.h"
#include "util/BigBuffer.h"

namespace aapt {

struct Span {
  std::string name;
  uint32_t first_char;
  uint32_t last_char;

  bool operator==(const Span& right) const {
    return name == right.name && first_char == right.first_char && last_char == right.last_char;
  }
};

struct StyleString {
  std::string str;
  std::vector<Span> spans;
};

// A StringPool for storing the value of String and StyledString resources.
// Styles and Strings are stored separately, since the runtime variant of this
// class -- ResStringPool -- requires that styled strings *always* appear first, since their
// style data is stored as an array indexed by the same indices as the main string pool array.
// Otherwise, the style data array would have to be sparse and take up more space.
class StringPool {
 public:
  using size_type = size_t;

  class Context {
   public:
    enum : uint32_t {
      kHighPriority = 1u,
      kNormalPriority = 0x7fffffffu,
      kLowPriority = 0xffffffffu,
    };
    uint32_t priority = kNormalPriority;
    android::ConfigDescription config;

    Context() = default;
    Context(uint32_t p, const android::ConfigDescription& c) : priority(p), config(c) {}
    explicit Context(uint32_t p) : priority(p) {}
    explicit Context(const android::ConfigDescription& c) : priority(kNormalPriority), config(c) {
    }
  };

  class Entry;

  class Ref {
   public:
    Ref();
    Ref(const Ref&);
    ~Ref();

    Ref& operator=(const Ref& rhs);
    bool operator==(const Ref& rhs) const;
    bool operator!=(const Ref& rhs) const;
    const std::string* operator->() const;
    const std::string& operator*() const;

    size_t index() const;
    const Context& GetContext() const;

   private:
    friend class StringPool;

    explicit Ref(Entry* entry);

    Entry* entry_;
  };

  class StyleEntry;

  class StyleRef {
   public:
    StyleRef();
    StyleRef(const StyleRef&);
    ~StyleRef();

    StyleRef& operator=(const StyleRef& rhs);
    bool operator==(const StyleRef& rhs) const;
    bool operator!=(const StyleRef& rhs) const;
    const StyleEntry* operator->() const;
    const StyleEntry& operator*() const;

    size_t index() const;
    const Context& GetContext() const;

   private:
    friend class StringPool;

    explicit StyleRef(StyleEntry* entry);

    StyleEntry* entry_;
  };

  class Entry {
   public:
    std::string value;
    Context context;

   private:
    friend class StringPool;
    friend class Ref;

    size_t index_;
    int ref_;
    const StringPool* pool_;
  };

  struct Span {
    Ref name;
    uint32_t first_char;
    uint32_t last_char;
  };

  class StyleEntry {
   public:
    std::string value;
    Context context;
    std::vector<Span> spans;

   private:
    friend class StringPool;
    friend class StyleRef;

    size_t index_;
    int ref_;
  };

  static bool FlattenUtf8(BigBuffer* out, const StringPool& pool, IDiagnostics* diag);
  static bool FlattenUtf16(BigBuffer* out, const StringPool& pool, IDiagnostics* diag);

  StringPool() = default;
  StringPool(StringPool&&) = default;
  StringPool& operator=(StringPool&&) = default;

  // Adds a string to the pool, unless it already exists. Returns a reference to the string in the
  // pool.
  Ref MakeRef(const android::StringPiece& str);

  // Adds a string to the pool, unless it already exists, with a context object that can be used
  // when sorting the string pool. Returns a reference to the string in the pool.
  Ref MakeRef(const android::StringPiece& str, const Context& context);

  // Adds a string from another string pool. Returns a reference to the string in the string pool.
  Ref MakeRef(const Ref& ref);

  // Adds a style to the string pool and returns a reference to it.
  StyleRef MakeRef(const StyleString& str);

  // Adds a style to the string pool with a context object that can be used when sorting the string
  // pool. Returns a reference to the style in the string pool.
  StyleRef MakeRef(const StyleString& str, const Context& context);

  // Adds a style from another string pool. Returns a reference to the style in the string pool.
  StyleRef MakeRef(const StyleRef& ref);

  // Moves pool into this one without coalescing strings. When this function returns, pool will be
  // empty.
  void Merge(StringPool&& pool);

  inline const std::vector<std::unique_ptr<Entry>>& strings() const {
    return strings_;
  }

  // Returns the number of strings in the table.
  inline size_t size() const {
    return styles_.size() + strings_.size();
  }

  // Reserves space for strings and styles as an optimization.
  void HintWillAdd(size_t string_count, size_t style_count);

  // Sorts the strings according to their Context using some comparison function.
  // Equal Contexts are further sorted by string value, lexicographically.
  // If no comparison function is provided, values are only sorted lexicographically.
  void Sort(const std::function<int(const Context&, const Context&)>& cmp = nullptr);

  // Removes any strings that have no references.
  void Prune();

 private:
  DISALLOW_COPY_AND_ASSIGN(StringPool);

  static bool Flatten(BigBuffer* out, const StringPool& pool, bool utf8, IDiagnostics* diag);

  Ref MakeRefImpl(const android::StringPiece& str, const Context& context, bool unique);
  void ReAssignIndices();

  std::vector<std::unique_ptr<Entry>> strings_;
  std::vector<std::unique_ptr<StyleEntry>> styles_;
  std::unordered_multimap<android::StringPiece, Entry*> indexed_strings_;
};

}  // namespace aapt

#endif  // AAPT_STRING_POOL_H
