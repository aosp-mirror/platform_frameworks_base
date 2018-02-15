package com.android.systemui.statusbar.car;

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.keyguard.AlphaOptimizedImageButton;
import com.android.systemui.R;

/**
 * CarFacetButton is a ui component designed to be used as a shortcut for an app of a defined
 * category. It can also render a indicator impling that there are more options of apps to launch
 * using this component. This is done with a "More icon" currently an arrow as defined in the layout
 * file. The class is to serve as an example.
 * Usage example: A button that allows a user to select a music app and indicate that there are
 * other music apps installed.
 */
public class CarFacetButton extends LinearLayout {
    private static final float SELECTED_ALPHA = 1f;
    private static final float UNSELECTED_ALPHA = 0.7f;

    private static final String FACET_FILTER_DELIMITER = ";";
    /**
     * Extra information to be sent to a helper to make the decision of what app to launch when
     * clicked.
     */
    private static final String EXTRA_FACET_CATEGORIES = "categories";
    private static final String EXTRA_FACET_PACKAGES = "packages";
    private static final String EXTRA_FACET_ID = "filter_id";
    private static final String EXTRA_FACET_LAUNCH_PICKER = "launch_picker";

    private Context mContext;
    private AlphaOptimizedImageButton mIcon;
    private AlphaOptimizedImageButton mMoreIcon;
    private boolean mSelected = false;
    /** App categories that are to be used with this widget */
    private String[] mFacetCategories;
    /** App packages that are allowed to be used with this widget */
    private String[] mFacetPackages;


    public CarFacetButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        View.inflate(context, R.layout.car_facet_button, this);

        // extract custom attributes
        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.CarFacetButton);
        setupIntents(typedArray);
        setupIcons(typedArray);
    }

    /**
     * Reads the custom attributes to setup click handlers for this component.
     */
    private void setupIntents(TypedArray typedArray) {
        String intentString = typedArray.getString(R.styleable.CarFacetButton_intent);
        String longPressIntentString = typedArray.getString(R.styleable.CarFacetButton_longIntent);
        String categoryString = typedArray.getString(R.styleable.CarFacetButton_categories);
        String packageString = typedArray.getString(R.styleable.CarFacetButton_packages);
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

            setOnClickListener(v -> {
                intent.putExtra(EXTRA_FACET_LAUNCH_PICKER, mSelected);
                mContext.startActivity(intent);
            });

            if (longPressIntentString != null) {
                final Intent longPressIntent = Intent.parseUri(longPressIntentString,
                        Intent.URI_INTENT_SCHEME);
                setOnLongClickListener(v -> {
                    mContext.startActivity(longPressIntent);
                    return true;
                });
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to attach intent", e);
        }
    }


    private void setupIcons(TypedArray styledAttributes) {
        mIcon = findViewById(R.id.car_nav_button_icon);
        mIcon.setScaleType(ImageView.ScaleType.CENTER);
        mIcon.setClickable(false);
        mIcon.setAlpha(UNSELECTED_ALPHA);
        int iconResourceId = styledAttributes.getResourceId(R.styleable.CarFacetButton_icon, 0);
        if (iconResourceId == 0)  {
            throw new RuntimeException("specified icon resource was not found and is required");
        }
        mIcon.setImageResource(iconResourceId);

        mMoreIcon = findViewById(R.id.car_nav_button_more_icon);
        mMoreIcon.setClickable(false);
        mMoreIcon.setImageDrawable(getContext().getDrawable(R.drawable.car_ic_arrow));
        mMoreIcon.setAlpha(UNSELECTED_ALPHA);
        mMoreIcon.setVisibility(GONE);
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
     * Updates the alpha of the icons to "selected" and shows the "More icon"
     * @param selected true if the view must be selected, false otherwise
     */
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        setSelected(selected, selected);
    }

    /**
     * Updates the visual state to let the user know if it's been selected.
     * @param selected true if should update the alpha of the icon to selected, false otherwise
     * @param showMoreIcon true if the "more icon" should be shown, false otherwise
     */
    public void setSelected(boolean selected, boolean showMoreIcon) {
        mSelected = selected;
        if (selected) {
            mMoreIcon.setVisibility(showMoreIcon ? VISIBLE : GONE);
            mMoreIcon.setAlpha(SELECTED_ALPHA);
            mIcon.setAlpha(SELECTED_ALPHA);
        } else {
            mMoreIcon.setVisibility(GONE);
            mIcon.setAlpha(UNSELECTED_ALPHA);
        }
    }
}
