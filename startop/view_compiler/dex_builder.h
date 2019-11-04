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
#ifndef DEX_BUILDER_H_
#define DEX_BUILDER_H_

#include <array>
#include <forward_list>
#include <map>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include "android-base/logging.h"

#include "slicer/dex_bytecode.h"
#include "slicer/dex_ir.h"
#include "slicer/writer.h"

namespace startop {
namespace dex {

// TODO: remove this once the dex generation code is complete.
void WriteTestDexFile(const std::string& filename);

//////////////////////////
// Forward declarations //
//////////////////////////
class DexBuilder;

// Our custom allocator for dex::Writer
//
// This keeps track of all allocations and ensures they are freed when
// TrackingAllocator is destroyed. Pointers to memory allocated by this
// allocator must not outlive the allocator.
class TrackingAllocator : public ::dex::Writer::Allocator {
 public:
  virtual void* Allocate(size_t size);
  virtual void Free(void* ptr);

 private:
  std::unordered_map<void*, std::unique_ptr<uint8_t[]>> allocations_;
};

// Represents a DEX type descriptor.
//
// TODO: add a way to create a descriptor for a reference of a class type.
class TypeDescriptor {
 public:
  // Named constructors for base type descriptors.
  static const TypeDescriptor Int();
  static const TypeDescriptor Void();

  // Creates a type descriptor from a fully-qualified class name. For example, it turns the class
  // name java.lang.Object into the descriptor Ljava/lang/Object.
  static TypeDescriptor FromClassname(const std::string& name);

  // Return the full descriptor, such as I or Ljava/lang/Object
  const std::string& descriptor() const { return descriptor_; }
  // Return the shorty descriptor, such as I or L
  std::string short_descriptor() const { return descriptor().substr(0, 1); }

  bool is_object() const { return short_descriptor() == "L"; }

  bool operator<(const TypeDescriptor& rhs) const { return descriptor_ < rhs.descriptor_; }

 private:
  explicit TypeDescriptor(std::string descriptor) : descriptor_{descriptor} {}

  const std::string descriptor_;
};

// Defines a function signature. For example, Prototype{TypeDescriptor::VOID, TypeDescriptor::Int}
// represents the function type (Int) -> Void.
class Prototype {
 public:
  template <typename... TypeDescriptors>
  explicit Prototype(TypeDescriptor return_type, TypeDescriptors... param_types)
      : return_type_{return_type}, param_types_{param_types...} {}

  // Encode this prototype into the dex file.
  ir::Proto* Encode(DexBuilder* dex) const;

  // Get the shorty descriptor, such as VII for (Int, Int) -> Void
  std::string Shorty() const;

  const TypeDescriptor& ArgType(size_t index) const;

  bool operator<(const Prototype& rhs) const {
    return std::make_tuple(return_type_, param_types_) <
           std::make_tuple(rhs.return_type_, rhs.param_types_);
  }

 private:
  const TypeDescriptor return_type_;
  const std::vector<TypeDescriptor> param_types_;
};

// Represents a DEX register or constant. We separate regular registers and parameters
// because we will not know the real parameter id until after all instructions
// have been generated.
class Value {
 public:
  static constexpr Value Local(size_t id) { return Value{id, Kind::kLocalRegister}; }
  static constexpr Value Parameter(size_t id) { return Value{id, Kind::kParameter}; }
  static constexpr Value Immediate(size_t value) { return Value{value, Kind::kImmediate}; }
  static constexpr Value String(size_t value) { return Value{value, Kind::kString}; }
  static constexpr Value Label(size_t id) { return Value{id, Kind::kLabel}; }
  static constexpr Value Type(size_t id) { return Value{id, Kind::kType}; }

  bool is_register() const { return kind_ == Kind::kLocalRegister; }
  bool is_parameter() const { return kind_ == Kind::kParameter; }
  bool is_variable() const { return is_register() || is_parameter(); }
  bool is_immediate() const { return kind_ == Kind::kImmediate; }
  bool is_string() const { return kind_ == Kind::kString; }
  bool is_label() const { return kind_ == Kind::kLabel; }
  bool is_type() const { return kind_ == Kind::kType; }

  size_t value() const { return value_; }

  constexpr Value() : value_{0}, kind_{Kind::kInvalid} {}

 private:
  enum class Kind { kInvalid, kLocalRegister, kParameter, kImmediate, kString, kLabel, kType };

