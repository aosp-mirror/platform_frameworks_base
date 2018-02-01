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
#include "incidentd_util.h"

#include "section_list.h"

const Privacy* get_privacy_of_section(int id) {
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
Fpipe::Fpipe() : mRead(), mWrite() {}

Fpipe::~Fpipe() { close(); }

bool Fpipe::close() {
    mRead.reset();
    mWrite.reset();
    return true;
}

bool Fpipe::init() { return Pipe(&mRead, &mWrite); }

int Fpipe::readFd() const { return mRead.get(); }

int Fpipe::writeFd() const { return mWrite.get(); }