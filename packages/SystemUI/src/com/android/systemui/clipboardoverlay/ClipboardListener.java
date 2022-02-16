/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.CLIPBOARD_OVERLAY_ENABLED;

import android.content.ClipboardManager;
import android.content.Context;
import android.provider.DeviceConfig;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.util.DeviceConfigProxy;

import javax.inject.Inject;

/**
 * ClipboardListener brings up a clipboard overlay when something is copied to the clipboard.
 */
@SysUISingleton
public class ClipboardListener extends CoreStartable
        implements ClipboardManager.OnPrimaryClipChangedListener {

    private final DeviceConfigProxy mDeviceConfig;
    private final ClipboardOverlayControllerFactory mOverlayFactory;
    private final ClipboardManager mClipboardManager;
    private ClipboardOverlayController mClipboardOverlayController;

    @Inject
    public ClipboardListener(Context context, DeviceConfigProxy deviceConfigProxy,
            ClipboardOverlayControllerFactory overlayFactory, ClipboardManager clipboardManager) {
        super(context);
        mDeviceConfig = deviceConfigProxy;
        mOverlayFactory = overlayFactory;
        mClipboardManager = clipboardManager;
    }

    @Override
    public void start() {
        if (mDeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI, CLIPBOARD_OVERLAY_ENABLED, true)) {
            mClipboardManager.addPrimaryClipChangedListener(this);
        }
    }

    @Override
    public void onPrimaryClipChanged() {
        if (!mClipboardManager.hasPrimaryClip()) {
            return;
        }
        if (mClipboardOverlayController == null) {
            mClipboardOverlayController = mOverlayFactory.create(mContext);
        }
        mClipboardOverlayController.setClipData(
                mClipboardManager.getPrimaryClip(), mClipboardManager.getPrimaryClipSource());
        mClipboardOverlayController.setOnSessionCompleteListener(() -> {
            // Session is complete, free memory until it's needed again.
            mClipboardOverlayController = null;
        });
    }
}
