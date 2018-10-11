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

#include <map>
#include <string>
#include <vector>

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
  std::map<void*, std::unique_ptr<uint8_t[]>> allocations_;
};

// Represents a DEX type descriptor.
//
// TODO: add a way to create a descriptor for a reference of a class type.
class TypeDescriptor {
 public:
  // Named constructors for base type descriptors.
  static const TypeDescriptor Int();
  static const TypeDescriptor Void();

  // Return the full descriptor, such as I or Ljava/lang/Object
  const std::string& descriptor() const { return descriptor_; }
  // Return the shorty descriptor, such as I or L
  std::string short_descriptor() const { return descriptor().substr(0, 1); }

 private:
  TypeDescriptor(std::string descriptor) : descriptor_{descriptor} {}

  const std::string descriptor_;
};

// Defines a function signature. For example, Prototype{TypeDescriptor::VOID, TypeDescriptor::Int}
// represents the function type (Int) -> Void.
class Prototype {
 public:
  template <typename... TypeDescriptors>
  Prototype(TypeDescriptor return_type, TypeDescriptors... param_types)
      : return_type_{return_type}, param_types_{param_types...} {}

  // Encode this prototype into the dex file.
  ir::Proto* Encode(DexBuilder* dex) const;

  // Get the shorty descriptor, such as VII for (Int, Int) -> Void
  std::string Shorty() const;

 private:
  const TypeDescriptor return_type_;
  const std::vector<TypeDescriptor> param_types_;
};

// Tools to help build methods and their bodies.
class MethodBuilder {
 public:
  MethodBuilder(DexBuilder* dex, ir::Class* class_def, ir::MethodDecl* decl);

  // Encode the method into DEX format.
  ir::EncodedMethod* Encode();

  // Registers are just represented by their number.
  using Register = size_t;

  // Create a new register to be used to storing values. Note that these are not SSA registers, like
  // might be expected in similar code generators. This does no liveness tracking or anything, so
  // it's up to the caller to reuse registers as appropriate.
  Register MakeRegister();

  /////////////////////////////////
  // Instruction builder methods //
  /////////////////////////////////

  // return-void
  void BuildReturn();
  void BuildReturn(Register src);
  // const/4
  void BuildConst4(Register target, int value);

  // TODO: add builders for more instructions

 private:
  DexBuilder* dex_;
  ir::Class* class_;
  ir::MethodDecl* decl_;

  // A buffer to hold instructions we are generating.
  std::vector<::dex::u2> buffer_;

  // How many registers we've allocated
  size_t num_registers_;
};

// A helper to build class definitions.
class ClassBuilder {
 public:
  ClassBuilder(DexBuilder* parent, ir::Class* class_def);

  void set_source_file(const std::string& source);

  // Create a method with the given name and prototype. The returned MethodBuilder can be used to
  // fill in the method body.
  MethodBuilder CreateMethod(const std::string& name, Prototype prototype);

 private:
  DexBuilder* parent_;
  ir::Class* class_;
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
  // See the TypeDescriptor class for help generating these.
  ir::Type* GetOrAddType(const std::string& descriptor);

 private:
  std::shared_ptr<ir::DexFile> dex_file_;

  // allocator_ is needed to be able to encode the image.
  TrackingAllocator allocator_;

  // We'll need to allocate buffers for all of the encoded strings we create. This is where we store
  // all of them.
  std::vector<std::unique_ptr<uint8_t[]>> string_data_;

  // Keep track of what types we've defined so we can look them up later.
  std::map<std::string, ir::Type*> types_by_descriptor_;

  // Keep track of what strings we've defined so we can look them up later.
  std::map<std::string, ir::String*> strings_;
};

}  // namespace dex
}  // namespace startop

#endif  // DEX_BUILDER_H_
