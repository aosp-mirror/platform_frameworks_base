/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <utility>

#include "androidfw/CursorWindow.h"

#include "TestHelpers.h"

// Verify that the memory in use is a multiple of 4 bytes
#define ASSERT_ALIGNED(w) \
    ASSERT_EQ(((w)->sizeInUse() & 3), 0); \
    ASSERT_EQ(((w)->freeSpace() & 3), 0); \
    ASSERT_EQ(((w)->sizeOfSlots() & 3), 0)

#define CREATE_WINDOW_1K \
    CursorWindow* w; \
    CursorWindow::create(String8("test"), 1 << 10, &w); \
    ASSERT_ALIGNED(w);

#define CREATE_WINDOW_1K_3X3 \
    CursorWindow* w; \
    CursorWindow::create(String8("test"), 1 << 10, &w); \
    ASSERT_EQ(w->setNumColumns(3), OK); \
    ASSERT_EQ(w->allocRow(), OK); \
    ASSERT_EQ(w->allocRow(), OK); \
    ASSERT_EQ(w->allocRow(), OK); \
    ASSERT_ALIGNED(w);

#define CREATE_WINDOW_2M \
    CursorWindow* w; \
    CursorWindow::create(String8("test"), 1 << 21, &w); \
    ASSERT_ALIGNED(w);

static constexpr const size_t kHalfInlineSize = 8192;
static constexpr const size_t kGiantSize = 1048576;

