/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "Resource.h"

#include "flatten/FileExportWriter.h"
#include "util/BigBuffer.h"
#include "util/Util.h"

#include "test/Common.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(FileExportWriterTest, FlattenResourceFileDataWithNoExports) {
    ResourceFile resFile = {
            test::parseNameOrDie(u"@android:layout/main.xml"),
            test::parseConfigOrDie("sw600dp-v4"),
            Source{ "res/layout/main.xml" },
    };

    BigBuffer buffer(1024);
    ChunkWriter writer = wrapBufferWithFileExportHeader(&buffer, &resFile);
    *writer.getBuffer()->nextBlock<uint32_t>() = 42u;
    writer.finish();

    std::unique_ptr<uint8_t[]> data = util::copy(buffer);

    // There should be more data (string pool) besides the header and our data.
    ASSERT_GT(buffer.size(), sizeof(FileExport_header) + sizeof(uint32_t));

    // Write at the end of this chunk is our data.
    uint32_t* val = (uint32_t*)(data.get() + buffer.size()) - 1;
    EXPECT_EQ(*val, 42u);
}

} // namespace aapt
