/*
 * Copyright (C) 2018 The Android Open Source Project
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

#define ATRACE_TAG ATRACE_TAG_RESOURCES

#include <binder/BinderService.h>
#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>

#include <cstdlib>  // EXIT_{FAILURE,SUCCESS}
#include <iostream>
#include <sstream>

#include "Idmap2Service.h"
#include "android-base/macros.h"

using android::BinderService;
using android::IPCThreadState;
using android::ProcessState;
using android::sp;
using android::status_t;
using android::os::Idmap2Service;

int main(int argc ATTRIBUTE_UNUSED, char** argv ATTRIBUTE_UNUSED) {
  IPCThreadState::disableBackgroundScheduling(true);
  status_t ret = BinderService<Idmap2Service>::publish();
  if (ret != android::OK) {
    return EXIT_FAILURE;
  }
  sp<ProcessState> ps(ProcessState::self());
  ps->startThreadPool();
  ps->giveThreadPoolName();
  IPCThreadState::self()->joinThreadPool();
  return EXIT_SUCCESS;
}