namespace android {

TEST(CursorWindowTest, Empty) {
    CREATE_WINDOW_1K;

    ASSERT_EQ(w->getNumRows(), 0);
    ASSERT_EQ(w->getNumColumns(), 0);
    ASSERT_EQ(w->size(), 1 << 10);
    ASSERT_EQ(w->freeSpace(), 1 << 10);
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, SetNumColumns) {
    CREATE_WINDOW_1K;

    // Once we've locked in columns, we can't adjust
    ASSERT_EQ(w->getNumColumns(), 0);
    ASSERT_EQ(w->setNumColumns(4), OK);
    ASSERT_NE(w->setNumColumns(5), OK);
    ASSERT_NE(w->setNumColumns(3), OK);
    ASSERT_EQ(w->getNumColumns(), 4);
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, SetNumColumnsAfterRow) {
    CREATE_WINDOW_1K;

    // Once we've locked in a row, we can't adjust columns
    ASSERT_EQ(w->getNumColumns(), 0);
    ASSERT_EQ(w->allocRow(), OK);
    ASSERT_NE(w->setNumColumns(4), OK);
    ASSERT_EQ(w->getNumColumns(), 0);
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, AllocRow) {
    CREATE_WINDOW_1K;

    ASSERT_EQ(w->setNumColumns(4), OK);

    // Rolling forward means we have less free space
    ASSERT_EQ(w->getNumRows(), 0);
    auto before = w->freeSpace();
    ASSERT_EQ(w->allocRow(), OK);
    ASSERT_LT(w->freeSpace(), before);
    ASSERT_EQ(w->getNumRows(), 1);
    ASSERT_ALIGNED(w);

    // Verify we can unwind
    ASSERT_EQ(w->freeLastRow(), OK);
    ASSERT_EQ(w->freeSpace(), before);
    ASSERT_EQ(w->getNumRows(), 0);
    ASSERT_ALIGNED(w);

    // Can't unwind when no rows left
    ASSERT_NE(w->freeLastRow(), OK);
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, AllocRowBounds) {
    CREATE_WINDOW_1K;

    // 60 columns is 960 bytes, which means only a single row can fit
    ASSERT_EQ(w->setNumColumns(60), OK);
    ASSERT_EQ(w->allocRow(), OK);
    ASSERT_NE(w->allocRow(), OK);
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, StoreNull) {
    CREATE_WINDOW_1K_3X3;

    ASSERT_EQ(w->putNull(1, 1), OK);
    ASSERT_EQ(w->putNull(0, 0), OK);

    {
        auto field = w->getFieldSlot(1, 1);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_NULL);
    }
    {
        auto field = w->getFieldSlot(0, 0);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_NULL);
    }
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, StoreLong) {
    CREATE_WINDOW_1K_3X3;

    ASSERT_EQ(w->putLong(1, 1, 0xf00d), OK);
    ASSERT_EQ(w->putLong(0, 0, 0xcafe), OK);

    {
        auto field = w->getFieldSlot(1, 1);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_INTEGER);
        ASSERT_EQ(w->getFieldSlotValueLong(field), 0xf00d);
    }
    {
        auto field = w->getFieldSlot(0, 0);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_INTEGER);
        ASSERT_EQ(w->getFieldSlotValueLong(field), 0xcafe);
    }
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, StoreString) {
    CREATE_WINDOW_1K_3X3;

    ASSERT_EQ(w->putString(1, 1, "food", 5), OK);
    ASSERT_EQ(w->putString(0, 0, "cafe", 5), OK);

    size_t size;
    {
        auto field = w->getFieldSlot(1, 1);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_STRING);
        auto actual = w->getFieldSlotValueString(field, &size);
        ASSERT_EQ(std::string(actual), "food");
    }
    {
        auto field = w->getFieldSlot(0, 0);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_STRING);
        auto actual = w->getFieldSlotValueString(field, &size);
        ASSERT_EQ(std::string(actual), "cafe");
    }
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, StoreBounds) {
    CREATE_WINDOW_1K_3X3;

    // Can't work with values beyond bounds
    ASSERT_NE(w->putLong(0, 3, 0xcafe), OK);
    ASSERT_NE(w->putLong(3, 0, 0xcafe), OK);
    ASSERT_NE(w->putLong(3, 3, 0xcafe), OK);
    ASSERT_EQ(w->getFieldSlot(0, 3), nullptr);
    ASSERT_EQ(w->getFieldSlot(3, 0), nullptr);
    ASSERT_EQ(w->getFieldSlot(3, 3), nullptr);

    // Can't work with invalid indexes
    ASSERT_NE(w->putLong(-1, 0, 0xcafe), OK);
    ASSERT_NE(w->putLong(0, -1, 0xcafe), OK);
    ASSERT_NE(w->putLong(-1, -1, 0xcafe), OK);
    ASSERT_EQ(w->getFieldSlot(-1, 0), nullptr);
    ASSERT_EQ(w->getFieldSlot(0, -1), nullptr);
    ASSERT_EQ(w->getFieldSlot(-1, -1), nullptr);
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, Inflate) {
    CREATE_WINDOW_2M;

    auto before = w->size();
    ASSERT_EQ(w->setNumColumns(4), OK);
    ASSERT_EQ(w->allocRow(), OK);

    // Scratch buffer that will fit before inflation
    void* buf = malloc(kHalfInlineSize);

    // Store simple value
    ASSERT_EQ(w->putLong(0, 0, 0xcafe), OK);

    // Store first object that fits inside
    memset(buf, 42, kHalfInlineSize);
    ASSERT_EQ(w->putBlob(0, 1, buf, kHalfInlineSize), OK);
    ASSERT_EQ(w->size(), before);

    // Store second simple value
    ASSERT_EQ(w->putLong(0, 2, 0xface), OK);

    // Store second object that requires inflation
    memset(buf, 84, kHalfInlineSize);
    ASSERT_EQ(w->putBlob(0, 3, buf, kHalfInlineSize), OK);
    ASSERT_GT(w->size(), before);

    // Verify data is intact
    {
        auto field = w->getFieldSlot(0, 0);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_INTEGER);
        ASSERT_EQ(w->getFieldSlotValueLong(field), 0xcafe);
    }
    {
        auto field = w->getFieldSlot(0, 1);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_BLOB);
        size_t actualSize;
        auto actual = w->getFieldSlotValueBlob(field, &actualSize);
        ASSERT_EQ(actualSize, kHalfInlineSize);
        memset(buf, 42, kHalfInlineSize);
        ASSERT_NE(actual, buf);
        ASSERT_EQ(memcmp(buf, actual, kHalfInlineSize), 0);
    }
    {
        auto field = w->getFieldSlot(0, 2);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_INTEGER);
        ASSERT_EQ(w->getFieldSlotValueLong(field), 0xface);
    }
    {
        auto field = w->getFieldSlot(0, 3);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_BLOB);
        size_t actualSize;
        auto actual = w->getFieldSlotValueBlob(field, &actualSize);
        ASSERT_EQ(actualSize, kHalfInlineSize);
        memset(buf, 84, kHalfInlineSize);
        ASSERT_NE(actual, buf);
        ASSERT_EQ(memcmp(buf, actual, kHalfInlineSize), 0);
    }
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, ParcelEmpty) {
    CREATE_WINDOW_2M;

    Parcel p;
    w->writeToParcel(&p);
    p.setDataPosition(0);
    w = nullptr;

    ASSERT_EQ(CursorWindow::createFromParcel(&p, &w), OK);
    ASSERT_EQ(w->getNumRows(), 0);
    ASSERT_EQ(w->getNumColumns(), 0);
    ASSERT_EQ(w->size(), 0);
    ASSERT_EQ(w->freeSpace(), 0);
    ASSERT_ALIGNED(w);

    // We can't mutate the window after parceling
    ASSERT_NE(w->setNumColumns(4), OK);
    ASSERT_NE(w->allocRow(), OK);
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, ParcelSmall) {
    CREATE_WINDOW_2M;

    auto before = w->size();
    ASSERT_EQ(w->setNumColumns(4), OK);
    ASSERT_EQ(w->allocRow(), OK);

    // Scratch buffer that will fit before inflation
    void* buf = malloc(kHalfInlineSize);

    // Store simple value
    ASSERT_EQ(w->putLong(0, 0, 0xcafe), OK);

    // Store first object that fits inside
    memset(buf, 42, kHalfInlineSize);
    ASSERT_EQ(w->putBlob(0, 1, buf, kHalfInlineSize), OK);
    ASSERT_EQ(w->size(), before);

    // Store second object with zero length
    ASSERT_EQ(w->putBlob(0, 2, buf, 0), OK);
    ASSERT_EQ(w->size(), before);

    // Force through a parcel
    Parcel p;
    w->writeToParcel(&p);
    p.setDataPosition(0);
    w = nullptr;

    ASSERT_EQ(CursorWindow::createFromParcel(&p, &w), OK);
    ASSERT_EQ(w->getNumRows(), 1);
    ASSERT_EQ(w->getNumColumns(), 4);

    // Verify data is intact
    {
        auto field = w->getFieldSlot(0, 0);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_INTEGER);
        ASSERT_EQ(w->getFieldSlotValueLong(field), 0xcafe);
    }
    {
        auto field = w->getFieldSlot(0, 1);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_BLOB);
        size_t actualSize;
        auto actual = w->getFieldSlotValueBlob(field, &actualSize);
        ASSERT_EQ(actualSize, kHalfInlineSize);
        memset(buf, 42, kHalfInlineSize);
        ASSERT_NE(actual, buf);
        ASSERT_EQ(memcmp(buf, actual, kHalfInlineSize), 0);
    }
    {
        auto field = w->getFieldSlot(0, 2);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_BLOB);
        size_t actualSize;
        auto actual = w->getFieldSlotValueBlob(field, &actualSize);
        ASSERT_EQ(actualSize, 0);
        ASSERT_NE(actual, nullptr);
    }
    ASSERT_ALIGNED(w);
}

