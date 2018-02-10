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

#define LOG_TAG "incidentd"

#include "Section.h"

#include <errno.h>
#include <sys/prctl.h>
#include <unistd.h>
#include <wait.h>

#include <memory>
#include <mutex>

#include <android/util/protobuf.h>
#include <binder/IServiceManager.h>
#include <log/log_event_list.h>
#include <log/logprint.h>
#include <log/log_read.h>
#include <private/android_logger.h>

#include "FdBuffer.h"
#include "frameworks/base/core/proto/android/util/log.proto.h"
#include "io_util.h"
#include "Privacy.h"
#include "PrivacyBuffer.h"
#include "section_list.h"

using namespace android::util;
using namespace std;

// special section ids
const int FIELD_ID_INCIDENT_HEADER = 1;
const int FIELD_ID_INCIDENT_METADATA = 2;

// incident section parameters
const int   WAIT_MAX = 5;
const struct timespec WAIT_INTERVAL_NS = {0, 200 * 1000 * 1000};
const char INCIDENT_HELPER[] = "/system/bin/incident_helper";

static pid_t
fork_execute_incident_helper(const int id, const char* name, Fpipe& p2cPipe, Fpipe& c2pPipe)
{
    const char* ihArgs[] { INCIDENT_HELPER, "-s", String8::format("%d", id).string(), NULL };
    // fork used in multithreaded environment, avoid adding unnecessary code in child process
    pid_t pid = fork();
    if (pid == 0) {
        if (TEMP_FAILURE_RETRY(dup2(p2cPipe.readFd(),  STDIN_FILENO))  != 0
            || !p2cPipe.close()
            || TEMP_FAILURE_RETRY(dup2(c2pPipe.writeFd(), STDOUT_FILENO)) != 1
            || !c2pPipe.close()) {
            ALOGW("%s can't setup stdin and stdout for incident helper", name);
            _exit(EXIT_FAILURE);
        }

        /* make sure the child dies when incidentd dies */
        prctl(PR_SET_PDEATHSIG, SIGKILL);

        execv(INCIDENT_HELPER, const_cast<char**>(ihArgs));

        ALOGW("%s failed in incident helper process: %s", name, strerror(errno));
        _exit(EXIT_FAILURE); // always exits with failure if any
    }
    // close the fds used in incident helper
    close(p2cPipe.readFd());
    close(c2pPipe.writeFd());
    return pid;
}

// ================================================================================
static status_t statusCode(int status) {
    if (WIFSIGNALED(status)) {
      ALOGD("return by signal: %s", strerror(WTERMSIG(status)));
      return -WTERMSIG(status);
    } else if (WIFEXITED(status) && WEXITSTATUS(status) > 0) {
      ALOGD("return by exit: %s", strerror(WEXITSTATUS(status)));
      return -WEXITSTATUS(status);
    }
    return NO_ERROR;
}

static status_t kill_child(pid_t pid) {
    int status;
    ALOGD("try to kill child process %d", pid);
    kill(pid, SIGKILL);
    if (waitpid(pid, &status, 0) == -1) return -1;
    return statusCode(status);
}

static status_t wait_child(pid_t pid) {
    int status;
    bool died = false;
    // wait for child to report status up to 1 seconds
    for(int loop = 0; !died && loop < WAIT_MAX; loop++) {
        if (waitpid(pid, &status, WNOHANG) == pid) died = true;
        // sleep for 0.2 second
        nanosleep(&WAIT_INTERVAL_NS, NULL);
    }
    if (!died) return kill_child(pid);
    return statusCode(status);
}
// ================================================================================
static const Privacy*
get_privacy_of_section(int id)
{
    int l = 0;
    int r = PRIVACY_POLICY_COUNT - 1;
    while (l <= r) {
        int mid = (l + r) >> 1;
        const Privacy* p = PRIVACY_POLICY_LIST[mid];

        if (p->field_id < (uint32_t)id) {
            l = mid + 1;
        } else if (p->field_id > (uint32_t)id) {
            r = mid - 1;
        } else {
            return p;
        }
    }
    return NULL;
}

