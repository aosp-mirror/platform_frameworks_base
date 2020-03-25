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

#include "utils.h"

#include "android-base/strings.h"

namespace android {
namespace stats_log_api_gen {

static void build_non_chained_decl_map(const Atoms& atoms,
                                       std::map<int, set<AtomDecl>::const_iterator>* decl_map) {
    for (set<AtomDecl>::const_iterator atom = atoms.non_chained_decls.begin();
         atom != atoms.non_chained_decls.end(); atom++) {
        decl_map->insert(std::make_pair(atom->code, atom));
    }
}

/**
 * Turn lower and camel case into upper case with underscores.
 */
string make_constant_name(const string& str) {
    string result;
    const int N = str.size();
    bool underscore_next = false;
    for (int i = 0; i < N; i++) {
        char c = str[i];
        if (c >= 'A' && c <= 'Z') {
            if (underscore_next) {
                result += '_';
                underscore_next = false;
            }
        } else if (c >= 'a' && c <= 'z') {
            c = 'A' + c - 'a';
            underscore_next = true;
        } else if (c == '_') {
            underscore_next = false;
        }
        result += c;
    }
    return result;
}

const char* cpp_type_name(java_type_t type) {
    switch (type) {
        case JAVA_TYPE_BOOLEAN:
            return "bool";
        case JAVA_TYPE_INT:
        case JAVA_TYPE_ENUM:
            return "int32_t";
        case JAVA_TYPE_LONG:
            return "int64_t";
        case JAVA_TYPE_FLOAT:
            return "float";
        case JAVA_TYPE_DOUBLE:
            return "double";
        case JAVA_TYPE_STRING:
            return "char const*";
        case JAVA_TYPE_BYTE_ARRAY:
            return "const BytesField&";
        default:
            return "UNKNOWN";
    }
}

const char* java_type_name(java_type_t type) {
    switch (type) {
        case JAVA_TYPE_BOOLEAN:
            return "boolean";
        case JAVA_TYPE_INT:
        case JAVA_TYPE_ENUM:
            return "int";
        case JAVA_TYPE_LONG:
            return "long";
        case JAVA_TYPE_FLOAT:
            return "float";
        case JAVA_TYPE_DOUBLE:
            return "double";
        case JAVA_TYPE_STRING:
            return "java.lang.String";
        case JAVA_TYPE_BYTE_ARRAY:
            return "byte[]";
        default:
            return "UNKNOWN";
    }
}

// Native
// Writes namespaces for the cpp and header files, returning the number of
// namespaces written.
void write_namespace(FILE* out, const string& cppNamespaces) {
    vector<string> cppNamespaceVec = android::base::Split(cppNamespaces, ",");
    for (string cppNamespace : cppNamespaceVec) {
        fprintf(out, "namespace %s {\n", cppNamespace.c_str());
    }
}

// Writes namespace closing brackets for cpp and header files.
void write_closing_namespace(FILE* out, const string& cppNamespaces) {
    vector<string> cppNamespaceVec = android::base::Split(cppNamespaces, ",");
    for (auto it = cppNamespaceVec.rbegin(); it != cppNamespaceVec.rend(); ++it) {
        fprintf(out, "} // namespace %s\n", it->c_str());
    }
}

static void write_cpp_usage(FILE* out, const string& method_name, const string& atom_code_name,
                            const AtomDecl& atom, const AtomDecl& attributionDecl) {
    fprintf(out, "     * Usage: %s(StatsLog.%s", method_name.c_str(), atom_code_name.c_str());

    for (vector<AtomField>::const_iterator field = atom.fields.begin(); field != atom.fields.end();
         field++) {
        if (field->javaType == JAVA_TYPE_ATTRIBUTION_CHAIN) {
            for (auto chainField : attributionDecl.fields) {
                if (chainField.javaType == JAVA_TYPE_STRING) {
                    fprintf(out, ", const std::vector<%s>& %s", cpp_type_name(chainField.javaType),
                            chainField.name.c_str());
                } else {
                    fprintf(out, ", const %s* %s, size_t %s_length",
                            cpp_type_name(chainField.javaType), chainField.name.c_str(),
                            chainField.name.c_str());
                }
            }
        } else if (field->javaType == JAVA_TYPE_KEY_VALUE_PAIR) {
            fprintf(out,
                    ", const std::map<int, int32_t>& %s_int"
                    ", const std::map<int, int64_t>& %s_long"
                    ", const std::map<int, char const*>& %s_str"
                    ", const std::map<int, float>& %s_float",
                    field->name.c_str(), field->name.c_str(), field->name.c_str(),
                    field->name.c_str());
        } else {
            fprintf(out, ", %s %s", cpp_type_name(field->javaType), field->name.c_str());
        }
    }
    fprintf(out, ");\n");
}

void write_native_atom_constants(FILE* out, const Atoms& atoms, const AtomDecl& attributionDecl) {
    fprintf(out, "/**\n");
    fprintf(out, " * Constants for atom codes.\n");
    fprintf(out, " */\n");
    fprintf(out, "enum {\n");

    std::map<int, set<AtomDecl>::const_iterator> atom_code_to_non_chained_decl_map;
    build_non_chained_decl_map(atoms, &atom_code_to_non_chained_decl_map);

    size_t i = 0;
    // Print atom constants
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin(); atom != atoms.decls.end();
         atom++) {
        string constant = make_constant_name(atom->name);
        fprintf(out, "\n");
        fprintf(out, "    /**\n");
        fprintf(out, "     * %s %s\n", atom->message.c_str(), atom->name.c_str());
        write_cpp_usage(out, "stats_write", constant, *atom, attributionDecl);

        auto non_chained_decl = atom_code_to_non_chained_decl_map.find(atom->code);
        if (non_chained_decl != atom_code_to_non_chained_decl_map.end()) {
            write_cpp_usage(out, "stats_write_non_chained", constant, *non_chained_decl->second,
                            attributionDecl);
        }
        fprintf(out, "     */\n");
        char const* const comma = (i == atoms.decls.size() - 1) ? "" : ",";
        fprintf(out, "    %s = %d%s\n", constant.c_str(), atom->code, comma);
        i++;
    }
    fprintf(out, "\n");
    fprintf(out, "};\n");
    fprintf(out, "\n");
}

void write_native_method_signature(FILE* out, const string& methodName,
                                   const vector<java_type_t>& signature,
                                   const AtomDecl& attributionDecl, const string& closer) {
    fprintf(out, "%s(int32_t code", methodName.c_str());
    int argIndex = 1;
    for (vector<java_type_t>::const_iterator arg = signature.begin(); arg != signature.end();
         arg++) {
        if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
            for (auto chainField : attributionDecl.fields) {
                if (chainField.javaType == JAVA_TYPE_STRING) {
                    fprintf(out, ", const std::vector<%s>& %s", cpp_type_name(chainField.javaType),
                            chainField.name.c_str());
                } else {
                    fprintf(out, ", const %s* %s, size_t %s_length",
                            cpp_type_name(chainField.javaType), chainField.name.c_str(),
                            chainField.name.c_str());
                }
            }
        } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
            fprintf(out,
                    ", const std::map<int, int32_t>& arg%d_1, "
                    "const std::map<int, int64_t>& arg%d_2, "
                    "const std::map<int, char const*>& arg%d_3, "
                    "const std::map<int, float>& arg%d_4",
                    argIndex, argIndex, argIndex, argIndex);
        } else {
            fprintf(out, ", %s arg%d", cpp_type_name(*arg), argIndex);
        }
        argIndex++;
    }
    fprintf(out, ")%s\n", closer.c_str());
}

