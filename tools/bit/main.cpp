/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "aapt.h"
#include "adb.h"
#include "make.h"
#include "print.h"
#include "util.h"

#include <sstream>
#include <string>
#include <vector>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <google/protobuf/stubs/common.h>

using namespace std;

#define NATIVE_TESTS "NATIVE_TESTS"

/**
 * An entry from the command line for something that will be built, installed,
 * and/or tested.
 */
struct Target {
    bool build;
    bool install;
    bool test;
    string pattern;
    string name;
    vector<string> actions;
    Module module;

    int testActionCount;

    int testPassCount;
    int testFailCount;
    int unknownFailureCount; // unknown failure == "Process crashed", etc.
    bool actionsWithNoTests;

    Target(bool b, bool i, bool t, const string& p);
};

Target::Target(bool b, bool i, bool t, const string& p)
    :build(b),
     install(i),
     test(t),
     pattern(p),
     testActionCount(0),
     testPassCount(0),
     testFailCount(0),
     unknownFailureCount(0),
     actionsWithNoTests(false)
{
}

/**
 * Command line options.
 */
struct Options {
    // For help
    bool runHelp;

    // For refreshing module-info.json
    bool runRefresh;

    // For tab completion
    bool runTab;
    string tabPattern;

    // For build/install/test
    bool noRestart;
    bool reboot;
    vector<Target*> targets;

    Options();
    ~Options();
};

Options::Options()
    :runHelp(false),
     runRefresh(false),
     runTab(false),
     noRestart(false),
     reboot(false),
     targets()
{
}

Options::~Options()
{
}

struct InstallApk
{
    TrackedFile file;
    bool alwaysInstall;
    bool installed;

    InstallApk();
    InstallApk(const InstallApk& that);
    InstallApk(const string& filename, bool always);
    ~InstallApk() {};
};

InstallApk::InstallApk()
{
}

InstallApk::InstallApk(const InstallApk& that)
    :file(that.file),
     alwaysInstall(that.alwaysInstall),
     installed(that.installed)
{
}

InstallApk::InstallApk(const string& filename, bool always)
    :file(filename),
     alwaysInstall(always),
     installed(false)
{
}

struct PushedFile
{
    TrackedFile file;
    string dest;

    PushedFile();
    PushedFile(const PushedFile& that);
    PushedFile(const string& filename, const string& dest);
    ~PushedFile() {};
};

PushedFile::PushedFile()
{
}

PushedFile::PushedFile(const PushedFile& that)
    :file(that.file),
     dest(that.dest)
{
}

PushedFile::PushedFile(const string& f, const string& d)
    :file(f),
     dest(d)
{
}

/**
 * Record for an test that is going to be launched.
 */
struct TestAction {
    TestAction();

    // The package name from the apk
    string packageName;

    // The test runner class
    string runner;

    // The test class, or none if all tests should be run
    string className;

    // The original target that requested this action
    Target* target;

    // The number of tests that passed
    int passCount;

    // The number of tests that failed
    int failCount;
};

TestAction::TestAction()
    :passCount(0),
     failCount(0)
{
}

/**
 * Record for an activity that is going to be launched.
 */
struct ActivityAction {
    // The package name from the apk
    string packageName;

    // The test class, or none if all tests should be run
    string className;
};

/**
 * Callback class for the am instrument command.
 */
class TestResults: public InstrumentationCallbacks
{
public:
    virtual void OnTestStatus(TestStatus& status);
    virtual void OnSessionStatus(SessionStatus& status);

    /**
     * Set the TestAction that the tests are for.
     * It will be updated with statistics as the tests run.
     */
    void SetCurrentAction(TestAction* action);

    bool IsSuccess();

    string GetErrorMessage();

private:
    TestAction* m_currentAction;
    SessionStatus m_sessionStatus;
};

