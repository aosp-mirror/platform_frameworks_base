/*
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_QS_MODE;

import android.content.Intent;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.NightDisplayListener;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Switch;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.NightDisplayListenerModule;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.LocationController;

import java.text.DateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.TimeZone;

import javax.inject.Inject;

/** Quick settings tile: Night display **/
public class NightDisplayTile extends QSTileImpl<BooleanState> implements
        NightDisplayListener.Callback {

    public static final String TILE_SPEC = "night";

    /**
     * Pattern for {@link java.time.format.DateTimeFormatter} used to approximate the time to the
     * nearest hour and add on the AM/PM indicator.
     */
    private static final String PATTERN_HOUR = "h a";
    private static final String PATTERN_HOUR_MINUTE = "h:mm a";
    private static final String PATTERN_HOUR_NINUTE_24 = "HH:mm";

    private ColorDisplayManager mManager;
    private final LocationController mLocationController;
    private final NightDisplayListenerModule.Builder mNightDisplayListenerBuilder;
    private NightDisplayListener mListener;
    private boolean mIsListening;

    @Inject
    public NightDisplayTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            LocationController locationController,
            ColorDisplayManager colorDisplayManager,
            NightDisplayListenerModule.Builder nightDisplayListenerBuilder
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mLocationController = locationController;
        mManager = colorDisplayManager;
        mNightDisplayListenerBuilder = nightDisplayListenerBuilder;
        mListener = mNightDisplayListenerBuilder.setUser(host.getUserContext().getUserId()).build();
    }

    @Override
    public boolean isAvailable() {
        return ColorDisplayManager.isNightDisplayAvailable(mContext);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        // Enroll in forced auto mode if eligible.
        if ("1".equals(Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE))
                && mManager.getNightDisplayAutoModeRaw() == -1) {
            mManager.setNightDisplayAutoMode(ColorDisplayManager.AUTO_MODE_CUSTOM_TIME);
            Log.i("NightDisplayTile", "Enrolled in forced night display auto mode");
        }

        // Change current activation state.
        final boolean activated = !mState.value;
        mManager.setNightDisplayActivated(activated);
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        // Stop listening to the old controller.
        if (mIsListening) {
            mListener.setCallback(null);
        }

        mManager = getHost().getUserContext().getSystemService(ColorDisplayManager.class);

        // Make a new controller for the new user.
        mListener = mNightDisplayListenerBuilder.setUser(newUserId).build();
        if (mIsListening) {
            mListener.setCallback(this);
        }

        super.handleUserSwitch(newUserId);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = mManager.isNightDisplayActivated();
        state.label = mContext.getString(R.string.quick_settings_night_display_label);
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.icon = maybeLoadResourceIcon(state.value ? R.drawable.qs_nightlight_icon_on
                : R.drawable.qs_nightlight_icon_off);
        state.secondaryLabel = getSecondaryLabel(state.value);
        state.contentDescription = TextUtils.isEmpty(state.secondaryLabel)
                ? state.label
                : TextUtils.concat(state.label, ", ", state.secondaryLabel);
    }

    /**
     * Returns a String for the secondary label that reflects when the light will be turned on or
     * off based on the current auto mode and night light activated status.
     */
    @Nullable
    private String getSecondaryLabel(boolean isNightLightActivated) {
        switch (mManager.getNightDisplayAutoMode()) {
            case ColorDisplayManager.AUTO_MODE_TWILIGHT:
                if (!mLocationController.isLocationEnabled()) return null;
                // Auto mode related to sunrise & sunset. If the light is on, it's guaranteed to be
                // turned off at sunrise. If it's off, it's guaranteed to be turned on at sunset.
                return isNightLightActivated
                        ? mContext.getString(
                                R.string.quick_settings_night_secondary_label_until_sunrise)
                        : mContext.getString(
                                R.string.quick_settings_night_secondary_label_on_at_sunset);

            case ColorDisplayManager.AUTO_MODE_CUSTOM_TIME:
                // User-specified time, approximated to the nearest hour.
                final @StringRes int toggleTimeStringRes;
                final LocalTime toggleTime;
                final DateTimeFormatter toggleTimeFormat;

                if (isNightLightActivated) {
                    toggleTime = mManager.getNightDisplayCustomEndTime();
                    toggleTimeStringRes = R.string.quick_settings_secondary_label_until;
                } else {
                    toggleTime = mManager.getNightDisplayCustomStartTime();
                    toggleTimeStringRes = R.string.quick_settings_night_secondary_label_on_at;
                }

                // TODO(b/111085930): Move this calendar snippet to a common code location that
                // settings lib can also access.
                final Calendar c = Calendar.getInstance();
                DateFormat nightTileFormat = android.text.format.DateFormat.getTimeFormat(mContext);
                nightTileFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                c.setTimeZone(nightTileFormat.getTimeZone());
                c.set(Calendar.HOUR_OF_DAY, toggleTime.getHour());
                c.set(Calendar.MINUTE, toggleTime.getMinute());
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                return mContext.getString(toggleTimeStringRes, nightTileFormat.format(c.getTime()));

            default:
                // No secondary label when auto mode is disabled.
                return null;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_NIGHT_DISPLAY;
    }

    @Override
    public LogMaker populate(LogMaker logMaker) {
        return super.populate(logMaker)
                .addTaggedData(FIELD_QS_MODE, mManager.getNightDisplayAutoModeRaw());
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS);
    }

    @Override
    protected void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        mIsListening = listening;
        if (listening) {
            mListener.setCallback(this);
            refreshState();
        } else {
            mListener.setCallback(null);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_night_display_label);
    }

    @Override
    public void onActivated(boolean activated) {
        refreshState();
    }
}
