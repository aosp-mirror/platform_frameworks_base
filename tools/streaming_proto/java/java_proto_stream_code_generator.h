/*
 * Copyright (C) 2024 The Android Open Source Project
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

#ifndef AOSP_MAIN_FRAMEWORKS_BASE_JAVAPROTOSTREAMCODEGENERATOR_H
#define AOSP_MAIN_FRAMEWORKS_BASE_JAVAPROTOSTREAMCODEGENERATOR_H

#include "stream_proto_utils.h"
#include "string_utils.h"

using namespace android::stream_proto;
using namespace google::protobuf::io;
using namespace std;

CodeGeneratorResponse generate_java_protostream_code(CodeGeneratorRequest request);

#endif // AOSP_MAIN_FRAMEWORKS_BASE_JAVAPROTOSTREAMCODEGENERATOR_H