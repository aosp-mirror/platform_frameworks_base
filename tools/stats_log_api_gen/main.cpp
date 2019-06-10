

#include "Collation.h"

#include "frameworks/base/cmds/statsd/src/atoms.pb.h"

#include <set>
#include <vector>

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "android-base/strings.h"

using namespace google::protobuf;
using namespace std;

namespace android {
namespace stats_log_api_gen {

int maxPushedAtomId = 2;

const string DEFAULT_MODULE_NAME = "DEFAULT";
const string DEFAULT_CPP_NAMESPACE = "android,util";
const string DEFAULT_CPP_HEADER_IMPORT = "statslog.h";
const string DEFAULT_JAVA_PACKAGE = "android.util";
const string DEFAULT_JAVA_CLASS = "StatsLogInternal";

const int JAVA_MODULE_REQUIRES_FLOAT = 0x01;
const int JAVA_MODULE_REQUIRES_ATTRIBUTION = 0x02;

using android::os::statsd::Atom;

/**
 * Turn lower and camel case into upper case with underscores.
 */
static string
make_constant_name(const string& str)
{
    string result;
    const int N = str.size();
    bool underscore_next = false;
    for (int i=0; i<N; i++) {
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

static const char*
cpp_type_name(java_type_t type)
{
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

static const char*
java_type_name(java_type_t type)
{
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

static bool atom_needed_for_module(const AtomDecl& atomDecl, const string& moduleName) {
    if (moduleName == DEFAULT_MODULE_NAME) {
        return true;
    }
    return atomDecl.hasModule && (moduleName == atomDecl.moduleName);
}

static bool signature_needed_for_module(const set<string>& modules, const string& moduleName) {
    if (moduleName == DEFAULT_MODULE_NAME) {
        return true;
    }
    return modules.find(moduleName) != modules.end();
}

static void write_atoms_info_cpp(FILE *out, const Atoms &atoms) {
    std::set<string> kTruncatingAtomNames = {"mobile_radio_power_state_changed",
                                                 "audio_state_changed",
                                                 "call_state_changed",
                                                 "phone_signal_strength_changed",
                                                 "mobile_bytes_transfer_by_fg_bg",
                                                 "mobile_bytes_transfer"};
    fprintf(out,
            "const std::set<int> "
            "AtomsInfo::kTruncatingTimestampAtomBlackList = {\n");
    for (set<string>::const_iterator blacklistedAtom = kTruncatingAtomNames.begin();
         blacklistedAtom != kTruncatingAtomNames.end(); blacklistedAtom++) {
            fprintf(out, " %s,\n", make_constant_name(*blacklistedAtom).c_str());
    }
    fprintf(out, "};\n");
    fprintf(out, "\n");

    fprintf(out,
            "const std::set<int> AtomsInfo::kAtomsWithAttributionChain = {\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
         atom != atoms.decls.end(); atom++) {
        for (vector<AtomField>::const_iterator field = atom->fields.begin();
             field != atom->fields.end(); field++) {
            if (field->javaType == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                string constant = make_constant_name(atom->name);
                fprintf(out, " %s,\n", constant.c_str());
                break;
            }
        }
    }

    fprintf(out, "};\n");
    fprintf(out, "\n");

    fprintf(out,
            "const std::set<int> AtomsInfo::kWhitelistedAtoms = {\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
         atom != atoms.decls.end(); atom++) {
        if (atom->whitelisted) {
            string constant = make_constant_name(atom->name);
            fprintf(out, " %s,\n", constant.c_str());
        }
    }

    fprintf(out, "};\n");
    fprintf(out, "\n");

    fprintf(out, "static std::map<int, int> getAtomUidField() {\n");
    fprintf(out, "  std::map<int, int> uidField;\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
         atom != atoms.decls.end(); atom++) {
        if (atom->uidField == 0) {
            continue;
        }
        fprintf(out,
                "\n    // Adding uid field for atom "
                "(%d)%s\n",
                atom->code, atom->name.c_str());
        fprintf(out, "    uidField[static_cast<int>(%s)] = %d;\n",
                make_constant_name(atom->name).c_str(), atom->uidField);
    }

    fprintf(out, "    return uidField;\n");
    fprintf(out, "};\n");

    fprintf(out,
            "const std::map<int, int> AtomsInfo::kAtomsWithUidField = "
            "getAtomUidField();\n");

    fprintf(out,
            "static std::map<int, StateAtomFieldOptions> "
            "getStateAtomFieldOptions() {\n");
    fprintf(out, "    std::map<int, StateAtomFieldOptions> options;\n");
    fprintf(out, "    StateAtomFieldOptions opt;\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
         atom != atoms.decls.end(); atom++) {
        if (atom->primaryFields.size() == 0 && atom->exclusiveField == 0) {
            continue;
        }
        fprintf(out,
                "\n    // Adding primary and exclusive fields for atom "
                "(%d)%s\n",
                atom->code, atom->name.c_str());
        fprintf(out, "    opt.primaryFields.clear();\n");
        for (const auto& field : atom->primaryFields) {
            fprintf(out, "    opt.primaryFields.push_back(%d);\n", field);
        }

        fprintf(out, "    opt.exclusiveField = %d;\n", atom->exclusiveField);
        fprintf(out, "    options[static_cast<int>(%s)] = opt;\n",
                make_constant_name(atom->name).c_str());
    }

    fprintf(out, "    return options;\n");
    fprintf(out, "}\n");

    fprintf(out,
            "const std::map<int, StateAtomFieldOptions> "
            "AtomsInfo::kStateAtomsFieldOptions = "
            "getStateAtomFieldOptions();\n");

    fprintf(out,
            "static std::map<int, std::vector<int>> "
            "getBinaryFieldAtoms() {\n");
    fprintf(out, "    std::map<int, std::vector<int>> options;\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
         atom != atoms.decls.end(); atom++) {
        if (atom->binaryFields.size() == 0) {
            continue;
        }
        fprintf(out,
                "\n    // Adding binary fields for atom "
                "(%d)%s\n",
                atom->code, atom->name.c_str());

        for (const auto& field : atom->binaryFields) {
            fprintf(out, "    options[static_cast<int>(%s)].push_back(%d);\n",
                    make_constant_name(atom->name).c_str(), field);
        }
    }

    fprintf(out, "    return options;\n");
    fprintf(out, "}\n");

    fprintf(out,
            "const std::map<int, std::vector<int>> "
            "AtomsInfo::kBytesFieldAtoms = "
            "getBinaryFieldAtoms();\n");
}

// Writes namespaces for the cpp and header files, returning the number of namespaces written.
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

static int write_stats_log_cpp(FILE *out, const Atoms &atoms, const AtomDecl &attributionDecl,
                               const string& moduleName, const string& cppNamespace,
                               const string& importHeader) {
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");

    fprintf(out, "#include <mutex>\n");
    fprintf(out, "#include <chrono>\n");
    fprintf(out, "#include <thread>\n");
    fprintf(out, "#ifdef __ANDROID__\n");
    fprintf(out, "#include <cutils/properties.h>\n");
    fprintf(out, "#endif\n");
    fprintf(out, "#include <stats_event_list.h>\n");
    fprintf(out, "#include <log/log.h>\n");
    fprintf(out, "#include <%s>\n", importHeader.c_str());
    fprintf(out, "#include <utils/SystemClock.h>\n");
    fprintf(out, "\n");

    write_namespace(out, cppNamespace);
    fprintf(out, "// the single event tag id for all stats logs\n");
    fprintf(out, "const static int kStatsEventTag = 1937006964;\n");
    fprintf(out, "#ifdef __ANDROID__\n");
    fprintf(out, "const static bool kStatsdEnabled = property_get_bool(\"ro.statsd.enable\", true);\n");
    fprintf(out, "#else\n");
    fprintf(out, "const static bool kStatsdEnabled = false;\n");
    fprintf(out, "#endif\n");

    // AtomsInfo is only used by statsd internally and is not needed for other modules.
    if (moduleName == DEFAULT_MODULE_NAME) {
        write_atoms_info_cpp(out, atoms);
    }

    fprintf(out, "int64_t lastRetryTimestampNs = -1;\n");
    fprintf(out, "const int64_t kMinRetryIntervalNs = NS_PER_SEC * 60 * 20; // 20 minutes\n");
    fprintf(out, "static std::mutex mLogdRetryMutex;\n");

    // Print write methods
    fprintf(out, "\n");
    for (auto signature_to_modules_it = atoms.signatures_to_modules.begin();
        signature_to_modules_it != atoms.signatures_to_modules.end(); signature_to_modules_it++) {
        if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
            continue;
        }
        vector<java_type_t> signature = signature_to_modules_it->first;
        int argIndex;

        fprintf(out, "int\n");
        fprintf(out, "try_stats_write(int32_t code");
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
            arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                for (auto chainField : attributionDecl.fields) {
                    if (chainField.javaType == JAVA_TYPE_STRING) {
                            fprintf(out, ", const std::vector<%s>& %s",
                                 cpp_type_name(chainField.javaType),
                                 chainField.name.c_str());
                    } else {
                            fprintf(out, ", const %s* %s, size_t %s_length",
                                 cpp_type_name(chainField.javaType),
                                 chainField.name.c_str(), chainField.name.c_str());
                    }
                }
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                fprintf(out, ", const std::map<int, int32_t>& arg%d_1, "
                             "const std::map<int, int64_t>& arg%d_2, "
                             "const std::map<int, char const*>& arg%d_3, "
                             "const std::map<int, float>& arg%d_4",
                             argIndex, argIndex, argIndex, argIndex);
            } else {
                fprintf(out, ", %s arg%d", cpp_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ")\n");

        fprintf(out, "{\n");
        argIndex = 1;
        fprintf(out, "  if (kStatsdEnabled) {\n");
        fprintf(out, "    stats_event_list event(kStatsEventTag);\n");
        fprintf(out, "    event << android::elapsedRealtimeNano();\n\n");
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

   for (auto signature_to_modules_it = atoms.signatures_to_modules.begin();
       signature_to_modules_it != atoms.signatures_to_modules.end(); signature_to_modules_it++) {
       if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
           continue;
       }
       vector<java_type_t> signature = signature_to_modules_it->first;
       int argIndex;

       fprintf(out, "int\n");
       fprintf(out, "stats_write(int32_t code");
       argIndex = 1;
       for (vector<java_type_t>::const_iterator arg = signature.begin();
           arg != signature.end(); arg++) {
           if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
               for (auto chainField : attributionDecl.fields) {
                   if (chainField.javaType == JAVA_TYPE_STRING) {
                           fprintf(out, ", const std::vector<%s>& %s",
                                cpp_type_name(chainField.javaType),
                                chainField.name.c_str());
                   } else {
                           fprintf(out, ", const %s* %s, size_t %s_length",
                                cpp_type_name(chainField.javaType),
                                chainField.name.c_str(), chainField.name.c_str());
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
       fprintf(out, ")\n");

       fprintf(out, "{\n");
       fprintf(out, "  int ret = 0;\n");

       fprintf(out, "  for(int retry = 0; retry < 2; ++retry) {\n");
       fprintf(out, "      ret =  try_stats_write(code");

       argIndex = 1;
       for (vector<java_type_t>::const_iterator arg = signature.begin();
           arg != signature.end(); arg++) {
           if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
               for (auto chainField : attributionDecl.fields) {
                   if (chainField.javaType == JAVA_TYPE_STRING) {
                           fprintf(out, ", %s",
                                chainField.name.c_str());
                   } else {
                           fprintf(out, ",  %s,  %s_length",
                                chainField.name.c_str(), chainField.name.c_str());
                   }
               }
           } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
               fprintf(out, ", arg%d_1, arg%d_2, arg%d_3, arg%d_4", argIndex,
                       argIndex, argIndex, argIndex);
           } else {
               fprintf(out, ", arg%d", argIndex);
           }
           argIndex++;
       }
       fprintf(out, ");\n");
       fprintf(out, "      if (ret >= 0) { break; }\n");

       fprintf(out, "      {\n");
       fprintf(out, "          std::lock_guard<std::mutex> lock(mLogdRetryMutex);\n");
       fprintf(out, "          if ((android::elapsedRealtimeNano() - lastRetryTimestampNs) <= "
                                "kMinRetryIntervalNs) break;\n");
       fprintf(out, "          lastRetryTimestampNs = android::elapsedRealtimeNano();\n");
       fprintf(out, "      }\n");
       fprintf(out, "      std::this_thread::sleep_for(std::chrono::milliseconds(10));\n");
       fprintf(out, "  }\n");
       fprintf(out, "  if (ret < 0) {\n");
       fprintf(out, "      note_log_drop(ret, code);\n");
       fprintf(out, "  }\n");
       fprintf(out, "  return ret;\n");
       fprintf(out, "}\n");
       fprintf(out, "\n");
   }

    for (auto signature_it = atoms.non_chained_signatures_to_modules.begin();
            signature_it != atoms.non_chained_signatures_to_modules.end(); signature_it++) {
        if (!signature_needed_for_module(signature_it->second, moduleName)) {
            continue;
        }
        vector<java_type_t> signature = signature_it->first;
        int argIndex;

        fprintf(out, "int\n");
        fprintf(out, "try_stats_write_non_chained(int32_t code");
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
            arg != signature.end(); arg++) {
            fprintf(out, ", %s arg%d", cpp_type_name(*arg), argIndex);
            argIndex++;
        }
        fprintf(out, ")\n");

        fprintf(out, "{\n");
        argIndex = 1;
        fprintf(out, "  if (kStatsdEnabled) {\n");
        fprintf(out, "    stats_event_list event(kStatsEventTag);\n");
        fprintf(out, "    event << android::elapsedRealtimeNano();\n\n");
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
                        "arg%d.arg_length);",
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

    for (auto signature_it = atoms.non_chained_signatures_to_modules.begin();
            signature_it != atoms.non_chained_signatures_to_modules.end(); signature_it++) {
       if (!signature_needed_for_module(signature_it->second, moduleName)) {
           continue;
       }
       vector<java_type_t> signature = signature_it->first;
       int argIndex;

       fprintf(out, "int\n");
       fprintf(out, "stats_write_non_chained(int32_t code");
       argIndex = 1;
       for (vector<java_type_t>::const_iterator arg = signature.begin();
           arg != signature.end(); arg++) {
           fprintf(out, ", %s arg%d", cpp_type_name(*arg), argIndex);
           argIndex++;
       }
       fprintf(out, ")\n");

       fprintf(out, "{\n");

       fprintf(out, "  int ret = 0;\n");
       fprintf(out, "  for(int retry = 0; retry < 2; ++retry) {\n");
       fprintf(out, "      ret =  try_stats_write_non_chained(code");

       argIndex = 1;
       for (vector<java_type_t>::const_iterator arg = signature.begin();
           arg != signature.end(); arg++) {
           fprintf(out, ", arg%d",   argIndex);
           argIndex++;
       }
       fprintf(out, ");\n");
       fprintf(out, "      if (ret >= 0) { break; }\n");

       fprintf(out, "      {\n");
       fprintf(out, "          std::lock_guard<std::mutex> lock(mLogdRetryMutex);\n");
       fprintf(out, "          if ((android::elapsedRealtimeNano() - lastRetryTimestampNs) <= "
                                "kMinRetryIntervalNs) break;\n");
       fprintf(out, "          lastRetryTimestampNs = android::elapsedRealtimeNano();\n");
       fprintf(out, "      }\n");

       fprintf(out, "      std::this_thread::sleep_for(std::chrono::milliseconds(10));\n");
       fprintf(out, "  }\n");
       fprintf(out, "  if (ret < 0) {\n");
       fprintf(out, "      note_log_drop(ret, code);\n");
       fprintf(out, "  }\n");
       fprintf(out, "  return ret;\n\n");
       fprintf(out, "}\n");

       fprintf(out, "\n");
   }


    // Print footer
    fprintf(out, "\n");
    write_closing_namespace(out, cppNamespace);

    return 0;
}

void build_non_chained_decl_map(const Atoms& atoms,
                                std::map<int, set<AtomDecl>::const_iterator>* decl_map){
    for (set<AtomDecl>::const_iterator atom = atoms.non_chained_decls.begin();
        atom != atoms.non_chained_decls.end(); atom++) {
        decl_map->insert(std::make_pair(atom->code, atom));
    }
}

static void write_cpp_usage(
    FILE* out, const string& method_name, const string& atom_code_name,
    const AtomDecl& atom, const AtomDecl &attributionDecl) {
    fprintf(out, "     * Usage: %s(StatsLog.%s", method_name.c_str(),
            atom_code_name.c_str());

    for (vector<AtomField>::const_iterator field = atom.fields.begin();
            field != atom.fields.end(); field++) {
        if (field->javaType == JAVA_TYPE_ATTRIBUTION_CHAIN) {
            for (auto chainField : attributionDecl.fields) {
                if (chainField.javaType == JAVA_TYPE_STRING) {
                    fprintf(out, ", const std::vector<%s>& %s",
                         cpp_type_name(chainField.javaType),
                         chainField.name.c_str());
                } else {
                    fprintf(out, ", const %s* %s, size_t %s_length",
                         cpp_type_name(chainField.javaType),
                         chainField.name.c_str(), chainField.name.c_str());
                }
            }
        } else if (field->javaType == JAVA_TYPE_KEY_VALUE_PAIR) {
            fprintf(out, ", const std::map<int, int32_t>& %s_int"
                         ", const std::map<int, int64_t>& %s_long"
                         ", const std::map<int, char const*>& %s_str"
                         ", const std::map<int, float>& %s_float",
                         field->name.c_str(),
                         field->name.c_str(),
                         field->name.c_str(),
                         field->name.c_str());
        } else {
            fprintf(out, ", %s %s", cpp_type_name(field->javaType), field->name.c_str());
        }
    }
    fprintf(out, ");\n");
}

static void write_cpp_method_header(
        FILE* out,
        const string& method_name,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const AtomDecl &attributionDecl, const string& moduleName) {

    for (auto signature_to_modules_it = signatures_to_modules.begin();
            signature_to_modules_it != signatures_to_modules.end(); signature_to_modules_it++) {
        // Skip if this signature is not needed for the module.
        if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
            continue;
        }

        vector<java_type_t> signature = signature_to_modules_it->first;
        fprintf(out, "int %s(int32_t code", method_name.c_str());
        int argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                for (auto chainField : attributionDecl.fields) {
                    if (chainField.javaType == JAVA_TYPE_STRING) {
                        fprintf(out, ", const std::vector<%s>& %s",
                            cpp_type_name(chainField.javaType), chainField.name.c_str());
                    } else {
                        fprintf(out, ", const %s* %s, size_t %s_length",
                            cpp_type_name(chainField.javaType),
                            chainField.name.c_str(), chainField.name.c_str());
                    }
                }
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                fprintf(out, ", const std::map<int, int32_t>& arg%d_1, "
                             "const std::map<int, int64_t>& arg%d_2, "
                             "const std::map<int, char const*>& arg%d_3, "
                             "const std::map<int, float>& arg%d_4",
                             argIndex, argIndex, argIndex, argIndex);
            } else {
                fprintf(out, ", %s arg%d", cpp_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ");\n");

    }
}

static int
write_stats_log_header(FILE* out, const Atoms& atoms, const AtomDecl &attributionDecl,
        const string& moduleName, const string& cppNamespace)
{
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
    fprintf(out, "/**\n");
    fprintf(out, " * Constants for atom codes.\n");
    fprintf(out, " */\n");
    fprintf(out, "enum {\n");

    std::map<int, set<AtomDecl>::const_iterator> atom_code_to_non_chained_decl_map;
    build_non_chained_decl_map(atoms, &atom_code_to_non_chained_decl_map);

    size_t i = 0;
    // Print atom constants
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
        atom != atoms.decls.end(); atom++) {
        // Skip if the atom is not needed for the module.
        if (!atom_needed_for_module(*atom, moduleName)) {
            continue;
        }
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
        if (atom->code < PULL_ATOM_START_ID && atom->code > maxPushedAtomId) {
            maxPushedAtomId = atom->code;
        }
        i++;
    }
    fprintf(out, "\n");
    fprintf(out, "};\n");
    fprintf(out, "\n");

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

    // This metadata is only used by statsd, which uses the default libstatslog.
    if (moduleName == DEFAULT_MODULE_NAME) {

        fprintf(out, "struct StateAtomFieldOptions {\n");
        fprintf(out, "  std::vector<int> primaryFields;\n");
        fprintf(out, "  int exclusiveField;\n");
        fprintf(out, "};\n");
        fprintf(out, "\n");

        fprintf(out, "struct AtomsInfo {\n");
        fprintf(out,
                "  const static std::set<int> "
                "kTruncatingTimestampAtomBlackList;\n");
        fprintf(out, "  const static std::map<int, int> kAtomsWithUidField;\n");
        fprintf(out,
                "  const static std::set<int> kAtomsWithAttributionChain;\n");
        fprintf(out,
                "  const static std::map<int, StateAtomFieldOptions> "
                "kStateAtomsFieldOptions;\n");
        fprintf(out,
                "  const static std::map<int, std::vector<int>> "
                "kBytesFieldAtoms;");
        fprintf(out,
                "  const static std::set<int> kWhitelistedAtoms;\n");
        fprintf(out, "};\n");

        fprintf(out, "const static int kMaxPushedAtomId = %d;\n\n",
                maxPushedAtomId);
    }

    // Print write methods
    fprintf(out, "//\n");
    fprintf(out, "// Write methods\n");
    fprintf(out, "//\n");
    write_cpp_method_header(out, "stats_write", atoms.signatures_to_modules, attributionDecl,
            moduleName);

    fprintf(out, "//\n");
    fprintf(out, "// Write flattened methods\n");
    fprintf(out, "//\n");
    write_cpp_method_header(out, "stats_write_non_chained", atoms.non_chained_signatures_to_modules,
        attributionDecl, moduleName);

    fprintf(out, "\n");
    write_closing_namespace(out, cppNamespace);

    return 0;
}

static void write_java_usage(FILE* out, const string& method_name, const string& atom_code_name,
        const AtomDecl& atom) {
    fprintf(out, "     * Usage: StatsLog.%s(StatsLog.%s",
        method_name.c_str(), atom_code_name.c_str());
    for (vector<AtomField>::const_iterator field = atom.fields.begin();
        field != atom.fields.end(); field++) {
        if (field->javaType == JAVA_TYPE_ATTRIBUTION_CHAIN) {
            fprintf(out, ", android.os.WorkSource workSource");
        } else if (field->javaType == JAVA_TYPE_KEY_VALUE_PAIR) {
            fprintf(out, ", SparseArray<Object> value_map");
        } else if (field->javaType == JAVA_TYPE_BYTE_ARRAY) {
            fprintf(out, ", byte[] %s", field->name.c_str());
        } else {
            fprintf(out, ", %s %s", java_type_name(field->javaType), field->name.c_str());
        }
    }
    fprintf(out, ");<br>\n");
}

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
                fprintf(out, ", SparseArray<Object> value_map");
            } else {
                fprintf(out, ", %s arg%d", java_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ");\n");
    }
}

static void write_java_helpers_for_module(
        FILE * out,
        const AtomDecl &attributionDecl,
        const int requiredHelpers) {
    fprintf(out, "    private static void copyInt(byte[] buff, int pos, int val) {\n");
    fprintf(out, "        buff[pos] = (byte) (val);\n");
    fprintf(out, "        buff[pos + 1] = (byte) (val >> 8);\n");
    fprintf(out, "        buff[pos + 2] = (byte) (val >> 16);\n");
    fprintf(out, "        buff[pos + 3] = (byte) (val >> 24);\n");
    fprintf(out, "        return;\n");
    fprintf(out, "    }\n");
    fprintf(out, "\n");

    fprintf(out, "    private static void copyLong(byte[] buff, int pos, long val) {\n");
    fprintf(out, "        buff[pos] = (byte) (val);\n");
    fprintf(out, "        buff[pos + 1] = (byte) (val >> 8);\n");
    fprintf(out, "        buff[pos + 2] = (byte) (val >> 16);\n");
    fprintf(out, "        buff[pos + 3] = (byte) (val >> 24);\n");
    fprintf(out, "        buff[pos + 4] = (byte) (val >> 32);\n");
    fprintf(out, "        buff[pos + 5] = (byte) (val >> 40);\n");
    fprintf(out, "        buff[pos + 6] = (byte) (val >> 48);\n");
    fprintf(out, "        buff[pos + 7] = (byte) (val >> 56);\n");
    fprintf(out, "        return;\n");
    fprintf(out, "    }\n");
    fprintf(out, "\n");

    if (requiredHelpers & JAVA_MODULE_REQUIRES_FLOAT) {
        fprintf(out, "    private static void copyFloat(byte[] buff, int pos, float val) {\n");
        fprintf(out, "        copyInt(buff, pos, Float.floatToIntBits(val));\n");
        fprintf(out, "        return;\n");
        fprintf(out, "    }\n");
        fprintf(out, "\n");
    }

    if (requiredHelpers & JAVA_MODULE_REQUIRES_ATTRIBUTION) {
        fprintf(out, "    private static void writeAttributionChain(byte[] buff, int pos");
        for (auto chainField : attributionDecl.fields) {
            fprintf(out, ", %s[] %s",
                java_type_name(chainField.javaType), chainField.name.c_str());
        }
        fprintf(out, ") {\n");

        const char* uidName = attributionDecl.fields.front().name.c_str();
        const char* tagName = attributionDecl.fields.back().name.c_str();

        // Write the first list begin.
        fprintf(out, "        buff[pos] = LIST_TYPE;\n");
        fprintf(out, "        buff[pos + 1] = (byte) (%s.length);\n", tagName);
        fprintf(out, "        pos += LIST_TYPE_OVERHEAD;\n");

        // Iterate through the attribution chain and write the nodes.
        fprintf(out, "        for (int i = 0; i < %s.length; i++) {\n", tagName);
        // Write the list begin.
        fprintf(out, "            buff[pos] = LIST_TYPE;\n");
        fprintf(out, "            buff[pos + 1] = %lu;\n", attributionDecl.fields.size());
        fprintf(out, "            pos += LIST_TYPE_OVERHEAD;\n");

        // Write the uid.
        fprintf(out, "            buff[pos] = INT_TYPE;\n");
        fprintf(out, "            copyInt(buff, pos + 1, %s[i]);\n", uidName);
        fprintf(out, "            pos += INT_TYPE_SIZE;\n");

        // Write the tag.
        fprintf(out, "            String %sStr = (%s[i] == null) ? \"\" : %s[i];\n",
                tagName, tagName, tagName);
        fprintf(out, "            byte[] %sByte = %sStr.getBytes(UTF_8);\n", tagName, tagName);
        fprintf(out, "            buff[pos] = STRING_TYPE;\n");
        fprintf(out, "            copyInt(buff, pos + 1, %sByte.length);\n", tagName);
        fprintf(out, "            System.arraycopy("
                "%sByte, 0, buff, pos + STRING_TYPE_OVERHEAD, %sByte.length);\n",
                tagName, tagName);
        fprintf(out, "            pos += STRING_TYPE_OVERHEAD + %sByte.length;\n", tagName);
        fprintf(out, "        }\n");
        fprintf(out, "    }\n");
        fprintf(out, "\n");
    }
}


static int write_java_non_chained_method_for_module(
        FILE* out,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const string& moduleName
        ) {
    for (auto signature_to_modules_it = signatures_to_modules.begin();
            signature_to_modules_it != signatures_to_modules.end(); signature_to_modules_it++) {
        // Skip if this signature is not needed for the module.
        if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
            continue;
        }

        // Print method signature.
        vector<java_type_t> signature = signature_to_modules_it->first;
        fprintf(out, "    public static void write_non_chained(int code");
        int argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                // Non chained signatures should not have attribution chains.
                return 1;
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                // Module logging does not yet support key value pair.
                return 1;
            } else {
                fprintf(out, ", %s arg%d", java_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ") {\n");

        fprintf(out, "        write(code");
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
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

static int write_java_method_for_module(
        FILE* out,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const AtomDecl &attributionDecl,
        const string& moduleName,
        int* requiredHelpers
        ) {

    for (auto signature_to_modules_it = signatures_to_modules.begin();
            signature_to_modules_it != signatures_to_modules.end(); signature_to_modules_it++) {
        // Skip if this signature is not needed for the module.
        if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
            continue;
        }

        // Print method signature.
        vector<java_type_t> signature = signature_to_modules_it->first;
        fprintf(out, "    public static void write(int code");
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
                return 1;
            } else {
                fprintf(out, ", %s arg%d", java_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ") {\n");

        // Calculate the size of the buffer.
        fprintf(out, "        // Initial overhead of the list, timestamp, and atom tag.\n");
        fprintf(out, "        int needed = LIST_TYPE_OVERHEAD + LONG_TYPE_SIZE + INT_TYPE_SIZE;\n");
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            switch (*arg) {
            case JAVA_TYPE_BOOLEAN:
            case JAVA_TYPE_INT:
            case JAVA_TYPE_FLOAT:
            case JAVA_TYPE_ENUM:
                fprintf(out, "        needed += INT_TYPE_SIZE;\n");
                break;
            case JAVA_TYPE_LONG:
                // Longs take 9 bytes, 1 for the type and 8 for the value.
                fprintf(out, "        needed += LONG_TYPE_SIZE;\n");
                break;
            case JAVA_TYPE_STRING:
                // Strings take 5 metadata bytes + length of byte encoded string.
                fprintf(out, "        if (arg%d == null) {\n", argIndex);
                fprintf(out, "            arg%d = \"\";\n", argIndex);
                fprintf(out, "        }\n");
                fprintf(out, "        byte[] arg%dBytes= arg%d.getBytes(UTF_8);\n",
                        argIndex, argIndex);
                fprintf(out, "        needed += STRING_TYPE_OVERHEAD + arg%dBytes.length;\n",
                        argIndex);
                break;
            case JAVA_TYPE_BYTE_ARRAY:
                // Byte arrays take 5 metadata bytes + length of byte array.
                fprintf(out, "        if (arg%d == null) {\n", argIndex);
                fprintf(out, "            arg%d = new byte[0];\n", argIndex);
                fprintf(out, "        }\n");
                fprintf(out, "        needed += STRING_TYPE_OVERHEAD + arg%d.length;\n", argIndex);
                break;
            case JAVA_TYPE_ATTRIBUTION_CHAIN:
            {
                const char* uidName = attributionDecl.fields.front().name.c_str();
                const char* tagName = attributionDecl.fields.back().name.c_str();
                // Null checks on the params.
                fprintf(out, "        if (%s == null) {\n", uidName);
                fprintf(out, "            %s = new %s[0];\n", uidName,
                        java_type_name(attributionDecl.fields.front().javaType));
                fprintf(out, "        }\n");
                fprintf(out, "        if (%s == null) {\n", tagName);
                fprintf(out, "            %s = new %s[0];\n", tagName,
                        java_type_name(attributionDecl.fields.back().javaType));
                fprintf(out, "        }\n");

                // First check that the lengths of the uid and tag arrays are the same.
                fprintf(out, "        if (%s.length != %s.length) {\n", uidName, tagName);
                fprintf(out, "            return;\n");
                fprintf(out, "        }\n");
                fprintf(out, "        int attrSize = LIST_TYPE_OVERHEAD;\n");
                fprintf(out, "        for (int i = 0; i < %s.length; i++) {\n", tagName);
                fprintf(out, "            String str%d = (%s[i] == null) ? \"\" : %s[i];\n",
                        argIndex, tagName, tagName);
                fprintf(out, "            int str%dlen = str%d.getBytes(UTF_8).length;\n",
                        argIndex, argIndex);
                fprintf(out,
                        "            attrSize += "
                        "LIST_TYPE_OVERHEAD + INT_TYPE_SIZE + STRING_TYPE_OVERHEAD + str%dlen;\n",
                        argIndex);
                fprintf(out, "        }\n");
                fprintf(out, "        needed += attrSize;\n");
                break;
            }
            default:
                // Unsupported types: OBJECT, DOUBLE, KEY_VALUE_PAIR.
                return 1;
            }
            argIndex++;
        }

        // Now we have the size that is needed. Check for overflow and return if needed.
        fprintf(out, "        if (needed > MAX_EVENT_PAYLOAD) {\n");
        fprintf(out, "            return;\n");
        fprintf(out, "        }\n");

        // Create new buffer, and associated data types.
        fprintf(out, "        byte[] buff = new byte[needed];\n");
        fprintf(out, "        int pos = 0;\n");

        // Initialize the buffer with list data type.
        fprintf(out, "        buff[pos] = LIST_TYPE;\n");
        fprintf(out, "        buff[pos + 1] = %zu;\n", signature.size() + 2);
        fprintf(out, "        pos += LIST_TYPE_OVERHEAD;\n");

        // Write timestamp.
        fprintf(out, "        long elapsedRealtime = SystemClock.elapsedRealtimeNanos();\n");
        fprintf(out, "        buff[pos] = LONG_TYPE;\n");
        fprintf(out, "        copyLong(buff, pos + 1, elapsedRealtime);\n");
        fprintf(out, "        pos += LONG_TYPE_SIZE;\n");

        // Write atom code.
        fprintf(out, "        buff[pos] = INT_TYPE;\n");
        fprintf(out, "        copyInt(buff, pos + 1, code);\n");
        fprintf(out, "        pos += INT_TYPE_SIZE;\n");

        // Write the args.
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            switch (*arg) {
            case JAVA_TYPE_BOOLEAN:
                fprintf(out, "        buff[pos] = INT_TYPE;\n");
                fprintf(out, "        copyInt(buff, pos + 1, arg%d? 1 : 0);\n", argIndex);
                fprintf(out, "        pos += INT_TYPE_SIZE;\n");
                break;
            case JAVA_TYPE_INT:
            case JAVA_TYPE_ENUM:
                fprintf(out, "        buff[pos] = INT_TYPE;\n");
                fprintf(out, "        copyInt(buff, pos + 1, arg%d);\n", argIndex);
                fprintf(out, "        pos += INT_TYPE_SIZE;\n");
                break;
            case JAVA_TYPE_FLOAT:
                *requiredHelpers |= JAVA_MODULE_REQUIRES_FLOAT;
                fprintf(out, "        buff[pos] = FLOAT_TYPE;\n");
                fprintf(out, "        copyFloat(buff, pos + 1, arg%d);\n", argIndex);
                fprintf(out, "        pos += FLOAT_TYPE_SIZE;\n");
                break;
            case JAVA_TYPE_LONG:
                fprintf(out, "        buff[pos] = LONG_TYPE;\n");
                fprintf(out, "        copyLong(buff, pos + 1, arg%d);\n", argIndex);
                fprintf(out, "        pos += LONG_TYPE_SIZE;\n");
                break;
            case JAVA_TYPE_STRING:
                fprintf(out, "        buff[pos] = STRING_TYPE;\n");
                fprintf(out, "        copyInt(buff, pos + 1, arg%dBytes.length);\n", argIndex);
                fprintf(out, "        System.arraycopy("
                        "arg%dBytes, 0, buff, pos + STRING_TYPE_OVERHEAD, arg%dBytes.length);\n",
                        argIndex, argIndex);
                fprintf(out, "        pos += STRING_TYPE_OVERHEAD + arg%dBytes.length;\n",
                        argIndex);
                break;
            case JAVA_TYPE_BYTE_ARRAY:
                fprintf(out, "        buff[pos] = STRING_TYPE;\n");
                fprintf(out, "        copyInt(buff, pos + 1, arg%d.length);\n", argIndex);
                fprintf(out, "        System.arraycopy("
                        "arg%d, 0, buff, pos + STRING_TYPE_OVERHEAD, arg%d.length);\n",
                        argIndex, argIndex);
                fprintf(out, "        pos += STRING_TYPE_OVERHEAD + arg%d.length;\n", argIndex);
                break;
            case JAVA_TYPE_ATTRIBUTION_CHAIN:
            {
                *requiredHelpers |= JAVA_MODULE_REQUIRES_ATTRIBUTION;
                const char* uidName = attributionDecl.fields.front().name.c_str();
                const char* tagName = attributionDecl.fields.back().name.c_str();

                fprintf(out, "        writeAttributionChain(buff, pos, %s, %s);\n",
                        uidName, tagName);
                fprintf(out, "        pos += attrSize;\n");
                break;
            }
            default:
                // Unsupported types: OBJECT, DOUBLE, KEY_VALUE_PAIR.
                return 1;
            }
            argIndex++;
        }

