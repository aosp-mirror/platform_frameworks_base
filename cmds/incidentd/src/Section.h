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
#pragma once

#ifndef SECTIONS_H
#define SECTIONS_H

#include "Reporter.h"

#include <stdarg.h>
#include <map>

#include <utils/String16.h>
#include <utils/String8.h>
#include <utils/Vector.h>

namespace android {
namespace os {
namespace incidentd {

const int64_t REMOTE_CALL_TIMEOUT_MS = 30 * 1000;  // 30 seconds

/**
 * Base class for sections
 */
class Section {
public:
    const int id;
    const int64_t timeoutMs;  // each section must have a timeout
    String8 name;

    Section(int id, int64_t timeoutMs = REMOTE_CALL_TIMEOUT_MS);
    virtual ~Section();

    virtual status_t Execute(ReportWriter* writer) const = 0;
};

/**
 * Section that reads in a file.
 */
class FileSection : public Section {
public:
    FileSection(int id, const char* filename,
                int64_t timeoutMs = 5000 /* 5 seconds */);
    virtual ~FileSection();

    virtual status_t Execute(ReportWriter* writer) const;

private:
    const char* mFilename;
    bool mIsSysfs;  // sysfs files are pollable but return POLLERR by default, handle it separately
};

/**
 * Section that reads in a file and gzips the content.
 */
class GZipSection : public Section {
public:
    GZipSection(int id, const char* filename, ...);
    virtual ~GZipSection();

    virtual status_t Execute(ReportWriter* writer) const;

private:
    // It looks up the content from multiple files and stops when the first one is available.
    const char** mFilenames;
};

/**
 * Base class for sections that call a command that might need a timeout.
 */
class WorkerThreadSection : public Section {
public:
    WorkerThreadSection(int id, int64_t timeoutMs = REMOTE_CALL_TIMEOUT_MS);
    virtual ~WorkerThreadSection();

    virtual status_t Execute(ReportWriter* writer) const;

    virtual status_t BlockingCall(int pipeWriteFd) const = 0;
};

/**
 * Section that forks and execs a command, and puts stdout as the section.
 */
class CommandSection : public Section {
public:
    CommandSection(int id, int64_t timeoutMs, const char* command, ...);

    CommandSection(int id, const char* command, ...);

    virtual ~CommandSection();

    virtual status_t Execute(ReportWriter* writer) const;

private:
    const char** mCommand;
};

/**
 * Section that calls dumpsys on a system service.
 */
class DumpsysSection : public WorkerThreadSection {
public:
    DumpsysSection(int id, const char* service, ...);
    virtual ~DumpsysSection();

    virtual status_t BlockingCall(int pipeWriteFd) const;

private:
    String16 mService;
    Vector<String16> mArgs;
};

/**
 * Section that calls dumpsys on a system service.
 */
class SystemPropertyDumpsysSection : public WorkerThreadSection {
public:
    SystemPropertyDumpsysSection(int id, const char* service, ...);
    virtual ~SystemPropertyDumpsysSection();

    virtual status_t BlockingCall(int pipeWriteFd) const;

private:
    String16 mService;
    Vector<String16> mArgs;
};

/**
 * Section that reads from logd.
 */
class LogSection : public WorkerThreadSection {
    // global last log retrieved timestamp for each log_id_t.
    static map<log_id_t, log_time> gLastLogsRetrieved;

public:
    LogSection(int id, log_id_t logID);
    virtual ~LogSection();

    virtual status_t BlockingCall(int pipeWriteFd) const;

private:
    log_id_t mLogID;
    bool mBinary;
};

/**
 * Section that gets data from tombstoned.
 */
class TombstoneSection : public WorkerThreadSection {
public:
    TombstoneSection(int id, const char* type, int64_t timeoutMs = 120000 /* 2 minutes */);
    virtual ~TombstoneSection();

    virtual status_t BlockingCall(int pipeWriteFd) const;

private:
    std::string mType;
};


/**
 * These sections will not be generated when doing an 'all' report, either
 * for size, speed of collection, or privacy.
 */
bool section_requires_specific_mention(int sectionId);

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // SECTIONS_H
