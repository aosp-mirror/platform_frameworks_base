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

import static com.android.systemui.plugins.DarkIconDispatcher.getTint;
import static com.android.systemui.plugins.DarkIconDispatcher.isInAreas;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.graph.SignalDrawable;
import com.android.systemui.DualToneHandler;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.MobileIconState;

import java.util.ArrayList;

/**
 * View group for the mobile icon in the status bar
 */
public class StatusBarMobileView extends BaseStatusBarFrameLayout implements DarkReceiver,
        StatusIconDisplayable {
    private static final String TAG = "StatusBarMobileView";

    /// Used to show etc dots
    private StatusBarIconView mDotView;
    /// The main icon view
    private LinearLayout mMobileGroup;
    private String mSlot;
    private MobileIconState mState;
    private SignalDrawable mMobileDrawable;
    private View mInoutContainer;
    private ImageView mIn;
    private ImageView mOut;
    private ImageView mMobile, mMobileType, mMobileRoaming;
    private View mMobileSignalType;
    private View mMobileRoamingSpace;
    @StatusBarIconView.VisibleState
    private int mVisibleState = STATE_HIDDEN;
    private DualToneHandler mDualToneHandler;
    private boolean mForceHidden;
    private boolean mOldStyleType;
    private ImageView mMobileTypeSmall;

    /**
     * Designated constructor
     *
     * This view is special, in that it is the only view in SystemUI that allows for a configuration
     * override on a MCC/MNC-basis. This means that for every mobile view inflated, we have to
     * construct a context with that override, since the resource system doesn't have a way to
     * handle this for us.
     *
     * @param context A context with resources configured by MCC/MNC
     * @param slot The string key defining which slot this icon refers to. Always "mobile" for the
     *             mobile icon
     */
    public static StatusBarMobileView fromContext(
            Context context,
            String slot
    ) {
        LayoutInflater inflater = LayoutInflater.from(context);
        StatusBarMobileView v = (StatusBarMobileView)
                inflater.inflate(R.layout.status_bar_mobile_signal_group, null);
        v.setSlot(slot);
        v.init();
        v.setVisibleState(STATE_ICON);
        return v;
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

    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        float translationX = getTranslationX();
        float translationY = getTranslationY();
        outRect.left += translationX;
        outRect.right += translationX;
        outRect.top += translationY;
        outRect.bottom += translationY;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mMobileGroup.measure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(mMobileGroup.getMeasuredWidth(), mMobileGroup.getMeasuredHeight());
    }

    private void init() {
        mDualToneHandler = new DualToneHandler(getContext());
        mMobileGroup = findViewById(R.id.mobile_group);
        mMobile = findViewById(R.id.mobile_signal);
        mMobileType = findViewById(R.id.mobile_type);
        mMobileRoaming = findViewById(R.id.mobile_roaming);
        mMobileRoamingSpace = findViewById(R.id.mobile_roaming_space);
        mIn = findViewById(R.id.mobile_in);
        mOut = findViewById(R.id.mobile_out);
        mInoutContainer = findViewById(R.id.inout_container);
        mMobileSignalType = findViewById(R.id.mobile_signal_type);
        mMobileTypeSmall = findViewById(R.id.mobile_type_small);

        mMobileDrawable = new SignalDrawable(getContext());
        mMobile.setImageDrawable(mMobileDrawable);

        initDotView();
    }

    private void initDotView() {
        mDotView = new StatusBarIconView(mContext, mSlot, null);
        mDotView.setVisibleState(STATE_DOT);

        int width = mContext.getResources().getDimensionPixelSize(R.dimen.status_bar_icon_size);
        LayoutParams lp = new LayoutParams(width, width);
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        addView(mDotView, lp);
    }

    public void applyMobileState(MobileIconState state) {
        boolean requestLayout = false;
        if (state == null) {
            requestLayout = getVisibility() != View.GONE;
            setVisibility(View.GONE);
            mState = null;
        } else if (mState == null) {
            requestLayout = true;
            mState = state.copy();
            initViewState();
        } else if (!mState.equals(state)) {
            requestLayout = updateState(state.copy());
        }

        if (requestLayout) {
            requestLayout();
        }
    }

    private void initViewState() {
        setContentDescription(mState.contentDescription);
        if (!mState.visible || mForceHidden) {
            mMobileGroup.setVisibility(View.GONE);
        } else {
            mMobileGroup.setVisibility(View.VISIBLE);
        }
        mMobileDrawable.setLevel(mState.strengthId);
        if (mState.typeId > 0) {
            mMobileType.setContentDescription(mState.typeContentDescription);
            mMobileType.setImageResource(mState.typeId);
            mMobileType.setVisibility(View.VISIBLE);
        } else {
            mMobile.setVisibility(View.GONE);
        }

        boolean showRoamingSpace = false;
        if (mState.typeId > 0) {
            if (mOldStyleType) {
                showOldStyle(mState);
                showRoamingSpace = true;
            } else {
                showNewStyle(mState);
            }
        } else {
            hideIndicators();
        }
        if (mState.roaming) {
            mMobileTypeSmall.setVisibility(View.GONE);
            setMobileSignalWidth(true);
        }
        mMobile.setVisibility(mState.showTriangle ? View.VISIBLE : View.GONE);
        mMobileRoaming.setVisibility(mState.roaming ? View.VISIBLE : View.GONE);
        mMobileRoamingSpace.setVisibility(mState.roaming || showRoamingSpace ? View.VISIBLE : View.GONE);
        mIn.setVisibility(mState.activityIn ? View.VISIBLE : View.GONE);
        mOut.setVisibility(mState.activityOut ? View.VISIBLE : View.GONE);
        mInoutContainer.setVisibility((mState.activityIn || mState.activityOut)
                ? View.VISIBLE : View.GONE);
    }

    private void setMobileSignalWidth(boolean small) {
        if (!small) {
            final int paddingLimit = mContext.getResources().getDimensionPixelSize(
                R.dimen.status_bar_mobile_type_padding_limit);
            final int padding = mMobileTypeSmall.getWidth() < paddingLimit
                ? mContext.getResources().getDimensionPixelSize(
                    R.dimen.status_bar_mobile_type_padding)
                : 0;
            mMobileTypeSmall.setPadding(padding, 0, 0, 0);
        }
        final ViewGroup.LayoutParams p = mMobileSignalType.getLayoutParams();
        p.width = mContext.getResources().getDimensionPixelSize(small
            ? R.dimen.status_bar_mobile_signal_width
            : R.dimen.status_bar_mobile_signal_with_type_width);
        mMobileSignalType.setLayoutParams(p);
    }

    private boolean updateState(MobileIconState state) {
        boolean needsLayout = false;

        setContentDescription(state.contentDescription);
        int newVisibility = state.visible && !mForceHidden ? View.VISIBLE : View.GONE;
        if (newVisibility != mMobileGroup.getVisibility() && STATE_ICON == mVisibleState) {
            mMobileGroup.setVisibility(newVisibility);
            needsLayout = true;
        }
        if (mState.strengthId != state.strengthId) {
            mMobileDrawable.setLevel(state.strengthId);
        }
        boolean showRoamingSpace = false;
        if (mState.typeId != state.typeId) {
            needsLayout |= state.typeId == 0 || mState.typeId == 0;
            if (state.typeId != 0) {
                if (mOldStyleType) {
                    showOldStyle(state);
                    showRoamingSpace = true;
                } else {
                    showNewStyle(state);
                }
            } else {
                hideIndicators();
            }
        }

        mMobile.setVisibility(state.showTriangle ? View.VISIBLE : View.GONE);
        if (state.roaming) {
            mMobileTypeSmall.setVisibility(View.GONE);
            setMobileSignalWidth(true);
        }
        mMobileRoaming.setVisibility(state.roaming ? View.VISIBLE : View.GONE);
        mMobileRoamingSpace.setVisibility(showRoamingSpace || state.roaming ? View.VISIBLE : View.GONE);
        mIn.setVisibility(state.activityIn ? View.VISIBLE : View.GONE);
        mOut.setVisibility(state.activityOut ? View.VISIBLE : View.GONE);
        mInoutContainer.setVisibility((state.activityIn || state.activityOut)
                ? View.VISIBLE : View.GONE);

        needsLayout |= state.roaming != mState.roaming
                || state.activityIn != mState.activityIn
                || state.activityOut != mState.activityOut
                || state.showTriangle != mState.showTriangle;

        mState = state;
        return needsLayout;
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        float intensity = isInAreas(areas, this) ? darkIntensity : 0;
        mMobileDrawable.setTintList(
                ColorStateList.valueOf(mDualToneHandler.getSingleColor(intensity)));
        ColorStateList color = ColorStateList.valueOf(getTint(areas, this, tint));
        mIn.setImageTintList(color);
        mOut.setImageTintList(color);
        mMobileType.setImageTintList(color);
        mMobileTypeSmall.setImageTintList(color);
        mMobileRoaming.setImageTintList(color);
        mDotView.setDecorColor(tint);
        mDotView.setIconColor(tint, false);
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
        mMobileDrawable.setTintList(list);
        mIn.setImageTintList(list);
        mOut.setImageTintList(list);
        mMobileType.setImageTintList(list);
        mMobileTypeSmall.setImageTintList(list);
        mMobileRoaming.setImageTintList(list);
        mDotView.setDecorColor(color);
    }

    @Override
    public void setDecorColor(int color) {
        mDotView.setDecorColor(color);
    }

    @Override
    public boolean isIconVisible() {
        return mState.visible && !mForceHidden;
    }

    @Override
    public void setVisibleState(@StatusBarIconView.VisibleState int state, boolean animate) {
        if (state == mVisibleState) {
            return;
        }

        mVisibleState = state;
        switch (state) {
            case STATE_ICON:
                mMobileGroup.setVisibility(View.VISIBLE);
                mDotView.setVisibility(View.GONE);
                break;
            case STATE_DOT:
                mMobileGroup.setVisibility(View.INVISIBLE);
                mDotView.setVisibility(View.VISIBLE);
                break;
            case STATE_HIDDEN:
            default:
                mMobileGroup.setVisibility(View.INVISIBLE);
                mDotView.setVisibility(View.INVISIBLE);
                break;
        }
    }

    /**
     * Forces the state to be hidden (views will be GONE) and if necessary updates the layout.
     *
     * Makes sure that the {@link StatusBarIconController} cannot make it visible while this flag
     * is enabled.
     * @param forceHidden {@code true} if the icon should be GONE in its view regardless of its
     *                                state.
     *               {@code false} if the icon should show as determined by its controller.
     */
    public void forceHidden(boolean forceHidden) {
        if (mForceHidden != forceHidden) {
            mForceHidden = forceHidden;
            updateState(mState);
            requestLayout();
        }
    }

    @Override
    @StatusBarIconView.VisibleState
    public int getVisibleState() {
        return mVisibleState;
    }

    @VisibleForTesting
    public MobileIconState getState() {
        return mState;
    }

    @Override
    public String toString() {
        return "StatusBarMobileView(slot=" + mSlot + " state=" + mState + ")";
    }

    public void updateDisplayType(boolean oldStyleType) {
        boolean needsLayout = false;
        boolean showRoamingSpace = false;

        if (mOldStyleType != oldStyleType) {
            if (mState.typeId != 0) {
                if (oldStyleType) {
                    showOldStyle(mState);
                    showRoamingSpace = true;
                } else {
                    showNewStyle(mState);
                }
            } else {
               hideIndicators();
            }
        }
        if (mState.roaming) {
            mMobileTypeSmall.setVisibility(View.GONE);
            setMobileSignalWidth(true);
        }
        mMobileRoaming.setVisibility(mState.roaming ? View.VISIBLE : View.GONE);
        mMobileRoamingSpace.setVisibility(showRoamingSpace || mState.roaming ? View.VISIBLE : View.GONE);
        mIn.setVisibility(mState.activityIn ? View.VISIBLE : View.GONE);
        mOut.setVisibility(mState.activityOut ? View.VISIBLE : View.GONE);
        mInoutContainer.setVisibility((mState.activityIn || mState.activityOut)
                ? View.VISIBLE : View.GONE);

        needsLayout = mOldStyleType != oldStyleType;
        mOldStyleType = oldStyleType;

        if (needsLayout) {
            requestLayout();
        }
    }

    private void showOldStyle(MobileIconState state) {
        mMobileType.setVisibility(View.GONE);
        mMobileTypeSmall.setContentDescription(state.typeContentDescription);
        mMobileTypeSmall.setImageResource(state.typeId);
        mMobileTypeSmall.setVisibility(View.VISIBLE);
        setMobileSignalWidth(false);
    }

    private void showNewStyle(MobileIconState state) {
        mMobileType.setVisibility(View.VISIBLE);
        mMobileType.setContentDescription(state.typeContentDescription);
        mMobileType.setImageResource(state.typeId);
        mMobileTypeSmall.setVisibility(View.GONE);
        setMobileSignalWidth(true);
    }

    private void showRoaming() {
        mMobileTypeSmall.setVisibility(View.GONE);
        setMobileSignalWidth(true);
    }

    private void hideIndicators() {
        mMobileType.setVisibility(View.GONE);
        mMobileTypeSmall.setVisibility(View.GONE);
        setMobileSignalWidth(true);
    }
}
