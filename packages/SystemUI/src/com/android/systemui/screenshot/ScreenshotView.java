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

import static com.android.systemui.screenshot.LogConfig.DEBUG_ANIM;
import static com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS;
import static com.android.systemui.screenshot.LogConfig.DEBUG_INPUT;
import static com.android.systemui.screenshot.LogConfig.DEBUG_SCROLL;
import static com.android.systemui.screenshot.LogConfig.DEBUG_UI;
import static com.android.systemui.screenshot.LogConfig.DEBUG_WINDOW;
import static com.android.systemui.screenshot.LogConfig.logTag;

import static java.util.Objects.requireNonNull;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.screenshot.ScreenshotController.SavedImageData.ShareTransition;
import com.android.systemui.shared.system.QuickStepContract;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Handles the visual elements and animations for the screenshot flow.
 */
public class ScreenshotView extends FrameLayout implements
        ViewTreeObserver.OnComputeInternalInsetsListener {

    interface ScreenshotViewCallback {
        void onUserInteraction();

        void onDismiss();
    }

    private static final String TAG = logTag(ScreenshotView.class);

    private static final long SCREENSHOT_FLASH_IN_DURATION_MS = 133;
    private static final long SCREENSHOT_FLASH_OUT_DURATION_MS = 217;
    // delay before starting to fade in dismiss button
    private static final long SCREENSHOT_TO_CORNER_DISMISS_DELAY_MS = 200;
    private static final long SCREENSHOT_TO_CORNER_X_DURATION_MS = 234;
    private static final long SCREENSHOT_TO_CORNER_Y_DURATION_MS = 500;
    private static final long SCREENSHOT_TO_CORNER_SCALE_DURATION_MS = 234;
    private static final long SCREENSHOT_ACTIONS_EXPANSION_DURATION_MS = 400;
    private static final long SCREENSHOT_ACTIONS_ALPHA_DURATION_MS = 100;
    private static final long SCREENSHOT_DISMISS_Y_DURATION_MS = 350;
    private static final long SCREENSHOT_DISMISS_ALPHA_DURATION_MS = 183;
    private static final long SCREENSHOT_DISMISS_ALPHA_OFFSET_MS = 50; // delay before starting fade
    private static final long SCREENSHOT_DISMISS_SHARE_OFFSET_MS = 300; // delay after share clicked
    private static final float SCREENSHOT_ACTIONS_START_SCALE_X = .7f;
    private static final float ROUNDED_CORNER_RADIUS = .05f;
    private static final int SWIPE_PADDING_DP = 12; // extra padding around views to allow swipe

    private final Interpolator mAccelerateInterpolator = new AccelerateInterpolator();

    private final Resources mResources;
    private final Interpolator mFastOutSlowIn;
    private final DisplayMetrics mDisplayMetrics;
    private final float mCornerSizeX;
    private final float mDismissDeltaY;

    private int mNavMode;
    private int mLeftInset;
    private int mRightInset;
    private boolean mOrientationPortrait;
    private boolean mDirectionLTR;

    private ScreenshotSelectorView mScreenshotSelectorView;
    private View mScreenshotStatic;
    private ImageView mScreenshotPreview;
    private ImageView mScreenshotFlash;
    private ImageView mActionsContainerBackground;
    private HorizontalScrollView mActionsContainer;
    private LinearLayout mActionsView;
    private ImageView mBackgroundProtection;
    private FrameLayout mDismissButton;
    private ScreenshotActionChip mShareChip;
    private ScreenshotActionChip mEditChip;
    private ScreenshotActionChip mScrollChip;

    private UiEventLogger mUiEventLogger;
    private ScreenshotViewCallback mCallbacks;
    private Animator mDismissAnimation;
    private boolean mIgnoreDismiss;

    private final ArrayList<ScreenshotActionChip> mSmartChips = new ArrayList<>();
    private PendingInteraction mPendingInteraction;

    private enum PendingInteraction {
        PREVIEW,
        EDIT,
        SHARE
    }

    public ScreenshotView(Context context) {
        this(context, null);
    }

    public ScreenshotView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScreenshotView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ScreenshotView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mResources = mContext.getResources();

        mCornerSizeX = mResources.getDimensionPixelSize(R.dimen.global_screenshot_x_scale);
        mDismissDeltaY = mResources.getDimensionPixelSize(
                R.dimen.screenshot_dismissal_height_delta);

        // standard material ease
        mFastOutSlowIn =
                AnimationUtils.loadInterpolator(mContext, android.R.interpolator.fast_out_slow_in);

        mDisplayMetrics = new DisplayMetrics();
        mContext.getDisplay().getRealMetrics(mDisplayMetrics);
    }

    /**
     * Called to display the scroll action chip when support is detected.
     *
     * @param onClick the action to take when the chip is clicked.
     */
    public void showScrollChip(Runnable onClick) {
        if (DEBUG_SCROLL) {
            Log.d(TAG, "Showing Scroll option");
        }
        mScrollChip.setVisibility(VISIBLE);
        mScrollChip.setOnClickListener((v) -> {
            if (DEBUG_INPUT) {
                Log.d(TAG, "scroll chip tapped");
            }
            onClick.run();
            // TODO Logging, store event consumer to a field
            //onElementTapped.accept(ScreenshotEvent.SCREENSHOT_SCROLL_TAPPED);
        });
    }

    @Override // ViewTreeObserver.OnComputeInternalInsetsListener
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        Region touchRegion = new Region();

        final Rect tmpRect = new Rect();
        mScreenshotPreview.getBoundsOnScreen(tmpRect);
        tmpRect.inset((int) dpToPx(-SWIPE_PADDING_DP), (int) dpToPx(-SWIPE_PADDING_DP));
        touchRegion.op(tmpRect, Region.Op.UNION);
        mActionsContainerBackground.getBoundsOnScreen(tmpRect);
        tmpRect.inset((int) dpToPx(-SWIPE_PADDING_DP), (int) dpToPx(-SWIPE_PADDING_DP));
        touchRegion.op(tmpRect, Region.Op.UNION);
        mDismissButton.getBoundsOnScreen(tmpRect);
        touchRegion.op(tmpRect, Region.Op.UNION);

        if (QuickStepContract.isGesturalMode(mNavMode)) {
            // Receive touches in gesture insets such that they don't cause TOUCH_OUTSIDE
            Rect inset = new Rect(0, 0, mLeftInset, mDisplayMetrics.heightPixels);
            touchRegion.op(inset, Region.Op.UNION);
            inset.set(mDisplayMetrics.widthPixels - mRightInset, 0, mDisplayMetrics.widthPixels,
                    mDisplayMetrics.heightPixels);
            touchRegion.op(inset, Region.Op.UNION);
        }

        inoutInfo.touchableRegion.set(touchRegion);
    }

    @Override // View
    protected void onFinishInflate() {
        mScreenshotStatic = requireNonNull(findViewById(R.id.global_screenshot_static));
        mScreenshotPreview = requireNonNull(findViewById(R.id.global_screenshot_preview));

        mActionsContainerBackground = requireNonNull(findViewById(
                R.id.global_screenshot_actions_container_background));
        mActionsContainer = requireNonNull(findViewById(R.id.global_screenshot_actions_container));
        mActionsView = requireNonNull(findViewById(R.id.global_screenshot_actions));
        mBackgroundProtection = requireNonNull(
                findViewById(R.id.global_screenshot_actions_background));
        mDismissButton = requireNonNull(findViewById(R.id.global_screenshot_dismiss_button));
        mScreenshotFlash = requireNonNull(findViewById(R.id.global_screenshot_flash));
        mScreenshotSelectorView = requireNonNull(findViewById(R.id.global_screenshot_selector));
        mShareChip = requireNonNull(mActionsContainer.findViewById(R.id.screenshot_share_chip));
        mEditChip = requireNonNull(mActionsContainer.findViewById(R.id.screenshot_edit_chip));
        mScrollChip = requireNonNull(mActionsContainer.findViewById(R.id.screenshot_scroll_chip));

        mScreenshotPreview.setClipToOutline(true);
        mScreenshotPreview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(new Rect(0, 0, view.getWidth(), view.getHeight()),
                        ROUNDED_CORNER_RADIUS * view.getWidth());
            }
        });

        int swipePaddingPx = (int) dpToPx(SWIPE_PADDING_DP);
        TouchDelegate previewDelegate = new TouchDelegate(
                new Rect(swipePaddingPx, swipePaddingPx, swipePaddingPx, swipePaddingPx),
                mScreenshotPreview);
        mScreenshotPreview.setTouchDelegate(previewDelegate);
        TouchDelegate actionsDelegate = new TouchDelegate(
                new Rect(swipePaddingPx, swipePaddingPx, swipePaddingPx, swipePaddingPx),
                mActionsContainerBackground);
        mActionsContainerBackground.setTouchDelegate(actionsDelegate);

        setFocusable(true);
        mScreenshotSelectorView.setFocusable(true);
        mScreenshotSelectorView.setFocusableInTouchMode(true);
        mActionsContainer.setScrollX(0);

        mNavMode = getResources().getInteger(
                com.android.internal.R.integer.config_navBarInteractionMode);
        mOrientationPortrait =
                getResources().getConfiguration().orientation == ORIENTATION_PORTRAIT;
        mDirectionLTR =
                getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_LTR;

        setOnApplyWindowInsetsListener((v, insets) -> {
            if (QuickStepContract.isGesturalMode(mNavMode)) {
                Insets gestureInsets = insets.getInsets(WindowInsets.Type.systemGestures());
                mLeftInset = gestureInsets.left;
                mRightInset = gestureInsets.right;
            } else {
                mLeftInset = mRightInset = 0;
            }
            return ScreenshotView.this.onApplyWindowInsets(insets);
        });

        // Get focus so that the key events go to the layout.
        setFocusableInTouchMode(true);
        requestFocus();
    }

    /**
     * Set up the logger and callback on dismissal.
     *
     * Note: must be called before any other (non-constructor) method or null pointer exceptions
     * may occur.
     */
    void init(UiEventLogger uiEventLogger, ScreenshotViewCallback callbacks) {
        mUiEventLogger = uiEventLogger;
        mCallbacks = callbacks;
    }

    void takePartialScreenshot(Consumer<Rect> onPartialScreenshotSelected) {
        mScreenshotSelectorView.setOnScreenshotSelected(onPartialScreenshotSelected);
        mScreenshotSelectorView.setVisibility(View.VISIBLE);
        mScreenshotSelectorView.requestFocus();
    }

    void prepareForAnimation(Bitmap bitmap, Insets screenInsets) {
        mScreenshotPreview.setImageDrawable(createScreenDrawable(mResources, bitmap, screenInsets));
        // make static preview invisible (from gone) so we can query its location on screen
        mScreenshotPreview.setVisibility(View.INVISIBLE);
    }

    AnimatorSet createScreenshotDropInAnimation(Rect bounds, boolean showFlash) {
        mScreenshotPreview.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mScreenshotPreview.buildLayer();

        Rect previewBounds = new Rect();
        mScreenshotPreview.getBoundsOnScreen(previewBounds);
        int[] previewLocation = new int[2];
        mScreenshotPreview.getLocationInWindow(previewLocation);

        float cornerScale =
                mCornerSizeX / (mOrientationPortrait ? bounds.width() : bounds.height());
        final float currentScale = 1 / cornerScale;

        mScreenshotPreview.setScaleX(currentScale);
        mScreenshotPreview.setScaleY(currentScale);

        mDismissButton.setAlpha(0);
        mDismissButton.setVisibility(View.VISIBLE);

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

        // animate from the current location, to the static preview location
        final PointF startPos = new PointF(bounds.centerX(), bounds.centerY());
        final PointF finalPos = new PointF(previewLocation[0] + previewBounds.width() / 2f,
                previewLocation[1] + previewBounds.height() / 2f);

        ValueAnimator toCorner = ValueAnimator.ofFloat(0, 1);
        toCorner.setDuration(SCREENSHOT_TO_CORNER_Y_DURATION_MS);
        float xPositionPct =
                SCREENSHOT_TO_CORNER_X_DURATION_MS / (float) SCREENSHOT_TO_CORNER_Y_DURATION_MS;
        float dismissPct =
                SCREENSHOT_TO_CORNER_DISMISS_DELAY_MS / (float) SCREENSHOT_TO_CORNER_Y_DURATION_MS;
        float scalePct =
                SCREENSHOT_TO_CORNER_SCALE_DURATION_MS / (float) SCREENSHOT_TO_CORNER_Y_DURATION_MS;
        toCorner.addUpdateListener(animation -> {
            float t = animation.getAnimatedFraction();
            if (t < scalePct) {
                float scale = MathUtils.lerp(
                        currentScale, 1, mFastOutSlowIn.getInterpolation(t / scalePct));
                mScreenshotPreview.setScaleX(scale);
                mScreenshotPreview.setScaleY(scale);
            } else {
                mScreenshotPreview.setScaleX(1);
                mScreenshotPreview.setScaleY(1);
            }

            if (t < xPositionPct) {
                float xCenter = MathUtils.lerp(startPos.x, finalPos.x,
                        mFastOutSlowIn.getInterpolation(t / xPositionPct));
                mScreenshotPreview.setX(xCenter - mScreenshotPreview.getWidth() / 2f);
            } else {
                mScreenshotPreview.setX(finalPos.x - mScreenshotPreview.getWidth() / 2f);
            }
            float yCenter = MathUtils.lerp(
                    startPos.y, finalPos.y, mFastOutSlowIn.getInterpolation(t));
            mScreenshotPreview.setY(yCenter - mScreenshotPreview.getHeight() / 2f);

            if (t >= dismissPct) {
                mDismissButton.setAlpha((t - dismissPct) / (1 - dismissPct));
                float currentX = mScreenshotPreview.getX();
                float currentY = mScreenshotPreview.getY();
                mDismissButton.setY(currentY - mDismissButton.getHeight() / 2f);
                if (mDirectionLTR) {
                    mDismissButton.setX(currentX + mScreenshotPreview.getWidth()
                            - mDismissButton.getWidth() / 2f);
                } else {
                    mDismissButton.setX(currentX - mDismissButton.getWidth() / 2f);
                }
            }
        });

        toCorner.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mScreenshotPreview.setVisibility(View.VISIBLE);
            }
        });

        mScreenshotFlash.setAlpha(0f);
        mScreenshotFlash.setVisibility(View.VISIBLE);

        if (showFlash) {
            dropInAnimation.play(flashOutAnimator).after(flashInAnimator);
            dropInAnimation.play(flashOutAnimator).with(toCorner);
        } else {
            dropInAnimation.play(toCorner);
        }

        dropInAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (DEBUG_ANIM) {
                    Log.d(TAG, "drop-in animation completed");
                }
                mDismissButton.setOnClickListener(view -> {
                    if (DEBUG_INPUT) {
                        Log.d(TAG, "dismiss button clicked");
                    }
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_EXPLICIT_DISMISSAL);
                    animateDismissal();
                });
                mDismissButton.setAlpha(1);
                float dismissOffset = mDismissButton.getWidth() / 2f;
                float finalDismissX = mDirectionLTR
                        ? finalPos.x - dismissOffset + bounds.width() * cornerScale / 2f
                        : finalPos.x - dismissOffset - bounds.width() * cornerScale / 2f;
                mDismissButton.setX(finalDismissX);
                mDismissButton.setY(
                        finalPos.y - dismissOffset - bounds.height() * cornerScale / 2f);
                mScreenshotPreview.setScaleX(1);
                mScreenshotPreview.setScaleY(1);
                mScreenshotPreview.setX(finalPos.x - bounds.width() * cornerScale / 2f);
                mScreenshotPreview.setY(finalPos.y - bounds.height() * cornerScale / 2f);
                requestLayout();

                createScreenshotActionsShadeAnimation().start();

                SwipeDismissHandler swipeDismissHandler = new SwipeDismissHandler();
                mScreenshotPreview.setOnTouchListener(swipeDismissHandler);
                mActionsContainer.setOnTouchListener(swipeDismissHandler);
                mActionsContainerBackground.setOnTouchListener(swipeDismissHandler);
                mBackgroundProtection.setOnTouchListener(swipeDismissHandler);
            }
        });

        return dropInAnimation;
    }

    ValueAnimator createScreenshotActionsShadeAnimation() {
        // By default the activities won't be able to start immediately; override this to keep
        // the same behavior as if started from a notification
        try {
            ActivityManager.getService().resumeAppSwitches();
        } catch (RemoteException e) {
        }

        ArrayList<ScreenshotActionChip> chips = new ArrayList<>();

        mShareChip.setText(mContext.getString(com.android.internal.R.string.share));
        mShareChip.setIcon(Icon.createWithResource(mContext, R.drawable.ic_screenshot_share), true);
        mShareChip.setOnClickListener(v -> {
            mShareChip.setIsPending(true);
            mEditChip.setIsPending(false);
            mPendingInteraction = PendingInteraction.SHARE;
        });
        chips.add(mShareChip);

        mEditChip.setText(mContext.getString(R.string.screenshot_edit_label));
        mEditChip.setIcon(Icon.createWithResource(mContext, R.drawable.ic_screenshot_edit), true);
        mEditChip.setOnClickListener(v -> {
            mEditChip.setIsPending(true);
            mShareChip.setIsPending(false);
            mPendingInteraction = PendingInteraction.EDIT;
        });
        chips.add(mEditChip);

        mScreenshotPreview.setOnClickListener(v -> {
            mShareChip.setIsPending(false);
            mEditChip.setIsPending(false);
            mPendingInteraction = PendingInteraction.PREVIEW;
        });

        mScrollChip.setText(mContext.getString(R.string.screenshot_scroll_label));
        mScrollChip.setIcon(Icon.createWithResource(mContext,
                R.drawable.ic_screenshot_scroll), true);
        chips.add(mScrollChip);

        // remove the margin from the last chip so that it's correctly aligned with the end
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)
                mActionsView.getChildAt(0).getLayoutParams();
        params.setMarginEnd(0);
        mActionsView.getChildAt(0).setLayoutParams(params);

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
    protected View getScreenshotPreview() {
        return mScreenshotPreview;
    }

    void setChipIntents(ScreenshotController.SavedImageData imageData) {
        mShareChip.setOnClickListener(v -> {
            ShareTransition transition = imageData.shareTransition.get();
            try {
                mIgnoreDismiss = true;
                transition.shareAction.actionIntent.send();
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SHARE_TAPPED);

                // Ensures that we delay dismissing until transition has started.
                postDelayed(() -> {
                    mIgnoreDismiss = false;
                    animateDismissal();
                }, SCREENSHOT_DISMISS_SHARE_OFFSET_MS);
            } catch (PendingIntent.CanceledException e) {
                mIgnoreDismiss = false;
                if (transition.onCancelRunnable != null) {
                    transition.onCancelRunnable.run();
                }
                Log.e(TAG, "Share intent cancelled", e);
            }
        });
        mEditChip.setPendingIntent(imageData.editAction.actionIntent,
                () -> {
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_EDIT_TAPPED);
                    animateDismissal();
                });
        mScreenshotPreview.setOnClickListener(v -> {
            try {
                imageData.editAction.actionIntent.send();
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "PendingIntent was cancelled", e);
            }
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_PREVIEW_TAPPED);
            animateDismissal();
        });

        if (mPendingInteraction != null) {
            switch (mPendingInteraction) {
                case PREVIEW:
                    mScreenshotPreview.callOnClick();
                    break;
                case SHARE:
                    mShareChip.callOnClick();
                    break;
                case EDIT:
                    mEditChip.callOnClick();
                    break;
            }
        } else {
            LayoutInflater inflater = LayoutInflater.from(mContext);

            for (Notification.Action smartAction : imageData.smartActions) {
                ScreenshotActionChip actionChip = (ScreenshotActionChip) inflater.inflate(
                        R.layout.global_screenshot_action_chip, mActionsView, false);
                actionChip.setText(smartAction.title);
                actionChip.setIcon(smartAction.getIcon(), false);
                actionChip.setPendingIntent(smartAction.actionIntent,
                        () -> {
                            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SMART_ACTION_TAPPED);
                            animateDismissal();
                        });
                actionChip.setAlpha(1);
                mActionsView.addView(actionChip);
                mSmartChips.add(actionChip);
            }
        }
    }

    boolean isDismissing() {
        return (mDismissAnimation != null && mDismissAnimation.isRunning());
    }

    void animateDismissal() {
        animateDismissal(createScreenshotDismissAnimation());
    }

    private void animateDismissal(Animator dismissAnimation) {
        if (mIgnoreDismiss) {
            return;
        }
        if (DEBUG_WINDOW) {
            Log.d(TAG, "removing OnComputeInternalInsetsListener");
        }
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        mDismissAnimation = dismissAnimation;
        mDismissAnimation.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                if (DEBUG_ANIM) {
                    Log.d(TAG, "Cancelled dismiss animation");
                }
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (!mCancelled) {
                    if (DEBUG_ANIM) {
                        Log.d(TAG, "after dismiss animation, calling onDismissRunnable.run()");
                    }
                    mCallbacks.onDismiss();
                }
            }
        });
        if (DEBUG_ANIM) {
            Log.d(TAG, "Starting dismiss animation");
        }
        mDismissAnimation.start();
    }

    void reset() {
        if (DEBUG_UI) {
            Log.d(TAG, "reset screenshot view");
        }

        if (mDismissAnimation != null && mDismissAnimation.isRunning()) {
            if (DEBUG_ANIM) {
                Log.d(TAG, "cancelling dismiss animation");
            }
            mDismissAnimation.cancel();
        }
        if (DEBUG_WINDOW) {
            Log.d(TAG, "removing OnComputeInternalInsetsListener");
        }
        // Make sure we clean up the view tree observer
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        // Clear any references to the bitmap
        mScreenshotPreview.setImageDrawable(null);
        mActionsContainerBackground.setVisibility(View.GONE);
        mActionsContainer.setVisibility(View.GONE);
        mBackgroundProtection.setAlpha(0f);
        mDismissButton.setVisibility(View.GONE);
        mScreenshotPreview.setVisibility(View.GONE);
        mScreenshotPreview.setLayerType(View.LAYER_TYPE_NONE, null);
        mScreenshotStatic.setTranslationX(0);
        mScreenshotPreview.setTranslationY(0);
        mScreenshotPreview.setContentDescription(
                mContext.getResources().getString(R.string.screenshot_preview_description));
        mScreenshotPreview.setOnClickListener(null);
        mShareChip.setOnClickListener(null);
        mEditChip.setOnClickListener(null);
        mShareChip.setIsPending(false);
        mEditChip.setIsPending(false);
        mPendingInteraction = null;
        for (ScreenshotActionChip chip : mSmartChips) {
            mActionsView.removeView(chip);
        }
        mSmartChips.clear();
        setAlpha(1);
        mDismissButton.setTranslationY(0);
        mActionsContainer.setTranslationY(0);
        mActionsContainerBackground.setTranslationY(0);
        mScreenshotSelectorView.stop();
    }

    private AnimatorSet createScreenshotDismissAnimation() {
        ValueAnimator alphaAnim = ValueAnimator.ofFloat(0, 1);
        alphaAnim.setStartDelay(SCREENSHOT_DISMISS_ALPHA_OFFSET_MS);
        alphaAnim.setDuration(SCREENSHOT_DISMISS_ALPHA_DURATION_MS);
        alphaAnim.addUpdateListener(animation -> {
            setAlpha(1 - animation.getAnimatedFraction());
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
     * Create a drawable using the size of the bitmap and insets as the fractional inset parameters.
     */
    private static Drawable createScreenDrawable(Resources res, Bitmap bitmap, Insets insets) {
        int insettedWidth = bitmap.getWidth() - insets.left - insets.right;
        int insettedHeight = bitmap.getHeight() - insets.top - insets.bottom;

        BitmapDrawable bitmapDrawable = new BitmapDrawable(res, bitmap);
        if (insettedHeight == 0 || insettedWidth == 0 || bitmap.getWidth() == 0
                || bitmap.getHeight() == 0) {
            Log.e(TAG, "Can't create inset drawable, using 0 insets bitmap and insets create "
                    + "degenerate region: " + bitmap.getWidth() + "x" + bitmap.getHeight() + " "
                    + bitmapDrawable);
            return bitmapDrawable;
        }

        InsetDrawable insetDrawable = new InsetDrawable(bitmapDrawable,
                -1f * insets.left / insettedWidth,
                -1f * insets.top / insettedHeight,
                -1f * insets.right / insettedWidth,
                -1f * insets.bottom / insettedHeight);

        if (insets.left < 0 || insets.top < 0 || insets.right < 0 || insets.bottom < 0) {
            // Are any of the insets negative, meaning the bitmap is smaller than the bounds so need
            // to fill in the background of the drawable.
            return new LayerDrawable(new Drawable[]{
                    new ColorDrawable(Color.BLACK), insetDrawable});
        } else {
            return insetDrawable;
        }
    }

    private float dpToPx(float dp) {
        return dp * mDisplayMetrics.densityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;
    }

    class SwipeDismissHandler implements OnTouchListener {

        // if distance moved on ACTION_UP is less than this, register a click
        // otherwise, run return animator
        private static final float CLICK_MOVEMENT_THRESHOLD_DP = 1;
        // distance needed to register a dismissal
        private static final float DISMISS_DISTANCE_THRESHOLD_DP = 30;

        private final GestureDetector mGestureDetector;
        private final float mDismissStartX;
        private final Rect mActionsRect = new Rect();

        private float mStartX;
        private float mStartY;
        private float mTranslationX = 0;

        // tracks whether mStartX has been set for this interaction
        private boolean mInteractionStarted = false;
        // tracks whether we're dragging the UI (as opposed to scrolling the actions bar)
        private boolean mIsDragging = false;

        SwipeDismissHandler() {
            GestureDetector.OnGestureListener gestureListener = new SwipeDismissGestureListener();
            mGestureDetector = new GestureDetector(mContext, gestureListener);
            mDismissStartX = mDismissButton.getX();
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            boolean gestureResult = mGestureDetector.onTouchEvent(event);
            mCallbacks.onUserInteraction();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mInteractionStarted = true;
                mStartX = event.getRawX();
                mStartY = event.getRawY();
                return true;
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mInteractionStarted = false;
                mIsDragging = false;
                if (isPastDismissThreshold()
                        && (mDismissAnimation == null || !mDismissAnimation.isRunning())) {
                    if (DEBUG_INPUT) {
                        Log.d(TAG, "dismiss triggered via swipe gesture");
                    }
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SWIPE_DISMISSED);
                    animateDismissal(createSwipeDismissAnimation());
                    return true;
                } else if (MathUtils.dist(mStartX, mStartY, event.getRawX(), event.getRawY())
                        > dpToPx(CLICK_MOVEMENT_THRESHOLD_DP)) {
                    // if we've moved a non-negligible distance (but not past the threshold),
                    if (DEBUG_DISMISS) {
                        Log.d(TAG, "swipe gesture abandoned");
                    }
                    // start the return animation
                    if ((mDismissAnimation == null || !mDismissAnimation.isRunning())) {
                        createSwipeReturnAnimation().start();
                    }
                    return true;
                } else {
                    if (view == mScreenshotPreview) {
                        mScreenshotPreview.performClick();
                    }
                }
            }
            return gestureResult;
        }

        class SwipeDismissGestureListener extends GestureDetector.SimpleOnGestureListener {

            /**
             * This is somewhat complicated to handle because we want to allow scrolling the actions
             * bar (if it extends off the screen) as well as dismissing the UI horizontally by
             * dragging the actions bar. In addition, we don't get the initial ACTION_DOWN because
             * it is consumed by the action bar view.
             *
             * So, we use a gated system: first, keep track of the pointer location as we move;
             * next, check whether the actions bar can scroll in the direction we're moving in. If
             * it can, let the actions bar handle the event; otherwise, we've gone as far as we can
             * and can start dragging the UI instead.
             */
            @Override
            public boolean onScroll(
                    MotionEvent ev1, MotionEvent ev2, float distanceX, float distanceY) {
                mActionsContainer.getBoundsOnScreen(mActionsRect);
                if (!mInteractionStarted) {
                    if (mActionsRect.contains((int) ev2.getRawX(), (int) ev2.getRawY())) {
                        mStartX = ev2.getRawX();
                        mInteractionStarted = true;
                    }
                } else {
                    float distance = ev2.getRawX() - mStartX;
                    if ((mIsDragging && distance * mTranslationX > 0)
                            || !mActionsRect.contains((int) ev2.getRawX(), (int) ev2.getRawY())
                            || !mActionsContainer.canScrollHorizontally(-1 * (int) distance)) {
                        mIsDragging = true;
                        mTranslationX = distance;
                        mScreenshotStatic.setTranslationX(mTranslationX);
                        return true;
                    } else {
                        mStartX = ev2.getRawX();
                    }
                }
                return false;
            }
        }

        private boolean isPastDismissThreshold() {
            if (mDirectionLTR) {
                return mTranslationX <= -1 * dpToPx(DISMISS_DISTANCE_THRESHOLD_DP);
            } else {
                return mTranslationX >= dpToPx(DISMISS_DISTANCE_THRESHOLD_DP);
            }
        }

        private ValueAnimator createSwipeDismissAnimation() {
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            float startX = mTranslationX;
            float finalX = mDirectionLTR
                    ? -1 * (mActionsContainerBackground.getX()
                    + mActionsContainerBackground.getWidth())
                    : mDisplayMetrics.widthPixels;

            anim.addUpdateListener(animation -> {
                float translation = MathUtils.lerp(startX, finalX,
                        animation.getAnimatedFraction());
                mScreenshotStatic.setTranslationX(translation);

                setAlpha(1 - animation.getAnimatedFraction());
            });
            anim.setDuration(400);
            return anim;
        }

        private ValueAnimator createSwipeReturnAnimation() {
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            float startX = mTranslationX;
            float finalX = 0;
            mTranslationX = 0;

            anim.addUpdateListener(animation -> {
                float translation = MathUtils.lerp(
                        startX, finalX, animation.getAnimatedFraction());
                mScreenshotStatic.setTranslationX(translation);
            });

            return anim;
        }
    }
}
