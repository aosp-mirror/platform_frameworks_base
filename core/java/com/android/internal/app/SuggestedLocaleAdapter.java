/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;


/**
 * This adapter wraps around a regular ListAdapter for LocaleInfo, and creates 2 sections.
 *
 * <p>The first section contains "suggested" languages (usually including a region),
 * the second section contains all the languages within the original adapter.
 * The "others" might still include languages that appear in the "suggested" section.</p>
 *
 * <p>Example: if we show "German Switzerland" as "suggested" (based on SIM, let's say),
 * then "German" will still show in the "others" section, clicking on it will only show the
 * countries for all the other German locales, but not Switzerland
 * (Austria, Belgium, Germany, Liechtenstein, Luxembourg)</p>
 */
public class SuggestedLocaleAdapter extends BaseAdapter implements Filterable {
    private static final int TYPE_HEADER_SUGGESTED = 0;
    private static final int TYPE_HEADER_ALL_OTHERS = 1;
    private static final int TYPE_LOCALE = 2;
    private static final int MIN_REGIONS_FOR_SUGGESTIONS = 6;

    private ArrayList<LocaleStore.LocaleInfo> mLocaleOptions;
    private ArrayList<LocaleStore.LocaleInfo> mOriginalLocaleOptions;
    private int mSuggestionCount;
    private final boolean mCountryMode;
    private LayoutInflater mInflater;

    public SuggestedLocaleAdapter(Set<LocaleStore.LocaleInfo> localeOptions, boolean countryMode) {
        mCountryMode = countryMode;
        mLocaleOptions = new ArrayList<>(localeOptions.size());
        for (LocaleStore.LocaleInfo li : localeOptions) {
            if (li.isSuggested()) {
                mSuggestionCount++;
            }
            mLocaleOptions.add(li);
        }
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        return getItemViewType(position) == TYPE_LOCALE;
    }

    @Override
    public int getItemViewType(int position) {
        if (!showHeaders()) {
            return TYPE_LOCALE;
        } else {
            if (position == 0) {
                return TYPE_HEADER_SUGGESTED;
            }
            if (position == mSuggestionCount + 1) {
                return TYPE_HEADER_ALL_OTHERS;
            }
            return TYPE_LOCALE;
        }
    }

    @Override
    public int getViewTypeCount() {
        if (showHeaders()) {
            return 3; // Two headers in addition to the locales
        } else {
            return 1; // Locales items only
        }
    }

    @Override
    public int getCount() {
        if (showHeaders()) {
            return mLocaleOptions.size() + 2; // 2 extra for the headers
        } else {
            return mLocaleOptions.size();
        }
    }

    @Override
    public Object getItem(int position) {
        int offset = 0;
        if (showHeaders()) {
            offset = position > mSuggestionCount ? -2 : -1;
        }

        return mLocaleOptions.get(position + offset);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null && mInflater == null) {
            mInflater = LayoutInflater.from(parent.getContext());
        }