void write_native_method_call(FILE* out, const string& methodName,
                              const vector<java_type_t>& signature, const AtomDecl& attributionDecl,
                              int argIndex) {
    fprintf(out, "%s(code", methodName.c_str());
    for (vector<java_type_t>::const_iterator arg = signature.begin(); arg != signature.end();
         arg++) {
        if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
            for (auto chainField : attributionDecl.fields) {
                if (chainField.javaType == JAVA_TYPE_STRING) {
                    fprintf(out, ", %s", chainField.name.c_str());
                } else {
                    fprintf(out, ",  %s,  %s_length", chainField.name.c_str(),
                            chainField.name.c_str());
                }
            }
        } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
            fprintf(out, ", arg%d_1, arg%d_2, arg%d_3, arg%d_4", argIndex, argIndex, argIndex,
                    argIndex);
        } else {
            fprintf(out, ", arg%d", argIndex);
        }
        argIndex++;
    }
    fprintf(out, ");\n");
}

// Java
void write_java_atom_codes(FILE* out, const Atoms& atoms) {
    fprintf(out, "    // Constants for atom codes.\n");

    std::map<int, set<AtomDecl>::const_iterator> atom_code_to_non_chained_decl_map;
    build_non_chained_decl_map(atoms, &atom_code_to_non_chained_decl_map);

    // Print constants for the atom codes.
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin(); atom != atoms.decls.end();
         atom++) {
        string constant = make_constant_name(atom->name);
        fprintf(out, "\n");
        fprintf(out, "    /**\n");
        fprintf(out, "     * %s %s<br>\n", atom->message.c_str(), atom->name.c_str());
        write_java_usage(out, "write", constant, *atom);
        auto non_chained_decl = atom_code_to_non_chained_decl_map.find(atom->code);
        if (non_chained_decl != atom_code_to_non_chained_decl_map.end()) {
            write_java_usage(out, "write_non_chained", constant, *non_chained_decl->second);
        }
        fprintf(out, "     */\n");
        fprintf(out, "    public static final int %s = %d;\n", constant.c_str(), atom->code);
    }
    fprintf(out, "\n");
}

