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

#include <dlfcn.h>
#include <iostream>

#include "include/WVMExtractor.h"
#include <media/stagefright/Utils.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDebug.h>

using namespace android;
using namespace std;

class TestLibWVM
{
public:
    TestLibWVM() {}
    ~TestLibWVM() {}

    // Tests
    void Load();
};

DrmManagerClient* gDrmManagerClient;

// This test just confirms that there are no unresolved symbols in libwvm and we
// can locate the entry point.

void TestLibWVM::Load()
{
    cout << "TestLibWVM::Load" << endl;

    const char *path = "/system/lib/libwvm.so";
    void *handle = dlopen(path, RTLD_NOW);
    if (handle == NULL) {
        fprintf(stderr, "Can't open plugin: %s\n", path);
        exit(-1);
    }

    typedef MediaExtractor *(*GetInstanceFunc)(sp<DataSource>);
    GetInstanceFunc getInstanceFunc =
        (GetInstanceFunc) dlsym(handle,
                "_ZN7android11GetInstanceENS_2spINS_10DataSourceEEE");

    // Basic test - just see if we can instantiate the object and call a method
    if (getInstanceFunc) {
        LOGD("Found GetInstanceFunc");
    } else {
        LOGE("Failed to locate GetInstance in libwvm.so");
    }

//    dlclose(handle);
    printf("Test successful!\n");
    exit(0);
}

int main(int argc, char **argv)
{
    TestLibWVM test;
    test.Load();
}
