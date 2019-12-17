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

/**
 * @hide
 */
public final class ModeAction extends ControlAction {

    private static final @ActionType int TYPE = TYPE_MODE;
    private static final String KEY_MODE = "key_mode";

    private final int mNewMode;

    @Override
    public int getActionType() {
        return TYPE;
    }

    public ModeAction(@NonNull String templateId, int newMode, @Nullable String challengeValue) {
        super(templateId, challengeValue);
        mNewMode = newMode;
    }

    public ModeAction(@NonNull String templateId, int newMode) {
        this(templateId, newMode, null);
    }

    ModeAction(Bundle b) {
        super(b);
        mNewMode = b.getInt(KEY_MODE);
    }

    @Override
    protected Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putInt(KEY_MODE, mNewMode);
        return b;
    }

    public int getNewMode() {
        return mNewMode;
    }

    public static final Creator<ModeAction> CREATOR = new Creator<ModeAction>() {
        @Override
        public ModeAction createFromParcel(Parcel source) {
            int type = source.readInt();
            verifyType(type, TYPE);
            return new ModeAction(source.readBundle());
        }

        @Override
        public ModeAction[] newArray(int size) {
            return new ModeAction[size];
        }
    };
}
