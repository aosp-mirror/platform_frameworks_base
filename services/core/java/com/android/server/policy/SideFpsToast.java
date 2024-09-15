/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.policy;

import android.annotation.NonNull;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;

import com.android.internal.R;

/**
 * Toast for side fps. This is typically shown during enrollment
 * when a user presses the power button.
 *
 * This dialog is used by {@link SideFpsEventHandler}
 */
public class SideFpsToast extends Dialog {
    SideFpsToast(Context context) {
        super(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.side_fps_toast);
    }

    @Override
    protected void onStart() {
        super.onStart();
        final Window window = this.getWindow();
        WindowManager.LayoutParams windowParams = window.getAttributes();
        windowParams.dimAmount = 0;
        windowParams.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        windowParams.gravity = Gravity.BOTTOM;
        window.setAttributes(windowParams);
    }

    /**
     * Sets the onClickListener for the toast dialog.
     * @param listener
     */
    public void setOnClickListener(View.OnClickListener listener) {
        final Button turnOffScreen = findViewById(R.id.turn_off_screen);
        if (turnOffScreen != null) {
            turnOffScreen.setOnClickListener(listener);
        }
    }

    /**
     * When accessibility mode is on, add AccessibilityDelegate to dismiss dialog when focus is
     * moved away from the dialog.
     */
    public void addAccessibilityDelegate() {
        final Button turnOffScreen = findViewById(R.id.turn_off_screen);
        if (turnOffScreen != null) {
            turnOffScreen.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityEvent(@NonNull View host,
                        @NonNull AccessibilityEvent event) {
                    if (event.getEventType()
                            == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
                            && isShowing()) {
                        dismiss();
                    }
                    super.onInitializeAccessibilityEvent(host, event);
                }
            });

        }
    }
}
