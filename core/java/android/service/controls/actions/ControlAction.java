/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.controls.actions;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.service.controls.Control;
import android.service.controls.ControlsProviderService;
import android.service.controls.templates.ControlTemplate;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An abstract action indicating a user interaction with a {@link Control}.
 *
 * In some cases, an action needs to be validated by the user, using a password, PIN or simple
 * acknowledgment. For those cases, an optional (nullable) parameter can be passed to send the user
 * input. This <b>challenge value</b> will be requested from the user and sent as part
 * of a {@link ControlAction} only if the service has responded to an action with one of:
 * <ul>
 *     <li> {@link #RESPONSE_CHALLENGE_ACK}
 *     <li> {@link #RESPONSE_CHALLENGE_PIN}
 *     <li> {@link #RESPONSE_CHALLENGE_PASSPHRASE}
 * </ul>
 */
public abstract class ControlAction {

    private static final String TAG = "ControlAction";

    private static final String KEY_ACTION_TYPE = "key_action_type";
    private static final String KEY_TEMPLATE_ID = "key_template_id";
    private static final String KEY_CHALLENGE_VALUE = "key_challenge_value";

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TYPE_ERROR,
            TYPE_BOOLEAN,
            TYPE_FLOAT,
            TYPE_MODE,
            TYPE_COMMAND
    })
    public @interface ActionType {};

    /**
     * Object returned when there is an unparcelling error.
     * @hide
     */
    public static final @NonNull ControlAction ERROR_ACTION = new ControlAction() {
        @Override
        public int getActionType() {
            return TYPE_ERROR;
        }
    };

    /**
     * The identifier of the action returned by {@link #getErrorAction}.
     */
    public static final @ActionType int TYPE_ERROR = -1;

    /**
     * The identifier of {@link BooleanAction}.
     */
    public static final @ActionType int TYPE_BOOLEAN = 1;

    /**
     * The identifier of {@link FloatAction}.
     */
    public static final @ActionType int TYPE_FLOAT = 2;

    /**
     * The identifier of {@link ModeAction}.
     */
    public static final @ActionType int TYPE_MODE = 4;

    /**
     * The identifier of {@link CommandAction}.
     */
    public static final @ActionType int TYPE_COMMAND = 5;


    public static final boolean isValidResponse(@ResponseResult int response) {
        return (response >= 0 && response < NUM_RESPONSE_TYPES);
    }
    private static final int NUM_RESPONSE_TYPES = 6;
    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            RESPONSE_UNKNOWN,
            RESPONSE_OK,
            RESPONSE_FAIL,
            RESPONSE_CHALLENGE_ACK,
            RESPONSE_CHALLENGE_PIN,
            RESPONSE_CHALLENGE_PASSPHRASE
    })
    public @interface ResponseResult {};

    public static final @ResponseResult int RESPONSE_UNKNOWN = 0;

    /**
     * Response code for the {@code consumer} in
     * {@link ControlsProviderService#performControlAction} indicating that the action has been
     * performed. The action may still fail later and the state may not change.
     */
    public static final @ResponseResult int RESPONSE_OK = 1;
    /**
     * Response code for the {@code consumer} in
     * {@link ControlsProviderService#performControlAction} indicating that the action has failed.
     */
    public static final @ResponseResult int RESPONSE_FAIL = 2;
    /**
     * Response code for the {@code consumer} in
     * {@link ControlsProviderService#performControlAction} indicating that in order for the action
     * to be performed, acknowledgment from the user is required. Any non-empty string returned
     * from {@link #getChallengeValue} shall be treated as a positive acknowledgment.
     */
    public static final @ResponseResult int RESPONSE_CHALLENGE_ACK = 3;
    /**
     * Response code for the {@code consumer} in
     * {@link ControlsProviderService#performControlAction} indicating that in order for the action
     * to be performed, a PIN is required.
     */
    public static final @ResponseResult int RESPONSE_CHALLENGE_PIN = 4;
    /**
     * Response code for the {@code consumer} in
     * {@link ControlsProviderService#performControlAction} indicating that in order for the action
     * to be performed, an alphanumeric passphrase is required.
     */
    public static final @ResponseResult int RESPONSE_CHALLENGE_PASSPHRASE = 5;

    /**
     * The action type associated with this class.
     */
    public abstract @ActionType int getActionType();

    private final @NonNull String mTemplateId;
    private final @Nullable String mChallengeValue;

    private ControlAction() {
        mTemplateId = "";
        mChallengeValue = null;
    }

    /**
     * @hide
     */
    ControlAction(@NonNull String templateId, @Nullable String challengeValue) {
        Preconditions.checkNotNull(templateId);
        mTemplateId = templateId;
        mChallengeValue = challengeValue;
    }

    /**
     * @hide
     */
    ControlAction(Bundle b) {
        mTemplateId = b.getString(KEY_TEMPLATE_ID);
        mChallengeValue = b.getString(KEY_CHALLENGE_VALUE);
    }

    /**
     * The identifier of the {@link ControlTemplate} that originated this action
     */
    @NonNull
    public String getTemplateId() {
        return mTemplateId;
    }

    /**
     * The challenge value used to authenticate certain actions, if available.
     */
    @Nullable
    public String getChallengeValue() {
        return mChallengeValue;
    }

    /**
     * Obtain a {@link Bundle} describing this object populated with data.
     *
     * Implementations in subclasses should populate the {@link Bundle} returned by
     * {@link ControlAction}.
     * @return a {@link Bundle} containing the data that represents this object.
     * @hide
     */
    @CallSuper
    @NonNull
    Bundle getDataBundle() {
        Bundle b = new Bundle();
        b.putInt(KEY_ACTION_TYPE, getActionType());
        b.putString(KEY_TEMPLATE_ID, mTemplateId);
        b.putString(KEY_CHALLENGE_VALUE, mChallengeValue);
        return b;
    }

    /**
     * @param bundle
     * @return
     * @hide
     */
    @NonNull
    static ControlAction createActionFromBundle(@NonNull Bundle bundle) {
        if (bundle == null) {
            Log.e(TAG, "Null bundle");
            return ERROR_ACTION;
        }
        int type = bundle.getInt(KEY_ACTION_TYPE, TYPE_ERROR);
        try {
            switch (type) {
                case TYPE_BOOLEAN:
                    return new BooleanAction(bundle);
                case TYPE_FLOAT:
                    return new FloatAction(bundle);
                case TYPE_MODE:
                    return new ModeAction(bundle);
                case TYPE_COMMAND:
                    return new CommandAction(bundle);
                case TYPE_ERROR:
                default:
                    return ERROR_ACTION;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating action", e);
            return ERROR_ACTION;
        }
    }

    /**
     * Returns a singleton {@link ControlAction} used for indicating an error in unparceling.
     */
    @NonNull
    public static ControlAction getErrorAction() {
        return ERROR_ACTION;
    }
}
