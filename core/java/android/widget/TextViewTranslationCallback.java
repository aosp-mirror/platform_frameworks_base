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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.text.TextUtils;
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

    // TODO(b/182433547): remove Build.IS_DEBUGGABLE before ship. Enable the logging in debug build
    //  to help the debug during the development phase
    private static final boolean DEBUG = Log.isLoggable(UiTranslationManager.LOG_TAG, Log.DEBUG)
            || Build.IS_DEBUGGABLE;

    private TranslationTransformationMethod mTranslationTransformation;
    private boolean mIsShowingTranslation = false;
    private boolean mIsTextPaddingEnabled = false;
    private CharSequence mPaddedText;

    private CharSequence mContentDescription;

    /**
     * Invoked by the platform when receiving the successful {@link ViewTranslationResponse} for the
     * view that provides the translatable information by {@link View#createTranslationRequest} and
     * sent by the platform.
     */
    void setTranslationTransformation(TranslationTransformationMethod method) {
        if (method == null) {
            if (DEBUG) {
                Log.w(TAG, "setTranslationTransformation: should not set null "
                        + "TranslationTransformationMethod");
            }
            return;
        }
        mTranslationTransformation = method;
    }

    TranslationTransformationMethod getTranslationTransformation() {
        return mTranslationTransformation;
    }

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
        mIsShowingTranslation = true;
        if (view.getViewTranslationResponse() == null) {
            Log.wtf(TAG, "onShowTranslation() shouldn't be called before "
                    + "onViewTranslationResponse().");
            return false;
        }
        if (mTranslationTransformation != null) {
            ((TextView) view).setTransformationMethod(mTranslationTransformation);
            ViewTranslationResponse response = view.getViewTranslationResponse();
            if (response.getKeys().contains(ViewTranslationRequest.ID_CONTENT_DESCRIPTION)) {
                CharSequence translatedContentDescription =
                        response.getValue(ViewTranslationRequest.ID_CONTENT_DESCRIPTION).getText();
                if (!TextUtils.isEmpty(translatedContentDescription)) {
                    mContentDescription = view.getContentDescription();
                    view.setContentDescription(translatedContentDescription);
                }
            }
        } else {
            if (DEBUG) {
                // TODO(b/182433547): remove before S release
                Log.w(TAG, "onShowTranslation(): no translated text.");
            }
            return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onHideTranslation(@NonNull View view) {
        mIsShowingTranslation = false;
        if (view.getViewTranslationResponse() == null) {
            Log.wtf(TAG, "onHideTranslation() shouldn't be called before "
                    + "onViewTranslationResponse().");
            return false;
        }
        // Restore to original text content.
        if (mTranslationTransformation != null) {
            ((TextView) view).setTransformationMethod(
                    mTranslationTransformation.getOriginalTransformationMethod());
            if (!TextUtils.isEmpty(mContentDescription)) {
                view.setContentDescription(mContentDescription);
            }
        } else {
            if (DEBUG) {
                // TODO(b/182433547): remove before S release
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
                // TODO(b/182433547): remove before S release
                Log.w(TAG, "onClearTranslation(): no translated text.");
            }
            return false;
        }
        return true;
    }

    boolean isShowingTranslation() {
        return mIsShowingTranslation;
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
}
