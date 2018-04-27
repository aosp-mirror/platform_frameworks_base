/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static com.android.systemui.statusbar.policy.DarkIconDispatcher.getTint;
import static com.android.systemui.statusbar.policy.DarkIconDispatcher.isInArea;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.AlphaOptimizedLinearLayout;
import com.android.settingslib.graph.SignalDrawable;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.MobileIconState;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;

public class StatusBarMobileView extends AlphaOptimizedLinearLayout implements DarkReceiver,
        StatusIconDisplayable {
    private static final String TAG = "StatusBarMobileView";

    private String mSlot;
    private MobileIconState mState;
    private SignalDrawable mMobileDrawable;
    private View mInoutContainer;
    private ImageView mIn;
    private ImageView mOut;
    private ImageView mMobile, mMobileType, mMobileRoaming;
    private View mMobileRoamingSpace;

    public static StatusBarMobileView fromContext(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);

        return (StatusBarMobileView)
                inflater.inflate(R.layout.status_bar_mobile_signal_group, null);
    }

    public StatusBarMobileView(Context context) {
        super(context);
    }

    public StatusBarMobileView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StatusBarMobileView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public StatusBarMobileView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        init();
    }

    private void init() {
        mMobile = findViewById(R.id.mobile_signal);
        mMobileType = findViewById(R.id.mobile_type);
        mMobileRoaming = findViewById(R.id.mobile_roaming);
        mMobileRoamingSpace = findViewById(R.id.mobile_roaming_space);
        mIn = findViewById(R.id.mobile_in);
        mOut = findViewById(R.id.mobile_out);
        mInoutContainer = findViewById(R.id.inout_container);

        mMobileDrawable = new SignalDrawable(getContext());
        mMobile.setImageDrawable(mMobileDrawable);
    }

    public void applyMobileState(MobileIconState state) {
        if (state == null) {
            setVisibility(View.GONE);
            mState = null;
            return;
        }

        if (mState == null) {
            mState = state.copy();
            initViewState();
            return;
        }

        if (!mState.equals(state)) {
            updateState(state.copy());
        }
    }

    private void initViewState() {
        setContentDescription(mState.contentDescription);
        if (!mState.visible) {
            setVisibility(View.GONE);
        } else {
            setVisibility(View.VISIBLE);
        }
        mMobileDrawable.setLevel(mState.strengthId);
        if (mState.typeId > 0) {
            mMobileType.setContentDescription(mState.typeContentDescription);
            mMobileType.setImageResource(mState.typeId);
            mMobileType.setVisibility(View.VISIBLE);
        } else {
            mMobileType.setVisibility(View.GONE);
        }

        mMobileRoaming.setVisibility(mState.roaming ? View.VISIBLE : View.GONE);
        mMobileRoamingSpace.setVisibility(mState.roaming ? View.VISIBLE : View.GONE);
        mIn.setVisibility(mState.activityIn ? View.VISIBLE : View.GONE);
        mOut.setVisibility(mState.activityIn ? View.VISIBLE : View.GONE);
        mInoutContainer.setVisibility((mState.activityIn || mState.activityOut)
                ? View.VISIBLE : View.GONE);
    }

    private void updateState(MobileIconState state) {
        setContentDescription(state.contentDescription);
        if (mState.visible != state.visible) {
            setVisibility(state.visible ? View.VISIBLE : View.GONE);
        }
        if (mState.strengthId != state.strengthId) {
            mMobileDrawable.setLevel(state.strengthId);
        }
        if (mState.typeId != state.typeId) {
            if (state.typeId != 0) {
                mMobileType.setContentDescription(state.typeContentDescription);
                mMobileType.setImageResource(state.typeId);
                mMobileType.setVisibility(View.VISIBLE);
            } else {
                mMobileType.setVisibility(View.GONE);
            }
        }

        mMobileRoaming.setVisibility(state.roaming ? View.VISIBLE : View.GONE);
        mMobileRoamingSpace.setVisibility(state.roaming ? View.VISIBLE : View.GONE);
        mIn.setVisibility(state.activityIn ? View.VISIBLE : View.GONE);
        mOut.setVisibility(state.activityIn ? View.VISIBLE : View.GONE);
        mInoutContainer.setVisibility((state.activityIn || state.activityOut)
                ? View.VISIBLE : View.GONE);

        mState = state;
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        if (!isInArea(area, this)) {
            return;
        }
        mMobileDrawable.setDarkIntensity(darkIntensity);
        ColorStateList color = ColorStateList.valueOf(getTint(area, this, tint));
        mIn.setImageTintList(color);
        mOut.setImageTintList(color);
        mMobileType.setImageTintList(color);
        mMobileRoaming.setImageTintList(color);
    }

    @Override
    public String getSlot() {
        return mSlot;
    }

    public void setSlot(String slot) {
        mSlot = slot;
    }

    @Override
    public void setStaticDrawableColor(int color) {
        ColorStateList list = ColorStateList.valueOf(color);
        float intensity = color == Color.WHITE ? 0 : 1;
        mMobileDrawable.setDarkIntensity(intensity);

        mIn.setImageTintList(list);
        mOut.setImageTintList(list);
        mMobileType.setImageTintList(list);
        mMobileRoaming.setImageTintList(list);
    }

    @Override
    public void setDecorColor(int color) {
        //TODO: May also not be needed
    }

    @Override
    public boolean isIconVisible() {
        return mState.visible;
    }

    @Override
    public void setVisibleState(int state) {
        //TODO: May not be needed. Mobile is always expected to be visible (not a dot)
    }

    @VisibleForTesting
    public MobileIconState getState() {
        return mState;
    }

    @Override
    public String toString() {
        return "StatusBarMobileView(slot=" + mSlot + " state=" + mState + ")";
    }
}
