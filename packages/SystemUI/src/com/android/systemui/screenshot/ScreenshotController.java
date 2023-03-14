/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;

import static com.android.systemui.flags.Flags.SCREENSHOT_WORK_PROFILE_POLICY;
import static com.android.systemui.screenshot.LogConfig.DEBUG_ANIM;
import static com.android.systemui.screenshot.LogConfig.DEBUG_CALLBACK;
import static com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS;
import static com.android.systemui.screenshot.LogConfig.DEBUG_INPUT;
import static com.android.systemui.screenshot.LogConfig.DEBUG_UI;
import static com.android.systemui.screenshot.LogConfig.DEBUG_WINDOW;
import static com.android.systemui.screenshot.LogConfig.logTag;
import static com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER;
import static com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_INTERACTION_TIMEOUT;

import static java.util.Objects.requireNonNull;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ExitTransitionCoordinator;
import android.app.ExitTransitionCoordinator.ExitTransitionCallbacks;
import android.app.ICompatCameraControlCallback;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.AudioAttributes;
import android.media.AudioSystem;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.ScrollCaptureResponse;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.window.WindowContext;

import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.android.internal.app.ChooserActivity;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.policy.PhoneWindow;
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.clipboardoverlay.ClipboardOverlayController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.screenshot.ScreenshotController.SavedImageData.ActionTransition;
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback;
import com.android.systemui.util.Assert;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.inject.Inject;

/**
 * Controls the state and flow for screenshots.
 */
public class ScreenshotController {
    private static final String TAG = logTag(ScreenshotController.class);

    private ScrollCaptureResponse mLastScrollCaptureResponse;
    private ListenableFuture<ScrollCaptureResponse> mLastScrollCaptureRequest;

    /**
     * This is effectively a no-op, but we need something non-null to pass in, in order to
     * successfully override the pending activity entrance animation.
     */
    static final IRemoteAnimationRunner.Stub SCREENSHOT_REMOTE_RUNNER =
            new IRemoteAnimationRunner.Stub() {
                @Override
                public void onAnimationStart(
                        @WindowManager.TransitionOldType int transit,
                        RemoteAnimationTarget[] apps,
                        RemoteAnimationTarget[] wallpapers,
                        RemoteAnimationTarget[] nonApps,
                        final IRemoteAnimationFinishedCallback finishedCallback) {
                    try {
                        finishedCallback.onAnimationFinished();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error finishing screenshot remote animation", e);
                    }
                }

                @Override
                public void onAnimationCancelled(boolean isKeyguardOccluded) {
                }
            };

    /**
     * POD used in the AsyncTask which saves an image in the background.
     */
    static class SaveImageInBackgroundData {
        public Bitmap image;
        public Consumer<Uri> finisher;
        public ScreenshotController.ActionsReadyListener mActionsReadyListener;
        public ScreenshotController.QuickShareActionReadyListener mQuickShareActionsReadyListener;
        public UserHandle owner;

        void clearImage() {
            image = null;
        }
    }

    /**
     * Structure returned by the SaveImageInBackgroundTask
     */
    static class SavedImageData {
        public Uri uri;
        public Supplier<ActionTransition> shareTransition;
        public Supplier<ActionTransition> editTransition;
        public Notification.Action deleteAction;
        public List<Notification.Action> smartActions;
        public Notification.Action quickShareAction;
        public UserHandle owner;
        public String subject;  // Title for sharing

        /**
         * POD for shared element transition.
         */
        static class ActionTransition {
            public Bundle bundle;
            public Notification.Action action;
            public Runnable onCancelRunnable;
        }

        /**
         * Used to reset the return data on error
         */
        public void reset() {
            uri = null;
            shareTransition = null;
            editTransition = null;
            deleteAction = null;
            smartActions = null;
            quickShareAction = null;
            subject = null;
        }
    }

    /**
     * Structure returned by the QueryQuickShareInBackgroundTask
     */
    static class QuickShareData {
        public Notification.Action quickShareAction;

        /**
         * Used to reset the return data on error
         */
        public void reset() {
            quickShareAction = null;
        }
    }

    interface ActionsReadyListener {
        void onActionsReady(ScreenshotController.SavedImageData imageData);
    }

    interface QuickShareActionReadyListener {
        void onActionsReady(ScreenshotController.QuickShareData quickShareData);
    }

    interface TransitionDestination {
        /**
         * Allows the long screenshot activity to call back with a destination location (the bounds
         * on screen of the destination for the transitioning view) and a Runnable to be run once
         * the transition animation is complete.
         */
        void setTransitionDestination(Rect transitionDestination, Runnable onTransitionEnd);
    }

    // These strings are used for communicating the action invoked to
    // ScreenshotNotificationSmartActionsProvider.
    static final String EXTRA_ACTION_TYPE = "android:screenshot_action_type";
    static final String EXTRA_ID = "android:screenshot_id";
    static final String ACTION_TYPE_DELETE = "Delete";
    static final String ACTION_TYPE_SHARE = "Share";
    static final String ACTION_TYPE_EDIT = "Edit";
    static final String EXTRA_SMART_ACTIONS_ENABLED = "android:smart_actions_enabled";
    static final String EXTRA_OVERRIDE_TRANSITION = "android:screenshot_override_transition";
    static final String EXTRA_ACTION_INTENT = "android:screenshot_action_intent";

