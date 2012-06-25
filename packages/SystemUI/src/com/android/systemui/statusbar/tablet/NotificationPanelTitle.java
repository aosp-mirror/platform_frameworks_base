/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import java.util.ArrayList;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.RelativeLayout;

import com.android.systemui.R;


public class NotificationPanelTitle extends RelativeLayout implements View.OnClickListener {
    private NotificationPanel mPanel;
    private ArrayList<View> buttons;
    private View mSettingsButton;

    public NotificationPanelTitle(Context context, AttributeSet attrs) {
        super(context, attrs);
        buttons = new ArrayList<View>();
        setOnClickListener(this);
    }

    public void setPanel(NotificationPanel p) {
        mPanel = p;
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        buttons.add(mSettingsButton = findViewById(R.id.settings_button));
        buttons.add(findViewById(R.id.notification_button));
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        for (View button : buttons) {
            if (button != null) {
                button.setPressed(pressed);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!mSettingsButton.isEnabled())
            return false;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                setPressed(true);
                break;
            case MotionEvent.ACTION_MOVE:
                final int x = (int) e.getX();
                final int y = (int) e.getY();
                setPressed(x > 0 && x < getWidth() && y > 0 && y < getHeight());
                break;
            case MotionEvent.ACTION_UP:
                if (isPressed()) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    mPanel.swapPanels();
                    setPressed(false);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                break;
        }
        return true;
    }

    @Override
    public void onClick(View v) {
        if (mSettingsButton.isEnabled() && v == this) {
            mPanel.swapPanels();
        }
    }

    @Override
    public boolean onRequestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEvent(child, event)) {
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }
}
