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

package com.android.internal.inputmethod;

import static android.view.inputmethod.InputConnectionCallProto.GET_CURSOR_CAPS_MODE;
import static android.view.inputmethod.InputConnectionCallProto.GET_EXTRACTED_TEXT;
import static android.view.inputmethod.InputConnectionCallProto.GET_SELECTED_TEXT;
import static android.view.inputmethod.InputConnectionCallProto.GET_SURROUNDING_TEXT;
import static android.view.inputmethod.InputConnectionCallProto.GET_TEXT_AFTER_CURSOR;
import static android.view.inputmethod.InputConnectionCallProto.GET_TEXT_BEFORE_CURSOR;
import static android.view.inputmethod.InputConnectionCallProto.GetExtractedText.REQUEST;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.proto.ProtoOutputStream;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnectionCallProto.GetCursorCapsMode;
import android.view.inputmethod.InputConnectionCallProto.GetExtractedText;
import android.view.inputmethod.InputConnectionCallProto.GetSelectedText;
import android.view.inputmethod.InputConnectionCallProto.GetSurroundingText;
import android.view.inputmethod.InputConnectionCallProto.GetTextAfterCursor;
import android.view.inputmethod.InputConnectionCallProto.GetTextBeforeCursor;
import android.view.inputmethod.SurroundingText;

/**
 * Helper class for constructing {@link android.view.inputmethod.InputConnection} dumps, which are
 * integrated into {@link ImeTracing}.
 */
public final class InputConnectionProtoDumper {
    static final String TAG = "InputConnectionProtoDumper";

    private InputConnectionProtoDumper() {}

    /**
     * Builder for InputConnectionCallProto to hold
     * {@link android.view.inputmethod.InputConnection#getTextAfterCursor(int, int)} data.
     *
     * @param length The expected length of the text. This must be non-negative.
     * @param flags Supplies additional options controlling how the text is
     * returned. May be either {@code 0} or
     * {@link android.view.inputmethod.InputConnection#GET_TEXT_WITH_STYLES}.
     * @param result The text after the cursor position; the length of the
     * returned text might be less than <var>length</var>.
     * @return Byte-array holding the InputConnectionCallProto data.
     */
    @NonNull
    public static byte[] buildGetTextAfterCursorProto(@IntRange(from = 0) int length, int flags,
            @Nullable CharSequence result) {
        ProtoOutputStream proto = new ProtoOutputStream();
        final long token = proto.start(GET_TEXT_AFTER_CURSOR);
        proto.write(GetTextAfterCursor.LENGTH, length);
        proto.write(GetTextAfterCursor.FLAGS, flags);
        proto.end(token);
        return proto.getBytes();
    }

    /**
     * Builder for InputConnectionCallProto to hold
     * {@link android.view.inputmethod.InputConnection#getTextBeforeCursor(int, int)} data.
     *
     * @param length The expected length of the text. This must be non-negative.
     * @param flags Supplies additional options controlling how the text is
     * returned. May be either {@code 0} or
     * {@link android.view.inputmethod.InputConnection#GET_TEXT_WITH_STYLES}.
     * @param result The text before the cursor position; the length of the
     * returned text might be less than <var>length</var>.
     * @return Byte-array holding the InputConnectionCallProto data.
     */
    @NonNull
    public static byte[] buildGetTextBeforeCursorProto(@IntRange(from = 0) int length,
            int flags, @Nullable CharSequence result) {
        ProtoOutputStream proto = new ProtoOutputStream();
        final long token = proto.start(GET_TEXT_BEFORE_CURSOR);
        proto.write(GetTextBeforeCursor.LENGTH, length);
        proto.write(GetTextBeforeCursor.FLAGS, flags);
        proto.end(token);
        return proto.getBytes();
    }

    /**
     * Builder for InputConnectionCallProto to hold
     * {@link android.view.inputmethod.InputConnection#getSelectedText(int)} data.
     *
     * @param flags Supplies additional options controlling how the text is
     * returned. May be either {@code 0} or
     * {@link android.view.inputmethod.InputConnection#GET_TEXT_WITH_STYLES}.
     * @param result the text that is currently selected, if any, or null if
     * no text is selected. In {@link android.os.Build.VERSION_CODES#N} and
     * later, returns false when the target application does not implement
     * this method.
     * @return Byte-array holding the InputConnectionCallProto data.
     */
    @NonNull
    public static byte[] buildGetSelectedTextProto(int flags, @Nullable CharSequence result) {
        ProtoOutputStream proto = new ProtoOutputStream();
        final long token = proto.start(GET_SELECTED_TEXT);
        proto.write(GetSelectedText.FLAGS, flags);
        proto.end(token);
        return proto.getBytes();
    }

