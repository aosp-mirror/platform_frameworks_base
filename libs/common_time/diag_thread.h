/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef __DIAG_THREAD_H__
#define __DIAG_THREAD_H__

#include <utils/List.h>
#include <utils/threads.h>

namespace android {

class CommonClock;
class LocalClock;

class DiagThread : public Thread {
  public:
    DiagThread(CommonClock* common_clock, LocalClock* local_clock);
    ~DiagThread();

    status_t      startWorkThread();
    void          stopWorkThread();
    virtual bool  threadLoop();

    void pushDisciplineEvent(int64_t observed_local_time,
                             int64_t observed_common_time,
                             int64_t nominal_common_time,
                             int32_t total_correction,
                             int32_t rtt);

  private:
    typedef struct {
        int64_t event_id;
        int64_t action_local_time;
        int64_t action_common_time;
        int64_t observed_local_time;
        int64_t observed_common_time;
        int64_t nominal_common_time;
        int32_t total_correction;
        int32_t rtt;
    } DisciplineEventRecord;

    bool            openListenSocket();
    void            cleanupListenSocket();
    void            cleanupDataSocket();
    void            resetLogIDs();

    CommonClock*    common_clock_;
    LocalClock*     local_clock_;
    int             listen_fd_;
    int             data_fd_;

    int64_t         kernel_logID_basis_;
    bool            kernel_logID_basis_known_;

    static const size_t         kMaxDisciplineLogSize = 16;
    Mutex                       discipline_log_lock_;
    List<DisciplineEventRecord> discipline_log_;
    int64_t                     discipline_log_ID_;
};

}  // namespace android

#endif  //__ DIAG_THREAD_H__
