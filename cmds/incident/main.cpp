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
#include <android/util/ProtoOutputStream.h>
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
using android::util::FIELD_COUNT_SINGLE;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;

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
    ALOGD("section %d status %d\n", section, status);
    return Status::ok();
}

Status
StatusListener::onReportServiceStatus(const String16& service, int32_t status)
{
    fprintf(stderr, "service '%s' status %d\n", String8(service).string(), status);
    ALOGD("service '%s' status %d\n", String8(service).string(), status);
    return Status::ok();
}

Status
StatusListener::onReportFinished()
{
    fprintf(stderr, "done\n");
    ALOGD("done\n");
    exit(0);
    return Status::ok();
}

Status
StatusListener::onReportFailed()
{
    fprintf(stderr, "failed\n");
    ALOGD("failed\n");
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
    ssize_t low = 0;
    ssize_t high = INCIDENT_SECTION_COUNT - 1;

    while (low <= high) {
        ssize_t mid = (low + high) / 2;
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
get_privacy_policy(const char* arg)
{
    if (strcmp(arg, "L") == 0
        || strcmp(arg, "LOCAL") == 0) {
      return PRIVACY_POLICY_LOCAL;
    }
    if (strcmp(arg, "E") == 0
        || strcmp(arg, "EXPLICIT") == 0) {
      return PRIVACY_POLICY_EXPLICIT;
    }
    if (strcmp(arg, "A") == 0
        || strcmp(arg, "AUTO") == 0
        || strcmp(arg, "AUTOMATIC") == 0) {
      return PRIVACY_POLICY_AUTOMATIC;
    }
    return -1; // return the default value
}

// ================================================================================
static bool
parse_receiver_arg(const string& arg, string* pkg, string* cls)
{
    if (arg.length() == 0) {
        return true;
    }
    size_t slash = arg.find('/');
    if (slash == string::npos) {
        return false;
    }
    if (slash == 0 || slash == arg.length() - 1) {
        return false;
    }
    if (arg.find('/', slash+1) != string::npos) {
        return false;
    }
    pkg->assign(arg, 0, slash);
    cls->assign(arg, slash+1);
    if ((*cls)[0] == '.') {
        *cls = (*pkg) + (*cls);
    }
    return true;
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
    fprintf(out, "  -l           list available sections\n");
    fprintf(out, "  -p           privacy spec, LOCAL, EXPLICIT or AUTOMATIC. Default AUTOMATIC.\n");
    fprintf(out, "\n");
    fprintf(out, "and one of these destinations:\n");
    fprintf(out, "  -b           (default) print the report to stdout (in proto format)\n");
    fprintf(out, "  -d           send the report into dropbox\n");
    fprintf(out, "  -r REASON    human readable description of why the report is taken.\n");
    fprintf(out, "  -s PKG/CLS   send broadcast to the broadcast receiver.\n");
    fprintf(out, "\n");
    fprintf(out, "  SECTION     the field numbers of the incident report fields to include\n");
    fprintf(out, "\n");
}

int
main(int argc, char** argv)
{
    Status status;
    IncidentReportArgs args;
    enum { DEST_UNSET, DEST_DROPBOX, DEST_STDOUT, DEST_BROADCAST } destination = DEST_UNSET;
    int privacyPolicy = PRIVACY_POLICY_AUTOMATIC;
    string reason;
    string receiverArg;

    // Parse the args
    int opt;
    while ((opt = getopt(argc, argv, "bhdlp:r:s:")) != -1) {
        switch (opt) {
            case 'h':
                usage(stdout);
                return 0;
            case 'l':
                section_list(stdout);
                return 0;
            case 'b':
                if (!(destination == DEST_UNSET || destination == DEST_STDOUT)) {
                    usage(stderr);
                    return 1;
                }
                destination = DEST_STDOUT;
                break;
            case 'd':
                if (!(destination == DEST_UNSET || destination == DEST_DROPBOX)) {
                    usage(stderr);
                    return 1;
                }
                destination = DEST_DROPBOX;
                break;
            case 'p':
                privacyPolicy = get_privacy_policy(optarg);
                break;
            case 'r':
                if (reason.size() > 0) {
                    usage(stderr);
                    return 1;
                }
                reason = optarg;
                break;
            case 's':
                if (destination != DEST_UNSET) {
                    usage(stderr);
                    return 1;
                }
                destination = DEST_BROADCAST;
                receiverArg = optarg;
                break;
            default:
                usage(stderr);
                return 1;
        }
    }
    if (destination == DEST_UNSET) {
        destination = DEST_STDOUT;
    }

    string pkg;
    string cls;
    if (parse_receiver_arg(receiverArg, &pkg, &cls)) {
        args.setReceiverPkg(pkg);
        args.setReceiverCls(cls);
    } else {
        fprintf(stderr, "badly formatted -s package/class option: %s\n\n", receiverArg.c_str());
        usage(stderr);
        return 1;
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
                        ALOGD("Invalid section: %s\n", arg);
                        fprintf(stderr, "Invalid section: %s\n", arg);
                        return 1;
                    }
                    args.addSection(ic->id);
                }
            }
        }
    }
    args.setPrivacyPolicy(privacyPolicy);

    if (reason.size() > 0) {
        ProtoOutputStream proto;
        proto.write(/* reason field id */ 2 | FIELD_TYPE_STRING | FIELD_COUNT_SINGLE, reason);
        vector<uint8_t> header;
        proto.serializeToVector(&header);
        args.addHeader(header);
    }

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
            uint8_t buf[4096];
            ssize_t amt = TEMP_FAILURE_RETRY(read(fds[0], buf, sizeof(buf)));
            if (amt < 0) {
                break;
            } else if (amt == 0) {
                break;
            }

            ssize_t wamt = TEMP_FAILURE_RETRY(write(STDOUT_FILENO, buf, amt));
            if (wamt != amt) {
                return errno;
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
