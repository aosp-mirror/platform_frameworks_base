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

#include "Benchmark.h"

#include "protos/hwui.pb.h"

#include <getopt.h>
#include <stdio.h>
#include <string>
#include <unistd.h>
#include <unordered_map>
#include <vector>

using namespace android;
using namespace android::uirenderer;

// Not a static global because we need to force the map to be constructed
// before we try to add things to it.
std::unordered_map<std::string, BenchmarkInfo>& testMap() {
    static std::unordered_map<std::string, BenchmarkInfo> testMap;
    return testMap;
}

void Benchmark::registerBenchmark(const BenchmarkInfo& info) {
    testMap()[info.name] = info;
}

static int gFrameCount = 150;
static int gRepeatCount = 1;
static std::vector<BenchmarkInfo> gRunTests;

static void printHelp() {
    printf("\
USAGE: hwuitest [OPTIONS] <TESTNAME>\n\
\n\
OPTIONS:\n\
  -c, --count=NUM      NUM loops a test should run (example, number of frames)\n\
  -r, --runs=NUM       Repeat the test(s) NUM times\n\
  -h, --help           Display this help\n\
  --list               List all tests\n\
\n");
}

static void listTests() {
    printf("Tests: \n");
    for (auto&& test : testMap()) {
        auto&& info = test.second;
        const char* col1 = info.name.c_str();
        int dlen = info.description.length();
        const char* col2 = info.description.c_str();
        // World's best line breaking algorithm.
        do {
            int toPrint = dlen;
            if (toPrint > 50) {
                char* found = (char*) memrchr(col2, ' ', 50);
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
                col2++; dlen--;
            }
        } while (dlen > 0);
        printf("\n");
    }
}

static const struct option LONG_OPTIONS[] = {
    { "frames", required_argument, nullptr, 'f' },
    { "repeat", required_argument, nullptr, 'r' },
    { "help", no_argument, nullptr, 'h' },
    { "list", no_argument, nullptr, 'l' },
    { 0, 0, 0, 0 }
};

static const char* SHORT_OPTIONS = "c:r:h";

void parseOptions(int argc, char* argv[]) {
    int c;
    // temporary variable
    int count;
    bool error = false;
    opterr = 0;

    while (true) {

        /* getopt_long stores the option index here. */
        int option_index = 0;

        c = getopt_long(argc, argv, SHORT_OPTIONS, LONG_OPTIONS, &option_index);

        if (c == -1)
            break;

        switch (c) {
        case 0:
            // Option set a flag, don't need to do anything
            // (although none of the current LONG_OPTIONS do this...)
            break;

        case 'l':
            listTests();
            exit(EXIT_SUCCESS);
            break;

        case 'c':
            count = atoi(optarg);
            if (!count) {
                fprintf(stderr, "Invalid frames argument '%s'\n", optarg);
                error = true;
            } else {
                gFrameCount = (count > 0 ? count : INT_MAX);
            }
            break;

        case 'r':
            count = atoi(optarg);
            if (!count) {
                fprintf(stderr, "Invalid repeat argument '%s'\n", optarg);
                error = true;
            } else {
                gRepeatCount = (count > 0 ? count : INT_MAX);
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
            auto pos = testMap().find(test);
            if (pos == testMap().end()) {
                fprintf(stderr, "Unknown test '%s'\n", test);
                exit(EXIT_FAILURE);
            } else {
                gRunTests.push_back(pos->second);
            }
        } while (optind < argc);
    } else {
        gRunTests.push_back(testMap()["shadowgrid"]);
    }
}

int main(int argc, char* argv[]) {
    parseOptions(argc, argv);

    BenchmarkOptions opts;
    opts.count = gFrameCount;
    for (int i = 0; i < gRepeatCount; i++) {
        for (auto&& test : gRunTests) {
            test.functor(opts);
        }
    }
    printf("Success!\n");
    return 0;
}