        fprintf(out, "        StatsLog.writeRaw(buff, pos);\n");
        fprintf(out, "    }\n");
        fprintf(out, "\n");
    }
    return 0;
}

static void write_java_work_source_method(FILE* out,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const string& moduleName) {
    fprintf(out, "\n    // WorkSource methods.\n");
    for (auto signature_to_modules_it = signatures_to_modules.begin();
            signature_to_modules_it != signatures_to_modules.end(); signature_to_modules_it++) {
        // Skip if this signature is not needed for the module.
        if (!signature_needed_for_module(signature_to_modules_it->second, moduleName)) {
            continue;
        }
        vector<java_type_t> signature = signature_to_modules_it->first;
        // Determine if there is Attribution in this signature.
        int attributionArg = -1;
        int argIndexMax = 0;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            argIndexMax++;
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                if (attributionArg > -1) {
                    fprintf(stderr, "An atom contains multiple AttributionNode fields.\n");
                    fprintf(stderr, "This is not supported. Aborting WorkSource method writing.\n");
                    fprintf(out, "\n// Invalid for WorkSource: more than one attribution chain.\n");
                    return;
                }
                attributionArg = argIndexMax;
            }
        }
        if (attributionArg < 0) {
            continue;
        }

        // Method header (signature)
        if (moduleName == DEFAULT_MODULE_NAME) {
            fprintf(out, "    /** @hide */\n");
        }
        fprintf(out, "    public static void write(int code");
        int argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                fprintf(out, ", WorkSource ws");
            } else {
                fprintf(out, ", %s arg%d", java_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ") {\n");

        // write_non_chained() component. TODO: Remove when flat uids are no longer needed.
        fprintf(out, "        for (int i = 0; i < ws.size(); ++i) {\n");
        fprintf(out, "            write_non_chained(code");
        for (int argIndex = 1; argIndex <= argIndexMax; argIndex++) {
            if (argIndex == attributionArg) {
                fprintf(out, ", ws.get(i), ws.getName(i)");
            } else {
               fprintf(out, ", arg%d", argIndex);
            }
        }
        fprintf(out, ");\n");
        fprintf(out, "        }\n"); // close for-loop

        // write() component.
        fprintf(out, "        ArrayList<WorkSource.WorkChain> workChains = ws.getWorkChains();\n");
        fprintf(out, "        if (workChains != null) {\n");
        fprintf(out, "            for (WorkSource.WorkChain wc : workChains) {\n");
        fprintf(out, "                write(code");
        for (int argIndex = 1; argIndex <= argIndexMax; argIndex++) {
            if (argIndex == attributionArg) {
                fprintf(out, ", wc.getUids(), wc.getTags()");
            } else {
               fprintf(out, ", arg%d", argIndex);
            }
        }
        fprintf(out, ");\n");
        fprintf(out, "            }\n"); // close for-loop
        fprintf(out, "        }\n"); // close if
        fprintf(out, "    }\n"); // close method
    }
}

