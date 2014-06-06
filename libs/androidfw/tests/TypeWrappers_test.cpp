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

#include <androidfw/ResourceTypes.h>
#include <androidfw/TypeWrappers.h>
#include <utils/String8.h>

#include <gtest/gtest.h>

namespace android {

void* createTypeData() {
    ResTable_type t;
    memset(&t, 0, sizeof(t));
    t.header.type = RES_TABLE_TYPE_TYPE;
    t.header.headerSize = sizeof(t);
    t.id = 1;
    t.entryCount = 3;

    uint32_t offsets[3];
    t.entriesStart = t.header.headerSize + sizeof(offsets);
    t.header.size = t.entriesStart;

    offsets[0] = 0;
    ResTable_entry e1;
    memset(&e1, 0, sizeof(e1));
    e1.size = sizeof(e1);
    e1.key.index = 0;
    t.header.size += sizeof(e1);

    Res_value v1;
    memset(&v1, 0, sizeof(v1));
    t.header.size += sizeof(v1);

    offsets[1] = ResTable_type::NO_ENTRY;

    offsets[2] = sizeof(e1) + sizeof(v1);
    ResTable_entry e2;
    memset(&e2, 0, sizeof(e2));
    e2.size = sizeof(e2);
    e2.key.index = 1;
    t.header.size += sizeof(e2);

    Res_value v2;
    memset(&v2, 0, sizeof(v2));
    t.header.size += sizeof(v2);

    uint8_t* data = (uint8_t*)malloc(t.header.size);
    uint8_t* p = data;
    memcpy(p, &t, sizeof(t));
    p += sizeof(t);
    memcpy(p, offsets, sizeof(offsets));
    p += sizeof(offsets);
    memcpy(p, &e1, sizeof(e1));
    p += sizeof(e1);
    memcpy(p, &v1, sizeof(v1));
    p += sizeof(v1);
    memcpy(p, &e2, sizeof(e2));
    p += sizeof(e2);
    memcpy(p, &v2, sizeof(v2));
    p += sizeof(v2);
    return data;
}

TEST(TypeVariantIteratorTest, shouldIterateOverTypeWithoutErrors) {
    ResTable_type* data = (ResTable_type*) createTypeData();

    TypeVariant v(data);

    TypeVariant::iterator iter = v.beginEntries();
    ASSERT_EQ(uint32_t(0), iter.index());
    ASSERT_TRUE(NULL != *iter);
    ASSERT_EQ(uint32_t(0), iter->key.index);
    ASSERT_NE(v.endEntries(), iter);

    iter++;

    ASSERT_EQ(uint32_t(1), iter.index());
    ASSERT_TRUE(NULL == *iter);
    ASSERT_NE(v.endEntries(), iter);

    iter++;

    ASSERT_EQ(uint32_t(2), iter.index());
    ASSERT_TRUE(NULL != *iter);
    ASSERT_EQ(uint32_t(1), iter->key.index);
    ASSERT_NE(v.endEntries(), iter);

    iter++;

    ASSERT_EQ(v.endEntries(), iter);

    free(data);
}

} // namespace android
