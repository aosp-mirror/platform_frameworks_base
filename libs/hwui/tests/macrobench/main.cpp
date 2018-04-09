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

#include "tests/common/LeakChecker.h"
#include "tests/common/TestScene.h"

#include "Properties.h"
#include "hwui/Typeface.h"
#include "protos/hwui.pb.h"

#include <benchmark/benchmark.h>
#include <getopt.h>
#include <pthread.h>
#include <stdio.h>
#include <unistd.h>
#include <string>
#include <unordered_map>
#include <vector>

#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::test;

static int gRepeatCount = 1;
static std::vector<TestScene::Info> gRunTests;
static TestScene::Options gOpts;
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
  --renderer=TYPE      Sets the render pipeline to use. May be skiagl or skiavk
  --render-ahead=NUM   Sets how far to render-ahead. Must be 0 (default), 1, or 2.
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
        gBenchmarkReporter.reset(new benchmark::JSONReporter());
    } else if (!strcmp(format, "csv")) {
        gBenchmarkReporter.reset(new benchmark::CSVReporter());
    } else {
        fprintf(stderr, "Unknown format '%s'", format);
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
        fprintf(stderr, "Unknown format '%s'", renderer);
        return false;
    }
    return true;
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
    Onscreen,
    Offscreen,
    Renderer,
    RenderAhead,
};
}

static const struct option LONG_OPTIONS[] = {
        {"frames", required_argument, nullptr, 'f'},
        {"repeat", required_argument, nullptr, 'r'},
        {"help", no_argument, nullptr, 'h'},
        {"list", no_argument, nullptr, LongOpts::List},
        {"wait-for-gpu", no_argument, nullptr, LongOpts::WaitForGpu},
        {"report-frametime", optional_argument, nullptr, LongOpts::ReportFrametime},
        {"cpuset", required_argument, nullptr, LongOpts::CpuSet},
        {"benchmark_format", required_argument, nullptr, LongOpts::BenchmarkFormat},
        {"onscreen", no_argument, nullptr, LongOpts::Onscreen},
        {"offscreen", no_argument, nullptr, LongOpts::Offscreen},
        {"renderer", required_argument, nullptr, LongOpts::Renderer},
        {"render-ahead", required_argument, nullptr, LongOpts::RenderAhead},
        {0, 0, 0, 0}};

static const char* SHORT_OPTIONS = "c:r:h";

void parseOptions(int argc, char* argv[]) {
    int c;
    bool error = false;
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
                gOpts.count = atoi(optarg);
                if (!gOpts.count) {
                    fprintf(stderr, "Invalid frames argument '%s'\n", optarg);
                    error = true;
                }
                break;

            case 'r':
                gRepeatCount = atoi(optarg);
                if (!gRepeatCount) {
                    fprintf(stderr, "Invalid repeat argument '%s'\n", optarg);
                    error = true;
                } else {
                    gRepeatCount = (gRepeatCount > 0 ? gRepeatCount : INT_MAX);
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

            case LongOpts::RenderAhead:
                if (!optarg) {
                    error = true;
                }
                gOpts.renderAhead = atoi(optarg);
                if (gOpts.renderAhead < 0 || gOpts.renderAhead > 2) {
                    error = true;
                }
                break;

            case 'h':
                printHelp();
                exit(EXIT_SUCCESS);
                break;

            case '?':
                fprintf(stderr, "Unrecognized option '%s'\n", argv[optind - 1]);
            // fall-through
            default:
                error = true;
                break;
        }
    }

    if (error) {
        fprintf(stderr, "Try 'hwuitest --help' for more information.\n");
        exit(EXIT_FAILURE);
    }

    /* Print any remaining command line arguments (not options). */
    if (optind < argc) {
        do {
            const char* test = argv[optind++];
            auto pos = TestScene::testMap().find(test);
            if (pos == TestScene::testMap().end()) {
                fprintf(stderr, "Unknown test '%s'\n", test);
                exit(EXIT_FAILURE);
            } else {
                gRunTests.push_back(pos->second);
            }
        } while (optind < argc);
    } else {
        for (auto& iter : TestScene::testMap()) {
            gRunTests.push_back(iter.second);
        }
    }
}

int main(int argc, char* argv[]) {
    // set defaults
    gOpts.count = 150;

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

    for (int i = 0; i < gRepeatCount; i++) {
        for (auto&& test : gRunTests) {
            run(test, gOpts, gBenchmarkReporter.get());
        }
    }

    if (gBenchmarkReporter) {
        gBenchmarkReporter->Finalize();
    }

    LeakChecker::checkForLeaks();
    return 0;
}
