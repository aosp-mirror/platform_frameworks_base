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

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.keyguard.AlphaOptimizedImageButton;
import com.android.systemui.CarSystemUIFactory;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;

/**
 * CarFacetButton is a ui component designed to be used as a shortcut for an app of a defined
 * category. It can also render a indicator impling that there are more options of apps to launch
 * using this component. This is done with a "More icon" currently an arrow as defined in the layout
 * file. The class is to serve as an example.
 * Usage example: A button that allows a user to select a music app and indicate that there are
 * other music apps installed.
 */
public class CarFacetButton extends LinearLayout {
    private static final String FACET_FILTER_DELIMITER = ";";
    /**
     * Extra information to be sent to a helper to make the decision of what app to launch when
     * clicked.
     */
    private static final String EXTRA_FACET_CATEGORIES = "categories";
    private static final String EXTRA_FACET_PACKAGES = "packages";
    private static final String EXTRA_FACET_ID = "filter_id";
    private static final String EXTRA_FACET_LAUNCH_PICKER = "launch_picker";
    private static final String TAG = "CarFacetButton";

    private Context mContext;
    private AlphaOptimizedImageButton mIcon;
    private AlphaOptimizedImageButton mMoreIcon;
    private boolean mSelected = false;
    private String[] mComponentNames;
    /** App categories that are to be used with this widget */
    private String[] mFacetCategories;
    /** App packages that are allowed to be used with this widget */
    private String[] mFacetPackages;
    private int mIconResourceId;
    /**
     * If defined in the xml this will be the icon that's rendered when the button is marked as
     * selected
     */
    private int mSelectedIconResourceId;
    private boolean mUseMoreIcon = true;
    private float mSelectedAlpha = 1f;
    private float mUnselectedAlpha = 1f;

    public CarFacetButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        View.inflate(context, R.layout.car_facet_button, this);
        // extract custom attributes
        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.CarFacetButton);
        setupIntents(typedArray);
        setupIcons(typedArray);
        CarSystemUIFactory factory = SystemUIFactory.getInstance();
        CarFacetButtonController carFacetButtonController = factory.getCarDependencyComponent()
                .getCarFacetButtonController();
        carFacetButtonController.addFacetButton(this);
    }

    /**
     * Reads the custom attributes to setup click handlers for this component.
     */
    protected void setupIntents(TypedArray typedArray) {
        String intentString = typedArray.getString(R.styleable.CarFacetButton_intent);
        String longPressIntentString = typedArray.getString(R.styleable.CarFacetButton_longIntent);
        String categoryString = typedArray.getString(R.styleable.CarFacetButton_categories);
        String packageString = typedArray.getString(R.styleable.CarFacetButton_packages);
        String componentNameString =
                typedArray.getString(R.styleable.CarFacetButton_componentNames);
        try {
            final Intent intent = Intent.parseUri(intentString, Intent.URI_INTENT_SCHEME);
            intent.putExtra(EXTRA_FACET_ID, Integer.toString(getId()));

            if (packageString != null) {
                mFacetPackages = packageString.split(FACET_FILTER_DELIMITER);
                intent.putExtra(EXTRA_FACET_PACKAGES, mFacetPackages);
            }
            if (categoryString != null) {
                mFacetCategories = categoryString.split(FACET_FILTER_DELIMITER);
                intent.putExtra(EXTRA_FACET_CATEGORIES, mFacetCategories);
            }
            if (componentNameString != null) {
                mComponentNames = componentNameString.split(FACET_FILTER_DELIMITER);
            }

            setOnClickListener(v -> {
                intent.putExtra(EXTRA_FACET_LAUNCH_PICKER, mSelected);
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            });

            if (longPressIntentString != null) {
                final Intent longPressIntent = Intent.parseUri(longPressIntentString,
                        Intent.URI_INTENT_SCHEME);
                setOnLongClickListener(v -> {
                    mContext.startActivityAsUser(longPressIntent, UserHandle.CURRENT);
                    return true;
                });
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to attach intent", e);
        }
    }

    private void setupIcons(TypedArray styledAttributes) {
        mSelectedAlpha = styledAttributes.getFloat(
                R.styleable.CarFacetButton_selectedAlpha, mSelectedAlpha);
        mUnselectedAlpha = styledAttributes.getFloat(
                R.styleable.CarFacetButton_unselectedAlpha, mUnselectedAlpha);
        mIcon = findViewById(R.id.car_nav_button_icon);
        mIcon.setScaleType(ImageView.ScaleType.CENTER);
        mIcon.setClickable(false);
        mIcon.setAlpha(mUnselectedAlpha);
        mIconResourceId = styledAttributes.getResourceId(R.styleable.CarFacetButton_icon, 0);
        mIcon.setImageResource(mIconResourceId);
        mSelectedIconResourceId = styledAttributes.getResourceId(
                R.styleable.CarFacetButton_selectedIcon, mIconResourceId);

        mMoreIcon = findViewById(R.id.car_nav_button_more_icon);
        mMoreIcon.setClickable(false);
        mMoreIcon.setAlpha(mSelectedAlpha);
        mMoreIcon.setVisibility(GONE);
        mUseMoreIcon = styledAttributes.getBoolean(R.styleable.CarFacetButton_useMoreIcon, true);
    }

    /**
     * @return The app categories the component represents
     */
    public String[] getCategories() {
        if (mFacetCategories == null) {
            return new String[0];
        }
        return mFacetCategories;
    }

    /**
     * @return The valid packages that should be considered.
     */
    public String[] getFacetPackages() {
        if (mFacetPackages == null) {
            return new String[0];
        }
        return mFacetPackages;
    }

    /**
     * @return The list of component names.
     */
    public String[] getComponentName() {
        if (mComponentNames == null) {
            return new String[0];
        }
        return mComponentNames;
    }

    /**
     * Updates the alpha of the icons to "selected" and shows the "More icon"
     *
     * @param selected true if the view must be selected, false otherwise
     */
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        setSelected(selected, selected);
    }

    /**
     * Updates the visual state to let the user know if it's been selected.
     *
     * @param selected     true if should update the alpha of the icon to selected, false otherwise
     * @param showMoreIcon true if the "more icon" should be shown, false otherwise. Note this
     *                     is ignored if the attribute useMoreIcon is set to false
     */
    public void setSelected(boolean selected, boolean showMoreIcon) {
        mSelected = selected;
        mIcon.setAlpha(mSelected ? mSelectedAlpha : mUnselectedAlpha);
        mIcon.setImageResource(mSelected ? mSelectedIconResourceId : mIconResourceId);
        if (mUseMoreIcon) {
            mMoreIcon.setVisibility(showMoreIcon ? VISIBLE : GONE);
        }
    }
}
