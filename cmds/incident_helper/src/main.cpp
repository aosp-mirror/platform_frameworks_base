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

#include "parsers/BatteryTypeParser.h"
#include "parsers/CpuFreqParser.h"
#include "parsers/CpuInfoParser.h"
#include "parsers/EventLogTagsParser.h"
#include "parsers/KernelWakesParser.h"
#include "parsers/PageTypeInfoParser.h"
#include "parsers/ProcrankParser.h"
#include "parsers/PsParser.h"
#include "parsers/SystemPropertiesParser.h"

#include <android-base/file.h>
#include <getopt.h>
#include <stdlib.h>
#include <unistd.h>

using namespace android::base;
using namespace std;

static void usage(FILE* out) {
    fprintf(out, "incident_helper is not designed to run manually,");
    fprintf(out, "it reads from stdin and writes to stdout, see README.md for details.\n");
    fprintf(out, "usage: incident_helper -s SECTION\n");
    fprintf(out, "REQUIRED:\n");
    fprintf(out, "  -s           section id, must be positive\n");
}

//=============================================================================
static TextParserBase* selectParser(int section) {
    switch (section) {
        // IDs smaller than or equal to 0 are reserved for testing
        case -1:
            return new TimeoutParser();
        case 0:
            return new NoopParser();
        case 1: // 1 is reserved for incident header so it won't be section id
            return new ReverseParser();
/* ========================================================================= */
        // IDs larger than 1 are section ids reserved in incident.proto
        case 1000:
            return new SystemPropertiesParser();
        case 1100:
            return new EventLogTagsParser();
        case 2000:
            return new ProcrankParser();
        case 2001:
            return new PageTypeInfoParser();
        case 2002:
            return new KernelWakesParser();
        case 2003:
            return new CpuInfoParser();
        case 2004:
            return new CpuFreqParser();
        case 2005:
            return new PsParser();
        case 2006:
            return new BatteryTypeParser();
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
    while ((opt = getopt(argc, argv, "hs:")) != -1) {
        switch (opt) {
            case 'h':
                usage(stdout);
                return 0;
            case 's':
                sectionID = atoi(optarg);
                break;
        }
    }

    fprintf(stderr, "Pasring section %d...\n", sectionID);
    TextParserBase* parser = selectParser(sectionID);
    if (parser != NULL) {
        fprintf(stderr, "Running parser: %s\n", parser->name.string());
        status_t err = parser->Parse(STDIN_FILENO, STDOUT_FILENO);
        if (err != NO_ERROR) {
            fprintf(stderr, "Parse error in section %d: %s\n", sectionID, strerror(-err));
            return -1;
        }

        delete parser;
    }
    fprintf(stderr, "Finish section %d, exiting...\n", sectionID);

    return 0;
}
