/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.screenshot.appclips;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.Display;

import androidx.annotation.Nullable;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;

import javax.inject.Inject;

/** An intermediary singleton object to help communicating with the cross process service. */
@SysUISingleton
public class AppClipsCrossProcessHelper {

    private final ServiceConnector<IAppClipsScreenshotHelperService> mProxyConnector;

    @Inject
    public AppClipsCrossProcessHelper(@Application Context context) {
        mProxyConnector = new ServiceConnector.Impl<IAppClipsScreenshotHelperService>(context,
                new Intent(context, AppClipsScreenshotHelperService.class),
                Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY
                        | Context.BIND_NOT_VISIBLE, context.getUserId(),
                IAppClipsScreenshotHelperService.Stub::asInterface);
    }

    /**
     * Returns a {@link Bitmap} captured in the SysUI process, {@code null} in case of an error.
     *
     * <p>Note: The SysUI process captures a {@link ScreenshotHardwareBufferInternal} which is ok to
     * pass around but not a {@link Bitmap}.
     */
    @Nullable
    public Bitmap takeScreenshot() {
        try {
            AndroidFuture<ScreenshotHardwareBufferInternal> future =
                    mProxyConnector.postForResult(
                            service -> service.takeScreenshot(Display.DEFAULT_DISPLAY));
            return future.get().createBitmapThenCloseBuffer();
        } catch (Exception e) {
            return null;
        }
    }
}
