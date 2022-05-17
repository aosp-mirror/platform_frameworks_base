/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.widget;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
import android.text.method.TransformationMethod;
import android.text.method.TranslationTransformationMethod;
import android.util.Log;
import android.view.View;
import android.view.translation.UiTranslationManager;
import android.view.translation.ViewTranslationCallback;
import android.view.translation.ViewTranslationRequest;
import android.view.translation.ViewTranslationResponse;

/**
 * Default implementation for {@link ViewTranslationCallback} for {@link TextView} components.
 * This class handles how to display the translated information for {@link TextView}.
 *
 * @hide
 */
public class TextViewTranslationCallback implements ViewTranslationCallback {

    private static final String TAG = "TextViewTranslationCb";

    private static final boolean DEBUG = Log.isLoggable(UiTranslationManager.LOG_TAG, Log.DEBUG);

    private TranslationTransformationMethod mTranslationTransformation;
    private boolean mIsShowingTranslation = false;
    private boolean mAnimationRunning = false;
    private boolean mIsTextPaddingEnabled = false;
    private CharSequence mPaddedText;
    private int mAnimationDurationMillis = 250; // default value

    private CharSequence mContentDescription;

    private void clearTranslationTransformation() {
        if (DEBUG) {
            Log.v(TAG, "clearTranslationTransformation: " + mTranslationTransformation);
        }
        mTranslationTransformation = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onShowTranslation(@NonNull View view) {
        if (mIsShowingTranslation) {
            if (DEBUG) {
                Log.d(TAG, view + " is already showing translated text.");
            }
            return false;
        }
        ViewTranslationResponse response = view.getViewTranslationResponse();
        if (response == null) {
            Log.e(TAG, "onShowTranslation() shouldn't be called before "
                    + "onViewTranslationResponse().");
            return false;
        }
        // It is possible user changes text and new translation response returns, system should
        // update the translation response to keep the result up to date.
        // Because TextView.setTransformationMethod() will skip the same TransformationMethod
        // instance, we should create a new one to let new translation can work.
        if (mTranslationTransformation == null
                || !response.equals(mTranslationTransformation.getViewTranslationResponse())) {
            TransformationMethod originalTranslationMethod =
                    ((TextView) view).getTransformationMethod();
            mTranslationTransformation = new TranslationTransformationMethod(response,
                    originalTranslationMethod);
        }
        final TransformationMethod transformation = mTranslationTransformation;
        runChangeTextWithAnimationIfNeeded(
                (TextView) view,
                () -> {
                    mIsShowingTranslation = true;
                    mAnimationRunning = false;
                    // TODO(b/178353965): well-handle setTransformationMethod.
                    ((TextView) view).setTransformationMethod(transformation);
                });
        if (response.getKeys().contains(ViewTranslationRequest.ID_CONTENT_DESCRIPTION)) {
            CharSequence translatedContentDescription =
                    response.getValue(ViewTranslationRequest.ID_CONTENT_DESCRIPTION).getText();
            if (!TextUtils.isEmpty(translatedContentDescription)) {
                mContentDescription = view.getContentDescription();
                view.setContentDescription(translatedContentDescription);
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onHideTranslation(@NonNull View view) {
        if (view.getViewTranslationResponse() == null) {
            Log.e(TAG, "onHideTranslation() shouldn't be called before "
                    + "onViewTranslationResponse().");
            return false;
        }
        // Restore to original text content.
        if (mTranslationTransformation != null) {
            final TransformationMethod transformation =
                    mTranslationTransformation.getOriginalTransformationMethod();
            runChangeTextWithAnimationIfNeeded(
                    (TextView) view,
                    () -> {
                        mIsShowingTranslation = false;
                        mAnimationRunning = false;
                        ((TextView) view).setTransformationMethod(transformation);
                    });
            if (!TextUtils.isEmpty(mContentDescription)) {
                view.setContentDescription(mContentDescription);
            }
        } else {
            if (DEBUG) {
                Log.w(TAG, "onHideTranslation(): no translated text.");
            }
            return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onClearTranslation(@NonNull View view) {
        // Restore to original text content and clear TranslationTransformation
        if (mTranslationTransformation != null) {
            onHideTranslation(view);
            clearTranslationTransformation();
            mPaddedText = null;
            mContentDescription = null;
        } else {
            if (DEBUG) {
                Log.w(TAG, "onClearTranslation(): no translated text.");
            }
            return false;
        }
        return true;
    }

    public boolean isShowingTranslation() {
        return mIsShowingTranslation;
    }

    /**
     * Returns whether the view is running animation to show or hide the translation.
     */
    public boolean isAnimationRunning() {
        return mAnimationRunning;
    }

    @Override
    public void enableContentPadding() {
        mIsTextPaddingEnabled = true;
    }

    /**
     * Returns whether readers of the view text should receive padded text for compatibility
     * reasons. The view's original text will be padded to match the length of the translated text.
     */
    boolean isTextPaddingEnabled() {
        return mIsTextPaddingEnabled;
    }

    /**
     * Returns the view's original text with padding added. If the translated text isn't longer than
     * the original text, returns the original text itself.
     *
     * @param text the view's original text
     * @param translatedText the view's translated text
     * @see #isTextPaddingEnabled()
     */
    @Nullable
    CharSequence getPaddedText(CharSequence text, CharSequence translatedText) {
        if (text == null) {
            return null;
        }
        if (mPaddedText == null) {
            mPaddedText = computePaddedText(text, translatedText);
        }
        return mPaddedText;
    }

    @NonNull
    private CharSequence computePaddedText(CharSequence text, CharSequence translatedText) {
        if (translatedText == null) {
            return text;
        }
        int newLength = translatedText.length();
        if (newLength <= text.length()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(newLength);
        sb.append(text);
        for (int i = text.length(); i < newLength; i++) {
            sb.append(COMPAT_PAD_CHARACTER);
        }
        return sb;
    }

    private static final char COMPAT_PAD_CHARACTER = '\u2002';

    @Override
    public void setAnimationDurationMillis(int durationMillis) {
        mAnimationDurationMillis = durationMillis;
    }

    /**
     * Applies a simple text alpha animation when toggling between original and translated text. The
     * text is fully faded out, then swapped to the new text, then the fading is reversed.
     *
     * @param changeTextRunnable the operation to run on the view after the text is faded out, to
     * change to displaying the original or translated text.
     */
    private void runChangeTextWithAnimationIfNeeded(TextView view, Runnable changeTextRunnable) {
        boolean areAnimatorsEnabled = ValueAnimator.areAnimatorsEnabled();
        if (!areAnimatorsEnabled) {
            // The animation is disabled, just change display text
            changeTextRunnable.run();
            return;
        }
        if (mAnimator != null) {
            mAnimator.end();
            // Note: mAnimator is now null; do not use again here.
        }
        mAnimationRunning = true;
        int fadedOutColor = colorWithAlpha(view.getCurrentTextColor(), 0);
        mAnimator = ValueAnimator.ofArgb(view.getCurrentTextColor(), fadedOutColor);
        mAnimator.addUpdateListener(
                // Note that if the text has a ColorStateList, this replaces it with a single color
                // for all states. The original ColorStateList is restored when the animation ends
                // (see below).
                (valueAnimator) -> view.setTextColor((Integer) valueAnimator.getAnimatedValue()));
        mAnimator.setRepeatMode(ValueAnimator.REVERSE);
        mAnimator.setRepeatCount(1);
        mAnimator.setDuration(mAnimationDurationMillis);
        final ColorStateList originalColors = view.getTextColors();
        mAnimator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                view.setTextColor(originalColors);
                mAnimator = null;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
            }

            @Override
            public void onAnimationRepeat(Animator animation) {
                changeTextRunnable.run();
            }
        });
        mAnimator.start();
    }

    private ValueAnimator mAnimator;

    /**
     * Returns {@code color} with alpha changed to {@code newAlpha}
     */
    private static int colorWithAlpha(int color, int newAlpha) {
        return Color.argb(newAlpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
