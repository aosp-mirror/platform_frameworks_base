/*
 * Copyright (C) 2014 The Android Open Source Project
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
#ifndef VIRTUALLIGHTREFBASE_H
#define VIRTUALLIGHTREFBASE_H

#include <utils/RefBase.h>

namespace android {
namespace uirenderer {

// This is a wrapper around LightRefBase that simply enforces a virtual
// destructor to eliminate the template requirement of LightRefBase
class VirtualLightRefBase : public LightRefBase<VirtualLightRefBase> {
public:
    virtual ~VirtualLightRefBase() {}
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* VIRTUALLIGHTREFBASE_H */
