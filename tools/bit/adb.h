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

#ifndef ADB_H
#define ADB_H

#include "proto/instrumentation_data.pb.h"

#include <string>

using namespace android::am;
using namespace google::protobuf;
using namespace std;

class InstrumentationCallbacks {
public:
    virtual void OnTestStatus(TestStatus& status) = 0;
    virtual void OnSessionStatus(SessionStatus& status) = 0;
};

int run_adb(const char* first, ...);

string get_system_property(const string& name, int* err);

int run_instrumentation_test(const string& packageName, const string& runner,
        const string& className, InstrumentationCallbacks* callbacks);

string get_bundle_string(const ResultsBundle& bundle, bool* found, ...);
int32_t get_bundle_int(const ResultsBundle& bundle, bool* found, ...);
float get_bundle_float(const ResultsBundle& bundle, bool* found, ...);
double get_bundle_double(const ResultsBundle& bundle, bool* found, ...);
int64_t get_bundle_long(const ResultsBundle& bundle, bool* found, ...);

#endif // ADB_H
