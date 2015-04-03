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

#include "Resolver.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "SourceXmlPullParser.h"
#include "Util.h"
#include "XmlFlattener.h"

#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>
#include <gtest/gtest.h>
#include <sstream>
#include <string>

using namespace android;

namespace aapt {

constexpr const char* kXmlPreamble = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

class XmlFlattenerTest : public ::testing::Test {
public:
    virtual void SetUp() override {
        std::shared_ptr<ResourceTable> table = std::make_shared<ResourceTable>();
        table->setPackage(u"android");
        table->setPackageId(0x01);

        table->addResource(ResourceName{ {}, ResourceType::kAttr, u"id" },
                           ResourceId{ 0x01010000 }, {}, {},
                           util::make_unique<Attribute>(false, ResTable_map::TYPE_ANY));

        table->addResource(ResourceName{ {}, ResourceType::kId, u"test" },
                           ResourceId{ 0x01020000 }, {}, {}, util::make_unique<Id>());

        mFlattener = std::make_shared<XmlFlattener>(
                std::make_shared<Resolver>(table, std::make_shared<AssetManager>()));
    }

    ::testing::AssertionResult testFlatten(std::istream& in, ResXMLTree* outTree) {
        std::stringstream input(kXmlPreamble);
        input << in.rdbuf() << std::endl;
        std::shared_ptr<XmlPullParser> xmlParser = std::make_shared<SourceXmlPullParser>(input);
        BigBuffer outBuffer(1024);
        if (!mFlattener->flatten(Source{ "test" }, xmlParser, &outBuffer, {})) {
            return ::testing::AssertionFailure();
        }

        std::unique_ptr<uint8_t[]> data = util::copy(outBuffer);
        if (outTree->setTo(data.get(), outBuffer.size(), true) != NO_ERROR) {
            return ::testing::AssertionFailure();
        }
        return ::testing::AssertionSuccess();
    }

    std::shared_ptr<XmlFlattener> mFlattener;
};

TEST_F(XmlFlattenerTest, ParseSimpleView) {
    std::stringstream input;
    input << "<View xmlns:android=\"http://schemas.android.com/apk/res/android\"" << std::endl
          << "      android:id=\"@id/test\">" << std::endl
          << "</View>" << std::endl;

    ResXMLTree tree;
    ASSERT_TRUE(testFlatten(input, &tree));

    while (tree.next() != ResXMLTree::END_DOCUMENT) {
        ASSERT_NE(tree.getEventType(), ResXMLTree::BAD_DOCUMENT);
    }
}

} // namespace aapt