static void write_java_atom_codes(FILE* out, const Atoms& atoms, const string& moduleName) {
    fprintf(out, "    // Constants for atom codes.\n");

    std::map<int, set<AtomDecl>::const_iterator> atom_code_to_non_chained_decl_map;
    build_non_chained_decl_map(atoms, &atom_code_to_non_chained_decl_map);

    // Print constants for the atom codes.
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
            atom != atoms.decls.end(); atom++) {
        // Skip if the atom is not needed for the module.
        if (!atom_needed_for_module(*atom, moduleName)) {
            continue;
        }
        string constant = make_constant_name(atom->name);
        fprintf(out, "\n");
        fprintf(out, "    /**\n");
        fprintf(out, "     * %s %s<br>\n", atom->message.c_str(), atom->name.c_str());
        write_java_usage(out, "write", constant, *atom);
        auto non_chained_decl = atom_code_to_non_chained_decl_map.find(atom->code);
        if (non_chained_decl != atom_code_to_non_chained_decl_map.end()) {
            write_java_usage(out, "write_non_chained", constant, *non_chained_decl->second);
        }
        if (moduleName == DEFAULT_MODULE_NAME) {
            fprintf(out, "     * @hide\n");
        }
        fprintf(out, "     */\n");
        fprintf(out, "    public static final int %s = %d;\n", constant.c_str(), atom->code);
    }
    fprintf(out, "\n");
}

