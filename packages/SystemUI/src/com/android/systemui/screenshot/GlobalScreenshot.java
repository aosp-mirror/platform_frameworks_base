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

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.provider.DeviceConfig.NAMESPACE_SYSTEMUI;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.SCREENSHOT_SCROLLING_ENABLED;
import static com.android.systemui.statusbar.phone.StatusBar.SYSTEM_DIALOG_REASON_SCREENSHOT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Icon;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/**
 * Class for handling device screen shots
 */
@Singleton
public class GlobalScreenshot implements ViewTreeObserver.OnComputeInternalInsetsListener {

    /**
     * POD used in the AsyncTask which saves an image in the background.
     */
    static class SaveImageInBackgroundData {
        public Bitmap image;
        public Consumer<Uri> finisher;
        public GlobalScreenshot.ActionsReadyListener mActionsReadyListener;
        public int errorMsgResId;
        public boolean createDeleteAction;

        void clearImage() {
            image = null;
        }
    }

    /**
     * Structure returned by the SaveImageInBackgroundTask
     */
    static class SavedImageData {
        public Uri uri;
        public Notification.Action shareAction;
        public Notification.Action editAction;
        public Notification.Action deleteAction;
        public List<Notification.Action> smartActions;

        /**
         * Used to reset the return data on error
         */
        public void reset() {
            uri = null;
            shareAction = null;
            editAction = null;
            deleteAction = null;
            smartActions = null;
        }
    }

    abstract static class ActionsReadyListener {
        abstract void onActionsReady(SavedImageData imageData);
    }

    // These strings are used for communicating the action invoked to
    // ScreenshotNotificationSmartActionsProvider.
    static final String EXTRA_ACTION_TYPE = "android:screenshot_action_type";
    static final String EXTRA_ID = "android:screenshot_id";
    static final String ACTION_TYPE_DELETE = "Delete";
    static final String ACTION_TYPE_SHARE = "Share";
    static final String ACTION_TYPE_EDIT = "Edit";
    static final String EXTRA_SMART_ACTIONS_ENABLED = "android:smart_actions_enabled";
    static final String EXTRA_ACTION_INTENT = "android:screenshot_action_intent";

    static final String SCREENSHOT_URI_ID = "android:screenshot_uri_id";
    static final String EXTRA_CANCEL_NOTIFICATION = "android:screenshot_cancel_notification";
    static final String EXTRA_DISALLOW_ENTER_PIP = "android:screenshot_disallow_enter_pip";

    // From WizardManagerHelper.java
    private static final String SETTINGS_SECURE_USER_SETUP_COMPLETE = "user_setup_complete";

    private static final String TAG = "GlobalScreenshot";

    private static final long SCREENSHOT_FLASH_IN_DURATION_MS = 133;
    private static final long SCREENSHOT_FLASH_OUT_DURATION_MS = 217;
    private static final long SCREENSHOT_TO_CORNER_X_DURATION_MS = 234;
    private static final long SCREENSHOT_TO_CORNER_Y_DURATION_MS = 500;
    private static final long SCREENSHOT_TO_CORNER_SCALE_DURATION_MS = 234;
    private static final long SCREENSHOT_ACTIONS_EXPANSION_DURATION_MS = 400;
    private static final long SCREENSHOT_ACTIONS_ALPHA_DURATION_MS = 100;
    private static final long SCREENSHOT_DISMISS_Y_DURATION_MS = 350;
    private static final long SCREENSHOT_DISMISS_ALPHA_DURATION_MS = 183;
    private static final long SCREENSHOT_DISMISS_ALPHA_OFFSET_MS = 50; // delay before starting fade
    private static final float SCREENSHOT_ACTIONS_START_SCALE_X = .7f;
    private static final float ROUNDED_CORNER_RADIUS = .05f;
    private static final int SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS = 6000;
    private static final int MESSAGE_CORNER_TIMEOUT = 2;

    private final Interpolator mAccelerateInterpolator = new AccelerateInterpolator();

    private final ScreenshotNotificationsController mNotificationsController;
    private final UiEventLogger mUiEventLogger;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mWindowLayoutParams;
    private final Display mDisplay;
    private final DisplayMetrics mDisplayMetrics;

    private View mScreenshotLayout;
    private ScreenshotSelectorView mScreenshotSelectorView;
    private ImageView mScreenshotAnimatedView;
    private ImageView mScreenshotPreview;
    private ImageView mScreenshotFlash;
    private ImageView mActionsContainerBackground;
    private HorizontalScrollView mActionsContainer;
    private LinearLayout mActionsView;
    private ImageView mBackgroundProtection;
    private FrameLayout mDismissButton;

