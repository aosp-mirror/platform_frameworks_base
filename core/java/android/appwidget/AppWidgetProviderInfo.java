/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.appwidget;

import static android.appwidget.flags.Flags.FLAG_GENERATED_PREVIEWS;
import static android.appwidget.flags.Flags.generatedPreviews;

import android.annotation.FlaggedApi;
import android.annotation.IdRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ResourceId;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes the meta data for an installed AppWidget provider.  The fields in this class
 * correspond to the fields in the <code>&lt;appwidget-provider&gt;</code> xml tag.
 */
public class AppWidgetProviderInfo implements Parcelable {

    /**
     * Widget is not resizable.
     */
    public static final int RESIZE_NONE             = 0;
    /**
     * Widget is resizable in the horizontal axis only.
     */
    public static final int RESIZE_HORIZONTAL       = 1;
    /**
     * Widget is resizable in the vertical axis only.
     */
    public static final int RESIZE_VERTICAL         = 2;
    /**
     * Widget is resizable in both the horizontal and vertical axes.
     */
    public static final int RESIZE_BOTH = RESIZE_HORIZONTAL | RESIZE_VERTICAL;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            RESIZE_HORIZONTAL,
            RESIZE_VERTICAL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResizeModeFlags {}

    /** {@hide} */
    public static final int WIDGET_CATEGORY_UNKNOWN = -1;

    /**
     * Indicates that the widget can be displayed on the home screen. This is the default value.
     */
    public static final int WIDGET_CATEGORY_HOME_SCREEN = 1;

    /**
     * Indicates that the widget can be displayed on the keyguard.
     */
    public static final int WIDGET_CATEGORY_KEYGUARD = 2;

    /**
     * Indicates that the widget can be displayed within a space reserved for the search box.
     */
    public static final int WIDGET_CATEGORY_SEARCHBOX = 4;

    /**
     * Indicates that the widget should never be shown on the keyguard.
     *
     * <p>Some keyguard style features may decide that {@link #WIDGET_CATEGORY_KEYGUARD} isn't
     * required to be added by an app to show on the feature when chosen by a user.
     * This category allows for a stronger statement about placement of the widget that, even in the
     * above case, this widget should not be offered on the keyguard.
     *
     * <p>Setting this category doesn't change the behavior of AppWidgetManager queries, it is the
     * responsibility of the widget surface to respect this value.
     */
    @FlaggedApi(android.appwidget.flags.Flags.FLAG_NOT_KEYGUARD_CATEGORY)
    public static final int WIDGET_CATEGORY_NOT_KEYGUARD = 8;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            WIDGET_CATEGORY_HOME_SCREEN,
            WIDGET_CATEGORY_KEYGUARD,
            WIDGET_CATEGORY_SEARCHBOX,
            WIDGET_CATEGORY_NOT_KEYGUARD,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CategoryFlags {}

    /**
     * The widget can be reconfigured anytime after it is bound by starting the
     * {@link #configure} activity.
     *
     * @see #widgetFeatures
     */
    public static final int WIDGET_FEATURE_RECONFIGURABLE = 1;

    /**
     * The widget is added directly by the app, and the host may hide this widget when providing
     * the user with the list of available widgets to choose from.
     *
     * @see AppWidgetManager#requestPinAppWidget(ComponentName, Bundle, PendingIntent)
     * @see #widgetFeatures
     */
    public static final int WIDGET_FEATURE_HIDE_FROM_PICKER = 2;

    /**
     * The widget provides a default configuration. The host may choose not to launch the provided
     * configuration activity.
     *
     * @see #widgetFeatures
     */
    public static final int WIDGET_FEATURE_CONFIGURATION_OPTIONAL = 4;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            WIDGET_FEATURE_RECONFIGURABLE,
            WIDGET_FEATURE_HIDE_FROM_PICKER,
            WIDGET_FEATURE_CONFIGURATION_OPTIONAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FeatureFlags {}

    /**
     * Identity of this AppWidget component.  This component should be a {@link
     * android.content.BroadcastReceiver}, and it will be sent the AppWidget intents
     * {@link android.appwidget as described in the AppWidget package documentation}.
     *
     * <p>This field corresponds to the <code>android:name</code> attribute in
     * the <code>&lt;receiver&gt;</code> element in the AndroidManifest.xml file.
     */
    public ComponentName provider;

