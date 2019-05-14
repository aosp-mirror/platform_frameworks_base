/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.settingslib.wifi;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.net.wifi.WifiConfiguration;
import android.os.Looper;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.R;
import com.android.settingslib.TronUtils;
import com.android.settingslib.Utils;
import com.android.settingslib.wifi.AccessPoint.Speed;

public class AccessPointPreference extends Preference {

    private static final int[] STATE_SECURED = {
            R.attr.state_encrypted
    };

    private static final int[] STATE_METERED = {
            R.attr.state_metered
    };

    private static final int[] FRICTION_ATTRS = {
            R.attr.wifi_friction
    };

    private static final int[] WIFI_CONNECTION_STRENGTH = {
            R.string.accessibility_no_wifi,
            R.string.accessibility_wifi_one_bar,
            R.string.accessibility_wifi_two_bars,
            R.string.accessibility_wifi_three_bars,
            R.string.accessibility_wifi_signal_full
    };

    @Nullable private final StateListDrawable mFrictionSld;
    private final int mBadgePadding;
    private final UserBadgeCache mBadgeCache;
    private final IconInjector mIconInjector;
    private TextView mTitleView;
    private boolean mShowDivider;

    private boolean mForSavedNetworks = false;
    private AccessPoint mAccessPoint;
    private Drawable mBadge;
    private int mLevel;
    private CharSequence mContentDescription;
    private int mDefaultIconResId;
    private int mWifiSpeed = Speed.NONE;

    @Nullable
    private static StateListDrawable getFrictionStateListDrawable(Context context) {
        TypedArray frictionSld;
        try {
            frictionSld = context.getTheme().obtainStyledAttributes(FRICTION_ATTRS);
        } catch (Resources.NotFoundException e) {
            // Fallback for platforms that do not need friction icon resources.
            frictionSld = null;
        }
        return frictionSld != null ? (StateListDrawable) frictionSld.getDrawable(0) : null;
    }

