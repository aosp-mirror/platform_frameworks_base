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

import android.app.ActivityManager.StackId;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.support.v4.util.SimpleArrayMap;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.View;
import android.widget.LinearLayout;
import com.android.systemui.R;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * A controller to populate data for CarNavigationBarView and handle user interactions.
 *
 * <p>Each button inside the navigation bar is defined by data in arrays_car.xml. OEMs can
 * customize the navigation buttons by updating arrays_car.xml appropriately in an overlay.
 */
class CarNavigationBarController {
    private static final String TAG = "CarNavBarController";

    private static final String EXTRA_FACET_CATEGORIES = "categories";
    private static final String EXTRA_FACET_PACKAGES = "packages";
    private static final String EXTRA_FACET_ID = "filter_id";
    private static final String EXTRA_FACET_LAUNCH_PICKER = "launch_picker";

    /**
     * Each facet of the navigation bar maps to a set of package names or categories defined in
     * arrays_car.xml. Package names for a given facet are delimited by ";".
     */
    private static final String FACET_FILTER_DELIMITER = ";";

    private final Context mContext;
    private final CarNavigationBarView mNavBar;
    private final CarStatusBar mStatusBar;

    /**
     * Set of categories each facet will filter on.
     */
    private final List<String[]> mFacetCategories = new ArrayList<>();

    /**
     * Set of package names each facet will filter on.
     */
    private final List<String[]> mFacetPackages = new ArrayList<>();

    private final SimpleArrayMap<String, Integer> mFacetCategoryMap = new SimpleArrayMap<>();
    private final SimpleArrayMap<String, Integer> mFacetPackageMap = new SimpleArrayMap<>();

    private final List<CarNavigationButton> mNavButtons = new ArrayList<>();

    private final SparseBooleanArray mFacetHasMultipleAppsCache = new SparseBooleanArray();

    private int mCurrentFacetIndex;
    private Intent mPersistentTaskIntent;

    public CarNavigationBarController(Context context, CarNavigationBarView navBar,
            CarStatusBar activityStarter) {
        mContext = context;
        mNavBar = navBar;
        mStatusBar = activityStarter;
        bind();

        if (context.getResources().getBoolean(R.bool.config_enablePersistentDockedActivity)) {
            setupPersistentDockedTask();
        }
    }

