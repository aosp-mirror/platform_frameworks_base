/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "dex_builder.h"

#include "dex/art_dex_file_loader.h"
#include "dex/dex_file.h"
#include "gtest/gtest.h"

using namespace startop::dex;

// Takes a DexBuilder, encodes it into an in-memory DEX file, verifies the resulting DEX file and
// returns whether the verification was successful.
bool EncodeAndVerify(DexBuilder* dex_file) {
  slicer::MemView image{dex_file->CreateImage()};

  art::ArtDexFileLoader loader;
  std::string error_msg;
  std::unique_ptr<const art::DexFile> loaded_dex_file{loader.Open(image.ptr<const uint8_t>(),
                                                                  image.size(),
                                                                  /*location=*/"",
                                                                  /*location_checksum=*/0,
                                                                  /*oat_dex_file=*/nullptr,
                                                                  /*verify=*/true,
                                                                  /*verify_checksum=*/false,
                                                                  &error_msg)};
  return loaded_dex_file != nullptr;
}

// Write out and verify a DEX file that corresponds to:
//
// package dextest;
// public class DexTest {
//     public static void foo() {}
// }
TEST(DexBuilderTest, VerifyDexWithClassMethod) {
  DexBuilder dex_file;

  auto cbuilder{dex_file.MakeClass("dextest.DexTest")};

  auto method{cbuilder.CreateMethod("foo", Prototype{TypeDescriptor::Void()})};
  method.BuildReturn();
  method.Encode();

  EXPECT_TRUE(EncodeAndVerify(&dex_file));
}

// Makes sure a bad DEX class fails to verify.
TEST(DexBuilderTest, VerifyBadDexWithClassMethod) {
  DexBuilder dex_file;

  auto cbuilder{dex_file.MakeClass("dextest.DexTest")};

  // This method has the error, because methods cannot take Void() as a parameter.
  auto method{
      cbuilder.CreateMethod("foo", Prototype{TypeDescriptor::Void(), TypeDescriptor::Void()})};
  method.BuildReturn();
  method.Encode();

  EXPECT_FALSE(EncodeAndVerify(&dex_file));
}

// Write out and verify a DEX file that corresponds to:
//
// package dextest;
// public class DexTest {
//     public static int foo() { return 5; }
// }
TEST(DexBuilderTest, VerifyDexReturn5) {
  DexBuilder dex_file;

  auto cbuilder{dex_file.MakeClass("dextest.DexTest")};

  auto method{cbuilder.CreateMethod("foo", Prototype{TypeDescriptor::Int()})};
  auto r = method.MakeRegister();
  method.BuildConst4(r, 5);
  method.BuildReturn(r);
  method.Encode();

  EXPECT_TRUE(EncodeAndVerify(&dex_file));
}

// Write out and verify a DEX file that corresponds to:
//
// package dextest;
// public class DexTest {
//     public static int foo(int x) { return x; }
// }
TEST(DexBuilderTest, VerifyDexReturnIntParam) {
  DexBuilder dex_file;

  auto cbuilder{dex_file.MakeClass("dextest.DexTest")};

  auto method{
      cbuilder.CreateMethod("foo", Prototype{TypeDescriptor::Int(), TypeDescriptor::Int()})};
  method.BuildReturn(Value::Parameter(0));
  method.Encode();

  EXPECT_TRUE(EncodeAndVerify(&dex_file));
}

// Write out and verify a DEX file that corresponds to:
//
// package dextest;
// public class DexTest {
//     public static int foo(String s) { return s.length(); }
// }
TEST(DexBuilderTest, VerifyDexCallStringLength) {
  DexBuilder dex_file;

  auto cbuilder{dex_file.MakeClass("dextest.DexTest")};

  MethodBuilder method{cbuilder.CreateMethod(
      "foo", Prototype{TypeDescriptor::Int(), TypeDescriptor::FromClassname("java.lang.String")})};

  Value result = method.MakeRegister();

  MethodDeclData string_length =
      dex_file.GetOrDeclareMethod(TypeDescriptor::FromClassname("java.lang.String"),
                                  "length",
                                  Prototype{TypeDescriptor::Int()});

  method.AddInstruction(Instruction::InvokeVirtual(string_length.id, result, Value::Parameter(0)));
  method.BuildReturn(result);

  method.Encode();

  EXPECT_TRUE(EncodeAndVerify(&dex_file));
}

// Write out and verify a DEX file that corresponds to:
//
// package dextest;
// public class DexTest {
//     public static int foo(String s) { return s.length(); }
// }
TEST(DexBuilderTest, VerifyDexCallManyRegisters) {
  DexBuilder dex_file;

  auto cbuilder{dex_file.MakeClass("dextest.DexTest")};

  MethodBuilder method{cbuilder.CreateMethod(
      "foo", Prototype{TypeDescriptor::Int()})};

  Value result = method.MakeRegister();

  // Make a bunch of registers
  for (size_t i = 0; i < 25; ++i) {
    method.MakeRegister();
  }

  // Now load a string literal into a register
  Value string_val = method.MakeRegister();
  method.BuildConstString(string_val, "foo");

  MethodDeclData string_length =
      dex_file.GetOrDeclareMethod(TypeDescriptor::FromClassname("java.lang.String"),
                                  "length",
                                  Prototype{TypeDescriptor::Int()});

  method.AddInstruction(Instruction::InvokeVirtual(string_length.id, result, string_val));
  method.BuildReturn(result);

  method.Encode();

  EXPECT_TRUE(EncodeAndVerify(&dex_file));
}
