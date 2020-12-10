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

import static android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS;

import static com.android.internal.util.ScreenshotHelper.SCREENSHOT_MSG_PROCESS_COMPLETE;
import static com.android.internal.util.ScreenshotHelper.SCREENSHOT_MSG_URI;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.android.systemui.shared.recents.utilities.BitmapUtil;

import java.util.function.Consumer;

import javax.inject.Inject;

public class TakeScreenshotService extends Service {
    private static final String TAG = "TakeScreenshotService";

    private final GlobalScreenshot mScreenshot;
    private final UserManager mUserManager;
    private final UiEventLogger mUiEventLogger;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction()) && mScreenshot != null) {
                mScreenshot.dismissScreenshot("close system dialogs", false);
            }
        }
    };

    private Handler mHandler = new Handler(Looper.myLooper()) {
        @Override
        public void handleMessage(Message msg) {
            final Messenger callback = msg.replyTo;
            Consumer<Uri> uriConsumer = uri -> {
                Message reply = Message.obtain(null, SCREENSHOT_MSG_URI, uri);
                try {
                    callback.send(reply);
                } catch (RemoteException e) {
                }
            };
            Runnable onComplete = () -> {
                Message reply = Message.obtain(null, SCREENSHOT_MSG_PROCESS_COMPLETE);
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
                post(() -> uriConsumer.accept(null));
                post(onComplete);
                return;
            }

            ScreenshotHelper.ScreenshotRequest screenshotRequest =
                    (ScreenshotHelper.ScreenshotRequest) msg.obj;

            mUiEventLogger.log(ScreenshotEvent.getScreenshotSource(screenshotRequest.getSource()));

            switch (msg.what) {
                case WindowManager.TAKE_SCREENSHOT_FULLSCREEN:
                    mScreenshot.takeScreenshotFullscreen(uriConsumer, onComplete);
                    break;
                case WindowManager.TAKE_SCREENSHOT_SELECTED_REGION:
                    mScreenshot.takeScreenshotPartial(uriConsumer, onComplete);
                    break;
                case WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE:
                    Bitmap screenshot = BitmapUtil.bundleToHardwareBitmap(
                            screenshotRequest.getBitmapBundle());
                    Rect screenBounds = screenshotRequest.getBoundsInScreen();
                    Insets insets = screenshotRequest.getInsets();
                    int taskId = screenshotRequest.getTaskId();
                    int userId = screenshotRequest.getUserId();
                    ComponentName topComponent = screenshotRequest.getTopComponent();
                    mScreenshot.handleImageAsScreenshot(screenshot, screenBounds, insets,
                            taskId, userId, topComponent, uriConsumer, onComplete);
                    break;
                default:
                    Log.d(TAG, "Invalid screenshot option: " + msg.what);
            }
        }
    };

    @Inject
    public TakeScreenshotService(GlobalScreenshot globalScreenshot, UserManager userManager,
            UiEventLogger uiEventLogger) {
        mScreenshot = globalScreenshot;
        mUserManager = userManager;
        mUiEventLogger = uiEventLogger;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // register broadcast receiver
        IntentFilter filter = new IntentFilter(ACTION_CLOSE_SYSTEM_DIALOGS);
        registerReceiver(mBroadcastReceiver, filter);

        return new Messenger(mHandler).getBinder();

    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (mScreenshot != null) mScreenshot.stopScreenshot();
        unregisterReceiver(mBroadcastReceiver);
        return true;
    }
}
