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

#include "Grouper.h"

#include "SplitDescription.h"

#include <gtest/gtest.h>
#include <utils/String8.h>
#include <utils/Vector.h>

using namespace android;

namespace split {

class GrouperTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        Vector<SplitDescription> splits;
        addSplit(splits, "en-rUS-sw600dp-hdpi");
        addSplit(splits, "fr-rFR-sw600dp-hdpi");
        addSplit(splits, "fr-rFR-sw600dp-xhdpi");
        addSplit(splits, ":armeabi");
        addSplit(splits, "en-rUS-sw300dp-xhdpi");
        addSplit(splits, "large");
        addSplit(splits, "pl-rPL");
        addSplit(splits, "xlarge");
        addSplit(splits, "en-rUS-sw600dp-xhdpi");
        addSplit(splits, "en-rUS-sw300dp-hdpi");
        addSplit(splits, "xxhdpi");
        addSplit(splits, "hdpi");
        addSplit(splits, "de-rDE");
        addSplit(splits, "xhdpi");
        addSplit(splits, ":x86");
        addSplit(splits, "anydpi");
        addSplit(splits, "v7");
        addSplit(splits, "v8");
        addSplit(splits, "sw600dp");
        addSplit(splits, "sw300dp");
        mGroups = groupByMutualExclusivity(splits);
    }

    void addSplit(Vector<SplitDescription>& splits, const char* str);
    void expectHasGroupWithSplits(const char* a);
    void expectHasGroupWithSplits(const char* a, const char* b);
    void expectHasGroupWithSplits(const char* a, const char* b, const char* c);
    void expectHasGroupWithSplits(const char* a, const char* b, const char* c, const char* d);
    void expectHasGroupWithSplits(const Vector<const char*>& expectedStrs);

    Vector<SortedVector<SplitDescription> > mGroups;
};

TEST_F(GrouperTest, shouldHaveCorrectNumberOfGroups) {
    EXPECT_EQ(12u, mGroups.size());
}

TEST_F(GrouperTest, shouldGroupDensities) {
    expectHasGroupWithSplits("en-rUS-sw300dp-hdpi", "en-rUS-sw300dp-xhdpi");
    expectHasGroupWithSplits("en-rUS-sw600dp-hdpi", "en-rUS-sw600dp-xhdpi");
    expectHasGroupWithSplits("fr-rFR-sw600dp-hdpi", "fr-rFR-sw600dp-xhdpi");
    expectHasGroupWithSplits("hdpi", "xhdpi", "xxhdpi", "anydpi");
}

TEST_F(GrouperTest, shouldGroupAbi) {
    expectHasGroupWithSplits(":armeabi", ":x86");
}

TEST_F(GrouperTest, shouldGroupLocale) {
    expectHasGroupWithSplits("pl-rPL", "de-rDE");
}

TEST_F(GrouperTest, shouldGroupEachSplitIntoItsOwnGroup) {
    expectHasGroupWithSplits("large");
    expectHasGroupWithSplits("xlarge");
    expectHasGroupWithSplits("v7");
    expectHasGroupWithSplits("v8");
    expectHasGroupWithSplits("sw600dp");
    expectHasGroupWithSplits("sw300dp");
}

//
// Helper methods
//

void GrouperTest::expectHasGroupWithSplits(const char* a) {
    Vector<const char*> expected;
    expected.add(a);
    expectHasGroupWithSplits(expected);
}

void GrouperTest::expectHasGroupWithSplits(const char* a, const char* b) {
    Vector<const char*> expected;
    expected.add(a);
    expected.add(b);
    expectHasGroupWithSplits(expected);
}

void GrouperTest::expectHasGroupWithSplits(const char* a, const char* b, const char* c) {
    Vector<const char*> expected;
    expected.add(a);
    expected.add(b);
    expected.add(c);
    expectHasGroupWithSplits(expected);
}

void GrouperTest::expectHasGroupWithSplits(const char* a, const char* b, const char* c, const char* d) {
    Vector<const char*> expected;
    expected.add(a);
    expected.add(b);
    expected.add(c);
    expected.add(d);
    expectHasGroupWithSplits(expected);
}

void GrouperTest::expectHasGroupWithSplits(const Vector<const char*>& expectedStrs) {
    Vector<SplitDescription> splits;
    const size_t expectedStrCount = expectedStrs.size();
    for (size_t i = 0; i < expectedStrCount; i++) {
        splits.add();
        if (!SplitDescription::parse(String8(expectedStrs[i]), &splits.editTop())) {
            ADD_FAILURE() << "Failed to parse SplitDescription " << expectedStrs[i];
            return;
        }
    }
    const size_t splitCount = splits.size();

    const size_t groupCount = mGroups.size();
    for (size_t i = 0; i < groupCount; i++) {
        const SortedVector<SplitDescription>& group = mGroups[i];
        if (group.size() != splitCount) {
            continue;
        }

        size_t found = 0;
        for (size_t j = 0; j < splitCount; j++) {
            if (group.indexOf(splits[j]) >= 0) {
                found++;
            }
        }

        if (found == splitCount) {
            return;
        }
    }

    String8 errorMessage("Failed to find expected group [");
    for (size_t i = 0; i < splitCount; i++) {
        if (i != 0) {
            errorMessage.append(", ");
        }
        errorMessage.append(splits[i].toString());
    }
    errorMessage.append("].\nActual:\n");

    for (size_t i = 0; i < groupCount; i++) {
        errorMessage.appendFormat("Group %d:\n", int(i + 1));
        const SortedVector<SplitDescription>& group = mGroups[i];
        for (size_t j = 0; j < group.size(); j++) {
            errorMessage.append("  ");
            errorMessage.append(group[j].toString());
            errorMessage.append("\n");
        }
    }
    ADD_FAILURE() << errorMessage.string();
}

void GrouperTest::addSplit(Vector<SplitDescription>& splits, const char* str) {
    splits.add();
    EXPECT_TRUE(SplitDescription::parse(String8(str), &splits.editTop()));
}

} // namespace split
