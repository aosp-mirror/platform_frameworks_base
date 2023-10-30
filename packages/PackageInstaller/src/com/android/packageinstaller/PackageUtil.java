/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.packageinstaller;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * This is a utility class for defining some utility methods and constants
 * used in the package installer application.
 */
public class PackageUtil {
    private static final String LOG_TAG = PackageUtil.class.getSimpleName();

    public static final String PREFIX="com.android.packageinstaller.";
    public static final String INTENT_ATTR_INSTALL_STATUS = PREFIX+"installStatus";
    public static final String INTENT_ATTR_APPLICATION_INFO=PREFIX+"applicationInfo";
    public static final String INTENT_ATTR_PERMISSIONS_LIST=PREFIX+"PermissionsList";
    //intent attribute strings related to uninstall
    public static final String INTENT_ATTR_PACKAGE_NAME=PREFIX+"PackageName";

    /**
     * Utility method to get package information for a given {@link File}
     */
    @Nullable
    public static PackageInfo getPackageInfo(Context context, File sourceFile, int flags) {
        try {
            return context.getPackageManager().getPackageArchiveInfo(sourceFile.getAbsolutePath(),
                    flags);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static View initSnippet(View snippetView, CharSequence label, Drawable icon) {
        ((ImageView)snippetView.findViewById(R.id.app_icon)).setImageDrawable(icon);
        ((TextView)snippetView.findViewById(R.id.app_name)).setText(label);
        return snippetView;
    }

    /**
     * Utility method to display a snippet of an installed application.
     * The content view should have been set on context before invoking this method.
     * appSnippet view should include R.id.app_icon and R.id.app_name
     * defined on it.
     *
     * @param pContext context of package that can load the resources
     * @param componentInfo ComponentInfo object whose resources are to be loaded
     * @param snippetView the snippet view
     */
    public static View initSnippetForInstalledApp(Context pContext,
            ApplicationInfo appInfo, View snippetView) {
        return initSnippetForInstalledApp(pContext, appInfo, snippetView, null);
    }

    /**
     * Utility method to display a snippet of an installed application.
     * The content view should have been set on context before invoking this method.
     * appSnippet view should include R.id.app_icon and R.id.app_name
     * defined on it.
     *
     * @param pContext context of package that can load the resources
     * @param componentInfo ComponentInfo object whose resources are to be loaded
     * @param snippetView the snippet view
     * @param UserHandle user that the app si installed for.
     */
    public static View initSnippetForInstalledApp(Context pContext,
            ApplicationInfo appInfo, View snippetView, UserHandle user) {
        final PackageManager pm = pContext.getPackageManager();
        Drawable icon = appInfo.loadIcon(pm);
        if (user != null) {
            icon = pContext.getPackageManager().getUserBadgedIcon(icon, user);
        }
        return initSnippet(
                snippetView,
                appInfo.loadLabel(pm),
                icon);
    }

    static final class AppSnippet implements Parcelable {
        @NonNull public CharSequence label;
        @Nullable public Drawable icon;
        public int iconSize;

        public AppSnippet(@NonNull CharSequence label, @Nullable Drawable icon, Context context) {
            this.label = label;
            this.icon = icon;
            final ActivityManager am = context.getSystemService(ActivityManager.class);
            this.iconSize = am.getLauncherLargeIconSize();
        }

        private AppSnippet(Parcel in) {
            label = in.readString();
            Bitmap bmp = in.readParcelable(getClass().getClassLoader(), Bitmap.class);
            icon = new BitmapDrawable(Resources.getSystem(), bmp);
            iconSize = in.readInt();
        }

        @Override
        public String toString() {
            return "AppSnippet[" + label + (icon != null ? "(has" : "(no ") + " icon)]";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(label.toString());
            Bitmap bmp = getBitmapFromDrawable(icon);
            dest.writeParcelable(bmp, 0);
            dest.writeInt(iconSize);
        }

        private Bitmap getBitmapFromDrawable(Drawable drawable) {
            // Create an empty bitmap with the dimensions of our drawable
            final Bitmap bmp = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(),
                    Bitmap.Config.ARGB_8888);
            // Associate it with a canvas. This canvas will draw the icon on the bitmap
            final Canvas canvas = new Canvas(bmp);
            // Draw the drawable in the canvas. The canvas will ultimately paint the drawable in the
            // bitmap held within
            drawable.draw(canvas);

            // Scale it down if the icon is too large
            if ((bmp.getWidth() > iconSize * 2) || (bmp.getHeight() > iconSize * 2)) {
                Bitmap scaledBitmap = Bitmap.createScaledBitmap(bmp, iconSize, iconSize, true);
                if (scaledBitmap != bmp) {
                    bmp.recycle();
                }
                return scaledBitmap;
            }

            return bmp;
        }

        public static final Parcelable.Creator<AppSnippet> CREATOR = new Parcelable.Creator<>() {
            public AppSnippet createFromParcel(Parcel in) {
                return new AppSnippet(in);
            }

            public AppSnippet[] newArray(int size) {
                return new AppSnippet[size];
            }
        };
    }

    /**
     * Utility method to load application label
     *
     * @param pContext context of package that can load the resources
     * @param appInfo ApplicationInfo object of package whose resources are to be loaded
     * @param sourceFile File the package is in
     */
    public static AppSnippet getAppSnippet(
            Activity pContext, ApplicationInfo appInfo, File sourceFile) {
        final String archiveFilePath = sourceFile.getAbsolutePath();
        PackageManager pm = pContext.getPackageManager();
        appInfo.publicSourceDir = archiveFilePath;

        CharSequence label = null;
        // Try to load the label from the package's resources. If an app has not explicitly
        // specified any label, just use the package name.
        if (appInfo.labelRes != 0) {
            try {
                label = appInfo.loadLabel(pm);
            } catch (Resources.NotFoundException e) {
            }
        }
        if (label == null) {
            label = (appInfo.nonLocalizedLabel != null) ?
                    appInfo.nonLocalizedLabel : appInfo.packageName;
        }
        Drawable icon = null;
        // Try to load the icon from the package's resources. If an app has not explicitly
        // specified any resource, just use the default icon for now.
        try {
            if (appInfo.icon != 0) {
                try {
                    icon = appInfo.loadIcon(pm);
                } catch (Resources.NotFoundException e) {
                }
            }
            if (icon == null) {
                icon = pContext.getPackageManager().getDefaultActivityIcon();
            }
        } catch (OutOfMemoryError e) {
            Log.i(LOG_TAG, "Could not load app icon", e);
        }
        return new PackageUtil.AppSnippet(label, icon, pContext);
    }

    /**
     * Get the maximum target sdk for a UID.
     *
     * @param context The context to use
     * @param uid The UID requesting the install/uninstall
     *
     * @return The maximum target SDK or -1 if the uid does not match any packages.
     */
    static int getMaxTargetSdkVersionForUid(@NonNull Context context, int uid) {
        PackageManager pm = context.getPackageManager();
        final String[] packages = pm.getPackagesForUid(uid);
        int targetSdkVersion = -1;
        if (packages != null) {
            for (String packageName : packages) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                    targetSdkVersion = Math.max(targetSdkVersion, info.targetSdkVersion);
                } catch (PackageManager.NameNotFoundException e) {
                    // Ignore and try the next package
                }
            }
        }
        return targetSdkVersion;
    }


    /**
     * Quietly close a closeable resource (e.g. a stream or file). The input may already
     * be closed and it may even be null.
     */
    static void safeClose(Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (IOException ioe) {
                // Catch and discard the error
            }
        }
    }

    /**
     * A simple error dialog showing a message
     */
    public static class SimpleErrorDialog extends DialogFragment {
        private static final String MESSAGE_KEY =
                SimpleErrorDialog.class.getName() + "MESSAGE_KEY";

        static SimpleErrorDialog newInstance(@StringRes int message) {
            SimpleErrorDialog dialog = new SimpleErrorDialog();

            Bundle args = new Bundle();
            args.putInt(MESSAGE_KEY, message);
            dialog.setArguments(args);

            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(getActivity())
                    .setMessage(getArguments().getInt(MESSAGE_KEY))
                    .setPositiveButton(R.string.ok, (dialog, which) -> getActivity().finish())
                    .create();
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            getActivity().setResult(Activity.RESULT_CANCELED);
            getActivity().finish();
        }
    }
}