// ================================================================================
static status_t
write_section_header(int fd, int sectionId, size_t size)
{
    uint8_t buf[20];
    uint8_t *p = write_length_delimited_tag_header(buf, sectionId, size);
    return write_all(fd, buf, p-buf);
}

static status_t
write_report_requests(const int id, const FdBuffer& buffer, ReportRequestSet* requests)
{
    status_t err = -EBADF;
    EncodedBuffer::iterator data = buffer.data();
    PrivacyBuffer privacyBuffer(get_privacy_of_section(id), data);
    int writeable = 0;
    auto stats = requests->sectionStats(id);

    stats->set_dump_size_bytes(data.size());
    stats->set_dump_duration_ms(buffer.durationMs());
    stats->set_timed_out(buffer.timedOut());
    stats->set_is_truncated(buffer.truncated());

    // The streaming ones, group requests by spec in order to save unnecessary strip operations
    map<PrivacySpec, vector<sp<ReportRequest>>> requestsBySpec;
    for (auto it = requests->begin(); it != requests->end(); it++) {
        sp<ReportRequest> request = *it;
        if (!request->ok() || !request->args.containsSection(id)) {
            continue;  // skip invalid request
        }
        PrivacySpec spec = PrivacySpec::new_spec(request->args.dest());
        requestsBySpec[spec].push_back(request);
    }

    for (auto mit = requestsBySpec.begin(); mit != requestsBySpec.end(); mit++) {
        PrivacySpec spec = mit->first;
        err = privacyBuffer.strip(spec);
        if (err != NO_ERROR) return err; // it means the privacyBuffer data is corrupted.
        if (privacyBuffer.size() == 0) continue;

        for (auto it = mit->second.begin(); it != mit->second.end(); it++) {
            sp<ReportRequest> request = *it;
            err = write_section_header(request->fd, id, privacyBuffer.size());
            if (err != NO_ERROR) { request->err = err; continue; }
            err = privacyBuffer.flush(request->fd);
            if (err != NO_ERROR) { request->err = err; continue; }
            writeable++;
            ALOGD("Section %d flushed %zu bytes to fd %d with spec %d", id,
                  privacyBuffer.size(), request->fd, spec.dest);
        }
        privacyBuffer.clear();
    }

    // The dropbox file
    if (requests->mainFd() >= 0) {
        PrivacySpec spec = PrivacySpec::new_spec(requests->mainDest());
        err = privacyBuffer.strip(spec);
        if (err != NO_ERROR) return err; // the buffer data is corrupted.
        if (privacyBuffer.size() == 0) goto DONE;

        err = write_section_header(requests->mainFd(), id, privacyBuffer.size());
        if (err != NO_ERROR) { requests->setMainFd(-1); goto DONE; }
        err = privacyBuffer.flush(requests->mainFd());
        if (err != NO_ERROR) { requests->setMainFd(-1); goto DONE; }
        writeable++;
        ALOGD("Section %d flushed %zu bytes to dropbox %d with spec %d", id,
              privacyBuffer.size(), requests->mainFd(), spec.dest);
        stats->set_report_size_bytes(privacyBuffer.size());
    }

DONE:
    // only returns error if there is no fd to write to.
    return writeable > 0 ? NO_ERROR : err;
}

// ================================================================================
Section::Section(int i, const int64_t timeoutMs)
    :id(i),
     timeoutMs(timeoutMs)
{
}

Section::~Section()
{
}

// ================================================================================
HeaderSection::HeaderSection()
    :Section(FIELD_ID_INCIDENT_HEADER, 0)
{
}

HeaderSection::~HeaderSection()
{
}