  size_t value_;
  Kind kind_;

  constexpr Value(size_t value, Kind kind) : value_{value}, kind_{kind} {}
};

// Represents an allocated register returned by MethodBuilder::AllocRegister
class LiveRegister {
  friend class MethodBuilder;

 public:
  LiveRegister(LiveRegister&& other) : liveness_{other.liveness_}, index_{other.index_} {
    other.index_ = {};
  };
  ~LiveRegister() {
    if (index_.has_value()) {
      (*liveness_)[*index_] = false;
    }
  };

  operator const Value() const { return Value::Local(*index_); }

 private:
  LiveRegister(std::vector<bool>* liveness, size_t index) : liveness_{liveness}, index_{index} {}

  std::vector<bool>* const liveness_;
  std::optional<size_t> index_;
};

// A virtual instruction. We convert these to real instructions in MethodBuilder::Encode.
// Virtual instructions are needed to keep track of information that is not known until all of the
// code is generated. This information includes things like how many local registers are created and
// branch target locations.
class Instruction {
 public:
  // The operation performed by this instruction. These are virtual instructions that do not
  // correspond exactly to DEX instructions.
  enum class Op {
    kBindLabel,
    kBranchEqz,
    kBranchNEqz,
    kCheckCast,
    kGetInstanceField,
    kGetStaticField,
    kInvokeDirect,
    kInvokeInterface,
    kInvokeStatic,
    kInvokeVirtual,
    kMove,
    kMoveObject,
    kNew,
    kReturn,
    kReturnObject,
    kSetInstanceField,
    kSetStaticField
  };

  ////////////////////////
  // Named Constructors //
  ////////////////////////

  // For instructions with no return value and no arguments.
  static inline Instruction OpNoArgs(Op opcode) {
    return Instruction{opcode, /*index_argument*/ 0, /*dest*/ {}};
  }
  // For most instructions, which take some number of arguments and have an optional return value.
  template <typename... T>
  static inline Instruction OpWithArgs(Op opcode, std::optional<const Value> dest,
                                       const T&... args) {
    return Instruction{opcode, /*index_argument=*/0, /*result_is_object=*/false, dest, args...};
  }

  // A cast instruction. Basically, `(type)val`
  static inline Instruction Cast(Value val, Value type) {
    CHECK(type.is_type());
    return OpWithArgs(Op::kCheckCast, val, type);
  }

  // For method calls.
  template <typename... T>
  static inline Instruction InvokeVirtual(size_t index_argument, std::optional<const Value> dest,
                                          Value this_arg, T... args) {
    return Instruction{
        Op::kInvokeVirtual, index_argument, /*result_is_object=*/false, dest, this_arg, args...};
  }
  // Returns an object
  template <typename... T>
  static inline Instruction InvokeVirtualObject(size_t index_argument,
                                                std::optional<const Value> dest, Value this_arg,
                                                const T&... args) {
    return Instruction{
        Op::kInvokeVirtual, index_argument, /*result_is_object=*/true, dest, this_arg, args...};
  }
  // For direct calls (basically, constructors).
  template <typename... T>
  static inline Instruction InvokeDirect(size_t index_argument, std::optional<const Value> dest,
                                         Value this_arg, const T&... args) {
    return Instruction{
        Op::kInvokeDirect, index_argument, /*result_is_object=*/false, dest, this_arg, args...};
  }
  // Returns an object
  template <typename... T>
  static inline Instruction InvokeDirectObject(size_t index_argument,
                                               std::optional<const Value> dest, Value this_arg,
                                               T... args) {
    return Instruction{
        Op::kInvokeDirect, index_argument, /*result_is_object=*/true, dest, this_arg, args...};
  }
  // For static calls.
  template <typename... T>
  static inline Instruction InvokeStatic(size_t index_argument, std::optional<const Value> dest,
                                         T... args) {
    return Instruction{
        Op::kInvokeStatic, index_argument, /*result_is_object=*/false, dest, args...};
  }
  // Returns an object
  template <typename... T>
  static inline Instruction InvokeStaticObject(size_t index_argument,
                                               std::optional<const Value> dest, T... args) {
    return Instruction{Op::kInvokeStatic, index_argument, /*result_is_object=*/true, dest, args...};
  }
  // For static calls.
  template <typename... T>
  static inline Instruction InvokeInterface(size_t index_argument, std::optional<const Value> dest,
                                            const T&... args) {
    return Instruction{
        Op::kInvokeInterface, index_argument, /*result_is_object=*/false, dest, args...};
  }

