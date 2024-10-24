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

import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_RESOURCE_APK;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.hardware.ISerialManager;
import android.hardware.SerialManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.ravenwood.example.BlueManager;
import android.ravenwood.example.RedManager;
import android.util.ArrayMap;
import android.util.Singleton;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public class RavenwoodContext extends RavenwoodBaseContext {
    private static final String TAG = "Ravenwood";

    private final Object mLock = new Object();
    private final String mPackageName;
    private final HandlerThread mMainThread;

    private final RavenwoodPermissionEnforcer mEnforcer = new RavenwoodPermissionEnforcer();

    private final ArrayMap<Class<?>, String> mClassToName = new ArrayMap<>();
    private final ArrayMap<String, Supplier<?>> mNameToFactory = new ArrayMap<>();

    private final File mFilesDir;
    private final File mCacheDir;
    private final Supplier<Resources> mResourcesSupplier;

    private RavenwoodContext mAppContext;

    @GuardedBy("mLock")
    private Resources mResources;

    @GuardedBy("mLock")
    private Resources.Theme mTheme;

    private void registerService(Class<?> serviceClass, String serviceName,
            Supplier<?> serviceSupplier) {
        mClassToName.put(serviceClass, serviceName);
        mNameToFactory.put(serviceName, serviceSupplier);
    }

    public RavenwoodContext(String packageName, HandlerThread mainThread,
            Supplier<Resources> resourcesSupplier) throws IOException {
        mPackageName = packageName;
        mMainThread = mainThread;
        mResourcesSupplier = resourcesSupplier;
        mFilesDir = createTempDir(packageName + "_files-dir");
        mCacheDir = createTempDir(packageName + "_cache-dir");

        // Services provided by a typical shipping device
        registerService(ClipboardManager.class,
                Context.CLIPBOARD_SERVICE, memoize(() ->
                        new ClipboardManager(this, getMainThreadHandler())));
        registerService(PermissionEnforcer.class,
                Context.PERMISSION_ENFORCER_SERVICE, () -> mEnforcer);
        registerService(SerialManager.class,
                Context.SERIAL_SERVICE, memoize(() ->
                        new SerialManager(this, ISerialManager.Stub.asInterface(
                                ServiceManager.getService(Context.SERIAL_SERVICE)))
                ));

        // Additional services we provide for testing purposes
        registerService(BlueManager.class,
                BlueManager.SERVICE_NAME, memoize(() -> new BlueManager()));
        registerService(RedManager.class,
                RedManager.SERVICE_NAME, memoize(() -> new RedManager()));
    }

    @Override
    public Object getSystemService(String serviceName) {
        // TODO: pivot to using SystemServiceRegistry
        final Supplier<?> serviceSupplier = mNameToFactory.get(serviceName);
        if (serviceSupplier != null) {
            return serviceSupplier.get();
        } else {
            throw new UnsupportedOperationException(
                    "Service " + serviceName + " not yet supported under Ravenwood");
        }
    }

    void cleanUp() {
        deleteDir(mFilesDir);
        deleteDir(mCacheDir);
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        // TODO: pivot to using SystemServiceRegistry
        final String serviceName = mClassToName.get(serviceClass);
        if (serviceName != null) {
            return serviceName;
        } else {
            throw new UnsupportedOperationException(
                    "Service " + serviceClass + " not yet supported under Ravenwood");
        }
    }

    @Override
    public Looper getMainLooper() {
        Objects.requireNonNull(mMainThread,
                "Test must request setProvideMainThread() via RavenwoodConfig");
        return mMainThread.getLooper();
    }

    @Override
    public Handler getMainThreadHandler() {
        Objects.requireNonNull(mMainThread,
                "Test must request setProvideMainThread() via RavenwoodConfig");
        return mMainThread.getThreadHandler();
    }

    @Override
    public Executor getMainExecutor() {
        Objects.requireNonNull(mMainThread,
                "Test must request setProvideMainThread() via RavenwoodConfig");
        return mMainThread.getThreadExecutor();
    }

    @Override
    public String getPackageName() {
        return Objects.requireNonNull(mPackageName,
                "Test must request setPackageName() (or setTargetPackageName())"
                + " via RavenwoodConfig");
    }

    @Override
    public String getOpPackageName() {
        return Objects.requireNonNull(mPackageName,
                "Test must request setPackageName() via RavenwoodConfig");
    }

    @Override
    public String getAttributionTag() {
        return null;
    }

    @Override
    public UserHandle getUser() {
        return android.os.UserHandle.of(android.os.UserHandle.myUserId());
    }

    @Override
    public int getUserId() {
        return android.os.UserHandle.myUserId();
    }

    @Override
    public int getDeviceId() {
        return Context.DEVICE_ID_DEFAULT;
    }

    @Override
    public File getFilesDir() {
        return mFilesDir;
    }

    @Override
    public File getCacheDir() {
        return mCacheDir;
    }

    @Override
    public boolean deleteFile(String name) {
        File f = new File(name);
        return f.delete();
    }

    @Override
    public Resources getResources() {
        synchronized (mLock) {
            if (mResources == null) {
                mResources = mResourcesSupplier.get();
            }
            return mResources;
        }
    }

    @Override
    public AssetManager getAssets() {
        return getResources().getAssets();
    }

    @Override
    public Theme getTheme() {
        synchronized (mLock) {
            if (mTheme == null) {
                mTheme = getResources().newTheme();
            }
            return mTheme;
        }
    }

    @Override
    public String getPackageResourcePath() {
        return new File(RAVENWOOD_RESOURCE_APK).getAbsolutePath();
    }

    public void setApplicationContext(RavenwoodContext appContext) {
        mAppContext = appContext;
    }

    @Override
    public Context getApplicationContext() {
        return mAppContext;
    }

    /**
     * Wrap the given {@link Supplier} to become memoized.
     *
     * The underlying {@link Supplier} will only be invoked once, and that result will be cached
     * and returned for any future requests.
     */
    private static <T> Supplier<T> memoize(ThrowingSupplier<T> supplier) {
        final Singleton<T> singleton = new Singleton<>() {
            @Override
            protected T create() {
                try {
                    return supplier.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        return () -> {
            return singleton.get();
        };
    }

    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }


    static File createTempDir(String prefix) throws IOException {
        // Create a temp file, delete it and recreate it as a directory.
        final File dir = File.createTempFile(prefix + "-", "");
        dir.delete();
        dir.mkdirs();
        return dir;
    }

    static void deleteDir(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteDir(child);
                } else {
                    child.delete();
                }
            }
        }
    }
}
