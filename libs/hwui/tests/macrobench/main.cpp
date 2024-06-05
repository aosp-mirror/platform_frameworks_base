/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include <android-base/parsebool.h>
#include <benchmark/benchmark.h>
#include <errno.h>
#include <fcntl.h>
#include <fnmatch.h>
#include <getopt.h>
#include <pthread.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <regex>
#include <string>
#include <unordered_map>
#include <vector>

#include "HardwareBitmapUploader.h"
#include "Properties.h"
#include "hwui/Typeface.h"
#include "renderthread/RenderProxy.h"
#include "tests/common/LeakChecker.h"
#include "tests/common/TestScene.h"

using namespace android;
using namespace android::base;
using namespace android::uirenderer;
using namespace android::uirenderer::test;

static std::vector<TestScene::Info> gRunTests;
static TestScene::Options gOpts;
static bool gRunLeakCheck = true;
std::unique_ptr<benchmark::BenchmarkReporter> gBenchmarkReporter;

void run(const TestScene::Info& info, const TestScene::Options& opts,
         benchmark::BenchmarkReporter* reporter);

static void printHelp() {
    printf(R"(
USAGE: hwuimacro [OPTIONS] <TESTNAME>

OPTIONS:
  -c, --count=NUM      NUM loops a test should run (example, number of frames)
  -r, --runs=NUM       Repeat the test(s) NUM times
  -h, --help           Display this help
  --list               List all tests
  --wait-for-gpu       Set this to wait for the GPU before producing the
                       next frame. Note that without locked clocks this will
                       pathologically bad performance due to large idle time
  --report-frametime[=weight] If set, the test will print to stdout the
                       moving average frametime. Weight is optional, default is 10
  --cpuset=name        Adds the test to the specified cpuset before running
                       Not supported on all devices and needs root
  --offscreen          Render tests off device screen. This option is on by default
  --onscreen           Render tests on device screen. By default tests
                       are offscreen rendered
  --benchmark_format   Set output format. Possible values are tabular, json, csv
  --benchmark_list_tests Lists the tests that would run but does not run them
  --benchmark_filter=<regex> Filters the test set to the given regex. If prefixed with `-` and test
                       that doesn't match the given regex is run
  --renderer=TYPE      Sets the render pipeline to use. May be skiagl or skiavk
  --skip-leak-check    Skips the memory leak check
  --report-gpu-memory[=verbose]  Dumps the GPU memory usage after each test run
)");
}

static void listTests() {
    printf("Tests: \n");
    for (auto&& test : TestScene::testMap()) {
        auto&& info = test.second;
        const char* col1 = info.name.c_str();
        int dlen = info.description.length();
        const char* col2 = info.description.c_str();
        // World's best line breaking algorithm.
        do {
            int toPrint = dlen;
            if (toPrint > 50) {
                char* found = (char*)memrchr(col2, ' ', 50);
                if (found) {
                    toPrint = found - col2;
                } else {
                    toPrint = 50;
                }
            }
            printf("%-20s %.*s\n", col1, toPrint, col2);
            col1 = "";
            col2 += toPrint;
            dlen -= toPrint;
            while (*col2 == ' ') {
                col2++;
                dlen--;
            }
        } while (dlen > 0);
        printf("\n");
    }
}

static void moveToCpuSet(const char* cpusetName) {
    if (access("/dev/cpuset/tasks", F_OK)) {
        fprintf(stderr, "don't have access to cpusets, skipping...\n");
        return;
    }
    static const int BUF_SIZE = 100;
    char buffer[BUF_SIZE];

    if (snprintf(buffer, BUF_SIZE, "/dev/cpuset/%s/tasks", cpusetName) >= BUF_SIZE) {
        fprintf(stderr, "Error, cpusetName too large to fit in buffer '%s'\n", cpusetName);
        return;
    }
    int fd = open(buffer, O_WRONLY | O_CLOEXEC);
    if (fd == -1) {
        fprintf(stderr, "Error opening file %d\n", errno);
        return;
    }
    pid_t pid = getpid();

    int towrite = snprintf(buffer, BUF_SIZE, "%ld", (long)pid);
    if (towrite >= BUF_SIZE) {
        fprintf(stderr, "Buffer wasn't large enough?\n");
    } else {
        if (write(fd, buffer, towrite) != towrite) {
            fprintf(stderr, "Failed to write, errno=%d", errno);
        }
    }
    close(fd);
}

