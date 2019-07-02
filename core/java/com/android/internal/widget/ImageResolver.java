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

import android.graphics.drawable.Drawable;
import android.net.Uri;

/**
 * An interface for image resolvers that have custom implementations like cache mechanisms.
 */
public interface ImageResolver {
    /**
     * Load an image from specified uri.
     * @param uri Uri of the target image.
     * @return Target image in Drawable.
     */
    Drawable loadImage(Uri uri);
}
