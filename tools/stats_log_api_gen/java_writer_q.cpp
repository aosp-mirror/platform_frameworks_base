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

#include "java_writer_q.h"
#include "utils.h"

namespace android {
namespace stats_log_api_gen {

void write_java_q_logging_constants(FILE* out, const string& indent) {
    fprintf(out, "%s// Payload limits.\n", indent.c_str());
    fprintf(out, "%sprivate static final int LOGGER_ENTRY_MAX_PAYLOAD = 4068;\n", indent.c_str());
    fprintf(out,
            "%sprivate static final int MAX_EVENT_PAYLOAD = LOGGER_ENTRY_MAX_PAYLOAD - 4;\n",
            indent.c_str());

    // Value types. Must match with EventLog.java and log.h.
    fprintf(out, "\n");
    fprintf(out, "%s// Value types.\n", indent.c_str());
    fprintf(out, "%sprivate static final byte INT_TYPE = 0;\n", indent.c_str());
    fprintf(out, "%sprivate static final byte LONG_TYPE = 1;\n", indent.c_str());
    fprintf(out, "%sprivate static final byte STRING_TYPE = 2;\n", indent.c_str());
    fprintf(out, "%sprivate static final byte LIST_TYPE = 3;\n", indent.c_str());
    fprintf(out, "%sprivate static final byte FLOAT_TYPE = 4;\n", indent.c_str());

    // Size of each value type.
    // Booleans, ints, floats, and enums take 5 bytes, 1 for the type and 4 for the value.
    fprintf(out, "\n");
    fprintf(out, "%s// Size of each value type.\n", indent.c_str());
    fprintf(out, "%sprivate static final int INT_TYPE_SIZE = 5;\n", indent.c_str());
    fprintf(out, "%sprivate static final int FLOAT_TYPE_SIZE = 5;\n", indent.c_str());
    // Longs take 9 bytes, 1 for the type and 8 for the value.
    fprintf(out, "%sprivate static final int LONG_TYPE_SIZE = 9;\n", indent.c_str());
    // Strings take 5 metadata bytes: 1 byte is for the type, 4 are for the length.
    fprintf(out, "%sprivate static final int STRING_TYPE_OVERHEAD = 5;\n", indent.c_str());
    fprintf(out, "%sprivate static final int LIST_TYPE_OVERHEAD = 2;\n", indent.c_str());
}

int write_java_methods_q_schema(
        FILE* out,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const AtomDecl &attributionDecl,
        const string& moduleName,
        const string& indent) {
    int requiredHelpers = 0;
    for (auto signature_to_modules_it = signatures_to_modules.begin();
            signature_to_modules_it != signatures_to_modules.end(); signature_to_modules_it++) {
        // Skip if this signature is not needed for the module.
        if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
            continue;
        }

        // Print method signature.
        vector<java_type_t> signature = signature_to_modules_it->first;
        fprintf(out, "%spublic static void write(int code", indent.c_str());
        int argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                for (auto chainField : attributionDecl.fields) {
                    fprintf(out, ", %s[] %s",
                        java_type_name(chainField.javaType), chainField.name.c_str());
                }
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                // Module logging does not yet support key value pair.
                fprintf(stderr, "Module logging does not yet support key value pair.\n");
                continue;
            } else {
                fprintf(out, ", %s arg%d", java_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ") {\n");

        // Calculate the size of the buffer.
        fprintf(out, "%s    // Initial overhead of the list, timestamp, and atom tag.\n",
                indent.c_str());
        fprintf(out,
                "%s    int needed = LIST_TYPE_OVERHEAD + LONG_TYPE_SIZE + INT_TYPE_SIZE;\n",
                indent.c_str());
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            switch (*arg) {
            case JAVA_TYPE_BOOLEAN:
            case JAVA_TYPE_INT:
            case JAVA_TYPE_FLOAT:
            case JAVA_TYPE_ENUM:
                fprintf(out, "%s    needed += INT_TYPE_SIZE;\n", indent.c_str());
                break;
            case JAVA_TYPE_LONG:
                // Longs take 9 bytes, 1 for the type and 8 for the value.
                fprintf(out, "%s    needed += LONG_TYPE_SIZE;\n", indent.c_str());
                break;
            case JAVA_TYPE_STRING:
                // Strings take 5 metadata bytes + length of byte encoded string.
                fprintf(out, "%s    if (arg%d == null) {\n", indent.c_str(), argIndex);
                fprintf(out, "%s        arg%d = \"\";\n", indent.c_str(), argIndex);
                fprintf(out, "%s    }\n", indent.c_str());
                fprintf(out,
                        "%s    byte[] arg%dBytes = "
                        "arg%d.getBytes(java.nio.charset.StandardCharsets.UTF_8);\n",
                        indent.c_str(), argIndex, argIndex);
                fprintf(out, "%s    needed += STRING_TYPE_OVERHEAD + arg%dBytes.length;\n",
                        indent.c_str(), argIndex);
                break;
            case JAVA_TYPE_BYTE_ARRAY:
                // Byte arrays take 5 metadata bytes + length of byte array.
                fprintf(out, "%s    if (arg%d == null) {\n", indent.c_str(), argIndex);
                fprintf(out, "%s        arg%d = new byte[0];\n", indent.c_str(), argIndex);
                fprintf(out, "%s    }\n", indent.c_str());
                fprintf(out, "%s    needed += STRING_TYPE_OVERHEAD + arg%d.length;\n",
                        indent.c_str(), argIndex);
                break;
            case JAVA_TYPE_ATTRIBUTION_CHAIN:
            {
                const char* uidName = attributionDecl.fields.front().name.c_str();
                const char* tagName = attributionDecl.fields.back().name.c_str();
                // Null checks on the params.
                fprintf(out, "%s    if (%s == null) {\n", indent.c_str(), uidName);
                fprintf(out, "%s        %s = new %s[0];\n", indent.c_str(), uidName,
                        java_type_name(attributionDecl.fields.front().javaType));
                fprintf(out, "%s    }\n", indent.c_str());
                fprintf(out, "%s    if (%s == null) {\n", indent.c_str(), tagName);
                fprintf(out, "%s        %s = new %s[0];\n", indent.c_str(), tagName,
                        java_type_name(attributionDecl.fields.back().javaType));
                fprintf(out, "%s    }\n", indent.c_str());

                // First check that the lengths of the uid and tag arrays are the same.
                fprintf(out, "%s    if (%s.length != %s.length) {\n",
                        indent.c_str(), uidName, tagName);
                fprintf(out, "%s        return;\n", indent.c_str());
                fprintf(out, "%s    }\n", indent.c_str());
                fprintf(out, "%s    int attrSize = LIST_TYPE_OVERHEAD;\n", indent.c_str());
                fprintf(out, "%s    for (int i = 0; i < %s.length; i++) {\n",
                        indent.c_str(), tagName);
                fprintf(out, "%s        String str%d = (%s[i] == null) ? \"\" : %s[i];\n",
                        indent.c_str(), argIndex, tagName, tagName);
                fprintf(out,
                        "%s        int str%dlen = "
                        "str%d.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;\n",
                        indent.c_str(), argIndex, argIndex);
                fprintf(out,
                        "%s        attrSize += "
                        "LIST_TYPE_OVERHEAD + INT_TYPE_SIZE + STRING_TYPE_OVERHEAD + str%dlen;\n",
                        indent.c_str(), argIndex);
                fprintf(out, "%s    }\n", indent.c_str());
                fprintf(out, "%s    needed += attrSize;\n", indent.c_str());
                break;
            }
            default:
                // Unsupported types: OBJECT, DOUBLE, KEY_VALUE_PAIR.
                fprintf(stderr, "Module logging does not yet support key value pair.\n");
                return 1;
            }
            argIndex++;
        }