void write_java_enum_values(FILE* out, const Atoms& atoms) {
    fprintf(out, "    // Constants for enum values.\n\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin(); atom != atoms.decls.end();
         atom++) {
        for (vector<AtomField>::const_iterator field = atom->fields.begin();
             field != atom->fields.end(); field++) {
            if (field->javaType == JAVA_TYPE_ENUM) {
                fprintf(out, "    // Values for %s.%s\n", atom->message.c_str(),
                        field->name.c_str());
                for (map<int, string>::const_iterator value = field->enumValues.begin();
                     value != field->enumValues.end(); value++) {
                    fprintf(out, "    public static final int %s__%s__%s = %d;\n",
                            make_constant_name(atom->message).c_str(),
                            make_constant_name(field->name).c_str(),
                            make_constant_name(value->second).c_str(), value->first);
                }
                fprintf(out, "\n");
            }
        }
    }
}

void write_java_usage(FILE* out, const string& method_name, const string& atom_code_name,
                      const AtomDecl& atom) {
    fprintf(out, "     * Usage: StatsLog.%s(StatsLog.%s", method_name.c_str(),
            atom_code_name.c_str());
    for (vector<AtomField>::const_iterator field = atom.fields.begin(); field != atom.fields.end();
         field++) {
        if (field->javaType == JAVA_TYPE_ATTRIBUTION_CHAIN) {
            fprintf(out, ", android.os.WorkSource workSource");
        } else if (field->javaType == JAVA_TYPE_KEY_VALUE_PAIR) {
            fprintf(out, ", android.util.SparseArray<Object> value_map");
        } else if (field->javaType == JAVA_TYPE_BYTE_ARRAY) {
            fprintf(out, ", byte[] %s", field->name.c_str());
        } else {
            fprintf(out, ", %s %s", java_type_name(field->javaType), field->name.c_str());
        }
    }
    fprintf(out, ");<br>\n");
}

