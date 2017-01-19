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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.stack.StackStateAnimator;

import java.util.Set;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class NotificationGuts extends LinearLayout {
    private static final String TAG = "NotificationGuts";
    private static final long CLOSE_GUTS_DELAY = 8000;

    private Drawable mBackground;
    private int mClipTopAmount;
    private int mClipBottomAmount;
    private int mActualHeight;
    private boolean mExposed;
    private INotificationManager mINotificationManager;
    private int mStartingUserImportance;
    private StatusBarNotification mStatusBarNotification;
    private NotificationChannel mNotificationChannel;

    private View mImportanceGroup;
    private View mChannelDisabled;
    private Switch mChannelEnabledSwitch;
    private RadioButton mMinImportanceButton;
    private RadioButton mLowImportanceButton;
    private RadioButton mDefaultImportanceButton;
    private RadioButton mHighImportanceButton;

    private Handler mHandler;
    private Runnable mFalsingCheck;
    private boolean mNeedsFalsingProtection;
    private OnGutsClosedListener mListener;

    public interface OnGutsClosedListener {
        public void onGutsClosed(NotificationGuts guts);
    }

    public NotificationGuts(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        mHandler = new Handler();
        mFalsingCheck = new Runnable() {
            @Override
            public void run() {
                if (mNeedsFalsingProtection && mExposed) {
                    closeControls(-1 /* x */, -1 /* y */, false /* save */);
                }
            }
        };
        final TypedArray ta =
                context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.Theme, 0, 0);
        ta.recycle();
    }

    public void resetFalsingCheck() {
        mHandler.removeCallbacks(mFalsingCheck);
        if (mNeedsFalsingProtection && mExposed) {
            mHandler.postDelayed(mFalsingCheck, CLOSE_GUTS_DELAY);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        draw(canvas, mBackground);
    }

    private void draw(Canvas canvas, Drawable drawable) {
        int top = mClipTopAmount;
        int bottom = mActualHeight - mClipBottomAmount;
        if (drawable != null && top < bottom) {
            drawable.setBounds(0, top, getWidth(), bottom);
            drawable.draw(canvas);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackground = mContext.getDrawable(R.drawable.notification_guts_bg);
        if (mBackground != null) {
            mBackground.setCallback(this);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        drawableStateChanged(mBackground);
    }

    private void drawableStateChanged(Drawable d) {
        if (d != null && d.isStateful()) {
            d.setState(getDrawableState());
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    interface OnSettingsClickListener {
        void onClick(View v, int appUid);
    }

    void bindNotification(final PackageManager pm, final INotificationManager iNotificationManager,
            final StatusBarNotification sbn, final NotificationChannel channel,
            OnSettingsClickListener onSettingsClick,
            OnClickListener onDoneClick, final Set<String> nonBlockablePkgs) {
        mINotificationManager = iNotificationManager;
        mNotificationChannel = channel;
        mStatusBarNotification = sbn;
        mStartingUserImportance = channel.getImportance();

        final String pkg = sbn.getPackageName();
        int appUid = -1;
        String appname = pkg;
        Drawable pkgicon = null;
        try {
            final ApplicationInfo info = pm.getApplicationInfo(pkg,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                appUid = info.uid;
                appname = String.valueOf(pm.getApplicationLabel(info));
                pkgicon = pm.getApplicationIcon(info);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // app is gone, just show package name and generic icon
            pkgicon = pm.getDefaultActivityIcon();
        }

        // If this is the placeholder channel, don't use our channel-specific text.
        String appNameText;
        CharSequence channelNameText;
        if (channel.getId().equals(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            appNameText = appname;
            channelNameText = mContext.getString(R.string.notification_header_default_channel);
        } else {
            appNameText = mContext.getString(R.string.notification_importance_header_app, appname);
            channelNameText = channel.getName();
        }
        ((TextView) findViewById(R.id.pkgname)).setText(appNameText);
        ((TextView) findViewById(R.id.channel_name)).setText(channelNameText);

        // Settings button.
        final TextView settingsButton = (TextView) findViewById(R.id.more_settings);
        if (appUid >= 0 && onSettingsClick != null) {
            final int appUidF = appUid;
            settingsButton.setOnClickListener(
                    (View view) -> { onSettingsClick.onClick(view, appUidF); });
            settingsButton.setText(R.string.notification_more_settings);
        } else {
            settingsButton.setVisibility(View.GONE);
        }

        // Done button.
        final TextView doneButton = (TextView) findViewById(R.id.done);
        doneButton.setText(R.string.notification_done);
        doneButton.setOnClickListener(onDoneClick);

        boolean nonBlockable = false;
        try {
            final PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            nonBlockable = Utils.isSystemPackage(getResources(), pm, info);
        } catch (PackageManager.NameNotFoundException e) {
            // unlikely.
        }
        if (nonBlockablePkgs != null) {
            nonBlockable |= nonBlockablePkgs.contains(pkg);
        }

        final View importanceButtons = findViewById(R.id.importance_buttons);
        bindToggles(importanceButtons, mStartingUserImportance, nonBlockable);

        // Importance Text (hardcoded to 4 importance levels)
        final ViewGroup importanceTextGroup =
                (ViewGroup) findViewById(R.id.importance_buttons_text);
        final int size = importanceTextGroup.getChildCount();
        for (int i = 0; i < size; i++) {
            int importanceNameResId = 0;
            int importanceDescResId = 0;
            switch (i) {
                case 0:
                    importanceNameResId = R.string.high_importance;
                    importanceDescResId = R.string.notification_importance_high;
                    break;
                case 1:
                    importanceNameResId = R.string.default_importance;
                    importanceDescResId = R.string.notification_importance_default;
                    break;
                case 2:
                    importanceNameResId = R.string.low_importance;
                    importanceDescResId = R.string.notification_importance_low;
                    break;
                case 3:
                    importanceNameResId = R.string.min_importance;
                    importanceDescResId = R.string.notification_importance_min;
                    break;
                default:
                    Log.e(TAG, "Too many importance groups in this layout.");
                    break;
            }
            final ViewGroup importanceChildGroup = (ViewGroup) importanceTextGroup.getChildAt(i);
            ((TextView) importanceChildGroup.getChildAt(0)).setText(importanceNameResId);
            ((TextView) importanceChildGroup.getChildAt(1)).setText(importanceDescResId);
        }

        // Top-level importance group
        mImportanceGroup = findViewById(R.id.importance);
        mChannelDisabled = findViewById(R.id.channel_disabled);
        updateImportanceGroup();
    }

    public boolean hasImportanceChanged() {
        return mStartingUserImportance != getSelectedImportance();
    }

    private void saveImportance() {
        int selectedImportance = getSelectedImportance();
        if (selectedImportance == mStartingUserImportance) {
            return;
        }
        MetricsLogger.action(mContext, MetricsEvent.ACTION_SAVE_IMPORTANCE,
                selectedImportance - mStartingUserImportance);
        mNotificationChannel.setImportance(selectedImportance);
        try {
            mINotificationManager.updateNotificationChannelForPackage(
                    mStatusBarNotification.getPackageName(), mStatusBarNotification.getUid(),
                    mNotificationChannel);
        } catch (RemoteException e) {
            // :(
        }
    }

    private int getSelectedImportance() {
        if (!mChannelEnabledSwitch.isChecked()) {
            return NotificationManager.IMPORTANCE_NONE;
        } else if (mMinImportanceButton.isChecked()) {
            return NotificationManager.IMPORTANCE_MIN;
        } else if (mLowImportanceButton.isChecked()) {
            return NotificationManager.IMPORTANCE_LOW;
        } else if (mDefaultImportanceButton.isChecked()) {
            return NotificationManager.IMPORTANCE_DEFAULT;
        } else if (mHighImportanceButton.isChecked()) {
            return NotificationManager.IMPORTANCE_HIGH;
        } else {
            return NotificationManager.IMPORTANCE_UNSPECIFIED;
        }
    }

    private void bindToggles(final View importanceButtons, final int importance,
            final boolean nonBlockable) {
        // Enabled Switch
        mChannelEnabledSwitch = (Switch) findViewById(R.id.channel_enabled_switch);
        mChannelEnabledSwitch.setChecked(importance != NotificationManager.IMPORTANCE_NONE);
        mChannelEnabledSwitch.setVisibility(nonBlockable ? View.INVISIBLE : View.VISIBLE);

        // Importance Buttons
        mMinImportanceButton = (RadioButton) importanceButtons.findViewById(R.id.min_importance);
        mLowImportanceButton = (RadioButton) importanceButtons.findViewById(R.id.low_importance);
        mDefaultImportanceButton =
                (RadioButton) importanceButtons.findViewById(R.id.default_importance);
        mHighImportanceButton = (RadioButton) importanceButtons.findViewById(R.id.high_importance);

        // Set to current importance setting
        switch (importance) {
            case NotificationManager.IMPORTANCE_UNSPECIFIED:
            case NotificationManager.IMPORTANCE_NONE:
                break;
            case NotificationManager.IMPORTANCE_MIN:
                mMinImportanceButton.setChecked(true);
                break;
            case NotificationManager.IMPORTANCE_LOW:
                mLowImportanceButton.setChecked(true);
                break;
            case NotificationManager.IMPORTANCE_DEFAULT:
                mDefaultImportanceButton.setChecked(true);
                break;
            case NotificationManager.IMPORTANCE_HIGH:
            case NotificationManager.IMPORTANCE_MAX:
                mHighImportanceButton.setChecked(true);
                break;
        }

        // Callback when checked.
        mChannelEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            resetFalsingCheck();
            updateImportanceGroup();
        });
        ((RadioGroup) importanceButtons).setOnCheckedChangeListener(
                (buttonView, isChecked) -> { resetFalsingCheck(); });
    }

    private void updateImportanceGroup() {
        final boolean disabled = getSelectedImportance() == NotificationManager.IMPORTANCE_NONE;
        mImportanceGroup.setVisibility(disabled ? View.GONE : View.VISIBLE);
        mChannelDisabled.setVisibility(disabled ? View.VISIBLE : View.GONE);
    }

    public void closeControls(int x, int y, boolean saveImportance) {
        if (saveImportance) {
            saveImportance();
        }
        if (getWindowToken() == null) {
            if (mListener != null) {
                mListener.onGutsClosed(this);
            }
            return;
        }
        if (x == -1 || y == -1) {
            x = (getLeft() + getRight()) / 2;
            y = (getTop() + getHeight() / 2);
        }
        final double horz = Math.max(getWidth() - x, x);
        final double vert = Math.max(getHeight() - y, y);
        final float r = (float) Math.hypot(horz, vert);
        final Animator a = ViewAnimationUtils.createCircularReveal(this,
                x, y, r, 0);
        a.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        a.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                setVisibility(View.GONE);
            }
        });
        a.start();
        setExposed(false, mNeedsFalsingProtection);
        if (mListener != null) {
            mListener.onGutsClosed(this);
        }
    }

    public void setActualHeight(int actualHeight) {
        mActualHeight = actualHeight;
        invalidate();
    }

    public int getActualHeight() {
        return mActualHeight;
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {
        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setClosedListener(OnGutsClosedListener listener) {
        mListener = listener;
    }

    public void setExposed(boolean exposed, boolean needsFalsingProtection) {
        mExposed = exposed;
        mNeedsFalsingProtection = needsFalsingProtection;
        if (mExposed && mNeedsFalsingProtection) {
            resetFalsingCheck();
        } else {
            mHandler.removeCallbacks(mFalsingCheck);
        }
    }

    public boolean isExposed() {
        return mExposed;
    }
}
