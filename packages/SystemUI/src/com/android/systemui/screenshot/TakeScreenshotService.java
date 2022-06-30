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

import static android.app.admin.DevicePolicyResources.Strings.SystemUi.SCREENSHOT_BLOCKED_BY_ADMIN;
import static android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS;

import static com.android.internal.util.ScreenshotHelper.SCREENSHOT_MSG_PROCESS_COMPLETE;
import static com.android.internal.util.ScreenshotHelper.SCREENSHOT_MSG_URI;
import static com.android.systemui.screenshot.LogConfig.DEBUG_CALLBACK;
import static com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS;
import static com.android.systemui.screenshot.LogConfig.DEBUG_SERVICE;
import static com.android.systemui.screenshot.LogConfig.logTag;
import static com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER;

import android.annotation.MainThread;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
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
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.ScreenshotHelper;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;

public class TakeScreenshotService extends Service {
    private static final String TAG = logTag(TakeScreenshotService.class);

    private ScreenshotController mScreenshot;

    private final UserManager mUserManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final UiEventLogger mUiEventLogger;
    private final ScreenshotNotificationsController mNotificationsController;
    private final Handler mHandler;
    private final Context mContext;
    private final @Background Executor mBgExecutor;

    private final BroadcastReceiver mCloseSystemDialogs = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction()) && mScreenshot != null) {
                if (DEBUG_DISMISS) {
                    Log.d(TAG, "Received ACTION_CLOSE_SYSTEM_DIALOGS");
                }
                if (!mScreenshot.isPendingSharedTransition()) {
                    mUiEventLogger.log(SCREENSHOT_DISMISSED_OTHER);
                    mScreenshot.dismissScreenshot(false);
                }
            }
        }
    };

    /** Informs about coarse grained state of the Controller. */
    interface RequestCallback {
        /** Respond to the current request indicating the screenshot request failed. */
        void reportError();

        /** The controller has completed handling this request UI has been removed */
        void onFinish();
    }

    @Inject
    public TakeScreenshotService(ScreenshotController screenshotController, UserManager userManager,
            DevicePolicyManager devicePolicyManager, UiEventLogger uiEventLogger,
            ScreenshotNotificationsController notificationsController, Context context,
            @Background Executor bgExecutor) {
        if (DEBUG_SERVICE) {
            Log.d(TAG, "new " + this);
        }
        mHandler = new Handler(Looper.getMainLooper(), this::handleMessage);
        mScreenshot = screenshotController;
        mUserManager = userManager;
        mDevicePolicyManager = devicePolicyManager;
        mUiEventLogger = uiEventLogger;
        mNotificationsController = notificationsController;
        mContext = context;
        mBgExecutor = bgExecutor;
    }

    @Override
    public void onCreate() {
        if (DEBUG_SERVICE) {
            Log.d(TAG, "onCreate()");
        }
    }

    @Override
    public IBinder onBind(@NonNull Intent intent) {
        registerReceiver(mCloseSystemDialogs, new IntentFilter(ACTION_CLOSE_SYSTEM_DIALOGS),
                Context.RECEIVER_EXPORTED);
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
            mScreenshot.removeWindow();
            mScreenshot = null;
        }
        unregisterReceiver(mCloseSystemDialogs);
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mScreenshot != null) {
            mScreenshot.onDestroy();
            mScreenshot = null;
        }
        if (DEBUG_SERVICE) {
            Log.d(TAG, "onDestroy");
        }
    }

    static class RequestCallbackImpl implements RequestCallback {
        private final Messenger mReplyTo;

        RequestCallbackImpl(Messenger replyTo) {
            mReplyTo = replyTo;
        }

        public void reportError() {
            reportUri(mReplyTo, null);
            sendComplete(mReplyTo);
        }

        @Override
        public void onFinish() {
            sendComplete(mReplyTo);
        }
    }

    /** Respond to incoming Message via Binder (Messenger) */
    @MainThread
    private boolean handleMessage(Message msg) {
        final Messenger replyTo = msg.replyTo;
        final Consumer<Uri> uriConsumer = (uri) -> reportUri(replyTo, uri);
        RequestCallback requestCallback = new RequestCallbackImpl(replyTo);

        // If the storage for this user is locked, we have no place to store
        // the screenshot, so skip taking it instead of showing a misleading
        // animation and error notification.
        if (!mUserManager.isUserUnlocked()) {
            Log.w(TAG, "Skipping screenshot because storage is locked!");
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_save_user_locked_text);
            requestCallback.reportError();
            return true;
        }

        if (mDevicePolicyManager.getScreenCaptureDisabled(null, UserHandle.USER_ALL)) {
            mBgExecutor.execute(() -> {
                Log.w(TAG, "Skipping screenshot because an IT admin has disabled "
                        + "screenshots on the device");
                String blockedByAdminText = mDevicePolicyManager.getResources().getString(
                        SCREENSHOT_BLOCKED_BY_ADMIN,
                        () -> mContext.getString(R.string.screenshot_blocked_by_admin));
                mHandler.post(() ->
                        Toast.makeText(mContext, blockedByAdminText, Toast.LENGTH_SHORT).show());
                requestCallback.reportError();
            });
            return true;
        }

        ScreenshotHelper.ScreenshotRequest screenshotRequest =
                (ScreenshotHelper.ScreenshotRequest) msg.obj;

        ComponentName topComponent = screenshotRequest.getTopComponent();
        mUiEventLogger.log(ScreenshotEvent.getScreenshotSource(screenshotRequest.getSource()), 0,
                topComponent == null ? "" : topComponent.getPackageName());

        switch (msg.what) {
            case WindowManager.TAKE_SCREENSHOT_FULLSCREEN:
                if (DEBUG_SERVICE) {
                    Log.d(TAG, "handleMessage: TAKE_SCREENSHOT_FULLSCREEN");
                }
                mScreenshot.takeScreenshotFullscreen(topComponent, uriConsumer, requestCallback);
                break;
            case WindowManager.TAKE_SCREENSHOT_SELECTED_REGION:
                if (DEBUG_SERVICE) {
                    Log.d(TAG, "handleMessage: TAKE_SCREENSHOT_SELECTED_REGION");
                }
                mScreenshot.takeScreenshotPartial(topComponent, uriConsumer, requestCallback);
                break;
            case WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE:
                if (DEBUG_SERVICE) {
                    Log.d(TAG, "handleMessage: TAKE_SCREENSHOT_PROVIDED_IMAGE");
                }
                Bitmap screenshot = ScreenshotHelper.HardwareBitmapBundler.bundleToHardwareBitmap(
                        screenshotRequest.getBitmapBundle());
                Rect screenBounds = screenshotRequest.getBoundsInScreen();
                Insets insets = screenshotRequest.getInsets();
                int taskId = screenshotRequest.getTaskId();
                int userId = screenshotRequest.getUserId();

                if (screenshot == null) {
                    Log.e(TAG, "Got null bitmap from screenshot message");
                    mNotificationsController.notifyScreenshotError(
                            R.string.screenshot_failed_to_capture_text);
                    requestCallback.reportError();
                } else {
                    mScreenshot.handleImageAsScreenshot(screenshot, screenBounds, insets,
                            taskId, userId, topComponent, uriConsumer, requestCallback);
                }
                break;
            default:
                Log.w(TAG, "Invalid screenshot option: " + msg.what);
                return false;
        }
        return true;
    }

    private static void sendComplete(Messenger target) {
        try {
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "sendComplete: " + target);
            }
            target.send(Message.obtain(null, SCREENSHOT_MSG_PROCESS_COMPLETE));
        } catch (RemoteException e) {
            Log.d(TAG, "ignored remote exception", e);
        }
    }

    private static void reportUri(Messenger target, Uri uri) {
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
