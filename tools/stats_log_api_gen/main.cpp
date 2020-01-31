

#include "Collation.h"
#include "atoms_info_writer.h"
#if !defined(STATS_SCHEMA_LEGACY)
#include "java_writer.h"
#endif
#include "java_writer_q.h"
#include "native_writer.h"
#include "utils.h"

#include "frameworks/base/cmds/statsd/src/atoms.pb.h"

#include <map>
#include <set>
#include <vector>

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

using namespace google::protobuf;
using namespace std;

namespace android {
namespace stats_log_api_gen {

using android::os::statsd::Atom;

// Hide the JNI write helpers that are not used in the new schema.
// TODO(b/145100015): Remove this and other JNI related functionality once StatsEvent migration is
// complete.
#if defined(STATS_SCHEMA_LEGACY)
// JNI helpers.
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
write_stats_log_jni_method(FILE* out, const string& java_method_name, const string& cpp_method_name,
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
#endif // JNI helpers.

static int
#if defined(STATS_SCHEMA_LEGACY)
write_stats_log_jni(FILE* out, const Atoms& atoms, const AtomDecl &attributionDecl)
#else
// Write empty JNI file that doesn't contain any JNI methods.
// TODO(b/145100015): remove this function and all JNI autogen code once StatsEvent migration is
// complete.
write_stats_log_jni(FILE* out)
#endif
{
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");

#if defined(STATS_SCHEMA_LEGACY)
    fprintf(out, "#include <statslog.h>\n");
    fprintf(out, "\n");
    fprintf(out, "#include <nativehelper/JNIHelp.h>\n");
    fprintf(out, "#include <nativehelper/ScopedUtfChars.h>\n");
    fprintf(out, "#include <utils/Vector.h>\n");
#endif
    fprintf(out, "#include \"core_jni_helpers.h\"\n");
    fprintf(out, "#include \"jni.h\"\n");
    fprintf(out, "\n");
#if defined(STATS_SCHEMA_LEGACY)
    fprintf(out, "#define UNUSED  __attribute__((__unused__))\n");
    fprintf(out, "\n");
#endif

    fprintf(out, "namespace android {\n");
    fprintf(out, "\n");

#if defined(STATS_SCHEMA_LEGACY)
    write_stats_log_jni_method(out, "write", "stats_write", atoms.signatures_to_modules, attributionDecl);
    write_stats_log_jni_method(out, "write_non_chained", "stats_write_non_chained",
            atoms.non_chained_signatures_to_modules, attributionDecl);
#endif

    // Print registration function table
    fprintf(out, "/*\n");
    fprintf(out, " * JNI registration.\n");
    fprintf(out, " */\n");
    fprintf(out, "static const JNINativeMethod gRegisterMethods[] = {\n");
#if defined(STATS_SCHEMA_LEGACY)
    write_jni_registration(out, "write", atoms.signatures_to_modules, attributionDecl);
    write_jni_registration(out, "write_non_chained", atoms.non_chained_signatures_to_modules,
            attributionDecl);
#endif
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
    fprintf(stderr, "  --cpp FILENAME       the header file to output for write helpers\n");
    fprintf(stderr, "  --header FILENAME    the cpp file to output for write helpers\n");
    fprintf(stderr,
            "  --atomsInfoCpp FILENAME       the header file to output for statsd metadata\n");
    fprintf(stderr, "  --atomsInfoHeader FILENAME    the cpp file to output for statsd metadata\n");
    fprintf(stderr, "  --help               this message\n");
    fprintf(stderr, "  --java FILENAME      the java file to output\n");
    fprintf(stderr, "  --jni FILENAME       the jni file to output\n");
    fprintf(stderr, "  --module NAME        optional, module name to generate outputs for\n");
    fprintf(stderr, "  --namespace COMMA,SEP,NAMESPACE   required for cpp/header with module\n");
    fprintf(stderr, "                                    comma separated namespace of the files\n");
    fprintf(stderr,"  --importHeader NAME  required for cpp/jni to say which header to import "
            "for write helpers\n");
    fprintf(stderr,"  --atomsInfoImportHeader NAME  required for cpp to say which header to import "
            "for statsd metadata\n");
    fprintf(stderr, "  --javaPackage PACKAGE             the package for the java file.\n");
    fprintf(stderr, "                                    required for java with module\n");
    fprintf(stderr, "  --javaClass CLASS    the class name of the java class.\n");
    fprintf(stderr, "                       Optional for Java with module.\n");
    fprintf(stderr, "                       Default is \"StatsLogInternal\"\n");
    fprintf(stderr, "  --supportQ           Include runtime support for Android Q.\n");
    fprintf(stderr, "  --worksource         Include support for logging WorkSource objects.\n");
    fprintf(stderr, "  --compileQ           Include compile-time support for Android Q "
            "(Java only).\n");
}

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
    string atomsInfoCppFilename;
    string atomsInfoHeaderFilename;

    string moduleName = DEFAULT_MODULE_NAME;
    string cppNamespace = DEFAULT_CPP_NAMESPACE;
    string cppHeaderImport = DEFAULT_CPP_HEADER_IMPORT;
    string atomsInfoCppHeaderImport = DEFAULT_ATOMS_INFO_CPP_HEADER_IMPORT;
    string javaPackage = DEFAULT_JAVA_PACKAGE;
    string javaClass = DEFAULT_JAVA_CLASS;
    bool supportQ = false;
    bool supportWorkSource = false;
    bool compileQ = false;

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
        } else if (0 == strcmp("--atomsInfoHeader", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            atomsInfoHeaderFilename = argv[index];
        } else if (0 == strcmp("--atomsInfoCpp", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            atomsInfoCppFilename = argv[index];
        } else if (0 == strcmp("--atomsInfoImportHeader", argv[index])) {
            index++;
            if (index >= argc) {
                print_usage();
                return 1;
            }
            atomsInfoCppHeaderImport = argv[index];
        } else if (0 == strcmp("--supportQ", argv[index])) {
            supportQ = true;
        } else if (0 == strcmp("--worksource", argv[index])) {
            supportWorkSource = true;
        } else if (0 == strcmp("--compileQ", argv[index])) {
            compileQ = true;
        }

        index++;
    }

    if (cppFilename.size() == 0
            && headerFilename.size() == 0
            && javaFilename.size() == 0
            && jniFilename.size() == 0
            && atomsInfoHeaderFilename.size() == 0
            && atomsInfoCppFilename.size() == 0) {
        print_usage();
        return 1;
    }

    if (DEFAULT_MODULE_NAME == moduleName && (supportQ || compileQ)) {
        // Support for Q schema is not needed for default module.
        fprintf(stderr, "%s cannot support Q schema\n", moduleName.c_str());
        return 1;
    }

    if (supportQ && compileQ) {
        // Runtime Q support is redundant if compile-time Q support is required.
        fprintf(stderr, "Cannot specify compileQ and supportQ simultaneously.\n");
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

    // Write the atoms info .cpp file
    if (atomsInfoCppFilename.size() != 0) {
        FILE* out = fopen(atomsInfoCppFilename.c_str(), "w");
        if (out == NULL) {
            fprintf(stderr, "Unable to open file for write: %s\n", atomsInfoCppFilename.c_str());
            return 1;
        }
        errorCount = android::stats_log_api_gen::write_atoms_info_cpp(
            out, atoms, cppNamespace, atomsInfoCppHeaderImport, cppHeaderImport);
        fclose(out);
    }

    // Write the atoms info .h file
    if (atomsInfoHeaderFilename.size() != 0) {
        FILE* out = fopen(atomsInfoHeaderFilename.c_str(), "w");
        if (out == NULL) {
            fprintf(stderr, "Unable to open file for write: %s\n", atomsInfoHeaderFilename.c_str());
            return 1;
        }
        errorCount = android::stats_log_api_gen::write_atoms_info_header(out, atoms, cppNamespace);
        fclose(out);
    }


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
            out, atoms, attributionDecl, moduleName, cppNamespace, cppHeaderImport, supportQ);
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

#if defined(STATS_SCHEMA_LEGACY)
        if (moduleName == DEFAULT_MODULE_NAME) {
            errorCount = android::stats_log_api_gen::write_stats_log_java_q(
                    out, atoms, attributionDecl, supportWorkSource);
        } else {
            errorCount = android::stats_log_api_gen::write_stats_log_java_q_for_module(
                    out, atoms, attributionDecl, moduleName, javaClass, javaPackage,
                    supportWorkSource);

        }
#else
        if (moduleName == DEFAULT_MODULE_NAME) {
            javaClass = "StatsLogInternal";
            javaPackage = "android.util";
        }
        if (compileQ) {
            errorCount = android::stats_log_api_gen::write_stats_log_java_q_for_module(
                    out, atoms, attributionDecl, moduleName, javaClass, javaPackage,
                    supportWorkSource);
        } else {
            errorCount = android::stats_log_api_gen::write_stats_log_java(
                    out, atoms, attributionDecl, moduleName, javaClass, javaPackage, supportQ,
                    supportWorkSource);
        }
#endif

        fclose(out);
    }

    // Write the jni file
    if (jniFilename.size() != 0) {
        FILE* out = fopen(jniFilename.c_str(), "w");
        if (out == NULL) {
            fprintf(stderr, "Unable to open file for write: %s\n", jniFilename.c_str());
            return 1;
        }

#if defined(STATS_SCHEMA_LEGACY)
        errorCount = android::stats_log_api_gen::write_stats_log_jni(
            out, atoms, attributionDecl);
#else
        errorCount = android::stats_log_api_gen::write_stats_log_jni(out);
#endif

        fclose(out);
    }

    return errorCount;
}

}  // namespace stats_log_api_gen
}  // namespace android

/**
 * Main.
 */
int
main(int argc, char const*const* argv)
{
    GOOGLE_PROTOBUF_VERIFY_VERSION;

    return android::stats_log_api_gen::run(argc, argv);
}
