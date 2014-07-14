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

package com.android.systemui.recents.model;

import android.graphics.drawable.Drawable;

/**
 * The Drawable LRU cache.
 */
class DrawableLruCache extends KeyStoreLruCache<Drawable> {
    public DrawableLruCache(int cacheSize) {
        super(cacheSize);
    }

    @Override
    protected int computeSize(Drawable d) {
        // The cache size will be measured in kilobytes rather than number of items
        // NOTE: this isn't actually correct, as the icon may be smaller
        int maxBytes = (d.getIntrinsicWidth() * d.getIntrinsicHeight() * 4);
        return maxBytes;
    }
}