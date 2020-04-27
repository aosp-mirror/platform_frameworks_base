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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipDescription;
import android.view.View;
import android.view.inputmethod.InputConnection;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

/**
 * Callback for apps to implement handling for insertion of rich content. "Rich content" here refers
 * to both text and non-text content: plain text, styled text, HTML, images, videos, audio files,
 * etc.
 *
 * <p>This callback can be attached to different types of UI components. For editable
 * {@link TextView} components, implementations should typically wrap
 * {@link TextView#DEFAULT_RICH_CONTENT_RECEIVER}.
 *
 * <p>Example implementation:<br>
 * <pre class="prettyprint">
 *   public class MyRichContentReceiver implements RichContentReceiver&lt;TextView&gt; {
 *
 *       private static final Set&lt;String&gt; SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(
 *           Set.of("text/*", "image/gif", "image/png", "image/jpg"));
 *
 *       &#64;NonNull
 *       &#64;Override
 *       public Set&lt;String&gt; getSupportedMimeTypes() {
 *           return SUPPORTED_MIME_TYPES;
 *       }
 *
 *       &#64;Override
 *       public boolean onReceive(@NonNull TextView textView, @NonNull ClipData clip,
 *               int source, int flags) {
 *         if (clip.getDescription().hasMimeType("image/*")) {
 *             return receiveImage(textView, clip);
 *         }
 *         return TextView.DEFAULT_RICH_CONTENT_RECEIVER.onReceive(textView, clip, source);
 *       }
 *
 *       private boolean receiveImage(@NonNull TextView textView, @NonNull ClipData clip) {
 *           // ... app-specific logic to handle the content URI in the clip ...
 *       }
 *   }
 * </pre>
 *
 * @param <T> The type of {@link View} with which this receiver can be associated.
 */
@SuppressLint("CallbackMethodName")
public interface RichContentReceiver<T extends View> {
    /**
     * Specifies the UI through which content is being inserted.
     *
     * @hide
     */
    @IntDef(prefix = {"SOURCE_"}, value = {SOURCE_CLIPBOARD, SOURCE_INPUT_METHOD,
            SOURCE_DRAG_AND_DROP, SOURCE_AUTOFILL, SOURCE_PROCESS_TEXT})
    @Retention(RetentionPolicy.SOURCE)
    @interface Source {}

    /**
     * Specifies that the operation was triggered by a paste from the clipboard (e.g. "Paste" or
     * "Paste as plain text" action in the insertion/selection menu).
     */
    int SOURCE_CLIPBOARD = 0;

    /**
     * Specifies that the operation was triggered from the soft keyboard (also known as input method
     * editor or IME). See https://developer.android.com/guide/topics/text/image-keyboard for more
     * info.
     */
    int SOURCE_INPUT_METHOD = 1;

    /**
     * Specifies that the operation was triggered by the drag/drop framework. See
     * https://developer.android.com/guide/topics/ui/drag-drop for more info.
     */
    int SOURCE_DRAG_AND_DROP = 2;

    /**
     * Specifies that the operation was triggered by the autofill framework. See
     * https://developer.android.com/guide/topics/text/autofill for more info.
     */
    int SOURCE_AUTOFILL = 3;

    /**
     * Specifies that the operation was triggered by a result from a
     * {@link android.content.Intent#ACTION_PROCESS_TEXT PROCESS_TEXT} action in the selection menu.
     */
    int SOURCE_PROCESS_TEXT = 4;

    /**
     * Flags to configure the insertion behavior.
     *
     * @hide
     */
    @IntDef(flag = true, prefix = {"FLAG_"}, value = {FLAG_CONVERT_TO_PLAIN_TEXT})
    @Retention(RetentionPolicy.SOURCE)
    @interface Flags {}

    /**
     * Flag for {@link #onReceive} requesting that the content should be converted to plain text
     * prior to inserting.
     */
    int FLAG_CONVERT_TO_PLAIN_TEXT = 1 << 0;

