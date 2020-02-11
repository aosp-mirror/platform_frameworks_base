/*
 * Copyright (C) 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include "Collation.h"

#include <stdio.h>
#include <string.h>

namespace android {
namespace stats_log_api_gen {

using namespace std;

void write_native_cpp_includes_q(FILE* out);

void write_native_stats_log_cpp_globals_q(FILE* out);

void write_native_try_stats_write_methods_q(FILE* out, const Atoms& atoms,
        const AtomDecl& attributionDecl, const string& moduleName);

void write_native_stats_write_methods_q(FILE* out, const string& methodName, const Atoms& atoms,
        const AtomDecl& attributionDecl, const string& moduleName, const string& tryMethodName);

void write_native_try_stats_write_non_chained_methods_q(FILE* out, const Atoms& atoms,
        const AtomDecl& attributionDecl, const string& moduleName);

void write_native_stats_write_non_chained_methods_q(FILE* out, const string& methodName,
        const Atoms& atoms, const AtomDecl& attributionDecl, const string& moduleName,
        const string& tryMethodName);

void write_native_get_timestamp_ns_q(FILE* out);

}  // namespace stats_log_api_gen
}  // namespace android
