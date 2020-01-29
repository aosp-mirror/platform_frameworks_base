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

#include "java_writer.h"
#include "java_writer_q.h"
#include "utils.h"

namespace android {
namespace stats_log_api_gen {

static int write_java_q_logger_class(
        FILE* out,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const AtomDecl &attributionDecl,
        const string& moduleName
        ) {
    fprintf(out, "\n");
    fprintf(out, "    // Write logging helper methods for statsd in Q and earlier.\n");
    fprintf(out, "    private static class QLogger {\n");

    write_java_q_logging_constants(out, "        ");

    // Print Q write methods.
    fprintf(out, "\n");
    fprintf(out, "        // Write methods.\n");
    write_java_methods_q_schema(
            out, signatures_to_modules, attributionDecl, moduleName, "        ");

    fprintf(out, "    }\n");
    return 0;
}


static int write_java_methods(
        FILE* out,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const AtomDecl &attributionDecl,
        const string& moduleName,
        const bool supportQ
        ) {
    for (auto signature_to_modules_it = signatures_to_modules.begin();
            signature_to_modules_it != signatures_to_modules.end(); signature_to_modules_it++) {
        // Skip if this signature is not needed for the module.
        if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
            continue;
        }

        // Print method signature.
        if (DEFAULT_MODULE_NAME == moduleName) {
            fprintf(out, "    /** @hide */\n");
        }
        fprintf(out, "    public static void write(int code");
        vector<java_type_t> signature = signature_to_modules_it->first;
        int argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                for (auto chainField : attributionDecl.fields) {
                    fprintf(out, ", %s[] %s",
                        java_type_name(chainField.javaType), chainField.name.c_str());
                }
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                fprintf(out, ", android.util.SparseArray<Object> valueMap");
            } else {
                fprintf(out, ", %s arg%d", java_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ") {\n");

        // Print method body.
        string indent("");
        if (supportQ) {
            // TODO(b/146235828): Use just SDK_INT check once it is incremented from Q.
            fprintf(out, "        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q\n");
            fprintf(out, "                || (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q\n");
            fprintf(out, "                    && Build.VERSION.PREVIEW_SDK_INT > 0)) {\n");
            indent = "    ";
        }

        // Start StatsEvent.Builder.
        fprintf(out, "%s        final StatsEvent.Builder builder = StatsEvent.newBuilder();\n",
                indent.c_str());

        // Write atom code.
        fprintf(out, "%s        builder.setAtomId(code);\n", indent.c_str());

        // Write the args.
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            switch (*arg) {
            case JAVA_TYPE_BOOLEAN:
                fprintf(out, "%s        builder.writeBoolean(arg%d);\n", indent.c_str(), argIndex);
                break;
            case JAVA_TYPE_INT:
            case JAVA_TYPE_ENUM:
                fprintf(out, "%s        builder.writeInt(arg%d);\n", indent.c_str(), argIndex);
                break;
            case JAVA_TYPE_FLOAT:
                fprintf(out, "%s        builder.writeFloat(arg%d);\n", indent.c_str(), argIndex);
                break;
            case JAVA_TYPE_LONG:
                fprintf(out, "%s        builder.writeLong(arg%d);\n", indent.c_str(), argIndex);
                break;
            case JAVA_TYPE_STRING:
                fprintf(out, "%s        builder.writeString(arg%d);\n", indent.c_str(), argIndex);
                break;
            case JAVA_TYPE_BYTE_ARRAY:
                fprintf(out, "%s        builder.writeByteArray(null == arg%d ? new byte[0] : arg%d);\n",
                        indent.c_str(), argIndex, argIndex);
                break;
            case JAVA_TYPE_ATTRIBUTION_CHAIN:
            {
                const char* uidName = attributionDecl.fields.front().name.c_str();
                const char* tagName = attributionDecl.fields.back().name.c_str();

                fprintf(out, "%s        builder.writeAttributionChain(\n", indent.c_str());
                fprintf(out, "%s                null == %s ? new int[0] : %s,\n",
                        indent.c_str(), uidName, uidName);
                fprintf(out, "%s                null == %s ? new String[0] : %s);\n",
                        indent.c_str(), tagName, tagName);
                break;
            }
            case JAVA_TYPE_KEY_VALUE_PAIR:
                fprintf(out, "\n");
                fprintf(out,
                        "%s        // Write KeyValuePairs.\n", indent.c_str());
                fprintf(out,
                        "%s        final int count = valueMap.size();\n", indent.c_str());
                fprintf(out,
                        "%s        android.util.SparseIntArray intMap = null;\n",
                        indent.c_str());
                fprintf(out,
                        "%s        android.util.SparseLongArray longMap = null;\n",
                        indent.c_str());
                fprintf(out,
                        "%s        android.util.SparseArray<String> stringMap = null;\n",
                        indent.c_str());
                fprintf(out,
                        "%s        android.util.SparseArray<Float> floatMap = null;\n",
                        indent.c_str());
                fprintf(out,
                        "%s        for (int i = 0; i < count; i++) {\n", indent.c_str());
                fprintf(out,
                        "%s            final int key = valueMap.keyAt(i);\n", indent.c_str());
                fprintf(out,
                        "%s            final Object value = valueMap.valueAt(i);\n",
                        indent.c_str());
                fprintf(out,
                        "%s            if (value instanceof Integer) {\n", indent.c_str());
                fprintf(out,
                        "%s                if (null == intMap) {\n", indent.c_str());
                fprintf(out,
                        "%s                    intMap = new android.util.SparseIntArray();\n", indent.c_str());
                fprintf(out,
                        "%s                }\n", indent.c_str());
                fprintf(out,
                        "%s                intMap.put(key, (Integer) value);\n", indent.c_str());
                fprintf(out,
                        "%s            } else if (value instanceof Long) {\n", indent.c_str());
                fprintf(out,
                        "%s                if (null == longMap) {\n", indent.c_str());
                fprintf(out,
                        "%s                    longMap = new android.util.SparseLongArray();\n", indent.c_str());
                fprintf(out,
                        "%s                }\n", indent.c_str());
                fprintf(out,
                        "%s                longMap.put(key, (Long) value);\n", indent.c_str());
                fprintf(out,
                        "%s            } else if (value instanceof String) {\n", indent.c_str());
                fprintf(out,
                        "%s                if (null == stringMap) {\n", indent.c_str());
                fprintf(out,
                        "%s                    stringMap = new android.util.SparseArray<>();\n", indent.c_str());
                fprintf(out,
                        "%s                }\n", indent.c_str());
                fprintf(out,
                        "%s                stringMap.put(key, (String) value);\n", indent.c_str());
                fprintf(out,
                        "%s            } else if (value instanceof Float) {\n", indent.c_str());
                fprintf(out,
                        "%s                if (null == floatMap) {\n", indent.c_str());
                fprintf(out,
                        "%s                    floatMap = new android.util.SparseArray<>();\n", indent.c_str());
                fprintf(out,
                        "%s                }\n", indent.c_str());
                fprintf(out,
                        "%s                floatMap.put(key, (Float) value);\n", indent.c_str());
                fprintf(out,
                        "%s            }\n", indent.c_str());
                fprintf(out,
                        "%s        }\n", indent.c_str());
                fprintf(out,
                        "%s        builder.writeKeyValuePairs("
                        "intMap, longMap, stringMap, floatMap);\n", indent.c_str());
                break;
            default:
                // Unsupported types: OBJECT, DOUBLE.
                fprintf(stderr, "Encountered unsupported type.");
                return 1;
            }
            argIndex++;
        }

