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

import static android.content.ContentResolver.SCHEME_CONTENT;
import static android.view.OnReceiveContentCallback.Payload.FLAG_CONVERT_TO_PLAIN_TEXT;
import static android.view.OnReceiveContentCallback.Payload.SOURCE_AUTOFILL;
import static android.view.OnReceiveContentCallback.Payload.SOURCE_DRAG_AND_DROP;
import static android.view.OnReceiveContentCallback.Payload.SOURCE_INPUT_METHOD;

import static java.util.Collections.singleton;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.ArraySet;
import android.util.Log;
import android.view.OnReceiveContentCallback;
import android.view.OnReceiveContentCallback.Payload.Flags;
import android.view.OnReceiveContentCallback.Payload.Source;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

/**
 * Default implementation of {@link android.view.OnReceiveContentCallback} for editable
 * {@link TextView} components. This class handles insertion of text (plain text, styled text, HTML,
 * etc) but not images or other content. This class can be used as a base class for an
 * implementation of {@link android.view.OnReceiveContentCallback} for a {@link TextView}, to
 * provide consistent behavior for insertion of text.
 */
public class TextViewOnReceiveContentCallback implements OnReceiveContentCallback<TextView> {
    private static final String LOG_TAG = "OnReceiveContent";

    private static final String MIME_TYPE_ALL_TEXT = "text/*";
    private static final Set<String> MIME_TYPES_ALL_TEXT = singleton(MIME_TYPE_ALL_TEXT);

    @Nullable private InputConnectionInfo mInputConnectionInfo;
    @Nullable private ArraySet<String> mCachedSupportedMimeTypes;

    @Override
    public boolean onReceiveContent(@NonNull TextView view, @NonNull Payload payload) {
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(LOG_TAG, "onReceive: " + payload);
        }
        ClipData clip = payload.getClip();
        @Source int source = payload.getSource();
        @Flags int flags = payload.getFlags();
        if (source == SOURCE_INPUT_METHOD) {
            // InputConnection.commitContent() should only be used for non-text input which is not
            // supported by the default implementation.
            return false;
        }
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

    private boolean onReceiveForAutofill(@NonNull TextView view, @NonNull ClipData clip,
            @Flags int flags) {
        if (isUsageOfImeCommitContentEnabled(view)) {
            clip = handleNonTextViaImeCommitContent(clip);
            if (clip == null) {
                if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                    Log.v(LOG_TAG, "onReceive: Handled via IME");
                }
                return true;
            }
        }
        final CharSequence text = coerceToText(clip, view.getContext(), flags);
        // First autofill it...
        view.setText(text);
        // ...then move cursor to the end.
        final Editable editable = (Editable) view.getText();
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

