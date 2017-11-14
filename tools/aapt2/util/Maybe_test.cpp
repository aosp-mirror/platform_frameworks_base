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

#include "util/Maybe.h"

#include <string>

#include "test/Test.h"

namespace aapt {

struct Dummy {
  Dummy() {
    data = new int;
    *data = 1;
    std::cerr << "Construct Dummy{0x" << (void*)this << "} with data=0x"
              << (void*)data << std::endl;
  }

  Dummy(const Dummy& rhs) {
    data = nullptr;
    if (rhs.data) {
      data = new int;
      *data = *rhs.data;
    }
    std::cerr << "CopyConstruct Dummy{0x" << (void*)this << "} from Dummy{0x"
              << (const void*)&rhs << "}" << std::endl;
  }

  Dummy(Dummy&& rhs) {
    data = rhs.data;
    rhs.data = nullptr;
    std::cerr << "MoveConstruct Dummy{0x" << (void*)this << "} from Dummy{0x"
              << (const void*)&rhs << "}" << std::endl;
  }

  Dummy& operator=(const Dummy& rhs) {
    delete data;
    data = nullptr;

    if (rhs.data) {
      data = new int;
      *data = *rhs.data;
    }
    std::cerr << "CopyAssign Dummy{0x" << (void*)this << "} from Dummy{0x"
              << (const void*)&rhs << "}" << std::endl;
    return *this;
  }

  Dummy& operator=(Dummy&& rhs) {
    delete data;
    data = rhs.data;
    rhs.data = nullptr;
    std::cerr << "MoveAssign Dummy{0x" << (void*)this << "} from Dummy{0x"
              << (const void*)&rhs << "}" << std::endl;
    return *this;
  }

  ~Dummy() {
    std::cerr << "Destruct Dummy{0x" << (void*)this << "} with data=0x"
              << (void*)data << std::endl;
    delete data;
  }

  int* data;
};

TEST(MaybeTest, MakeNothing) {
  Maybe<int> val = make_nothing<int>();
  EXPECT_FALSE(val);

  Maybe<std::string> val2 = make_nothing<std::string>();
  EXPECT_FALSE(val2);

  val2 = make_nothing<std::string>();
  EXPECT_FALSE(val2);
}

TEST(MaybeTest, MakeSomething) {
  Maybe<int> val = make_value(23);
  ASSERT_TRUE(val);
  EXPECT_EQ(23, val.value());

  Maybe<std::string> val2 = make_value(std::string("hey"));
  ASSERT_TRUE(val2);
  EXPECT_EQ(std::string("hey"), val2.value());
}

TEST(MaybeTest, Lifecycle) {
  Maybe<Dummy> val = make_nothing<Dummy>();

  Maybe<Dummy> val2 = make_value(Dummy());
}

TEST(MaybeTest, MoveAssign) {
  Maybe<Dummy> val;
  {
    Maybe<Dummy> val2 = Dummy();
    val = std::move(val2);
  }
}

TEST(MaybeTest, Equality) {
  Maybe<int> a = 1;
  Maybe<int> b = 1;
  Maybe<int> c;

  Maybe<int> emptyA, emptyB;

  EXPECT_EQ(a, b);
  EXPECT_EQ(b, a);
  EXPECT_NE(a, c);
  EXPECT_EQ(emptyA, emptyB);
}

}  // namespace aapt