void
TestResults::OnTestStatus(TestStatus& status)
{
    bool found;
//    printf("OnTestStatus\n");
//    status.PrintDebugString();
    int32_t resultCode = status.has_results() ? status.result_code() : 0;

    if (!status.has_results()) {
        return;
    }
    const ResultsBundle &results = status.results();

    int32_t currentTestNum = get_bundle_int(results, &found, "current", NULL);
    if (!found) {
        currentTestNum = -1;
    }

    int32_t testCount = get_bundle_int(results, &found, "numtests", NULL);
    if (!found) {
        testCount = -1;
    }

    string className = get_bundle_string(results, &found, "class", NULL);
    if (!found) {
        return;
    }

    string testName = get_bundle_string(results, &found, "test", NULL);
    if (!found) {
        return;
    }

    if (resultCode == 0) {
        // test passed
        m_currentAction->passCount++;
        m_currentAction->target->testPassCount++;
    } else if (resultCode == 1) {
        // test starting
        ostringstream line;
        line << "Running";
        if (currentTestNum > 0) {
            line << ": " << currentTestNum;
            if (testCount > 0) {
                line << " of " << testCount;
            }
        }
        line << ": " << m_currentAction->target->name << ':' << className << "\\#" << testName;
        print_one_line("%s", line.str().c_str());
    } else if ((resultCode == -1) || (resultCode == -2)) {
        // test failed
        // Note -2 means an assertion failure, and -1 means other exceptions.  We just treat them
        // all as "failures".
        m_currentAction->failCount++;
        m_currentAction->target->testFailCount++;
        printf("%s\n%sFailed: %s:%s\\#%s%s\n", g_escapeClearLine, g_escapeRedBold,
                m_currentAction->target->name.c_str(), className.c_str(),
                testName.c_str(), g_escapeEndColor);

        bool stackFound;
        string stack = get_bundle_string(results, &stackFound, "stack", NULL);
        if (status.has_logcat()) {
            const string logcat = status.logcat();
            if (logcat.length() > 0) {
                printf("%s\n", logcat.c_str());
            }
        } else if (stackFound) {
            printf("%s\n", stack.c_str());
        }
    }
}

void
TestResults::OnSessionStatus(SessionStatus& status)
{
    //status.PrintDebugString();
    m_sessionStatus = status;
    if (m_currentAction && !IsSuccess()) {
        m_currentAction->target->unknownFailureCount++;
    }
}

void
TestResults::SetCurrentAction(TestAction* action)
{
    m_currentAction = action;
}

bool
TestResults::IsSuccess()
{
    return m_sessionStatus.result_code() == -1; // Activity.RESULT_OK.
}

string
TestResults::GetErrorMessage()
{
    bool found;
    string shortMsg = get_bundle_string(m_sessionStatus.results(), &found, "shortMsg", NULL);
    if (!found) {
        return IsSuccess() ? "" : "Unknown failure";
    }
    return shortMsg;
}


/**
 * Prints the usage statement / help text.
 */
