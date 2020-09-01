
#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <map>
#include <set>
#include <vector>

#include "Collation.h"
#include "frameworks/base/cmds/statsd/src/atoms.pb.h"
#include "java_writer.h"
#include "java_writer_q.h"
#include "native_writer.h"
#include "utils.h"

using namespace google::protobuf;
using namespace std;

namespace android {
namespace stats_log_api_gen {

using android::os::statsd::Atom;

static void print_usage() {
    fprintf(stderr, "usage: stats-log-api-gen OPTIONS\n");
    fprintf(stderr, "\n");
    fprintf(stderr, "OPTIONS\n");
    fprintf(stderr, "  --cpp FILENAME       the header file to output for write helpers\n");
    fprintf(stderr, "  --header FILENAME    the cpp file to output for write helpers\n");
    fprintf(stderr, "  --help               this message\n");
    fprintf(stderr, "  --java FILENAME      the java file to output\n");
    fprintf(stderr, "  --module NAME        optional, module name to generate outputs for\n");
    fprintf(stderr,
            "  --namespace COMMA,SEP,NAMESPACE   required for cpp/header with "
            "module\n");
    fprintf(stderr,
            "                                    comma separated namespace of "
            "the files\n");
    fprintf(stderr,
            "  --importHeader NAME  required for cpp/jni to say which header to "
            "import "
            "for write helpers\n");
    fprintf(stderr, "  --javaPackage PACKAGE             the package for the java file.\n");
    fprintf(stderr, "                                    required for java with module\n");
    fprintf(stderr, "  --javaClass CLASS    the class name of the java class.\n");
    fprintf(stderr, "                       Optional for Java with module.\n");
    fprintf(stderr, "                       Default is \"StatsLogInternal\"\n");
    fprintf(stderr, "  --supportQ           Include runtime support for Android Q.\n");
    fprintf(stderr,
            "  --worksource         Include support for logging WorkSource "
            "objects.\n");
    fprintf(stderr,
            "  --compileQ           Include compile-time support for Android Q "
            "(Java only).\n");
}

/**
 * Do the argument parsing and execute the tasks.
 */
static int run(int argc, char const* const* argv) {
    string cppFilename;
    string headerFilename;
    string javaFilename;
    string javaPackage;
    string javaClass;

    string moduleName = DEFAULT_MODULE_NAME;
    string cppNamespace = DEFAULT_CPP_NAMESPACE;
    string cppHeaderImport = DEFAULT_CPP_HEADER_IMPORT;
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
        } else if (0 == strcmp("--supportQ", argv[index])) {
            supportQ = true;
        } else if (0 == strcmp("--worksource", argv[index])) {
            supportWorkSource = true;
        } else if (0 == strcmp("--compileQ", argv[index])) {
            compileQ = true;
        }

        index++;
    }

    if (cppFilename.size() == 0 && headerFilename.size() == 0 && javaFilename.size() == 0) {
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
    int errorCount = collate_atoms(Atom::descriptor(), moduleName, &atoms);
    if (errorCount != 0) {
        return 1;
    }

    AtomDecl attributionDecl;
    vector<java_type_t> attributionSignature;
    collate_atom(android::os::statsd::AttributionNode::descriptor(), &attributionDecl,
                 &attributionSignature);

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
        // If this is for a specific module, the header file to import must also be
        // provided.
        if (moduleName != DEFAULT_MODULE_NAME && cppHeaderImport == DEFAULT_CPP_HEADER_IMPORT) {
            fprintf(stderr, "Must supply --headerImport if supplying a specific module\n");
            return 1;
        }
        errorCount = android::stats_log_api_gen::write_stats_log_cpp(
                out, atoms, attributionDecl, cppNamespace, cppHeaderImport, supportQ);
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
        errorCount = android::stats_log_api_gen::write_stats_log_header(out, atoms, attributionDecl,
                                                                        cppNamespace);
        fclose(out);
    }

    // Write the .java file
    if (javaFilename.size() != 0) {
        if (javaClass.size() == 0) {
            fprintf(stderr, "Must supply --javaClass if supplying a Java filename");
            return 1;
        }

        if (javaPackage.size() == 0) {
            fprintf(stderr, "Must supply --javaPackage if supplying a Java filename");
            return 1;
        }

        if (moduleName.size() == 0) {
            fprintf(stderr, "Must supply --module if supplying a Java filename");
            return 1;
        }

        FILE* out = fopen(javaFilename.c_str(), "w");
        if (out == NULL) {
            fprintf(stderr, "Unable to open file for write: %s\n", javaFilename.c_str());
            return 1;
        }

        if (compileQ) {
            errorCount = android::stats_log_api_gen::write_stats_log_java_q_for_module(
                    out, atoms, attributionDecl, javaClass, javaPackage, supportWorkSource);
        } else {
            errorCount = android::stats_log_api_gen::write_stats_log_java(
                    out, atoms, attributionDecl, javaClass, javaPackage, supportQ,
                    supportWorkSource);
        }

        fclose(out);
    }

    return errorCount;
}

}  // namespace stats_log_api_gen
}  // namespace android

/**
 * Main.
 */
int main(int argc, char const* const* argv) {
    GOOGLE_PROTOBUF_VERIFY_VERSION;

    return android::stats_log_api_gen::run(argc, argv);
}
