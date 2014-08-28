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
#ifndef CONTEXTFACTORY_H_
#define CONTEXTFACTORY_H_

namespace android {
namespace uirenderer {

namespace renderthread {
class TimeLord;
}

class AnimationContext;

class IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) = 0;

protected:
    virtual ~IContextFactory() {}
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* CONTEXTFACTORY_H_ */