        int itemType = getItemViewType(position);
        switch (itemType) {
            case TYPE_HEADER_SUGGESTED: // intentional fallthrough
            case TYPE_HEADER_ALL_OTHERS:
                // Covers both null, and "reusing" a wrong kind of view
                if (!(convertView instanceof TextView)) {
                    convertView = mInflater.inflate(R.layout.language_picker_section_header,
                            parent, false);
                }
                TextView textView = (TextView) convertView;
                if (itemType == TYPE_HEADER_SUGGESTED) {
                    textView.setText(R.string.language_picker_section_suggested);
                } else {
                    textView.setText(R.string.language_picker_section_all);
                }
                textView.setTextLocale(Locale.getDefault());
                break;
            default:
                // Covers both null, and "reusing" a wrong kind of view
                if (!(convertView instanceof ViewGroup)) {
                    convertView = mInflater.inflate(R.layout.language_picker_item, parent, false);
                }

                TextView text = (TextView) convertView.findViewById(R.id.locale);
                LocaleStore.LocaleInfo item = (LocaleStore.LocaleInfo) getItem(position);
                text.setText(item.getLabel(mCountryMode));
                text.setTextLocale(item.getLocale());
                text.setContentDescription(item.getContentDescription(mCountryMode));
                if (mCountryMode) {
                    int layoutDir = TextUtils.getLayoutDirectionFromLocale(item.getParent());
                    //noinspection ResourceType
                    convertView.setLayoutDirection(layoutDir);
                    text.setTextDirection(layoutDir == View.LAYOUT_DIRECTION_RTL
                            ? View.TEXT_DIRECTION_RTL
                            : View.TEXT_DIRECTION_LTR);
                }
        }
        return convertView;
    }

    private boolean showHeaders() {
        // We don't want to show suggestions for locales with very few regions
        // (e.g. Romanian, with 2 regions)
        // So we put a (somewhat) arbitrary limit.
        //
        // The initial idea was to make that limit dependent on the screen height.
        // But that would mean rotating the screen could make the suggestions disappear,
        // as the number of countries that fits on the screen would be different in portrait
        // and landscape mode.
        if (mCountryMode && mLocaleOptions.size() < MIN_REGIONS_FOR_SUGGESTIONS) {
            return false;
        }
        return mSuggestionCount != 0 && mSuggestionCount != mLocaleOptions.size();
    }

    /**
     * Sorts the items in the adapter using a locale-aware comparator.
     * @param comp The locale-aware comparator to use.
     */
    public void sort(LocaleHelper.LocaleInfoComparator comp) {
        Collections.sort(mLocaleOptions, comp);
    }

    class FilterByNativeAndUiNames extends Filter {

        @Override
        protected FilterResults performFiltering(CharSequence prefix) {
            FilterResults results = new FilterResults();

            if (mOriginalLocaleOptions == null) {
                mOriginalLocaleOptions = new ArrayList<>(mLocaleOptions);
            }

            ArrayList<LocaleStore.LocaleInfo> values;
            values = new ArrayList<>(mOriginalLocaleOptions);
            if (prefix == null || prefix.length() == 0) {
                results.values = values;
                results.count = values.size();
            } else {
                // TODO: decide if we should use the string's locale
                Locale locale = Locale.getDefault();
                String prefixString = LocaleHelper.normalizeForSearch(prefix.toString(), locale);

                final int count = values.size();
                final ArrayList<LocaleStore.LocaleInfo> newValues = new ArrayList<>();

                for (int i = 0; i < count; i++) {
                    final LocaleStore.LocaleInfo value = values.get(i);
                    final String nameToCheck = LocaleHelper.normalizeForSearch(
                            value.getFullNameInUiLanguage(), locale);
                    final String nativeNameToCheck = LocaleHelper.normalizeForSearch(
                            value.getFullNameNative(), locale);
                    if (wordMatches(nativeNameToCheck, prefixString)
                            || wordMatches(nameToCheck, prefixString)) {
                        newValues.add(value);
                    }
                }

                results.values = newValues;
                results.count = newValues.size();
            }

            return results;
        }

        // TODO: decide if this is enough, or we want to use a BreakIterator...
        boolean wordMatches(String valueText, String prefixString) {
            // First match against the whole, non-split value
            if (valueText.startsWith(prefixString)) {
                return true;
            }

            final String[] words = valueText.split(" ");
            // Start at index 0, in case valueText starts with space(s)
            for (String word : words) {
                if (word.startsWith(prefixString)) {
                    return true;
                }
            }

            return false;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            mLocaleOptions = (ArrayList<LocaleStore.LocaleInfo>) results.values;

            mSuggestionCount = 0;
            for (LocaleStore.LocaleInfo li : mLocaleOptions) {
                if (li.isSuggested()) {
                    mSuggestionCount++;
                }
            }

            if (results.count > 0) {
                notifyDataSetChanged();
            } else {
                notifyDataSetInvalidated();
            }
        }
    }

    @Override
    public Filter getFilter() {
        return new FilterByNativeAndUiNames();
    }
}
