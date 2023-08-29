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
#define DEBUG false
#include "Log.h"

#include "Section.h"

#include <dirent.h>
#include <errno.h>
#include <mutex>
#include <set>
#include <thread>

#include <android-base/file.h>
#include <android-base/properties.h>
#include <android-base/stringprintf.h>
#include <android/util/protobuf.h>
#include <android/util/ProtoOutputStream.h>
#include <binder/IServiceManager.h>
#include <debuggerd/client.h>
#include <dumputils/dump_utils.h>
#include <log/log_event_list.h>
#include <log/logprint.h>
#include <private/android_logger.h>
#include <sys/mman.h>

#include "FdBuffer.h"
#include "Privacy.h"
#include "frameworks/base/core/proto/android/os/backtrace.proto.h"
#include "frameworks/base/core/proto/android/os/data.proto.h"
#include "frameworks/base/core/proto/android/util/log.proto.h"
#include "frameworks/base/core/proto/android/util/textdump.proto.h"
#include "incidentd_util.h"

namespace android {
namespace os {
namespace incidentd {

using namespace android::base;
using namespace android::util;

// special section ids
const int FIELD_ID_INCIDENT_METADATA = 2;

// incident section parameters
const char INCIDENT_HELPER[] = "/system/bin/incident_helper";
const char* GZIP[] = {"/system/bin/gzip", NULL};

static pid_t fork_execute_incident_helper(const int id, Fpipe* p2cPipe, Fpipe* c2pPipe) {
    const char* ihArgs[]{INCIDENT_HELPER, "-s", String8::format("%d", id).c_str(), NULL};
    return fork_execute_cmd(const_cast<char**>(ihArgs), p2cPipe, c2pPipe);
}

bool section_requires_specific_mention(int sectionId) {
    switch (sectionId) {
        case 3025: // restricted_images
            return true;
        case 3026: // system_trace
            return true;
        default:
            return false;
    }
}

// ================================================================================
Section::Section(int i, int64_t timeoutMs)
    : id(i),
      timeoutMs(timeoutMs) {
}

Section::~Section() {}

// ================================================================================
static inline bool isSysfs(const char* filename) { return strncmp(filename, "/sys/", 5) == 0; }

FileSection::FileSection(int id, const char* filename, const int64_t timeoutMs)
    : Section(id, timeoutMs), mFilename(filename) {
    name = "file ";
    name += filename;
    mIsSysfs = isSysfs(filename);
}

FileSection::~FileSection() {}

status_t FileSection::Execute(ReportWriter* writer) const {
    // read from mFilename first, make sure the file is available
    // add O_CLOEXEC to make sure it is closed when exec incident helper
    unique_fd fd(open(mFilename, O_RDONLY | O_CLOEXEC));
    if (fd.get() == -1) {
        ALOGW("[%s] failed to open file", this->name.c_str());
        // There may be some devices/architectures that won't have the file.
        // Just return here without an error.
        return NO_ERROR;
    }

    Fpipe p2cPipe;
    Fpipe c2pPipe;
    // initiate pipes to pass data to/from incident_helper
    if (!p2cPipe.init() || !c2pPipe.init()) {
        ALOGW("[%s] failed to setup pipes", this->name.c_str());
        return -errno;
    }

    pid_t pid = fork_execute_incident_helper(this->id, &p2cPipe, &c2pPipe);
    if (pid == -1) {
        ALOGW("[%s] failed to fork", this->name.c_str());
        return -errno;
    }

    // parent process
    FdBuffer buffer;
    status_t readStatus = buffer.readProcessedDataInStream(fd.get(), std::move(p2cPipe.writeFd()),
                                                           std::move(c2pPipe.readFd()),
                                                           this->timeoutMs, mIsSysfs);
    writer->setSectionStats(buffer);
    if (readStatus != NO_ERROR || buffer.timedOut()) {
        ALOGW("[%s] failed to read data from incident helper: %s, timedout: %s",
              this->name.c_str(), strerror(-readStatus), buffer.timedOut() ? "true" : "false");
        kill_child(pid);
        return readStatus;
    }

    status_t ihStatus = wait_child(pid);
    if (ihStatus != NO_ERROR) {
        ALOGW("[%s] abnormal child process: %s", this->name.c_str(), strerror(-ihStatus));
        return OK; // Not a fatal error.
    }

    return writer->writeSection(buffer);
}
// ================================================================================
GZipSection::GZipSection(int id, const char* filename, ...) : Section(id) {
    va_list args;
    va_start(args, filename);
    mFilenames = varargs(filename, args);
    va_end(args);
    name = "gzip";
    for (int i = 0; mFilenames[i] != NULL; i++) {
        name += " ";
        name += mFilenames[i];
    }
}

GZipSection::~GZipSection() { free(mFilenames); }

status_t GZipSection::Execute(ReportWriter* writer) const {
    // Reads the files in order, use the first available one.
    int index = 0;
    unique_fd fd;
    while (mFilenames[index] != NULL) {
        fd.reset(open(mFilenames[index], O_RDONLY | O_CLOEXEC));
        if (fd.get() != -1) {
            break;
        }
        ALOGW("GZipSection failed to open file %s", mFilenames[index]);
        index++;  // look at the next file.
    }
    if (fd.get() == -1) {
        ALOGW("[%s] can't open all the files", this->name.c_str());
        return NO_ERROR;  // e.g. LAST_KMSG will reach here in user build.
    }
    FdBuffer buffer;
    Fpipe p2cPipe;
    Fpipe c2pPipe;
    // initiate pipes to pass data to/from gzip
    if (!p2cPipe.init() || !c2pPipe.init()) {
        ALOGW("[%s] failed to setup pipes", this->name.c_str());
        return -errno;
    }

    pid_t pid = fork_execute_cmd((char* const*)GZIP, &p2cPipe, &c2pPipe);
    if (pid == -1) {
        ALOGW("[%s] failed to fork", this->name.c_str());
        return -errno;
    }
    // parent process

    // construct Fdbuffer to output GZippedfileProto, the reason to do this instead of using
    // ProtoOutputStream is to avoid allocation of another buffer inside ProtoOutputStream.
    sp<EncodedBuffer> internalBuffer = buffer.data();
    internalBuffer->writeHeader((uint32_t)GZippedFileProto::FILENAME, WIRE_TYPE_LENGTH_DELIMITED);
    size_t fileLen = strlen(mFilenames[index]);
    internalBuffer->writeRawVarint32(fileLen);
    for (size_t i = 0; i < fileLen; i++) {
        internalBuffer->writeRawByte(mFilenames[index][i]);
    }
    internalBuffer->writeHeader((uint32_t)GZippedFileProto::GZIPPED_DATA,
                                WIRE_TYPE_LENGTH_DELIMITED);
    size_t editPos = internalBuffer->wp()->pos();
    internalBuffer->wp()->move(8);  // reserve 8 bytes for the varint of the data size.
    size_t dataBeginAt = internalBuffer->wp()->pos();
    VLOG("[%s] editPos=%zu, dataBeginAt=%zu", this->name.c_str(), editPos, dataBeginAt);

    status_t readStatus = buffer.readProcessedDataInStream(
            fd.get(), std::move(p2cPipe.writeFd()), std::move(c2pPipe.readFd()), this->timeoutMs,
            isSysfs(mFilenames[index]));
    writer->setSectionStats(buffer);
    if (readStatus != NO_ERROR || buffer.timedOut()) {
        ALOGW("[%s] failed to read data from gzip: %s, timedout: %s", this->name.c_str(),
              strerror(-readStatus), buffer.timedOut() ? "true" : "false");
        kill_child(pid);
        return readStatus;
    }

    status_t gzipStatus = wait_child(pid);
    if (gzipStatus != NO_ERROR) {
        ALOGW("[%s] abnormal child process: %s", this->name.c_str(), strerror(-gzipStatus));
        return gzipStatus;
    }
    // Revisit the actual size from gzip result and edit the internal buffer accordingly.
    size_t dataSize = buffer.size() - dataBeginAt;
    internalBuffer->wp()->rewind()->move(editPos);
    internalBuffer->writeRawVarint32(dataSize);
    internalBuffer->copy(dataBeginAt, dataSize);

    return writer->writeSection(buffer);
}

// ================================================================================
struct WorkerThreadData : public virtual RefBase {
    const WorkerThreadSection* section;
    Fpipe pipe;

