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

package android.app;

import com.android.internal.policy.PolicyManager;
import com.android.internal.util.Preconditions;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IIntentReceiver;
import android.content.IntentSender;
import android.content.ReceiverCallNotAllowedException;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.hardware.ISerialManager;
import android.hardware.SensorManager;
import android.hardware.SerialManager;
import android.hardware.SystemSensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManager;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbManager;
import android.location.CountryDetector;
import android.location.ICountryDetector;
import android.location.ILocationManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.INetworkPolicyManager;
import android.net.NetworkPolicyManager;
import android.net.ThrottleManager;
import android.net.IThrottleManager;
import android.net.Uri;
import android.net.nsd.INsdManager;
import android.net.nsd.NsdManager;
import android.net.wifi.IWifiManager;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.IWifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.nfc.NfcManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IUserManager;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.SystemVibrator;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.telephony.TelephonyManager;
import android.content.ClipboardManager;
import android.util.AndroidRuntimeException;
import android.util.Log;
import android.util.Slog;
import android.view.CompatibilityInfoHolder;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.WindowManagerImpl;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;
import android.view.textservice.TextServicesManager;
import android.accounts.AccountManager;
import android.accounts.IAccountManager;
import android.app.admin.DevicePolicyManager;
import com.android.internal.os.IDropBoxManagerService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;

class ReceiverRestrictedContext extends ContextWrapper {
    ReceiverRestrictedContext(Context base) {
        super(base);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return registerReceiver(receiver, filter, null, null);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        if (receiver == null) {
            // Allow retrieving current sticky broadcast; this is safe since we
            // aren't actually registering a receiver.
            return super.registerReceiver(null, filter, broadcastPermission, scheduler);
        } else {
            throw new ReceiverCallNotAllowedException(
                    "BroadcastReceiver components are not allowed to register to receive intents");
        }
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        if (receiver == null) {
            // Allow retrieving current sticky broadcast; this is safe since we
            // aren't actually registering a receiver.
            return super.registerReceiverAsUser(null, user, filter, broadcastPermission, scheduler);
        } else {
            throw new ReceiverCallNotAllowedException(
                    "BroadcastReceiver components are not allowed to register to receive intents");
        }
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        throw new ReceiverCallNotAllowedException(
                "BroadcastReceiver components are not allowed to bind to services");
    }
}

/**
 * Common implementation of Context API, which provides the base
 * context object for Activity and other application components.
 */
class ContextImpl extends Context {
    private final static String TAG = "ApplicationContext";
    private final static boolean DEBUG = false;

    private static final HashMap<String, SharedPreferencesImpl> sSharedPrefs =
            new HashMap<String, SharedPreferencesImpl>();

    /*package*/ LoadedApk mPackageInfo;
    private String mBasePackageName;
    private Resources mResources;
    /*package*/ ActivityThread mMainThread;
    private Context mOuterContext;
    private IBinder mActivityToken = null;
    private ApplicationContentResolver mContentResolver;
    private int mThemeResource = 0;
    private Resources.Theme mTheme = null;
    private PackageManager mPackageManager;
    private Display mDisplay; // may be null if default display
    private Context mReceiverRestrictedContext = null;
    private boolean mRestricted;
    private UserHandle mUser;

    private final Object mSync = new Object();

    private File mDatabasesDir;
    private File mPreferencesDir;
    private File mFilesDir;
    private File mCacheDir;
    private File mObbDir;
    private File mExternalFilesDir;
    private File mExternalCacheDir;

    private static final String[] EMPTY_FILE_LIST = {};

    /**
     * Override this class when the system service constructor needs a
     * ContextImpl.  Else, use StaticServiceFetcher below.
     */
    /*package*/ static class ServiceFetcher {
        int mContextCacheIndex = -1;

        /**
         * Main entrypoint; only override if you don't need caching.
         */
        public Object getService(ContextImpl ctx) {
            ArrayList<Object> cache = ctx.mServiceCache;
            Object service;
            synchronized (cache) {
                if (cache.size() == 0) {
                    // Initialize the cache vector on first access.
                    // At this point sNextPerContextServiceCacheIndex
                    // is the number of potential services that are
                    // cached per-Context.
                    for (int i = 0; i < sNextPerContextServiceCacheIndex; i++) {
                        cache.add(null);
                    }
                } else {
                    service = cache.get(mContextCacheIndex);
                    if (service != null) {
                        return service;
                    }
                }
                service = createService(ctx);
                cache.set(mContextCacheIndex, service);
                return service;
            }
        }

        /**
         * Override this to create a new per-Context instance of the
         * service.  getService() will handle locking and caching.
         */
        public Object createService(ContextImpl ctx) {
            throw new RuntimeException("Not implemented");
        }
    }

    /**
     * Override this class for services to be cached process-wide.
     */
    abstract static class StaticServiceFetcher extends ServiceFetcher {
        private Object mCachedInstance;

        @Override
        public final Object getService(ContextImpl unused) {
            synchronized (StaticServiceFetcher.this) {
                Object service = mCachedInstance;
                if (service != null) {
                    return service;
                }
                return mCachedInstance = createStaticService();
            }
        }

        public abstract Object createStaticService();
    }

    private static final HashMap<String, ServiceFetcher> SYSTEM_SERVICE_MAP =
            new HashMap<String, ServiceFetcher>();

    private static int sNextPerContextServiceCacheIndex = 0;
    private static void registerService(String serviceName, ServiceFetcher fetcher) {
        if (!(fetcher instanceof StaticServiceFetcher)) {
            fetcher.mContextCacheIndex = sNextPerContextServiceCacheIndex++;
        }
        SYSTEM_SERVICE_MAP.put(serviceName, fetcher);
    }

    // This one's defined separately and given a variable name so it
    // can be re-used by getWallpaperManager(), avoiding a HashMap
    // lookup.
    private static ServiceFetcher WALLPAPER_FETCHER = new ServiceFetcher() {
            public Object createService(ContextImpl ctx) {
                return new WallpaperManager(ctx.getOuterContext(),
                        ctx.mMainThread.getHandler());
            }};

