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

package android.service.controls;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

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

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TYPE_BOOLEAN,
            TYPE_FLOAT
    })
    public @interface ActionType {};

    /**
     * The identifier of {@link BooleanAction}.
     */
    public static final @ActionType int TYPE_BOOLEAN = 0;

    /**
     * The identifier of {@link FloatAction}.
     */
    public static final @ActionType int TYPE_FLOAT = 1;

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
    ControlAction(Parcel in) {
        mTemplateId = in.readString();
        if (in.readByte() == 1) {
            mChallengeValue = in.readString();
        } else {
            mChallengeValue = null;
        }
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
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(getActionType());
        dest.writeString(mTemplateId);
        if (mChallengeValue != null) {
            dest.writeByte((byte) 1);
            dest.writeString(mChallengeValue);
        } else {
            dest.writeByte((byte) 0);
        }
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
                return BooleanAction.CREATOR.createFromParcel(source);
            case TYPE_FLOAT:
                return FloatAction.CREATOR.createFromParcel(source);
            default:
                return null;
        }
    }

}
