/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.notification.modes;

import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.app.NotificationManager.zenModeToInterruptionFilter;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
import static android.service.notification.SystemZenRules.PACKAGE_ANDROID;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AutomaticZenRule;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.util.Log;

import androidx.annotation.DrawableRes;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Class used for Settings-NMS interactions related to Mode management.
 *
 * <p>This class converts {@link AutomaticZenRule} instances, as well as the manual zen mode,
 * into the unified {@link ZenMode} format.
 */
public class ZenModesBackend {

    private static final String TAG = "ZenModeBackend";

    @Nullable // Until first usage
    private static ZenModesBackend sInstance;

    private final NotificationManager mNotificationManager;

    private final Context mContext;

    public static ZenModesBackend getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ZenModesBackend(context.getApplicationContext());
        }
        return sInstance;
    }

    /** Replaces the singleton instance of {@link ZenModesBackend} by the provided one. */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setInstance(@Nullable ZenModesBackend backend) {
        sInstance = backend;
    }

    ZenModesBackend(Context context) {
        mContext = context;
        mNotificationManager = context.getSystemService(NotificationManager.class);
    }

    public List<ZenMode> getModes() {
        Map<String, AutomaticZenRule> zenRules = mNotificationManager.getAutomaticZenRules();
        ZenModeConfig currentConfig = mNotificationManager.getZenModeConfig();

        ArrayList<ZenMode> modes = new ArrayList<>();
        modes.add(getManualDndMode(currentConfig));

        for (Map.Entry<String, AutomaticZenRule> zenRuleEntry : zenRules.entrySet()) {
            String ruleId = zenRuleEntry.getKey();
            ZenModeConfig.ZenRule extraData = currentConfig.automaticRules.get(ruleId);
            if (extraData != null) {
                modes.add(new ZenMode(ruleId, zenRuleEntry.getValue(), extraData));
            } else {
                Log.w(TAG, "Found AZR " + zenRuleEntry.getValue()
                        + " but no corresponding entry in ZenModeConfig (" + currentConfig
                        + "). Skipping");
            }
        }

        modes.sort(ZenMode.PRIORITIZING_COMPARATOR);
        return modes;
    }

    @Nullable
    public ZenMode getMode(String id) {
        ZenModeConfig currentConfig = mNotificationManager.getZenModeConfig();
        if (ZenMode.MANUAL_DND_MODE_ID.equals(id)) {
            return getManualDndMode(currentConfig);
        } else {
            AutomaticZenRule rule = mNotificationManager.getAutomaticZenRule(id);
            ZenModeConfig.ZenRule extraData = currentConfig.automaticRules.get(id);
            if (rule == null || extraData == null) {
                return null;
            }
            return new ZenMode(id, rule, extraData);
        }
    }

    private ZenMode getManualDndMode(ZenModeConfig config) {
        ZenModeConfig.ZenRule manualRule = config.manualRule;

        // If DND is currently on with an interruption filter other than PRIORITY, construct the
        // rule with that. DND will be *non-editable* while in this state.
        int dndInterruptionFilter = config.isManualActive()
                ? zenModeToInterruptionFilter(manualRule.zenMode)
                : INTERRUPTION_FILTER_PRIORITY;

        AutomaticZenRule manualDndRule = new AutomaticZenRule.Builder(
                mContext.getString(R.string.zen_mode_do_not_disturb_name), manualRule.conditionId)
                .setPackage(PACKAGE_ANDROID)
                .setType(AutomaticZenRule.TYPE_OTHER)
                .setZenPolicy(manualRule.zenPolicy)
                .setDeviceEffects(manualRule.zenDeviceEffects)
                .setManualInvocationAllowed(true)
                .setConfigurationActivity(null) // No further settings
                .setInterruptionFilter(dndInterruptionFilter)
                .build();

        return ZenMode.manualDndMode(manualDndRule, config.isManualActive());
    }

    public void updateMode(ZenMode mode) {
        if (mode.isManualDnd()) {
            try {
                NotificationManager.Policy dndPolicy =
                        new ZenModeConfig().toNotificationPolicy(mode.getPolicy());
                mNotificationManager.setNotificationPolicy(dndPolicy, /* fromUser= */ true);

                mNotificationManager.setManualZenRuleDeviceEffects(
                        mode.getRule().getDeviceEffects());
            } catch (Exception e) {
                Log.w(TAG, "Error updating manual mode", e);
            }
        } else {
            mNotificationManager.updateAutomaticZenRule(mode.getId(), mode.getRule(),
                    /* fromUser= */ true);
        }
    }

    public void activateMode(ZenMode mode, @Nullable Duration forDuration) {
        if (mode.isManualDnd()) {
            Uri durationConditionId = null;
            if (forDuration != null) {
                durationConditionId = ZenModeConfig.toTimeCondition(mContext,
                        (int) forDuration.toMinutes(), ActivityManager.getCurrentUser(), true).id;
            }
            mNotificationManager.setZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                    durationConditionId, TAG, /* fromUser= */ true);

        } else {
            if (forDuration != null) {
                throw new IllegalArgumentException(
                        "Only the manual DND mode can be activated for a specific duration");
            }
            mNotificationManager.setAutomaticZenRuleState(mode.getId(),
                    new Condition(mode.getRule().getConditionId(), "", Condition.STATE_TRUE,
                            Condition.SOURCE_USER_ACTION));
        }
    }

    public void deactivateMode(ZenMode mode) {
        if (mode.isManualDnd()) {
            // When calling with fromUser=true this will not snooze other modes.
            mNotificationManager.setZenMode(Settings.Global.ZEN_MODE_OFF, null, TAG,
                    /* fromUser= */ true);
        } else {
            mNotificationManager.setAutomaticZenRuleState(mode.getId(),
                    new Condition(mode.getRule().getConditionId(), "", Condition.STATE_FALSE,
                            Condition.SOURCE_USER_ACTION));
        }
    }

    public void removeMode(ZenMode mode) {
        if (!mode.canBeDeleted()) {
            throw new IllegalArgumentException("Mode " + mode + " cannot be deleted!");
        }
        mNotificationManager.removeAutomaticZenRule(mode.getId(), /* fromUser= */ true);
    }

    /**
     * Creates a new custom mode with the provided {@code name}. The mode will be "manual" (i.e.
     * not have a schedule), this can be later updated by the user in the mode settings page.
     *
     * @param name mode name
     * @param iconResId resource id of the chosen icon, {code 0} if none.
     * @return the created mode. Only {@code null} if creation failed due to an internal error
     */
    @Nullable
    public ZenMode addCustomManualMode(String name, @DrawableRes int iconResId) {
        AutomaticZenRule rule = ZenMode.newCustomManual(name, iconResId).getRule();
        String ruleId = mNotificationManager.addAutomaticZenRule(rule);
        return getMode(ruleId);
    }
}