    // Used for dummy pref.
    public AccessPointPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFrictionSld = null;
        mBadgePadding = 0;
        mBadgeCache = null;
        mIconInjector = new IconInjector(context);
    }

    public AccessPointPreference(AccessPoint accessPoint, Context context, UserBadgeCache cache,
            boolean forSavedNetworks) {
        this(accessPoint, context, cache, 0 /* iconResId */, forSavedNetworks);
        refresh();
    }

    public AccessPointPreference(AccessPoint accessPoint, Context context, UserBadgeCache cache,
            int iconResId, boolean forSavedNetworks) {
        this(accessPoint, context, cache, iconResId, forSavedNetworks,
                getFrictionStateListDrawable(context), -1 /* level */, new IconInjector(context));
    }

    @VisibleForTesting
    AccessPointPreference(AccessPoint accessPoint, Context context, UserBadgeCache cache,
                          int iconResId, boolean forSavedNetworks, StateListDrawable frictionSld,
                          int level, IconInjector iconInjector) {
        super(context);
        setLayoutResource(R.layout.preference_access_point);
        setWidgetLayoutResource(getWidgetLayoutResourceId());
        mBadgeCache = cache;
        mAccessPoint = accessPoint;
        mForSavedNetworks = forSavedNetworks;
        mAccessPoint.setTag(this);
        mLevel = level;
        mDefaultIconResId = iconResId;
        mFrictionSld = frictionSld;
        mIconInjector = iconInjector;
        mBadgePadding = context.getResources()
                .getDimensionPixelSize(R.dimen.wifi_preference_badge_padding);
    }

    protected int getWidgetLayoutResourceId() {
        return R.layout.access_point_friction_widget;
    }

    public AccessPoint getAccessPoint() {
        return mAccessPoint;
    }

    @Override
    public void onBindViewHolder(final PreferenceViewHolder view) {
        super.onBindViewHolder(view);
        if (mAccessPoint == null) {
            // Used for dummy pref.
            return;
        }
        Drawable drawable = getIcon();
        if (drawable != null) {
            drawable.setLevel(mLevel);
        }

        mTitleView = (TextView) view.findViewById(android.R.id.title);
        if (mTitleView != null) {
            // Attach to the end of the title view
            mTitleView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, mBadge, null);
            mTitleView.setCompoundDrawablePadding(mBadgePadding);
        }
        view.itemView.setContentDescription(mContentDescription);

        ImageView frictionImageView = (ImageView) view.findViewById(R.id.friction_icon);
        bindFrictionImage(frictionImageView);

        final View divider = view.findViewById(R.id.two_target_divider);
        divider.setVisibility(shouldShowDivider() ? View.VISIBLE : View.INVISIBLE);
    }

    public boolean shouldShowDivider() {
        return mShowDivider;
    }

    public void setShowDivider(boolean showDivider) {
        mShowDivider = showDivider;
        notifyChanged();
    }

    protected void updateIcon(int level, Context context) {
        if (level == -1) {
            safeSetDefaultIcon();
            return;
        }
        TronUtils.logWifiSettingsSpeed(context, mWifiSpeed);

        Drawable drawable = mIconInjector.getIcon(level);
        if (!mForSavedNetworks && drawable != null) {
            drawable.setTintList(Utils.getColorAttr(context, android.R.attr.colorControlNormal));
            setIcon(drawable);
        } else {
            safeSetDefaultIcon();
        }
    }

    /**
     * Binds the friction icon drawable using a StateListDrawable.
     *
     * <p>Friction icons will be rebound when notifyChange() is called, and therefore
     * do not need to be managed in refresh()</p>.
     */
    private void bindFrictionImage(ImageView frictionImageView) {
        if (frictionImageView == null || mFrictionSld == null) {
            return;
        }
        if ((mAccessPoint.getSecurity() != AccessPoint.SECURITY_NONE)
                && (mAccessPoint.getSecurity() != AccessPoint.SECURITY_OWE)
                && (mAccessPoint.getSecurity() != AccessPoint.SECURITY_OWE_TRANSITION)) {
            mFrictionSld.setState(STATE_SECURED);
        } else if (mAccessPoint.isMetered()) {
            mFrictionSld.setState(STATE_METERED);
        }
        Drawable drawable = mFrictionSld.getCurrent();
        frictionImageView.setImageDrawable(drawable);
    }

    private void safeSetDefaultIcon() {
        if (mDefaultIconResId != 0) {
            setIcon(mDefaultIconResId);
        } else {
            setIcon(null);
        }
    }

    protected void updateBadge(Context context) {
        WifiConfiguration config = mAccessPoint.getConfig();
        if (config != null) {
            // Fetch badge (may be null)
            // Get the badge using a cache since the PM will ask the UserManager for the list
            // of profiles every time otherwise.
            mBadge = mBadgeCache.getUserBadge(config.creatorUid);
        }
    }

    /**
     * Updates the title and summary; may indirectly call notifyChanged().
     */
    public void refresh() {
        setTitle(this, mAccessPoint);
        final Context context = getContext();
        int level = mAccessPoint.getLevel();
        int wifiSpeed = mAccessPoint.getSpeed();
        if (level != mLevel || wifiSpeed != mWifiSpeed) {
            mLevel = level;
            mWifiSpeed = wifiSpeed;
            updateIcon(mLevel, context);
            notifyChanged();
        }

        updateBadge(context);

        setSummary(mForSavedNetworks ? mAccessPoint.getSavedNetworkSummary()
                : mAccessPoint.getSettingsSummary());

        mContentDescription = buildContentDescription(getContext(), this /* pref */, mAccessPoint);
    }

    @Override
    protected void notifyChanged() {
        if (Looper.getMainLooper() != Looper.myLooper()) {
            // Let our BG thread callbacks call setTitle/setSummary.
            postNotifyChanged();
        } else {
            super.notifyChanged();
        }
    }

    @VisibleForTesting
    static void setTitle(AccessPointPreference preference, AccessPoint ap) {
        preference.setTitle(ap.getTitle());
    }

    /**
     * Helper method to generate content description string.
     */
    @VisibleForTesting
    static CharSequence buildContentDescription(Context context, Preference pref, AccessPoint ap) {
        CharSequence contentDescription = pref.getTitle();
        final CharSequence summary = pref.getSummary();
        if (!TextUtils.isEmpty(summary)) {
            contentDescription = TextUtils.concat(contentDescription, ",", summary);
        }
        int level = ap.getLevel();
        if (level >= 0 && level < WIFI_CONNECTION_STRENGTH.length) {
            contentDescription = TextUtils.concat(contentDescription, ",",
                    context.getString(WIFI_CONNECTION_STRENGTH[level]));
        }
        return TextUtils.concat(contentDescription, ",",
                ap.getSecurity() == AccessPoint.SECURITY_NONE
                        ? context.getString(R.string.accessibility_wifi_security_type_none)
                        : context.getString(R.string.accessibility_wifi_security_type_secured));
    }

    public void onLevelChanged() {
        postNotifyChanged();
    }

    private void postNotifyChanged() {
        if (mTitleView != null) {
            mTitleView.post(mNotifyChanged);
        } // Otherwise we haven't been bound yet, and don't need to update.
    }

    private final Runnable mNotifyChanged = new Runnable() {
        @Override
        public void run() {
            notifyChanged();
        }
    };

    public static class UserBadgeCache {
        private final SparseArray<Drawable> mBadges = new SparseArray<>();
        private final PackageManager mPm;

        public UserBadgeCache(PackageManager pm) {
            mPm = pm;
        }

        private Drawable getUserBadge(int userId) {
            int index = mBadges.indexOfKey(userId);
            if (index < 0) {
                Drawable badge = mPm.getUserBadgeForDensity(new UserHandle(userId), 0 /* dpi */);
                mBadges.put(userId, badge);
                return badge;
            }
            return mBadges.valueAt(index);
        }
    }

    static class IconInjector {
        private final Context mContext;

        public IconInjector(Context context) {
            mContext = context;
        }

        public Drawable getIcon(int level) {
            return mContext.getDrawable(Utils.getWifiIconResource(level));
        }
    }
}
