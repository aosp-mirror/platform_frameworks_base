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
 * limitations under the License.
 */

package com.android.internal.app;

import com.android.internal.R;

import android.app.ListFragment;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.ArrayMap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class LocaleAdapter extends ArrayAdapter<LocalePicker.LocaleInfo> {
    final private Map<String, LocalePicker.LocaleInfo> mLevelOne = new ArrayMap<>();
    final private Map<String, HashSet<LocalePicker.LocaleInfo>> mLevelTwo = new ArrayMap<>();
    final private LayoutInflater mInflater;

    final static class LocaleAwareComparator implements Comparator<LocalePicker.LocaleInfo> {
        private final Collator mCollator;

        public LocaleAwareComparator(Locale sortLocale) {
            mCollator = Collator.getInstance(sortLocale);
        }

        @Override
        public int compare(LocalePicker.LocaleInfo lhs, LocalePicker.LocaleInfo rhs) {
            return mCollator.compare(lhs.getLabel(), rhs.getLabel());
        }
    }

    static List<Locale> getCuratedLocaleList(Context context) {
        final Resources resources = context.getResources();
        final String[] supportedLocaleCodes = resources.getStringArray(R.array.supported_locales);

        final ArrayList<Locale> result = new ArrayList<>(supportedLocaleCodes.length);
        for (String localeId : supportedLocaleCodes) {
            Locale locale = Locale.forLanguageTag(localeId);
            if (!locale.getCountry().isEmpty()) {
                result.add(Locale.forLanguageTag(localeId));
            }
        }
        return result;
    }

    public LocaleAdapter(Context context) {
        this(context, getCuratedLocaleList(context));
    }

    static Locale getBaseLocale(Locale locale) {
        return new Locale.Builder()
                .setLocale(locale)
                .setRegion("")
                .build();
    }

    // There is no good API available for this, not even in ICU.
    // We can revisit this if we get some ICU support later
    //
    // There are currently several tickets requesting this feature:
    // * ICU needs to provide an easy way to titlecase only one first letter
    //   http://bugs.icu-project.org/trac/ticket/11729
    // * Add "initial case"
    //    http://bugs.icu-project.org/trac/ticket/8394
    // * Add code for initialCase, toTitlecase don't modify after Lt,
    //   avoid 49Ers, low-level language-specific casing
    //   http://bugs.icu-project.org/trac/ticket/10410
    // * BreakIterator.getFirstInstance: Often you need to titlecase just the first
    //   word, and leave the rest of the string alone.  (closed as duplicate)
    //   http://bugs.icu-project.org/trac/ticket/8946
    //
    // A (clunky) option with the current ICU API is:
    //   BreakIterator breakIterator = BreakIterator.getSentenceInstance(locale);
    //   String result = UCharacter.toTitleCase(locale,
    //       source, breakIterator, UCharacter.TITLECASE_NO_LOWERCASE);
    // That also means creating BreakIteratos for each locale. Expensive...
    private static String toTitleCase(String s, Locale locale) {
        if (s.length() == 0) {
            return s;
        }
        final int firstCodePointLen = s.offsetByCodePoints(0, 1);
        return s.substring(0, firstCodePointLen).toUpperCase(locale)
                + s.substring(firstCodePointLen);
    }

    public LocaleAdapter(Context context, List<Locale> locales) {
        super(context, R.layout.locale_picker_item, R.id.locale);
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        for (Locale locale : locales) {
            Locale baseLocale = getBaseLocale(locale);
            String language = baseLocale.toLanguageTag();
            if (!mLevelOne.containsKey(language)) {
                String label = toTitleCase(baseLocale.getDisplayName(baseLocale), baseLocale);
                mLevelOne.put(language, new LocalePicker.LocaleInfo(label, baseLocale));
            }

            final HashSet<LocalePicker.LocaleInfo> subLocales;
            if (mLevelTwo.containsKey(language)) {
                subLocales = mLevelTwo.get(language);
            } else {
                subLocales = new HashSet<>();
                mLevelTwo.put(language, subLocales);
            }
            String label = locale.getDisplayCountry(locale);
            subLocales.add(new LocalePicker.LocaleInfo(label, locale));
        }

        setAdapterLevel(null);
    }

    public void setAdapterLevel(String parentLocale) {
        this.clear();

        if (parentLocale == null) {
            this.addAll(mLevelOne.values());
        } else {
            this.addAll(mLevelTwo.get(parentLocale));
        }

        Locale sortLocale = (parentLocale == null)
                ? Locale.getDefault()
                : Locale.forLanguageTag(parentLocale);
        LocaleAwareComparator comparator = new LocaleAwareComparator(sortLocale);
        this.sort(comparator);

        this.notifyDataSetChanged();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view;
        TextView text;
        if (convertView == null) {
            view = mInflater.inflate(R.layout.locale_picker_item, parent, false);
            text = (TextView) view.findViewById(R.id.locale);
            view.setTag(text);
        } else {
            view = convertView;
            text = (TextView) view.getTag();
        }
        LocalePicker.LocaleInfo item = getItem(position);
        text.setText(item.getLabel());
        text.setTextLocale(item.getLocale());
        return view;
    }
}

public class LocalePickerWithRegion extends ListFragment {
    private static final int LIST_MODE_LANGUAGE = 0;
    private static final int LIST_MODE_COUNTRY = 1;

    private LocaleAdapter mAdapter;
    private int mDisplayMode = LIST_MODE_LANGUAGE;

    public static interface LocaleSelectionListener {
        // You can add any argument if you really need it...
        public void onLocaleSelected(Locale locale);
    }

    private LocaleSelectionListener mListener = null;

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mAdapter = new LocaleAdapter(getContext());
        mAdapter.setAdapterLevel(null);
        setListAdapter(mAdapter);
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
     * Each listener needs to call {@link LocalePicker.updateLocale(Locale)} to actually
     * change the locale.
     * <p/>
     * We don't call {@link LocalePicker.updateLocale(Locale)} automatically, as it halts
     * the system for a moment and some callers won't want it.
     */
    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        final Locale locale = ((LocalePicker.LocaleInfo) getListAdapter().getItem(position)).locale;
        // TODO: handle the back buttons to return to the language list
        if (mDisplayMode == LIST_MODE_LANGUAGE) {
            mDisplayMode = LIST_MODE_COUNTRY;
            mAdapter.setAdapterLevel(locale.toLanguageTag());
            return;
        }
        if (mListener != null) {
            mListener.onLocaleSelected(locale);
        }
    }
}
