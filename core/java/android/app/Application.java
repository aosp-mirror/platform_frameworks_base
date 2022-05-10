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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentCallbacks;
import android.content.ComponentCallbacks2;
import android.content.ComponentCallbacksController;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.autofill.AutofillManager;

import java.util.ArrayList;

/**
 * Base class for maintaining global application state. You can provide your own
 * implementation by creating a subclass and specifying the fully-qualified name
 * of this subclass as the <code>"android:name"</code> attribute in your
 * AndroidManifest.xml's <code>&lt;application&gt;</code> tag. The Application
 * class, or your subclass of the Application class, is instantiated before any
 * other class when the process for your application/package is created.
 *
 * <p class="note"><strong>Note: </strong>There is normally no need to subclass
 * Application.  In most situations, static singletons can provide the same
 * functionality in a more modular way.  If your singleton needs a global
 * context (for example to register broadcast receivers), include
 * {@link android.content.Context#getApplicationContext() Context.getApplicationContext()}
 * as a {@link android.content.Context} argument when invoking your singleton's
 * <code>getInstance()</code> method.
 * </p>
 */
public class Application extends ContextWrapper implements ComponentCallbacks2 {
    private static final String TAG = "Application";

    @UnsupportedAppUsage
    private ArrayList<ActivityLifecycleCallbacks> mActivityLifecycleCallbacks =
            new ArrayList<ActivityLifecycleCallbacks>();
    @UnsupportedAppUsage
    private ArrayList<OnProvideAssistDataListener> mAssistCallbacks = null;

    private final ComponentCallbacksController mCallbacksController =
            new ComponentCallbacksController();

    /** @hide */
    @UnsupportedAppUsage
    public LoadedApk mLoadedApk;

    public interface ActivityLifecycleCallbacks {

        /**
         * Called as the first step of the Activity being created. This is always called before
         * {@link Activity#onCreate}.
         */
        default void onActivityPreCreated(@NonNull Activity activity,
                @Nullable Bundle savedInstanceState) {
        }

        /**
         * Called when the Activity calls {@link Activity#onCreate super.onCreate()}.
         */
        void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState);

        /**
         * Called as the last step of the Activity being created. This is always called after
         * {@link Activity#onCreate}.
         */
        default void onActivityPostCreated(@NonNull Activity activity,
                @Nullable Bundle savedInstanceState) {
        }

        /**
         * Called as the first step of the Activity being started. This is always called before
         * {@link Activity#onStart}.
         */
        default void onActivityPreStarted(@NonNull Activity activity) {
        }

        /**
         * Called when the Activity calls {@link Activity#onStart super.onStart()}.
         */
        void onActivityStarted(@NonNull Activity activity);

        /**
         * Called as the last step of the Activity being started. This is always called after
         * {@link Activity#onStart}.
         */
        default void onActivityPostStarted(@NonNull Activity activity) {
        }

        /**
         * Called as the first step of the Activity being resumed. This is always called before
         * {@link Activity#onResume}.
         */
        default void onActivityPreResumed(@NonNull Activity activity) {
        }

        /**
         * Called when the Activity calls {@link Activity#onResume super.onResume()}.
         */
        void onActivityResumed(@NonNull Activity activity);

        /**
         * Called as the last step of the Activity being resumed. This is always called after
         * {@link Activity#onResume} and {@link Activity#onPostResume}.
         */
        default void onActivityPostResumed(@NonNull Activity activity) {
        }

        /**
         * Called as the first step of the Activity being paused. This is always called before
         * {@link Activity#onPause}.
         */
        default void onActivityPrePaused(@NonNull Activity activity) {
        }

        /**
         * Called when the Activity calls {@link Activity#onPause super.onPause()}.
         */
        void onActivityPaused(@NonNull Activity activity);

        /**
         * Called as the last step of the Activity being paused. This is always called after
         * {@link Activity#onPause}.
         */
        default void onActivityPostPaused(@NonNull Activity activity) {
        }

        /**
         * Called as the first step of the Activity being stopped. This is always called before
         * {@link Activity#onStop}.
         */
        default void onActivityPreStopped(@NonNull Activity activity) {
        }

        /**
         * Called when the Activity calls {@link Activity#onStop super.onStop()}.
         */
        void onActivityStopped(@NonNull Activity activity);

        /**
         * Called as the last step of the Activity being stopped. This is always called after
         * {@link Activity#onStop}.
         */
        default void onActivityPostStopped(@NonNull Activity activity) {
        }

        /**
         * Called as the first step of the Activity saving its instance state. This is always
         * called before {@link Activity#onSaveInstanceState}.
         */
        default void onActivityPreSaveInstanceState(@NonNull Activity activity,
                @NonNull Bundle outState) {
        }