static void
print_usage(FILE* out) {
    fprintf(out, "usage: bit OPTIONS PATTERN\n");
    fprintf(out, "\n");
    fprintf(out, "  Build, sync and test android code.\n");
    fprintf(out, "\n");
    fprintf(out, "  The -b -i and -t options allow you to specify which phases\n");
    fprintf(out, "  you want to run. If none of those options are given, then\n");
    fprintf(out, "  all phases are run. If any of these options are provided\n");
    fprintf(out, "  then only the listed phases are run.\n");
    fprintf(out, "\n");
    fprintf(out, "  OPTIONS\n");
    fprintf(out, "  -b     Run a build\n");
    fprintf(out, "  -i     Install the targets\n");
    fprintf(out, "  -t     Run the tests\n");
    fprintf(out, "\n");
    fprintf(out, "  -n     Don't reboot or restart\n");
    fprintf(out, "  -r     If the runtime needs to be restarted, do a full reboot\n");
    fprintf(out, "         instead\n");
    fprintf(out, "\n");
    fprintf(out, "  PATTERN\n");
    fprintf(out, "  One or more targets to build, install and test. The target\n");
    fprintf(out, "  names are the names that appear in the LOCAL_MODULE or\n");
    fprintf(out, "  LOCAL_PACKAGE_NAME variables in Android.mk or Android.bp files.\n");
    fprintf(out, "\n");
    fprintf(out, "  Building and installing\n");
    fprintf(out, "  -----------------------\n");
    fprintf(out, "  The modules specified will be built and then installed. If the\n");
    fprintf(out, "  files are on the system partition, they will be synced and the\n");
    fprintf(out, "  attached device rebooted. If they are APKs that aren't on the\n");
    fprintf(out, "  system partition they are installed with adb install.\n");
    fprintf(out, "\n");
    fprintf(out, "  For example:\n");
    fprintf(out, "    bit framework\n");
    fprintf(out, "      Builds framework.jar, syncs the system partition and reboots.\n");
    fprintf(out, "\n");
    fprintf(out, "    bit SystemUI\n");
    fprintf(out, "      Builds SystemUI.apk, syncs the system partition and reboots.\n");
    fprintf(out, "\n");
    fprintf(out, "    bit CtsProtoTestCases\n");
    fprintf(out, "      Builds this CTS apk, adb installs it, but does not run any\n");
    fprintf(out, "      tests.\n");
    fprintf(out, "\n");
    fprintf(out, "  Running Unit Tests\n");
    fprintf(out, "  ------------------\n");
    fprintf(out, "  To run a unit test, list the test class names and optionally the\n");
    fprintf(out, "  test method after the module.\n");
    fprintf(out, "\n");
    fprintf(out, "  For example:\n");
    fprintf(out, "    bit CtsProtoTestCases:*\n");
    fprintf(out, "      Builds this CTS apk, adb installs it, and runs all the tests\n");
    fprintf(out, "      contained in that apk.\n");
    fprintf(out, "\n");
    fprintf(out, "    bit framework CtsProtoTestCases:*\n");
    fprintf(out, "      Builds the framework and the apk, syncs and reboots, then\n");
    fprintf(out, "      adb installs CtsProtoTestCases.apk, and runs all tests \n");
    fprintf(out, "      contained in that apk.\n");
    fprintf(out, "\n");
    fprintf(out, "    bit CtsProtoTestCases:.ProtoOutputStreamBoolTest\n");
    fprintf(out, "    bit CtsProtoTestCases:android.util.proto.cts.ProtoOutputStreamBoolTest\n");
    fprintf(out, "      Builds and installs CtsProtoTestCases.apk, and runs all the\n");
    fprintf(out, "      tests in the ProtoOutputStreamBoolTest class.\n");
    fprintf(out, "\n");
    fprintf(out, "    bit CtsProtoTestCases:.ProtoOutputStreamBoolTest\\#testWrite\n");
    fprintf(out, "      Builds and installs CtsProtoTestCases.apk, and runs the testWrite\n");
    fprintf(out, "      test method on that class.\n");
    fprintf(out, "\n");
    fprintf(out, "    bit CtsProtoTestCases:.ProtoOutputStreamBoolTest\\#testWrite,.ProtoOutputStreamBoolTest\\#testRepeated\n");
    fprintf(out, "      Builds and installs CtsProtoTestCases.apk, and runs the testWrite\n");
    fprintf(out, "      and testRepeated test methods on that class.\n");
    fprintf(out, "\n");
    fprintf(out, "    bit CtsProtoTestCases:android.util.proto.cts.\n");
    fprintf(out, "      Builds and installs CtsProtoTestCases.apk, and runs the tests in the java package\n");
    fprintf(out, "      \"android.util.proto.cts\".\n");
    fprintf(out, "\n");
    fprintf(out, "  Launching an Activity\n");
    fprintf(out, "  ---------------------\n");
    fprintf(out, "  To launch an activity, specify the activity class name after\n");
    fprintf(out, "  the module name.\n");
    fprintf(out, "\n");
    fprintf(out, "  For example:\n");
    fprintf(out, "    bit StatusBarTest:NotificationBuilderTest\n");
    fprintf(out, "    bit StatusBarTest:.NotificationBuilderTest\n");
    fprintf(out, "    bit StatusBarTest:com.android.statusbartest.NotificationBuilderTest\n");
    fprintf(out, "      Builds and installs StatusBarTest.apk, launches the\n");
    fprintf(out, "      com.android.statusbartest/.NotificationBuilderTest activity.\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: bit --refresh\n");
    fprintf(out, "\n");
    fprintf(out, "  Update module-info.json, the cache of make goals that can be built.\n");
    fprintf(out, "\n");
    fprintf(out, "usage: bit --tab ...\n");
    fprintf(out, "\n");
    fprintf(out, "  Lists the targets in a format for tab completion. To get tab\n");
    fprintf(out, "  completion, add this to your bash environment:\n");
    fprintf(out, "\n");
    fprintf(out, "     complete -C \"bit --tab\" bit\n");
    fprintf(out, "\n");
    fprintf(out, "  Sourcing android's build/envsetup.sh will do this for you\n");
    fprintf(out, "  automatically.\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: bit --help\n");
    fprintf(out, "usage: bit -h\n");
    fprintf(out, "\n");
    fprintf(out, "  Print this help message\n");
    fprintf(out, "\n");
}


/**
 * Sets the appropriate flag* variables. If there is a problem with the
 * commandline arguments, prints the help message and exits with an error.
 */