    static final String SCREENSHOT_URI_ID = "android:screenshot_uri_id";
    static final String EXTRA_CANCEL_NOTIFICATION = "android:screenshot_cancel_notification";
    static final String EXTRA_DISALLOW_ENTER_PIP = "android:screenshot_disallow_enter_pip";

    // From WizardManagerHelper.java
    private static final String SETTINGS_SECURE_USER_SETUP_COMPLETE = "user_setup_complete";

    private static final int SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS = 6000;

    private final WindowContext mContext;
    private final FeatureFlags mFlags;
    private final ScreenshotNotificationsController mNotificationsController;
    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final UiEventLogger mUiEventLogger;
    private final ImageExporter mImageExporter;
    private final ImageCapture mImageCapture;
    private final Executor mMainExecutor;
    private final ExecutorService mBgExecutor;
    private final BroadcastSender mBroadcastSender;

    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mWindowLayoutParams;
    private final AccessibilityManager mAccessibilityManager;
    private final ListenableFuture<MediaPlayer> mCameraSound;
    private final ScrollCaptureClient mScrollCaptureClient;
    private final PhoneWindow mWindow;
    private final DisplayManager mDisplayManager;
    private final ScrollCaptureController mScrollCaptureController;
    private final LongScreenshotData mLongScreenshotHolder;
    private final boolean mIsLowRamDevice;
    private final ScreenshotNotificationSmartActionsProvider
            mScreenshotNotificationSmartActionsProvider;
    private final TimeoutHandler mScreenshotHandler;
    private final ActionIntentExecutor mActionExecutor;
    private final UserManager mUserManager;

    private final OnBackInvokedCallback mOnBackInvokedCallback = () -> {
        if (DEBUG_INPUT) {
            Log.d(TAG, "Predictive Back callback dispatched");
        }
        respondToBack();
    };

    private ScreenshotView mScreenshotView;
    private Bitmap mScreenBitmap;
    private SaveImageInBackgroundTask mSaveInBgTask;
    private boolean mScreenshotTakenInPortrait;
    private boolean mBlockAttach;

    private Animator mScreenshotAnimation;
    private RequestCallback mCurrentRequestCallback;
    private String mPackageName = "";
    private BroadcastReceiver mCopyBroadcastReceiver;

