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

package com.android.systemui.accessibility;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;

import com.android.systemui.SystemUI;
import com.android.systemui.dagger.qualifiers.Main;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Class to handle changes to setting window_magnification value.
 */
@Singleton
public class WindowMagnification extends SystemUI {
    private WindowMagnificationController mWindowMagnificationController;
    private final Handler mHandler;

    @Inject
    public WindowMagnification(Context context, @Main Handler mainHandler) {
        super(context);
        mHandler = mainHandler;
    }

    @Override
    public void start() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WINDOW_MAGNIFICATION),
                true, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateWindowMagnification();
                    }
                });
    }

    private void updateWindowMagnification() {
        try {
            boolean enable = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.WINDOW_MAGNIFICATION) != 0;
            if (enable) {
                enableMagnification();
            } else {
                disableMagnification();
            }
        } catch (Settings.SettingNotFoundException e) {
            disableMagnification();
        }
    }

    private void enableMagnification() {
        if (mWindowMagnificationController == null) {
            mWindowMagnificationController = new WindowMagnificationController(mContext, mHandler);
        }
        mWindowMagnificationController.createWindowMagnification();
    }

    private void disableMagnification() {
        if (mWindowMagnificationController != null) {
            mWindowMagnificationController.deleteWindowMagnification();
        }
        mWindowMagnificationController = null;
    }
}