status_t
HeaderSection::Execute(ReportRequestSet* requests) const
{
    for (ReportRequestSet::iterator it=requests->begin(); it!=requests->end(); it++) {
        const sp<ReportRequest> request = *it;
        const vector<vector<uint8_t>>& headers = request->args.headers();

        for (vector<vector<uint8_t>>::const_iterator buf=headers.begin(); buf!=headers.end(); buf++) {
            if (buf->empty()) continue;

            // So the idea is only requests with negative fd are written to dropbox file.
            int fd = request->fd >= 0 ? request->fd : requests->mainFd();
            write_section_header(fd, id, buf->size());
            write_all(fd, (uint8_t const*)buf->data(), buf->size());
            // If there was an error now, there will be an error later and we will remove
            // it from the list then.
        }
    }
    return NO_ERROR;
}
// ================================================================================
MetadataSection::MetadataSection()
    :Section(FIELD_ID_INCIDENT_METADATA, 0)
{
}

MetadataSection::~MetadataSection()
{
}

status_t
MetadataSection::Execute(ReportRequestSet* requests) const
{
    std::string metadataBuf;
    requests->metadata().SerializeToString(&metadataBuf);
    for (ReportRequestSet::iterator it=requests->begin(); it!=requests->end(); it++) {
        const sp<ReportRequest> request = *it;
        if (metadataBuf.empty() || request->fd < 0 || request->err != NO_ERROR) {
            continue;
        }
        write_section_header(request->fd, id, metadataBuf.size());
        write_all(request->fd, (uint8_t const*)metadataBuf.data(), metadataBuf.size());
    }
    if (requests->mainFd() >= 0 && !metadataBuf.empty()) {
        write_section_header(requests->mainFd(), id, metadataBuf.size());
        write_all(requests->mainFd(), (uint8_t const*)metadataBuf.data(), metadataBuf.size());
    }
    return NO_ERROR;
}
// ================================================================================
FileSection::FileSection(int id, const char* filename, const int64_t timeoutMs)
    :Section(id, timeoutMs),
     mFilename(filename)
{
    name = filename;
    mIsSysfs = strncmp(filename, "/sys/", 5) == 0;
}

FileSection::~FileSection() {}

status_t
FileSection::Execute(ReportRequestSet* requests) const
{
    // read from mFilename first, make sure the file is available
    // add O_CLOEXEC to make sure it is closed when exec incident helper
    int fd = open(mFilename, O_RDONLY | O_CLOEXEC);
    if (fd == -1) {
       ALOGW("FileSection '%s' failed to open file", this->name.string());
       return -errno;
    }

    FdBuffer buffer;
    Fpipe p2cPipe;
    Fpipe c2pPipe;
    // initiate pipes to pass data to/from incident_helper
    if (!p2cPipe.init() || !c2pPipe.init()) {
        ALOGW("FileSection '%s' failed to setup pipes", this->name.string());
        return -errno;
    }

    pid_t pid = fork_execute_incident_helper(this->id, this->name.string(), p2cPipe, c2pPipe);
    if (pid == -1) {
        ALOGW("FileSection '%s' failed to fork", this->name.string());
        return -errno;
    }

    // parent process
    status_t readStatus = buffer.readProcessedDataInStream(fd, p2cPipe.writeFd(), c2pPipe.readFd(),
            this->timeoutMs, mIsSysfs);
    if (readStatus != NO_ERROR || buffer.timedOut()) {
        ALOGW("FileSection '%s' failed to read data from incident helper: %s, timedout: %s",
            this->name.string(), strerror(-readStatus), buffer.timedOut() ? "true" : "false");
        kill_child(pid);
        return readStatus;
    }

    status_t ihStatus = wait_child(pid);
    if (ihStatus != NO_ERROR) {
        ALOGW("FileSection '%s' abnormal child process: %s", this->name.string(), strerror(-ihStatus));
        return ihStatus;
    }

    ALOGD("FileSection '%s' wrote %zd bytes in %d ms", this->name.string(), buffer.size(),
            (int)buffer.durationMs());
    status_t err = write_report_requests(this->id, buffer, requests);
    if (err != NO_ERROR) {
        ALOGW("FileSection '%s' failed writing: %s", this->name.string(), strerror(-err));
        return err;
    }

    return NO_ERROR;
}

