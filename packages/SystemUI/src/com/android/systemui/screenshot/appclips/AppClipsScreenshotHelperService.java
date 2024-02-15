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

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;
import android.window.ScreenCapture.SynchronousScreenCaptureListener;

import androidx.annotation.Nullable;

import com.android.wm.shell.bubbles.Bubbles;

import java.util.Optional;

import javax.inject.Inject;

/**
 * A helper service that runs in SysUI process and helps {@link AppClipsActivity} which runs in its
 * own separate process take a screenshot.
 *
 * <p>Note: This service always runs in the SysUI process running on the system user irrespective of
 * which user started the service. This is required so that the correct instance of {@link Bubbles}
 * instance is injected. This is set via attribute {@code android:singleUser=”true”} in
 * AndroidManifest.
 */
public class AppClipsScreenshotHelperService extends Service {

    private final Optional<Bubbles> mOptionalBubbles;

    @Inject
    public AppClipsScreenshotHelperService(Optional<Bubbles> optionalBubbles) {
        mOptionalBubbles = optionalBubbles;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new IAppClipsScreenshotHelperService.Stub() {
            @Override
            @Nullable
            public ScreenshotHardwareBufferInternal takeScreenshot(int displayId) {
                if (mOptionalBubbles.isEmpty()) {
                    return null;
                }

                SynchronousScreenCaptureListener screenshotSync =
                        mOptionalBubbles.get().getScreenshotExcludingBubble(displayId);
                ScreenshotHardwareBuffer screenshotHardwareBuffer = screenshotSync.getBuffer();
                if (screenshotHardwareBuffer == null) {
                    return null;
                }

                return new ScreenshotHardwareBufferInternal(screenshotHardwareBuffer);
            }
        };
    }
}