static void write_java_enum_values(FILE* out, const Atoms& atoms, const string& moduleName) {
    fprintf(out, "    // Constants for enum values.\n\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
        atom != atoms.decls.end(); atom++) {
        // Skip if the atom is not needed for the module.
        if (!atom_needed_for_module(*atom, moduleName)) {
            continue;
        }
        for (vector<AtomField>::const_iterator field = atom->fields.begin();
            field != atom->fields.end(); field++) {
            if (field->javaType == JAVA_TYPE_ENUM) {
                fprintf(out, "    // Values for %s.%s\n", atom->message.c_str(),
                    field->name.c_str());
                for (map<int, string>::const_iterator value = field->enumValues.begin();
                    value != field->enumValues.end(); value++) {
                    if (moduleName == DEFAULT_MODULE_NAME) {
                        fprintf(out, "    /** @hide */\n");
                    }
                    fprintf(out, "    public static final int %s__%s__%s = %d;\n",
                        make_constant_name(atom->message).c_str(),
                        make_constant_name(field->name).c_str(),
                        make_constant_name(value->second).c_str(),
                        value->first);
                }
                fprintf(out, "\n");
            }
        }
    }
}

static int
write_stats_log_java(FILE* out, const Atoms& atoms, const AtomDecl &attributionDecl)
{
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");
    fprintf(out, "package android.util;\n");
    fprintf(out, "\n");
    fprintf(out, "import android.os.WorkSource;\n");
    fprintf(out, "import android.util.SparseArray;\n");
    fprintf(out, "import java.util.ArrayList;\n");
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
    write_java_work_source_method(out, atoms.signatures_to_modules, DEFAULT_MODULE_NAME);

    fprintf(out, "}\n");

    return 0;
}

