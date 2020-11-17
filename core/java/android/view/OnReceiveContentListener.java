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
import android.util.ArrayMap;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Listener for apps to implement handling for insertion of content. Content may be both text and
 * non-text (plain/styled text, HTML, images, videos, audio files, etc).
 *
 * <p>This listener can be attached to different types of UI components using
 * {@link View#setOnReceiveContentListener}.
 *
 * <p>Here is a sample implementation that handles content URIs and delegates the processing for
 * text and everything else to the platform:<br>
 * <pre class="prettyprint">
 * // (1) Define the listener
 * public class MyReceiver implements OnReceiveContentListener {
 *     public static final String[] MIME_TYPES = new String[] {"image/*", "video/*"};
 *
 *     &#64;Override
 *     public Payload onReceiveContent(View view, Payload payload) {
 *         Map&lt;Boolean, Payload&gt; split = payload.partition(item -&gt; item.getUri() != null);
 *         if (split.get(true) != null) {
 *             ClipData clip = payload.getClip();
 *             for (int i = 0; i < clip.getItemCount(); i++) {
 *                 Uri uri = clip.getItemAt(i).getUri();
 *                 // ... app-specific logic to handle the URI ...
 *             }
 *         }
 *         // Return anything that we didn't handle ourselves. This preserves the default platform
 *         // behavior for text and anything else for which we are not implementing custom handling.
 *         return split.get(false);
 *     }
 * }
 *
 * // (2) Register the listener
 * public class MyActivity extends Activity {
 *     &#64;Override
 *     public void onCreate(Bundle savedInstanceState) {
 *         // ...
 *
 *         EditText myInput = findViewById(R.id.my_input);
 *         myInput.setOnReceiveContentListener(MyReceiver.MIME_TYPES, new MyReceiver());
 *     }
 * </pre>
 */
public interface OnReceiveContentListener {
    /**
     * Receive the given content.
     *
     * <p>Implementations should handle any content items of interest and return all unhandled
     * items to preserve the default platform behavior for content that does not have app-specific
     * handling. For example, an implementation may provide handling for content URIs (to provide
     * support for inserting images, etc) and delegate the processing of text to the platform to
     * preserve the common behavior for inserting text. See the class javadoc for a sample
     * implementation and see {@link Payload#partition} for a convenient way to split the passed-in
     * content.
     *
     * <p>If implementing handling for text: if the view has a selection, the selection should
     * be overwritten by the passed-in content; if there's no selection, the passed-in content
     * should be inserted at the current cursor position.
     *
     * <p>If implementing handling for non-text content (e.g. images): the content may be
     * inserted inline, or it may be added as an attachment (could potentially be shown in a
     * completely separate view).
     *
     * @param view The view where the content insertion was requested.
     * @param payload The content to insert and related metadata.
     *
     * @return The portion of the passed-in content whose processing should be delegated to
     * the platform. Return null if all content was handled in some way. Actual insertion of
     * the content may be processed asynchronously in the background and may or may not
     * succeed even if this method returns null. For example, an app may end up not inserting
     * an item if it exceeds the app's size limit for that type of content.
     */
    @Nullable Payload onReceiveContent(@NonNull View view, @NonNull Payload payload);

    /**
     * Holds all the relevant data for a request to {@link OnReceiveContentListener}.
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
                    + "clip=" + mClip
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
         * Partitions this payload based on the given predicate.
         *
         * <p>Similar to a
         * {@link java.util.stream.Collectors#partitioningBy(Predicate) partitioning collector},
         * this function classifies the content in this payload and organizes it into a map,
         * grouping the content that matched vs didn't match the predicate.
         *
         * <p>Except for the {@link ClipData} items, the returned payloads will contain all the same
         * metadata as the original payload.
         *
         * @param itemPredicate The predicate to test each {@link ClipData.Item} to determine which
         * partition to place it into.
         * @return A map containing the partitioned content. The map will contain a single entry if
         * all items were classified into the same partition (all matched or all didn't match the
         * predicate) or two entries (if there's at least one item that matched the predicate and at
         * least one item that didn't match the predicate).
         */
        public @NonNull Map<Boolean, Payload> partition(
                @NonNull Predicate<ClipData.Item> itemPredicate) {
            if (mClip.getItemCount() == 1) {
                Map<Boolean, Payload> result = new ArrayMap<>(1);
                result.put(itemPredicate.test(mClip.getItemAt(0)), this);
                return result;
            }
            ArrayList<ClipData.Item> accepted = new ArrayList<>();
            ArrayList<ClipData.Item> remaining = new ArrayList<>();
            for (int i = 0; i < mClip.getItemCount(); i++) {
                ClipData.Item item = mClip.getItemAt(i);
                if (itemPredicate.test(item)) {
                    accepted.add(item);
                } else {
                    remaining.add(item);
                }
            }
            Map<Boolean, Payload> result = new ArrayMap<>(2);
            if (!accepted.isEmpty()) {
                ClipData acceptedClip = new ClipData(mClip.getDescription(), accepted);
                result.put(true, new Builder(this).setClip(acceptedClip).build());
            }
            if (!remaining.isEmpty()) {
                ClipData remainingClip = new ClipData(mClip.getDescription(), remaining);
                result.put(false, new Builder(this).setClip(remainingClip).build());
            }
            return result;
        }

        /**
         * Builder for {@link Payload}.
         */
        public static final class Builder {
            @NonNull private ClipData mClip;
            private @Source int mSource;
            private @Flags int mFlags;
            @Nullable private Uri mLinkUri;
            @Nullable private Bundle mExtras;

            /**
             * Creates a new builder initialized with the data from the given builder.
             */
            public Builder(@NonNull Payload payload) {
                mClip = payload.mClip;
                mSource = payload.mSource;
                mFlags = payload.mFlags;
                mLinkUri = payload.mLinkUri;
                mExtras = payload.mExtras;
            }

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
             * Sets the data to be inserted.
             * @param clip The data to insert.
             * @return this builder
             */
            @NonNull
            public Builder setClip(@NonNull ClipData clip) {
                mClip = clip;
                return this;
            }

            /**
             * Sets the source of the operation.
             * @param source The source of the operation. See {@code SOURCE_} constants.
             * @return this builder
             */
            @NonNull
            public Builder setSource(@Source int source) {
                mSource = source;
                return this;
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
