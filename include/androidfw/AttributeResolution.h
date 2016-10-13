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

#include <androidfw/ResourceTypes.h>

namespace android {

// These are all variations of the same method. They each perform the exact same operation,
// but on various data sources. I *think* they are re-written to avoid an extra branch
// in the inner loop, but after one branch miss (some pointer != null), the branch predictor should
// predict the rest of the iterations' branch correctly.
// TODO(adamlesinski): Run performance tests against these methods and a new, single method
// that uses all the sources and branches to the right ones within the inner loop.

bool resolveAttrs(ResTable::Theme* theme,
                  uint32_t defStyleAttr,
                  uint32_t defStyleRes,
                  uint32_t* srcValues, size_t srcValuesLength,
                  uint32_t* attrs, size_t attrsLength,
                  uint32_t* outValues,
                  uint32_t* outIndices);

bool applyStyle(ResTable::Theme* theme, ResXMLParser* xmlParser,
                uint32_t defStyleAttr,
                uint32_t defStyleRes,
                uint32_t* attrs, size_t attrsLength,
                uint32_t* outValues,
                uint32_t* outIndices);

bool retrieveAttributes(const ResTable* res, ResXMLParser* xmlParser,
                        uint32_t* attrs, size_t attrsLength,
                        uint32_t* outValues,
                        uint32_t* outIndices);

} // namespace android

#endif /* ANDROIDFW_ATTRIBUTERESOLUTION_H */
