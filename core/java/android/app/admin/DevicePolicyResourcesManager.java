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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.util.DisplayMetrics;
import android.util.Log;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Class containing required APIs to set, reset, and get device policy related resources.
 */
public class DevicePolicyResourcesManager {
    private static String TAG = "DevicePolicyResourcesManager";

    private static String DISABLE_RESOURCES_UPDATABILITY_FLAG = "disable_resources_updatability";
    private static boolean DEFAULT_DISABLE_RESOURCES_UPDATABILITY = false;

    private final Context mContext;
    private final IDevicePolicyManager mService;

    /**
     * @hide
     */
    protected DevicePolicyResourcesManager(Context context, IDevicePolicyManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * For each {@link DevicePolicyDrawableResource} item in {@code drawables}, if
     * {@link DevicePolicyDrawableResource#getDrawableSource()} is not set, it updates the drawable
     * resource for the combination of {@link DevicePolicyDrawableResource#getDrawableId()} and
     * {@link DevicePolicyDrawableResource#getDrawableStyle()} to the drawable with resource ID
     * {@link DevicePolicyDrawableResource#getResourceIdInCallingPackage()},
     * meaning any system UI surface calling {@link #getDrawable} with {@code drawableId} and
     * {@code drawableStyle} will get the new resource after this API is called.
     *
     * <p>Otherwise, if {@link DevicePolicyDrawableResource#getDrawableSource()} is set, it
     * overrides any drawables that was set for the same {@code drawableId} and
     * {@code drawableStyle} for the provided source.
     *
     * <p>Sends a broadcast with action
     * {@link DevicePolicyManager#ACTION_DEVICE_POLICY_RESOURCE_UPDATED} to registered receivers
     * when a resource has been updated successfully.
     *
     * <p>Important notes to consider when using this API:
     * <ul>
     * <li> Updated resources are persisted over reboots.
     * <li>{@link #getDrawable} references the resource
     * {@link DevicePolicyDrawableResource#getResourceIdInCallingPackage()} in the
     * calling package each time it gets called. You have to ensure that the resource is always
     * available in the calling package as long as it is used as an updated resource.
     * <li>You still have to re-call {@code setDrawables} even if you only make changes to the
     * content of the resource with ID
     * {@link DevicePolicyDrawableResource#getResourceIdInCallingPackage()} as the content might be
     * cached and would need updating.
     * </ul>
     *
     * @param drawables The list of {@link DevicePolicyDrawableResource} to update.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_MANAGEMENT_RESOURCES)
    public void setDrawables(@NonNull Set<DevicePolicyDrawableResource> drawables) {
        if (mService != null) {
            try {
                mService.setDrawables(new ArrayList<>(drawables));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Removes all updated drawables for the list of {@code drawableIds} that was previously set by
     * calling {@link #setDrawables}, meaning any subsequent calls to {@link #getDrawable} for the
     * provided IDs with any {@code drawableStyle} and any {@code drawableSource} will return the
     * default drawable from {@code defaultDrawableLoader}.
     *
     * <p>Sends a broadcast with action
     * {@link DevicePolicyManager#ACTION_DEVICE_POLICY_RESOURCE_UPDATED} to registered receivers
     * when a resource has been reset successfully.
     *
     * @param drawableIds The list of IDs to remove.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_MANAGEMENT_RESOURCES)
    public void resetDrawables(@NonNull Set<String> drawableIds) {
        if (mService != null) {
            try {
                mService.resetDrawables(new ArrayList<>(drawableIds));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the appropriate updated drawable for the {@code drawableId} with style
     * {@code drawableStyle} if one was set using {@code setDrawables}, otherwise returns the
     * drawable from {@code defaultDrawableLoader}.
     *
     * <p>Also returns the drawable from {@code defaultDrawableLoader} if {@code drawableId}
     * is {@link DevicePolicyResources#UNDEFINED}.
     *
     * <p>Calls to this API will not return {@code null} unless no updated drawable was found
     * and the call to {@code defaultDrawableLoader} returned {@code null}.
     *
     * <p>This API uses the screen density returned from {@link Resources#getConfiguration()}, to
     * set a different value use
     * {@link #getDrawableForDensity(String, String, int, Supplier)}.
     *
     * <p>Callers should register for
     * {@link DevicePolicyManager#ACTION_DEVICE_POLICY_RESOURCE_UPDATED} to get notified when a
     * resource has been updated.
     *
     * <p>Note that each call to this API loads the resource from the package that called
     * {@code setDrawables} to set the updated resource.
     *
     * @param drawableId The drawable ID to get the updated resource for.
     * @param drawableStyle The drawable style to use.
     * @param defaultDrawableLoader To get the default drawable if no updated drawable was set for
     *                              the provided params.
     */
    @Nullable
    public Drawable getDrawable(
            @NonNull String drawableId,
            @NonNull String drawableStyle,
            @NonNull Supplier<Drawable> defaultDrawableLoader) {
        return getDrawable(
                drawableId, drawableStyle, DevicePolicyResources.UNDEFINED, defaultDrawableLoader);
    }

    /**
     * Similar to {@link #getDrawable(String, String, Supplier)}, but also accepts
     * a {@code drawableSource} which could result in returning a different drawable than
     * {@link #getDrawable(String, String, Supplier)} if an override was set for that specific
     * source.
     *
     * <p> If {@code drawableSource} is {@link DevicePolicyResources#UNDEFINED}, it returns the
     * appropriate string for {@code drawableId} and {@code drawableStyle} similar to
     * {@link #getDrawable(String, String, Supplier)}.
     *
     * <p>Calls to this API will not return {@code null} unless no updated drawable was found
     * and the call to {@code defaultDrawableLoader} returned {@code null}.
     *
     * <p>Callers should register for
     * {@link DevicePolicyManager#ACTION_DEVICE_POLICY_RESOURCE_UPDATED} to get notified when a
     * resource has been updated.
     *
     * @param drawableId The drawable ID to get the updated resource for.
     * @param drawableStyle The drawable style to use.
     * @param drawableSource The source for the caller.
     * @param defaultDrawableLoader To get the default drawable if no updated drawable was set for
     *                              the provided params.
     */
    @Nullable
    public Drawable getDrawable(
            @NonNull String drawableId,
            @NonNull String drawableStyle,
            @NonNull String drawableSource,
            @NonNull Supplier<Drawable> defaultDrawableLoader) {

        Objects.requireNonNull(drawableId, "drawableId can't be null");
        Objects.requireNonNull(drawableStyle, "drawableStyle can't be null");
        Objects.requireNonNull(drawableSource, "drawableSource can't be null");
        Objects.requireNonNull(defaultDrawableLoader, "defaultDrawableLoader can't be null");

        if (drawableId.equals(DevicePolicyResources.UNDEFINED)
                || DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_DEVICE_POLICY_MANAGER,
                        DISABLE_RESOURCES_UPDATABILITY_FLAG,
                        DEFAULT_DISABLE_RESOURCES_UPDATABILITY)) {
            return ParcelableResource.loadDefaultDrawable(defaultDrawableLoader);
        }

        if (mService != null) {
            try {
                ParcelableResource resource = mService.getDrawable(
                        drawableId, drawableStyle, drawableSource);
                if (resource == null) {
                    return ParcelableResource.loadDefaultDrawable(defaultDrawableLoader);
                }
                return resource.getDrawable(
                        mContext,
                        /* density= */ 0,
                        defaultDrawableLoader);

            } catch (RemoteException e) {
                Log.e(
                        TAG,
                        "Error getting the updated drawable from DevicePolicyManagerService.",
                        e);
                return ParcelableResource.loadDefaultDrawable(defaultDrawableLoader);
            }
        }
        return ParcelableResource.loadDefaultDrawable(defaultDrawableLoader);
    }

    /**
     * Similar to {@link #getDrawable(String, String, Supplier)}, but also accepts
     * {@code density}. See {@link Resources#getDrawableForDensity(int, int, Resources.Theme)}.
     *
     * <p>Calls to this API will not return {@code null} unless no updated drawable was found
     * and the call to {@code defaultDrawableLoader} returned {@code null}.
     *
     * <p>Callers should register for
     * {@link DevicePolicyManager#ACTION_DEVICE_POLICY_RESOURCE_UPDATED} to get notified when a
     * resource has been updated.
     *
     * @param drawableId The drawable ID to get the updated resource for.
     * @param drawableStyle The drawable style to use.
     * @param density The desired screen density indicated by the resource as
     *            found in {@link DisplayMetrics}. A value of 0 means to use the
     *            density returned from {@link Resources#getConfiguration()}.
     * @param defaultDrawableLoader To get the default drawable if no updated drawable was set for
     *                              the provided params.
     */
    @Nullable
    public Drawable getDrawableForDensity(
            @NonNull String drawableId,
            @NonNull String drawableStyle,
            int density,
            @NonNull Supplier<Drawable> defaultDrawableLoader) {
        return getDrawableForDensity(
                drawableId,
                drawableStyle,
                DevicePolicyResources.UNDEFINED,
                density,
                defaultDrawableLoader);
    }

    /**
     * Similar to {@link #getDrawable(String, String, String, Supplier)}, but also accepts
     * {@code density}. See {@link Resources#getDrawableForDensity(int, int, Resources.Theme)}.
     *
     * <p>Calls to this API will not return {@code null} unless no updated drawable was found
     * and the call to {@code defaultDrawableLoader} returned {@code null}.
     *
     * <p>Callers should register for
     * {@link DevicePolicyManager#ACTION_DEVICE_POLICY_RESOURCE_UPDATED} to get notified when a
     * resource has been updated.
     *
     * @param drawableId The drawable ID to get the updated resource for.
     * @param drawableStyle The drawable style to use.
     * @param drawableSource The source for the caller.
     * @param density The desired screen density indicated by the resource as
     *            found in {@link DisplayMetrics}. A value of 0 means to use the
     *            density returned from {@link Resources#getConfiguration()}.
     * @param defaultDrawableLoader To get the default drawable if no updated drawable was set for
     *                              the provided params.
     */
    @Nullable
    public Drawable getDrawableForDensity(
            @NonNull String drawableId,
            @NonNull String drawableStyle,
            @NonNull String drawableSource,
            int density,
            @NonNull Supplier<Drawable> defaultDrawableLoader) {

        Objects.requireNonNull(drawableId, "drawableId can't be null");
        Objects.requireNonNull(drawableStyle, "drawableStyle can't be null");
        Objects.requireNonNull(drawableSource, "drawableSource can't be null");
        Objects.requireNonNull(defaultDrawableLoader, "defaultDrawableLoader can't be null");

        if (drawableId.equals(DevicePolicyResources.UNDEFINED)
                || DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_DEVICE_POLICY_MANAGER,
                        DISABLE_RESOURCES_UPDATABILITY_FLAG,
                        DEFAULT_DISABLE_RESOURCES_UPDATABILITY)) {
            return ParcelableResource.loadDefaultDrawable(defaultDrawableLoader);
        }

        if (mService != null) {
            try {
                ParcelableResource resource = mService.getDrawable(
                        drawableId, drawableStyle, drawableSource);
                if (resource == null) {
                    return ParcelableResource.loadDefaultDrawable(defaultDrawableLoader);
                }
                return resource.getDrawable(mContext, density, defaultDrawableLoader);
            } catch (RemoteException e) {
                Log.e(
                        TAG,
                        "Error getting the updated drawable from DevicePolicyManagerService.",
                        e);
                return ParcelableResource.loadDefaultDrawable(defaultDrawableLoader);
            }
        }
        return ParcelableResource.loadDefaultDrawable(defaultDrawableLoader);
    }

    /**
     * Similar to {@link #getDrawable(String, String, String, Supplier)} but returns an
     * {@link Icon} instead of a {@link Drawable}.
     *
     * @param drawableId The drawable ID to get the updated resource for.
     * @param drawableStyle The drawable style to use.
     * @param drawableSource The source for the caller.
     * @param defaultIcon Returned if no updated drawable was set for the provided params.
     */
    @Nullable
    public Icon getDrawableAsIcon(
            @NonNull String drawableId,
            @NonNull String drawableStyle,
            @NonNull String drawableSource,
            @Nullable Icon defaultIcon) {
        Objects.requireNonNull(drawableId, "drawableId can't be null");
        Objects.requireNonNull(drawableStyle, "drawableStyle can't be null");
        Objects.requireNonNull(drawableSource, "drawableSource can't be null");
        Objects.requireNonNull(defaultIcon, "defaultIcon can't be null");

        if (drawableId.equals(DevicePolicyResources.UNDEFINED)
                || DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_DEVICE_POLICY_MANAGER,
                        DISABLE_RESOURCES_UPDATABILITY_FLAG,
                        DEFAULT_DISABLE_RESOURCES_UPDATABILITY)) {
            return defaultIcon;
        }

        if (mService != null) {
            try {
                ParcelableResource resource = mService.getDrawable(
                        drawableId, drawableStyle, drawableSource);
                if (resource == null) {
                    return defaultIcon;
                }
                return Icon.createWithResource(resource.getPackageName(), resource.getResourceId());
            } catch (RemoteException e) {
                Log.e(
                        TAG,
                        "Error getting the updated drawable from DevicePolicyManagerService.",
                        e);
                return defaultIcon;
            }
        }
        return defaultIcon;
    }

    /**
     * Similar to {@link #getDrawable(String, String, Supplier)} but returns an {@link Icon}
     * instead of a {@link Drawable}.
     *
     * @param drawableId The drawable ID to get the updated resource for.
     * @param drawableStyle The drawable style to use.
     * @param defaultIcon Returned if no updated drawable was set for the provided params.
     */
    @Nullable
    public Icon getDrawableAsIcon(
            @NonNull String drawableId,
            @NonNull String drawableStyle,
            @Nullable Icon defaultIcon) {
        return getDrawableAsIcon(
                drawableId, drawableStyle, DevicePolicyResources.UNDEFINED, defaultIcon);
    }


    /**
     * For each {@link DevicePolicyStringResource} item in {@code strings}, it updates the string
     * resource for {@link DevicePolicyStringResource#getStringId()} to the string with ID
     * {@code callingPackageResourceId}, meaning any system UI surface calling {@link #getString}
     * with {@code stringId} will get the new resource after this API is called.
     *
     * <p>Sends a broadcast with action
     * {@link DevicePolicyManager#ACTION_DEVICE_POLICY_RESOURCE_UPDATED} to registered receivers
     * when a resource has been updated successfully.
     *
     * <p>Important notes to consider when using this API:
     * <ul>
     * <li> Updated resources are persisted over reboots.
     * <li> {@link #getString} references the resource
     * {@link DevicePolicyStringResource#getResourceIdInCallingPackage()} in the
     * calling package each time it gets called. You have to ensure that the resource is always
     * available in the calling package as long as it is used as an updated resource.
     * <li> You still have to re-call {@code setStrings} even if you only make changes to the
     * content of the resource with ID {@code callingPackageResourceId} as the content might be
     * cached and would need updating.
     * </ul>
     *
     * @param strings The list of {@link DevicePolicyStringResource} to update.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_MANAGEMENT_RESOURCES)
    public void setStrings(@NonNull Set<DevicePolicyStringResource> strings) {
        if (mService != null) {
            try {
                mService.setStrings(new ArrayList<>(strings));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Removes the updated strings for the list of {@code stringIds} that was previously set by
     * calling {@link #setStrings}, meaning any subsequent calls to {@link #getString} for the
     * provided IDs will return the default string from {@code defaultStringLoader}.
     *
     * <p>Sends a broadcast with action
     * {@link DevicePolicyManager#ACTION_DEVICE_POLICY_RESOURCE_UPDATED} to registered receivers
     * when a resource has been reset successfully.
     *
     * @param stringIds The list of IDs to remove the updated resources for.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_MANAGEMENT_RESOURCES)
    public void resetStrings(@NonNull Set<String> stringIds) {
        if (mService != null) {
            try {
                mService.resetStrings(new ArrayList<>(stringIds));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the appropriate updated string for the {@code stringId} (see
     * {@code DevicePolicyResources.Strings}) if one was set using
     * {@code setStrings}, otherwise returns the string from {@code defaultStringLoader}.
     *
     * <p>Also returns the string from {@code defaultStringLoader} if {@code stringId} is
     * {@link DevicePolicyResources#UNDEFINED}.
     *
     * <p>Calls to this API will not return {@code null} unless no updated drawable was found
     * and the call to {@code defaultStringLoader} returned {@code null}.
     *
     * <p>Callers should register for
     * {@link DevicePolicyManager#ACTION_DEVICE_POLICY_RESOURCE_UPDATED} to get notified when a
     * resource has been updated.
     *
     * <p>Note that each call to this API loads the resource from the package that called
     * {@code setStrings} to set the updated resource.
     *
     * @param stringId The IDs to get the updated resource for.
     * @param defaultStringLoader To get the default string if no updated string was set for
     *         {@code stringId}.
     */
    @Nullable
    public String getString(
            @NonNull String stringId,
            @NonNull Supplier<String> defaultStringLoader) {

        Objects.requireNonNull(stringId, "stringId can't be null");
        Objects.requireNonNull(defaultStringLoader, "defaultStringLoader can't be null");

        if (stringId.equals(DevicePolicyResources.UNDEFINED) || DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_DEVICE_POLICY_MANAGER,
                DISABLE_RESOURCES_UPDATABILITY_FLAG,
                DEFAULT_DISABLE_RESOURCES_UPDATABILITY)) {
            return ParcelableResource.loadDefaultString(defaultStringLoader);
        }
        if (mService != null) {
            try {
                ParcelableResource resource = mService.getString(stringId);
                if (resource == null) {
                    return ParcelableResource.loadDefaultString(defaultStringLoader);
                }
                return resource.getString(mContext, defaultStringLoader);
            } catch (RemoteException e) {
                Log.e(
                        TAG,
                        "Error getting the updated string from DevicePolicyManagerService.",
                        e);
                return ParcelableResource.loadDefaultString(defaultStringLoader);
            }
        }
        return ParcelableResource.loadDefaultString(defaultStringLoader);
    }

    /**
     * Similar to {@link #getString(String, Supplier)} but accepts {@code formatArgs} and returns a
     * localized formatted string, substituting the format arguments as defined in
     * {@link java.util.Formatter} and {@link java.lang.String#format}, (see
     * {@link Resources#getString(int, Object...)}).
     *
     * <p>Calls to this API will not return {@code null} unless no updated drawable was found
     * and the call to {@code defaultStringLoader} returned {@code null}.
     *
     * @param stringId The IDs to get the updated resource for.
     * @param defaultStringLoader To get the default string if no updated string was set for
     *         {@code stringId}.
     * @param formatArgs The format arguments that will be used for substitution.
     */
    @Nullable
    @SuppressLint("SamShouldBeLast")
    public String getString(
            @NonNull String stringId,
            @NonNull Supplier<String> defaultStringLoader,
            @NonNull Object... formatArgs) {

        Objects.requireNonNull(stringId, "stringId can't be null");
        Objects.requireNonNull(defaultStringLoader, "defaultStringLoader can't be null");

        if (stringId.equals(DevicePolicyResources.UNDEFINED) || DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_DEVICE_POLICY_MANAGER,
                DISABLE_RESOURCES_UPDATABILITY_FLAG,
                DEFAULT_DISABLE_RESOURCES_UPDATABILITY)) {
            return ParcelableResource.loadDefaultString(defaultStringLoader);
        }
        if (mService != null) {
            try {
                ParcelableResource resource = mService.getString(stringId);
                if (resource == null) {
                    return ParcelableResource.loadDefaultString(defaultStringLoader);
                }
                return resource.getString(mContext, defaultStringLoader, formatArgs);
            } catch (RemoteException e) {
                Log.e(
                        TAG,
                        "Error getting the updated string from DevicePolicyManagerService.",
                        e);
                return ParcelableResource.loadDefaultString(defaultStringLoader);
            }
        }
        return ParcelableResource.loadDefaultString(defaultStringLoader);
    }
}
