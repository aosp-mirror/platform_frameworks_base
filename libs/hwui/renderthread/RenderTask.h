/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef RENDERTASK_H_
#define RENDERTASK_H_

#include <cutils/compiler.h>

namespace android {
namespace uirenderer {
namespace renderthread {

class ANDROID_API RenderTask {
public:
    ANDROID_API RenderTask();
    ANDROID_API virtual ~RenderTask();

    ANDROID_API virtual void run() = 0;

    RenderTask* mNext;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* RENDERTASK_H_ */