    static {
        registerService(ACCESSIBILITY_SERVICE, new ServiceFetcher() {
                public Object getService(ContextImpl ctx) {
                    return AccessibilityManager.getInstance(ctx);
                }});

        registerService(ACCOUNT_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    IBinder b = ServiceManager.getService(ACCOUNT_SERVICE);
                    IAccountManager service = IAccountManager.Stub.asInterface(b);
                    return new AccountManager(ctx, service);
                }});

        registerService(ACTIVITY_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new ActivityManager(ctx.getOuterContext(), ctx.mMainThread.getHandler());
                }});

        registerService(ALARM_SERVICE, new StaticServiceFetcher() {
                public Object createStaticService() {
                    IBinder b = ServiceManager.getService(ALARM_SERVICE);
                    IAlarmManager service = IAlarmManager.Stub.asInterface(b);
                    return new AlarmManager(service);
                }});

        registerService(AUDIO_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new AudioManager(ctx);
                }});

        registerService(MEDIA_ROUTER_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new MediaRouter(ctx);
                }});

        registerService(BLUETOOTH_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return BluetoothAdapter.getDefaultAdapter();
                }});

        registerService(CLIPBOARD_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new ClipboardManager(ctx.getOuterContext(),
                            ctx.mMainThread.getHandler());
                }});

        registerService(CONNECTIVITY_SERVICE, new StaticServiceFetcher() {
                public Object createStaticService() {
                    IBinder b = ServiceManager.getService(CONNECTIVITY_SERVICE);
                    return new ConnectivityManager(IConnectivityManager.Stub.asInterface(b));
                }});

        registerService(COUNTRY_DETECTOR, new StaticServiceFetcher() {
                public Object createStaticService() {
                    IBinder b = ServiceManager.getService(COUNTRY_DETECTOR);
                    return new CountryDetector(ICountryDetector.Stub.asInterface(b));
                }});

        registerService(DEVICE_POLICY_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return DevicePolicyManager.create(ctx, ctx.mMainThread.getHandler());
                }});

        registerService(DOWNLOAD_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new DownloadManager(ctx.getContentResolver(), ctx.getPackageName());
                }});

        registerService(NFC_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new NfcManager(ctx);
                }});

        registerService(DROPBOX_SERVICE, new StaticServiceFetcher() {
                public Object createStaticService() {
                    return createDropBoxManager();
                }});

        registerService(INPUT_SERVICE, new StaticServiceFetcher() {
                public Object createStaticService() {
                    return InputManager.getInstance();
                }});

        registerService(DISPLAY_SERVICE, new ServiceFetcher() {
                @Override
                public Object createService(ContextImpl ctx) {
                    return new DisplayManager(ctx.getOuterContext());
                }});

        registerService(INPUT_METHOD_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return InputMethodManager.getInstance(ctx);
                }});

        registerService(TEXT_SERVICES_MANAGER_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return TextServicesManager.getInstance();
                }});

        registerService(KEYGUARD_SERVICE, new ServiceFetcher() {
                public Object getService(ContextImpl ctx) {
                    // TODO: why isn't this caching it?  It wasn't
                    // before, so I'm preserving the old behavior and
                    // using getService(), instead of createService()
                    // which would do the caching.
                    return new KeyguardManager();
                }});

        registerService(LAYOUT_INFLATER_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return PolicyManager.makeNewLayoutInflater(ctx.getOuterContext());
                }});

        registerService(LOCATION_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    IBinder b = ServiceManager.getService(LOCATION_SERVICE);
                    return new LocationManager(ctx, ILocationManager.Stub.asInterface(b));
                }});

        registerService(NETWORK_POLICY_SERVICE, new ServiceFetcher() {
            @Override
            public Object createService(ContextImpl ctx) {
                return new NetworkPolicyManager(INetworkPolicyManager.Stub.asInterface(
                        ServiceManager.getService(NETWORK_POLICY_SERVICE)));
            }
        });

        registerService(NOTIFICATION_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    final Context outerContext = ctx.getOuterContext();
                    return new NotificationManager(
                        new ContextThemeWrapper(outerContext,
                                Resources.selectSystemTheme(0,
                                        outerContext.getApplicationInfo().targetSdkVersion,
                                        com.android.internal.R.style.Theme_Dialog,
                                        com.android.internal.R.style.Theme_Holo_Dialog,
                                        com.android.internal.R.style.Theme_DeviceDefault_Dialog)),
                        ctx.mMainThread.getHandler());
                }});

        registerService(NSD_SERVICE, new ServiceFetcher() {
                @Override
                public Object createService(ContextImpl ctx) {
                    IBinder b = ServiceManager.getService(NSD_SERVICE);
                    INsdManager service = INsdManager.Stub.asInterface(b);
                    return new NsdManager(ctx.getOuterContext(), service);
                }});

        // Note: this was previously cached in a static variable, but
        // constructed using mMainThread.getHandler(), so converting
        // it to be a regular Context-cached service...
        registerService(POWER_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    IBinder b = ServiceManager.getService(POWER_SERVICE);
                    IPowerManager service = IPowerManager.Stub.asInterface(b);
                    return new PowerManager(ctx.getOuterContext(),
                            service, ctx.mMainThread.getHandler());
                }});

        registerService(SEARCH_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new SearchManager(ctx.getOuterContext(),
                            ctx.mMainThread.getHandler());
                }});

        registerService(SENSOR_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new SystemSensorManager(ctx.mMainThread.getHandler().getLooper());
                }});

        registerService(STATUS_BAR_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new StatusBarManager(ctx.getOuterContext());
                }});

        registerService(STORAGE_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    try {
                        return new StorageManager(ctx.mMainThread.getHandler().getLooper());
                    } catch (RemoteException rex) {
                        Log.e(TAG, "Failed to create StorageManager", rex);
                        return null;
                    }
                }});

        registerService(TELEPHONY_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new TelephonyManager(ctx.getOuterContext());
                }});

        registerService(THROTTLE_SERVICE, new StaticServiceFetcher() {
                public Object createStaticService() {
                    IBinder b = ServiceManager.getService(THROTTLE_SERVICE);
                    return new ThrottleManager(IThrottleManager.Stub.asInterface(b));
                }});

        registerService(UI_MODE_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new UiModeManager();
                }});

        registerService(USB_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    IBinder b = ServiceManager.getService(USB_SERVICE);
                    return new UsbManager(ctx, IUsbManager.Stub.asInterface(b));
                }});

        registerService(SERIAL_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    IBinder b = ServiceManager.getService(SERIAL_SERVICE);
                    return new SerialManager(ctx, ISerialManager.Stub.asInterface(b));
                }});

        registerService(VIBRATOR_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    return new SystemVibrator();
                }});

        registerService(WALLPAPER_SERVICE, WALLPAPER_FETCHER);

        registerService(WIFI_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    IBinder b = ServiceManager.getService(WIFI_SERVICE);
                    IWifiManager service = IWifiManager.Stub.asInterface(b);
                    return new WifiManager(ctx.getOuterContext(), service);
                }});

        registerService(WIFI_P2P_SERVICE, new ServiceFetcher() {
                public Object createService(ContextImpl ctx) {
                    IBinder b = ServiceManager.getService(WIFI_P2P_SERVICE);
                    IWifiP2pManager service = IWifiP2pManager.Stub.asInterface(b);
                    return new WifiP2pManager(service);
                }});

        registerService(WINDOW_SERVICE, new ServiceFetcher() {
                public Object getService(ContextImpl ctx) {
                    Display display = ctx.mDisplay;
                    if (display == null) {
                        DisplayManager dm = (DisplayManager)ctx.getOuterContext().getSystemService(
                                Context.DISPLAY_SERVICE);
                        display = dm.getDisplay(Display.DEFAULT_DISPLAY);
                    }
                    return new WindowManagerImpl(display);
                }});

        registerService(USER_SERVICE, new ServiceFetcher() {
            public Object getService(ContextImpl ctx) {
                IBinder b = ServiceManager.getService(USER_SERVICE);
                IUserManager service = IUserManager.Stub.asInterface(b);
                return new UserManager(ctx, service);
            }});
    }

    static ContextImpl getImpl(Context context) {
        Context nextContext;
        while ((context instanceof ContextWrapper) &&
                (nextContext=((ContextWrapper)context).getBaseContext()) != null) {
            context = nextContext;
        }
        return (ContextImpl)context;
    }

    // The system service cache for the system services that are
    // cached per-ContextImpl.  Package-scoped to avoid accessor
    // methods.
    final ArrayList<Object> mServiceCache = new ArrayList<Object>();

    @Override
    public AssetManager getAssets() {
        return getResources().getAssets();
    }

    @Override
    public Resources getResources() {
        return mResources;
    }

    @Override
    public PackageManager getPackageManager() {
        if (mPackageManager != null) {
            return mPackageManager;
        }

        IPackageManager pm = ActivityThread.getPackageManager();
        if (pm != null) {
            // Doesn't matter if we make more than one instance.
            return (mPackageManager = new ApplicationPackageManager(this, pm));
        }

        return null;
    }

    @Override
    public ContentResolver getContentResolver() {
        return mContentResolver;
    }

    @Override
    public Looper getMainLooper() {
        return mMainThread.getLooper();
    }

    @Override
    public Context getApplicationContext() {
        return (mPackageInfo != null) ?
                mPackageInfo.getApplication() : mMainThread.getApplication();
    }

    @Override
    public void setTheme(int resid) {
        mThemeResource = resid;
    }

    @Override
    public int getThemeResId() {
        return mThemeResource;
    }

    @Override
    public Resources.Theme getTheme() {
        if (mTheme == null) {
            mThemeResource = Resources.selectDefaultTheme(mThemeResource,
                    getOuterContext().getApplicationInfo().targetSdkVersion);
            mTheme = mResources.newTheme();
            mTheme.applyStyle(mThemeResource, true);
        }
        return mTheme;
    }

    @Override
    public ClassLoader getClassLoader() {
        return mPackageInfo != null ?
                mPackageInfo.getClassLoader() : ClassLoader.getSystemClassLoader();
    }

    @Override
    public String getPackageName() {
        if (mPackageInfo != null) {
            return mPackageInfo.getPackageName();
        }
        throw new RuntimeException("Not supported in system context");
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        if (mPackageInfo != null) {
            return mPackageInfo.getApplicationInfo();
        }
        throw new RuntimeException("Not supported in system context");
    }

    @Override
    public String getPackageResourcePath() {
        if (mPackageInfo != null) {
            return mPackageInfo.getResDir();
        }
        throw new RuntimeException("Not supported in system context");
    }

    @Override
    public String getPackageCodePath() {
        if (mPackageInfo != null) {
            return mPackageInfo.getAppDir();
        }
        throw new RuntimeException("Not supported in system context");
    }

    public File getSharedPrefsFile(String name) {
        return makeFilename(getPreferencesDir(), name + ".xml");
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferencesImpl sp;
        synchronized (sSharedPrefs) {
            sp = sSharedPrefs.get(name);
            if (sp == null) {
                File prefsFile = getSharedPrefsFile(name);
                sp = new SharedPreferencesImpl(prefsFile, mode);
                sSharedPrefs.put(name, sp);
                return sp;
            }
        }
        if ((mode & Context.MODE_MULTI_PROCESS) != 0 ||
            getApplicationInfo().targetSdkVersion < android.os.Build.VERSION_CODES.HONEYCOMB) {
            // If somebody else (some other process) changed the prefs
            // file behind our back, we reload it.  This has been the
            // historical (if undocumented) behavior.
            sp.startReloadIfChangedUnexpectedly();
        }
        return sp;
    }

    private File getPreferencesDir() {
        synchronized (mSync) {
            if (mPreferencesDir == null) {
                mPreferencesDir = new File(getDataDirFile(), "shared_prefs");
            }
            return mPreferencesDir;
        }
    }

    @Override
    public FileInputStream openFileInput(String name)
        throws FileNotFoundException {
        File f = makeFilename(getFilesDir(), name);
        return new FileInputStream(f);
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode)
        throws FileNotFoundException {
        final boolean append = (mode&MODE_APPEND) != 0;
        File f = makeFilename(getFilesDir(), name);
        try {
            FileOutputStream fos = new FileOutputStream(f, append);
            setFilePermissionsFromMode(f.getPath(), mode, 0);
            return fos;
        } catch (FileNotFoundException e) {
        }

        File parent = f.getParentFile();
        parent.mkdir();
        FileUtils.setPermissions(
            parent.getPath(),
            FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
            -1, -1);
        FileOutputStream fos = new FileOutputStream(f, append);
        setFilePermissionsFromMode(f.getPath(), mode, 0);
        return fos;
    }

    @Override
    public boolean deleteFile(String name) {
        File f = makeFilename(getFilesDir(), name);
        return f.delete();
    }

    @Override
    public File getFilesDir() {
        synchronized (mSync) {
            if (mFilesDir == null) {
                mFilesDir = new File(getDataDirFile(), "files");
            }
            if (!mFilesDir.exists()) {
                if(!mFilesDir.mkdirs()) {
                    Log.w(TAG, "Unable to create files directory " + mFilesDir.getPath());
                    return null;
                }
                FileUtils.setPermissions(
                        mFilesDir.getPath(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                        -1, -1);
            }
            return mFilesDir;
        }
    }

    @Override
    public File getExternalFilesDir(String type) {
        synchronized (mSync) {
            if (mExternalFilesDir == null) {
                mExternalFilesDir = Environment.getExternalStorageAppFilesDirectory(
                        getPackageName());
            }
            if (!mExternalFilesDir.exists()) {
                try {
                    (new File(Environment.getExternalStorageAndroidDataDir(),
                            ".nomedia")).createNewFile();
                } catch (IOException e) {
                }
                if (!mExternalFilesDir.mkdirs()) {
                    Log.w(TAG, "Unable to create external files directory");
                    return null;
                }
            }
            if (type == null) {
                return mExternalFilesDir;
            }
            File dir = new File(mExternalFilesDir, type);
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    Log.w(TAG, "Unable to create external media directory " + dir);
                    return null;
                }
            }
            return dir;
        }
    }

    @Override
    public File getObbDir() {
        synchronized (mSync) {
            if (mObbDir == null) {
                mObbDir = Environment.getExternalStorageAppObbDirectory(
                        getPackageName());
            }
            return mObbDir;
        }
    }

    @Override
    public File getCacheDir() {
        synchronized (mSync) {
            if (mCacheDir == null) {
                mCacheDir = new File(getDataDirFile(), "cache");
            }
            if (!mCacheDir.exists()) {
                if(!mCacheDir.mkdirs()) {
                    Log.w(TAG, "Unable to create cache directory " + mCacheDir.getAbsolutePath());
                    return null;
                }
                FileUtils.setPermissions(
                        mCacheDir.getPath(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                        -1, -1);
            }
        }
        return mCacheDir;
    }

    @Override
    public File getExternalCacheDir() {
        synchronized (mSync) {
            if (mExternalCacheDir == null) {
                mExternalCacheDir = Environment.getExternalStorageAppCacheDirectory(
                        getPackageName());
            }
            if (!mExternalCacheDir.exists()) {
                try {
                    (new File(Environment.getExternalStorageAndroidDataDir(),
                            ".nomedia")).createNewFile();
                } catch (IOException e) {
                }
                if (!mExternalCacheDir.mkdirs()) {
                    Log.w(TAG, "Unable to create external cache directory");
                    return null;
                }
            }
            return mExternalCacheDir;
        }
    }

    @Override
    public File getFileStreamPath(String name) {
        return makeFilename(getFilesDir(), name);
    }

    @Override
    public String[] fileList() {
        final String[] list = getFilesDir().list();
        return (list != null) ? list : EMPTY_FILE_LIST;
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory) {
        return openOrCreateDatabase(name, mode, factory, null);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, CursorFactory factory,
            DatabaseErrorHandler errorHandler) {
        File f = validateFilePath(name, true);
        int flags = SQLiteDatabase.CREATE_IF_NECESSARY;
        if ((mode & MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) {
            flags |= SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
        }
        SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), factory, flags, errorHandler);
        setFilePermissionsFromMode(f.getPath(), mode, 0);
        return db;
    }

    @Override
    public boolean deleteDatabase(String name) {
        try {
            File f = validateFilePath(name, false);
            return SQLiteDatabase.deleteDatabase(f);
        } catch (Exception e) {
        }
        return false;
    }

    @Override
    public File getDatabasePath(String name) {
        return validateFilePath(name, false);
    }

    @Override
    public String[] databaseList() {
        final String[] list = getDatabasesDir().list();
        return (list != null) ? list : EMPTY_FILE_LIST;
    }


    private File getDatabasesDir() {
        synchronized (mSync) {
            if (mDatabasesDir == null) {
                mDatabasesDir = new File(getDataDirFile(), "databases");
            }
            if (mDatabasesDir.getPath().equals("databases")) {
                mDatabasesDir = new File("/data/system");
            }
            return mDatabasesDir;
        }
    }

    @Override
    public Drawable getWallpaper() {
        return getWallpaperManager().getDrawable();
    }

    @Override
    public Drawable peekWallpaper() {
        return getWallpaperManager().peekDrawable();
    }

    @Override
    public int getWallpaperDesiredMinimumWidth() {
        return getWallpaperManager().getDesiredMinimumWidth();
    }

    @Override
    public int getWallpaperDesiredMinimumHeight() {
        return getWallpaperManager().getDesiredMinimumHeight();
    }

    @Override
    public void setWallpaper(Bitmap bitmap) throws IOException  {
        getWallpaperManager().setBitmap(bitmap);
    }

    @Override
    public void setWallpaper(InputStream data) throws IOException {
        getWallpaperManager().setStream(data);
    }

    @Override
    public void clearWallpaper() throws IOException {
        getWallpaperManager().clear();
    }

    @Override
    public void startActivity(Intent intent) {
        warnIfCallingFromSystemProcess();
        startActivity(intent, null);
    }

    /** @hide */
    @Override
    public void startActivityAsUser(Intent intent, UserHandle user) {
        startActivityAsUser(intent, null, user);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        warnIfCallingFromSystemProcess();
        if ((intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            throw new AndroidRuntimeException(
                    "Calling startActivity() from outside of an Activity "
                    + " context requires the FLAG_ACTIVITY_NEW_TASK flag."
                    + " Is this really what you want?");
        }
        mMainThread.getInstrumentation().execStartActivity(
            getOuterContext(), mMainThread.getApplicationThread(), null,
            (Activity)null, intent, -1, options);
    }

    /** @hide */
    @Override
    public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
        try {
            ActivityManagerNative.getDefault().startActivityAsUser(
                mMainThread.getApplicationThread(), intent,
                intent.resolveTypeIfNeeded(getContentResolver()),
                null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null, null, options,
                user.getIdentifier());
        } catch (RemoteException re) {
        }
    }

    @Override
    public void startActivities(Intent[] intents) {
        warnIfCallingFromSystemProcess();
        startActivities(intents, null);
    }

    /** @hide */
    @Override
    public void startActivitiesAsUser(Intent[] intents, Bundle options, UserHandle userHandle) {
        if ((intents[0].getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            throw new AndroidRuntimeException(
                    "Calling startActivities() from outside of an Activity "
                    + " context requires the FLAG_ACTIVITY_NEW_TASK flag on first Intent."
                    + " Is this really what you want?");
        }
        mMainThread.getInstrumentation().execStartActivitiesAsUser(
            getOuterContext(), mMainThread.getApplicationThread(), null,
            (Activity)null, intents, options, userHandle.getIdentifier());
    }

    @Override
    public void startActivities(Intent[] intents, Bundle options) {
        warnIfCallingFromSystemProcess();
        if ((intents[0].getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            throw new AndroidRuntimeException(
                    "Calling startActivities() from outside of an Activity "
                    + " context requires the FLAG_ACTIVITY_NEW_TASK flag on first Intent."
                    + " Is this really what you want?");
        }
        mMainThread.getInstrumentation().execStartActivities(
            getOuterContext(), mMainThread.getApplicationThread(), null,
            (Activity)null, intents, options);
    }

    @Override
    public void startIntentSender(IntentSender intent,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, null);
    }

    @Override
    public void startIntentSender(IntentSender intent, Intent fillInIntent,
            int flagsMask, int flagsValues, int extraFlags, Bundle options)
            throws IntentSender.SendIntentException {
        try {
            String resolvedType = null;
            if (fillInIntent != null) {
                fillInIntent.setAllowFds(false);
                resolvedType = fillInIntent.resolveTypeIfNeeded(getContentResolver());
            }
            int result = ActivityManagerNative.getDefault()
                .startActivityIntentSender(mMainThread.getApplicationThread(), intent,
                        fillInIntent, resolvedType, null, null,
                        0, flagsMask, flagsValues, options);
            if (result == ActivityManager.START_CANCELED) {
                throw new IntentSender.SendIntentException();
            }
            Instrumentation.checkStartActivityResult(result, null);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendBroadcast(Intent intent) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, null, false, false,
                getUserId());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, receiverPermission, false, false,
                getUserId());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendOrderedBroadcast(Intent intent,
            String receiverPermission) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, receiverPermission, true, false,
                getUserId());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendOrderedBroadcast(Intent intent,
            String receiverPermission, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        warnIfCallingFromSystemProcess();
        IIntentReceiver rd = null;
        if (resultReceiver != null) {
            if (mPackageInfo != null) {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = mPackageInfo.getReceiverDispatcher(
                    resultReceiver, getOuterContext(), scheduler,
                    mMainThread.getInstrumentation(), false);
            } else {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = new LoadedApk.ReceiverDispatcher(
                        resultReceiver, getOuterContext(), scheduler, null, false).getIIntentReceiver();
            }
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, rd,
                initialCode, initialData, initialExtras, receiverPermission,
                true, false, getUserId());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().broadcastIntent(mMainThread.getApplicationThread(),
                    intent, resolvedType, null, Activity.RESULT_OK, null, null, null, false, false,
                    user.getIdentifier());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, receiverPermission, false, false,
                user.getIdentifier());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, BroadcastReceiver resultReceiver, Handler scheduler,
            int initialCode, String initialData, Bundle initialExtras) {
        IIntentReceiver rd = null;
        if (resultReceiver != null) {
            if (mPackageInfo != null) {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = mPackageInfo.getReceiverDispatcher(
                    resultReceiver, getOuterContext(), scheduler,
                    mMainThread.getInstrumentation(), false);
            } else {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = new LoadedApk.ReceiverDispatcher(
                        resultReceiver, getOuterContext(), scheduler, null, false).getIIntentReceiver();
            }
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, rd,
                initialCode, initialData, initialExtras, receiverPermission,
                true, false, user.getIdentifier());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendStickyBroadcast(Intent intent) {
        warnIfCallingFromSystemProcess();
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, null, false, true,
                getUserId());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendStickyOrderedBroadcast(Intent intent,
            BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        warnIfCallingFromSystemProcess();
        IIntentReceiver rd = null;
        if (resultReceiver != null) {
            if (mPackageInfo != null) {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = mPackageInfo.getReceiverDispatcher(
                    resultReceiver, getOuterContext(), scheduler,
                    mMainThread.getInstrumentation(), false);
            } else {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = new LoadedApk.ReceiverDispatcher(
                        resultReceiver, getOuterContext(), scheduler, null, false).getIIntentReceiver();
            }
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, rd,
                initialCode, initialData, initialExtras, null,
                true, true, getUserId());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void removeStickyBroadcast(Intent intent) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        if (resolvedType != null) {
            intent = new Intent(intent);
            intent.setDataAndType(intent.getData(), resolvedType);
        }
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().unbroadcastIntent(
                    mMainThread.getApplicationThread(), intent, getUserId());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, null, false, true, user.getIdentifier());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void sendStickyOrderedBroadcastAsUser(Intent intent,
            UserHandle user, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData,
            Bundle initialExtras) {
        IIntentReceiver rd = null;
        if (resultReceiver != null) {
            if (mPackageInfo != null) {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = mPackageInfo.getReceiverDispatcher(
                    resultReceiver, getOuterContext(), scheduler,
                    mMainThread.getInstrumentation(), false);
            } else {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = new LoadedApk.ReceiverDispatcher(
                        resultReceiver, getOuterContext(), scheduler, null, false).getIIntentReceiver();
            }
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, rd,
                initialCode, initialData, initialExtras, null,
                true, true, user.getIdentifier());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void removeStickyBroadcastAsUser(Intent intent, UserHandle user) {
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        if (resolvedType != null) {
            intent = new Intent(intent);
            intent.setDataAndType(intent.getData(), resolvedType);
        }
        try {
            intent.setAllowFds(false);
            ActivityManagerNative.getDefault().unbroadcastIntent(
                    mMainThread.getApplicationThread(), intent, user.getIdentifier());
        } catch (RemoteException e) {
        }
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return registerReceiver(receiver, filter, null, null);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        return registerReceiverInternal(receiver, getUserId(),
                filter, broadcastPermission, scheduler, getOuterContext());
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        return registerReceiverInternal(receiver, user.getIdentifier(),
                filter, broadcastPermission, scheduler, getOuterContext());
    }

    private Intent registerReceiverInternal(BroadcastReceiver receiver, int userId,
            IntentFilter filter, String broadcastPermission,
            Handler scheduler, Context context) {
        IIntentReceiver rd = null;
        if (receiver != null) {
            if (mPackageInfo != null && context != null) {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = mPackageInfo.getReceiverDispatcher(
                    receiver, context, scheduler,
                    mMainThread.getInstrumentation(), true);
            } else {
                if (scheduler == null) {
                    scheduler = mMainThread.getHandler();
                }
                rd = new LoadedApk.ReceiverDispatcher(
                        receiver, context, scheduler, null, true).getIIntentReceiver();
            }
        }
        try {
            return ActivityManagerNative.getDefault().registerReceiver(
                    mMainThread.getApplicationThread(), mBasePackageName,
                    rd, filter, broadcastPermission, userId);
        } catch (RemoteException e) {
            return null;
        }
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        if (mPackageInfo != null) {
            IIntentReceiver rd = mPackageInfo.forgetReceiverDispatcher(
                    getOuterContext(), receiver);
            try {
                ActivityManagerNative.getDefault().unregisterReceiver(rd);
            } catch (RemoteException e) {
            }
        } else {
            throw new RuntimeException("Not supported in system context");
        }
    }

    @Override
    public ComponentName startService(Intent service) {
        warnIfCallingFromSystemProcess();
        return startServiceAsUser(service, mUser);
    }

    @Override
    public boolean stopService(Intent service) {
        warnIfCallingFromSystemProcess();
        return stopServiceAsUser(service, mUser);
    }

    @Override
    public ComponentName startServiceAsUser(Intent service, UserHandle user) {
        try {
            service.setAllowFds(false);
            ComponentName cn = ActivityManagerNative.getDefault().startService(
                mMainThread.getApplicationThread(), service,
                service.resolveTypeIfNeeded(getContentResolver()), user.getIdentifier());
            if (cn != null) {
                if (cn.getPackageName().equals("!")) {
                    throw new SecurityException(
                            "Not allowed to start service " + service
                            + " without permission " + cn.getClassName());
                } else if (cn.getPackageName().equals("!!")) {
                    throw new SecurityException(
                            "Unable to start service " + service
                            + ": " + cn.getClassName());
                }
            }
            return cn;
        } catch (RemoteException e) {
            return null;
        }
    }

    @Override
    public boolean stopServiceAsUser(Intent service, UserHandle user) {
        try {
            service.setAllowFds(false);
            int res = ActivityManagerNative.getDefault().stopService(
                mMainThread.getApplicationThread(), service,
                service.resolveTypeIfNeeded(getContentResolver()), user.getIdentifier());
            if (res < 0) {
                throw new SecurityException(
                        "Not allowed to stop service " + service);
            }
            return res != 0;
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn,
            int flags) {
        warnIfCallingFromSystemProcess();
        return bindService(service, conn, flags, UserHandle.getUserId(Process.myUid()));
    }

    /** @hide */
    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags, int userHandle) {
        IServiceConnection sd;
        if (conn == null) {
            throw new IllegalArgumentException("connection is null");
        }
        if (mPackageInfo != null) {
            sd = mPackageInfo.getServiceDispatcher(conn, getOuterContext(),
                    mMainThread.getHandler(), flags);
        } else {
            throw new RuntimeException("Not supported in system context");
        }
        try {
            IBinder token = getActivityToken();
            if (token == null && (flags&BIND_AUTO_CREATE) == 0 && mPackageInfo != null
                    && mPackageInfo.getApplicationInfo().targetSdkVersion
                    < android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                flags |= BIND_WAIVE_PRIORITY;
            }
            service.setAllowFds(false);
            int res = ActivityManagerNative.getDefault().bindService(
                mMainThread.getApplicationThread(), getActivityToken(),
                service, service.resolveTypeIfNeeded(getContentResolver()),
                sd, flags, userHandle);
            if (res < 0) {
                throw new SecurityException(
                        "Not allowed to bind to service " + service);
            }
            return res != 0;
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        if (conn == null) {
            throw new IllegalArgumentException("connection is null");
        }
        if (mPackageInfo != null) {
            IServiceConnection sd = mPackageInfo.forgetServiceDispatcher(
                    getOuterContext(), conn);
            try {
                ActivityManagerNative.getDefault().unbindService(sd);
            } catch (RemoteException e) {
            }
        } else {
            throw new RuntimeException("Not supported in system context");
        }
    }

    @Override
    public boolean startInstrumentation(ComponentName className,
            String profileFile, Bundle arguments) {
        try {
            if (arguments != null) {
                arguments.setAllowFds(false);
            }
            return ActivityManagerNative.getDefault().startInstrumentation(
                    className, profileFile, 0, arguments, null, getUserId());
        } catch (RemoteException e) {
            // System has crashed, nothing we can do.
        }
        return false;
    }

    @Override
    public Object getSystemService(String name) {
        ServiceFetcher fetcher = SYSTEM_SERVICE_MAP.get(name);
        return fetcher == null ? null : fetcher.getService(this);
    }

    private WallpaperManager getWallpaperManager() {
        return (WallpaperManager) WALLPAPER_FETCHER.getService(this);
    }

    /* package */ static DropBoxManager createDropBoxManager() {
        IBinder b = ServiceManager.getService(DROPBOX_SERVICE);
        IDropBoxManagerService service = IDropBoxManagerService.Stub.asInterface(b);
        if (service == null) {
            // Don't return a DropBoxManager that will NPE upon use.
            // This also avoids caching a broken DropBoxManager in
            // getDropBoxManager during early boot, before the
            // DROPBOX_SERVICE is registered.
            return null;
        }
        return new DropBoxManager(service);
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        try {
            return ActivityManagerNative.getDefault().checkPermission(
                    permission, pid, uid);
        } catch (RemoteException e) {
            return PackageManager.PERMISSION_DENIED;
        }
    }

    @Override
    public int checkCallingPermission(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        int pid = Binder.getCallingPid();
        if (pid != Process.myPid()) {
            return checkPermission(permission, pid, Binder.getCallingUid());
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        return checkPermission(permission, Binder.getCallingPid(),
                Binder.getCallingUid());
    }

    private void enforce(
            String permission, int resultOfCheck,
            boolean selfToo, int uid, String message) {
        if (resultOfCheck != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    (message != null ? (message + ": ") : "") +
                    (selfToo
                     ? "Neither user " + uid + " nor current process has "
                     : "uid " + uid + " does not have ") +
                    permission +
                    ".");
        }
    }

    public void enforcePermission(
            String permission, int pid, int uid, String message) {
        enforce(permission,
                checkPermission(permission, pid, uid),
                false,
                uid,
                message);
    }

    public void enforceCallingPermission(String permission, String message) {
        enforce(permission,
                checkCallingPermission(permission),
                false,
                Binder.getCallingUid(),
                message);
    }

    public void enforceCallingOrSelfPermission(
            String permission, String message) {
        enforce(permission,
                checkCallingOrSelfPermission(permission),
                true,
                Binder.getCallingUid(),
                message);
    }

    @Override
    public void grantUriPermission(String toPackage, Uri uri, int modeFlags) {
         try {
            ActivityManagerNative.getDefault().grantUriPermission(
                    mMainThread.getApplicationThread(), toPackage, uri,
                    modeFlags);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void revokeUriPermission(Uri uri, int modeFlags) {
         try {
            ActivityManagerNative.getDefault().revokeUriPermission(
                    mMainThread.getApplicationThread(), uri,
                    modeFlags);
        } catch (RemoteException e) {
        }
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        try {
            return ActivityManagerNative.getDefault().checkUriPermission(
                    uri, pid, uid, modeFlags);
        } catch (RemoteException e) {
            return PackageManager.PERMISSION_DENIED;
        }
    }

    @Override
    public int checkCallingUriPermission(Uri uri, int modeFlags) {
        int pid = Binder.getCallingPid();
        if (pid != Process.myPid()) {
            return checkUriPermission(uri, pid,
                    Binder.getCallingUid(), modeFlags);
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public int checkCallingOrSelfUriPermission(Uri uri, int modeFlags) {
        return checkUriPermission(uri, Binder.getCallingPid(),
                Binder.getCallingUid(), modeFlags);
    }

    @Override
    public int checkUriPermission(Uri uri, String readPermission,
            String writePermission, int pid, int uid, int modeFlags) {
        if (DEBUG) {
            Log.i("foo", "checkUriPermission: uri=" + uri + "readPermission="
                    + readPermission + " writePermission=" + writePermission
                    + " pid=" + pid + " uid=" + uid + " mode" + modeFlags);
        }
        if ((modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            if (readPermission == null
                    || checkPermission(readPermission, pid, uid)
                    == PackageManager.PERMISSION_GRANTED) {
                return PackageManager.PERMISSION_GRANTED;
            }
        }
        if ((modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            if (writePermission == null
                    || checkPermission(writePermission, pid, uid)
                    == PackageManager.PERMISSION_GRANTED) {
                return PackageManager.PERMISSION_GRANTED;
            }
        }
        return uri != null ? checkUriPermission(uri, pid, uid, modeFlags)
                : PackageManager.PERMISSION_DENIED;
    }

    private String uriModeFlagToString(int uriModeFlags) {
        switch (uriModeFlags) {
            case Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION:
                return "read and write";
            case Intent.FLAG_GRANT_READ_URI_PERMISSION:
                return "read";
            case Intent.FLAG_GRANT_WRITE_URI_PERMISSION:
                return "write";
        }
        throw new IllegalArgumentException(
                "Unknown permission mode flags: " + uriModeFlags);
    }

    private void enforceForUri(
            int modeFlags, int resultOfCheck, boolean selfToo,
            int uid, Uri uri, String message) {
        if (resultOfCheck != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    (message != null ? (message + ": ") : "") +
                    (selfToo
                     ? "Neither user " + uid + " nor current process has "
                     : "User " + uid + " does not have ") +
                    uriModeFlagToString(modeFlags) +
                    " permission on " +
                    uri +
                    ".");
        }
    }

    public void enforceUriPermission(
            Uri uri, int pid, int uid, int modeFlags, String message) {
        enforceForUri(
                modeFlags, checkUriPermission(uri, pid, uid, modeFlags),
                false, uid, uri, message);
    }

    public void enforceCallingUriPermission(
            Uri uri, int modeFlags, String message) {
        enforceForUri(
                modeFlags, checkCallingUriPermission(uri, modeFlags),
                false,
                Binder.getCallingUid(), uri, message);
    }

    public void enforceCallingOrSelfUriPermission(
            Uri uri, int modeFlags, String message) {
        enforceForUri(
                modeFlags,
                checkCallingOrSelfUriPermission(uri, modeFlags), true,
                Binder.getCallingUid(), uri, message);
    }

    public void enforceUriPermission(
            Uri uri, String readPermission, String writePermission,
            int pid, int uid, int modeFlags, String message) {
        enforceForUri(modeFlags,
                      checkUriPermission(
                              uri, readPermission, writePermission, pid, uid,
                              modeFlags),
                      false,
                      uid,
                      uri,
                      message);
    }

    private void warnIfCallingFromSystemProcess() {
        if (Process.myUid() == Process.SYSTEM_UID) {
            Slog.w(TAG, "Calling a method in the system process without a qualified user: "
                    + Debug.getCallers(3));
        }
    }

    @Override
    public Context createPackageContext(String packageName, int flags)
            throws NameNotFoundException {
        return createPackageContextAsUser(packageName, flags,
                mUser != null ? mUser : Process.myUserHandle());
    }

    @Override
    public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
            throws NameNotFoundException {
        if (packageName.equals("system") || packageName.equals("android")) {
            final ContextImpl context = new ContextImpl(mMainThread.getSystemContext());
            context.mRestricted = (flags & CONTEXT_RESTRICTED) == CONTEXT_RESTRICTED;
            context.init(mPackageInfo, null, mMainThread, mResources, mBasePackageName, user);
            return context;
        }

        LoadedApk pi =
            mMainThread.getPackageInfo(packageName, mResources.getCompatibilityInfo(), flags,
                    user.getIdentifier());
        if (pi != null) {
            ContextImpl c = new ContextImpl();
            c.mRestricted = (flags & CONTEXT_RESTRICTED) == CONTEXT_RESTRICTED;
            c.init(pi, null, mMainThread, mResources, mBasePackageName, user);
            if (c.mResources != null) {
                return c;
            }
        }

        // Should be a better exception.
        throw new PackageManager.NameNotFoundException(
            "Application package " + packageName + " not found");
    }

    @Override
    public Context createConfigurationContext(Configuration overrideConfiguration) {
        if (overrideConfiguration == null) {
            throw new IllegalArgumentException("overrideConfiguration must not be null");
        }

        ContextImpl c = new ContextImpl();
        c.init(mPackageInfo, null, mMainThread);
        c.mResources = mMainThread.getTopLevelResources(
                mPackageInfo.getResDir(),
                getDisplayId(), overrideConfiguration,
                mResources.getCompatibilityInfo());
        return c;
    }

    @Override
    public Context createDisplayContext(Display display) {
        if (display == null) {
            throw new IllegalArgumentException("display must not be null");
        }

        int displayId = display.getDisplayId();
        CompatibilityInfo ci = CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
        CompatibilityInfoHolder cih = getCompatibilityInfo(displayId);
        if (cih != null) {
            ci = cih.get();
        }

        ContextImpl context = new ContextImpl();
        context.init(mPackageInfo, null, mMainThread);
        context.mDisplay = display;
        context.mResources = mMainThread.getTopLevelResources(
                mPackageInfo.getResDir(), displayId, null, ci);
        return context;
    }

    private int getDisplayId() {
        return mDisplay != null ? mDisplay.getDisplayId() : Display.DEFAULT_DISPLAY;
    }

    @Override
    public boolean isRestricted() {
        return mRestricted;
    }

    @Override
    public CompatibilityInfoHolder getCompatibilityInfo(int displayId) {
        return displayId == Display.DEFAULT_DISPLAY ? mPackageInfo.mCompatibilityInfo : null;
    }

    private File getDataDirFile() {
        if (mPackageInfo != null) {
            return mPackageInfo.getDataDirFile();
        }
        throw new RuntimeException("Not supported in system context");
    }

    @Override
    public File getDir(String name, int mode) {
        name = "app_" + name;
        File file = makeFilename(getDataDirFile(), name);
        if (!file.exists()) {
            file.mkdir();
            setFilePermissionsFromMode(file.getPath(), mode,
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH);
        }
        return file;
    }

    /** {@hide} */
    public int getUserId() {
        return mUser.getIdentifier();
    }

    static ContextImpl createSystemContext(ActivityThread mainThread) {
        final ContextImpl context = new ContextImpl();
        context.init(Resources.getSystem(), mainThread, Process.myUserHandle());
        return context;
    }

    ContextImpl() {
        mOuterContext = this;
    }

    /**
     * Create a new ApplicationContext from an existing one.  The new one
     * works and operates the same as the one it is copying.
     *
     * @param context Existing application context.
     */
    public ContextImpl(ContextImpl context) {
        mPackageInfo = context.mPackageInfo;
        mBasePackageName = context.mBasePackageName;
        mResources = context.mResources;
        mMainThread = context.mMainThread;
        mContentResolver = context.mContentResolver;
        mUser = context.mUser;
        mDisplay = context.mDisplay;
        mOuterContext = this;
    }

    final void init(LoadedApk packageInfo, IBinder activityToken, ActivityThread mainThread) {
        init(packageInfo, activityToken, mainThread, null, null, Process.myUserHandle());
    }

    final void init(LoadedApk packageInfo, IBinder activityToken, ActivityThread mainThread,
            Resources container, String basePackageName, UserHandle user) {
        mPackageInfo = packageInfo;
        mBasePackageName = basePackageName != null ? basePackageName : packageInfo.mPackageName;
        mResources = mPackageInfo.getResources(mainThread);

        if (mResources != null && container != null
                && container.getCompatibilityInfo().applicationScale !=
                        mResources.getCompatibilityInfo().applicationScale) {
            if (DEBUG) {
                Log.d(TAG, "loaded context has different scaling. Using container's" +
                        " compatiblity info:" + container.getDisplayMetrics());
            }
            mResources = mainThread.getTopLevelResources(
                    mPackageInfo.getResDir(), Display.DEFAULT_DISPLAY,
                    null, container.getCompatibilityInfo());
        }
        mMainThread = mainThread;
        mActivityToken = activityToken;
        mContentResolver = new ApplicationContentResolver(this, mainThread, user);
        mUser = user;
    }

    final void init(Resources resources, ActivityThread mainThread, UserHandle user) {
        mPackageInfo = null;
        mBasePackageName = null;
        mResources = resources;
        mMainThread = mainThread;
        mContentResolver = new ApplicationContentResolver(this, mainThread, user);
        mUser = user;
    }

    final void scheduleFinalCleanup(String who, String what) {
        mMainThread.scheduleContextCleanup(this, who, what);
    }

    final void performFinalCleanup(String who, String what) {
        //Log.i(TAG, "Cleanup up context: " + this);
        mPackageInfo.removeContextRegistrations(getOuterContext(), who, what);
    }

    final Context getReceiverRestrictedContext() {
        if (mReceiverRestrictedContext != null) {
            return mReceiverRestrictedContext;
        }
        return mReceiverRestrictedContext = new ReceiverRestrictedContext(getOuterContext());
    }

    final void setOuterContext(Context context) {
        mOuterContext = context;
    }

    final Context getOuterContext() {
        return mOuterContext;
    }

    final IBinder getActivityToken() {
        return mActivityToken;
    }

    static void setFilePermissionsFromMode(String name, int mode,
            int extraPermissions) {
        int perms = FileUtils.S_IRUSR|FileUtils.S_IWUSR
            |FileUtils.S_IRGRP|FileUtils.S_IWGRP
            |extraPermissions;
        if ((mode&MODE_WORLD_READABLE) != 0) {
            perms |= FileUtils.S_IROTH;
        }
        if ((mode&MODE_WORLD_WRITEABLE) != 0) {
            perms |= FileUtils.S_IWOTH;
        }
        if (DEBUG) {
            Log.i(TAG, "File " + name + ": mode=0x" + Integer.toHexString(mode)
                  + ", perms=0x" + Integer.toHexString(perms));
        }
        FileUtils.setPermissions(name, perms, -1, -1);
    }

    private File validateFilePath(String name, boolean createDirectory) {
        File dir;
        File f;

        if (name.charAt(0) == File.separatorChar) {
            String dirPath = name.substring(0, name.lastIndexOf(File.separatorChar));
            dir = new File(dirPath);
            name = name.substring(name.lastIndexOf(File.separatorChar));
            f = new File(dir, name);
        } else {
            dir = getDatabasesDir();
            f = makeFilename(dir, name);
        }

        if (createDirectory && !dir.isDirectory() && dir.mkdir()) {
            FileUtils.setPermissions(dir.getPath(),
                FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                -1, -1);
        }

        return f;
    }

    private File makeFilename(File base, String name) {
        if (name.indexOf(File.separatorChar) < 0) {
            return new File(base, name);
        }
        throw new IllegalArgumentException(
                "File " + name + " contains a path separator");
    }

    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------

    private static final class ApplicationContentResolver extends ContentResolver {
        private final ActivityThread mMainThread;
        private final UserHandle mUser;

        public ApplicationContentResolver(
                Context context, ActivityThread mainThread, UserHandle user) {
            super(context);
            mMainThread = Preconditions.checkNotNull(mainThread);
            mUser = Preconditions.checkNotNull(user);
        }

        @Override
        protected IContentProvider acquireProvider(Context context, String auth) {
            return mMainThread.acquireProvider(context, auth, mUser.getIdentifier(), true);
        }

        @Override
        protected IContentProvider acquireExistingProvider(Context context, String auth) {
            return mMainThread.acquireExistingProvider(context, auth, mUser.getIdentifier(), true);
        }

        @Override
        public boolean releaseProvider(IContentProvider provider) {
            return mMainThread.releaseProvider(provider, true);
        }

        @Override
        protected IContentProvider acquireUnstableProvider(Context c, String auth) {
            return mMainThread.acquireProvider(c, auth, mUser.getIdentifier(), false);
        }

        @Override
        public boolean releaseUnstableProvider(IContentProvider icp) {
            return mMainThread.releaseProvider(icp, false);
        }

        @Override
        public void unstableProviderDied(IContentProvider icp) {
            mMainThread.handleUnstableProviderDied(icp.asBinder(), true);
        }
    }
}
