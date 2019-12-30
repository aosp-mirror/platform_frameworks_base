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

#include "native_writer_q.h"
#include "utils.h"

namespace android {
namespace stats_log_api_gen {

static void write_native_stats_write_body_q(FILE* out, const vector<java_type_t>& signature,
        const AtomDecl& attributionDecl, const string& indent, const string& tryMethodName) {
    fprintf(out, "%sint ret = 0;\n", indent.c_str());

    fprintf(out, "%sfor(int retry = 0; retry < 2; ++retry) {\n", indent.c_str());
    fprintf(out, "%s    ret = ", indent.c_str());
    write_native_method_call(out, tryMethodName, signature, attributionDecl);
    fprintf(out, "%s    if (ret >= 0) { break; }\n", indent.c_str());

    fprintf(out, "%s    {\n", indent.c_str());
    fprintf(out, "%s        std::lock_guard<std::mutex> lock(mLogdRetryMutex);\n", indent.c_str());
    fprintf(out, "%s        if ((get_elapsed_realtime_ns() - lastRetryTimestampNs) <= "
                            "kMinRetryIntervalNs) break;\n", indent.c_str());
    fprintf(out, "%s        lastRetryTimestampNs = get_elapsed_realtime_ns();\n",
            indent.c_str());
    fprintf(out, "%s    }\n", indent.c_str());
    fprintf(out, "%s    std::this_thread::sleep_for(std::chrono::milliseconds(10));\n",
            indent.c_str());
    fprintf(out, "%s}\n", indent.c_str());
    fprintf(out, "%sif (ret < 0) {\n", indent.c_str());
    fprintf(out, "%s    note_log_drop(ret, code);\n", indent.c_str());
    fprintf(out, "%s}\n", indent.c_str());
    fprintf(out, "%sreturn ret;\n", indent.c_str());
}

void write_native_cpp_includes_q(FILE* out) {
    fprintf(out, "#include <mutex>\n");
    fprintf(out, "#include <chrono>\n");
    fprintf(out, "#include <thread>\n");
    fprintf(out, "#ifdef __ANDROID__\n");
    fprintf(out, "#include <cutils/properties.h>\n");
    fprintf(out, "#endif\n");
    fprintf(out, "#include <stats_event_list.h>\n");
    fprintf(out, "#include <log/log.h>\n");
    fprintf(out, "#include <time.h>\n");
}

void write_native_get_timestamp_ns_q(FILE* out) {
    fprintf(out, "\n");
    fprintf(out, "static int64_t get_elapsed_realtime_ns() {\n");
    fprintf(out, "    struct timespec t;\n");
    fprintf(out, "    t.tv_sec = t.tv_nsec = 0;\n");
    fprintf(out, "    clock_gettime(CLOCK_BOOTTIME, &t);\n");
    fprintf(out, "    return (int64_t)t.tv_sec * 1000000000LL + t.tv_nsec;\n");
    fprintf(out, "}\n");
}

void write_native_stats_log_cpp_globals_q(FILE* out) {
    fprintf(out, "// the single event tag id for all stats logs\n");
    fprintf(out, "const static int kStatsEventTag = 1937006964;\n");
    fprintf(out, "#ifdef __ANDROID__\n");
    fprintf(out,
            "const static bool kStatsdEnabled = property_get_bool(\"ro.statsd.enable\", true);\n");
    fprintf(out, "#else\n");
    fprintf(out, "const static bool kStatsdEnabled = false;\n");
    fprintf(out, "#endif\n");

    fprintf(out, "int64_t lastRetryTimestampNs = -1;\n");
    fprintf(out, "const int64_t kMinRetryIntervalNs = NS_PER_SEC * 60 * 20; // 20 minutes\n");
    fprintf(out, "static std::mutex mLogdRetryMutex;\n");
}

void write_native_try_stats_write_methods_q(FILE* out, const Atoms& atoms,
        const AtomDecl& attributionDecl, const string& moduleName) {
    fprintf(out, "\n");
    for (auto signature_to_modules_it = atoms.signatures_to_modules.begin();
        signature_to_modules_it != atoms.signatures_to_modules.end(); signature_to_modules_it++) {
        if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
            continue;
        }
        vector<java_type_t> signature = signature_to_modules_it->first;

        write_native_method_signature(out, "static int try_stats_write", signature,
                attributionDecl, " {");

        int argIndex = 1;
        fprintf(out, "  if (kStatsdEnabled) {\n");
        fprintf(out, "    stats_event_list event(kStatsEventTag);\n");
        fprintf(out, "    event << get_elapsed_realtime_ns();\n\n");
        fprintf(out, "    event << code;\n\n");
        for (vector<java_type_t>::const_iterator arg = signature.begin();
            arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                for (const auto &chainField : attributionDecl.fields) {
                    if (chainField.javaType == JAVA_TYPE_STRING) {
                        fprintf(out, "    if (%s_length != %s.size()) {\n",
                            attributionDecl.fields.front().name.c_str(), chainField.name.c_str());
                        fprintf(out, "        return -EINVAL;\n");
                        fprintf(out, "    }\n");
                    }
                }
                fprintf(out, "\n    event.begin();\n");
                fprintf(out, "    for (size_t i = 0; i < %s_length; ++i) {\n",
                    attributionDecl.fields.front().name.c_str());
                fprintf(out, "        event.begin();\n");
                for (const auto &chainField : attributionDecl.fields) {
                    if (chainField.javaType == JAVA_TYPE_STRING) {
                        fprintf(out, "        if (%s[i] != NULL) {\n", chainField.name.c_str());
                        fprintf(out, "           event << %s[i];\n", chainField.name.c_str());
                        fprintf(out, "        } else {\n");
                        fprintf(out, "           event << \"\";\n");
                        fprintf(out, "        }\n");
                    } else {
                        fprintf(out, "        event << %s[i];\n", chainField.name.c_str());
                    }
                }
                fprintf(out, "        event.end();\n");
                fprintf(out, "    }\n");
                fprintf(out, "    event.end();\n\n");
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                    fprintf(out, "    event.begin();\n\n");
                    fprintf(out, "    for (const auto& it : arg%d_1) {\n", argIndex);
                    fprintf(out, "         event.begin();\n");
                    fprintf(out, "         event << it.first;\n");
                    fprintf(out, "         event << it.second;\n");
                    fprintf(out, "         event.end();\n");
                    fprintf(out, "    }\n");

                    fprintf(out, "    for (const auto& it : arg%d_2) {\n", argIndex);
                    fprintf(out, "         event.begin();\n");
                    fprintf(out, "         event << it.first;\n");
                    fprintf(out, "         event << it.second;\n");
                    fprintf(out, "         event.end();\n");
                    fprintf(out, "    }\n");

                    fprintf(out, "    for (const auto& it : arg%d_3) {\n", argIndex);
                    fprintf(out, "         event.begin();\n");
                    fprintf(out, "         event << it.first;\n");
                    fprintf(out, "         event << it.second;\n");
                    fprintf(out, "         event.end();\n");
                    fprintf(out, "    }\n");

                    fprintf(out, "    for (const auto& it : arg%d_4) {\n", argIndex);
                    fprintf(out, "         event.begin();\n");
                    fprintf(out, "         event << it.first;\n");
                    fprintf(out, "         event << it.second;\n");
                    fprintf(out, "         event.end();\n");
                    fprintf(out, "    }\n");

                    fprintf(out, "    event.end();\n\n");
            } else if (*arg == JAVA_TYPE_BYTE_ARRAY) {
                fprintf(out,
                        "    event.AppendCharArray(arg%d.arg, "
                        "arg%d.arg_length);\n",
                        argIndex, argIndex);
            } else {
                if (*arg == JAVA_TYPE_STRING) {
                    fprintf(out, "    if (arg%d == NULL) {\n", argIndex);
                    fprintf(out, "        arg%d = \"\";\n", argIndex);
                    fprintf(out, "    }\n");
                }
                fprintf(out, "    event << arg%d;\n", argIndex);
            }
            argIndex++;
        }

