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
package com.android.systemui.bubbles;

import androidx.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.Collection;
import java.util.HashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Keeps track of active bubbles.
 */
@Singleton
class BubbleData {

    private HashMap<String, Bubble> mBubbles = new HashMap<>();

    @Inject
    BubbleData() {}

    /**
     * The set of bubbles.
     */
    public Collection<Bubble> getBubbles() {
        return mBubbles.values();
    }

    @Nullable
    public Bubble getBubble(String key) {
        return mBubbles.get(key);
    }

    public void addBubble(Bubble b) {
        mBubbles.put(b.key, b);
    }

    @Nullable
    public Bubble removeBubble(String key) {
        return mBubbles.remove(key);
    }

    public void updateBubble(String key, NotificationEntry newEntry) {
        Bubble oldBubble = mBubbles.get(key);
        if (oldBubble != null) {
            oldBubble.setEntry(newEntry);
        }
    }

    public void clear() {
        mBubbles.clear();
    }
}
