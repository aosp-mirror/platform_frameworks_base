/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <memory>
#include <type_traits>
#include <utility>

#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "idmap2/Result.h"

namespace android::idmap2 {

struct Container {
  uint32_t value;  // NOLINT(misc-non-private-member-variables-in-classes)
};

// Tests: Error

TEST(ResultTests, ErrorTraits) {
  ASSERT_TRUE(std::is_move_constructible<Error>::value);
  ASSERT_TRUE(std::is_move_assignable<Error>::value);
  ASSERT_TRUE(std::is_copy_constructible<Error>::value);
  ASSERT_TRUE(std::is_copy_assignable<Error>::value);
}

TEST(ResultTests, ErrorCtorFormat) {
  Error e("%s=0x%08x", "resid", 0x7f010002);
  ASSERT_EQ(e.GetMessage(), "resid=0x7f010002");
}

TEST(ResultTests, ErrorPropagateParent) {
  Error e1("foo");
  ASSERT_EQ(e1.GetMessage(), "foo");

  Error e2(e1, "bar");
  ASSERT_EQ(e2.GetMessage(), "foo -> bar");

  Error e3(e2);  // NOLINT(performance-unnecessary-copy-initialization)
  ASSERT_EQ(e3.GetMessage(), "foo -> bar");

  Error e4(e3, "%02d", 1);
  ASSERT_EQ(e4.GetMessage(), "foo -> bar -> 01");
}

// Tests: Result<T> member functions

// Result(const Result&)
TEST(ResultTests, CopyConstructor) {
  Result<uint32_t> r1(42U);

  Result<uint32_t> r2(r1);
  ASSERT_TRUE(r2);
  ASSERT_EQ(*r2, 42U);

  Result<uint32_t> r3 = r2;
  ASSERT_TRUE(r3);
  ASSERT_EQ(*r3, 42U);
}

// Result(const T&)
TEST(ResultTests, Constructor) {
  uint32_t v = 42U;
  Result<uint32_t> r1(v);
  ASSERT_TRUE(r1);
  ASSERT_EQ(*r1, 42U);

  Error e("foo");
  Result<uint32_t> r2(e);
  ASSERT_FALSE(r2);
  ASSERT_EQ(r2.GetErrorMessage(), "foo");
}

// Result(const T&&)
TEST(ResultTests, MoveConstructor) {
  Result<uint32_t> r1(42U);
  ASSERT_TRUE(r1);
  ASSERT_EQ(*r1, 42U);

  Result<uint32_t> r2(Error("foo"));
  ASSERT_FALSE(r2);
  ASSERT_EQ(r2.GetErrorMessage(), "foo");
}

// operator=
TEST(ResultTests, CopyAssignmentOperator) {
  // note: 'Result<...> r2 = r1;' calls the copy ctor
  Result<uint32_t> r1(42U);
  Result<uint32_t> r2(0U);
  r2 = r1;
  ASSERT_TRUE(r2);
  ASSERT_EQ(*r2, 42U);

  Result<uint32_t> r3(Error("foo"));
  r2 = r3;
  ASSERT_FALSE(r2);
  ASSERT_EQ(r2.GetErrorMessage(), "foo");
}

TEST(ResultTests, MoveAssignmentOperator) {
  Result<uint32_t> r(0U);
  r = Result<uint32_t>(42U);
  ASSERT_TRUE(r);
  ASSERT_EQ(*r, 42U);

  r = Result<uint32_t>(Error("foo"));
  ASSERT_FALSE(r);
  ASSERT_EQ(r.GetErrorMessage(), "foo");
}

// operator bool()
TEST(ResultTests, BoolOperator) {
  Result<uint32_t> r1(42U);
  ASSERT_TRUE(r1);
  ASSERT_EQ(*r1, 42U);

  Result<uint32_t> r2(Error("foo"));
  ASSERT_FALSE(r2);
  ASSERT_EQ(r2.GetErrorMessage(), "foo");
}

// operator*
TEST(ResultTests, IndirectionOperator) {
  const Result<uint32_t> r1(42U);
  ASSERT_TRUE(r1);
  ASSERT_EQ(*r1, 42U);

  const Result<Container> r2(Container{42U});
  ASSERT_TRUE(r2);
  const Container& c = *r2;
  ASSERT_EQ(c.value, 42U);

  Result<Container> r3(Container{42U});
  ASSERT_TRUE(r3);
  ASSERT_EQ((*r3).value, 42U);
  (*r3).value = 0U;
  ASSERT_EQ((*r3).value, 0U);
}

// operator->
TEST(ResultTests, DereferenceOperator) {
  const Result<Container> r1(Container{42U});
  ASSERT_TRUE(r1);
  ASSERT_EQ(r1->value, 42U);

  Result<Container> r2(Container{42U});
  ASSERT_TRUE(r2);
  ASSERT_EQ(r2->value, 42U);
  r2->value = 0U;
  ASSERT_EQ(r2->value, 0U);
}

// Tests: intended use of Result<T>

TEST(ResultTests, ResultTraits) {
  ASSERT_TRUE(std::is_move_constructible<Result<uint32_t>>::value);
  ASSERT_TRUE(std::is_move_assignable<Result<uint32_t>>::value);
  ASSERT_TRUE(std::is_copy_constructible<Result<uint32_t>>::value);
  ASSERT_TRUE(std::is_copy_assignable<Result<uint32_t>>::value);
}

TEST(ResultTests, UnitTypeResult) {
  Result<Unit> r(Unit{});
  ASSERT_TRUE(r);
}

struct RefCountData {
  int ctor;       // NOLINT(misc-non-private-member-variables-in-classes)
  int copy_ctor;  // NOLINT(misc-non-private-member-variables-in-classes)
  int dtor;       // NOLINT(misc-non-private-member-variables-in-classes)
  int move;       // NOLINT(misc-non-private-member-variables-in-classes)
};

class RefCountContainer {
 public:
  explicit RefCountContainer(RefCountData& data) : data_(data) {
    ++data_.ctor;
  }

