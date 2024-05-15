/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.systemui.Flags.screenshotShelfUi2;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.android.systemui.res.R;
import com.android.systemui.screenshot.DraggableConstraintLayout;
import com.android.systemui.screenshot.FloatingWindowUtil;
import com.android.systemui.screenshot.OverlayActionChip;
import com.android.systemui.screenshot.ui.binder.ActionButtonViewBinder;
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonAppearance;
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonViewModel;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import java.util.ArrayList;

/**
 * Handles the visual elements and animations for the clipboard overlay.
 */
public class ClipboardOverlayView extends DraggableConstraintLayout {

    interface ClipboardOverlayCallbacks extends SwipeDismissCallbacks {
        void onDismissButtonTapped();

        void onRemoteCopyButtonTapped();

        void onShareButtonTapped();

        void onPreviewTapped();

        void onMinimizedViewTapped();
    }

    private static final String TAG = "ClipboardView";

    private static final int SWIPE_PADDING_DP = 12; // extra padding around views to allow swipe
    private static final int FONT_SEARCH_STEP_PX = 4;

    private final DisplayMetrics mDisplayMetrics;
    private final AccessibilityManager mAccessibilityManager;
    private final ArrayList<View> mActionChips = new ArrayList<>();

    private View mClipboardPreview;
    private ImageView mImagePreview;
    private TextView mTextPreview;
    private TextView mHiddenPreview;
    private LinearLayout mMinimizedPreview;
    private View mPreviewBorder;
    private View mShareChip;
    private View mRemoteCopyChip;
    private View mActionContainerBackground;
    private View mDismissButton;
    private LinearLayout mActionContainer;
    private ClipboardOverlayCallbacks mClipboardCallbacks;
    private ActionButtonViewBinder mActionButtonViewBinder = new ActionButtonViewBinder();

    public ClipboardOverlayView(Context context) {
        this(context, null);
    }

