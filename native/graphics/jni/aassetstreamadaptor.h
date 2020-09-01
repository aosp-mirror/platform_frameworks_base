/*
 * Copyright 2019 The Android Open Source Project
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

#pragma once

#include "SkStream.h"

#include <android/asset_manager.h>

// Like AssetStreamAdaptor, but operates on AAsset, a public NDK API.
class AAssetStreamAdaptor : public SkStreamRewindable {
public:
    /**
     * Create an SkStream that reads from an AAsset.
     *
     * The AAsset must remain open for the lifetime of the Adaptor. The Adaptor
     * does *not* close the AAsset.
     */
    explicit AAssetStreamAdaptor(AAsset*);

    bool rewind() override;
    size_t read(void* buffer, size_t size) override;
    bool hasLength() const override { return true; }
    size_t getLength() const override;
    bool hasPosition() const override;
    size_t getPosition() const override;
    bool seek(size_t position) override;
    bool move(long offset) override;
    bool isAtEnd() const override;
    const void* getMemoryBase() override;
protected:
    SkStreamRewindable* onDuplicate() const override;

private:
    AAsset* mAAsset;
};

