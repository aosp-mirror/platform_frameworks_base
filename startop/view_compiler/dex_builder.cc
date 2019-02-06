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

#include <fstream>
#include <memory>

namespace startop {
namespace dex {

using std::shared_ptr;
using std::string;

using ::dex::kAccPublic;
using Op = Instruction::Op;

using Opcode = ::art::Instruction::Code;

const TypeDescriptor TypeDescriptor::Int() { return TypeDescriptor{"I"}; };
const TypeDescriptor TypeDescriptor::Void() { return TypeDescriptor{"V"}; };

namespace {
// From https://source.android.com/devices/tech/dalvik/dex-format#dex-file-magic
constexpr uint8_t kDexFileMagic[]{0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x38, 0x00};

// Strings lengths can be 32 bits long, but encoded as LEB128 this can take up to five bytes.
constexpr size_t kMaxEncodedStringLength{5};

// Converts invoke-* to invoke-*/range
constexpr Opcode InvokeToInvokeRange(Opcode opcode) {
  switch (opcode) {
    case ::art::Instruction::INVOKE_VIRTUAL:
      return ::art::Instruction::INVOKE_VIRTUAL_RANGE;
    case ::art::Instruction::INVOKE_DIRECT:
      return ::art::Instruction::INVOKE_DIRECT_RANGE;
    case ::art::Instruction::INVOKE_STATIC:
      return ::art::Instruction::INVOKE_STATIC_RANGE;
    case ::art::Instruction::INVOKE_INTERFACE:
      return ::art::Instruction::INVOKE_INTERFACE_RANGE;
    default:
      LOG(FATAL) << opcode << " is not a recognized invoke opcode.";
      UNREACHABLE();
  }
}

}  // namespace

std::ostream& operator<<(std::ostream& out, const Instruction::Op& opcode) {
  switch (opcode) {
    case Instruction::Op::kReturn:
      out << "kReturn";
      return out;
    case Instruction::Op::kReturnObject:
      out << "kReturnObject";
      return out;
    case Instruction::Op::kMove:
      out << "kMove";
      return out;
    case Instruction::Op::kMoveObject:
      out << "kMoveObject";
      return out;
    case Instruction::Op::kInvokeVirtual:
      out << "kInvokeVirtual";
      return out;
    case Instruction::Op::kInvokeDirect:
      out << "kInvokeDirect";
      return out;
    case Instruction::Op::kInvokeStatic:
      out << "kInvokeStatic";
      return out;
    case Instruction::Op::kInvokeInterface:
      out << "kInvokeInterface";
      return out;
    case Instruction::Op::kBindLabel:
      out << "kBindLabel";
      return out;
    case Instruction::Op::kBranchEqz:
      out << "kBranchEqz";
      return out;
    case Instruction::Op::kBranchNEqz:
      out << "kBranchNEqz";
      return out;
    case Instruction::Op::kNew:
      out << "kNew";
      return out;
    case Instruction::Op::kCheckCast:
      out << "kCheckCast";
      return out;
  }
}

std::ostream& operator<<(std::ostream& out, const Value& value) {
  if (value.is_register()) {
    out << "Register(" << value.value() << ")";
  } else if (value.is_parameter()) {
    out << "Parameter(" << value.value() << ")";
  } else if (value.is_immediate()) {
    out << "Immediate(" << value.value() << ")";
  } else if (value.is_string()) {
    out << "String(" << value.value() << ")";
  } else if (value.is_label()) {
    out << "Label(" << value.value() << ")";
  } else if (value.is_type()) {
    out << "Type(" << value.value() << ")";
  } else {
    out << "UnknownValue";
  }
  return out;
}

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
//     public static int foo(String s) { return s.length(); }
// }
void WriteTestDexFile(const string& filename) {
  DexBuilder dex_file;

  ClassBuilder cbuilder{dex_file.MakeClass("dextest.DexTest")};
  cbuilder.set_source_file("dextest.java");

  TypeDescriptor string_type = TypeDescriptor::FromClassname("java.lang.String");

  MethodBuilder method{cbuilder.CreateMethod("foo", Prototype{TypeDescriptor::Int(), string_type})};

  Value result = method.MakeRegister();

  MethodDeclData string_length =
      dex_file.GetOrDeclareMethod(string_type, "length", Prototype{TypeDescriptor::Int()});

  method.AddInstruction(Instruction::InvokeVirtual(string_length.id, result, Value::Parameter(0)));
  method.BuildReturn(result);

  method.Encode();

  slicer::MemView image{dex_file.CreateImage()};

  std::ofstream out_file(filename);
  out_file.write(image.ptr<const char>(), image.size());
}

TypeDescriptor TypeDescriptor::FromClassname(const std::string& name) {
  return TypeDescriptor{art::DotToDescriptor(name.c_str())};
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
    ::dex::u4 const new_index = dex_file_->strings_indexes.AllocateIndex();
    dex_file_->strings_map[new_index] = entry;
    entry->orig_index = new_index;
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
  return ClassBuilder{this, name, class_def};
}

ir::Type* DexBuilder::GetOrAddType(const std::string& descriptor) {
  if (types_by_descriptor_.find(descriptor) != types_by_descriptor_.end()) {
    return types_by_descriptor_[descriptor];
  }

  ir::Type* type = Alloc<ir::Type>();
  type->descriptor = GetOrAddString(descriptor);
  types_by_descriptor_[descriptor] = type;
  type->orig_index = dex_file_->types_indexes.AllocateIndex();
  dex_file_->types_map[type->orig_index] = type;
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

const TypeDescriptor& Prototype::ArgType(size_t index) const {
  CHECK_LT(index, param_types_.size());
  return param_types_[index];
}

ClassBuilder::ClassBuilder(DexBuilder* parent, const std::string& name, ir::Class* class_def)
    : parent_(parent), type_descriptor_{TypeDescriptor::FromClassname(name)}, class_(class_def) {}

MethodBuilder ClassBuilder::CreateMethod(const std::string& name, Prototype prototype) {
  ir::MethodDecl* decl = parent_->GetOrDeclareMethod(type_descriptor_, name, prototype).decl;

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
  CHECK(decl_->prototype != nullptr);
  size_t const num_args =
      decl_->prototype->param_types != nullptr ? decl_->prototype->param_types->types.size() : 0;
  code->registers = num_registers_ + num_args + kMaxScratchRegisters;
  code->ins_count = num_args;
  EncodeInstructions();
  code->instructions = slicer::ArrayView<const ::dex::u2>(buffer_.data(), buffer_.size());
  size_t const return_count = decl_->prototype->return_type == dex_->GetOrAddType("V") ? 0 : 1;
  code->outs_count = std::max(return_count, max_args_);
  method->code = code;

  class_->direct_methods.push_back(method);

  return method;
}

Value MethodBuilder::MakeRegister() { return Value::Local(num_registers_++); }

Value MethodBuilder::MakeLabel() {
  labels_.push_back({});
  return Value::Label(labels_.size() - 1);
}

void MethodBuilder::AddInstruction(Instruction instruction) {
  instructions_.push_back(instruction);
}

void MethodBuilder::BuildReturn() { AddInstruction(Instruction::OpNoArgs(Op::kReturn)); }

void MethodBuilder::BuildReturn(Value src, bool is_object) {
  AddInstruction(Instruction::OpWithArgs(
      is_object ? Op::kReturnObject : Op::kReturn, /*destination=*/{}, src));
}

void MethodBuilder::BuildConst4(Value target, int value) {
  CHECK_LT(value, 16);
  AddInstruction(Instruction::OpWithArgs(Op::kMove, target, Value::Immediate(value)));
}

void MethodBuilder::BuildConstString(Value target, const std::string& value) {
  const ir::String* const dex_string = dex_->GetOrAddString(value);
  AddInstruction(Instruction::OpWithArgs(Op::kMove, target, Value::String(dex_string->orig_index)));
}

void MethodBuilder::EncodeInstructions() {
  buffer_.clear();
  for (const auto& instruction : instructions_) {
    EncodeInstruction(instruction);
  }
}

void MethodBuilder::EncodeInstruction(const Instruction& instruction) {
  switch (instruction.opcode()) {
    case Instruction::Op::kReturn:
      return EncodeReturn(instruction, ::art::Instruction::RETURN);
    case Instruction::Op::kReturnObject:
      return EncodeReturn(instruction, ::art::Instruction::RETURN_OBJECT);
    case Instruction::Op::kMove:
    case Instruction::Op::kMoveObject:
      return EncodeMove(instruction);
    case Instruction::Op::kInvokeVirtual:
      return EncodeInvoke(instruction, art::Instruction::INVOKE_VIRTUAL);
    case Instruction::Op::kInvokeDirect:
      return EncodeInvoke(instruction, art::Instruction::INVOKE_DIRECT);
    case Instruction::Op::kInvokeStatic:
      return EncodeInvoke(instruction, art::Instruction::INVOKE_STATIC);
    case Instruction::Op::kInvokeInterface:
      return EncodeInvoke(instruction, art::Instruction::INVOKE_INTERFACE);
    case Instruction::Op::kBindLabel:
      return BindLabel(instruction.args()[0]);
    case Instruction::Op::kBranchEqz:
      return EncodeBranch(art::Instruction::IF_EQZ, instruction);
    case Instruction::Op::kBranchNEqz:
      return EncodeBranch(art::Instruction::IF_NEZ, instruction);
    case Instruction::Op::kNew:
      return EncodeNew(instruction);
    case Instruction::Op::kCheckCast:
      return EncodeCast(instruction);
  }
}

void MethodBuilder::EncodeReturn(const Instruction& instruction, ::art::Instruction::Code opcode) {
  CHECK(!instruction.dest().has_value());
  if (instruction.args().size() == 0) {
    Encode10x(art::Instruction::RETURN_VOID);
  } else {
    CHECK_EQ(1, instruction.args().size());
    size_t source = RegisterValue(instruction.args()[0]);
    Encode11x(opcode, source);
  }
}

void MethodBuilder::EncodeMove(const Instruction& instruction) {
  CHECK(Instruction::Op::kMove == instruction.opcode() ||
        Instruction::Op::kMoveObject == instruction.opcode());
  CHECK(instruction.dest().has_value());
  CHECK(instruction.dest()->is_variable());
  CHECK_EQ(1, instruction.args().size());

  const Value& source = instruction.args()[0];

  if (source.is_immediate()) {
    // TODO: support more registers
    CHECK_LT(RegisterValue(*instruction.dest()), 16);
    Encode11n(art::Instruction::CONST_4, RegisterValue(*instruction.dest()), source.value());
  } else if (source.is_string()) {
    constexpr size_t kMaxRegisters = 256;
    CHECK_LT(RegisterValue(*instruction.dest()), kMaxRegisters);
    CHECK_LT(source.value(), 65536);  // make sure we don't need a jumbo string
    Encode21c(::art::Instruction::CONST_STRING, RegisterValue(*instruction.dest()), source.value());
  } else if (source.is_variable()) {
    // For the moment, we only use this when we need to reshuffle registers for
    // an invoke instruction, meaning we are too big for the 4-bit version.
    // We'll err on the side of caution and always generate the 16-bit form of
    // the instruction.
    Opcode opcode = instruction.opcode() == Instruction::Op::kMove
                        ? ::art::Instruction::MOVE_16
                        : ::art::Instruction::MOVE_OBJECT_16;
    Encode32x(opcode, RegisterValue(*instruction.dest()), RegisterValue(source));
  } else {
    UNIMPLEMENTED(FATAL);
  }
}

void MethodBuilder::EncodeInvoke(const Instruction& instruction, ::art::Instruction::Code opcode) {
  constexpr size_t kMaxArgs = 5;

  // Currently, we only support up to 5 arguments.
  CHECK_LE(instruction.args().size(), kMaxArgs);

  uint8_t arguments[kMaxArgs]{};
  bool has_long_args = false;
  for (size_t i = 0; i < instruction.args().size(); ++i) {
    CHECK(instruction.args()[i].is_variable());
    arguments[i] = RegisterValue(instruction.args()[i]);
    if (!IsShortRegister(arguments[i])) {
      has_long_args = true;
    }
  }

  if (has_long_args) {
    // Some of the registers don't fit in the four bit short form of the invoke
    // instruction, so we need to do an invoke/range. To do this, we need to
    // first move all the arguments into contiguous temporary registers.
    std::array<Value, kMaxArgs> scratch = GetScratchRegisters<kMaxArgs>();

    const auto& prototype = dex_->GetPrototypeByMethodId(instruction.method_id());
    CHECK(prototype.has_value());

    for (size_t i = 0; i < instruction.args().size(); ++i) {
      Instruction::Op move_op;
      if (opcode == ::art::Instruction::INVOKE_VIRTUAL ||
          opcode == ::art::Instruction::INVOKE_DIRECT) {
        // In this case, there is an implicit `this` argument, which is always an object.
        if (i == 0) {
          move_op = Instruction::Op::kMoveObject;
        } else {
          move_op = prototype->ArgType(i - 1).is_object() ? Instruction::Op::kMoveObject
                                                          : Instruction::Op::kMove;
        }
      } else {
        move_op = prototype->ArgType(i).is_object() ? Instruction::Op::kMoveObject
                                                    : Instruction::Op::kMove;
      }

      EncodeMove(Instruction::OpWithArgs(move_op, scratch[i], instruction.args()[i]));
    }

    Encode3rc(InvokeToInvokeRange(opcode),
              instruction.args().size(),
              instruction.method_id(),
              RegisterValue(scratch[0]));
  } else {
    Encode35c(opcode,
              instruction.args().size(),
              instruction.method_id(),
              arguments[0],
              arguments[1],
              arguments[2],
              arguments[3],
              arguments[4]);
  }

  // If there is a return value, add a move-result instruction
  if (instruction.dest().has_value()) {
    Encode11x(instruction.result_is_object() ? art::Instruction::MOVE_RESULT_OBJECT
                                             : art::Instruction::MOVE_RESULT,
              RegisterValue(*instruction.dest()));
  }

  max_args_ = std::max(max_args_, instruction.args().size());
}

// Encodes a conditional branch that tests a single argument.
void MethodBuilder::EncodeBranch(art::Instruction::Code op, const Instruction& instruction) {
  const auto& args = instruction.args();
  const auto& test_value = args[0];
  const auto& branch_target = args[1];
  CHECK_EQ(2, args.size());
  CHECK(test_value.is_variable());
  CHECK(branch_target.is_label());

  size_t instruction_offset = buffer_.size();
  size_t field_offset = buffer_.size() + 1;
  Encode21c(
      op, RegisterValue(test_value), LabelValue(branch_target, instruction_offset, field_offset));
}

void MethodBuilder::EncodeNew(const Instruction& instruction) {
  CHECK_EQ(Instruction::Op::kNew, instruction.opcode());
  CHECK(instruction.dest().has_value());
  CHECK(instruction.dest()->is_variable());
  CHECK_EQ(1, instruction.args().size());

  const Value& type = instruction.args()[0];
  CHECK_LT(RegisterValue(*instruction.dest()), 256);
  CHECK(type.is_type());
  Encode21c(::art::Instruction::NEW_INSTANCE, RegisterValue(*instruction.dest()), type.value());
}

void MethodBuilder::EncodeCast(const Instruction& instruction) {
  CHECK_EQ(Instruction::Op::kCheckCast, instruction.opcode());
  CHECK(instruction.dest().has_value());
  CHECK(instruction.dest()->is_variable());
  CHECK_EQ(1, instruction.args().size());

  const Value& type = instruction.args()[0];
  CHECK_LT(RegisterValue(*instruction.dest()), 256);
  CHECK(type.is_type());
  Encode21c(::art::Instruction::CHECK_CAST, RegisterValue(*instruction.dest()), type.value());
}

size_t MethodBuilder::RegisterValue(const Value& value) const {
  if (value.is_register()) {
    return value.value();
  } else if (value.is_parameter()) {
    return value.value() + num_registers_ + kMaxScratchRegisters;
  }
  CHECK(false && "Must be either a parameter or a register");
  return 0;
}

void MethodBuilder::BindLabel(const Value& label_id) {
  CHECK(label_id.is_label());

  LabelData& label = labels_[label_id.value()];
  CHECK(!label.bound_address.has_value());

  label.bound_address = buffer_.size();

  // patch any forward references to this label.
  for (const auto& ref : label.references) {
    buffer_[ref.field_offset] = *label.bound_address - ref.instruction_offset;
  }
  // No point keeping these around anymore.
  label.references.clear();
}

::dex::u2 MethodBuilder::LabelValue(const Value& label_id, size_t instruction_offset,
                                    size_t field_offset) {
  CHECK(label_id.is_label());
  LabelData& label = labels_[label_id.value()];

  // Short-circuit if the label is already bound.
  if (label.bound_address.has_value()) {
    return *label.bound_address - instruction_offset;
  }

  // Otherwise, save a reference to where we need to back-patch later.
  label.references.push_front(LabelReference{instruction_offset, field_offset});
  return 0;
}

const MethodDeclData& DexBuilder::GetOrDeclareMethod(TypeDescriptor type, const std::string& name,
                                                     Prototype prototype) {
  MethodDeclData& entry = method_id_map_[{type, name, prototype}];

  if (entry.decl == nullptr) {
    // This method has not already been declared, so declare it.
    ir::MethodDecl* decl = dex_file_->Alloc<ir::MethodDecl>();
    // The method id is the last added method.
    size_t id = dex_file_->methods.size() - 1;

    ir::String* dex_name{GetOrAddString(name)};
    decl->name = dex_name;
    decl->parent = GetOrAddType(type.descriptor());
    decl->prototype = GetOrEncodeProto(prototype);

    // update the index -> ir node map (see tools/dexter/slicer/dex_ir_builder.cc)
    auto new_index = dex_file_->methods_indexes.AllocateIndex();
    auto& ir_node = dex_file_->methods_map[new_index];
    CHECK(ir_node == nullptr);
    ir_node = decl;
    decl->orig_index = decl->index = new_index;

    entry = {id, decl};
  }

  return entry;
}

std::optional<const Prototype> DexBuilder::GetPrototypeByMethodId(size_t method_id) const {
  for (const auto& entry : method_id_map_) {
    if (entry.second.id == method_id) {
      return entry.first.prototype;
    }
  }
  return {};
}

ir::Proto* DexBuilder::GetOrEncodeProto(Prototype prototype) {
  ir::Proto*& ir_proto = proto_map_[prototype];
  if (ir_proto == nullptr) {
    ir_proto = prototype.Encode(this);
  }
  return ir_proto;
}

}  // namespace dex
}  // namespace startop
