/*
 * Copyright (C) 2014 The TeamEos Project
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
 * 
 * Helper functions mostly for device configuration and some utilities
 * including a fun ViewGroup crawler and dpi conversion
 * 
 */

package com.android.internal.util.hwkeys;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
//import android.content.res.ThemeConfig;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.util.Log;
import android.view.Display;
import android.provider.MediaStore;
import android.view.IWindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.hwkeys.ActionConstants.Defaults;
import com.android.internal.util.hwkeys.Config.ActionConfig;
import com.android.internal.util.hwkeys.Config.ButtonConfig;

import com.android.internal.statusbar.IStatusBarService;
import static android.content.Context.NOTIFICATION_SERVICE;
import static android.content.Context.VIBRATOR_SERVICE;

public final class ActionUtils {

    private static final String TAG = ActionUtils.class.getSimpleName();

    public static final String ANDROIDNS = "http://schemas.android.com/apk/res/android";
    public static final String PACKAGE_SYSTEMUI = "com.android.systemui";
    public static final String PACKAGE_ANDROID = "android";
    public static final String FORMAT_NONE = "none";
    public static final String FORMAT_FLOAT = "float";

    public static final String ID = "id";
    public static final String DIMEN = "dimen";
    public static final String DIMEN_PIXEL = "dimen_pixel";
    public static final String FLOAT = "float";
    public static final String INT = "integer";
    public static final String DRAWABLE = "drawable";
    public static final String COLOR = "color";
    public static final String BOOL = "bool";
    public static final String STRING = "string";
    public static final String ANIM = "anim";
    public static final String INTENT_SCREENSHOT = "action_take_screenshot";
    public static final String INTENT_REGION_SCREENSHOT = "action_take_region_screenshot";

    private static IStatusBarService mStatusBarService = null;

    private static IStatusBarService getStatusBarService() {
        synchronized (ActionUtils.class) {
            if (mStatusBarService == null) {
                mStatusBarService = IStatusBarService.Stub.asInterface(
                        ServiceManager.getService("statusbar"));
            }
            return mStatusBarService;
        }
    }

    // 10 inch tablets
    public static boolean isXLargeScreen() {
        int screenLayout = Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
        return screenLayout == Configuration.SCREENLAYOUT_SIZE_XLARGE;
    }

