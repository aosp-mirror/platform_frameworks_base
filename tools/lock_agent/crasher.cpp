/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <android-base/logging.h>

// Simple binary that will just crash with the message given as the first parameter.
//
// This is helpful in cases the caller does not want to crash itself, e.g., fork+crash
// instead, as LOG(FATAL) might not be safe (for example in a multi-threaded environment).
int main(int argc, char *argv[]) {
    if (argc != 2) {
        LOG(FATAL) << "Need one argument for abort message";
        __builtin_unreachable();
    }
    LOG(FATAL) << argv[1];
    __builtin_unreachable();
}
