/*
 * Copyright (C) 2005 The Android Open Source Project
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

#pragma once

// This file contains cruft that used to be in libutils' String8, that's only
// used for aapt.

#include <utils/String8.h>

// Converts all separators in this string to /, the default path
// separator.
// If the default OS separator is backslash, this converts all
// backslashes to slashes, in-place. Otherwise it does nothing.
void convertToResPath(android::String8&);

/**
 * Retrieve the front (root dir) component.  Optionally also return the
 * remaining components.
 *
 * "/tmp/foo/bar.c" --> "tmp" (remain = "foo/bar.c")
 * "/tmp" --> "tmp" (remain = "")
 * "bar.c" --> "bar.c" (remain = "")
 */
android::String8 walkPath(const android::String8& path, android::String8* outRemains = nullptr);
