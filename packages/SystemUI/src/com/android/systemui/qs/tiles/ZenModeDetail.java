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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.service.notification.Condition;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.util.HashSet;

/** Quick settings control panel: Zen mode **/
public class ZenModeDetail extends RelativeLayout {
    private static final String TAG = "ZenModeDetail";
    private static final Intent ZEN_SETTINGS = new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);
    private static final int[] MINUTES = new int[] { 15, 30, 45, 60, 120, 180, 240 };

    private final H mHandler = new H();

    private int mMinutesIndex = 3;
    private Context mContext;
    private ZenModeTile mTile;
    private QSTile.Host mHost;
    private ZenModeController mController;

    private Switch mSwitch;
    private ConditionAdapter mAdapter;

    public ZenModeDetail(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void init(ZenModeTile tile) {
        mTile = tile;
        mHost = mTile.getHost();
        mContext = getContext();
        mController = mHost.getZenModeController();

        final ImageView close = (ImageView) findViewById(android.R.id.button1);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mTile.showDetail(false);
            }
        });
        mSwitch = (Switch) findViewById(android.R.id.checkbox);
        mSwitch.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mController.setZen(isChecked);
            }
        });
        mSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean isChecked = mSwitch.isChecked();
                mController.setZen(isChecked);
                if (!isChecked) {
                    mTile.showDetail(false);
                }
            }
        });

        final View moreSettings = findViewById(android.R.id.button2);
        moreSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mHost.startSettingsActivity(ZEN_SETTINGS);
                mTile.showDetail(false);
            }
        });
        final ListView conditions = (ListView) findViewById(android.R.id.content);
        mAdapter = new ConditionAdapter(mContext);
        conditions.setAdapter(mAdapter);
        mAdapter.add(updateTimeCondition());

        updateZen(mController.isZen());
    }

    private Condition updateTimeCondition() {
        final int minutes = MINUTES[mMinutesIndex];
        final long millis = System.currentTimeMillis() + minutes * 60 * 1000;
        final Uri id = new Uri.Builder().scheme(Condition.SCHEME).authority("android")
                .appendPath("countdown").appendPath(Long.toString(millis)).build();
        final int num = minutes < 60 ? minutes : minutes / 60;
        final String units = minutes < 60 ? "minutes" : minutes == 60 ? "hour" : "hours";
        return new Condition(id, "For " + num + " " + units, "", "", 0, Condition.STATE_TRUE,
                Condition.FLAG_RELEVANT_NOW);
    }

    private void editTimeCondition(int delta) {
        final int i = mMinutesIndex + delta;
        if (i < 0 || i >= MINUTES.length) return;
        mMinutesIndex = i;
        mAdapter.remove(mAdapter.getItem(0));
        final Condition c = updateTimeCondition();
        mAdapter.insert(c, 0);
        select(c);
    }

    private void select(Condition condition) {
        mController.select(condition);
    }

    private void updateZen(boolean zen) {
        mHandler.obtainMessage(H.UPDATE_ZEN, zen ? 1 : 0, 0).sendToTarget();
    }

    private void updateConditions(Condition[] conditions) {
        if (conditions == null) return;
        mHandler.obtainMessage(H.UPDATE_CONDITIONS, conditions).sendToTarget();
    }

    private void handleUpdateZen(boolean zen) {
        mSwitch.setChecked(zen);
    }

    private void handleUpdateConditions(Condition[] conditions) {
        for (int i = mAdapter.getCount() - 1; i > 0; i--) {
            mAdapter.remove(mAdapter.getItem(i));
        }
        for (Condition condition : conditions) {
            mAdapter.add(condition);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mController.addCallback(mCallback);
        mController.requestConditions(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mController.removeCallback(mCallback);
        mController.requestConditions(false);
    }

    private final class H extends Handler {
        private static final int UPDATE_ZEN = 1;
        private static final int UPDATE_CONDITIONS = 2;

        public H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == UPDATE_ZEN) {
                handleUpdateZen(msg.arg1 == 1);
            } else if (msg.what == UPDATE_CONDITIONS) {
                handleUpdateConditions((Condition[])msg.obj);
            }
        }
    }

    private final ZenModeController.Callback mCallback = new ZenModeController.Callback() {
        @Override
        public void onZenChanged(boolean zen) {
            updateZen(zen);
        }
        public void onConditionsChanged(Condition[] conditions) {
            updateConditions(conditions);
        }
    };

    private final class ConditionAdapter extends ArrayAdapter<Condition> {
        private final LayoutInflater mInflater;
        private final HashSet<RadioButton> mRadioButtons = new HashSet<RadioButton>();

        public ConditionAdapter(Context context) {
            super(context, 0);
            mInflater = LayoutInflater.from(new ContextThemeWrapper(context, R.style.QSWhiteTheme));
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Condition condition = getItem(position);
            final boolean enabled = condition.state == Condition.STATE_TRUE;

            final View row = convertView != null ? convertView : mInflater
                    .inflate(R.layout.qs_zen_mode_detail_condition, parent, false);
            final RadioButton rb = (RadioButton) row.findViewById(android.R.id.checkbox);
            mRadioButtons.add(rb);
            rb.setEnabled(enabled);
            rb.setOnCheckedChangeListener(new OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (isChecked) {
                        for (RadioButton otherButton : mRadioButtons) {
                            if (otherButton == rb) continue;
                            otherButton.setChecked(false);
                        }
                        select(condition);
                    }
                }
            });
            final TextView title = (TextView) row.findViewById(android.R.id.title);
            title.setText(condition.summary);
            title.setEnabled(enabled);
            title.setAlpha(enabled ? 1 : .5f);
            final ImageView button1 = (ImageView) row.findViewById(android.R.id.button1);
            button1.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    rb.setChecked(true);
                    editTimeCondition(-1);
                }
            });

            final ImageView button2 = (ImageView) row.findViewById(android.R.id.button2);
            button2.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    rb.setChecked(true);
                    editTimeCondition(1);
                }
            });
            title.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    rb.setChecked(true);
                }
            });
            if (position != 0) {
                button1.setVisibility(View.GONE);
                button2.setVisibility(View.GONE);
            }
            if (position == 0 && mRadioButtons.size() == 1) {
                rb.setChecked(true);
            }
            return row;
        }
    }
}
