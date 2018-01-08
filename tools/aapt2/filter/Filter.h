/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef AAPT2_FILTER_H
#define AAPT2_FILTER_H

#include <string>
#include <vector>

#include "util/Util.h"

namespace aapt {

/** A filter to be applied to a path segment. */
class IPathFilter {
 public:
  virtual ~IPathFilter() = default;

  /** Returns true if the path should be kept. */
  virtual bool Keep(const std::string& path) = 0;
};

/**
 * Path filter that keeps anything that matches the provided prefix.
 */
class PrefixFilter : public IPathFilter {
 public:
  explicit PrefixFilter(std::string prefix) : prefix_(std::move(prefix)) {
  }

  /** Returns true if the provided path matches the prefix. */
  bool Keep(const std::string& path) override {
    return util::StartsWith(path, prefix_);
  }

 private:
  const std::string prefix_;
};

/** Applies a set of IPathFilters to a path and returns true iif all filters keep the path. */
class FilterChain : public IPathFilter {
 public:
  /** Adds a filter to the list to be applied to each path. */
  void AddFilter(std::unique_ptr<IPathFilter> filter) {
    filters_.push_back(std::move(filter));
  }

  /** Returns true if all filters keep the path. */
  bool Keep(const std::string& path) override {
    for (auto& filter : filters_) {
      if (!filter->Keep(path)) {
        return false;
      }
    }
    return true;
  }

 private:
  std::vector<std::unique_ptr<IPathFilter>> filters_;
};

}  // namespace aapt

#endif  // AAPT2_FILTER_H
