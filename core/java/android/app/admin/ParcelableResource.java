/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.admin;

import static java.util.Objects.requireNonNull;

import android.annotation.AnyRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Used to store the required information to load a resource that was updated using
 * {@link DevicePolicyResourcesManager#setDrawables} and
 * {@link DevicePolicyResourcesManager#setStrings}.
 *
 * @hide
 */
public final class ParcelableResource implements Parcelable {

    private static String TAG = "DevicePolicyManager";

    private static final String ATTR_RESOURCE_ID = "resource-id";
    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_RESOURCE_NAME = "resource-name";
    private static final String ATTR_RESOURCE_TYPE = "resource-type";

    public static final int RESOURCE_TYPE_DRAWABLE = 1;
    public static final int RESOURCE_TYPE_STRING = 2;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "RESOURCE_TYPE_" }, value = {
            RESOURCE_TYPE_DRAWABLE,
            RESOURCE_TYPE_STRING
    })
    public @interface ResourceType {}

    private final int mResourceId;
    @NonNull private final String mPackageName;
    @NonNull private final String mResourceName;
    private final int mResourceType;

    /**
     *
     * Creates a {@code ParcelableDevicePolicyResource} for the given {@code resourceId} and
     * verifies that it exists in the package of the given {@code context}.
     *
     * @param context for the package containing the {@code resourceId} to use as the updated
     *                resource
     * @param resourceId of the resource to use as an updated resource
     * @param resourceType see {@link ResourceType}
     */
    public ParcelableResource(
            @NonNull Context context, @AnyRes int resourceId, @ResourceType int resourceType)
            throws IllegalStateException, IllegalArgumentException {
        Objects.requireNonNull(context, "context must be provided");
        verifyResourceExistsInCallingPackage(context, resourceId, resourceType);

        this.mResourceId = resourceId;
        this.mPackageName = context.getResources().getResourcePackageName(resourceId);
        this.mResourceName = context.getResources().getResourceName(resourceId);
        this.mResourceType = resourceType;
    }

    /**
     * Creates a {@code ParcelableDevicePolicyResource} with the given params, this DOES NOT make
     * any verifications on whether the given {@code resourceId} actually exists.
     */
    private ParcelableResource(
            @AnyRes int resourceId, @NonNull String packageName, @NonNull String resourceName,
            @ResourceType int resourceType) {
        this.mResourceId = resourceId;
        this.mPackageName = requireNonNull(packageName);
        this.mResourceName = requireNonNull(resourceName);
        this.mResourceType = resourceType;
    }

    private static void verifyResourceExistsInCallingPackage(
            Context context, @AnyRes int resourceId, @ResourceType int resourceType)
            throws IllegalStateException, IllegalArgumentException {
        switch (resourceType) {
            case RESOURCE_TYPE_DRAWABLE:
                if (!hasDrawableInCallingPackage(context, resourceId)) {
                    throw new IllegalStateException(String.format(
                            "Drawable with id %d doesn't exist in the calling package %s",
                            resourceId,
                            context.getPackageName()));
                }
                break;
            case RESOURCE_TYPE_STRING:
                if (!hasStringInCallingPackage(context, resourceId)) {
                    throw new IllegalStateException(String.format(
                            "String with id %d doesn't exist in the calling package %s",
                            resourceId,
                            context.getPackageName()));
                }
                break;
            default:
                throw new IllegalArgumentException(
                        "Unknown ResourceType: " + resourceType);
        }
    }

    private static boolean hasDrawableInCallingPackage(Context context, @AnyRes int resourceId) {
        try {
            return "drawable".equals(context.getResources().getResourceTypeName(resourceId));
        } catch (Resources.NotFoundException e) {
            return false;
        }
    }

    private static boolean hasStringInCallingPackage(Context context, @AnyRes int resourceId) {
        try {
            return "string".equals(context.getResources().getResourceTypeName(resourceId));
        } catch (Resources.NotFoundException e) {
            return false;
        }
    }

    public @AnyRes int getResourceId() {
        return mResourceId;
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    @NonNull
    public String getResourceName() {
        return mResourceName;
    }

    public int getResourceType() {
        return mResourceType;
    }

    /**
     * Loads the drawable with id {@code mResourceId} from {@code mPackageName} using the provided
     * {@code density} and {@link Resources.Theme} and {@link Resources#getConfiguration} of the
     * provided {@code context}.
     *
     * <p>Returns the default drawable by calling the {@code defaultDrawableLoader} if the updated
     * drawable was not found or could not be loaded.</p>
     */
    @Nullable
    public Drawable getDrawable(
            Context context,
            int density,
            @NonNull Supplier<Drawable> defaultDrawableLoader) {
        // TODO(b/203548565): properly handle edge case when the device manager role holder is
        //  unavailable because it's being updated.
        try {
            Resources resources = getAppResourcesWithCallersConfiguration(context);
            verifyResourceName(resources);
            return resources.getDrawableForDensity(mResourceId, density, context.getTheme());
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Slog.e(TAG, "Unable to load drawable resource " + mResourceName, e);
            return loadDefaultDrawable(defaultDrawableLoader);
        }
    }

    /**
     * Loads the string with id {@code mResourceId} from {@code mPackageName} using the
     * configuration returned from {@link Resources#getConfiguration} of the provided
     * {@code context}.
     *
     * <p>Returns the default string by calling  {@code defaultStringLoader} if the updated
     * string was not found or could not be loaded.</p>
     */
    @Nullable
    public String getString(
            Context context,
            @NonNull Supplier<String> defaultStringLoader) {
        // TODO(b/203548565): properly handle edge case when the device manager role holder is
        //  unavailable because it's being updated.
        try {
            Resources resources = getAppResourcesWithCallersConfiguration(context);
            verifyResourceName(resources);
            return resources.getString(mResourceId);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Slog.e(TAG, "Unable to load string resource " + mResourceName, e);
            return loadDefaultString(defaultStringLoader);
        }
    }

    /**
     * Loads the string with id {@code mResourceId} from {@code mPackageName} using the
     * configuration returned from {@link Resources#getConfiguration} of the provided
     * {@code context}.
     *
     * <p>Returns the default string by calling  {@code defaultStringLoader} if the updated
     * string was not found or could not be loaded.</p>
     */
    @Nullable
    public String getString(
            Context context,
            @NonNull Supplier<String> defaultStringLoader,
            @NonNull Object... formatArgs) {
        // TODO(b/203548565): properly handle edge case when the device manager role holder is
        //  unavailable because it's being updated.
        try {
            Resources resources = getAppResourcesWithCallersConfiguration(context);
            verifyResourceName(resources);
            String rawString = resources.getString(mResourceId);
            return String.format(
                    context.getResources().getConfiguration().getLocales().get(0),
                    rawString,
                    formatArgs);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Slog.e(TAG, "Unable to load string resource " + mResourceName, e);
            return loadDefaultString(defaultStringLoader);
        }
    }

    private Resources getAppResourcesWithCallersConfiguration(Context context)
            throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        ApplicationInfo ai = pm.getApplicationInfo(
                mPackageName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES
                        | PackageManager.GET_SHARED_LIBRARY_FILES);
        return pm.getResourcesForApplication(ai, context.getResources().getConfiguration());
    }

    private void verifyResourceName(Resources resources) throws IllegalStateException {
        String name = resources.getResourceName(mResourceId);
        if (!mResourceName.equals(name)) {
            throw new IllegalStateException(String.format("Current resource name %s for resource id"
                            + " %d has changed from the previously stored resource name %s.",
                    name, mResourceId, mResourceName));
        }
    }

    /**
     * returns the {@link Drawable} loaded from calling {@code defaultDrawableLoader}.
     */
    @Nullable
    public static Drawable loadDefaultDrawable(@NonNull Supplier<Drawable> defaultDrawableLoader) {
        Objects.requireNonNull(defaultDrawableLoader, "defaultDrawableLoader can't be null");
        return defaultDrawableLoader.get();
    }

    /**
     * returns the {@link String} loaded from calling {@code defaultStringLoader}.
     */
    @Nullable
    public static String loadDefaultString(@NonNull Supplier<String> defaultStringLoader) {
        Objects.requireNonNull(defaultStringLoader, "defaultStringLoader can't be null");
        return defaultStringLoader.get();
    }

    /**
     * Writes the content of the current {@code ParcelableDevicePolicyResource} to the xml file
     * specified by {@code xmlSerializer}.
     */
    public void writeToXmlFile(TypedXmlSerializer xmlSerializer) throws IOException {
        xmlSerializer.attributeInt(/* namespace= */ null, ATTR_RESOURCE_ID, mResourceId);
        xmlSerializer.attribute(/* namespace= */ null, ATTR_PACKAGE_NAME, mPackageName);
        xmlSerializer.attribute(/* namespace= */ null, ATTR_RESOURCE_NAME, mResourceName);
        xmlSerializer.attributeInt(/* namespace= */ null, ATTR_RESOURCE_TYPE, mResourceType);
    }

    /**
     * Creates a new {@code ParcelableDevicePolicyResource} using the content of
     * {@code xmlPullParser}.
     */
    public static ParcelableResource createFromXml(TypedXmlPullParser xmlPullParser)
            throws XmlPullParserException, IOException {
        int resourceId = xmlPullParser.getAttributeInt(/* namespace= */ null, ATTR_RESOURCE_ID);
        String packageName = xmlPullParser.getAttributeValue(
                /* namespace= */ null, ATTR_PACKAGE_NAME);
        String resourceName = xmlPullParser.getAttributeValue(
                /* namespace= */ null, ATTR_RESOURCE_NAME);
        int resourceType = xmlPullParser.getAttributeInt(
                /* namespace= */ null, ATTR_RESOURCE_TYPE);

        return new ParcelableResource(
                resourceId, packageName, resourceName, resourceType);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParcelableResource other = (ParcelableResource) o;
        return mResourceId == other.mResourceId
                && mPackageName.equals(other.mPackageName)
                && mResourceName.equals(other.mResourceName)
                && mResourceType == other.mResourceType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mResourceId, mPackageName, mResourceName, mResourceType);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mResourceId);
        dest.writeString(mPackageName);
        dest.writeString(mResourceName);
        dest.writeInt(mResourceType);
    }

    public static final @NonNull Creator<ParcelableResource> CREATOR =
            new Creator<ParcelableResource>() {
                @Override
                public ParcelableResource createFromParcel(Parcel in) {
                    int resourceId = in.readInt();
                    String packageName = in.readString();
                    String resourceName = in.readString();
                    int resourceType = in.readInt();

                    return new ParcelableResource(
                            resourceId, packageName, resourceName, resourceType);
                }

                @Override
                public ParcelableResource[] newArray(int size) {
                    return new ParcelableResource[size];
                }
            };
}
