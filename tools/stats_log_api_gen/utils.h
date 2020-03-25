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

#include <stdio.h>
#include <string.h>

#include <map>
#include <set>
#include <vector>

#include "Collation.h"

namespace android {
namespace stats_log_api_gen {

using namespace std;

const string DEFAULT_CPP_NAMESPACE = "android,util";
const string DEFAULT_CPP_HEADER_IMPORT = "statslog.h";
const string DEFAULT_ATOMS_INFO_CPP_HEADER_IMPORT = "atoms_info.h";

const int JAVA_MODULE_REQUIRES_FLOAT = 0x01;
const int JAVA_MODULE_REQUIRES_ATTRIBUTION = 0x02;
const int JAVA_MODULE_REQUIRES_KEY_VALUE_PAIRS = 0x04;

string make_constant_name(const string& str);

const char* cpp_type_name(java_type_t type);

const char* java_type_name(java_type_t type);

// Common Native helpers
void write_namespace(FILE* out, const string& cppNamespaces);

void write_closing_namespace(FILE* out, const string& cppNamespaces);

void write_native_atom_constants(FILE* out, const Atoms& atoms, const AtomDecl& attributionDecl);

void write_native_method_signature(FILE* out, const string& methodName,
                                   const vector<java_type_t>& signature,
                                   const AtomDecl& attributionDecl, const string& closer);

void write_native_method_call(FILE* out, const string& methodName,
                              const vector<java_type_t>& signature, const AtomDecl& attributionDecl,
                              int argIndex = 1);

// Common Java helpers.
void write_java_atom_codes(FILE* out, const Atoms& atoms);

void write_java_enum_values(FILE* out, const Atoms& atoms);

void write_java_usage(FILE* out, const string& method_name, const string& atom_code_name,
                      const AtomDecl& atom);

int write_java_non_chained_methods(
        FILE* out, const map<vector<java_type_t>, FieldNumberToAnnotations>& signatureInfoMap);

int write_java_work_source_methods(
        FILE* out, const map<vector<java_type_t>, FieldNumberToAnnotations>& signatureInfoMap);

}  // namespace stats_log_api_gen
}  // namespace android