// TODO: Merge this with write_stats_log_java so that we can get rid of StatsLogInternal JNI.
static int
write_stats_log_java_for_module(FILE* out, const Atoms& atoms, const AtomDecl &attributionDecl,
                     const string& moduleName, const string& javaClass, const string& javaPackage)
{
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
    fprintf(out, "import java.util.ArrayList;\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "/**\n");
    fprintf(out, " * Utility class for logging statistics events.\n");
    fprintf(out, " */\n");
    fprintf(out, "public class %s {\n", javaClass.c_str());

    // TODO: ideally these match with the native values (and automatically change if they change).
    fprintf(out, "    private static final int LOGGER_ENTRY_MAX_PAYLOAD = 4068;\n");
    fprintf(out,
            "    private static final int MAX_EVENT_PAYLOAD = LOGGER_ENTRY_MAX_PAYLOAD - 4;\n");
    // Value types. Must match with EventLog.java and log.h.
    fprintf(out, "    private static final byte INT_TYPE = 0;\n");
    fprintf(out, "    private static final byte LONG_TYPE = 1;\n");
    fprintf(out, "    private static final byte STRING_TYPE = 2;\n");
    fprintf(out, "    private static final byte LIST_TYPE = 3;\n");
    fprintf(out, "    private static final byte FLOAT_TYPE = 4;\n");

    // Size of each value type.
    // Booleans, ints, floats, and enums take 5 bytes, 1 for the type and 4 for the value.
    fprintf(out, "    private static final int INT_TYPE_SIZE = 5;\n");
    fprintf(out, "    private static final int FLOAT_TYPE_SIZE = 5;\n");
    // Longs take 9 bytes, 1 for the type and 8 for the value.
    fprintf(out, "    private static final int LONG_TYPE_SIZE = 9;\n");
    // Strings take 5 metadata bytes: 1 byte is for the type, 4 are for the length.
    fprintf(out, "    private static final int STRING_TYPE_OVERHEAD = 5;\n");
    fprintf(out, "    private static final int LIST_TYPE_OVERHEAD = 2;\n");

    write_java_atom_codes(out, atoms, moduleName);

    write_java_enum_values(out, atoms, moduleName);

    int errors = 0;
    int requiredHelpers = 0;
    // Print write methods
    fprintf(out, "    // Write methods\n");
    errors += write_java_method_for_module(out, atoms.signatures_to_modules, attributionDecl,
            moduleName, &requiredHelpers);
    errors += write_java_non_chained_method_for_module(out, atoms.non_chained_signatures_to_modules,
            moduleName);

    fprintf(out, "    // Helper methods for copying primitives\n");
    write_java_helpers_for_module(out, attributionDecl, requiredHelpers);

    fprintf(out, "}\n");

    return errors;
}

static const char*
jni_type_name(java_type_t type)
{
    switch (type) {
        case JAVA_TYPE_BOOLEAN:
            return "jboolean";
        case JAVA_TYPE_INT:
        case JAVA_TYPE_ENUM:
            return "jint";
        case JAVA_TYPE_LONG:
            return "jlong";
        case JAVA_TYPE_FLOAT:
            return "jfloat";
        case JAVA_TYPE_DOUBLE:
            return "jdouble";
        case JAVA_TYPE_STRING:
            return "jstring";
        case JAVA_TYPE_BYTE_ARRAY:
            return "jbyteArray";
        default:
            return "UNKNOWN";
    }
}

static const char*
jni_array_type_name(java_type_t type)
{
    switch (type) {
        case JAVA_TYPE_INT:
            return "jintArray";
        case JAVA_TYPE_FLOAT:
            return "jfloatArray";
        case JAVA_TYPE_STRING:
            return "jobjectArray";
        default:
            return "UNKNOWN";
    }
}

static string
jni_function_name(const string& method_name, const vector<java_type_t>& signature)
{
    string result("StatsLog_" + method_name);
    for (vector<java_type_t>::const_iterator arg = signature.begin();
        arg != signature.end(); arg++) {
        switch (*arg) {
            case JAVA_TYPE_BOOLEAN:
                result += "_boolean";
                break;
            case JAVA_TYPE_INT:
            case JAVA_TYPE_ENUM:
                result += "_int";
                break;
            case JAVA_TYPE_LONG:
                result += "_long";
                break;
            case JAVA_TYPE_FLOAT:
                result += "_float";
                break;
            case JAVA_TYPE_DOUBLE:
                result += "_double";
                break;
            case JAVA_TYPE_STRING:
                result += "_String";
                break;
            case JAVA_TYPE_ATTRIBUTION_CHAIN:
              result += "_AttributionChain";
              break;
            case JAVA_TYPE_KEY_VALUE_PAIR:
              result += "_KeyValuePairs";
              break;
            case JAVA_TYPE_BYTE_ARRAY:
                result += "_bytes";
                break;
            default:
                result += "_UNKNOWN";
                break;
        }
    }
    return result;
}

static const char*
java_type_signature(java_type_t type)
{
    switch (type) {
        case JAVA_TYPE_BOOLEAN:
            return "Z";
        case JAVA_TYPE_INT:
        case JAVA_TYPE_ENUM:
            return "I";
        case JAVA_TYPE_LONG:
            return "J";
        case JAVA_TYPE_FLOAT:
            return "F";
        case JAVA_TYPE_DOUBLE:
            return "D";
        case JAVA_TYPE_STRING:
            return "Ljava/lang/String;";
        case JAVA_TYPE_BYTE_ARRAY:
            return "[B";
        default:
            return "UNKNOWN";
    }
}

static string
jni_function_signature(const vector<java_type_t>& signature, const AtomDecl &attributionDecl)
{
    string result("(I");
    for (vector<java_type_t>::const_iterator arg = signature.begin();
        arg != signature.end(); arg++) {
        if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
            for (auto chainField : attributionDecl.fields) {
                result += "[";
                result += java_type_signature(chainField.javaType);
            }
        } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
            result += "Landroid/util/SparseArray;";
        } else {
            result += java_type_signature(*arg);
        }
    }
    result += ")I";
    return result;
}

