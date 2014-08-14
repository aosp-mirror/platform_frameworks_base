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

#ifndef __APK_BUILDER_H
#define __APK_BUILDER_H

#include <set>
#include <utils/Errors.h>
#include <utils/String8.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

#include "ConfigDescription.h"
#include "OutputSet.h"
#include "ResourceFilter.h"

class ApkSplit;
class AaptFile;

class ApkBuilder : public android::RefBase {
public:
    ApkBuilder(const sp<WeakResourceFilter>& configFilter);

    /**
     * Tells the builder to generate a separate APK for resources that
     * match the configurations specified. Split APKs can not have
     * overlapping resources.
     *
     * NOTE: All splits should be set up before any files are added.
     */
    android::status_t createSplitForConfigs(const std::set<ConfigDescription>& configs);

    /**
     * Adds a file to be written to the final APK. It's name must not collide
     * with that of any files previously added. When a Split APK is being
     * generated, duplicates can exist as long as they are in different splits
     * (resources.arsc, AndroidManifest.xml).
     */
    android::status_t addEntry(const String8& path, const android::sp<AaptFile>& file);

    android::Vector<sp<ApkSplit> >& getSplits() {
        return mSplits;
    }

    android::sp<ApkSplit> getBaseSplit() {
        return mSplits[0];
    }

    void print() const;

private:
    android::sp<ResourceFilter> mConfigFilter;
    android::sp<AndResourceFilter> mDefaultFilter;
    android::Vector<sp<ApkSplit> > mSplits;
};

class ApkSplit : public OutputSet {
public:
    android::status_t addEntry(const String8& path, const android::sp<AaptFile>& file);

    const std::set<OutputEntry>& getEntries() const {
        return mFiles;
    }

    const std::set<ConfigDescription>& getConfigs() const {
        return mConfigs;
    }

    bool matches(const sp<AaptFile>& file) const {
        return mFilter->match(file->getGroupEntry().toParams());
    }

    sp<ResourceFilter> getResourceFilter() const {
        return mFilter;
    }

    const android::String8& getPrintableName() const {
        return mName;
    }

    const android::String8& getDirectorySafeName() const {
        return mDirName;
    }

    const android::String8& getPackageSafeName() const {
        return mPackageSafeName;
    }

    bool isBase() const {
        return mIsBase;
    }

    void print() const;

private:
    friend class ApkBuilder;

    ApkSplit(const std::set<ConfigDescription>& configs, const android::sp<ResourceFilter>& filter, bool isBase=false);

    std::set<ConfigDescription> mConfigs;
    const sp<ResourceFilter> mFilter;
    const bool mIsBase;
    String8 mName;
    String8 mDirName;
    String8 mPackageSafeName;
    std::set<OutputEntry> mFiles;
};

#endif // __APK_BUILDER_H