  RefCountContainer(RefCountContainer const&) = delete;

  RefCountContainer(RefCountContainer&& rhs) noexcept : data_(rhs.data_) {
    ++data_.copy_ctor;
  }

  RefCountContainer& operator=(RefCountContainer const&) = delete;

  RefCountContainer& operator=(RefCountContainer&& rhs) noexcept {
    data_ = rhs.data_;
    ++data_.move;
    return *this;
  }

  ~RefCountContainer() {
    ++data_.dtor;
  }

 private:
  RefCountData& data_;
};

TEST(ResultTests, ReferenceCount) {
  ASSERT_TRUE(std::is_move_constructible<RefCountContainer>::value);
  ASSERT_TRUE(std::is_move_assignable<RefCountContainer>::value);
  ASSERT_FALSE(std::is_copy_constructible<RefCountContainer>::value);
  ASSERT_FALSE(std::is_copy_assignable<RefCountContainer>::value);

  RefCountData rc{0, 0, 0, 0};
  { Result<RefCountContainer> r(RefCountContainer{rc}); }
  ASSERT_EQ(rc.ctor, 1);
  ASSERT_EQ(rc.copy_ctor, 1);
  ASSERT_EQ(rc.move, 0);
  ASSERT_EQ(rc.dtor, 2);
}

Result<Container> CreateContainer(bool succeed) {
  if (!succeed) {
    return Error("foo");
  }
  return Container{42U};
}

TEST(ResultTests, FunctionReturn) {
  auto r1 = CreateContainer(true);
  ASSERT_TRUE(r1);
  ASSERT_EQ(r1->value, 42U);

  auto r2 = CreateContainer(false);
  ASSERT_FALSE(r2);
  ASSERT_EQ(r2.GetErrorMessage(), "foo");
  ASSERT_EQ(r2.GetError().GetMessage(), "foo");
}

Result<Container> FailToCreateContainer() {
  auto container = CreateContainer(false);
  if (!container) {
    return Error(container.GetError(), "bar");
  }
  return container;
}

TEST(ResultTests, CascadeError) {
  auto container = FailToCreateContainer();
  ASSERT_FALSE(container);
  ASSERT_EQ(container.GetErrorMessage(), "foo -> bar");
}

struct NoCopyContainer {
  uint32_t value;  // NOLINT(misc-non-private-member-variables-in-classes)
  NoCopyContainer(const NoCopyContainer&) = delete;
  NoCopyContainer& operator=(const NoCopyContainer&) = delete;
};

Result<std::unique_ptr<NoCopyContainer>> CreateNoCopyContainer(bool succeed) {
  if (!succeed) {
    return Error("foo");
  }
  std::unique_ptr<NoCopyContainer> p(new NoCopyContainer{0U});
  p->value = 42U;
  return std::move(p);
}

TEST(ResultTests, UniquePtr) {
  auto r1 = CreateNoCopyContainer(true);
  ASSERT_TRUE(r1);
  ASSERT_EQ((*r1)->value, 42U);
  (*r1)->value = 0U;
  ASSERT_EQ((*r1)->value, 0U);

  auto r2 = CreateNoCopyContainer(false);
  ASSERT_FALSE(r2);
  ASSERT_EQ(r2.GetErrorMessage(), "foo");
}

}  // namespace android::idmap2
