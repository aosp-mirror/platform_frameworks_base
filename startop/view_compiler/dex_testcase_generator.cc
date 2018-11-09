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

#include "android-base/logging.h"
#include "dex_builder.h"

#include <fstream>
#include <string>

// Adding tests here requires changes in several other places. See README.md in
// the view_compiler directory for more information.

using namespace startop::dex;
using namespace std;

void GenerateTrivialDexFile(const string& outdir) {
  DexBuilder dex_file;

  ClassBuilder cbuilder{dex_file.MakeClass("android.startop.test.testcases.Trivial")};
  cbuilder.set_source_file("dex_testcase_generator.cc#GenerateTrivialDexFile");

  slicer::MemView image{dex_file.CreateImage()};
  std::ofstream out_file(outdir + "/trivial.dex");
  out_file.write(image.ptr<const char>(), image.size());
}

// Generates test cases that test around 1 instruction.
void GenerateSimpleTestCases(const string& outdir) {
  DexBuilder dex_file;

  ClassBuilder cbuilder{dex_file.MakeClass("android.startop.test.testcases.SimpleTests")};
  cbuilder.set_source_file("dex_testcase_generator.cc#GenerateSimpleTestCases");

  // int return5() { return 5; }
  auto return5{cbuilder.CreateMethod("return5", Prototype{TypeDescriptor::Int()})};
  Value r{return5.MakeRegister()};
  return5.BuildConst4(r, 5);
  return5.BuildReturn(r);
  return5.Encode();

  // // int returnParam(int x) { return x; }
  auto returnParam{cbuilder.CreateMethod("returnParam",
                                         Prototype{TypeDescriptor::Int(), TypeDescriptor::Int()})};
  returnParam.BuildReturn(Value::Parameter(0));
  returnParam.Encode();

  // int returnStringLength(String x) { return x.length(); }
  auto string_type{TypeDescriptor::FromClassname("java.lang.String")};
  MethodDeclData string_length{
      dex_file.GetOrDeclareMethod(string_type, "length", Prototype{TypeDescriptor::Int()})};

  auto returnStringLength{
      cbuilder.CreateMethod("returnStringLength", Prototype{TypeDescriptor::Int(), string_type})};
  Value result = returnStringLength.MakeRegister();
  returnStringLength.AddInstruction(
      Instruction::InvokeVirtual(string_length.id, result, Value::Parameter(0)));
  returnStringLength.BuildReturn(result);
  returnStringLength.Encode();

  slicer::MemView image{dex_file.CreateImage()};
  std::ofstream out_file(outdir + "/simple.dex");
  out_file.write(image.ptr<const char>(), image.size());
}

int main(int argc, char** argv) {
  CHECK_EQ(argc, 2);

  string outdir = argv[1];

  GenerateTrivialDexFile(outdir);
  GenerateSimpleTestCases(outdir);
}