  static inline Instruction GetStaticField(size_t field_id, Value dest) {
    return Instruction{Op::kGetStaticField, field_id, dest};
  }

  static inline Instruction SetStaticField(size_t field_id, Value value) {
    return Instruction{
        Op::kSetStaticField, field_id, /*result_is_object=*/false, /*dest=*/{}, value};
  }

  static inline Instruction GetField(size_t field_id, Value dest, Value object) {
    return Instruction{Op::kGetInstanceField, field_id, /*result_is_object=*/false, dest, object};
  }

  static inline Instruction SetField(size_t field_id, Value object, Value value) {
    return Instruction{
        Op::kSetInstanceField, field_id, /*result_is_object=*/false, /*dest=*/{}, object, value};
  }

  ///////////////
  // Accessors //
  ///////////////

  Op opcode() const { return opcode_; }
  size_t index_argument() const { return index_argument_; }
  bool result_is_object() const { return result_is_object_; }
  const std::optional<const Value>& dest() const { return dest_; }
  const std::vector<const Value>& args() const { return args_; }

 private:
  inline Instruction(Op opcode, size_t index_argument, std::optional<const Value> dest)
      : opcode_{opcode},
        index_argument_{index_argument},
        result_is_object_{false},
        dest_{dest},
        args_{} {}

  template <typename... T>
  inline Instruction(Op opcode, size_t index_argument, bool result_is_object,
                     std::optional<const Value> dest, const T&... args)
      : opcode_{opcode},
        index_argument_{index_argument},
        result_is_object_{result_is_object},
        dest_{dest},
        args_{args...} {}

  const Op opcode_;
  // The index of the method to invoke, for kInvokeVirtual and similar opcodes.
  const size_t index_argument_{0};
  const bool result_is_object_;
  const std::optional<const Value> dest_;
  const std::vector<const Value> args_;
};

// Needed for CHECK_EQ, DCHECK_EQ, etc.
std::ostream& operator<<(std::ostream& out, const Instruction::Op& opcode);

// Keeps track of information needed to manipulate or call a method.
struct MethodDeclData {
  size_t id;
  ir::MethodDecl* decl;
};

// Tools to help build methods and their bodies.
class MethodBuilder {
 public:
  MethodBuilder(DexBuilder* dex, ir::Class* class_def, ir::MethodDecl* decl);

  // Encode the method into DEX format.
  ir::EncodedMethod* Encode();

  // Create a new register to be used to storing values.
  LiveRegister AllocRegister();

  Value MakeLabel();

  /////////////////////////////////
  // Instruction builder methods //
  /////////////////////////////////

  void AddInstruction(Instruction instruction);

  // return-void
  void BuildReturn();
  void BuildReturn(Value src, bool is_object = false);
  // const/4
  void BuildConst4(Value target, int value);
  void BuildConstString(Value target, const std::string& value);
  template <typename... T>
  void BuildNew(Value target, TypeDescriptor type, Prototype constructor, const T&... args);

  // TODO: add builders for more instructions

  DexBuilder* dex_file() const { return dex_; }

 private:
  void EncodeInstructions();
  void EncodeInstruction(const Instruction& instruction);

  // Encodes a return instruction. For instructions with no return value, the opcode field is
  // ignored. Otherwise, this specifies which return instruction will be used (return,
  // return-object, etc.)
  void EncodeReturn(const Instruction& instruction, ::dex::Opcode opcode);

  void EncodeMove(const Instruction& instruction);
  void EncodeInvoke(const Instruction& instruction, ::dex::Opcode opcode);
  void EncodeBranch(::dex::Opcode op, const Instruction& instruction);
  void EncodeNew(const Instruction& instruction);
  void EncodeCast(const Instruction& instruction);
  void EncodeFieldOp(const Instruction& instruction);

  // Low-level instruction format encoding. See
  // https://source.android.com/devices/tech/dalvik/instruction-formats for documentation of
  // formats.

  inline uint8_t ToBits(::dex::Opcode opcode) {
    static_assert(sizeof(uint8_t) == sizeof(::dex::Opcode));
    return static_cast<uint8_t>(opcode);
  }