static bool setBenchmarkFormat(const char* format) {
    if (!strcmp(format, "tabular")) {
        gBenchmarkReporter.reset(new benchmark::ConsoleReporter());
    } else if (!strcmp(format, "json")) {
        // We cannot print the leak check if outputing to JSON as that will break
        // JSON parsers since it's not JSON-formatted
        gRunLeakCheck = false;
        gBenchmarkReporter.reset(new benchmark::JSONReporter());
    } else {
        fprintf(stderr, "Unknown format '%s'\n", format);
        return false;
    }
    return true;
}

static bool setRenderer(const char* renderer) {
    if (!strcmp(renderer, "skiagl")) {
        Properties::overrideRenderPipelineType(RenderPipelineType::SkiaGL);
    } else if (!strcmp(renderer, "skiavk")) {
        Properties::overrideRenderPipelineType(RenderPipelineType::SkiaVulkan);
    } else {
        fprintf(stderr, "Unknown format '%s'\n", renderer);
        return false;
    }
    return true;
}

static void addTestsThatMatchFilter(std::string spec) {
    if (spec.empty() || spec == "all") {
        spec = ".";  // Regexp that matches all benchmarks
    }
    bool isNegativeFilter = false;
    if (spec[0] == '-') {
        spec.replace(0, 1, "");
        isNegativeFilter = true;
    }
    std::regex re(spec, std::regex_constants::extended);
    for (auto& iter : TestScene::testMap()) {
        if ((isNegativeFilter && !std::regex_search(iter.first, re)) ||
            (!isNegativeFilter && std::regex_search(iter.first, re))) {
            gRunTests.push_back(iter.second);
        }
    }
}

// For options that only exist in long-form. Anything in the
// 0-255 range is reserved for short options (which just use their ASCII value)
namespace LongOpts {
enum {
    Reserved = 255,
    List,
    WaitForGpu,
    ReportFrametime,
    CpuSet,
    BenchmarkFormat,
    BenchmarkListTests,
    BenchmarkFilter,
    Onscreen,
    Offscreen,
    Renderer,
    SkipLeakCheck,
    ReportGpuMemory,
};
}

static const struct option LONG_OPTIONS[] = {
        {"count", required_argument, nullptr, 'c'},
        {"runs", required_argument, nullptr, 'r'},
        {"help", no_argument, nullptr, 'h'},
        {"list", no_argument, nullptr, LongOpts::List},
        {"wait-for-gpu", no_argument, nullptr, LongOpts::WaitForGpu},
        {"report-frametime", optional_argument, nullptr, LongOpts::ReportFrametime},
        {"cpuset", required_argument, nullptr, LongOpts::CpuSet},
        {"benchmark_format", required_argument, nullptr, LongOpts::BenchmarkFormat},
        {"benchmark_list_tests", optional_argument, nullptr, LongOpts::BenchmarkListTests},
        {"benchmark_filter", required_argument, nullptr, LongOpts::BenchmarkFilter},
        {"onscreen", no_argument, nullptr, LongOpts::Onscreen},
        {"offscreen", no_argument, nullptr, LongOpts::Offscreen},
        {"renderer", required_argument, nullptr, LongOpts::Renderer},
        {"skip-leak-check", no_argument, nullptr, LongOpts::SkipLeakCheck},
        {"report-gpu-memory", optional_argument, nullptr, LongOpts::ReportGpuMemory},
        {0, 0, 0, 0}};

static const char* SHORT_OPTIONS = "c:r:h";

