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

#include "native_writer.h"
#include "native_writer_q.h"
#include "utils.h"

namespace android {
namespace stats_log_api_gen {

#if !defined(STATS_SCHEMA_LEGACY)
static void write_native_key_value_pairs_for_type(FILE* out, const int argIndex,
        const int typeIndex, const string& type, const string& valueFieldName) {
    fprintf(out, "    for (const auto& it : arg%d_%d) {\n", argIndex, typeIndex);
    fprintf(out, "        pairs.push_back("
            "{ .key = it.first, .valueType = %s, .%s = it.second });\n",
            type.c_str(), valueFieldName.c_str());
    fprintf(out, "    }\n");

}

static int write_native_stats_write_methods(FILE* out, const Atoms& atoms,
        const AtomDecl& attributionDecl, const string& moduleName, const bool supportQ) {
    fprintf(out, "\n");
    for (auto signature_to_modules_it = atoms.signatures_to_modules.begin();
        signature_to_modules_it != atoms.signatures_to_modules.end(); signature_to_modules_it++) {
        if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
            continue;
        }
        vector<java_type_t> signature = signature_to_modules_it->first;

        write_native_method_signature(out, "int stats_write", signature,
                attributionDecl, " {");

        int argIndex = 1;
        if (supportQ) {
            fprintf(out, "    StatsEventCompat event;\n");
            fprintf(out, "    event.setAtomId(code);\n");
            for (vector<java_type_t>::const_iterator arg = signature.begin();
                    arg != signature.end(); arg++) {
                switch (*arg) {
                    case JAVA_TYPE_ATTRIBUTION_CHAIN: {
                        const char* uidName = attributionDecl.fields.front().name.c_str();
                        const char* tagName = attributionDecl.fields.back().name.c_str();
                        fprintf(out, "    event.writeAttributionChain(%s, %s_length, %s);\n",
                                uidName, uidName, tagName);
                        break;
                    }
                    case JAVA_TYPE_KEY_VALUE_PAIR:
                        fprintf(out, "    event.writeKeyValuePairs("
                                "arg%d_1, arg%d_2, arg%d_3, arg%d_4);\n",
                                argIndex, argIndex, argIndex, argIndex);
                        break;
                    case JAVA_TYPE_BYTE_ARRAY:
                        fprintf(out, "    event.writeByteArray(arg%d.arg, arg%d.arg_length);\n",
                                argIndex, argIndex);
                        break;
                    case JAVA_TYPE_BOOLEAN:
                        fprintf(out, "    event.writeBool(arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_INT: // Fall through.
                    case JAVA_TYPE_ENUM:
                        fprintf(out, "    event.writeInt32(arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_FLOAT:
                        fprintf(out, "    event.writeFloat(arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_LONG:
                        fprintf(out, "    event.writeInt64(arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_STRING:
                        fprintf(out, "    event.writeString(arg%d);\n", argIndex);
                        break;
                    default:
                        // Unsupported types: OBJECT, DOUBLE.
                        fprintf(stderr, "Encountered unsupported type.");
                        return 1;
                }
                argIndex++;
            }
            fprintf(out, "    return event.writeToSocket();\n");
        } else {
            fprintf(out, "    struct stats_event* event = stats_event_obtain();\n");
            fprintf(out, "    stats_event_set_atom_id(event, code);\n");
            for (vector<java_type_t>::const_iterator arg = signature.begin();
                    arg != signature.end(); arg++) {
                switch (*arg) {
                    case JAVA_TYPE_ATTRIBUTION_CHAIN: {
                        const char* uidName = attributionDecl.fields.front().name.c_str();
                        const char* tagName = attributionDecl.fields.back().name.c_str();
                        fprintf(out,
                                "    stats_event_write_attribution_chain(event, "
                                "reinterpret_cast<const uint32_t*>(%s), %s.data(), "
                                "static_cast<uint8_t>(%s_length));\n",
                                uidName, tagName, uidName);
                        break;
                    }
                    case JAVA_TYPE_KEY_VALUE_PAIR:
                        fprintf(out, "    std::vector<key_value_pair> pairs;\n");
                        write_native_key_value_pairs_for_type(
                                out, argIndex, 1, "INT32_TYPE", "int32Value");
                        write_native_key_value_pairs_for_type(
                                out, argIndex, 2, "INT64_TYPE", "int64Value");
                        write_native_key_value_pairs_for_type(
                                out, argIndex, 3, "STRING_TYPE", "stringValue");
                        write_native_key_value_pairs_for_type(
                                out, argIndex, 4, "FLOAT_TYPE", "floatValue");
                        fprintf(out,
                                "    stats_event_write_key_value_pairs(event, pairs.data(), "
                                "static_cast<uint8_t>(pairs.size()));\n");
                        break;
                    case JAVA_TYPE_BYTE_ARRAY:
                        fprintf(out,
                                "    stats_event_write_byte_array(event, "
                                "reinterpret_cast<const uint8_t*>(arg%d.arg), arg%d.arg_length);\n",
                                argIndex, argIndex);
                        break;
                    case JAVA_TYPE_BOOLEAN:
                        fprintf(out, "    stats_event_write_bool(event, arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_INT: // Fall through.
                    case JAVA_TYPE_ENUM:
                        fprintf(out, "    stats_event_write_int32(event, arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_FLOAT:
                        fprintf(out, "    stats_event_write_float(event, arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_LONG:
                        fprintf(out, "    stats_event_write_int64(event, arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_STRING:
                        fprintf(out, "    stats_event_write_string8(event, arg%d);\n", argIndex);
                        break;
                    default:
                        // Unsupported types: OBJECT, DOUBLE.
                        fprintf(stderr, "Encountered unsupported type.");
                        return 1;
                }
                argIndex++;
            }
            fprintf(out, "    const int ret = stats_event_write(event);\n");
            fprintf(out, "    stats_event_release(event);\n");
            fprintf(out, "    return ret;\n");
        }
        fprintf(out, "}\n\n");
    }
    return 0;
}

static void write_native_stats_write_non_chained_methods(FILE* out, const Atoms& atoms,
        const AtomDecl& attributionDecl, const string& moduleName) {
    fprintf(out, "\n");
    for (auto signature_it = atoms.non_chained_signatures_to_modules.begin();
            signature_it != atoms.non_chained_signatures_to_modules.end(); signature_it++) {
        if (!signature_needed_for_module(signature_it->second, moduleName)) {
            continue;
        }
        vector<java_type_t> signature = signature_it->first;

        write_native_method_signature(out, "int stats_write_non_chained", signature,
                attributionDecl, " {");

        vector<java_type_t> newSignature;

        // First two args form the attribution node so size goes down by 1.
        newSignature.reserve(signature.size() - 1);

        // First arg is Attribution Chain.
        newSignature.push_back(JAVA_TYPE_ATTRIBUTION_CHAIN);

        // Followed by the originial signature except the first 2 args.
        newSignature.insert(newSignature.end(), signature.begin() + 2, signature.end());

        const char* uidName = attributionDecl.fields.front().name.c_str();
        const char* tagName = attributionDecl.fields.back().name.c_str();
        fprintf(out, "    const int32_t* %s = &arg1;\n", uidName);
        fprintf(out, "    const size_t %s_length = 1;\n", uidName);
        fprintf(out, "    const std::vector<char const*> %s(1, arg2);\n", tagName);
        fprintf(out, "    return ");
        write_native_method_call(out, "stats_write", newSignature, attributionDecl, 2);

        fprintf(out, "}\n\n");
    }

}
#endif

static void write_native_method_header(
        FILE* out,
        const string& methodName,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const AtomDecl &attributionDecl, const string& moduleName) {

    for (auto signature_to_modules_it = signatures_to_modules.begin();
            signature_to_modules_it != signatures_to_modules.end(); signature_to_modules_it++) {
        // Skip if this signature is not needed for the module.
        if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
            continue;
        }

        vector<java_type_t> signature = signature_to_modules_it->first;
        write_native_method_signature(out, methodName, signature, attributionDecl, ";");
    }
}

int write_stats_log_cpp(FILE *out, const Atoms &atoms, const AtomDecl &attributionDecl,
                        const string& moduleName, const string& cppNamespace,
                        const string& importHeader, const bool supportQ) {
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");

    fprintf(out, "#include <%s>\n", importHeader.c_str());
#if defined(STATS_SCHEMA_LEGACY)
    (void)supportQ; // Workaround for unused parameter error.
    write_native_cpp_includes_q(out);
#else
    if (supportQ) {
        fprintf(out, "#include <StatsEventCompat.h>\n");
    } else {
        fprintf(out, "#include <stats_event.h>\n");
    }
#endif

    fprintf(out, "\n");
    write_namespace(out, cppNamespace);

#if defined(STATS_SCHEMA_LEGACY)
    write_native_stats_log_cpp_globals_q(out);
    write_native_get_timestamp_ns_q(out);
    write_native_try_stats_write_methods_q(out, atoms, attributionDecl, moduleName);
    write_native_stats_write_methods_q(out, "int stats_write", atoms, attributionDecl, moduleName,
            "try_stats_write");
    write_native_try_stats_write_non_chained_methods_q(out, atoms, attributionDecl, moduleName);
    write_native_stats_write_non_chained_methods_q(out, "int stats_write_non_chained", atoms,
            attributionDecl, moduleName, "try_stats_write_non_chained");
#else
    write_native_stats_write_methods(out, atoms, attributionDecl, moduleName, supportQ);
    write_native_stats_write_non_chained_methods(out, atoms, attributionDecl, moduleName);
#endif

    // Print footer
    fprintf(out, "\n");
    write_closing_namespace(out, cppNamespace);

    return 0;
}

int write_stats_log_header(FILE* out, const Atoms& atoms, const AtomDecl &attributionDecl,
        const string& moduleName, const string& cppNamespace) {
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");
    fprintf(out, "#pragma once\n");
    fprintf(out, "\n");
    fprintf(out, "#include <stdint.h>\n");
    fprintf(out, "#include <vector>\n");
    fprintf(out, "#include <map>\n");
    fprintf(out, "#include <set>\n");
    fprintf(out, "\n");

    write_namespace(out, cppNamespace);
    fprintf(out, "\n");
    fprintf(out, "/*\n");
    fprintf(out, " * API For logging statistics events.\n");
    fprintf(out, " */\n");
    fprintf(out, "\n");

    write_native_atom_constants(out, atoms, attributionDecl, moduleName);

    // Print constants for the enum values.
    fprintf(out, "//\n");
    fprintf(out, "// Constants for enum values\n");
    fprintf(out, "//\n\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
        atom != atoms.decls.end(); atom++) {
        // Skip if the atom is not needed for the module.
        if (!atom_needed_for_module(*atom, moduleName)) {
            continue;
        }

        for (vector<AtomField>::const_iterator field = atom->fields.begin();
            field != atom->fields.end(); field++) {
            if (field->javaType == JAVA_TYPE_ENUM) {
                fprintf(out, "// Values for %s.%s\n", atom->message.c_str(),
                    field->name.c_str());
                for (map<int, string>::const_iterator value = field->enumValues.begin();
                    value != field->enumValues.end(); value++) {
                    fprintf(out, "const int32_t %s__%s__%s = %d;\n",
                        make_constant_name(atom->message).c_str(),
                        make_constant_name(field->name).c_str(),
                        make_constant_name(value->second).c_str(),
                        value->first);
                }
                fprintf(out, "\n");
            }
        }
    }

    fprintf(out, "struct BytesField {\n");
    fprintf(out,
            "  BytesField(char const* array, size_t len) : arg(array), "
            "arg_length(len) {}\n");
    fprintf(out, "  char const* arg;\n");
    fprintf(out, "  size_t arg_length;\n");
    fprintf(out, "};\n");
    fprintf(out, "\n");

    // Print write methods
    fprintf(out, "//\n");
    fprintf(out, "// Write methods\n");
    fprintf(out, "//\n");
    write_native_method_header(out, "int stats_write", atoms.signatures_to_modules, attributionDecl,
            moduleName);

    fprintf(out, "//\n");
    fprintf(out, "// Write flattened methods\n");
    fprintf(out, "//\n");
    write_native_method_header(out, "int stats_write_non_chained",
            atoms.non_chained_signatures_to_modules, attributionDecl, moduleName);

    fprintf(out, "\n");
    write_closing_namespace(out, cppNamespace);

    return 0;
}

}  // namespace stats_log_api_gen
}  // namespace android
