/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * CarFacetButtons placed on the nav bar are designed to have visual indication that the active
 * application on screen is associated with it. This is basically a similar concept to a radio
 * button group.
 */
@Singleton
public class CarFacetButtonController {

    protected ButtonMap mButtonsByCategory = new ButtonMap();
    protected ButtonMap mButtonsByPackage = new ButtonMap();
    protected ButtonMap mButtonsByComponentName = new ButtonMap();
    protected HashSet<CarFacetButton> mSelectedFacetButtons;
    protected Context mContext;

    @Inject
    public CarFacetButtonController(Context context) {
        mContext = context;
        mSelectedFacetButtons = new HashSet<>();
    }

    /**
     * Add facet button to this controller. The expected use is for the facet button
     * to get a reference to this controller via {@link com.android.systemui.Dependency}
     * and self add.
     */
    public void addFacetButton(CarFacetButton facetButton) {
        String[] categories = facetButton.getCategories();
        for (int i = 0; i < categories.length; i++) {
            mButtonsByCategory.add(categories[i], facetButton);
        }

        String[] facetPackages = facetButton.getFacetPackages();
        for (int i = 0; i < facetPackages.length; i++) {
            mButtonsByPackage.add(facetPackages[i], facetButton);
        }
        String[] componentNames = facetButton.getComponentName();
        for (int i = 0; i < componentNames.length; i++) {
            mButtonsByComponentName.add(componentNames[i], facetButton);
        }
    }

    public void removeAll() {
        mButtonsByCategory.clear();
        mButtonsByPackage.clear();
        mButtonsByComponentName.clear();
        mSelectedFacetButtons.clear();
    }

    /**
     * Iterate through a view looking for CarFacetButtons and adding them to the controller if found
     *
     * @param v the View that may contain CarFacetButtons
     */
    public void addAllFacetButtons(View v) {
        if (v instanceof CarFacetButton) {
            addFacetButton((CarFacetButton) v);
        } else if (v instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) v;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                addAllFacetButtons(viewGroup.getChildAt(i));
            }
        }
    }

    /**
     * This will unselect the currently selected CarFacetButton and determine which one should be
     * selected next. It does this by reading the properties on the CarFacetButton and seeing if
     * they are a match with the supplied StackInfo list.
     * The order of selection detection is ComponentName, PackageName then Category
     * They will then be compared with the supplied StackInfo list.
     * The StackInfo is expected to be supplied in order of recency and StackInfo will only be used
     * for consideration if it has the same displayId as the CarFacetButtons.
     *
     * @param stackInfoList of the currently running application
     */
    public void taskChanged(List<ActivityManager.StackInfo> stackInfoList) {
        ActivityManager.StackInfo validStackInfo = null;
        for (ActivityManager.StackInfo stackInfo : stackInfoList) {
            // Find the first stack info with a topActivity in the primary display.
            // TODO: We assume that CarFacetButton will launch an app only in the primary display.
            // We need to extend the functionality to handle the mutliple display properly.
            if (stackInfo.topActivity != null && stackInfo.displayId == Display.DEFAULT_DISPLAY) {
                validStackInfo = stackInfo;
                break;
            }
        }

        if (validStackInfo == null) {
            // No stack was found that was on the same display as the facet buttons thus return
            return;
        }

        if (mSelectedFacetButtons != null) {
            Iterator<CarFacetButton> iterator = mSelectedFacetButtons.iterator();
            while(iterator.hasNext()) {
                CarFacetButton carFacetButton = iterator.next();
                if (carFacetButton.getDisplayId() == validStackInfo.displayId) {
                    carFacetButton.setSelected(false);
                    iterator.remove();
                }
            }
        }

        String packageName = validStackInfo.topActivity.getPackageName();
        HashSet<CarFacetButton> facetButton =
                findFacetButtonByComponentName(validStackInfo.topActivity);
        if (facetButton == null) {
            facetButton = mButtonsByPackage.get(packageName);
        }

        if (facetButton == null) {
            String category = getPackageCategory(packageName);
            if (category != null) {
                facetButton = mButtonsByCategory.get(category);
            }
        }

        if (facetButton != null) {
            for (CarFacetButton carFacetButton : facetButton) {
                if (carFacetButton.getDisplayId() == validStackInfo.displayId) {
                    carFacetButton.setSelected(true);
                    mSelectedFacetButtons.add(carFacetButton);
                }
            }
        }

    }

    private HashSet<CarFacetButton> findFacetButtonByComponentName(ComponentName componentName) {
        HashSet<CarFacetButton> buttons =
                mButtonsByComponentName.get(componentName.flattenToShortString());
        return (buttons != null) ? buttons :
                mButtonsByComponentName.get(componentName.flattenToString());
    }

    protected String getPackageCategory(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        Set<String> supportedCategories = mButtonsByCategory.keySet();
        for (String category : supportedCategories) {
            Intent intent = new Intent();
            intent.setPackage(packageName);
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(category);
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            if (list.size() > 0) {
                // Cache this package name into facetPackageMap, so we won't have to query
                // all categories next time this package name shows up.
                mButtonsByPackage.put(packageName, mButtonsByCategory.get(category));
                return category;
            }
        }
        return null;
    }

    // simple multi-map
    private static class ButtonMap extends HashMap<String, HashSet<CarFacetButton>> {

        public boolean add(String key, CarFacetButton value) {
            if (containsKey(key)) {
                return get(key).add(value);
            }
            HashSet<CarFacetButton> set = new HashSet<>();
            set.add(value);
            put(key, set);
            return true;
        }
    }
}