static void write_key_value_map_jni(FILE* out) {
   fprintf(out, "    std::map<int, int32_t> int32_t_map;\n");
   fprintf(out, "    std::map<int, int64_t> int64_t_map;\n");
   fprintf(out, "    std::map<int, float> float_map;\n");
   fprintf(out, "    std::map<int, char const*> string_map;\n\n");

   fprintf(out, "    jclass jmap_class = env->FindClass(\"android/util/SparseArray\");\n");

   fprintf(out, "    jmethodID jget_size_method = env->GetMethodID(jmap_class, \"size\", \"()I\");\n");
   fprintf(out, "    jmethodID jget_key_method = env->GetMethodID(jmap_class, \"keyAt\", \"(I)I\");\n");
   fprintf(out, "    jmethodID jget_value_method = env->GetMethodID(jmap_class, \"valueAt\", \"(I)Ljava/lang/Object;\");\n\n");


   fprintf(out, "    std::vector<std::unique_ptr<ScopedUtfChars>> scoped_ufs;\n\n");

   fprintf(out, "    jclass jint_class = env->FindClass(\"java/lang/Integer\");\n");
   fprintf(out, "    jclass jlong_class = env->FindClass(\"java/lang/Long\");\n");
   fprintf(out, "    jclass jfloat_class = env->FindClass(\"java/lang/Float\");\n");
   fprintf(out, "    jclass jstring_class = env->FindClass(\"java/lang/String\");\n");
   fprintf(out, "    jmethodID jget_int_method = env->GetMethodID(jint_class, \"intValue\", \"()I\");\n");
   fprintf(out, "    jmethodID jget_long_method = env->GetMethodID(jlong_class, \"longValue\", \"()J\");\n");
   fprintf(out, "    jmethodID jget_float_method = env->GetMethodID(jfloat_class, \"floatValue\", \"()F\");\n\n");

   fprintf(out, "    jint jsize = env->CallIntMethod(value_map, jget_size_method);\n");
   fprintf(out, "    for(int i = 0; i < jsize; i++) {\n");
   fprintf(out, "        jint key = env->CallIntMethod(value_map, jget_key_method, i);\n");
   fprintf(out, "        jobject jvalue_obj = env->CallObjectMethod(value_map, jget_value_method, i);\n");
   fprintf(out, "        if (jvalue_obj == NULL) { continue; }\n");
   fprintf(out, "        if (env->IsInstanceOf(jvalue_obj, jint_class)) {\n");
   fprintf(out, "            int32_t_map[key] = env->CallIntMethod(jvalue_obj, jget_int_method);\n");
   fprintf(out, "        } else if (env->IsInstanceOf(jvalue_obj, jlong_class)) {\n");
   fprintf(out, "            int64_t_map[key] = env->CallLongMethod(jvalue_obj, jget_long_method);\n");
   fprintf(out, "        } else if (env->IsInstanceOf(jvalue_obj, jfloat_class)) {\n");
   fprintf(out, "            float_map[key] = env->CallFloatMethod(jvalue_obj, jget_float_method);\n");
   fprintf(out, "        } else if (env->IsInstanceOf(jvalue_obj, jstring_class)) {\n");
   fprintf(out, "            std::unique_ptr<ScopedUtfChars> utf(new ScopedUtfChars(env, (jstring)jvalue_obj));\n");
   fprintf(out, "            if (utf->c_str() != NULL) { string_map[key] = utf->c_str(); }\n");
   fprintf(out, "            scoped_ufs.push_back(std::move(utf));\n");
   fprintf(out, "        }\n");
   fprintf(out, "    }\n");
}

