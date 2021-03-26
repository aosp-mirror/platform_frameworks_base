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
import android.text.method.TranslationTransformationMethod;
import android.util.Log;
import android.view.View;
import android.view.translation.UiTranslationManager;
import android.view.translation.ViewTranslationCallback;
import android.view.translation.ViewTranslationResponse;

/**
 * Default implementation for {@link ViewTranslationCallback} for {@link TextView} components.
 * This class handles how to display the translated information for {@link TextView}.
 *
 * @hide
 */
public class TextViewTranslationCallback implements ViewTranslationCallback {

    private static final String TAG = "TextViewTranslationCallback";

    private static final boolean DEBUG = Log.isLoggable(UiTranslationManager.LOG_TAG, Log.DEBUG);

    private TranslationTransformationMethod mTranslationTransformation;

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
        if (mTranslationTransformation != null) {
            ((TextView) view).setTransformationMethod(mTranslationTransformation);
        } else {
            if (DEBUG) {
                // TODO(b/182433547): remove before S release
                Log.w(TAG, "onShowTranslation(): no translated text.");
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onHideTranslation(@NonNull View view) {
        // Restore to original text content.
        if (mTranslationTransformation != null) {
            ((TextView) view).setTransformationMethod(
                    mTranslationTransformation.getOriginalTransformationMethod());
        } else {
            if (DEBUG) {
                // TODO(b/182433547): remove before S release
                Log.w(TAG, "onHideTranslation(): no translated text.");
            }
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
            ((TextView) view).setTransformationMethod(
                    mTranslationTransformation.getOriginalTransformationMethod());
            clearTranslationTransformation();
        } else {
            if (DEBUG) {
                // TODO(b/182433547): remove before S release
                Log.w(TAG, "onClearTranslation(): no translated text.");
            }
        }
        return true;
    }
}
