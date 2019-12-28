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
import android.os.Parcel;
import android.os.Parcelable;
import android.service.controls.templates.ControlTemplate;
import android.service.controls.IControlsProviderCallback;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An abstract action that is executed from a {@link ControlTemplate}.
 *
 * The action may have a value to authenticate the input, when the provider has requested it to
 * complete the action.
 * @hide
 */
public abstract class ControlAction implements Parcelable {

    private static final String KEY_TEMPLATE_ID = "key_template_id";
    private static final String KEY_CHALLENGE_VALUE = "key_challenge_value";


    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TYPE_UNKNOWN,
            TYPE_BOOLEAN,
            TYPE_FLOAT,
            TYPE_MULTI_FLOAT,
            TYPE_MODE,
            TYPE_COMMAND
    })
    public @interface ActionType {};
    public static final ControlAction UNKNOWN_ACTION = new ControlAction() {

        @Override
        public int getActionType() {
            return TYPE_UNKNOWN;
        }
    };

    public static final @ActionType int TYPE_UNKNOWN = 0;
    /**
     * The identifier of {@link BooleanAction}.
     */
    public static final @ActionType int TYPE_BOOLEAN = 1;

    /**
     * The identifier of {@link FloatAction}.
     */
    public static final @ActionType int TYPE_FLOAT = 2;

    public static final @ActionType int TYPE_MULTI_FLOAT = 3;

    public static final @ActionType int TYPE_MODE = 4;

    public static final @ActionType int TYPE_COMMAND = 5;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            RESPONSE_OK,
            RESPONSE_FAIL,
            RESPONSE_CHALLENGE_ACK,
            RESPONSE_CHALLENGE_PIN,
            RESPONSE_CHALLENGE_PASSPHRASE
    })
    public @interface ResponseResult {};

    /**
     * Response code for {@link IControlsProviderCallback#onControlActionResponse} indicating that
     * the action has been performed. The action may still fail later and the state may not change.
     */
    public static final @ResponseResult int RESPONSE_OK = 0;
    /**
     * Response code for {@link IControlsProviderCallback#onControlActionResponse} indicating that
     * the action has failed.
     */
    public static final @ResponseResult int RESPONSE_FAIL = 1;
    /**
     * Response code for {@link IControlsProviderCallback#onControlActionResponse} indicating that
     * in order for the action to be performed, acknowledgment from the user is required.
     */
    public static final @ResponseResult int RESPONSE_CHALLENGE_ACK = 2;
    /**
     * Response code for {@link IControlsProviderCallback#onControlActionResponse} indicating that
     * in order for the action to be performed, a PIN is required.
     */
    public static final @ResponseResult int RESPONSE_CHALLENGE_PIN = 3;
    /**
     * Response code for {@link IControlsProviderCallback#onControlActionResponse} indicating that
     * in order for the action to be performed, an alphanumeric passphrase is required.
     */
    public static final @ResponseResult int RESPONSE_CHALLENGE_PASSPHRASE = 4;

    /**
     * The {@link ActionType} associated with this class.
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

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public final void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(getActionType());
        dest.writeBundle(getDataBundle());
    }

    /**
     * Obtain a {@link Bundle} describing this object populated with data.
     *
     * Implementations in subclasses should populate the {@link Bundle} returned by
     * {@link ControlAction}.
     * @return a {@link Bundle} containing the data that represents this object.
     */
    @CallSuper
    protected Bundle getDataBundle() {
        Bundle b = new Bundle();
        b.putString(KEY_TEMPLATE_ID, mTemplateId);
        b.putString(KEY_CHALLENGE_VALUE, mChallengeValue);
        return b;
    }

    public static final @NonNull Creator<ControlAction> CREATOR = new Creator<ControlAction>() {
        @Override
        public ControlAction createFromParcel(Parcel source) {
            int type = source.readInt();
            return createActionFromType(type, source);
        }

        @Override
        public ControlAction[] newArray(int size) {
            return new ControlAction[size];
        }
    };


    private static ControlAction createActionFromType(@ActionType int type, Parcel source) {
        switch(type) {
            case TYPE_BOOLEAN:
                return new BooleanAction(source.readBundle());
            case TYPE_FLOAT:
                return new FloatAction(source.readBundle());
            case TYPE_MULTI_FLOAT:
                return new MultiFloatAction(source.readBundle());
            case TYPE_MODE:
                return new ModeAction(source.readBundle());
            case TYPE_COMMAND:
                return new CommandAction(source.readBundle());
            default:
                source.readBundle();
                return UNKNOWN_ACTION;
        }
    }

    protected static void verifyType(@ActionType int type, @ActionType int thisType) {
        if (type != thisType) {
            throw new IllegalStateException("The type " + type + "does not match " + thisType);
        }
    }

}
