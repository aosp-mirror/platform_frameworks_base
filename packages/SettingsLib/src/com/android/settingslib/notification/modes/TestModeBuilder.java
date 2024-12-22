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
import static android.service.notification.ZenModeConfig.ORIGIN_UNKNOWN;
import static android.service.notification.ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI;

import android.app.AutomaticZenRule;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.net.Uri;
import android.service.notification.Condition;
import android.service.notification.SystemZenRules;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenPolicy;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

public class TestModeBuilder {

    private String mId;
    private AutomaticZenRule mRule;
    private ZenModeConfig.ZenRule mConfigZenRule;

    public static final ZenMode EXAMPLE = new TestModeBuilder().build();

    public static final ZenMode MANUAL_DND_ACTIVE = manualDnd(Uri.EMPTY,
            INTERRUPTION_FILTER_PRIORITY, true);

    public static final ZenMode MANUAL_DND_INACTIVE = manualDnd(Uri.EMPTY,
            INTERRUPTION_FILTER_PRIORITY, false);

    @NonNull
    public static ZenMode manualDnd(@NotificationManager.InterruptionFilter int filter,
            boolean isActive) {
        return manualDnd(Uri.EMPTY, filter, isActive);
    }

    private static ZenMode manualDnd(Uri conditionId,
            @NotificationManager.InterruptionFilter int filter, boolean isActive) {
        return ZenMode.manualDndMode(
                new AutomaticZenRule.Builder("Do Not Disturb", conditionId)
                        .setInterruptionFilter(filter)
                        .setType(AutomaticZenRule.TYPE_OTHER)
                        .setManualInvocationAllowed(true)
                        .setPackage(SystemZenRules.PACKAGE_ANDROID)
                        .setZenPolicy(new ZenPolicy.Builder().disallowAllSounds().build())
                        .build(),
                isActive);
    }

    public TestModeBuilder() {
        // Reasonable defaults
        int id = new Random().nextInt(1000);
        mId = "rule_" + id;
        mRule = new AutomaticZenRule.Builder("Test Rule #" + id, Uri.parse("rule://" + id))
                .setPackage("some_package")
                .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                .setZenPolicy(new ZenPolicy.Builder().disallowAllSounds().build())
                .build();
        mConfigZenRule = new ZenModeConfig.ZenRule();
        mConfigZenRule.enabled = true;
        mConfigZenRule.pkg = "some_package";
    }

    public TestModeBuilder(ZenMode previous) {
        mId = previous.getId();
        mRule = new AutomaticZenRule.Builder(previous.getRule()).build();

        mConfigZenRule = new ZenModeConfig.ZenRule();
        mConfigZenRule.enabled = previous.getRule().isEnabled();
        mConfigZenRule.pkg = previous.getRule().getPackageName();
        setActive(previous.isActive());
    }

    public TestModeBuilder setId(String id) {
        mId = id;
        return this;
    }

    public TestModeBuilder setAzr(AutomaticZenRule rule) {
        mRule = rule;
        mConfigZenRule.pkg = rule.getPackageName();
        mConfigZenRule.conditionId = rule.getConditionId();
        mConfigZenRule.enabled = rule.isEnabled();
        return this;
    }

    public TestModeBuilder setConfigZenRule(ZenModeConfig.ZenRule configZenRule) {
        mConfigZenRule = configZenRule;
        return this;
    }

    public TestModeBuilder setName(String name) {
        mRule.setName(name);
        mConfigZenRule.name = name;
        return this;
    }

    public TestModeBuilder setPackage(String pkg) {
        mRule.setPackageName(pkg);
        mConfigZenRule.pkg = pkg;
        return this;
    }

    public TestModeBuilder setOwner(ComponentName owner) {
        mRule.setOwner(owner);
        mConfigZenRule.component = owner;
        return this;
    }

    public TestModeBuilder setConfigurationActivity(ComponentName configActivity) {
        mRule.setConfigurationActivity(configActivity);
        mConfigZenRule.configurationActivity = configActivity;
        return this;
    }

    public TestModeBuilder setConditionId(Uri conditionId) {
        mRule.setConditionId(conditionId);
        mConfigZenRule.conditionId = conditionId;
        return this;
    }

    public TestModeBuilder setType(@AutomaticZenRule.Type int type) {
        mRule.setType(type);
        mConfigZenRule.type = type;
        return this;
    }

    public TestModeBuilder setInterruptionFilter(
            @NotificationManager.InterruptionFilter int interruptionFilter) {
        mRule.setInterruptionFilter(interruptionFilter);
        mConfigZenRule.zenMode = NotificationManager.zenModeFromInterruptionFilter(
                interruptionFilter, INTERRUPTION_FILTER_PRIORITY);
        return this;
    }

    public TestModeBuilder setZenPolicy(@Nullable ZenPolicy policy) {
        mRule.setZenPolicy(policy);
        mConfigZenRule.zenPolicy = policy;
        return this;
    }

    public TestModeBuilder setDeviceEffects(@Nullable ZenDeviceEffects deviceEffects) {
        mRule.setDeviceEffects(deviceEffects);
        mConfigZenRule.zenDeviceEffects = deviceEffects;
        return this;
    }

    public TestModeBuilder setEnabled(boolean enabled) {
        return setEnabled(enabled, /* byUser= */ false);
    }

    public TestModeBuilder setEnabled(boolean enabled, boolean byUser) {
        mRule.setEnabled(enabled);
        mConfigZenRule.enabled = enabled;
        if (!enabled) {
            mConfigZenRule.disabledOrigin = byUser ? ORIGIN_USER_IN_SYSTEMUI : ORIGIN_UNKNOWN;
        }
        return this;
    }

    public TestModeBuilder setManualInvocationAllowed(boolean allowed) {
        mRule.setManualInvocationAllowed(allowed);
        mConfigZenRule.allowManualInvocation = allowed;
        return this;
    }

    public TestModeBuilder setTriggerDescription(@Nullable String triggerDescription) {
        mRule.setTriggerDescription(triggerDescription);
        mConfigZenRule.triggerDescription = triggerDescription;
        return this;
    }

    public TestModeBuilder setIconResId(@DrawableRes int iconResId) {
        mRule.setIconResId(iconResId);
        return this;
    }

    public TestModeBuilder setActive(boolean active) {
        if (active) {
            mConfigZenRule.enabled = true;
            mConfigZenRule.condition = new Condition(mRule.getConditionId(), "...",
                    Condition.STATE_TRUE);
        } else {
            mConfigZenRule.condition = null;
        }
        return this;
    }

    public ZenMode build() {
        return new ZenMode(mId, mRule, mConfigZenRule);
    }
}