        // Now we have the size that is needed. Check for overflow and return if needed.
        fprintf(out, "%s    if (needed > MAX_EVENT_PAYLOAD) {\n", indent.c_str());
        fprintf(out, "%s        return;\n", indent.c_str());
        fprintf(out, "%s    }\n", indent.c_str());

        // Create new buffer, and associated data types.
        fprintf(out, "%s    byte[] buff = new byte[needed];\n", indent.c_str());
        fprintf(out, "%s    int pos = 0;\n", indent.c_str());

        // Initialize the buffer with list data type.
        fprintf(out, "%s    buff[pos] = LIST_TYPE;\n", indent.c_str());
        fprintf(out, "%s    buff[pos + 1] = %zu;\n", indent.c_str(), signature.size() + 2);
        fprintf(out, "%s    pos += LIST_TYPE_OVERHEAD;\n", indent.c_str());

        // Write timestamp.
        fprintf(out, "%s    long elapsedRealtime = SystemClock.elapsedRealtimeNanos();\n", indent.c_str());
        fprintf(out, "%s    buff[pos] = LONG_TYPE;\n", indent.c_str());
        fprintf(out, "%s    copyLong(buff, pos + 1, elapsedRealtime);\n", indent.c_str());
        fprintf(out, "%s    pos += LONG_TYPE_SIZE;\n", indent.c_str());

