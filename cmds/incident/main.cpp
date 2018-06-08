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

#define LOG_TAG "incident"

#include "incident_sections.h"

#include <android/os/BnIncidentReportStatusListener.h>
#include <android/os/IIncidentManager.h>
#include <android/os/IncidentReportArgs.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <utils/Looper.h>

#include <cstring>
#include <fcntl.h>
#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace android::os;

// ================================================================================
class StatusListener : public BnIncidentReportStatusListener {
public:
    StatusListener();
    virtual ~StatusListener();

    virtual Status onReportStarted();
    virtual Status onReportSectionStatus(int32_t section, int32_t status);
    virtual Status onReportServiceStatus(const String16& service, int32_t status);
    virtual Status onReportFinished();
    virtual Status onReportFailed();
};

StatusListener::StatusListener()
{
}

StatusListener::~StatusListener()
{
}

Status
StatusListener::onReportStarted()
{
    return Status::ok();
}

Status
StatusListener::onReportSectionStatus(int32_t section, int32_t status)
{
    fprintf(stderr, "section %d status %d\n", section, status);
    return Status::ok();
}

Status
StatusListener::onReportServiceStatus(const String16& service, int32_t status)
{
    fprintf(stderr, "service '%s' status %d\n", String8(service).string(), status);
    return Status::ok();
}

Status
StatusListener::onReportFinished()
{
    fprintf(stderr, "done\n");
    exit(0);
    return Status::ok();
}

Status
StatusListener::onReportFailed()
{
    fprintf(stderr, "failed\n");
    exit(1);
    return Status::ok();
}

// ================================================================================
static void section_list(FILE* out) {
    IncidentSection sections[INCIDENT_SECTION_COUNT];
    int i = 0;
    int j = 0;
    // sort the sections based on id
    while (i < INCIDENT_SECTION_COUNT) {
        IncidentSection curr = INCIDENT_SECTIONS[i];
        for (int k = 0; k < j; k++) {
            if (curr.id > sections[k].id) {
                continue;
            }
            IncidentSection tmp = curr;
            curr = sections[k];
            sections[k] = tmp;
        }
        sections[j] = curr;
        i++;
        j++;
    }

    fprintf(out, "available sections:\n");
    for (int i = 0; i < INCIDENT_SECTION_COUNT; ++i) {
        fprintf(out, "id: %4d, name: %s\n", sections[i].id, sections[i].name);
    }
}

// ================================================================================
static IncidentSection const*
find_section(const char* name)
{
    size_t low = 0;
    size_t high = INCIDENT_SECTION_COUNT - 1;

    while (low <= high) {
        size_t mid = (low + high) >> 1;
        IncidentSection const* section = INCIDENT_SECTIONS + mid;

        int cmp = strcmp(section->name, name);
        if (cmp < 0) {
            low = mid + 1;
        } else if (cmp > 0) {
            high = mid - 1;
        } else {
            return section;
        }
    }
    return NULL;
}

// ================================================================================
static int
get_dest(const char* arg)
{
    if (strcmp(arg, "L") == 0
        || strcmp(arg, "LOCAL") == 0) {
      return DEST_LOCAL;
    }
    if (strcmp(arg, "E") == 0
        || strcmp(arg, "EXPLICIT") == 0) {
      return DEST_EXPLICIT;
    }
    if (strcmp(arg, "A") == 0
        || strcmp(arg, "AUTO") == 0
        || strcmp(arg, "AUTOMATIC") == 0) {
      return DEST_AUTOMATIC;
    }
    return -1; // return the default value
}

// ================================================================================
static void
usage(FILE* out)
{
    fprintf(out, "usage: incident OPTIONS [SECTION...]\n");
    fprintf(out, "\n");
    fprintf(out, "Takes an incident report.\n");
    fprintf(out, "\n");
    fprintf(out, "OPTIONS\n");
    fprintf(out, "  -b           (default) print the report to stdout (in proto format)\n");
    fprintf(out, "  -d           send the report into dropbox\n");
    fprintf(out, "  -l           list available sections\n");
    fprintf(out, "  -p           privacy spec, LOCAL, EXPLICIT or AUTOMATIC\n");
    fprintf(out, "\n");
    fprintf(out, "  SECTION     the field numbers of the incident report fields to include\n");
    fprintf(out, "\n");
}

int
main(int argc, char** argv)
{
    Status status;
    IncidentReportArgs args;
    enum { DEST_DROPBOX, DEST_STDOUT } destination = DEST_STDOUT;
    int dest = -1; // default

    // Parse the args
    int opt;
    while ((opt = getopt(argc, argv, "bhdlp:")) != -1) {
        switch (opt) {
            case 'h':
                usage(stdout);
                return 0;
            case 'l':
                section_list(stdout);
                return 0;
            case 'b':
                destination = DEST_STDOUT;
                break;
            case 'd':
                destination = DEST_DROPBOX;
                break;
            case 'p':
                dest = get_dest(optarg);
                break;
            default:
                usage(stderr);
                return 1;
        }
    }

    if (optind == argc) {
        args.setAll(true);
    } else {
        for (int i=optind; i<argc; i++) {
            const char* arg = argv[i];
            char* end;
            if (arg[0] != '\0') {
                int section = strtol(arg, &end, 0);
                if (*end == '\0') {
                    args.addSection(section);
                } else {
                    IncidentSection const* ic = find_section(arg);
                    if (ic == NULL) {
                        fprintf(stderr, "Invalid section: %s\n", arg);
                        return 1;
                    }
                    args.addSection(ic->id);
                }
            }
        }
    }
    args.setDest(dest);

    // Start the thread pool.
    sp<ProcessState> ps(ProcessState::self());
    ps->startThreadPool();
    ps->giveThreadPoolName();

    // Look up the service
    sp<IIncidentManager> service = interface_cast<IIncidentManager>(
            defaultServiceManager()->getService(android::String16("incident")));
    if (service == NULL) {
        fprintf(stderr, "Couldn't look up the incident service\n");
        return 1;
    }

    // Construct the stream
    int fds[2];
    pipe(fds);

    unique_fd readEnd(fds[0]);
    unique_fd writeEnd(fds[1]);

    if (destination == DEST_STDOUT) {
        // Call into the service
        sp<StatusListener> listener(new StatusListener());
        status = service->reportIncidentToStream(args, listener, writeEnd);

        if (!status.isOk()) {
            fprintf(stderr, "reportIncident returned \"%s\"\n", status.toString8().string());
            return 1;
        }

        // Wait for the result and print out the data they send.
        //IPCThreadState::self()->joinThreadPool();

        while (true) {
            int amt = splice(fds[0], NULL, STDOUT_FILENO, NULL, 4096, 0);
            fprintf(stderr, "spliced %d bytes\n", amt);
            if (amt < 0) {
                return errno;
            } else if (amt == 0) {
                return 0;
            }
        }
    } else {
        status = service->reportIncident(args);
        if (!status.isOk()) {
            fprintf(stderr, "reportIncident returned \"%s\"\n", status.toString8().string());
            return 1;
        } else {
            return 0;
        }
    }

}