TEST(CursorWindowTest, ParcelLarge) {
    CREATE_WINDOW_2M;

    ASSERT_EQ(w->setNumColumns(4), OK);
    ASSERT_EQ(w->allocRow(), OK);

    // Store simple value
    ASSERT_EQ(w->putLong(0, 0, 0xcafe), OK);

    // Store object that forces inflation
    void* buf = malloc(kGiantSize);
    memset(buf, 42, kGiantSize);
    ASSERT_EQ(w->putBlob(0, 1, buf, kGiantSize), OK);

    // Store second object with zero length
    ASSERT_EQ(w->putBlob(0, 2, buf, 0), OK);

    // Force through a parcel
    Parcel p;
    w->writeToParcel(&p);
    p.setDataPosition(0);
    w = nullptr;

    ASSERT_EQ(CursorWindow::createFromParcel(&p, &w), OK);
    ASSERT_EQ(w->getNumRows(), 1);
    ASSERT_EQ(w->getNumColumns(), 4);

    // Verify data is intact
    {
        auto field = w->getFieldSlot(0, 0);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_INTEGER);
        ASSERT_EQ(w->getFieldSlotValueLong(field), 0xcafe);
    }
    {
        auto field = w->getFieldSlot(0, 1);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_BLOB);
        size_t actualSize;
        auto actual = w->getFieldSlotValueBlob(field, &actualSize);
        ASSERT_EQ(actualSize, kGiantSize);
        memset(buf, 42, kGiantSize);
        ASSERT_EQ(memcmp(buf, actual, kGiantSize), 0);
    }
    {
        auto field = w->getFieldSlot(0, 2);
        ASSERT_EQ(w->getFieldSlotType(field), CursorWindow::FIELD_TYPE_BLOB);
        size_t actualSize;
        auto actual = w->getFieldSlotValueBlob(field, &actualSize);
        ASSERT_EQ(actualSize, 0);
        ASSERT_NE(actual, nullptr);
    }
    ASSERT_ALIGNED(w);
}

} // android
