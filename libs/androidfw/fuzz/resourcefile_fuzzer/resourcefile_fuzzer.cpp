/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <string>
#include <memory>

#include <androidfw/ApkAssets.h>
#include <androidfw/LoadedArsc.h>
#include <androidfw/StringPiece.h>

#include <fuzzer/FuzzedDataProvider.h>

using android::ApkAssets;
using android::LoadedArsc;
using android::StringPiece;

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(data, size);
  return 0;
}