static int
write_stats_log_jni(FILE* out, const string& java_method_name, const string& cpp_method_name,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const AtomDecl &attributionDecl) {
    // Print write methods
    for (auto signature_to_modules_it = signatures_to_modules.begin();
            signature_to_modules_it != signatures_to_modules.end(); signature_to_modules_it++) {
        vector<java_type_t> signature = signature_to_modules_it->first;
        int argIndex;

        fprintf(out, "static int\n");
        fprintf(out, "%s(JNIEnv* env, jobject clazz UNUSED, jint code",
                jni_function_name(java_method_name, signature).c_str());
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                for (auto chainField : attributionDecl.fields) {
                    fprintf(out, ", %s %s", jni_array_type_name(chainField.javaType),
                        chainField.name.c_str());
                }
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                fprintf(out, ", jobject value_map");
            } else {
                fprintf(out, ", %s arg%d", jni_type_name(*arg), argIndex);
            }
            argIndex++;
        }
        fprintf(out, ")\n");

        fprintf(out, "{\n");

        // Prepare strings
        argIndex = 1;
        bool hadStringOrChain = false;
        bool isKeyValuePairAtom = false;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_STRING) {
                hadStringOrChain = true;
                fprintf(out, "    const char* str%d;\n", argIndex);
                fprintf(out, "    if (arg%d != NULL) {\n", argIndex);
                fprintf(out, "        str%d = env->GetStringUTFChars(arg%d, NULL);\n",
                        argIndex, argIndex);
                fprintf(out, "    } else {\n");
                fprintf(out, "        str%d = NULL;\n", argIndex);
                fprintf(out, "    }\n");
            } else if (*arg == JAVA_TYPE_BYTE_ARRAY) {
                hadStringOrChain = true;
                fprintf(out, "    jbyte* jbyte_array%d;\n", argIndex);
                fprintf(out, "    const char* str%d;\n", argIndex);
                fprintf(out, "    int str%d_length = 0;\n", argIndex);
                fprintf(out,
                        "    if (arg%d != NULL && env->GetArrayLength(arg%d) > "
                        "0) {\n",
                        argIndex, argIndex);
                fprintf(out,
                        "        jbyte_array%d = "
                        "env->GetByteArrayElements(arg%d, NULL);\n",
                        argIndex, argIndex);
                fprintf(out,
                        "        str%d_length = env->GetArrayLength(arg%d);\n",
                        argIndex, argIndex);
                fprintf(out,
                        "        str%d = "
                        "reinterpret_cast<char*>(env->GetByteArrayElements(arg%"
                        "d, NULL));\n",
                        argIndex, argIndex);
                fprintf(out, "    } else {\n");
                fprintf(out, "        jbyte_array%d = NULL;\n", argIndex);
                fprintf(out, "        str%d = NULL;\n", argIndex);
                fprintf(out, "    }\n");

                fprintf(out,
                        "    android::util::BytesField bytesField%d(str%d, "
                        "str%d_length);",
                        argIndex, argIndex, argIndex);

            } else if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                hadStringOrChain = true;
                for (auto chainField : attributionDecl.fields) {
                    fprintf(out, "    size_t %s_length = env->GetArrayLength(%s);\n",
                        chainField.name.c_str(), chainField.name.c_str());
                    if (chainField.name != attributionDecl.fields.front().name) {
                        fprintf(out, "    if (%s_length != %s_length) {\n",
                            chainField.name.c_str(),
                            attributionDecl.fields.front().name.c_str());
                        fprintf(out, "        return -EINVAL;\n");
                        fprintf(out, "    }\n");
                    }
                    if (chainField.javaType == JAVA_TYPE_INT) {
                        fprintf(out, "    jint* %s_array = env->GetIntArrayElements(%s, NULL);\n",
                            chainField.name.c_str(), chainField.name.c_str());
                    } else if (chainField.javaType == JAVA_TYPE_STRING) {
                        fprintf(out, "    std::vector<%s> %s_vec;\n",
                            cpp_type_name(chainField.javaType), chainField.name.c_str());
                        fprintf(out, "    std::vector<ScopedUtfChars*> scoped_%s_vec;\n",
                            chainField.name.c_str());
                        fprintf(out, "    for (size_t i = 0; i < %s_length; ++i) {\n",
                            chainField.name.c_str());
                        fprintf(out, "        jstring jstr = "
                            "(jstring)env->GetObjectArrayElement(%s, i);\n",
                             chainField.name.c_str());
                        fprintf(out, "        if (jstr == NULL) {\n");
                        fprintf(out, "            %s_vec.push_back(NULL);\n",
                            chainField.name.c_str());
                        fprintf(out, "        } else {\n");
                        fprintf(out, "            ScopedUtfChars* scoped_%s = "
                            "new ScopedUtfChars(env, jstr);\n",
                             chainField.name.c_str());
                        fprintf(out, "            %s_vec.push_back(scoped_%s->c_str());\n",
                                chainField.name.c_str(), chainField.name.c_str());
                        fprintf(out, "            scoped_%s_vec.push_back(scoped_%s);\n",
                                chainField.name.c_str(), chainField.name.c_str());
                        fprintf(out, "        }\n");
                        fprintf(out, "    }\n");
                    }
                    fprintf(out, "\n");
                }
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                isKeyValuePairAtom = true;
            }
            argIndex++;
        }
        // Emit this to quiet the unused parameter warning if there were no strings or attribution
        // chains.
        if (!hadStringOrChain && !isKeyValuePairAtom) {
            fprintf(out, "    (void)env;\n");
        }
        if (isKeyValuePairAtom) {
            write_key_value_map_jni(out);
        }

        // stats_write call
        argIndex = 1;
        fprintf(out, "\n    int ret =  android::util::%s(code",
                cpp_method_name.c_str());
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                for (auto chainField : attributionDecl.fields) {
                    if (chainField.javaType == JAVA_TYPE_INT) {
                        fprintf(out, ", (const %s*)%s_array, %s_length",
                            cpp_type_name(chainField.javaType),
                            chainField.name.c_str(), chainField.name.c_str());
                    } else if (chainField.javaType == JAVA_TYPE_STRING) {
                        fprintf(out, ", %s_vec", chainField.name.c_str());
                    }
                }
            } else if (*arg == JAVA_TYPE_KEY_VALUE_PAIR) {
                fprintf(out, ", int32_t_map, int64_t_map, string_map, float_map");
            } else if (*arg == JAVA_TYPE_BYTE_ARRAY) {
                fprintf(out, ", bytesField%d", argIndex);
            } else {
                const char* argName =
                        (*arg == JAVA_TYPE_STRING) ? "str" : "arg";
                fprintf(out, ", (%s)%s%d", cpp_type_name(*arg), argName, argIndex);
            }
            argIndex++;
        }
        fprintf(out, ");\n");
        fprintf(out, "\n");

        // Clean up strings
        argIndex = 1;
        for (vector<java_type_t>::const_iterator arg = signature.begin();
                arg != signature.end(); arg++) {
            if (*arg == JAVA_TYPE_STRING) {
                fprintf(out, "    if (str%d != NULL) {\n", argIndex);
                fprintf(out, "        env->ReleaseStringUTFChars(arg%d, str%d);\n",
                        argIndex, argIndex);
                fprintf(out, "    }\n");
            } else if (*arg == JAVA_TYPE_BYTE_ARRAY) {
                fprintf(out, "    if (str%d != NULL) { \n", argIndex);
                fprintf(out,
                        "        env->ReleaseByteArrayElements(arg%d, "
                        "jbyte_array%d, 0);\n",
                        argIndex, argIndex);
                fprintf(out, "    }\n");
            } else if (*arg == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                for (auto chainField : attributionDecl.fields) {
                    if (chainField.javaType == JAVA_TYPE_INT) {
                        fprintf(out, "    env->ReleaseIntArrayElements(%s, %s_array, 0);\n",
                            chainField.name.c_str(), chainField.name.c_str());
                    } else if (chainField.javaType == JAVA_TYPE_STRING) {
                        fprintf(out, "    for (size_t i = 0; i < scoped_%s_vec.size(); ++i) {\n",
                            chainField.name.c_str());
                        fprintf(out, "        delete scoped_%s_vec[i];\n", chainField.name.c_str());
                        fprintf(out, "    }\n");
                    }
                }
            }
            argIndex++;
        }

        fprintf(out, "    return ret;\n");

        fprintf(out, "}\n");
        fprintf(out, "\n");
    }


    return 0;
}

void write_jni_registration(FILE* out, const string& java_method_name,
        const map<vector<java_type_t>, set<string>>& signatures_to_modules,
        const AtomDecl &attributionDecl) {
    for (auto signature_to_modules_it = signatures_to_modules.begin();
            signature_to_modules_it != signatures_to_modules.end(); signature_to_modules_it++) {
        vector<java_type_t> signature = signature_to_modules_it->first;
        fprintf(out, "    { \"%s\", \"%s\", (void*)%s },\n",
            java_method_name.c_str(),
            jni_function_signature(signature, attributionDecl).c_str(),
            jni_function_name(java_method_name, signature).c_str());
    }
}

