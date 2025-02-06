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
#include <androidfw/Util.h>

#include <optional>
#include <vector>

#include <gtest/gtest.h>

namespace android {

using ResValueVector = std::vector<std::optional<Res_value>>;

// create a ResTable_type in memory
static util::unique_cptr<ResTable_type> createTypeTable(
    const ResValueVector& in_values, bool compact_entry, bool short_offsets, bool sparse)
{
    ResValueVector sparse_values;
    if (sparse) {
      std::ranges::copy_if(in_values, std::back_inserter(sparse_values),
                           [](auto&& v) { return v.has_value(); });
    }
    const ResValueVector& values = sparse ? sparse_values : in_values;

    ResTable_type t{};
    t.header.type = RES_TABLE_TYPE_TYPE;
    t.header.headerSize = sizeof(t);
    t.header.size = sizeof(t);
    t.id = 1;
    t.flags = sparse
                  ? ResTable_type::FLAG_SPARSE
                  : short_offsets ? ResTable_type::FLAG_OFFSET16 : 0;

    t.header.size += values.size() *
                     (sparse ? sizeof(ResTable_sparseTypeEntry) :
                         short_offsets ? sizeof(uint16_t) : sizeof(uint32_t));
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
    uint32_t sparse_index = 0;

    for (auto const& v : in_values) {
        if (sparse) {
            if (!v) {
                ++i;
                continue;
            }
            const auto p = reinterpret_cast<ResTable_sparseTypeEntry*>(p_offsets) + sparse_index++;
            p->idx = i;
            p->offset = (entry_offset >> 2) & 0xffffu;
        } else if (short_offsets) {
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
    return util::unique_cptr<ResTable_type>{reinterpret_cast<ResTable_type*>(data)};
}

TEST(TypeVariantIteratorTest, shouldIterateOverTypeWithoutErrors) {
    ResValueVector values;

    values.push_back(std::nullopt);
    values.push_back(Res_value{});
    values.push_back(std::nullopt);
    values.push_back(Res_value{});
    values.push_back(Res_value{ sizeof(Res_value), 0, Res_value::TYPE_STRING, 0x12345678});
    values.push_back(std::nullopt);
    values.push_back(std::nullopt);
    values.push_back(std::nullopt);
    values.push_back(Res_value{ sizeof(Res_value), 0, Res_value::TYPE_STRING, 0x87654321});
    values.push_back(std::nullopt);

    // test for combinations of compact_entry and short_offsets
    for (size_t i = 0; i < 8; i++) {
        bool compact_entry = i & 0x1, short_offsets = i & 0x2, sparse = i & 0x4;
        auto data = createTypeTable(values, compact_entry, short_offsets, sparse);
        TypeVariant v(data.get());

        TypeVariant::iterator iter = v.beginEntries();
        ASSERT_EQ(uint32_t(0), iter.index());
        ASSERT_TRUE(NULL == *iter);
        ASSERT_NE(v.endEntries(), iter);

        ++iter;

        ASSERT_EQ(uint32_t(1), iter.index());
        ASSERT_TRUE(NULL != *iter);
        ASSERT_EQ(uint32_t(1), iter->key());
        ASSERT_NE(v.endEntries(), iter);

        iter++;

        ASSERT_EQ(uint32_t(2), iter.index());
        ASSERT_TRUE(NULL == *iter);
        ASSERT_NE(v.endEntries(), iter);

        ++iter;

        ASSERT_EQ(uint32_t(3), iter.index());
        ASSERT_TRUE(NULL != *iter);
        ASSERT_EQ(uint32_t(3), iter->key());
        ASSERT_NE(v.endEntries(), iter);

        iter++;

        ASSERT_EQ(uint32_t(4), iter.index());
        ASSERT_TRUE(NULL != *iter);
        ASSERT_EQ(iter->is_compact(), compact_entry);
        ASSERT_EQ(uint32_t(4), iter->key());
        ASSERT_EQ(uint32_t(0x12345678), iter->value().data);
        ASSERT_EQ(Res_value::TYPE_STRING, iter->value().dataType);

        ++iter;

        ASSERT_EQ(uint32_t(5), iter.index());
        ASSERT_TRUE(NULL == *iter);
        ASSERT_NE(v.endEntries(), iter);

        ++iter;

        ASSERT_EQ(uint32_t(6), iter.index());
        ASSERT_TRUE(NULL == *iter);
        ASSERT_NE(v.endEntries(), iter);

        ++iter;

        ASSERT_EQ(uint32_t(7), iter.index());
        ASSERT_TRUE(NULL == *iter);
        ASSERT_NE(v.endEntries(), iter);

        iter++;

        ASSERT_EQ(uint32_t(8), iter.index());
        ASSERT_TRUE(NULL != *iter);
        ASSERT_EQ(iter->is_compact(), compact_entry);
        ASSERT_EQ(uint32_t(8), iter->key());
        ASSERT_EQ(uint32_t(0x87654321), iter->value().data);
        ASSERT_EQ(Res_value::TYPE_STRING, iter->value().dataType);

        ++iter;

        ASSERT_EQ(uint32_t(9), iter.index());
        ASSERT_TRUE(NULL == *iter);
        if (sparse) {
          // Sparse iterator doesn't know anything beyond the last entry.
          ASSERT_EQ(v.endEntries(), iter);
        } else {
          ASSERT_NE(v.endEntries(), iter);
        }

        ++iter;

        ASSERT_EQ(v.endEntries(), iter);
    }
}

} // namespace android
