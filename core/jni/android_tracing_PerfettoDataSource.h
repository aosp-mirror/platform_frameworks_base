/*
 * Copyright 2023 The Android Open Source Project
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

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <nativehelper/JNIHelp.h>
#include <perfetto/public/data_source.h>
#include <perfetto/public/producer.h>
#include <perfetto/public/protos/trace/test_event.pzc.h>
#include <perfetto/public/protos/trace/trace_packet.pzc.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include <map>
#include <sstream>
#include <thread>

#include "android_tracing_PerfettoDataSourceInstance.h"
#include "core_jni_helpers.h"

namespace android {

class PerfettoDataSource : public virtual RefBase {
public:
    const std::string dataSourceName;
    struct PerfettoDs dataSource = PERFETTO_DS_INIT();

    PerfettoDataSource(JNIEnv* env, jobject java_data_source, std::string data_source_name);
    ~PerfettoDataSource();

    jobject newInstance(JNIEnv* env, void* ds_config, size_t ds_config_size,
                        PerfettoDsInstanceIndex inst_id);

    bool TraceIterateBegin();
    bool TraceIterateNext();
    void TraceIterateBreak();
    PerfettoDsInstanceIndex GetInstanceIndex();
    void WritePackets(JNIEnv* env, jobjectArray packets);

    jobject GetCustomTls();
    void SetCustomTls(jobject);
    jobject GetIncrementalState();
    void SetIncrementalState(jobject);

    void flushAll();

private:
    jobject mJavaDataSource;
    std::map<PerfettoDsInstanceIndex, PerfettoDataSourceInstance*> mInstances;
};

} // namespace android