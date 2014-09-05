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

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.content.ComponentName;
import android.os.UserHandle;

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
     * Identity of this AppWidget component.  This component should be a {@link
     * android.content.BroadcastReceiver}, and it will be sent the AppWidget intents
     * {@link android.appwidget as described in the AppWidget package documentation}.
     *
     * <p>This field corresponds to the <code>android:name</code> attribute in
     * the <code>&lt;receiver&gt;</code> element in the AndroidManifest.xml file.
     */
    public ComponentName provider;

    /**
     * The default height of the widget when added to a host, in dp. The widget will get
     * at least this width, and will often be given more, depending on the host.
     *
     * <p>This field corresponds to the <code>android:minWidth</code> attribute in
     * the AppWidget meta-data file.
     */
    public int minWidth;

    /**
     * The default height of the widget when added to a host, in dp. The widget will get
     * at least this height, and will often be given more, depending on the host.
     *
     * <p>This field corresponds to the <code>android:minHeight</code> attribute in
     * the AppWidget meta-data file.
     */
    public int minHeight;

    /**
     * Minimum width (in dp) which the widget can be resized to. This field has no effect if it
     * is greater than minWidth or if horizontal resizing isn't enabled (see {@link #resizeMode}).
     *
     * <p>This field corresponds to the <code>android:minResizeWidth</code> attribute in
     * the AppWidget meta-data file.
     */
    public int minResizeWidth;

    /**
     * Minimum height (in dp) which the widget can be resized to. This field has no effect if it
     * is greater than minHeight or if vertical resizing isn't enabled (see {@link #resizeMode}).
     *
     * <p>This field corresponds to the <code>android:minResizeHeight</code> attribute in
     * the AppWidget meta-data file.
     */
    public int minResizeHeight;

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
     * <p>This field corresponds to the <code>android:previewImage</code> attribute in
     * the <code>&lt;receiver&gt;</code> element in the AndroidManifest.xml file.
     */
    public int previewImage;

    /**
     * The rules by which a widget can be resized. See {@link #RESIZE_NONE},
     * {@link #RESIZE_NONE}, {@link #RESIZE_HORIZONTAL},
     * {@link #RESIZE_VERTICAL}, {@link #RESIZE_BOTH}.
     *
     * <p>This field corresponds to the <code>android:resizeMode</code> attribute in
     * the AppWidget meta-data file.
     */
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
    public int widgetCategory;

    /** @hide */
    public ActivityInfo providerInfo;

    public AppWidgetProviderInfo() {

    }

    /**
     * Unflatten the AppWidgetProviderInfo from a parcel.
     */
    @SuppressWarnings("deprecation")
    public AppWidgetProviderInfo(Parcel in) {
        if (0 != in.readInt()) {
            this.provider = new ComponentName(in);
        }
        this.minWidth = in.readInt();
        this.minHeight = in.readInt();
        this.minResizeWidth = in.readInt();
        this.minResizeHeight = in.readInt();
        this.updatePeriodMillis = in.readInt();
        this.initialLayout = in.readInt();
        this.initialKeyguardLayout = in.readInt();
        if (0 != in.readInt()) {
            this.configure = new ComponentName(in);
        }
        this.label = in.readString();
        this.icon = in.readInt();
        this.previewImage = in.readInt();
        this.autoAdvanceViewId = in.readInt();
        this.resizeMode = in.readInt();
        this.widgetCategory = in.readInt();
        this.providerInfo = in.readParcelable(null);
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
     * Gets the user profile in which the provider resides.
     *
     * @return The hosting user profile.
     */
    public final UserHandle getProfile() {
        return new UserHandle(UserHandle.getUserId(providerInfo.applicationInfo.uid));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void writeToParcel(android.os.Parcel out, int flags) {
        if (this.provider != null) {
            out.writeInt(1);
            this.provider.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }
        out.writeInt(this.minWidth);
        out.writeInt(this.minHeight);
        out.writeInt(this.minResizeWidth);
        out.writeInt(this.minResizeHeight);
        out.writeInt(this.updatePeriodMillis);
        out.writeInt(this.initialLayout);
        out.writeInt(this.initialKeyguardLayout);
        if (this.configure != null) {
            out.writeInt(1);
            this.configure.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }
        out.writeString(this.label);
        out.writeInt(this.icon);
        out.writeInt(this.previewImage);
        out.writeInt(this.autoAdvanceViewId);
        out.writeInt(this.resizeMode);
        out.writeInt(this.widgetCategory);
        out.writeParcelable(this.providerInfo, flags);
    }

    @Override
    @SuppressWarnings("deprecation")
    public AppWidgetProviderInfo clone() {
        AppWidgetProviderInfo that = new AppWidgetProviderInfo();
        that.provider = this.provider == null ? null : this.provider.clone();
        that.minWidth = this.minWidth;
        that.minHeight = this.minHeight;
        that.minResizeWidth = this.minResizeHeight;
        that.minResizeHeight = this.minResizeHeight;
        that.updatePeriodMillis = this.updatePeriodMillis;
        that.initialLayout = this.initialLayout;
        that.initialKeyguardLayout = this.initialKeyguardLayout;
        that.configure = this.configure == null ? null : this.configure.clone();
        that.label = this.label == null ? null : this.label.substring(0);
        that.icon = this.icon;
        that.previewImage = this.previewImage;
        that.autoAdvanceViewId = this.autoAdvanceViewId;
        that.resizeMode = this.resizeMode;
        that.widgetCategory = this.widgetCategory;
        that.providerInfo = this.providerInfo;
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
            if (resourceId > 0) {
                if (density <= 0) {
                    density = context.getResources().getDisplayMetrics().densityDpi;
                }
                return resources.getDrawableForDensity(resourceId, density);
            }
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            /* ignore */
        }
        return loadDefaultIcon ? providerInfo.loadIcon(context.getPackageManager()) : null;
    }

    /**
     * Parcelable.Creator that instantiates AppWidgetProviderInfo objects
     */
    public static final Parcelable.Creator<AppWidgetProviderInfo> CREATOR
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