    // Lock protects these fields
    std::mutex lock;
    bool workerDone;
    status_t workerError;

    explicit WorkerThreadData(const WorkerThreadSection* section);
    virtual ~WorkerThreadData();
};

WorkerThreadData::WorkerThreadData(const WorkerThreadSection* sec)
    : section(sec), workerDone(false), workerError(NO_ERROR) {}

WorkerThreadData::~WorkerThreadData() {}

// ================================================================================
WorkerThreadSection::WorkerThreadSection(int id, const int64_t timeoutMs)
    : Section(id, timeoutMs) {}

WorkerThreadSection::~WorkerThreadSection() {}

void sigpipe_handler(int signum) {
    if (signum == SIGPIPE) {
        ALOGE("Wrote to a broken pipe\n");
    } else {
        ALOGE("Received unexpected signal: %d\n", signum);
    }
}

status_t WorkerThreadSection::Execute(ReportWriter* writer) const {
    // Create shared data and pipe. Don't put data on the stack since this thread may exit early.
    sp<WorkerThreadData> data = new WorkerThreadData(this);
    if (!data->pipe.init()) {
        return -errno;
    }
    data->incStrong(this);
    std::thread([data, this]() {
        // Don't crash the service if writing to a closed pipe (may happen if dumping times out)
        signal(SIGPIPE, sigpipe_handler);
        status_t err = data->section->BlockingCall(data->pipe.writeFd());
        {
            std::scoped_lock<std::mutex> lock(data->lock);
            data->workerDone = true;
            data->workerError = err;
            // unique_fd is not thread safe. If we don't lock it, reset() may pause half way while
            // the other thread executes to the end, calling ~Fpipe, which is a race condition.
            data->pipe.writeFd().reset();
        }
        data->decStrong(this);
    }).detach();

    // Loop reading until either the timeout or the worker side is done (i.e. eof).
    status_t err = NO_ERROR;
    bool workerDone = false;
    FdBuffer buffer;
    err = buffer.read(data->pipe.readFd().get(), this->timeoutMs);
    if (err != NO_ERROR) {
        ALOGE("[%s] reader failed with error '%s'", this->name.c_str(), strerror(-err));
    }

    // If the worker side is finished, then return its error (which may overwrite
    // our possible error -- but it's more interesting anyway). If not, then we timed out.
    {
        std::scoped_lock<std::mutex> lock(data->lock);
        data->pipe.close();
        if (data->workerError != NO_ERROR) {
            err = data->workerError;
            ALOGE("[%s] worker failed with error '%s'", this->name.c_str(), strerror(-err));
        }
        workerDone = data->workerDone;
    }

    writer->setSectionStats(buffer);
    if (err != NO_ERROR) {
        char errMsg[128];
        snprintf(errMsg, 128, "[%s] failed with error '%s'",
            this->name.c_str(), strerror(-err));
        writer->error(this, err, "WorkerThreadSection failed.");
        return NO_ERROR;
    }
    if (buffer.truncated()) {
        ALOGW("[%s] too large, truncating", this->name.c_str());
        // Do not write a truncated section. It won't pass through the PrivacyFilter.
        return NO_ERROR;
    }
    if (!workerDone || buffer.timedOut()) {
        ALOGW("[%s] timed out", this->name.c_str());
        return NO_ERROR;
    }

    // Write the data that was collected
    return writer->writeSection(buffer);
}

// ================================================================================
CommandSection::CommandSection(int id, const int64_t timeoutMs, const char* command, ...)
    : Section(id, timeoutMs) {
    va_list args;
    va_start(args, command);
    mCommand = varargs(command, args);
    va_end(args);
    name = "cmd";
    for (int i = 0; mCommand[i] != NULL; i++) {
        name += " ";
        name += mCommand[i];
    }
}

CommandSection::CommandSection(int id, const char* command, ...) : Section(id) {
    va_list args;
    va_start(args, command);
    mCommand = varargs(command, args);
    va_end(args);
    name = "cmd";
    for (int i = 0; mCommand[i] != NULL; i++) {
        name += " ";
        name += mCommand[i];
    }
}

CommandSection::~CommandSection() { free(mCommand); }

status_t CommandSection::Execute(ReportWriter* writer) const {
    Fpipe cmdPipe;
    Fpipe ihPipe;

    if (!cmdPipe.init() || !ihPipe.init()) {
        ALOGW("[%s] failed to setup pipes", this->name.c_str());
        return -errno;
    }

    pid_t cmdPid = fork_execute_cmd((char* const*)mCommand, NULL, &cmdPipe);
    if (cmdPid == -1) {
        ALOGW("[%s] failed to fork", this->name.c_str());
        return -errno;
    }
    pid_t ihPid = fork_execute_incident_helper(this->id, &cmdPipe, &ihPipe);
    if (ihPid == -1) {
        ALOGW("[%s] failed to fork", this->name.c_str());
        return -errno;
    }

    cmdPipe.writeFd().reset();
    FdBuffer buffer;
    status_t readStatus = buffer.read(ihPipe.readFd().get(), this->timeoutMs);
    writer->setSectionStats(buffer);
    if (readStatus != NO_ERROR || buffer.timedOut()) {
        ALOGW("[%s] failed to read data from incident helper: %s, timedout: %s",
              this->name.c_str(), strerror(-readStatus), buffer.timedOut() ? "true" : "false");
        kill_child(cmdPid);
        kill_child(ihPid);
        return readStatus;
    }

    // Waiting for command here has one trade-off: the failed status of command won't be detected
    // until buffer timeout, but it has advatage on starting the data stream earlier.
    status_t cmdStatus = wait_child(cmdPid);
    status_t ihStatus = wait_child(ihPid);
    if (cmdStatus != NO_ERROR || ihStatus != NO_ERROR) {
        ALOGW("[%s] abnormal child processes, return status: command: %s, incident helper: %s",
              this->name.c_str(), strerror(-cmdStatus), strerror(-ihStatus));
        // Not a fatal error.
        return NO_ERROR;
    }

    return writer->writeSection(buffer);
}

// ================================================================================
DumpsysSection::DumpsysSection(int id, const char* service, ...)
    : WorkerThreadSection(id, REMOTE_CALL_TIMEOUT_MS), mService(service) {
    name = "dumpsys ";
    name += service;

    va_list args;
    va_start(args, service);
    while (true) {
        const char* arg = va_arg(args, const char*);
        if (arg == NULL) {
            break;
        }
        mArgs.add(String16(arg));
        name += " ";
        name += arg;
    }
    va_end(args);
}

DumpsysSection::~DumpsysSection() {}

status_t DumpsysSection::BlockingCall(unique_fd& pipeWriteFd) const {
    // checkService won't wait for the service to show up like getService will.
    sp<IBinder> service = defaultServiceManager()->checkService(mService);

    if (service == NULL) {
        ALOGW("DumpsysSection: Can't lookup service: %s", String8(mService).c_str());
        return NAME_NOT_FOUND;
    }

    service->dump(pipeWriteFd.get(), mArgs);

    return NO_ERROR;
}

// ================================================================================
TextDumpsysSection::TextDumpsysSection(int id, const char* service, ...)
        :Section(id), mService(service) {
    name = "dumpsys ";
    name += service;

    va_list args;
    va_start(args, service);
    while (true) {
        const char* arg = va_arg(args, const char*);
        if (arg == NULL) {
            break;
        }
        mArgs.add(String16(arg));
        name += " ";
        name += arg;
    }
    va_end(args);
}

TextDumpsysSection::~TextDumpsysSection() {}

status_t TextDumpsysSection::Execute(ReportWriter* writer) const {
    // checkService won't wait for the service to show up like getService will.
    sp<IBinder> service = defaultServiceManager()->checkService(mService);
    if (service == NULL) {
        ALOGW("TextDumpsysSection: Can't lookup service: %s", String8(mService).c_str());
        return NAME_NOT_FOUND;
    }

    // Create pipe
    Fpipe dumpPipe;
    if (!dumpPipe.init()) {
        ALOGW("[%s] failed to setup pipe", this->name.c_str());
        return -errno;
    }

    // Run dumping thread
    const uint64_t start = Nanotime();
    std::thread worker([write_fd = std::move(dumpPipe.writeFd()), service = std::move(service),
                        this]() mutable {
        // Don't crash the service if writing to a closed pipe (may happen if dumping times out)
        signal(SIGPIPE, sigpipe_handler);
        status_t err = service->dump(write_fd.get(), this->mArgs);
        if (err != OK) {
            ALOGW("[%s] dump thread failed. Error: %s", this->name.c_str(), strerror(-err));
        }
        write_fd.reset();
    });

    // Collect dump content
    FdBuffer buffer;
    ProtoOutputStream proto;
    proto.write(TextDumpProto::COMMAND, std::string(name.c_str()));
    proto.write(TextDumpProto::DUMP_DURATION_NS, int64_t(Nanotime() - start));
    buffer.write(proto.data());

    sp<EncodedBuffer> internalBuffer = buffer.data();
    internalBuffer->writeHeader((uint32_t)TextDumpProto::CONTENT, WIRE_TYPE_LENGTH_DELIMITED);
    size_t editPos = internalBuffer->wp()->pos();
    internalBuffer->wp()->move(8); // reserve 8 bytes for the varint of the data size
    size_t dataBeginPos = internalBuffer->wp()->pos();

    status_t readStatus = buffer.read(dumpPipe.readFd(), this->timeoutMs);
    dumpPipe.readFd().reset();
    writer->setSectionStats(buffer);
    if (readStatus != OK || buffer.timedOut()) {
        ALOGW("[%s] failed to read from dumpsys: %s, timedout: %s", this->name.c_str(),
              strerror(-readStatus), buffer.timedOut() ? "true" : "false");
        worker.detach();
        return readStatus;
    }
    worker.join(); // wait for worker to finish

    // Revisit the actual size from dumpsys and edit the internal buffer accordingly.
    size_t dumpSize = buffer.size() - dataBeginPos;
    internalBuffer->wp()->rewind()->move(editPos);
    internalBuffer->writeRawVarint32(dumpSize);
    internalBuffer->copy(dataBeginPos, dumpSize);

    return writer->writeSection(buffer);
}

// ================================================================================
// initialization only once in Section.cpp.
map<log_id_t, log_time> LogSection::gLastLogsRetrieved;

LogSection::LogSection(int id, const char* logID, ...) : WorkerThreadSection(id), mLogMode(logModeBase) {
    name = "logcat -b ";
    name += logID;

    va_list args;
    va_start(args, logID);
    mLogID = android_name_to_log_id(logID);
    while(true) {
        const char* arg = va_arg(args, const char*);
        if (arg == NULL) {
            break;
        }
        if (!strcmp(arg, "-L")) {
          // Read from last logcat buffer
          mLogMode = mLogMode | ANDROID_LOG_PSTORE;
        }
        name += " ";
        name += arg;
    }
    va_end(args);

    switch (mLogID) {
        case LOG_ID_EVENTS:
        case LOG_ID_STATS:
        case LOG_ID_SECURITY:
            mBinary = true;
            break;
        default:
            mBinary = false;
    }
}

LogSection::~LogSection() {}

static size_t trimTail(char const* buf, size_t len) {
    while (len > 0) {
        char c = buf[len - 1];
        if (c == '\0' || c == ' ' || c == '\n' || c == '\r' || c == ':') {
            len--;
        } else {
            break;
        }
    }
    return len;
}

static inline int32_t get4LE(uint8_t const* src) {
    return src[0] | (src[1] << 8) | (src[2] << 16) | (src[3] << 24);
}

status_t LogSection::BlockingCall(unique_fd& pipeWriteFd) const {
    // heap profile shows that liblog malloc & free significant amount of memory in this process.
    // Hence forking a new process to prevent memory fragmentation.
    pid_t pid = fork();
    if (pid < 0) {
        ALOGW("[%s] failed to fork", this->name.c_str());
        return errno;
    }
    if (pid > 0) {
        return wait_child(pid, this->timeoutMs);
    }
    // Open log buffer and getting logs since last retrieved time if any.
    unique_ptr<logger_list, void (*)(logger_list*)> loggers(
            gLastLogsRetrieved.find(mLogID) == gLastLogsRetrieved.end()
                    ? android_logger_list_alloc(mLogMode, 0, 0)
                    : android_logger_list_alloc_time(mLogMode, gLastLogsRetrieved[mLogID], 0),
            android_logger_list_free);

    if (android_logger_open(loggers.get(), mLogID) == NULL) {
        ALOGE("[%s] Can't get logger.", this->name.c_str());
        _exit(EXIT_FAILURE);
    }

    log_msg msg;
    log_time lastTimestamp(0);

    ProtoOutputStream proto;
    status_t err = OK;
    while (true) {  // keeps reading until logd buffer is fully read.
        status_t status = android_logger_list_read(loggers.get(), &msg);
        // status = 0 - no content, unexpected connection drop or EOF.
        // status = +ive number - size of retrieved data from logger
        // status = -ive number, OS supplied error _except_ for -EAGAIN
        // status = -EAGAIN, graceful indication for ANDRODI_LOG_NONBLOCK that this is the end.
        if (status <= 0) {
            if (status != -EAGAIN) {
                ALOGW("[%s] fails to read a log_msg.\n", this->name.c_str());
                err = -status;
            }
            break;
        }
        if (mBinary) {
            // remove the first uint32 which is tag's index in event log tags
            android_log_context context = create_android_log_parser(msg.msg() + sizeof(uint32_t),
                                                                    msg.len() - sizeof(uint32_t));
            android_log_list_element elem;

            lastTimestamp.tv_sec = msg.entry.sec;
            lastTimestamp.tv_nsec = msg.entry.nsec;

            // format a BinaryLogEntry
            uint64_t token = proto.start(LogProto::BINARY_LOGS);
            proto.write(BinaryLogEntry::SEC, (int32_t)msg.entry.sec);
            proto.write(BinaryLogEntry::NANOSEC, (int32_t)msg.entry.nsec);
            proto.write(BinaryLogEntry::UID, (int)msg.entry.uid);
            proto.write(BinaryLogEntry::PID, msg.entry.pid);
            proto.write(BinaryLogEntry::TID, (int32_t)msg.entry.tid);
            proto.write(BinaryLogEntry::TAG_INDEX,
                        get4LE(reinterpret_cast<uint8_t const*>(msg.msg())));
            do {
                elem = android_log_read_next(context);
                uint64_t elemToken = proto.start(BinaryLogEntry::ELEMS);
                switch (elem.type) {
                    case EVENT_TYPE_INT:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_INT);
                        proto.write(BinaryLogEntry::Elem::VAL_INT32, (int)elem.data.int32);
                        break;
                    case EVENT_TYPE_LONG:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_LONG);
                        proto.write(BinaryLogEntry::Elem::VAL_INT64, (long long)elem.data.int64);
                        break;
                    case EVENT_TYPE_STRING:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_STRING);
                        proto.write(BinaryLogEntry::Elem::VAL_STRING, elem.data.string, elem.len);
                        break;
                    case EVENT_TYPE_FLOAT:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_FLOAT);
                        proto.write(BinaryLogEntry::Elem::VAL_FLOAT, elem.data.float32);
                        break;
                    case EVENT_TYPE_LIST:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_LIST);
                        break;
                    case EVENT_TYPE_LIST_STOP:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_LIST_STOP);
                        break;
                    case EVENT_TYPE_UNKNOWN:
                        proto.write(BinaryLogEntry::Elem::TYPE,
                                    BinaryLogEntry::Elem::EVENT_TYPE_UNKNOWN);
                        break;
                }
                proto.end(elemToken);
            } while ((elem.type != EVENT_TYPE_UNKNOWN) && !elem.complete);
            proto.end(token);
            if (context) {
                android_log_destroy(&context);
            }
        } else {
            AndroidLogEntry entry;
            status = android_log_processLogBuffer(&msg.entry, &entry);
            if (status != OK) {
                ALOGW("[%s] fails to process to an entry.\n", this->name.c_str());
                err = status;
                break;
            }
            lastTimestamp.tv_sec = entry.tv_sec;
            lastTimestamp.tv_nsec = entry.tv_nsec;

            // format a TextLogEntry
            uint64_t token = proto.start(LogProto::TEXT_LOGS);
            proto.write(TextLogEntry::SEC, (long long)entry.tv_sec);
            proto.write(TextLogEntry::NANOSEC, (long long)entry.tv_nsec);
            proto.write(TextLogEntry::PRIORITY, (int)entry.priority);
            proto.write(TextLogEntry::UID, entry.uid);
            proto.write(TextLogEntry::PID, entry.pid);
            proto.write(TextLogEntry::TID, entry.tid);
            proto.write(TextLogEntry::TAG, entry.tag, trimTail(entry.tag, entry.tagLen));
            proto.write(TextLogEntry::LOG, entry.message,
                        trimTail(entry.message, entry.messageLen));
            proto.end(token);
        }
        if (!proto.flush(pipeWriteFd.get())) {
            if (errno == EPIPE) {
                ALOGW("[%s] wrote to a broken pipe\n", this->name.c_str());
            }
            err = errno;
            break;
        }
        proto.clear();
    }
    gLastLogsRetrieved[mLogID] = lastTimestamp;
    _exit(err);
}

