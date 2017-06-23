/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "incident_helper"

#include "IncidentHelper.h"

#include <android-base/file.h>
#include <getopt.h>
#include <stdlib.h>
#include <unistd.h>

using namespace android::base;
using namespace std;

static void usage(FILE* out) {
    fprintf(out, "incident_helper is not designed to run manually, see README.md\n");
    fprintf(out, "usage: incident_helper -s SECTION -i INPUT -o OUTPUT\n");
    fprintf(out, "REQUIRED:\n");
    fprintf(out, "  -s           section id, must be positive\n");
    fprintf(out, "  -i           (default stdin) input fd\n");
    fprintf(out, "  -o           (default stdout) output fd\n");
}

//=============================================================================
static TextParserBase* selectParser(int section) {
    switch (section) {
        // IDs smaller than or equal to 0 are reserved for testing
        case -1:
            return new TimeoutParser();
        case 0:
            return new ReverseParser();
/* ========================================================================= */
        // IDs larger than 0 are reserved in incident.proto
        case 2002:
            return new KernelWakesParser();
        default:
            return NULL;
    }
}

//=============================================================================
int main(int argc, char** argv) {
    fprintf(stderr, "Start incident_helper...\n");

    // Parse the args
    int opt;
    int sectionID = 0;
    int inputFd = STDIN_FILENO;
    int outputFd = STDOUT_FILENO;
    while ((opt = getopt(argc, argv, "hs:i:o:")) != -1) {
        switch (opt) {
            case 'h':
                usage(stdout);
                return 0;
            case 's':
                sectionID = atoi(optarg);
                break;
            case 'i':
                inputFd = atoi(optarg);
                break;
            case 'o':
                outputFd = atoi(optarg);
                break;
        }
    }

    // Check mandatory parameters:
    if (inputFd < 0) {
        fprintf(stderr, "invalid input fd: %d\n", inputFd);
        return 1;
    }
    if (outputFd < 0) {
        fprintf(stderr, "invalid output fd: %d\n", outputFd);
        return 1;
    }

    fprintf(stderr, "Pasring section %d...\n", sectionID);
    TextParserBase* parser = selectParser(sectionID);
    if (parser != NULL) {
        fprintf(stderr, "Running parser: %s\n", parser->name.string());
        status_t err = parser->Parse(inputFd, outputFd);
        if (err != NO_ERROR) {
            fprintf(stderr, "Parse error in section %d: %s\n", sectionID, strerror(-err));
            return -1;
        }

        delete parser;
    }
    fprintf(stderr, "Finish section %d, exiting...\n", sectionID);

    return 0;
}