    /**
     * Insert the given clip.
     *
     * <p>For editable {@link TextView} components, this function will be invoked for the
     * following scenarios:
     * <ol>
     *     <li>Paste from the clipboard (e.g. "Paste" or "Paste as plain text" action in the
     *     insertion/selection menu)
     *     <li>Content insertion from the keyboard ({@link InputConnection#commitContent})
     *     <li>Drag and drop ({@link View#onDragEvent})
     *     <li>Autofill, when the type for the field is
     *     {@link android.view.View.AutofillType#AUTOFILL_TYPE_RICH_CONTENT}
     * </ol>
     *
     * <p>For text, if the view has a selection, the selection should be overwritten by the
     * clip; if there's no selection, this method should insert the content at the current
     * cursor position.
     *
     * <p>For rich content (e.g. an image), this function may insert the content inline, or it may
     * add the content as an attachment (could potentially go into a completely separate view).
     *
     * <p>This function may be invoked with a clip whose MIME type is not in the list of supported
     * types returned by {@link #getSupportedMimeTypes()}. This provides the opportunity to
     * implement custom fallback logic if desired.
     *
     * @param view   The view where the content insertion was requested.
     * @param clip   The clip to insert.
     * @param source The trigger of the operation.
     * @param flags  Optional flags to configure the insertion behavior. Use 0 for default
     *               behavior. See {@code FLAG_} constants on this interface for other options.
     * @return Returns true if the clip was inserted.
     */
    boolean onReceive(@NonNull T view, @NonNull ClipData clip, @Source int source, int flags);

    /**
     * Returns the MIME types that can be handled by this callback.
     *
     * <p>Different platform features (e.g. pasting from the clipboard, inserting stickers from the
     * keyboard, etc) may use this function to conditionally alter their behavior. For example, the
     * keyboard may choose to hide its UI for inserting GIFs if the input field that has focus has
     * a {@link RichContentReceiver} set and the MIME types returned from this function don't
     * include "image/gif".
     *
     * @return An immutable set with the MIME types supported by this callback. The returned
     * MIME types may contain wildcards such as "text/*", "image/*", etc.
     */
    @NonNull
    Set<String> getSupportedMimeTypes();

    /**
     * Returns true if the MIME type of the given clip is {@link #getSupportedMimeTypes supported}
     * by this receiver.
     *
     * @hide
     */
    default boolean supports(@NonNull ClipDescription description) {
        for (String supportedMimeType : getSupportedMimeTypes()) {
            if (description.hasMimeType(supportedMimeType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if this receiver {@link #getSupportedMimeTypes supports} non-text content, such
     * as images.
     *
     * @hide
     */
    default boolean supportsNonTextContent() {
        for (String supportedMimeType : getSupportedMimeTypes()) {
            if (!supportedMimeType.startsWith("text/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the symbolic name of the given source.
     *
     * @hide
     */
    static String sourceToString(@Source int source) {
        switch (source) {
            case SOURCE_CLIPBOARD: return "SOURCE_CLIPBOARD";
            case SOURCE_INPUT_METHOD: return "SOURCE_INPUT_METHOD";
            case SOURCE_DRAG_AND_DROP: return "SOURCE_DRAG_AND_DROP";
            case SOURCE_AUTOFILL: return "SOURCE_AUTOFILL";
            case SOURCE_PROCESS_TEXT: return "SOURCE_PROCESS_TEXT";
        }
        return String.valueOf(source);
    }

    /**
     * Returns the symbolic names of the set flags or {@code "0"} if no flags are set.
     *
     * @hide
     */
    static String flagsToString(@Flags int flags) {
        if ((flags & FLAG_CONVERT_TO_PLAIN_TEXT) != 0) {
            return "FLAG_CONVERT_TO_PLAIN_TEXT";
        }
        return String.valueOf(flags);
    }
}
