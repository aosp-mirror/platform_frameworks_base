/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include <android/trace.h>
#include <cutils/trace.h>

bool ATrace_isEnabled() {
    return atrace_is_tag_enabled(ATRACE_TAG_APP);
}

void ATrace_beginSection(const char* sectionName) {
    atrace_begin(ATRACE_TAG_APP, sectionName);
}

void ATrace_endSection() {
    atrace_end(ATRACE_TAG_APP);
}
