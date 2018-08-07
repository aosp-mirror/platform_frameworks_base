/*
 * Copyright (C) 2017, The Android Open Source Project
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

#ifndef ANDROID_STATS_LOG_API_GEN_COLLATION_H
#define ANDROID_STATS_LOG_API_GEN_COLLATION_H


#include <google/protobuf/descriptor.h>

#include <set>
#include <vector>
#include <map>

namespace android {
namespace stats_log_api_gen {

using std::map;
using std::set;
using std::string;
using std::vector;
using google::protobuf::Descriptor;
using google::protobuf::FieldDescriptor;

/**
 * The types for atom parameters.
 */
typedef enum {
  JAVA_TYPE_UNKNOWN = 0,

  JAVA_TYPE_ATTRIBUTION_CHAIN = 1,
  JAVA_TYPE_BOOLEAN = 2,
  JAVA_TYPE_INT = 3,
  JAVA_TYPE_LONG = 4,
  JAVA_TYPE_FLOAT = 5,
  JAVA_TYPE_DOUBLE = 6,
  JAVA_TYPE_STRING = 7,
  JAVA_TYPE_ENUM = 8,

  JAVA_TYPE_OBJECT = -1,
  JAVA_TYPE_BYTE_ARRAY = -2,
} java_type_t;

/**
 * The name and type for an atom field.
 */
struct AtomField {
    string name;
    java_type_t javaType;

    // If the field is of type enum, the following map contains the list of enum values.
    map<int /* numeric value */, string /* value name */> enumValues;

    inline AtomField() :name(), javaType(JAVA_TYPE_UNKNOWN) {}
    inline AtomField(const AtomField& that) :name(that.name),
                                             javaType(that.javaType),
                                             enumValues(that.enumValues) {}
    inline AtomField(string n, java_type_t jt) :name(n), javaType(jt) {}
    inline ~AtomField() {}
};

/**
 * The name and code for an atom.
 */
struct AtomDecl {
    int code;
    string name;

    string message;
    vector<AtomField> fields;

    vector<int> primaryFields;
    int exclusiveField = 0;

    int uidField = 0;

    AtomDecl();
    AtomDecl(const AtomDecl& that);
    AtomDecl(int code, const string& name, const string& message);
    ~AtomDecl();

    inline bool operator<(const AtomDecl& that) const {
        return (code == that.code) ? (name < that.name) : (code < that.code);
    }
};

struct Atoms {
    set<vector<java_type_t>> signatures;
    set<AtomDecl> decls;
    set<AtomDecl> non_chained_decls;
    set<vector<java_type_t>> non_chained_signatures;
};

/**
 * Gather the information about the atoms.  Returns the number of errors.
 */
int collate_atoms(const Descriptor* descriptor, Atoms* atoms);
int collate_atom(const Descriptor *atom, AtomDecl *atomDecl, vector<java_type_t> *signature);

}  // namespace stats_log_api_gen
}  // namespace android


#endif // ANDROID_STATS_LOG_API_GEN_COLLATION_H