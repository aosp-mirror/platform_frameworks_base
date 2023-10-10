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
import static com.android.systemui.flags.Flags.MULTI_DISPLAY_SCREENSHOT;
import static com.android.systemui.screenshot.LogConfig.DEBUG_CALLBACK;
import static com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS;
import static com.android.systemui.screenshot.LogConfig.DEBUG_SERVICE;
import static com.android.systemui.screenshot.LogConfig.logTag;
import static com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_CAPTURE_FAILED;
import static com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER;

import android.annotation.MainThread;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.view.Display;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.ScreenshotRequest;
import com.android.systemui.res.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.flags.FeatureFlags;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Provider;

public class TakeScreenshotService extends Service {
    private static final String TAG = logTag(TakeScreenshotService.class);

    private final ScreenshotController mScreenshot;

    private final UserManager mUserManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final UiEventLogger mUiEventLogger;
    private final ScreenshotNotificationsController mNotificationsController;
    private final Handler mHandler;
    private final Context mContext;
    private final @Background Executor mBgExecutor;
    private final RequestProcessor mProcessor;
    private final FeatureFlags mFeatureFlags;

    private final BroadcastReceiver mCloseSystemDialogs = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction()) && mScreenshot != null) {
                if (DEBUG_DISMISS) {
                    Log.d(TAG, "Received ACTION_CLOSE_SYSTEM_DIALOGS");
                }
                if (mFeatureFlags.isEnabled(MULTI_DISPLAY_SCREENSHOT)) {
                    // TODO(b/295143676): move receiver inside executor when the flag is enabled.
                    mTakeScreenshotExecutor.get().onCloseSystemDialogsReceived();
                } else if (!mScreenshot.isPendingSharedTransition()) {
                    mScreenshot.dismissScreenshot(SCREENSHOT_DISMISSED_OTHER);
                }
            }
        }
    };
    private final Provider<TakeScreenshotExecutor> mTakeScreenshotExecutor;


    /** Informs about coarse grained state of the Controller. */
    public interface RequestCallback {
        /**
         * Respond to the current request indicating the screenshot request failed.
         * <p>
         * After this, the service will be disconnected and all visible UI is removed.
         */
        void reportError();

        /** The controller has completed handling this request UI has been removed */
        void onFinish();
    }

    @Inject
    public TakeScreenshotService(ScreenshotController.Factory screenshotControllerFactory,
            UserManager userManager, DevicePolicyManager devicePolicyManager,
            UiEventLogger uiEventLogger,
            ScreenshotNotificationsController.Factory notificationsControllerFactory,
            Context context, @Background Executor bgExecutor, FeatureFlags featureFlags,
            RequestProcessor processor, Provider<TakeScreenshotExecutor> takeScreenshotExecutor) {
        if (DEBUG_SERVICE) {
            Log.d(TAG, "new " + this);
        }
        mHandler = new Handler(Looper.getMainLooper(), this::handleMessage);
        mUserManager = userManager;
        mDevicePolicyManager = devicePolicyManager;
        mUiEventLogger = uiEventLogger;
        mNotificationsController = notificationsControllerFactory.create(Display.DEFAULT_DISPLAY);
        mContext = context;
        mBgExecutor = bgExecutor;
        mFeatureFlags = featureFlags;
        mProcessor = processor;
        mTakeScreenshotExecutor = takeScreenshotExecutor;
        if (mFeatureFlags.isEnabled(MULTI_DISPLAY_SCREENSHOT)) {
            mScreenshot = null;
        } else {
            mScreenshot = screenshotControllerFactory.create(
                    Display.DEFAULT_DISPLAY, /* showUIOnExternalDisplay= */ false);
        }
    }

    @Override
    public void onCreate() {
        if (DEBUG_SERVICE) {
            Log.d(TAG, "onCreate()");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
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
        if (mFeatureFlags.isEnabled(MULTI_DISPLAY_SCREENSHOT)) {
            mTakeScreenshotExecutor.get().removeWindows();
        } else {
            mScreenshot.removeWindow();
        }
        unregisterReceiver(mCloseSystemDialogs);
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mFeatureFlags.isEnabled(MULTI_DISPLAY_SCREENSHOT)) {
            mTakeScreenshotExecutor.get().onDestroy();
        } else {
            mScreenshot.onDestroy();
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

    @MainThread
    private boolean handleMessage(Message msg) {
        final Messenger replyTo = msg.replyTo;
        final Consumer<Uri> onSaved = (uri) -> reportUri(replyTo, uri);
        RequestCallback callback = new RequestCallbackImpl(replyTo);

        ScreenshotRequest request = (ScreenshotRequest) msg.obj;

        handleRequest(request, onSaved, callback);
        return true;
    }

    @MainThread
    @VisibleForTesting
    void handleRequest(ScreenshotRequest request, Consumer<Uri> onSaved,
            RequestCallback callback) {
        // If the storage for this user is locked, we have no place to store
        // the screenshot, so skip taking it instead of showing a misleading
        // animation and error notification.
        if (!mUserManager.isUserUnlocked()) {
            Log.w(TAG, "Skipping screenshot because storage is locked!");
            logFailedRequest(request);
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_save_user_locked_text);
            callback.reportError();
            return;
        }

        if (mDevicePolicyManager.getScreenCaptureDisabled(null, UserHandle.USER_ALL)) {
            mBgExecutor.execute(() -> {
                Log.w(TAG, "Skipping screenshot because an IT admin has disabled "
                        + "screenshots on the device");
                logFailedRequest(request);
                String blockedByAdminText = mDevicePolicyManager.getResources().getString(
                        SCREENSHOT_BLOCKED_BY_ADMIN,
                        () -> mContext.getString(R.string.screenshot_blocked_by_admin));
                mHandler.post(() ->
                        Toast.makeText(mContext, blockedByAdminText, Toast.LENGTH_SHORT).show());
                callback.reportError();
            });
            return;
        }

        Log.d(TAG, "Processing screenshot data");


        if (mFeatureFlags.isEnabled(MULTI_DISPLAY_SCREENSHOT)) {
            mTakeScreenshotExecutor.get().executeScreenshotsAsync(request, onSaved, callback);
            return;
        }
        // TODO(b/295143676): Delete the following after the flag is released.
        try {
            ScreenshotData screenshotData = ScreenshotData.fromRequest(
                    request, Display.DEFAULT_DISPLAY);
            mProcessor.processAsync(screenshotData, (data) ->
                    dispatchToController(data, onSaved, callback));

        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to process screenshot request!", e);
            logFailedRequest(request);
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
            callback.reportError();
        }
    }

    // TODO(b/295143676): Delete this.
    private void dispatchToController(ScreenshotData screenshot,
            Consumer<Uri> uriConsumer, RequestCallback callback) {
        mUiEventLogger.log(ScreenshotEvent.getScreenshotSource(screenshot.getSource()), 0,
                screenshot.getPackageNameString());
        Log.d(TAG, "Screenshot request: " + screenshot);
        mScreenshot.handleScreenshot(screenshot, uriConsumer, callback);
    }

    private void logFailedRequest(ScreenshotRequest request) {
        ComponentName topComponent = request.getTopComponent();
        String packageName = topComponent == null ? "" : topComponent.getPackageName();
        mUiEventLogger.log(
                ScreenshotEvent.getScreenshotSource(request.getSource()), 0, packageName);
        mUiEventLogger.log(SCREENSHOT_CAPTURE_FAILED, 0, packageName);
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
