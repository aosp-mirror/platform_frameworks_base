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
package com.android.systemui.statusbar.car;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.support.v4.util.SimpleArrayMap;
import android.util.SparseBooleanArray;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.ActivityStarter;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A controller to populate data for CarNavigationBarView and handle user interactions.
 * <p/>
 * Each button inside the navigation bar is defined by data in arrays_car.xml. OEMs can customize
 * the navigation buttons by updating arrays_car.xml appropriately in an overlay.
 */
class CarNavigationBarController {
    private static final String EXTRA_FACET_CATEGORIES = "categories";
    private static final String EXTRA_FACET_PACKAGES = "packages";
    private static final String EXTRA_FACET_ID = "filter_id";
    private static final String EXTRA_FACET_LAUNCH_PICKER = "launch_picker";

    // Each facet of the navigation bar maps to a set of package names or categories defined in
    // arrays_car.xml. Package names for a given facet are delimited by ";"
    private static final String FACET_FILTER_DEMILITER = ";";

    private Context mContext;
    private CarNavigationBarView mNavBar;
    private ActivityStarter mActivityStarter;

    // Set of categories each facet will filter on.
    private List<String[]> mFacetCategories = new ArrayList<String[]>();
    // Set of package names each facet will filter on.
    private List<String[]> mFacetPackages = new ArrayList<String[]>();

    private SimpleArrayMap<String, Integer> mFacetCategoryMap
            = new SimpleArrayMap<String, Integer>();
    private SimpleArrayMap<String, Integer> mFacetPackageMap
            = new SimpleArrayMap<String, Integer>();

    private List<Intent> mIntents;
    private List<Intent> mLongPressIntents;

    private List<CarNavigationButton> mNavButtons = new ArrayList<CarNavigationButton>();

    private int mCurrentFacetIndex;
    private SparseBooleanArray mFacetHasMultipleAppsCache = new SparseBooleanArray();

    public CarNavigationBarController(Context context,
                                      CarNavigationBarView navBar,
                                      ActivityStarter activityStarter) {
        mContext = context;
        mNavBar = navBar;
        mActivityStarter = activityStarter;
        bind();
    }

    public void taskChanged(String packageName) {
        // If the package name belongs to a filter, then highlight appropriate button in
        // the navigation bar.
        if (mFacetPackageMap.containsKey(packageName)) {
            setCurrentFacet(mFacetPackageMap.get(packageName));
        }

        // Check if the package matches any of the categories for the facets
        String category = getPackageCategory(packageName);
        if (category != null) {
            setCurrentFacet(mFacetCategoryMap.get(category));
        }
    }

    public void onPackageChange(String packageName) {
        if (mFacetPackageMap.containsKey(packageName)) {
            int index = mFacetPackageMap.get(packageName);
            mFacetHasMultipleAppsCache.put(index, facetHasMultiplePackages(index));
            // No need to check categories because we've already refreshed the cache.
            return;
        }

        String category = getPackageCategory(packageName);
        if (mFacetCategoryMap.containsKey(category)) {
            int index = mFacetCategoryMap.get(category);
            mFacetHasMultipleAppsCache.put(index, facetHasMultiplePackages(index));
        }
    }

    private void bind() {
        // Read up arrays_car.xml and populate the navigation bar here.
        Resources r = mContext.getResources();
        TypedArray icons = r.obtainTypedArray(R.array.car_facet_icons);
        TypedArray intents = r.obtainTypedArray(R.array.car_facet_intent_uris);
        TypedArray longpressIntents =
                r.obtainTypedArray(R.array.car_facet_longpress_intent_uris);
        TypedArray facetPackageNames = r.obtainTypedArray(R.array.car_facet_package_filters);

        TypedArray facetCategories = r.obtainTypedArray(R.array.car_facet_category_filters);

        if (icons.length() != intents.length()
                || icons.length() != longpressIntents.length()
                || icons.length() != facetPackageNames.length()
                || icons.length() != facetCategories.length()) {
            throw new RuntimeException("car_facet array lengths do not match");
        }

        mIntents = createEmptyIntentList(icons.length());
        mLongPressIntents = createEmptyIntentList(icons.length());

        for (int i = 0; i < icons.length(); i++) {
            Drawable icon = icons.getDrawable(i);
            try {
                mIntents.set(i,
                        Intent.parseUri(intents.getString(i), Intent.URI_INTENT_SCHEME));

                String longpressUri = longpressIntents.getString(i);
                boolean hasLongpress = !longpressUri.isEmpty();
                if (hasLongpress) {
                    mLongPressIntents.set(i,
                            Intent.parseUri(longpressUri, Intent.URI_INTENT_SCHEME));
                }

                CarNavigationButton button = createNavButton(icon, i, hasLongpress);
                mNavButtons.add(button);
                mNavBar.addButton(button,
                        createNavButton(icon, i, hasLongpress) /* lightsOutButton */);

                initFacetFilterMaps(i,
                        facetPackageNames.getString(i).split(FACET_FILTER_DEMILITER),
                        facetCategories.getString(i).split(FACET_FILTER_DEMILITER));
                        mFacetHasMultipleAppsCache.put(i, facetHasMultiplePackages(i));
            } catch (URISyntaxException e) {
                throw new RuntimeException("Malformed intent uri", e);
            }
        }
    }

