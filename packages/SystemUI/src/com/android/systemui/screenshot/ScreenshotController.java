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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;

import static com.android.systemui.screenshot.LogConfig.DEBUG_ANIM;
import static com.android.systemui.screenshot.LogConfig.DEBUG_CALLBACK;
import static com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS;
import static com.android.systemui.screenshot.LogConfig.DEBUG_INPUT;
import static com.android.systemui.screenshot.LogConfig.DEBUG_UI;
import static com.android.systemui.screenshot.LogConfig.DEBUG_WINDOW;
import static com.android.systemui.screenshot.LogConfig.logTag;

import static java.util.Objects.requireNonNull;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ExitTransitionCoordinator;
import android.app.ExitTransitionCoordinator.ExitTransitionCallbacks;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.RemoteAnimationTarget;
import android.view.ScrollCaptureResponse;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;
import android.window.WindowContext;

import com.android.internal.app.ChooserActivity;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.policy.PhoneWindow;
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.screenshot.ScreenshotController.SavedImageData.ActionTransition;
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback;

import com.google.common.util.concurrent.ListenableFuture;

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
                public void onAnimationCancelled() {
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


    private static final int MESSAGE_CORNER_TIMEOUT = 2;
    private static final int SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS = 6000;

    // From WizardManagerHelper.java
    private static final String SETTINGS_SECURE_USER_SETUP_COMPLETE = "user_setup_complete";

    private final WindowContext mContext;
    private final ScreenshotNotificationsController mNotificationsController;
    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final UiEventLogger mUiEventLogger;
    private final ImageExporter mImageExporter;
    private final Executor mMainExecutor;
    private final ExecutorService mBgExecutor;

    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mWindowLayoutParams;
    private final AccessibilityManager mAccessibilityManager;
    private final MediaActionSound mCameraSound;
    private final ScrollCaptureClient mScrollCaptureClient;
    private final PhoneWindow mWindow;
    private final DisplayManager mDisplayManager;
    private final ScrollCaptureController mScrollCaptureController;
    private final LongScreenshotHolder mLongScreenshotHolder;

    private ScreenshotView mScreenshotView;
    private Bitmap mScreenBitmap;
    private SaveImageInBackgroundTask mSaveInBgTask;

    private Animator mScreenshotAnimation;
    private RequestCallback mCurrentRequestCallback;

    private final Handler mScreenshotHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_CORNER_TIMEOUT:
                    if (DEBUG_UI) {
                        Log.d(TAG, "Corner timeout hit");
                    }
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_INTERACTION_TIMEOUT);
                    ScreenshotController.this.dismissScreenshot(false);
                    break;
                default:
                    break;
            }
        }
    };

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
            ScreenshotSmartActions screenshotSmartActions,
            ScreenshotNotificationsController screenshotNotificationsController,
            ScrollCaptureClient scrollCaptureClient,
            UiEventLogger uiEventLogger,
            ImageExporter imageExporter,
            @Main Executor mainExecutor,
            ScrollCaptureController scrollCaptureController,
            LongScreenshotHolder longScreenshotHolder) {
        mScreenshotSmartActions = screenshotSmartActions;
        mNotificationsController = screenshotNotificationsController;
        mScrollCaptureClient = scrollCaptureClient;
        mUiEventLogger = uiEventLogger;
        mImageExporter = imageExporter;
        mMainExecutor = mainExecutor;
        mScrollCaptureController = scrollCaptureController;
        mLongScreenshotHolder = longScreenshotHolder;
        mBgExecutor = Executors.newSingleThreadExecutor();

        mDisplayManager = requireNonNull(context.getSystemService(DisplayManager.class));
        final Context displayContext = context.createDisplayContext(getDefaultDisplay());
        mContext = (WindowContext) displayContext.createWindowContext(TYPE_SCREENSHOT, null);
        mWindowManager = mContext.getSystemService(WindowManager.class);

        mAccessibilityManager = AccessibilityManager.getInstance(mContext);

        // Setup the window that we are going to use
        mWindowLayoutParams = new WindowManager.LayoutParams(
                MATCH_PARENT, MATCH_PARENT, /* xpos */ 0, /* ypos */ 0, TYPE_SCREENSHOT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.setTitle("ScreenshotAnimation");
        mWindowLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindowLayoutParams.setFitInsetsTypes(0);
        // This is needed to let touches pass through outside the touchable areas
        mWindowLayoutParams.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

        mWindow = new PhoneWindow(mContext);
        mWindow.setWindowManager(mWindowManager, null, null);
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
        mWindow.setBackgroundDrawableResource(android.R.color.transparent);

        mConfigChanges.applyNewConfig(context.getResources());
        reloadAssets();

        // Setup the Camera shutter sound
        mCameraSound = new MediaActionSound();
        mCameraSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    void takeScreenshotFullscreen(Consumer<Uri> finisher, RequestCallback requestCallback) {
        mCurrentRequestCallback = requestCallback;
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getDefaultDisplay().getRealMetrics(displayMetrics);
        takeScreenshotInternal(
                finisher,
                new Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels));
    }

    void handleImageAsScreenshot(Bitmap screenshot, Rect screenshotScreenBounds,
            Insets visibleInsets, int taskId, int userId, ComponentName topComponent,
            Consumer<Uri> finisher, RequestCallback requestCallback) {
        // TODO: use task Id, userId, topComponent for smart handler

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
        saveScreenshot(screenshot, finisher, screenshotScreenBounds, visibleInsets, showFlash);
    }

    /**
     * Displays a screenshot selector
     */
    void takeScreenshotPartial(final Consumer<Uri> finisher, RequestCallback requestCallback) {
        mScreenshotView.reset();
        mCurrentRequestCallback = requestCallback;

        attachWindow();
        mWindow.setContentView(mScreenshotView);
        mScreenshotView.requestApplyInsets();

        mScreenshotView.takePartialScreenshot(
                rect -> takeScreenshotInternal(finisher, rect));
    }

    /**
     * Clears current screenshot
     */
    void dismissScreenshot(boolean immediate) {
        if (DEBUG_DISMISS) {
            Log.d(TAG, "dismissScreenshot(immediate=" + immediate + ")");
        }
        // If we're already animating out, don't restart the animation
        // (but do obey an immediate dismissal)
        if (!immediate && mScreenshotView.isDismissing()) {
            if (DEBUG_DISMISS) {
                Log.v(TAG, "Already dismissing, ignoring duplicate command");
            }
            return;
        }
        cancelTimeout();
        if (immediate) {
            finishDismiss();
        } else {
            mScreenshotView.animateDismissal();
        }

        if (mLastScrollCaptureResponse != null) {
            mLastScrollCaptureResponse.close();
            mLastScrollCaptureResponse = null;
        }
    }

    boolean isPendingSharedTransition() {
        return mScreenshotView.isPendingSharedTransition();
    }

    /**
     * Release the constructed window context.
     */
    void releaseContext() {
        mContext.release();
        mCameraSound.release();
        mBgExecutor.shutdownNow();
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
                LayoutInflater.from(mContext).inflate(R.layout.global_screenshot, null);
        mScreenshotView.init(mUiEventLogger, new ScreenshotView.ScreenshotViewCallback() {
            @Override
            public void onUserInteraction() {
                resetTimeout();
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
        });

        mScreenshotView.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (DEBUG_INPUT) {
                    Log.d(TAG, "onKeyEvent: KeyEvent.KEYCODE_BACK");
                }
                dismissScreenshot(false);
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
    private void takeScreenshotInternal(Consumer<Uri> finisher, Rect crop) {
        // copy the input Rect, since SurfaceControl.screenshot can mutate it
        Rect screenRect = new Rect(crop);
        int width = crop.width();
        int height = crop.height();

        Bitmap screenshot = null;
        final Display display = getDefaultDisplay();
        final DisplayAddress address = display.getAddress();
        if (!(address instanceof DisplayAddress.Physical)) {
            Log.e(TAG, "Skipping Screenshot - Default display does not have a physical address: "
                    + display);
        } else {
            final DisplayAddress.Physical physicalAddress = (DisplayAddress.Physical) address;

            final IBinder displayToken = SurfaceControl.getPhysicalDisplayToken(
                    physicalAddress.getPhysicalDisplayId());
            final SurfaceControl.DisplayCaptureArgs captureArgs =
                    new SurfaceControl.DisplayCaptureArgs.Builder(displayToken)
                            .setSourceCrop(crop)
                            .setSize(width, height)
                            .build();
            final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
                    SurfaceControl.captureDisplay(captureArgs);
            screenshot = screenshotBuffer == null ? null : screenshotBuffer.asBitmap();
        }

        if (screenshot == null) {
            Log.e(TAG, "takeScreenshotInternal: Screenshot bitmap was null");
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
            if (mCurrentRequestCallback != null) {
                mCurrentRequestCallback.reportError();
            }
            return;
        }

        saveScreenshot(screenshot, finisher, screenRect, Insets.NONE, true);
    }

    private void saveScreenshot(Bitmap screenshot, Consumer<Uri> finisher, Rect screenRect,
            Insets screenInsets, boolean showFlash) {
        if (mAccessibilityManager.isEnabled()) {
            AccessibilityEvent event =
                    new AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setContentDescription(
                    mContext.getResources().getString(R.string.screenshot_saving_title));
            mAccessibilityManager.sendAccessibilityEvent(event);
        }


        if (mScreenshotView.isAttachedToWindow()) {
            // if we didn't already dismiss for another reason
            if (!mScreenshotView.isDismissing()) {
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_REENTERED);
            }
            if (DEBUG_WINDOW) {
                Log.d(TAG, "saveScreenshot: screenshotView is already attached, resetting. "
                        + "(dismissing=" + mScreenshotView.isDismissing() + ")");
            }
            mScreenshotView.reset();
        }

        mScreenshotView.updateOrientation(mWindowManager.getCurrentWindowMetrics()
                .getWindowInsets().getDisplayCutout());

        mScreenBitmap = screenshot;

        if (!isUserSetupComplete()) {
            Log.w(TAG, "User setup not complete, displaying toast only");
            // User setup isn't complete, so we don't want to show any UI beyond a toast, as editing
            // and sharing shouldn't be exposed to the user.
            saveScreenshotAndToast(finisher);
            return;
        }

        // Optimizations
        mScreenBitmap.setHasAlpha(false);
        mScreenBitmap.prepareToDraw();

        saveScreenshotInWorkerThread(finisher, this::showUiOnActionsReady,
                this::showUiOnQuickShareActionReady);

        // The window is focusable by default
        setWindowFocusable(true);

        // Wait until this window is attached to request because it is
        // the reference used to locate the target window (below).
        withWindowAttached(() -> {
            requestScrollCapture();
            mWindow.peekDecorView().getViewRootImpl().setActivityConfigCallback(
                    (overrideConfig, newDisplayId) -> {
                        if (mConfigChanges.applyNewConfig(mContext.getResources())) {
                            // Hide the scroll chip until we know it's available in this orientation
                            mScreenshotView.hideScrollChip();
                            // Delay scroll capture eval a bit to allow the underlying activity
                            // to set up in the new orientation.
                            mScreenshotHandler.postDelayed(this::requestScrollCapture, 150);
                            mScreenshotView.updateDisplayCutoutMargins(
                                    mWindowManager.getCurrentWindowMetrics().getWindowInsets()
                                            .getDisplayCutout());
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
        mScreenshotView.setScreenshot(mScreenBitmap, screenInsets);
        if (DEBUG_WINDOW) {
            Log.d(TAG, "setContentView: " + mScreenshotView);
        }
        setContentView(mScreenshotView);
        // ignore system bar insets for the purpose of window layout
        mWindow.getDecorView().setOnApplyWindowInsetsListener(
                (v, insets) -> WindowInsets.CONSUMED);
        cancelTimeout(); // restarted after animation
    }

    private void requestScrollCapture() {
        mScrollCaptureClient.setHostWindowToken(mWindow.getDecorView().getWindowToken());
        if (mLastScrollCaptureRequest != null) {
            mLastScrollCaptureRequest.cancel(true);
        }
        mLastScrollCaptureRequest = mScrollCaptureClient.request(DEFAULT_DISPLAY);
        mLastScrollCaptureRequest.addListener(() ->
                onScrollCaptureResponseReady(mLastScrollCaptureRequest), mMainExecutor);
    }

    private void onScrollCaptureResponseReady(Future<ScrollCaptureResponse> responseFuture) {
        try {
            if (mLastScrollCaptureResponse != null) {
                mLastScrollCaptureResponse.close();
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
            mScreenshotView.showScrollChip(/* onClick */ () -> {
                mScreenshotView.prepareScrollingTransition(response, mScreenBitmap);
                // Clear the reference to prevent close() in dismissScreenshot
                mLastScrollCaptureResponse = null;
                final ListenableFuture<ScrollCaptureController.LongScreenshot> future =
                        mScrollCaptureController.run(response);
                future.addListener(() -> {
                    ScrollCaptureController.LongScreenshot longScreenshot;
                    try {
                        longScreenshot = future.get();
                    } catch (CancellationException | InterruptedException | ExecutionException e) {
                        Log.e(TAG, "Exception", e);
                        return;
                    }

                    mLongScreenshotHolder.setLongScreenshot(longScreenshot);

                    final Intent intent = new Intent(mContext, LongScreenshotActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                    Pair<ActivityOptions, ExitTransitionCoordinator> transition =
                            ActivityOptions.startSharedElementAnimation(
                                    mWindow, new ScreenshotExitTransitionCallbacks(), null,
                                    Pair.create(mScreenshotView.getScrollablePreview(),
                                            ChooserActivity.FIRST_IMAGE_PREVIEW_TRANSITION_NAME));
                    transition.second.startExit();
                    mContext.startActivity(intent, transition.first.toBundle());
                }, mMainExecutor);
            });
        } catch (CancellationException e) {
            // Ignore
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "requestScrollCapture failed", e);
        }
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

    private void attachWindow() {
        View decorView = mWindow.getDecorView();
        if (decorView.isAttachedToWindow()) {
            return;
        }
        if (DEBUG_WINDOW) {
            Log.d(TAG, "attachWindow");
        }
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
    }

    /**
     * Save the bitmap but don't show the normal screenshot UI.. just a toast (or notification on
     * failure).
     */
    private void saveScreenshotAndToast(Consumer<Uri> finisher) {
        // Play the shutter sound to notify that we've taken a screenshot
        mCameraSound.play(MediaActionSound.SHUTTER_CLICK);

        saveScreenshotInWorkerThread(
                /* onComplete */ finisher,
                /* actionsReadyListener */ imageData -> {
                    if (DEBUG_CALLBACK) {
                        Log.d(TAG, "returning URI to finisher (Consumer<URI>): " + imageData.uri);
                    }
                    finisher.accept(imageData.uri);
                    if (imageData.uri == null) {
                        mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED);
                        mNotificationsController.notifyScreenshotError(
                                R.string.screenshot_failed_to_save_text);
                    } else {
                        mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED);
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
        mCameraSound.play(MediaActionSound.SHUTTER_CLICK);

        if (DEBUG_ANIM) {
            Log.d(TAG, "starting post-screenshot animation");
        }
        mScreenshotAnimation.start();
    }

    /** Reset screenshot view and then call onCompleteRunnable */
    private void finishDismiss() {
        if (DEBUG_UI) {
            Log.d(TAG, "finishDismiss");
        }
        cancelTimeout();
        removeWindow();
        mScreenshotView.reset();
        if (mCurrentRequestCallback != null) {
            mCurrentRequestCallback.onFinish();
            mCurrentRequestCallback = null;
        }
    }

    /**
     * Creates a new worker thread and saves the screenshot to the media store.
     */
    private void saveScreenshotInWorkerThread(Consumer<Uri> finisher,
            @Nullable ScreenshotController.ActionsReadyListener actionsReadyListener,
            @Nullable ScreenshotController.QuickShareActionReadyListener
                    quickShareActionsReadyListener) {
        ScreenshotController.SaveImageInBackgroundData
                data = new ScreenshotController.SaveImageInBackgroundData();
        data.image = mScreenBitmap;
        data.finisher = finisher;
        data.mActionsReadyListener = actionsReadyListener;
        data.mQuickShareActionsReadyListener = quickShareActionsReadyListener;

        if (mSaveInBgTask != null) {
            // just log success/failure for the pre-existing screenshot
            mSaveInBgTask.setActionsReadyListener(this::logSuccessOnActionsReady);
        }

        mSaveInBgTask = new SaveImageInBackgroundTask(mContext, mImageExporter,
                mScreenshotSmartActions, data, getActionTransitionSupplier());
        mSaveInBgTask.execute();
    }

    private void cancelTimeout() {
        mScreenshotHandler.removeMessages(MESSAGE_CORNER_TIMEOUT);
    }

    private void resetTimeout() {
        cancelTimeout();

        AccessibilityManager accessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        long timeoutMs = accessibilityManager.getRecommendedTimeoutMillis(
                SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);

        mScreenshotHandler.sendMessageDelayed(
                mScreenshotHandler.obtainMessage(MESSAGE_CORNER_TIMEOUT),
                timeoutMs);
        if (DEBUG_UI) {
            Log.d(TAG, "dismiss timeout: " + timeoutMs + " ms");
        }

    }

    /**
     * Sets up the action shade and its entrance animation, once we get the screenshot URI.
     */
    private void showUiOnActionsReady(ScreenshotController.SavedImageData imageData) {
        logSuccessOnActionsReady(imageData);
        if (DEBUG_UI) {
            Log.d(TAG, "Showing UI actions");
        }

        resetTimeout();

        if (imageData.uri != null) {
            mScreenshotHandler.post(() -> {
                if (mScreenshotAnimation != null && mScreenshotAnimation.isRunning()) {
                    mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            mScreenshotView.setChipIntents(imageData);
                        }
                    });
                } else {
                    mScreenshotView.setChipIntents(imageData);
                }
            });
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
                            mWindow, new ScreenshotExitTransitionCallbacks(), null,
                            Pair.create(mScreenshotView.getScreenshotPreview(),
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
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED);
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_save_text);
        } else {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED);
        }
    }

    private boolean isUserSetupComplete() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                SETTINGS_SECURE_USER_SETUP_COMPLETE, 0) == 1;
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

    private class ScreenshotExitTransitionCallbacks implements ExitTransitionCallbacks {
        @Override
        public boolean isReturnTransitionAllowed() {
            return false;
        }

        @Override
        public void hideSharedElements() {
            finishDismiss();
        }

        @Override
        public void onFinish() {
        }
    }
}
