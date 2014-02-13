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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.LinearLayout;

public class PanelHeaderView extends LinearLayout {
    private static final String TAG = "PanelHeaderView";
    private static final boolean DEBUG = false;

    private ZenModeView mZenModeView;

    public PanelHeaderView(Context context) {
        super(context);
    }

    public PanelHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setZenModeView(ZenModeView zmv) {
        mZenModeView = zmv;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final boolean rt = super.dispatchTouchEvent(ev);
        if (DEBUG) logTouchEvent("dispatchTouchEvent", rt, ev);
        if (mZenModeView != null) {
            mZenModeView.dispatchExternalTouchEvent(ev);
        }
        return rt;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final boolean rt = super.onInterceptTouchEvent(ev);
        if (DEBUG) logTouchEvent("onInterceptTouchEvent", rt, ev);
        return rt;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean rt = super.onTouchEvent(event);
        if (DEBUG) logTouchEvent("onTouchEvent", rt, event);
        return true;
    }

    private void logTouchEvent(String method, boolean rt, MotionEvent ev) {
        Log.d(TAG, method + " " + (rt ? "TRUE" : "FALSE") + " " + ev);
    }
}