int write_java_non_chained_methods(
        FILE* out, const map<vector<java_type_t>, FieldNumberToAnnotations>& signatureInfoMap) {
    for (auto signatureInfoMapIt = signatureInfoMap.begin();
         signatureInfoMapIt != signatureInfoMap.end(); signatureInfoMapIt++) {
        // Print method signature.
        fprintf(out, "    public static void write_non_chained(int code");
        vector<java_type_t> signature = signatureInfoMapIt->first;
        int argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin(); arg != signature.end();
             arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                fprintf(stderr, "Non chained signatures should not have attribution chains.\n");
                return 1;
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                fprintf(stderr, "Module logging does not yet support key value pair.\n");
                return 1;
            } else {
                fprintf(out, ", %s arg%d", java_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ") {\n");

        fprintf(out, "        write(code");
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin(); arg != signature.end();
             arg++) {
            // First two args are uid and tag of attribution chain.
            if (argIndex == 1) {
                fprintf(out, ", new int[] {arg%d}", argIndex);
            } else if (argIndex == 2) {
                fprintf(out, ", new java.lang.String[] {arg%d}", argIndex);
            } else {
                fprintf(out, ", arg%d", argIndex);
            }
            argIndex++;
        }
        fprintf(out, ");\n");
        fprintf(out, "    }\n");
        fprintf(out, "\n");
    }
    return 0;
}

int write_java_work_source_methods(
        FILE* out, const map<vector<java_type_t>, FieldNumberToAnnotations>& signatureInfoMap) {
    fprintf(out, "    // WorkSource methods.\n");
    for (auto signatureInfoMapIt = signatureInfoMap.begin();
         signatureInfoMapIt != signatureInfoMap.end(); signatureInfoMapIt++) {
        vector<java_type_t> signature = signatureInfoMapIt->first;
        // Determine if there is Attribution in this signature.
        int attributionArg = -1;
        int argIndexMax = 0;
        for (vector<java_type_t>::const_iterator arg = signature.begin(); arg != signature.end();
             arg++) {
            argIndexMax++;
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                if (attributionArg > -1) {
                    fprintf(stderr, "An atom contains multiple AttributionNode fields.\n");
                    fprintf(stderr, "This is not supported. Aborting WorkSource method writing.\n");
                    fprintf(out,
                            "\n// Invalid for WorkSource: more than one attribution "
                            "chain.\n");
                    return 1;
                }
                attributionArg = argIndexMax;
            }
        }
        if (attributionArg < 0) {
            continue;
        }

        fprintf(out, "\n");
        // Method header (signature)
        fprintf(out, "    public static void write(int code");
        int argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin(); arg != signature.end();
             arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                fprintf(out, ", android.os.WorkSource ws");
            } else {
                fprintf(out, ", %s arg%d", java_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ") {\n");

        // write_non_chained() component. TODO: Remove when flat uids are no longer
        // needed.
        fprintf(out, "        for (int i = 0; i < ws.size(); ++i) {\n");
        fprintf(out, "            write_non_chained(code");
        for (int argIndex = 1; argIndex <= argIndexMax; argIndex++) {
            if (argIndex == attributionArg) {
                fprintf(out, ", ws.getUid(i), ws.getPackageName(i)");
            } else {
                fprintf(out, ", arg%d", argIndex);
            }
        }
        fprintf(out, ");\n");
        fprintf(out, "        }\n");  // close for-loop

        // write() component.
        fprintf(out,
                "        java.util.List<android.os.WorkSource.WorkChain> workChains = "
                "ws.getWorkChains();\n");
        fprintf(out, "        if (workChains != null) {\n");
        fprintf(out,
                "            for (android.os.WorkSource.WorkChain wc : workChains) "
                "{\n");
        fprintf(out, "                write(code");
        for (int argIndex = 1; argIndex <= argIndexMax; argIndex++) {
            if (argIndex == attributionArg) {
                fprintf(out, ", wc.getUids(), wc.getTags()");
            } else {
                fprintf(out, ", arg%d", argIndex);
            }
        }
        fprintf(out, ");\n");
        fprintf(out, "            }\n");  // close for-loop
        fprintf(out, "        }\n");      // close if
        fprintf(out, "    }\n");          // close method
    }
    return 0;
}

}  // namespace stats_log_api_gen
}  // namespace android
