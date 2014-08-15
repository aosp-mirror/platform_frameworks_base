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

#include "AaptAssets.h"
#include "ApkBuilder.h"

using namespace android;

ApkBuilder::ApkBuilder(const sp<WeakResourceFilter>& configFilter)
    : mConfigFilter(configFilter)
    , mDefaultFilter(new AndResourceFilter()) {
    // Add the default split, which is present for all APKs.
    mDefaultFilter->addFilter(mConfigFilter);
    mSplits.add(new ApkSplit(std::set<ConfigDescription>(), mDefaultFilter, true));
}

status_t ApkBuilder::createSplitForConfigs(const std::set<ConfigDescription>& configs) {
    const size_t N = mSplits.size();
    for (size_t i = 0; i < N; i++) {
        const std::set<ConfigDescription>& splitConfigs = mSplits[i]->getConfigs();
        std::set<ConfigDescription>::const_iterator iter = configs.begin();
        for (; iter != configs.end(); iter++) {
            if (splitConfigs.count(*iter) > 0) {
                // Can't have overlapping configurations.
                fprintf(stderr, "ERROR: Split configuration '%s' is already defined "
                        "in another split.\n", iter->toString().string());
                return ALREADY_EXISTS;
            }
        }
    }

    sp<StrongResourceFilter> splitFilter = new StrongResourceFilter(configs);

    // Add the inverse filter of this split filter to the base apk filter so it will
    // omit resources that belong in this split.
    mDefaultFilter->addFilter(new InverseResourceFilter(splitFilter));

    // Now add the apk-wide config filter to our split filter.
    sp<AndResourceFilter> filter = new AndResourceFilter();
    filter->addFilter(splitFilter);
    filter->addFilter(mConfigFilter);
    mSplits.add(new ApkSplit(configs, filter));
    return NO_ERROR;
}

status_t ApkBuilder::addEntry(const String8& path, const sp<AaptFile>& file) {
    const size_t N = mSplits.size();
    for (size_t i = 0; i < N; i++) {
        if (mSplits[i]->matches(file)) {
            return mSplits.editItemAt(i)->addEntry(path, file);
        }
    }
    // Entry can be dropped if it doesn't match any split. This will only happen
    // if the enry doesn't mConfigFilter.
    return NO_ERROR;
}

void ApkBuilder::print() const {
    fprintf(stderr, "APK Builder\n");
    fprintf(stderr, "-----------\n");
    const size_t N = mSplits.size();
    for (size_t i = 0; i < N; i++) {
        mSplits[i]->print();
        fprintf(stderr, "\n");
    }
}

ApkSplit::ApkSplit(const std::set<ConfigDescription>& configs, const sp<ResourceFilter>& filter, bool isBase)
    : mConfigs(configs), mFilter(filter), mIsBase(isBase) {
    std::set<ConfigDescription>::const_iterator iter = configs.begin();
    for (; iter != configs.end(); iter++) {
        if (mName.size() > 0) {
            mName.append(",");
            mDirName.append("_");
            mPackageSafeName.append(".");
        }

        String8 configStr = iter->toString();
        String8 packageConfigStr(configStr);
        size_t len = packageConfigStr.length();
        if (len > 0) {
            char* buf = packageConfigStr.lockBuffer(len);
            for (char* end = buf + len; buf < end; ++buf) {
                if (*buf == '-') {
                    *buf = '_';
                }
            }
            packageConfigStr.unlockBuffer(len);
        }
        mName.append(configStr);
        mDirName.append(configStr);
        mPackageSafeName.append(packageConfigStr);
    }
}

status_t ApkSplit::addEntry(const String8& path, const sp<AaptFile>& file) {
    if (!mFiles.insert(OutputEntry(path, file)).second) {
        // Duplicate file.
        return ALREADY_EXISTS;
    }
    return NO_ERROR;
}

void ApkSplit::print() const {
    fprintf(stderr, "APK Split '%s'\n", mName.string());

    std::set<OutputEntry>::const_iterator iter = mFiles.begin();
    for (; iter != mFiles.end(); iter++) {
        fprintf(stderr, "  %s (%s)\n", iter->getPath().string(), iter->getFile()->getSourceFile().string());
    }
}
