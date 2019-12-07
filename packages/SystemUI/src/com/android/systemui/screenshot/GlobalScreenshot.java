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

import static android.content.Context.NOTIFICATION_SERVICE;
import static android.os.AsyncTask.THREAD_POOL_EXECUTOR;
import static android.provider.DeviceConfig.NAMESPACE_SYSTEMUI;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.SCREENSHOT_CORNER_FLOW;
import static com.android.systemui.statusbar.phone.StatusBar.SYSTEM_DIALOG_REASON_SCREENSHOT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.dagger.qualifiers.MainResources;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.util.NotificationChannels;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
public class GlobalScreenshot {

    /**
     * POD used in the AsyncTask which saves an image in the background.
     */
    static class SaveImageInBackgroundData {
        public Context context;
        public Bitmap image;
        public Uri imageUri;
        public Consumer<Uri> finisher;
        public GlobalScreenshot.ActionsReadyListener mActionsReadyListener;
        public int iconSize;
        public int previewWidth;
        public int previewheight;
        public int errorMsgResId;

        void clearImage() {
            image = null;
            imageUri = null;
            iconSize = 0;
        }

        void clearContext() {
            context = null;
        }
    }

    abstract static class ActionsReadyListener {
        abstract void onActionsReady(PendingIntent shareAction, PendingIntent editAction);
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

    private static final String TAG = "GlobalScreenshot";

    private static final int SCREENSHOT_FLASH_TO_PEAK_DURATION = 130;
    private static final int SCREENSHOT_DROP_IN_DURATION = 430;
    private static final int SCREENSHOT_DROP_OUT_DELAY = 500;
    private static final int SCREENSHOT_DROP_OUT_DURATION = 430;
    private static final int SCREENSHOT_DROP_OUT_SCALE_DURATION = 370;
    private static final int SCREENSHOT_FAST_DROP_OUT_DURATION = 320;
    private static final float BACKGROUND_ALPHA = 0.5f;
    private static final float SCREENSHOT_SCALE = 1f;
    private static final float SCREENSHOT_DROP_IN_MIN_SCALE = SCREENSHOT_SCALE * 0.725f;
    private static final float SCREENSHOT_DROP_OUT_MIN_SCALE = SCREENSHOT_SCALE * 0.45f;
    private static final float SCREENSHOT_CORNER_MIN_SCALE = SCREENSHOT_SCALE * 0.2f;
    private static final float SCREENSHOT_FAST_DROP_OUT_MIN_SCALE = SCREENSHOT_SCALE * 0.6f;
    private static final float SCREENSHOT_DROP_OUT_MIN_SCALE_OFFSET = 0f;
    private static final float SCREENSHOT_CORNER_MIN_SCALE_OFFSET = .1f;
    private static final long SCREENSHOT_CORNER_TIMEOUT_MILLIS = 8000;
    private static final int MESSAGE_CORNER_TIMEOUT = 2;
    private final int mPreviewWidth;
    private final int mPreviewHeight;

    private Context mContext;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mWindowLayoutParams;
    private NotificationManager mNotificationManager;
    private Display mDisplay;
    private DisplayMetrics mDisplayMetrics;

    private Bitmap mScreenBitmap;
    private View mScreenshotLayout;
    private ScreenshotSelectorView mScreenshotSelectorView;
    private ImageView mBackgroundView;
    private ImageView mScreenshotView;
    private ImageView mScreenshotFlash;
    private LinearLayout mActionsView;
    private TextView mShareAction;
    private TextView mEditAction;
    private TextView mScrollAction;

    private AnimatorSet mScreenshotAnimation;

    private int mNotificationIconSize;
    private float mBgPadding;
    private float mBgPaddingScale;

    private AsyncTask<Void, Void, Void> mSaveInBgTask;

    private MediaActionSound mCameraSound;