// ================================================================================
struct WorkerThreadData : public virtual RefBase
{
    const WorkerThreadSection* section;
    int fds[2];

    // Lock protects these fields
    mutex lock;
    bool workerDone;
    status_t workerError;

    WorkerThreadData(const WorkerThreadSection* section);
    virtual ~WorkerThreadData();

    int readFd() { return fds[0]; }
    int writeFd() { return fds[1]; }
};

WorkerThreadData::WorkerThreadData(const WorkerThreadSection* sec)
    :section(sec),
     workerDone(false),
     workerError(NO_ERROR)
{
    fds[0] = -1;
    fds[1] = -1;
}

WorkerThreadData::~WorkerThreadData()
{
}

// ================================================================================
WorkerThreadSection::WorkerThreadSection(int id)
    :Section(id)
{
}

WorkerThreadSection::~WorkerThreadSection()
{
}

static void*
worker_thread_func(void* cookie)
{
    WorkerThreadData* data = (WorkerThreadData*)cookie;
    status_t err = data->section->BlockingCall(data->writeFd());

    {
        unique_lock<mutex> lock(data->lock);
        data->workerDone = true;
        data->workerError = err;
    }

    close(data->writeFd());
    data->decStrong(data->section);
    // data might be gone now. don't use it after this point in this thread.
    return NULL;
}

status_t
WorkerThreadSection::Execute(ReportRequestSet* requests) const
{
    status_t err = NO_ERROR;
    pthread_t thread;
    pthread_attr_t attr;
    bool timedOut = false;
    FdBuffer buffer;

    // Data shared between this thread and the worker thread.
    sp<WorkerThreadData> data = new WorkerThreadData(this);

    // Create the pipe
    err = pipe(data->fds);
    if (err != 0) {
        return -errno;
    }

    // The worker thread needs a reference and we can't let the count go to zero
    // if that thread is slow to start.
    data->incStrong(this);

    // Create the thread
    err = pthread_attr_init(&attr);
    if (err != 0) {
        return -err;
    }
    // TODO: Do we need to tweak thread priority?
    err = pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (err != 0) {
        pthread_attr_destroy(&attr);
        return -err;
    }
    err = pthread_create(&thread, &attr, worker_thread_func, (void*)data.get());
    if (err != 0) {
        pthread_attr_destroy(&attr);
        return -err;
    }
    pthread_attr_destroy(&attr);

    // Loop reading until either the timeout or the worker side is done (i.e. eof).
    err = buffer.read(data->readFd(), this->timeoutMs);
    if (err != NO_ERROR) {
        // TODO: Log this error into the incident report.
        ALOGW("WorkerThreadSection '%s' reader failed with error '%s'", this->name.string(),
                strerror(-err));
    }

    // Done with the read fd. The worker thread closes the write one so
    // we never race and get here first.
    close(data->readFd());

    // If the worker side is finished, then return its error (which may overwrite
    // our possible error -- but it's more interesting anyway).  If not, then we timed out.
    {
        unique_lock<mutex> lock(data->lock);
        if (!data->workerDone) {
            // We timed out
            timedOut = true;
        } else {
            if (data->workerError != NO_ERROR) {
                err = data->workerError;
                // TODO: Log this error into the incident report.
                ALOGW("WorkerThreadSection '%s' worker failed with error '%s'", this->name.string(),
                        strerror(-err));
            }
        }
    }

    if (timedOut || buffer.timedOut()) {
        ALOGW("WorkerThreadSection '%s' timed out", this->name.string());
        return NO_ERROR;
    }

    if (buffer.truncated()) {
        // TODO: Log this into the incident report.
    }

    // TODO: There was an error with the command or buffering. Report that.  For now
    // just exit with a log messasge.
    if (err != NO_ERROR) {
        ALOGW("WorkerThreadSection '%s' failed with error '%s'", this->name.string(),
                strerror(-err));
        return NO_ERROR;
    }

    // Write the data that was collected
    ALOGD("WorkerThreadSection '%s' wrote %zd bytes in %d ms", name.string(), buffer.size(),
            (int)buffer.durationMs());
    err = write_report_requests(this->id, buffer, requests);
    if (err != NO_ERROR) {
        ALOGW("WorkerThreadSection '%s' failed writing: '%s'", this->name.string(), strerror(-err));
        return err;
    }

    return NO_ERROR;
}

