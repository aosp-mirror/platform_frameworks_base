/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.widget;

/**
 * An interface for the class who will use the {@link ImageResolver} to resolve images.
 */
public interface ImageMessageConsumer {
    /**
     * Set the custom {@link ImageResolver} other than {@link LocalImageResolver}.
     * @param resolver An image resolver that has custom implementation.
     */
    void setImageResolver(ImageResolver resolver);
}