    /**
     * The default width of the widget when added to a host, in px. The widget will get
     * at least this width, and will often be given more, depending on the host.
     *
     * <p>This field corresponds to the <code>android:minWidth</code> attribute in
     * the AppWidget meta-data file.
     */
    public int minWidth;

    /**
     * The default height of the widget when added to a host, in px. The widget will get
     * at least this height, and will often be given more, depending on the host.
     *
     * <p>This field corresponds to the <code>android:minHeight</code> attribute in
     * the AppWidget meta-data file.
     */
    public int minHeight;

    /**
     * Minimum width (in px) which the widget can be resized to. This field has no effect if it
     * is greater than minWidth or if horizontal resizing isn't enabled (see {@link #resizeMode}).
     *
     * <p>This field corresponds to the <code>android:minResizeWidth</code> attribute in
     * the AppWidget meta-data file.
     */
    public int minResizeWidth;

    /**
     * Minimum height (in px) which the widget can be resized to. This field has no effect if it
     * is greater than minHeight or if vertical resizing isn't enabled (see {@link #resizeMode}).
     *
     * <p>This field corresponds to the <code>android:minResizeHeight</code> attribute in
     * the AppWidget meta-data file.
     */
    public int minResizeHeight;

    /**
     * Maximum width (in px) which the widget can be resized to. This field has no effect if it is
     * smaller than minWidth or if horizontal resizing isn't enabled (see {@link #resizeMode}).
     *
     * <p>This field corresponds to the <code>android:maxResizeWidth</code> attribute in the
     * AppWidget meta-data file.
     */
    @SuppressLint("MutableBareField")
    public int maxResizeWidth;

    /**
     * Maximum height (in px) which the widget can be resized to. This field has no effect if it is
     * smaller than minHeight or if vertical resizing isn't enabled (see {@link #resizeMode}).
     *
     * <p>This field corresponds to the <code>android:maxResizeHeight</code> attribute in the
     * AppWidget meta-data file.
     */
    @SuppressLint("MutableBareField")
    public int maxResizeHeight;

    /**
     * The default width of a widget when added to a host, in units of launcher grid cells.
     *
     * <p>This field corresponds to the <code>android:targetCellWidth</code> attribute in the
     * AppWidget meta-data file.
     */
    @SuppressLint("MutableBareField")
    public int targetCellWidth;

    /**
     * The default height of a widget when added to a host, in units of launcher grid cells.
     *
     * <p>This field corresponds to the <code>android:targetCellHeight</code> attribute in the
     * AppWidget meta-data file.
     */
    @SuppressLint("MutableBareField")
    public int targetCellHeight;

    /**
     * How often, in milliseconds, that this AppWidget wants to be updated.
     * The AppWidget manager may place a limit on how often a AppWidget is updated.
     *
     * <p>This field corresponds to the <code>android:updatePeriodMillis</code> attribute in
     * the AppWidget meta-data file.
     *
     * <p class="note"><b>Note:</b> Updates requested with <code>updatePeriodMillis</code>
     * will not be delivered more than once every 30 minutes.</p>
     */
    public int updatePeriodMillis;

    /**
     * The resource id of the initial layout for this AppWidget.  This should be
     * displayed until the RemoteViews for the AppWidget is available.
     *
     * <p>This field corresponds to the <code>android:initialLayout</code> attribute in
     * the AppWidget meta-data file.
     */
    public int initialLayout;

    /**
     * The resource id of the initial layout for this AppWidget when it is displayed on keyguard.
     * This parameter only needs to be provided if the widget can be displayed on the keyguard,
     * see {@link #widgetCategory}.
     *
     * <p>This field corresponds to the <code>android:initialKeyguardLayout</code> attribute in
     * the AppWidget meta-data file.
     */
    public int initialKeyguardLayout;

    /**
     * The activity to launch that will configure the AppWidget.
     *
     * <p>This class name of field corresponds to the <code>android:configure</code> attribute in
     * the AppWidget meta-data file.  The package name always corresponds to the package containing
     * the AppWidget provider.
     */
    public ComponentName configure;

    /**
     * The label to display to the user in the AppWidget picker.
     *
     * @deprecated Use {@link #loadLabel(android.content.pm.PackageManager)}.
     */
    @Deprecated
    public String label;

