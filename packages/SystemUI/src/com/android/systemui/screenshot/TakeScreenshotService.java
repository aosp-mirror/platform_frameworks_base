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
import static com.android.systemui.screenshot.LogConfig.DEBUG_CALLBACK;
import static com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS;
import static com.android.systemui.screenshot.LogConfig.DEBUG_SERVICE;
import static com.android.systemui.screenshot.LogConfig.logTag;

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

import androidx.annotation.NonNull;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.ScreenshotHelper;
import com.android.systemui.R;
import com.android.systemui.shared.recents.utilities.BitmapUtil;

import java.util.function.Consumer;

import javax.inject.Inject;

public class TakeScreenshotService extends Service {
    private static final String TAG = logTag(TakeScreenshotService.class);

    private final ScreenshotController mScreenshot;
    private final UserManager mUserManager;
    private final UiEventLogger mUiEventLogger;
    private final ScreenshotNotificationsController mNotificationsController;
    private final Handler mHandler;

    private final BroadcastReceiver mCloseSystemDialogs = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction()) && mScreenshot != null) {
                if (DEBUG_DISMISS) {
                    Log.d(TAG, "Received ACTION_CLOSE_SYSTEM_DIALOGS");
                }
                if (!mScreenshot.isPendingSharedTransition()) {
                    mScreenshot.dismissScreenshot(false);
                }
            }
        }
    };

    @Inject
    public TakeScreenshotService(ScreenshotController screenshotController, UserManager userManager,
            UiEventLogger uiEventLogger,
            ScreenshotNotificationsController notificationsController) {
        if (DEBUG_SERVICE) {
            Log.d(TAG, "new " + this);
        }
        mHandler = new Handler(Looper.getMainLooper(), this::handleMessage);
        mScreenshot = screenshotController;
        mUserManager = userManager;
        mUiEventLogger = uiEventLogger;
        mNotificationsController = notificationsController;
    }

    @Override
    public void onCreate() {
        if (DEBUG_SERVICE) {
            Log.d(TAG, "onCreate()");
        }
    }

    @Override
    public IBinder onBind(@NonNull Intent intent) {
        registerReceiver(mCloseSystemDialogs, new IntentFilter(ACTION_CLOSE_SYSTEM_DIALOGS));
        final Messenger m = new Messenger(mHandler);
        if (DEBUG_SERVICE) {
            Log.d(TAG, "onBind: returning connection: " + m);
        }
        return m.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (DEBUG_SERVICE) {
            Log.d(TAG, "onUnbind");
        }
        if (mScreenshot != null) {
            mScreenshot.dismissScreenshot(true);
        }
        unregisterReceiver(mCloseSystemDialogs);
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mScreenshot != null) {
            mScreenshot.releaseContext();
        }
        if (DEBUG_SERVICE) {
            Log.d(TAG, "onDestroy");
        }
    }

    /** Respond to incoming Message via Binder (Messenger) */
    private boolean handleMessage(Message msg) {
        final Messenger replyTo = msg.replyTo;
        final Runnable onComplete = () -> sendComplete(replyTo);
        final Consumer<Uri> uriConsumer = (uri) -> reportUri(replyTo, uri);

        // If the storage for this user is locked, we have no place to store
        // the screenshot, so skip taking it instead of showing a misleading
        // animation and error notification.
        if (!mUserManager.isUserUnlocked()) {
            Log.w(TAG, "Skipping screenshot because storage is locked!");
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_save_user_locked_text);
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "handleMessage: calling uriConsumer.accept(null)");
            }
            uriConsumer.accept(null);
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "handleMessage: calling onComplete.run()");
            }
            onComplete.run();
            return true;
        }

        ScreenshotHelper.ScreenshotRequest screenshotRequest =
                (ScreenshotHelper.ScreenshotRequest) msg.obj;

        mUiEventLogger.log(ScreenshotEvent.getScreenshotSource(screenshotRequest.getSource()));

        switch (msg.what) {
            case WindowManager.TAKE_SCREENSHOT_FULLSCREEN:
                if (DEBUG_SERVICE) {
                    Log.d(TAG, "handleMessage: TAKE_SCREENSHOT_FULLSCREEN");
                }
                mScreenshot.takeScreenshotFullscreen(uriConsumer, onComplete);
                break;
            case WindowManager.TAKE_SCREENSHOT_SELECTED_REGION:
                if (DEBUG_SERVICE) {
                    Log.d(TAG, "handleMessage: TAKE_SCREENSHOT_SELECTED_REGION");
                }
                mScreenshot.takeScreenshotPartial(uriConsumer, onComplete);
                break;
            case WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE:
                if (DEBUG_SERVICE) {
                    Log.d(TAG, "handleMessage: TAKE_SCREENSHOT_PROVIDED_IMAGE");
                }
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
                Log.w(TAG, "Invalid screenshot option: " + msg.what);
                return false;
        }
        return true;
    };

    private void sendComplete(Messenger target) {
        try {
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "sendComplete: " + target);
            }
            target.send(Message.obtain(null, SCREENSHOT_MSG_PROCESS_COMPLETE));
        } catch (RemoteException e) {
            Log.d(TAG, "ignored remote exception", e);
        }
    }

    private void reportUri(Messenger target, Uri uri) {
        try {
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "reportUri: " + target + " -> " + uri);
            }
            target.send(Message.obtain(null, SCREENSHOT_MSG_URI, uri));
        } catch (RemoteException e) {
            Log.d(TAG, "ignored remote exception", e);
        }
    }
}
