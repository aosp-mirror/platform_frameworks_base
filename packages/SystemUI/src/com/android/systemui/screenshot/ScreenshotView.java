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
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Looper;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.view.Choreographer;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScrollCaptureResponse;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.screenshot.ScreenshotController.SavedImageData.ActionTransition;
import com.android.systemui.shared.system.InputMonitorCompat;
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

        /** DOWN motion event was observed outside of the touchable areas of this view. */
        void onTouchOutside();
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
    private static final float SCREENSHOT_ACTIONS_START_SCALE_X = .7f;
    private static final float ROUNDED_CORNER_RADIUS = .25f;
    private static final int SWIPE_PADDING_DP = 12; // extra padding around views to allow swipe

    private final Interpolator mAccelerateInterpolator = new AccelerateInterpolator();

    private final Resources mResources;
    private final Interpolator mFastOutSlowIn;
    private final DisplayMetrics mDisplayMetrics;
    private final float mCornerSizeX;
    private final float mDismissDeltaY;
    private final AccessibilityManager mAccessibilityManager;

    private int mNavMode;
    private boolean mOrientationPortrait;
    private boolean mDirectionLTR;

    private ScreenshotSelectorView mScreenshotSelectorView;
    private ImageView mScrollingScrim;
    private View mScreenshotStatic;
    private ImageView mScreenshotPreview;
    private View mScreenshotPreviewBorder;
    private ImageView mScrollablePreview;
    private ImageView mScreenshotFlash;
    private ImageView mActionsContainerBackground;
    private HorizontalScrollView mActionsContainer;
    private LinearLayout mActionsView;
    private ImageView mBackgroundProtection;
    private FrameLayout mDismissButton;
    private ScreenshotActionChip mShareChip;
    private ScreenshotActionChip mEditChip;
    private ScreenshotActionChip mScrollChip;
    private ScreenshotActionChip mQuickShareChip;

    private UiEventLogger mUiEventLogger;
    private ScreenshotViewCallback mCallbacks;
    private Animator mDismissAnimation;
    private boolean mPendingSharedTransition;
    private GestureDetector mSwipeDetector;
    private SwipeDismissHandler mSwipeDismissHandler;
    private InputMonitorCompat mInputMonitor;

    private final ArrayList<ScreenshotActionChip> mSmartChips = new ArrayList<>();
    private PendingInteraction mPendingInteraction;

    private enum PendingInteraction {
        PREVIEW,
        EDIT,
        SHARE,
        QUICK_SHARE
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

        mAccessibilityManager = AccessibilityManager.getInstance(mContext);

        mSwipeDetector = new GestureDetector(mContext,
                new GestureDetector.SimpleOnGestureListener() {
                    final Rect mActionsRect = new Rect();

                    @Override
                    public boolean onScroll(
                            MotionEvent ev1, MotionEvent ev2, float distanceX, float distanceY) {
                        mActionsContainer.getBoundsOnScreen(mActionsRect);
                        // return true if we aren't in the actions bar, or if we are but it isn't
                        // scrollable in the direction of movement
                        return !mActionsRect.contains((int) ev2.getRawX(), (int) ev2.getRawY())
                                || !mActionsContainer.canScrollHorizontally((int) distanceX);
                    }
                });
        mSwipeDetector.setIsLongpressEnabled(false);
        mSwipeDismissHandler = new SwipeDismissHandler();
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                startInputListening();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                stopInputListening();
            }
        });
    }

    public void hideScrollChip() {
        mScrollChip.setVisibility(View.GONE);
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
        mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_IMPRESSION);
        mScrollChip.setVisibility(VISIBLE);
        mScrollChip.setOnClickListener((v) -> {
            if (DEBUG_INPUT) {
                Log.d(TAG, "scroll chip tapped");
            }
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_REQUESTED);
            onClick.run();
        });
    }

    @Override // ViewTreeObserver.OnComputeInternalInsetsListener
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        inoutInfo.touchableRegion.set(getTouchRegion(true));
    }

    private Region getTouchRegion(boolean includeScrim) {
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

        if (includeScrim && mScrollingScrim.getVisibility() == View.VISIBLE) {
            mScrollingScrim.getBoundsOnScreen(tmpRect);
            touchRegion.op(tmpRect, Region.Op.UNION);
        }

        if (QuickStepContract.isGesturalMode(mNavMode)) {
            final WindowManager wm = mContext.getSystemService(WindowManager.class);
            final WindowMetrics windowMetrics = wm.getCurrentWindowMetrics();
            final Insets gestureInsets = windowMetrics.getWindowInsets().getInsets(
                    WindowInsets.Type.systemGestures());
            // Receive touches in gesture insets such that they don't cause TOUCH_OUTSIDE
            Rect inset = new Rect(0, 0, gestureInsets.left, mDisplayMetrics.heightPixels);
            touchRegion.op(inset, Region.Op.UNION);
            inset.set(mDisplayMetrics.widthPixels - gestureInsets.right, 0,
                    mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels);
            touchRegion.op(inset, Region.Op.UNION);
        }
        return touchRegion;
    }

    private void startInputListening() {
        stopInputListening();
        mInputMonitor = new InputMonitorCompat("Screenshot", Display.DEFAULT_DISPLAY);
        mInputMonitor.getInputReceiver(Looper.getMainLooper(), Choreographer.getInstance(),
                ev -> {
                    if (ev instanceof MotionEvent) {
                        MotionEvent event = (MotionEvent) ev;
                        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                                && !getTouchRegion(false).contains(
                                (int) event.getRawX(), (int) event.getRawY())) {
                            mCallbacks.onTouchOutside();
                        }
                    }
                });
    }

    private void stopInputListening() {
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    @Override // ViewGroup
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // scrolling scrim should not be swipeable; return early if we're on the scrim
        if (!getTouchRegion(false).contains((int) ev.getRawX(), (int) ev.getRawY())) {
            return false;
        }
        // always pass through the down event so the swipe handler knows the initial state
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mSwipeDismissHandler.onTouch(this, ev);
        }
        return mSwipeDetector.onTouchEvent(ev);
    }

    @Override // View
    protected void onFinishInflate() {
        mScrollingScrim = requireNonNull(findViewById(R.id.screenshot_scrolling_scrim));
        mScreenshotStatic = requireNonNull(findViewById(R.id.global_screenshot_static));
        mScreenshotPreview = requireNonNull(findViewById(R.id.global_screenshot_preview));
        mScreenshotPreviewBorder = requireNonNull(
                findViewById(R.id.global_screenshot_preview_border));
        mScreenshotPreview.setClipToOutline(true);

        mActionsContainerBackground = requireNonNull(findViewById(
                R.id.global_screenshot_actions_container_background));
        mActionsContainer = requireNonNull(findViewById(R.id.global_screenshot_actions_container));
        mActionsView = requireNonNull(findViewById(R.id.global_screenshot_actions));
        mBackgroundProtection = requireNonNull(
                findViewById(R.id.global_screenshot_actions_background));
        mDismissButton = requireNonNull(findViewById(R.id.global_screenshot_dismiss_button));
        mScrollablePreview = requireNonNull(findViewById(R.id.screenshot_scrollable_preview));
        mScreenshotFlash = requireNonNull(findViewById(R.id.global_screenshot_flash));
        mScreenshotSelectorView = requireNonNull(findViewById(R.id.global_screenshot_selector));
        mShareChip = requireNonNull(mActionsContainer.findViewById(R.id.screenshot_share_chip));
        mEditChip = requireNonNull(mActionsContainer.findViewById(R.id.screenshot_edit_chip));
        mScrollChip = requireNonNull(mActionsContainer.findViewById(R.id.screenshot_scroll_chip));

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

        // Get focus so that the key events go to the layout.
        setFocusableInTouchMode(true);
        requestFocus();
    }

    View getScreenshotPreview() {
        return mScreenshotPreview;
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

    void setScreenshot(Bitmap bitmap, Insets screenInsets) {
        mScreenshotPreview.setImageDrawable(createScreenDrawable(mResources, bitmap, screenInsets));
    }

    void updateDisplayCutoutMargins(DisplayCutout cutout) {
        int orientation = mContext.getResources().getConfiguration().orientation;
        mOrientationPortrait = (orientation == ORIENTATION_PORTRAIT);
        FrameLayout.LayoutParams p =
                (FrameLayout.LayoutParams) mScreenshotStatic.getLayoutParams();
        if (cutout == null) {
            p.setMargins(0, 0, 0, 0);
        } else {
            Insets waterfall = cutout.getWaterfallInsets();
            if (mOrientationPortrait) {
                p.setMargins(waterfall.left, Math.max(cutout.getSafeInsetTop(), waterfall.top),
                        waterfall.right, Math.max(cutout.getSafeInsetBottom(), waterfall.bottom));
            } else {
                p.setMargins(Math.max(cutout.getSafeInsetLeft(), waterfall.left), waterfall.top,
                        Math.max(cutout.getSafeInsetRight(), waterfall.right), waterfall.bottom);
            }
        }
        mScreenshotStatic.setLayoutParams(p);
        mScreenshotStatic.requestLayout();
    }

    void updateOrientation(DisplayCutout cutout) {
        int orientation = mContext.getResources().getConfiguration().orientation;
        mOrientationPortrait = (orientation == ORIENTATION_PORTRAIT);
        updateDisplayCutoutMargins(cutout);
        int screenshotFixedSize =
                mContext.getResources().getDimensionPixelSize(R.dimen.global_screenshot_x_scale);
        ViewGroup.LayoutParams params = mScreenshotPreview.getLayoutParams();
        if (mOrientationPortrait) {
            params.width = screenshotFixedSize;
            params.height = LayoutParams.WRAP_CONTENT;
            mScreenshotPreview.setScaleType(ImageView.ScaleType.FIT_START);
        } else {
            params.width = LayoutParams.WRAP_CONTENT;
            params.height = screenshotFixedSize;
            mScreenshotPreview.setScaleType(ImageView.ScaleType.FIT_END);
        }

        mScreenshotPreview.setLayoutParams(params);
    }

    AnimatorSet createScreenshotDropInAnimation(Rect bounds, boolean showFlash) {
        if (DEBUG_ANIM) {
            Log.d(TAG, "createAnim: bounds=" + bounds + " showFlash=" + showFlash);
        }

        Rect targetPosition = new Rect();
        mScreenshotPreview.getHitRect(targetPosition);

        // ratio of preview width, end vs. start size
        float cornerScale =
                mCornerSizeX / (mOrientationPortrait ? bounds.width() : bounds.height());
        final float currentScale = 1 / cornerScale;

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
        final PointF finalPos = new PointF(targetPosition.exactCenterX(),
                targetPosition.exactCenterY());

        // Shift to screen coordinates so that the animation runs on top of the entire screen,
        // including e.g. bars covering the display cutout.
        int[] locInScreen = mScreenshotPreview.getLocationOnScreen();
        startPos.offset(targetPosition.left - locInScreen[0], targetPosition.top - locInScreen[1]);

        if (DEBUG_ANIM) {
            Log.d(TAG, "toCorner: startPos=" + startPos);
            Log.d(TAG, "toCorner: finalPos=" + finalPos);
        }

        ValueAnimator toCorner = ValueAnimator.ofFloat(0, 1);
        toCorner.setDuration(SCREENSHOT_TO_CORNER_Y_DURATION_MS);

        toCorner.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mScreenshotPreview.setScaleX(currentScale);
                mScreenshotPreview.setScaleY(currentScale);
                mScreenshotPreview.setVisibility(View.VISIBLE);
                if (mAccessibilityManager.isEnabled()) {
                    mDismissButton.setAlpha(0);
                    mDismissButton.setVisibility(View.VISIBLE);
                }
            }
        });

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

        mScreenshotFlash.setAlpha(0f);
        mScreenshotFlash.setVisibility(View.VISIBLE);

        ValueAnimator borderFadeIn = ValueAnimator.ofFloat(0, 1);
        borderFadeIn.setDuration(100);
        borderFadeIn.addUpdateListener((animation) ->
                mScreenshotPreviewBorder.setAlpha(animation.getAnimatedFraction()));

        if (showFlash) {
            dropInAnimation.play(flashOutAnimator).after(flashInAnimator);
            dropInAnimation.play(flashOutAnimator).with(toCorner);
        } else {
            dropInAnimation.play(toCorner);
        }
        dropInAnimation.play(borderFadeIn).after(toCorner);

        dropInAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (DEBUG_ANIM) {
                    Log.d(TAG, "drop-in animation ended");
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
                mScreenshotPreview.setX(finalPos.x - mScreenshotPreview.getWidth() / 2f);
                mScreenshotPreview.setY(finalPos.y - mScreenshotPreview.getHeight() / 2f);
                requestLayout();

                createScreenshotActionsShadeAnimation().start();

                setOnTouchListener(mSwipeDismissHandler);
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

        mShareChip.setContentDescription(mContext.getString(com.android.internal.R.string.share));
        mShareChip.setIcon(Icon.createWithResource(mContext, R.drawable.ic_screenshot_share), true);
        mShareChip.setOnClickListener(v -> {
            mShareChip.setIsPending(true);
            mEditChip.setIsPending(false);
            if (mQuickShareChip != null) {
                mQuickShareChip.setIsPending(false);
            }
            mPendingInteraction = PendingInteraction.SHARE;
        });
        chips.add(mShareChip);

        mEditChip.setContentDescription(mContext.getString(R.string.screenshot_edit_label));
        mEditChip.setIcon(Icon.createWithResource(mContext, R.drawable.ic_screenshot_edit), true);
        mEditChip.setOnClickListener(v -> {
            mEditChip.setIsPending(true);
            mShareChip.setIsPending(false);
            if (mQuickShareChip != null) {
                mQuickShareChip.setIsPending(false);
            }
            mPendingInteraction = PendingInteraction.EDIT;
        });
        chips.add(mEditChip);

        mScreenshotPreview.setOnClickListener(v -> {
            mShareChip.setIsPending(false);
            mEditChip.setIsPending(false);
            if (mQuickShareChip != null) {
                mQuickShareChip.setIsPending(false);
            }
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

    void setChipIntents(ScreenshotController.SavedImageData imageData) {
        mShareChip.setOnClickListener(v -> {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SHARE_TAPPED);
            startSharedTransition(
                    imageData.shareTransition.get());
        });
        mEditChip.setOnClickListener(v -> {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_EDIT_TAPPED);
            startSharedTransition(
                    imageData.editTransition.get());
        });
        mScreenshotPreview.setOnClickListener(v -> {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_PREVIEW_TAPPED);
            startSharedTransition(
                    imageData.editTransition.get());
        });
        if (mQuickShareChip != null) {
            mQuickShareChip.setPendingIntent(imageData.quickShareAction.actionIntent,
                    () -> {
                        mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SMART_ACTION_TAPPED);
                        animateDismissal();
                    });
        }

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
                case QUICK_SHARE:
                    mQuickShareChip.callOnClick();
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

    void addQuickShareChip(Notification.Action quickShareAction) {
        if (mPendingInteraction == null) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            mQuickShareChip = (ScreenshotActionChip) inflater.inflate(
                    R.layout.global_screenshot_action_chip, mActionsView, false);
            mQuickShareChip.setText(quickShareAction.title);
            mQuickShareChip.setIcon(quickShareAction.getIcon(), false);
            mQuickShareChip.setOnClickListener(v -> {
                mShareChip.setIsPending(false);
                mEditChip.setIsPending(false);
                mQuickShareChip.setIsPending(true);
                mPendingInteraction = PendingInteraction.QUICK_SHARE;
            });
            mQuickShareChip.setAlpha(1);
            mActionsView.addView(mQuickShareChip);
            mSmartChips.add(mQuickShareChip);
        }
    }

    private Rect scrollableAreaOnScreen(ScrollCaptureResponse response) {
        Rect r = new Rect(response.getBoundsInWindow());
        Rect windowInScreen = response.getWindowBounds();
        r.offset(windowInScreen.left, windowInScreen.top);
        r.intersect(new Rect(0, 0, mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
        return r;
    }

    void startLongScreenshotTransition(Rect destination, Runnable onTransitionEnd,
            ScrollCaptureController.LongScreenshot longScreenshot) {
        mScrollablePreview.setImageBitmap(longScreenshot.toBitmap());
        ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
        float startX = mScrollablePreview.getX();
        float startY = mScrollablePreview.getY();
        int[] locInScreen = mScrollablePreview.getLocationOnScreen();
        destination.offset((int) startX - locInScreen[0], (int) startY - locInScreen[1]);
        mScrollablePreview.setPivotX(0);
        mScrollablePreview.setPivotY(0);
        mScrollablePreview.setAlpha(1f);
        float currentScale = mScrollablePreview.getWidth() / (float) longScreenshot.getWidth();
        Matrix matrix = new Matrix();
        matrix.setScale(currentScale, currentScale);
        matrix.postTranslate(
                longScreenshot.getLeft() * currentScale, longScreenshot.getTop() * currentScale);
        mScrollablePreview.setImageMatrix(matrix);
        float destinationScale = destination.width() / (float) mScrollablePreview.getWidth();
        anim.addUpdateListener(animation -> {
            float t = animation.getAnimatedFraction();
            mScrollingScrim.setAlpha(1 - t);
            float currScale = MathUtils.lerp(1, destinationScale, t);
            mScrollablePreview.setScaleX(currScale);
            mScrollablePreview.setScaleY(currScale);
            mScrollablePreview.setX(MathUtils.lerp(startX, destination.left, t));
            mScrollablePreview.setY(MathUtils.lerp(startY, destination.top, t));
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                onTransitionEnd.run();
                mScrollablePreview.animate().alpha(0).setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        mCallbacks.onDismiss();
                    }
                });
            }
        });
        anim.start();
    }

    void prepareScrollingTransition(ScrollCaptureResponse response, Bitmap screenBitmap,
            Bitmap newBitmap) {
        mScrollingScrim.setImageBitmap(newBitmap);
        mScrollingScrim.setVisibility(View.VISIBLE);
        Rect scrollableArea = scrollableAreaOnScreen(response);
        float scale = mCornerSizeX
                / (mOrientationPortrait ? screenBitmap.getWidth() : screenBitmap.getHeight());
        ConstraintLayout.LayoutParams params =
                (ConstraintLayout.LayoutParams) mScrollablePreview.getLayoutParams();

        params.width = (int) (scale * scrollableArea.width());
        params.height = (int) (scale * scrollableArea.height());
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(-scrollableArea.left * scale, -scrollableArea.top * scale);

        mScrollablePreview.setTranslationX(scale * scrollableArea.left);
        mScrollablePreview.setTranslationY(scale * scrollableArea.top);
        mScrollablePreview.setImageMatrix(matrix);

        mScrollablePreview.setImageBitmap(screenBitmap);
        mScrollablePreview.setVisibility(View.VISIBLE);
        mDismissButton.setVisibility(View.GONE);
        mActionsContainer.setVisibility(View.GONE);
        mBackgroundProtection.setVisibility(View.GONE);
        // set these invisible, but not gone, so that the views are laid out correctly
        mActionsContainerBackground.setVisibility(View.INVISIBLE);
        mScreenshotPreviewBorder.setVisibility(View.INVISIBLE);
        mScreenshotPreview.setVisibility(View.INVISIBLE);
        mScrollingScrim.setImageTintBlendMode(BlendMode.SRC_ATOP);
        ValueAnimator anim = ValueAnimator.ofFloat(0, .3f);
        anim.addUpdateListener(animation -> mScrollingScrim.setImageTintList(
                ColorStateList.valueOf(Color.argb((float) animation.getAnimatedValue(), 0, 0, 0))));
        anim.setDuration(200);
        anim.start();
    }

    void restoreNonScrollingUi() {
        mScrollChip.setVisibility(View.GONE);
        mScrollablePreview.setVisibility(View.GONE);
        mScrollingScrim.setVisibility(View.GONE);

        if (mAccessibilityManager.isEnabled()) {
            mDismissButton.setVisibility(View.VISIBLE);
        }
        mActionsContainer.setVisibility(View.VISIBLE);
        mBackgroundProtection.setVisibility(View.VISIBLE);
        mActionsContainerBackground.setVisibility(View.VISIBLE);
        mScreenshotPreviewBorder.setVisibility(View.VISIBLE);
        mScreenshotPreview.setVisibility(View.VISIBLE);
        // reset the timeout
        mCallbacks.onUserInteraction();
    }

    boolean isDismissing() {
        return (mDismissAnimation != null && mDismissAnimation.isRunning());
    }

    boolean isPendingSharedTransition() {
        return mPendingSharedTransition;
    }

    void animateDismissal() {
        animateDismissal(createScreenshotTranslateDismissAnimation());
    }

    private void animateDismissal(Animator dismissAnimation) {
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
        mScreenshotPreview.setVisibility(View.INVISIBLE);
        mScreenshotPreviewBorder.setAlpha(0);
        mPendingSharedTransition = false;
        mActionsContainerBackground.setVisibility(View.GONE);
        mActionsContainer.setVisibility(View.GONE);
        mBackgroundProtection.setAlpha(0f);
        mDismissButton.setVisibility(View.GONE);
        mScreenshotStatic.setTranslationX(0);
        mScreenshotPreview.setTranslationY(0);
        mScreenshotPreview.setContentDescription(
                mContext.getResources().getString(R.string.screenshot_preview_description));
        mScreenshotPreview.setOnClickListener(null);
        mShareChip.setOnClickListener(null);
        mScrollingScrim.setVisibility(View.GONE);
        mEditChip.setOnClickListener(null);
        mShareChip.setIsPending(false);
        mEditChip.setIsPending(false);
        mPendingInteraction = null;
        for (ScreenshotActionChip chip : mSmartChips) {
            mActionsView.removeView(chip);
        }
        mSmartChips.clear();
        mQuickShareChip = null;
        setAlpha(1);
        mDismissButton.setTranslationY(0);
        mActionsContainer.setTranslationY(0);
        mActionsContainerBackground.setTranslationY(0);
        mScreenshotSelectorView.stop();
    }

    private void startSharedTransition(ActionTransition transition) {
        try {
            mPendingSharedTransition = true;
            transition.action.actionIntent.send();

            // fade out non-preview UI
            createScreenshotFadeDismissAnimation().start();
        } catch (PendingIntent.CanceledException e) {
            mPendingSharedTransition = false;
            if (transition.onCancelRunnable != null) {
                transition.onCancelRunnable.run();
            }
            Log.e(TAG, "Intent cancelled", e);
        }
    }

    private AnimatorSet createScreenshotTranslateDismissAnimation() {
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
            mScreenshotPreviewBorder.setTranslationY(screenshotStartY + yDelta);
            mDismissButton.setTranslationY(dismissStartY + yDelta);
            mActionsContainer.setTranslationY(yDelta);
            mActionsContainerBackground.setTranslationY(yDelta);
        });

        AnimatorSet animSet = new AnimatorSet();
        animSet.play(yAnim).with(alphaAnim);

        return animSet;
    }

    ValueAnimator createScreenshotFadeDismissAnimation() {
        ValueAnimator alphaAnim = ValueAnimator.ofFloat(0, 1);
        alphaAnim.addUpdateListener(animation -> {
            float alpha = 1 - animation.getAnimatedFraction();
            mDismissButton.setAlpha(alpha);
            mActionsContainerBackground.setAlpha(alpha);
            mActionsContainer.setAlpha(alpha);
            mBackgroundProtection.setAlpha(alpha);
            mScreenshotPreviewBorder.setAlpha(alpha);
        });
        alphaAnim.setDuration(600);
        return alphaAnim;
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
        // distance needed to register a dismissal
        private static final float DISMISS_DISTANCE_THRESHOLD_DP = 20;

        private final GestureDetector mGestureDetector;

        private float mStartX;
        // Keeps track of the most recent direction (between the last two move events).
        // -1 for left; +1 for right.
        private int mDirectionX;
        private float mPreviousX;

        SwipeDismissHandler() {
            GestureDetector.OnGestureListener gestureListener = new SwipeDismissGestureListener();
            mGestureDetector = new GestureDetector(mContext, gestureListener);
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            boolean gestureResult = mGestureDetector.onTouchEvent(event);
            mCallbacks.onUserInteraction();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mStartX = event.getRawX();
                mPreviousX = mStartX;
                return true;
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (isPastDismissThreshold()
                        && (mDismissAnimation == null || !mDismissAnimation.isRunning())) {
                    if (DEBUG_INPUT) {
                        Log.d(TAG, "dismiss triggered via swipe gesture");
                    }
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SWIPE_DISMISSED);
                    animateDismissal(createSwipeDismissAnimation());
                } else {
                    // if we've moved, but not past the threshold, start the return animation
                    if (DEBUG_DISMISS) {
                        Log.d(TAG, "swipe gesture abandoned");
                    }
                    if ((mDismissAnimation == null || !mDismissAnimation.isRunning())) {
                        createSwipeReturnAnimation().start();
                    }
                }
                return true;
            }
            return gestureResult;
        }

        class SwipeDismissGestureListener extends GestureDetector.SimpleOnGestureListener {
            @Override
            public boolean onScroll(
                    MotionEvent ev1, MotionEvent ev2, float distanceX, float distanceY) {
                mScreenshotStatic.setTranslationX(ev2.getRawX() - mStartX);
                mDirectionX = (ev2.getRawX() < mPreviousX) ? -1 : 1;
                mPreviousX = ev2.getRawX();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                    float velocityY) {
                if (mScreenshotStatic.getTranslationX() * velocityX > 0
                        && (mDismissAnimation == null || !mDismissAnimation.isRunning())) {
                    animateDismissal(createSwipeDismissAnimation(velocityX / (float) 1000));
                    return true;
                }
                return false;
            }
        }

        private boolean isPastDismissThreshold() {
            float translationX = mScreenshotStatic.getTranslationX();
            // Determines whether the absolute translation from the start is in the same direction
            // as the current movement. For example, if the user moves most of the way to the right,
            // but then starts dragging back left, we do not dismiss even though the absolute
            // distance is greater than the threshold.
            if (translationX * mDirectionX > 0) {
                return Math.abs(translationX) >= dpToPx(DISMISS_DISTANCE_THRESHOLD_DP);
            }
            return false;
        }

        private ValueAnimator createSwipeDismissAnimation() {
            return createSwipeDismissAnimation(1);
        }

        private ValueAnimator createSwipeDismissAnimation(float velocity) {
            // velocity is measured in pixels per millisecond
            velocity = Math.min(3, Math.max(1, velocity));
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            float startX = mScreenshotStatic.getTranslationX();
            // make sure the UI gets all the way off the screen in the direction of movement
            // (the actions container background is guaranteed to be both the leftmost and
            // rightmost UI element in LTR and RTL)
            float finalX = startX < 0
                    ? -1 * mActionsContainerBackground.getRight()
                    : mDisplayMetrics.widthPixels;
            float distance = Math.abs(finalX - startX);

            anim.addUpdateListener(animation -> {
                float translation = MathUtils.lerp(startX, finalX, animation.getAnimatedFraction());
                mScreenshotStatic.setTranslationX(translation);
                setAlpha(1 - animation.getAnimatedFraction());
            });
            anim.setDuration((long) (distance / Math.abs(velocity)));
            return anim;
        }

        private ValueAnimator createSwipeReturnAnimation() {
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            float startX = mScreenshotStatic.getTranslationX();
            float finalX = 0;

            anim.addUpdateListener(animation -> {
                float translation = MathUtils.lerp(
                        startX, finalX, animation.getAnimatedFraction());
                mScreenshotStatic.setTranslationX(translation);
            });

            return anim;
        }
    }
}