    /**
     * The icon to display for this AppWidget in the AppWidget picker. If not supplied in the
     * xml, the application icon will be used.
     *
     * <p>This field corresponds to the <code>android:icon</code> attribute in
     * the <code>&lt;receiver&gt;</code> element in the AndroidManifest.xml file.
     */
    public int icon;

    /**
     * The view id of the AppWidget subview which should be auto-advanced by the widget's host.
     *
     * <p>This field corresponds to the <code>android:autoAdvanceViewId</code> attribute in
     * the AppWidget meta-data file.
     */
    public int autoAdvanceViewId;

    /**
     * A preview of what the AppWidget will look like after it's configured.
     * If not supplied, the AppWidget's icon will be used.
     *
     * <p>This field corresponds to the <code>android:previewImage</code> attribute in the AppWidget
     * meta-data file.
     */
    public int previewImage;

    /**
     * The layout resource id of a preview of what the AppWidget will look like after it's
     * configured.
     *
     * <p>Unlike previewImage, previewLayout can better showcase AppWidget in different locales,
     * system themes, display sizes & density etc.
     *
     * <p>If supplied, this will take precedence over the previewImage on supported widget hosts.
     * Otherwise, previewImage will be used.
     *
     * <p>This field corresponds to the <code>android:previewLayout</code> attribute in the
     * AppWidget meta-data file.
     */
    @SuppressLint("MutableBareField")
    @IdRes
    public int previewLayout;

    /**
     * The rules by which a widget can be resized. See {@link #RESIZE_NONE},
     * {@link #RESIZE_NONE}, {@link #RESIZE_HORIZONTAL},
     * {@link #RESIZE_VERTICAL}, {@link #RESIZE_BOTH}.
     *
     * <p>This field corresponds to the <code>android:resizeMode</code> attribute in
     * the AppWidget meta-data file.
     */
    @ResizeModeFlags
    public int resizeMode;

    /**
     * Determines whether this widget can be displayed on the home screen, the keyguard, or both.
     * A widget which is displayed on both needs to ensure that it follows the design guidelines
     * for both widget classes. This can be achieved by querying the AppWidget options in its
     * widget provider's update method.
     *
     * <p>This field corresponds to the <code>widgetCategory</code> attribute in
     * the AppWidget meta-data file.
     */
    @CategoryFlags
    public int widgetCategory;

    /**
     * Resource id for the description of the AppWidget.
     *
     * <p>This field corresponds to the <code>android:description</code> attribute in the AppWidget
     * meta-data file.
     */
    @SuppressLint("MutableBareField")
    @IdRes
    public int descriptionRes;

    /**
     * Flags indicating various features supported by the widget. These are hints to the widget
     * host, and do not actually change the behavior of the widget.
     *
     * @see #WIDGET_FEATURE_RECONFIGURABLE
     * @see #WIDGET_FEATURE_HIDE_FROM_PICKER
     * @see #WIDGET_FEATURE_CONFIGURATION_OPTIONAL
     */
    @FeatureFlags
    public int widgetFeatures;

    /** @hide */
    @UnsupportedAppUsage
    public ActivityInfo providerInfo;

    /** @hide */
    public boolean isExtendedFromAppWidgetProvider;

    /**
     * Flags indicating the widget categories for which generated previews are available.
     * These correspond to the previews set by this provider with
     * {@link AppWidgetManager#setWidgetPreview}.
     *
     * @see #WIDGET_CATEGORY_HOME_SCREEN
     * @see #WIDGET_CATEGORY_KEYGUARD
     * @see #WIDGET_CATEGORY_SEARCHBOX
     * @see AppWidgetManager#getWidgetPreview
     */
    @FlaggedApi(FLAG_GENERATED_PREVIEWS)
    @SuppressLint("MutableBareField")
    public int generatedPreviewCategories;

    public AppWidgetProviderInfo() {

    }

