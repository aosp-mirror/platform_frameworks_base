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

import org.xmlpull.v1.XmlPullParserException;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import static android.provider.Settings.Secure.getString;

import android.provider.Settings;
import android.speech.tts.TextToSpeech.Engine;
import android.speech.tts.TextToSpeech.EngineInfo;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
    private static final String TAG = "TtsEngines";
    private static final boolean DBG = false;

    private static final String LOCALE_DELIMITER = "-";

    private final Context mContext;

    public TtsEngines(Context ctx) {
        mContext = ctx;
    }

    /**
     * @return the default TTS engine. If the user has set a default, and the engine
     *         is available on the device, the default is returned. Otherwise,
     *         the highest ranked engine is returned as per {@link EngineInfoComparator}.
     */
    public String getDefaultEngine() {
        String engine = getString(mContext.getContentResolver(),
                Settings.Secure.TTS_DEFAULT_SYNTH);
        return isEngineInstalled(engine) ? engine : getHighestRankedEngineName();
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

    private boolean isSystemEngine(ServiceInfo info) {
        final ApplicationInfo appInfo = info.applicationInfo;
        return appInfo != null && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /**
     * @return true if a given engine is installed on the system.
     */
    public boolean isEngineInstalled(String engine) {
        if (engine == null) {
            return false;
        }

        return getEngineInfo(engine) != null;
    }

    /**
     * @return an intent that can launch the settings activity for a given tts engine.
     */
    public Intent getSettingsIntent(String engine) {
        PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(Engine.INTENT_ACTION_TTS_SERVICE);
        intent.setPackage(engine);
        List<ResolveInfo> resolveInfos = pm.queryIntentServices(intent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_META_DATA);
        // Note that the current API allows only one engine per
        // package name. Since the "engine name" is the same as
        // the package name.
        if (resolveInfos != null && resolveInfos.size() == 1) {
            ServiceInfo service = resolveInfos.get(0).serviceInfo;
            if (service != null) {
                final String settings = settingsActivityFromServiceInfo(service, pm);
                if (settings != null) {
                    Intent i = new Intent();
                    i.setClassName(engine, settings);
                    return i;
                }
            }
        }

        return null;
    }

    /**
     * The name of the XML tag that text to speech engines must use to
     * declare their meta data.
     *
     * {@link com.android.internal.R.styleable#TextToSpeechEngine}
     */
    private static final String XML_TAG_NAME = "tts-engine";

    private String settingsActivityFromServiceInfo(ServiceInfo si, PackageManager pm) {
        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, TextToSpeech.Engine.SERVICE_META_DATA);
            if (parser == null) {
                Log.w(TAG, "No meta-data found for :" + si);
                return null;
            }

            final Resources res = pm.getResourcesForApplication(si.applicationInfo);

            int type;
            while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT) {
                if (type == XmlResourceParser.START_TAG) {
                    if (!XML_TAG_NAME.equals(parser.getName())) {
                        Log.w(TAG, "Package " + si + " uses unknown tag :"
                                + parser.getName());
                        return null;
                    }

                    final AttributeSet attrs = Xml.asAttributeSet(parser);
                    final TypedArray array = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.TextToSpeechEngine);
                    final String settings = array.getString(
                            com.android.internal.R.styleable.TextToSpeechEngine_settingsActivity);
                    array.recycle();

                    return settings;
                }
            }

            return null;
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Could not load resources for : " + si);
            return null;
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Error parsing metadata for " + si + ":" + e);
            return null;
        } catch (IOException e) {
            Log.w(TAG, "Error parsing metadata for " + si + ":" + e);
            return null;
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
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

    /**
     * Returns the locale string for a given TTS engine. Attempts to read the
     * value from {@link Settings.Secure#TTS_DEFAULT_LOCALE}, failing which the
     * old style value from {@link Settings.Secure#TTS_DEFAULT_LANG} is read. If
     * both these values are empty, the default phone locale is returned.
     *
     * @param engineName the engine to return the locale for.
     * @return the locale string preference for this engine. Will be non null
     *         and non empty.
     */
    public String getLocalePrefForEngine(String engineName) {
        String locale = parseEnginePrefFromList(
                getString(mContext.getContentResolver(), Settings.Secure.TTS_DEFAULT_LOCALE),
                engineName);

        if (TextUtils.isEmpty(locale)) {
            // The new style setting is unset, attempt to return the old style setting.
            locale = getV1Locale();
        }

        if (DBG) Log.d(TAG, "getLocalePrefForEngine(" + engineName + ")= " + locale);

        return locale;
    }

    /**
     * Parses a locale preference value delimited by {@link #LOCALE_DELIMITER}.
     * Varies from {@link String#split} in that it will always return an array
     * of length 3 with non null values.
     */
    public static String[] parseLocalePref(String pref) {
        String[] returnVal = new String[] { "", "", ""};
        if (!TextUtils.isEmpty(pref)) {
            String[] split = pref.split(LOCALE_DELIMITER);
            System.arraycopy(split, 0, returnVal, 0, split.length);
        }

        if (DBG) Log.d(TAG, "parseLocalePref(" + returnVal[0] + "," + returnVal[1] +
                "," + returnVal[2] +")");

        return returnVal;
    }

    /**
     * @return the old style locale string constructed from
     *         {@link Settings.Secure#TTS_DEFAULT_LANG},
     *         {@link Settings.Secure#TTS_DEFAULT_COUNTRY} and
     *         {@link Settings.Secure#TTS_DEFAULT_VARIANT}. If no such locale is set,
     *         then return the default phone locale.
     */
    private String getV1Locale() {
        final ContentResolver cr = mContext.getContentResolver();

        final String lang = Settings.Secure.getString(cr, Settings.Secure.TTS_DEFAULT_LANG);
        final String country = Settings.Secure.getString(cr, Settings.Secure.TTS_DEFAULT_COUNTRY);
        final String variant = Settings.Secure.getString(cr, Settings.Secure.TTS_DEFAULT_VARIANT);

        if (TextUtils.isEmpty(lang)) {
            return getDefaultLocale();
        }

        String v1Locale = lang;
        if (!TextUtils.isEmpty(country)) {
            v1Locale += LOCALE_DELIMITER + country;
        } else {
            return v1Locale;
        }

        if (!TextUtils.isEmpty(variant)) {
            v1Locale += LOCALE_DELIMITER + variant;
        }

        return v1Locale;
    }

    private String getDefaultLocale() {
        final Locale locale = Locale.getDefault();

        // Note that the default locale might have an empty variant
        // or language, and we take care that the construction is
        // the same as {@link #getV1Locale} i.e no trailing delimiters
        // or spaces.
        String defaultLocale = locale.getISO3Language();
        if (TextUtils.isEmpty(defaultLocale)) {
            Log.w(TAG, "Default locale is empty.");
            return "";
        }

        if (!TextUtils.isEmpty(locale.getISO3Country())) {
            defaultLocale += LOCALE_DELIMITER + locale.getISO3Country();
        } else {
            // Do not allow locales of the form lang--variant with
            // an empty country.
            return defaultLocale;
        }
        if (!TextUtils.isEmpty(locale.getVariant())) {
            defaultLocale += LOCALE_DELIMITER + locale.getVariant();
        }

        return defaultLocale;
    }

    /**
     * Parses a comma separated list of engine locale preferences. The list is of the
     * form {@code "engine_name_1:locale_1,engine_name_2:locale2"} and so on and
     * so forth. Returns null if the list is empty, malformed or if there is no engine
     * specific preference in the list.
     */
    private static String parseEnginePrefFromList(String prefValue, String engineName) {
        if (TextUtils.isEmpty(prefValue)) {
            return null;
        }

        String[] prefValues = prefValue.split(",");

        for (String value : prefValues) {
            final int delimiter = value.indexOf(':');
            if (delimiter > 0) {
                if (engineName.equals(value.substring(0, delimiter))) {
                    return value.substring(delimiter + 1);
                }
            }
        }

        return null;
    }

    public synchronized void updateLocalePrefForEngine(String name, String newLocale) {
        final String prefList = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.TTS_DEFAULT_LOCALE);
        if (DBG) {
            Log.d(TAG, "updateLocalePrefForEngine(" + name + ", " + newLocale +
                    "), originally: " + prefList);
        }

        final String newPrefList = updateValueInCommaSeparatedList(prefList,
                name, newLocale);

        if (DBG) Log.d(TAG, "updateLocalePrefForEngine(), writing: " + newPrefList.toString());

        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.TTS_DEFAULT_LOCALE, newPrefList.toString());
    }

    /**
     * Updates the value for a given key in a comma separated list of key value pairs,
     * each of which are delimited by a colon. If no value exists for the given key,
     * the kay value pair are appended to the end of the list.
     */
    private String updateValueInCommaSeparatedList(String list, String key,
            String newValue) {
        StringBuilder newPrefList = new StringBuilder();
        if (TextUtils.isEmpty(list)) {
            // If empty, create a new list with a single entry.
            newPrefList.append(key).append(':').append(newValue);
        } else {
            String[] prefValues = list.split(",");
            // Whether this is the first iteration in the loop.
            boolean first = true;
            // Whether we found the given key.
            boolean found = false;
            for (String value : prefValues) {
                final int delimiter = value.indexOf(':');
                if (delimiter > 0) {
                    if (key.equals(value.substring(0, delimiter))) {
                        if (first) {
                            first = false;
                        } else {
                            newPrefList.append(',');
                        }
                        found = true;
                        newPrefList.append(key).append(':').append(newValue);
                    } else {
                        if (first) {
                            first = false;
                        } else {
                            newPrefList.append(',');
                        }
                        // Copy across the entire key + value as is.
                        newPrefList.append(value);
                    }
                }
            }

            if (!found) {
                // Not found, but the rest of the keys would have been copied
                // over already, so just append it to the end.
                newPrefList.append(',');
                newPrefList.append(key).append(':').append(newValue);
            }
        }

        return newPrefList.toString();
    }
}
