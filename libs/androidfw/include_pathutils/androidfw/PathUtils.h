/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include <utils/String8.h>

/* This library contains path manipulation functions that are used only by androidfw and aapt.
 * When it's possible, migrate all uses to std::filesystem::path.
 */

namespace android {

/**
 * Get just the filename component.
 *
 * DEPRECATED: use std::filesystem::path::filename
 *
 * "/tmp/foo/bar.c" --> "bar.c"
 */
String8 getPathLeaf(const String8& str);

/**
 * Remove the last (file name) component, leaving just the directory
 * name.
 *
 * DEPRECATED: use std::filesystem::path::parent_path
 *
 * "/tmp/foo/bar.c" --> "/tmp/foo"
 * "/tmp" --> "" // ????? shouldn't this be "/" ???? XXX
 * "bar.c" --> ""
 */
String8 getPathDir(const String8& str);

/**
 * Return the filename extension.  This is the last '.' and any number
 * of characters that follow it.  The '.' is included in case we
 * decide to expand our definition of what constitutes an extension.
 *
 * DEPRECATED: use std::filesystem::path::extension
 *
 * "/tmp/foo/bar.c" --> ".c"
 * "/tmp" --> ""
 * "/tmp/foo.bar/baz" --> ""
 * "foo.jpeg" --> ".jpeg"
 * "foo." --> ""
 */
String8 getPathExtension(const String8& str);

/**
 * Return the path without the extension.  Rules for what constitutes
 * an extension are described in the comment for getPathExtension().
 *
 * DEPRECATED: use std::filesystem::path::stem and std::filesystem::path::parent_path
 *
 * "/tmp/foo/bar.c" --> "/tmp/foo/bar"
 */
String8 getBasePath(const String8& str);

/**
 * Add a component to the pathname.  We guarantee that there is
 * exactly one path separator between the old path and the new.
 * If there is no existing name, we just copy the new name in.
 *
 * DEPRECATED: use std::filesystem::path::operator/=
 *
 * If leaf is a fully qualified path (i.e. starts with '/', it
 * replaces whatever was there before.
 */
String8& appendPath(String8& str, const char* leaf);
inline String8& appendPath(String8& str, const String8& leaf) {
    return appendPath(str, leaf.c_str());
}

/**
 * Like appendPath(), but does not affect this string.  Returns a new one instead.
 *
 * DEPRECATED: use std::filesystem::operator/
 */
inline String8 appendPathCopy(String8 str, const char* leaf) { return appendPath(str, leaf); }
inline String8 appendPathCopy(String8 str, const String8& leaf) {
    return appendPath(str, leaf.c_str());
}

} // namespace android
