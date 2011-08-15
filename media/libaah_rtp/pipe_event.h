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

#ifndef __PIPE_EVENT_H__
#define __PIPE_EVENT_H__

namespace android {

class PipeEvent {
  public:
    PipeEvent();
   ~PipeEvent();

    bool initCheck() const {
        return ((pipe_[0] >= 0) && (pipe_[1] >= 0));
    }

    int getWakeupHandle() const { return pipe_[0]; }

    // block until the event fires; returns true if the event fired and false if
    // the wait timed out.  Timeout is expressed in milliseconds; negative
    // values mean wait forever.
    bool wait(int timeout = -1);

    void clearPendingEvents();
    void setEvent();

  private:
    int pipe_[2];
};

}  // namespace android

#endif  // __PIPE_EVENT_H__