// ================================================================================
void
CommandSection::init(const char* command, va_list args)
{
    va_list copied_args;
    int numOfArgs = 0;

    va_copy(copied_args, args);
    while(va_arg(copied_args, const char*) != NULL) {
        numOfArgs++;
    }
    va_end(copied_args);

    // allocate extra 1 for command and 1 for NULL terminator
    mCommand = (const char**)malloc(sizeof(const char*) * (numOfArgs + 2));

    mCommand[0] = command;
    name = command;
    for (int i=0; i<numOfArgs; i++) {
        const char* arg = va_arg(args, const char*);
        mCommand[i+1] = arg;
        name += " ";
        name += arg;
    }
    mCommand[numOfArgs+1] = NULL;
}

CommandSection::CommandSection(int id, const int64_t timeoutMs, const char* command, ...)
    :Section(id, timeoutMs)
{
    va_list args;
    va_start(args, command);
    init(command, args);
    va_end(args);
}

CommandSection::CommandSection(int id, const char* command, ...)
    :Section(id)
{
    va_list args;
    va_start(args, command);
    init(command, args);
    va_end(args);
}

CommandSection::~CommandSection()
{
    free(mCommand);
}

status_t
CommandSection::Execute(ReportRequestSet* requests) const
{
    FdBuffer buffer;
    Fpipe cmdPipe;
    Fpipe ihPipe;

    if (!cmdPipe.init() || !ihPipe.init()) {
        ALOGW("CommandSection '%s' failed to setup pipes", this->name.string());
        return -errno;
    }

    pid_t cmdPid = fork();
    if (cmdPid == -1) {
        ALOGW("CommandSection '%s' failed to fork", this->name.string());
        return -errno;
    }
    // child process to execute the command as root
    if (cmdPid == 0) {
        // replace command's stdout with ihPipe's write Fd
        if (dup2(cmdPipe.writeFd(), STDOUT_FILENO) != 1 || !ihPipe.close() || !cmdPipe.close()) {
            ALOGW("CommandSection '%s' failed to set up stdout: %s", this->name.string(), strerror(errno));
            _exit(EXIT_FAILURE);
        }
        execvp(this->mCommand[0], (char *const *) this->mCommand);
        int err = errno; // record command error code
        ALOGW("CommandSection '%s' failed in executing command: %s", this->name.string(), strerror(errno));
        _exit(err); // exit with command error code
    }
    pid_t ihPid = fork_execute_incident_helper(this->id, this->name.string(), cmdPipe, ihPipe);
    if (ihPid == -1) {
        ALOGW("CommandSection '%s' failed to fork", this->name.string());
        return -errno;
    }

    close(cmdPipe.writeFd());
    status_t readStatus = buffer.read(ihPipe.readFd(), this->timeoutMs);
    if (readStatus != NO_ERROR || buffer.timedOut()) {
        ALOGW("CommandSection '%s' failed to read data from incident helper: %s, timedout: %s",
            this->name.string(), strerror(-readStatus), buffer.timedOut() ? "true" : "false");
        kill_child(cmdPid);
        kill_child(ihPid);
        return readStatus;
    }

    // TODO: wait for command here has one trade-off: the failed status of command won't be detected until
    //       buffer timeout, but it has advatage on starting the data stream earlier.
    status_t cmdStatus = wait_child(cmdPid);
    status_t ihStatus  = wait_child(ihPid);
    if (cmdStatus != NO_ERROR || ihStatus != NO_ERROR) {
        ALOGW("CommandSection '%s' abnormal child processes, return status: command: %s, incident helper: %s",
            this->name.string(), strerror(-cmdStatus), strerror(-ihStatus));
        return cmdStatus != NO_ERROR ? cmdStatus : ihStatus;
    }

    ALOGD("CommandSection '%s' wrote %zd bytes in %d ms", this->name.string(), buffer.size(),
            (int)buffer.durationMs());
    status_t err = write_report_requests(this->id, buffer, requests);
    if (err != NO_ERROR) {
        ALOGW("CommandSection '%s' failed writing: %s", this->name.string(), strerror(-err));
        return err;
    }
    return NO_ERROR;
}

