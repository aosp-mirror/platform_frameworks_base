package com.android.systemui.statusbar.car;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

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
    protected HashMap<String, CarFacetButton> mButtonsByComponentName = new HashMap<>();
    protected CarFacetButton mSelectedFacetButton;
    protected Context mContext;

    public CarFacetButtonController(Context context) {
        mContext = context;
    }

    /**
     * Add facet button to this controller. The expected use is for the facet button
     * to get a reference to this controller via {@link com.android.systemui.Dependency}
     * and self add.
     * @param facetButton
     */
    public void addFacetButton(CarFacetButton facetButton) {
        String[] categories = facetButton.getCategories();
        for (int i = 0; i < categories.length; i++) {
            mButtonsByCategory.put(categories[i], facetButton);
        }

        String[] facetPackages = facetButton.getFacetPackages();
        for (int i = 0; i < facetPackages.length; i++) {
            mButtonsByPackage.put(facetPackages[i], facetButton);
        }
        String[] componentNames = facetButton.getComponentName();
        for (int i = 0; i < componentNames.length; i++) {
            mButtonsByComponentName.put(componentNames[i], facetButton);
        }
    }

    public void removeAll() {
        mButtonsByCategory.clear();
        mButtonsByPackage.clear();
        mButtonsByComponentName.clear();
        mSelectedFacetButton = null;
    }

    /**
     * This will unselect the currently selected CarFacetButton and determine which one should be
     * selected next. It does this by reading the properties on the CarFacetButton and seeing if
     * they are a match with the supplied taskInfo.
     * Order of selection detection ComponentName, PackageName, Category
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
        CarFacetButton facetButton = findFacetButtongByComponentName(taskInfo.topActivity);
        if (facetButton == null) {
            facetButton =  mButtonsByPackage.get(packageName);
        }
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

    private CarFacetButton findFacetButtongByComponentName(ComponentName componentName) {
        CarFacetButton button = mButtonsByComponentName.get(componentName.flattenToShortString());
        return (button != null) ? button :
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
}