  inline void Encode10x(::dex::Opcode opcode) {
    // 00|op
    static_assert(sizeof(uint8_t) == sizeof(::dex::Opcode));
    buffer_.push_back(ToBits(opcode));
  }

  inline void Encode11x(::dex::Opcode opcode, uint8_t a) {
    // aa|op
    buffer_.push_back((a << 8) | ToBits(opcode));
  }

  inline void Encode11n(::dex::Opcode opcode, uint8_t a, int8_t b) {
    // b|a|op

    // Make sure the fields are in bounds (4 bits for a, 4 bits for b).
    CHECK_LT(a, 16);
    CHECK_LE(-8, b);
    CHECK_LT(b, 8);

    buffer_.push_back(((b & 0xf) << 12) | (a << 8) | ToBits(opcode));
  }

  inline void Encode21c(::dex::Opcode opcode, uint8_t a, uint16_t b) {
    // aa|op|bbbb
    buffer_.push_back((a << 8) | ToBits(opcode));
    buffer_.push_back(b);
  }

  inline void Encode22c(::dex::Opcode opcode, uint8_t a, uint8_t b, uint16_t c) {
    // b|a|op|bbbb
    CHECK(IsShortRegister(a));
    CHECK(IsShortRegister(b));
    buffer_.push_back((b << 12) | (a << 8) | ToBits(opcode));
    buffer_.push_back(c);
  }

  inline void Encode32x(::dex::Opcode opcode, uint16_t a, uint16_t b) {
    buffer_.push_back(ToBits(opcode));
    buffer_.push_back(a);
    buffer_.push_back(b);
  }

  inline void Encode35c(::dex::Opcode opcode, size_t a, uint16_t b, uint8_t c, uint8_t d,
                        uint8_t e, uint8_t f, uint8_t g) {
    // a|g|op|bbbb|f|e|d|c

    CHECK_LE(a, 5);
    CHECK(IsShortRegister(c));
    CHECK(IsShortRegister(d));
    CHECK(IsShortRegister(e));
    CHECK(IsShortRegister(f));
    CHECK(IsShortRegister(g));
    buffer_.push_back((a << 12) | (g << 8) | ToBits(opcode));
    buffer_.push_back(b);
    buffer_.push_back((f << 12) | (e << 8) | (d << 4) | c);
  }

  inline void Encode3rc(::dex::Opcode opcode, size_t a, uint16_t b, uint16_t c) {
    CHECK_LE(a, 255);
    buffer_.push_back((a << 8) | ToBits(opcode));
    buffer_.push_back(b);
    buffer_.push_back(c);
  }

  static constexpr bool IsShortRegister(size_t register_value) { return register_value < 16; }

  // Returns an array of num_regs scratch registers. These are guaranteed to be
  // contiguous, so they are suitable for the invoke-*/range instructions.
  template <int num_regs>
  std::array<Value, num_regs> GetScratchRegisters() const {
    static_assert(num_regs <= kMaxScratchRegisters);
    std::array<Value, num_regs> regs;
    for (size_t i = 0; i < num_regs; ++i) {
      regs[i] = std::move(Value::Local(NumRegisters() + i));
    }
    return regs;
  }

  // Converts a register or parameter to its DEX register number.
  size_t RegisterValue(const Value& value) const;

  // Sets a label's address to the current position in the instruction buffer. If there are any
  // forward references to the label, this function will back-patch them.
  void BindLabel(const Value& label);

  // Returns the offset of the label relative to the given instruction offset. If the label is not
  // bound, a reference will be saved and it will automatically be patched when the label is bound.
  ::dex::u2 LabelValue(const Value& label, size_t instruction_offset, size_t field_offset);

  DexBuilder* dex_;
  ir::Class* class_;
  ir::MethodDecl* decl_;

  // A list of the instructions we will eventually encode.
  std::vector<Instruction> instructions_;

  // A buffer to hold instructions that have been encoded.
  std::vector<::dex::u2> buffer_;

  // We create some scratch registers for when we have to shuffle registers
  // around to make legal DEX code.
  static constexpr size_t kMaxScratchRegisters = 5;

  size_t NumRegisters() const {
    return register_liveness_.size();
  }

  // Stores information needed to back-patch a label once it is bound. We need to know the start of
  // the instruction that refers to the label, and the offset to where the actual label value should
  // go.
  struct LabelReference {
    size_t instruction_offset;
    size_t field_offset;
  };