    /**
     * Builder for InputConnectionCallProto to hold
     * {@link android.view.inputmethod.InputConnection#getSurroundingText(int, int, int)} data.
     *
     * @param beforeLength The expected length of the text before the cursor.
     * @param afterLength The expected length of the text after the cursor.
     * @param flags Supplies additional options controlling how the text is
     * returned. May be either {@code 0} or
     * {@link android.view.inputmethod.InputConnection#GET_TEXT_WITH_STYLES}.
     * @param result an {@link SurroundingText} object describing the
     * surrounding text and state of selection, or null if the input connection is no longer valid,
     * or the editor can't comply with the request for some reason, or the application does not
     * implement this method. The length of the returned text might be less than the sum of
     * <var>beforeLength</var> and <var>afterLength</var> .
     * @return Byte-array holding the InputConnectionCallProto data.
     */
    @NonNull
    public static byte[] buildGetSurroundingTextProto(@IntRange(from = 0) int beforeLength,
            @IntRange(from = 0) int afterLength, int flags, @Nullable SurroundingText result) {
        ProtoOutputStream proto = new ProtoOutputStream();
        final long token = proto.start(GET_SURROUNDING_TEXT);
        proto.write(GetSurroundingText.BEFORE_LENGTH, beforeLength);
        proto.write(GetSurroundingText.AFTER_LENGTH, afterLength);
        proto.write(GetSurroundingText.FLAGS, flags);
        if (result != null) {
            final long token_result = proto.start(GetSurroundingText.RESULT);
            proto.write(GetSurroundingText.SurroundingText.SELECTION_START,
                    result.getSelectionStart());
            proto.write(GetSurroundingText.SurroundingText.SELECTION_END,
                    result.getSelectionEnd());
            proto.write(GetSurroundingText.SurroundingText.OFFSET, result.getOffset());
            proto.end(token_result);
        }
        proto.end(token);
        return proto.getBytes();
    }

    /**
     * Builder for InputConnectionCallProto to hold
     * {@link android.view.inputmethod.InputConnection#getCursorCapsMode(int)} data.
     *
     * @param reqModes The desired modes to retrieve, as defined by
     * {@link android.text.TextUtils#getCapsMode TextUtils.getCapsMode}.
     * @param result the caps mode flags that are in effect at the current
     * cursor position. See TYPE_TEXT_FLAG_CAPS_* in {@link android.text.InputType}.
     * @return Byte-array holding the InputConnectionCallProto data.
     */
    @NonNull
    public static byte[] buildGetCursorCapsModeProto(int reqModes, int result) {
        ProtoOutputStream proto = new ProtoOutputStream();
        final long token = proto.start(GET_CURSOR_CAPS_MODE);
        proto.write(GetCursorCapsMode.REQ_MODES, reqModes);
        proto.write(GetCursorCapsMode.RESULT, result);
        proto.end(token);
        return proto.getBytes();
    }

    /**
     * Builder for InputConnectionCallProto to hold
     * {@link android.view.inputmethod.InputConnection#getExtractedText(ExtractedTextRequest, int)}
     * data.
     *
     * @param request Description of how the text should be returned.
     * {@link ExtractedTextRequest}
     * @param flags Additional options to control the client, either {@code 0} or
     * {@link android.view.inputmethod.InputConnection#GET_EXTRACTED_TEXT_MONITOR}.
     * @param result an {@link ExtractedText}
     * object describing the state of the text view and containing the
     * extracted text itself, or null if the input connection is no
     * longer valid of the editor can't comply with the request for
     * some reason.
     * @return Byte-array holding the InputConnectionCallProto data.
     */
    @NonNull
    public static byte[] buildGetExtractedTextProto(@NonNull ExtractedTextRequest
            request, int flags, @Nullable ExtractedText result) {
        ProtoOutputStream proto = new ProtoOutputStream();
        final long token = proto.start(GET_EXTRACTED_TEXT);
        final long token_request = proto.start(REQUEST);
        proto.write(GetExtractedText.ExtractedTextRequest.TOKEN, request.token);
        proto.write(GetExtractedText.ExtractedTextRequest.FLAGS, request.flags);
        proto.write(GetExtractedText.ExtractedTextRequest.HINT_MAX_LINES, request.hintMaxLines);
        proto.write(GetExtractedText.ExtractedTextRequest.HINT_MAX_CHARS, request.hintMaxChars);
        proto.end(token_request);
        proto.write(GetExtractedText.FLAGS, flags);
        proto.end(token);
        return proto.getBytes();
    }
}
