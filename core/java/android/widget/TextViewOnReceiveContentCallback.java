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

package android.widget;

import static android.view.OnReceiveContentCallback.Payload.FLAG_CONVERT_TO_PLAIN_TEXT;
import static android.view.OnReceiveContentCallback.Payload.SOURCE_AUTOFILL;
import static android.view.OnReceiveContentCallback.Payload.SOURCE_DRAG_AND_DROP;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.Log;
import android.view.OnReceiveContentCallback;
import android.view.OnReceiveContentCallback.Payload.Flags;
import android.view.OnReceiveContentCallback.Payload.Source;

import java.util.Collections;
import java.util.Set;

/**
 * Default implementation of {@link android.view.OnReceiveContentCallback} for editable
 * {@link TextView} components. This class handles insertion of text (plain text, styled text, HTML,
 * etc) but not images or other content. This class can be used as a base class for an
 * implementation of {@link android.view.OnReceiveContentCallback} for a {@link TextView}, to
 * provide consistent behavior for insertion of text.
 */
public class TextViewOnReceiveContentCallback implements OnReceiveContentCallback<TextView> {
    private static final String LOG_TAG = "OnReceiveContentCallback";

    private static final Set<String> MIME_TYPES_ALL_TEXT = Collections.singleton("text/*");

    @SuppressLint("CallbackMethodName")
    @NonNull
    @Override
    public Set<String> getSupportedMimeTypes(@NonNull TextView view) {
        return MIME_TYPES_ALL_TEXT;
    }

    @Override
    public boolean onReceiveContent(@NonNull TextView view, @NonNull Payload payload) {
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(LOG_TAG, "onReceive:" + payload);
        }
        ClipData clip = payload.getClip();
        @Source int source = payload.getSource();
        @Flags int flags = payload.getFlags();
        if (source == SOURCE_AUTOFILL) {
            return onReceiveForAutofill(view, clip, flags);
        }
        if (source == SOURCE_DRAG_AND_DROP) {
            return onReceiveForDragAndDrop(view, clip, flags);
        }

        // The code here follows the original paste logic from TextView:
        // https://cs.android.com/android/_/android/platform/frameworks/base/+/9fefb65aa9e7beae9ca8306b925b9fbfaeffecc9:core/java/android/widget/TextView.java;l=12644
        // In particular, multiple items within the given ClipData will trigger separate calls to
        // replace/insert. This is to preserve the original behavior with respect to TextWatcher
        // notifications fired from SpannableStringBuilder when replace/insert is called.
        final Editable editable = (Editable) view.getText();
        final Context context = view.getContext();
        boolean didFirst = false;
        for (int i = 0; i < clip.getItemCount(); i++) {
            CharSequence itemText;
            if ((flags & FLAG_CONVERT_TO_PLAIN_TEXT) != 0) {
                itemText = clip.getItemAt(i).coerceToText(context);
                itemText = (itemText instanceof Spanned) ? itemText.toString() : itemText;
            } else {
                itemText = clip.getItemAt(i).coerceToStyledText(context);
            }
            if (itemText != null) {
                if (!didFirst) {
                    replaceSelection(editable, itemText);
                    didFirst = true;
                } else {
                    editable.insert(Selection.getSelectionEnd(editable), "\n");
                    editable.insert(Selection.getSelectionEnd(editable), itemText);
                }
            }
        }
        return true;
    }

    private static void replaceSelection(@NonNull Editable editable,
            @NonNull CharSequence replacement) {
        final int selStart = Selection.getSelectionStart(editable);
        final int selEnd = Selection.getSelectionEnd(editable);
        final int start = Math.max(0, Math.min(selStart, selEnd));
        final int end = Math.max(0, Math.max(selStart, selEnd));
        Selection.setSelection(editable, end);
        editable.replace(start, end, replacement);
    }

    private static boolean onReceiveForAutofill(@NonNull TextView textView, @NonNull ClipData clip,
            @Flags int flags) {
        final CharSequence text = coerceToText(clip, textView.getContext(), flags);
        // First autofill it...
        textView.setText(text);
        // ...then move cursor to the end.
        final Editable editable = (Editable) textView.getText();
        Selection.setSelection(editable, editable.length());
        return true;
    }

    private static boolean onReceiveForDragAndDrop(@NonNull TextView textView,
            @NonNull ClipData clip, @Flags int flags) {
        final CharSequence text = coerceToText(clip, textView.getContext(), flags);
        if (text.length() == 0) {
            return true;
        }
        replaceSelection((Editable) textView.getText(), text);
        return true;
    }

    private static CharSequence coerceToText(ClipData clip, Context context, @Flags int flags) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        for (int i = 0; i < clip.getItemCount(); i++) {
            CharSequence itemText;
            if ((flags & FLAG_CONVERT_TO_PLAIN_TEXT) != 0) {
                itemText = clip.getItemAt(i).coerceToText(context);
                itemText = (itemText instanceof Spanned) ? itemText.toString() : itemText;
            } else {
                itemText = clip.getItemAt(i).coerceToStyledText(context);
            }
            if (itemText != null) {
                ssb.append(itemText);
            }
        }
        return ssb;
    }
}
