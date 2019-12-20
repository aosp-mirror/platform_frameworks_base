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
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
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

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.phone.StatusBar;

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
public class GlobalScreenshot {

    /**
     * POD used in the AsyncTask which saves an image in the background.
     */
    static class SaveImageInBackgroundData {
        public Bitmap image;
        public Uri imageUri;
        public Consumer<Uri> finisher;
        public GlobalScreenshot.ActionsReadyListener mActionsReadyListener;
        public int errorMsgResId;

        void clearImage() {
            image = null;
            imageUri = null;
        }
    }

    abstract static class ActionsReadyListener {
        abstract void onActionsReady(Uri imageUri, List<Notification.Action> actions);
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

    private final ScreenshotNotificationsController mNotificationsController;

    private Context mContext;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mWindowLayoutParams;
    private Display mDisplay;
    private DisplayMetrics mDisplayMetrics;

    private Bitmap mScreenBitmap;
    private View mScreenshotLayout;
    private ScreenshotSelectorView mScreenshotSelectorView;
    private ImageView mBackgroundView;
    private ImageView mScreenshotView;
    private ImageView mScreenshotFlash;
    private LinearLayout mActionsView;

    private AnimatorSet mScreenshotAnimation;

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
    public GlobalScreenshot(
            Context context, @Main Resources resources, LayoutInflater layoutInflater,
            ScreenshotNotificationsController screenshotNotificationsController) {
        mContext = context;
        mNotificationsController = screenshotNotificationsController;

        // Inflate the screenshot layout
        mScreenshotLayout = layoutInflater.inflate(R.layout.global_screenshot, null);
        mBackgroundView = mScreenshotLayout.findViewById(R.id.global_screenshot_background);
        mScreenshotView = mScreenshotLayout.findViewById(R.id.global_screenshot);
        mActionsView = mScreenshotLayout.findViewById(R.id.global_screenshot_actions);

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
        mWindowLayoutParams.setFitWindowInsetsTypes(0 /* types */);
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();
        mDisplayMetrics = new DisplayMetrics();
        mDisplay.getRealMetrics(mDisplayMetrics);

        // Scale has to account for both sides of the bg
        mBgPadding = (float) resources.getDimensionPixelSize(R.dimen.global_screenshot_bg_padding);
        mBgPaddingScale = mBgPadding / mDisplayMetrics.widthPixels;


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
        data.image = mScreenBitmap;
        data.finisher = finisher;
        data.mActionsReadyListener = actionsReadyListener;
        if (mSaveInBgTask != null) {
            mSaveInBgTask.cancel(false);
        }

        if (!DeviceConfig.getBoolean(
                NAMESPACE_SYSTEMUI, SCREENSHOT_CORNER_FLOW, false)) {
            mNotificationsController.reset();
            mNotificationsController.setImage(mScreenBitmap);
            mNotificationsController.showSavingScreenshotNotification();
        }
        mSaveInBgTask = new SaveImageInBackgroundTask(mContext, data).execute();
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
            mNotificationsController.notifyScreenshotError(
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
                                mScreenshotLayout.post(() -> takeScreenshot(
                                        finisher, statusBarVisible, navBarVisible, rect));
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
                    saveScreenshotInWorkerThread(finisher, new ActionsReadyListener() {
                        @Override
                        void onActionsReady(Uri uri, List<Notification.Action> actions) {
                            if (uri == null) {
                                mNotificationsController.notifyScreenshotError(
                                        R.string.screenshot_failed_to_capture_text);
                            } else {
                                mNotificationsController
                                        .showScreenshotActionsNotification(uri, actions);
                            }
                        }
                    });
                    clearScreenshot();
                } else {
                    saveScreenshotInWorkerThread(finisher, new ActionsReadyListener() {
                        @Override
                        void onActionsReady(Uri uri, List<Notification.Action> actions) {
                            if (uri == null) {
                                mNotificationsController.notifyScreenshotError(
                                        R.string.screenshot_failed_to_capture_text);
                            } else {
                                mScreenshotHandler.post(() ->
                                        createScreenshotActionsShadeAnimation(actions).start());
                            }
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

    private ValueAnimator createScreenshotActionsShadeAnimation(List<Notification.Action> actions) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        mActionsView.removeAllViews();

        for (Notification.Action action : actions) {
            TextView actionChip = (TextView) inflater.inflate(
                    R.layout.global_screenshot_action_chip, mActionsView, false);
            actionChip.setText(action.title);
            actionChip.setOnClickListener(v -> {
                try {
                    action.actionIntent.send();
                    clearScreenshot();
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG,
                            String.format("Intent cancelled (title: %s)", action.title), e);
                }
            });
            mActionsView.addView(actionChip);
        }
        TextView scrollChip = (TextView) inflater.inflate(
                R.layout.global_screenshot_action_chip, mActionsView, false);
        Toast scrollNotImplemented = Toast.makeText(
                mContext, "Not implemented", Toast.LENGTH_SHORT);
        scrollChip.setText("Scroll"); // TODO (mkephart): add resource and translate
        scrollChip.setOnClickListener(v -> scrollNotImplemented.show());
        mActionsView.addView(scrollChip);

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
            }
        });
        return animator;
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
                String actionType = Intent.ACTION_EDIT.equals(intent.getAction()) ? ACTION_TYPE_EDIT
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
            context.startActivityAsUser(actionIntent, opts.toBundle(),
                    UserHandle.CURRENT);

            ScreenshotSmartActions.notifyScreenshotAction(
                    context, intent.getStringExtra(EXTRA_ID), actionType, true);
        }
    }
}
