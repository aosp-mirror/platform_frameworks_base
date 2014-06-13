package android.telecomm;

import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telecomm.ITelecommService;

import java.util.ArrayList;
import java.util.List;

/**
 * Class for managing the primary phone application that will receive incoming calls, and be allowed
 * to make emergency outgoing calls.
 *
 * @hide
 */
public class PhoneApplication {
    private static final String TAG = PhoneApplication.class.getSimpleName();
    private static final String TELECOMM_SERVICE_NAME = "telecomm";

    /**
     * Sets the specified package name as the default phone application. The caller of this method
     * needs to have permission to write to secure settings.
     *
     * @hide
     * */
    @SystemApi
    public static void setDefaultPhoneApplication(String packageName, Context context) {
        // Get old package name
        String oldPackageName = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.PHONE_DEFAULT_APPLICATION);

        if (packageName != null && oldPackageName != null && packageName.equals(oldPackageName)) {
            // No change
            return;
        }

        // Only make the change if the new package belongs to a valid phone application
        List<ComponentName> componentNames = getInstalledPhoneApplications(context);
        ComponentName foundComponent = null;
        for (ComponentName componentName : componentNames) {
            if (TextUtils.equals(componentName.getPackageName(), packageName)) {
                foundComponent = componentName;
                break;
            }
        }

        if (foundComponent != null) {
            // Update the secure setting.
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.PHONE_DEFAULT_APPLICATION, foundComponent.getPackageName());
        }
    }

    /**
     * Returns the installed phone application that will be used to receive incoming calls, and is
     * allowed to make emergency calls.
     *
     * The application will be returned in order of preference:
     * 1) User selected phone application (if still installed)
     * 2) Pre-installed system dialer (if not disabled)
     * 3) Null
     *
     * @hide
     * */
    @SystemApi
    public static ComponentName getDefaultPhoneApplication(Context context) {
        String defaultPackageName = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.PHONE_DEFAULT_APPLICATION);

        final List<ComponentName> componentNames = getInstalledPhoneApplications(context);
        if (!TextUtils.isEmpty(defaultPackageName)) {
            for (ComponentName componentName : componentNames) {
                if (TextUtils.equals(componentName.getPackageName(), defaultPackageName)) {
                    return componentName;
                }
            }
        }

        // No user-set dialer found, fallback to system dialer
        ComponentName systemDialer = null;
        try {
            systemDialer = getTelecommService().getSystemPhoneApplication();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#getSystemPhoneApplication", e);
            return null;
        }

        if (systemDialer == null) {
            // No system dialer configured at build time
            return null;
        }

        // Verify that the system dialer has not been disabled.
        return getComponentName(componentNames, systemDialer.getPackageName());
    }

    /**
     * Returns a list of installed and available phone applications.
     *
     * In order to appear in the list, a phone application must implement an intent-filter with
     * the DIAL intent for the following schemes:
     *
     * 1) Empty scheme
     * 2) tel Uri scheme
     *
     * @hide
     **/
    @SystemApi
    public static List<ComponentName> getInstalledPhoneApplications(Context context) {
        PackageManager packageManager = context.getPackageManager();

        // Get the list of apps registered for the DIAL intent with empty scheme
        Intent intent = new Intent(Intent.ACTION_DIAL);
        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(intent, 0);

        List<ComponentName> componentNames = new ArrayList<ComponentName> ();

        for (ResolveInfo resolveInfo : resolveInfoList) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            if (activityInfo == null) {
                continue;
            }
            final ComponentName componentName =
                    new ComponentName(activityInfo.packageName, activityInfo.name);
            componentNames.add(componentName);
        }

        // TODO: Filter for apps that don't handle DIAL intent with tel scheme
        return componentNames;
    }

    /**
     * Returns the {@link ComponentName} for the installed phone application for a given package
     * name.
     *
     * @param context A valid context.
     * @param packageName to retrieve the {@link ComponentName} for.
     *
     * @return The {@link ComponentName} for the installed phone application corresponding to the
     * package name, or null if none is found.
     *
     * @hide
     */
    @SystemApi
    public static ComponentName getPhoneApplicationForPackageName(Context context,
            String packageName) {
        return getComponentName(getInstalledPhoneApplications(context), packageName);
    }

    /**
     * Returns the component from a list of application components that corresponds to the package
     * name.
     *
     * @param componentNames A list of component names
     * @param packageName The package name to look for
     * @return The {@link ComponentName} that matches the provided packageName, or null if not
     *         found.
     */
    private static ComponentName getComponentName(List<ComponentName> componentNames,
            String packageName) {
        for (ComponentName componentName : componentNames) {
            if (TextUtils.equals(packageName, componentName.getPackageName())) {
                return componentName;
            }
        }
        return null;
    }

    private static ITelecommService getTelecommService() {
        return ITelecommService.Stub.asInterface(ServiceManager.getService(TELECOMM_SERVICE_NAME));
    }
}
