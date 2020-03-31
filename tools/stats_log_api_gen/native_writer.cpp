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

#include "utils.h"

namespace android {
namespace stats_log_api_gen {

static void write_annotations(FILE* out, int argIndex,
                              const FieldNumberToAtomDeclSet& fieldNumberToAtomDeclSet,
                              const string& methodPrefix, const string& methodSuffix) {
    FieldNumberToAtomDeclSet::const_iterator fieldNumberToAtomDeclSetIt =
            fieldNumberToAtomDeclSet.find(argIndex);
    if (fieldNumberToAtomDeclSet.end() == fieldNumberToAtomDeclSetIt) {
        return;
    }
    const AtomDeclSet& atomDeclSet = fieldNumberToAtomDeclSetIt->second;
    for (const shared_ptr<AtomDecl>& atomDecl : atomDeclSet) {
        fprintf(out, "    if (code == %d) {\n", atomDecl->code);
        const AnnotationSet& annotations = atomDecl->fieldNumberToAnnotations.at(argIndex);
        int resetState = -1;
        int defaultState = -1;
        for (const shared_ptr<Annotation>& annotation : annotations) {
            // TODO(b/151786433): Write atom constant name instead of atom id literal.
            switch (annotation->type) {
                // TODO(b/151776731): Check for reset state annotation and only include
                // reset state when field value == default state annotation value.
                case ANNOTATION_TYPE_INT:
                    // TODO(b/151786433): Write annotation constant name instead of
                    // annotation id literal.
                    if (ANNOTATION_ID_RESET_STATE == annotation->annotationId) {
                        resetState = annotation->value.intValue;
                    } else if (ANNOTATION_ID_DEFAULT_STATE == annotation->annotationId) {
                        defaultState = annotation->value.intValue;
                    } else {
                        fprintf(out, "        %saddInt32Annotation(%s%d, %d);\n",
                                methodPrefix.c_str(), methodSuffix.c_str(),
                                annotation->annotationId, annotation->value.intValue);
                    }
                    break;
                case ANNOTATION_TYPE_BOOL:
                    // TODO(b/151786433): Write annotation constant name instead of
                    // annotation id literal.
                    fprintf(out, "        %saddBoolAnnotation(%s%d, %s);\n", methodPrefix.c_str(),
                            methodSuffix.c_str(), annotation->annotationId,
                            annotation->value.boolValue ? "true" : "false");
                    break;
                default:
                    break;
            }
        }
        if (defaultState != -1 && resetState != -1) {
            fprintf(out, "        if (arg%d == %d) {\n", argIndex, resetState);
            fprintf(out, "            %saddInt32Annotation(%s%d, %d);\n", methodPrefix.c_str(),
                    methodSuffix.c_str(), ANNOTATION_ID_RESET_STATE, defaultState);
            fprintf(out, "        }\n");
        }
        fprintf(out, "    }\n");
    }
}

static int write_native_stats_write_methods(FILE* out, const Atoms& atoms,
                                            const AtomDecl& attributionDecl, const bool supportQ) {
    fprintf(out, "\n");
    for (auto signatureInfoMapIt = atoms.signatureInfoMap.begin();
         signatureInfoMapIt != atoms.signatureInfoMap.end(); signatureInfoMapIt++) {
        vector<java_type_t> signature = signatureInfoMapIt->first;
        const FieldNumberToAtomDeclSet& fieldNumberToAtomDeclSet = signatureInfoMapIt->second;
        // Key value pairs not supported in native.
        if (find(signature.begin(), signature.end(), JAVA_TYPE_KEY_VALUE_PAIR) != signature.end()) {
            continue;
        }
        write_native_method_signature(out, "int stats_write", signature, attributionDecl, " {");

        int argIndex = 1;
        if (supportQ) {
            fprintf(out, "    StatsEventCompat event;\n");
            fprintf(out, "    event.setAtomId(code);\n");
            write_annotations(out, ATOM_ID_FIELD_NUMBER, fieldNumberToAtomDeclSet, "event.", "");
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
                    case JAVA_TYPE_BYTE_ARRAY:
                        fprintf(out, "    event.writeByteArray(arg%d.arg, arg%d.arg_length);\n",
                                argIndex, argIndex);
                        break;
                    case JAVA_TYPE_BOOLEAN:
                        fprintf(out, "    event.writeBool(arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_INT:  // Fall through.
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
                        // Unsupported types: OBJECT, DOUBLE, KEY_VALUE_PAIRS.
                        fprintf(stderr, "Encountered unsupported type.");
                        return 1;
                }
                write_annotations(out, argIndex, fieldNumberToAtomDeclSet, "event.", "");
                argIndex++;
            }
            fprintf(out, "    return event.writeToSocket();\n");
        } else {
            fprintf(out, "    AStatsEvent* event = AStatsEvent_obtain();\n");
            fprintf(out, "    AStatsEvent_setAtomId(event, code);\n");
            write_annotations(out, ATOM_ID_FIELD_NUMBER, fieldNumberToAtomDeclSet, "AStatsEvent_",
                              "event, ");
            for (vector<java_type_t>::const_iterator arg = signature.begin();
                 arg != signature.end(); arg++) {
                switch (*arg) {
                    case JAVA_TYPE_ATTRIBUTION_CHAIN: {
                        const char* uidName = attributionDecl.fields.front().name.c_str();
                        const char* tagName = attributionDecl.fields.back().name.c_str();
                        fprintf(out,
                                "    AStatsEvent_writeAttributionChain(event, "
                                "reinterpret_cast<const uint32_t*>(%s), %s.data(), "
                                "static_cast<uint8_t>(%s_length));\n",
                                uidName, tagName, uidName);
                        break;
                    }
                    case JAVA_TYPE_BYTE_ARRAY:
                        fprintf(out,
                                "    AStatsEvent_writeByteArray(event, "
                                "reinterpret_cast<const uint8_t*>(arg%d.arg), "
                                "arg%d.arg_length);\n",
                                argIndex, argIndex);
                        break;
                    case JAVA_TYPE_BOOLEAN:
                        fprintf(out, "    AStatsEvent_writeBool(event, arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_INT:  // Fall through.
                    case JAVA_TYPE_ENUM:
                        fprintf(out, "    AStatsEvent_writeInt32(event, arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_FLOAT:
                        fprintf(out, "    AStatsEvent_writeFloat(event, arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_LONG:
                        fprintf(out, "    AStatsEvent_writeInt64(event, arg%d);\n", argIndex);
                        break;
                    case JAVA_TYPE_STRING:
                        fprintf(out, "    AStatsEvent_writeString(event, arg%d);\n", argIndex);
                        break;
                    default:
                        // Unsupported types: OBJECT, DOUBLE, KEY_VALUE_PAIRS
                        fprintf(stderr, "Encountered unsupported type.");
                        return 1;
                }
                write_annotations(out, argIndex, fieldNumberToAtomDeclSet, "AStatsEvent_",
                                  "event, ");
                argIndex++;
            }
            fprintf(out, "    const int ret = AStatsEvent_write(event);\n");
            fprintf(out, "    AStatsEvent_release(event);\n");
            fprintf(out, "    return ret;\n");
        }
        fprintf(out, "}\n\n");
    }
    return 0;
}

static void write_native_stats_write_non_chained_methods(FILE* out, const Atoms& atoms,
                                                         const AtomDecl& attributionDecl) {
    fprintf(out, "\n");
    for (auto signature_it = atoms.nonChainedSignatureInfoMap.begin();
         signature_it != atoms.nonChainedSignatureInfoMap.end(); signature_it++) {
        vector<java_type_t> signature = signature_it->first;
        // Key value pairs not supported in native.
        if (find(signature.begin(), signature.end(), JAVA_TYPE_KEY_VALUE_PAIR) != signature.end()) {
            continue;
        }

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

static void write_native_method_header(FILE* out, const string& methodName,
                                       const SignatureInfoMap& signatureInfoMap,
                                       const AtomDecl& attributionDecl) {
    for (auto signatureInfoMapIt = signatureInfoMap.begin();
         signatureInfoMapIt != signatureInfoMap.end(); signatureInfoMapIt++) {
        vector<java_type_t> signature = signatureInfoMapIt->first;

        // Key value pairs not supported in native.
        if (find(signature.begin(), signature.end(), JAVA_TYPE_KEY_VALUE_PAIR) != signature.end()) {
            continue;
        }
        write_native_method_signature(out, methodName, signature, attributionDecl, ";");
    }
}

int write_stats_log_cpp(FILE* out, const Atoms& atoms, const AtomDecl& attributionDecl,
                        const string& cppNamespace, const string& importHeader,
                        const bool supportQ) {
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");

    fprintf(out, "#include <%s>\n", importHeader.c_str());
    if (supportQ) {
        fprintf(out, "#include <StatsEventCompat.h>\n");
    } else {
        fprintf(out, "#include <stats_event.h>\n");
    }

    fprintf(out, "\n");
    write_namespace(out, cppNamespace);

    write_native_stats_write_methods(out, atoms, attributionDecl, supportQ);
    write_native_stats_write_non_chained_methods(out, atoms, attributionDecl);

    // Print footer
    fprintf(out, "\n");
    write_closing_namespace(out, cppNamespace);

    return 0;
}

int write_stats_log_header(FILE* out, const Atoms& atoms, const AtomDecl& attributionDecl,
                           const string& cppNamespace) {
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

    write_native_atom_constants(out, atoms, attributionDecl);

    // Print constants for the enum values.
    fprintf(out, "//\n");
    fprintf(out, "// Constants for enum values\n");
    fprintf(out, "//\n\n");
    for (AtomDeclSet::const_iterator atomIt = atoms.decls.begin(); atomIt != atoms.decls.end();
         atomIt++) {
        for (vector<AtomField>::const_iterator field = (*atomIt)->fields.begin();
             field != (*atomIt)->fields.end(); field++) {
            if (field->javaType == JAVA_TYPE_ENUM) {
                fprintf(out, "// Values for %s.%s\n", (*atomIt)->message.c_str(),
                        field->name.c_str());
                for (map<int, string>::const_iterator value = field->enumValues.begin();
                     value != field->enumValues.end(); value++) {
                    fprintf(out, "const int32_t %s__%s__%s = %d;\n",
                            make_constant_name((*atomIt)->message).c_str(),
                            make_constant_name(field->name).c_str(),
                            make_constant_name(value->second).c_str(), value->first);
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
    write_native_method_header(out, "int stats_write", atoms.signatureInfoMap, attributionDecl);

    fprintf(out, "//\n");
    fprintf(out, "// Write flattened methods\n");
    fprintf(out, "//\n");
    write_native_method_header(out, "int stats_write_non_chained", atoms.nonChainedSignatureInfoMap,
                               attributionDecl);

    fprintf(out, "\n");
    write_closing_namespace(out, cppNamespace);

    return 0;
}

}  // namespace stats_log_api_gen
}  // namespace android
