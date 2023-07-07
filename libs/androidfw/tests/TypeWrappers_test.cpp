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

#include <algorithm>
#include <androidfw/ResourceTypes.h>
#include <androidfw/TypeWrappers.h>
#include <utils/String8.h>

#include <gtest/gtest.h>

namespace android {

// create a ResTable_type in memory with a vector of Res_value*
static ResTable_type* createTypeTable(std::vector<Res_value*>& values,
                             bool compact_entry = false,
                             bool short_offsets = false)
{
    ResTable_type t{};
    t.header.type = RES_TABLE_TYPE_TYPE;
    t.header.headerSize = sizeof(t);
    t.header.size = sizeof(t);
    t.id = 1;
    t.flags = short_offsets ? ResTable_type::FLAG_OFFSET16 : 0;

    t.header.size += values.size() * (short_offsets ? sizeof(uint16_t) : sizeof(uint32_t));
    t.entriesStart = t.header.size;
    t.entryCount = values.size();

    size_t entry_size = compact_entry ? sizeof(ResTable_entry)
                                      : sizeof(ResTable_entry) + sizeof(Res_value);
    for (auto const v : values) {
        t.header.size += v ? entry_size : 0;
    }

    uint8_t* data = (uint8_t *)malloc(t.header.size);
    uint8_t* p_header = data;
    uint8_t* p_offsets = data + t.header.headerSize;
    uint8_t* p_entries = data + t.entriesStart;

    memcpy(p_header, &t, sizeof(t));

    size_t i = 0, entry_offset = 0;
    uint32_t k = 0;
    for (auto const& v : values) {
        if (short_offsets) {
            uint16_t *p = reinterpret_cast<uint16_t *>(p_offsets) + i;
            *p = v ? (entry_offset >> 2) & 0xffffu : 0xffffu;
        } else {
            uint32_t *p = reinterpret_cast<uint32_t *>(p_offsets) + i;
            *p = v ? entry_offset : ResTable_type::NO_ENTRY;
        }

        if (v) {
            ResTable_entry entry{};
            if (compact_entry) {
                entry.compact.key = i;
                entry.compact.flags = ResTable_entry::FLAG_COMPACT | (v->dataType << 8);
                entry.compact.data = v->data;
                memcpy(p_entries, &entry, sizeof(entry)); p_entries += sizeof(entry);
                entry_offset += sizeof(entry);
            } else {
                Res_value value{};
                entry.full.size = sizeof(entry);
                entry.full.key.index = i;
                value = *v;
                memcpy(p_entries, &entry, sizeof(entry)); p_entries += sizeof(entry);
                memcpy(p_entries, &value, sizeof(value)); p_entries += sizeof(value);
                entry_offset += sizeof(entry) + sizeof(value);
            }
        }
        i++;
    }
    return reinterpret_cast<ResTable_type*>(data);
}

TEST(TypeVariantIteratorTest, shouldIterateOverTypeWithoutErrors) {
    std::vector<Res_value *> values;

    Res_value *v1 = new Res_value{};
    values.push_back(v1);

    values.push_back(nullptr);

    Res_value *v2 = new Res_value{};
    values.push_back(v2);

    Res_value *v3 = new Res_value{ sizeof(Res_value), 0, Res_value::TYPE_STRING, 0x12345678};
    values.push_back(v3);

    // test for combinations of compact_entry and short_offsets
    for (size_t i = 0; i < 4; i++) {
        bool compact_entry = i & 0x1, short_offsets = i & 0x2;
        ResTable_type* data = createTypeTable(values, compact_entry, short_offsets);
        TypeVariant v(data);

        TypeVariant::iterator iter = v.beginEntries();
        ASSERT_EQ(uint32_t(0), iter.index());
        ASSERT_TRUE(NULL != *iter);
        ASSERT_EQ(uint32_t(0), iter->key());
        ASSERT_NE(v.endEntries(), iter);

        iter++;

        ASSERT_EQ(uint32_t(1), iter.index());
        ASSERT_TRUE(NULL == *iter);
        ASSERT_NE(v.endEntries(), iter);

        iter++;

        ASSERT_EQ(uint32_t(2), iter.index());
        ASSERT_TRUE(NULL != *iter);
        ASSERT_EQ(uint32_t(2), iter->key());
        ASSERT_NE(v.endEntries(), iter);

        iter++;

        ASSERT_EQ(uint32_t(3), iter.index());
        ASSERT_TRUE(NULL != *iter);
        ASSERT_EQ(iter->is_compact(), compact_entry);
        ASSERT_EQ(uint32_t(3), iter->key());
        ASSERT_EQ(uint32_t(0x12345678), iter->value().data);
        ASSERT_EQ(Res_value::TYPE_STRING, iter->value().dataType);

        iter++;

        ASSERT_EQ(v.endEntries(), iter);

        free(data);
    }
}

} // namespace android
