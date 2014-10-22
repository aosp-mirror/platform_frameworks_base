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

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A {@code RemoteInput} object specifies input to be collected from a user to be passed along with
 * an intent inside a {@link android.app.PendingIntent} that is sent.
 * Always use {@link RemoteInput.Builder} to create instances of this class.
 * <p class="note"> See
 * <a href="{@docRoot}wear/notifications/remote-input.html">Receiving Voice Input from
 * a Notification</a> for more information on how to use this class.
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

    /** Extra added to a clip data intent object to hold the results bundle. */
    public static final String EXTRA_RESULTS_DATA = "android.remoteinput.resultsData";

    // Flags bitwise-ored to mFlags
    private static final int FLAG_ALLOW_FREE_FORM_INPUT = 0x1;

    // Default value for flags integer
    private static final int DEFAULT_FLAGS = FLAG_ALLOW_FREE_FORM_INPUT;

    private final String mResultKey;
    private final CharSequence mLabel;
    private final CharSequence[] mChoices;
    private final int mFlags;
    private final Bundle mExtras;

    private RemoteInput(String resultKey, CharSequence label, CharSequence[] choices,
            int flags, Bundle extras) {
        this.mResultKey = resultKey;
        this.mLabel = label;
        this.mChoices = choices;
        this.mFlags = flags;
        this.mExtras = extras;
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
     * Get whether or not users can provide an arbitrary value for
     * input. If you set this to {@code false}, users must select one of the
     * choices in {@link #getChoices}. An {@link IllegalArgumentException} is thrown
     * if you set this to false and {@link #getChoices} returns {@code null} or empty.
     */
    public boolean getAllowFreeFormInput() {
        return (mFlags & FLAG_ALLOW_FREE_FORM_INPUT) != 0;
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
        private CharSequence mLabel;
        private CharSequence[] mChoices;
        private int mFlags = DEFAULT_FLAGS;
        private Bundle mExtras = new Bundle();

        /**
         * Create a builder object for {@link RemoteInput} objects.
         * @param resultKey the Bundle key that refers to this input when collected from the user
         */
        public Builder(String resultKey) {
            if (resultKey == null) {
                throw new IllegalArgumentException("Result key can't be null");
            }
            mResultKey = resultKey;
        }

        /**
         * Set a label to be displayed to the user when collecting this input.
         * @param label The label to show to users when they input a response.
         * @return this object for method chaining
         */
        public Builder setLabel(CharSequence label) {
            mLabel = Notification.safeCharSequence(label);
            return this;
        }

        /**
         * Specifies choices available to the user to satisfy this input.
         * @param choices an array of pre-defined choices for users input.
         *        You must provide a non-null and non-empty array if
         *        you disabled free form input using {@link #setAllowFreeFormInput}.
         * @return this object for method chaining
         */
        public Builder setChoices(CharSequence[] choices) {
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
         * Specifies whether the user can provide arbitrary values.
         *
         * @param allowFreeFormInput The default is {@code true}.
         *         If you specify {@code false}, you must provide a non-null
         *         and non-empty array to {@link #setChoices} or an
         *         {@link IllegalArgumentException} is thrown.
         * @return this object for method chaining
         */
        public Builder setAllowFreeFormInput(boolean allowFreeFormInput) {
            setFlag(mFlags, allowFreeFormInput);
            return this;
        }

        /**
         * Merge additional metadata into this builder.
         *
         * <p>Values within the Bundle will replace existing extras values in this Builder.
         *
         * @see RemoteInput#getExtras
         */
        public Builder addExtras(Bundle extras) {
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
        public RemoteInput build() {
            return new RemoteInput(mResultKey, mLabel, mChoices, mFlags, mExtras);
        }
    }

    private RemoteInput(Parcel in) {
        mResultKey = in.readString();
        mLabel = in.readCharSequence();
        mChoices = in.readCharSequenceArray();
        mFlags = in.readInt();
        mExtras = in.readBundle();
    }

    /**
     * Get the remote input results bundle from an intent. The returned Bundle will
     * contain a key/value for every result key populated by remote input collector.
     * Use the {@link Bundle#getCharSequence(String)} method to retrieve a value.
     * @param intent The intent object that fired in response to an action or content intent
     *               which also had one or more remote input requested.
     */
    public static Bundle getResultsFromIntent(Intent intent) {
        ClipData clipData = intent.getClipData();
        if (clipData == null) {
            return null;
        }
        ClipDescription clipDescription = clipData.getDescription();
        if (!clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_INTENT)) {
            return null;
        }
        if (clipDescription.getLabel().equals(RESULTS_CLIP_LABEL)) {
            return clipData.getItemAt(0).getIntent().getExtras().getParcelable(EXTRA_RESULTS_DATA);
        }
        return null;
    }

    /**
     * Populate an intent object with the results gathered from remote input. This method
     * should only be called by remote input collection services when sending results to a
     * pending intent.
     * @param remoteInputs The remote inputs for which results are being provided
     * @param intent The intent to add remote inputs to. The {@link ClipData}
     *               field of the intent will be modified to contain the results.
     * @param results A bundle holding the remote input results. This bundle should
     *                be populated with keys matching the result keys specified in
     *                {@code remoteInputs} with values being the result per key.
     */
    public static void addResultsToIntent(RemoteInput[] remoteInputs, Intent intent,
            Bundle results) {
        Bundle resultsBundle = new Bundle();
        for (RemoteInput remoteInput : remoteInputs) {
            Object result = results.get(remoteInput.getResultKey());
            if (result instanceof CharSequence) {
                resultsBundle.putCharSequence(remoteInput.getResultKey(), (CharSequence) result);
            }
        }
        Intent clipIntent = new Intent();
        clipIntent.putExtra(EXTRA_RESULTS_DATA, resultsBundle);
        intent.setClipData(ClipData.newIntent(RESULTS_CLIP_LABEL, clipIntent));
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
        out.writeBundle(mExtras);
    }

    public static final Creator<RemoteInput> CREATOR = new Creator<RemoteInput>() {
        @Override
        public RemoteInput createFromParcel(Parcel in) {
            return new RemoteInput(in);
        }

        @Override
        public RemoteInput[] newArray(int size) {
            return new RemoteInput[size];
        }
    };
}
