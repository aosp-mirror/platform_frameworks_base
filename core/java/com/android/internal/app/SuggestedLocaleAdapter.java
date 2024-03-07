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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.LinearLayout;
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
    protected static final int TYPE_HEADER_SUGGESTED = 0;
    protected static final int TYPE_HEADER_ALL_OTHERS = 1;
    protected static final int TYPE_LOCALE = 2;
    protected static final int TYPE_SYSTEM_LANGUAGE_FOR_APP_LANGUAGE_PICKER = 3;
    protected static final int TYPE_CURRENT_LOCALE = 4;
    protected static final int MIN_REGIONS_FOR_SUGGESTIONS = 6;
    protected static final int APP_LANGUAGE_PICKER_TYPE_COUNT = 5;
    protected static final int SYSTEM_LANGUAGE_TYPE_COUNT = 3;
    protected static final int SYSTEM_LANGUAGE_WITHOUT_HEADER_TYPE_COUNT = 1;

    protected ArrayList<LocaleStore.LocaleInfo> mLocaleOptions;
    protected ArrayList<LocaleStore.LocaleInfo> mOriginalLocaleOptions;
    protected int mSuggestionCount;
    protected final boolean mCountryMode;
    protected boolean mIsNumberingMode;
    protected LayoutInflater mInflater;

    protected Locale mDisplayLocale = null;
    // used to potentially cache a modified Context that uses mDisplayLocale
    protected Context mContextOverride = null;
    private boolean mHasSpecificAppPackageName;

    public SuggestedLocaleAdapter(Set<LocaleStore.LocaleInfo> localeOptions, boolean countryMode) {
        this(localeOptions, countryMode, false);
    }

    public SuggestedLocaleAdapter(Set<LocaleStore.LocaleInfo> localeOptions, boolean countryMode,
            boolean hasSpecificAppPackageName) {
        mCountryMode = countryMode;
        mLocaleOptions = new ArrayList<>(localeOptions.size());
        mHasSpecificAppPackageName = hasSpecificAppPackageName;

        for (LocaleStore.LocaleInfo li : localeOptions) {
            if (li.isSuggested()) {
                mSuggestionCount++;
            }
            mLocaleOptions.add(li);
        }
    }

    public void setNumberingSystemMode(boolean isNumberSystemMode) {
        mIsNumberingMode = isNumberSystemMode;
    }

    public boolean getIsForNumberingSystem() {
        return mIsNumberingMode;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        return getItemViewType(position) == TYPE_LOCALE
                || getItemViewType(position) == TYPE_SYSTEM_LANGUAGE_FOR_APP_LANGUAGE_PICKER
                || getItemViewType(position) == TYPE_CURRENT_LOCALE;
    }

    @Override
    public int getItemViewType(int position) {
        if (!showHeaders()) {
            LocaleStore.LocaleInfo item = (LocaleStore.LocaleInfo) getItem(position);
            if (item.isSystemLocale()) {
                return TYPE_SYSTEM_LANGUAGE_FOR_APP_LANGUAGE_PICKER;
            }
            if (item.isAppCurrentLocale()) {
                return TYPE_CURRENT_LOCALE;
            }
            return TYPE_LOCALE;
        } else {
            if (position == 0) {
                return TYPE_HEADER_SUGGESTED;
            }
            if (position == mSuggestionCount + 1) {
                return TYPE_HEADER_ALL_OTHERS;
            }

            LocaleStore.LocaleInfo item = (LocaleStore.LocaleInfo) getItem(position);
            if (item == null) {
                throw new NullPointerException("Non header locale cannot be null");
            }
            if (item.isSystemLocale()) {
                return TYPE_SYSTEM_LANGUAGE_FOR_APP_LANGUAGE_PICKER;
            }
            if (item.isAppCurrentLocale()) {
                return TYPE_CURRENT_LOCALE;
            }
            return TYPE_LOCALE;
        }
    }

    @Override
    public int getViewTypeCount() {
        if (mHasSpecificAppPackageName && showHeaders()) {
            // Two headers, 1 "System language", 1 current locale
            return APP_LANGUAGE_PICKER_TYPE_COUNT;
        } else if (showHeaders()) {
            // Two headers in addition to the locales
            return SYSTEM_LANGUAGE_TYPE_COUNT;
        } else {
            return SYSTEM_LANGUAGE_WITHOUT_HEADER_TYPE_COUNT; // Locales items only
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
        if (isHeaderPosition(position)) {
            return null;
        }

        int offset = 0;
        if (showHeaders()) {
            offset = position > mSuggestionCount ? -2 : -1;
        }

        return mLocaleOptions.get(position + offset);
    }

    private boolean isHeaderPosition(int position) {
        return showHeaders() && (position == 0 || position == mSuggestionCount + 1);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    /**
     * Overrides the locale used to display localized labels. Setting the locale to null will reset
     * the Adapter to use the default locale for the labels.
     */
    public void setDisplayLocale(@NonNull Context context, @Nullable Locale locale) {
        if (locale == null) {
            mDisplayLocale = null;
            mContextOverride = null;
        } else if (!locale.equals(mDisplayLocale)) {
            mDisplayLocale = locale;
            final Configuration configOverride = new Configuration();
            configOverride.setLocale(locale);
            mContextOverride = context.createConfigurationContext(configOverride);
        }
    }

    protected void setTextTo(@NonNull TextView textView, int resId) {
        if (mContextOverride == null) {
            textView.setText(resId);
        } else {
            textView.setText(mContextOverride.getText(resId));
            // If mContextOverride is not null, mDisplayLocale can't be null either.
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null && mInflater == null) {
            mInflater = LayoutInflater.from(parent.getContext());
        }
        int itemType = getItemViewType(position);
        View itemView = getNewViewIfNeeded(convertView, parent, itemType, position);
        switch (itemType) {
            case TYPE_HEADER_SUGGESTED: // intentional fallthrough
            case TYPE_HEADER_ALL_OTHERS:
                TextView textView = (TextView) itemView;
                if (itemType == TYPE_HEADER_SUGGESTED) {
                    if (mCountryMode && !mIsNumberingMode) {
                        setTextTo(textView, R.string.language_picker_regions_section_suggested);
                    } else {
                        setTextTo(textView, R.string.language_picker_section_suggested);
                    }
                } else {
                    if (mCountryMode && !mIsNumberingMode) {
                        setTextTo(textView, R.string.region_picker_section_all);
                    } else {
                        setTextTo(textView, R.string.language_picker_section_all);
                    }
                }
                textView.setTextLocale(
                        mDisplayLocale != null ? mDisplayLocale : Locale.getDefault());
                break;
            case TYPE_SYSTEM_LANGUAGE_FOR_APP_LANGUAGE_PICKER:
                TextView title;
                LocaleStore.LocaleInfo info = (LocaleStore.LocaleInfo) getItem(position);
                if (info == null) {
                    throw new NullPointerException("Non header locale cannot be null.");
                }
                if (info.isAppCurrentLocale()) {
                    title = itemView.findViewById(R.id.language_picker_item);
                } else {
                    title = itemView.findViewById(R.id.locale);
                }
                title.setText(R.string.system_locale_title);
                break;
            case TYPE_CURRENT_LOCALE:
                updateTextView(itemView,
                        itemView.findViewById(R.id.language_picker_item), position);
                break;
            default:
                updateTextView(itemView, itemView.findViewById(R.id.locale), position);
                break;
        }
        return itemView;
    }

    /** Check if the old view can be reused, otherwise create a new one. */
    private View getNewViewIfNeeded(
            View convertView, ViewGroup parent, int itemType, int position) {
        View updatedView = convertView;
        boolean shouldReuseView;
        switch (itemType) {
            case TYPE_HEADER_SUGGESTED: // intentional fallthrough
            case TYPE_HEADER_ALL_OTHERS:
                shouldReuseView = convertView instanceof TextView
                        && convertView.findViewById(R.id.language_picker_header) != null;
                if (!shouldReuseView) {
                    updatedView = mInflater.inflate(
                            R.layout.language_picker_section_header, parent, false);
                }
                break;
            case TYPE_SYSTEM_LANGUAGE_FOR_APP_LANGUAGE_PICKER:
                if (((LocaleStore.LocaleInfo) getItem(position)).isAppCurrentLocale()) {
                    shouldReuseView = convertView instanceof LinearLayout
                            && convertView.findViewById(R.id.language_picker_item) != null;
                    if (!shouldReuseView) {
                        updatedView = mInflater.inflate(
                                R.layout.app_language_picker_current_locale_item,
                                parent, false);
                    }
                } else {
                    shouldReuseView = convertView instanceof TextView
                            && convertView.findViewById(R.id.locale) != null;
                    if (!shouldReuseView) {
                        updatedView = mInflater.inflate(
                                R.layout.language_picker_item, parent, false);
                    }
                }
                break;
            case TYPE_CURRENT_LOCALE:
                shouldReuseView = convertView instanceof LinearLayout
                        && convertView.findViewById(R.id.language_picker_item) != null;
                if (!shouldReuseView) {
                    updatedView = mInflater.inflate(
                            R.layout.app_language_picker_current_locale_item, parent, false);
                }
                break;
            default:
                shouldReuseView = convertView instanceof TextView
                        && convertView.findViewById(R.id.locale) != null;
                if (!shouldReuseView) {
                    updatedView = mInflater.inflate(R.layout.language_picker_item, parent, false);
                }
                break;
        }
        return updatedView;
    }

    protected boolean showHeaders() {
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

    private void updateTextView(View convertView, TextView text, int position) {
        LocaleStore.LocaleInfo item = (LocaleStore.LocaleInfo) getItem(position);
        text.setText(mIsNumberingMode
                ? item.getNumberingSystem() : item.getLabel(mCountryMode));
        text.setTextLocale(item.getLocale());
        text.setContentDescription(mIsNumberingMode
                        ? item.getNumberingSystem() : item.getContentDescription(mCountryMode));
        if (mCountryMode) {
            int layoutDir = TextUtils.getLayoutDirectionFromLocale(item.getParent());
            //noinspection ResourceType
            convertView.setLayoutDirection(layoutDir);
            text.setTextDirection(layoutDir == View.LAYOUT_DIRECTION_RTL
                    ? View.TEXT_DIRECTION_RTL
                    : View.TEXT_DIRECTION_LTR);
        }
    }
}
