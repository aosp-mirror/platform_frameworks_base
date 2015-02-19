/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.trust;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.SystemService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.Manifest;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustListener;
import android.app.trust.ITrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.trust.TrustAgentService;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.Xml;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages trust agents and trust listeners.
 *
 * It is responsible for binding to the enabled {@link android.service.trust.TrustAgentService}s
 * of each user and notifies them about events that are relevant to them.
 * It start and stops them based on the value of
 * {@link com.android.internal.widget.LockPatternUtils#getEnabledTrustAgents(int)}.
 *
 * It also keeps a set of {@link android.app.trust.ITrustListener}s that are notified whenever the
 * trust state changes for any user.
 *
 * Trust state and the setting of enabled agents is kept per user and each user has its own
 * instance of a {@link android.service.trust.TrustAgentService}.
 */
public class TrustManagerService extends SystemService {

    private static final boolean DEBUG = false;
    private static final String TAG = "TrustManagerService";

    private static final Intent TRUST_AGENT_INTENT =
            new Intent(TrustAgentService.SERVICE_INTERFACE);
    private static final String PERMISSION_PROVIDE_AGENT = Manifest.permission.PROVIDE_TRUST_AGENT;

    private static final int MSG_REGISTER_LISTENER = 1;
    private static final int MSG_UNREGISTER_LISTENER = 2;
    private static final int MSG_DISPATCH_UNLOCK_ATTEMPT = 3;
    private static final int MSG_ENABLED_AGENTS_CHANGED = 4;
    private static final int MSG_REQUIRE_CREDENTIAL_ENTRY = 5;
    private static final int MSG_KEYGUARD_SHOWING_CHANGED = 6;
    private static final int MSG_START_USER = 7;
    private static final int MSG_CLEANUP_USER = 8;
    private static final int MSG_SWITCH_USER = 9;

    private final ArraySet<AgentInfo> mActiveAgents = new ArraySet<AgentInfo>();
    private final ArrayList<ITrustListener> mTrustListeners = new ArrayList<ITrustListener>();
    private final Receiver mReceiver = new Receiver();
    private final SparseBooleanArray mUserHasAuthenticatedSinceBoot = new SparseBooleanArray();
    /* package */ final TrustArchive mArchive = new TrustArchive();
    private final Context mContext;
    private final LockPatternUtils mLockPatternUtils;
    private final UserManager mUserManager;
    private final ActivityManager mActivityManager;

    @GuardedBy("mUserIsTrusted")
    private final SparseBooleanArray mUserIsTrusted = new SparseBooleanArray();

    @GuardedBy("mDeviceLockedForUser")
    private final SparseBooleanArray mDeviceLockedForUser = new SparseBooleanArray();

    private boolean mTrustAgentsCanRun = false;
    private int mCurrentUser = UserHandle.USER_OWNER;

    public TrustManagerService(Context context) {
        super(context);
        mContext = context;
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mLockPatternUtils = new LockPatternUtils(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.TRUST_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (isSafeMode()) {
            // No trust agents in safe mode.
            return;
        }
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mPackageMonitor.register(mContext, mHandler.getLooper(), UserHandle.ALL, true);
            mReceiver.register(mContext);
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            mTrustAgentsCanRun = true;
            refreshAgentList(UserHandle.USER_ALL);
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            maybeEnableFactoryTrustAgents(mLockPatternUtils, UserHandle.USER_OWNER);
        }
    }

    // Agent management

    private static final class AgentInfo {
        CharSequence label;
        Drawable icon;
        ComponentName component; // service that implements ITrustAgent
        ComponentName settings; // setting to launch to modify agent.
        TrustAgentWrapper agent;
        int userId;

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof AgentInfo)) {
                return false;
            }
            AgentInfo o = (AgentInfo) other;
            return component.equals(o.component) && userId == o.userId;
        }

        @Override
        public int hashCode() {
            return component.hashCode() * 31 + userId;
        }
    }

    private void updateTrustAll() {
        List<UserInfo> userInfos = mUserManager.getUsers(true /* excludeDying */);
        for (UserInfo userInfo : userInfos) {
            updateTrust(userInfo.id, false);
        }
    }

    public void updateTrust(int userId, boolean initiatedByUser) {
        dispatchOnTrustManagedChanged(aggregateIsTrustManaged(userId), userId);
        boolean trusted = aggregateIsTrusted(userId);
        boolean changed;
        synchronized (mUserIsTrusted) {
            changed = mUserIsTrusted.get(userId) != trusted;
            mUserIsTrusted.put(userId, trusted);
        }
        dispatchOnTrustChanged(trusted, userId, initiatedByUser);
        if (changed) {
            refreshDeviceLockedForUser(userId);
        }
    }

    void refreshAgentList(int userId) {
        if (DEBUG) Slog.d(TAG, "refreshAgentList()");
        if (!mTrustAgentsCanRun) {
            return;
        }
        if (userId != UserHandle.USER_ALL && userId < UserHandle.USER_OWNER) {
            Log.e(TAG, "refreshAgentList(userId=" + userId + "): Invalid user handle,"
                    + " must be USER_ALL or a specific user.", new Throwable("here"));
            userId = UserHandle.USER_ALL;
        }
        PackageManager pm = mContext.getPackageManager();

        List<UserInfo> userInfos;
        if (userId == UserHandle.USER_ALL) {
            userInfos = mUserManager.getUsers(true /* excludeDying */);
        } else {
            userInfos = new ArrayList<>();
            userInfos.add(mUserManager.getUserInfo(userId));
        }
        LockPatternUtils lockPatternUtils = mLockPatternUtils;

        ArraySet<AgentInfo> obsoleteAgents = new ArraySet<>();
        obsoleteAgents.addAll(mActiveAgents);

        for (UserInfo userInfo : userInfos) {
            if (userInfo == null || userInfo.partial || !userInfo.isEnabled()
                    || userInfo.guestToRemove) continue;
            if (!userInfo.supportsSwitchTo()) continue;
            if (!mActivityManager.isUserRunning(userInfo.id)) continue;
            if (!lockPatternUtils.isSecure(userInfo.id)) continue;
            if (!mUserHasAuthenticatedSinceBoot.get(userInfo.id)) continue;
            DevicePolicyManager dpm = lockPatternUtils.getDevicePolicyManager();
            int disabledFeatures = dpm.getKeyguardDisabledFeatures(null, userInfo.id);
            final boolean disableTrustAgents =
                    (disabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS) != 0;

            List<ComponentName> enabledAgents = lockPatternUtils.getEnabledTrustAgents(userInfo.id);
            if (enabledAgents == null) {
                continue;
            }
            List<ResolveInfo> resolveInfos = resolveAllowedTrustAgents(pm, userInfo.id);
            for (ResolveInfo resolveInfo : resolveInfos) {
                ComponentName name = getComponentName(resolveInfo);

                if (!enabledAgents.contains(name)) continue;
                if (disableTrustAgents) {
                    List<PersistableBundle> config =
                            dpm.getTrustAgentConfiguration(null /* admin */, name, userInfo.id);
                    // Disable agent if no features are enabled.
                    if (config == null || config.isEmpty()) continue;
                }

                AgentInfo agentInfo = new AgentInfo();
                agentInfo.component = name;
                agentInfo.userId = userInfo.id;
                if (!mActiveAgents.contains(agentInfo)) {
                    agentInfo.label = resolveInfo.loadLabel(pm);
                    agentInfo.icon = resolveInfo.loadIcon(pm);
                    agentInfo.settings = getSettingsComponentName(pm, resolveInfo);
                    agentInfo.agent = new TrustAgentWrapper(mContext, this,
                            new Intent().setComponent(name), userInfo.getUserHandle());
                    mActiveAgents.add(agentInfo);
                } else {
                    obsoleteAgents.remove(agentInfo);
                }
            }
        }

        boolean trustMayHaveChanged = false;
        for (int i = 0; i < obsoleteAgents.size(); i++) {
            AgentInfo info = obsoleteAgents.valueAt(i);
            if (userId == UserHandle.USER_ALL || userId == info.userId) {
                if (info.agent.isManagingTrust()) {
                    trustMayHaveChanged = true;
                }
                info.agent.destroy();
                mActiveAgents.remove(info);
            }
        }

        if (trustMayHaveChanged) {
            if (userId == UserHandle.USER_ALL) {
                updateTrustAll();
            } else {
                updateTrust(userId, false /* initiatedByUser */);
            }
        }
    }

    boolean isDeviceLockedInner(int userId) {
        synchronized (mDeviceLockedForUser) {
            return mDeviceLockedForUser.get(userId, true);
        }
    }

    private void refreshDeviceLockedForUser(int userId) {
        if (userId != UserHandle.USER_ALL && userId < UserHandle.USER_OWNER) {
            Log.e(TAG, "refreshDeviceLockedForUser(userId=" + userId + "): Invalid user handle,"
                    + " must be USER_ALL or a specific user.", new Throwable("here"));
            userId = UserHandle.USER_ALL;
        }

        List<UserInfo> userInfos;
        if (userId == UserHandle.USER_ALL) {
            userInfos = mUserManager.getUsers(true /* excludeDying */);
        } else {
            userInfos = new ArrayList<>();
            userInfos.add(mUserManager.getUserInfo(userId));
        }

        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();

        for (int i = 0; i < userInfos.size(); i++) {
            UserInfo info = userInfos.get(i);

            if (info == null || info.partial || !info.isEnabled() || info.guestToRemove
                    || !info.supportsSwitchTo()) {
                continue;
            }

            int id = info.id;
            boolean secure = mLockPatternUtils.isSecure(id);
            boolean trusted = aggregateIsTrusted(id);
            boolean showingKeyguard = true;
            if (mCurrentUser == id) {
                try {
                    showingKeyguard = wm.isKeyguardLocked();
                } catch (RemoteException e) {
                }
            }
            boolean deviceLocked = secure && showingKeyguard && !trusted;

            boolean changed;
            synchronized (mDeviceLockedForUser) {
                changed = isDeviceLockedInner(id) != deviceLocked;
                mDeviceLockedForUser.put(id, deviceLocked);
            }
            if (changed) {
                dispatchDeviceLocked(id, deviceLocked);
            }
        }
    }

    private void dispatchDeviceLocked(int userId, boolean isLocked) {
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo agent = mActiveAgents.valueAt(i);
            if (agent.userId == userId) {
                if (isLocked) {
                    agent.agent.onDeviceLocked();
                } else{
                    agent.agent.onDeviceUnlocked();
                }
            }
        }
    }

    void updateDevicePolicyFeatures() {
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.agent.isConnected()) {
                info.agent.updateDevicePolicyFeatures();
            }
        }
    }

    private void removeAgentsOfPackage(String packageName) {
        boolean trustMayHaveChanged = false;
        for (int i = mActiveAgents.size() - 1; i >= 0; i--) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (packageName.equals(info.component.getPackageName())) {
                Log.i(TAG, "Resetting agent " + info.component.flattenToShortString());
                if (info.agent.isManagingTrust()) {
                    trustMayHaveChanged = true;
                }
                info.agent.destroy();
                mActiveAgents.removeAt(i);
            }
        }
        if (trustMayHaveChanged) {
            updateTrustAll();
        }
    }

    public void resetAgent(ComponentName name, int userId) {
        boolean trustMayHaveChanged = false;
        for (int i = mActiveAgents.size() - 1; i >= 0; i--) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (name.equals(info.component) && userId == info.userId) {
                Log.i(TAG, "Resetting agent " + info.component.flattenToShortString());
                if (info.agent.isManagingTrust()) {
                    trustMayHaveChanged = true;
                }
                info.agent.destroy();
                mActiveAgents.removeAt(i);
            }
        }
        if (trustMayHaveChanged) {
            updateTrust(userId, false);
        }
        refreshAgentList(userId);
    }

    private ComponentName getSettingsComponentName(PackageManager pm, ResolveInfo resolveInfo) {
        if (resolveInfo == null || resolveInfo.serviceInfo == null
                || resolveInfo.serviceInfo.metaData == null) return null;
        String cn = null;
        XmlResourceParser parser = null;
        Exception caughtException = null;
        try {
            parser = resolveInfo.serviceInfo.loadXmlMetaData(pm,
                    TrustAgentService.TRUST_AGENT_META_DATA);
            if (parser == null) {
                Slog.w(TAG, "Can't find " + TrustAgentService.TRUST_AGENT_META_DATA + " meta-data");
                return null;
            }
            Resources res = pm.getResourcesForApplication(resolveInfo.serviceInfo.applicationInfo);
            AttributeSet attrs = Xml.asAttributeSet(parser);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
                // Drain preamble.
            }
            String nodeName = parser.getName();
            if (!"trust-agent".equals(nodeName)) {
                Slog.w(TAG, "Meta-data does not start with trust-agent tag");
                return null;
            }
            TypedArray sa = res
                    .obtainAttributes(attrs, com.android.internal.R.styleable.TrustAgent);
            cn = sa.getString(com.android.internal.R.styleable.TrustAgent_settingsActivity);
            sa.recycle();
        } catch (PackageManager.NameNotFoundException e) {
            caughtException = e;
        } catch (IOException e) {
            caughtException = e;
        } catch (XmlPullParserException e) {
            caughtException = e;
        } finally {
            if (parser != null) parser.close();
        }
        if (caughtException != null) {
            Slog.w(TAG, "Error parsing : " + resolveInfo.serviceInfo.packageName, caughtException);
            return null;
        }
        if (cn == null) {
            return null;
        }
        if (cn.indexOf('/') < 0) {
            cn = resolveInfo.serviceInfo.packageName + "/" + cn;
        }
        return ComponentName.unflattenFromString(cn);
    }

    private ComponentName getComponentName(ResolveInfo resolveInfo) {
        if (resolveInfo == null || resolveInfo.serviceInfo == null) return null;
        return new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
    }

    private void maybeEnableFactoryTrustAgents(LockPatternUtils utils, int userId) {
        if (0 != Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.TRUST_AGENTS_INITIALIZED, 0, userId)) {
            return;
        }
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = resolveAllowedTrustAgents(pm, userId);
        ArraySet<ComponentName> discoveredAgents = new ArraySet<>();
        for (ResolveInfo resolveInfo : resolveInfos) {
            ComponentName componentName = getComponentName(resolveInfo);
            int applicationInfoFlags = resolveInfo.serviceInfo.applicationInfo.flags;
            if ((applicationInfoFlags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                Log.i(TAG, "Leaving agent " + componentName + " disabled because package "
                        + "is not a system package.");
                continue;
            }
            discoveredAgents.add(componentName);
        }

        List<ComponentName> previouslyEnabledAgents = utils.getEnabledTrustAgents(userId);
        if (previouslyEnabledAgents != null) {
            discoveredAgents.addAll(previouslyEnabledAgents);
        }
        utils.setEnabledTrustAgents(discoveredAgents, userId);
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.TRUST_AGENTS_INITIALIZED, 1, userId);
    }

    private List<ResolveInfo> resolveAllowedTrustAgents(PackageManager pm, int userId) {
        List<ResolveInfo> resolveInfos = pm.queryIntentServicesAsUser(TRUST_AGENT_INTENT,
                0 /* flags */, userId);
        ArrayList<ResolveInfo> allowedAgents = new ArrayList<>(resolveInfos.size());
        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo.serviceInfo == null) continue;
            if (resolveInfo.serviceInfo.applicationInfo == null) continue;
            String packageName = resolveInfo.serviceInfo.packageName;
            if (pm.checkPermission(PERMISSION_PROVIDE_AGENT, packageName)
                    != PackageManager.PERMISSION_GRANTED) {
                ComponentName name = getComponentName(resolveInfo);
                Log.w(TAG, "Skipping agent " + name + " because package does not have"
                        + " permission " + PERMISSION_PROVIDE_AGENT + ".");
                continue;
            }
            allowedAgents.add(resolveInfo);
        }
        return allowedAgents;
    }

    // Agent dispatch and aggregation

    private boolean aggregateIsTrusted(int userId) {
        if (!mUserHasAuthenticatedSinceBoot.get(userId)) {
            return false;
        }
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                if (info.agent.isTrusted()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean aggregateIsTrustManaged(int userId) {
        if (!mUserHasAuthenticatedSinceBoot.get(userId)) {
            return false;
        }
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                if (info.agent.isManagingTrust()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void dispatchUnlockAttempt(boolean successful, int userId) {
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                info.agent.onUnlockAttempt(successful);
            }
        }

        if (successful) {
            updateUserHasAuthenticated(userId);
        }
    }

    private void updateUserHasAuthenticated(int userId) {
        if (!mUserHasAuthenticatedSinceBoot.get(userId)) {
            mUserHasAuthenticatedSinceBoot.put(userId, true);
            refreshAgentList(userId);
        }
    }


    private void requireCredentialEntry(int userId) {
        if (userId == UserHandle.USER_ALL) {
            mUserHasAuthenticatedSinceBoot.clear();
            refreshAgentList(UserHandle.USER_ALL);
        } else {
            mUserHasAuthenticatedSinceBoot.put(userId, false);
            refreshAgentList(userId);
        }
    }

    // Listeners

    private void addListener(ITrustListener listener) {
        for (int i = 0; i < mTrustListeners.size(); i++) {
            if (mTrustListeners.get(i).asBinder() == listener.asBinder()) {
                return;
            }
        }
        mTrustListeners.add(listener);
        updateTrustAll();
    }

    private void removeListener(ITrustListener listener) {
        for (int i = 0; i < mTrustListeners.size(); i++) {
            if (mTrustListeners.get(i).asBinder() == listener.asBinder()) {
                mTrustListeners.remove(i);
                return;
            }
        }
    }

    private void dispatchOnTrustChanged(boolean enabled, int userId, boolean initiatedByUser) {
        if (!enabled) initiatedByUser = false;
        for (int i = 0; i < mTrustListeners.size(); i++) {
            try {
                mTrustListeners.get(i).onTrustChanged(enabled, userId, initiatedByUser);
            } catch (DeadObjectException e) {
                Slog.d(TAG, "Removing dead TrustListener.");
                mTrustListeners.remove(i);
                i--;
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while notifying TrustListener.", e);
            }
        }
    }

    private void dispatchOnTrustManagedChanged(boolean managed, int userId) {
        for (int i = 0; i < mTrustListeners.size(); i++) {
            try {
                mTrustListeners.get(i).onTrustManagedChanged(managed, userId);
            } catch (DeadObjectException e) {
                Slog.d(TAG, "Removing dead TrustListener.");
                mTrustListeners.remove(i);
                i--;
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while notifying TrustListener.", e);
            }
        }
    }

    // User lifecycle

    @Override
    public void onStartUser(int userId) {
        mHandler.obtainMessage(MSG_START_USER, userId, 0, null).sendToTarget();
    }

    @Override
    public void onCleanupUser(int userId) {
        mHandler.obtainMessage(MSG_CLEANUP_USER, userId, 0, null).sendToTarget();
    }

    @Override
    public void onSwitchUser(int userId) {
        mHandler.obtainMessage(MSG_SWITCH_USER, userId, 0, null).sendToTarget();
    }

    // Plumbing

    private final IBinder mService = new ITrustManager.Stub() {
        @Override
        public void reportUnlockAttempt(boolean authenticated, int userId) throws RemoteException {
            enforceReportPermission();
            mHandler.obtainMessage(MSG_DISPATCH_UNLOCK_ATTEMPT, authenticated ? 1 : 0, userId)
                    .sendToTarget();
        }

        @Override
        public void reportEnabledTrustAgentsChanged(int userId) throws RemoteException {
            enforceReportPermission();
            // coalesce refresh messages.
            mHandler.removeMessages(MSG_ENABLED_AGENTS_CHANGED);
            mHandler.sendEmptyMessage(MSG_ENABLED_AGENTS_CHANGED);
        }

        @Override
        public void reportRequireCredentialEntry(int userId) throws RemoteException {
            enforceReportPermission();
            if (userId == UserHandle.USER_ALL || userId >= UserHandle.USER_OWNER) {
                mHandler.obtainMessage(MSG_REQUIRE_CREDENTIAL_ENTRY, userId, 0).sendToTarget();
            } else {
                throw new IllegalArgumentException(
                        "userId must be an explicit user id or USER_ALL");
            }
        }

        @Override
        public void reportKeyguardShowingChanged() throws RemoteException {
            enforceReportPermission();
            // coalesce refresh messages.
            mHandler.removeMessages(MSG_KEYGUARD_SHOWING_CHANGED);
            mHandler.sendEmptyMessage(MSG_KEYGUARD_SHOWING_CHANGED);
        }

        @Override
        public void registerTrustListener(ITrustListener trustListener) throws RemoteException {
            enforceListenerPermission();
            mHandler.obtainMessage(MSG_REGISTER_LISTENER, trustListener).sendToTarget();
        }

        @Override
        public void unregisterTrustListener(ITrustListener trustListener) throws RemoteException {
            enforceListenerPermission();
            mHandler.obtainMessage(MSG_UNREGISTER_LISTENER, trustListener).sendToTarget();
        }

        @Override
        public boolean isDeviceLocked(int userId) throws RemoteException {
            userId = ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(), userId,
                    false /* allowAll */, true /* requireFull */, "isDeviceLocked", null);
            userId = resolveProfileParent(userId);

            return isDeviceLockedInner(userId);
        }

        private void enforceReportPermission() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE, "reporting trust events");
        }

        private void enforceListenerPermission() {
            mContext.enforceCallingPermission(Manifest.permission.TRUST_LISTENER,
                    "register trust listener");
        }

        @Override
        protected void dump(FileDescriptor fd, final PrintWriter fout, String[] args) {
            mContext.enforceCallingPermission(Manifest.permission.DUMP,
                    "dumping TrustManagerService");
            if (isSafeMode()) {
                fout.println("disabled because the system is in safe mode.");
                return;
            }
            if (!mTrustAgentsCanRun) {
                fout.println("disabled because the third-party apps can't run yet.");
                return;
            }
            final List<UserInfo> userInfos = mUserManager.getUsers(true /* excludeDying */);
            mHandler.runWithScissors(new Runnable() {
                @Override
                public void run() {
                    fout.println("Trust manager state:");
                    for (UserInfo user : userInfos) {
                        dumpUser(fout, user, user.id == mCurrentUser);
                    }
                }
            }, 1500);
        }

        private void dumpUser(PrintWriter fout, UserInfo user, boolean isCurrent) {
            fout.printf(" User \"%s\" (id=%d, flags=%#x)",
                    user.name, user.id, user.flags);
            if (!user.supportsSwitchTo()) {
                fout.println("(managed profile)");
                fout.println("   disabled because switching to this user is not possible.");
                return;
            }
            if (isCurrent) {
                fout.print(" (current)");
            }
            fout.print(": trusted=" + dumpBool(aggregateIsTrusted(user.id)));
            fout.print(", trustManaged=" + dumpBool(aggregateIsTrustManaged(user.id)));
            fout.print(", deviceLocked=" + dumpBool(isDeviceLockedInner(user.id)));
            fout.println();
            fout.println("   Enabled agents:");
            boolean duplicateSimpleNames = false;
            ArraySet<String> simpleNames = new ArraySet<String>();
            for (AgentInfo info : mActiveAgents) {
                if (info.userId != user.id) { continue; }
                boolean trusted = info.agent.isTrusted();
                fout.print("    "); fout.println(info.component.flattenToShortString());
                fout.print("     bound=" + dumpBool(info.agent.isBound()));
                fout.print(", connected=" + dumpBool(info.agent.isConnected()));
                fout.print(", managingTrust=" + dumpBool(info.agent.isManagingTrust()));
                fout.print(", trusted=" + dumpBool(trusted));
                fout.println();
                if (trusted) {
                    fout.println("      message=\"" + info.agent.getMessage() + "\"");
                }
                if (!info.agent.isConnected()) {
                    String restartTime = TrustArchive.formatDuration(
                            info.agent.getScheduledRestartUptimeMillis()
                                    - SystemClock.uptimeMillis());
                    fout.println("      restartScheduledAt=" + restartTime);
                }
                if (!simpleNames.add(TrustArchive.getSimpleName(info.component))) {
                    duplicateSimpleNames = true;
                }
            }
            fout.println("   Events:");
            mArchive.dump(fout, 50, user.id, "    " /* linePrefix */, duplicateSimpleNames);
            fout.println();
        }

        private String dumpBool(boolean b) {
            return b ? "1" : "0";
        }
    };

    private int resolveProfileParent(int userId) {
        long identity = Binder.clearCallingIdentity();
        try {
            UserInfo parent = mUserManager.getProfileParent(userId);
            if (parent != null) {
                return parent.getUserHandle().getIdentifier();
            }
            return userId;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_LISTENER:
                    addListener((ITrustListener) msg.obj);
                    break;
                case MSG_UNREGISTER_LISTENER:
                    removeListener((ITrustListener) msg.obj);
                    break;
                case MSG_DISPATCH_UNLOCK_ATTEMPT:
                    dispatchUnlockAttempt(msg.arg1 != 0, msg.arg2);
                    break;
                case MSG_ENABLED_AGENTS_CHANGED:
                    refreshAgentList(UserHandle.USER_ALL);
                    // This is also called when the security mode of a user changes.
                    refreshDeviceLockedForUser(UserHandle.USER_ALL);
                    break;
                case MSG_REQUIRE_CREDENTIAL_ENTRY:
                    requireCredentialEntry(msg.arg1);
                    break;
                case MSG_KEYGUARD_SHOWING_CHANGED:
                    refreshDeviceLockedForUser(mCurrentUser);
                    break;
                case MSG_START_USER:
                case MSG_CLEANUP_USER:
                    refreshAgentList(msg.arg1);
                    break;
                case MSG_SWITCH_USER:
                    mCurrentUser = msg.arg1;
                    refreshDeviceLockedForUser(UserHandle.USER_ALL);
                    break;
            }
        }
    };

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onSomePackagesChanged() {
            refreshAgentList(UserHandle.USER_ALL);
        }

        @Override
        public boolean onPackageChanged(String packageName, int uid, String[] components) {
            // We're interested in all changes, even if just some components get enabled / disabled.
            return true;
        }

        @Override
        public void onPackageDisappeared(String packageName, int reason) {
            removeAgentsOfPackage(packageName);
        }
    };

    private class Receiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED.equals(action)) {
                refreshAgentList(getSendingUserId());
                updateDevicePolicyFeatures();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                updateUserHasAuthenticated(getSendingUserId());
            } else if (Intent.ACTION_USER_ADDED.equals(action)) {
                int userId = getUserId(intent);
                if (userId > 0) {
                    maybeEnableFactoryTrustAgents(mLockPatternUtils, userId);
                }
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                int userId = getUserId(intent);
                if (userId > 0) {
                    mUserHasAuthenticatedSinceBoot.delete(userId);
                    synchronized (mUserIsTrusted) {
                        mUserIsTrusted.delete(userId);
                    }
                    synchronized (mDeviceLockedForUser) {
                        mDeviceLockedForUser.delete(userId);
                    }
                    refreshAgentList(userId);
                    refreshDeviceLockedForUser(userId);
                }
            }
        }

        private int getUserId(Intent intent) {
            int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -100);
            if (userId > 0) {
                return userId;
            } else {
                Slog.wtf(TAG, "EXTRA_USER_HANDLE missing or invalid, value=" + userId);
                return -100;
            }
        }

        public void register(Context context) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_USER_ADDED);
            filter.addAction(Intent.ACTION_USER_REMOVED);
            context.registerReceiverAsUser(this,
                    UserHandle.ALL,
                    filter,
                    null /* permission */,
                    null /* scheduler */);
        }
    }
}
