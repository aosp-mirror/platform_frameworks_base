/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A {@code RemoteInput} object specifies input to be collected from a user to be passed along with
 * an intent inside a {@link android.app.PendingIntent} that is sent.
 * Always use {@link RemoteInput.Builder} to create instances of this class.
 * <p class="note"> See
 * <a href="{@docRoot}guide/topics/ui/notifiers/notifications.html#direct">Replying
 * to notifications</a> for more information on how to use this class.
 *
 * <p>The following example adds a {@code RemoteInput} to a {@link Notification.Action},
 * sets the result key as {@code quick_reply}, and sets the label as {@code Quick reply}.
 * Users are prompted to input a response when they trigger the action. The results are sent along
 * with the intent and can be retrieved with the result key (provided to the {@link Builder}
 * constructor) from the Bundle returned by {@link #getResultsFromIntent}.
 *
 * <pre class="prettyprint">
 * public static final String KEY_QUICK_REPLY_TEXT = "quick_reply";
 * Notification.Action action = new Notification.Action.Builder(
 *         R.drawable.reply, &quot;Reply&quot;, actionIntent)
 *         <b>.addRemoteInput(new RemoteInput.Builder(KEY_QUICK_REPLY_TEXT)
 *                 .setLabel("Quick reply").build()</b>)
 *         .build();</pre>
 *
 * <p>When the {@link android.app.PendingIntent} is fired, the intent inside will contain the
 * input results if collected. To access these results, use the {@link #getResultsFromIntent}
 * function. The result values will present under the result key passed to the {@link Builder}
 * constructor.
 *
 * <pre class="prettyprint">
 * public static final String KEY_QUICK_REPLY_TEXT = "quick_reply";
 * Bundle results = RemoteInput.getResultsFromIntent(intent);
 * if (results != null) {
 *     CharSequence quickReplyResult = results.getCharSequence(KEY_QUICK_REPLY_TEXT);
 * }</pre>
 */
public final class RemoteInput implements Parcelable {
    /** Label used to denote the clip data type used for remote input transport */
    public static final String RESULTS_CLIP_LABEL = "android.remoteinput.results";

    /** Extra added to a clip data intent object to hold the text results bundle. */
    public static final String EXTRA_RESULTS_DATA = "android.remoteinput.resultsData";

    /** Extra added to a clip data intent object to hold the data results bundle. */
    private static final String EXTRA_DATA_TYPE_RESULTS_DATA =
            "android.remoteinput.dataTypeResultsData";

    /** Extra added to a clip data intent object identifying the {@link Source} of the results. */
    private static final String EXTRA_RESULTS_SOURCE = "android.remoteinput.resultsSource";

    /** @hide */
    @IntDef(prefix = {"SOURCE_"}, value = {SOURCE_FREE_FORM_INPUT, SOURCE_CHOICE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Source {}

    /** The user manually entered the data. */
    public static final int SOURCE_FREE_FORM_INPUT = 0;

    /** The user selected one of the choices from {@link #getChoices}. */
    public static final int SOURCE_CHOICE = 1;

    /** @hide */
    @IntDef(prefix = {"EDIT_CHOICES_BEFORE_SENDING_"},
            value = {EDIT_CHOICES_BEFORE_SENDING_AUTO, EDIT_CHOICES_BEFORE_SENDING_DISABLED,
                    EDIT_CHOICES_BEFORE_SENDING_ENABLED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EditChoicesBeforeSending {}

    /** The platform will determine whether choices will be edited before being sent to the app. */
    public static final int EDIT_CHOICES_BEFORE_SENDING_AUTO = 0;

    /** Tapping on a choice should send the input immediately, without letting the user edit it. */
    public static final int EDIT_CHOICES_BEFORE_SENDING_DISABLED = 1;

    /** Tapping on a choice should let the user edit the input before it is sent to the app. */
    public static final int EDIT_CHOICES_BEFORE_SENDING_ENABLED = 2;

    // Flags bitwise-ored to mFlags
    private static final int FLAG_ALLOW_FREE_FORM_INPUT = 0x1;

    // Default value for flags integer
    private static final int DEFAULT_FLAGS = FLAG_ALLOW_FREE_FORM_INPUT;

    private final String mResultKey;
    private final CharSequence mLabel;
    private final CharSequence[] mChoices;
    private final int mFlags;
    @EditChoicesBeforeSending private final int mEditChoicesBeforeSending;
    private final Bundle mExtras;
    private final ArraySet<String> mAllowedDataTypes;

    private RemoteInput(String resultKey, CharSequence label, CharSequence[] choices,
            int flags, int editChoicesBeforeSending, Bundle extras,
            ArraySet<String> allowedDataTypes) {
        this.mResultKey = resultKey;
        this.mLabel = label;
        this.mChoices = choices;
        this.mFlags = flags;
        this.mEditChoicesBeforeSending = editChoicesBeforeSending;
        this.mExtras = extras;
        this.mAllowedDataTypes = allowedDataTypes;
        if (getEditChoicesBeforeSending() == EDIT_CHOICES_BEFORE_SENDING_ENABLED
                && !getAllowFreeFormInput()) {
            throw new IllegalArgumentException(
                    "setEditChoicesBeforeSending requires setAllowFreeFormInput");
        }
    }

    /**
     * Get the key that the result of this input will be set in from the Bundle returned by
     * {@link #getResultsFromIntent} when the {@link android.app.PendingIntent} is sent.
     */
    public String getResultKey() {
        return mResultKey;
    }

    /**
     * Get the label to display to users when collecting this input.
     */
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * Get possible input choices. This can be {@code null} if there are no choices to present.
     */
    public CharSequence[] getChoices() {
        return mChoices;
    }

    /**
     * Get possible non-textual inputs that are accepted.
     * This can be {@code null} if the input does not accept non-textual values.
     * See {@link Builder#setAllowDataType}.
     */
    public Set<String> getAllowedDataTypes() {
        return mAllowedDataTypes;
    }

    /**
     * Returns true if the input only accepts data, meaning {@link #getAllowFreeFormInput}
     * is false, {@link #getChoices} is null or empty, and {@link #getAllowedDataTypes} is
     * non-null and not empty.
     */
    public boolean isDataOnly() {
        return !getAllowFreeFormInput()
                && (getChoices() == null || getChoices().length == 0)
                && !getAllowedDataTypes().isEmpty();
    }

    /**
     * Get whether or not users can provide an arbitrary value for
     * input. If you set this to {@code false}, users must select one of the
     * choices in {@link #getChoices}. An {@link IllegalArgumentException} is thrown
     * if you set this to false and {@link #getChoices} returns {@code null} or empty.
     */
    public boolean getAllowFreeFormInput() {
        return (mFlags & FLAG_ALLOW_FREE_FORM_INPUT) != 0;
    }

    /**
     * Gets whether tapping on a choice should let the user edit the input before it is sent to the
     * app.
     */
    @EditChoicesBeforeSending
    public int getEditChoicesBeforeSending() {
        return mEditChoicesBeforeSending;
    }

    /**
     * Get additional metadata carried around with this remote input.
     */
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Builder class for {@link RemoteInput} objects.
     */
    public static final class Builder {
        private final String mResultKey;
        private final ArraySet<String> mAllowedDataTypes = new ArraySet<>();
        private final Bundle mExtras = new Bundle();
        private CharSequence mLabel;
        private CharSequence[] mChoices;
        private int mFlags = DEFAULT_FLAGS;
        @EditChoicesBeforeSending
        private int mEditChoicesBeforeSending = EDIT_CHOICES_BEFORE_SENDING_AUTO;

        /**
         * Create a builder object for {@link RemoteInput} objects.
         *
         * @param resultKey the Bundle key that refers to this input when collected from the user
         */
        public Builder(@NonNull String resultKey) {
            if (resultKey == null) {
                throw new IllegalArgumentException("Result key can't be null");
            }
            mResultKey = resultKey;
        }

        /**
         * Set a label to be displayed to the user when collecting this input.
         *
         * @param label The label to show to users when they input a response
         * @return this object for method chaining
         */
        @NonNull
        public Builder setLabel(@Nullable CharSequence label) {
            mLabel = Notification.safeCharSequence(label);
            return this;
        }

        /**
         * Specifies choices available to the user to satisfy this input.
         *
         * <p>Note: Starting in Android P, these choices will always be shown on phones if the app's
         * target SDK is >= P. However, these choices may also be rendered on other types of devices
         * regardless of target SDK.
         *
         * @param choices an array of pre-defined choices for users input.
         *        You must provide a non-null and non-empty array if
         *        you disabled free form input using {@link #setAllowFreeFormInput}
         * @return this object for method chaining
         */
        @NonNull
        public Builder setChoices(@Nullable CharSequence[] choices) {
            if (choices == null) {
                mChoices = null;
            } else {
                mChoices = new CharSequence[choices.length];
                for (int i = 0; i < choices.length; i++) {
                    mChoices[i] = Notification.safeCharSequence(choices[i]);
                }
            }
            return this;
        }

        /**
         * Specifies whether the user can provide arbitrary values. This allows an input
         * to accept non-textual values. Examples of usage are an input that wants audio
         * or an image.
         *
         * @param mimeType A mime type that results are allowed to come in.
         *         Be aware that text results (see {@link #setAllowFreeFormInput}
         *         are allowed by default. If you do not want text results you will have to
         *         pass false to {@code setAllowFreeFormInput}
         * @param doAllow Whether the mime type should be allowed or not
         * @return this object for method chaining
         */
        @NonNull
        public Builder setAllowDataType(@NonNull String mimeType, boolean doAllow) {
            if (doAllow) {
                mAllowedDataTypes.add(mimeType);
            } else {
                mAllowedDataTypes.remove(mimeType);
            }
            return this;
        }

        /**
         * Specifies whether the user can provide arbitrary text values.
         *
         * @param allowFreeFormTextInput The default is {@code true}.
         *         If you specify {@code false}, you must either provide a non-null
         *         and non-empty array to {@link #setChoices}, or enable a data result
         *         in {@code setAllowDataType}. Otherwise an
         *         {@link IllegalArgumentException} is thrown
         * @return this object for method chaining
         */
        @NonNull
        public Builder setAllowFreeFormInput(boolean allowFreeFormTextInput) {
            setFlag(FLAG_ALLOW_FREE_FORM_INPUT, allowFreeFormTextInput);
            return this;
        }

        /**
         * Specifies whether tapping on a choice should let the user edit the input before it is
         * sent to the app. The default is {@link #EDIT_CHOICES_BEFORE_SENDING_AUTO}.
         *
         * It cannot be used if {@link #setAllowFreeFormInput} has been set to false.
         */
        @NonNull
        public Builder setEditChoicesBeforeSending(
                @EditChoicesBeforeSending int editChoicesBeforeSending) {
            mEditChoicesBeforeSending = editChoicesBeforeSending;
            return this;
        }

        /**
         * Merge additional metadata into this builder.
         *
         * <p>Values within the Bundle will replace existing extras values in this Builder.
         *
         * @see RemoteInput#getExtras
         */
        @NonNull
        public Builder addExtras(@NonNull Bundle extras) {
            if (extras != null) {
                mExtras.putAll(extras);
            }
            return this;
        }

        /**
         * Get the metadata Bundle used by this Builder.
         *
         * <p>The returned Bundle is shared with this Builder.
         */
        @NonNull
        public Bundle getExtras() {
            return mExtras;
        }

        private void setFlag(int mask, boolean value) {
            if (value) {
                mFlags |= mask;
            } else {
                mFlags &= ~mask;
            }
        }

        /**
         * Combine all of the options that have been set and return a new {@link RemoteInput}
         * object.
         */
        @NonNull
        public RemoteInput build() {
            return new RemoteInput(mResultKey, mLabel, mChoices, mFlags, mEditChoicesBeforeSending,
                    mExtras, mAllowedDataTypes);
        }
    }

    private RemoteInput(Parcel in) {
        mResultKey = in.readString();
        mLabel = in.readCharSequence();
        mChoices = in.readCharSequenceArray();
        mFlags = in.readInt();
        mEditChoicesBeforeSending = in.readInt();
        mExtras = in.readBundle();
        mAllowedDataTypes = (ArraySet<String>) in.readArraySet(null);
    }

    /**
     * Similar as {@link #getResultsFromIntent} but retrieves data results for a
     * specific RemoteInput result. To retrieve a value use:
     * <pre>
     * {@code
     * Map<String, Uri> results =
     *     RemoteInput.getDataResultsFromIntent(intent, REMOTE_INPUT_KEY);
     * if (results != null) {
     *   Uri data = results.get(MIME_TYPE_OF_INTEREST);
     * }
     * }
     * </pre>
     * @param intent The intent object that fired in response to an action or content intent
     *               which also had one or more remote input requested.
     * @param remoteInputResultKey The result key for the RemoteInput you want results for.
     */
    public static Map<String, Uri> getDataResultsFromIntent(
            Intent intent, String remoteInputResultKey) {
        Intent clipDataIntent = getClipDataIntentFromIntent(intent);
        if (clipDataIntent == null) {
            return null;
        }
        Map<String, Uri> results = new HashMap<>();
        Bundle extras = clipDataIntent.getExtras();
        for (String key : extras.keySet()) {
          if (key.startsWith(EXTRA_DATA_TYPE_RESULTS_DATA)) {
              String mimeType = key.substring(EXTRA_DATA_TYPE_RESULTS_DATA.length());
              if (mimeType == null || mimeType.isEmpty()) {
                  continue;
              }
              Bundle bundle = clipDataIntent.getBundleExtra(key);
              String uriStr = bundle.getString(remoteInputResultKey);
              if (uriStr == null || uriStr.isEmpty()) {
                  continue;
              }
              results.put(mimeType, Uri.parse(uriStr));
          }
        }
        return results.isEmpty() ? null : results;
    }

    /**
     * Get the remote input text results bundle from an intent. The returned Bundle will
     * contain a key/value for every result key populated with text by remote input collector.
     * Use the {@link Bundle#getCharSequence(String)} method to retrieve a value. For non-text
     * results use {@link #getDataResultsFromIntent}.
     * @param intent The intent object that fired in response to an action or content intent
     *               which also had one or more remote input requested.
     */
    public static Bundle getResultsFromIntent(Intent intent) {
        Intent clipDataIntent = getClipDataIntentFromIntent(intent);
        if (clipDataIntent == null) {
            return null;
        }
        return clipDataIntent.getExtras().getParcelable(EXTRA_RESULTS_DATA);
    }

    /**
     * Populate an intent object with the text results gathered from remote input. This method
     * should only be called by remote input collection services when sending results to a
     * pending intent.
     * @param remoteInputs The remote inputs for which results are being provided
     * @param intent The intent to add remote inputs to. The {@link ClipData}
     *               field of the intent will be modified to contain the results.
     * @param results A bundle holding the remote input results. This bundle should
     *                be populated with keys matching the result keys specified in
     *                {@code remoteInputs} with values being the CharSequence results per key.
     */
    public static void addResultsToIntent(RemoteInput[] remoteInputs, Intent intent,
            Bundle results) {
        Intent clipDataIntent = getClipDataIntentFromIntent(intent);
        if (clipDataIntent == null) {
            clipDataIntent = new Intent();  // First time we've added a result.
        }
        Bundle resultsBundle = clipDataIntent.getBundleExtra(EXTRA_RESULTS_DATA);
        if (resultsBundle == null) {
            resultsBundle = new Bundle();
        }
        for (RemoteInput remoteInput : remoteInputs) {
            Object result = results.get(remoteInput.getResultKey());
            if (result instanceof CharSequence) {
                resultsBundle.putCharSequence(remoteInput.getResultKey(), (CharSequence) result);
            }
        }
        clipDataIntent.putExtra(EXTRA_RESULTS_DATA, resultsBundle);
        intent.setClipData(ClipData.newIntent(RESULTS_CLIP_LABEL, clipDataIntent));
    }

    /**
     * Same as {@link #addResultsToIntent} but for setting data results. This is used
     * for inputs that accept non-textual results (see {@link Builder#setAllowDataType}).
     * Only one result can be provided for every mime type accepted by the RemoteInput.
     * If multiple inputs of the same mime type are expected then multiple RemoteInputs
     * should be used.
     *
     * @param remoteInput The remote input for which results are being provided
     * @param intent The intent to add remote input results to. The {@link ClipData}
     *               field of the intent will be modified to contain the results.
     * @param results A map of mime type to the Uri result for that mime type.
     */
    public static void addDataResultToIntent(RemoteInput remoteInput, Intent intent,
            Map<String, Uri> results) {
        Intent clipDataIntent = getClipDataIntentFromIntent(intent);
        if (clipDataIntent == null) {
            clipDataIntent = new Intent();  // First time we've added a result.
        }
        for (Map.Entry<String, Uri> entry : results.entrySet()) {
            String mimeType = entry.getKey();
            Uri uri = entry.getValue();
            if (mimeType == null) {
                continue;
            }
            Bundle resultsBundle =
                    clipDataIntent.getBundleExtra(getExtraResultsKeyForData(mimeType));
            if (resultsBundle == null) {
                resultsBundle = new Bundle();
            }
            resultsBundle.putString(remoteInput.getResultKey(), uri.toString());

            clipDataIntent.putExtra(getExtraResultsKeyForData(mimeType), resultsBundle);
        }
        intent.setClipData(ClipData.newIntent(RESULTS_CLIP_LABEL, clipDataIntent));
    }

    /**
     * Set the source of the RemoteInput results. This method should only be called by remote
     * input collection services (e.g.
     * {@link android.service.notification.NotificationListenerService})
     * when sending results to a pending intent.
     *
     * @see #SOURCE_FREE_FORM_INPUT
     * @see #SOURCE_CHOICE
     *
     * @param intent The intent to add remote input source to. The {@link ClipData}
     *               field of the intent will be modified to contain the source.
     * @param source The source of the results.
     */
    public static void setResultsSource(Intent intent, @Source int source) {
        Intent clipDataIntent = getClipDataIntentFromIntent(intent);
        if (clipDataIntent == null) {
            clipDataIntent = new Intent();  // First time we've added a result.
        }
        clipDataIntent.putExtra(EXTRA_RESULTS_SOURCE, source);
        intent.setClipData(ClipData.newIntent(RESULTS_CLIP_LABEL, clipDataIntent));
    }

    /**
     * Get the source of the RemoteInput results.
     *
     * @see #SOURCE_FREE_FORM_INPUT
     * @see #SOURCE_CHOICE
     *
     * @param intent The intent object that fired in response to an action or content intent
     *               which also had one or more remote input requested.
     * @return The source of the results. If no source was set, {@link #SOURCE_FREE_FORM_INPUT} will
     * be returned.
     */
    @Source
    public static int getResultsSource(Intent intent) {
        Intent clipDataIntent = getClipDataIntentFromIntent(intent);
        if (clipDataIntent == null) {
            return SOURCE_FREE_FORM_INPUT;
        }
        return clipDataIntent.getExtras().getInt(EXTRA_RESULTS_SOURCE, SOURCE_FREE_FORM_INPUT);
    }

    private static String getExtraResultsKeyForData(String mimeType) {
        return EXTRA_DATA_TYPE_RESULTS_DATA + mimeType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mResultKey);
        out.writeCharSequence(mLabel);
        out.writeCharSequenceArray(mChoices);
        out.writeInt(mFlags);
        out.writeInt(mEditChoicesBeforeSending);
        out.writeBundle(mExtras);
        out.writeArraySet(mAllowedDataTypes);
    }

    public static final @android.annotation.NonNull Creator<RemoteInput> CREATOR = new Creator<RemoteInput>() {
        @Override
        public RemoteInput createFromParcel(Parcel in) {
            return new RemoteInput(in);
        }

        @Override
        public RemoteInput[] newArray(int size) {
            return new RemoteInput[size];
        }
    };

    private static Intent getClipDataIntentFromIntent(Intent intent) {
        ClipData clipData = intent.getClipData();
        if (clipData == null) {
            return null;
        }
        ClipDescription clipDescription = clipData.getDescription();
        if (!clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_INTENT)) {
            return null;
        }
        if (!clipDescription.getLabel().equals(RESULTS_CLIP_LABEL)) {
            return null;
        }
        return clipData.getItemAt(0).getIntent();
    }
}