        fprintf(out, "\n");
        fprintf(out, "%s        builder.usePooledBuffer();\n", indent.c_str());
        fprintf(out, "%s        StatsLog.write(builder.build());\n", indent.c_str());

        // Add support for writing using Q schema if this is not the default module.
        if (supportQ) {
            fprintf(out, "        } else {\n");
            fprintf(out, "            QLogger.write(code");
            argIndex = 1;
            for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
                if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                    const char* uidName = attributionDecl.fields.front().name.c_str();
                    const char* tagName = attributionDecl.fields.back().name.c_str();
                    fprintf(out, ", %s, %s", uidName, tagName);
                } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                    // Module logging does not yet support key value pair.
                    fprintf(stderr, "Module logging does not yet support key value pair.\n");
                    return 1;
                } else {
                    fprintf(out, ", arg%d", argIndex);
                }
                argIndex++;
            }
            fprintf(out, ");\n");
            fprintf(out, "        }\n"); // if
        }

        fprintf(out, "    }\n"); // method
        fprintf(out, "\n");
    }
    return 0;

}

int write_stats_log_java(FILE* out, const Atoms& atoms, const AtomDecl &attributionDecl,
                                    const string& moduleName, const string& javaClass,
                                    const string& javaPackage, const bool supportQ,
                                    const bool supportWorkSource) {
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");
    fprintf(out, "package %s;\n", javaPackage.c_str());
    fprintf(out, "\n");
    fprintf(out, "\n");
    if (supportQ) {
        fprintf(out, "import android.os.Build;\n");
        fprintf(out, "import android.os.SystemClock;\n");
    }

    fprintf(out, "import android.util.StatsEvent;\n");
    fprintf(out, "import android.util.StatsLog;\n");

    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "/**\n");
    fprintf(out, " * Utility class for logging statistics events.\n");
    if (DEFAULT_MODULE_NAME == moduleName) {
        fprintf(out, " * @hide\n");
    }
    fprintf(out, " */\n");
    fprintf(out, "public class %s {\n", javaClass.c_str());

    write_java_atom_codes(out, atoms, moduleName);
    write_java_enum_values(out, atoms, moduleName);

    int errors = 0;

    // Print write methods.
    fprintf(out, "    // Write methods\n");
    errors += write_java_methods(
            out, atoms.signatures_to_modules, attributionDecl, moduleName, supportQ);
    errors += write_java_non_chained_methods(
            out, atoms.non_chained_signatures_to_modules, moduleName);
    if (supportWorkSource) {
        errors += write_java_work_source_methods(out, atoms.signatures_to_modules, moduleName);
    }

    if (supportQ) {
        errors += write_java_q_logger_class(
                out, atoms.signatures_to_modules, attributionDecl, moduleName);
    }

    fprintf(out, "}\n");

    return errors;
}

}  // namespace stats_log_api_gen
}  // namespace android