    /**
     * Unflatten the AppWidgetProviderInfo from a parcel.
     */
    @SuppressWarnings("deprecation")
    public AppWidgetProviderInfo(Parcel in) {
        this.provider = in.readTypedObject(ComponentName.CREATOR);
        this.minWidth = in.readInt();
        this.minHeight = in.readInt();
        this.minResizeWidth = in.readInt();
        this.minResizeHeight = in.readInt();
        this.maxResizeWidth = in.readInt();
        this.maxResizeHeight = in.readInt();
        this.targetCellWidth = in.readInt();
        this.targetCellHeight = in.readInt();
        this.updatePeriodMillis = in.readInt();
        this.initialLayout = in.readInt();
        this.initialKeyguardLayout = in.readInt();
        this.configure = in.readTypedObject(ComponentName.CREATOR);
        this.label = in.readString();
        this.icon = in.readInt();
        this.previewImage = in.readInt();
        this.previewLayout = in.readInt();
        this.autoAdvanceViewId = in.readInt();
        this.resizeMode = in.readInt();
        this.widgetCategory = in.readInt();
        this.providerInfo = in.readTypedObject(ActivityInfo.CREATOR);
        this.widgetFeatures = in.readInt();
        this.descriptionRes = in.readInt();
        this.isExtendedFromAppWidgetProvider = in.readBoolean();
        if (generatedPreviews()) {
            generatedPreviewCategories = in.readInt();
        }
    }

    /**
     * Loads the localized label to display to the user in the AppWidget picker.
     *
     * @param packageManager Package manager instance for loading resources.
     * @return The label for the current locale.
     */
    public final String loadLabel(PackageManager packageManager) {
        CharSequence label = providerInfo.loadLabel(packageManager);
        if (label != null) {
            return label.toString().trim();
        }
        return null;
    }

    /**
     * Loads the icon to display for this AppWidget in the AppWidget picker. If not
     * supplied in the xml, the application icon will be used. A client can optionally
     * provide a desired density such as {@link android.util.DisplayMetrics#DENSITY_LOW}
     * {@link android.util.DisplayMetrics#DENSITY_MEDIUM}, etc. If no density is
     * provided, the density of the current display will be used.
     * <p>
     * The loaded icon corresponds to the <code>android:icon</code> attribute in
     * the <code>&lt;receiver&gt;</code> element in the AndroidManifest.xml file.
     * </p>
     *
     * @param context Context for accessing resources.
     * @param density The optional desired density as per
     *         {@link android.util.DisplayMetrics#densityDpi}.
     * @return The provider icon.
     */
    public final Drawable loadIcon(@NonNull Context context, int density) {
        return loadDrawable(context, density, providerInfo.getIconResource(), true);
    }

    /**
     * Loads a preview of what the AppWidget will look like after it's configured.
     * A client can optionally provide a desired density such as
     * {@link android.util.DisplayMetrics#DENSITY_LOW}
     * {@link android.util.DisplayMetrics#DENSITY_MEDIUM}, etc. If no density is
     * provided, the density of the current display will be used.
     * <p>
     * The loaded image corresponds to the <code>android:previewImage</code> attribute
     * in the <code>&lt;receiver&gt;</code> element in the AndroidManifest.xml file.
     * </p>
     *
     * @param context Context for accessing resources.
     * @param density The optional desired density as per
     *         {@link android.util.DisplayMetrics#densityDpi}.
     * @return The widget preview image or null if preview image is not available.
     */
    public final Drawable loadPreviewImage(@NonNull Context context, int density) {
        return loadDrawable(context, density, previewImage, false);
    }

    /**
     * Loads localized description for the app widget.
     *
     * <p>Description is intended to be displayed in the UI of the widget picker.
     *
     * @param context Context for accessing resources.
     *
     * @return CharSequence for app widget description for the current locale.
     */
    @Nullable
    public final CharSequence loadDescription(@NonNull Context context) {
        if (ResourceId.isValid(descriptionRes)) {
            CharSequence description =
                    context.getPackageManager().getText(
                            providerInfo.packageName,
                            descriptionRes,
                            providerInfo.applicationInfo);
            if (description != null) {
                return description.toString().trim();
            }
        }
        return null;
    }

    /**
     * Gets the user profile in which the provider resides.
     *
     * @return The hosting user profile.
     */
    public final UserHandle getProfile() {
        return new UserHandle(UserHandle.getUserId(providerInfo.applicationInfo.uid));
    }