    /** Tracks config changes that require re-creating UI */
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
            ActivityInfo.CONFIG_ORIENTATION
                    | ActivityInfo.CONFIG_LAYOUT_DIRECTION
                    | ActivityInfo.CONFIG_LOCALE
                    | ActivityInfo.CONFIG_UI_MODE
                    | ActivityInfo.CONFIG_SCREEN_LAYOUT
                    | ActivityInfo.CONFIG_ASSETS_PATHS);

    @Inject
    ScreenshotController(
            Context context,
            FeatureFlags flags,
            ScreenshotSmartActions screenshotSmartActions,
            ScreenshotNotificationsController screenshotNotificationsController,
            ScrollCaptureClient scrollCaptureClient,
            UiEventLogger uiEventLogger,
            ImageExporter imageExporter,
            ImageCapture imageCapture,
            @Main Executor mainExecutor,
            ScrollCaptureController scrollCaptureController,
            LongScreenshotData longScreenshotHolder,
            ActivityManager activityManager,
            TimeoutHandler timeoutHandler,
            BroadcastSender broadcastSender,
            ScreenshotNotificationSmartActionsProvider screenshotNotificationSmartActionsProvider,
            ActionIntentExecutor actionExecutor,
            UserManager userManager
    ) {
        mScreenshotSmartActions = screenshotSmartActions;
        mNotificationsController = screenshotNotificationsController;
        mScrollCaptureClient = scrollCaptureClient;
        mUiEventLogger = uiEventLogger;
        mImageExporter = imageExporter;
        mImageCapture = imageCapture;
        mMainExecutor = mainExecutor;
        mScrollCaptureController = scrollCaptureController;
        mLongScreenshotHolder = longScreenshotHolder;
        mIsLowRamDevice = activityManager.isLowRamDevice();
        mScreenshotNotificationSmartActionsProvider = screenshotNotificationSmartActionsProvider;
        mBgExecutor = Executors.newSingleThreadExecutor();
        mBroadcastSender = broadcastSender;

        mScreenshotHandler = timeoutHandler;
        mScreenshotHandler.setDefaultTimeoutMillis(SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS);
        mScreenshotHandler.setOnTimeoutRunnable(() -> {
            if (DEBUG_UI) {
                Log.d(TAG, "Corner timeout hit");
            }
            dismissScreenshot(SCREENSHOT_INTERACTION_TIMEOUT);
        });

        mDisplayManager = requireNonNull(context.getSystemService(DisplayManager.class));
        final Context displayContext = context.createDisplayContext(getDefaultDisplay());
        mContext = (WindowContext) displayContext.createWindowContext(TYPE_SCREENSHOT, null);
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mFlags = flags;
        mActionExecutor = actionExecutor;
        mUserManager = userManager;

        mAccessibilityManager = AccessibilityManager.getInstance(mContext);

        // Setup the window that we are going to use
        mWindowLayoutParams = FloatingWindowUtil.getFloatingWindowParams();
        mWindowLayoutParams.setTitle("ScreenshotAnimation");

        mWindow = FloatingWindowUtil.getFloatingWindow(mContext);
        mWindow.setWindowManager(mWindowManager, null, null);

        mConfigChanges.applyNewConfig(context.getResources());
        reloadAssets();

        // Setup the Camera shutter sound
        mCameraSound = loadCameraSound();

        mCopyBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ClipboardOverlayController.COPY_OVERLAY_ACTION.equals(intent.getAction())) {
                    dismissScreenshot(SCREENSHOT_DISMISSED_OTHER);
                }
            }
        };
        mContext.registerReceiver(mCopyBroadcastReceiver, new IntentFilter(
                        ClipboardOverlayController.COPY_OVERLAY_ACTION),
                ClipboardOverlayController.SELF_PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
    }

    @MainThread
    void takeScreenshotFullscreen(ComponentName topComponent, Consumer<Uri> finisher,
            RequestCallback requestCallback) {
        Assert.isMainThread();
        mCurrentRequestCallback = requestCallback;
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getDefaultDisplay().getRealMetrics(displayMetrics);
        takeScreenshotInternal(
                topComponent, finisher,
                new Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels));
    }

    @MainThread
    void handleImageAsScreenshot(Bitmap screenshot, Rect screenshotScreenBounds,
            Insets visibleInsets, int taskId, int userId, ComponentName topComponent,
            Consumer<Uri> finisher, RequestCallback requestCallback) {
        Assert.isMainThread();
        if (screenshot == null) {
            Log.e(TAG, "Got null bitmap from screenshot message");
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
            requestCallback.reportError();
            return;
        }

        boolean showFlash = false;
        if (!aspectRatiosMatch(screenshot, visibleInsets, screenshotScreenBounds)) {
            showFlash = true;
            visibleInsets = Insets.NONE;
            screenshotScreenBounds.set(0, 0, screenshot.getWidth(), screenshot.getHeight());
        }
        mCurrentRequestCallback = requestCallback;
        saveScreenshot(screenshot, finisher, screenshotScreenBounds, visibleInsets, topComponent,
                showFlash, UserHandle.of(userId));
    }

    /**
     * Clears current screenshot
     */
    void dismissScreenshot(ScreenshotEvent event) {
        if (DEBUG_DISMISS) {
            Log.d(TAG, "dismissScreenshot");
        }
        // If we're already animating out, don't restart the animation
        if (mScreenshotView.isDismissing()) {
            if (DEBUG_DISMISS) {
                Log.v(TAG, "Already dismissing, ignoring duplicate command");
            }
            return;
        }
        mUiEventLogger.log(event, 0, mPackageName);
        mScreenshotHandler.cancelTimeout();
        mScreenshotView.animateDismissal();
    }

    boolean isPendingSharedTransition() {
        return mScreenshotView.isPendingSharedTransition();
    }

    // Any cleanup needed when the service is being destroyed.
    void onDestroy() {
        if (mSaveInBgTask != null) {
            // just log success/failure for the pre-existing screenshot
            mSaveInBgTask.setActionsReadyListener(this::logSuccessOnActionsReady);
        }
        removeWindow();
        releaseMediaPlayer();
        releaseContext();
        mBgExecutor.shutdownNow();
    }

    /**
     * Release the constructed window context.
     */
    private void releaseContext() {
        mContext.unregisterReceiver(mCopyBroadcastReceiver);
        mContext.release();
    }

    private void releaseMediaPlayer() {
        // Note that this may block if the sound is still being loaded (very unlikely) but we can't
        // reliably release in the background because the service is being destroyed.
        try {
            MediaPlayer player = mCameraSound.get();
            if (player != null) {
                player.release();
            }
        } catch (InterruptedException | ExecutionException e) {
        }
    }

    private void respondToBack() {
        dismissScreenshot(SCREENSHOT_DISMISSED_OTHER);
    }

    /**
     * Update resources on configuration change. Reinflate for theme/color changes.
     */
    private void reloadAssets() {
        if (DEBUG_UI) {
            Log.d(TAG, "reloadAssets()");
        }

        // Inflate the screenshot layout
        mScreenshotView = (ScreenshotView)
                LayoutInflater.from(mContext).inflate(R.layout.screenshot, null);
        mScreenshotView.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(@NonNull View v) {
                        if (DEBUG_INPUT) {
                            Log.d(TAG, "Registering Predictive Back callback");
                        }
                        mScreenshotView.findOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mOnBackInvokedCallback);
                    }

                    @Override
                    public void onViewDetachedFromWindow(@NonNull View v) {
                        if (DEBUG_INPUT) {
                            Log.d(TAG, "Unregistering Predictive Back callback");
                        }
                        mScreenshotView.findOnBackInvokedDispatcher()
                                .unregisterOnBackInvokedCallback(mOnBackInvokedCallback);
                    }
                });
        mScreenshotView.init(mUiEventLogger, new ScreenshotView.ScreenshotViewCallback() {
            @Override
            public void onUserInteraction() {
                if (DEBUG_INPUT) {
                    Log.d(TAG, "onUserInteraction");
                }
                mScreenshotHandler.resetTimeout();
            }

            @Override
            public void onDismiss() {
                finishDismiss();
            }

            @Override
            public void onTouchOutside() {
                // TODO(159460485): Remove this when focus is handled properly in the system
                setWindowFocusable(false);
            }
        }, mActionExecutor, mFlags);
        mScreenshotView.setDefaultTimeoutMillis(mScreenshotHandler.getDefaultTimeoutMillis());

        mScreenshotView.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (DEBUG_INPUT) {
                    Log.d(TAG, "onKeyEvent: KeyEvent.KEYCODE_BACK");
                }
                respondToBack();
                return true;
            }
            return false;
        });

        if (DEBUG_WINDOW) {
            Log.d(TAG, "adding OnComputeInternalInsetsListener");
        }
        mScreenshotView.getViewTreeObserver().addOnComputeInternalInsetsListener(mScreenshotView);
    }

    /**
     * Takes a screenshot of the current display and shows an animation.
     */
    private void takeScreenshotInternal(ComponentName topComponent, Consumer<Uri> finisher,
            Rect crop) {
        mScreenshotTakenInPortrait =
                mContext.getResources().getConfiguration().orientation == ORIENTATION_PORTRAIT;

        // copy the input Rect, since SurfaceControl.screenshot can mutate it
        Rect screenRect = new Rect(crop);
        Bitmap screenshot = mImageCapture.captureDisplay(DEFAULT_DISPLAY, crop);

        if (screenshot == null) {
            Log.e(TAG, "takeScreenshotInternal: Screenshot bitmap was null");
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
            if (mCurrentRequestCallback != null) {
                mCurrentRequestCallback.reportError();
            }
            return;
        }

        saveScreenshot(screenshot, finisher, screenRect, Insets.NONE, topComponent, true,
                Process.myUserHandle());

        mBroadcastSender.sendBroadcast(new Intent(ClipboardOverlayController.SCREENSHOT_ACTION),
                ClipboardOverlayController.SELF_PERMISSION);
    }

    private void saveScreenshot(Bitmap screenshot, Consumer<Uri> finisher, Rect screenRect,
            Insets screenInsets, ComponentName topComponent, boolean showFlash, UserHandle owner) {
        withWindowAttached(() -> {
            if (mFlags.isEnabled(SCREENSHOT_WORK_PROFILE_POLICY)
                    && mUserManager.isManagedProfile(owner.getIdentifier())) {
                mScreenshotView.announceForAccessibility(mContext.getResources().getString(
                        R.string.screenshot_saving_work_profile_title));
            } else {
                mScreenshotView.announceForAccessibility(
                        mContext.getResources().getString(R.string.screenshot_saving_title));
            }
        });

        mScreenshotView.reset();

        if (mScreenshotView.isAttachedToWindow()) {
            // if we didn't already dismiss for another reason
            if (!mScreenshotView.isDismissing()) {
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_REENTERED, 0, mPackageName);
            }
            if (DEBUG_WINDOW) {
                Log.d(TAG, "saveScreenshot: screenshotView is already attached, resetting. "
                        + "(dismissing=" + mScreenshotView.isDismissing() + ")");
            }
        }
        mPackageName = topComponent == null ? "" : topComponent.getPackageName();
        mScreenshotView.setPackageName(mPackageName);

        mScreenshotView.updateOrientation(
                mWindowManager.getCurrentWindowMetrics().getWindowInsets());

        mScreenBitmap = screenshot;

        if (!isUserSetupComplete(owner)) {
            Log.w(TAG, "User setup not complete, displaying toast only");
            // User setup isn't complete, so we don't want to show any UI beyond a toast, as editing
            // and sharing shouldn't be exposed to the user.
            saveScreenshotAndToast(owner, finisher);
            return;
        }

        // Optimizations
        mScreenBitmap.setHasAlpha(false);
        mScreenBitmap.prepareToDraw();

        saveScreenshotInWorkerThread(owner, finisher, this::showUiOnActionsReady,
                this::showUiOnQuickShareActionReady);

        // The window is focusable by default
        setWindowFocusable(true);

        // Wait until this window is attached to request because it is
        // the reference used to locate the target window (below).
        withWindowAttached(() -> {
            requestScrollCapture(owner);
            mWindow.peekDecorView().getViewRootImpl().setActivityConfigCallback(
                    new ViewRootImpl.ActivityConfigCallback() {
                        @Override
                        public void onConfigurationChanged(Configuration overrideConfig,
                                int newDisplayId) {
                            if (mConfigChanges.applyNewConfig(mContext.getResources())) {
                                // Hide the scroll chip until we know it's available in this
                                // orientation
                                mScreenshotView.hideScrollChip();
                                // Delay scroll capture eval a bit to allow the underlying activity
                                // to set up in the new orientation.
                                mScreenshotHandler.postDelayed(() -> {
                                    requestScrollCapture(owner);
                                }, 150);
                                mScreenshotView.updateInsets(
                                        mWindowManager.getCurrentWindowMetrics().getWindowInsets());
                                // Screenshot animation calculations won't be valid anymore,
                                // so just end
                                if (mScreenshotAnimation != null
                                        && mScreenshotAnimation.isRunning()) {
                                    mScreenshotAnimation.end();
                                }
                            }
                        }

                        @Override
                        public void requestCompatCameraControl(boolean showControl,
                                boolean transformationApplied,
                                ICompatCameraControlCallback callback) {
                            Log.w(TAG, "Unexpected requestCompatCameraControl callback");
                        }
                    });
        });

        attachWindow();
        mScreenshotView.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (DEBUG_WINDOW) {
                            Log.d(TAG, "onPreDraw: startAnimation");
                        }
                        mScreenshotView.getViewTreeObserver().removeOnPreDrawListener(this);
                        startAnimation(screenRect, showFlash);
                        return true;
                    }
                });

        if (mFlags.isEnabled(SCREENSHOT_WORK_PROFILE_POLICY)) {
            mScreenshotView.badgeScreenshot(
                    mContext.getPackageManager().getUserBadgeForDensity(owner, 0));
        }
        mScreenshotView.setScreenshot(mScreenBitmap, screenInsets);
        if (DEBUG_WINDOW) {
            Log.d(TAG, "setContentView: " + mScreenshotView);
        }
        setContentView(mScreenshotView);
        // ignore system bar insets for the purpose of window layout
        mWindow.getDecorView().setOnApplyWindowInsetsListener(
                (v, insets) -> WindowInsets.CONSUMED);
        mScreenshotHandler.cancelTimeout(); // restarted after animation
    }

    private void requestScrollCapture(UserHandle owner) {
        if (!allowLongScreenshots()) {
            Log.d(TAG, "Long screenshots not supported on this device");
            return;
        }
        mScrollCaptureClient.setHostWindowToken(mWindow.getDecorView().getWindowToken());
        if (mLastScrollCaptureRequest != null) {
            mLastScrollCaptureRequest.cancel(true);
        }
        final ListenableFuture<ScrollCaptureResponse> future =
                mScrollCaptureClient.request(DEFAULT_DISPLAY);
        mLastScrollCaptureRequest = future;
        mLastScrollCaptureRequest.addListener(() ->
                onScrollCaptureResponseReady(future, owner), mMainExecutor);
    }

    private void onScrollCaptureResponseReady(Future<ScrollCaptureResponse> responseFuture,
            UserHandle owner) {
        try {
            if (mLastScrollCaptureResponse != null) {
                mLastScrollCaptureResponse.close();
                mLastScrollCaptureResponse = null;
            }
            if (responseFuture.isCancelled()) {
                return;
            }
            mLastScrollCaptureResponse = responseFuture.get();
            if (!mLastScrollCaptureResponse.isConnected()) {
                // No connection means that the target window wasn't found
                // or that it cannot support scroll capture.
                Log.d(TAG, "ScrollCapture: " + mLastScrollCaptureResponse.getDescription() + " ["
                        + mLastScrollCaptureResponse.getWindowTitle() + "]");
                return;
            }
            Log.d(TAG, "ScrollCapture: connected to window ["
                    + mLastScrollCaptureResponse.getWindowTitle() + "]");

            final ScrollCaptureResponse response = mLastScrollCaptureResponse;
            mScreenshotView.showScrollChip(response.getPackageName(), /* onClick */ () -> {
                DisplayMetrics displayMetrics = new DisplayMetrics();
                getDefaultDisplay().getRealMetrics(displayMetrics);
                Bitmap newScreenshot = mImageCapture.captureDisplay(DEFAULT_DISPLAY,
                        new Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels));

                mScreenshotView.prepareScrollingTransition(response, mScreenBitmap, newScreenshot,
                        mScreenshotTakenInPortrait);
                // delay starting scroll capture to make sure the scrim is up before the app moves
                mScreenshotView.post(() -> runBatchScrollCapture(response, owner));
            });
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "requestScrollCapture failed", e);
        }
    }

    ListenableFuture<ScrollCaptureController.LongScreenshot> mLongScreenshotFuture;

    private void runBatchScrollCapture(ScrollCaptureResponse response, UserHandle owner) {
        // Clear the reference to prevent close() in dismissScreenshot
        mLastScrollCaptureResponse = null;

        if (mLongScreenshotFuture != null) {
            mLongScreenshotFuture.cancel(true);
        }
        mLongScreenshotFuture = mScrollCaptureController.run(response);
        mLongScreenshotFuture.addListener(() -> {
            ScrollCaptureController.LongScreenshot longScreenshot;
            try {
                longScreenshot = mLongScreenshotFuture.get();
            } catch (CancellationException e) {
                Log.e(TAG, "Long screenshot cancelled");
                return;
            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, "Exception", e);
                mScreenshotView.restoreNonScrollingUi();
                return;
            }

            if (longScreenshot.getHeight() == 0) {
                mScreenshotView.restoreNonScrollingUi();
                return;
            }

            mLongScreenshotHolder.setLongScreenshot(longScreenshot);
            mLongScreenshotHolder.setTransitionDestinationCallback(
                    (transitionDestination, onTransitionEnd) -> {
                            mScreenshotView.startLongScreenshotTransition(
                                    transitionDestination, onTransitionEnd,
                                    longScreenshot);
                        // TODO: Do this via ActionIntentExecutor instead.
                        mContext.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
                    }
            );

            final Intent intent = new Intent(mContext, LongScreenshotActivity.class);
            intent.putExtra(LongScreenshotActivity.EXTRA_SCREENSHOT_USER_HANDLE,
                    owner);
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            mContext.startActivity(intent,
                    ActivityOptions.makeCustomAnimation(mContext, 0, 0).toBundle());
            RemoteAnimationAdapter runner = new RemoteAnimationAdapter(
                    SCREENSHOT_REMOTE_RUNNER, 0, 0);
            try {
                WindowManagerGlobal.getWindowManagerService()
                        .overridePendingAppTransitionRemote(runner, DEFAULT_DISPLAY);
            } catch (Exception e) {
                Log.e(TAG, "Error overriding screenshot app transition", e);
            }
        }, mMainExecutor);
    }

    private void withWindowAttached(Runnable action) {
        View decorView = mWindow.getDecorView();
        if (decorView.isAttachedToWindow()) {
            action.run();
        } else {
            decorView.getViewTreeObserver().addOnWindowAttachListener(
                    new ViewTreeObserver.OnWindowAttachListener() {
                        @Override
                        public void onWindowAttached() {
                            mBlockAttach = false;
                            decorView.getViewTreeObserver().removeOnWindowAttachListener(this);
                            action.run();
                        }

                        @Override
                        public void onWindowDetached() {
                        }
                    });

        }
    }

    private void setContentView(View contentView) {
        mWindow.setContentView(contentView);
    }

    @MainThread
    private void attachWindow() {
        View decorView = mWindow.getDecorView();
        if (decorView.isAttachedToWindow() || mBlockAttach) {
            return;
        }
        if (DEBUG_WINDOW) {
            Log.d(TAG, "attachWindow");
        }
        mBlockAttach = true;
        mWindowManager.addView(decorView, mWindowLayoutParams);
        decorView.requestApplyInsets();
    }

    void removeWindow() {
        final View decorView = mWindow.peekDecorView();
        if (decorView != null && decorView.isAttachedToWindow()) {
            if (DEBUG_WINDOW) {
                Log.d(TAG, "Removing screenshot window");
            }
            mWindowManager.removeViewImmediate(decorView);
        }
        // Ensure that we remove the input monitor
        if (mScreenshotView != null) {
            mScreenshotView.stopInputListening();
        }
    }

    private ListenableFuture<MediaPlayer> loadCameraSound() {
        // The media player creation is slow and needs on the background thread.
        return CallbackToFutureAdapter.getFuture((completer) -> {
            mBgExecutor.execute(() -> {
                try {
                    MediaPlayer player = MediaPlayer.create(mContext,
                            Uri.fromFile(new File(mContext.getResources().getString(
                                    com.android.internal.R.string.config_cameraShutterSound))),
                            null,
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build(), AudioSystem.newAudioSessionId());
                    completer.set(player);
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Screenshot sound initialization failed", e);
                    completer.set(null);
                }
            });
            return "ScreenshotController#loadCameraSound";
        });
    }

    private void playCameraSound() {
        mCameraSound.addListener(() -> {
            try {
                MediaPlayer player = mCameraSound.get();
                if (player != null) {
                    player.start();
                }
            } catch (InterruptedException | ExecutionException e) {
            }
        }, mBgExecutor);
    }

    /**
     * Save the bitmap but don't show the normal screenshot UI.. just a toast (or notification on
     * failure).
     */
    private void saveScreenshotAndToast(UserHandle owner, Consumer<Uri> finisher) {
        // Play the shutter sound to notify that we've taken a screenshot
        playCameraSound();

        saveScreenshotInWorkerThread(
                owner,
                /* onComplete */ finisher,
                /* actionsReadyListener */ imageData -> {
                    if (DEBUG_CALLBACK) {
                        Log.d(TAG, "returning URI to finisher (Consumer<URI>): " + imageData.uri);
                    }
                    finisher.accept(imageData.uri);
                    if (imageData.uri == null) {
                        mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED, 0, mPackageName);
                        mNotificationsController.notifyScreenshotError(
                                R.string.screenshot_failed_to_save_text);
                    } else {
                        mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED, 0, mPackageName);
                        mScreenshotHandler.post(() -> Toast.makeText(mContext,
                                R.string.screenshot_saved_title, Toast.LENGTH_SHORT).show());
                    }
                },
                null);
    }

    /**
     * Starts the animation after taking the screenshot
     */
    private void startAnimation(Rect screenRect, boolean showFlash) {
        if (mScreenshotAnimation != null && mScreenshotAnimation.isRunning()) {
            mScreenshotAnimation.cancel();
        }

        mScreenshotAnimation =
                mScreenshotView.createScreenshotDropInAnimation(screenRect, showFlash);

        // Play the shutter sound to notify that we've taken a screenshot
        playCameraSound();

        if (DEBUG_ANIM) {
            Log.d(TAG, "starting post-screenshot animation");
        }
        mScreenshotAnimation.start();
    }

    /** Reset screenshot view and then call onCompleteRunnable */
    private void finishDismiss() {
        if (DEBUG_DISMISS) {
            Log.d(TAG, "finishDismiss");
        }
        if (mLastScrollCaptureRequest != null) {
            mLastScrollCaptureRequest.cancel(true);
            mLastScrollCaptureRequest = null;
        }
        if (mLastScrollCaptureResponse != null) {
            mLastScrollCaptureResponse.close();
            mLastScrollCaptureResponse = null;
        }
        if (mLongScreenshotFuture != null) {
            mLongScreenshotFuture.cancel(true);
        }
        if (mCurrentRequestCallback != null) {
            mCurrentRequestCallback.onFinish();
            mCurrentRequestCallback = null;
        }
        mScreenshotView.reset();
        removeWindow();
        mScreenshotHandler.cancelTimeout();
    }

    /**
     * Creates a new worker thread and saves the screenshot to the media store.
     */
    private void saveScreenshotInWorkerThread(
            UserHandle owner,
            @NonNull Consumer<Uri> finisher,
            @Nullable ActionsReadyListener actionsReadyListener,
            @Nullable QuickShareActionReadyListener
                    quickShareActionsReadyListener) {
        ScreenshotController.SaveImageInBackgroundData
                data = new ScreenshotController.SaveImageInBackgroundData();
        data.image = mScreenBitmap;
        data.finisher = finisher;
        data.mActionsReadyListener = actionsReadyListener;
        data.mQuickShareActionsReadyListener = quickShareActionsReadyListener;
        data.owner = owner;

        if (mSaveInBgTask != null) {
            // just log success/failure for the pre-existing screenshot
            mSaveInBgTask.setActionsReadyListener(this::logSuccessOnActionsReady);
        }

        mSaveInBgTask = new SaveImageInBackgroundTask(mContext, mFlags, mImageExporter,
                mScreenshotSmartActions, data, getActionTransitionSupplier(),
                mScreenshotNotificationSmartActionsProvider);
        mSaveInBgTask.execute();
    }


    /**
     * Sets up the action shade and its entrance animation, once we get the screenshot URI.
     */
    private void showUiOnActionsReady(ScreenshotController.SavedImageData imageData) {
        logSuccessOnActionsReady(imageData);
        if (DEBUG_UI) {
            Log.d(TAG, "Showing UI actions");
        }

        mScreenshotHandler.resetTimeout();

        if (imageData.uri != null) {
            if (!imageData.owner.equals(Process.myUserHandle())) {
                Log.d(TAG, "Screenshot saved to user " + imageData.owner + " as "
                        + imageData.uri);
            }
            mScreenshotHandler.post(() -> {
                if (mScreenshotAnimation != null && mScreenshotAnimation.isRunning()) {
                    mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            doPostAnimation(imageData);
                        }
                    });
                } else {
                    doPostAnimation(imageData);
                }
            });
        }
    }

    private void doPostAnimation(ScreenshotController.SavedImageData imageData) {
        mScreenshotView.setChipIntents(imageData);
        if (mFlags.isEnabled(SCREENSHOT_WORK_PROFILE_POLICY)
                && mUserManager.isManagedProfile(imageData.owner.getIdentifier())) {
            // TODO: Read app from configuration
            mScreenshotView.showWorkProfileMessage("Files");
        }
    }

    /**
     * Sets up the action shade and its entrance animation, once we get the Quick Share action data.
     */
    private void showUiOnQuickShareActionReady(ScreenshotController.QuickShareData quickShareData) {
        if (DEBUG_UI) {
            Log.d(TAG, "Showing UI for Quick Share action");
        }
        if (quickShareData.quickShareAction != null) {
            mScreenshotHandler.post(() -> {
                if (mScreenshotAnimation != null && mScreenshotAnimation.isRunning()) {
                    mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            mScreenshotView.addQuickShareChip(quickShareData.quickShareAction);
                        }
                    });
                } else {
                    mScreenshotView.addQuickShareChip(quickShareData.quickShareAction);
                }
            });
        }
    }

    /**
     * Supplies the necessary bits for the shared element transition to share sheet.
     * Note that once supplied, the action intent to share must be sent immediately after.
     */
    private Supplier<ActionTransition> getActionTransitionSupplier() {
        return () -> {
            Pair<ActivityOptions, ExitTransitionCoordinator> transition =
                    ActivityOptions.startSharedElementAnimation(
                            mWindow, new ScreenshotExitTransitionCallbacksSupplier(true).get(),
                            null, Pair.create(mScreenshotView.getScreenshotPreview(),
                                    ChooserActivity.FIRST_IMAGE_PREVIEW_TRANSITION_NAME));
            transition.second.startExit();

            ActionTransition supply = new ActionTransition();
            supply.bundle = transition.first.toBundle();
            supply.onCancelRunnable = () -> ActivityOptions.stopSharedElementAnimation(mWindow);
            return supply;
        };
    }

    /**
     * Logs success/failure of the screenshot saving task, and shows an error if it failed.
     */
    private void logSuccessOnActionsReady(ScreenshotController.SavedImageData imageData) {
        if (imageData.uri == null) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED, 0, mPackageName);
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_save_text);
        } else {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED, 0, mPackageName);
            if (mFlags.isEnabled(SCREENSHOT_WORK_PROFILE_POLICY)
                    && mUserManager.isManagedProfile(imageData.owner.getIdentifier())) {
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED_TO_WORK_PROFILE, 0,
                        mPackageName);
            }
        }
    }

    private boolean isUserSetupComplete(UserHandle owner) {
        if (mFlags.isEnabled(SCREENSHOT_WORK_PROFILE_POLICY)) {
            return Settings.Secure.getInt(mContext.createContextAsUser(owner, 0)
                    .getContentResolver(), SETTINGS_SECURE_USER_SETUP_COMPLETE, 0) == 1;
        } else {
            return Settings.Secure.getInt(mContext.getContentResolver(),
                    SETTINGS_SECURE_USER_SETUP_COMPLETE, 0) == 1;
        }
    }

    /**
     * Updates the window focusability.  If the window is already showing, then it updates the
     * window immediately, otherwise the layout params will be applied when the window is next
     * shown.
     */
    private void setWindowFocusable(boolean focusable) {
        if (DEBUG_WINDOW) {
            Log.d(TAG, "setWindowFocusable: " + focusable);
        }
        int flags = mWindowLayoutParams.flags;
        if (focusable) {
            mWindowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (mWindowLayoutParams.flags == flags) {
            if (DEBUG_WINDOW) {
                Log.d(TAG, "setWindowFocusable: skipping, already " + focusable);
            }
            return;
        }
        final View decorView = mWindow.peekDecorView();
        if (decorView != null && decorView.isAttachedToWindow()) {
            mWindowManager.updateViewLayout(decorView, mWindowLayoutParams);
        }
    }

    private Display getDefaultDisplay() {
        return mDisplayManager.getDisplay(DEFAULT_DISPLAY);
    }

    private boolean allowLongScreenshots() {
        return !mIsLowRamDevice;
    }

    /** Does the aspect ratio of the bitmap with insets removed match the bounds. */
    private static boolean aspectRatiosMatch(Bitmap bitmap, Insets bitmapInsets,
            Rect screenBounds) {
        int insettedWidth = bitmap.getWidth() - bitmapInsets.left - bitmapInsets.right;
        int insettedHeight = bitmap.getHeight() - bitmapInsets.top - bitmapInsets.bottom;

        if (insettedHeight == 0 || insettedWidth == 0 || bitmap.getWidth() == 0
                || bitmap.getHeight() == 0) {
            if (DEBUG_UI) {
                Log.e(TAG, "Provided bitmap and insets create degenerate region: "
                        + bitmap.getWidth() + "x" + bitmap.getHeight() + " " + bitmapInsets);
            }
            return false;
        }

        float insettedBitmapAspect = ((float) insettedWidth) / insettedHeight;
        float boundsAspect = ((float) screenBounds.width()) / screenBounds.height();

        boolean matchWithinTolerance = Math.abs(insettedBitmapAspect - boundsAspect) < 0.1f;
        if (DEBUG_UI) {
            Log.d(TAG, "aspectRatiosMatch: don't match bitmap: " + insettedBitmapAspect
                    + ", bounds: " + boundsAspect);
        }
        return matchWithinTolerance;
    }

    private class ScreenshotExitTransitionCallbacksSupplier implements
            Supplier<ExitTransitionCallbacks> {
        final boolean mDismissOnHideSharedElements;

        ScreenshotExitTransitionCallbacksSupplier(boolean dismissOnHideSharedElements) {
            mDismissOnHideSharedElements = dismissOnHideSharedElements;
        }

        @Override
        public ExitTransitionCallbacks get() {
            return new ExitTransitionCallbacks() {
                @Override
                public boolean isReturnTransitionAllowed() {
                    return false;
                }

                @Override
                public void hideSharedElements() {
                    if (mDismissOnHideSharedElements) {
                        finishDismiss();
                    }
                }

                @Override
                public void onFinish() {
                }
            };
        }
    }
}