    private void setupPersistentDockedTask() {
        try {
            mPersistentTaskIntent = Intent.parseUri(
                    mContext.getString(R.string.config_persistentDockedActivityIntentUri),
                    Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Malformed persistent task intent.");
        }
    }

    public void taskChanged(String packageName, int stackId) {
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

        // Set up the persistent docked task if needed.
        if (mPersistentTaskIntent != null && !mStatusBar.hasDockedTask()
                && stackId != StackId.HOME_STACK_ID) {
            mStatusBar.startActivityOnStack(mPersistentTaskIntent, StackId.DOCKED_STACK_ID);
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

    /**
     * Iterates through the items in arrays_car.xml and sets up the facet bar buttons to
     * perform the task in that configuration file when clicked or long-pressed.
     */
    private void bind() {
        Resources res = mContext.getResources();

        TypedArray icons = res.obtainTypedArray(R.array.car_facet_icons);
        TypedArray intents = res.obtainTypedArray(R.array.car_facet_intent_uris);
        TypedArray longPressIntents = res.obtainTypedArray(R.array.car_facet_longpress_intent_uris);
        TypedArray facetPackageNames = res.obtainTypedArray(R.array.car_facet_package_filters);
        TypedArray facetCategories = res.obtainTypedArray(R.array.car_facet_category_filters);

        try {
            if (icons.length() != intents.length()
                    || icons.length() != longPressIntents.length()
                    || icons.length() != facetPackageNames.length()
                    || icons.length() != facetCategories.length()) {
                throw new RuntimeException("car_facet array lengths do not match");
            }

            for (int i = 0, size = icons.length(); i < size; i++) {
                Drawable icon = icons.getDrawable(i);
                CarNavigationButton button = createNavButton(icon);
                initClickListeners(button, i, intents.getString(i), longPressIntents.getString(i));

                mNavButtons.add(button);
                mNavBar.addButton(button, createNavButton(icon) /* lightsOutButton */);

                initFacetFilterMaps(i, facetPackageNames.getString(i).split(FACET_FILTER_DELIMITER),
                        facetCategories.getString(i).split(FACET_FILTER_DELIMITER));
                mFacetHasMultipleAppsCache.put(i, facetHasMultiplePackages(i));
            }
        } finally {
            // Clean up all the TypedArrays.
            icons.recycle();
            intents.recycle();
            longPressIntents.recycle();
            facetPackageNames.recycle();
            facetCategories.recycle();
        }
    }

    /**
     * Recreates each of the buttons on a density or font scale change. This manual process is
     * necessary since this class is not part of an activity that automatically gets recreated.
     */
    public void onDensityOrFontScaleChanged() {
        TypedArray icons = mContext.getResources().obtainTypedArray(R.array.car_facet_icons);

        try {
            int length = icons.length();
            if (length != mNavButtons.size()) {
                // This should not happen since the mNavButtons list is created from the length
                // of the icons array in bind().
                throw new RuntimeException("car_facet array lengths do not match number of "
                        + "created buttons.");
            }

            for (int i = 0; i < length; i++) {
                Drawable icon = icons.getDrawable(i);

                // Setting a new icon will trigger a requestLayout() call if necessary.
                mNavButtons.get(i).setResources(icon);
            }
        } finally {
            icons.recycle();
        }
    }

    private void initFacetFilterMaps(int id, String[] packageNames, String[] categories) {
        mFacetCategories.add(categories);
        for (String category : categories) {
            mFacetCategoryMap.put(category, id);
        }

        mFacetPackages.add(packageNames);
        for (String packageName : packageNames) {
            mFacetPackageMap.put(packageName, id);
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
     * Helper method to check if a given facet has multiple packages associated with it. This can
     * be resource defined package names or package names filtered by facet category.
     *
     * @return {@code true} if the facet at the given index has more than one package.
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

    /**
     * Sets the facet at the given index to be the facet that is currently active. The button will
     * be highlighted appropriately.
     */
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

    /**
     * Creates the View that is used for the buttons along the navigation bar.
     *
     * @param icon The icon to be used for the button.
     */
    private CarNavigationButton createNavButton(Drawable icon) {
        CarNavigationButton button = (CarNavigationButton) View.inflate(mContext,
                R.layout.car_navigation_button, null);
        button.setResources(icon);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        button.setLayoutParams(lp);

        return button;
    }

    /**
     * Initializes the click and long click listeners that correspond to the given command string.
     * The click listeners are attached to the given button.
     */
    private void initClickListeners(View button, int index, String clickString,
            String longPressString) {
        // Each button at least have an action when pressed.
        if (TextUtils.isEmpty(clickString)) {
            throw new RuntimeException("Facet at index " + index + " does not have click action.");
        }

        try {
            Intent intent = Intent.parseUri(clickString, Intent.URI_INTENT_SCHEME);
            button.setOnClickListener(v -> onFacetClicked(intent, index));
        } catch (URISyntaxException e) {
            throw new RuntimeException("Malformed intent uri", e);
        }

        if (TextUtils.isEmpty(longPressString)) {
            button.setLongClickable(false);
            return;
        }

        try {
            Intent intent = Intent.parseUri(longPressString, Intent.URI_INTENT_SCHEME);
            button.setOnLongClickListener(v -> {
                onFacetLongClicked(intent, index);
                return true;
            });
        } catch (URISyntaxException e) {
            throw new RuntimeException("Malformed long-press intent uri", e);
        }
    }

    /**
     * Handles a click on a facet. A click will trigger the given Intent.
     *
     * @param index The index of the facet that was clicked.
     */
    private void onFacetClicked(Intent intent, int index) {
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

        int stackId = StackId.FULLSCREEN_WORKSPACE_STACK_ID;
        if (intent.getCategories().contains(Intent.CATEGORY_HOME)) {
            stackId = StackId.HOME_STACK_ID;
        }

        setCurrentFacet(index);
        mStatusBar.startActivityOnStack(intent, stackId);
    }

    /**
     * Handles a long-press on a facet. The long-press will trigger the given Intent.
     *
     * @param index The index of the facet that was clicked.
     */
    private void onFacetLongClicked(Intent intent, int index) {
        setCurrentFacet(index);
        mStatusBar.startActivityOnStack(intent, StackId.FULLSCREEN_WORKSPACE_STACK_ID);
    }
}
