/*
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

package com.android.settingslib.location;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.location.SettingInjectorService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import androidx.preference.Preference;

import com.android.settingslib.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds the preferences specified by the {@link InjectedSetting} objects to a preference group.
 *
 * Duplicates some code from {@link android.content.pm.RegisteredServicesCache}. We do not use that
 * class directly because it is not a good match for our use case: we do not need the caching, and
 * so do not want the additional resource hit at app install/upgrade time; and we would have to
 * suppress the tie-breaking between multiple services reporting settings with the same name.
 * Code-sharing would require extracting {@link
 * android.content.pm.RegisteredServicesCache#parseServiceAttributes(android.content.res.Resources,
 * String, android.util.AttributeSet)} into an interface, which didn't seem worth it.
 */
public class SettingsInjector {
    static final String TAG = "SettingsInjector";

    /**
     * If reading the status of a setting takes longer than this, we go ahead and start reading
     * the next setting.
     */
    private static final long INJECTED_STATUS_UPDATE_TIMEOUT_MILLIS = 1000;

    /**
     * {@link Message#what} value for starting to load status values
     * in case we aren't already in the process of loading them.
     */
    private static final int WHAT_RELOAD = 1;

    /**
     * {@link Message#what} value sent after receiving a status message.
     */
    private static final int WHAT_RECEIVED_STATUS = 2;

    /**
     * {@link Message#what} value sent after the timeout waiting for a status message.
     */
    private static final int WHAT_TIMEOUT = 3;

    private final Context mContext;

    /**
     * The settings that were injected
     */
    protected final Set<Setting> mSettings;

    private final Handler mHandler;

    public SettingsInjector(Context context) {
        mContext = context;
        mSettings = new HashSet<Setting>();
        mHandler = new StatusLoadingHandler(mSettings);
    }

    /**
     * Returns a list for a profile with one {@link InjectedSetting} object for each
     * {@link android.app.Service} that responds to
     * {@link SettingInjectorService#ACTION_SERVICE_INTENT} and provides the expected setting
     * metadata.
     *
     * Duplicates some code from {@link android.content.pm.RegisteredServicesCache}.
     *
     * TODO: unit test
     */
    protected List<InjectedSetting> getSettings(final UserHandle userHandle) {
        PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(SettingInjectorService.ACTION_SERVICE_INTENT);

        final int profileId = userHandle.getIdentifier();
        List<ResolveInfo> resolveInfos =
                pm.queryIntentServicesAsUser(intent, PackageManager.GET_META_DATA, profileId);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Found services for profile id " + profileId + ": " + resolveInfos);
        }

        final PackageManager userPackageManager = mContext.createContextAsUser(
                userHandle, /* flags */ 0).getPackageManager();
        List<InjectedSetting> settings = new ArrayList<InjectedSetting>(resolveInfos.size());
        for (ResolveInfo resolveInfo : resolveInfos) {
            try {
                InjectedSetting setting = parseServiceInfo(resolveInfo, userHandle,
                        userPackageManager);
                if (setting == null) {
                    Log.w(TAG, "Unable to load service info " + resolveInfo);
                } else {
                    settings.add(setting);
                }
            } catch (XmlPullParserException e) {
                Log.w(TAG, "Unable to load service info " + resolveInfo, e);
            } catch (IOException e) {
                Log.w(TAG, "Unable to load service info " + resolveInfo, e);
            }
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Loaded settings for profile id " + profileId + ": " + settings);
        }

