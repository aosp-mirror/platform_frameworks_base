/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.navigationbar;

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
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * CarNavigationButtons can optionally have selection state that toggles certain visual indications
 * based on whether the active application on screen is associated with it. This is basically a
 * similar concept to a radio button group.
 *
 * This class controls the selection state of CarNavigationButtons that have opted in to have such
 * selection state-dependent visual indications.
 */
@Singleton
public class ButtonSelectionStateController {

    private final Set<CarNavigationButton> mRegisteredViews = new HashSet<>();

    protected ButtonMap mButtonsByCategory = new ButtonMap();
    protected ButtonMap mButtonsByPackage = new ButtonMap();
    protected ButtonMap mButtonsByComponentName = new ButtonMap();
    protected HashSet<CarNavigationButton> mSelectedButtons;
    protected Context mContext;

    @Inject
    public ButtonSelectionStateController(Context context) {
        mContext = context;
        mSelectedButtons = new HashSet<>();
    }

    /**
     * Iterate through a view looking for CarNavigationButton and add it to the controller if it
     * opted in to be highlighted when the active application is associated with it.
     *
     * @param v the View that may contain CarFacetButtons
     */
    protected void addAllButtonsWithSelectionState(View v) {
        if (v instanceof CarNavigationButton) {
            if (((CarNavigationButton) v).hasSelectionState()) {
                addButtonWithSelectionState((CarNavigationButton) v);
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) v;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                addAllButtonsWithSelectionState(viewGroup.getChildAt(i));
            }
        }
    }

    /** Removes all buttons from the button maps. */
    protected void removeAll() {
        mButtonsByCategory.clear();
        mButtonsByPackage.clear();
        mButtonsByComponentName.clear();
        mSelectedButtons.clear();
        mRegisteredViews.clear();
    }

    /**
     * This will unselect the currently selected CarNavigationButton and determine which one should
     * be selected next. It does this by reading the properties on the CarNavigationButton and
     * seeing if they are a match with the supplied StackInfo list.
     * The order of selection detection is ComponentName, PackageName then Category
     * They will then be compared with the supplied StackInfo list.
     * The StackInfo is expected to be supplied in order of recency and StackInfo will only be used
     * for consideration if it has the same displayId as the CarNavigationButton.
     *
     * @param stackInfoList of the currently running application
     * @param validDisplay index of the valid display
     */

    protected void taskChanged(List<ActivityManager.StackInfo> stackInfoList, int validDisplay) {
        ActivityManager.StackInfo validStackInfo = null;
        for (ActivityManager.StackInfo stackInfo : stackInfoList) {
            // Find the first stack info with a topActivity in the primary display.
            // TODO: We assume that CarFacetButton will launch an app only in the primary display.
            // We need to extend the functionality to handle the multiple display properly.
            if (stackInfo.topActivity != null && stackInfo.displayId == validDisplay) {
                validStackInfo = stackInfo;
                break;
            }
        }

        if (validStackInfo == null) {
            // No stack was found that was on the same display as the buttons thus return
            return;
        }
        int displayId = validStackInfo.displayId;

        mSelectedButtons.forEach(carNavigationButton -> {
            if (carNavigationButton.getDisplayId() == displayId) {
                carNavigationButton.setSelected(false);
            }
        });
        mSelectedButtons.clear();

        HashSet<CarNavigationButton> selectedButtons = findSelectedButtons(validStackInfo);

        if (selectedButtons != null) {
            selectedButtons.forEach(carNavigationButton -> {
                if (carNavigationButton.getDisplayId() == displayId) {
                    carNavigationButton.setSelected(true);
                    mSelectedButtons.add(carNavigationButton);
                }
            });
        }
    }

    /**
     * Defaults to Display.DEFAULT_DISPLAY when no parameter is provided for the validDisplay.
     *
     * @param stackInfoList
     */
    protected void taskChanged(List<ActivityManager.StackInfo> stackInfoList) {
        taskChanged(stackInfoList, Display.DEFAULT_DISPLAY);
    }

    /**
     * Add navigation button to this controller if it uses selection state.
     */
    private void addButtonWithSelectionState(CarNavigationButton carNavigationButton) {
        if (mRegisteredViews.contains(carNavigationButton)) {
            return;
        }
        String[] categories = carNavigationButton.getCategories();
        for (int i = 0; i < categories.length; i++) {
            mButtonsByCategory.add(categories[i], carNavigationButton);
        }

        String[] packages = carNavigationButton.getPackages();
        for (int i = 0; i < packages.length; i++) {
            mButtonsByPackage.add(packages[i], carNavigationButton);
        }
        String[] componentNames = carNavigationButton.getComponentName();
        for (int i = 0; i < componentNames.length; i++) {
            mButtonsByComponentName.add(componentNames[i], carNavigationButton);
        }

        mRegisteredViews.add(carNavigationButton);
    }

    private HashSet<CarNavigationButton> findSelectedButtons(
            ActivityManager.StackInfo validStackInfo) {
        String packageName = validStackInfo.topActivity.getPackageName();

        HashSet<CarNavigationButton> selectedButtons =
                findButtonsByComponentName(validStackInfo.topActivity);
        if (selectedButtons == null) {
            selectedButtons = mButtonsByPackage.get(packageName);
        }
        if (selectedButtons == null) {
            String category = getPackageCategory(packageName);
            if (category != null) {
                selectedButtons = mButtonsByCategory.get(category);
            }
        }

        return selectedButtons;
    }

    private HashSet<CarNavigationButton> findButtonsByComponentName(
            ComponentName componentName) {
        HashSet<CarNavigationButton> buttons =
                mButtonsByComponentName.get(componentName.flattenToShortString());
        return (buttons != null) ? buttons :
                mButtonsByComponentName.get(componentName.flattenToString());
    }

    private String getPackageCategory(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        Set<String> supportedCategories = mButtonsByCategory.keySet();
        for (String category : supportedCategories) {
            Intent intent = new Intent();
            intent.setPackage(packageName);
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(category);
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            if (list.size() > 0) {
                // Cache this package name into ButtonsByPackage map, so we won't have to query
                // all categories next time this package name shows up.
                mButtonsByPackage.put(packageName, mButtonsByCategory.get(category));
                return category;
            }
        }
        return null;
    }

    // simple multi-map
    private static class ButtonMap extends HashMap<String, HashSet<CarNavigationButton>> {

        public boolean add(String key, CarNavigationButton value) {
            if (containsKey(key)) {
                return get(key).add(value);
            }
            HashSet<CarNavigationButton> set = new HashSet<>();
            set.add(value);
            put(key, set);
            return true;
        }
    }
}
