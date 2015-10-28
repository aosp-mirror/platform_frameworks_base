/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public abstract class QSTileBaseView extends ViewGroup {

    public static final int QS_TYPE_NORMAL = 0;
    public static final int QS_TYPE_DUAL   = 1;
    public static final int QS_TYPE_QUICK  = 2;

    private final H mHandler = new H();

    public QSTileBaseView(Context context) {
        super(context);
    }

    public QSTileBaseView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }


    public void onStateChanged(QSTile.State state) {
        mHandler.obtainMessage(H.STATE_CHANGED, state).sendToTarget();
    }

    public abstract void init(OnClickListener click, OnClickListener clickSecondary,
                              OnLongClickListener longClick);
    public abstract View updateAccessibilityOrder(View previousView);
    public abstract boolean setType(int type);

    protected abstract void handleStateChanged(QSTile.State state);

    protected static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    protected static void layout(View child, int left, int top) {
        child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
    }

    private class H extends Handler {
        private static final int STATE_CHANGED = 1;
        public H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == STATE_CHANGED) {
                handleStateChanged((QSTile.State) msg.obj);
            }
        }
    }
}
