/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.keyguard;

import android.graphics.drawable.Drawable;

import java.util.HashMap;

public class MultiUserAvatarCache {

    private static MultiUserAvatarCache sInstance;

    private final HashMap<Integer, Drawable> mCache;

    private MultiUserAvatarCache() {
        mCache = new HashMap<Integer, Drawable>();
    }

    public static MultiUserAvatarCache getInstance() {
        if (sInstance == null) {
            sInstance = new MultiUserAvatarCache();
        }
        return sInstance;
    }

    public void clear(int userId) {
        mCache.remove(userId);
    }

    public Drawable get(int userId) {
        return mCache.get(userId);
    }

    public void put(int userId, Drawable image) {
        mCache.put(userId, image);
    }
}
