/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.qs.customize;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.LinearLayout;
import com.android.systemui.R;
import com.android.systemui.qs.QSDetailClipper;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.QSTileHost;

import java.util.ArrayList;
import java.util.List;

/**
 * Allows full-screen customization of QS, through show() and hide().
 *
 * This adds itself to the status bar window, so it can appear on top of quick settings and
 * *someday* do fancy animations to get into/out of it.
 */
public class QSCustomizer extends LinearLayout implements OnClickListener {

    private final QSDetailClipper mClipper;

    private PhoneStatusBar mPhoneStatusBar;

    private boolean isShown;
    private QSTileHost mHost;
    private RecyclerView mRecyclerView;
    private TileAdapter mTileAdapter;
    private View mClose;
    private View mSave;
    private View mReset;

    public QSCustomizer(Context context, AttributeSet attrs) {
        super(new ContextThemeWrapper(context, android.R.style.Theme_Material), attrs);
        mClipper = new QSDetailClipper(this);
    }

    public void setHost(QSTileHost host) {
        mHost = host;
        mPhoneStatusBar = host.getPhoneStatusBar();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mClose = findViewById(R.id.close);
        mSave = findViewById(R.id.save);
        mReset = findViewById(R.id.reset);
        mClose.setOnClickListener(this);
        mSave.setOnClickListener(this);
        mReset.setOnClickListener(this);

        mRecyclerView = (RecyclerView) findViewById(android.R.id.list);
        mTileAdapter = new TileAdapter(getContext());
        mRecyclerView.setAdapter(mTileAdapter);
        new ItemTouchHelper(mTileAdapter.getCallback()).attachToRecyclerView(mRecyclerView);
        GridLayoutManager layout = new GridLayoutManager(getContext(), 3);
        layout.setSpanSizeLookup(mTileAdapter.getSizeLookup());
        mRecyclerView.setLayoutManager(layout);
        mRecyclerView.addItemDecoration(mTileAdapter.getItemDecoration());
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setMoveDuration(TileAdapter.MOVE_DURATION);
        mRecyclerView.setItemAnimator(animator);
    }

    public void show(int x, int y) {
        if (!isShown) {
            isShown = true;
            mPhoneStatusBar.getStatusBarWindow().addView(this);
            setTileSpecs();
            mClipper.animateCircularClip(x, y, true, null);
            new TileQueryHelper(mContext, mHost).setListener(mTileAdapter);
        }
    }

    public void hide(int x, int y) {
        if (isShown) {
            isShown = false;
            mClipper.animateCircularClip(x, y, false, mCollapseAnimationListener);
        }
    }

    public boolean isCustomizing() {
        return isShown;
    }

    private void reset() {
        ArrayList<String> tiles = new ArrayList<>();
        String defTiles = mContext.getString(R.string.quick_settings_tiles_default);
        for (String tile : defTiles.split(",")) {
            tiles.add(tile);
        }
        mTileAdapter.setTileSpecs(tiles);
    }

    private void setTileSpecs() {
        List<String> specs = new ArrayList<>();
        for (QSTile tile : mHost.getTiles()) {
            specs.add(tile.getTileSpec());
        }
        mTileAdapter.setTileSpecs(specs);
    }

    private void save() {
        mTileAdapter.saveSpecs(mHost);
        hide((int) mSave.getX() + mSave.getWidth() / 2, (int) mSave.getY() + mSave.getHeight() / 2);
    }

    @Override
    public void onClick(View v) {
        if (v == mClose) {
            hide((int) mClose.getX() + mClose.getWidth() / 2,
                    (int) mClose.getY() + mClose.getHeight() / 2);
        } else if (v == mSave) {
            save();
        } else if (v == mReset) {
            reset();
        }
    }

    private final AnimatorListener mCollapseAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (!isShown) {
                mPhoneStatusBar.getStatusBarWindow().removeView(QSCustomizer.this);
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (!isShown) {
                mPhoneStatusBar.getStatusBarWindow().removeView(QSCustomizer.this);
            }
        }
    };
}
