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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QuickQSPanel;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.tuner.TunerService;

public class QuickStatusBarHeader extends BaseStatusBarHeader implements
        NextAlarmController.NextAlarmChangeCallback, View.OnClickListener {

    private static final String TAG = "QuickStatusBarHeader";
    private ActivityStarter mActivityStarter;
    private NextAlarmController mNextAlarmController;
    private SettingsButton mSettingsButton;
    private View mSettingsContainer;
    private TextView mAlarmStatus;
    private View mQsDetailHeader;

    private ImageView mQsDetailHeaderProgress;
    private QSPanel mQsPanel;
    private Switch mQsDetailHeaderSwitch;

    private boolean mExpanded;
    private boolean mAlarmShowing;
    private boolean mShowingDetail;

    private boolean mDetailTransitioning;
    private ViewGroup mExpandedGroup;
    private ViewGroup mDateTimeGroup;
    private View mEmergencyOnly;
    private TextView mQsDetailHeaderTitle;
    private boolean mListening;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    private QuickQSPanel mHeaderQsPanel;
    private boolean mShowEmergencyCallsOnly;
    private float mDateTimeTranslation;
    private MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mEmergencyOnly = findViewById(R.id.header_emergency_calls_only);
        mDateTimeTranslation = mContext.getResources().getDimension(
                R.dimen.qs_date_anim_translation);
        mDateTimeGroup = (ViewGroup) findViewById(R.id.date_time_group);
        mExpandedGroup = (ViewGroup) findViewById(R.id.expanded_group);

        mHeaderQsPanel = (QuickQSPanel) findViewById(R.id.quick_qs_panel);

        mSettingsButton = (SettingsButton) findViewById(R.id.settings_button);
        mSettingsContainer = findViewById(R.id.settings_button_container);
        mSettingsButton.setOnClickListener(this);

        mAlarmStatus = (TextView) findViewById(R.id.alarm_status);
        mAlarmStatus.setOnClickListener(this);

        mQsDetailHeader = findViewById(R.id.qs_detail_header);
        mQsDetailHeader.setAlpha(0);
        mQsDetailHeaderTitle = (TextView) mQsDetailHeader.findViewById(android.R.id.title);
        mQsDetailHeaderSwitch = (Switch) mQsDetailHeader.findViewById(android.R.id.toggle);
        mQsDetailHeaderProgress = (ImageView) findViewById(R.id.qs_detail_header_progress);

        mMultiUserSwitch = (MultiUserSwitch) findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = (ImageView) mMultiUserSwitch.findViewById(R.id.multi_user_avatar);

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view
        ((RippleDrawable) getBackground()).setForceSoftware(true);
        ((RippleDrawable) mSettingsButton.getBackground()).setForceSoftware(true);

        addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right,
                    int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                setClipBounds(new Rect(getPaddingLeft(), 0, getWidth() - getPaddingRight(),
                        getHeight()));
            }
        });
    }

    @Override
    public int getCollapsedHeight() {
        return getHeight();
    }

    @Override
    public int getExpandedHeight() {
        return getHeight();
    }

    @Override
    public void setExpanded(boolean expanded) {
        mExpanded = expanded;
        updateEverything();
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        if (nextAlarm != null) {
            mAlarmStatus.setText(KeyguardStatusView.formatNextAlarm(getContext(), nextAlarm));
        }
        mAlarmShowing = nextAlarm != null;
        updateEverything();
    }

    @Override
    public void setExpansion(float headerExpansionFraction) {
        mExpandedGroup.setAlpha(headerExpansionFraction);
        mExpandedGroup.setVisibility(headerExpansionFraction > 0 ? View.VISIBLE : View.INVISIBLE);
        mHeaderQsPanel.setAlpha(1 - headerExpansionFraction);
        mHeaderQsPanel.setVisibility(headerExpansionFraction < 1 ? View.VISIBLE : View.INVISIBLE);

        mDateTimeGroup.setTranslationY(headerExpansionFraction * mDateTimeTranslation);
        mEmergencyOnly.setAlpha(headerExpansionFraction);
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;
        updateListeners();
    }

    @Override
    public void updateEverything() {
        updateVisibilities();
    }

    private void updateVisibilities() {
        mAlarmStatus.setVisibility(mAlarmShowing ? View.VISIBLE : View.GONE);
        mQsDetailHeader.setVisibility(mExpanded && mShowingDetail ? View.VISIBLE : View.INVISIBLE);
        mEmergencyOnly.setVisibility(mExpanded && mShowEmergencyCallsOnly
                ? View.VISIBLE : View.INVISIBLE);
        mSettingsContainer.findViewById(R.id.tuner_icon).setVisibility(
                TunerService.isTunerEnabled(mContext) ? View.VISIBLE : View.INVISIBLE);
        mMultiUserSwitch.setVisibility(mMultiUserSwitch.hasMultipleUsers() ? View.VISIBLE
                : View.GONE);
    }

    private boolean hasMultiUsers() {
        return false;
    }

    private void updateListeners() {
        if (mListening) {
            mNextAlarmController.addStateChangedCallback(this);
        } else {
            mNextAlarmController.removeStateChangedCallback(this);
        }
    }

    @Override
    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    @Override
    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
        if (mQsPanel != null) {
            mQsPanel.setCallback(mQsPanelCallback);
            mMultiUserSwitch.setQsPanel(qsPanel);
        }
    }

    public void setupHost(final QSTileHost host) {
        host.setHeaderView(this);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host);
        mHeaderQsPanel.setMaxTiles(5);
        mHeaderQsPanel.setTiles(host.getTiles());
        host.addCallback(new QSTile.Host.Callback() {
            @Override
            public void onTilesChanged() {
                mHeaderQsPanel.setTiles(host.getTiles());
            }
        });
    }

    @Override
    public void onClick(View v) {
        if (v == mSettingsButton) {
            if (mSettingsButton.isTunerClick()) {
                if (TunerService.isTunerEnabled(mContext)) {
                    TunerService.showResetRequest(mContext, new Runnable() {
                        @Override
                        public void run() {
                            // Relaunch settings so that the tuner disappears.
                            startSettingsActivity();
                        }
                    });
                } else {
                    Toast.makeText(getContext(), R.string.tuner_toast, Toast.LENGTH_LONG).show();
                    TunerService.setTunerEnabled(mContext, true);
                }
            }
            startSettingsActivity();
        } else if (v == mAlarmStatus && mNextAlarm != null) {
            PendingIntent showIntent = mNextAlarm.getShowIntent();
            if (showIntent != null && showIntent.isActivity()) {
                mActivityStarter.startActivity(showIntent.getIntent(), true /* dismissShade */);
            }
        }
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */);
    }

    @Override
    public void setNextAlarmController(NextAlarmController nextAlarmController) {
        mNextAlarmController = nextAlarmController;
    }

    @Override
    public void setBatteryController(BatteryController batteryController) {
        // Don't care
    }

    @Override
    public void setUserInfoController(UserInfoController userInfoController) {
        userInfoController.addListener(new UserInfoController.OnUserInfoChangedListener() {
            @Override
            public void onUserInfoChanged(String name, Drawable picture) {
                mMultiUserAvatar.setImageDrawable(picture);
            }
        });
    }

    @Override
    public void setEmergencyCallsOnly(boolean show) {
        boolean changed = show != mShowEmergencyCallsOnly;
        if (changed) {
            mShowEmergencyCallsOnly = show;
            if (mExpanded) {
                updateEverything();
            }
        }
    }

    private final QSPanel.Callback mQsPanelCallback = new QSPanel.Callback() {
        private boolean mScanState;

        @Override
        public void onShowingDetail(final QSTile.DetailAdapter detail) {
            mDetailTransitioning = true;
            post(new Runnable() {
                @Override
                public void run() {
                    handleShowingDetail(detail);
                }
            });
        }

        @Override
        public void onToggleStateChanged(final boolean state) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleToggleStateChanged(state);
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

        private void handleToggleStateChanged(boolean state) {
            mQsDetailHeaderSwitch.setChecked(state);
        }

        private void handleScanStateChanged(boolean state) {
            if (mScanState == state) return;
            mScanState = state;
            final Animatable anim = (Animatable) mQsDetailHeaderProgress.getDrawable();
            if (state) {
                mQsDetailHeaderProgress.animate().alpha(1f);
                anim.start();
            } else {
                mQsDetailHeaderProgress.animate().alpha(0f);
                anim.stop();
            }
        }

        private void handleShowingDetail(final QSTile.DetailAdapter detail) {
            final boolean showingDetail = detail != null;
            transition(mDateTimeGroup, !showingDetail);
            transition(mExpandedGroup, !showingDetail);
            if (mAlarmShowing) {
                transition(mAlarmStatus, !showingDetail);
            }
            transition(mQsDetailHeader, showingDetail);
            mShowingDetail = showingDetail;
            if (showingDetail) {
                mQsDetailHeaderTitle.setText(detail.getTitle());
                final Boolean toggleState = detail.getToggleState();
                if (toggleState == null) {
                    mQsDetailHeaderSwitch.setVisibility(INVISIBLE);
                    mQsDetailHeader.setClickable(false);
                } else {
                    mQsDetailHeaderSwitch.setVisibility(VISIBLE);
                    mQsDetailHeaderSwitch.setChecked(toggleState);
                    mQsDetailHeader.setClickable(true);
                    mQsDetailHeader.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            boolean checked = !mQsDetailHeaderSwitch.isChecked();
                            mQsDetailHeaderSwitch.setChecked(checked);
                            detail.setToggleState(checked);
                        }
                    });
                }
            } else {
                mQsDetailHeader.setClickable(false);
            }
        }

        private void transition(final View v, final boolean in) {
            if (in) {
                v.bringToFront();
                v.setVisibility(VISIBLE);
            }
            if (v.hasOverlappingRendering()) {
                v.animate().withLayer();
            }
            v.animate()
                    .alpha(in ? 1 : 0)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (!in) {
                                v.setVisibility(INVISIBLE);
                            }
                            mDetailTransitioning = false;
                        }
                    })
                    .start();
        }
    };
}