        // Write atom code.
        fprintf(out, "%s    buff[pos] = INT_TYPE;\n", indent.c_str());
        fprintf(out, "%s    copyInt(buff, pos + 1, code);\n", indent.c_str());
        fprintf(out, "%s    pos += INT_TYPE_SIZE;\n", indent.c_str());

        // Write the args.
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            switch (*arg) {
            case JAVA_TYPE_BOOLEAN:
                fprintf(out, "%s    buff[pos] = INT_TYPE;\n", indent.c_str());
                fprintf(out, "%s    copyInt(buff, pos + 1, arg%d? 1 : 0);\n",
                        indent.c_str(), argIndex);
                fprintf(out, "%s    pos += INT_TYPE_SIZE;\n", indent.c_str());
                break;
            case JAVA_TYPE_INT:
            case JAVA_TYPE_ENUM:
                fprintf(out, "%s    buff[pos] = INT_TYPE;\n", indent.c_str());
                fprintf(out, "%s    copyInt(buff, pos + 1, arg%d);\n", indent.c_str(), argIndex);
                fprintf(out, "%s    pos += INT_TYPE_SIZE;\n", indent.c_str());
                break;
            case JAVA_TYPE_FLOAT:
                requiredHelpers |= JAVA_MODULE_REQUIRES_FLOAT;
                fprintf(out, "%s    buff[pos] = FLOAT_TYPE;\n", indent.c_str());
                fprintf(out, "%s    copyFloat(buff, pos + 1, arg%d);\n", indent.c_str(), argIndex);
                fprintf(out, "%s    pos += FLOAT_TYPE_SIZE;\n", indent.c_str());
                break;
            case JAVA_TYPE_LONG:
                fprintf(out, "%s    buff[pos] = LONG_TYPE;\n", indent.c_str());
                fprintf(out, "%s    copyLong(buff, pos + 1, arg%d);\n", indent.c_str(), argIndex);
                fprintf(out, "%s    pos += LONG_TYPE_SIZE;\n", indent.c_str());
                break;
            case JAVA_TYPE_STRING:
                fprintf(out, "%s    buff[pos] = STRING_TYPE;\n", indent.c_str());
                fprintf(out, "%s    copyInt(buff, pos + 1, arg%dBytes.length);\n",
                        indent.c_str(), argIndex);
                fprintf(out, "%s    System.arraycopy("
                        "arg%dBytes, 0, buff, pos + STRING_TYPE_OVERHEAD, arg%dBytes.length);\n",
                        indent.c_str(), argIndex, argIndex);
                fprintf(out, "%s    pos += STRING_TYPE_OVERHEAD + arg%dBytes.length;\n",
                        indent.c_str(), argIndex);
                break;
            case JAVA_TYPE_BYTE_ARRAY:
                fprintf(out, "%s    buff[pos] = STRING_TYPE;\n", indent.c_str());
                fprintf(out, "%s    copyInt(buff, pos + 1, arg%d.length);\n",
                        indent.c_str(), argIndex);
                fprintf(out, "%s    System.arraycopy("
                        "arg%d, 0, buff, pos + STRING_TYPE_OVERHEAD, arg%d.length);\n",
                        indent.c_str(), argIndex, argIndex);
                fprintf(out, "%s    pos += STRING_TYPE_OVERHEAD + arg%d.length;\n",
                        indent.c_str(), argIndex);
                break;
            case JAVA_TYPE_ATTRIBUTION_CHAIN:
            {
                requiredHelpers |= JAVA_MODULE_REQUIRES_ATTRIBUTION;
                const char* uidName = attributionDecl.fields.front().name.c_str();
                const char* tagName = attributionDecl.fields.back().name.c_str();

                fprintf(out, "%s    writeAttributionChain(buff, pos, %s, %s);\n", indent.c_str(),
                        uidName, tagName);
                fprintf(out, "%s    pos += attrSize;\n", indent.c_str());
                break;
            }
            default:
                // Unsupported types: OBJECT, DOUBLE, KEY_VALUE_PAIR.
                fprintf(stderr,
                        "Object, Double, and KeyValuePairs are not supported in module logging");
                return 1;
            }
            argIndex++;
        }

        fprintf(out, "%s    StatsLog.writeRaw(buff, pos);\n", indent.c_str());
        fprintf(out, "%s}\n", indent.c_str());
        fprintf(out, "\n");
    }

    write_java_helpers_for_q_schema_methods(out, attributionDecl, requiredHelpers, indent);

    return 0;
}

