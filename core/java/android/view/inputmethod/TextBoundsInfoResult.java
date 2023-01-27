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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.RectF;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The object that holds the result of the
 * {@link InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)} call.
 *
 * @see InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)
 */
public final class TextBoundsInfoResult {
    private final int mResultCode;
    private final TextBoundsInfo mTextBoundsInfo;

    /**
     * Result for {@link InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)} when the
     * editor doesn't implement the method.
     */
    public static final int CODE_UNSUPPORTED = 0;

    /**
     * Result for {@link InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)} when the
     * editor successfully returns a {@link TextBoundsInfo}.
     */
    public static final int CODE_SUCCESS = 1;

    /**
     * Result for {@link InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)} when the
     * request failed. This result code is returned when the editor can't provide a valid
     * {@link TextBoundsInfo}. (e.g. The editor view is not laid out.)
     */
    public static final int CODE_FAILED = 2;

    /**
     * Result for {@link InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)} when the
     * request is cancelled. This happens when the {@link InputConnection} is or becomes
     * invalidated while requesting the
     * {@link TextBoundsInfo}, for example because a new {@code InputConnection} was started, or
     * due to {@link InputMethodManager#invalidateInput}.
     */
    public static final int CODE_CANCELLED = 3;

    /** @hide */
    @IntDef(prefix = { "CODE_" }, value = {
            CODE_UNSUPPORTED,
            CODE_SUCCESS,
            CODE_FAILED,
            CODE_CANCELLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {}

    /**
     * Create a {@link TextBoundsInfoResult} object with no {@link TextBoundsInfo}.
     * The given {@code resultCode} can't be {@link #CODE_SUCCESS}.
     * @param resultCode the result code of the
     * {@link InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)} call.
     */
    public TextBoundsInfoResult(@ResultCode int resultCode) {
        this(resultCode, null);
    }

    /**
     * Create a {@link TextBoundsInfoResult} object.
     *
     * @param resultCode the result code of the
     * {@link InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)} call.
     * @param textBoundsInfo the returned {@link TextBoundsInfo} of the
     * {@link InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)} call. It can't be
     *                       null if the {@code resultCode} is {@link #CODE_SUCCESS}.
     *
     * @throws IllegalStateException if the resultCode is
     * {@link #CODE_SUCCESS} but the given {@code textBoundsInfo}
     * is null.
     */
    public TextBoundsInfoResult(@ResultCode int resultCode,
            @Nullable TextBoundsInfo textBoundsInfo) {
        if (resultCode == CODE_SUCCESS && textBoundsInfo == null) {
            throw new IllegalStateException("TextBoundsInfo must be provided when the resultCode "
                    + "is CODE_SUCCESS.");
        }
        mResultCode = resultCode;
        mTextBoundsInfo = textBoundsInfo;
    }

    /**
     * Return the result code of the
     * {@link InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)} call.
     * Its value is one of the {@link #CODE_UNSUPPORTED}, {@link #CODE_SUCCESS},
     * {@link #CODE_FAILED} and {@link #CODE_CANCELLED}.
     */
    @ResultCode
    public int getResultCode() {
        return mResultCode;
    }

    /**
     * Return the {@link TextBoundsInfo} provided by the editor. It is non-null if the
     * {@code resultCode} is {@link #CODE_SUCCESS}.
     * Otherwise, it can be null in the following conditions:
     * <ul>
     *    <li>the editor doesn't support
     *      {@link InputConnection#requestTextBoundsInfo(RectF, Executor, Consumer)}.</li>
     *    <li>the editor doesn't have the text bounds information at the moment. (e.g. the editor
     *    view is not laid out yet.) </li>
     *    <li> the {@link InputConnection} is or become inactive during the request. </li>
     * <ul/>
     */
    @Nullable
    public TextBoundsInfo getTextBoundsInfo() {
        return  mTextBoundsInfo;
    }
}
