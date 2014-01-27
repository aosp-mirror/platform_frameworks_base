/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.RenderAction;

import android.content.Context;
import android.view.BridgeInflater;
import android.view.FallbackEventHandler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManagerPolicy;

/**
 * Custom implementation of PolicyManager that does nothing to run in LayoutLib.
 *
 */
public class PolicyManager {

    public static Window makeNewWindow(Context context) {
        // this will likely crash somewhere beyond so we log it.
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "Call to PolicyManager.makeNewWindow is not supported", null);
        return null;
    }

    public static LayoutInflater makeNewLayoutInflater(Context context) {
        return new BridgeInflater(context, RenderAction.getCurrentContext().getProjectCallback());
    }

    public static WindowManagerPolicy makeNewWindowManager() {
        // this will likely crash somewhere beyond so we log it.
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "Call to PolicyManager.makeNewWindowManager is not supported", null);
        return null;
    }

    public static FallbackEventHandler makeNewFallbackEventHandler(Context context) {
        return new FallbackEventHandler() {
            @Override
            public void setView(View v) {
            }

            @Override
            public void preDispatchKeyEvent(KeyEvent event) {
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                return false;
            }
        };
    }
}
