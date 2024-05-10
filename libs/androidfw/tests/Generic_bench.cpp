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

#include <stdint.h>

#include <map>
#include <unordered_map>

#include "benchmark/benchmark.h"

namespace android {

template <class Map = std::unordered_map<uint32_t, std::vector<uint32_t>>>
static Map prepare_map() {
  Map map;
  std::vector<uint32_t> vec;
  for (int i = 0; i < 1000; ++i) {
    map.emplace(i, vec);
  }
  return map;
}

static void BM_hashmap_emplace_same(benchmark::State& state) {
  auto map = prepare_map<>();
  auto val = map.size() - 1;
  std::vector<uint32_t> vec;
  for (auto&& _ : state) {
    benchmark::DoNotOptimize(map.emplace(val, vec));
  }
}
BENCHMARK(BM_hashmap_emplace_same);
static void BM_hashmap_try_emplace_same(benchmark::State& state) {
  auto map = prepare_map();
  auto val = map.size() - 1;
  for (auto&& _ : state) {
    benchmark::DoNotOptimize(map.try_emplace(val));
  }
}
BENCHMARK(BM_hashmap_try_emplace_same);
static void BM_hashmap_find(benchmark::State& state) {
  auto map = prepare_map<>();
  auto val = map.size() - 1;
  for (auto&& _ : state) {
    benchmark::DoNotOptimize(map.find(val));
  }
}
BENCHMARK(BM_hashmap_find);

static void BM_hashmap_emplace_diff(benchmark::State& state) {
  auto map = prepare_map<>();
  std::vector<uint32_t> vec;
  auto i = map.size();
  for (auto&& _ : state) {
    map.emplace(i++, vec);
  }
}
BENCHMARK(BM_hashmap_emplace_diff);
static void BM_hashmap_try_emplace_diff(benchmark::State& state) {
  auto map = prepare_map();
  auto i = map.size();
  for (auto&& _ : state) {
    map.try_emplace(i++);
  }
}
BENCHMARK(BM_hashmap_try_emplace_diff);
static void BM_hashmap_find_emplace_diff(benchmark::State& state) {
  auto map = prepare_map<>();
  std::vector<uint32_t> vec;
  auto i = map.size();
  for (auto&& _ : state) {
    if (map.find(i++) == map.end()) {
      map.emplace(i - 1, vec);
    }
  }
}
BENCHMARK(BM_hashmap_find_emplace_diff);

static void BM_treemap_emplace_same(benchmark::State& state) {
  auto map = prepare_map<std::map<uint32_t, std::vector<uint32_t>>>();
  auto val = map.size() - 1;
  std::vector<uint32_t> vec;
  for (auto&& _ : state) {
    benchmark::DoNotOptimize(map.emplace(val, vec));
  }
}
BENCHMARK(BM_treemap_emplace_same);
static void BM_treemap_try_emplace_same(benchmark::State& state) {
  auto map = prepare_map<std::map<uint32_t, std::vector<uint32_t>>>();
  auto val = map.size() - 1;
  for (auto&& _ : state) {
    benchmark::DoNotOptimize(map.try_emplace(val));
  }
}
BENCHMARK(BM_treemap_try_emplace_same);
static void BM_treemap_find(benchmark::State& state) {
  auto map = prepare_map<std::map<uint32_t, std::vector<uint32_t>>>();
  auto val = map.size() - 1;
  for (auto&& _ : state) {
    benchmark::DoNotOptimize(map.find(val));
  }
}
BENCHMARK(BM_treemap_find);

static void BM_treemap_emplace_diff(benchmark::State& state) {
  auto map = prepare_map<std::map<uint32_t, std::vector<uint32_t>>>();
  std::vector<uint32_t> vec;
  auto i = map.size();
  for (auto&& _ : state) {
    map.emplace(i++, vec);
  }
}
BENCHMARK(BM_treemap_emplace_diff);
static void BM_treemap_try_emplace_diff(benchmark::State& state) {
  auto map = prepare_map();
  auto i = map.size();
  for (auto&& _ : state) {
    map.try_emplace(i++);
  }
}
BENCHMARK(BM_treemap_try_emplace_diff);
static void BM_treemap_find_emplace_diff(benchmark::State& state) {
  auto map = prepare_map();
  std::vector<uint32_t> vec;
  auto i = map.size();
  for (auto&& _ : state) {
    if (map.find(i++) == map.end()) {
      map.emplace(i - 1, vec);
    }
  }
}
BENCHMARK(BM_treemap_find_emplace_diff);

}  // namespace android