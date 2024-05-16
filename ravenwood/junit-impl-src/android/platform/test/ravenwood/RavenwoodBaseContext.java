/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.platform.test.ravenwood;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.view.Display;
import android.view.DisplayAdjustments;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A subclass of Context with all the abstract methods replaced with concrete methods.
 *
 * <p>In order to make sure it implements all the abstract methods, we intentionally keep it
 * non-abstract.
 */
public class RavenwoodBaseContext extends Context {
    RavenwoodBaseContext() {
        // Only usable by ravenwood.
    }

    private static RuntimeException notSupported() {
        return new RuntimeException("This Context API is not yet supported under"
                + " the Ravenwood deviceless testing environment. Contact g/ravenwood");
    }

    @Override
    public AssetManager getAssets() {
        throw notSupported();
    }

    @Override
    public Resources getResources() {
        throw notSupported();
    }

    @Override
    public PackageManager getPackageManager() {
        throw notSupported();
    }

    @Override
    public ContentResolver getContentResolver() {
        throw notSupported();
    }

    @Override
    public Looper getMainLooper() {
        throw notSupported();
    }

    @Override
    public Context getApplicationContext() {
        throw notSupported();
    }

    @Override
    public void setTheme(int resid) {
        throw notSupported();
    }

    @Override
    public Theme getTheme() {
        throw notSupported();
    }

    @Override
    public ClassLoader getClassLoader() {
        throw notSupported();
    }

    @Override
    public String getPackageName() {
        throw notSupported();
    }

    @Override
    public String getBasePackageName() {
        throw notSupported();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        throw notSupported();
    }

    @Override
    public String getPackageResourcePath() {
        throw notSupported();
    }

    @Override
    public String getPackageCodePath() {
        throw notSupported();
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        throw notSupported();
    }

    @Override
    public SharedPreferences getSharedPreferences(File file, int mode) {
        throw notSupported();
    }

