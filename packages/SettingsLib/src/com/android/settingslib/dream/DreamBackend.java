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

package com.android.settingslib.dream;

import static android.service.dreams.Flags.allowDreamWhenPostured;

import android.annotation.IntDef;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DreamBackend {
    private static final String TAG = "DreamBackend";
    private static final boolean DEBUG = false;

    public static class DreamInfo {
        public CharSequence caption;
        public Drawable icon;
        public boolean isActive;
        public ComponentName componentName;
        public ComponentName settingsComponentName;
        public CharSequence description;
        public Drawable previewImage;
        public boolean supportsComplications = false;
        public int dreamCategory;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(DreamInfo.class.getSimpleName());
            sb.append('[').append(caption);
            if (isActive) {
                sb.append(",active");
            }
            sb.append(',').append(componentName);
            if (settingsComponentName != null) {
                sb.append("settings=").append(settingsComponentName);
            }
            return sb.append(']').toString();
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            WHILE_CHARGING,
            WHILE_DOCKED,
            WHILE_POSTURED,
            WHILE_CHARGING_OR_DOCKED,
            NEVER
    })
    public @interface WhenToDream {
    }

    public static final int WHILE_CHARGING = 0;
    public static final int WHILE_DOCKED = 1;
    public static final int WHILE_POSTURED = 2;
    public static final int WHILE_CHARGING_OR_DOCKED = 3;
    public static final int NEVER = 4;

    /**
     * The type of dream complications which can be provided by a
     * {@link com.android.systemui.dreams.ComplicationProvider}.
     */
    @IntDef(prefix = {"COMPLICATION_TYPE_"}, value = {
            COMPLICATION_TYPE_TIME,
            COMPLICATION_TYPE_DATE,
            COMPLICATION_TYPE_WEATHER,
            COMPLICATION_TYPE_AIR_QUALITY,
            COMPLICATION_TYPE_CAST_INFO,
            COMPLICATION_TYPE_HOME_CONTROLS,
            COMPLICATION_TYPE_SMARTSPACE,
            COMPLICATION_TYPE_MEDIA_ENTRY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ComplicationType {
    }

    public static final int COMPLICATION_TYPE_TIME = 1;
    public static final int COMPLICATION_TYPE_DATE = 2;
    public static final int COMPLICATION_TYPE_WEATHER = 3;
    public static final int COMPLICATION_TYPE_AIR_QUALITY = 4;
    public static final int COMPLICATION_TYPE_CAST_INFO = 5;
    public static final int COMPLICATION_TYPE_HOME_CONTROLS = 6;
    public static final int COMPLICATION_TYPE_SMARTSPACE = 7;
    public static final int COMPLICATION_TYPE_MEDIA_ENTRY = 8;

    private static final int SCREENSAVER_HOME_CONTROLS_ENABLED_DEFAULT = 1;
    private static final int LOCKSCREEN_SHOW_CONTROLS_DEFAULT = 0;

    private static final int DS_TYPE_ENABLED = FrameworkStatsLog
            .DREAM_SETTING_CHANGED__DREAM_SETTING_TYPE__DREAM_SETTING_TYPE_ENABLED;
    private static final int DS_TYPE_WHEN_TO_DREAM = FrameworkStatsLog
            .DREAM_SETTING_CHANGED__DREAM_SETTING_TYPE__DREAM_SETTING_TYPE_WHEN_TO_DREAM;
    private static final int DS_TYPE_DREAM_COMPONENT = FrameworkStatsLog
            .DREAM_SETTING_CHANGED__DREAM_SETTING_TYPE__DREAM_SETTING_TYPE_DREAM_COMPONENT;
    private static final int DS_TYPE_SHOW_ADDITIONAL_INFO = FrameworkStatsLog
            .DREAM_SETTING_CHANGED__DREAM_SETTING_TYPE__DREAM_SETTING_TYPE_SHOW_ADDITIONAL_INFO;
    private static final int DS_TYPE_SHOW_HOME_CONTROLS = FrameworkStatsLog
            .DREAM_SETTING_CHANGED__DREAM_SETTING_TYPE__DREAM_SETTING_TYPE_SHOW_HOME_CONTROLS;

    private static final int WHEN_TO_DREAM_UNSPECIFIED = FrameworkStatsLog
            .DREAM_SETTING_CHANGED__WHEN_TO_DREAM__WHEN_TO_DREAM_UNSPECIFIED;
    private static final int WHEN_TO_DREAM_CHARGING = FrameworkStatsLog
            .DREAM_SETTING_CHANGED__WHEN_TO_DREAM__WHEN_TO_DREAM_WHILE_CHARGING_ONLY;
    private static final int WHEN_TO_DREAM_DOCKED = FrameworkStatsLog
            .DREAM_SETTING_CHANGED__WHEN_TO_DREAM__WHEN_TO_DREAM_WHILE_DOCKED_ONLY;
    private static final int WHEN_TO_DREAM_POSTURED = FrameworkStatsLog
            .DREAM_SETTING_CHANGED__WHEN_TO_DREAM__WHEN_TO_DREAM_WHILE_POSTURED_ONLY;
    private static final int WHEN_TO_DREAM_CHARGING_OR_DOCKED = FrameworkStatsLog
            .DREAM_SETTING_CHANGED__WHEN_TO_DREAM__WHEN_TO_DREAM_EITHER_CHARGING_OR_DOCKED;

    private final Context mContext;
    private final IDreamManager mDreamManager;
    private final DreamInfoComparator mComparator;
    private final boolean mDreamsEnabledByDefault;
    private final boolean mDreamsActivatedOnSleepByDefault;
    private final boolean mDreamsActivatedOnDockByDefault;
    private final boolean mDreamsActivatedOnPosturedByDefault;
    private final Set<ComponentName> mDisabledDreams;
    private final List<String> mLoggableDreamPrefixes;
    private Set<Integer> mSupportedComplications;
    private static DreamBackend sInstance;

    public static DreamBackend getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DreamBackend(context);
        }
        return sInstance;
    }

    public DreamBackend(Context context) {
        mContext = context.getApplicationContext();
        final Resources resources = mContext.getResources();

        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.getService(DreamService.DREAM_SERVICE));
        mComparator = new DreamInfoComparator(getDefaultDream());
        mDreamsEnabledByDefault = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsEnabledByDefault);
        mDreamsActivatedOnSleepByDefault = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnSleepByDefault);
        mDreamsActivatedOnDockByDefault = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnDockByDefault);
        mDreamsActivatedOnPosturedByDefault = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnPosturedByDefault);
        mDisabledDreams = Arrays.stream(resources.getStringArray(
                        com.android.internal.R.array.config_disabledDreamComponents))
                .map(ComponentName::unflattenFromString)
                .collect(Collectors.toSet());
        mLoggableDreamPrefixes = Arrays.stream(resources.getStringArray(
                com.android.internal.R.array.config_loggable_dream_prefixes)).toList();

        mSupportedComplications = Arrays.stream(resources.getIntArray(
                        com.android.internal.R.array.config_supportedDreamComplications))
                .boxed()
                .collect(Collectors.toSet());
    }

    public List<DreamInfo> getDreamInfos() {
        logd("getDreamInfos()");
        ComponentName activeDream = getActiveDream();
        PackageManager pm = mContext.getPackageManager();
        Intent dreamIntent = new Intent(DreamService.SERVICE_INTERFACE);
        List<ResolveInfo> resolveInfos = pm.queryIntentServices(dreamIntent,
                PackageManager.GET_META_DATA);
        List<DreamInfo> dreamInfos = new ArrayList<>(resolveInfos.size());
        for (ResolveInfo resolveInfo : resolveInfos) {
            final ComponentName componentName = getDreamComponentName(resolveInfo);
            if (componentName == null || mDisabledDreams.contains(componentName)) {
                continue;
            }

            DreamInfo dreamInfo = new DreamInfo();
            dreamInfo.caption = resolveInfo.loadLabel(pm);
            dreamInfo.icon = resolveInfo.loadIcon(pm);
            dreamInfo.description = getDescription(resolveInfo, pm);
            dreamInfo.componentName = componentName;
            dreamInfo.isActive = dreamInfo.componentName.equals(activeDream);

            final DreamService.DreamMetadata dreamMetadata = DreamService.getDreamMetadata(
                    mContext.getPackageManager(), resolveInfo.serviceInfo);
            if (dreamMetadata != null) {
                dreamInfo.settingsComponentName = dreamMetadata.settingsActivity;
                dreamInfo.previewImage = dreamMetadata.previewImage;
                dreamInfo.supportsComplications = dreamMetadata.showComplications;
                dreamInfo.dreamCategory = dreamMetadata.dreamCategory;
            }
            dreamInfos.add(dreamInfo);
        }
        dreamInfos.sort(mComparator);
        return dreamInfos;
    }

    private static CharSequence getDescription(ResolveInfo resolveInfo, PackageManager pm) {
        String packageName = resolveInfo.resolvePackageName;
        ApplicationInfo applicationInfo = null;
        if (packageName == null) {
            packageName = resolveInfo.serviceInfo.packageName;
            applicationInfo = resolveInfo.serviceInfo.applicationInfo;
        }
        if (resolveInfo.serviceInfo.descriptionRes != 0) {
            return pm.getText(packageName,
                    resolveInfo.serviceInfo.descriptionRes,
                    applicationInfo);
        }
        return null;
    }

    public ComponentName getDefaultDream() {
        if (mDreamManager == null) {
            return null;
        }
        try {
            return mDreamManager.getDefaultDreamComponentForUser(mContext.getUserId());
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get default dream", e);
            return null;
        }
    }

    public CharSequence getActiveDreamName() {
        ComponentName cn = getActiveDream();
        if (cn != null) {
            PackageManager pm = mContext.getPackageManager();
            try {
                ServiceInfo ri = pm.getServiceInfo(cn, 0);
                if (ri != null) {
                    return ri.loadLabel(pm);
                }
            } catch (PackageManager.NameNotFoundException exc) {
                return null; // uninstalled?
            }
        }
        return null;
    }

    /**
     * Gets an icon from active dream.
     */
    public Drawable getActiveIcon() {
        final ComponentName cn = getActiveDream();
        if (cn != null) {
            final PackageManager pm = mContext.getPackageManager();
            try {
                final ServiceInfo ri = pm.getServiceInfo(cn, 0);
                if (ri != null) {
                    return ri.loadIcon(pm);
                }
            } catch (PackageManager.NameNotFoundException exc) {
                return null;
            }
        }
        return null;
    }

    @WhenToDream
    public int getWhenToDreamSetting() {
        return isActivatedOnDock() && isActivatedOnSleep() ? WHILE_CHARGING_OR_DOCKED
                : isActivatedOnDock() ? WHILE_DOCKED
                        : isActivatedOnPostured() ? WHILE_POSTURED
                                : isActivatedOnSleep() ? WHILE_CHARGING
                                        : NEVER;
    }

    public void setWhenToDream(@WhenToDream int whenToDream) {
        setEnabled(whenToDream != NEVER);

        switch (whenToDream) {
            case WHILE_CHARGING:
                setActivatedOnDock(false);
                setActivatedOnSleep(true);
                setActivatedOnPostured(false);
                break;

            case WHILE_DOCKED:
                setActivatedOnDock(true);
                setActivatedOnSleep(false);
                setActivatedOnPostured(false);
                break;

            case WHILE_CHARGING_OR_DOCKED:
                setActivatedOnDock(true);
                setActivatedOnSleep(true);
                setActivatedOnPostured(false);
                break;

            case WHILE_POSTURED:
                setActivatedOnPostured(true);
                setActivatedOnSleep(false);
                setActivatedOnDock(false);
                break;

            case NEVER:
            default:
                break;
        }

        logDreamSettingChangeToStatsd(DS_TYPE_WHEN_TO_DREAM);
    }

    /** Gets all complications which have been enabled by the user. */
    public Set<Integer> getEnabledComplications() {
        final Set<Integer> enabledComplications =
                getComplicationsEnabled()
                        ? new ArraySet<>(mSupportedComplications) : new ArraySet<>();

        if (!getHomeControlsEnabled()) {
            enabledComplications.remove(COMPLICATION_TYPE_HOME_CONTROLS);
        } else if (mSupportedComplications.contains(COMPLICATION_TYPE_HOME_CONTROLS)) {
            // Add home control type to list of enabled complications, even if other complications
            // have been disabled.
            enabledComplications.add(COMPLICATION_TYPE_HOME_CONTROLS);
        }
        return enabledComplications;
    }

    /** Sets complication enabled state. */
    public void setComplicationsEnabled(boolean enabled) {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_COMPLICATIONS_ENABLED, enabled ? 1 : 0);
        logDreamSettingChangeToStatsd(DS_TYPE_SHOW_ADDITIONAL_INFO);
    }

    /** Sets whether home controls are enabled by the user on the dream */
    public void setHomeControlsEnabled(boolean enabled) {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_HOME_CONTROLS_ENABLED, enabled ? 1 : 0);
        logDreamSettingChangeToStatsd(DS_TYPE_SHOW_HOME_CONTROLS);
    }

    /** Gets whether home controls button is enabled on the dream */
    private boolean getHomeControlsEnabled() {
        return Settings.Secure.getInt(
                mContext.getContentResolver(),
                Settings.Secure.LOCKSCREEN_SHOW_CONTROLS,
                LOCKSCREEN_SHOW_CONTROLS_DEFAULT) == 1
                && Settings.Secure.getInt(
                        mContext.getContentResolver(),
                        Settings.Secure.SCREENSAVER_HOME_CONTROLS_ENABLED,
                        SCREENSAVER_HOME_CONTROLS_ENABLED_DEFAULT) == 1;
    }

    /**
     * Gets whether complications are enabled on this device
     */
    public boolean getComplicationsEnabled() {
        return Settings.Secure.getInt(
                mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_COMPLICATIONS_ENABLED, 1) == 1;
    }

    /** Gets all dream complications which are supported on this device. **/
    public Set<Integer> getSupportedComplications() {
        return mSupportedComplications;
    }

    /**
     * Sets the list of supported complications. Should only be used in tests.
     */
    @VisibleForTesting
    public void setSupportedComplications(Set<Integer> complications) {
        mSupportedComplications = complications;
    }

    public boolean isEnabled() {
        return getBoolean(Settings.Secure.SCREENSAVER_ENABLED, mDreamsEnabledByDefault);
    }

    public void setEnabled(boolean value) {
        logd("setEnabled(%s)", value);
        setBoolean(Settings.Secure.SCREENSAVER_ENABLED, value);
        logDreamSettingChangeToStatsd(DS_TYPE_ENABLED);
    }

    public boolean isActivatedOnDock() {
        return getBoolean(Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                mDreamsActivatedOnDockByDefault);
    }

    public void setActivatedOnDock(boolean value) {
        logd("setActivatedOnDock(%s)", value);
        setBoolean(Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK, value);
    }

    public boolean isActivatedOnSleep() {
        return getBoolean(Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                mDreamsActivatedOnSleepByDefault);
    }

    public void setActivatedOnSleep(boolean value) {
        logd("setActivatedOnSleep(%s)", value);
        setBoolean(Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP, value);
    }

    public boolean isActivatedOnPostured() {
        return allowDreamWhenPostured()
                && getBoolean(Settings.Secure.SCREENSAVER_ACTIVATE_ON_POSTURED,
                        mDreamsActivatedOnPosturedByDefault);
    }

    /**
     * Sets whether dreams should be activated when the device is postured (stationary and upright)
     */
    public void setActivatedOnPostured(boolean value) {
        if (allowDreamWhenPostured()) {
            logd("setActivatedOnPostured(%s)", value);
            setBoolean(Settings.Secure.SCREENSAVER_ACTIVATE_ON_POSTURED, value);
        }
    }

    private boolean getBoolean(String key, boolean def) {
        return Settings.Secure.getInt(mContext.getContentResolver(), key, def ? 1 : 0) == 1;
    }

    private void setBoolean(String key, boolean value) {
        Settings.Secure.putInt(mContext.getContentResolver(), key, value ? 1 : 0);
    }

    public void setActiveDream(ComponentName dream) {
        logd("setActiveDream(%s)", dream);
        if (mDreamManager == null) {
            return;
        }
        try {
            ComponentName[] dreams = {dream};
            mDreamManager.setDreamComponents(dream == null ? null : dreams);
            logDreamSettingChangeToStatsd(DS_TYPE_DREAM_COMPONENT);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to set active dream to " + dream, e);
        }
    }

    public ComponentName getActiveDream() {
        if (mDreamManager == null) {
            return null;
        }
        try {
            ComponentName[] dreams = mDreamManager.getDreamComponents();
            return dreams != null && dreams.length > 0 ? dreams[0] : null;
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get active dream", e);
            return null;
        }
    }

    public void launchSettings(Context uiContext, DreamInfo dreamInfo) {
        logd("launchSettings(%s)", dreamInfo);
        if (dreamInfo == null || dreamInfo.settingsComponentName == null) {
            return;
        }
        final Intent intent = new Intent()
                .setComponent(dreamInfo.settingsComponentName)
                .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        uiContext.startActivity(intent);
    }

    /**
     * Preview a dream, given the component name.
     */
    public void preview(ComponentName componentName) {
        logd("preview(%s)", componentName);
        if (mDreamManager == null || componentName == null) {
            return;
        }
        try {
            mDreamManager.testDream(mContext.getUserId(), componentName);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to preview " + componentName, e);
        }
    }

    public void startDreaming() {
        logd("startDreaming()");
        if (mDreamManager == null) {
            return;
        }
        try {
            mDreamManager.dream();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to dream", e);
        }
    }

    private static ComponentName getDreamComponentName(ResolveInfo resolveInfo) {
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            return null;
        }
        return new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
    }

    private static void logd(String msg, Object... args) {
        if (DEBUG) {
            Log.d(TAG, args == null || args.length == 0 ? msg : String.format(msg, args));
        }
    }

    private void logDreamSettingChangeToStatsd(int dreamSettingType) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.DREAM_SETTING_CHANGED, /*atom_tag*/
                UserHandle.myUserId(), /*uid*/
                isEnabled(), /*enabled*/
                getActiveDreamComponentForStatsd(), /*dream_component*/
                getWhenToDreamForStatsd(), /*when_to_dream*/
                getComplicationsEnabled(), /*show_additional_info*/
                getHomeControlsEnabled(), /*show_home_controls*/
                dreamSettingType /*dream_setting_type*/
        );
    }

    /**
     * Returns the user selected dream component in string format for stats logging. If the dream
     * component is not loggable, returns "other".
     */
    private String getActiveDreamComponentForStatsd() {
        final ComponentName activeDream = getActiveDream();
        if (activeDream == null) {
            return "";
        }

        final String component = activeDream.flattenToShortString();
        if (isLoggableDreamComponentForStatsd(component)) {
            return component;
        } else {
            return "other";
        }
    }

    /**
     * Whether the dream component is loggable. Only components from the predefined packages are
     * allowed to be logged for privacy.
     */
    private boolean isLoggableDreamComponentForStatsd(String component) {
        for (int i = 0; i < mLoggableDreamPrefixes.size(); i++) {
            if (component.startsWith(mLoggableDreamPrefixes.get(i))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the enum of "when to dream" setting for statsd logging.
     */
    private int getWhenToDreamForStatsd() {
        switch (getWhenToDreamSetting()) {
            case WHILE_CHARGING:
                return WHEN_TO_DREAM_CHARGING;
            case WHILE_DOCKED:
                return WHEN_TO_DREAM_DOCKED;
            case WHILE_POSTURED:
                return WHEN_TO_DREAM_POSTURED;
            case WHILE_CHARGING_OR_DOCKED:
                return WHEN_TO_DREAM_CHARGING_OR_DOCKED;
            case NEVER:
            default:
                return WHEN_TO_DREAM_UNSPECIFIED;
        }
    }

    private static class DreamInfoComparator implements Comparator<DreamInfo> {
        private final ComponentName mDefaultDream;

        public DreamInfoComparator(ComponentName defaultDream) {
            mDefaultDream = defaultDream;
        }

        @Override
        public int compare(DreamInfo lhs, DreamInfo rhs) {
            return sortKey(lhs).compareTo(sortKey(rhs));
        }

        private String sortKey(DreamInfo di) {
            StringBuilder sb = new StringBuilder();
            sb.append(di.componentName.equals(mDefaultDream) ? '0' : '1');
            sb.append(di.caption);
            return sb.toString();
        }
    }
}
