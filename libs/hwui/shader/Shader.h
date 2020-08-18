/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "SkImageFilter.h"
#include "SkShader.h"
#include "SkPaint.h"
#include "SkRefCnt.h"

class SkMatrix;

namespace android::uirenderer {

/**
 * Shader class that can optionally wrap an SkShader or SkImageFilter depending
 * on the implementation
 */
class Shader: public SkRefCnt {
public:
    /**
     * Creates a Shader instance with an optional transformation matrix
     * @param matrix Optional matrix to transform the underlying SkShader or SkImageFilter
     */
    Shader(const SkMatrix* matrix);
    virtual ~Shader();

    /**
     * Create an SkShader from the current Shader instance or return a previously
     * created instance. This can be null if no SkShader could be created from this
     * Shader instance.
     */
    sk_sp<SkShader> asSkShader();

    /**
     * Create an SkImageFilter from the current Shader instance or return a previously
     * created instance. Unlike asSkShader, this method cannot return null.
     */
    sk_sp<SkImageFilter> asSkImageFilter();

protected:
    /**
     * Create a new SkShader instance based on this Shader instance
     */
    virtual sk_sp<SkShader> makeSkShader();

    /**
     * Create a new SkImageFilter instance based on this Shader instance. If no SkImageFilter
     * can be created then return nullptr
     */
    virtual sk_sp<SkImageFilter> makeSkImageFilter();

private:
    /**
     * Optional matrix transform
     */
    const SkMatrix localMatrix;

    /**
     * Cached SkShader instance to be returned on subsequent queries
     */
    sk_sp<SkShader> skShader;

    /**
     * Cached SkImageFilter instance to be returned on subsequent queries
     */
    sk_sp<SkImageFilter> skImageFilter;
};
}  // namespace android::uirenderer
