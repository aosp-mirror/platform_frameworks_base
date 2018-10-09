/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef AAPT_FORMAT_PROTO_PROTODESERIALIZE_H
#define AAPT_FORMAT_PROTO_PROTODESERIALIZE_H

#include "android-base/macros.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/ResourceTypes.h"

#include "Configuration.pb.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "Resources.pb.h"
#include "ResourcesInternal.pb.h"
#include "StringPool.h"
#include "io/File.h"
#include "xml/XmlDom.h"

namespace aapt {

std::unique_ptr<Value> DeserializeValueFromPb(const pb::Value& pb_value,
                                              const android::ResStringPool& src_pool,
                                              const android::ConfigDescription& config,
                                              StringPool* value_pool, io::IFileCollection* files,
                                              std::string* out_error);

std::unique_ptr<Item> DeserializeItemFromPb(const pb::Item& pb_item,
                                            const android::ResStringPool& src_pool,
                                            const android::ConfigDescription& config,
                                            StringPool* value_pool, io::IFileCollection* files,
                                            std::string* out_error);

std::unique_ptr<xml::XmlResource> DeserializeXmlResourceFromPb(const pb::XmlNode& pb_node,
                                                               std::string* out_error);

bool DeserializeXmlFromPb(const pb::XmlNode& pb_node, xml::Element* out_el, StringPool* value_pool,
                          std::string* out_error);

bool DeserializeConfigFromPb(const pb::Configuration& pb_config,
                             android::ConfigDescription* out_config, std::string* out_error);

// Optional io::IFileCollection used to lookup references to files in the ResourceTable.
bool DeserializeTableFromPb(const pb::ResourceTable& pb_table, io::IFileCollection* files,
                            ResourceTable* out_table, std::string* out_error);

bool DeserializeCompiledFileFromPb(const pb::internal::CompiledFile& pb_file,
                                   ResourceFile* out_file, std::string* out_error);

}  // namespace aapt

#endif /* AAPT_FORMAT_PROTO_PROTODESERIALIZE_H */
