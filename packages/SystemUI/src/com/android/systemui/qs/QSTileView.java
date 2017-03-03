/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs;

import static com.android.systemui.qs.QSTile.getColorForState;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.RippleDrawable;
import android.service.quicksettings.Tile;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;

import libcore.util.Objects;

/** View that represents a standard quick settings tile. **/
public class QSTileView extends QSTileBaseView {

    private final View mDivider;
    protected TextView mLabel;
    private ImageView mPadLock;
    private int mState;
    private ViewGroup mLabelContainer;

    public QSTileView(Context context, QSIconView icon) {
        this(context, icon, false);
    }

    public QSTileView(Context context, QSIconView icon, boolean collapsedView) {
        super(context, icon, collapsedView);

        setClipChildren(false);
        setClipToPadding(false);

        setClickable(true);
        setId(View.generateViewId());
        mDivider = LayoutInflater.from(context).inflate(R.layout.divider, this, false);
        addView(mDivider);
        createLabel();
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
    }

    TextView getLabel() {
        return mLabel;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        FontSizeUtils.updateFontSize(mLabel, R.dimen.qs_tile_text_size);
    }

    protected void createLabel() {
        mLabelContainer = (ViewGroup) LayoutInflater.from(getContext())
                .inflate(R.layout.qs_tile_label, this, false);
        mLabelContainer.setClipChildren(false);
        mLabelContainer.setClipToPadding(false);
        mLabel = (TextView) mLabelContainer.findViewById(R.id.tile_label);
        mPadLock = (ImageView) mLabelContainer.findViewById(R.id.restricted_padlock);

        addView(mLabelContainer);
    }

    @Override
    protected void handleStateChanged(QSTile.State state) {
        super.handleStateChanged(state);
        if (!Objects.equal(mLabel.getText(), state.label) || mState != state.state) {
            if (state.state == Tile.STATE_UNAVAILABLE) {
                int color = getColorForState(getContext(), state.state);
                state.label = new SpannableStringBuilder().append(state.label,
                        new ForegroundColorSpan(color),
                        SpannableStringBuilder.SPAN_INCLUSIVE_INCLUSIVE);
            }
            mState = state.state;
            mLabel.setText(state.label);
        }
        mDivider.setVisibility(state.dualTarget ? View.VISIBLE : View.INVISIBLE);
        if (state.dualTarget != mLabelContainer.isClickable()) {
            mLabelContainer.setClickable(state.dualTarget);
            mLabelContainer.setLongClickable(state.dualTarget);
            mLabelContainer.setBackground(state.dualTarget ? newTileBackground() : null);
        }
        mLabel.setEnabled(!state.disabledByPolicy);
        mPadLock.setVisibility(state.disabledByPolicy ? View.VISIBLE : View.GONE);
    }

    @Override
    public void init(OnClickListener click, OnClickListener secondaryClick,
            OnLongClickListener longClick) {
        super.init(click, secondaryClick, longClick);
        mLabelContainer.setOnClickListener(secondaryClick);
        mLabelContainer.setOnLongClickListener(longClick);
        mLabelContainer.setClickable(false);
        mLabelContainer.setLongClickable(false);
    }
}