    @Override
    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        throw notSupported();
    }

    @Override
    public boolean deleteSharedPreferences(String name) {
        throw notSupported();
    }

    @Override
    public void reloadSharedPreferences() {
        throw notSupported();
    }

    @Override
    public FileInputStream openFileInput(String name) throws FileNotFoundException {
        throw notSupported();
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        throw notSupported();
    }

    @Override
    public boolean deleteFile(String name) {
        throw notSupported();
    }

    @Override
    public File getFileStreamPath(String name) {
        throw notSupported();
    }

    @Override
    public File getSharedPreferencesPath(String name) {
        throw notSupported();
    }

    @Override
    public File getDataDir() {
        throw notSupported();
    }

    @Override
    public File getFilesDir() {
        throw notSupported();
    }

    @Override
    public File getNoBackupFilesDir() {
        throw notSupported();
    }

    @Override
    public File getExternalFilesDir(String type) {
        throw notSupported();
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        throw notSupported();
    }

    @Override
    public File getObbDir() {
        throw notSupported();
    }

    @Override
    public File[] getObbDirs() {
        throw notSupported();
    }

    @Override
    public File getCacheDir() {
        throw notSupported();
    }

    @Override
    public File getCodeCacheDir() {
        throw notSupported();
    }

    @Override
    public File getExternalCacheDir() {
        throw notSupported();
    }

    @Override
    public File getPreloadsFileCache() {
        throw notSupported();
    }

    @Override
    public File[] getExternalCacheDirs() {
        throw notSupported();
    }

    @Override
    public File[] getExternalMediaDirs() {
        throw notSupported();
    }

    @Override
    public String[] fileList() {
        throw notSupported();
    }

    @Override
    public File getDir(String name, int mode) {
        throw notSupported();
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory) {
        throw notSupported();
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory,
            DatabaseErrorHandler errorHandler) {
        throw notSupported();
    }

    @Override
    public boolean moveDatabaseFrom(Context sourceContext, String name) {
        throw notSupported();
    }

    @Override
    public boolean deleteDatabase(String name) {
        throw notSupported();
    }

    @Override
    public File getDatabasePath(String name) {
        throw notSupported();
    }

    @Override
    public String[] databaseList() {
        throw notSupported();
    }

    @Override
    public Drawable getWallpaper() {
        throw notSupported();
    }

    @Override
    public Drawable peekWallpaper() {
        throw notSupported();
    }

    @Override
    public int getWallpaperDesiredMinimumWidth() {
        throw notSupported();
    }

    @Override
    public int getWallpaperDesiredMinimumHeight() {
        throw notSupported();
    }

    @Override
    public void setWallpaper(Bitmap bitmap) throws IOException {
        throw notSupported();
    }

    @Override
    public void setWallpaper(InputStream data) throws IOException {
        throw notSupported();
    }

    @Override
    public void clearWallpaper() throws IOException {
        throw notSupported();
    }

    @Override
    public void startActivity(Intent intent) {
        throw notSupported();
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        throw notSupported();
    }

    @Override
    public void startActivities(Intent[] intents) {
        throw notSupported();
    }

    @Override
    public void startActivities(Intent[] intents, Bundle options) {
        throw notSupported();
    }

    @Override
    public void startIntentSender(IntentSender intent, Intent fillInIntent, int flagsMask,
            int flagsValues, int extraFlags) throws SendIntentException {
        throw notSupported();
    }

    @Override
    public void startIntentSender(IntentSender intent, Intent fillInIntent, int flagsMask,
            int flagsValues, int extraFlags, Bundle options) throws SendIntentException {
        throw notSupported();
    }

    @Override
    public void sendBroadcast(Intent intent) {
        throw notSupported();
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        throw notSupported();
    }

    @Override
    public void sendBroadcastAsUserMultiplePermissions(Intent intent, UserHandle user,
            String[] receiverPermissions) {
        throw notSupported();
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, int appOp) {
        throw notSupported();
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
        throw notSupported();
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        throw notSupported();
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission,
            int appOp, BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        throw notSupported();
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        throw notSupported();
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission) {
        throw notSupported();
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission,
            Bundle options) {
        throw notSupported();
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission,
            int appOp) {
        throw notSupported();
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, BroadcastReceiver resultReceiver, Handler scheduler,
            int initialCode, String initialData, Bundle initialExtras) {
        throw notSupported();
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        throw notSupported();
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, Bundle options,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        throw notSupported();
    }

    @Override
    public void sendStickyBroadcast(Intent intent) {
        throw notSupported();
    }

    @Override
    public void sendStickyOrderedBroadcast(Intent intent, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        throw notSupported();

    }

    @Override
    public void removeStickyBroadcast(Intent intent) {
        throw notSupported();

    }

    @Override
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {
        throw notSupported();
    }

    @Override
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user, Bundle options) {
        throw notSupported();

    }

    @Override
    public void sendStickyOrderedBroadcastAsUser(Intent intent, UserHandle user,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        throw notSupported();
    }

    @Override
    public void removeStickyBroadcastAsUser(Intent intent, UserHandle user) {
        throw notSupported();
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        throw notSupported();
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        throw notSupported();
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        throw notSupported();
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler, int flags) {
        throw notSupported();
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        throw notSupported();
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler, int flags) {
        throw notSupported();
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        throw notSupported();
    }

    @Override
    public ComponentName startService(Intent service) {
        throw notSupported();
    }

    @Override
    public ComponentName startForegroundService(Intent service) {
        throw notSupported();
    }

    @Override
    public ComponentName startForegroundServiceAsUser(Intent service, UserHandle user) {
        throw notSupported();
    }

    @Override
    public boolean stopService(Intent service) {
        throw notSupported();
    }

    @Override
    public ComponentName startServiceAsUser(Intent service, UserHandle user) {
        throw notSupported();
    }

    @Override
    public boolean stopServiceAsUser(Intent service, UserHandle user) {
        throw notSupported();
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        throw notSupported();
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        throw notSupported();
    }

    @Override
    public boolean startInstrumentation(ComponentName className, String profileFile,
            Bundle arguments) {
        throw notSupported();
    }

    @Override
    public Object getSystemService(String name) {
        throw notSupported();
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        throw notSupported();
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        throw notSupported();
    }

    @Override
    public int checkPermission(String permission, int pid, int uid, IBinder callerToken) {
        throw notSupported();
    }

    @Override
    public int checkCallingPermission(String permission) {
        throw notSupported();
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        throw notSupported();
    }

    @Override
    public int checkSelfPermission(String permission) {
        throw notSupported();
    }

    @Override
    public void enforcePermission(String permission, int pid, int uid, String message) {
        throw notSupported();
    }

    @Override
    public void enforceCallingPermission(String permission, String message) {
        throw notSupported();
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        throw notSupported();
    }

    @Override
    public void grantUriPermission(String toPackage, Uri uri, int modeFlags) {
        throw notSupported();
    }

    @Override
    public void revokeUriPermission(Uri uri, int modeFlags) {
        throw notSupported();
    }

    @Override
    public void revokeUriPermission(String toPackage, Uri uri, int modeFlags) {
        throw notSupported();
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        throw notSupported();
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags, IBinder callerToken) {
        throw notSupported();
    }

    @Override
    public int checkCallingUriPermission(Uri uri, int modeFlags) {
        throw notSupported();
    }

    @Override
    public int checkCallingOrSelfUriPermission(Uri uri, int modeFlags) {
        throw notSupported();
    }

    @Override
    public int checkUriPermission(Uri uri, String readPermission, String writePermission,
            int pid, int uid, int modeFlags) {
        throw notSupported();
    }

    @Override
    public void enforceUriPermission(Uri uri, int pid, int uid, int modeFlags, String message) {
        throw notSupported();
    }

    @Override
    public void enforceCallingUriPermission(Uri uri, int modeFlags, String message) {
        throw notSupported();
    }

    @Override
    public void enforceCallingOrSelfUriPermission(Uri uri, int modeFlags, String message) {
        throw notSupported();
    }

    @Override
    public void enforceUriPermission(Uri uri, String readPermission, String writePermission,
            int pid, int uid, int modeFlags, String message) {
        throw notSupported();
    }

    @Override
    public Context createPackageContext(String packageName, int flags)
            throws NameNotFoundException {
        throw notSupported();
    }

    @Override
    public Context createApplicationContext(ApplicationInfo application, int flags)
            throws NameNotFoundException {
        throw notSupported();
    }

    @Override
    public Context createContextForSplit(String splitName) throws NameNotFoundException {
        throw notSupported();
    }

    @Override
    public Context createConfigurationContext(Configuration overrideConfiguration) {
        throw notSupported();
    }

    @Override
    public Context createDisplayContext(Display display) {
        throw notSupported();
    }

    @Override
    public Context createDeviceProtectedStorageContext() {
        throw notSupported();
    }

    @Override
    public Context createCredentialProtectedStorageContext() {
        throw notSupported();
    }

    @Override
    public DisplayAdjustments getDisplayAdjustments(int displayId) {
        throw notSupported();
    }

    @Override
    public int getDisplayId() {
        throw notSupported();
    }

    @Override
    public void updateDisplay(int displayId) {
        throw notSupported();
    }

    @Override
    public boolean isDeviceProtectedStorage() {
        throw notSupported();
    }

    @Override
    public boolean isCredentialProtectedStorage() {
        throw notSupported();
    }

    @Override
    public boolean canLoadUnsafeResources() {
        throw notSupported();
    }
}
