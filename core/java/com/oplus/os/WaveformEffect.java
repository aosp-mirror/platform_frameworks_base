/*
 * Copyright (C) 2022 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.os;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;

public class WaveformEffect implements Parcelable {

    private static final String TAG = "WaveformEffect";

    public static final Parcelable.Creator<WaveformEffect> CREATOR =
            new Parcelable.Creator<WaveformEffect>() {
        @Override
        public WaveformEffect createFromParcel(Parcel in) {
            return new WaveformEffect(in);
        }

        @Override
        public WaveformEffect[] newArray(int size) {
            return new WaveformEffect[size];
        }
    };

    private int mEffectType;
    private boolean mEffectLoop;
    private boolean mStrengthSettingEnabled;

    private WaveformEffect() {
        mEffectType = -1;
        mEffectLoop = false;
    }

    public int getEffectType() {
        return mEffectType;
    }

    public boolean getEffectLoop() {
        return mEffectLoop;
    }

    public boolean getStrengthSettingEnabled() {
        return mStrengthSettingEnabled;
    }

    public static class Builder {
        private int mEffectType;
        private boolean mEffectLoop;
        private boolean mStrengthSettingEnabled;

        public Builder() {
            this.mEffectType = -1;
            this.mEffectLoop = false;
            this.mStrengthSettingEnabled = false;
        }

        public Builder(WaveformEffect effect) {
            this.mEffectType = -1;
            this.mEffectLoop = false;
            this.mStrengthSettingEnabled = false;
        }

        public WaveformEffect build() {
            WaveformEffect effect = new WaveformEffect();
            effect.mEffectType = this.mEffectType;
            effect.mEffectLoop = this.mEffectLoop;
            effect.mStrengthSettingEnabled = this.mStrengthSettingEnabled;
            return effect;
        }

        public Builder setEffectType(int type) {
            this.mEffectType = type;
            return this;
        }

        public Builder setEffectLoop(boolean loop) {
            this.mEffectLoop = loop;
            return this;
        }

        public Builder setStrengthSettingEnabled(boolean enabled) {
            this.mStrengthSettingEnabled = enabled;
            return this;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEffectType);
        dest.writeBoolean(mEffectLoop);
        dest.writeBoolean(mStrengthSettingEnabled);
    }

    private WaveformEffect(Parcel in) {
        mEffectType = in.readInt();
        mEffectLoop = in.readBoolean();
        mStrengthSettingEnabled = in.readBoolean();
    }

    @Override
    public String toString() {
        return String.valueOf(mEffectType);
    }
}