    private final Handler mScreenshotHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_CORNER_TIMEOUT:
                    GlobalScreenshot.this.clearScreenshot();
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
    public GlobalScreenshot(Context context, @MainResources Resources resources,
            LayoutInflater layoutInflater) {
        mContext = context;

        // Inflate the screenshot layout
        mScreenshotLayout = layoutInflater.inflate(R.layout.global_screenshot, null);
        mBackgroundView = mScreenshotLayout.findViewById(R.id.global_screenshot_background);
        mScreenshotView = mScreenshotLayout.findViewById(R.id.global_screenshot);
        mActionsView = mScreenshotLayout.findViewById(R.id.global_screenshot_actions);

        mShareAction = (TextView) layoutInflater.inflate(
                R.layout.global_screenshot_action_chip, mActionsView, false);
        mEditAction = (TextView) layoutInflater.inflate(
                R.layout.global_screenshot_action_chip, mActionsView, false);
        mScrollAction = (TextView) layoutInflater.inflate(
                R.layout.global_screenshot_action_chip, mActionsView, false);

        mShareAction.setText(com.android.internal.R.string.share);
        mEditAction.setText(com.android.internal.R.string.screenshot_edit);
        mScrollAction.setText("Scroll"); // TODO (mkephart): Add to resources and translate

        mActionsView.addView(mShareAction);
        mActionsView.addView(mEditAction);
        mActionsView.addView(mScrollAction);

        mScreenshotFlash = mScreenshotLayout.findViewById(R.id.global_screenshot_flash);
        mScreenshotSelectorView = mScreenshotLayout.findViewById(R.id.global_screenshot_selector);
        mScreenshotLayout.setFocusable(true);
        mScreenshotSelectorView.setFocusable(true);
        mScreenshotSelectorView.setFocusableInTouchMode(true);
        mScreenshotLayout.setOnTouchListener((v, event) -> {
            // Intercept and ignore all touch events
            return true;
        });

        // Setup the window that we are going to use
        mWindowLayoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
                WindowManager.LayoutParams.TYPE_SCREENSHOT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.setTitle("ScreenshotAnimation");
        mWindowLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mNotificationManager =
                (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();
        mDisplayMetrics = new DisplayMetrics();
        mDisplay.getRealMetrics(mDisplayMetrics);

        // Get the various target sizes
        mNotificationIconSize =
                resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height);

        // Scale has to account for both sides of the bg
        mBgPadding = (float) resources.getDimensionPixelSize(R.dimen.global_screenshot_bg_padding);
        mBgPaddingScale = mBgPadding / mDisplayMetrics.widthPixels;

        // determine the optimal preview size
        int panelWidth = 0;
        try {
            panelWidth = resources.getDimensionPixelSize(R.dimen.notification_panel_width);
        } catch (Resources.NotFoundException e) {
        }
        if (panelWidth <= 0) {
            // includes notification_panel_width==match_parent (-1)
            panelWidth = mDisplayMetrics.widthPixels;
        }
        mPreviewWidth = panelWidth;
        mPreviewHeight = resources.getDimensionPixelSize(R.dimen.notification_max_height);

        // Setup the Camera shutter sound
        mCameraSound = new MediaActionSound();
        mCameraSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    /**
     * Creates a new worker thread and saves the screenshot to the media store.
     */
    private void saveScreenshotInWorkerThread(
            Consumer<Uri> finisher, @Nullable ActionsReadyListener actionsReadyListener) {
        SaveImageInBackgroundData data = new SaveImageInBackgroundData();
        data.context = mContext;
        data.image = mScreenBitmap;
        data.iconSize = mNotificationIconSize;
        data.finisher = finisher;
        data.mActionsReadyListener = actionsReadyListener;
        data.previewWidth = mPreviewWidth;
        data.previewheight = mPreviewHeight;
        if (mSaveInBgTask != null) {
            mSaveInBgTask.cancel(false);
        }
        mSaveInBgTask = new SaveImageInBackgroundTask(mContext, data, mNotificationManager)
                .execute();
    }

    private void saveScreenshotInWorkerThread(Consumer<Uri> finisher) {
        saveScreenshotInWorkerThread(finisher, null);
    }

    /**
     * Takes a screenshot of the current display and shows an animation.
     */
    private void takeScreenshot(Consumer<Uri> finisher, boolean statusBarVisible,
            boolean navBarVisible, Rect crop) {
        int rot = mDisplay.getRotation();
        int width = crop.width();
        int height = crop.height();

        // Take the screenshot
        mScreenBitmap = SurfaceControl.screenshot(crop, width, height, rot);
        if (mScreenBitmap == null) {
            notifyScreenshotError(mContext, mNotificationManager,
                    R.string.screenshot_failed_to_capture_text);
            finisher.accept(null);
            return;
        }

        // Optimizations
        mScreenBitmap.setHasAlpha(false);
        mScreenBitmap.prepareToDraw();

        // Start the post-screenshot animation
        startAnimation(finisher, mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels,
                statusBarVisible, navBarVisible);
    }

    void takeScreenshot(Consumer<Uri> finisher, boolean statusBarVisible, boolean navBarVisible) {
        mDisplay.getRealMetrics(mDisplayMetrics);
        takeScreenshot(finisher, statusBarVisible, navBarVisible,
                new Rect(0, 0, mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
    }

    /**
     * Displays a screenshot selector
     */
    void takeScreenshotPartial(final Consumer<Uri> finisher, final boolean statusBarVisible,
            final boolean navBarVisible) {
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
                                mScreenshotLayout.post(new Runnable() {
                                    public void run() {
                                        takeScreenshot(finisher, statusBarVisible, navBarVisible,
                                                rect);
                                    }
                                });
                            }
                        }

                        view.stopSelection();
                        return true;
                }

                return false;
            }
        });
        mScreenshotLayout.post(new Runnable() {
            @Override
            public void run() {
                mScreenshotSelectorView.setVisibility(View.VISIBLE);
                mScreenshotSelectorView.requestFocus();
            }
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
     * Clears current screenshot
     */
    private void clearScreenshot() {
        if (mScreenshotLayout.isAttachedToWindow()) {
            mWindowManager.removeView(mScreenshotLayout);
        }

        // Clear any references to the bitmap
        mScreenBitmap = null;
        mScreenshotView.setImageBitmap(null);
        mActionsView.setVisibility(View.GONE);
        mBackgroundView.setVisibility(View.GONE);
        mScreenshotView.setVisibility(View.GONE);
        mScreenshotView.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    /**
     * Starts the animation after taking the screenshot
     */
    private void startAnimation(final Consumer<Uri> finisher, int w, int h,
            boolean statusBarVisible, boolean navBarVisible) {
        // If power save is on, show a toast so there is some visual indication that a screenshot
        // has been taken.
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        if (powerManager.isPowerSaveMode()) {
            Toast.makeText(mContext, R.string.screenshot_saved_title, Toast.LENGTH_SHORT).show();
        }

        // Add the view for the animation
        mScreenshotView.setImageBitmap(mScreenBitmap);
        mScreenshotLayout.requestFocus();

        // Setup the animation with the screenshot just taken
        if (mScreenshotAnimation != null) {
            if (mScreenshotAnimation.isStarted()) {
                mScreenshotAnimation.end();
            }
            mScreenshotAnimation.removeAllListeners();
        }

        boolean useCornerFlow =
                DeviceConfig.getBoolean(NAMESPACE_SYSTEMUI, SCREENSHOT_CORNER_FLOW, false);
        mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        ValueAnimator screenshotDropInAnim = createScreenshotDropInAnimation();
        ValueAnimator screenshotFadeOutAnim = useCornerFlow
                ? createScreenshotToCornerAnimation(w, h)
                : createScreenshotDropOutAnimation(w, h, statusBarVisible, navBarVisible);
        mScreenshotAnimation = new AnimatorSet();
        mScreenshotAnimation.playSequentially(screenshotDropInAnim, screenshotFadeOutAnim);
        mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Save the screenshot once we have a bit of time now
                if (!useCornerFlow) {
                    saveScreenshotInWorkerThread(finisher);
                    clearScreenshot();
                } else {
                    saveScreenshotInWorkerThread(finisher, new ActionsReadyListener() {
                        @Override
                        void onActionsReady(PendingIntent shareAction, PendingIntent editAction) {
                            mScreenshotHandler.post(() ->
                                    createScreenshotActionsShadeAnimation(shareAction, editAction)
                                            .start());
                        }
                    });
                    mScreenshotHandler.sendMessageDelayed(
                            mScreenshotHandler.obtainMessage(MESSAGE_CORNER_TIMEOUT),
                            SCREENSHOT_CORNER_TIMEOUT_MILLIS);
                }
            }
        });
        mScreenshotHandler.post(() -> {
            // Play the shutter sound to notify that we've taken a screenshot
            mCameraSound.play(MediaActionSound.SHUTTER_CLICK);

            mScreenshotView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            mScreenshotView.buildLayer();
            mScreenshotAnimation.start();
        });
    }

    private ValueAnimator createScreenshotDropInAnimation() {
        final float flashPeakDurationPct = ((float) (SCREENSHOT_FLASH_TO_PEAK_DURATION)
                / SCREENSHOT_DROP_IN_DURATION);
        final float flashDurationPct = 2f * flashPeakDurationPct;
        final Interpolator flashAlphaInterpolator = new Interpolator() {
            @Override
            public float getInterpolation(float x) {
                // Flash the flash view in and out quickly
                if (x <= flashDurationPct) {
                    return (float) Math.sin(Math.PI * (x / flashDurationPct));
                }
                return 0;
            }
        };
        final Interpolator scaleInterpolator = new Interpolator() {
            @Override
            public float getInterpolation(float x) {
                // We start scaling when the flash is at it's peak
                if (x < flashPeakDurationPct) {
                    return 0;
                }
                return (x - flashDurationPct) / (1f - flashDurationPct);
            }
        };

        Resources r = mContext.getResources();
        if ((r.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES) {
            mScreenshotView.getBackground().setTint(Color.BLACK);
        } else {
            mScreenshotView.getBackground().setTintList(null);
        }

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(SCREENSHOT_DROP_IN_DURATION);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mBackgroundView.setAlpha(0f);
                mBackgroundView.setVisibility(View.VISIBLE);
                mScreenshotView.setAlpha(0f);
                mScreenshotView.setTranslationX(0f);
                mScreenshotView.setTranslationY(0f);
                mScreenshotView.setScaleX(SCREENSHOT_SCALE + mBgPaddingScale);
                mScreenshotView.setScaleY(SCREENSHOT_SCALE + mBgPaddingScale);
                mScreenshotView.setVisibility(View.VISIBLE);
                mScreenshotFlash.setAlpha(0f);
                mScreenshotFlash.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                mScreenshotFlash.setVisibility(View.GONE);
            }
        });
        anim.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                float scaleT = (SCREENSHOT_SCALE + mBgPaddingScale)
                        - scaleInterpolator.getInterpolation(t)
                        * (SCREENSHOT_SCALE - SCREENSHOT_DROP_IN_MIN_SCALE);
                mBackgroundView.setAlpha(scaleInterpolator.getInterpolation(t) * BACKGROUND_ALPHA);
                mScreenshotView.setAlpha(t);
                mScreenshotView.setScaleX(scaleT);
                mScreenshotView.setScaleY(scaleT);
                mScreenshotFlash.setAlpha(flashAlphaInterpolator.getInterpolation(t));
            }
        });
        return anim;
    }

    private ValueAnimator createScreenshotDropOutAnimation(int w, int h, boolean statusBarVisible,
            boolean navBarVisible) {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setStartDelay(SCREENSHOT_DROP_OUT_DELAY);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBackgroundView.setVisibility(View.GONE);
                mScreenshotView.setVisibility(View.GONE);
                mScreenshotView.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });

        if (!statusBarVisible || !navBarVisible) {
            // There is no status bar/nav bar, so just fade the screenshot away in place
            anim.setDuration(SCREENSHOT_FAST_DROP_OUT_DURATION);
            anim.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float t = (Float) animation.getAnimatedValue();
                    float scaleT = (SCREENSHOT_DROP_IN_MIN_SCALE + mBgPaddingScale)
                            - t * (SCREENSHOT_DROP_IN_MIN_SCALE
                            - SCREENSHOT_FAST_DROP_OUT_MIN_SCALE);
                    mBackgroundView.setAlpha((1f - t) * BACKGROUND_ALPHA);
                    mScreenshotView.setAlpha(1f - t);
                    mScreenshotView.setScaleX(scaleT);
                    mScreenshotView.setScaleY(scaleT);
                }
            });
        } else {
            // In the case where there is a status bar, animate to the origin of the bar (top-left)
            final float scaleDurationPct = (float) SCREENSHOT_DROP_OUT_SCALE_DURATION
                    / SCREENSHOT_DROP_OUT_DURATION;
            final Interpolator scaleInterpolator = new Interpolator() {
                @Override
                public float getInterpolation(float x) {
                    if (x < scaleDurationPct) {
                        // Decelerate, and scale the input accordingly
                        return (float) (1f - Math.pow(1f - (x / scaleDurationPct), 2f));
                    }
                    return 1f;
                }
            };

            // Determine the bounds of how to scale
            float halfScreenWidth = (w - 2f * mBgPadding) / 2f;
            float halfScreenHeight = (h - 2f * mBgPadding) / 2f;
            final float offsetPct = SCREENSHOT_DROP_OUT_MIN_SCALE_OFFSET;
            final PointF finalPos = new PointF(
                    -halfScreenWidth
                            + (SCREENSHOT_DROP_OUT_MIN_SCALE + offsetPct) * halfScreenWidth,
                    -halfScreenHeight
                            + (SCREENSHOT_DROP_OUT_MIN_SCALE + offsetPct) * halfScreenHeight);

            // Animate the screenshot to the status bar
            anim.setDuration(SCREENSHOT_DROP_OUT_DURATION);
            anim.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float t = (Float) animation.getAnimatedValue();
                    float scaleT = (SCREENSHOT_DROP_IN_MIN_SCALE + mBgPaddingScale)
                            - scaleInterpolator.getInterpolation(t)
                            * (SCREENSHOT_DROP_IN_MIN_SCALE - SCREENSHOT_DROP_OUT_MIN_SCALE);
                    mBackgroundView.setAlpha((1f - t) * BACKGROUND_ALPHA);
                    mScreenshotView.setAlpha(1f - scaleInterpolator.getInterpolation(t));
                    mScreenshotView.setScaleX(scaleT);
                    mScreenshotView.setScaleY(scaleT);
                    mScreenshotView.setTranslationX(t * finalPos.x);
                    mScreenshotView.setTranslationY(t * finalPos.y);
                }
            });
        }
        return anim;
    }

    private ValueAnimator createScreenshotToCornerAnimation(int w, int h) {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setStartDelay(SCREENSHOT_DROP_OUT_DELAY);

        final float scaleDurationPct =
                (float) SCREENSHOT_DROP_OUT_SCALE_DURATION / SCREENSHOT_DROP_OUT_DURATION;
        final Interpolator scaleInterpolator = new Interpolator() {
            @Override
            public float getInterpolation(float x) {
                if (x < scaleDurationPct) {
                    // Decelerate, and scale the input accordingly
                    return (float) (1f - Math.pow(1f - (x / scaleDurationPct), 2f));
                }
                return 1f;
            }
        };

        // Determine the bounds of how to scale
        float halfScreenWidth = (w - 2f * mBgPadding) / 2f;
        float halfScreenHeight = (h - 2f * mBgPadding) / 2f;
        final float offsetPct = SCREENSHOT_CORNER_MIN_SCALE_OFFSET;
        final PointF finalPos = new PointF(
                -halfScreenWidth + (SCREENSHOT_CORNER_MIN_SCALE + offsetPct) * halfScreenWidth,
                halfScreenHeight - (SCREENSHOT_CORNER_MIN_SCALE + offsetPct) * halfScreenHeight);

        // Animate the screenshot to the bottom left corner
        anim.setDuration(SCREENSHOT_DROP_OUT_DURATION);
        anim.addUpdateListener(animation -> {
            float t = (Float) animation.getAnimatedValue();
            float scaleT = (SCREENSHOT_DROP_IN_MIN_SCALE + mBgPaddingScale)
                    - scaleInterpolator.getInterpolation(t)
                    * (SCREENSHOT_DROP_IN_MIN_SCALE - SCREENSHOT_CORNER_MIN_SCALE);
            mBackgroundView.setAlpha((1f - t) * BACKGROUND_ALPHA);
            mScreenshotView.setScaleX(scaleT);
            mScreenshotView.setScaleY(scaleT);
            mScreenshotView.setTranslationX(t * finalPos.x);
            mScreenshotView.setTranslationY(t * finalPos.y);
        });
        return anim;
    }

    private ValueAnimator createScreenshotActionsShadeAnimation(
            PendingIntent shareAction, PendingIntent editAction) {
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        mActionsView.setY(mDisplayMetrics.heightPixels);
        mActionsView.setVisibility(VISIBLE);
        mActionsView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float actionsViewHeight = mActionsView.getMeasuredHeight();
        float screenshotStartHeight = mScreenshotView.getTranslationY();

        animator.addUpdateListener(animation -> {
            float t = animation.getAnimatedFraction();
            mScreenshotView.setTranslationY(screenshotStartHeight - actionsViewHeight * t);
            mActionsView.setY(mDisplayMetrics.heightPixels - actionsViewHeight * t);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mScreenshotView.requestFocus();
                mShareAction.setOnClickListener(v -> {
                    try {
                        shareAction.send();
                        clearScreenshot();
                    } catch (PendingIntent.CanceledException e) {
                        Log.e(TAG, "Share intent cancelled", e);
                    }
                });
                mEditAction.setOnClickListener(v -> {
                    try {
                        editAction.send();
                        clearScreenshot();
                    } catch (PendingIntent.CanceledException e) {
                        Log.e(TAG, "Edit intent cancelled", e);
                    }
                });
                Toast scrollNotImplemented = Toast.makeText(
                        mContext, "Not implemented", Toast.LENGTH_SHORT);
                mScrollAction.setOnClickListener(v -> scrollNotImplemented.show());
            }
        });
        return animator;
    }

    static void notifyScreenshotError(Context context, NotificationManager nManager, int msgResId) {
        Resources r = context.getResources();
        String errorMsg = r.getString(msgResId);

        // Repurpose the existing notification to notify the user of the error
        Notification.Builder b = new Notification.Builder(context, NotificationChannels.ALERTS)
                .setTicker(r.getString(R.string.screenshot_failed_title))
                .setContentTitle(r.getString(R.string.screenshot_failed_title))
                .setContentText(errorMsg)
                .setSmallIcon(R.drawable.stat_notify_image_error)
                .setWhen(System.currentTimeMillis())
                .setVisibility(Notification.VISIBILITY_PUBLIC) // ok to show outside lockscreen
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        final Intent intent = dpm.createAdminSupportIntent(
                DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);
        if (intent != null) {
            final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                    context, 0, intent, 0, null, UserHandle.CURRENT);
            b.setContentIntent(pendingIntent);
        }

        SystemUI.overrideNotificationAppName(context, b, true);

        Notification n = new Notification.BigTextStyle(b)
                .bigText(errorMsg)
                .build();
        nManager.notify(SystemMessage.NOTE_GLOBAL_SCREENSHOT, n);
    }

    @VisibleForTesting
    static CompletableFuture<List<Notification.Action>> getSmartActionsFuture(String screenshotId,
            Bitmap image, ScreenshotNotificationSmartActionsProvider smartActionsProvider,
            boolean smartActionsEnabled, boolean isManagedProfile) {
        if (!smartActionsEnabled) {
            Slog.i(TAG, "Screenshot Intelligence not enabled, returning empty list.");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        if (image.getConfig() != Bitmap.Config.HARDWARE) {
            Slog.w(TAG, String.format(
                    "Bitmap expected: Hardware, Bitmap found: %s. Returning empty list.",
                    image.getConfig()));
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        Slog.d(TAG, "Screenshot from a managed profile: " + isManagedProfile);
        CompletableFuture<List<Notification.Action>> smartActionsFuture;
        long startTimeMs = SystemClock.uptimeMillis();
        try {
            ActivityManager.RunningTaskInfo runningTask =
                    ActivityManagerWrapper.getInstance().getRunningTask();
            ComponentName componentName =
                    (runningTask != null && runningTask.topActivity != null)
                            ? runningTask.topActivity
                            : new ComponentName("", "");
            smartActionsFuture = smartActionsProvider.getActions(screenshotId, image,
                    componentName,
                    isManagedProfile);
        } catch (Throwable e) {
            long waitTimeMs = SystemClock.uptimeMillis() - startTimeMs;
            smartActionsFuture = CompletableFuture.completedFuture(Collections.emptyList());
            Slog.e(TAG, "Failed to get future for screenshot notification smart actions.", e);
            notifyScreenshotOp(screenshotId, smartActionsProvider,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOp.REQUEST_SMART_ACTIONS,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.ERROR,
                    waitTimeMs);
        }
        return smartActionsFuture;
    }

    @VisibleForTesting
    static List<Notification.Action> getSmartActions(String screenshotId,
            CompletableFuture<List<Notification.Action>> smartActionsFuture, int timeoutMs,
            ScreenshotNotificationSmartActionsProvider smartActionsProvider) {
        long startTimeMs = SystemClock.uptimeMillis();
        try {
            List<Notification.Action> actions = smartActionsFuture.get(timeoutMs,
                    TimeUnit.MILLISECONDS);
            long waitTimeMs = SystemClock.uptimeMillis() - startTimeMs;
            Slog.d(TAG, String.format("Got %d smart actions. Wait time: %d ms",
                    actions.size(), waitTimeMs));
            notifyScreenshotOp(screenshotId, smartActionsProvider,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOp.WAIT_FOR_SMART_ACTIONS,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.SUCCESS,
                    waitTimeMs);
            return actions;
        } catch (Throwable e) {
            long waitTimeMs = SystemClock.uptimeMillis() - startTimeMs;
            Slog.e(TAG, String.format("Error getting smart actions. Wait time: %d ms", waitTimeMs),
                    e);
            ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus status =
                    (e instanceof TimeoutException)
                            ? ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.TIMEOUT
                            : ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.ERROR;
            notifyScreenshotOp(screenshotId, smartActionsProvider,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOp.WAIT_FOR_SMART_ACTIONS,
                    status, waitTimeMs);
            return Collections.emptyList();
        }
    }

    static void notifyScreenshotOp(String screenshotId,
            ScreenshotNotificationSmartActionsProvider smartActionsProvider,
            ScreenshotNotificationSmartActionsProvider.ScreenshotOp op,
            ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus status, long durationMs) {
        try {
            smartActionsProvider.notifyOp(screenshotId, op, status, durationMs);
        } catch (Throwable e) {
            Slog.e(TAG, "Error in notifyScreenshotOp: ", e);
        }
    }

    static void notifyScreenshotAction(Context context, String screenshotId, String action,
            boolean isSmartAction) {
        try {
            ScreenshotNotificationSmartActionsProvider provider =
                    SystemUIFactory.getInstance().createScreenshotNotificationSmartActionsProvider(
                            context, THREAD_POOL_EXECUTOR, new Handler());
            provider.notifyAction(screenshotId, action, isSmartAction);
        } catch (Throwable e) {
            Slog.e(TAG, "Error in notifyScreenshotAction: ", e);
        }
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
                    cancelScreenshotNotification(context);
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
                String actionType = Intent.ACTION_EDIT.equals(intent.getAction()) ? ACTION_TYPE_EDIT
                        : ACTION_TYPE_SHARE;
                notifyScreenshotAction(context, intent.getStringExtra(EXTRA_ID),
                        actionType, false);
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
            cancelScreenshotNotification(context);
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
            cancelScreenshotNotification(context);

            // And delete the image from the media store
            final Uri uri = Uri.parse(intent.getStringExtra(SCREENSHOT_URI_ID));
            new DeleteImageInBackgroundTask(context).execute(uri);
            if (intent.getBooleanExtra(EXTRA_SMART_ACTIONS_ENABLED, false)) {
                notifyScreenshotAction(context, intent.getStringExtra(EXTRA_ID),
                        ACTION_TYPE_DELETE,
                        false);
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
            context.startActivityAsUser(actionIntent, opts.toBundle(),
                    UserHandle.CURRENT);

            notifyScreenshotAction(context, intent.getStringExtra(EXTRA_ID),
                    actionType,
                    true);
        }
    }

    private static void cancelScreenshotNotification(Context context) {
        final NotificationManager nm =
                (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(SystemMessage.NOTE_GLOBAL_SCREENSHOT);
    }
}
