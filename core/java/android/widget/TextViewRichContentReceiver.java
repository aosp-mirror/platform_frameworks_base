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

import android.annotation.NonNull;
import android.content.ClipData;
import android.content.Context;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import java.util.Collections;
import java.util.Set;

/**
 * Default implementation of {@link RichContentReceiver} for editable {@link TextView} components.
 * This class handles insertion of text (plain text, styled text, HTML, etc) but not images or other
 * rich content. Typically this class will be used as a delegate by custom implementations of
 * {@link RichContentReceiver}, to provide consistent behavior for insertion of text while
 * implementing custom behavior for insertion of other content (images, etc). See
 * {@link TextView#DEFAULT_RICH_CONTENT_RECEIVER}.
 *
 * @hide
 */
final class TextViewRichContentReceiver implements RichContentReceiver<TextView> {
    static final TextViewRichContentReceiver INSTANCE = new TextViewRichContentReceiver();

    private static final Set<String> MIME_TYPES_ALL_TEXT = Collections.singleton("text/*");

    @Override
    public Set<String> getSupportedMimeTypes() {
        return MIME_TYPES_ALL_TEXT;
    }

    @Override
    public boolean onReceive(@NonNull TextView textView, @NonNull ClipData clip,
            @Source int source, @Flags int flags) {
        if (source == SOURCE_AUTOFILL) {
            return onReceiveForAutofill(textView, clip, flags);
        }
        if (source == SOURCE_DRAG_AND_DROP) {
            return onReceiveForDragAndDrop(textView, clip, flags);
        }
        if (source == SOURCE_INPUT_METHOD && !supports(clip.getDescription())) {
            return false;
        }

        // The code here follows the original paste logic from TextView:
        // https://cs.android.com/android/_/android/platform/frameworks/base/+/9fefb65aa9e7beae9ca8306b925b9fbfaeffecc9:core/java/android/widget/TextView.java;l=12644
        // In particular, multiple items within the given ClipData will trigger separate calls to
        // replace/insert. This is to preserve the original behavior with respect to TextWatcher
        // notifications fired from SpannableStringBuilder when replace/insert is called.
        final Editable editable = (Editable) textView.getText();
        final Context context = textView.getContext();
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
        return didFirst;
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
        if (text.length() == 0) {
            return false;
        }
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
            return false;
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