    /**
     * Returns the broadcast receiver that is providing this widget.
     */
    @NonNull
    public ActivityInfo getActivityInfo() {
        return providerInfo;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void writeToParcel(Parcel out, int flags) {
        out.writeTypedObject(this.provider, flags);
        out.writeInt(this.minWidth);
        out.writeInt(this.minHeight);
        out.writeInt(this.minResizeWidth);
        out.writeInt(this.minResizeHeight);
        out.writeInt(this.maxResizeWidth);
        out.writeInt(this.maxResizeHeight);
        out.writeInt(this.targetCellWidth);
        out.writeInt(this.targetCellHeight);
        out.writeInt(this.updatePeriodMillis);
        out.writeInt(this.initialLayout);
        out.writeInt(this.initialKeyguardLayout);
        out.writeTypedObject(this.configure, flags);
        out.writeString(this.label);
        out.writeInt(this.icon);
        out.writeInt(this.previewImage);
        out.writeInt(this.previewLayout);
        out.writeInt(this.autoAdvanceViewId);
        out.writeInt(this.resizeMode);
        out.writeInt(this.widgetCategory);
        out.writeTypedObject(this.providerInfo, flags);
        out.writeInt(this.widgetFeatures);
        out.writeInt(this.descriptionRes);
        out.writeBoolean(this.isExtendedFromAppWidgetProvider);
        if (generatedPreviews()) {
            out.writeInt(this.generatedPreviewCategories);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public AppWidgetProviderInfo clone() {
        AppWidgetProviderInfo that = new AppWidgetProviderInfo();
        that.provider = this.provider == null ? null : this.provider.clone();
        that.minWidth = this.minWidth;
        that.minHeight = this.minHeight;
        that.minResizeWidth = this.minResizeWidth;
        that.minResizeHeight = this.minResizeHeight;
        that.maxResizeWidth = this.maxResizeWidth;
        that.maxResizeHeight = this.maxResizeHeight;
        that.targetCellWidth = this.targetCellWidth;
        that.targetCellHeight = this.targetCellHeight;
        that.updatePeriodMillis = this.updatePeriodMillis;
        that.initialLayout = this.initialLayout;
        that.initialKeyguardLayout = this.initialKeyguardLayout;
        that.configure = this.configure == null ? null : this.configure.clone();
        that.label = this.label;
        that.icon = this.icon;
        that.previewImage = this.previewImage;
        that.previewLayout = this.previewLayout;
        that.autoAdvanceViewId = this.autoAdvanceViewId;
        that.resizeMode = this.resizeMode;
        that.widgetCategory = this.widgetCategory;
        that.providerInfo = this.providerInfo;
        that.widgetFeatures = this.widgetFeatures;
        that.descriptionRes = this.descriptionRes;
        that.isExtendedFromAppWidgetProvider = this.isExtendedFromAppWidgetProvider;
        if (generatedPreviews()) {
            that.generatedPreviewCategories = this.generatedPreviewCategories;
        }
        return that;
    }

    public int describeContents() {
        return 0;
    }

    private Drawable loadDrawable(Context context, int density, int resourceId,
            boolean loadDefaultIcon) {
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(
                    providerInfo.applicationInfo);
            if (ResourceId.isValid(resourceId)) {
                if (density < 0) {
                    density = 0;
                }
                return resources.getDrawableForDensity(resourceId, density, null);
            }
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            /* ignore */
        }
        return loadDefaultIcon ? providerInfo.loadIcon(context.getPackageManager()) : null;
    }

    /**
     * @hide
     */
    public void updateDimensions(DisplayMetrics displayMetrics) {
        // Converting complex to dp.
        minWidth = TypedValue.complexToDimensionPixelSize(minWidth, displayMetrics);
        minHeight = TypedValue.complexToDimensionPixelSize(minHeight, displayMetrics);
        minResizeWidth = TypedValue.complexToDimensionPixelSize(minResizeWidth, displayMetrics);
        minResizeHeight = TypedValue.complexToDimensionPixelSize(minResizeHeight, displayMetrics);
        maxResizeWidth = TypedValue.complexToDimensionPixelSize(maxResizeWidth, displayMetrics);
        maxResizeHeight = TypedValue.complexToDimensionPixelSize(maxResizeHeight, displayMetrics);
    }

    /**
     * Parcelable.Creator that instantiates AppWidgetProviderInfo objects
     */
    public static final @android.annotation.NonNull Parcelable.Creator<AppWidgetProviderInfo> CREATOR
            = new Parcelable.Creator<AppWidgetProviderInfo>()
    {
        public AppWidgetProviderInfo createFromParcel(Parcel parcel)
        {
            return new AppWidgetProviderInfo(parcel);
        }

        public AppWidgetProviderInfo[] newArray(int size)
        {
            return new AppWidgetProviderInfo[size];
        }
    };

    public String toString() {
        return "AppWidgetProviderInfo(" + getProfile() + '/' + provider + ')';
    }
}
