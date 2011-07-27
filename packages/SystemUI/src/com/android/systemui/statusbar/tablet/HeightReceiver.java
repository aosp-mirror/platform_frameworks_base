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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.WindowManagerPolicy;

public class HeightReceiver extends BroadcastReceiver {
    private static final String TAG = "StatusBar.HeightReceiver";

    public interface OnBarHeightChangedListener {
        public void onBarHeightChanged(int height);
    }

    Context mContext;
    ArrayList<OnBarHeightChangedListener> mListeners = new ArrayList<OnBarHeightChangedListener>();
    WindowManager mWindowManager;
    int mHeight;
    boolean mPlugged;

    public HeightReceiver(Context context) {
        mContext = context;
        mWindowManager = WindowManagerImpl.getDefault();
    }

    public void addOnBarHeightChangedListener(OnBarHeightChangedListener l) {
        mListeners.add(l);
        l.onBarHeightChanged(mHeight);
    }

    public void removeOnBarHeightChangedListener(OnBarHeightChangedListener l) {
        mListeners.remove(l);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final boolean plugged
                = intent.getBooleanExtra(WindowManagerPolicy.EXTRA_HDMI_PLUGGED_STATE, false);
        setPlugged(plugged);
    }

    public void registerReceiver() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(WindowManagerPolicy.ACTION_HDMI_PLUGGED);
        final Intent val = mContext.registerReceiver(this, filter);
        onReceive(mContext, val);
    }

    private void setPlugged(boolean plugged) {
        mPlugged = plugged;
        updateHeight();
    }

    public void updateHeight() {
        final Resources res = mContext.getResources();

        int height = -1;
        if (mPlugged) {
            final DisplayMetrics metrics = new DisplayMetrics();
            Display display = mWindowManager.getDefaultDisplay();
            display.getRealMetrics(metrics);

            //Slog.i(TAG, "updateHeight: display metrics=" + metrics);
            final int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
            final int externalShortSide = Math.min(display.getRawExternalWidth(),
                    display.getRawExternalHeight());
            height = shortSide - externalShortSide;
        }

        final int minHeight
                = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        if (height < minHeight) {
            height = minHeight;
        }
        Slog.i(TAG, "Resizing status bar plugged=" + mPlugged + " height="
                + height + " old=" + mHeight);
        mHeight = height;

        final int N = mListeners.size();
        for (int i=0; i<N; i++) {
            mListeners.get(i).onBarHeightChanged(height);
        }
    }

    public int getHeight() {
        return mHeight;
    }
}

