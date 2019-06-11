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

package android.view.accessibility;

import android.view.View;

/** @hide */
public final class AccessibilityNodeIdManager {
    private WeakSparseArray<View> mIdsToViews = new WeakSparseArray<View>();
    private static AccessibilityNodeIdManager sIdManager;

    /**
     * Gets singleton.
     * @return The instance.
     */
    public static synchronized AccessibilityNodeIdManager getInstance() {
        if (sIdManager == null) {
            sIdManager = new AccessibilityNodeIdManager();
        }
        return sIdManager;
    }

    private AccessibilityNodeIdManager() {
    }

    /**
     * Register view to be kept track of by the accessibility system.
     * Must be paired with unregisterView, otherwise this will leak.
     * @param view The view to be registered.
     * @param id The accessibilityViewId of the view.
     */
    public void registerViewWithId(View view, int id) {
        synchronized (mIdsToViews) {
            mIdsToViews.append(id, view);
        }
    }

    /**
     * Unregister view, accessibility won't keep track of this view after this call.
     * @param id The id returned from registerView when the view as first associated.
     */
    public void unregisterViewWithId(int id) {
        synchronized (mIdsToViews) {
            mIdsToViews.remove(id);
        }
    }

    /**
     * Accessibility uses this to find the view in the hierarchy.
     * @param id The accessibility view id.
     * @return The view.
     */
    public View findView(int id) {
        View view = null;
        synchronized (mIdsToViews) {
            view = mIdsToViews.get(id);
        }
        return view != null && view.includeForAccessibility() ? view : null;
    }
}