// ================================================================================
DumpsysSection::DumpsysSection(int id, const char* service, ...)
    :WorkerThreadSection(id),
     mService(service)
{
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

DumpsysSection::~DumpsysSection()
{
}

status_t
DumpsysSection::BlockingCall(int pipeWriteFd) const
{
    // checkService won't wait for the service to show up like getService will.
    sp<IBinder> service = defaultServiceManager()->checkService(mService);

    if (service == NULL) {
        // Returning an error interrupts the entire incident report, so just
        // log the failure.
        // TODO: have a meta record inside the report that would log this
        // failure inside the report, because the fact that we can't find
        // the service is good data in and of itself. This is running in
        // another thread so lock that carefully...
        ALOGW("DumpsysSection: Can't lookup service: %s", String8(mService).string());
        return NO_ERROR;
    }

    service->dump(pipeWriteFd, mArgs);

    return NO_ERROR;
}

// ================================================================================
// initialization only once in Section.cpp.
map<log_id_t, log_time> LogSection::gLastLogsRetrieved;

LogSection::LogSection(int id, log_id_t logID)
    :WorkerThreadSection(id),
     mLogID(logID)
{
    name += "logcat ";
    name += android_log_id_to_name(logID);
    switch (logID) {
    case LOG_ID_EVENTS:
    case LOG_ID_STATS:
    case LOG_ID_SECURITY:
        mBinary = true;
        break;
    default:
        mBinary = false;
    }
}

LogSection::~LogSection()
{
}

static size_t
trimTail(char const* buf, size_t len)
{
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

status_t
LogSection::BlockingCall(int pipeWriteFd) const
{
    status_t err = NO_ERROR;
    // Open log buffer and getting logs since last retrieved time if any.
    unique_ptr<logger_list, void (*)(logger_list*)> loggers(
        gLastLogsRetrieved.find(mLogID) == gLastLogsRetrieved.end() ?
        android_logger_list_alloc(ANDROID_LOG_RDONLY | ANDROID_LOG_NONBLOCK, 0, 0) :
        android_logger_list_alloc_time(ANDROID_LOG_RDONLY | ANDROID_LOG_NONBLOCK,
            gLastLogsRetrieved[mLogID], 0),
        android_logger_list_free);

    if (android_logger_open(loggers.get(), mLogID) == NULL) {
        ALOGW("LogSection %s: Can't get logger.", this->name.string());
        return err;
    }

    log_msg msg;
    log_time lastTimestamp(0);

    ProtoOutputStream proto;
    while (true) { // keeps reading until logd buffer is fully read.
        status_t err = android_logger_list_read(loggers.get(), &msg);
        // err = 0 - no content, unexpected connection drop or EOF.
        // err = +ive number - size of retrieved data from logger
        // err = -ive number, OS supplied error _except_ for -EAGAIN
        // err = -EAGAIN, graceful indication for ANDRODI_LOG_NONBLOCK that this is the end of data.
        if (err <= 0) {
            if (err != -EAGAIN) {
                ALOGE("LogSection %s: fails to read a log_msg.\n", this->name.string());
            }
            break;
        }
        if (mBinary) {
            // remove the first uint32 which is tag's index in event log tags
            android_log_context context = create_android_log_parser(msg.msg() + sizeof(uint32_t),
                    msg.len() - sizeof(uint32_t));;
            android_log_list_element elem;

            lastTimestamp.tv_sec = msg.entry_v1.sec;
            lastTimestamp.tv_nsec = msg.entry_v1.nsec;

            // format a BinaryLogEntry
            long long token = proto.start(LogProto::BINARY_LOGS);
            proto.write(BinaryLogEntry::SEC, msg.entry_v1.sec);
            proto.write(BinaryLogEntry::NANOSEC, msg.entry_v1.nsec);
            proto.write(BinaryLogEntry::UID, (int) msg.entry_v4.uid);
            proto.write(BinaryLogEntry::PID, msg.entry_v1.pid);
            proto.write(BinaryLogEntry::TID, msg.entry_v1.tid);
            proto.write(BinaryLogEntry::TAG_INDEX, get4LE(reinterpret_cast<uint8_t const*>(msg.msg())));
            do {
                elem = android_log_read_next(context);
                long long elemToken = proto.start(BinaryLogEntry::ELEMS);
                switch (elem.type) {
                    case EVENT_TYPE_INT:
                        proto.write(BinaryLogEntry::Elem::TYPE, BinaryLogEntry::Elem::EVENT_TYPE_INT);
                        proto.write(BinaryLogEntry::Elem::VAL_INT32, (int) elem.data.int32);
                        break;
                    case EVENT_TYPE_LONG:
                        proto.write(BinaryLogEntry::Elem::TYPE, BinaryLogEntry::Elem::EVENT_TYPE_LONG);
                        proto.write(BinaryLogEntry::Elem::VAL_INT64, (long long) elem.data.int64);
                        break;
                    case EVENT_TYPE_STRING:
                        proto.write(BinaryLogEntry::Elem::TYPE, BinaryLogEntry::Elem::EVENT_TYPE_STRING);
                        proto.write(BinaryLogEntry::Elem::VAL_STRING, elem.data.string, elem.len);
                        break;
                    case EVENT_TYPE_FLOAT:
                        proto.write(BinaryLogEntry::Elem::TYPE, BinaryLogEntry::Elem::EVENT_TYPE_FLOAT);
                        proto.write(BinaryLogEntry::Elem::VAL_FLOAT, elem.data.float32);
                        break;
                    case EVENT_TYPE_LIST:
                        proto.write(BinaryLogEntry::Elem::TYPE, BinaryLogEntry::Elem::EVENT_TYPE_LIST);
                        break;
                    case EVENT_TYPE_LIST_STOP:
                        proto.write(BinaryLogEntry::Elem::TYPE, BinaryLogEntry::Elem::EVENT_TYPE_LIST_STOP);
                        break;
                    case EVENT_TYPE_UNKNOWN:
                        proto.write(BinaryLogEntry::Elem::TYPE, BinaryLogEntry::Elem::EVENT_TYPE_UNKNOWN);
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
            err = android_log_processLogBuffer(&msg.entry_v1, &entry);
            if (err != NO_ERROR) {
                ALOGE("LogSection %s: fails to process to an entry.\n", this->name.string());
                break;
            }
            lastTimestamp.tv_sec = entry.tv_sec;
            lastTimestamp.tv_nsec = entry.tv_nsec;

            // format a TextLogEntry
            long long token = proto.start(LogProto::TEXT_LOGS);
            proto.write(TextLogEntry::SEC, (long long)entry.tv_sec);
            proto.write(TextLogEntry::NANOSEC, (long long)entry.tv_nsec);
            proto.write(TextLogEntry::PRIORITY, (int)entry.priority);
            proto.write(TextLogEntry::UID, entry.uid);
            proto.write(TextLogEntry::PID, entry.pid);
            proto.write(TextLogEntry::TID, entry.tid);
            proto.write(TextLogEntry::TAG, entry.tag, trimTail(entry.tag, entry.tagLen));
            proto.write(TextLogEntry::LOG, entry.message, trimTail(entry.message, entry.messageLen));
            proto.end(token);
        }
    }
    gLastLogsRetrieved[mLogID] = lastTimestamp;
    proto.flush(pipeWriteFd);
    return err;
}