        fprintf(out, "    return event.write(LOG_ID_STATS);\n");
        fprintf(out, "  } else {\n");
        fprintf(out, "    return 1;\n");
        fprintf(out, "  }\n");
        fprintf(out, "}\n");
        fprintf(out, "\n");
    }

}

void write_native_stats_write_methods_q(FILE* out, const string& methodName, const Atoms& atoms,
        const AtomDecl& attributionDecl, const string& moduleName, const string& tryMethodName) {
    for (auto signature_to_modules_it = atoms.signatures_to_modules.begin();
        signature_to_modules_it != atoms.signatures_to_modules.end();
        signature_to_modules_it++) {
        if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
            continue;
        }
        vector<java_type_t> signature = signature_to_modules_it->first;

        write_native_method_signature(out, methodName, signature, attributionDecl, " {");

        write_native_stats_write_body_q(out, signature, attributionDecl, "    ", tryMethodName);
        fprintf(out, "}\n\n");
    }
}

void write_native_stats_write_non_chained_methods_q(FILE* out, const string& methodName,
        const Atoms& atoms, const AtomDecl& attributionDecl, const string& moduleName,
        const string& tryMethodName) {
    for (auto signature_it = atoms.non_chained_signatures_to_modules.begin();
            signature_it != atoms.non_chained_signatures_to_modules.end(); signature_it++) {
        if (!signature_needed_for_module(signature_it->second, moduleName)) {
            continue;
        }
        vector<java_type_t> signature = signature_it->first;

        write_native_method_signature(out, methodName, signature, attributionDecl, " {");

        write_native_stats_write_body_q(out, signature, attributionDecl, "    ", tryMethodName);
        fprintf(out, "}\n\n");
    }
}

