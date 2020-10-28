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

package android.view;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ClipData;
import android.net.Uri;
import android.os.Bundle;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Callback for apps to implement handling for insertion of content. Content may be both text and
 * non-text (plain/styled text, HTML, images, videos, audio files, etc).
 *
 * <p>This callback can be attached to different types of UI components using
 * {@link View#setOnReceiveContentCallback}.
 *
 * <p>For editable {@link android.widget.TextView} components, implementations can extend from
 * {@link android.widget.TextViewOnReceiveContentCallback} to reuse default platform behavior for
 * handling text.
 *
 * <p>Example implementation:<br>
 * <pre class="prettyprint">
 * // (1) Define the callback
 * public class MyOnReceiveContentCallback implements OnReceiveContentCallback&lt;TextView&gt; {
 *     public static final Set&lt;String&gt; MIME_TYPES = Collections.unmodifiableSet(
 *         Set.of("image/*", "video/*"));
 *
 *     &#64;Override
 *     public boolean onReceiveContent(@NonNull TextView view, @NonNull Payload payload) {
 *         // ... app-specific logic to handle the content in the payload ...
 *     }
 * }
 *
 * // (2) Register the callback
 * public class MyActivity extends Activity {
 *     &#64;Override
 *     public void onCreate(Bundle savedInstanceState) {
 *         // ...
 *
 *         EditText myInput = findViewById(R.id.my_input);
 *         myInput.setOnReceiveContentCallback(
 *                 MyOnReceiveContentCallback.MIME_TYPES,
 *                 new MyOnReceiveContentCallback());
 *     }
 * </pre>
 *
 * @param <T> The type of {@link View} with which this callback can be associated.
 */
public interface OnReceiveContentCallback<T extends View> {
    /**
     * Receive the given content.
     *
     * <p>This method is only invoked for content whose MIME type matches a type specified via
     * {@link View#setOnReceiveContentCallback}.
     *
     * <p>For text, if the view has a selection, the selection should be overwritten by the clip; if
     * there's no selection, this method should insert the content at the current cursor position.
     *
     * <p>For non-text content (e.g. an image), the content may be inserted inline, or it may be
     * added as an attachment (could potentially be shown in a completely separate view).
     *
     * @param view The view where the content insertion was requested.
     * @param payload The content to insert and related metadata.
     *
     * @return Returns true if the content was handled in some way, false otherwise. Actual
     * insertion may be processed asynchronously in the background and may or may not succeed even
     * if this method returns true. For example, an app may not end up inserting an item if it
     * exceeds the app's size limit for that type of content.
     */
    boolean onReceiveContent(@NonNull T view, @NonNull Payload payload);

    /**
     * Holds all the relevant data for a request to {@link OnReceiveContentCallback}.
     */
    final class Payload {

        /**
         * Specifies the UI through which content is being inserted. Future versions of Android may
         * support additional values.
         *
         * @hide
         */
        @IntDef(prefix = {"SOURCE_"}, value = {SOURCE_APP, SOURCE_CLIPBOARD, SOURCE_INPUT_METHOD,
                SOURCE_DRAG_AND_DROP, SOURCE_AUTOFILL, SOURCE_PROCESS_TEXT})
        @Retention(RetentionPolicy.SOURCE)
        public @interface Source {}

        /**
         * Specifies that the operation was triggered by the app that contains the target view.
         */
        public static final int SOURCE_APP = 0;

        /**
         * Specifies that the operation was triggered by a paste from the clipboard (e.g. "Paste" or
         * "Paste as plain text" action in the insertion/selection menu).
         */
        public static final int SOURCE_CLIPBOARD = 1;

        /**
         * Specifies that the operation was triggered from the soft keyboard (also known as input
         * method editor or IME). See https://developer.android.com/guide/topics/text/image-keyboard
         * for more info.
         */
        public static final int SOURCE_INPUT_METHOD = 2;

        /**
         * Specifies that the operation was triggered by the drag/drop framework. See
         * https://developer.android.com/guide/topics/ui/drag-drop for more info.
         */
        public static final int SOURCE_DRAG_AND_DROP = 3;

        /**
         * Specifies that the operation was triggered by the autofill framework. See
         * https://developer.android.com/guide/topics/text/autofill for more info.
         */
        public static final int SOURCE_AUTOFILL = 4;

        /**
         * Specifies that the operation was triggered by a result from a
         * {@link android.content.Intent#ACTION_PROCESS_TEXT PROCESS_TEXT} action in the selection
         * menu.
         */
        public static final int SOURCE_PROCESS_TEXT = 5;

        /**
         * Returns the symbolic name of the given source.
         *
         * @hide
         */
        static String sourceToString(@Source int source) {
            switch (source) {
                case SOURCE_APP: return "SOURCE_APP";
                case SOURCE_CLIPBOARD: return "SOURCE_CLIPBOARD";
                case SOURCE_INPUT_METHOD: return "SOURCE_INPUT_METHOD";
                case SOURCE_DRAG_AND_DROP: return "SOURCE_DRAG_AND_DROP";
                case SOURCE_AUTOFILL: return "SOURCE_AUTOFILL";
                case SOURCE_PROCESS_TEXT: return "SOURCE_PROCESS_TEXT";
            }
            return String.valueOf(source);
        }

        /**
         * Flags to configure the insertion behavior.
         *
         * @hide
         */
        @IntDef(flag = true, prefix = {"FLAG_"}, value = {FLAG_CONVERT_TO_PLAIN_TEXT})
        @Retention(RetentionPolicy.SOURCE)
        public @interface Flags {}

        /**
         * Flag requesting that the content should be converted to plain text prior to inserting.
         */
        public static final int FLAG_CONVERT_TO_PLAIN_TEXT = 1 << 0;

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

        @NonNull private final ClipData mClip;
        private final @Source int mSource;
        private final @Flags int mFlags;
        @Nullable private final Uri mLinkUri;
        @Nullable private final Bundle mExtras;

        private Payload(Builder b) {
            this.mClip = Objects.requireNonNull(b.mClip);
            this.mSource = Preconditions.checkArgumentInRange(b.mSource, 0, SOURCE_PROCESS_TEXT,
                    "source");
            this.mFlags = Preconditions.checkFlagsArgument(b.mFlags, FLAG_CONVERT_TO_PLAIN_TEXT);
            this.mLinkUri = b.mLinkUri;
            this.mExtras = b.mExtras;
        }

        @NonNull
        @Override
        public String toString() {
            return "Payload{"
                    + "clip=" + mClip.getDescription()
                    + ", source=" + sourceToString(mSource)
                    + ", flags=" + flagsToString(mFlags)
                    + ", linkUri=" + mLinkUri
                    + ", extras=" + mExtras
                    + "}";
        }

        /**
         * The data to be inserted.
         */
        public @NonNull ClipData getClip() {
            return mClip;
        }

        /**
         * The source of the operation. See {@code SOURCE_} constants. Future versions of Android
         * may pass additional values.
         */
        public @Source int getSource() {
            return mSource;
        }

        /**
         * Optional flags that control the insertion behavior. See {@code FLAG_} constants.
         */
        public @Flags int getFlags() {
            return mFlags;
        }

        /**
         * Optional http/https URI for the content that may be provided by the IME. This is only
         * populated if the source is {@link #SOURCE_INPUT_METHOD} and if a non-empty
         * {@link android.view.inputmethod.InputContentInfo#getLinkUri linkUri} was passed by the
         * IME.
         */
        public @Nullable Uri getLinkUri() {
            return mLinkUri;
        }

        /**
         * Optional additional metadata. If the source is {@link #SOURCE_INPUT_METHOD}, this will
         * include the {@link android.view.inputmethod.InputConnection#commitContent opts} passed by
         * the IME.
         */
        public @Nullable Bundle getExtras() {
            return mExtras;
        }

        /**
         * Builder for {@link Payload}.
         */
        public static final class Builder {
            @NonNull private final ClipData mClip;
            private final @Source int mSource;
            private @Flags int mFlags;
            @Nullable private Uri mLinkUri;
            @Nullable private Bundle mExtras;

            /**
             * Creates a new builder.
             * @param clip   The data to insert.
             * @param source The source of the operation. See {@code SOURCE_} constants.
             */
            public Builder(@NonNull ClipData clip, @Source int source) {
                mClip = clip;
                mSource = source;
            }

            /**
             * Sets flags that control content insertion behavior.
             * @param flags Optional flags to configure the insertion behavior. Use 0 for default
             *              behavior. See {@code FLAG_} constants.
             * @return this builder
             */
            @NonNull
            public Builder setFlags(@Flags int flags) {
                mFlags = flags;
                return this;
            }

            /**
             * Sets the http/https URI for the content. See
             * {@link android.view.inputmethod.InputContentInfo#getLinkUri} for more info.
             * @param linkUri Optional http/https URI for the content.
             * @return this builder
             */
            @NonNull
            public Builder setLinkUri(@Nullable Uri linkUri) {
                mLinkUri = linkUri;
                return this;
            }

            /**
             * Sets additional metadata.
             * @param extras Optional bundle with additional metadata.
             * @return this builder
             */
            @NonNull
            public Builder setExtras(@Nullable Bundle extras) {
                mExtras = extras;
                return this;
            }

            /**
             * @return A new {@link Payload} instance with the data from this builder.
             */
            @NonNull
            public Payload build() {
                return new Payload(this);
            }
        }
    }
}
