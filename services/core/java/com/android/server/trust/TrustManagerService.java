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

import com.android.internal.content.PackageMonitor;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.SystemService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.Manifest;
import android.app.trust.ITrustListener;
import android.app.trust.ITrustManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.trust.TrustAgentService;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.Xml;

import java.io.IOException;
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

    private static final int MSG_REGISTER_LISTENER = 1;
    private static final int MSG_UNREGISTER_LISTENER = 2;
    private static final int MSG_DISPATCH_UNLOCK_ATTEMPT = 3;
    private static final int MSG_ENABLED_AGENTS_CHANGED = 4;

    private final ArraySet<AgentInfo> mActiveAgents = new ArraySet<AgentInfo>();
    private final ArrayList<ITrustListener> mTrustListeners = new ArrayList<ITrustListener>();
    private final Context mContext;

    private UserManager mUserManager;

    /**
     * Cache for {@link #refreshAgentList()}
     */
    private final ArraySet<AgentInfo> mObsoleteAgents = new ArraySet<AgentInfo>();


    public TrustManagerService(Context context) {
        super(context);
        mContext = context;
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.TRUST_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY && !isSafeMode()) {
            // Listen for package changes
            mPackageMonitor.register(mContext, mHandler.getLooper(), UserHandle.ALL, true);
            refreshAgentList();
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
            updateTrust(userInfo.id);
        }
    }

    public void updateTrust(int userId) {
        dispatchOnTrustChanged(aggregateIsTrusted(userId), userId);
    }

    protected void refreshAgentList() {
        if (DEBUG) Slog.d(TAG, "refreshAgentList()");
        PackageManager pm = mContext.getPackageManager();

        List<UserInfo> userInfos = mUserManager.getUsers(true /* excludeDying */);
        LockPatternUtils lockPatternUtils = new LockPatternUtils(mContext);

        mObsoleteAgents.clear();
        mObsoleteAgents.addAll(mActiveAgents);

        for (UserInfo userInfo : userInfos) {
            List<ComponentName> enabledAgents = lockPatternUtils.getEnabledTrustAgents(userInfo.id);
            if (enabledAgents == null) {
                continue;
            }
            List<ResolveInfo> resolveInfos = pm.queryIntentServicesAsUser(TRUST_AGENT_INTENT,
                    PackageManager.GET_META_DATA, userInfo.id);
            for (ResolveInfo resolveInfo : resolveInfos) {
                if (resolveInfo.serviceInfo == null) continue;
                ComponentName name = getComponentName(resolveInfo);
                if (!enabledAgents.contains(name)) continue;

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
                    mObsoleteAgents.remove(agentInfo);
                }
            }
        }

        boolean trustMayHaveChanged = false;
        for (int i = 0; i < mObsoleteAgents.size(); i++) {
            AgentInfo info = mObsoleteAgents.valueAt(i);
            if (info.agent.isTrusted()) {
                trustMayHaveChanged = true;
            }
            info.agent.unbind();
            mActiveAgents.remove(info);
        }

        if (trustMayHaveChanged) {
            updateTrustAll();
        }
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

    // Agent dispatch and aggregation

    private boolean aggregateIsTrusted(int userId) {
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

    private void dispatchUnlockAttempt(boolean successful, int userId) {
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                info.agent.onUnlockAttempt(successful);
            }
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
    }

    private void removeListener(ITrustListener listener) {
        for (int i = 0; i < mTrustListeners.size(); i++) {
            if (mTrustListeners.get(i).asBinder() == listener.asBinder()) {
                mTrustListeners.get(i);
                return;
            }
        }
    }

    private void dispatchOnTrustChanged(boolean enabled, int userId) {
        for (int i = 0; i < mTrustListeners.size(); i++) {
            try {
                mTrustListeners.get(i).onTrustChanged(enabled, userId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while notifying TrustListener. Removing listener.", e);
                mTrustListeners.get(i);
                i--;
            }
        }
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
        public void registerTrustListener(ITrustListener trustListener) throws RemoteException {
            enforceListenerPermission();
            mHandler.obtainMessage(MSG_REGISTER_LISTENER, trustListener).sendToTarget();
        }

        @Override
        public void unregisterTrustListener(ITrustListener trustListener) throws RemoteException {
            enforceListenerPermission();
            mHandler.obtainMessage(MSG_UNREGISTER_LISTENER, trustListener).sendToTarget();
        }

        private void enforceReportPermission() {
            mContext.enforceCallingPermission(Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE,
                    "reporting trust events");
        }

        private void enforceListenerPermission() {
            mContext.enforceCallingPermission(Manifest.permission.TRUST_LISTENER,
                    "register trust listener");
        }
    };

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
                    refreshAgentList();
                    break;
            }
        }
    };

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onSomePackagesChanged() {
            refreshAgentList();
        }

        @Override
        public boolean onPackageChanged(String packageName, int uid, String[] components) {
            // We're interested in all changes, even if just some components get enabled / disabled.
            return true;
        }
    };
}