    private void initFacetFilterMaps(int id, String[] packageNames, String[] categories) {
        mFacetCategories.add(categories);
        for (int i = 0; i < categories.length; i++) {
            mFacetCategoryMap.put(categories[i], id);
        }

        mFacetPackages.add(packageNames);
        for (int i = 0; i < packageNames.length; i++) {
            mFacetPackageMap.put(packageNames[i], id);
        }
    }

    private String getPackageCategory(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        int size = mFacetCategories.size();
        // For each facet, check if the given package name matches one of its categories
        for (int i = 0; i < size; i++) {
            String[] categories = mFacetCategories.get(i);
            for (int j = 0; j < categories.length; j++) {
                String category = categories[j];
                Intent intent = new Intent();
                intent.setPackage(packageName);
                intent.setAction(Intent.ACTION_MAIN);
                intent.addCategory(category);
                List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
                if (list.size() > 0) {
                    // Cache this package name into facetPackageMap, so we won't have to query
                    // all categories next time this package name shows up.
                    mFacetPackageMap.put(packageName, mFacetCategoryMap.get(category));
                    return category;
                }
            }
        }
        return null;
    }

    /**
     * Helper method to check if a given facet has multiple packages associated with it.
     * This can be resource defined package names or package names filtered by facet category.
     */
    private boolean facetHasMultiplePackages(int index) {
        PackageManager pm = mContext.getPackageManager();

        // Check if the packages defined for the filter actually exists on the device
        String[] packages = mFacetPackages.get(index);
        if (packages.length > 1) {
            int count = 0;
            for (int i = 0; i < packages.length; i++) {
                count += pm.getLaunchIntentForPackage(packages[i]) != null ? 1 : 0;
                if (count > 1) {
                    return true;
                }
            }
        }

        // If there weren't multiple packages defined for the facet, check the categories
        // and see if they resolve to multiple package names
        String categories[] = mFacetCategories.get(index);

        int count = 0;
        for (int i = 0; i < categories.length; i++) {
            String category = categories[i];
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(category);
            count += pm.queryIntentActivities(intent, 0).size();
            if (count > 1) {
                return true;
            }
        }
        return false;
    }

    private void setCurrentFacet(int index) {
        if (index == mCurrentFacetIndex) {
            return;
        }

        if (mNavButtons.get(mCurrentFacetIndex) != null) {
            mNavButtons.get(mCurrentFacetIndex)
                    .setSelected(false /* selected */, false /* showMoreIcon */);
        }

        if (mNavButtons.get(index) != null) {
            mNavButtons.get(index).setSelected(true /* selected */,
                    mFacetHasMultipleAppsCache.get(index)  /* showMoreIcon */);
        }
        mCurrentFacetIndex = index;
    }

    private CarNavigationButton createNavButton(Drawable icon, final int id,
                                                boolean longClickEnabled) {
        CarNavigationButton button = (CarNavigationButton) View.inflate(mContext,
                R.layout.car_navigation_button, null);
        button.setResources(icon);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        button.setLayoutParams(lp);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onFacetClicked(id);
            }
        });

        if (longClickEnabled) {
            button.setLongClickable(true);
            button.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    onFacetLongClicked(id);
                    return true;
                }
            });
        } else {
            button.setLongClickable(false);
        }

        return button;
    }

    private void startActivity(Intent intent) {
        if (mActivityStarter != null && intent != null) {
            mActivityStarter.startActivity(intent, false);
        }
    }

    private void onFacetClicked(int index) {
        Intent intent = mIntents.get(index);
        String packageName = intent.getPackage();

        if (packageName == null) {
            return;
        }

        intent.putExtra(EXTRA_FACET_CATEGORIES, mFacetCategories.get(index));
        intent.putExtra(EXTRA_FACET_PACKAGES, mFacetPackages.get(index));
        // The facet is identified by the index in which it was added to the nav bar.
        // This value can be used to determine which facet was selected
        intent.putExtra(EXTRA_FACET_ID, Integer.toString(index));

        // If the current facet is clicked, we want to launch the picker by default
        // rather than the "preferred/last run" app.
        intent.putExtra(EXTRA_FACET_LAUNCH_PICKER, index == mCurrentFacetIndex);

        setCurrentFacet(index);
        startActivity(intent);
    }

    private void onFacetLongClicked(int index) {
        setCurrentFacet(index);
        startActivity(mLongPressIntents.get(index));
    }

    private List<Intent> createEmptyIntentList(int size) {
        return Arrays.asList(new Intent[size]);
    }
}
