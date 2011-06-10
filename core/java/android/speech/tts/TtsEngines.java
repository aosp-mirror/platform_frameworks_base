/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.speech.tts;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.Settings;
import android.speech.tts.TextToSpeech.Engine;
import android.speech.tts.TextToSpeech.EngineInfo;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Support class for querying the list of available engines
 * on the device and deciding which one to use etc.
 *
 * Comments in this class the use the shorthand "system engines" for engines that
 * are a part of the system image.
 *
 * @hide
 */
public class TtsEngines {
    private final Context mContext;

    public TtsEngines(Context ctx) {
        mContext = ctx;
    }

    /**
     * @return the default TTS engine. If the user has set a default, that
     *         value is returned else the highest ranked engine is returned,
     *         as per {@link EngineInfoComparator}.
     */
    public String getDefaultEngine() {
        String engine = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.TTS_DEFAULT_SYNTH);
        return engine != null ? engine : getHighestRankedEngineName();
    }

    /**
     * @return the package name of the highest ranked system engine, {@code null}
     *         if no TTS engines were present in the system image.
     */
    public String getHighestRankedEngineName() {
        final List<EngineInfo> engines = getEngines();

        if (engines.size() > 0 && engines.get(0).system) {
            return engines.get(0).name;
        }

        return null;
    }

    /**
     * Returns the engine info for a given engine name. Note that engines are
     * identified by their package name.
     */
    public EngineInfo getEngineInfo(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(Engine.INTENT_ACTION_TTS_SERVICE);
        intent.setPackage(packageName);
        List<ResolveInfo> resolveInfos = pm.queryIntentServices(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        // Note that the current API allows only one engine per
        // package name. Since the "engine name" is the same as
        // the package name.
        if (resolveInfos != null && resolveInfos.size() == 1) {
            return getEngineInfo(resolveInfos.get(0), pm);
        }

        return null;
    }

    /**
     * Gets a list of all installed TTS engines.
     *
     * @return A list of engine info objects. The list can be empty, but never {@code null}.
     */
    public List<EngineInfo> getEngines() {
        PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(Engine.INTENT_ACTION_TTS_SERVICE);
        List<ResolveInfo> resolveInfos =
                pm.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfos == null) return Collections.emptyList();

        List<EngineInfo> engines = new ArrayList<EngineInfo>(resolveInfos.size());

        for (ResolveInfo resolveInfo : resolveInfos) {
            EngineInfo engine = getEngineInfo(resolveInfo, pm);
            if (engine != null) {
                engines.add(engine);
            }
        }
        Collections.sort(engines, EngineInfoComparator.INSTANCE);

        return engines;
    }

    /**
     * Checks whether a given engine is enabled or not. Note that all system
     * engines are enabled by default.
     */
    public boolean isEngineEnabled(String engine) {
        // System engines are enabled by default always.
        EngineInfo info = getEngineInfo(engine);
        if (info != null && info.system) {
            return true;
        }

        for (String enabled : getUserEnabledEngines()) {
            if (engine.equals(enabled)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSystemEngine(ServiceInfo info) {
        final ApplicationInfo appInfo = info.applicationInfo;
        return appInfo != null && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private EngineInfo getEngineInfo(ResolveInfo resolve, PackageManager pm) {
        ServiceInfo service = resolve.serviceInfo;
        if (service != null) {
            EngineInfo engine = new EngineInfo();
            // Using just the package name isn't great, since it disallows having
            // multiple engines in the same package, but that's what the existing API does.
            engine.name = service.packageName;
            CharSequence label = service.loadLabel(pm);
            engine.label = TextUtils.isEmpty(label) ? engine.name : label.toString();
            engine.icon = service.getIconResource();
            engine.priority = resolve.priority;
            engine.system = isSystemEngine(service);
            return engine;
        }

        return null;
    }

    // Note that in addition to this list, all engines that are a part
    // of the system are enabled by default.
    private String[] getUserEnabledEngines() {
        String str = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.TTS_ENABLED_PLUGINS);
        if (TextUtils.isEmpty(str)) {
            return new String[0];
        }
        return str.split(" ");
    }

    private static class EngineInfoComparator implements Comparator<EngineInfo> {
        private EngineInfoComparator() { }

        static EngineInfoComparator INSTANCE = new EngineInfoComparator();

        /**
         * Engines that are a part of the system image are always lesser
         * than those that are not. Within system engines / non system engines
         * the engines are sorted in order of their declared priority.
         */
        @Override
        public int compare(EngineInfo lhs, EngineInfo rhs) {
            if (lhs.system && !rhs.system) {
                return -1;
            } else if (rhs.system && !lhs.system) {
                return 1;
            } else {
                // Either both system engines, or both non system
                // engines.
                //
                // Note, this isn't a typo. Higher priority numbers imply
                // higher priority, but are "lower" in the sort order.
                return rhs.priority - lhs.priority;
            }
        }
    }

}