        /**
         * Called when the Activity calls
         * {@link Activity#onSaveInstanceState super.onSaveInstanceState()}.
         */
        void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState);

        /**
         * Called as the last step of the Activity saving its instance state. This is always
         * called after{@link Activity#onSaveInstanceState}.
         */
        default void onActivityPostSaveInstanceState(@NonNull Activity activity,
                @NonNull Bundle outState) {
        }

        /**
         * Called as the first step of the Activity being destroyed. This is always called before
         * {@link Activity#onDestroy}.
         */
        default void onActivityPreDestroyed(@NonNull Activity activity) {
        }

        /**
         * Called when the Activity calls {@link Activity#onDestroy super.onDestroy()}.
         */
        void onActivityDestroyed(@NonNull Activity activity);

        /**
         * Called as the last step of the Activity being destroyed. This is always called after
         * {@link Activity#onDestroy}.
         */
        default void onActivityPostDestroyed(@NonNull Activity activity) {
        }

        /**
         * Called when the Activity configuration was changed.
         * @hide
         */
        default void onActivityConfigurationChanged(@NonNull Activity activity) {
        }
    }

    /**
     * Callback interface for use with {@link Application#registerOnProvideAssistDataListener}
     * and {@link Application#unregisterOnProvideAssistDataListener}.
     */
    public interface OnProvideAssistDataListener {
        /**
         * This is called when the user is requesting an assist, to build a full
         * {@link Intent#ACTION_ASSIST} Intent with all of the context of the current
         * application.  You can override this method to place into the bundle anything
         * you would like to appear in the {@link Intent#EXTRA_ASSIST_CONTEXT} part
         * of the assist Intent.
         */
        public void onProvideAssistData(Activity activity, Bundle data);
    }

    public Application() {
        super(null);
    }

    private String getLoadedApkInfo() {
        if (mLoadedApk == null) {
            return "null";
        }
        return mLoadedApk + "/pkg=" + mLoadedApk.mPackageName;
    }

    /**
     * Called when the application is starting, before any activity, service,
     * or receiver objects (excluding content providers) have been created.
     *
     * <p>Implementations should be as quick as possible (for example using
     * lazy initialization of state) since the time spent in this function
     * directly impacts the performance of starting the first activity,
     * service, or receiver in a process.</p>
     *
     * <p>If you override this method, be sure to call {@code super.onCreate()}.</p>
     *
     * <p class="note">Be aware that direct boot may also affect callback order on
     * Android {@link android.os.Build.VERSION_CODES#N} and later devices.
     * Until the user unlocks the device, only direct boot aware components are
     * allowed to run. You should consider that all direct boot unaware
     * components, including such {@link android.content.ContentProvider}, are
     * disabled until user unlock happens, especially when component callback
     * order matters.</p>
     */
    @CallSuper
    public void onCreate() {
    }

    /**
     * This method is for use in emulated process environments.  It will
     * never be called on a production Android device, where processes are
     * removed by simply killing them; no user code (including this callback)
     * is executed when doing so.
     */
    @CallSuper
    public void onTerminate() {
    }

    @CallSuper
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        mCallbacksController.dispatchConfigurationChanged(newConfig);
    }

    @CallSuper
    public void onLowMemory() {
        mCallbacksController.dispatchLowMemory();
    }

    @CallSuper
    public void onTrimMemory(int level) {
        mCallbacksController.dispatchTrimMemory(level);
    }

    public void registerComponentCallbacks(ComponentCallbacks callback) {
        mCallbacksController.registerCallbacks(callback);
    }

    public void unregisterComponentCallbacks(ComponentCallbacks callback) {
        mCallbacksController.unregisterCallbacks(callback);
    }

    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        synchronized (mActivityLifecycleCallbacks) {
            mActivityLifecycleCallbacks.add(callback);
        }
    }

    public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        synchronized (mActivityLifecycleCallbacks) {
            mActivityLifecycleCallbacks.remove(callback);
        }
    }

    public void registerOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
        synchronized (this) {
            if (mAssistCallbacks == null) {
                mAssistCallbacks = new ArrayList<OnProvideAssistDataListener>();
            }
            mAssistCallbacks.add(callback);
        }
    }

    public void unregisterOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
        synchronized (this) {
            if (mAssistCallbacks != null) {
                mAssistCallbacks.remove(callback);
            }
        }
    }

    /**
     * Returns the name of the current process. A package's default process name
     * is the same as its package name. Non-default processes will look like
     * "$PACKAGE_NAME:$NAME", where $NAME corresponds to an android:process
     * attribute within AndroidManifest.xml.
     */
    public static String getProcessName() {
        return ActivityThread.currentProcessName();
    }

    // ------------------ Internal API ------------------

    /**
     * @hide
     */
    @UnsupportedAppUsage
    /* package */ final void attach(Context context) {
        attachBaseContext(context);
        mLoadedApk = ContextImpl.getImpl(context).mPackageInfo;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPreCreated(@NonNull Activity activity,
            @Nullable Bundle savedInstanceState) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPreCreated(activity,
                        savedInstanceState);
            }
        }
    }

    @UnsupportedAppUsage
    /* package */ void dispatchActivityCreated(@NonNull Activity activity,
            @Nullable Bundle savedInstanceState) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityCreated(activity,
                        savedInstanceState);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPostCreated(@NonNull Activity activity,
            @Nullable Bundle savedInstanceState) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPostCreated(activity,
                        savedInstanceState);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPreStarted(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPreStarted(activity);
            }
        }
    }

    @UnsupportedAppUsage
    /* package */ void dispatchActivityStarted(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityStarted(activity);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPostStarted(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPostStarted(activity);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPreResumed(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPreResumed(activity);
            }
        }
    }

    @UnsupportedAppUsage
    /* package */ void dispatchActivityResumed(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityResumed(activity);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPostResumed(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPostResumed(activity);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPrePaused(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPrePaused(activity);
            }
        }
    }

    @UnsupportedAppUsage
    /* package */ void dispatchActivityPaused(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityPaused(activity);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPostPaused(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPostPaused(activity);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPreStopped(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPreStopped(activity);
            }
        }
    }

    @UnsupportedAppUsage
    /* package */ void dispatchActivityStopped(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityStopped(activity);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPostStopped(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPostStopped(activity);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPreSaveInstanceState(@NonNull Activity activity,
            @NonNull Bundle outState) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPreSaveInstanceState(
                        activity, outState);
            }
        }
    }

    @UnsupportedAppUsage
    /* package */ void dispatchActivitySaveInstanceState(@NonNull Activity activity,
            @NonNull Bundle outState) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivitySaveInstanceState(activity,
                        outState);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPostSaveInstanceState(@NonNull Activity activity,
            @NonNull Bundle outState) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPostSaveInstanceState(
                        activity, outState);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPreDestroyed(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPreDestroyed(activity);
            }
        }
    }

    @UnsupportedAppUsage
    /* package */ void dispatchActivityDestroyed(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((ActivityLifecycleCallbacks)callbacks[i]).onActivityDestroyed(activity);
            }
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        /* package */ void dispatchActivityPostDestroyed(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityPostDestroyed(activity);
            }
        }
    }

    /* package */ void dispatchActivityConfigurationChanged(@NonNull Activity activity) {
        Object[] callbacks = collectActivityLifecycleCallbacks();
        if (callbacks != null) {
            for (int i = 0; i < callbacks.length; i++) {
                ((ActivityLifecycleCallbacks) callbacks[i]).onActivityConfigurationChanged(
                        activity);
            }
        }
    }

    @UnsupportedAppUsage
    private Object[] collectActivityLifecycleCallbacks() {
        Object[] callbacks = null;
        synchronized (mActivityLifecycleCallbacks) {
            if (mActivityLifecycleCallbacks.size() > 0) {
                callbacks = mActivityLifecycleCallbacks.toArray();
            }
        }
        return callbacks;
    }

    /* package */ void dispatchOnProvideAssistData(Activity activity, Bundle data) {
        Object[] callbacks;
        synchronized (this) {
            if (mAssistCallbacks == null) {
                return;
            }
            callbacks = mAssistCallbacks.toArray();
        }
        if (callbacks != null) {
            for (int i=0; i<callbacks.length; i++) {
                ((OnProvideAssistDataListener)callbacks[i]).onProvideAssistData(activity, data);
            }
        }
    }

    /** @hide */
    @Override
    public AutofillManager.AutofillClient getAutofillClient() {
        final AutofillManager.AutofillClient client = super.getAutofillClient();
        if (client != null) {
            return client;
        }
        if (android.view.autofill.Helper.sVerbose) {
            Log.v(TAG, "getAutofillClient(): null on super, trying to find activity thread");
        }
        // Okay, ppl use the application context when they should not. This breaks
        // autofill among other things. We pick the focused activity since autofill
        // interacts only with the currently focused activity and we need the fill
        // client only if a call comes from the focused activity. Sigh...
        final ActivityThread activityThread = ActivityThread.currentActivityThread();
        if (activityThread == null) {
            return null;
        }
        final int activityCount = activityThread.mActivities.size();
        for (int i = 0; i < activityCount; i++) {
            final ActivityThread.ActivityClientRecord record =
                    activityThread.mActivities.valueAt(i);
            if (record == null) {
                continue;
            }
            final Activity activity = record.activity;
            if (activity == null) {
                continue;
            }
            if (activity.getWindow().getDecorView().hasFocus()) {
                if (android.view.autofill.Helper.sVerbose) {
                    Log.v(TAG, "getAutofillClient(): found activity for " + this + ": " + activity);
                }
                return activity.getAutofillClient();
            }
        }
        if (android.view.autofill.Helper.sVerbose) {
            Log.v(TAG, "getAutofillClient(): none of the " + activityCount + " activities on "
                    + this + " have focus");
        }
        return null;
    }
}