    public ClipboardOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ClipboardOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDisplayMetrics = new DisplayMetrics();
        mContext.getDisplay().getRealMetrics(mDisplayMetrics);
        mAccessibilityManager = AccessibilityManager.getInstance(mContext);
    }

    @Override
    protected void onFinishInflate() {
        mActionContainerBackground = requireViewById(R.id.actions_container_background);
        mActionContainer = requireViewById(R.id.actions);
        mClipboardPreview = requireViewById(R.id.clipboard_preview);
        mPreviewBorder = requireViewById(R.id.preview_border);
        mImagePreview = requireViewById(R.id.image_preview);
        mTextPreview = requireViewById(R.id.text_preview);
        mHiddenPreview = requireViewById(R.id.hidden_preview);
        mMinimizedPreview = requireViewById(R.id.minimized_preview);
        mShareChip = requireViewById(R.id.share_chip);
        mRemoteCopyChip = requireViewById(R.id.remote_copy_chip);
        mDismissButton = requireViewById(R.id.dismiss_button);

        bindDefaultActionChips();

        mTextPreview.getViewTreeObserver().addOnPreDrawListener(() -> {
            int availableHeight = mTextPreview.getHeight()
                    - (mTextPreview.getPaddingTop() + mTextPreview.getPaddingBottom());
            mTextPreview.setMaxLines(Math.max(availableHeight / mTextPreview.getLineHeight(), 1));
            return true;
        });
        super.onFinishInflate();
    }

    private void bindDefaultActionChips() {
        if (screenshotShelfUi2()) {
            mActionButtonViewBinder.bind(mRemoteCopyChip,
                    ActionButtonViewModel.Companion.withNextId(
                            new ActionButtonAppearance(
                                    Icon.createWithResource(mContext,
                                            R.drawable.ic_baseline_devices_24).loadDrawable(
                                            mContext),
                                    null,
                                    mContext.getString(R.string.clipboard_send_nearby_description)),
                            new Function0<>() {
                                @Override
                                public Unit invoke() {
                                    if (mClipboardCallbacks != null) {
                                        mClipboardCallbacks.onRemoteCopyButtonTapped();
                                    }
                                    return null;
                                }
                            }));
            mActionButtonViewBinder.bind(mShareChip,
                    ActionButtonViewModel.Companion.withNextId(
                            new ActionButtonAppearance(
                                    Icon.createWithResource(mContext,
                                            R.drawable.ic_screenshot_share).loadDrawable(mContext),
                                    null, mContext.getString(com.android.internal.R.string.share)),
                            new Function0<>() {
                                @Override
                                public Unit invoke() {
                                    if (mClipboardCallbacks != null) {
                                        mClipboardCallbacks.onShareButtonTapped();
                                    }
                                    return null;
                                }
                            }));
        } else {
            mShareChip.setAlpha(1);
            mRemoteCopyChip.setAlpha(1);

            ((ImageView) mRemoteCopyChip.findViewById(R.id.overlay_action_chip_icon)).setImageIcon(
                    Icon.createWithResource(mContext, R.drawable.ic_baseline_devices_24));
            ((ImageView) mShareChip.findViewById(R.id.overlay_action_chip_icon)).setImageIcon(
                    Icon.createWithResource(mContext, R.drawable.ic_screenshot_share));

            mShareChip.setContentDescription(
                    mContext.getString(com.android.internal.R.string.share));
            mRemoteCopyChip.setContentDescription(
                    mContext.getString(R.string.clipboard_send_nearby_description));
        }
    }

    @Override
    public void setCallbacks(SwipeDismissCallbacks callbacks) {
        super.setCallbacks(callbacks);
        ClipboardOverlayCallbacks clipboardCallbacks = (ClipboardOverlayCallbacks) callbacks;
        if (!screenshotShelfUi2()) {
            mShareChip.setOnClickListener(v -> clipboardCallbacks.onShareButtonTapped());
            mRemoteCopyChip.setOnClickListener(v -> clipboardCallbacks.onRemoteCopyButtonTapped());
        }
        mDismissButton.setOnClickListener(v -> clipboardCallbacks.onDismissButtonTapped());
        mClipboardPreview.setOnClickListener(v -> clipboardCallbacks.onPreviewTapped());
        mMinimizedPreview.setOnClickListener(v -> clipboardCallbacks.onMinimizedViewTapped());
        mClipboardCallbacks = clipboardCallbacks;
    }

    void setEditAccessibilityAction(boolean editable) {
        if (editable) {
            ViewCompat.replaceAccessibilityAction(mClipboardPreview,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    mContext.getString(R.string.clipboard_edit), null);
        } else {
            ViewCompat.replaceAccessibilityAction(mClipboardPreview,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    null, null);
        }
    }

    void setMinimized(boolean minimized) {
        if (minimized) {
            mMinimizedPreview.setVisibility(View.VISIBLE);
            mClipboardPreview.setVisibility(View.GONE);
            mPreviewBorder.setVisibility(View.GONE);
            mActionContainer.setVisibility(View.GONE);
            mActionContainerBackground.setVisibility(View.GONE);
        } else {
            mMinimizedPreview.setVisibility(View.GONE);
            mClipboardPreview.setVisibility(View.VISIBLE);
            mPreviewBorder.setVisibility(View.VISIBLE);
            mActionContainer.setVisibility(View.VISIBLE);
        }
    }

    void setInsets(WindowInsets insets, int orientation) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) getLayoutParams();
        if (p == null) {
            return;
        }
        Rect margins = computeMargins(insets, orientation);

        p.setMargins(margins.left, margins.top, margins.right, margins.bottom);
        setLayoutParams(p);
        requestLayout();
    }

    boolean isInTouchRegion(int x, int y) {
        Region touchRegion = new Region();
        final Rect tmpRect = new Rect();

        mPreviewBorder.getBoundsOnScreen(tmpRect);
        tmpRect.inset(
                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP),
                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP));
        touchRegion.op(tmpRect, Region.Op.UNION);

        mActionContainerBackground.getBoundsOnScreen(tmpRect);
        tmpRect.inset(
                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP),
                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP));
        touchRegion.op(tmpRect, Region.Op.UNION);

        mMinimizedPreview.getBoundsOnScreen(tmpRect);
        tmpRect.inset(
                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP),
                (int) FloatingWindowUtil.dpToPx(mDisplayMetrics, -SWIPE_PADDING_DP));
        touchRegion.op(tmpRect, Region.Op.UNION);

        mDismissButton.getBoundsOnScreen(tmpRect);
        touchRegion.op(tmpRect, Region.Op.UNION);

        return touchRegion.contains(x, y);
    }

    void setRemoteCopyVisibility(boolean visible) {
        if (visible) {
            mRemoteCopyChip.setVisibility(View.VISIBLE);
            mActionContainerBackground.setVisibility(View.VISIBLE);
        } else {
            mRemoteCopyChip.setVisibility(View.GONE);
        }
    }

    void showDefaultTextPreview() {
        String copied = mContext.getString(R.string.clipboard_overlay_text_copied);
        showTextPreview(copied, false);
    }

    void showTextPreview(CharSequence text, boolean hidden) {
        TextView textView = hidden ? mHiddenPreview : mTextPreview;
        showSinglePreview(textView);
        textView.setText(text.subSequence(0, Math.min(500, text.length())));
        updateTextSize(text, textView);
        textView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (right - left != oldRight - oldLeft) {
                        updateTextSize(text, textView);
                    }
                });
    }

    View getPreview() {
        return mClipboardPreview;
    }

    void showImagePreview(@Nullable Bitmap thumbnail) {
        if (thumbnail == null) {
            mHiddenPreview.setText(mContext.getString(R.string.clipboard_text_hidden));
            showSinglePreview(mHiddenPreview);
        } else {
            mImagePreview.setImageBitmap(thumbnail);
            showSinglePreview(mImagePreview);
        }
    }

    void showShareChip() {
        mShareChip.setVisibility(View.VISIBLE);
        mActionContainerBackground.setVisibility(View.VISIBLE);
    }

    void reset() {
        setTranslationX(0);
        setAlpha(0);
        mActionContainerBackground.setVisibility(View.GONE);
        mDismissButton.setVisibility(View.GONE);
        mShareChip.setVisibility(View.GONE);
        mRemoteCopyChip.setVisibility(View.GONE);
        setEditAccessibilityAction(false);
        resetActionChips();
    }

    void resetActionChips() {
        for (View chip : mActionChips) {
            mActionContainer.removeView(chip);
        }
        mActionChips.clear();
    }

    Animator getMinimizedFadeoutAnimation() {
        ObjectAnimator anim = ObjectAnimator.ofFloat(mMinimizedPreview, "alpha", 1, 0);
        anim.setDuration(66);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mMinimizedPreview.setVisibility(View.GONE);
                mMinimizedPreview.setAlpha(1);
            }
        });
        return anim;
    }

    Animator getEnterAnimation() {
        if (mAccessibilityManager.isEnabled()) {
            mDismissButton.setVisibility(View.VISIBLE);
        }
        TimeInterpolator linearInterpolator = new LinearInterpolator();
        TimeInterpolator scaleInterpolator = new PathInterpolator(0, 0, 0, 1f);
        AnimatorSet enterAnim = new AnimatorSet();

        ValueAnimator rootAnim = ValueAnimator.ofFloat(0, 1);
        rootAnim.setInterpolator(linearInterpolator);
        rootAnim.setDuration(66);
        rootAnim.addUpdateListener(animation -> {
            setAlpha(animation.getAnimatedFraction());
        });

        ValueAnimator scaleAnim = ValueAnimator.ofFloat(0, 1);
        scaleAnim.setInterpolator(scaleInterpolator);
        scaleAnim.setDuration(333);
        scaleAnim.addUpdateListener(animation -> {
            float previewScale = MathUtils.lerp(.9f, 1f, animation.getAnimatedFraction());
            mMinimizedPreview.setScaleX(previewScale);
            mMinimizedPreview.setScaleY(previewScale);
            mClipboardPreview.setScaleX(previewScale);
            mClipboardPreview.setScaleY(previewScale);
            mPreviewBorder.setScaleX(previewScale);
            mPreviewBorder.setScaleY(previewScale);

            float pivotX = mClipboardPreview.getWidth() / 2f + mClipboardPreview.getX();
            mActionContainerBackground.setPivotX(pivotX - mActionContainerBackground.getX());
            mActionContainer.setPivotX(pivotX - ((View) mActionContainer.getParent()).getX());
            float actionsScaleX = MathUtils.lerp(.7f, 1f, animation.getAnimatedFraction());
            float actionsScaleY = MathUtils.lerp(.9f, 1f, animation.getAnimatedFraction());
            mActionContainer.setScaleX(actionsScaleX);
            mActionContainer.setScaleY(actionsScaleY);
            mActionContainerBackground.setScaleX(actionsScaleX);
            mActionContainerBackground.setScaleY(actionsScaleY);
        });

        ValueAnimator alphaAnim = ValueAnimator.ofFloat(0, 1);
        alphaAnim.setInterpolator(linearInterpolator);
        alphaAnim.setDuration(283);
        alphaAnim.addUpdateListener(animation -> {
            float alpha = animation.getAnimatedFraction();
            mMinimizedPreview.setAlpha(alpha);
            mClipboardPreview.setAlpha(alpha);
            mPreviewBorder.setAlpha(alpha);
            mDismissButton.setAlpha(alpha);
            mActionContainer.setAlpha(alpha);
        });

        mMinimizedPreview.setAlpha(0);
        mActionContainer.setAlpha(0);
        mPreviewBorder.setAlpha(0);
        mClipboardPreview.setAlpha(0);
        enterAnim.play(rootAnim).with(scaleAnim);
        enterAnim.play(alphaAnim).after(50).after(rootAnim);

        enterAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                setAlpha(1);
            }
        });
        return enterAnim;
    }

    Animator getFadeOutAnimation() {
        ValueAnimator alphaAnim = ValueAnimator.ofFloat(1, 0);
        alphaAnim.addUpdateListener(animation -> {
            float alpha = (float) animation.getAnimatedValue();
            mActionContainer.setAlpha(alpha);
            mActionContainerBackground.setAlpha(alpha);
            mPreviewBorder.setAlpha(alpha);
            mDismissButton.setAlpha(alpha);
        });
        alphaAnim.setDuration(300);
        return alphaAnim;
    }

    Animator getExitAnimation() {
        TimeInterpolator linearInterpolator = new LinearInterpolator();
        TimeInterpolator scaleInterpolator = new PathInterpolator(.3f, 0, 1f, 1f);
        AnimatorSet exitAnim = new AnimatorSet();

        ValueAnimator rootAnim = ValueAnimator.ofFloat(0, 1);
        rootAnim.setInterpolator(linearInterpolator);
        rootAnim.setDuration(100);
        rootAnim.addUpdateListener(anim -> setAlpha(1 - anim.getAnimatedFraction()));

        ValueAnimator scaleAnim = ValueAnimator.ofFloat(0, 1);
        scaleAnim.setInterpolator(scaleInterpolator);
        scaleAnim.setDuration(250);
        scaleAnim.addUpdateListener(animation -> {
            float previewScale = MathUtils.lerp(1f, .9f, animation.getAnimatedFraction());
            mMinimizedPreview.setScaleX(previewScale);
            mMinimizedPreview.setScaleY(previewScale);
            mClipboardPreview.setScaleX(previewScale);
            mClipboardPreview.setScaleY(previewScale);
            mPreviewBorder.setScaleX(previewScale);
            mPreviewBorder.setScaleY(previewScale);

            float pivotX = mClipboardPreview.getWidth() / 2f + mClipboardPreview.getX();
            mActionContainerBackground.setPivotX(pivotX - mActionContainerBackground.getX());
            mActionContainer.setPivotX(pivotX - ((View) mActionContainer.getParent()).getX());
            float actionScaleX = MathUtils.lerp(1f, .8f, animation.getAnimatedFraction());
            float actionScaleY = MathUtils.lerp(1f, .9f, animation.getAnimatedFraction());
            mActionContainer.setScaleX(actionScaleX);
            mActionContainer.setScaleY(actionScaleY);
            mActionContainerBackground.setScaleX(actionScaleX);
            mActionContainerBackground.setScaleY(actionScaleY);
        });

        ValueAnimator alphaAnim = ValueAnimator.ofFloat(0, 1);
        alphaAnim.setInterpolator(linearInterpolator);
        alphaAnim.setDuration(166);
        alphaAnim.addUpdateListener(animation -> {
            float alpha = 1 - animation.getAnimatedFraction();
            mMinimizedPreview.setAlpha(alpha);
            mClipboardPreview.setAlpha(alpha);
            mPreviewBorder.setAlpha(alpha);
            mDismissButton.setAlpha(alpha);
            mActionContainer.setAlpha(alpha);
        });

        exitAnim.play(alphaAnim).with(scaleAnim);
        exitAnim.play(rootAnim).after(150).after(alphaAnim);
        return exitAnim;
    }

    void setActionChip(RemoteAction action, Runnable onFinish) {
        mActionContainerBackground.setVisibility(View.VISIBLE);
        View chip;
        if (screenshotShelfUi2()) {
            chip = constructShelfActionChip(action, onFinish);
        } else {
            chip = constructActionChip(action, onFinish);
        }
        mActionContainer.addView(chip);
        mActionChips.add(chip);
    }

    private void showSinglePreview(View v) {
        mTextPreview.setVisibility(View.GONE);
        mImagePreview.setVisibility(View.GONE);
        mHiddenPreview.setVisibility(View.GONE);
        mMinimizedPreview.setVisibility(View.GONE);
        v.setVisibility(View.VISIBLE);
    }

    private View constructShelfActionChip(RemoteAction action, Runnable onFinish) {
        View chip = LayoutInflater.from(mContext).inflate(
                R.layout.shelf_action_chip, mActionContainer, false);
        mActionButtonViewBinder.bind(chip, ActionButtonViewModel.Companion.withNextId(
                new ActionButtonAppearance(action.getIcon().loadDrawable(mContext),
                        action.getTitle(), action.getTitle()), new Function0<>() {
                    @Override
                    public Unit invoke() {
                        try {
                            action.getActionIntent().send();
                            onFinish.run();
                        } catch (PendingIntent.CanceledException e) {
                            Log.e(TAG, "Failed to send intent");
                        }
                        return null;
                    }
                }));

        return chip;
    }

    private OverlayActionChip constructActionChip(RemoteAction action, Runnable onFinish) {
        OverlayActionChip chip = (OverlayActionChip) LayoutInflater.from(mContext).inflate(
                R.layout.overlay_action_chip, mActionContainer, false);
        chip.setText(action.getTitle());
        chip.setContentDescription(action.getTitle());
        chip.setIcon(action.getIcon(), false);
        chip.setPendingIntent(action.getActionIntent(), onFinish);
        chip.setAlpha(1);
        return chip;
    }

    private static void updateTextSize(CharSequence text, TextView textView) {
        Paint paint = new Paint(textView.getPaint());
        Resources res = textView.getResources();
        float minFontSize = res.getDimensionPixelSize(R.dimen.clipboard_overlay_min_font);
        float maxFontSize = res.getDimensionPixelSize(R.dimen.clipboard_overlay_max_font);
        if (isOneWord(text) && fitsInView(text, textView, paint, minFontSize)) {
            // If the text is a single word and would fit within the TextView at the min font size,
            // find the biggest font size that will fit.
            float fontSizePx = minFontSize;
            while (fontSizePx + FONT_SEARCH_STEP_PX < maxFontSize
                    && fitsInView(text, textView, paint, fontSizePx + FONT_SEARCH_STEP_PX)) {
                fontSizePx += FONT_SEARCH_STEP_PX;
            }
            // Need to turn off autosizing, otherwise setTextSize is a no-op.
            textView.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
            // It's possible to hit the max font size and not fill the width, so centering
            // horizontally looks better in this case.
            textView.setGravity(Gravity.CENTER);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, (int) fontSizePx);
        } else {
            // Otherwise just stick with autosize.
            textView.setAutoSizeTextTypeUniformWithConfiguration((int) minFontSize,
                    (int) maxFontSize, FONT_SEARCH_STEP_PX, TypedValue.COMPLEX_UNIT_PX);
            textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        }
    }

    private static boolean fitsInView(CharSequence text, TextView textView, Paint paint,
            float fontSizePx) {
        paint.setTextSize(fontSizePx);
        float size = paint.measureText(text.toString());
        float availableWidth = textView.getWidth() - textView.getPaddingLeft()
                - textView.getPaddingRight();
        return size < availableWidth;
    }

    private static boolean isOneWord(CharSequence text) {
        return text.toString().split("\\s+", 2).length == 1;
    }

    private static Rect computeMargins(WindowInsets insets, int orientation) {
        DisplayCutout cutout = insets.getDisplayCutout();
        Insets navBarInsets = insets.getInsets(WindowInsets.Type.navigationBars());
        Insets imeInsets = insets.getInsets(WindowInsets.Type.ime());
        if (cutout == null) {
            return new Rect(0, 0, 0, Math.max(imeInsets.bottom, navBarInsets.bottom));
        } else {
            Insets waterfall = cutout.getWaterfallInsets();
            if (orientation == ORIENTATION_PORTRAIT) {
                return new Rect(
                        waterfall.left,
                        Math.max(cutout.getSafeInsetTop(), waterfall.top),
                        waterfall.right,
                        Math.max(imeInsets.bottom,
                                Math.max(cutout.getSafeInsetBottom(),
                                        Math.max(navBarInsets.bottom, waterfall.bottom))));
            } else {
                return new Rect(
                        waterfall.left,
                        waterfall.top,
                        waterfall.right,
                        Math.max(imeInsets.bottom,
                                Math.max(navBarInsets.bottom, waterfall.bottom)));
            }
        }
    }
}