static void
parse_args(Options* options, int argc, const char** argv)
{
    // Help
    if (argc == 2 && (strcmp(argv[1],  "-h") == 0 || strcmp(argv[1], "--help") == 0)) {
        options->runHelp = true;
        return;
    }

    // Refresh
    if (argc == 2 && strcmp(argv[1], "--refresh") == 0) {
        options->runRefresh = true;
        return;
    }

    // Tab
    if (argc >= 4 && strcmp(argv[1], "--tab") == 0) {
        options->runTab = true;
        options->tabPattern = argv[3];
        return;
    }

    // Normal usage
    bool anyPhases = false;
    bool gotPattern = false;
    bool flagBuild = false;
    bool flagInstall = false;
    bool flagTest = false;
    for (int i=1; i < argc; i++) {
        string arg(argv[i]);
        if (arg[0] == '-') {
            for (size_t j=1; j<arg.size(); j++) {
                switch (arg[j]) {
                    case '-':
                        break;
                    case 'b':
                        if (gotPattern) {
                            gotPattern = false;
                            flagInstall = false;
                            flagTest = false;
                        }
                        flagBuild = true;
                        anyPhases = true;
                        break;
                    case 'i':
                        if (gotPattern) {
                            gotPattern = false;
                            flagBuild = false;
                            flagTest = false;
                        }
                        flagInstall = true;
                        anyPhases = true;
                        break;
                    case 't':
                        if (gotPattern) {
                            gotPattern = false;
                            flagBuild = false;
                            flagInstall = false;
                        }
                        flagTest = true;
                        anyPhases = true;
                        break;
                    case 'n':
                        options->noRestart = true;
                        break;
                    case 'r':
                        options->reboot = true;
                        break;
                    default:
                        fprintf(stderr, "Unrecognized option '%c'\n", arg[j]);
                        print_usage(stderr);
                        exit(1);
                        break;
                }
            }
        } else {
            Target* target = new Target(flagBuild || !anyPhases, flagInstall || !anyPhases,
                    flagTest || !anyPhases, arg);
            size_t colonPos = arg.find(':');
            if (colonPos == 0) {
                fprintf(stderr, "Test / activity supplied without a module to build: %s\n",
                        arg.c_str());
                print_usage(stderr);
                delete target;
                exit(1);
            } else if (colonPos == string::npos) {
                target->name = arg;
            } else {
                target->name.assign(arg, 0, colonPos);
                size_t beginPos = colonPos+1;
                size_t commaPos;
                while (true) {
                    commaPos = arg.find(',', beginPos);
                    if (commaPos == string::npos) {
                        if (beginPos != arg.size()) {
                            target->actions.push_back(string(arg, beginPos, commaPos));
                        }
                        break;
                    } else {
                        if (commaPos != beginPos) {
                            target->actions.push_back(string(arg, beginPos, commaPos-beginPos));
                        }
                        beginPos = commaPos+1;
                    }
                }
            }
            options->targets.push_back(target);
            gotPattern = true;
        }
    }
    // If no pattern was supplied, give an error
    if (options->targets.size() == 0) {
        fprintf(stderr, "No PATTERN supplied.\n\n");
        print_usage(stderr);
        exit(1);
    }
}

/**
 * Get an environment variable.
 * Exits with an error if it is unset or the empty string.
 */
static string
get_required_env(const char* name, bool quiet)
{
    const char* value = getenv(name);
    if (value == NULL || value[0] == '\0') {
        if (!quiet) {
            fprintf(stderr, "%s not set. Did you source build/envsetup.sh,"
                    " run lunch and do a build?\n", name);
        }
        exit(1);
    }
    return string(value);
}

/**
 * Get the out directory.
 *
 * This duplicates the logic in build/make/core/envsetup.mk (which hasn't changed since 2011)
 * so that we don't have to wait for get_build_var make invocation.
 */
string
get_out_dir()
{
    const char* out_dir = getenv("OUT_DIR");
    if (out_dir == NULL || out_dir[0] == '\0') {
        const char* common_base = getenv("OUT_DIR_COMMON_BASE");
        if (common_base == NULL || common_base[0] == '\0') {
            // We don't prefix with buildTop because we cd there and it
            // makes all the filenames long when being pretty printed.
            return "out";
        } else {
            char pwd[PATH_MAX];
            if (getcwd(pwd, PATH_MAX) == NULL) {
                fprintf(stderr, "Your pwd is too long.\n");
                exit(1);
            }
            const char* slash = strrchr(pwd, '/');
            if (slash == NULL) {
                slash = "";
            }
            string result(common_base);
            result += slash;
            return result;
        }
    }
    return string(out_dir);
}

/**
 * Check that a system property on the device matches the expected value.
 * Exits with an error if they don't.
 */
static void
check_device_property(const string& property, const string& expected)
{
    int err;
    string deviceValue = get_system_property(property, &err);
    check_error(err);
    if (deviceValue != expected) {
        print_error("There is a mismatch between the build you just did and the device you");
        print_error("are trying to sync it to in the %s system property", property.c_str());
        print_error("   build:  %s", expected.c_str());
        print_error("   device: %s", deviceValue.c_str());
        exit(1);
    }
}

static void
chdir_or_exit(const char *path) {
    // TODO: print_command("cd", path);
    if (0 != chdir(path)) {
        print_error("Error: Could not chdir: %s", path);
        exit(1);
    }
}

/**
 * Run the build, install, and test actions.
 */
