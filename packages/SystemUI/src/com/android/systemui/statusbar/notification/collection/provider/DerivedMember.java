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

package com.android.systemui.statusbar.notification.collection.provider;
/**
 * Caches a computed value until invalidate() is called
 * @param <Parent> Object used to computeValue
 * @param <Value> type of value to cache until invalidate is called
 */
public abstract class DerivedMember<Parent, Value> {
    private Value mValue;
    protected abstract Value computeValue(Parent parent);

    /**
     * Gets the last cached value, else recomputes the value.
     */
    public Value get(Parent parent) {
        if (mValue == null) {
            mValue = computeValue(parent);
        }
        return mValue;
    }

    /**
     * Resets the cached value.
     * Next time "get" is called, the value is recomputed.
     */
    public void invalidate() {
        mValue = null;
    }

    /**
     * Called when a NotificationEntry's status bar notification has updated.
     * Derived members can invalidate here.
     */
    public void onSbnUpdated() {}

    /**
     * Called when a NotificationEntry's Ranking has updated.
     * Derived members can invalidate here.
     */
    public void onRankingUpdated() {}

    /**
     * Called when a ListEntry's grouping information (parent or children) has changed.
     * Derived members can invalidate here.
     */
    public void onGroupingUpdated() {}
}
