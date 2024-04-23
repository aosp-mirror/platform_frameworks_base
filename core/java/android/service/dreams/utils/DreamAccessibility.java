/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.service.dreams.utils;

import android.annotation.NonNull;
import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.R;

/**
 * {@link DreamAccessibility} allows customization of accessibility
 * actions for the root view of the dream overlay.
 * @hide
 */
public class DreamAccessibility {
    private final Context mContext;
    private final View mView;
    private final View.AccessibilityDelegate mAccessibilityDelegate;

    public DreamAccessibility(@NonNull Context context, @NonNull View view) {
        mContext = context;
        mView = view;
        mAccessibilityDelegate = createNewAccessibilityDelegate(mContext);
    }

    /**
     * @param interactive
     * Removes and add accessibility configuration depending if the dream is interactive or not
     */
    public void updateAccessibilityConfiguration(Boolean interactive) {
        if (!interactive) {
            addAccessibilityConfiguration();
        } else {
            removeCustomAccessibilityAction();
        }
    }

    /**
     * Configures the accessibility actions for the given root view.
     */
    private void addAccessibilityConfiguration() {
        mView.setAccessibilityDelegate(mAccessibilityDelegate);
    }

    /**
     * Removes Configured the accessibility actions for the given root view.
     */
    private void removeCustomAccessibilityAction() {
        if (mView.getAccessibilityDelegate() == mAccessibilityDelegate) {
            mView.setAccessibilityDelegate(null);
        }
    }

    private View.AccessibilityDelegate createNewAccessibilityDelegate(Context context) {
        return new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
                    if (action.getId() == AccessibilityNodeInfo.ACTION_CLICK) {
                        info.removeAction(action);
                        break;
                    }
                }
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        context.getResources().getString(R.string.dream_accessibility_action_click)
                ));
            }
        };
    }
}
