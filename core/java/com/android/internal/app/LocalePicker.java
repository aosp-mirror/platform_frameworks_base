/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IActivityManager;
import android.app.ListFragment;
import android.app.backup.BackupManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.RemoteException;
import android.provider.Settings;
import android.sysprop.LocalizationProperties;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.android.internal.R;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class LocalePicker extends ListFragment {
    private static final String TAG = "LocalePicker";
    private static final boolean DEBUG = false;
    private static final String[] pseudoLocales = { "en-XA", "ar-XB" };

    public static interface LocaleSelectionListener {
        // You can add any argument if you really need it...
        public void onLocaleSelected(Locale locale);
    }

    LocaleSelectionListener mListener;  // default to null

    public static class LocaleInfo implements Comparable<LocaleInfo> {
        static final Collator sCollator = Collator.getInstance();

        String label;
        final Locale locale;

        public LocaleInfo(String label, Locale locale) {
            this.label = label;
            this.locale = locale;
        }

        public String getLabel() {
            return label;
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public Locale getLocale() {
            return locale;
        }

        @Override
        public String toString() {
            return this.label;
        }

        @Override
        public int compareTo(LocaleInfo another) {
            return sCollator.compare(this.label, another.label);
        }
    }

    public static String[] getSystemAssetLocales() {
        return Resources.getSystem().getAssets().getLocales();
    }

    public static String[] getSupportedLocales(Context context) {
        String[] allLocales = context.getResources().getStringArray(R.array.supported_locales);

        Predicate<String> localeFilter = getLocaleFilter();
        if (localeFilter == null) {
            return allLocales;
        }

        List<String> result = new ArrayList<>(allLocales.length);
        for (String locale : allLocales) {
            if (localeFilter.test(locale)) {
                result.add(locale);
            }
        }

        int localeCount = result.size();
        return (localeCount == allLocales.length) ? allLocales
                : result.toArray(new String[localeCount]);
    }

    @Nullable
    private static Predicate<String> getLocaleFilter() {
        try {
            return LocalizationProperties.locale_filter()
                    .map(filter -> Pattern.compile(filter).asPredicate())
                    .orElse(null);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to read locale filter.", e);
        } catch (PatternSyntaxException e) {
            Log.e(TAG, "Bad locale filter format (\"" + e.getPattern() + "\"), skipping.");
        }

        return null;
    }

    public static List<LocaleInfo> getAllAssetLocales(Context context, boolean isInDeveloperMode) {
        final Resources resources = context.getResources();

        final String[] locales = getSystemAssetLocales();
        List<String> localeList = new ArrayList<String>(locales.length);
        Collections.addAll(localeList, locales);

        Collections.sort(localeList);
        final String[] specialLocaleCodes = resources.getStringArray(R.array.special_locale_codes);
        final String[] specialLocaleNames = resources.getStringArray(R.array.special_locale_names);

        final ArrayList<LocaleInfo> localeInfos = new ArrayList<LocaleInfo>(localeList.size());
        for (String locale : localeList) {
            final Locale l = Locale.forLanguageTag(locale.replace('_', '-'));
            if (l == null || "und".equals(l.getLanguage())
                    || l.getLanguage().isEmpty() || l.getCountry().isEmpty()) {
                continue;
            }
            // Don't show the pseudolocales unless we're in developer mode. http://b/17190407.
            if (!isInDeveloperMode && LocaleList.isPseudoLocale(l)) {
                continue;
            }

            if (localeInfos.isEmpty()) {
                if (DEBUG) {
                    Log.v(TAG, "adding initial "+ toTitleCase(l.getDisplayLanguage(l)));
                }
                localeInfos.add(new LocaleInfo(toTitleCase(l.getDisplayLanguage(l)), l));
            } else {
                // check previous entry:
                //  same lang and a country -> upgrade to full name and
                //    insert ours with full name
                //  diff lang -> insert ours with lang-only name
                final LocaleInfo previous = localeInfos.get(localeInfos.size() - 1);
                if (previous.locale.getLanguage().equals(l.getLanguage()) &&
                        !previous.locale.getLanguage().equals("zz")) {
                    if (DEBUG) {
                        Log.v(TAG, "backing up and fixing " + previous.label + " to " +
                                getDisplayName(previous.locale, specialLocaleCodes, specialLocaleNames));
                    }
                    previous.label = toTitleCase(getDisplayName(
                            previous.locale, specialLocaleCodes, specialLocaleNames));
                    if (DEBUG) {
                        Log.v(TAG, "  and adding "+ toTitleCase(
                                getDisplayName(l, specialLocaleCodes, specialLocaleNames)));
                    }
                    localeInfos.add(new LocaleInfo(toTitleCase(
                            getDisplayName(l, specialLocaleCodes, specialLocaleNames)), l));
                } else {
                    String displayName = toTitleCase(l.getDisplayLanguage(l));
                    if (DEBUG) {
                        Log.v(TAG, "adding "+displayName);
                    }
                    localeInfos.add(new LocaleInfo(displayName, l));
                }
            }
        }

        Collections.sort(localeInfos);
        return localeInfos;
    }

    /**
     * Constructs an Adapter object containing Locale information. Content is sorted by
     * {@link LocaleInfo#label}.
     */
    public static ArrayAdapter<LocaleInfo> constructAdapter(Context context) {
        return constructAdapter(context, R.layout.locale_picker_item, R.id.locale);
    }

    public static ArrayAdapter<LocaleInfo> constructAdapter(Context context,
            final int layoutId, final int fieldId) {
        boolean isInDeveloperMode = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        final List<LocaleInfo> localeInfos = getAllAssetLocales(context, isInDeveloperMode);

        final LayoutInflater inflater =
                (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        return new ArrayAdapter<LocaleInfo>(context, layoutId, fieldId, localeInfos) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view;
                TextView text;
                if (convertView == null) {
                    view = inflater.inflate(layoutId, parent, false);
                    text = (TextView) view.findViewById(fieldId);
                    view.setTag(text);
                } else {
                    view = convertView;
                    text = (TextView) view.getTag();
                }
                LocaleInfo item = getItem(position);
                text.setText(item.toString());
                text.setTextLocale(item.getLocale());

                return view;
            }
        };
    }

    private static String toTitleCase(String s) {
        if (s.length() == 0) {
            return s;
        }

        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String getDisplayName(
            Locale l, String[] specialLocaleCodes, String[] specialLocaleNames) {
        String code = l.toString();

        for (int i = 0; i < specialLocaleCodes.length; i++) {
            if (specialLocaleCodes[i].equals(code)) {
                return specialLocaleNames[i];
            }
        }

        return l.getDisplayName(l);
    }

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        final ArrayAdapter<LocaleInfo> adapter = constructAdapter(getActivity());
        setListAdapter(adapter);
    }

    public void setLocaleSelectionListener(LocaleSelectionListener listener) {
        mListener = listener;
    }

    @Override
    public void onResume() {
        super.onResume();
        getListView().requestFocus();
    }

    /**
     * Each listener needs to call {@link #updateLocale(Locale)} to actually change the locale.
     *
     * We don't call {@link #updateLocale(Locale)} automatically, as it halt the system for
     * a moment and some callers won't want it.
     */
    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        if (mListener != null) {
            final Locale locale = ((LocaleInfo)getListAdapter().getItem(position)).locale;
            mListener.onLocaleSelected(locale);
        }
    }

    /**
     * Requests the system to update the system locale. Note that the system looks halted
     * for a while during the Locale migration, so the caller need to take care of it.
     *
     * @see #updateLocales(LocaleList)
     */
    @UnsupportedAppUsage
    public static void updateLocale(Locale locale) {
        updateLocales(new LocaleList(locale));
    }

    /**
     * Requests the system to update the list of system locales.
     * Note that the system looks halted for a while during the Locale migration,
     * so the caller need to take care of it.
     */
    @UnsupportedAppUsage
    public static void updateLocales(LocaleList locales) {
        if (locales != null) {
            locales = removeExcludedLocales(locales);
        }
        // Note: the empty list case is covered by Configuration.setLocales().

        try {
            final IActivityManager am = ActivityManager.getService();
            final Configuration config = am.getConfiguration();

            config.setLocales(locales);
            config.userSetLocale = true;

            am.updatePersistentConfigurationWithAttribution(config,
                    ActivityThread.currentOpPackageName(), null);
            // Trigger the dirty bit for the Settings Provider.
            BackupManager.dataChanged("com.android.providers.settings");
        } catch (RemoteException e) {
            // Intentionally left blank
        }
    }

    @NonNull
    private static LocaleList removeExcludedLocales(@NonNull LocaleList locales) {
        Predicate<String> localeFilter = getLocaleFilter();
        if (localeFilter == null) {
            return locales;
        }

        int localeCount = locales.size();
        ArrayList<Locale> filteredLocales = new ArrayList<>(localeCount);
        for (int i = 0; i < localeCount; ++i) {
            Locale locale = locales.get(i);
            if (localeFilter.test(locale.toString())) {
                filteredLocales.add(locale);
            }
        }

        return (localeCount == filteredLocales.size()) ? locales
                : new LocaleList(filteredLocales.toArray(new Locale[0]));
    }

    /**
     * Get the locale list.
     *
     * @return The locale list.
     */
    @UnsupportedAppUsage
    public static LocaleList getLocales() {
        try {
            return ActivityManager.getService()
                    .getConfiguration().getLocales();
        } catch (RemoteException e) {
            // If something went wrong
            return LocaleList.getDefault();
        }
    }
}