bool
run_phases(vector<Target*> targets, const Options& options)
{
    int err = 0;

    //
    // Initialization
    //

    print_status("Initializing");

    const string buildTop = get_required_env("ANDROID_BUILD_TOP", false);
    const string buildProduct = get_required_env("TARGET_PRODUCT", false);
    const string buildVariant = get_required_env("TARGET_BUILD_VARIANT", false);
    const string buildType = get_required_env("TARGET_BUILD_TYPE", false);
    const string buildOut = get_out_dir();
    chdir_or_exit(buildTop.c_str());

    BuildVars buildVars(buildOut, buildProduct, buildVariant, buildType);

    const string buildDevice = buildVars.GetBuildVar("TARGET_DEVICE", false);
    const string buildId = buildVars.GetBuildVar("BUILD_ID", false);

    // Get the modules for the targets
    map<string,Module> modules;
    read_modules(buildOut, buildDevice, &modules, false);
    for (size_t i=0; i<targets.size(); i++) {
        Target* target = targets[i];
        map<string,Module>::iterator mod = modules.find(target->name);
        if (mod != modules.end()) {
            target->module = mod->second;
        } else {
            print_error("Error: Could not find module: %s", target->name.c_str());
            fprintf(stderr, "Try running %sbit --refresh%s if you recently added %s%s%s.\n",
                    g_escapeBold, g_escapeEndColor,
                    g_escapeBold, target->name.c_str(), g_escapeEndColor);
            err = 1;
        }
    }
    if (err != 0) {
        exit(1);
    }

    // Choose the goals
    vector<string> goals;
    for (size_t i=0; i<targets.size(); i++) {
        Target* target = targets[i];
        if (target->build) {
            goals.push_back(target->name);
        }
    }

    // Figure out whether we need to sync the system and which apks to install
    string deviceTargetPath = buildOut + "/target/product/" + buildDevice;
    string systemPath = deviceTargetPath + "/system/";
    string dataPath = deviceTargetPath + "/data/";
    bool syncSystem = false;
    bool alwaysSyncSystem = false;
    vector<string> systemFiles;
    vector<InstallApk> installApks;
    vector<PushedFile> pushedFiles;
    for (size_t i=0; i<targets.size(); i++) {
        Target* target = targets[i];
        if (target->install) {
            for (size_t j=0; j<target->module.installed.size(); j++) {
                const string& file = target->module.installed[j];
                // System partition
                if (starts_with(file, systemPath)) {
                    syncSystem = true;
                    systemFiles.push_back(file);
                    if (!target->build) {
                        // If a system partition target didn't get built then
                        // it won't change we will always need to do adb sync
                        alwaysSyncSystem = true;
                    }
                    continue;
                }
                // Apk in the data partition
                if (starts_with(file, dataPath) && ends_with(file, ".apk")) {
                    // Always install it if we didn't build it because otherwise
                    // it will never have changed.
                    installApks.push_back(InstallApk(file, !target->build));
                    continue;
                }
                // If it's a native test module, push it.
                if (target->module.HasClass(NATIVE_TESTS) && starts_with(file, dataPath)) {
                    string installedPath(file.c_str() + deviceTargetPath.length());
                    pushedFiles.push_back(PushedFile(file, installedPath));
                }
            }
        }
    }
    map<string,FileInfo> systemFilesBefore;
    if (syncSystem && !alwaysSyncSystem) {
        get_directory_contents(systemPath, &systemFilesBefore);
    }

    if (systemFiles.size() > 0){
        print_info("System files:");
        for (size_t i=0; i<systemFiles.size(); i++) {
            printf("  %s\n", systemFiles[i].c_str());
        }
    }
    if (pushedFiles.size() > 0){
        print_info("Files to push:");
        for (size_t i=0; i<pushedFiles.size(); i++) {
            printf("  %s\n", pushedFiles[i].file.filename.c_str());
            printf("    --> %s\n", pushedFiles[i].dest.c_str());
        }
    }
    if (installApks.size() > 0){
        print_info("APKs to install:");
        for (size_t i=0; i<installApks.size(); i++) {
            printf("  %s\n", installApks[i].file.filename.c_str());
        }
    }

    //
    // Build
    //

    // Run the build
    if (goals.size() > 0) {
        print_status("Building");
        err = build_goals(goals);
        check_error(err);
    }

    //
    // Install
    //

    // Sync the system partition and reboot
    bool skipSync = false;
    if (syncSystem) {
        print_status("Syncing /system");

        if (!alwaysSyncSystem) {
            // If nothing changed and we weren't forced to sync, skip the reboot for speed.
            map<string,FileInfo> systemFilesAfter;
            get_directory_contents(systemPath, &systemFilesAfter);
            skipSync = !directory_contents_differ(systemFilesBefore, systemFilesAfter);
        }
        if (skipSync) {
            printf("Skipping sync because no files changed.\n");
        } else {
            // Do some sanity checks
            check_device_property("ro.build.product", buildProduct);
            check_device_property("ro.build.type", buildVariant);
            check_device_property("ro.build.id", buildId);

            // Stop & Sync
            if (!options.noRestart) {
                err = run_adb("shell", "stop", NULL);
                check_error(err);
            }
            err = run_adb("remount", NULL);
            check_error(err);
            err = run_adb("sync", "system", NULL);
            check_error(err);

            if (!options.noRestart) {
                if (options.reboot) {
                    print_status("Rebooting");

                    err = run_adb("reboot", NULL);
                    check_error(err);
                    err = run_adb("wait-for-device", NULL);
                    check_error(err);
                } else {
                    print_status("Restarting the runtime");

                    err = run_adb("shell", "setprop", "sys.boot_completed", "0", NULL);
                    check_error(err);
                    err = run_adb("shell", "start", NULL);
                    check_error(err);
                }

                while (true) {
                    string completed = get_system_property("sys.boot_completed", &err);
                    check_error(err);
                    if (completed == "1") {
                        break;
                    }
                    sleep(2);
                }
                sleep(1);
                err = run_adb("shell", "wm", "dismiss-keyguard", NULL);
                check_error(err);
            }
        }
    }

    // Push files
    if (pushedFiles.size() > 0) {
        print_status("Pushing files");
        for (size_t i=0; i<pushedFiles.size(); i++) {
            const PushedFile& pushed = pushedFiles[i];
            string dir = dirname(pushed.dest);
            if (dir.length() == 0 || dir == "/") {
                // This isn't really a file inside the data directory. Just skip it.
                continue;
            }
            // TODO: if (!apk.file.fileInfo.exists || apk.file.HasChanged())
            err = run_adb("shell", "mkdir", "-p", dir.c_str(), NULL);
            check_error(err);
            err = run_adb("push", pushed.file.filename.c_str(), pushed.dest.c_str(), NULL);
            check_error(err);
            // pushed.installed = true;
        }
    }

    // Install APKs
    if (installApks.size() > 0) {
        print_status("Installing APKs");
        for (size_t i=0; i<installApks.size(); i++) {
            InstallApk& apk = installApks[i];
            if (!apk.file.fileInfo.exists || apk.file.HasChanged()) {
                // It didn't exist before or it changed, so int needs install
                err = run_adb("install", "-r", "-g", apk.file.filename.c_str(), NULL);
                check_error(err);
                apk.installed = true;
            } else {
                printf("APK didn't change. Skipping install of %s\n", apk.file.filename.c_str());
            }
        }
    }

    //
    // Actions
    //

    // Whether there have been any tests run, so we can print a summary.
    bool testsRun = false;

    // Run the native tests.
    // TODO: We don't have a good way of running these and capturing the output of
    // them live.  It'll take some work.  On the other hand, if they're gtest tests,
    // the output of gtest is not completely insane like the text output of the
    // instrumentation tests.  So for now, we'll just live with that.
    for (size_t i=0; i<targets.size(); i++) {
        Target* target = targets[i];
        if (target->test && target->module.HasClass(NATIVE_TESTS)) {
            // We don't have a clear signal from the build system which of the installed
            // files is actually the test, so we guess by looking for one with the same
            // leaf name as the module that is executable.
            for (size_t j=0; j<target->module.installed.size(); j++) {
                string filename = target->module.installed[j];
                if (!starts_with(filename, dataPath)) {
                    // Native tests go into the data directory.
                    continue;
                }
                if (leafname(filename) != target->module.name) {
                    // This isn't the test executable.
                    continue;
                }
                if (!is_executable(filename)) {
                    continue;
                }
                string installedPath(filename.c_str() + deviceTargetPath.length());
                printf("the magic one is: %s\n", filename.c_str());
                printf("  and it's installed at: %s\n", installedPath.c_str());

                // Convert bit-style actions to gtest test filter arguments
                if (target->actions.size() > 0) {
                    testsRun = true;
                    target->testActionCount++;
                    bool runAll = false;
                    string filterArg("--gtest_filter=");
                    for (size_t k=0; k<target->actions.size(); k++) {
                        string actionString = target->actions[k];
                        if (actionString == "*") {
                            runAll = true;
                        } else {
                            filterArg += actionString;
                            if (k != target->actions.size()-1) {
                                // We would otherwise have to worry about this condition
                                // being true, and appending an extra ':', but we know that
                                // if the extra action is "*", then we'll just run all and
                                // won't use filterArg anyway, so just keep this condition
                                // simple.
                                filterArg += ':';
                            }
                        }
                    }
                    if (runAll) {
                        err = run_adb("shell", installedPath.c_str(), NULL);
                    } else {
                        err = run_adb("shell", installedPath.c_str(), filterArg.c_str(), NULL);
                    }
                    if (err == 0) {
                        target->testPassCount++;
                    } else {
                        target->testFailCount++;
                    }
                }
            }
        }
    }

    // Inspect the apks, and figure out what is an activity and what needs a test runner
    bool printedInspecting = false;
    vector<TestAction> testActions;
    vector<ActivityAction> activityActions;
    for (size_t i=0; i<targets.size(); i++) {
        Target* target = targets[i];
        if (target->test) {
            for (size_t j=0; j<target->module.installed.size(); j++) {
                string filename = target->module.installed[j];

                // Apk in the data partition
                if (!starts_with(filename, dataPath) || !ends_with(filename, ".apk")) {
                    continue;
                }

                if (!printedInspecting) {
                    printedInspecting = true;
                    print_status("Inspecting APKs");
                }

                Apk apk;
                err = inspect_apk(&apk, filename);
                check_error(err);

                for (size_t k=0; k<target->actions.size(); k++) {
                    string actionString = target->actions[k];
                    if (actionString == "*") {
                        if (apk.runner.length() == 0) {
                            print_error("Error: Test requested for apk that doesn't"
                                    " have an <instrumentation> tag: %s\n",
                                    target->module.name.c_str());
                            exit(1);
                        }
                        TestAction action;
                        action.packageName = apk.package;
                        action.runner = apk.runner;
                        action.target = target;
                        testActions.push_back(action);
                        target->testActionCount++;
                    } else if (apk.HasActivity(actionString)) {
                        ActivityAction action;
                        action.packageName = apk.package;
                        action.className = full_class_name(apk.package, actionString);
                        activityActions.push_back(action);
                    } else {
                        if (apk.runner.length() == 0) {
                            print_error("Error: Test requested for apk that doesn't"
                                    " have an <instrumentation> tag: %s\n",
                                    target->module.name.c_str());
                            exit(1);
                        }
                        TestAction action;
                        action.packageName = apk.package;
                        action.runner = apk.runner;
                        action.className = full_class_name(apk.package, actionString);
                        action.target = target;
                        testActions.push_back(action);
                        target->testActionCount++;
                    }
                }
            }
        }
    }

    // Run the instrumentation tests
    TestResults testResults;
    if (testActions.size() > 0) {
        print_status("Running tests");
        testsRun = true;
        for (size_t i=0; i<testActions.size(); i++) {
            TestAction& action = testActions[i];
            testResults.SetCurrentAction(&action);
            err = run_instrumentation_test(action.packageName, action.runner, action.className,
                    &testResults);
            check_error(err);
            if (action.passCount == 0 && action.failCount == 0) {
                action.target->actionsWithNoTests = true;
            }
            int total = action.passCount + action.failCount;
            printf("%sRan %d test%s for %s. ", g_escapeClearLine,
                    total, total > 1 ? "s" : "", action.target->name.c_str());
            if (action.passCount == 0 && action.failCount == 0) {
                printf("%s%d passed, %d failed%s\n", g_escapeYellowBold, action.passCount,
                        action.failCount, g_escapeEndColor);
            } else if (action.failCount >  0) {
                printf("%d passed, %s%d failed%s\n", action.passCount, g_escapeRedBold,
                        action.failCount, g_escapeEndColor);
            } else {
                printf("%s%d passed%s, %d failed\n", g_escapeGreenBold, action.passCount,
                        g_escapeEndColor, action.failCount);
            }
            if (!testResults.IsSuccess()) {
                printf("\n%sTest didn't finish successfully: %s%s\n", g_escapeRedBold,
                        testResults.GetErrorMessage().c_str(), g_escapeEndColor);
            }
        }
    }

    // Launch the activity
    if (activityActions.size() > 0) {
        print_status("Starting activity");

        if (activityActions.size() > 1) {
            print_warning("Multiple activities specified.  Will only start the first one:");
            for (size_t i=0; i<activityActions.size(); i++) {
                ActivityAction& action = activityActions[i];
                print_warning("   %s",
                        pretty_component_name(action.packageName, action.className).c_str());
            }
        }

        const ActivityAction& action = activityActions[0];
        string componentName = action.packageName + "/" + action.className;
        err = run_adb("shell", "am", "start", componentName.c_str(), NULL);
        check_error(err);
    }

    //
    // Print summary
    //

    printf("\n%s--------------------------------------------%s\n", g_escapeBold, g_escapeEndColor);

    // Build
    if (goals.size() > 0) {
        printf("%sBuilt:%s\n", g_escapeBold, g_escapeEndColor);
        for (size_t i=0; i<goals.size(); i++) {
            printf("   %s\n", goals[i].c_str());
        }
    }

    // Install
    if (syncSystem) {
        if (skipSync) {
            printf("%sSkipped syncing /system partition%s\n", g_escapeBold, g_escapeEndColor);
        } else {
            printf("%sSynced /system partition%s\n", g_escapeBold, g_escapeEndColor);
        }
    }
    if (installApks.size() > 0) {
        bool printedTitle = false;
        for (size_t i=0; i<installApks.size(); i++) {
            const InstallApk& apk = installApks[i];
            if (apk.installed) {
                if (!printedTitle) {
                    printf("%sInstalled:%s\n", g_escapeBold, g_escapeEndColor);
                    printedTitle = true;
                }
                printf("   %s\n", apk.file.filename.c_str());
            }
        }
        printedTitle = false;
        for (size_t i=0; i<installApks.size(); i++) {
            const InstallApk& apk = installApks[i];
            if (!apk.installed) {
                if (!printedTitle) {
                    printf("%sSkipped install:%s\n", g_escapeBold, g_escapeEndColor);
                    printedTitle = true;
                }
                printf("   %s\n", apk.file.filename.c_str());
            }
        }
    }

    // Tests
    bool hasErrors = false;
    if (testsRun) {
        printf("%sRan tests:%s\n", g_escapeBold, g_escapeEndColor);
        size_t maxNameLength = 0;
        for (size_t i=0; i<targets.size(); i++) {
            Target* target = targets[i];
            if (target->test) {
                size_t len = target->name.length();
                if (len > maxNameLength) {
                    maxNameLength = len;
                }
            }
        }
        string padding(maxNameLength, ' ');
        for (size_t i=0; i<targets.size(); i++) {
            Target* target = targets[i];
            if (target->testActionCount > 0) {
                printf("   %s%s", target->name.c_str(), padding.c_str() + target->name.length());
                if (target->unknownFailureCount > 0) {
                    printf("     %sUnknown failure, see above message.%s\n",
                            g_escapeRedBold, g_escapeEndColor);
                    hasErrors = true;
                } else if (target->actionsWithNoTests) {
                    printf("     %s%d passed, %d failed%s\n", g_escapeYellowBold,
                            target->testPassCount, target->testFailCount, g_escapeEndColor);
                    hasErrors = true;
                } else if (target->testFailCount > 0) {
                    printf("     %d passed, %s%d failed%s\n", target->testPassCount,
                            g_escapeRedBold, target->testFailCount, g_escapeEndColor);
                    hasErrors = true;
                } else {
                    printf("     %s%d passed%s, %d failed\n", g_escapeGreenBold,
                            target->testPassCount, g_escapeEndColor, target->testFailCount);
                }
            }
        }
    }
    if (activityActions.size() > 1) {
        printf("%sStarted Activity:%s\n", g_escapeBold, g_escapeEndColor);
        const ActivityAction& action = activityActions[0];
        printf("   %s\n", pretty_component_name(action.packageName, action.className).c_str());
    }

    printf("%s--------------------------------------------%s\n", g_escapeBold, g_escapeEndColor);
    return !hasErrors;
}

