/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef AAPT_FORMAT_PROTO_PROTOSERIALIZE_H
#define AAPT_FORMAT_PROTO_PROTOSERIALIZE_H

#include "android-base/macros.h"
#include "androidfw/ConfigDescription.h"

#include "Configuration.pb.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "Resources.pb.h"
#include "ResourcesInternal.pb.h"
#include "StringPool.h"
#include "xml/XmlDom.h"

namespace aapt {

struct SerializeXmlOptions {
  /** Remove text nodes that only contain whitespace. */
  bool remove_empty_text_nodes = false;
};

// Serializes a Value to its protobuf representation. An optional StringPool will hold the
// source path string.
void SerializeValueToPb(const Value& value, pb::Value* out_value, StringPool* src_pool = nullptr);

// Serialize an Item into its protobuf representation. pb::Item does not store the source path nor
// comments of an Item.
void SerializeItemToPb(const Item& item, pb::Item* out_item);

// Serializes an XML element into its protobuf representation.
void SerializeXmlToPb(const xml::Element& el, pb::XmlNode* out_node,
                      const SerializeXmlOptions options = {});

// Serializes an XmlResource into its protobuf representation. The ResourceFile is NOT serialized.
void SerializeXmlResourceToPb(const xml::XmlResource& resource, pb::XmlNode* out_node,
                              const SerializeXmlOptions options = {});

// Serializes a StringPool into its protobuf representation, which is really just the binary
// ResStringPool representation stuffed into a bytes field.
void SerializeStringPoolToPb(const StringPool& pool, pb::StringPool* out_pb_pool, IDiagnostics* diag);

// Serializes a ConfigDescription into its protobuf representation.
void SerializeConfig(const android::ConfigDescription& config, pb::Configuration* out_pb_config);

// Serializes a ResourceTable into its protobuf representation.
void SerializeTableToPb(const ResourceTable& table, pb::ResourceTable* out_table, IDiagnostics* diag);

// Serializes a ResourceFile into its protobuf representation.
void SerializeCompiledFileToPb(const ResourceFile& file, pb::internal::CompiledFile* out_file);

}  // namespace aapt

#endif /* AAPT_FORMAT_PROTO_PROTOSERIALIZE_H */