void write_java_helpers_for_q_schema_methods(
        FILE* out,
        const AtomDecl &attributionDecl,
        const int requiredHelpers,
        const string& indent) {
    fprintf(out, "\n");
    fprintf(out, "%s// Helper methods for copying primitives\n", indent.c_str());
    fprintf(out, "%sprivate static void copyInt(byte[] buff, int pos, int val) {\n",
            indent.c_str());
    fprintf(out, "%s    buff[pos] = (byte) (val);\n", indent.c_str());
    fprintf(out, "%s    buff[pos + 1] = (byte) (val >> 8);\n", indent.c_str());
    fprintf(out, "%s    buff[pos + 2] = (byte) (val >> 16);\n", indent.c_str());
    fprintf(out, "%s    buff[pos + 3] = (byte) (val >> 24);\n", indent.c_str());
    fprintf(out, "%s    return;\n", indent.c_str());
    fprintf(out, "%s}\n", indent.c_str());
    fprintf(out, "\n");

    fprintf(out, "%sprivate static void copyLong(byte[] buff, int pos, long val) {\n",
            indent.c_str());
    fprintf(out, "%s    buff[pos] = (byte) (val);\n", indent.c_str());
    fprintf(out, "%s    buff[pos + 1] = (byte) (val >> 8);\n", indent.c_str());
    fprintf(out, "%s    buff[pos + 2] = (byte) (val >> 16);\n", indent.c_str());
    fprintf(out, "%s    buff[pos + 3] = (byte) (val >> 24);\n", indent.c_str());
    fprintf(out, "%s    buff[pos + 4] = (byte) (val >> 32);\n", indent.c_str());
    fprintf(out, "%s    buff[pos + 5] = (byte) (val >> 40);\n", indent.c_str());
    fprintf(out, "%s    buff[pos + 6] = (byte) (val >> 48);\n", indent.c_str());
    fprintf(out, "%s    buff[pos + 7] = (byte) (val >> 56);\n", indent.c_str());
    fprintf(out, "%s    return;\n", indent.c_str());
    fprintf(out, "%s}\n", indent.c_str());
    fprintf(out, "\n");

    if (requiredHelpers & JAVA_MODULE_REQUIRES_FLOAT) {
        fprintf(out, "%sprivate static void copyFloat(byte[] buff, int pos, float val) {\n",
                indent.c_str());
        fprintf(out, "%s    copyInt(buff, pos, Float.floatToIntBits(val));\n", indent.c_str());
        fprintf(out, "%s    return;\n", indent.c_str());
        fprintf(out, "%s}\n", indent.c_str());
        fprintf(out, "\n");
    }

    if (requiredHelpers & JAVA_MODULE_REQUIRES_ATTRIBUTION) {
        fprintf(out, "%sprivate static void writeAttributionChain(byte[] buff, int pos",
                indent.c_str());
        for (auto chainField : attributionDecl.fields) {
            fprintf(out, ", %s[] %s",
                java_type_name(chainField.javaType), chainField.name.c_str());
        }
        fprintf(out, ") {\n");

        const char* uidName = attributionDecl.fields.front().name.c_str();
        const char* tagName = attributionDecl.fields.back().name.c_str();

        // Write the first list begin.
        fprintf(out, "%s    buff[pos] = LIST_TYPE;\n", indent.c_str());
        fprintf(out, "%s    buff[pos + 1] = (byte) (%s.length);\n", indent.c_str(), tagName);
        fprintf(out, "%s    pos += LIST_TYPE_OVERHEAD;\n", indent.c_str());

        // Iterate through the attribution chain and write the nodes.
        fprintf(out, "%s    for (int i = 0; i < %s.length; i++) {\n", indent.c_str(), tagName);
        // Write the list begin.
        fprintf(out, "%s        buff[pos] = LIST_TYPE;\n", indent.c_str());
        fprintf(out, "%s        buff[pos + 1] = %lu;\n",
                indent.c_str(), attributionDecl.fields.size());
        fprintf(out, "%s        pos += LIST_TYPE_OVERHEAD;\n", indent.c_str());

        // Write the uid.
        fprintf(out, "%s        buff[pos] = INT_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyInt(buff, pos + 1, %s[i]);\n", indent.c_str(), uidName);
        fprintf(out, "%s        pos += INT_TYPE_SIZE;\n", indent.c_str());

        // Write the tag.
        fprintf(out, "%s        String %sStr = (%s[i] == null) ? \"\" : %s[i];\n",
                indent.c_str(), tagName, tagName, tagName);
        fprintf(out, "%s        byte[] %sByte = "
                "%sStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);\n",
                indent.c_str(), tagName, tagName);
        fprintf(out, "%s        buff[pos] = STRING_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyInt(buff, pos + 1, %sByte.length);\n", indent.c_str(), tagName);
        fprintf(out, "%s        System.arraycopy("
                "%sByte, 0, buff, pos + STRING_TYPE_OVERHEAD, %sByte.length);\n",
                indent.c_str(), tagName, tagName);
        fprintf(out, "%s        pos += STRING_TYPE_OVERHEAD + %sByte.length;\n",
                indent.c_str(), tagName);
        fprintf(out, "%s    }\n", indent.c_str());
        fprintf(out, "%s}\n", indent.c_str());
        fprintf(out, "\n");
    }
}

