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

#include <androidfw/PathUtils.h>

#include <utils/Compat.h>

namespace android {

String8 getPathLeaf(const String8& str) {
    const char* cp;
    const char*const buf = str.c_str();

    cp = strrchr(buf, OS_PATH_SEPARATOR);
    if (cp == nullptr)
        return str;
    else
        return String8(cp+1);
}

String8 getPathDir(const String8& str8) {
    const char* cp;
    const char*const str = str8.c_str();

    cp = strrchr(str, OS_PATH_SEPARATOR);
    if (cp == nullptr)
        return String8();
    else
        return String8(str, cp - str);
}

static char* findExtension(const String8& str8) {
    const char* lastSlash;
    const char* lastDot;
    const char* const str = str8.c_str();

    // only look at the filename
    lastSlash = strrchr(str, OS_PATH_SEPARATOR);
    if (lastSlash == nullptr)
        lastSlash = str;
    else
        lastSlash++;

    // find the last dot
    lastDot = strrchr(lastSlash, '.');
    if (lastDot == nullptr)
        return nullptr;

    // looks good, ship it
    return const_cast<char*>(lastDot);
}

String8 getPathExtension(const String8& str) {
    char* ext;

    ext = findExtension(str);
    if (ext != nullptr)
        return String8(ext);
    else
        return String8();
}

String8 getBasePath(const String8& str8) {
    char* ext;
    const char* const str = str8.c_str();

    ext = findExtension(str8);
    if (ext == nullptr)
        return str8;
    else
        return String8(str, ext - str);
}

static void setPathName(String8& s, const char* name) {
    size_t len = strlen(name);
    char* buf = s.lockBuffer(len);

    memcpy(buf, name, len);

    // remove trailing path separator, if present
    if (len > 0 && buf[len - 1] == OS_PATH_SEPARATOR) len--;
    buf[len] = '\0';

    s.unlockBuffer(len);
}

String8& appendPath(String8& str, const char* name) {
    // TODO: The test below will fail for Win32 paths. Fix later or ignore.
    if (name[0] != OS_PATH_SEPARATOR) {
        if (*name == '\0') {
            // nothing to do
            return str;
        }

        size_t len = str.length();
        if (len == 0) {
            // no existing filename, just use the new one
            setPathName(str, name);
            return str;
        }

        // make room for oldPath + '/' + newPath
        int newlen = strlen(name);

        char* buf = str.lockBuffer(len+1+newlen);

        // insert a '/' if needed
        if (buf[len-1] != OS_PATH_SEPARATOR)
            buf[len++] = OS_PATH_SEPARATOR;

        memcpy(buf+len, name, newlen+1);
        len += newlen;

        str.unlockBuffer(len);
        return str;
    } else {
        setPathName(str, name);
        return str;
    }
}

} // namespace android