    // 7 inch "phablets" i.e. grouper
    public static boolean isLargeScreen() {
        int screenLayout = Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
        return screenLayout == Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    // normal phones
    public static boolean isNormalScreen() {
        int screenLayout = Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
        return screenLayout == Configuration.SCREENLAYOUT_SIZE_NORMAL;
    }

    public static boolean isLandscape(Context context) {
        return Configuration.ORIENTATION_LANDSCAPE
                == context.getResources().getConfiguration().orientation;
    }

    public static boolean navigationBarCanMove() {
        return Resources.getSystem().getConfiguration().smallestScreenWidthDp < 600;
    }

    public static boolean hasNavbarByDefault(Context context) {
        boolean needsNav = (Boolean)getValue(context, "config_showNavigationBar", BOOL, PACKAGE_ANDROID);
        String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
        if ("1".equals(navBarOverride)) {
            needsNav = false;
        } else if ("0".equals(navBarOverride)) {
            needsNav = true;
        }
        return needsNav;
    }

    public static boolean isHWKeysSupported(Context context) {
        return getInt(context, "config_deviceHardwareKeys", PACKAGE_ANDROID) != 64;
    }

    public static boolean deviceSupportsLte(Context ctx) {
        final TelephonyManager tm = (TelephonyManager)
                ctx.getSystemService(Context.TELEPHONY_SERVICE);
        return (tm.getLteOnCdmaMode(tm.getSubscriptionId()) == PhoneConstants.LTE_ON_CDMA_TRUE);
//                || tm.getLteOnGsmMode() != 0;
//        return tm.getLteOnCdmaModeStatic() == PhoneConstants.LTE_ON_CDMA_TRUE;
    }

    public static boolean deviceSupportsDdsSupported(Context context) {
        TelephonyManager tm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        return tm.isMultiSimEnabled()
                && tm.getMultiSimConfiguration() == TelephonyManager.MultiSimVariants.DSDA;
    }

    public static boolean deviceSupportsMobileData(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        Network[] networks = cm.getAllNetworks();
        for (int i = 0; i < networks.length; i++) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(networks[i]);
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return true;
             }
        }
        return false;
    }

    public static boolean deviceSupportsBluetooth() {
        return BluetoothAdapter.getDefaultAdapter() != null;
    }

    public static boolean deviceSupportsNfc(Context context) {
        PackageManager packageManager = context.getPackageManager();
        return packageManager.hasSystemFeature(PackageManager.FEATURE_NFC);
    }

    public static boolean deviceSupportsFlashLight(Context context) {
        CameraManager cameraManager = (CameraManager) context.getSystemService(
                Context.CAMERA_SERVICE);
        try {
            String[] ids = cameraManager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
                if (flashAvailable != null
                        && flashAvailable
                        && lensFacing != null
                        && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    return true;
                }
            }
        } catch (CameraAccessException | AssertionError e) {
            // Ignore
        }
        return false;
    }

    public static boolean deviceSupportsCompass(Context context) {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        return sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
                && sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null;
    }

    public static boolean deviceSupportsDoze(Context context) {
        String name = (String) getValue(context, "config_dozeComponent",
                STRING, PACKAGE_ANDROID);
        return !TextUtils.isEmpty(name);
    }

    // Launch camera
    public static void launchCamera(Context context) {
        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }

    // Launch voice search
    public static void launchVoiceSearch(Context context) {
        Intent intent = new Intent(Intent.ACTION_SEARCH_LONG_PRESS);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * This method converts dp unit to equivalent pixels, depending on device
     * density.
     * 
     * @param dp A value in dp (density independent pixels) unit. Which we need
     *            to convert into pixels
     * @param context Context to get resources and device specific display
     *            metrics
     * @return A float value to represent px equivalent to dp depending on
     *         device density
     */
    public static float convertDpToPixel(float dp, Context context) {
        Resources resources = context.getResources();
        DisplayMetrics metrics = resources.getDisplayMetrics();
        float px = dp * (metrics.densityDpi / 160f);
        return px;
    }

    public static int ConvertDpToPixelAsInt(float dp, Context context) {
        float px = convertDpToPixel(dp, context);
        if (px < 1)
            px = 1;
        return Math.round(px);
    }

    public static int ConvertDpToPixelAsInt(int dp, Context context) {
        float px = convertDpToPixel((float) dp, context);
        if (px < 1)
            px = 1;
        return Math.round(px);
    }

    /**
     * This method converts device specific pixels to density independent
     * pixels.
     * 
     * @param px A value in px (pixels) unit. Which we need to convert into db
     * @param context Context to get resources and device specific display
     *            metrics
     * @return A float value to represent dp equivalent to px value
     */
    public static float convertPixelsToDp(float px, Context context) {
        Resources resources = context.getResources();
        DisplayMetrics metrics = resources.getDisplayMetrics();
        float dp = px / (metrics.densityDpi / 160f);
        return dp;
    }

    public static int dpToPx(Context context, int dp) {
        return (int) ((dp * context.getResources().getDisplayMetrics().density) + 0.5);
    }

    public static int pxToDp(Context context, int px) {
        return (int) ((px / context.getResources().getDisplayMetrics().density) + 0.5);
    }

    /* utility to iterate a viewgroup and return a list of child views */
    public static ArrayList<View> getAllChildren(View v) {

        if (!(v instanceof ViewGroup)) {
            ArrayList<View> viewArrayList = new ArrayList<View>();
            viewArrayList.add(v);
            return viewArrayList;
        }

        ArrayList<View> result = new ArrayList<View>();

        ViewGroup vg = (ViewGroup) v;
        for (int i = 0; i < vg.getChildCount(); i++) {

            View child = vg.getChildAt(i);

            ArrayList<View> viewArrayList = new ArrayList<View>();
            viewArrayList.add(v);
            viewArrayList.addAll(getAllChildren(child));

            result.addAll(viewArrayList);
        }
        return result;
    }

    /* utility to iterate a viewgroup and return a list of child views of type */
    public static <T extends View> ArrayList<T> getAllChildren(View root, Class<T> returnType) {
        if (!(root instanceof ViewGroup)) {
            ArrayList<T> viewArrayList = new ArrayList<T>();
            try {
                viewArrayList.add(returnType.cast(root));
            } catch (Exception e) {
                // handle all exceptions the same and silently fail
            }
            return viewArrayList;
        }
        ArrayList<T> result = new ArrayList<T>();
        ViewGroup vg = (ViewGroup) root;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            ArrayList<T> viewArrayList = new ArrayList<T>();
            try {
                viewArrayList.add(returnType.cast(root));
            } catch (Exception e) {
                // handle all exceptions the same and silently fail
            }
            viewArrayList.addAll(getAllChildren(child, returnType));
            result.addAll(viewArrayList);
        }
        return result;
    }

    public static void resolveAndUpdateButtonActions(Context ctx, Defaults defaults) {
        if (ctx == null || defaults == null) {
            return;
        }
        boolean configChanged = false;
        final PackageManager pm = ctx.getPackageManager();
        ArrayList<ButtonConfig> configs = Config.getConfig(ctx, defaults);
        ArrayList<ButtonConfig> buttonsToChange = new ArrayList<ButtonConfig>();
        buttonsToChange.addAll(configs);
        for (int h = 0; h < configs.size(); h++) {
            ButtonConfig button = configs.get(h);
            for (int i = 0; i < 3; i++) {
                ActionConfig action = button.getActionConfig(i);
                final String task = action.getAction();
                if (task.startsWith(ActionHandler.SYSTEM_PREFIX)) {
                    continue;
                }
                String resolvedName = getFriendlyNameForUri(ctx, task);
                if (resolvedName == null || TextUtils.equals(resolvedName, task)) {
                    // if resolved name is null or the full raw intent string is
                    // returned, we were unable to resolve
                    configChanged = true;
                    ActionConfig newAction = new ActionConfig(ctx,
                            ActionHandler.SYSTEMUI_TASK_NO_ACTION, action.getIconUri());
                    ButtonConfig newButton = buttonsToChange.get(h);
                    newButton.setActionConfig(newAction, i);
                    buttonsToChange.remove(h);
                    buttonsToChange.add(h, newButton);
                }
            }
        }
        if (configChanged) {
            Config.setConfig(ctx, defaults, buttonsToChange);
        }
    }

    public static Intent getIntent(String uri) {
        if (uri == null || uri.startsWith(ActionHandler.SYSTEM_PREFIX)) {
            return null;
        }

        Intent intent = null;
        try {
            intent = Intent.parseUri(uri, 0);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return intent;
    }

    public static Object getValue(Context context, String resName, String resType, String pkg) {
        return getValue(context, resName, resType, null, pkg);
    }

    public static Object getValue(Context context, String resName, String resType, String format,
            String pkg) {
        Resources res = getResourcesForPackage(context, pkg);
        String tmp;
        if (resType.equals(DIMEN_PIXEL)) {
            tmp = DIMEN;
        } else {
            tmp = resType;
        }
        int id = res.getIdentifier(resName, tmp, pkg);
        if (format != null) { // standard res
            TypedValue typedVal = new TypedValue();
            res.getValue(id, typedVal, true);
            if (format.equals(FORMAT_FLOAT)) {
                return Float.valueOf(typedVal.getFloat());
            }
        } else { // typed values
            if (resType.equals(ID)) {
                return Integer.valueOf(id);
            } else if (resType.equals(DIMEN)) {
                return Float.valueOf(res.getDimension(id));
            } else if (resType.equals(DIMEN_PIXEL)) {
                return Integer.valueOf(res.getDimensionPixelSize(id));
            } else if (resType.equals(FLOAT)) {
                return Float.valueOf(res.getFloat(id));
            } else if (resType.equals(INT)) {
                return Integer.valueOf(res.getInteger(id));
            } else if (resType.equals(COLOR)) {
                int rawColor = res.getColor(id);
                return Integer.valueOf(Color.argb(Color.alpha(rawColor), Color.red(rawColor),
                        Color.green(rawColor), Color.blue(rawColor)));
            } else if (resType.equals(BOOL)) {
                return Boolean.valueOf(res.getBoolean(id));
            } else if (resType.equals(STRING)) {
                return String.valueOf(res.getString(id));
            } else if (resType.equals(DRAWABLE)) {
                return getDrawable(context, resName, pkg);
            }
        }
        return null;
    }

    public static void putValue(String key, Object val, String type, Bundle b) {
        if (type.equals(ID) || type.equals(DIMEN_PIXEL) || type.equals(INT) || type.equals(COLOR)) {
            b.putInt(key, (Integer) val);
        } else if (type.equals(FLOAT) || type.equals(DIMEN)) {
            b.putFloat(key, (Float) val);
        } else if (type.equals(BOOL)) {
            b.putBoolean(key, (Boolean) val);
        } else if (type.equals(STRING)) {
            b.putString(key, (String) val);
        }
    }

    public static int getIdentifier(Context context, String resName, String resType, String pkg) {
        try {
            Resources res = context.getPackageManager()
                    .getResourcesForApplication(pkg);
            int ident = res.getIdentifier(resName, resType, pkg);
            return ident;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String getString(Context context, String resName, String pkg) {
        return (String) getValue(context, resName, STRING, null, pkg);
    }

    public static boolean getBoolean(Context context, String resName, String pkg) {
        return (Boolean) getValue(context, resName, BOOL, null, pkg);
    }

    public static int getInt(Context context, String resName, String pkg) {
        return (Integer) getValue(context, resName, INT, null, pkg);
    }

    public static int getColor(Context context, String resName, String pkg) {        
        return (Integer) getValue(context, resName, COLOR, null, pkg);
    }

    public static int getId(Context context, String resName, String pkg) {
        return (Integer) getValue(context, resName, ID, null, pkg);
    }

    public static float getDimen(Context context, String resName, String pkg) {
        return (Float) getValue(context, resName, DIMEN, null, pkg);
    }

    public static int getDimenPixelSize(Context context, String resName, String pkg) {
        return (Integer) getValue(context, resName, DIMEN_PIXEL, null, pkg);
    }

    public static Drawable getDrawable(Context context, String drawableName, String pkg) {
        return getDrawable(getResourcesForPackage(context, pkg), drawableName, pkg);
    }

    public static Drawable getDrawable(Context context, Uri uri) {
        //set inputs here so we can clean up them in the finally
        InputStream inputStream = null;

        try {
          //get the inputstream
          inputStream = context.getContentResolver().openInputStream(uri);

          //get available bitmapfactory options
          BitmapFactory.Options options = new BitmapFactory.Options();
          //query the bitmap to decode the stream but don't allocate pixels in memory yet
          options.inJustDecodeBounds = true;
          //decode the bitmap with calculated bounds
          Bitmap b1 = BitmapFactory.decodeStream(inputStream, null, options);
          //get raw height and width of the bitmap
          int rawHeight = options.outHeight;
          int rawWidth = options.outWidth;

          //check if the bitmap is big and we need to scale the quality to take less memory
          options.inSampleSize = calculateInSampleSize(options, rawHeight, rawWidth);

          //We need to close and load again the inputstream to avoid null
          try {
              inputStream.close();
          }
          catch (IOException e) {
              e.printStackTrace();
          }
          inputStream = context.getContentResolver().openInputStream(uri);

          //decode the stream again, with the calculated SampleSize option,
          //and allocate the memory. Also add some metrics options to take a proper density
          options.inJustDecodeBounds = false;
          DisplayMetrics metrics = context.getResources().getDisplayMetrics();
          options.inScreenDensity = metrics.densityDpi;
          options.inTargetDensity = metrics.densityDpi;
          options.inDensity = DisplayMetrics.DENSITY_DEFAULT;
          b1 = BitmapFactory.decodeStream(inputStream, null, options);
          return new BitmapDrawable(context.getResources(), b1);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        //clean up the system resources
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    //Automate the quality scaling process
    public static int calculateInSampleSize(BitmapFactory.Options options, int height, int width) {
        //set default inSampleSize scale factor (no scaling)
        int inSampleSize = 1;

        //if img size is in 257-512 range, sample scale factor will be 4x
        if (height > 256 || width > 256) {
            inSampleSize = 4;
            return inSampleSize;
        //if img size is in 129-256 range, sample scale factor will be 2x
        } else if (height > 128 || width > 128) {
            inSampleSize = 2;
            return inSampleSize;
        }
        //if img size is in 0-128 range, no need to scale it
        return inSampleSize;
    }

    /**
     * Screen images based on desired dimensions before fully decoding
     *
     *@param ctx Calling context
     *@param uri Image uri
     *@param maxWidth maximum allowed image width
     *@param maxHeight maximum allowed image height
     */
    public static boolean isBitmapAllowedSize(Context ctx, Uri uri, int maxWidth, int maxHeight) {
        InputStream inputStream = null;
        try {
            inputStream = ctx.getContentResolver().openInputStream(uri);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(inputStream, null, options);
            if (options.outWidth <= maxWidth && options.outHeight <= maxHeight) {
                return true;
            }
        } catch (Exception e) {
            return false;
        } finally {
            try {
                inputStream.close();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    public static Drawable getDrawableFromComponent(PackageManager pm, String activity) {
        Drawable d = null;
        try {
            Intent intent = Intent.parseUri(activity, 0);
            ActivityInfo info = intent.resolveActivityInfo(pm,
                    PackageManager.GET_ACTIVITIES);
            if (info != null) {
                d = info.loadIcon(pm);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return d;
    }

    public static String getFriendlyActivityName(PackageManager pm, Intent intent,
            boolean labelOnly) {
        ActivityInfo ai = intent.resolveActivityInfo(pm, PackageManager.GET_ACTIVITIES);
        String friendlyName = null;
        if (ai != null) {
            friendlyName = ai.loadLabel(pm).toString();
            if (friendlyName == null && !labelOnly) {
                friendlyName = ai.name;
            }
        }
        return friendlyName != null || labelOnly ? friendlyName : intent.toUri(0);
    }

    public static String getFriendlyShortcutName(PackageManager pm, Intent intent) {
        String activityName = getFriendlyActivityName(pm, intent, true);
        String name = intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);

        if (activityName != null && name != null) {
            return activityName + ": " + name;
        }
        return name != null ? name : intent.toUri(0);
    }

    public static String getFriendlyNameForUri(Context ctx, String uri) {
        if (uri == null) {
            return null;
        }
        if (uri.startsWith(ActionHandler.SYSTEM_PREFIX)) {
            for (int i = 0; i < ActionHandler.systemActions.length; i++) {
                if (ActionHandler.systemActions[i].mAction.equals(uri)) {
                    return getString(ctx, ActionHandler.systemActions[i].mLabelRes,
                            ActionHandler.systemActions[i].mResPackage);
                }
            }
        } else {
            try {
                Intent intent = Intent.parseUri(uri, 0);
                if (Intent.ACTION_MAIN.equals(intent.getAction())) {
                    return getFriendlyActivityName(ctx.getPackageManager(), intent, false);
                }
                return getFriendlyShortcutName(ctx.getPackageManager(), intent);
            } catch (URISyntaxException e) {
            }
        }
        return uri;
    }

    /**
     * 
     * @param Target package resources
     * @param drawableName
     * @param Target package name
     * @return the drawable if found, otherwise fall back to a green android guy
     */
    public static Drawable getDrawable(Resources res, String drawableName, String pkg) {
        try {
            int resId = res.getIdentifier(drawableName, DRAWABLE, pkg);
            Drawable icon = ImageHelper.getVector(res, resId, false);
            if (icon == null) {
                icon = res.getDrawable(resId);
            }
            return icon;
        } catch (Exception e) {
            return res.getDrawable(
                    com.android.internal.R.drawable.sym_def_app_icon);
        }
    }

    /**
     * 
     * @param Target package resources
     * @param drawableName
     * @param Target package name
     * @return the drawable if found, null otherwise. Useful for testing if a drawable is found
     *         in a theme overlay
     */
    private static Drawable getMaybeNullDrawable(Resources res, String drawableName, String pkg) {
        try {
            int resId = res.getIdentifier(drawableName, DRAWABLE, pkg);
            Drawable icon = ImageHelper.getVector(res, resId, false);
            if (icon == null) {
                icon = res.getDrawable(resId);
            }
            return icon;
        } catch (Exception e) {
            return null;
        }
    }

    public static Resources getResourcesForPackage(Context ctx, String pkg) {
        try {
            Resources res = ctx.getPackageManager()
                    .getResourcesForApplication(pkg);
            return res;
        } catch (Exception e) {
            return ctx.getResources();
        }
    }

    /**
     * 
     * @param Context of the calling package
     * @param the action we want a drawable for
     * @return if a system action drawable is requested, we try to get the drawable
     *         from any current navigation overlay. if no overlay is found, get it
     *         from SystemUI. Return a component drawable if not a system action
     */
    public static Drawable getDrawableForAction(Context context, String action) {
        Drawable d = null;

        // this null check is probably no-op but let's be safe anyways
        if (action == null || context == null) {
            return d;
        }
        if (action.startsWith(ActionHandler.SYSTEM_PREFIX)) {
            for (int i = 0; i < ActionHandler.systemActions.length; i++) {
                if (ActionHandler.systemActions[i].mAction.equals(action)) {
                    // should always be SystemUI
                    String packageName = ActionHandler.systemActions[i].mResPackage;
                    Resources res = getResourcesForPackage(context, packageName);
                    String iconName = ActionHandler.systemActions[i].mIconRes;
                    d = getNavbarThemedDrawable(context, res, iconName);
                    if (d == null) {
                        d = getDrawable(res, iconName, packageName);
                    }
                }
            }
        } else {
            d = getDrawableFromComponent(context.getPackageManager(), action);
        }
        return d;
    }

    /**
     * 
     * @param calling package context, usually Settings for the custom action list adapter
     * @param target package resources, usually SystemUI
     * @param drawableName
     * @return a navigation bar overlay themed action drawable if available, otherwise
     *         return drawable from SystemUI resources
     */
    public static Drawable getNavbarThemedDrawable(Context context, Resources defRes,
            String drawableName) {
        if (context == null || defRes == null || drawableName == null)
            return null;

        // TODO: turn on cmte support when it comes back
        return getDrawable(defRes, drawableName, PACKAGE_SYSTEMUI);
/*
        ThemeConfig themeConfig = context.getResources().getConfiguration().themeConfig;

        Drawable d = null;
        if (themeConfig != null) {
            try {
                final String navbarThemePkgName = themeConfig.getOverlayForNavBar();
                final String sysuiThemePkgName = themeConfig.getOverlayForStatusBar();
                // Check if the same theme is applied for systemui, if so we can skip this
                if (navbarThemePkgName != null && !navbarThemePkgName.equals(sysuiThemePkgName)) {
                    // Navbar theme and SystemUI (statusbar) theme packages are different
                    // But we can't assume navbar package has our drawable, so try navbar theme
                    // package first. If we fail, try the systemui (statusbar) package
                    // if we still fail, fall back to default package resource
                    Resources res = context.getPackageManager().getThemedResourcesForApplication(
                            PACKAGE_SYSTEMUI, navbarThemePkgName);
                    d = getMaybeNullDrawable(res, drawableName, PACKAGE_SYSTEMUI);
                    if (d == null) {
                        // drawable not found in overlay, get from default SystemUI res
                        d = getDrawable(defRes, drawableName, PACKAGE_SYSTEMUI);
                    }
                } else {
                    // no navbar overlay present, get from default SystemUI res
                    d = getDrawable(defRes, drawableName, PACKAGE_SYSTEMUI);
                }
            } catch (PackageManager.NameNotFoundException e) {
                // error thrown (unlikely), get from default SystemUI res
                d = getDrawable(defRes, drawableName, PACKAGE_SYSTEMUI);
            }
        }
        if (d == null) {
            // theme config likely null, get from default SystemUI res
            d = getDrawable(defRes, drawableName, PACKAGE_SYSTEMUI);
        }
        return d;
        */
    }

    // Screen off
    public static void switchScreenOff(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm!= null && pm.isScreenOn()) {
            pm.goToSleep(SystemClock.uptimeMillis());
        }
    }

    // Screen on
    public static void switchScreenOn(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        pm.wakeUp(SystemClock.uptimeMillis(), "com.android.systemui:CAMERA_GESTURE_PREVENT_LOCK");
    }

    // Volume panel
    public static void toggleVolumePanel(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        am.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
    }

    // Toggle flashlight
    public static void toggleCameraFlash() {
        IStatusBarService service = getStatusBarService();
        if (service != null) {
            try {
                service.toggleCameraFlash();
            } catch (RemoteException e) {
                // do nothing.
            }
        }
    }

    // Clear notifications
    public static void clearAllNotifications() {
        IStatusBarService service = getStatusBarService();
        if (service != null) {
            try {
                service.onClearAllNotifications(ActivityManager.getCurrentUser());
            } catch (RemoteException e) {
                // do nothing.
            }
        }
    }

    // Toggle notifications panel
    public static void toggleNotifications() {
        IStatusBarService service = getStatusBarService();
        if (service != null) {
            try {
                service.togglePanel();
            } catch (RemoteException e) {
                // do nothing.
            }
        }
    }

    // Toggle qs panel
    public static void toggleQsPanel() {
        IStatusBarService service = getStatusBarService();
        if (service != null) {
            try {
                service.toggleSettingsPanel();
            } catch (RemoteException e) {
                // do nothing.
            }
        }
    }

    // Cycle ringer modes
    public static void toggleRingerModes (Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        Vibrator mVibrator = (Vibrator) context.getSystemService(VIBRATOR_SERVICE);

        switch (am.getRingerMode()) {
            case AudioManager.RINGER_MODE_NORMAL:
                if (mVibrator.hasVibrator()) {
                    am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                }
                break;
            case AudioManager.RINGER_MODE_VIBRATE:
                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                NotificationManager notificationManager =
                        (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
                notificationManager.setInterruptionFilter(
                        NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                break;
            case AudioManager.RINGER_MODE_SILENT:
                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                break;
        }
    }

    // Switch to last app
    public static void switchToLastApp(Context context) {
        final ActivityManager am =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.RunningTaskInfo lastTask = getLastTask(context, am);

        if (lastTask != null) {
            am.moveTaskToFront(lastTask.id, ActivityManager.MOVE_TASK_NO_USER_ACTION,
                    getAnimation(context).toBundle());
        }
    }

    private static ActivityOptions getAnimation(Context context) {
        return ActivityOptions.makeCustomAnimation(context,
                com.android.internal.R.anim.custom_app_in,
                com.android.internal.R.anim.custom_app_out);
    }

    private static ActivityManager.RunningTaskInfo getLastTask(Context context,
            final ActivityManager am) {
        final List<String> packageNames = getCurrentLauncherPackages(context);
        final List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(5);
        for (int i = 1; i < tasks.size(); i++) {
            String packageName = tasks.get(i).topActivity.getPackageName();
            if (!packageName.equals(context.getPackageName())
                    && !packageName.equals(PACKAGE_SYSTEMUI)
                    && !packageNames.contains(packageName)) {
                return tasks.get(i);
            }
        }
        return null;
    }

    private static List<String> getCurrentLauncherPackages(Context context) {
        final PackageManager pm = context.getPackageManager();
        final List<ResolveInfo> homeActivities = new ArrayList<>();
        pm.getHomeActivities(homeActivities);
        final List<String> packageNames = new ArrayList<>();
        for (ResolveInfo info : homeActivities) {
            final String name = info.activityInfo.packageName;
            if (!name.equals("com.android.settings")) {
                packageNames.add(name);
            }
        }
        return packageNames;
    }
}
