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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;

import com.android.internal.util.Preconditions;

/**
 * @hide
 */
public final class MultiFloatAction extends ControlAction {

    private static final String TAG = "MultiFloatAction";
    private static final @ActionType int TYPE = TYPE_MULTI_FLOAT;
    private static final String KEY_VALUES = "key_values";

    private final @NonNull float[] mNewValues;

    @Override
    public int getActionType() {
        return TYPE;
    }

    public MultiFloatAction(@NonNull String templateId,
            @NonNull float[] newValues,
            @Nullable String challengeValue) {
        super(templateId, challengeValue);
        Preconditions.checkNotNull(newValues);
        if (newValues.length == 0) {
            throw new IllegalArgumentException("newValues array length 0");
        }
        if (newValues.length == 1) {
            Log.w(TAG, "newValues array length 1");
        }
        mNewValues = newValues.clone();
    }

    public MultiFloatAction(@NonNull String templateId, @NonNull float[] newValues) {
        this(templateId, newValues, null);
    }

    MultiFloatAction(Bundle b) {
        super(b);
        mNewValues = b.getFloatArray(KEY_VALUES);
    }

    @NonNull
    public float[] getNewValues() {
        return mNewValues.clone();
    }

    @Override
    protected Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putFloatArray(KEY_VALUES, mNewValues);
        return b;
    }

    public static final Creator<MultiFloatAction> CREATOR = new Creator<MultiFloatAction>() {
        @Override
        public MultiFloatAction createFromParcel(Parcel source) {
            int type = source.readInt();
            verifyType(type, TYPE);
            return new MultiFloatAction(source.readBundle());
        }

        @Override
        public MultiFloatAction[] newArray(int size) {
            return new MultiFloatAction[size];
        }
    };
}