    /**
     * On Android S and above, the platform can provide non-text suggestions (e.g. images) via the
     * augmented autofill framework (see
     * <a href="/guide/topics/text/autofill-services">autofill services</a>). In order for an app to
     * be able to handle these suggestions, it must normally implement the
     * {@link android.view.OnReceiveContentCallback} API. To make the adoption of this smoother for
     * apps that have previously implemented the
     * {@link android.view.inputmethod.InputConnection#commitContent(InputContentInfo, int, Bundle)}
     * API, we reuse that API as a fallback if {@link android.view.OnReceiveContentCallback} is not
     * yet implemented by the app. This fallback is only enabled on Android S. This change ID
     * disables the fallback, such that apps targeting Android T and above must implement the
     * {@link android.view.OnReceiveContentCallback} API in order to accept non-text suggestions.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.S) // Enabled on Android T and higher
    private static final long AUTOFILL_NON_TEXT_REQUIRES_ON_RECEIVE_CONTENT_CALLBACK = 163400105L;

    /**
     * Returns true if we can use the IME {@link InputConnection#commitContent} API in order handle
     * non-text content.
     */
    private static boolean isUsageOfImeCommitContentEnabled(@NonNull View view) {
        if (view.getOnReceiveContentMimeTypes() != null) {
            if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                Log.v(LOG_TAG, "Fallback to commitContent disabled (custom callback is set)");
            }
            return false;
        }
        if (Compatibility.isChangeEnabled(AUTOFILL_NON_TEXT_REQUIRES_ON_RECEIVE_CONTENT_CALLBACK)) {
            if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                Log.v(LOG_TAG, "Fallback to commitContent disabled (target SDK is above S)");
            }
            return false;
        }
        return true;
    }

    private static final class InputConnectionInfo {
        @NonNull private final WeakReference<InputConnection> mInputConnection;
        @NonNull private final String[] mEditorInfoContentMimeTypes;

        private InputConnectionInfo(@NonNull InputConnection inputConnection,
                @NonNull String[] editorInfoContentMimeTypes) {
            mInputConnection = new WeakReference<>(inputConnection);
            mEditorInfoContentMimeTypes = editorInfoContentMimeTypes;
        }

        @Override
        public String toString() {
            return "InputConnectionInfo{"
                    + "mimeTypes=" + Arrays.toString(mEditorInfoContentMimeTypes)
                    + ", ic=" + mInputConnection
                    + '}';
        }
    }

    /**
     * Invoked by the platform when an {@link InputConnection} is successfully created for the view
     * that owns this callback instance.
     */
    void setInputConnectionInfo(@NonNull InputConnection ic, @NonNull EditorInfo editorInfo) {
        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(LOG_TAG, "setInputConnectionInfo: "
                    + Arrays.toString(editorInfo.contentMimeTypes));
        }
        String[] contentMimeTypes = editorInfo.contentMimeTypes;
        if (contentMimeTypes == null || contentMimeTypes.length == 0) {
            mInputConnectionInfo = null;
        } else {
            mInputConnectionInfo = new InputConnectionInfo(ic, contentMimeTypes);
        }
    }

    /**
     * Invoked by the platform when an {@link InputConnection} is closed for the view that owns this
     * callback instance.
     */
    void clearInputConnectionInfo() {
        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(LOG_TAG, "clearInputConnectionInfo: " + mInputConnectionInfo);
        }
        mInputConnectionInfo = null;
    }

    // TODO(b/168253885): Use this to populate the assist structure for Autofill

    /** @hide */
    @VisibleForTesting
    public Set<String> getMimeTypes(TextView view) {
        if (!isUsageOfImeCommitContentEnabled(view)) {
            return MIME_TYPES_ALL_TEXT;
        }
        return getSupportedMimeTypesAugmentedWithImeCommitContentMimeTypes();
    }

    private Set<String> getSupportedMimeTypesAugmentedWithImeCommitContentMimeTypes() {
        InputConnectionInfo icInfo = mInputConnectionInfo;
        if (icInfo == null) {
            if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                Log.v(LOG_TAG, "getSupportedMimeTypes: No usable EditorInfo/InputConnection");
            }
            return MIME_TYPES_ALL_TEXT;
        }
        String[] editorInfoContentMimeTypes = icInfo.mEditorInfoContentMimeTypes;
        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(LOG_TAG, "getSupportedMimeTypes: Augmenting with EditorInfo.contentMimeTypes: "
                    + Arrays.toString(editorInfoContentMimeTypes));
        }
        ArraySet<String> supportedMimeTypes = mCachedSupportedMimeTypes;
        if (canReuse(supportedMimeTypes, editorInfoContentMimeTypes)) {
            return supportedMimeTypes;
        }
        supportedMimeTypes = new ArraySet<>(editorInfoContentMimeTypes);
        supportedMimeTypes.add(MIME_TYPE_ALL_TEXT);
        mCachedSupportedMimeTypes = supportedMimeTypes;
        return supportedMimeTypes;
    }

    /**
     * We want to avoid creating a new set on every invocation of
     * {@link #getSupportedMimeTypesAugmentedWithImeCommitContentMimeTypes()}.
     * This method will check if the cached set of MIME types matches the data in the given array
     * from {@link EditorInfo} or if a new set should be created. The custom logic is needed for
     * comparing the data because the set contains the additional "text/*" MIME type.
     *
     * @param cachedMimeTypes Previously cached set of MIME types.
     * @param newEditorInfoMimeTypes MIME types from {@link EditorInfo}.
     *
     * @return Returns true if the data in the given cached set matches the data in the array.
     *
     * @hide
     */
    @VisibleForTesting
    public static boolean canReuse(@Nullable ArraySet<String> cachedMimeTypes,
            @NonNull String[] newEditorInfoMimeTypes) {
        if (cachedMimeTypes == null) {
            return false;
        }
        if (newEditorInfoMimeTypes.length != cachedMimeTypes.size()
                && newEditorInfoMimeTypes.length != (cachedMimeTypes.size() - 1)) {
            return false;
        }
        final boolean ignoreAllTextMimeType =
                newEditorInfoMimeTypes.length == (cachedMimeTypes.size() - 1);
        for (String mimeType : cachedMimeTypes) {
            if (ignoreAllTextMimeType && mimeType.equals(MIME_TYPE_ALL_TEXT)) {
                continue;
            }
            boolean present = false;
            for (String editorInfoContentMimeType : newEditorInfoMimeTypes) {
                if (editorInfoContentMimeType.equals(mimeType)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tries to insert the content in the clip into the app via the image keyboard API. If all the
     * items in the clip are successfully inserted, returns null. If one or more of the items in the
     * clip cannot be inserted, returns a non-null clip that contains the items that were not
     * inserted.
     */
    @Nullable
    private ClipData handleNonTextViaImeCommitContent(@NonNull ClipData clip) {
        ClipDescription description = clip.getDescription();
        if (!containsUri(clip) || containsOnlyText(clip)) {
            if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                Log.v(LOG_TAG, "onReceive: Clip doesn't contain any non-text URIs: "
                        + description);
            }
            return clip;
        }

        InputConnectionInfo icInfo = mInputConnectionInfo;
        InputConnection inputConnection = (icInfo != null) ? icInfo.mInputConnection.get() : null;
        if (inputConnection == null) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onReceive: No usable EditorInfo/InputConnection");
            }
            return clip;
        }
        String[] editorInfoContentMimeTypes = icInfo.mEditorInfoContentMimeTypes;
        if (!isClipMimeTypeSupported(editorInfoContentMimeTypes, clip.getDescription())) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG,
                        "onReceive: MIME type is not supported by the app's commitContent impl");
            }
            return clip;
        }

        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(LOG_TAG, "onReceive: Trying to insert via IME: " + description);
        }
        ArrayList<ClipData.Item> remainingItems = new ArrayList<>(0);
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            Uri uri = item.getUri();
            if (uri == null || !SCHEME_CONTENT.equals(uri.getScheme())) {
                if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                    Log.v(LOG_TAG, "onReceive: No content URI in item: uri=" + uri);
                }
                remainingItems.add(item);
                continue;
            }
            if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                Log.v(LOG_TAG, "onReceive: Calling commitContent: uri=" + uri);
            }
            InputContentInfo contentInfo = new InputContentInfo(uri, description);
            if (!inputConnection.commitContent(contentInfo, 0, null)) {
                if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                    Log.v(LOG_TAG, "onReceive: Call to commitContent returned false: uri=" + uri);
                }
                remainingItems.add(item);
            }
        }
        if (remainingItems.isEmpty()) {
            return null;
        }
        return new ClipData(description, remainingItems);
    }

    private static boolean isClipMimeTypeSupported(@NonNull String[] supportedMimeTypes,
            @NonNull ClipDescription description) {
        for (String imeSupportedMimeType : supportedMimeTypes) {
            if (description.hasMimeType(imeSupportedMimeType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUri(@NonNull ClipData clip) {
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item.getUri() != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsOnlyText(@NonNull ClipData clip) {
        ClipDescription description = clip.getDescription();
        for (int i = 0; i < description.getMimeTypeCount(); i++) {
            String mimeType = description.getMimeType(i);
            if (!mimeType.startsWith("text/")) {
                return false;
            }
        }
        return true;
    }
}
