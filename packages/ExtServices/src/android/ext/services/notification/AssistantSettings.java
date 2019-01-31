/**
 * Copyright (C) 2018 The Android Open Source Project
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

package android.ext.services.notification;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Observes the settings for {@link Assistant}.
 */
final class AssistantSettings extends ContentObserver {
    private static final String LOG_TAG = "AssistantSettings";
    public static Factory FACTORY = AssistantSettings::createAndRegister;
    private static final boolean DEFAULT_GENERATE_REPLIES = true;
    private static final boolean DEFAULT_GENERATE_ACTIONS = true;
    private static final int DEFAULT_NEW_INTERRUPTION_MODEL_INT = 1;
    private static final int DEFAULT_MAX_MESSAGES_TO_EXTRACT = 5;
    @VisibleForTesting
    static final int DEFAULT_MAX_SUGGESTIONS = 3;

    private static final Uri STREAK_LIMIT_URI =
            Settings.Global.getUriFor(Settings.Global.BLOCKING_HELPER_STREAK_LIMIT);
    private static final Uri DISMISS_TO_VIEW_RATIO_LIMIT_URI =
            Settings.Global.getUriFor(
                    Settings.Global.BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT);
    private static final Uri NOTIFICATION_NEW_INTERRUPTION_MODEL_URI =
            Settings.Secure.getUriFor(Settings.Secure.NOTIFICATION_NEW_INTERRUPTION_MODEL);

    private final ContentResolver mResolver;
    private final int mUserId;

    private final Handler mHandler;

    @VisibleForTesting
    protected final Runnable mOnUpdateRunnable;

    // Actual configuration settings.
    float mDismissToViewRatioLimit;
    int mStreakLimit;
    boolean mGenerateReplies = DEFAULT_GENERATE_REPLIES;
    boolean mGenerateActions = DEFAULT_GENERATE_ACTIONS;
    boolean mNewInterruptionModel;
    int mMaxMessagesToExtract = DEFAULT_MAX_MESSAGES_TO_EXTRACT;
    int mMaxSuggestions = DEFAULT_MAX_SUGGESTIONS;

    private AssistantSettings(Handler handler, ContentResolver resolver, int userId,
            Runnable onUpdateRunnable) {
        super(handler);
        mHandler = handler;
        mResolver = resolver;
        mUserId = userId;
        mOnUpdateRunnable = onUpdateRunnable;
    }

    private static AssistantSettings createAndRegister(
            Handler handler, ContentResolver resolver, int userId, Runnable onUpdateRunnable) {
        AssistantSettings assistantSettings =
                new AssistantSettings(handler, resolver, userId, onUpdateRunnable);
        assistantSettings.register();
        assistantSettings.registerDeviceConfigs();
        return assistantSettings;
    }

    /**
     * Creates an instance but doesn't register it as an observer.
     */
    @VisibleForTesting
    protected static AssistantSettings createForTesting(
            Handler handler, ContentResolver resolver, int userId, Runnable onUpdateRunnable) {
        return new AssistantSettings(handler, resolver, userId, onUpdateRunnable);
    }

    private void register() {
        mResolver.registerContentObserver(
                DISMISS_TO_VIEW_RATIO_LIMIT_URI, false, this, mUserId);
        mResolver.registerContentObserver(STREAK_LIMIT_URI, false, this, mUserId);

        // Update all uris on creation.
        update(null);
    }

    private void registerDeviceConfigs() {
        DeviceConfig.addOnPropertyChangedListener(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                this::postToHandler,
                this::onDeviceConfigPropertyChanged);

        // Update the fields in this class from the current state of the device config.
        updateFromDeviceConfigFlags();
    }

    private void postToHandler(Runnable r) {
        this.mHandler.post(r);
    }

    @VisibleForTesting
    void onDeviceConfigPropertyChanged(String namespace, String name, String value) {
        if (!DeviceConfig.NotificationAssistant.NAMESPACE.equals(namespace)) {
            Log.e(LOG_TAG, "Received update from DeviceConfig for unrelated namespace: "
                    + namespace + " " + name + "=" + value);
            return;
        }

        updateFromDeviceConfigFlags();
    }

    private void updateFromDeviceConfigFlags() {
        mGenerateReplies = DeviceConfigHelper.getBoolean(
                DeviceConfig.NotificationAssistant.GENERATE_REPLIES, DEFAULT_GENERATE_REPLIES);

        mGenerateActions = DeviceConfigHelper.getBoolean(
                DeviceConfig.NotificationAssistant.GENERATE_ACTIONS, DEFAULT_GENERATE_ACTIONS);

        mMaxMessagesToExtract = DeviceConfigHelper.getInteger(
                DeviceConfig.NotificationAssistant.MAX_MESSAGES_TO_EXTRACT,
                DEFAULT_MAX_MESSAGES_TO_EXTRACT);

        mMaxSuggestions = DeviceConfigHelper.getInteger(
                DeviceConfig.NotificationAssistant.MAX_SUGGESTIONS, DEFAULT_MAX_SUGGESTIONS);

        mOnUpdateRunnable.run();
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        update(uri);
    }

    private void update(Uri uri) {
        if (uri == null || DISMISS_TO_VIEW_RATIO_LIMIT_URI.equals(uri)) {
            mDismissToViewRatioLimit = Settings.Global.getFloat(
                    mResolver, Settings.Global.BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT,
                    ChannelImpressions.DEFAULT_DISMISS_TO_VIEW_RATIO_LIMIT);
        }
        if (uri == null || STREAK_LIMIT_URI.equals(uri)) {
            mStreakLimit = Settings.Global.getInt(
                    mResolver, Settings.Global.BLOCKING_HELPER_STREAK_LIMIT,
                    ChannelImpressions.DEFAULT_STREAK_LIMIT);
        }
        if (uri == null || NOTIFICATION_NEW_INTERRUPTION_MODEL_URI.equals(uri)) {
            int mNewInterruptionModelInt = Settings.Secure.getInt(
                    mResolver, Settings.Secure.NOTIFICATION_NEW_INTERRUPTION_MODEL,
                    DEFAULT_NEW_INTERRUPTION_MODEL_INT);
            mNewInterruptionModel = mNewInterruptionModelInt == 1;
        }

        mOnUpdateRunnable.run();
    }

    static class DeviceConfigHelper {

        static int getInteger(String key, int defaultValue) {
            String value = getValue(key);
            if (TextUtils.isEmpty(value)) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        static boolean getBoolean(String key, boolean defaultValue) {
            String value = getValue(key);
            if (TextUtils.isEmpty(value)) {
                return defaultValue;
            }
            return Boolean.parseBoolean(value);
        }

        private static String getValue(String key) {
            return DeviceConfig.getProperty(
                    DeviceConfig.NotificationAssistant.NAMESPACE,
                    key);
        }
    }

    public interface Factory {
        AssistantSettings createAndRegister(Handler handler, ContentResolver resolver, int userId,
                Runnable onUpdateRunnable);
    }
}