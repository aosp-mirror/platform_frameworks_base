/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef __OUTPUT_SET_H
#define __OUTPUT_SET_H

#include <set>
#include <utils/Errors.h>
#include <utils/String8.h>
#include <utils/StrongPointer.h>

class AaptFile;

class OutputEntry {
public:
    OutputEntry() {}
    OutputEntry(const android::String8& path, const android::sp<const AaptFile>& file)
        : mPath(path), mFile(file) {}

    inline const android::sp<const AaptFile>& getFile() const {
        return mFile;
    }

    inline const android::String8& getPath() const {
        return mPath;
    }

    bool operator<(const OutputEntry& o) const { return getPath() < o.mPath; }
    bool operator==(const OutputEntry& o) const { return getPath() == o.mPath; }

private:
    android::String8 mPath;
    android::sp<const AaptFile> mFile;
};

class OutputSet : public virtual android::RefBase {
public:
    virtual const std::set<OutputEntry>& getEntries() const = 0;

    virtual ~OutputSet() {}
};

#endif // __OUTPUT_SET_H
