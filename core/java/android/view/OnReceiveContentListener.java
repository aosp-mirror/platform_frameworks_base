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

import android.annotation.NonNull;
import android.annotation.Nullable;

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
 *     public ContentInfo onReceiveContent(View view, ContentInfo payload) {
 *         Pair&lt;ContentInfo, ContentInfo&gt; split =
 *                 ContentInfoCompat.partition(payload, item -&gt; item.getUri() != null);
 *         ContentInfo uriContent = split.first;
 *         ContentInfo remaining = split.second;
 *         if (uriContent != null) {
 *             ClipData clip = uriContent.getClip();
 *             for (int i = 0; i < clip.getItemCount(); i++) {
 *                 Uri uri = clip.getItemAt(i).getUri();
 *                 // ... app-specific logic to handle the URI ...
 *             }
 *         }
 *         // Return anything that we didn't handle ourselves. This preserves the default platform
 *         // behavior for text and anything else for which we are not implementing custom handling.
 *         return remaining;
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
     * implementation.
     *
     * <h3>Handling different content</h3>
     * <ul>
     *     <li>Text. If the {@link ContentInfo#getSource() source} is
     *     {@link ContentInfo#SOURCE_AUTOFILL autofill}, the view's content should be fully
     *     replaced by the passed-in text. For sources other than autofill, the passed-in text
     *     should overwrite the current selection or be inserted at the current cursor position
     *     if there is no selection.
     *     <li>Non-text content (e.g. images). The content may be inserted inline if the widget
     *     supports this, or it may be added as an attachment (could potentially be shown in a
     *     completely separate view).
     * </ul>
     *
     * <h3>URI permissions</h3>
     * <p>{@link android.content.Intent#FLAG_GRANT_READ_URI_PERMISSION Read permissions} are
     * granted automatically by the platform for any
     * {@link android.content.ContentResolver#SCHEME_CONTENT content URIs} in the payload passed
     * to this listener. Permissions are transient and will be released automatically by the
     * platform.
     * <p>Processing of content should normally be done in a service or activity.
     * For long-running processing, using {@code androidx.work.WorkManager} is recommended.
     * When implementing this, permissions should be extended to the target service or activity
     * by passing the content using {@link android.content.Intent#setClipData Intent.setClipData}
     * and {@link android.content.Intent#addFlags(int) setting} the flag
     * {@link android.content.Intent#FLAG_GRANT_READ_URI_PERMISSION FLAG_GRANT_READ_URI_PERMISSION}.
     * <p>Alternatively, if using a background thread within the current context to process the
     * content, a reference to the {@code payload} object should be maintained to ensure that
     * permissions are not revoked prematurely.
     *
     * @param view The view where the content insertion was requested.
     * @param payload The content to insert and related metadata. The payload may contain multiple
     *                items and their MIME types may be different (e.g. an image item and a text
     *                item). The payload may also contain items whose MIME type is not in the list
     *                of MIME types specified when
     *                {@link View#setOnReceiveContentListener setting} the listener. For
     *                those items, the listener may reject the content (defer to the default
     *                platform behavior) or execute some other fallback logic (e.g. show an
     *                appropriate message to the user).
     *
     * @return The portion of the passed-in content whose processing should be delegated to
     * the platform. Return null if all content was handled in some way. Actual insertion of
     * the content may be processed asynchronously in the background and may or may not
     * succeed even if this method returns null. For example, an app may end up not inserting
     * an item if it exceeds the app's size limit for that type of content.
     */
    @Nullable
    ContentInfo onReceiveContent(@NonNull View view, @NonNull ContentInfo payload);
}
