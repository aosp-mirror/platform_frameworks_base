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

package com.android.wm.shell.sysui;

import static android.content.pm.ActivityInfo.CONFIG_ASSETS_PATHS;
import static android.content.pm.ActivityInfo.CONFIG_FONT_SCALE;
import static android.content.pm.ActivityInfo.CONFIG_LAYOUT_DIRECTION;
import static android.content.pm.ActivityInfo.CONFIG_LOCALE;
import static android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_UI_MODE;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_SYSUI_EVENTS;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ExternalThread;

import java.io.PrintWriter;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Handles event callbacks from SysUI that can be used within the Shell.
 */
public class ShellController {
    private static final String TAG = ShellController.class.getSimpleName();

    private final ShellExecutor mMainExecutor;
    private final ShellInterfaceImpl mImpl = new ShellInterfaceImpl();

    private final CopyOnWriteArrayList<ConfigurationChangeListener> mListeners =
            new CopyOnWriteArrayList<>();
    private Configuration mLastConfiguration;


    public ShellController(ShellExecutor mainExecutor) {
        mMainExecutor = mainExecutor;
    }

    /**
     * Returns the external interface to this controller.
     */
    public ShellInterface asShell() {
        return mImpl;
    }

    /**
     * Adds a new configuration listener. The configuration change callbacks are not made in any
     * particular order.
     */
    public void addConfigurationChangeListener(ConfigurationChangeListener listener) {
        mListeners.remove(listener);
        mListeners.add(listener);
    }

    /**
     * Removes an existing configuration listener.
     */
    public void removeConfigurationChangeListener(ConfigurationChangeListener listener) {
        mListeners.remove(listener);
    }

    @VisibleForTesting
    void onConfigurationChanged(Configuration newConfig) {
        // The initial config is send on startup and doesn't trigger listener callbacks
        if (mLastConfiguration == null) {
            mLastConfiguration = new Configuration(newConfig);
            ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "Initial Configuration: %s", newConfig);
            return;
        }

        final int diff = newConfig.diff(mLastConfiguration);
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "New configuration change: %s", newConfig);
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "\tchanges=%s",
                Configuration.configurationDiffToString(diff));
        final boolean densityFontScaleChanged = (diff & CONFIG_FONT_SCALE) != 0
                || (diff & ActivityInfo.CONFIG_DENSITY) != 0;
        final boolean smallestScreenWidthChanged = (diff & CONFIG_SMALLEST_SCREEN_SIZE) != 0;
        final boolean themeChanged = (diff & CONFIG_ASSETS_PATHS) != 0
                || (diff & CONFIG_UI_MODE) != 0;
        final boolean localOrLayoutDirectionChanged = (diff & CONFIG_LOCALE) != 0
                || (diff & CONFIG_LAYOUT_DIRECTION) != 0;

        // Update the last configuration and call listeners
        mLastConfiguration.updateFrom(newConfig);
        for (ConfigurationChangeListener listener : mListeners) {
            listener.onConfigurationChanged(newConfig);
            if (densityFontScaleChanged) {
                listener.onDensityOrFontScaleChanged();
            }
            if (smallestScreenWidthChanged) {
                listener.onSmallestScreenWidthChanged();
            }
            if (themeChanged) {
                listener.onThemeChanged();
            }
            if (localOrLayoutDirectionChanged) {
                listener.onLocaleOrLayoutDirectionChanged();
            }
        }
    }

    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mListeners=" + mListeners.size());
        pw.println(innerPrefix + "mLastConfiguration=" + mLastConfiguration);
    }

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    @ExternalThread
    private class ShellInterfaceImpl implements ShellInterface {
        @Override
        public void onConfigurationChanged(Configuration newConfiguration) {
            mMainExecutor.execute(() ->
                    ShellController.this.onConfigurationChanged(newConfiguration));
        }
    }
}
