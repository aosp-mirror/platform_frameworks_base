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

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.qualifiers.Main;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Class to handle changes to setting window_magnification value.
 */
@Singleton
public class WindowMagnification extends SystemUI implements WindowMagnifierCallback {
    private static final String TAG = "WindowMagnification";
    private static final int CONFIG_MASK =
            ActivityInfo.CONFIG_DENSITY | ActivityInfo.CONFIG_ORIENTATION;

    private WindowMagnificationController mWindowMagnificationController;
    private final Handler mHandler;
    //TODO:Set it by the request from AccessibilityManager.
    private WindowMagnificationConnectionImpl mWindowMagnificationConnectionImpl;

    private Configuration mLastConfiguration;

    @Inject
    public WindowMagnification(Context context, @Main Handler mainHandler) {
        super(context);
        mHandler = mainHandler;
        mLastConfiguration = new Configuration(context.getResources().getConfiguration());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        final int configDiff = newConfig.diff(mLastConfiguration);
        if ((configDiff & CONFIG_MASK) == 0) {
            return;
        }
        mLastConfiguration.setTo(newConfig);
        if (mWindowMagnificationController != null) {
            mWindowMagnificationController.onConfigurationChanged(configDiff);
        }
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
                enableMagnification(
                        mContext.getResources().getInteger(R.integer.magnification_default_scale));
            } else {
                disableMagnification();
            }
        } catch (Settings.SettingNotFoundException e) {
            disableMagnification();
        }
    }

    private void enableMagnification(float scale) {
        enableWindowMagnification(Display.DEFAULT_DISPLAY, scale, Float.NaN, Float.NaN);
    }

    private void disableMagnification() {
        disableWindowMagnification(Display.DEFAULT_DISPLAY);
    }

    @MainThread
    void enableWindowMagnification(int displayId, float scale, float centerX, float centerY) {
        //TODO: b/144080869 support multi-display.
        if (mWindowMagnificationController == null) {
            mWindowMagnificationController = new WindowMagnificationController(mContext, null,
                    this);
        }
        mWindowMagnificationController.enableWindowMagnification(scale, centerX, centerY);
    }

    @MainThread
    void setScale(int displayId, float scale) {
        //TODO: b/144080869 support multi-display.
        if (mWindowMagnificationController != null) {
            mWindowMagnificationController.setScale(scale);
        }
    }

    @MainThread
    void moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
        //TODO: b/144080869 support multi-display.
        if (mWindowMagnificationController != null) {
            mWindowMagnificationController.moveWindowMagnifier(offsetX, offsetY);
        }
    }

    @MainThread
    void disableWindowMagnification(int displayId) {
        //TODO: b/144080869 support multi-display.
        if (mWindowMagnificationController != null) {
            mWindowMagnificationController.deleteWindowMagnification();
        }
        mWindowMagnificationController = null;
    }

    @Override
    public void onWindowMagnifierBoundsChanged(int displayId, Rect frame) {
        if (mWindowMagnificationConnectionImpl != null) {
            mWindowMagnificationConnectionImpl.onWindowMagnifierBoundsChanged(displayId, frame);
        }
    }

    private static class WindowMagnificationConnectionImpl extends
            IWindowMagnificationConnection.Stub {

        private static final String TAG = "WindowMagnificationConnectionImpl";

        private IWindowMagnificationConnectionCallback mConnectionCallback;
        private final WindowMagnification mWindowMagnification;
        private final Handler mHandler;

        WindowMagnificationConnectionImpl(@NonNull WindowMagnification windowMagnification,
                @Main Handler mainHandler) {
            mWindowMagnification = windowMagnification;
            mHandler = mainHandler;
        }

        @Override
        public void enableWindowMagnification(int displayId, float scale, float centerX,
                float centerY) {
            mHandler.post(
                    () -> mWindowMagnification.enableWindowMagnification(displayId, scale, centerX,
                            centerY));
        }

        @Override
        public void setScale(int displayId, float scale) {
            mHandler.post(() -> mWindowMagnification.setScale(displayId, scale));
        }

        @Override
        public void disableWindowMagnification(int displayId) {
            mHandler.post(() -> mWindowMagnification.disableWindowMagnification(displayId));
        }

        @Override
        public void moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
            mHandler.post(
                    () -> mWindowMagnification.moveWindowMagnifier(displayId, offsetX, offsetY));
        }

        @Override
        public void setConnectionCallback(IWindowMagnificationConnectionCallback callback) {
            mConnectionCallback = callback;
        }

        void onWindowMagnifierBoundsChanged(int displayId, Rect frame) {
            if (mConnectionCallback != null) {
                try {
                    mConnectionCallback.onWindowMagnifierBoundsChanged(displayId, frame);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to inform bounds changed", e);
                }
            }
        }
    }
}
