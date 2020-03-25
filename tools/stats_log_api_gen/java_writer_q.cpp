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
            "%sprivate static final int MAX_EVENT_PAYLOAD = "
            "LOGGER_ENTRY_MAX_PAYLOAD - 4;\n",
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
    // Booleans, ints, floats, and enums take 5 bytes, 1 for the type and 4 for
    // the value.
    fprintf(out, "\n");
    fprintf(out, "%s// Size of each value type.\n", indent.c_str());
    fprintf(out, "%sprivate static final int INT_TYPE_SIZE = 5;\n", indent.c_str());
    fprintf(out, "%sprivate static final int FLOAT_TYPE_SIZE = 5;\n", indent.c_str());
    // Longs take 9 bytes, 1 for the type and 8 for the value.
    fprintf(out, "%sprivate static final int LONG_TYPE_SIZE = 9;\n", indent.c_str());
    // Strings take 5 metadata bytes: 1 byte is for the type, 4 are for the
    // length.
    fprintf(out, "%sprivate static final int STRING_TYPE_OVERHEAD = 5;\n", indent.c_str());
    fprintf(out, "%sprivate static final int LIST_TYPE_OVERHEAD = 2;\n", indent.c_str());
}

int write_java_methods_q_schema(
        FILE* out, const map<vector<java_type_t>, FieldNumberToAnnotations>& signatureInfoMap,
        const AtomDecl& attributionDecl, const string& indent) {
    int requiredHelpers = 0;
    for (auto signatureInfoMapIt = signatureInfoMap.begin();
         signatureInfoMapIt != signatureInfoMap.end(); signatureInfoMapIt++) {
        // Print method signature.
        vector<java_type_t> signature = signatureInfoMapIt->first;
        fprintf(out, "%spublic static void write(int code", indent.c_str());
        int argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin(); arg != signature.end();
             arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                for (auto chainField : attributionDecl.fields) {
                    fprintf(out, ", %s[] %s", java_type_name(chainField.javaType),
                            chainField.name.c_str());
                }
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                fprintf(out, ", android.util.SparseArray<Object> valueMap");
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
                "%s    int needed = LIST_TYPE_OVERHEAD + LONG_TYPE_SIZE + "
                "INT_TYPE_SIZE;\n",
                indent.c_str());
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin(); arg != signature.end();
             arg++) {
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
                case JAVA_TYPE_ATTRIBUTION_CHAIN: {
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

                    // First check that the lengths of the uid and tag arrays are the
                    // same.
                    fprintf(out, "%s    if (%s.length != %s.length) {\n", indent.c_str(), uidName,
                            tagName);
                    fprintf(out, "%s        return;\n", indent.c_str());
                    fprintf(out, "%s    }\n", indent.c_str());
                    fprintf(out, "%s    int attrSize = LIST_TYPE_OVERHEAD;\n", indent.c_str());
                    fprintf(out, "%s    for (int i = 0; i < %s.length; i++) {\n", indent.c_str(),
                            tagName);
                    fprintf(out, "%s        String str%d = (%s[i] == null) ? \"\" : %s[i];\n",
                            indent.c_str(), argIndex, tagName, tagName);
                    fprintf(out,
                            "%s        int str%dlen = "
                            "str%d.getBytes(java.nio.charset.StandardCharsets.UTF_8)."
                            "length;\n",
                            indent.c_str(), argIndex, argIndex);
                    fprintf(out,
                            "%s        attrSize += "
                            "LIST_TYPE_OVERHEAD + INT_TYPE_SIZE + STRING_TYPE_OVERHEAD + "
                            "str%dlen;\n",
                            indent.c_str(), argIndex);
                    fprintf(out, "%s    }\n", indent.c_str());
                    fprintf(out, "%s    needed += attrSize;\n", indent.c_str());
                    break;
                }
                case JAVA_TYPE_KEY_VALUE_PAIR: {
                    fprintf(out, "%s    // Calculate bytes needed by Key Value Pairs.\n",
                            indent.c_str());
                    fprintf(out, "%s    final int count = valueMap.size();\n", indent.c_str());
                    fprintf(out, "%s    android.util.SparseIntArray intMap = null;\n",
                            indent.c_str());
                    fprintf(out, "%s    android.util.SparseLongArray longMap = null;\n",
                            indent.c_str());
                    fprintf(out, "%s    android.util.SparseArray<String> stringMap = null;\n",
                            indent.c_str());
                    fprintf(out, "%s    android.util.SparseArray<Float> floatMap = null;\n",
                            indent.c_str());
                    fprintf(out, "%s    int keyValuePairSize = LIST_TYPE_OVERHEAD;\n",
                            indent.c_str());
                    fprintf(out, "%s    for (int i = 0; i < count; i++) {\n", indent.c_str());
                    fprintf(out, "%s        final int key = valueMap.keyAt(i);\n", indent.c_str());
                    fprintf(out, "%s        final Object value = valueMap.valueAt(i);\n",
                            indent.c_str());
                    fprintf(out, "%s        if (value instanceof Integer) {\n", indent.c_str());
                    fprintf(out, "%s            keyValuePairSize += LIST_TYPE_OVERHEAD\n",
                            indent.c_str());
                    fprintf(out, "%s                    + INT_TYPE_SIZE + INT_TYPE_SIZE;\n",
                            indent.c_str());
                    fprintf(out, "%s            if (null == intMap) {\n", indent.c_str());
                    fprintf(out, "%s                intMap = new android.util.SparseIntArray();\n",
                            indent.c_str());
                    fprintf(out, "%s            }\n", indent.c_str());
                    fprintf(out, "%s            intMap.put(key, (Integer) value);\n",
                            indent.c_str());
                    fprintf(out, "%s        } else if (value instanceof Long) {\n", indent.c_str());
                    fprintf(out, "%s            keyValuePairSize += LIST_TYPE_OVERHEAD\n",
                            indent.c_str());
                    fprintf(out, "%s                    + INT_TYPE_SIZE + LONG_TYPE_SIZE;\n",
                            indent.c_str());
                    fprintf(out, "%s            if (null == longMap) {\n", indent.c_str());
                    fprintf(out,
                            "%s                longMap = new "
                            "android.util.SparseLongArray();\n",
                            indent.c_str());
                    fprintf(out, "%s            }\n", indent.c_str());
                    fprintf(out, "%s            longMap.put(key, (Long) value);\n", indent.c_str());
                    fprintf(out, "%s        } else if (value instanceof String) {\n",
                            indent.c_str());
                    fprintf(out,
                            "%s            final String str = (value == null) ? \"\" : "
                            "(String) value;\n",
                            indent.c_str());
                    fprintf(out,
                            "%s            final int len = "
                            "str.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;\n",
                            indent.c_str());
                    fprintf(out,
                            "%s            keyValuePairSize += LIST_TYPE_OVERHEAD + "
                            "INT_TYPE_SIZE\n",
                            indent.c_str());
                    fprintf(out, "%s                    + STRING_TYPE_OVERHEAD + len;\n",
                            indent.c_str());
                    fprintf(out, "%s            if (null == stringMap) {\n", indent.c_str());
                    fprintf(out,
                            "%s                stringMap = new "
                            "android.util.SparseArray<>();\n",
                            indent.c_str());
                    fprintf(out, "%s            }\n", indent.c_str());
                    fprintf(out, "%s            stringMap.put(key, str);\n", indent.c_str());
                    fprintf(out, "%s        } else if (value instanceof Float) {\n",
                            indent.c_str());
                    fprintf(out, "%s            keyValuePairSize += LIST_TYPE_OVERHEAD\n",
                            indent.c_str());
                    fprintf(out, "%s                    + INT_TYPE_SIZE + FLOAT_TYPE_SIZE;\n",
                            indent.c_str());
                    fprintf(out, "%s            if (null == floatMap) {\n", indent.c_str());
                    fprintf(out,
                            "%s                floatMap = new "
                            "android.util.SparseArray<>();\n",
                            indent.c_str());
                    fprintf(out, "%s            }\n", indent.c_str());
                    fprintf(out, "%s            floatMap.put(key, (Float) value);\n",
                            indent.c_str());
                    fprintf(out, "%s        }\n", indent.c_str());
                    fprintf(out, "%s    }\n", indent.c_str());
                    fprintf(out, "%s    needed += keyValuePairSize;\n", indent.c_str());
                    break;
                }
                default:
                    // Unsupported types: OBJECT, DOUBLE.
                    fprintf(stderr, "Module logging does not yet support Object and Double.\n");
                    return 1;
            }
            argIndex++;
        }

        // Now we have the size that is needed. Check for overflow and return if
        // needed.
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
        fprintf(out, "%s    long elapsedRealtime = SystemClock.elapsedRealtimeNanos();\n",
                indent.c_str());
        fprintf(out, "%s    buff[pos] = LONG_TYPE;\n", indent.c_str());
        fprintf(out, "%s    copyLong(buff, pos + 1, elapsedRealtime);\n", indent.c_str());
        fprintf(out, "%s    pos += LONG_TYPE_SIZE;\n", indent.c_str());

        // Write atom code.
        fprintf(out, "%s    buff[pos] = INT_TYPE;\n", indent.c_str());
        fprintf(out, "%s    copyInt(buff, pos + 1, code);\n", indent.c_str());
        fprintf(out, "%s    pos += INT_TYPE_SIZE;\n", indent.c_str());

        // Write the args.
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin(); arg != signature.end();
             arg++) {
            switch (*arg) {
                case JAVA_TYPE_BOOLEAN:
                    fprintf(out, "%s    buff[pos] = INT_TYPE;\n", indent.c_str());
                    fprintf(out, "%s    copyInt(buff, pos + 1, arg%d? 1 : 0);\n", indent.c_str(),
                            argIndex);
                    fprintf(out, "%s    pos += INT_TYPE_SIZE;\n", indent.c_str());
                    break;
                case JAVA_TYPE_INT:
                case JAVA_TYPE_ENUM:
                    fprintf(out, "%s    buff[pos] = INT_TYPE;\n", indent.c_str());
                    fprintf(out, "%s    copyInt(buff, pos + 1, arg%d);\n", indent.c_str(),
                            argIndex);
                    fprintf(out, "%s    pos += INT_TYPE_SIZE;\n", indent.c_str());
                    break;
                case JAVA_TYPE_FLOAT:
                    requiredHelpers |= JAVA_MODULE_REQUIRES_FLOAT;
                    fprintf(out, "%s    buff[pos] = FLOAT_TYPE;\n", indent.c_str());
                    fprintf(out, "%s    copyFloat(buff, pos + 1, arg%d);\n", indent.c_str(),
                            argIndex);
                    fprintf(out, "%s    pos += FLOAT_TYPE_SIZE;\n", indent.c_str());
                    break;
                case JAVA_TYPE_LONG:
                    fprintf(out, "%s    buff[pos] = LONG_TYPE;\n", indent.c_str());
                    fprintf(out, "%s    copyLong(buff, pos + 1, arg%d);\n", indent.c_str(),
                            argIndex);
                    fprintf(out, "%s    pos += LONG_TYPE_SIZE;\n", indent.c_str());
                    break;
                case JAVA_TYPE_STRING:
                    fprintf(out, "%s    buff[pos] = STRING_TYPE;\n", indent.c_str());
                    fprintf(out, "%s    copyInt(buff, pos + 1, arg%dBytes.length);\n",
                            indent.c_str(), argIndex);
                    fprintf(out,
                            "%s    System.arraycopy("
                            "arg%dBytes, 0, buff, pos + STRING_TYPE_OVERHEAD, "
                            "arg%dBytes.length);\n",
                            indent.c_str(), argIndex, argIndex);
                    fprintf(out, "%s    pos += STRING_TYPE_OVERHEAD + arg%dBytes.length;\n",
                            indent.c_str(), argIndex);
                    break;
                case JAVA_TYPE_BYTE_ARRAY:
                    fprintf(out, "%s    buff[pos] = STRING_TYPE;\n", indent.c_str());
                    fprintf(out, "%s    copyInt(buff, pos + 1, arg%d.length);\n", indent.c_str(),
                            argIndex);
                    fprintf(out,
                            "%s    System.arraycopy("
                            "arg%d, 0, buff, pos + STRING_TYPE_OVERHEAD, arg%d.length);\n",
                            indent.c_str(), argIndex, argIndex);
                    fprintf(out, "%s    pos += STRING_TYPE_OVERHEAD + arg%d.length;\n",
                            indent.c_str(), argIndex);
                    break;
                case JAVA_TYPE_ATTRIBUTION_CHAIN: {
                    requiredHelpers |= JAVA_MODULE_REQUIRES_ATTRIBUTION;
                    const char* uidName = attributionDecl.fields.front().name.c_str();
                    const char* tagName = attributionDecl.fields.back().name.c_str();

                    fprintf(out, "%s    writeAttributionChain(buff, pos, %s, %s);\n",
                            indent.c_str(), uidName, tagName);
                    fprintf(out, "%s    pos += attrSize;\n", indent.c_str());
                    break;
                }
                case JAVA_TYPE_KEY_VALUE_PAIR:
                    requiredHelpers |= JAVA_MODULE_REQUIRES_FLOAT;
                    requiredHelpers |= JAVA_MODULE_REQUIRES_KEY_VALUE_PAIRS;
                    fprintf(out,
                            "%s    writeKeyValuePairs(buff, pos, (byte) count, intMap, "
                            "longMap, "
                            "stringMap, floatMap);\n",
                            indent.c_str());
                    fprintf(out, "%s    pos += keyValuePairSize;\n", indent.c_str());
                    break;
                default:
                    // Unsupported types: OBJECT, DOUBLE.
                    fprintf(stderr, "Object and Double are not supported in module logging");
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

void write_java_helpers_for_q_schema_methods(FILE* out, const AtomDecl& attributionDecl,
                                             const int requiredHelpers, const string& indent) {
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
            fprintf(out, ", %s[] %s", java_type_name(chainField.javaType), chainField.name.c_str());
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
        fprintf(out, "%s        buff[pos + 1] = %lu;\n", indent.c_str(),
                attributionDecl.fields.size());
        fprintf(out, "%s        pos += LIST_TYPE_OVERHEAD;\n", indent.c_str());

        // Write the uid.
        fprintf(out, "%s        buff[pos] = INT_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyInt(buff, pos + 1, %s[i]);\n", indent.c_str(), uidName);
        fprintf(out, "%s        pos += INT_TYPE_SIZE;\n", indent.c_str());

        // Write the tag.
        fprintf(out, "%s        String %sStr = (%s[i] == null) ? \"\" : %s[i];\n", indent.c_str(),
                tagName, tagName, tagName);
        fprintf(out,
                "%s        byte[] %sByte = "
                "%sStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);\n",
                indent.c_str(), tagName, tagName);
        fprintf(out, "%s        buff[pos] = STRING_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyInt(buff, pos + 1, %sByte.length);\n", indent.c_str(), tagName);
        fprintf(out,
                "%s        System.arraycopy("
                "%sByte, 0, buff, pos + STRING_TYPE_OVERHEAD, %sByte.length);\n",
                indent.c_str(), tagName, tagName);
        fprintf(out, "%s        pos += STRING_TYPE_OVERHEAD + %sByte.length;\n", indent.c_str(),
                tagName);
        fprintf(out, "%s    }\n", indent.c_str());
        fprintf(out, "%s}\n", indent.c_str());
        fprintf(out, "\n");
    }

    if (requiredHelpers & JAVA_MODULE_REQUIRES_KEY_VALUE_PAIRS) {
        fprintf(out,
                "%sprivate static void writeKeyValuePairs(byte[] buff, int pos, "
                "byte numPairs,\n",
                indent.c_str());
        fprintf(out, "%s        final android.util.SparseIntArray intMap,\n", indent.c_str());
        fprintf(out, "%s        final android.util.SparseLongArray longMap,\n", indent.c_str());
        fprintf(out, "%s        final android.util.SparseArray<String> stringMap,\n",
                indent.c_str());
        fprintf(out, "%s        final android.util.SparseArray<Float> floatMap) {\n",
                indent.c_str());

        // Start list of lists.
        fprintf(out, "%s    buff[pos] = LIST_TYPE;\n", indent.c_str());
        fprintf(out, "%s    buff[pos + 1] = (byte) numPairs;\n", indent.c_str());
        fprintf(out, "%s    pos += LIST_TYPE_OVERHEAD;\n", indent.c_str());

        // Write integers.
        fprintf(out, "%s    final int intMapSize = null == intMap ? 0 : intMap.size();\n",
                indent.c_str());
        fprintf(out, "%s    for (int i = 0; i < intMapSize; i++) {\n", indent.c_str());
        fprintf(out, "%s        buff[pos] = LIST_TYPE;\n", indent.c_str());
        fprintf(out, "%s        buff[pos + 1] = (byte) 2;\n", indent.c_str());
        fprintf(out, "%s        pos += LIST_TYPE_OVERHEAD;\n", indent.c_str());
        fprintf(out, "%s        final int key = intMap.keyAt(i);\n", indent.c_str());
        fprintf(out, "%s        final int value = intMap.valueAt(i);\n", indent.c_str());
        fprintf(out, "%s        buff[pos] = INT_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyInt(buff, pos + 1, key);\n", indent.c_str());
        fprintf(out, "%s        pos += INT_TYPE_SIZE;\n", indent.c_str());
        fprintf(out, "%s        buff[pos] = INT_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyInt(buff, pos + 1, value);\n", indent.c_str());
        fprintf(out, "%s        pos += INT_TYPE_SIZE;\n", indent.c_str());
        fprintf(out, "%s    }\n", indent.c_str());

        // Write longs.
        fprintf(out, "%s    final int longMapSize = null == longMap ? 0 : longMap.size();\n",
                indent.c_str());
        fprintf(out, "%s    for (int i = 0; i < longMapSize; i++) {\n", indent.c_str());
        fprintf(out, "%s        buff[pos] = LIST_TYPE;\n", indent.c_str());
        fprintf(out, "%s        buff[pos + 1] = (byte) 2;\n", indent.c_str());
        fprintf(out, "%s        pos += LIST_TYPE_OVERHEAD;\n", indent.c_str());
        fprintf(out, "%s        final int key = longMap.keyAt(i);\n", indent.c_str());
        fprintf(out, "%s        final long value = longMap.valueAt(i);\n", indent.c_str());
        fprintf(out, "%s        buff[pos] = INT_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyInt(buff, pos + 1, key);\n", indent.c_str());
        fprintf(out, "%s        pos += INT_TYPE_SIZE;\n", indent.c_str());
        fprintf(out, "%s        buff[pos] = LONG_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyLong(buff, pos + 1, value);\n", indent.c_str());
        fprintf(out, "%s        pos += LONG_TYPE_SIZE;\n", indent.c_str());
        fprintf(out, "%s    }\n", indent.c_str());

        // Write Strings.
        fprintf(out,
                "%s    final int stringMapSize = null == stringMap ? 0 : "
                "stringMap.size();\n",
                indent.c_str());
        fprintf(out, "%s    for (int i = 0; i < stringMapSize; i++) {\n", indent.c_str());
        fprintf(out, "%s        buff[pos] = LIST_TYPE;\n", indent.c_str());
        fprintf(out, "%s        buff[pos + 1] = (byte) 2;\n", indent.c_str());
        fprintf(out, "%s        pos += LIST_TYPE_OVERHEAD;\n", indent.c_str());
        fprintf(out, "%s        final int key = stringMap.keyAt(i);\n", indent.c_str());
        fprintf(out, "%s        final String value = stringMap.valueAt(i);\n", indent.c_str());
        fprintf(out,
                "%s        final byte[] valueBytes = "
                "value.getBytes(java.nio.charset.StandardCharsets.UTF_8);\n",
                indent.c_str());
        fprintf(out, "%s        buff[pos] = INT_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyInt(buff, pos + 1, key);\n", indent.c_str());
        fprintf(out, "%s        pos += INT_TYPE_SIZE;\n", indent.c_str());
        fprintf(out, "%s        buff[pos] = STRING_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyInt(buff, pos + 1, valueBytes.length);\n", indent.c_str());
        fprintf(out,
                "%s        System.arraycopy("
                "valueBytes, 0, buff, pos + STRING_TYPE_OVERHEAD, "
                "valueBytes.length);\n",
                indent.c_str());
        fprintf(out, "%s        pos += STRING_TYPE_OVERHEAD + valueBytes.length;\n",
                indent.c_str());
        fprintf(out, "%s    }\n", indent.c_str());

        // Write floats.
        fprintf(out,
                "%s    final int floatMapSize = null == floatMap ? 0 : "
                "floatMap.size();\n",
                indent.c_str());
        fprintf(out, "%s    for (int i = 0; i < floatMapSize; i++) {\n", indent.c_str());
        fprintf(out, "%s        buff[pos] = LIST_TYPE;\n", indent.c_str());
        fprintf(out, "%s        buff[pos + 1] = (byte) 2;\n", indent.c_str());
        fprintf(out, "%s        pos += LIST_TYPE_OVERHEAD;\n", indent.c_str());
        fprintf(out, "%s        final int key = floatMap.keyAt(i);\n", indent.c_str());
        fprintf(out, "%s        final float value = floatMap.valueAt(i);\n", indent.c_str());
        fprintf(out, "%s        buff[pos] = INT_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyInt(buff, pos + 1, key);\n", indent.c_str());
        fprintf(out, "%s        pos += INT_TYPE_SIZE;\n", indent.c_str());
        fprintf(out, "%s        buff[pos] = FLOAT_TYPE;\n", indent.c_str());
        fprintf(out, "%s        copyFloat(buff, pos + 1, value);\n", indent.c_str());
        fprintf(out, "%s        pos += FLOAT_TYPE_SIZE;\n", indent.c_str());
        fprintf(out, "%s    }\n", indent.c_str());
        fprintf(out, "%s}\n", indent.c_str());
        fprintf(out, "\n");
    }
}

// This method is called in main.cpp to generate StatsLog for modules that's
// compatible with Q at compile-time.
int write_stats_log_java_q_for_module(FILE* out, const Atoms& atoms,
                                      const AtomDecl& attributionDecl, const string& javaClass,
                                      const string& javaPackage, const bool supportWorkSource) {
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

    write_java_atom_codes(out, atoms);

    write_java_enum_values(out, atoms);

    int errors = 0;
    // Print write methods
    fprintf(out, "    // Write methods\n");
    errors += write_java_methods_q_schema(out, atoms.signatureInfoMap, attributionDecl, "    ");
    errors += write_java_non_chained_methods(out, atoms.nonChainedSignatureInfoMap);
    if (supportWorkSource) {
        errors += write_java_work_source_methods(out, atoms.signatureInfoMap);
    }

    fprintf(out, "}\n");

    return errors;
}

}  // namespace stats_log_api_gen
}  // namespace android
