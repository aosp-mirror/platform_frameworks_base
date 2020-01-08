package com.android.server.usage;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.usage.AppStandbyInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager.StandbyBuckets;
import android.content.Context;
import android.os.Looper;

import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;

public interface AppStandbyInternal {
    /**
     * TODO AppStandbyController should probably be a binder service, and then we shouldn't need
     * this method.
     */
    static AppStandbyInternal newAppStandbyController(ClassLoader loader, Context context,
            Looper looper) {
        try {
            final Class<?> clazz = Class.forName("com.android.server.usage.AppStandbyController",
                    true, loader);
            final Constructor<?> ctor =  clazz.getConstructor(Context.class, Looper.class);
            return (AppStandbyInternal) ctor.newInstance(context, looper);
        } catch (NoSuchMethodException | InstantiationException
                | IllegalAccessException | InvocationTargetException | ClassNotFoundException e) {
            throw new RuntimeException("Unable to instantiate AppStandbyController!", e);
        }
    }

    /**
     * Listener interface for notifications that an app's idle state changed.
     */
    abstract static class AppIdleStateChangeListener {

        /** Callback to inform listeners that the idle state has changed to a new bucket. */
        public abstract void onAppIdleStateChanged(String packageName, @UserIdInt int userId,
                boolean idle, int bucket, int reason);

        /**
         * Optional callback to inform the listener that the app has transitioned into
         * an active state due to user interaction.
         */
        public void onUserInteractionStarted(String packageName, @UserIdInt int userId) {
            // No-op by default
        }
    }

    void onBootPhase(int phase);

    void postCheckIdleStates(int userId);

    /**
     * We send a different message to check idle states once, otherwise we would end up
     * scheduling a series of repeating checkIdleStates each time we fired off one.
     */
    void postOneTimeCheckIdleStates();

    void reportEvent(UsageEvents.Event event, long elapsedRealtime, int userId);

    void setLastJobRunTime(String packageName, int userId, long elapsedRealtime);

    long getTimeSinceLastJobRun(String packageName, int userId);

    void onUserRemoved(int userId);

    void addListener(AppIdleStateChangeListener listener);

    void removeListener(AppIdleStateChangeListener listener);

    int getAppId(String packageName);

    /**
     * @see #isAppIdleFiltered(String, int, int, long)
     */
    boolean isAppIdleFiltered(String packageName, int userId, long elapsedRealtime,
            boolean shouldObfuscateInstantApps);

    /**
     * Checks if an app has been idle for a while and filters out apps that are excluded.
     * It returns false if the current system state allows all apps to be considered active.
     * Called by interface impls.
     */
    boolean isAppIdleFiltered(String packageName, int appId, int userId,
            long elapsedRealtime);

    int[] getIdleUidsForUser(int userId);

    void setAppIdleAsync(String packageName, boolean idle, int userId);

    @StandbyBuckets
    int getAppStandbyBucket(String packageName, int userId,
            long elapsedRealtime, boolean shouldObfuscateInstantApps);

    List<AppStandbyInfo> getAppStandbyBuckets(int userId);

    /**
     * Changes an app's standby bucket to the provided value. The caller can only set the standby
     * bucket for a different app than itself.
     */
    void setAppStandbyBucket(@NonNull String packageName, int bucket, int userId, int callingUid,
            int callingPid);

    /**
     * Changes the app standby bucket for multiple apps at once.
     */
    void setAppStandbyBuckets(@NonNull List<AppStandbyInfo> appBuckets, int userId, int callingUid,
            int callingPid);

    void addActiveDeviceAdmin(String adminPkg, int userId);

    void setActiveAdminApps(Set<String> adminPkgs, int userId);

    void onAdminDataAvailable();

    void clearCarrierPrivilegedApps();

    void flushToDisk(int userId);

    void flushDurationsToDisk();

    void initializeDefaultsForSystemApps(int userId);

    void postReportContentProviderUsage(String name, String packageName, int userId);

    void postReportSyncScheduled(String packageName, int userId, boolean exempted);

    void postReportExemptedSyncStart(String packageName, int userId);

    void dumpUser(IndentingPrintWriter idpw, int userId, String pkg);

    void dumpState(String[] args, PrintWriter pw);

    boolean isAppIdleEnabled();
}