/**
 * Refresh module-info.
 */
void
run_refresh()
{
    int err;

    print_status("Initializing");
    const string buildTop = get_required_env("ANDROID_BUILD_TOP", false);
    const string buildProduct = get_required_env("TARGET_PRODUCT", false);
    const string buildVariant = get_required_env("TARGET_BUILD_VARIANT", false);
    const string buildType = get_required_env("TARGET_BUILD_TYPE", false);
    const string buildOut = get_out_dir();
    chdir_or_exit(buildTop.c_str());

    BuildVars buildVars(buildOut, buildProduct, buildVariant, buildType);

    string buildDevice = buildVars.GetBuildVar("TARGET_DEVICE", false);

    vector<string> goals;
    goals.push_back(buildOut + "/target/product/" + buildDevice + "/module-info.json");

    print_status("Refreshing module-info.json");
    err = build_goals(goals);
    check_error(err);
}

/**
 * Implement tab completion of the target names from the all modules file.
 */
void
run_tab_completion(const string& word)
{
    const string buildTop = get_required_env("ANDROID_BUILD_TOP", false);
    const string buildProduct = get_required_env("TARGET_PRODUCT", false);
    const string buildVariant = get_required_env("TARGET_BUILD_VARIANT", false);
    const string buildType = get_required_env("TARGET_BUILD_TYPE", false);
    const string buildOut = get_out_dir();
    chdir_or_exit(buildTop.c_str());

    BuildVars buildVars(buildOut, buildProduct, buildVariant, buildType);

    string buildDevice = buildVars.GetBuildVar("TARGET_DEVICE", false);

    map<string,Module> modules;
    read_modules(buildOut, buildDevice, &modules, true);

    for (map<string,Module>::const_iterator it = modules.begin(); it != modules.end(); it++) {
        if (starts_with(it->first, word)) {
            printf("%s\n", it->first.c_str());
        }
    }
}

/**
 * Main entry point.
 */
int
main(int argc, const char** argv)
{
    GOOGLE_PROTOBUF_VERIFY_VERSION;
    init_print();

    Options options;
    parse_args(&options, argc, argv);

    if (options.runHelp) {
        // Help
        print_usage(stdout);
        exit(0);
    } else if (options.runRefresh) {
        run_refresh();
        exit(0);
    } else if (options.runTab) {
        run_tab_completion(options.tabPattern);
        exit(0);
    } else {
        // Normal run
        exit(run_phases(options.targets, options) ? 0 : 1);
    }

    return 0;
}

