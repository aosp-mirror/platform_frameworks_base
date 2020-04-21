/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.screenshot;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.ScreenshotHelper;

import java.util.function.Consumer;

import javax.inject.Inject;

public class TakeScreenshotService extends Service {
    private static final String TAG = "TakeScreenshotService";

    private final GlobalScreenshot mScreenshot;
    private final GlobalScreenshotLegacy mScreenshotLegacy;
    private final UserManager mUserManager;
    private final UiEventLogger mUiEventLogger;

    private Handler mHandler = new Handler(Looper.myLooper()) {
        @Override
        public void handleMessage(Message msg) {
            final Messenger callback = msg.replyTo;
            Consumer<Uri> finisher = uri -> {
                Message reply = Message.obtain(null, 1, uri);
                try {
                    callback.send(reply);
                } catch (RemoteException e) {
                }
            };

            // If the storage for this user is locked, we have no place to store
            // the screenshot, so skip taking it instead of showing a misleading
            // animation and error notification.
            if (!mUserManager.isUserUnlocked()) {
                Log.w(TAG, "Skipping screenshot because storage is locked!");
                post(() -> finisher.accept(null));
                return;
            }

            // TODO: clean up once notifications flow is fully deprecated
            boolean useCornerFlow = true;

            ScreenshotHelper.ScreenshotRequest screenshotRequest =
                    (ScreenshotHelper.ScreenshotRequest) msg.obj;

            mUiEventLogger.log(ScreenshotEvent.getScreenshotSource(screenshotRequest.getSource()));

            switch (msg.what) {
                case WindowManager.TAKE_SCREENSHOT_FULLSCREEN:
                    if (useCornerFlow) {
                        mScreenshot.takeScreenshot(finisher);
                    } else {
                        mScreenshotLegacy.takeScreenshot(
                                finisher, screenshotRequest.getHasStatusBar(),
                                screenshotRequest.getHasNavBar());
                    }
                    break;
                case WindowManager.TAKE_SCREENSHOT_SELECTED_REGION:
                    if (useCornerFlow) {
                        mScreenshot.takeScreenshotPartial(finisher);
                    } else {
                        mScreenshotLegacy.takeScreenshotPartial(
                                finisher, screenshotRequest.getHasStatusBar(),
                                screenshotRequest.getHasNavBar());
                    }
                    break;
                case WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE:
                    Bitmap screenshot = screenshotRequest.getBitmap();
                    Rect screenBounds = screenshotRequest.getBoundsInScreen();
                    Insets insets = screenshotRequest.getInsets();
                    int taskId = screenshotRequest.getTaskId();
                    if (useCornerFlow) {
                        mScreenshot.handleImageAsScreenshot(
                                screenshot, screenBounds, insets, taskId, finisher);
                    } else {
                        mScreenshotLegacy.handleImageAsScreenshot(
                                screenshot, screenBounds, insets, taskId, finisher);
                    }
                    break;
                default:
                    Log.d(TAG, "Invalid screenshot option: " + msg.what);
            }
        }
    };

    @Inject
    public TakeScreenshotService(GlobalScreenshot globalScreenshot,
            GlobalScreenshotLegacy globalScreenshotLegacy, UserManager userManager,
            UiEventLogger uiEventLogger) {
        mScreenshot = globalScreenshot;
        mScreenshotLegacy = globalScreenshotLegacy;
        mUserManager = userManager;
        mUiEventLogger = uiEventLogger;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new Messenger(mHandler).getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (mScreenshot != null) mScreenshot.stopScreenshot();
        // TODO (mkephart) remove once notifications flow is fully deprecated
        if (mScreenshotLegacy != null) mScreenshotLegacy.stopScreenshot();
        return true;
    }
}
