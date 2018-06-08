package com.android.systemui.statusbar.car;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.Display;
import android.view.View;

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
        // Using the following as a default button for display id info it's not
        // attached to a screen at this point so it can't be extracted here.
        mSelectedFacetButton = facetButton;
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
     * they are a match with the supplied StackInfo list.
     * The order of selection detection is ComponentName, PackageName then Category
     * They will then be compared with the supplied StackInfo list.
     * The StackInfo is expected to be supplied in order of recency and StackInfo will only be used
     * for consideration if it has the same displayId as the CarFacetButtons.
     * @param taskInfo of the currently running application
     */
    public void taskChanged(List<ActivityManager.StackInfo> stackInfoList) {
        int displayId = getDisplayId();
        ActivityManager.StackInfo validStackInfo = null;
        for (ActivityManager.StackInfo stackInfo :stackInfoList) {
            // If the display id is unknown or it matches the stack, it's valid for use
            if ((displayId == -1 || displayId == stackInfo.displayId) &&
                    stackInfo.topActivity != null) {
                validStackInfo = stackInfo;
                break;
            }
        }

        if (validStackInfo == null) {
            // No stack was found that was on the same display as the facet buttons thus return
            return;
        }

        if (mSelectedFacetButton != null) {
            mSelectedFacetButton.setSelected(false);
        }

        String packageName = validStackInfo.topActivity.getPackageName();
        CarFacetButton facetButton = findFacetButtongByComponentName(validStackInfo.topActivity);
        if (facetButton == null) {
            facetButton = mButtonsByPackage.get(packageName);
        }

        if (facetButton == null) {
            String category = getPackageCategory(packageName);
            if (category != null) {
                facetButton = mButtonsByCategory.get(category);
            }
        }

        if (facetButton != null && facetButton.getVisibility() == View.VISIBLE) {
            facetButton.setSelected(true);
            mSelectedFacetButton = facetButton;
        }

    }

    private int getDisplayId() {
        if (mSelectedFacetButton != null) {
            Display display = mSelectedFacetButton.getDisplay();
            if (display != null) {
                return display.getDisplayId();
            }
        }
        return -1;
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