void write_native_try_stats_write_non_chained_methods_q(FILE* out, const Atoms& atoms,
        const AtomDecl& attributionDecl, const string& moduleName) {
    for (auto signature_it = atoms.non_chained_signatures_to_modules.begin();
            signature_it != atoms.non_chained_signatures_to_modules.end(); signature_it++) {
        if (!signature_needed_for_module(signature_it->second, moduleName)) {
            continue;
        }
        vector<java_type_t> signature = signature_it->first;

        write_native_method_signature(out, "static int try_stats_write_non_chained", signature,
                attributionDecl, " {");

        int argIndex = 1;
        fprintf(out, "  if (kStatsdEnabled) {\n");
        fprintf(out, "    stats_event_list event(kStatsEventTag);\n");
        fprintf(out, "    event << get_elapsed_realtime_ns();\n\n");
        fprintf(out, "    event << code;\n\n");
        for (vector<java_type_t>::const_iterator arg = signature.begin();
            arg != signature.end(); arg++) {
            if (argIndex == 1) {
                fprintf(out, "    event.begin();\n\n");
                fprintf(out, "    event.begin();\n");
            }
            if (*arg == JAVA_TYPE_STRING) {
                fprintf(out, "    if (arg%d == NULL) {\n", argIndex);
                fprintf(out, "        arg%d = \"\";\n", argIndex);
                fprintf(out, "    }\n");
            }
            if (*arg == JAVA_TYPE_BYTE_ARRAY) {
                fprintf(out,
                        "    event.AppendCharArray(arg%d.arg, "
                        "arg%d.arg_length);\n",
                        argIndex, argIndex);
            } else {
                fprintf(out, "    event << arg%d;\n", argIndex);
            }
            if (argIndex == 2) {
                fprintf(out, "    event.end();\n\n");
                fprintf(out, "    event.end();\n\n");
            }
            argIndex++;
        }

        fprintf(out, "    return event.write(LOG_ID_STATS);\n");
        fprintf(out, "  } else {\n");
        fprintf(out, "    return 1;\n");
        fprintf(out, "  }\n");
        fprintf(out, "}\n");
        fprintf(out, "\n");
    }
}

}  // namespace stats_log_api_gen
}  // namespace android
