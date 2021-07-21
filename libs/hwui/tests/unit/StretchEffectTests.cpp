/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include <gtest/gtest.h>
#include <effects/StretchEffect.h>
#include "tests/common/TestUtils.h"
#include "Properties.h"

using namespace android;
using namespace android::uirenderer;

TEST(StretchEffect, noStretchDirectionDoesNotRequireLayer) {
  Properties::setStretchEffectBehavior(StretchEffectBehavior::ShaderHWUI);
  auto stretchEffect = StretchEffect({.fX = 0.f, .fY = 0.f}, 100.f, 100.f);
  ASSERT_FALSE(stretchEffect.requiresLayer());

  Properties::setStretchEffectBehavior(StretchEffectBehavior::UniformScale);
  ASSERT_FALSE(stretchEffect.requiresLayer());
}

TEST(StretchEffect, horizontalStretchRequiresLayer) {
  Properties::setStretchEffectBehavior(StretchEffectBehavior::ShaderHWUI);
  auto stretchEffect = StretchEffect({.fX = 1.f, .fY = 0.f}, 100.f, 100.f);
  ASSERT_TRUE(stretchEffect.requiresLayer());

  Properties::setStretchEffectBehavior(StretchEffectBehavior::UniformScale);
  ASSERT_TRUE(stretchEffect.requiresLayer());
}

TEST(StretchEffect, verticalStretchRequiresLayer) {
  Properties::setStretchEffectBehavior(StretchEffectBehavior::ShaderHWUI);

  auto stretchEffect = StretchEffect({.fX = 0.f, .fY = 1.f}, 100.f, 100.f);
  ASSERT_TRUE(stretchEffect.requiresLayer());

  Properties::setStretchEffectBehavior(StretchEffectBehavior::UniformScale);
  ASSERT_TRUE(stretchEffect.requiresLayer());
}

TEST(StretchEffect, bidirectionalStretchRequiresLayer) {
  Properties::setStretchEffectBehavior(StretchEffectBehavior::ShaderHWUI);

  auto stretchEffect = StretchEffect({.fX = 1.f, .fY = 1.f}, 100.f, 100.f);
  ASSERT_TRUE(stretchEffect.requiresLayer());

  Properties::setStretchEffectBehavior(StretchEffectBehavior::UniformScale);
  ASSERT_TRUE(stretchEffect.requiresLayer());
}