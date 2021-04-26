/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceClickListener;
import androidx.preference.PreferenceViewHolder;

import com.airbnb.lottie.LottieAnimationView;

/**
 * IllustrationPreference is a preference that can play lottie format animation
 */
public class IllustrationPreference extends Preference implements OnPreferenceClickListener {

    static final String TAG = "IllustrationPreference";
    private int mAnimationId;
    private boolean mIsAnimating;
    private ImageView mPlayButton;
    private LottieAnimationView mIllustrationView;

    public IllustrationPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public IllustrationPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    public IllustrationPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (mAnimationId == 0) {
            Log.w(TAG, "Invalid illustration resource id.");
            return;
        }
        mPlayButton = (ImageView) holder.findViewById(R.id.video_play_button);
        mIllustrationView = (LottieAnimationView) holder.findViewById(R.id.lottie_view);
        mIllustrationView.setAnimation(mAnimationId);
        mIllustrationView.loop(true);
        mIllustrationView.playAnimation();
        updateAnimationStatus(mIsAnimating);
        setOnPreferenceClickListener(this);
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        mIsAnimating = !isAnimating();
        updateAnimationStatus(mIsAnimating);
        return true;
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        ss.mIsAnimating = mIsAnimating;
        return ss;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        mIsAnimating = ss.mIsAnimating;
    }

    @VisibleForTesting
    boolean isAnimating() {
        return mIllustrationView.isAnimating();
    }

    private void init(Context context, AttributeSet attrs) {
        setLayoutResource(R.layout.illustration_preference);

        mIsAnimating = true;
        if (attrs != null) {
            final TypedArray a = context.obtainStyledAttributes(attrs,
                    R.styleable.LottieAnimationView, 0 /*defStyleAttr*/, 0 /*defStyleRes*/);
            mAnimationId = a.getResourceId(R.styleable.LottieAnimationView_lottie_rawRes, 0);
            a.recycle();
        }
    }

    private void updateAnimationStatus(boolean playAnimation) {
        if (playAnimation) {
            mIllustrationView.resumeAnimation();
            mPlayButton.setVisibility(View.INVISIBLE);
        } else {
            mIllustrationView.pauseAnimation();
            mPlayButton.setVisibility(View.VISIBLE);
        }
    }

    static class SavedState extends BaseSavedState {
        boolean mIsAnimating;

        SavedState(Parcelable superState) {
            super(superState);
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        private SavedState(Parcel in) {
            super(in);
            mIsAnimating = (Boolean) in.readValue(null);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeValue(mIsAnimating);
        }

        @Override
        public String toString() {
            return "IllustrationPreference.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " mIsAnimating=" + mIsAnimating + "}";
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
                    public SavedState createFromParcel(Parcel in) {
                        return new SavedState(in);
                    }

                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }
}