  struct LabelData {
    std::optional<size_t> bound_address;
    std::forward_list<LabelReference> references;
  };

  std::vector<LabelData> labels_;

  // During encoding, keep track of the largest number of arguments needed, so we can use it for our
  // outs count
  size_t max_args_{0};

  std::vector<bool> register_liveness_;
};

// A helper to build class definitions.
class ClassBuilder {
 public:
  ClassBuilder(DexBuilder* parent, const std::string& name, ir::Class* class_def);

  void set_source_file(const std::string& source);

  // Create a method with the given name and prototype. The returned MethodBuilder can be used to
  // fill in the method body.
  MethodBuilder CreateMethod(const std::string& name, Prototype prototype);

 private:
  DexBuilder* const parent_;
  const TypeDescriptor type_descriptor_;
  ir::Class* const class_;
};

// Builds Dex files from scratch.
class DexBuilder {
 public:
  DexBuilder();

  // Create an in-memory image of the DEX file that can either be loaded directly or written to a
  // file.
  slicer::MemView CreateImage();

  template <typename T>
  T* Alloc() {
    return dex_file_->Alloc<T>();
  }

  // Find the ir::String that matches the given string, creating it if it does not exist.
  ir::String* GetOrAddString(const std::string& string);
  // Create a new class of the given name.
  ClassBuilder MakeClass(const std::string& name);

  // Add a type for the given descriptor, or return the existing one if it already exists.
  // See the TypeDescriptor class for help generating these. GetOrAddType can be used to declare
  // imported classes.
  ir::Type* GetOrAddType(const std::string& descriptor);
  inline ir::Type* GetOrAddType(TypeDescriptor descriptor) {
    return GetOrAddType(descriptor.descriptor());
  }

  ir::FieldDecl* GetOrAddField(TypeDescriptor parent, const std::string& name, TypeDescriptor type);

  // Returns the method id for the method, creating it if it has not been created yet.
  const MethodDeclData& GetOrDeclareMethod(TypeDescriptor type, const std::string& name,
                                           Prototype prototype);

  std::optional<const Prototype> GetPrototypeByMethodId(size_t method_id) const;

 private:
  // Looks up the ir::Proto* corresponding to this given prototype, or creates one if it does not
  // exist.
  ir::Proto* GetOrEncodeProto(Prototype prototype);

  std::shared_ptr<ir::DexFile> dex_file_;

  // allocator_ is needed to be able to encode the image.
  TrackingAllocator allocator_;

  // We'll need to allocate buffers for all of the encoded strings we create. This is where we store
  // all of them.
  std::vector<std::unique_ptr<uint8_t[]>> string_data_;

  // Keep track of what types we've defined so we can look them up later.
  std::unordered_map<std::string, ir::Type*> types_by_descriptor_;

  struct MethodDescriptor {
    TypeDescriptor type;
    std::string name;
    Prototype prototype;

    inline bool operator<(const MethodDescriptor& rhs) const {
      return std::make_tuple(type, name, prototype) <
             std::make_tuple(rhs.type, rhs.name, rhs.prototype);
    }
  };

  // Maps method declarations to their method index. This is needed to encode references to them.
  // When we go to actually write the DEX file, slicer will re-assign these after correctly sorting
  // the methods list.
  std::map<MethodDescriptor, MethodDeclData> method_id_map_;

  // Keep track of what strings we've defined so we can look them up later.
  std::unordered_map<std::string, ir::String*> strings_;

  // Keep track of already-encoded protos.
  std::map<Prototype, ir::Proto*> proto_map_;

  // Keep track of fields that have been declared
  std::map<std::tuple<TypeDescriptor, std::string>, ir::FieldDecl*> field_decls_by_key_;
};

template <typename... T>
void MethodBuilder::BuildNew(Value target, TypeDescriptor type, Prototype constructor,
                             const T&... args) {
  MethodDeclData constructor_data{dex_->GetOrDeclareMethod(type, "<init>", constructor)};
  // allocate the object
  ir::Type* type_def = dex_->GetOrAddType(type.descriptor());
  AddInstruction(
      Instruction::OpWithArgs(Instruction::Op::kNew, target, Value::Type(type_def->orig_index)));
  // call the constructor
  AddInstruction(Instruction::InvokeDirect(constructor_data.id, /*dest=*/{}, target, args...));
};

}  // namespace dex
}  // namespace startop

#endif  // DEX_BUILDER_H_
