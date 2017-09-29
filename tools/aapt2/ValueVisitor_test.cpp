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

#include "ValueVisitor.h"

#include <string>

#include "ResourceValues.h"
#include "test/Test.h"
#include "util/Util.h"

namespace aapt {

struct SingleReferenceVisitor : public DescendingValueVisitor {
  using DescendingValueVisitor::Visit;

  Reference* visited = nullptr;

  void Visit(Reference* ref) override { visited = ref; }
};

struct StyleVisitor : public DescendingValueVisitor {
  using DescendingValueVisitor::Visit;

  std::list<Reference*> visited_refs;
  Style* visited_style = nullptr;

  void Visit(Reference* ref) override { visited_refs.push_back(ref); }

  void Visit(Style* style) override {
    visited_style = style;
    DescendingValueVisitor::Visit(style);
  }
};

TEST(ValueVisitorTest, VisitsReference) {
  Reference ref(ResourceName{"android", ResourceType::kAttr, "foo"});
  SingleReferenceVisitor visitor;
  ref.Accept(&visitor);

  EXPECT_EQ(visitor.visited, &ref);
}

TEST(ValueVisitorTest, VisitsReferencesInStyle) {
  std::unique_ptr<Style> style =
      test::StyleBuilder()
          .SetParent("android:style/foo")
          .AddItem("android:attr/one", test::BuildReference("android:id/foo"))
          .Build();

  StyleVisitor visitor;
  style->Accept(&visitor);

  ASSERT_EQ(style.get(), visitor.visited_style);

  // Entry attribute references, plus the parent reference, plus one value
  // reference.
  ASSERT_EQ(style->entries.size() + 2, visitor.visited_refs.size());
}

TEST(ValueVisitorTest, ValueCast) {
  std::unique_ptr<Reference> ref = test::BuildReference("android:color/white");
  EXPECT_NE(ValueCast<Reference>(ref.get()), nullptr);

  std::unique_ptr<Style> style =
      test::StyleBuilder()
          .AddItem("android:attr/foo",
                   test::BuildReference("android:color/black"))
          .Build();
  EXPECT_NE(ValueCast<Style>(style.get()), nullptr);
  EXPECT_EQ(ValueCast<Reference>(style.get()), nullptr);
}

}  // namespace aapt
