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

package com.android.server.inputmethod;

import static android.provider.InputMethodManagerDeviceConfig.KEY_HIDE_IME_WHEN_NO_EDITOR_FOCUS;

import android.app.ActivityThread;
import android.provider.DeviceConfig;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Class for the device-level configuration related to the input method manager
 * platform features in {@link DeviceConfig}.
 */
final class InputMethodDeviceConfigs {
    private boolean mHideImeWhenNoEditorFocus;
    private final DeviceConfig.OnPropertiesChangedListener mDeviceConfigChangedListener;

    InputMethodDeviceConfigs() {
        mDeviceConfigChangedListener = properties -> {
            if (!DeviceConfig.NAMESPACE_INPUT_METHOD_MANAGER.equals(properties.getNamespace())) {
                return;
            }
            for (String name : properties.getKeyset()) {
                if (KEY_HIDE_IME_WHEN_NO_EDITOR_FOCUS.equals(name)) {
                    mHideImeWhenNoEditorFocus = properties.getBoolean(name,
                            true /* defaultValue */);
                    break;
                }
            }
        };
        mHideImeWhenNoEditorFocus = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_INPUT_METHOD_MANAGER,
                KEY_HIDE_IME_WHEN_NO_EDITOR_FOCUS, true);
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_INPUT_METHOD_MANAGER,
                ActivityThread.currentApplication().getMainExecutor(),
                mDeviceConfigChangedListener);
    }

    /**
     * Whether the IME should be hidden when the window gained focus without an editor focused.
     */
    public boolean shouldHideImeWhenNoEditorFocus() {
        return mHideImeWhenNoEditorFocus;
    }

    @VisibleForTesting
    void destroy() {
        DeviceConfig.removeOnPropertiesChangedListener(mDeviceConfigChangedListener);
    }
}
