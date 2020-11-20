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

#ifndef ANDROIDFW_ATTRIBUTERESOLUTION_H
#define ANDROIDFW_ATTRIBUTERESOLUTION_H

#include "androidfw/AssetManager2.h"
#include "androidfw/ResourceTypes.h"

namespace android {

// Offsets into the outValues array populated by the methods below. outValues is a uint32_t
// array, but each logical element takes up 7 uint32_t-sized physical elements.
// Keep these in sync with android.content.res.TypedArray java class
enum {
  STYLE_NUM_ENTRIES = 7,
  STYLE_TYPE = 0,
  STYLE_DATA = 1,
  STYLE_ASSET_COOKIE = 2,
  STYLE_RESOURCE_ID = 3,
  STYLE_CHANGING_CONFIGURATIONS = 4,
  STYLE_DENSITY = 5,
  STYLE_SOURCE_RESOURCE_ID = 6
};

// These are all variations of the same method. They each perform the exact same operation,
// but on various data sources. I *think* they are re-written to avoid an extra branch
// in the inner loop, but after one branch miss (some pointer != null), the branch predictor should
// predict the rest of the iterations' branch correctly.
// TODO(adamlesinski): Run performance tests against these methods and a new, single method
// that uses all the sources and branches to the right ones within the inner loop.

// `out_values` must NOT be nullptr.
// `out_indices` may be nullptr.
base::expected<std::monostate, IOError> ResolveAttrs(Theme* theme, uint32_t def_style_attr,
                                                     uint32_t def_style_resid, uint32_t* src_values,
                                                     size_t src_values_length, uint32_t* attrs,
                                                     size_t attrs_length, uint32_t* out_values,
                                                     uint32_t* out_indices);

// `out_values` must NOT be nullptr.
// `out_indices` is NOT optional and must NOT be nullptr.
base::expected<std::monostate, IOError> ApplyStyle(Theme* theme, ResXMLParser* xml_parser,
                                                   uint32_t def_style_attr,
                                                   uint32_t def_style_resid,
                                                   const uint32_t* attrs, size_t attrs_length,
                                                   uint32_t* out_values, uint32_t* out_indices);

// `out_values` must NOT be nullptr.
// `out_indices` may be nullptr.
base::expected<std::monostate, IOError> RetrieveAttributes(AssetManager2* assetmanager,
                                                           ResXMLParser* xml_parser,
                                                           uint32_t* attrs,
                                                           size_t attrs_length,
                                                           uint32_t* out_values,
                                                           uint32_t* out_indices);

}  // namespace android

#endif /* ANDROIDFW_ATTRIBUTERESOLUTION_H */