    private Bitmap mScreenBitmap;
    private SaveImageInBackgroundTask mSaveInBgTask;
    private Animator mScreenshotAnimation;
    private Runnable mOnCompleteRunnable;
    private Animator mDismissAnimation;
    private boolean mInDarkMode = false;
    private boolean mDirectionLTR = true;
    private boolean mOrientationPortrait = true;

    private float mScreenshotOffsetXPx;
    private float mScreenshotOffsetYPx;
    private float mCornerSizeX;
    private float mDismissDeltaY;

    private MediaActionSound mCameraSound;

    // standard material ease
    private final Interpolator mFastOutSlowIn;

    private final Handler mScreenshotHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_CORNER_TIMEOUT:
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_INTERACTION_TIMEOUT);
                    GlobalScreenshot.this.dismissScreenshot("timeout", false);
                    mOnCompleteRunnable.run();
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * @param context everything needs a context :(
     */
    @Inject
    public GlobalScreenshot(
            Context context, @Main Resources resources,
            ScreenshotNotificationsController screenshotNotificationsController,
            UiEventLogger uiEventLogger) {
        mContext = context;
        mNotificationsController = screenshotNotificationsController;
        mUiEventLogger = uiEventLogger;

        reloadAssets();
        Configuration config = mContext.getResources().getConfiguration();
        mInDarkMode = config.isNightModeActive();
        mDirectionLTR = config.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR;
        mOrientationPortrait = config.orientation == ORIENTATION_PORTRAIT;

        // Setup the window that we are going to use
        mWindowLayoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
                WindowManager.LayoutParams.TYPE_SCREENSHOT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.setTitle("ScreenshotAnimation");
        mWindowLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindowLayoutParams.setFitInsetsTypes(0 /* types */);
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();
        mDisplayMetrics = new DisplayMetrics();
        mDisplay.getRealMetrics(mDisplayMetrics);

        mScreenshotOffsetXPx = resources.getDimensionPixelSize(R.dimen.screenshot_offset_x);
        mScreenshotOffsetYPx = resources.getDimensionPixelSize(R.dimen.screenshot_offset_y);
        mCornerSizeX = resources.getDimensionPixelSize(R.dimen.global_screenshot_x_scale);
        mDismissDeltaY = resources.getDimensionPixelSize(R.dimen.screenshot_dismissal_height_delta);

        mFastOutSlowIn =
                AnimationUtils.loadInterpolator(mContext, android.R.interpolator.fast_out_slow_in);

        // Setup the Camera shutter sound
        mCameraSound = new MediaActionSound();
        mCameraSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    @Override // ViewTreeObserver.OnComputeInternalInsetsListener
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        Region touchRegion = new Region();

        Rect screenshotRect = new Rect();
        mScreenshotPreview.getBoundsOnScreen(screenshotRect);
        touchRegion.op(screenshotRect, Region.Op.UNION);
        Rect actionsRect = new Rect();
        mActionsContainer.getBoundsOnScreen(actionsRect);
        touchRegion.op(actionsRect, Region.Op.UNION);
        Rect dismissRect = new Rect();
        mDismissButton.getBoundsOnScreen(dismissRect);
        touchRegion.op(dismissRect, Region.Op.UNION);

        inoutInfo.touchableRegion.set(touchRegion);
    }

    private void onConfigChanged(Configuration newConfig) {
        boolean needsUpdate = false;
        // dark mode
        if (newConfig.isNightModeActive()) {
            // Night mode is active, we're using dark theme
            if (!mInDarkMode) {
                mInDarkMode = true;
                needsUpdate = true;
            }
        } else {
            // Night mode is not active, we're using the light theme
            if (mInDarkMode) {
                mInDarkMode = false;
                needsUpdate = true;
            }
        }

        // RTL configuration
        switch (newConfig.getLayoutDirection()) {
            case View.LAYOUT_DIRECTION_LTR:
                if (!mDirectionLTR) {
                    mDirectionLTR = true;
                    needsUpdate = true;
                }
                break;
            case View.LAYOUT_DIRECTION_RTL:
                if (mDirectionLTR) {
                    mDirectionLTR = false;
                    needsUpdate = true;
                }
                break;
        }

        // portrait/landscape orientation
        switch (newConfig.orientation) {
            case ORIENTATION_PORTRAIT:
                if (!mOrientationPortrait) {
                    mOrientationPortrait = true;
                    needsUpdate = true;
                }
                break;
            case ORIENTATION_LANDSCAPE:
                if (mOrientationPortrait) {
                    mOrientationPortrait = false;
                    needsUpdate = true;
                }
                break;
        }

        if (needsUpdate) {
            reloadAssets();
        }
    }

    /**
     * Update assets (called when the dark theme status changes). We only need to update the dismiss
     * button and the actions container background, since the buttons are re-inflated on demand.
     */
    private void reloadAssets() {
        boolean wasAttached = mScreenshotLayout != null && mScreenshotLayout.isAttachedToWindow();
        if (wasAttached) {
            mWindowManager.removeView(mScreenshotLayout);
        }

        // Inflate the screenshot layout
        mScreenshotLayout = LayoutInflater.from(mContext).inflate(R.layout.global_screenshot, null);
        mScreenshotAnimatedView =
                mScreenshotLayout.findViewById(R.id.global_screenshot_animated_view);
        mScreenshotAnimatedView.setClipToOutline(true);
        mScreenshotAnimatedView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(new Rect(0, 0, view.getWidth(), view.getHeight()),
                        ROUNDED_CORNER_RADIUS * view.getWidth());
            }
        });
        mScreenshotPreview = mScreenshotLayout.findViewById(R.id.global_screenshot_preview);
        mScreenshotPreview.setClipToOutline(true);
        mScreenshotPreview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(new Rect(0, 0, view.getWidth(), view.getHeight()),
                        ROUNDED_CORNER_RADIUS * view.getWidth());
            }
        });

        mActionsContainerBackground = mScreenshotLayout.findViewById(
                R.id.global_screenshot_actions_container_background);
        mActionsContainer = mScreenshotLayout.findViewById(
                R.id.global_screenshot_actions_container);
        mActionsView = mScreenshotLayout.findViewById(R.id.global_screenshot_actions);
        mBackgroundProtection = mScreenshotLayout.findViewById(
                R.id.global_screenshot_actions_background);
        mDismissButton = mScreenshotLayout.findViewById(R.id.global_screenshot_dismiss_button);
        mDismissButton.setOnClickListener(view -> {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_EXPLICIT_DISMISSAL);
            dismissScreenshot("dismiss_button", false);
            mOnCompleteRunnable.run();
        });

        mScreenshotFlash = mScreenshotLayout.findViewById(R.id.global_screenshot_flash);
        mScreenshotSelectorView = mScreenshotLayout.findViewById(R.id.global_screenshot_selector);
        mScreenshotLayout.setFocusable(true);
        mScreenshotSelectorView.setFocusable(true);
        mScreenshotSelectorView.setFocusableInTouchMode(true);
        mScreenshotAnimatedView.setPivotX(0);
        mScreenshotAnimatedView.setPivotY(0);
        mActionsContainer.setScrollX(0);

        if (wasAttached) {
            mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        }
    }


    /**
     * Creates a new worker thread and saves the screenshot to the media store.
     */
    private void saveScreenshotInWorkerThread(
            Consumer<Uri> finisher, @Nullable ActionsReadyListener actionsReadyListener) {
        SaveImageInBackgroundData data = new SaveImageInBackgroundData();
        data.image = mScreenBitmap;
        data.finisher = finisher;
        data.mActionsReadyListener = actionsReadyListener;
        data.createDeleteAction = false;

        if (mSaveInBgTask != null) {
            // just log success/failure for the pre-existing screenshot
            mSaveInBgTask.setActionsReadyListener(new ActionsReadyListener() {
                @Override
                void onActionsReady(SavedImageData imageData) {
                    logSuccessOnActionsReady(imageData);
                }
            });
        }

        mSaveInBgTask = new SaveImageInBackgroundTask(mContext, data);
        mSaveInBgTask.execute();
    }

    /**
     * Takes a screenshot of the current display and shows an animation.
     */
    private void takeScreenshot(Consumer<Uri> finisher, Rect crop) {
        int rot = mDisplay.getRotation();
        int width = crop.width();
        int height = crop.height();

        Rect screenRect = new Rect(0, 0, mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels);

        takeScreenshot(SurfaceControl.screenshot(crop, width, height, rot), finisher, screenRect);
    }

    private void takeScreenshot(Bitmap screenshot, Consumer<Uri> finisher, Rect screenRect) {
        dismissScreenshot("new screenshot requested", true);

        mScreenBitmap = screenshot;

        if (mScreenBitmap == null) {
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
            finisher.accept(null);
            mOnCompleteRunnable.run();
            return;
        }

        if (!isUserSetupComplete()) {
            // User setup isn't complete, so we don't want to show any UI beyond a toast, as editing
            // and sharing shouldn't be exposed to the user.
            saveScreenshotAndToast(finisher);
            return;
        }

        // Optimizations
        mScreenBitmap.setHasAlpha(false);
        mScreenBitmap.prepareToDraw();

        onConfigChanged(mContext.getResources().getConfiguration());


        if (mDismissAnimation != null && mDismissAnimation.isRunning()) {
            mDismissAnimation.cancel();
        }
        // Start the post-screenshot animation
        startAnimation(finisher, screenRect.width(), screenRect.height(), screenRect);
    }

    void takeScreenshot(Consumer<Uri> finisher, Runnable onComplete) {
        mOnCompleteRunnable = onComplete;

        mDisplay.getRealMetrics(mDisplayMetrics);
        takeScreenshot(
                finisher,
                new Rect(0, 0, mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
    }

    void handleImageAsScreenshot(Bitmap screenshot, Rect screenshotScreenBounds,
            Insets visibleInsets, int taskId, Consumer<Uri> finisher, Runnable onComplete) {
        // TODO use taskId and visibleInsets
        mOnCompleteRunnable = onComplete;
        takeScreenshot(screenshot, finisher, screenshotScreenBounds);
    }

    /**
     * Displays a screenshot selector
     */
    @SuppressLint("ClickableViewAccessibility")
    void takeScreenshotPartial(final Consumer<Uri> finisher, Runnable onComplete) {
        dismissScreenshot("new screenshot requested", true);
        mOnCompleteRunnable = onComplete;

        mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        mScreenshotSelectorView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                ScreenshotSelectorView view = (ScreenshotSelectorView) v;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        view.startSelection((int) event.getX(), (int) event.getY());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        view.updateSelection((int) event.getX(), (int) event.getY());
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.setVisibility(View.GONE);
                        mWindowManager.removeView(mScreenshotLayout);
                        final Rect rect = view.getSelectionRect();
                        if (rect != null) {
                            if (rect.width() != 0 && rect.height() != 0) {
                                // Need mScreenshotLayout to handle it after the view disappears
                                mScreenshotLayout.post(() -> takeScreenshot(finisher, rect));
                            }
                        }

                        view.stopSelection();
                        return true;
                }

                return false;
            }
        });
        mScreenshotLayout.post(() -> {
            mScreenshotSelectorView.setVisibility(View.VISIBLE);
            mScreenshotSelectorView.requestFocus();
        });
    }

    /**
     * Cancels screenshot request
     */
    void stopScreenshot() {
        // If the selector layer still presents on screen, we remove it and resets its state.
        if (mScreenshotSelectorView.getSelectionRect() != null) {
            mWindowManager.removeView(mScreenshotLayout);
            mScreenshotSelectorView.stopSelection();
        }
    }

    /**
     * Save the bitmap but don't show the normal screenshot UI.. just a toast (or notification on
     * failure).
     */
    private void saveScreenshotAndToast(Consumer<Uri> finisher) {
        // Play the shutter sound to notify that we've taken a screenshot
        mScreenshotHandler.post(() -> {
            mCameraSound.play(MediaActionSound.SHUTTER_CLICK);
        });

        saveScreenshotInWorkerThread(finisher, new ActionsReadyListener() {
            @Override
            void onActionsReady(SavedImageData imageData) {
                finisher.accept(imageData.uri);
                if (imageData.uri == null) {
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED);
                    mNotificationsController.notifyScreenshotError(
                            R.string.screenshot_failed_to_capture_text);
                } else {
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED);

                    mScreenshotHandler.post(() -> {
                        Toast.makeText(mContext, R.string.screenshot_saved_title,
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    private boolean isUserSetupComplete() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                SETTINGS_SECURE_USER_SETUP_COMPLETE, 0) == 1;
    }

    /**
     * Clears current screenshot
     */
    private void dismissScreenshot(String reason, boolean immediate) {
        Log.v(TAG, "clearing screenshot: " + reason);
        mScreenshotHandler.removeMessages(MESSAGE_CORNER_TIMEOUT);
        mScreenshotLayout.getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        if (!immediate) {
            mDismissAnimation = createScreenshotDismissAnimation();
            mDismissAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    clearScreenshot();
                }
            });
            mDismissAnimation.start();
        } else {
            clearScreenshot();
        }
    }

    private void clearScreenshot() {
        if (mScreenshotLayout.isAttachedToWindow()) {
            mWindowManager.removeView(mScreenshotLayout);
        }

        // Clear any references to the bitmap
        mScreenshotPreview.setImageBitmap(null);
        mScreenshotAnimatedView.setImageBitmap(null);
        mActionsContainerBackground.setVisibility(View.GONE);
        mActionsContainer.setVisibility(View.GONE);
        mBackgroundProtection.setAlpha(0f);
        mDismissButton.setVisibility(View.GONE);
        mScreenshotPreview.setVisibility(View.GONE);
        mScreenshotPreview.setLayerType(View.LAYER_TYPE_NONE, null);
        mScreenshotPreview.setContentDescription(
                mContext.getResources().getString(R.string.screenshot_preview_description));
        mScreenshotLayout.setAlpha(1);
        mDismissButton.setTranslationY(0);
        mActionsContainer.setTranslationY(0);
        mActionsContainerBackground.setTranslationY(0);
        mScreenshotPreview.setTranslationY(0);
    }

    /**
     * Sets up the action shade and its entrance animation, once we get the screenshot URI.
     */
    private void showUiOnActionsReady(SavedImageData imageData) {
        logSuccessOnActionsReady(imageData);
        if (imageData.uri != null) {
            mScreenshotHandler.post(() -> {
                if (mScreenshotAnimation != null && mScreenshotAnimation.isRunning()) {
                    mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            createScreenshotActionsShadeAnimation(imageData).start();
                        }
                    });
                } else {
                    createScreenshotActionsShadeAnimation(imageData).start();
                }

                AccessibilityManager accessibilityManager = (AccessibilityManager)
                        mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
                long timeoutMs = accessibilityManager.getRecommendedTimeoutMillis(
                        SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS,
                        AccessibilityManager.FLAG_CONTENT_CONTROLS);

                mScreenshotHandler.removeMessages(MESSAGE_CORNER_TIMEOUT);
                mScreenshotHandler.sendMessageDelayed(
                        mScreenshotHandler.obtainMessage(MESSAGE_CORNER_TIMEOUT),
                        timeoutMs);
            });
        }
    }

    /**
     * Logs success/failure of the screenshot saving task, and shows an error if it failed.
     */
    private void logSuccessOnActionsReady(SavedImageData imageData) {
        if (imageData.uri == null) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED);
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
        } else {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED);
        }
    }

    /**
     * Starts the animation after taking the screenshot
     */
    private void startAnimation(final Consumer<Uri> finisher, int w, int h,
            @Nullable Rect screenRect) {
        // If power save is on, show a toast so there is some visual indication that a
        // screenshot has been taken.
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        if (powerManager.isPowerSaveMode()) {
            Toast.makeText(mContext, R.string.screenshot_saved_title, Toast.LENGTH_SHORT).show();
        }

        mScreenshotAnimation = createScreenshotDropInAnimation(w, h, screenRect);

        saveScreenshotInWorkerThread(finisher, new ActionsReadyListener() {
                    @Override
                    void onActionsReady(SavedImageData imageData) {
                        showUiOnActionsReady(imageData);
                    }
                });
        mScreenshotHandler.post(() -> {
            if (!mScreenshotLayout.isAttachedToWindow()) {
                mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
            }
            mScreenshotLayout.getViewTreeObserver().addOnComputeInternalInsetsListener(this);

            mScreenshotHandler.post(() -> {

                // Play the shutter sound to notify that we've taken a screenshot
                mCameraSound.play(MediaActionSound.SHUTTER_CLICK);

                mScreenshotPreview.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                mScreenshotPreview.buildLayer();
                mScreenshotAnimation.start();
            });
        });
    }

    private AnimatorSet createScreenshotDropInAnimation(int width, int height, Rect bounds) {
        float screenWidth = mDisplayMetrics.widthPixels;
        float screenHeight = mDisplayMetrics.heightPixels;

        int rotation = mContext.getDisplay().getRotation();
        float cornerScale;
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            cornerScale = (mCornerSizeX /  screenHeight);
        } else {
            cornerScale = (mCornerSizeX / screenWidth);
        }
        float currentScale = width / screenWidth;

        mScreenshotAnimatedView.setScaleX(currentScale);
        mScreenshotAnimatedView.setScaleY(currentScale);

        mScreenshotAnimatedView.setPivotX(0);
        mScreenshotAnimatedView.setPivotY(0);

        mScreenshotAnimatedView.setImageBitmap(mScreenBitmap);
        mScreenshotPreview.setImageBitmap(mScreenBitmap);

        AnimatorSet dropInAnimation = new AnimatorSet();
        ValueAnimator flashInAnimator = ValueAnimator.ofFloat(0, 1);
        flashInAnimator.setDuration(SCREENSHOT_FLASH_IN_DURATION_MS);
        flashInAnimator.setInterpolator(mFastOutSlowIn);
        flashInAnimator.addUpdateListener(animation ->
                mScreenshotFlash.setAlpha((float) animation.getAnimatedValue()));

        ValueAnimator flashOutAnimator = ValueAnimator.ofFloat(1, 0);
        flashOutAnimator.setDuration(SCREENSHOT_FLASH_OUT_DURATION_MS);
        flashOutAnimator.setInterpolator(mFastOutSlowIn);
        flashOutAnimator.addUpdateListener(animation ->
                mScreenshotFlash.setAlpha((float) animation.getAnimatedValue()));

        final PointF startPos = new PointF(bounds.centerX(), bounds.centerY());
        float finalX;
        if (mDirectionLTR) {
            finalX = mScreenshotOffsetXPx + screenWidth * cornerScale / 2f;
        } else {
            finalX = screenWidth - mScreenshotOffsetXPx - screenWidth * cornerScale / 2f;
        }
        float finalY = screenHeight - mScreenshotOffsetYPx - screenHeight * cornerScale / 2f;
        final PointF finalPos = new PointF(finalX, finalY);

        ValueAnimator toCorner = ValueAnimator.ofFloat(0, 1);
        toCorner.setDuration(SCREENSHOT_TO_CORNER_Y_DURATION_MS);
        float xPositionPct =
                SCREENSHOT_TO_CORNER_X_DURATION_MS / (float) SCREENSHOT_TO_CORNER_Y_DURATION_MS;
        float scalePct =
                SCREENSHOT_TO_CORNER_SCALE_DURATION_MS / (float) SCREENSHOT_TO_CORNER_Y_DURATION_MS;
        toCorner.addUpdateListener(animation -> {
            float t = animation.getAnimatedFraction();
            if (t < scalePct) {
                float scale = MathUtils.lerp(
                        currentScale, cornerScale, mFastOutSlowIn.getInterpolation(t / scalePct));
                mScreenshotAnimatedView.setScaleX(scale);
                mScreenshotAnimatedView.setScaleY(scale);
            } else {
                mScreenshotAnimatedView.setScaleX(cornerScale);
                mScreenshotAnimatedView.setScaleY(cornerScale);
            }

            float currentScaleX = mScreenshotAnimatedView.getScaleX();
            float currentScaleY = mScreenshotAnimatedView.getScaleY();

            if (t < xPositionPct) {
                float xCenter = MathUtils.lerp(startPos.x, finalPos.x,
                        mFastOutSlowIn.getInterpolation(t / xPositionPct));
                mScreenshotAnimatedView.setX(xCenter - screenWidth * currentScaleX / 2f);
            } else {
                mScreenshotAnimatedView.setX(finalPos.x - screenWidth * currentScaleX / 2f);
            }
            float yCenter = MathUtils.lerp(startPos.y, finalPos.y,
                    mFastOutSlowIn.getInterpolation(t));
            mScreenshotAnimatedView.setY(yCenter - screenHeight * currentScaleY / 2f);
        });

        toCorner.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                mScreenshotAnimatedView.setVisibility(View.VISIBLE);
            }
        });

        mScreenshotFlash.setAlpha(0f);
        mScreenshotFlash.setVisibility(View.VISIBLE);

        dropInAnimation.play(flashOutAnimator).after(flashInAnimator);
        dropInAnimation.play(flashOutAnimator).with(toCorner);

        dropInAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mScreenshotAnimatedView.setScaleX(1);
                mScreenshotAnimatedView.setScaleY(1);
                mScreenshotAnimatedView.setX(finalPos.x - width * cornerScale / 2f);
                mScreenshotAnimatedView.setY(finalPos.y - height * cornerScale / 2f);
                Rect bounds = new Rect();
                mDismissButton.getBoundsOnScreen(bounds);
                mScreenshotAnimatedView.setVisibility(View.GONE);
                mScreenshotPreview.setVisibility(View.VISIBLE);
                mDismissButton.setVisibility(View.VISIBLE);
                mScreenshotLayout.forceLayout();
            }
        });

        return dropInAnimation;
    }

    private ValueAnimator createScreenshotActionsShadeAnimation(SavedImageData imageData) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        mActionsView.removeAllViews();
        mScreenshotLayout.invalidate();
        mScreenshotLayout.requestLayout();
        mScreenshotLayout.getViewTreeObserver().dispatchOnGlobalLayout();

        // By default the activities won't be able to start immediately; override this to keep
        // the same behavior as if started from a notification
        try {
            ActivityManager.getService().resumeAppSwitches();
        } catch (RemoteException e) {
        }

        ArrayList<ScreenshotActionChip> chips = new ArrayList<>();

        for (Notification.Action smartAction : imageData.smartActions) {
            ScreenshotActionChip actionChip = (ScreenshotActionChip) inflater.inflate(
                    R.layout.global_screenshot_action_chip, mActionsView, false);
            actionChip.setText(smartAction.title);
            actionChip.setIcon(smartAction.getIcon(), false);
            actionChip.setPendingIntent(smartAction.actionIntent,
                    () -> {
                        mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SMART_ACTION_TAPPED);
                        dismissScreenshot("chip tapped", false);
                        mOnCompleteRunnable.run();
                    });
            mActionsView.addView(actionChip);
            chips.add(actionChip);
        }

        ScreenshotActionChip shareChip = (ScreenshotActionChip) inflater.inflate(
                R.layout.global_screenshot_action_chip, mActionsView, false);
        shareChip.setText(imageData.shareAction.title);
        shareChip.setIcon(imageData.shareAction.getIcon(), true);
        shareChip.setPendingIntent(imageData.shareAction.actionIntent, () -> {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SHARE_TAPPED);
            dismissScreenshot("chip tapped", false);
            mOnCompleteRunnable.run();
        });
        mActionsView.addView(shareChip);
        chips.add(shareChip);

        ScreenshotActionChip editChip = (ScreenshotActionChip) inflater.inflate(
                R.layout.global_screenshot_action_chip, mActionsView, false);
        editChip.setText(imageData.editAction.title);
        editChip.setIcon(imageData.editAction.getIcon(), true);
        editChip.setPendingIntent(imageData.editAction.actionIntent, () -> {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_EDIT_TAPPED);
            dismissScreenshot("chip tapped", false);
            mOnCompleteRunnable.run();
        });
        mActionsView.addView(editChip);
        chips.add(editChip);

        mScreenshotPreview.setOnClickListener(v -> {
            try {
                imageData.editAction.actionIntent.send();
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_PREVIEW_TAPPED);
                dismissScreenshot("screenshot preview tapped", false);
                mOnCompleteRunnable.run();
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "Intent cancelled", e);
            }
        });
        mScreenshotPreview.setContentDescription(imageData.editAction.title);

        if (DeviceConfig.getBoolean(NAMESPACE_SYSTEMUI, SCREENSHOT_SCROLLING_ENABLED, false)) {
            ScreenshotActionChip scrollChip = (ScreenshotActionChip) inflater.inflate(
                    R.layout.global_screenshot_action_chip, mActionsView, false);
            Toast scrollNotImplemented = Toast.makeText(
                    mContext, "Not implemented", Toast.LENGTH_SHORT);
            scrollChip.setText("Extend"); // TODO: add resource and translate
            scrollChip.setIcon(
                    Icon.createWithResource(mContext, R.drawable.ic_arrow_downward), true);
            scrollChip.setOnClickListener(v -> {
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SCROLL_TAPPED);
                scrollNotImplemented.show();
            });
            mActionsView.addView(scrollChip);
            chips.add(scrollChip);
        }

        // remove the margin from the last chip so that it's correctly aligned with the end
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)
                mActionsView.getChildAt(mActionsView.getChildCount() - 1).getLayoutParams();
        params.setMarginEnd(0);

        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(SCREENSHOT_ACTIONS_EXPANSION_DURATION_MS);
        float alphaFraction = (float) SCREENSHOT_ACTIONS_ALPHA_DURATION_MS
                / SCREENSHOT_ACTIONS_EXPANSION_DURATION_MS;
        mActionsContainer.setAlpha(0f);
        mActionsContainerBackground.setAlpha(0f);
        mActionsContainer.setVisibility(View.VISIBLE);
        mActionsContainerBackground.setVisibility(View.VISIBLE);

        animator.addUpdateListener(animation -> {
            float t = animation.getAnimatedFraction();
            mBackgroundProtection.setAlpha(t);
            float containerAlpha = t < alphaFraction ? t / alphaFraction : 1;
            mActionsContainer.setAlpha(containerAlpha);
            mActionsContainerBackground.setAlpha(containerAlpha);
            float containerScale = SCREENSHOT_ACTIONS_START_SCALE_X
                    + (t * (1 - SCREENSHOT_ACTIONS_START_SCALE_X));
            mActionsContainer.setScaleX(containerScale);
            mActionsContainerBackground.setScaleX(containerScale);
            for (ScreenshotActionChip chip : chips) {
                chip.setAlpha(t);
                chip.setScaleX(1 / containerScale); // invert to keep size of children constant
            }
            mActionsContainer.setScrollX(mDirectionLTR ? 0 : mActionsContainer.getWidth());
            mActionsContainer.setPivotX(mDirectionLTR ? 0 : mActionsContainer.getWidth());
            mActionsContainerBackground.setPivotX(
                    mDirectionLTR ? 0 : mActionsContainerBackground.getWidth());
        });
        return animator;
    }

    private AnimatorSet createScreenshotDismissAnimation() {
        ValueAnimator alphaAnim = ValueAnimator.ofFloat(0, 1);
        alphaAnim.setStartDelay(SCREENSHOT_DISMISS_ALPHA_OFFSET_MS);
        alphaAnim.setDuration(SCREENSHOT_DISMISS_ALPHA_DURATION_MS);
        alphaAnim.addUpdateListener(animation -> {
            mScreenshotLayout.setAlpha(1 - animation.getAnimatedFraction());
        });

        ValueAnimator yAnim = ValueAnimator.ofFloat(0, 1);
        yAnim.setInterpolator(mAccelerateInterpolator);
        yAnim.setDuration(SCREENSHOT_DISMISS_Y_DURATION_MS);
        float screenshotStartY = mScreenshotPreview.getTranslationY();
        float dismissStartY = mDismissButton.getTranslationY();
        yAnim.addUpdateListener(animation -> {
            float yDelta = MathUtils.lerp(0, mDismissDeltaY, animation.getAnimatedFraction());
            mScreenshotPreview.setTranslationY(screenshotStartY + yDelta);
            mDismissButton.setTranslationY(dismissStartY + yDelta);
            mActionsContainer.setTranslationY(yDelta);
            mActionsContainerBackground.setTranslationY(yDelta);
        });

        AnimatorSet animSet = new AnimatorSet();
        animSet.play(yAnim).with(alphaAnim);

        return animSet;
    }

    /**
     * Receiver to proxy the share or edit intent, used to clean up the notification and send
     * appropriate signals to the system (ie. to dismiss the keyguard if necessary).
     */
    public static class ActionProxyReceiver extends BroadcastReceiver {
        static final int CLOSE_WINDOWS_TIMEOUT_MILLIS = 3000;
        private final StatusBar mStatusBar;

        @Inject
        public ActionProxyReceiver(Optional<Lazy<StatusBar>> statusBarLazy) {
            Lazy<StatusBar> statusBar = statusBarLazy.orElse(null);
            mStatusBar = statusBar != null ? statusBar.get() : null;
        }

        @Override
        public void onReceive(Context context, final Intent intent) {
            Runnable startActivityRunnable = () -> {
                try {
                    ActivityManagerWrapper.getInstance().closeSystemWindows(
                            SYSTEM_DIALOG_REASON_SCREENSHOT).get(
                            CLOSE_WINDOWS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException | InterruptedException | ExecutionException e) {
                    Slog.e(TAG, "Unable to share screenshot", e);
                    return;
                }

                Intent actionIntent = intent.getParcelableExtra(EXTRA_ACTION_INTENT);
                if (intent.getBooleanExtra(EXTRA_CANCEL_NOTIFICATION, false)) {
                    ScreenshotNotificationsController.cancelScreenshotNotification(context);
                }
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setDisallowEnterPictureInPictureWhileLaunching(
                        intent.getBooleanExtra(EXTRA_DISALLOW_ENTER_PIP, false));
                context.startActivityAsUser(actionIntent, opts.toBundle(), UserHandle.CURRENT);
            };

            if (mStatusBar != null) {
                mStatusBar.executeRunnableDismissingKeyguard(startActivityRunnable, null,
                        true /* dismissShade */, true /* afterKeyguardGone */,
                        true /* deferred */);
            } else {
                startActivityRunnable.run();
            }

            if (intent.getBooleanExtra(EXTRA_SMART_ACTIONS_ENABLED, false)) {
                String actionType = Intent.ACTION_EDIT.equals(intent.getAction())
                        ? ACTION_TYPE_EDIT
                        : ACTION_TYPE_SHARE;
                ScreenshotSmartActions.notifyScreenshotAction(
                        context, intent.getStringExtra(EXTRA_ID), actionType, false);
            }
        }
    }

    /**
     * Removes the notification for a screenshot after a share target is chosen.
     */
    public static class TargetChosenReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Clear the notification only after the user has chosen a share action
            ScreenshotNotificationsController.cancelScreenshotNotification(context);
        }
    }

    /**
     * Removes the last screenshot.
     */
    public static class DeleteScreenshotReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.hasExtra(SCREENSHOT_URI_ID)) {
                return;
            }

            // Clear the notification when the image is deleted
            ScreenshotNotificationsController.cancelScreenshotNotification(context);

            // And delete the image from the media store
            final Uri uri = Uri.parse(intent.getStringExtra(SCREENSHOT_URI_ID));
            new DeleteImageInBackgroundTask(context).execute(uri);
            if (intent.getBooleanExtra(EXTRA_SMART_ACTIONS_ENABLED, false)) {
                ScreenshotSmartActions.notifyScreenshotAction(
                        context, intent.getStringExtra(EXTRA_ID), ACTION_TYPE_DELETE, false);
            }
        }
    }

    /**
     * Executes the smart action tapped by the user in the notification.
     */
    public static class SmartActionsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            PendingIntent pendingIntent = intent.getParcelableExtra(EXTRA_ACTION_INTENT);
            Intent actionIntent = pendingIntent.getIntent();
            String actionType = intent.getStringExtra(EXTRA_ACTION_TYPE);
            Slog.d(TAG, "Executing smart action [" + actionType + "]:" + actionIntent);
            ActivityOptions opts = ActivityOptions.makeBasic();
            context.startActivityAsUser(actionIntent, opts.toBundle(), UserHandle.CURRENT);

            ScreenshotSmartActions.notifyScreenshotAction(
                    context, intent.getStringExtra(EXTRA_ID), actionType, true);
        }
    }
}