        return settings;
    }

    /**
     * Adds the InjectedSetting information to a Preference object
     */
    private void populatePreference(Preference preference, InjectedSetting setting) {
        preference.setTitle(setting.title);
        preference.setSummary(R.string.loading_injected_setting_summary);
        preference.setOnPreferenceClickListener(new ServiceSettingClickedListener(setting));
    }

    /**
     * Gets a list of preferences that other apps have injected.
     *
     * @param profileId Identifier of the user/profile to obtain the injected settings for or
     *                  UserHandle.USER_CURRENT for all profiles associated with current user.
     */
    public Map<Integer, List<Preference>> getInjectedSettings(Context prefContext,
            final int profileId) {
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        final List<UserHandle> profiles = um.getUserProfiles();
        final ArrayMap<Integer, List<Preference>> result = new ArrayMap<>();
        mSettings.clear();
        for (UserHandle userHandle : profiles) {
            if (profileId == UserHandle.USER_CURRENT || profileId == userHandle.getIdentifier()) {
                final List<Preference> prefs = new ArrayList<>();
                Iterable<InjectedSetting> settings = getSettings(userHandle);
                for (InjectedSetting setting : settings) {
                    Preference preference = createPreference(prefContext, setting);
                    populatePreference(preference, setting);
                    prefs.add(preference);
                    mSettings.add(new Setting(setting, preference));
                }
                if (!prefs.isEmpty()) {
                    result.put(userHandle.getIdentifier(), prefs);
                }
            }
        }

        reloadStatusMessages();
        return result;
    }

    /**
     * Creates an injected Preference
     *
     * @return the created Preference
     */
    protected Preference createPreference(Context prefContext, InjectedSetting setting) {
        return new Preference(prefContext);
    }

    /**
     * Gives descendants a chance to log Preference click event
     */
    protected void logPreferenceClick(Intent intent) {
    }

    /**
     * Returns the settings parsed from the attributes of the
     * {@link SettingInjectorService#META_DATA_NAME} tag, or null.
     *
     * Duplicates some code from {@link android.content.pm.RegisteredServicesCache}.
     */
    private static InjectedSetting parseServiceInfo(ResolveInfo service, UserHandle userHandle,
            PackageManager pm) throws XmlPullParserException, IOException {

        ServiceInfo si = service.serviceInfo;
        ApplicationInfo ai = si.applicationInfo;

        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "Ignoring attempt to inject setting from app not in system image: "
                        + service);
                return null;
            }
        }

        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, SettingInjectorService.META_DATA_NAME);
            if (parser == null) {
                throw new XmlPullParserException("No " + SettingInjectorService.META_DATA_NAME
                        + " meta-data for " + service + ": " + si);
            }

            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }

            String nodeName = parser.getName();
            if (!SettingInjectorService.ATTRIBUTES_NAME.equals(nodeName)) {
                throw new XmlPullParserException("Meta-data does not start with "
                        + SettingInjectorService.ATTRIBUTES_NAME + " tag");
            }

            Resources res = pm.getResourcesForApplication(si.packageName);
            return parseAttributes(si.packageName, si.name, userHandle, res, attrs);
        } catch (PackageManager.NameNotFoundException e) {
            throw new XmlPullParserException(
                    "Unable to load resources for package " + si.packageName);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    /**
     * Returns an immutable representation of the static attributes for the setting, or null.
     */
    private static InjectedSetting parseAttributes(String packageName, String className,
            UserHandle userHandle, Resources res, AttributeSet attrs) {

        TypedArray sa = res.obtainAttributes(attrs, android.R.styleable.SettingInjectorService);
        try {
            // Note that to help guard against malicious string injection, we do not allow dynamic
            // specification of the label (setting title)
            final String title = sa.getString(android.R.styleable.SettingInjectorService_title);
            final int iconId =
                    sa.getResourceId(android.R.styleable.SettingInjectorService_icon, 0);
            final String settingsActivity =
                    sa.getString(android.R.styleable.SettingInjectorService_settingsActivity);
            final String userRestriction = sa.getString(
                    android.R.styleable.SettingInjectorService_userRestriction);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "parsed title: " + title + ", iconId: " + iconId
                        + ", settingsActivity: " + settingsActivity);
            }
            return new InjectedSetting.Builder()
                    .setPackageName(packageName)
                    .setClassName(className)
                    .setTitle(title)
                    .setIconId(iconId)
                    .setUserHandle(userHandle)
                    .setSettingsActivity(settingsActivity)
                    .setUserRestriction(userRestriction)
                    .build();
        } finally {
            sa.recycle();
        }
    }

    /**
     * Reloads the status messages for all the preference items.
     */
    public void reloadStatusMessages() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "reloadingStatusMessages: " + mSettings);
        }
        mHandler.sendMessage(mHandler.obtainMessage(WHAT_RELOAD));
    }

    protected class ServiceSettingClickedListener
            implements Preference.OnPreferenceClickListener {
        private InjectedSetting mInfo;

        public ServiceSettingClickedListener(InjectedSetting info) {
            mInfo = info;
        }

        @Override
        public boolean onPreferenceClick(Preference preference) {
            // Activity to start if they click on the preference. Must start in new task to ensure
            // that "android.settings.LOCATION_SOURCE_SETTINGS" brings user back to
            // Settings > Location.
            Intent settingIntent = new Intent();
            settingIntent.setClassName(mInfo.packageName, mInfo.settingsActivity);
            logPreferenceClick(settingIntent);
            // Sometimes the user may navigate back to "Settings" and launch another different
            // injected setting after one injected setting has been launched.
            //
            // FLAG_ACTIVITY_CLEAR_TOP allows multiple Activities to stack on each other. When
            // "back" button is clicked, the user will navigate through all the injected settings
            // launched before. Such behavior could be quite confusing sometimes.
            //
            // In order to avoid such confusion, we use FLAG_ACTIVITY_CLEAR_TASK, which always clear
            // up all existing injected settings and make sure that "back" button always brings the
            // user back to "Settings" directly.
            settingIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mContext.startActivityAsUser(settingIntent, mInfo.mUserHandle);
            return true;
        }
    }

    /**
     * Loads the setting status values one at a time. Each load starts a subclass of {@link
     * SettingInjectorService}, so to reduce memory pressure we don't want to load too many at
     * once.
     */
    private static final class StatusLoadingHandler extends Handler {
        /**
         * References all the injected settings.
         */
        WeakReference<Set<Setting>> mAllSettings;

        /**
         * Settings whose status values need to be loaded. A set is used to prevent redundant loads.
         */
        private Deque<Setting> mSettingsToLoad = new ArrayDeque<Setting>();

        /**
         * Settings that are being loaded now and haven't timed out. In practice this should have
         * zero or one elements.
         */
        private Set<Setting> mSettingsBeingLoaded = new ArraySet<Setting>();

        public StatusLoadingHandler(Set<Setting> allSettings) {
            super(Looper.getMainLooper());
            mAllSettings = new WeakReference<>(allSettings);
        }

        @Override
        public void handleMessage(Message msg) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "handleMessage start: " + msg + ", " + this);
            }

            // Update state in response to message
            switch (msg.what) {
                case WHAT_RELOAD: {
                    final Set<Setting> allSettings = mAllSettings.get();
                    if (allSettings != null) {
                        // Reload requested, so must reload all settings
                        mSettingsToLoad.clear();
                        mSettingsToLoad.addAll(allSettings);
                    }
                    break;
                }
                case WHAT_RECEIVED_STATUS:
                    final Setting receivedSetting = (Setting) msg.obj;
                    receivedSetting.maybeLogElapsedTime();
                    mSettingsBeingLoaded.remove(receivedSetting);
                    removeMessages(WHAT_TIMEOUT, receivedSetting);
                    break;
                case WHAT_TIMEOUT:
                    final Setting timedOutSetting = (Setting) msg.obj;
                    mSettingsBeingLoaded.remove(timedOutSetting);
                    if (Log.isLoggable(TAG, Log.WARN)) {
                        Log.w(TAG, "Timed out after " + timedOutSetting.getElapsedTime()
                                + " millis trying to get status for: " + timedOutSetting);
                    }
                    break;
                default:
                    Log.wtf(TAG, "Unexpected what: " + msg);
            }

            // Decide whether to load additional settings based on the new state. Start by seeing
            // if we have headroom to load another setting.
            if (mSettingsBeingLoaded.size() > 0) {
                // Don't load any more settings until one of the pending settings has completed.
                // To reduce memory pressure, we want to be loading at most one setting.
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "too many services already live for " + msg + ", " + this);
                }
                return;
            }

            if (mSettingsToLoad.isEmpty()) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "nothing left to do for " + msg + ", " + this);
                }
                return;
            }
            Setting setting = mSettingsToLoad.removeFirst();

            // Request the status value
            setting.startService();
            mSettingsBeingLoaded.add(setting);

            // Ensure that if receiving the status value takes too long, we start loading the
            // next value anyway
            Message timeoutMsg = obtainMessage(WHAT_TIMEOUT, setting);
            sendMessageDelayed(timeoutMsg, INJECTED_STATUS_UPDATE_TIMEOUT_MILLIS);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "handleMessage end " + msg + ", " + this
                        + ", started loading " + setting);
            }
        }

        @Override
        public String toString() {
            return "StatusLoadingHandler{" +
                    "mSettingsToLoad=" + mSettingsToLoad +
                    ", mSettingsBeingLoaded=" + mSettingsBeingLoaded +
                    '}';
        }
    }

    private static class MessengerHandler extends Handler {
        private WeakReference<Setting> mSettingRef;
        private Handler mHandler;

        public MessengerHandler(Setting setting, Handler handler) {
            mSettingRef = new WeakReference(setting);
            mHandler = handler;
        }

        @Override
        public void handleMessage(Message msg) {
            final Setting setting = mSettingRef.get();
            if (setting == null) {
                return;
            }
            final Preference preference = setting.preference;
            Bundle bundle = msg.getData();
            boolean enabled = bundle.getBoolean(SettingInjectorService.ENABLED_KEY, true);
            String summary = bundle.getString(SettingInjectorService.SUMMARY_KEY, null);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, setting + ": received " + msg + ", bundle: " + bundle);
            }
            preference.setSummary(summary);
            preference.setEnabled(enabled);
            mHandler.sendMessage(
                    mHandler.obtainMessage(WHAT_RECEIVED_STATUS, setting));
        }
    }

    /**
     * Represents an injected setting and the corresponding preference.
     */
    protected final class Setting {
        public final InjectedSetting setting;
        public final Preference preference;
        public long startMillis;


        public Setting(InjectedSetting setting, Preference preference) {
            this.setting = setting;
            this.preference = preference;
        }

        @Override
        public String toString() {
            return "Setting{" +
                    "setting=" + setting +
                    ", preference=" + preference +
                    '}';
        }

        /**
         * Starts the service to fetch for the current status for the setting, and updates the
         * preference when the service replies.
         */
        public void startService() {
            final ActivityManager am = (ActivityManager)
                    mContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (!am.isUserRunning(setting.mUserHandle.getIdentifier())) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Cannot start service as user "
                            + setting.mUserHandle.getIdentifier() + " is not running");
                }
                return;
            }
            Handler handler = new MessengerHandler(this, mHandler);
            Messenger messenger = new Messenger(handler);

            Intent intent = setting.getServiceIntent();
            intent.putExtra(SettingInjectorService.MESSENGER_KEY, messenger);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, setting + ": sending update intent: " + intent
                        + ", handler: " + handler);
                startMillis = SystemClock.elapsedRealtime();
            } else {
                startMillis = 0;
            }

            // Start the service, making sure that this is attributed to the user associated with
            // the setting rather than the system user.
            mContext.startServiceAsUser(intent, setting.mUserHandle);
        }

        public long getElapsedTime() {
            long end = SystemClock.elapsedRealtime();
            return end - startMillis;
        }

        public void maybeLogElapsedTime() {
            if (Log.isLoggable(TAG, Log.DEBUG) && startMillis != 0) {
                long elapsed = getElapsedTime();
                Log.d(TAG, this + " update took " + elapsed + " millis");
            }
        }
    }
}
