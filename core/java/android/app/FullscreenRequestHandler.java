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

package android.app;

import static android.app.Activity.FULLSCREEN_MODE_REQUEST_EXIT;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.OutcomeReceiver;

/**
 * @hide
 */
public class FullscreenRequestHandler {
    @IntDef(prefix = { "RESULT_" }, value = {
            RESULT_APPROVED,
            RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY,
            RESULT_FAILED_NOT_TOP_FOCUSED
    })
    public @interface RequestResult {}

    public static final int RESULT_APPROVED = 0;
    public static final int RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY = 1;
    public static final int RESULT_FAILED_NOT_TOP_FOCUSED = 2;

    public static final String REMOTE_CALLBACK_RESULT_KEY = "result";

    static void requestFullscreenMode(@NonNull @Activity.FullscreenModeRequest int request,
            @Nullable OutcomeReceiver<Void, Throwable> approvalCallback, Configuration config,
            IBinder token) {
        int earlyCheck = earlyCheckRequestMatchesWindowingMode(
                request, config.windowConfiguration.getWindowingMode());
        if (earlyCheck != RESULT_APPROVED) {
            if (approvalCallback != null) {
                notifyFullscreenRequestResult(approvalCallback, earlyCheck);
            }
            return;
        }
        try {
            if (approvalCallback != null) {
                ActivityClient.getInstance().requestMultiwindowFullscreen(token, request,
                        new IRemoteCallback.Stub() {
                            @Override
                            public void sendResult(Bundle res) {
                                notifyFullscreenRequestResult(
                                        approvalCallback, res.getInt(REMOTE_CALLBACK_RESULT_KEY));
                            }
                        });
            } else {
                ActivityClient.getInstance().requestMultiwindowFullscreen(token, request, null);
            }
        } catch (Throwable e) {
            if (approvalCallback != null) {
                approvalCallback.onError(e);
            }
        }
    }

    private static void notifyFullscreenRequestResult(
            OutcomeReceiver<Void, Throwable> callback, int result) {
        Throwable e = null;
        switch (result) {
            case RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY:
                e = new IllegalStateException("The window is not in fullscreen by calling the "
                        + "requestFullscreenMode API before, such that cannot be restored.");
                break;
            case RESULT_FAILED_NOT_TOP_FOCUSED:
                e = new IllegalStateException("The window is not the top focused window.");
                break;
            default:
                callback.onResult(null);
                break;
        }
        if (e != null) {
            callback.onError(e);
        }
    }

    private static int earlyCheckRequestMatchesWindowingMode(int request, int windowingMode) {
        if (request == FULLSCREEN_MODE_REQUEST_EXIT) {
            if (windowingMode != WINDOWING_MODE_FULLSCREEN) {
                return RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY;
            }
        }
        return RESULT_APPROVED;
    }
}
