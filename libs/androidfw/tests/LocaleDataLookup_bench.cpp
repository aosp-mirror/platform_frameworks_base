/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "benchmark/benchmark.h"

#include "androidfw/LocaleDataLookup.h"

namespace android {

static void BM_LocaleDataLookupIsLocaleRepresentative(benchmark::State& state) {
  for (auto&& _ : state) {
    isLocaleRepresentative(packLocale("en", "US"), "Latn");
    isLocaleRepresentative(packLocale("es", "ES"), "Latn");
    isLocaleRepresentative(packLocale("zh", "CN"), "Hans");
    isLocaleRepresentative(packLocale("pt", "BR"), "Latn");
    isLocaleRepresentative(packLocale("ar", "EG"), "Arab");
    isLocaleRepresentative(packLocale("hi", "IN"), "Deva");
    isLocaleRepresentative(packLocale("jp", "JP"), "Jpan");
  }
}
BENCHMARK(BM_LocaleDataLookupIsLocaleRepresentative);

static void BM_LocaleDataLookupLikelyScript(benchmark::State& state) {
  for (auto&& _ : state) {
    lookupLikelyScript(packLocale("en", ""));
    lookupLikelyScript(packLocale("es", ""));
    lookupLikelyScript(packLocale("zh", ""));
    lookupLikelyScript(packLocale("pt", ""));
    lookupLikelyScript(packLocale("ar", ""));
    lookupLikelyScript(packLocale("hi", ""));
    lookupLikelyScript(packLocale("jp", ""));
    lookupLikelyScript(packLocale("en", "US"));
    lookupLikelyScript(packLocale("es", "ES"));
    lookupLikelyScript(packLocale("zh", "CN"));
    lookupLikelyScript(packLocale("pt", "BR"));
    lookupLikelyScript(packLocale("ar", "EG"));
    lookupLikelyScript(packLocale("hi", "IN"));
    lookupLikelyScript(packLocale("jp", "JP"));
  }
}
BENCHMARK(BM_LocaleDataLookupLikelyScript);


}  // namespace android
