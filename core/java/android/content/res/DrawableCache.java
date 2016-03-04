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

package android.content.res;

import android.graphics.drawable.Drawable;

/**
 * Class which can be used to cache Drawable resources against a theme.
 */
class DrawableCache extends ThemedResourceCache<Drawable.ConstantState> {
    /**
     * If the resource is cached, creates and returns a new instance of it.
     *
     * @param key a key that uniquely identifies the drawable resource
     * @param resources a Resources object from which to create new instances.
     * @param theme the theme where the resource will be used
     * @return a new instance of the resource, or {@code null} if not in
     *         the cache
     */
    public Drawable getInstance(long key, Resources resources, Resources.Theme theme) {
        final Drawable.ConstantState entry = get(key, theme);
        if (entry != null) {
            return entry.newDrawable(resources, theme);
        }

        return null;
    }

    @Override
    public boolean shouldInvalidateEntry(Drawable.ConstantState entry, int configChanges) {
        return Configuration.needNewResources(configChanges, entry.getChangingConfigurations());
    }
}
