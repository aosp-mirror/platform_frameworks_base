/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef _MOCK_SPRITE_H
#define _MOCK_SPRITE_H

#include <input/SpriteController.h>

#include <gmock/gmock.h>

namespace android {

class MockSprite : public Sprite {
public:
    virtual ~MockSprite() = default;

    MOCK_METHOD(void, setIcon, (const SpriteIcon& icon), (override));
    MOCK_METHOD(void, setVisible, (bool), (override));
    MOCK_METHOD(void, setPosition, (float, float), (override));
    MOCK_METHOD(void, setLayer, (int32_t), (override));
    MOCK_METHOD(void, setAlpha, (float), (override));
    MOCK_METHOD(void, setTransformationMatrix, (const SpriteTransformationMatrix&), (override));
    MOCK_METHOD(void, setDisplayId, (ui::LogicalDisplayId), (override));
    MOCK_METHOD(void, setSkipScreenshot, (bool), (override));
};

}  // namespace android

#endif  // _MOCK_SPRITE_H