// ================================================================================

const int LINK_NAME_LEN = 64;
const int EXE_NAME_LEN = 1024;

TombstoneSection::TombstoneSection(int id, const char* type, const int64_t timeoutMs)
    : WorkerThreadSection(id, timeoutMs), mType(type) {
    name = "tombstone ";
    name += type;
}

TombstoneSection::~TombstoneSection() {}

status_t TombstoneSection::BlockingCall(unique_fd& pipeWriteFd) const {
    std::unique_ptr<DIR, decltype(&closedir)> proc(opendir("/proc"), closedir);
    if (proc.get() == nullptr) {
        ALOGE("opendir /proc failed: %s\n", strerror(errno));
        return -errno;
    }

    const std::set<int> hal_pids = get_interesting_pids();

    auto pooledBuffer = get_buffer_from_pool();
    ProtoOutputStream proto(pooledBuffer);
    // dumpBufferSize should be a multiple of page size (4 KB) to reduce memory fragmentation
    size_t dumpBufferSize = 64 * 1024; // 64 KB is enough for most tombstone dump
    char* dumpBuffer = (char*)mmap(NULL, dumpBufferSize, PROT_READ | PROT_WRITE,
                MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
    struct dirent* d;
    char link_name[LINK_NAME_LEN];
    char exe_name[EXE_NAME_LEN];
    status_t err = NO_ERROR;
    while ((d = readdir(proc.get()))) {
        int pid = atoi(d->d_name);
        if (pid <= 0) {
            continue;
        }
        snprintf(link_name, LINK_NAME_LEN, "/proc/%d/exe", pid);
        struct stat fileStat;
        if (stat(link_name, &fileStat) != OK) {
            continue;
        }
        ssize_t exe_name_len = readlink(link_name, exe_name, EXE_NAME_LEN);
        if (exe_name_len < 0 || exe_name_len >= EXE_NAME_LEN) {
            ALOGE("[%s] Can't read '%s': %s", name.c_str(), link_name, strerror(errno));
            continue;
        }
        // readlink(2) does not put a null terminator at the end
        exe_name[exe_name_len] = '\0';

        bool is_java_process;
        if (strncmp(exe_name, "/system/bin/app_process32", LINK_NAME_LEN) == 0 ||
                strncmp(exe_name, "/system/bin/app_process64", LINK_NAME_LEN) == 0) {
            if (mType != "java") continue;
            // Don't bother dumping backtraces for the zygote.
            if (IsZygote(pid)) {
                VLOG("Skipping Zygote");
                continue;
            }

            is_java_process = true;
        } else if (should_dump_native_traces(exe_name)) {
            if (mType != "native") continue;
            is_java_process = false;
        } else if (hal_pids.find(pid) != hal_pids.end()) {
            if (mType != "hal") continue;
            is_java_process = false;
        } else {
            // Probably a native process we don't care about, continue.
            VLOG("Skipping %d", pid);
            continue;
        }

        Fpipe dumpPipe;
        if (!dumpPipe.init()) {
            ALOGW("[%s] failed to setup dump pipe", this->name.c_str());
            err = -errno;
            break;
        }

        const uint64_t start = Nanotime();
        pid_t child = fork();
        if (child < 0) {
            ALOGE("Failed to fork child process");
            break;
        } else if (child == 0) {
            // This is the child process.
            dumpPipe.readFd().reset();
            const int ret = dump_backtrace_to_file_timeout(
                    pid, is_java_process ? kDebuggerdJavaBacktrace : kDebuggerdNativeBacktrace,
                    is_java_process ? 5 : 20, dumpPipe.writeFd().get());
            if (ret == -1) {
                if (errno == 0) {
                    ALOGW("Dumping failed for pid '%d', likely due to a timeout\n", pid);
                } else {
                    ALOGE("Dumping failed for pid '%d': %s\n", pid, strerror(errno));
                }
            }
            dumpPipe.writeFd().reset();
            _exit(EXIT_SUCCESS);
        }
        dumpPipe.writeFd().reset();
        // Parent process.
        // Read from the pipe concurrently to avoid blocking the child.
        FdBuffer buffer;
        err = buffer.readFully(dumpPipe.readFd().get());
        // Wait on the child to avoid it becoming a zombie process.
        status_t cStatus = wait_child(child);
        if (err != NO_ERROR) {
            ALOGW("[%s] failed to read stack dump: %d", this->name.c_str(), err);
            dumpPipe.readFd().reset();
            break;
        }
        if (cStatus != NO_ERROR) {
            ALOGE("[%s] child had an issue: %s\n", this->name.c_str(), strerror(-cStatus));
        }

        // Resize dump buffer
        if (dumpBufferSize < buffer.size()) {
            munmap(dumpBuffer, dumpBufferSize);
            while(dumpBufferSize < buffer.size()) dumpBufferSize = dumpBufferSize << 1;
            dumpBuffer = (char*)mmap(NULL, dumpBufferSize, PROT_READ | PROT_WRITE,
                    MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
        }
        sp<ProtoReader> reader = buffer.data()->read();
        int i = 0;
        while (reader->hasNext()) {
            dumpBuffer[i] = reader->next();
            i++;
        }
        uint64_t token = proto.start(android::os::BackTraceProto::TRACES);
        proto.write(android::os::BackTraceProto::Stack::PID, pid);
        proto.write(android::os::BackTraceProto::Stack::DUMP, dumpBuffer, i);
        proto.write(android::os::BackTraceProto::Stack::DUMP_DURATION_NS,
                    static_cast<long long>(Nanotime() - start));
        proto.end(token);
        dumpPipe.readFd().reset();
        if (!proto.flush(pipeWriteFd.get())) {
            if (errno == EPIPE) {
                ALOGE("[%s] wrote to a broken pipe\n", this->name.c_str());
            }
            err = errno;
            break;
        }
        proto.clear();
    }
    munmap(dumpBuffer, dumpBufferSize);
    return_buffer_to_pool(pooledBuffer);
    return err;
}

// ================================================================================
BringYourOwnSection::BringYourOwnSection(int id, const char* customName, const uid_t callingUid,
        const sp<IIncidentDumpCallback>& callback)
    : WorkerThreadSection(id, REMOTE_CALL_TIMEOUT_MS), uid(callingUid), mCallback(callback) {
    name = "registered ";
    name += customName;
}

BringYourOwnSection::~BringYourOwnSection() {}

status_t BringYourOwnSection::BlockingCall(unique_fd& pipeWriteFd) const {
    android::os::ParcelFileDescriptor pfd(std::move(pipeWriteFd));
    if(mCallback != nullptr) {
        mCallback->onDumpSection(pfd);
    }
    return NO_ERROR;
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
