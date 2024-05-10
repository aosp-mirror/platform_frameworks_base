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

package android.view.inputmethod;

import android.annotation.Nullable;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;

import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.view.IInputMethodManager;

/**
 * This data class is a container for storing the last arguments used when calling into
 * {@link IInputMethodManager#startInputOrWindowGainedFocus}. They are used to determine if we
 * are switching from a non-editable view to another non-editable view, in which case we avoid
 * a binder call into the {@link com.android.server.inputmethod.InputMethodManagerService}.
 */
final class ViewFocusParameterInfo {
    @Nullable final EditorInfo mPreviousEditorInfo;
    @StartInputFlags final int mPreviousStartInputFlags;
    @StartInputReason final int mPreviousStartInputReason;
    @SoftInputModeFlags final int mPreviousSoftInputMode;
    final int mPreviousWindowFlags;

    ViewFocusParameterInfo(@Nullable EditorInfo previousEditorInfo,
            @StartInputFlags int previousStartInputFlags,
            @StartInputReason int previousStartInputReason,
            @SoftInputModeFlags int previousSoftInputMode,
            int previousWindowFlags) {
        mPreviousEditorInfo = previousEditorInfo;
        mPreviousStartInputFlags = previousStartInputFlags;
        mPreviousStartInputReason = previousStartInputReason;
        mPreviousSoftInputMode = previousSoftInputMode;
        mPreviousWindowFlags = previousWindowFlags;
    }

    boolean sameAs(@Nullable EditorInfo currentEditorInfo,
            @StartInputFlags int startInputFlags,
            @StartInputReason int startInputReason,
            @SoftInputModeFlags int softInputMode,
            int windowFlags) {
        return mPreviousStartInputFlags == startInputFlags
                && mPreviousStartInputReason == startInputReason
                && mPreviousSoftInputMode == softInputMode
                && mPreviousWindowFlags == windowFlags
                && (mPreviousEditorInfo == currentEditorInfo
                    || (mPreviousEditorInfo != null
                    && mPreviousEditorInfo.kindofEquals(currentEditorInfo)));
    }
}
