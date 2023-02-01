/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.service.autofill;

import static android.service.autofill.AutofillServiceHelper.assertValid;
import static android.view.autofill.Helper.sDebug;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.IntentSender;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * Information used to indicate that an {@link AutofillService} is interested on saving the
 * user-inputed data for future use, through a
 * {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)}
 * call.
 *
 * <p>A {@link SaveInfo} is always associated with a {@link FillResponse}, and it contains at least
 * two pieces of information:
 *
 * <ol>
 *   <li>The type(s) of user data (like password or credit card info) that would be saved.
 *   <li>The minimum set of views (represented by their {@link AutofillId}) that need to be changed
 *       to trigger a save request.
 * </ol>
 *
 * <p>Typically, the {@link SaveInfo} contains the same {@code id}s as the {@link Dataset}:
 *
 * <pre class="prettyprint">
 *   new FillResponse.Builder()
 *       .addDataset(new Dataset.Builder()
 *           .setValue(id1, AutofillValue.forText("homer"), createPresentation("homer")) // username
 *           .setValue(id2, AutofillValue.forText("D'OH!"), createPresentation("password for homer")) // password
 *           .build())
 *       .setSaveInfo(new SaveInfo.Builder(
 *           SaveInfo.SAVE_DATA_TYPE_USERNAME | SaveInfo.SAVE_DATA_TYPE_PASSWORD,
 *           new AutofillId[] { id1, id2 }).build())
 *       .build();
 * </pre>
 *
 * <p>The save type flags are used to display the appropriate strings in the autofill save UI.
 * You can pass multiple values, but try to keep it short if possible. In the above example, just
 * {@code SaveInfo.SAVE_DATA_TYPE_PASSWORD} would be enough.
 *
 * <p>There might be cases where the {@link AutofillService} knows how to fill the screen,
 * but the user has no data for it. In that case, the {@link FillResponse} should contain just the
 * {@link SaveInfo}, but no {@link Dataset Datasets}:
 *
 * <pre class="prettyprint">
 *   new FillResponse.Builder()
 *       .setSaveInfo(new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD,
 *           new AutofillId[] { id1, id2 }).build())
 *       .build();
 * </pre>
 *
 * <p>There might be cases where the user data in the {@link AutofillService} is enough
 * to populate some fields but not all, and the service would still be interested on saving the
 * other fields. In that case, the service could set the
 * {@link SaveInfo.Builder#setOptionalIds(AutofillId[])} as well:
 *
 * <pre class="prettyprint">
 *   new FillResponse.Builder()
 *       .addDataset(new Dataset.Builder()
 *           .setValue(id1, AutofillValue.forText("742 Evergreen Terrace"),
 *               createPresentation("742 Evergreen Terrace")) // street
 *           .setValue(id2, AutofillValue.forText("Springfield"),
 *               createPresentation("Springfield")) // city
 *           .build())
 *       .setSaveInfo(new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_ADDRESS,
 *           new AutofillId[] { id1, id2 }) // street and  city
 *           .setOptionalIds(new AutofillId[] { id3, id4 }) // state and zipcode
 *           .build())
 *       .build();
 * </pre>
 *
 * <a name="TriggeringSaveRequest"></a>
 * <h3>Triggering a save request</h3>
 *
 * <p>The {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)} can be triggered after
 * any of the following events:
 * <ul>
 *   <li>The {@link Activity} finishes.
 *   <li>The app explicitly calls {@link AutofillManager#commit()}.
 *   <li>All required views become invisible (if the {@link SaveInfo} was created with the
 *       {@link #FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE} flag).
 *   <li>The user clicks a specific view (defined by {@link Builder#setTriggerId(AutofillId)}.
 * </ul>
 *
 * <p>But it is only triggered when all conditions below are met:
 * <ul>
 *   <li>The {@link SaveInfo} associated with the {@link FillResponse} is not {@code null} neither
 *       has the {@link #FLAG_DELAY_SAVE} flag.
 *   <li>The {@link AutofillValue}s of all required views (as set by the {@code requiredIds} passed
 *       to the {@link SaveInfo.Builder} constructor are not empty.
 *   <li>The {@link AutofillValue} of at least one view (be it required or optional) has changed
 *       (i.e., it's neither the same value passed in a {@link Dataset}, nor the initial value
 *       presented in the view).
 *   <li>There is no {@link Dataset} in the last {@link FillResponse} that completely matches the
 *       screen state (i.e., all required and optional fields in the dataset have the same value as
 *       the fields in the screen).
 *   <li>The user explicitly tapped the autofill save UI asking to save data for autofill.
 * </ul>
 *
 * <a name="CustomizingSaveUI"></a>
 * <h3>Customizing the autofill save UI</h3>
 *
 * <p>The service can also customize some aspects of the autofill save UI:
 * <ul>
 *   <li>Add a simple subtitle by calling {@link Builder#setDescription(CharSequence)}.
 *   <li>Add a customized subtitle by calling
 *       {@link Builder#setCustomDescription(CustomDescription)}.
 *   <li>Customize the button used to reject the save request by calling
 *       {@link Builder#setNegativeAction(int, IntentSender)}.
 *   <li>Decide whether the UI should be shown based on the user input validation by calling
 *       {@link Builder#setValidator(Validator)}.
 * </ul>
 */
public final class SaveInfo implements Parcelable {

    /**
     * Type used when the service can save the contents of a screen, but cannot describe what
     * the content is for.
     */
    public static final int SAVE_DATA_TYPE_GENERIC = 0x0;

    /**
     * Type used when the {@link FillResponse} represents user credentials that have a password.
     */
    public static final int SAVE_DATA_TYPE_PASSWORD = 0x01;

    /**
     * Type used on when the {@link FillResponse} represents a physical address (such as street,
     * city, state, etc).
     */
    public static final int SAVE_DATA_TYPE_ADDRESS = 0x02;

    /**
     * Type used when the {@link FillResponse} represents a credit card.
     */
    public static final int SAVE_DATA_TYPE_CREDIT_CARD = 0x04;

    /**
     * Type used when the {@link FillResponse} represents just an username, without a password.
     */
    public static final int SAVE_DATA_TYPE_USERNAME = 0x08;

    /**
     * Type used when the {@link FillResponse} represents just an email address, without a password.
     */
    public static final int SAVE_DATA_TYPE_EMAIL_ADDRESS = 0x10;

    /**
     * Type used when the {@link FillResponse} represents a debit card.
     */
    public static final int SAVE_DATA_TYPE_DEBIT_CARD = 0x20;

    /**
     * Type used when the {@link FillResponse} represents a payment card except for credit and
     * debit cards.
     */
    public static final int SAVE_DATA_TYPE_PAYMENT_CARD = 0x40;

    /**
     * Type used when the {@link FillResponse} represents a card that does not a specified card or
     * cannot identify what the card is for.
     */
    public static final int SAVE_DATA_TYPE_GENERIC_CARD = 0x80;

    /**
     * Style for the negative button of the save UI to cancel the
     * save operation. In this case, the user tapping the negative
     * button signals that they would prefer to not save the filled
     * content.
     */
    public static final int NEGATIVE_BUTTON_STYLE_CANCEL = 0;

    /**
     * Style for the negative button of the save UI to reject the
     * save operation. This could be useful if the user needs to
     * opt-in your service and the save prompt is an advertisement
     * of the potential value you can add to the user. In this
     * case, the user tapping the negative button sends a strong
     * signal that the feature may not be useful and you may
     * consider some backoff strategy.
     */
    public static final int NEGATIVE_BUTTON_STYLE_REJECT = 1;

    /**
     * Style for the negative button of the save UI to never do the
     * save operation. This means that the user does not need to save
     * any data on this activity or application. Once the user tapping
     * the negative button, the service should never trigger the save
     * UI again. In addition to this, must consider providing restore
     * options for the user.
     */
    public static final int NEGATIVE_BUTTON_STYLE_NEVER = 2;

    /** @hide */
    @IntDef(prefix = { "NEGATIVE_BUTTON_STYLE_" }, value = {
            NEGATIVE_BUTTON_STYLE_CANCEL,
            NEGATIVE_BUTTON_STYLE_REJECT,
            NEGATIVE_BUTTON_STYLE_NEVER
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface NegativeButtonStyle{}

    /**
     * Style for the positive button of save UI to request the save operation.
     * In this case, the user tapping the positive button signals that they
     * agrees to save the filled content.
     */
    public static final int POSITIVE_BUTTON_STYLE_SAVE = 0;

    /**
     * Style for the positive button of save UI to have next action before the save operation.
     * This could be useful if the filled content contains sensitive personally identifiable
     * information and then requires user confirmation or verification. In this case, the user
     * tapping the positive button signals that they would complete the next required action
     * to save the filled content.
     */
    public static final int POSITIVE_BUTTON_STYLE_CONTINUE = 1;

    /** @hide */
    @IntDef(prefix = { "POSITIVE_BUTTON_STYLE_" }, value = {
            POSITIVE_BUTTON_STYLE_SAVE,
            POSITIVE_BUTTON_STYLE_CONTINUE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface PositiveButtonStyle{}

    /** @hide */
    @IntDef(flag = true, prefix = { "SAVE_DATA_TYPE_" }, value = {
            SAVE_DATA_TYPE_GENERIC,
            SAVE_DATA_TYPE_PASSWORD,
            SAVE_DATA_TYPE_ADDRESS,
            SAVE_DATA_TYPE_CREDIT_CARD,
            SAVE_DATA_TYPE_USERNAME,
            SAVE_DATA_TYPE_EMAIL_ADDRESS,
            SAVE_DATA_TYPE_DEBIT_CARD,
            SAVE_DATA_TYPE_PAYMENT_CARD,
            SAVE_DATA_TYPE_GENERIC_CARD
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface SaveDataType{}

    /**
     * Usually, a save request is only automatically <a href="#TriggeringSaveRequest">triggered</a>
     * once the {@link Activity} finishes. If this flag is set, it is triggered once all saved views
     * become invisible.
     */
    public static final int FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE = 0x1;

    /**
     * By default, a save request is automatically <a href="#TriggeringSaveRequest">triggered</a>
     * once the {@link Activity} finishes. If this flag is set, finishing the activity doesn't
     * trigger a save request.
     *
     * <p>This flag is typically used in conjunction with {@link Builder#setTriggerId(AutofillId)}.
     */
    public static final int FLAG_DONT_SAVE_ON_FINISH = 0x2;


    /**
     * Postpone the autofill save UI.
     *
     * <p>If flag is set, the autofill save UI is not triggered when the
     * autofill context associated with the response associated with this {@link SaveInfo} is
     * committed (with {@link AutofillManager#commit()}). Instead, the {@link FillContext}
     * is delivered in future fill requests (with {@link
     * AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)})
     * and save request (with {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)})
     * of an activity belonging to the same task.
     *
     * <p>This flag should be used when the service detects that the application uses
     * multiple screens to implement an autofillable workflow (for example, one screen for the
     * username field, another for password).
     */
    // TODO(b/113281366): improve documentation: add example, document relationship with other
    // flagss, etc...
    public static final int FLAG_DELAY_SAVE = 0x4;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE,
            FLAG_DONT_SAVE_ON_FINISH,
            FLAG_DELAY_SAVE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface SaveInfoFlags{}

    private final @SaveDataType int mType;
    private final @NegativeButtonStyle int mNegativeButtonStyle;
    private final @PositiveButtonStyle int mPositiveButtonStyle;
    private final IntentSender mNegativeActionListener;
    private final AutofillId[] mRequiredIds;
    private final AutofillId[] mOptionalIds;
    private final CharSequence mDescription;
    private final int mFlags;
    private final CustomDescription mCustomDescription;
    private final InternalValidator mValidator;
    private final InternalSanitizer[] mSanitizerKeys;
    private final AutofillId[][] mSanitizerValues;
    private final AutofillId mTriggerId;

    private SaveInfo(Builder builder) {
        mType = builder.mType;
        mNegativeButtonStyle = builder.mNegativeButtonStyle;
        mNegativeActionListener = builder.mNegativeActionListener;
        mPositiveButtonStyle = builder.mPositiveButtonStyle;
        mRequiredIds = builder.mRequiredIds;
        mOptionalIds = builder.mOptionalIds;
        mDescription = builder.mDescription;
        mFlags = builder.mFlags;
        mCustomDescription = builder.mCustomDescription;
        mValidator = builder.mValidator;
        if (builder.mSanitizers == null) {
            mSanitizerKeys = null;
            mSanitizerValues = null;
        } else {
            final int size = builder.mSanitizers.size();
            mSanitizerKeys = new InternalSanitizer[size];
            mSanitizerValues = new AutofillId[size][];
            for (int i = 0; i < size; i++) {
                mSanitizerKeys[i] = builder.mSanitizers.keyAt(i);
                mSanitizerValues[i] = builder.mSanitizers.valueAt(i);
            }
        }
        mTriggerId = builder.mTriggerId;
    }

    /** @hide */
    public @NegativeButtonStyle int getNegativeActionStyle() {
        return mNegativeButtonStyle;
    }

    /** @hide */
    public @Nullable IntentSender getNegativeActionListener() {
        return mNegativeActionListener;
    }

    /** @hide */
    public @PositiveButtonStyle int getPositiveActionStyle() {
        return mPositiveButtonStyle;
    }

    /** @hide */
    public @Nullable AutofillId[] getRequiredIds() {
        return mRequiredIds;
    }

    /** @hide */
    public @Nullable AutofillId[] getOptionalIds() {
        return mOptionalIds;
    }

    /** @hide */
    public @SaveDataType int getType() {
        return mType;
    }

    /** @hide */
    public @SaveInfoFlags int getFlags() {
        return mFlags;
    }

    /** @hide */
    public CharSequence getDescription() {
        return mDescription;
    }

     /** @hide */
    @Nullable
    public CustomDescription getCustomDescription() {
        return mCustomDescription;
    }

    /** @hide */
    @Nullable
    public InternalValidator getValidator() {
        return mValidator;
    }

    /** @hide */
    @Nullable
    public InternalSanitizer[] getSanitizerKeys() {
        return mSanitizerKeys;
    }

    /** @hide */
    @Nullable
    public AutofillId[][] getSanitizerValues() {
        return mSanitizerValues;
    }

    /** @hide */
    @Nullable
    public AutofillId getTriggerId() {
        return mTriggerId;
    }

    /**
     * A builder for {@link SaveInfo} objects.
     */
    public static final class Builder {

        private final @SaveDataType int mType;
        private @NegativeButtonStyle int mNegativeButtonStyle = NEGATIVE_BUTTON_STYLE_CANCEL;
        private @PositiveButtonStyle int mPositiveButtonStyle = POSITIVE_BUTTON_STYLE_SAVE;
        private IntentSender mNegativeActionListener;
        private final AutofillId[] mRequiredIds;
        private AutofillId[] mOptionalIds;
        private CharSequence mDescription;
        private boolean mDestroyed;
        private int mFlags;
        private CustomDescription mCustomDescription;
        private InternalValidator mValidator;
        private ArrayMap<InternalSanitizer, AutofillId[]> mSanitizers;
        // Set used to validate against duplicate ids.
        private ArraySet<AutofillId> mSanitizerIds;
        private AutofillId mTriggerId;

        /**
         * Creates a new builder.
         *
         * @param type the type of information the associated {@link FillResponse} represents. It
         * can be any combination of {@link SaveInfo#SAVE_DATA_TYPE_GENERIC},
         * {@link SaveInfo#SAVE_DATA_TYPE_PASSWORD},
         * {@link SaveInfo#SAVE_DATA_TYPE_ADDRESS}, {@link SaveInfo#SAVE_DATA_TYPE_CREDIT_CARD},
         * {@link SaveInfo#SAVE_DATA_TYPE_DEBIT_CARD}, {@link SaveInfo#SAVE_DATA_TYPE_PAYMENT_CARD},
         * {@link SaveInfo#SAVE_DATA_TYPE_GENERIC_CARD}, {@link SaveInfo#SAVE_DATA_TYPE_USERNAME},
         * or {@link SaveInfo#SAVE_DATA_TYPE_EMAIL_ADDRESS}.
         * @param requiredIds ids of all required views that will trigger a save request.
         *
         * <p>See {@link SaveInfo} for more info.
         *
         * @throws IllegalArgumentException if {@code requiredIds} is {@code null} or empty, or if
         * it contains any {@code null} entry.
         */
        public Builder(@SaveDataType int type, @NonNull AutofillId[] requiredIds) {
            mType = type;
            mRequiredIds = assertValid(requiredIds);
        }

        /**
         * Creates a new builder when no id is required.
         *
         * <p>When using this builder, caller must call {@link #setOptionalIds(AutofillId[])} before
         * calling {@link #build()}.
         *
         * @param type the type of information the associated {@link FillResponse} represents. It
         * can be any combination of {@link SaveInfo#SAVE_DATA_TYPE_GENERIC},
         * {@link SaveInfo#SAVE_DATA_TYPE_PASSWORD},
         * {@link SaveInfo#SAVE_DATA_TYPE_ADDRESS}, {@link SaveInfo#SAVE_DATA_TYPE_CREDIT_CARD},
         * {@link SaveInfo#SAVE_DATA_TYPE_DEBIT_CARD}, {@link SaveInfo#SAVE_DATA_TYPE_PAYMENT_CARD},
         * {@link SaveInfo#SAVE_DATA_TYPE_GENERIC_CARD}, {@link SaveInfo#SAVE_DATA_TYPE_USERNAME},
         * or {@link SaveInfo#SAVE_DATA_TYPE_EMAIL_ADDRESS}.
         *
         * <p>See {@link SaveInfo} for more info.
         */
        public Builder(@SaveDataType int type) {
            mType = type;
            mRequiredIds = null;
        }

        /**
         * Sets flags changing the save behavior.
         *
         * @param flags {@link #FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE},
         * {@link #FLAG_DONT_SAVE_ON_FINISH}, {@link #FLAG_DELAY_SAVE}, or {@code 0}.
         * @return This builder.
         */
        public @NonNull Builder setFlags(@SaveInfoFlags int flags) {
            throwIfDestroyed();

            mFlags = Preconditions.checkFlagsArgument(flags,
                    FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE | FLAG_DONT_SAVE_ON_FINISH
                            | FLAG_DELAY_SAVE);
            return this;
        }

        /**
         * Sets the ids of additional, optional views the service would be interested to save.
         *
         * <p>See {@link SaveInfo} for more info.
         *
         * @param ids The ids of the optional views.
         * @return This builder.
         *
         * @throws IllegalArgumentException if {@code ids} is {@code null} or empty, or if
         * it contains any {@code null} entry.
         */
        public @NonNull Builder setOptionalIds(@NonNull AutofillId[] ids) {
            throwIfDestroyed();
            mOptionalIds = assertValid(ids);
            return this;
        }

        /**
         * Sets an optional description to be shown in the UI when the user is asked to save.
         *
         * <p>Typically, it describes how the data will be stored by the service, so it can help
         * users to decide whether they can trust the service to save their data.
         *
         * @param description a succint description.
         * @return This Builder.
         *
         * @throws IllegalStateException if this call was made after calling
         * {@link #setCustomDescription(CustomDescription)}.
         */
        public @NonNull Builder setDescription(@Nullable CharSequence description) {
            throwIfDestroyed();
            Preconditions.checkState(mCustomDescription == null,
                    "Can call setDescription() or setCustomDescription(), but not both");
            mDescription = description;
            return this;
        }

        /**
         * Sets a custom description to be shown in the UI when the user is asked to save.
         *
         * <p>Typically used when the service must show more info about the object being saved,
         * like a credit card logo, masked number, and expiration date.
         *
         * @param customDescription the custom description.
         * @return This Builder.
         *
         * @throws IllegalStateException if this call was made after calling
         * {@link #setDescription(CharSequence)}.
         */
        public @NonNull Builder setCustomDescription(@NonNull CustomDescription customDescription) {
            throwIfDestroyed();
            Preconditions.checkState(mDescription == null,
                    "Can call setDescription() or setCustomDescription(), but not both");
            mCustomDescription = customDescription;
            return this;
        }

        /**
         * Sets the style and listener for the negative save action.
         *
         * <p>This allows an autofill service to customize the style and be
         * notified when the user selects the negative action in the save
         * UI. Note that selecting the negative action regardless of its style
         * and listener being customized would dismiss the save UI and if a
         * custom listener intent is provided then this intent is
         * started. The default style is {@link #NEGATIVE_BUTTON_STYLE_CANCEL}</p>
         *
         * @param style The action style.
         * @param listener The action listener.
         * @return This builder.
         *
         * @see #NEGATIVE_BUTTON_STYLE_CANCEL
         * @see #NEGATIVE_BUTTON_STYLE_REJECT
         * @see #NEGATIVE_BUTTON_STYLE_NEVER
         *
         * @throws IllegalArgumentException If the style is invalid
         */
        public @NonNull Builder setNegativeAction(@NegativeButtonStyle int style,
                @Nullable IntentSender listener) {
            throwIfDestroyed();
            Preconditions.checkArgumentInRange(style, NEGATIVE_BUTTON_STYLE_CANCEL,
                    NEGATIVE_BUTTON_STYLE_NEVER, "style");
            mNegativeButtonStyle = style;
            mNegativeActionListener = listener;
            return this;
        }

        /**
         * Sets the style for the positive save action.
         *
         * <p>This allows an autofill service to customize the style of the
         * positive action in the save UI. Note that selecting the positive
         * action regardless of its style would dismiss the save UI and calling
         * into the {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback) save request}.
         * The service should take the next action if selecting style
         * {@link #POSITIVE_BUTTON_STYLE_CONTINUE}. The default style is
         * {@link #POSITIVE_BUTTON_STYLE_SAVE}
         *
         * @param style The action style.
         * @return This builder.
         *
         * @see #POSITIVE_BUTTON_STYLE_SAVE
         * @see #POSITIVE_BUTTON_STYLE_CONTINUE
         *
         * @throws IllegalArgumentException If the style is invalid
         */
        public @NonNull Builder setPositiveAction(@PositiveButtonStyle int style) {
            throwIfDestroyed();
            Preconditions.checkArgumentInRange(style, POSITIVE_BUTTON_STYLE_SAVE,
                    POSITIVE_BUTTON_STYLE_CONTINUE, "style");
            mPositiveButtonStyle = style;
            return this;
        }

        /**
         * Sets an object used to validate the user input - if the input is not valid, the
         * autofill save UI is not shown.
         *
         * <p>Typically used to validate credit card numbers. Examples:
         *
         * <p>Validator for a credit number that must have exactly 16 digits:
         *
         * <pre class="prettyprint">
         * Validator validator = new RegexValidator(ccNumberId, Pattern.compile(""^\\d{16}$"))
         * </pre>
         *
         * <p>Validator for a credit number that must pass a Luhn checksum and either have
         * 16 digits, or 15 digits starting with 108:
         *
         * <pre class="prettyprint">
         * import static android.service.autofill.Validators.and;
         * import static android.service.autofill.Validators.or;
         *
         * Validator validator =
         *   and(
         *     new LuhnChecksumValidator(ccNumberId),
         *     or(
         *       new RegexValidator(ccNumberId, Pattern.compile("^\\d{16}$")),
         *       new RegexValidator(ccNumberId, Pattern.compile("^108\\d{12}$"))
         *     )
         *   );
         * </pre>
         *
         * <p><b>Note:</b> the example above is just for illustrative purposes; the same validator
         * could be created using a single regex for the {@code OR} part:
         *
         * <pre class="prettyprint">
         * Validator validator =
         *   and(
         *     new LuhnChecksumValidator(ccNumberId),
         *     new RegexValidator(ccNumberId, Pattern.compile(""^(\\d{16}|108\\d{12})$"))
         *   );
         * </pre>
         *
         * <p>Validator for a credit number contained in just 4 fields and that must have exactly
         * 4 digits on each field:
         *
         * <pre class="prettyprint">
         * import static android.service.autofill.Validators.and;
         *
         * Validator validator =
         *   and(
         *     new RegexValidator(ccNumberId1, Pattern.compile("^\\d{4}$")),
         *     new RegexValidator(ccNumberId2, Pattern.compile("^\\d{4}$")),
         *     new RegexValidator(ccNumberId3, Pattern.compile("^\\d{4}$")),
         *     new RegexValidator(ccNumberId4, Pattern.compile("^\\d{4}$"))
         *   );
         * </pre>
         *
         * @param validator an implementation provided by the Android System.
         * @return this builder.
         *
         * @throws IllegalArgumentException if {@code validator} is not a class provided
         * by the Android System.
         */
        public @NonNull Builder setValidator(@NonNull Validator validator) {
            throwIfDestroyed();
            Preconditions.checkArgument((validator instanceof InternalValidator),
                    "not provided by Android System: %s", validator);
            mValidator = (InternalValidator) validator;
            return this;
        }

        /**
         * Adds a sanitizer for one or more field.
         *
         * <p>When a sanitizer is set for a field, the {@link AutofillValue} is sent to the
         * sanitizer before a save request is <a href="#TriggeringSaveRequest">triggered</a>.
         *
         * <p>Typically used to avoid displaying the save UI for values that are autofilled but
         * reformattedby the app. For example, to remove spaces between every 4 digits of a
         * credit card number:
         *
         * <pre class="prettyprint">
         * builder.addSanitizer(new TextValueSanitizer(
         *     Pattern.compile("^(\\d{4})\\s?(\\d{4})\\s?(\\d{4})\\s?(\\d{4})$", "$1$2$3$4")),
         *     ccNumberId);
         * </pre>
         *
         * <p>The same sanitizer can be reused to sanitize multiple fields. For example, to trim
         * both the username and password fields:
         *
         * <pre class="prettyprint">
         * builder.addSanitizer(
         *     new TextValueSanitizer(Pattern.compile("^\\s*(.*)\\s*$"), "$1"),
         *         usernameId, passwordId);
         * </pre>
         *
         * <p>The sanitizer can also be used as an alternative for a
         * {@link #setValidator(Validator) validator}. If any of the {@code ids} is a
         * {@link #Builder(int, AutofillId[]) required id} and the {@code sanitizer} fails
         * because of it, then the save UI is not shown.
         *
         * @param sanitizer an implementation provided by the Android System.
         * @param ids id of fields whose value will be sanitized.
         * @return this builder.
         *
         * @throws IllegalArgumentException if a sanitizer for any of the {@code ids} has already
         * been added or if {@code ids} is empty.
         */
        public @NonNull Builder addSanitizer(@NonNull Sanitizer sanitizer,
                @NonNull AutofillId... ids) {
            throwIfDestroyed();
            Preconditions.checkArgument(!ArrayUtils.isEmpty(ids), "ids cannot be empty or null");
            Preconditions.checkArgument((sanitizer instanceof InternalSanitizer),
                    "not provided by Android System: %s", sanitizer);

            if (mSanitizers == null) {
                mSanitizers = new ArrayMap<>();
                mSanitizerIds = new ArraySet<>(ids.length);
            }

            // Check for duplicates first.
            for (AutofillId id : ids) {
                Preconditions.checkArgument(!mSanitizerIds.contains(id), "already added %s", id);
                mSanitizerIds.add(id);
            }

            mSanitizers.put((InternalSanitizer) sanitizer, ids);

            return this;
        }

       /**
         * Explicitly defines the view that should commit the autofill context when clicked.
         *
         * <p>Usually, the save request is only automatically
         * <a href="#TriggeringSaveRequest">triggered</a> after the activity is
         * finished or all relevant views become invisible, but there are scenarios where the
         * autofill context is automatically commited too late
         * &mdash;for example, when the activity manually clears the autofillable views when a
         * button is tapped. This method can be used to trigger the autofill save UI earlier in
         * these scenarios.
         *
         * <p><b>Note:</b> This method should only be used in scenarios where the automatic workflow
         * is not enough, otherwise it could trigger the autofill save UI when it should not&mdash;
         * for example, when the user entered invalid credentials for the autofillable views.
         */
        public @NonNull Builder setTriggerId(@NonNull AutofillId id) {
            throwIfDestroyed();
            mTriggerId = Objects.requireNonNull(id);
            return this;
        }

        /**
         * Builds a new {@link SaveInfo} instance.
         *
         * @throws IllegalStateException if no
         * {@link #Builder(int, AutofillId[]) required ids},
         * or {@link #setOptionalIds(AutofillId[]) optional ids}, or {@link #FLAG_DELAY_SAVE}
         * were set
         */
        public SaveInfo build() {
            throwIfDestroyed();
            Preconditions.checkState(
                    !ArrayUtils.isEmpty(mRequiredIds) || !ArrayUtils.isEmpty(mOptionalIds)
                            || (mFlags & FLAG_DELAY_SAVE) != 0,
                    "must have at least one required or optional id or FLAG_DELAYED_SAVE");
            mDestroyed = true;
            return new SaveInfo(this);
        }

        private void throwIfDestroyed() {
            if (mDestroyed) {
                throw new IllegalStateException("Already called #build()");
            }
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        final StringBuilder builder = new StringBuilder("SaveInfo: [type=")
                .append(DebugUtils.flagsToString(SaveInfo.class, "SAVE_DATA_TYPE_", mType))
                .append(", requiredIds=").append(Arrays.toString(mRequiredIds))
                .append(", negative style=").append(DebugUtils.flagsToString(SaveInfo.class,
                        "NEGATIVE_BUTTON_STYLE_", mNegativeButtonStyle))
                .append(", positive style=").append(DebugUtils.flagsToString(SaveInfo.class,
                        "POSITIVE_BUTTON_STYLE_", mPositiveButtonStyle));
        if (mOptionalIds != null) {
            builder.append(", optionalIds=").append(Arrays.toString(mOptionalIds));
        }
        if (mDescription != null) {
            builder.append(", description=").append(mDescription);
        }
        if (mFlags != 0) {
            builder.append(", flags=").append(mFlags);
        }
        if (mCustomDescription != null) {
            builder.append(", customDescription=").append(mCustomDescription);
        }
        if (mValidator != null) {
            builder.append(", validator=").append(mValidator);
        }
        if (mSanitizerKeys != null) {
            builder.append(", sanitizerKeys=").append(mSanitizerKeys.length);
        }
        if (mSanitizerValues != null) {
            builder.append(", sanitizerValues=").append(mSanitizerValues.length);
        }
        if (mTriggerId != null) {
            builder.append(", triggerId=").append(mTriggerId);
        }

        return builder.append("]").toString();
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mType);
        parcel.writeParcelableArray(mRequiredIds, flags);
        parcel.writeParcelableArray(mOptionalIds, flags);
        parcel.writeInt(mNegativeButtonStyle);
        parcel.writeParcelable(mNegativeActionListener, flags);
        parcel.writeInt(mPositiveButtonStyle);
        parcel.writeCharSequence(mDescription);
        parcel.writeParcelable(mCustomDescription, flags);
        parcel.writeParcelable(mValidator, flags);
        parcel.writeParcelableArray(mSanitizerKeys, flags);
        if (mSanitizerKeys != null) {
            for (int i = 0; i < mSanitizerValues.length; i++) {
                parcel.writeParcelableArray(mSanitizerValues[i], flags);
            }
        }
        parcel.writeParcelable(mTriggerId, flags);
        parcel.writeInt(mFlags);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<SaveInfo> CREATOR = new Parcelable.Creator<SaveInfo>() {
        @Override
        public SaveInfo createFromParcel(Parcel parcel) {

            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final int type = parcel.readInt();
            final AutofillId[] requiredIds = parcel.readParcelableArray(null, AutofillId.class);
            final Builder builder = requiredIds != null
                    ? new Builder(type, requiredIds)
                    : new Builder(type);
            final AutofillId[] optionalIds = parcel.readParcelableArray(null, AutofillId.class);
            if (optionalIds != null) {
                builder.setOptionalIds(optionalIds);
            }

            builder.setNegativeAction(parcel.readInt(), parcel.readParcelable(null, android.content.IntentSender.class));
            builder.setPositiveAction(parcel.readInt());
            builder.setDescription(parcel.readCharSequence());
            final CustomDescription customDescripton = parcel.readParcelable(null, android.service.autofill.CustomDescription.class);
            if (customDescripton != null) {
                builder.setCustomDescription(customDescripton);
            }
            final InternalValidator validator = parcel.readParcelable(null, android.service.autofill.InternalValidator.class);
            if (validator != null) {
                builder.setValidator(validator);
            }
            final InternalSanitizer[] sanitizers =
                    parcel.readParcelableArray(null, InternalSanitizer.class);
            if (sanitizers != null) {
                final int size = sanitizers.length;
                for (int i = 0; i < size; i++) {
                    final AutofillId[] autofillIds =
                            parcel.readParcelableArray(null, AutofillId.class);
                    builder.addSanitizer(sanitizers[i], autofillIds);
                }
            }
            final AutofillId triggerId = parcel.readParcelable(null, android.view.autofill.AutofillId.class);
            if (triggerId != null) {
                builder.setTriggerId(triggerId);
            }
            builder.setFlags(parcel.readInt());
            return builder.build();
        }

        @Override
        public SaveInfo[] newArray(int size) {
            return new SaveInfo[size];
        }
    };
}