void parseOptions(int argc, char* argv[]) {
    benchmark::BenchmarkReporter::Context::executable_name = (argc > 0) ? argv[0] : "unknown";

    int c;
    bool error = false;
    bool listTestsOnly = false;
    bool testsAreFiltered = false;
    opterr = 0;

    while (true) {
        /* getopt_long stores the option index here. */
        int option_index = 0;

        c = getopt_long(argc, argv, SHORT_OPTIONS, LONG_OPTIONS, &option_index);

        if (c == -1) break;

        switch (c) {
            case 0:
                // Option set a flag, don't need to do anything
                // (although none of the current LONG_OPTIONS do this...)
                break;

            case LongOpts::List:
                listTests();
                exit(EXIT_SUCCESS);
                break;

            case 'c':
                gOpts.frameCount = atoi(optarg);
                if (!gOpts.frameCount) {
                    fprintf(stderr, "Invalid frames argument '%s'\n", optarg);
                    error = true;
                }
                break;

            case 'r':
                gOpts.repeatCount = atoi(optarg);
                if (!gOpts.repeatCount) {
                    fprintf(stderr, "Invalid repeat argument '%s'\n", optarg);
                    error = true;
                } else {
                    gOpts.repeatCount = (gOpts.repeatCount > 0 ? gOpts.repeatCount : INT_MAX);
                }
                break;

            case LongOpts::ReportFrametime:
                if (optarg) {
                    gOpts.reportFrametimeWeight = atoi(optarg);
                    if (!gOpts.reportFrametimeWeight) {
                        fprintf(stderr, "Invalid report frametime weight '%s'\n", optarg);
                        error = true;
                    }
                } else {
                    gOpts.reportFrametimeWeight = 10;
                }
                break;

            case LongOpts::WaitForGpu:
                Properties::waitForGpuCompletion = true;
                break;

            case LongOpts::CpuSet:
                if (!optarg) {
                    error = true;
                    break;
                }
                moveToCpuSet(optarg);
                break;

            case LongOpts::BenchmarkFormat:
                if (!optarg) {
                    error = true;
                    break;
                }
                if (!setBenchmarkFormat(optarg)) {
                    error = true;
                }
                break;

            case LongOpts::BenchmarkListTests:
                if (!optarg || ParseBool(optarg) == ParseBoolResult::kTrue) {
                    listTestsOnly = true;
                }
                break;

            case LongOpts::BenchmarkFilter:
                if (!optarg) {
                    error = true;
                    break;
                }
                addTestsThatMatchFilter(optarg);
                testsAreFiltered = true;
                break;

            case LongOpts::Renderer:
                if (!optarg) {
                    error = true;
                    break;
                }
                if (!setRenderer(optarg)) {
                    error = true;
                }
                break;

            case LongOpts::Onscreen:
                gOpts.renderOffscreen = false;
                break;

            case LongOpts::Offscreen:
                gOpts.renderOffscreen = true;
                break;

            case LongOpts::SkipLeakCheck:
                gRunLeakCheck = false;
                break;

            case LongOpts::ReportGpuMemory:
                gOpts.reportGpuMemoryUsage = true;
                if (optarg) {
                    if (!strcmp("verbose", optarg)) {
                        gOpts.reportGpuMemoryUsageVerbose = true;
                    } else {
                        fprintf(stderr, "Invalid report gpu memory option '%s'\n", optarg);
                        error = true;
                    }
                }
                break;

            case 'h':
                printHelp();
                exit(EXIT_SUCCESS);
                break;

            case '?':
                fprintf(stderr, "Unrecognized option '%s'\n", argv[optind - 1]);
                [[fallthrough]];
            default:
                error = true;
                break;
        }
    }

    if (error) {
        fprintf(stderr, "Try '%s --help' for more information.\n", argv[0]);
        exit(EXIT_FAILURE);
    }

    /* Print any remaining command line arguments (not options). */
    if (optind < argc) {
        do {
            const char* test = argv[optind++];
            if (strchr(test, '*')) {
                // Glob match
                for (auto& iter : TestScene::testMap()) {
                    if (!fnmatch(test, iter.first.c_str(), 0)) {
                        gRunTests.push_back(iter.second);
                    }
                }
            } else {
                auto pos = TestScene::testMap().find(test);
                if (pos == TestScene::testMap().end()) {
                    fprintf(stderr, "Unknown test '%s'\n", test);
                    exit(EXIT_FAILURE);
                } else {
                    gRunTests.push_back(pos->second);
                }
            }
        } while (optind < argc);
    } else if (gRunTests.empty() && !testsAreFiltered) {
        for (auto& iter : TestScene::testMap()) {
            gRunTests.push_back(iter.second);
        }
    }

    if (listTestsOnly) {
        for (auto& iter : gRunTests) {
            std::cout << iter.name << std::endl;
        }
        exit(EXIT_SUCCESS);
    }
}

int main(int argc, char* argv[]) {
    Typeface::setRobotoTypefaceForTest();

    parseOptions(argc, argv);
    if (!gBenchmarkReporter && gOpts.renderOffscreen) {
        gBenchmarkReporter.reset(new benchmark::ConsoleReporter());
    }

    if (gBenchmarkReporter) {
        size_t name_field_width = 10;
        for (auto&& test : gRunTests) {
            name_field_width = std::max<size_t>(name_field_width, test.name.size());
        }
        // _50th, _90th, etc...
        name_field_width += 5;

        benchmark::BenchmarkReporter::Context context;
        context.name_field_width = name_field_width;
        gBenchmarkReporter->ReportContext(context);
    }

    for (auto&& test : gRunTests) {
        run(test, gOpts, gBenchmarkReporter.get());
    }

    if (gBenchmarkReporter) {
        gBenchmarkReporter->Finalize();
    }

    renderthread::RenderProxy::trimMemory(100);
    HardwareBitmapUploader::terminate();

    if (gRunLeakCheck) {
        LeakChecker::checkForLeaks();
    }
    return 0;
}
