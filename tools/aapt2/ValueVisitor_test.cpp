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

#include <gtest/gtest.h>
#include <string>

#include "ResourceValues.h"
#include "util/Util.h"
#include "ValueVisitor.h"
#include "test/Builders.h"

namespace aapt {

struct SingleReferenceVisitor : public ValueVisitor {
    using ValueVisitor::visit;

    Reference* visited = nullptr;

    void visit(Reference* ref) override {
        visited = ref;
    }
};

struct StyleVisitor : public ValueVisitor {
    using ValueVisitor::visit;

    std::list<Reference*> visitedRefs;
    Style* visitedStyle = nullptr;

    void visit(Reference* ref) override {
        visitedRefs.push_back(ref);
    }

    void visit(Style* style) override {
        visitedStyle = style;
        ValueVisitor::visit(style);
    }
};

TEST(ValueVisitorTest, VisitsReference) {
    Reference ref(ResourceName{u"android", ResourceType::kAttr, u"foo"});
    SingleReferenceVisitor visitor;
    ref.accept(&visitor);

    EXPECT_EQ(visitor.visited, &ref);
}

TEST(ValueVisitorTest, VisitsReferencesInStyle) {
    std::unique_ptr<Style> style = test::StyleBuilder()
            .setParent(u"@android:style/foo")
            .addItem(u"@android:attr/one", test::buildReference(u"@android:id/foo"))
            .build();

    StyleVisitor visitor;
    style->accept(&visitor);

    ASSERT_EQ(style.get(), visitor.visitedStyle);

    // Entry attribute references, plus the parent reference, plus one value reference.
    ASSERT_EQ(style->entries.size() + 2, visitor.visitedRefs.size());
}

TEST(ValueVisitorTest, ValueCast) {
    std::unique_ptr<Reference> ref = test::buildReference(u"@android:color/white");
    EXPECT_NE(valueCast<Reference>(ref.get()), nullptr);

    std::unique_ptr<Style> style = test::StyleBuilder()
            .addItem(u"@android:attr/foo", test::buildReference(u"@android:color/black"))
            .build();
    EXPECT_NE(valueCast<Style>(style.get()), nullptr);
    EXPECT_EQ(valueCast<Reference>(style.get()), nullptr);
}

} // namespace aapt