// This method is called in main.cpp to generate StatsLog for modules that's compatible with
// Q at compile-time.
int write_stats_log_java_q_for_module(FILE* out, const Atoms& atoms,
                                      const AtomDecl &attributionDecl, const string& moduleName,
                                      const string& javaClass, const string& javaPackage,
                                      const bool supportWorkSource) {
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");
    fprintf(out, "package %s;\n", javaPackage.c_str());
    fprintf(out, "\n");
    fprintf(out, "import static java.nio.charset.StandardCharsets.UTF_8;\n");
    fprintf(out, "\n");
    fprintf(out, "import android.util.StatsLog;\n");
    fprintf(out, "import android.os.SystemClock;\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "/**\n");
    fprintf(out, " * Utility class for logging statistics events.\n");
    fprintf(out, " */\n");
    fprintf(out, "public class %s {\n", javaClass.c_str());

    write_java_q_logging_constants(out, "    ");

    write_java_atom_codes(out, atoms, moduleName);

    write_java_enum_values(out, atoms, moduleName);

    int errors = 0;
    // Print write methods
    fprintf(out, "    // Write methods\n");
    errors += write_java_methods_q_schema(out, atoms.signatures_to_modules, attributionDecl,
            moduleName, "    ");
    errors += write_java_non_chained_methods(out, atoms.non_chained_signatures_to_modules,
            moduleName);
    if (supportWorkSource) {
        errors += write_java_work_source_methods(out, atoms.signatures_to_modules, moduleName);
    }

    fprintf(out, "}\n");

    return errors;
}

#if defined(STATS_SCHEMA_LEGACY)
static void write_java_method(
        FILE* out,
        const string& method_name,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const AtomDecl &attributionDecl) {

    for (auto signature_to_modules_it = signatures_to_modules.begin();
            signature_to_modules_it != signatures_to_modules.end(); signature_to_modules_it++) {
        vector<java_type_t> signature = signature_to_modules_it->first;
        fprintf(out, "    /** @hide */\n");
        fprintf(out, "    public static native int %s(int code", method_name.c_str());
        int argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
            arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                for (auto chainField : attributionDecl.fields) {
                    fprintf(out, ", %s[] %s",
                        java_type_name(chainField.javaType), chainField.name.c_str());
                }
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                fprintf(out, ", android.util.SparseArray<Object> value_map");
            } else {
                fprintf(out, ", %s arg%d", java_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ");\n");
        fprintf(out, "\n");
    }
}

int write_stats_log_java_q(FILE* out, const Atoms& atoms, const AtomDecl &attributionDecl,
                           const bool supportWorkSource) {
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");
    fprintf(out, "package android.util;\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "/**\n");
    fprintf(out, " * API For logging statistics events.\n");
    fprintf(out, " * @hide\n");
    fprintf(out, " */\n");
    fprintf(out, "public class StatsLogInternal {\n");
    write_java_atom_codes(out, atoms, DEFAULT_MODULE_NAME);

    write_java_enum_values(out, atoms, DEFAULT_MODULE_NAME);

    // Print write methods
    fprintf(out, "    // Write methods\n");
    write_java_method(out, "write", atoms.signatures_to_modules, attributionDecl);
    write_java_method(out, "write_non_chained", atoms.non_chained_signatures_to_modules,
            attributionDecl);
    if (supportWorkSource) {
        write_java_work_source_methods(out, atoms.signatures_to_modules, DEFAULT_MODULE_NAME);
    }

    fprintf(out, "}\n");

    return 0;
}
#endif

}  // namespace stats_log_api_gen
}  // namespace android
