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

import android.annotation.IntDef;
import android.annotation.Nullable;
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
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.settingslib.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
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

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(DreamInfo.class.getSimpleName());
            sb.append('[').append(caption);
            if (isActive)
                sb.append(",active");
            sb.append(',').append(componentName);
            if (settingsComponentName != null)
                sb.append("settings=").append(settingsComponentName);
            return sb.append(']').toString();
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({WHILE_CHARGING, WHILE_DOCKED, EITHER, NEVER})
    public @interface WhenToDream {}

    public static final int WHILE_CHARGING = 0;
    public static final int WHILE_DOCKED = 1;
    public static final int EITHER = 2;
    public static final int NEVER = 3;

    /**
     * The type of dream complications which can be provided by a
     * {@link com.android.systemui.dreams.ComplicationProvider}.
     */
    @IntDef(prefix = {"COMPLICATION_TYPE_"}, value = {
            COMPLICATION_TYPE_TIME,
            COMPLICATION_TYPE_DATE,
            COMPLICATION_TYPE_WEATHER,
            COMPLICATION_TYPE_AIR_QUALITY,
            COMPLICATION_TYPE_CAST_INFO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ComplicationType {}

    public static final int COMPLICATION_TYPE_TIME = 1;
    public static final int COMPLICATION_TYPE_DATE = 2;
    public static final int COMPLICATION_TYPE_WEATHER = 3;
    public static final int COMPLICATION_TYPE_AIR_QUALITY = 4;
    public static final int COMPLICATION_TYPE_CAST_INFO = 5;

    private final Context mContext;
    private final IDreamManager mDreamManager;
    private final DreamInfoComparator mComparator;
    private final boolean mDreamsEnabledByDefault;
    private final boolean mDreamsActivatedOnSleepByDefault;
    private final boolean mDreamsActivatedOnDockByDefault;
    private final Set<ComponentName> mDisabledDreams;
    private final Set<Integer> mSupportedComplications;
    private final Set<Integer> mDefaultEnabledComplications;

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
        mDisabledDreams = Arrays.stream(resources.getStringArray(
                        com.android.internal.R.array.config_disabledDreamComponents))
                .map(ComponentName::unflattenFromString)
                .collect(Collectors.toSet());

        mSupportedComplications = Arrays.stream(resources.getIntArray(
                        com.android.internal.R.array.config_supportedDreamComplications))
                .boxed()
                .collect(Collectors.toSet());

        mDefaultEnabledComplications = Arrays.stream(resources.getIntArray(
                        com.android.internal.R.array.config_dreamComplicationsEnabledByDefault))
                .boxed()
                // A complication can only be enabled by default if it is also supported.
                .filter(mSupportedComplications::contains)
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

            final DreamService.DreamMetadata dreamMetadata = DreamService.getDreamMetadata(mContext,
                    resolveInfo.serviceInfo);
            if (dreamMetadata != null) {
                dreamInfo.settingsComponentName = dreamMetadata.settingsActivity;
                dreamInfo.previewImage = dreamMetadata.previewImage;
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

    public @WhenToDream int getWhenToDreamSetting() {
        return isActivatedOnDock() && isActivatedOnSleep() ? EITHER
                : isActivatedOnDock() ? WHILE_DOCKED
                : isActivatedOnSleep() ? WHILE_CHARGING
                : NEVER;
    }

    public void setWhenToDream(@WhenToDream int whenToDream) {
        setEnabled(whenToDream != NEVER);

        switch (whenToDream) {
            case WHILE_CHARGING:
                setActivatedOnDock(false);
                setActivatedOnSleep(true);
                break;

            case WHILE_DOCKED:
                setActivatedOnDock(true);
                setActivatedOnSleep(false);
                break;

            case EITHER:
                setActivatedOnDock(true);
                setActivatedOnSleep(true);
                break;

            case NEVER:
            default:
                break;
        }
    }

    /** Returns whether a particular complication is enabled */
    public boolean isComplicationEnabled(@ComplicationType int complication) {
        return getEnabledComplications().contains(complication);
    }

    /** Gets all complications which have been enabled by the user. */
    public Set<Integer> getEnabledComplications() {
        final String enabledComplications = Settings.Secure.getString(
                mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_ENABLED_COMPLICATIONS);

        if (enabledComplications == null) {
            return mDefaultEnabledComplications;
        }

        return parseFromString(enabledComplications);
    }

    /** Gets all dream complications which are supported on this device. **/
    public Set<Integer> getSupportedComplications() {
        return mSupportedComplications;
    }

    /**
     * Enables or disables a particular dream complication.
     *
     * @param complicationType The dream complication to be enabled/disabled.
     * @param value            If true, the complication is enabled. Otherwise it is disabled.
     */
    public void setComplicationEnabled(@ComplicationType int complicationType, boolean value) {
        if (!mSupportedComplications.contains(complicationType)) return;

        Set<Integer> enabledComplications = getEnabledComplications();
        if (value) {
            enabledComplications.add(complicationType);
        } else {
            enabledComplications.remove(complicationType);
        }

        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_ENABLED_COMPLICATIONS,
                convertToString(enabledComplications));
    }

    /**
     * Gets the title of a particular complication type to be displayed to the user. If there
     * is no title, null is returned.
     */
    @Nullable
    public CharSequence getComplicationTitle(@ComplicationType int complicationType) {
        int res = 0;
        switch (complicationType) {
            case COMPLICATION_TYPE_TIME:
                res = R.string.dream_complication_title_time;
                break;
            case COMPLICATION_TYPE_DATE:
                res = R.string.dream_complication_title_date;
                break;
            case COMPLICATION_TYPE_WEATHER:
                res = R.string.dream_complication_title_weather;
                break;
            case COMPLICATION_TYPE_AIR_QUALITY:
                res = R.string.dream_complication_title_aqi;
                break;
            case COMPLICATION_TYPE_CAST_INFO:
                res = R.string.dream_complication_title_cast_info;
                break;
            default:
                return null;
        }
        return mContext.getString(res);
    }

    private static String convertToString(Set<Integer> set) {
        return set.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private static Set<Integer> parseFromString(String string) {
        if (TextUtils.isEmpty(string)) {
            return new HashSet<>();
        }
        return Arrays.stream(string.split(","))
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
    }

    public boolean isEnabled() {
        return getBoolean(Settings.Secure.SCREENSAVER_ENABLED, mDreamsEnabledByDefault);
    }

    public void setEnabled(boolean value) {
        logd("setEnabled(%s)", value);
        setBoolean(Settings.Secure.SCREENSAVER_ENABLED, value);
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

    private boolean getBoolean(String key, boolean def) {
        return Settings.Secure.getInt(mContext.getContentResolver(), key, def ? 1 : 0) == 1;
    }

    private void setBoolean(String key, boolean value) {
        Settings.Secure.putInt(mContext.getContentResolver(), key, value ? 1 : 0);
    }

    public void setActiveDream(ComponentName dream) {
        logd("setActiveDream(%s)", dream);
        if (mDreamManager == null)
            return;
        try {
            ComponentName[] dreams = { dream };
            mDreamManager.setDreamComponents(dream == null ? null : dreams);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to set active dream to " + dream, e);
        }
    }

    public ComponentName getActiveDream() {
        if (mDreamManager == null)
            return null;
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
