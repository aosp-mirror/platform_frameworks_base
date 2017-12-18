package com.android.systemui.statusbar.car;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.view.ViewGroup;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * CarFacetButtons placed on the nav bar are designed to have visual indication that the active
 * application on screen is associated with it. This is basically a similar concept to a radio
 * button group.
 */
public class CarFacetButtonController {

    protected HashMap<String, CarFacetButton> mButtonsByCategory = new HashMap<>();
    protected HashMap<String, CarFacetButton> mButtonsByPackage = new HashMap<>();
    protected CarFacetButton mSelectedFacetButton;
    protected Context mContext;

    public CarFacetButtonController(Context context) {
        mContext = context;
    }

    /**
     * Goes through the supplied CarNavigationBarView and keeps track of all the CarFacetButtons
     * such that it can select and unselect them based on running task chages
     * @param bar that may contain CarFacetButtons
     */
    public void addCarNavigationBar(CarNavigationBarView bar) {
        findFacets(bar);
    }

    private void findFacets(ViewGroup root) {
        final int childCount = root.getChildCount();

        for (int i = 0; i < childCount; ++i) {
            final View v = root.getChildAt(i);
            if (v instanceof CarFacetButton) {
                CarFacetButton facetButton = (CarFacetButton) v;
                String[] categories = facetButton.getCategories();
                for (int j = 0; j < categories.length; j++) {
                    String category = categories[j];
                    mButtonsByCategory.put(category, facetButton);
                }

                String[] facetPackages = facetButton.getFacetPackages();
                for (int j = 0; j < facetPackages.length; j++) {
                    String facetPackage = facetPackages[j];
                    mButtonsByPackage.put(facetPackage, facetButton);
                }
            } else if (v instanceof ViewGroup) {
                findFacets((ViewGroup) v);
            }
        }
    }


    /**
     * This will unselect the currently selected CarFacetButton and determine which one should be
     * selected next. It does this by reading the properties on the CarFacetButton and seeing if
     * they are a match with the supplied taskino.
     * @param taskInfo of the currently running application
     */
    public void taskChanged(ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo == null || taskInfo.baseActivity == null) {
            return;
        }
        String packageName = taskInfo.baseActivity.getPackageName();

        // If the package name belongs to a filter, then highlight appropriate button in
        // the navigation bar.
        if (mSelectedFacetButton != null) {
            mSelectedFacetButton.setSelected(false);
        }
        CarFacetButton facetButton = mButtonsByPackage.get(packageName);
        if (facetButton != null) {
            facetButton.setSelected(true);
            mSelectedFacetButton = facetButton;
        } else {
            String category = getPackageCategory(packageName);
            if (category != null) {
                facetButton = mButtonsByCategory.get(category);
                facetButton.setSelected(true);
                mSelectedFacetButton = facetButton;
            }
        }
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
}
