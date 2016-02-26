/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.shortcut;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;
import android.util.Log;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import com.android.settingslib.accessibility.AccessibilityUtils;
import com.android.systemui.R;
import com.android.systemui.SystemUI;

import java.util.List;
import java.util.Set;

/**
 * Dispatches shortcut to System UI components
 */
public class ShortcutKeyDispatcher extends SystemUI
        implements ShortcutKeyServiceProxy.Callbacks {

    private static final String TAG = "ShortcutKeyDispatcher";

    private ShortcutKeyServiceProxy mShortcutKeyServiceProxy = new ShortcutKeyServiceProxy(this);
    private IWindowManager windowManagerService = WindowManagerGlobal.getWindowManagerService();

    protected final long META_MASK = ((long) KeyEvent.META_META_ON) << Integer.SIZE;
    protected final long ALT_MASK = ((long) KeyEvent.META_ALT_ON) << Integer.SIZE;
    protected final long CTRL_MASK = ((long) KeyEvent.META_CTRL_ON) << Integer.SIZE;
    protected final long SHIFT_MASK = ((long) KeyEvent.META_SHIFT_ON) << Integer.SIZE;

    /**
     * Registers a shortcut key to window manager.
     * @param shortcutCode packed representation of shortcut key code and meta information
     */
    public void registerShortcutKey(long shortcutCode) {
        try {
            windowManagerService.registerShortcutKey(shortcutCode, mShortcutKeyServiceProxy);
        } catch (RemoteException e) {
            // Do nothing
        }
    }

    @Override
    public void onShortcutKeyPressed(long shortcutCode) {}

    @Override
    public void start() {}
}
