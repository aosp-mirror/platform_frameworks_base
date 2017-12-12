/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_MORE_SETTINGS;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Animatable;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Dependency;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.statusbar.CommandQueue;

public class QSDetail extends LinearLayout {

    private static final String TAG = "QSDetail";
    private static final long FADE_DURATION = 300;

    private final SparseArray<View> mDetailViews = new SparseArray<>();

    private ViewGroup mDetailContent;
    protected TextView mDetailSettingsButton;
    protected TextView mDetailDoneButton;
    private QSDetailClipper mClipper;
    private DetailAdapter mDetailAdapter;
    private QSPanel mQsPanel;

    protected View mQsDetailHeader;
    protected TextView mQsDetailHeaderTitle;
    protected Switch mQsDetailHeaderSwitch;
    protected ImageView mQsDetailHeaderProgress;

    protected QSTileHost mHost;

    private boolean mScanState;
    private boolean mClosingDetail;
    private boolean mFullyExpanded;
    private QuickStatusBarHeader mHeader;
    private boolean mTriggeredExpand;
    private int mOpenX;
    private int mOpenY;
    private boolean mAnimatingOpen;
    private boolean mSwitchState;
    private View mFooter;

    public QSDetail(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        FontSizeUtils.updateFontSize(mDetailDoneButton, R.dimen.qs_detail_button_text_size);
        FontSizeUtils.updateFontSize(mDetailSettingsButton, R.dimen.qs_detail_button_text_size);

        for (int i = 0; i < mDetailViews.size(); i++) {
            mDetailViews.valueAt(i).dispatchConfigurationChanged(newConfig);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mDetailContent = findViewById(android.R.id.content);
        mDetailSettingsButton = findViewById(android.R.id.button2);
        mDetailDoneButton = findViewById(android.R.id.button1);

        mQsDetailHeader = findViewById(R.id.qs_detail_header);
        mQsDetailHeaderTitle = (TextView) mQsDetailHeader.findViewById(android.R.id.title);
        mQsDetailHeaderSwitch = (Switch) mQsDetailHeader.findViewById(android.R.id.toggle);
        mQsDetailHeaderProgress = findViewById(R.id.qs_detail_header_progress);

        updateDetailText();

        mClipper = new QSDetailClipper(this);

        final OnClickListener doneListener = new OnClickListener() {
            @Override
            public void onClick(View v) {
                announceForAccessibility(
                        mContext.getString(R.string.accessibility_desc_quick_settings));
                mQsPanel.closeDetail();
            }
        };
        mDetailDoneButton.setOnClickListener(doneListener);
    }

    public void setQsPanel(QSPanel panel, QuickStatusBarHeader header, View footer) {
        mQsPanel = panel;
        mHeader = header;
        mFooter = footer;
        mHeader.setCallback(mQsPanelCallback);
        mQsPanel.setCallback(mQsPanelCallback);
    }

    public void setHost(QSTileHost host) {
        mHost = host;
    }
    public boolean isShowingDetail() {
        return mDetailAdapter != null;
    }

    public void setFullyExpanded(boolean fullyExpanded) {
        mFullyExpanded = fullyExpanded;
    }

    public void setExpanded(boolean qsExpanded) {
        if (!qsExpanded) {
            mTriggeredExpand = false;
        }
    }

    private void updateDetailText() {
        mDetailDoneButton.setText(R.string.quick_settings_done);
        mDetailSettingsButton.setText(R.string.quick_settings_more_settings);
    }

    public void updateResources() {
        updateDetailText();
    }

    public boolean isClosingDetail() {
        return mClosingDetail;
    }

    public interface Callback {
        void onShowingDetail(DetailAdapter detail, int x, int y);
        void onToggleStateChanged(boolean state);
        void onScanStateChanged(boolean state);
    }

    public void handleShowingDetail(final DetailAdapter adapter, int x, int y,
            boolean toggleQs) {
        final boolean showingDetail = adapter != null;
        setClickable(showingDetail);
        if (showingDetail) {
            setupDetailHeader(adapter);
            if (toggleQs && !mFullyExpanded) {
                mTriggeredExpand = true;
                SysUiServiceProvider.getComponent(mContext, CommandQueue.class)
                        .animateExpandSettingsPanel(null);
            } else {
                mTriggeredExpand = false;
            }
            mOpenX = x;
            mOpenY = y;
        } else {
            // Ensure we collapse into the same point we opened from.
            x = mOpenX;
            y = mOpenY;
            if (toggleQs && mTriggeredExpand) {
                SysUiServiceProvider.getComponent(mContext, CommandQueue.class)
                        .animateCollapsePanels();
                mTriggeredExpand = false;
            }
        }

        boolean visibleDiff = (mDetailAdapter != null) != (adapter != null);
        if (!visibleDiff && mDetailAdapter == adapter) return;  // already in right state
        AnimatorListener listener = null;
        if (adapter != null) {
            int viewCacheIndex = adapter.getMetricsCategory();
            View detailView = adapter.createDetailView(mContext, mDetailViews.get(viewCacheIndex),
                    mDetailContent);
            if (detailView == null) throw new IllegalStateException("Must return detail view");

            setupDetailFooter(adapter);

            mDetailContent.removeAllViews();
            mDetailContent.addView(detailView);
            mDetailViews.put(viewCacheIndex, detailView);
            Dependency.get(MetricsLogger.class).visible(adapter.getMetricsCategory());
            announceForAccessibility(mContext.getString(
                    R.string.accessibility_quick_settings_detail,
                    adapter.getTitle()));
            mDetailAdapter = adapter;
            listener = mHideGridContentWhenDone;
            setVisibility(View.VISIBLE);
        } else {
            if (mDetailAdapter != null) {
                Dependency.get(MetricsLogger.class).hidden(mDetailAdapter.getMetricsCategory());
            }
            mClosingDetail = true;
            mDetailAdapter = null;
            listener = mTeardownDetailWhenDone;
            mHeader.setVisibility(View.VISIBLE);
            mFooter.setVisibility(View.VISIBLE);
            mQsPanel.setGridContentVisibility(true);
            mQsPanelCallback.onScanStateChanged(false);
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);

        animateDetailVisibleDiff(x, y, visibleDiff, listener);
    }

    protected void animateDetailVisibleDiff(int x, int y, boolean visibleDiff, AnimatorListener listener) {
        if (visibleDiff) {
            mAnimatingOpen = mDetailAdapter != null;
            if (mFullyExpanded || mDetailAdapter != null) {
                setAlpha(1);
                mClipper.animateCircularClip(x, y, mDetailAdapter != null, listener);
            } else {
                animate().alpha(0)
                        .setDuration(FADE_DURATION)
                        .setListener(listener)
                        .start();
            }
        }
    }

    protected void setupDetailFooter(DetailAdapter adapter) {
        final Intent settingsIntent = adapter.getSettingsIntent();
        mDetailSettingsButton.setVisibility(settingsIntent != null ? VISIBLE : GONE);
        mDetailSettingsButton.setOnClickListener(v -> {
            Dependency.get(MetricsLogger.class).action(ACTION_QS_MORE_SETTINGS,
                    adapter.getMetricsCategory());
            Dependency.get(ActivityStarter.class)
                    .postStartActivityDismissingKeyguard(settingsIntent, 0);
        });
    }

    protected void setupDetailHeader(final DetailAdapter adapter) {
        mQsDetailHeaderTitle.setText(adapter.getTitle());
        final Boolean toggleState = adapter.getToggleState();
        if (toggleState == null) {
            mQsDetailHeaderSwitch.setVisibility(INVISIBLE);
            mQsDetailHeader.setClickable(false);
        } else {
            mQsDetailHeaderSwitch.setVisibility(VISIBLE);
            handleToggleStateChanged(toggleState, adapter.getToggleEnabled());
            mQsDetailHeader.setClickable(true);
            mQsDetailHeader.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean checked = !mQsDetailHeaderSwitch.isChecked();
                    mQsDetailHeaderSwitch.setChecked(checked);
                    adapter.setToggleState(checked);
                }
            });
        }
    }

    private void handleToggleStateChanged(boolean state, boolean toggleEnabled) {
        mSwitchState = state;
        if (mAnimatingOpen) {
            return;
        }
        mQsDetailHeaderSwitch.setChecked(state);
        mQsDetailHeader.setEnabled(toggleEnabled);
        mQsDetailHeaderSwitch.setEnabled(toggleEnabled);
    }

    private void handleScanStateChanged(boolean state) {
        if (mScanState == state) return;
        mScanState = state;
        final Animatable anim = (Animatable) mQsDetailHeaderProgress.getDrawable();
        if (state) {
            mQsDetailHeaderProgress.animate().cancel();
            mQsDetailHeaderProgress.animate()
                    .alpha(1)
                    .withEndAction(anim::start)
                    .start();
        } else {
            mQsDetailHeaderProgress.animate().cancel();
            mQsDetailHeaderProgress.animate()
                    .alpha(0f)
                    .withEndAction(anim::stop)
                    .start();
        }
    }

    private void checkPendingAnimations() {
        handleToggleStateChanged(mSwitchState,
                            mDetailAdapter != null && mDetailAdapter.getToggleEnabled());
    }

    protected Callback mQsPanelCallback = new Callback() {
        @Override
        public void onToggleStateChanged(final boolean state) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleToggleStateChanged(state,
                            mDetailAdapter != null && mDetailAdapter.getToggleEnabled());
                }
            });
        }

        @Override
        public void onShowingDetail(final DetailAdapter detail, final int x, final int y) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleShowingDetail(detail, x, y, false /* toggleQs */);
                }
            });
        }

        @Override
        public void onScanStateChanged(final boolean state) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleScanStateChanged(state);
                }
            });
        }
    };

    private final AnimatorListenerAdapter mHideGridContentWhenDone = new AnimatorListenerAdapter() {
        public void onAnimationCancel(Animator animation) {
            // If we have been cancelled, remove the listener so that onAnimationEnd doesn't get
            // called, this will avoid accidentally turning off the grid when we don't want to.
            animation.removeListener(this);
            mAnimatingOpen = false;
            checkPendingAnimations();
        };

        @Override
        public void onAnimationEnd(Animator animation) {
            // Only hide content if still in detail state.
            if (mDetailAdapter != null) {
                mQsPanel.setGridContentVisibility(false);
                mHeader.setVisibility(View.INVISIBLE);
                mFooter.setVisibility(View.INVISIBLE);
            }
            mAnimatingOpen = false;
            checkPendingAnimations();
        }
    };

    private final AnimatorListenerAdapter mTeardownDetailWhenDone = new AnimatorListenerAdapter() {
        public void onAnimationEnd(Animator animation) {
            mDetailContent.removeAllViews();
            setVisibility(View.INVISIBLE);
            mClosingDetail = false;
        };
    };
}
