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

import static com.android.internal.jank.InteractionJankMonitor.CUJ_TAKE_SCREENSHOT;
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
import android.content.Intent;
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
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.screenshot.ScreenshotController.SavedImageData.ActionTransition;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.shared.system.InputMonitorCompat;
import com.android.systemui.shared.system.QuickStepContract;

import java.util.ArrayList;

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
    public static final long SCREENSHOT_ACTIONS_EXPANSION_DURATION_MS = 400;
    private static final long SCREENSHOT_ACTIONS_ALPHA_DURATION_MS = 100;
    private static final float SCREENSHOT_ACTIONS_START_SCALE_X = .7f;
    private static final int SWIPE_PADDING_DP = 12; // extra padding around views to allow swipe

    private final Resources mResources;
    private final Interpolator mFastOutSlowIn;
    private final DisplayMetrics mDisplayMetrics;
    private final float mFixedSize;
    private final AccessibilityManager mAccessibilityManager;
    private final GestureDetector mSwipeDetector;

    private int mDefaultDisplay = Display.DEFAULT_DISPLAY;
    private int mNavMode;
    private boolean mOrientationPortrait;
    private boolean mDirectionLTR;

    private ImageView mScrollingScrim;
    private DraggableConstraintLayout mScreenshotStatic;
    private ImageView mScreenshotPreview;
    private ImageView mScreenshotBadge;
    private View mScreenshotPreviewBorder;
    private ImageView mScrollablePreview;
    private ImageView mScreenshotFlash;
    private ImageView mActionsContainerBackground;
    private HorizontalScrollView mActionsContainer;
    private LinearLayout mActionsView;
    private FrameLayout mDismissButton;
    private OverlayActionChip mShareChip;
    private OverlayActionChip mEditChip;
    private OverlayActionChip mScrollChip;
    private OverlayActionChip mQuickShareChip;

    private UiEventLogger mUiEventLogger;
    private ScreenshotViewCallback mCallbacks;
    private boolean mPendingSharedTransition;
    private InputMonitorCompat mInputMonitor;
    private InputChannelCompat.InputEventReceiver mInputEventReceiver;
    private boolean mShowScrollablePreview;
    private String mPackageName = "";

    private final ArrayList<OverlayActionChip> mSmartChips = new ArrayList<>();
    private PendingInteraction mPendingInteraction;
    // Should only be set/used if the SCREENSHOT_METADATA flag is set.
    private ScreenshotData mScreenshotData;

    private final InteractionJankMonitor mInteractionJankMonitor;
    private long mDefaultTimeoutOfTimeoutHandler;
    private ActionIntentExecutor mActionExecutor;
    private FeatureFlags mFlags;

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
        mInteractionJankMonitor = getInteractionJankMonitorInstance();

        mFixedSize = mResources.getDimensionPixelSize(R.dimen.overlay_x_scale);

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

    private InteractionJankMonitor getInteractionJankMonitorInstance() {
        return InteractionJankMonitor.getInstance();
    }

    void setDefaultTimeoutMillis(long timeout) {
        mDefaultTimeoutOfTimeoutHandler = timeout;
    }

    public void hideScrollChip() {
        mScrollChip.setVisibility(View.GONE);
    }

    /**
     * Called to display the scroll action chip when support is detected.
     *
     * @param packageName the owning package of the window to be captured
     * @param onClick     the action to take when the chip is clicked.
     */
    public void showScrollChip(String packageName, Runnable onClick) {
        if (DEBUG_SCROLL) {
            Log.d(TAG, "Showing Scroll option");
        }
        mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_IMPRESSION, 0, packageName);
        mScrollChip.setVisibility(VISIBLE);
        mScrollChip.setOnClickListener((v) -> {
            if (DEBUG_INPUT) {
                Log.d(TAG, "scroll chip tapped");
            }
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_REQUESTED, 0,
                    packageName);
            onClick.run();
        });
    }

    @Override // ViewTreeObserver.OnComputeInternalInsetsListener
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        inoutInfo.touchableRegion.set(getTouchRegion(true));
    }

    private Region getSwipeRegion() {
        Region swipeRegion = new Region();

        final Rect tmpRect = new Rect();
        mScreenshotPreview.getBoundsOnScreen(tmpRect);
        tmpRect.inset((int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP),
                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP));
        swipeRegion.op(tmpRect, Region.Op.UNION);
        mActionsContainerBackground.getBoundsOnScreen(tmpRect);
        tmpRect.inset((int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP),
                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP));
        swipeRegion.op(tmpRect, Region.Op.UNION);
        mDismissButton.getBoundsOnScreen(tmpRect);
        swipeRegion.op(tmpRect, Region.Op.UNION);

        View messageDismiss = findViewById(R.id.message_dismiss_button);
        if (messageDismiss != null) {
            messageDismiss.getBoundsOnScreen(tmpRect);
            swipeRegion.op(tmpRect, Region.Op.UNION);
        }

        return swipeRegion;
    }

    private Region getTouchRegion(boolean includeScrim) {
        Region touchRegion = getSwipeRegion();

        if (includeScrim && mScrollingScrim.getVisibility() == View.VISIBLE) {
            final Rect tmpRect = new Rect();
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
        mInputMonitor = new InputMonitorCompat("Screenshot", mDefaultDisplay);
        mInputEventReceiver = mInputMonitor.getInputReceiver(
                Looper.getMainLooper(), Choreographer.getInstance(), ev -> {
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

    void stopInputListening() {
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
    }

    @Override // View
    protected void onFinishInflate() {
        super.onFinishInflate();
        mScrollingScrim = requireNonNull(findViewById(R.id.screenshot_scrolling_scrim));
        mScreenshotStatic = requireNonNull(findViewById(R.id.screenshot_static));
        mScreenshotPreview = requireNonNull(findViewById(R.id.screenshot_preview));

        mScreenshotPreviewBorder = requireNonNull(
                findViewById(R.id.screenshot_preview_border));
        mScreenshotPreview.setClipToOutline(true);
        mScreenshotBadge = requireNonNull(findViewById(R.id.screenshot_badge));

        mActionsContainerBackground = requireNonNull(findViewById(
                R.id.actions_container_background));
        mActionsContainer = requireNonNull(findViewById(R.id.actions_container));
        mActionsView = requireNonNull(findViewById(R.id.screenshot_actions));
        mDismissButton = requireNonNull(findViewById(R.id.screenshot_dismiss_button));
        mScrollablePreview = requireNonNull(findViewById(R.id.screenshot_scrollable_preview));
        mScreenshotFlash = requireNonNull(findViewById(R.id.screenshot_flash));
        mShareChip = requireNonNull(mActionsContainer.findViewById(R.id.screenshot_share_chip));
        mEditChip = requireNonNull(mActionsContainer.findViewById(R.id.screenshot_edit_chip));
        mScrollChip = requireNonNull(mActionsContainer.findViewById(R.id.screenshot_scroll_chip));

        int swipePaddingPx = (int) FloatingWindowUtil.dpToPx(mDisplayMetrics, SWIPE_PADDING_DP);
        TouchDelegate previewDelegate = new TouchDelegate(
                new Rect(swipePaddingPx, swipePaddingPx, swipePaddingPx, swipePaddingPx),
                mScreenshotPreview);
        mScreenshotPreview.setTouchDelegate(previewDelegate);
        TouchDelegate actionsDelegate = new TouchDelegate(
                new Rect(swipePaddingPx, swipePaddingPx, swipePaddingPx, swipePaddingPx),
                mActionsContainerBackground);
        mActionsContainerBackground.setTouchDelegate(actionsDelegate);

        setFocusable(true);
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

        mScreenshotStatic.setCallbacks(new DraggableConstraintLayout.SwipeDismissCallbacks() {
            @Override
            public void onInteraction() {
                mCallbacks.onUserInteraction();
            }

            @Override
            public void onSwipeDismissInitiated(Animator animator) {
                if (DEBUG_DISMISS) {
                    Log.d(ScreenshotView.TAG, "dismiss triggered via swipe gesture");
                }
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SWIPE_DISMISSED, 0,
                        mPackageName);
            }

            @Override
            public void onDismissComplete() {
                if (mInteractionJankMonitor.isInstrumenting(CUJ_TAKE_SCREENSHOT)) {
                    mInteractionJankMonitor.end(CUJ_TAKE_SCREENSHOT);
                }
                mCallbacks.onDismiss();
            }
        });
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
    void init(UiEventLogger uiEventLogger, ScreenshotViewCallback callbacks,
            ActionIntentExecutor actionExecutor, FeatureFlags flags) {
        mUiEventLogger = uiEventLogger;
        mCallbacks = callbacks;
        mActionExecutor = actionExecutor;
        mFlags = flags;
    }

    void setScreenshot(Bitmap bitmap, Insets screenInsets) {
        mScreenshotPreview.setImageDrawable(createScreenDrawable(mResources, bitmap, screenInsets));
    }

    void setScreenshot(ScreenshotData screenshot) {
        mScreenshotData = screenshot;
        setScreenshot(screenshot.getBitmap(), screenshot.getInsets());
        mScreenshotPreview.setImageDrawable(createScreenDrawable(mResources, screenshot.getBitmap(),
                screenshot.getInsets()));
    }

    void setPackageName(String packageName) {
        mPackageName = packageName;
    }

    void setDefaultDisplay(int displayId) {
        mDefaultDisplay = displayId;
    }

    void updateInsets(WindowInsets insets) {
        int orientation = mContext.getResources().getConfiguration().orientation;
        mOrientationPortrait = (orientation == ORIENTATION_PORTRAIT);
        FrameLayout.LayoutParams p =
                (FrameLayout.LayoutParams) mScreenshotStatic.getLayoutParams();
        DisplayCutout cutout = insets.getDisplayCutout();
        Insets navBarInsets = insets.getInsets(WindowInsets.Type.navigationBars());
        if (cutout == null) {
            p.setMargins(0, 0, 0, navBarInsets.bottom);
        } else {
            Insets waterfall = cutout.getWaterfallInsets();
            if (mOrientationPortrait) {
                p.setMargins(
                        waterfall.left,
                        Math.max(cutout.getSafeInsetTop(), waterfall.top),
                        waterfall.right,
                        Math.max(cutout.getSafeInsetBottom(),
                                Math.max(navBarInsets.bottom, waterfall.bottom)));
            } else {
                p.setMargins(
                        Math.max(cutout.getSafeInsetLeft(), waterfall.left),
                        waterfall.top,
                        Math.max(cutout.getSafeInsetRight(), waterfall.right),
                        Math.max(navBarInsets.bottom, waterfall.bottom));
            }
        }
        mScreenshotStatic.setLayoutParams(p);
        mScreenshotStatic.requestLayout();
    }

    void updateOrientation(WindowInsets insets) {
        int orientation = mContext.getResources().getConfiguration().orientation;
        mOrientationPortrait = (orientation == ORIENTATION_PORTRAIT);
        updateInsets(insets);
        ViewGroup.LayoutParams params = mScreenshotPreview.getLayoutParams();
        if (mOrientationPortrait) {
            params.width = (int) mFixedSize;
            params.height = LayoutParams.WRAP_CONTENT;
            mScreenshotPreview.setScaleType(ImageView.ScaleType.FIT_START);
        } else {
            params.width = LayoutParams.WRAP_CONTENT;
            params.height = (int) mFixedSize;
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
                mFixedSize / (mOrientationPortrait ? bounds.width() : bounds.height());
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
        borderFadeIn.addUpdateListener((animation) -> {
            float borderAlpha = animation.getAnimatedFraction();
            mScreenshotPreviewBorder.setAlpha(borderAlpha);
            mScreenshotBadge.setAlpha(borderAlpha);
        });

        if (showFlash) {
            dropInAnimation.play(flashOutAnimator).after(flashInAnimator);
            dropInAnimation.play(flashOutAnimator).with(toCorner);
        } else {
            dropInAnimation.play(toCorner);
        }
        dropInAnimation.play(borderFadeIn).after(toCorner);

        dropInAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                mInteractionJankMonitor.cancel(CUJ_TAKE_SCREENSHOT);
            }

            @Override
            public void onAnimationStart(Animator animation) {
                InteractionJankMonitor.Configuration.Builder builder =
                        InteractionJankMonitor.Configuration.Builder.withView(
                                        CUJ_TAKE_SCREENSHOT, mScreenshotPreview)
                                .setTag("DropIn");
                mInteractionJankMonitor.begin(builder);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (DEBUG_ANIM) {
                    Log.d(TAG, "drop-in animation ended");
                }
                mDismissButton.setOnClickListener(view -> {
                    if (DEBUG_INPUT) {
                        Log.d(TAG, "dismiss button clicked");
                    }
                    mUiEventLogger.log(
                            ScreenshotEvent.SCREENSHOT_EXPLICIT_DISMISSAL, 0, mPackageName);
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
                mInteractionJankMonitor.end(CUJ_TAKE_SCREENSHOT);
                createScreenshotActionsShadeAnimation().start();
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

        ArrayList<OverlayActionChip> chips = new ArrayList<>();

        mShareChip.setContentDescription(mContext.getString(R.string.screenshot_share_description));
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

        mEditChip.setContentDescription(
                mContext.getString(R.string.screenshot_edit_description));
        mEditChip.setIcon(Icon.createWithResource(mContext, R.drawable.ic_screenshot_edit),
                true);
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

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                mInteractionJankMonitor.cancel(CUJ_TAKE_SCREENSHOT);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mInteractionJankMonitor.end(CUJ_TAKE_SCREENSHOT);
            }

            @Override
            public void onAnimationStart(Animator animation) {
                InteractionJankMonitor.Configuration.Builder builder =
                        InteractionJankMonitor.Configuration.Builder.withView(
                                        CUJ_TAKE_SCREENSHOT, mScreenshotStatic)
                                .setTag("Actions")
                                .setTimeout(mDefaultTimeoutOfTimeoutHandler);
                mInteractionJankMonitor.begin(builder);
            }
        });

        animator.addUpdateListener(animation -> {
            float t = animation.getAnimatedFraction();
            float containerAlpha = t < alphaFraction ? t / alphaFraction : 1;
            mActionsContainer.setAlpha(containerAlpha);
            mActionsContainerBackground.setAlpha(containerAlpha);
            float containerScale = SCREENSHOT_ACTIONS_START_SCALE_X
                    + (t * (1 - SCREENSHOT_ACTIONS_START_SCALE_X));
            mActionsContainer.setScaleX(containerScale);
            mActionsContainerBackground.setScaleX(containerScale);
            for (OverlayActionChip chip : chips) {
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

    void badgeScreenshot(Drawable badge) {
        mScreenshotBadge.setImageDrawable(badge);
        mScreenshotBadge.setVisibility(badge != null ? View.VISIBLE : View.GONE);
    }

    void setChipIntents(ScreenshotController.SavedImageData imageData) {
        mShareChip.setOnClickListener(v -> {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SHARE_TAPPED, 0, mPackageName);
            if (mFlags.isEnabled(Flags.SCREENSHOT_WORK_PROFILE_POLICY)) {
                prepareSharedTransition();

                Intent shareIntent;
                if (mFlags.isEnabled(Flags.SCREENSHOT_METADATA) && mScreenshotData != null
                        && mScreenshotData.getContextUrl() != null) {
                    shareIntent = ActionIntentCreator.INSTANCE.createShareIntentWithExtraText(
                            imageData.uri, mScreenshotData.getContextUrl().toString());
                } else {
                    shareIntent = ActionIntentCreator.INSTANCE.createShareIntentWithSubject(
                            imageData.uri, imageData.subject);
                }
                mActionExecutor.launchIntentAsync(shareIntent,
                        imageData.shareTransition.get().bundle,
                        imageData.owner.getIdentifier(), false);
            } else {
                startSharedTransition(imageData.shareTransition.get());
            }
        });
        mEditChip.setOnClickListener(v -> {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_EDIT_TAPPED, 0, mPackageName);
            if (mFlags.isEnabled(Flags.SCREENSHOT_WORK_PROFILE_POLICY)) {
                prepareSharedTransition();
                mActionExecutor.launchIntentAsync(
                        ActionIntentCreator.INSTANCE.createEditIntent(imageData.uri, mContext),
                        imageData.editTransition.get().bundle,
                        imageData.owner.getIdentifier(), true);
            } else {
                startSharedTransition(imageData.editTransition.get());
            }
        });
        mScreenshotPreview.setOnClickListener(v -> {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_PREVIEW_TAPPED, 0, mPackageName);
            if (mFlags.isEnabled(Flags.SCREENSHOT_WORK_PROFILE_POLICY)) {
                prepareSharedTransition();
                mActionExecutor.launchIntentAsync(
                        ActionIntentCreator.INSTANCE.createEditIntent(imageData.uri, mContext),
                        imageData.editTransition.get().bundle,
                        imageData.owner.getIdentifier(), true);
            } else {
                startSharedTransition(
                        imageData.editTransition.get());
            }
        });
        if (mQuickShareChip != null) {
            if (imageData.quickShareAction != null) {
                mQuickShareChip.setPendingIntent(imageData.quickShareAction.actionIntent,
                        () -> {
                            mUiEventLogger.log(
                                    ScreenshotEvent.SCREENSHOT_SMART_ACTION_TAPPED, 0,
                                    mPackageName);
                            animateDismissal();
                        });
            } else {
                // hide chip and unset pending interaction if necessary, since we don't actually
                // have a useable quick share intent
                Log.wtf(TAG, "Showed quick share chip, but quick share intent was null");
                if (mPendingInteraction == PendingInteraction.QUICK_SHARE) {
                    mPendingInteraction = null;
                }
                mQuickShareChip.setVisibility(GONE);
            }
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
                OverlayActionChip actionChip = (OverlayActionChip) inflater.inflate(
                        R.layout.overlay_action_chip, mActionsView, false);
                actionChip.setText(smartAction.title);
                actionChip.setIcon(smartAction.getIcon(), false);
                actionChip.setPendingIntent(smartAction.actionIntent,
                        () -> {
                            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SMART_ACTION_TAPPED,
                                    0, mPackageName);
                            animateDismissal();
                        });
                actionChip.setAlpha(1);
                mActionsView.addView(actionChip, mActionsView.getChildCount() - 1);
                mSmartChips.add(actionChip);
            }
        }
    }

    void addQuickShareChip(Notification.Action quickShareAction) {
        if (mQuickShareChip != null) {
            mSmartChips.remove(mQuickShareChip);
            mActionsView.removeView(mQuickShareChip);
        }
        if (mPendingInteraction == PendingInteraction.QUICK_SHARE) {
            mPendingInteraction = null;
        }
        if (mPendingInteraction == null) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            mQuickShareChip = (OverlayActionChip) inflater.inflate(
                    R.layout.overlay_action_chip, mActionsView, false);
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
        mPendingSharedTransition = true;
        AnimatorSet animSet = new AnimatorSet();

        ValueAnimator scrimAnim = ValueAnimator.ofFloat(0, 1);
        scrimAnim.addUpdateListener(animation ->
                mScrollingScrim.setAlpha(1 - animation.getAnimatedFraction()));

        if (mShowScrollablePreview) {
            mScrollablePreview.setImageBitmap(longScreenshot.toBitmap());
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
                    longScreenshot.getLeft() * currentScale,
                    longScreenshot.getTop() * currentScale);
            mScrollablePreview.setImageMatrix(matrix);
            float destinationScale = destination.width() / (float) mScrollablePreview.getWidth();

            ValueAnimator previewAnim = ValueAnimator.ofFloat(0, 1);
            previewAnim.addUpdateListener(animation -> {
                float t = animation.getAnimatedFraction();
                float currScale = MathUtils.lerp(1, destinationScale, t);
                mScrollablePreview.setScaleX(currScale);
                mScrollablePreview.setScaleY(currScale);
                mScrollablePreview.setX(MathUtils.lerp(startX, destination.left, t));
                mScrollablePreview.setY(MathUtils.lerp(startY, destination.top, t));
            });
            ValueAnimator previewFadeAnim = ValueAnimator.ofFloat(1, 0);
            previewFadeAnim.addUpdateListener(animation ->
                    mScrollablePreview.setAlpha(1 - animation.getAnimatedFraction()));
            animSet.play(previewAnim).with(scrimAnim).before(previewFadeAnim);
            previewAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    onTransitionEnd.run();
                }
            });
        } else {
            // if we switched orientations between the original screenshot and the long screenshot
            // capture, just fade out the scrim instead of running the preview animation
            animSet.play(scrimAnim);
            animSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    onTransitionEnd.run();
                }
            });
        }
        animSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mCallbacks.onDismiss();
            }
        });
        animSet.start();
    }

    void prepareScrollingTransition(ScrollCaptureResponse response, Bitmap screenBitmap,
            Bitmap newBitmap, boolean screenshotTakenInPortrait) {
        mShowScrollablePreview = (screenshotTakenInPortrait == mOrientationPortrait);

        mScrollingScrim.setImageBitmap(newBitmap);
        mScrollingScrim.setVisibility(View.VISIBLE);

        if (mShowScrollablePreview) {
            Rect scrollableArea = scrollableAreaOnScreen(response);

            float scale = mFixedSize
                    / (mOrientationPortrait ? screenBitmap.getWidth() : screenBitmap.getHeight());
            ConstraintLayout.LayoutParams params =
                    (ConstraintLayout.LayoutParams) mScrollablePreview.getLayoutParams();

            params.width = (int) (scale * scrollableArea.width());
            params.height = (int) (scale * scrollableArea.height());
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(-scrollableArea.left * scale, -scrollableArea.top * scale);

            mScrollablePreview.setTranslationX(scale
                    * (mDirectionLTR ? scrollableArea.left : scrollableArea.right - getWidth()));
            mScrollablePreview.setTranslationY(scale * scrollableArea.top);
            mScrollablePreview.setImageMatrix(matrix);
            mScrollablePreview.setImageBitmap(screenBitmap);
            mScrollablePreview.setVisibility(View.VISIBLE);
        }
        mDismissButton.setVisibility(View.GONE);
        mActionsContainer.setVisibility(View.GONE);
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
        mActionsContainerBackground.setVisibility(View.VISIBLE);
        mScreenshotPreviewBorder.setVisibility(View.VISIBLE);
        mScreenshotPreview.setVisibility(View.VISIBLE);
        // reset the timeout
        mCallbacks.onUserInteraction();
    }

    boolean isDismissing() {
        return mScreenshotStatic.isDismissing();
    }

    boolean isPendingSharedTransition() {
        return mPendingSharedTransition;
    }

    void animateDismissal() {
        mScreenshotStatic.dismiss();
    }

    void reset() {
        if (DEBUG_UI) {
            Log.d(TAG, "reset screenshot view");
        }
        mScreenshotStatic.cancelDismissal();
        if (DEBUG_WINDOW) {
            Log.d(TAG, "removing OnComputeInternalInsetsListener");
        }
        // Make sure we clean up the view tree observer
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        // Clear any references to the bitmap
        mScreenshotPreview.setImageDrawable(null);
        mScreenshotPreview.setVisibility(View.INVISIBLE);
        mScreenshotPreview.setAlpha(1f);
        mScreenshotPreviewBorder.setAlpha(0);
        mScreenshotBadge.setAlpha(0f);
        mScreenshotBadge.setVisibility(View.GONE);
        mScreenshotBadge.setImageDrawable(null);
        mPendingSharedTransition = false;
        mActionsContainerBackground.setVisibility(View.INVISIBLE);
        mActionsContainer.setVisibility(View.GONE);
        mDismissButton.setVisibility(View.GONE);
        mScrollingScrim.setVisibility(View.GONE);
        mScrollablePreview.setVisibility(View.GONE);
        mScreenshotStatic.setTranslationX(0);
        mScreenshotPreview.setContentDescription(
                mContext.getResources().getString(R.string.screenshot_preview_description));
        mScreenshotPreview.setOnClickListener(null);
        mShareChip.setOnClickListener(null);
        mScrollingScrim.setVisibility(View.GONE);
        mEditChip.setOnClickListener(null);
        mShareChip.setIsPending(false);
        mEditChip.setIsPending(false);
        mPendingInteraction = null;
        for (OverlayActionChip chip : mSmartChips) {
            mActionsView.removeView(chip);
        }
        mSmartChips.clear();
        mQuickShareChip = null;
        setAlpha(1);
        mScreenshotStatic.setAlpha(1);
        mScreenshotData = null;
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

    private void prepareSharedTransition() {
        mPendingSharedTransition = true;
        // fade out non-preview UI
        createScreenshotFadeDismissAnimation().start();
    }

    ValueAnimator createScreenshotFadeDismissAnimation() {
        ValueAnimator alphaAnim = ValueAnimator.ofFloat(0, 1);
        alphaAnim.addUpdateListener(animation -> {
            float alpha = 1 - animation.getAnimatedFraction();
            mDismissButton.setAlpha(alpha);
            mActionsContainerBackground.setAlpha(alpha);
            mActionsContainer.setAlpha(alpha);
            mScreenshotPreviewBorder.setAlpha(alpha);
            mScreenshotBadge.setAlpha(alpha);
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
}