static int
write_stats_log_jni(FILE* out, const Atoms& atoms, const AtomDecl &attributionDecl)
{
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");

    fprintf(out, "#include <statslog.h>\n");
    fprintf(out, "\n");
    fprintf(out, "#include <nativehelper/JNIHelp.h>\n");
    fprintf(out, "#include <nativehelper/ScopedUtfChars.h>\n");
    fprintf(out, "#include <utils/Vector.h>\n");
    fprintf(out, "#include \"core_jni_helpers.h\"\n");
    fprintf(out, "#include \"jni.h\"\n");
    fprintf(out, "\n");
    fprintf(out, "#define UNUSED  __attribute__((__unused__))\n");
    fprintf(out, "\n");

    fprintf(out, "namespace android {\n");
    fprintf(out, "\n");

    write_stats_log_jni(out, "write", "stats_write", atoms.signatures_to_modules, attributionDecl);
    write_stats_log_jni(out, "write_non_chained", "stats_write_non_chained",
            atoms.non_chained_signatures_to_modules, attributionDecl);

    // Print registration function table
    fprintf(out, "/*\n");
    fprintf(out, " * JNI registration.\n");
    fprintf(out, " */\n");
    fprintf(out, "static const JNINativeMethod gRegisterMethods[] = {\n");
    write_jni_registration(out, "write", atoms.signatures_to_modules, attributionDecl);
    write_jni_registration(out, "write_non_chained", atoms.non_chained_signatures_to_modules,
            attributionDecl);
    fprintf(out, "};\n");
    fprintf(out, "\n");

    // Print registration function
    fprintf(out, "int register_android_util_StatsLogInternal(JNIEnv* env) {\n");
    fprintf(out, "    return RegisterMethodsOrDie(\n");
    fprintf(out, "            env,\n");
    fprintf(out, "            \"android/util/StatsLogInternal\",\n");
    fprintf(out, "            gRegisterMethods, NELEM(gRegisterMethods));\n");
    fprintf(out, "}\n");

    fprintf(out, "\n");
    fprintf(out, "} // namespace android\n");
    return 0;
}

static void
print_usage()
{
    fprintf(stderr, "usage: stats-log-api-gen OPTIONS\n");
    fprintf(stderr, "\n");
    fprintf(stderr, "OPTIONS\n");
    fprintf(stderr, "  --cpp FILENAME       the header file to output\n");
    fprintf(stderr, "  --header FILENAME    the cpp file to output\n");
    fprintf(stderr, "  --help               this message\n");
    fprintf(stderr, "  --java FILENAME      the java file to output\n");
    fprintf(stderr, "  --jni FILENAME       the jni file to output\n");
    fprintf(stderr, "  --module NAME        optional, module name to generate outputs for\n");
    fprintf(stderr, "  --namespace COMMA,SEP,NAMESPACE   required for cpp/header with module\n");
    fprintf(stderr, "                                    comma separated namespace of the files\n");
    fprintf(stderr, "  --importHeader NAME  required for cpp/jni to say which header to import\n");
    fprintf(stderr, "  --javaPackage PACKAGE             the package for the java file.\n");
    fprintf(stderr, "                                    required for java with module\n");
    fprintf(stderr, "  --javaClass CLASS    the class name of the java class.\n");
    fprintf(stderr, "                       Optional for Java with module.\n");
    fprintf(stderr, "                       Default is \"StatsLogInternal\"\n");}

/**
 * Do the argument parsing and execute the tasks.
 */
static int
run(int argc, char const*const* argv)
{
    string cppFilename;
    string headerFilename;
    string javaFilename;
    string jniFilename;

    string moduleName = DEFAULT_MODULE_NAME;
    string cppNamespace = DEFAULT_CPP_NAMESPACE;
    string cppHeaderImport = DEFAULT_CPP_HEADER_IMPORT;
    string javaPackage = DEFAULT_JAVA_PACKAGE;
    string javaClass = DEFAULT_JAVA_CLASS;

    int index = 1;
    while (index < argc) {
        if (0 == strcmp("--help", argv[index])) {
            print_usage();
            return 0;
        } else if (0 == strcmp("--cpp", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            cppFilename = argv[index];
        } else if (0 == strcmp("--header", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            headerFilename = argv[index];
        } else if (0 == strcmp("--java", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            javaFilename = argv[index];
        } else if (0 == strcmp("--jni", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            jniFilename = argv[index];
        } else if (0 == strcmp("--module", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            moduleName = argv[index];
        } else if (0 == strcmp("--namespace", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            cppNamespace = argv[index];
        } else if (0 == strcmp("--importHeader", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            cppHeaderImport = argv[index];
        } else if (0 == strcmp("--javaPackage", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            javaPackage = argv[index];
        } else if (0 == strcmp("--javaClass", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            javaClass = argv[index];
        }
        index++;
    }

    if (cppFilename.size() == 0
            && headerFilename.size() == 0
            && javaFilename.size() == 0
            && jniFilename.size() == 0) {
        print_usage();
        return 1;
    }

    // Collate the parameters
    Atoms atoms;
    int errorCount = collate_atoms(Atom::descriptor(), &atoms);
    if (errorCount != 0) {
        return 1;
    }

    AtomDecl attributionDecl;
    vector<java_type_t> attributionSignature;
    collate_atom(android::os::statsd::AttributionNode::descriptor(),
                 &attributionDecl, &attributionSignature);

    // Write the .cpp file
    if (cppFilename.size() != 0) {
        FILE* out = fopen(cppFilename.c_str(), "w");
        if (out == NULL) {
            fprintf(stderr, "Unable to open file for write: %s\n", cppFilename.c_str());
            return 1;
        }
        // If this is for a specific module, the namespace must also be provided.
        if (moduleName != DEFAULT_MODULE_NAME && cppNamespace == DEFAULT_CPP_NAMESPACE) {
            fprintf(stderr, "Must supply --namespace if supplying a specific module\n");
            return 1;
        }
        // If this is for a specific module, the header file to import must also be provided.
        if (moduleName != DEFAULT_MODULE_NAME && cppHeaderImport == DEFAULT_CPP_HEADER_IMPORT) {
            fprintf(stderr, "Must supply --headerImport if supplying a specific module\n");
            return 1;
        }
        errorCount = android::stats_log_api_gen::write_stats_log_cpp(
            out, atoms, attributionDecl, moduleName, cppNamespace, cppHeaderImport);
        fclose(out);
    }

    // Write the .h file
    if (headerFilename.size() != 0) {
        FILE* out = fopen(headerFilename.c_str(), "w");
        if (out == NULL) {
            fprintf(stderr, "Unable to open file for write: %s\n", headerFilename.c_str());
            return 1;
        }
        // If this is for a specific module, the namespace must also be provided.
        if (moduleName != DEFAULT_MODULE_NAME && cppNamespace == DEFAULT_CPP_NAMESPACE) {
            fprintf(stderr, "Must supply --namespace if supplying a specific module\n");
        }
        errorCount = android::stats_log_api_gen::write_stats_log_header(
            out, atoms, attributionDecl, moduleName, cppNamespace);
        fclose(out);
    }

    // Write the .java file
    if (javaFilename.size() != 0) {
        FILE* out = fopen(javaFilename.c_str(), "w");
        if (out == NULL) {
            fprintf(stderr, "Unable to open file for write: %s\n", javaFilename.c_str());
            return 1;
        }
        // If this is for a specific module, the java package must also be provided.
        if (moduleName != DEFAULT_MODULE_NAME && javaPackage== DEFAULT_JAVA_PACKAGE) {
            fprintf(stderr, "Must supply --javaPackage if supplying a specific module\n");
            return 1;
        }
        if (moduleName == DEFAULT_MODULE_NAME) {
            errorCount = android::stats_log_api_gen::write_stats_log_java(
                    out, atoms, attributionDecl);
        } else {
            errorCount = android::stats_log_api_gen::write_stats_log_java_for_module(
                    out, atoms, attributionDecl, moduleName, javaClass, javaPackage);
        }
        fclose(out);
    }

    // Write the jni file
    if (jniFilename.size() != 0) {
        FILE* out = fopen(jniFilename.c_str(), "w");
        if (out == NULL) {
            fprintf(stderr, "Unable to open file for write: %s\n", jniFilename.c_str());
            return 1;
        }
        errorCount = android::stats_log_api_gen::write_stats_log_jni(
            out, atoms, attributionDecl);
        fclose(out);
    }

    return errorCount;
}

}
}

/**
 * Main.
 */
int
main(int argc, char const*const* argv)
{
    GOOGLE_PROTOBUF_VERIFY_VERSION;

    return android::stats_log_api_gen::run(argc, argv);
}
