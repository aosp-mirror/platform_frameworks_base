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
package android.text.method;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.translation.TranslationResponseValue;
import android.view.translation.ViewTranslationRequest;
import android.view.translation.ViewTranslationResponse;
import android.widget.TextView;

import java.util.regex.Pattern;

/**
 * Transforms source text into an translated string.
 *
 * @hide
 */
public class TranslationTransformationMethod implements TransformationMethod2 {

    private static final String TAG = "TranslationTransformationMethod";
    private static final Pattern PATTERN_WHITESPACE = Pattern.compile("\\s+");

    @NonNull
    private final ViewTranslationResponse mTranslationResponse;
    @Nullable
    private TransformationMethod mOriginalTranslationMethod;
    private boolean mAllowLengthChanges;

    /**
     * @param response the translated result from translation service.
     * @param method the {@link TextView}'s original {@link TransformationMethod}
     */
    public TranslationTransformationMethod(@NonNull ViewTranslationResponse response,
            @Nullable TransformationMethod method) {
        mTranslationResponse = response;
        mOriginalTranslationMethod = method;
    }

    /**
     * Returns the {@link TextView}'s original {@link TransformationMethod}. This can be used to
     * restore to show if the user pauses or finish the ui translation.
     */
    public TransformationMethod getOriginalTransformationMethod() {
        return mOriginalTranslationMethod;
    }

    /**
     * Returns the {@link TextView}'s {@link ViewTranslationResponse}.
     */
    public ViewTranslationResponse getViewTranslationResponse() {
        return mTranslationResponse;
    }

    @Override
    public CharSequence getTransformation(CharSequence source, View view) {
        if (!mAllowLengthChanges) {
            Log.w(TAG, "Caller did not enable length changes; not transforming to translated text");
            return source;
        }
        TranslationResponseValue value = mTranslationResponse.getValue(
                ViewTranslationRequest.ID_TEXT);
        CharSequence translatedText;
        if (value.getStatusCode() == TranslationResponseValue.STATUS_SUCCESS) {
            translatedText = value.getText();
        } else {
            translatedText = "";
        }

        if (TextUtils.isEmpty(translatedText) || isWhitespace(translatedText.toString())) {
            return source;
        } else {
            // TODO(b/174283799): apply the spans to the text
            return translatedText;
        }
    }

    @Override
    public void onFocusChanged(View view, CharSequence sourceText,
            boolean focused, int direction,
            Rect previouslyFocusedRect) {
        // do nothing
    }

    @Override
    public void setLengthChangesAllowed(boolean allowLengthChanges) {
        mAllowLengthChanges = allowLengthChanges;
    }

    private boolean isWhitespace(String text) {
        return PATTERN_WHITESPACE.matcher(text.substring(0, text.length())).matches();
    }
}
