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
import android.os.Bundle;
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
    private final Runnable mDismissCallback;

    public DreamAccessibility(@NonNull Context context, @NonNull View view,
            @NonNull Runnable dismissCallback) {
        mContext = context;
        mView = view;
        mAccessibilityDelegate = createNewAccessibilityDelegate(mContext);
        mDismissCallback = dismissCallback;
    }

    /**
     *  Adds default accessibility configuration if none exist on the dream
     */
    public void updateAccessibilityConfiguration() {
        if (mView.getAccessibilityDelegate() == null) {
            addAccessibilityConfiguration();
        }
    }

    /**
     * Configures the accessibility actions for the given root view.
     */
    private void addAccessibilityConfiguration() {
        mView.setAccessibilityDelegate(mAccessibilityDelegate);
    }

    private View.AccessibilityDelegate createNewAccessibilityDelegate(Context context) {
        return new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_DISMISS,
                        context.getResources().getString(R.string.dream_accessibility_action_click)
                ));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                switch(action){
                    case AccessibilityNodeInfo.ACTION_DISMISS:
                        if (mDismissCallback != null) {
                            mDismissCallback.run();
                        }
                        break;
                }
                return true;
            }
        };
    }
}
