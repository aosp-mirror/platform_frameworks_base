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

#include "dex/descriptors_names.h"
#include "dex/dex_instruction.h"

#include <fstream>
#include <memory>

namespace startop {
namespace dex {

using std::shared_ptr;
using std::string;

using art::Instruction;
using ::dex::kAccPublic;

const TypeDescriptor TypeDescriptor::Int() { return TypeDescriptor{"I"}; };
const TypeDescriptor TypeDescriptor::Void() { return TypeDescriptor{"V"}; };

namespace {
// From https://source.android.com/devices/tech/dalvik/dex-format#dex-file-magic
constexpr uint8_t kDexFileMagic[]{0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x38, 0x00};

// Strings lengths can be 32 bits long, but encoded as LEB128 this can take up to five bytes.
constexpr size_t kMaxEncodedStringLength{5};

}  // namespace

void* TrackingAllocator::Allocate(size_t size) {
  std::unique_ptr<uint8_t[]> buffer = std::make_unique<uint8_t[]>(size);
  void* raw_buffer = buffer.get();
  allocations_[raw_buffer] = std::move(buffer);
  return raw_buffer;
}

void TrackingAllocator::Free(void* ptr) { allocations_.erase(allocations_.find(ptr)); }

// Write out a DEX file that is basically:
//
// package dextest;
// public class DexTest {
//     public static int foo() { return 5; }
// }
void WriteTestDexFile(const string& filename) {
  DexBuilder dex_file;

  ClassBuilder cbuilder{dex_file.MakeClass("dextest.DexTest")};
  cbuilder.set_source_file("dextest.java");

  MethodBuilder method{cbuilder.CreateMethod("foo", Prototype{TypeDescriptor::Int()})};

  MethodBuilder::Register r = method.MakeRegister();
  method.BuildConst4(r, 5);
  method.BuildReturn(r);

  method.Encode();

  slicer::MemView image{dex_file.CreateImage()};

  std::ofstream out_file(filename);
  out_file.write(image.ptr<const char>(), image.size());
}

DexBuilder::DexBuilder() : dex_file_{std::make_shared<ir::DexFile>()} {
  dex_file_->magic = slicer::MemView{kDexFileMagic, sizeof(kDexFileMagic)};
}

slicer::MemView DexBuilder::CreateImage() {
  ::dex::Writer writer(dex_file_);
  size_t image_size{0};
  ::dex::u1* image = writer.CreateImage(&allocator_, &image_size);
  return slicer::MemView{image, image_size};
}

ir::String* DexBuilder::GetOrAddString(const std::string& string) {
  ir::String*& entry = strings_[string];

  if (entry == nullptr) {
    // Need to encode the length and then write out the bytes, including 1 byte for null terminator
    auto buffer = std::make_unique<uint8_t[]>(string.size() + kMaxEncodedStringLength + 1);
    uint8_t* string_data_start = ::dex::WriteULeb128(buffer.get(), string.size());

    size_t header_length =
        reinterpret_cast<uintptr_t>(string_data_start) - reinterpret_cast<uintptr_t>(buffer.get());

    auto end = std::copy(string.begin(), string.end(), string_data_start);
    *end = '\0';

    entry = Alloc<ir::String>();
    // +1 for null terminator
    entry->data = slicer::MemView{buffer.get(), header_length + string.size() + 1};
    string_data_.push_back(std::move(buffer));
  }
  return entry;
}

ClassBuilder DexBuilder::MakeClass(const std::string& name) {
  auto* class_def = Alloc<ir::Class>();
  ir::Type* type_def = GetOrAddType(art::DotToDescriptor(name.c_str()));
  type_def->class_def = class_def;

  class_def->type = type_def;
  class_def->super_class = GetOrAddType(art::DotToDescriptor("java.lang.Object"));
  class_def->access_flags = kAccPublic;
  return ClassBuilder{this, class_def};
}

// TODO(eholk): we probably want GetOrAddString() also
ir::Type* DexBuilder::GetOrAddType(const std::string& descriptor) {
  if (types_by_descriptor_.find(descriptor) != types_by_descriptor_.end()) {
    return types_by_descriptor_[descriptor];
  }

  ir::Type* type = Alloc<ir::Type>();
  type->descriptor = GetOrAddString(descriptor);
  types_by_descriptor_[descriptor] = type;
  return type;
}

ir::Proto* Prototype::Encode(DexBuilder* dex) const {
  auto* proto = dex->Alloc<ir::Proto>();
  proto->shorty = dex->GetOrAddString(Shorty());
  proto->return_type = dex->GetOrAddType(return_type_.descriptor());
  if (param_types_.size() > 0) {
    proto->param_types = dex->Alloc<ir::TypeList>();
    for (const auto& param_type : param_types_) {
      proto->param_types->types.push_back(dex->GetOrAddType(param_type.descriptor()));
    }
  } else {
    proto->param_types = nullptr;
  }
  return proto;
}

std::string Prototype::Shorty() const {
  std::string shorty;
  shorty.append(return_type_.short_descriptor());
  for (const auto& type_descriptor : param_types_) {
    shorty.append(type_descriptor.short_descriptor());
  }
  return shorty;
}

ClassBuilder::ClassBuilder(DexBuilder* parent, ir::Class* class_def)
    : parent_(parent), class_(class_def) {}

MethodBuilder ClassBuilder::CreateMethod(const std::string& name, Prototype prototype) {
  ir::String* dex_name{parent_->GetOrAddString(name)};

  auto* decl = parent_->Alloc<ir::MethodDecl>();
  decl->name = dex_name;
  decl->parent = class_->type;
  decl->prototype = prototype.Encode(parent_);

  return MethodBuilder{parent_, class_, decl};
}

void ClassBuilder::set_source_file(const string& source) {
  class_->source_file = parent_->GetOrAddString(source);
}

MethodBuilder::MethodBuilder(DexBuilder* dex, ir::Class* class_def, ir::MethodDecl* decl)
    : dex_{dex}, class_{class_def}, decl_{decl} {}

ir::EncodedMethod* MethodBuilder::Encode() {
  auto* method = dex_->Alloc<ir::EncodedMethod>();
  method->decl = decl_;

  // TODO: make access flags configurable
  method->access_flags = kAccPublic | ::dex::kAccStatic;

  auto* code = dex_->Alloc<ir::Code>();
  code->registers = num_registers_;
  // TODO: support ins and outs
  code->instructions = slicer::ArrayView<const ::dex::u2>(buffer_.data(), buffer_.size());
  method->code = code;

  class_->direct_methods.push_back(method);

  return method;
}

MethodBuilder::Register MethodBuilder::MakeRegister() { return num_registers_++; }

void MethodBuilder::BuildReturn() { buffer_.push_back(Instruction::RETURN_VOID); }

void MethodBuilder::BuildReturn(Register src) { buffer_.push_back(Instruction::RETURN | src << 8); }

void MethodBuilder::BuildConst4(Register target, int value) {
  DCHECK_LT(value, 16);
  // TODO: support more registers
  DCHECK_LT(target, 16);
  buffer_.push_back(Instruction::CONST_4 | (value << 12) | (target << 8));
}

}  // namespace dex
}  // namespace startop
