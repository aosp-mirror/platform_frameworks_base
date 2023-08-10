/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

/**
 * An interface for the class, who will use {@link NotificationIconManager} to load icons.
 */
public interface NotificationDrawableConsumer {

    /**
     * Sets a drawable as the content of this consumer.
     *
     * @param drawable the {@link Drawable} to set, or {@code null} to clear the content
     */
    void setImageDrawable(@Nullable Drawable drawable);
}
