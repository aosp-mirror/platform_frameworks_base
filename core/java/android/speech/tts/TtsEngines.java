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

import static android.provider.Settings.Secure.getString;

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
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
import android.provider.Settings;
import android.speech.tts.TextToSpeech.Engine;
import android.speech.tts.TextToSpeech.EngineInfo;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

/**
 * Support class for querying the list of available engines
 * on the device and deciding which one to use etc.
 *
 * Comments in this class the use the shorthand "system engines" for engines that
 * are a part of the system image.
 *
 * This class is thread-safe/
 *
 * @hide
 */
public class TtsEngines {
    private static final String TAG = "TtsEngines";
    private static final boolean DBG = false;

    /** Locale delimiter used by the old-style 3 char locale string format (like "eng-usa") */
    private static final String LOCALE_DELIMITER_OLD = "-";

    /** Locale delimiter used by the new-style locale string format (Locale.toString() results,
     * like "en_US") */
    private static final String LOCALE_DELIMITER_NEW = "_";

    private final Context mContext;

    /** Mapping of various language strings to the normalized Locale form */
    private static final Map<String, String> sNormalizeLanguage;

    /** Mapping of various country strings to the normalized Locale form */
    private static final Map<String, String> sNormalizeCountry;

    // Populate the sNormalize* maps
    static {
        HashMap<String, String> normalizeLanguage = new HashMap<String, String>();
        for (String language : Locale.getISOLanguages()) {
            try {
                normalizeLanguage.put(new Locale(language).getISO3Language(), language);
            } catch (MissingResourceException e) {
                continue;
            }
        }
        sNormalizeLanguage = Collections.unmodifiableMap(normalizeLanguage);

        HashMap<String, String> normalizeCountry = new HashMap<String, String>();
        for (String country : Locale.getISOCountries()) {
            try {
                normalizeCountry.put(new Locale("", country).getISO3Country(), country);
            } catch (MissingResourceException e) {
                continue;
            }
        }
        sNormalizeCountry = Collections.unmodifiableMap(normalizeCountry);
    }

    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
     * Returns the default locale for a given TTS engine. Attempts to read the
     * value from {@link Settings.Secure#TTS_DEFAULT_LOCALE}, failing which the
     * default phone locale is returned.
     *
     * @param engineName the engine to return the locale for.
     * @return the locale preference for this engine. Will be non null.
     */
    @UnsupportedAppUsage
    public Locale getLocalePrefForEngine(String engineName) {
        return getLocalePrefForEngine(engineName,
                getString(mContext.getContentResolver(), Settings.Secure.TTS_DEFAULT_LOCALE));
    }

    /**
     * Returns the default locale for a given TTS engine from given settings string. */
    public Locale getLocalePrefForEngine(String engineName, String prefValue) {
        String localeString = parseEnginePrefFromList(
                prefValue,
                engineName);

        if (TextUtils.isEmpty(localeString)) {
            // The new style setting is unset, attempt to return the old style setting.
            return Locale.getDefault();
        }

        Locale result = parseLocaleString(localeString);
        if (result == null) {
            Log.w(TAG, "Failed to parse locale " + localeString + ", returning en_US instead");
            result = Locale.US;
        }

        if (DBG) Log.d(TAG, "getLocalePrefForEngine(" + engineName + ")= " + result);

        return result;
    }


    /**
     * True if a given TTS engine uses the default phone locale as a default locale. Attempts to
     * read the value from {@link Settings.Secure#TTS_DEFAULT_LOCALE}. If
     * its  value is empty, this methods returns true.
     *
     * @param engineName the engine to return the locale for.
     */
    public boolean isLocaleSetToDefaultForEngine(String engineName) {
        return TextUtils.isEmpty(parseEnginePrefFromList(
                    getString(mContext.getContentResolver(), Settings.Secure.TTS_DEFAULT_LOCALE),
                    engineName));
    }

    /**
     * Parses a locale encoded as a string, and tries its best to return a valid {@link Locale}
     * object, even if the input string is encoded using the old-style 3 character format e.g.
     * "deu-deu". At the end, we test if the resulting locale can return ISO3 language and
     * country codes ({@link Locale#getISO3Language()} and {@link Locale#getISO3Country()}),
     * if it fails to do so, we return null.
     */
    @UnsupportedAppUsage
    public Locale parseLocaleString(String localeString) {
        String language = "", country = "", variant = "";
        if (!TextUtils.isEmpty(localeString)) {
            String[] split = localeString.split(
                    "[" + LOCALE_DELIMITER_OLD + LOCALE_DELIMITER_NEW + "]");
            language = split[0].toLowerCase();
            if (split.length == 0) {
                Log.w(TAG, "Failed to convert " + localeString + " to a valid Locale object. Only" +
                            " separators");
                return null;
            }
            if (split.length > 3) {
                Log.w(TAG, "Failed to convert " + localeString + " to a valid Locale object. Too" +
                        " many separators");
                return null;
            }
            if (split.length >= 2) {
                country = split[1].toUpperCase();
            }
            if (split.length >= 3) {
                variant = split[2];
            }

        }

        String normalizedLanguage = sNormalizeLanguage.get(language);
        if (normalizedLanguage != null) {
            language = normalizedLanguage;
        }

        String normalizedCountry= sNormalizeCountry.get(country);
        if (normalizedCountry != null) {
            country = normalizedCountry;
        }

        if (DBG) Log.d(TAG, "parseLocalePref(" + language + "," + country +
                "," + variant +")");

        Locale result = new Locale(language, country, variant);
        try {
            result.getISO3Language();
            result.getISO3Country();
            return result;
        } catch(MissingResourceException e) {
            Log.w(TAG, "Failed to convert " + localeString + " to a valid Locale object.");
            return null;
        }
    }

    /**
     * This method tries its best to return a valid {@link Locale} object from the TTS-specific
     * Locale input (returned by {@link TextToSpeech#getLanguage}
     * and {@link TextToSpeech#getDefaultLanguage}). A TTS Locale language field contains
     * a three-letter ISO 639-2/T code (where a proper Locale would use a two-letter ISO 639-1
     * code), and the country field contains a three-letter ISO 3166 country code (where a proper
     * Locale would use a two-letter ISO 3166-1 code).
     *
     * This method tries to convert three-letter language and country codes into their two-letter
     * equivalents. If it fails to do so, it keeps the value from the TTS locale.
     */
    @UnsupportedAppUsage
    public static Locale normalizeTTSLocale(Locale ttsLocale) {
        String language = ttsLocale.getLanguage();
        if (!TextUtils.isEmpty(language)) {
            String normalizedLanguage = sNormalizeLanguage.get(language);
            if (normalizedLanguage != null) {
                language = normalizedLanguage;
            }
        }

        String country = ttsLocale.getCountry();
        if (!TextUtils.isEmpty(country)) {
            String normalizedCountry= sNormalizeCountry.get(country);
            if (normalizedCountry != null) {
                country = normalizedCountry;
            }
        }
        return new Locale(language, country, ttsLocale.getVariant());
    }

    /**
     * Return the old-style string form of the locale. It consists of 3 letter codes:
     * <ul>
     *   <li>"ISO 639-2/T language code" if the locale has no country entry</li>
     *   <li> "ISO 639-2/T language code{@link #LOCALE_DELIMITER}ISO 3166 country code"
     *     if the locale has no variant entry</li>
     *   <li> "ISO 639-2/T language code{@link #LOCALE_DELIMITER}ISO 3166 country
     *     code{@link #LOCALE_DELIMITER}variant" if the locale has a variant entry</li>
     * </ul>
     * If we fail to generate those codes using {@link Locale#getISO3Country()} and
     * {@link Locale#getISO3Language()}, then we return new String[]{"eng","USA",""};
     */
    static public String[] toOldLocaleStringFormat(Locale locale) {
        String[] ret = new String[]{"","",""};
        try {
            // Note that the default locale might have an empty variant
            // or language.
            ret[0] = locale.getISO3Language();
            ret[1] = locale.getISO3Country();
            ret[2] = locale.getVariant();

            return ret;
        } catch (MissingResourceException e) {
            // Default locale does not have a ISO 3166 and/or ISO 639-2/T codes. Return the
            // default "eng-usa" (that would be the result of Locale.getDefault() == Locale.US).
            return new String[]{"eng","USA",""};
        }
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

    /**
     * Serialize the locale to a string and store it as a default locale for the given engine. If
     * the passed locale is null, an empty string will be serialized; that empty string, when
     * read back, will evaluate to {@link Locale#getDefault()}.
     */
    @UnsupportedAppUsage
    public synchronized void updateLocalePrefForEngine(
            @NonNull String engineName, Locale newLocale) {
        final String prefList = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.TTS_DEFAULT_LOCALE);
        if (DBG) {
            Log.d(TAG, "updateLocalePrefForEngine(" + engineName + ", " + newLocale +
                    "), originally: " + prefList);
        }

        final String newPrefList = updateValueInCommaSeparatedList(prefList,
                engineName, (newLocale != null) ? newLocale.toString() : "");

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
