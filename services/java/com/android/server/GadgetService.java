/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageItemInfo;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.gadget.GadgetManager;
import android.gadget.GadgetProviderInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Config;
import android.util.Log;
import android.util.Xml;
import android.widget.RemoteViews;

import java.io.IOException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;

import com.android.internal.gadget.IGadgetService;
import com.android.internal.gadget.IGadgetHost;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

class GadgetService extends IGadgetService.Stub
{
    private static final String TAG = "GadgetService";
    private static final boolean LOGD = Config.LOGD || false;

    private static final String SETTINGS_FILENAME = "gadgets.xml";
    private static final String SETTINGS_TMP_FILENAME = SETTINGS_FILENAME + ".tmp";

    /*
     * When identifying a Host or Provider based on the calling process, use the uid field.
     * When identifying a Host or Provider based on a package manager broadcast, use the
     * package given.
     */

    static class Provider {
        int uid;
        GadgetProviderInfo info;
        ArrayList<GadgetId> instances = new ArrayList();
        PendingIntent broadcast;
        
        int tag;    // for use while saving state (the index)
    }

    static class Host {
        int uid;
        int hostId;
        String packageName;
        ArrayList<GadgetId> instances = new ArrayList();
        IGadgetHost callbacks;
        
        int tag;    // for use while saving state (the index)
    }

    static class GadgetId {
        int gadgetId;
        Provider provider;
        RemoteViews views;
        Host host;
    }

    Context mContext;
    PackageManager mPackageManager;
    AlarmManager mAlarmManager;
    ArrayList<Provider> mInstalledProviders = new ArrayList();
    int mNextGadgetId = GadgetManager.INVALID_GADGET_ID + 1;
    ArrayList<GadgetId> mGadgetIds = new ArrayList();
    ArrayList<Host> mHosts = new ArrayList();

    GadgetService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        
        getGadgetList();
        loadStateLocked();

        // Register for the boot completed broadcast, so we can send the
        // ENABLE broacasts.  If we try to send them now, they time out,
        // because the system isn't ready to handle them yet.
        mContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);

        // Register for broadcasts about package install, etc., so we can
        // update the provider list.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (mGadgetIds) {
            int N = mInstalledProviders.size();
            pw.println("Providers: (size=" + N + ")");
            for (int i=0; i<N; i++) {
                GadgetProviderInfo info = mInstalledProviders.get(i).info;
                pw.println("  [" + i + "] provder=" + info.provider
                        + " min=(" + info.minWidth + "x" + info.minHeight + ")"
                        + " updatePeriodMillis=" + info.updatePeriodMillis
                        + " initialLayout=" + info.initialLayout);
            }

            N = mGadgetIds.size();
            pw.println("GadgetIds: (size=" + N + ")");
            for (int i=0; i<N; i++) {
                GadgetId id = mGadgetIds.get(i);
                pw.println("  [" + i + "] gadgetId=" + id.gadgetId
                        + " host=" + id.host.hostId + "/" + id.host.packageName + " provider="
                        + (id.provider == null ? "null" : id.provider.info.provider)
                        + " host.callbacks=" + (id.host != null ? id.host.callbacks : "(no host)")
                        + " views=" + id.views);
            }

            N = mHosts.size();
            pw.println("Hosts: (size=" + N + ")");
            for (int i=0; i<N; i++) {
                Host host = mHosts.get(i);
                pw.println("  [" + i + "] packageName=" + host.packageName + " uid=" + host.uid
                        + " hostId=" + host.hostId + " callbacks=" + host.callbacks
                        + " instances.size=" + host.instances.size());
            }
        }
    }

    public int allocateGadgetId(String packageName, int hostId) {
        int callingUid = enforceCallingUid(packageName);
        synchronized (mGadgetIds) {
            int gadgetId = mNextGadgetId++;

            Host host = lookupOrAddHostLocked(callingUid, packageName, hostId);

            GadgetId id = new GadgetId();
            id.gadgetId = gadgetId;
            id.host = host;

            host.instances.add(id);
            mGadgetIds.add(id);

            saveStateLocked();

            return gadgetId;
        }
    }

    public void deleteGadgetId(int gadgetId) {
        synchronized (mGadgetIds) {
            int callingUid = getCallingUid();
            final int N = mGadgetIds.size();
            for (int i=0; i<N; i++) {
                GadgetId id = mGadgetIds.get(i);
                if (id.provider != null && canAccessGadgetId(id, callingUid)) {
                    deleteGadgetLocked(id);

                    saveStateLocked();
                    return;
                }
            }
        }
    }

    public void deleteHost(int hostId) {
        synchronized (mGadgetIds) {
            int callingUid = getCallingUid();
            Host host = lookupHostLocked(callingUid, hostId);
            if (host != null) {
                deleteHostLocked(host);
                saveStateLocked();
            }
        }
    }

    public void deleteAllHosts() {
        synchronized (mGadgetIds) {
            int callingUid = getCallingUid();
            final int N = mHosts.size();
            boolean changed = false;
            for (int i=0; i<N; i++) {
                Host host = mHosts.get(i);
                if (host.uid == callingUid) {
                    deleteHostLocked(host);
                    changed = true;
                }
            }
            if (changed) {
                saveStateLocked();
            }
        }
    }

    void deleteHostLocked(Host host) {
        final int N = host.instances.size();
        for (int i=0; i<N; i++) {
            GadgetId id = host.instances.get(i);
            deleteGadgetLocked(id);
        }
        host.instances.clear();
        mHosts.remove(host);
        // it's gone or going away, abruptly drop the callback connection
        host.callbacks = null;
    }

    void deleteGadgetLocked(GadgetId id) {
        Host host = id.host;
        host.instances.remove(id);
        pruneHostLocked(host);

        mGadgetIds.remove(id);

        Provider p = id.provider;
        if (p != null) {
            p.instances.remove(id);
            // send the broacast saying that this gadgetId has been deleted
            Intent intent = new Intent(GadgetManager.ACTION_GADGET_DELETED);
            intent.setComponent(p.info.provider);
            intent.putExtra(GadgetManager.EXTRA_GADGET_ID, id.gadgetId);
            mContext.sendBroadcast(intent);
            if (p.instances.size() == 0) {
                // cancel the future updates
                cancelBroadcasts(p);

                // send the broacast saying that the provider is not in use any more
                intent = new Intent(GadgetManager.ACTION_GADGET_DISABLED);
                intent.setComponent(p.info.provider);
                mContext.sendBroadcast(intent);
            }
        }
    }

    void cancelBroadcasts(Provider p) {
        if (p.broadcast != null) {
            mAlarmManager.cancel(p.broadcast);
            long token = Binder.clearCallingIdentity();
            try {
                p.broadcast.cancel();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            p.broadcast = null;
        }
    }

    public void bindGadgetId(int gadgetId, ComponentName provider) {
        mContext.enforceCallingPermission(android.Manifest.permission.BIND_GADGET,
                "bindGagetId gadgetId=" + gadgetId + " provider=" + provider);
        synchronized (mGadgetIds) {
            GadgetId id = lookupGadgetIdLocked(gadgetId);
            if (id == null) {
                throw new IllegalArgumentException("bad gadgetId");
            }
            if (id.provider != null) {
                throw new IllegalArgumentException("gadgetId " + gadgetId + " already bound to "
                        + id.provider.info.provider);
            }
            Provider p = lookupProviderLocked(provider);
            if (p == null) {
                throw new IllegalArgumentException("not a gadget provider: " + provider);
            }

            id.provider = p;
            p.instances.add(id);
            int instancesSize = p.instances.size();
            if (instancesSize == 1) {
                // tell the provider that it's ready
                sendEnableIntentLocked(p);
            }

            // send an update now -- We need this update now, and just for this gadgetId.
            // It's less critical when the next one happens, so when we schdule the next one,
            // we add updatePeriodMillis to its start time.  That time will have some slop,
            // but that's okay.
            sendUpdateIntentLocked(p, new int[] { gadgetId });

            // schedule the future updates
            registerForBroadcastsLocked(p, getGadgetIds(p));
            saveStateLocked();
        }
    }

    public GadgetProviderInfo getGadgetInfo(int gadgetId) {
        synchronized (mGadgetIds) {
            GadgetId id = lookupGadgetIdLocked(gadgetId);
            if (id != null) {
                return id.provider.info;
            }
            return null;
        }
    }

    public RemoteViews getGadgetViews(int gadgetId) {
        synchronized (mGadgetIds) {
            GadgetId id = lookupGadgetIdLocked(gadgetId);
            if (id != null) {
                return id.views;
            }
            return null;
        }
    }

    public List<GadgetProviderInfo> getInstalledProviders() {
        synchronized (mGadgetIds) {
            final int N = mInstalledProviders.size();
            ArrayList<GadgetProviderInfo> result = new ArrayList(N);
            for (int i=0; i<N; i++) {
                result.add(mInstalledProviders.get(i).info);
            }
            return result;
        }
    }

    public void updateGadgetIds(int[] gadgetIds, RemoteViews views) {
        if (gadgetIds == null) {
            return;
        }
        if (gadgetIds.length == 0) {
            return;
        }
        final int N = gadgetIds.length;

        synchronized (mGadgetIds) {
            for (int i=0; i<N; i++) {
                GadgetId id = lookupGadgetIdLocked(gadgetIds[i]);
                updateGadgetInstanceLocked(id, views);
            }
        }
    }

    public void updateGadgetProvider(ComponentName provider, RemoteViews views) {
        synchronized (mGadgetIds) {
            Provider p = lookupProviderLocked(provider);
            if (p == null) {
                Log.w(TAG, "updateGadget: provider doesn't exist: " + provider);
                return;
            }
            ArrayList<GadgetId> instances = p.instances;
            final int N = instances.size();
            for (int i=0; i<N; i++) {
                GadgetId id = instances.get(i);
                updateGadgetInstanceLocked(id, views);
            }
        }
    }

    void updateGadgetInstanceLocked(GadgetId id, RemoteViews views) {
        // allow for stale gadgetIds and other badness
        // lookup also checks that the calling process can access the gadget id
        // drop unbound gadget ids (shouldn't be possible under normal circumstances)
        if (id != null && id.provider != null) {
            id.views = views;

            // is anyone listening?
            if (id.host.callbacks != null) {
                try {
                    // the lock is held, but this is a oneway call
                    id.host.callbacks.updateGadget(id.gadgetId, views);
                } catch (RemoteException e) {
                    // It failed; remove the callback. No need to prune because
                    // we know that this host is still referenced by this instance.
                    id.host.callbacks = null;
                }
            }
        }
    }

    public int[] startListening(IGadgetHost callbacks, String packageName, int hostId,
            List<RemoteViews> updatedViews) {
        int callingUid = enforceCallingUid(packageName);
        synchronized (mGadgetIds) {
            Host host = lookupOrAddHostLocked(callingUid, packageName, hostId);
            host.callbacks = callbacks;

            updatedViews.clear();

            ArrayList<GadgetId> instances = host.instances;
            int N = instances.size();
            int[] updatedIds = new int[N];
            for (int i=0; i<N; i++) {
                GadgetId id = instances.get(i);
                updatedIds[i] = id.gadgetId;
                updatedViews.add(id.views);
            }
            return updatedIds;
        }
    }

    public void stopListening(int hostId) {
        synchronized (mGadgetIds) {
            Host host = lookupHostLocked(getCallingUid(), hostId);
            host.callbacks = null;
            pruneHostLocked(host);
        }
    }

    boolean canAccessGadgetId(GadgetId id, int callingUid) {
        if (id.host.uid == callingUid) {
            // Apps hosting the gadget have access to it.
            return true;
        }
        if (id.provider != null && id.provider.uid == callingUid) {
            // Apps providing the gadget have access to it (if the gadgetId has been bound)
            return true;
        }
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.BIND_GADGET)
                == PackageManager.PERMISSION_GRANTED) {
            // Apps that can bind have access to all gadgetIds.
            return true;
        }
        // Nobody else can access it.
        // TODO: convert callingPackage over to use UID-based checking instead
        // TODO: our temp solution is to short-circuit this security check
        return true;
    }

   GadgetId lookupGadgetIdLocked(int gadgetId) {
        int callingUid = getCallingUid();
        final int N = mGadgetIds.size();
        for (int i=0; i<N; i++) {
            GadgetId id = mGadgetIds.get(i);
            if (id.gadgetId == gadgetId && canAccessGadgetId(id, callingUid)) {
                return id;
            }
        }
        return null;
    }

    Provider lookupProviderLocked(ComponentName provider) {
        final int N = mInstalledProviders.size();
        for (int i=0; i<N; i++) {
            Provider p = mInstalledProviders.get(i);
            if (p.info.provider.equals(provider)) {
                return p;
            }
        }
        return null;
    }

    Host lookupHostLocked(int uid, int hostId) {
        final int N = mHosts.size();
        for (int i=0; i<N; i++) {
            Host h = mHosts.get(i);
            if (h.uid == uid && h.hostId == hostId) {
                return h;
            }
        }
        return null;
    }

    Host lookupOrAddHostLocked(int uid, String packageName, int hostId) {
        final int N = mHosts.size();
        for (int i=0; i<N; i++) {
            Host h = mHosts.get(i);
            if (h.hostId == hostId && h.packageName.equals(packageName)) {
                return h;
            }
        }
        Host host = new Host();
        host.packageName = packageName;
        host.uid = uid;
        host.hostId = hostId;
        mHosts.add(host);
        return host;
    }

    void pruneHostLocked(Host host) {
        if (host.instances.size() == 0 && host.callbacks == null) {
            mHosts.remove(host);
        }
    }

    void getGadgetList() {
        PackageManager pm = mPackageManager;

        Intent intent = new Intent(GadgetManager.ACTION_GADGET_UPDATE);
        List<ResolveInfo> broadcastReceivers = pm.queryBroadcastReceivers(intent,
                PackageManager.GET_META_DATA);

        final int N = broadcastReceivers.size();
        for (int i=0; i<N; i++) {
            ResolveInfo ri = broadcastReceivers.get(i);
            addProviderLocked(ri);
        }
    }

    boolean addProviderLocked(ResolveInfo ri) {
        Provider p = parseProviderInfoXml(new ComponentName(ri.activityInfo.packageName,
                    ri.activityInfo.name), ri);
        if (p != null) {
            mInstalledProviders.add(p);
            return true;
        } else {
            return false;
        }
    }

    void removeProviderLocked(int index, Provider p) {
        int N = p.instances.size();
        for (int i=0; i<N; i++) {
            GadgetId id = p.instances.get(i);
            // Call back with empty RemoteViews
            updateGadgetInstanceLocked(id, null);
            // Stop telling the host about updates for this from now on
            cancelBroadcasts(p);
            // clear out references to this gadgetID
            id.host.instances.remove(id);
            mGadgetIds.remove(id);
            id.provider = null;
            pruneHostLocked(id.host);
            id.host = null;
        }
        p.instances.clear();
        mInstalledProviders.remove(index);
        // no need to send the DISABLE broadcast, since the receiver is gone anyway
        cancelBroadcasts(p);
    }

    void sendEnableIntentLocked(Provider p) {
        Intent intent = new Intent(GadgetManager.ACTION_GADGET_ENABLED);
        intent.setComponent(p.info.provider);
        mContext.sendBroadcast(intent);
    }

    void sendUpdateIntentLocked(Provider p, int[] gadgetIds) {
        if (gadgetIds != null && gadgetIds.length > 0) {
            Intent intent = new Intent(GadgetManager.ACTION_GADGET_UPDATE);
            intent.putExtra(GadgetManager.EXTRA_GADGET_IDS, gadgetIds);
            intent.setComponent(p.info.provider);
            mContext.sendBroadcast(intent);
        }
    }

    void registerForBroadcastsLocked(Provider p, int[] gadgetIds) {
        if (p.info.updatePeriodMillis > 0) {
            // if this is the first instance, set the alarm.  otherwise,
            // rely on the fact that we've already set it and that
            // PendingIntent.getBroadcast will update the extras.
            boolean alreadyRegistered = p.broadcast != null;
            int instancesSize = p.instances.size();
            Intent intent = new Intent(GadgetManager.ACTION_GADGET_UPDATE);
            intent.putExtra(GadgetManager.EXTRA_GADGET_IDS, gadgetIds);
            intent.setComponent(p.info.provider);
            long token = Binder.clearCallingIdentity();
            try {
                p.broadcast = PendingIntent.getBroadcast(mContext, 1, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (!alreadyRegistered) {
                mAlarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + p.info.updatePeriodMillis,
                        p.info.updatePeriodMillis, p.broadcast);
            }
        }
    }
    
    static int[] getGadgetIds(Provider p) {
        int instancesSize = p.instances.size();
        int gadgetIds[] = new int[instancesSize];
        for (int i=0; i<instancesSize; i++) {
            gadgetIds[i] = p.instances.get(i).gadgetId;
        }
        return gadgetIds;
    }

    private Provider parseProviderInfoXml(ComponentName component, ResolveInfo ri) {
        Provider p = null;

        ActivityInfo activityInfo = ri.activityInfo;
        XmlResourceParser parser = null;
        try {
            parser = activityInfo.loadXmlMetaData(mPackageManager,
                    GadgetManager.META_DATA_GADGET_PROVIDER);
            if (parser == null) {
                Log.w(TAG, "No " + GadgetManager.META_DATA_GADGET_PROVIDER + " meta-data for "
                        + "gadget provider '" + component + '\'');
                return null;
            }
        
            AttributeSet attrs = Xml.asAttributeSet(parser);
            
            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
                // drain whitespace, comments, etc.
            }
            
            String nodeName = parser.getName();
            if (!"gadget-provider".equals(nodeName)) {
                Log.w(TAG, "Meta-data does not start with gadget-provider tag for"
                        + " gadget provider '" + component + '\'');
                return null;
            }

            p = new Provider();
            GadgetProviderInfo info = p.info = new GadgetProviderInfo();

            info.provider = component;
            p.uid = activityInfo.applicationInfo.uid;

            TypedArray sa = mContext.getResources().obtainAttributes(attrs,
                    com.android.internal.R.styleable.GadgetProviderInfo);
            info.minWidth = sa.getDimensionPixelSize(
                    com.android.internal.R.styleable.GadgetProviderInfo_minWidth, 0);
            info.minHeight = sa.getDimensionPixelSize(
                    com.android.internal.R.styleable.GadgetProviderInfo_minHeight, 0);
            info.updatePeriodMillis = sa.getInt(
                    com.android.internal.R.styleable.GadgetProviderInfo_updatePeriodMillis, 0);
            info.initialLayout = sa.getResourceId(
                    com.android.internal.R.styleable.GadgetProviderInfo_initialLayout, 0);
            String className = sa.getString(
                    com.android.internal.R.styleable.GadgetProviderInfo_configure);
            if (className != null) {
                info.configure = new ComponentName(component.getPackageName(), className);
            }
            info.label = activityInfo.loadLabel(mPackageManager).toString();
            info.icon = ri.getIconResource();
            sa.recycle();
        } catch (Exception e) {
            // Ok to catch Exception here, because anything going wrong because
            // of what a client process passes to us should not be fatal for the
            // system process.
            Log.w(TAG, "XML parsing failed for gadget provider '" + component + '\'', e);
            return null;
        } finally {
            if (parser != null) parser.close();
        }
        return p;
    }

    int getUidForPackage(String packageName) throws PackageManager.NameNotFoundException {
        PackageInfo pkgInfo = mPackageManager.getPackageInfo(packageName, 0);
        if (pkgInfo == null || pkgInfo.applicationInfo == null) {
            throw new PackageManager.NameNotFoundException();
        }
        return pkgInfo.applicationInfo.uid;
    }

    int enforceCallingUid(String packageName) throws IllegalArgumentException {
        int callingUid = getCallingUid();
        int packageUid;
        try {
            packageUid = getUidForPackage(packageName);
        } catch (PackageManager.NameNotFoundException ex) {
            throw new IllegalArgumentException("packageName and uid don't match packageName="
                    + packageName);
        }
        if (callingUid != packageUid) {
            throw new IllegalArgumentException("packageName and uid don't match packageName="
                    + packageName);
        }
        return callingUid;
    }

    void sendInitialBroadcasts() {
        synchronized (mGadgetIds) {
            final int N = mInstalledProviders.size();
            for (int i=0; i<N; i++) {
                Provider p = mInstalledProviders.get(i);
                if (p.instances.size() > 0) {
                    sendEnableIntentLocked(p);
                    int[] gadgetIds = getGadgetIds(p);
                    sendUpdateIntentLocked(p, gadgetIds);
                    registerForBroadcastsLocked(p, gadgetIds);
                }
            }
        }
    }

    // only call from initialization -- it assumes that the data structures are all empty
    void loadStateLocked() {
        File temp = savedStateTempFile();
        File real = savedStateRealFile();

        // prefer the real file.  If it doesn't exist, use the temp one, and then copy it to the
        // real one.  if there is both a real file and a temp one, assume that the temp one isn't
        // fully written and delete it.
        if (real.exists()) {
            readStateFromFileLocked(real);
            if (temp.exists()) {
                temp.delete();
            }
        } else if (temp.exists()) {
            readStateFromFileLocked(temp);
            temp.renameTo(real);
        }
    }
    
    void saveStateLocked() {
        File temp = savedStateTempFile();
        File real = savedStateRealFile();

        if (!real.exists()) {
            // If the real one doesn't exist, it's either because this is the first time
            // or because something went wrong while copying them.  In this case, we can't
            // trust anything that's in temp.  In order to have the loadState code not
            // use the temporary one until it's fully written, create an empty file
            // for real, which will we'll shortly delete.
            try {
                real.createNewFile();
            } catch (IOException e) {
            }
        }

        if (temp.exists()) {
            temp.delete();
        }

        writeStateToFileLocked(temp);

        real.delete();
        temp.renameTo(real);
    }

    void writeStateToFileLocked(File file) {
        FileOutputStream stream = null;
        int N;

        try {
            stream = new FileOutputStream(file, false);
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, "utf-8");
            out.startDocument(null, true);

            
            out.startTag(null, "gs");

            int providerIndex = 0;
            N = mInstalledProviders.size();
            for (int i=0; i<N; i++) {
                Provider p = mInstalledProviders.get(i);
                if (p.instances.size() > 0) {
                    out.startTag(null, "p");
                    out.attribute(null, "pkg", p.info.provider.getPackageName());
                    out.attribute(null, "cl", p.info.provider.getClassName());
                    out.endTag(null, "h");
                    p.tag = providerIndex;
                    providerIndex++;
                }
            }

            N = mHosts.size();
            for (int i=0; i<N; i++) {
                Host host = mHosts.get(i);
                out.startTag(null, "h");
                out.attribute(null, "pkg", host.packageName);
                out.attribute(null, "id", Integer.toHexString(host.hostId));
                out.endTag(null, "h");
                host.tag = i;
            }

            N = mGadgetIds.size();
            for (int i=0; i<N; i++) {
                GadgetId id = mGadgetIds.get(i);
                out.startTag(null, "g");
                out.attribute(null, "id", Integer.toHexString(id.gadgetId));
                out.attribute(null, "h", Integer.toHexString(id.host.tag));
                if (id.provider != null) {
                    out.attribute(null, "p", Integer.toHexString(id.provider.tag));
                }
                out.endTag(null, "g");
            }

            out.endTag(null, "gs");

            out.endDocument();
            stream.close();
        } catch (IOException e) {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException ex) {
            }
            if (file.exists()) {
                file.delete();
            }
        }
    }

    void readStateFromFileLocked(File file) {
        FileInputStream stream = null;

        boolean success = false;

        try {
            stream = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            int type;
            int providerIndex = 0;
            HashMap<Integer,Provider> loadedProviders = new HashMap();
            do {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("p".equals(tag)) {
                        // TODO: do we need to check that this package has the same signature
                        // as before?
                        String pkg = parser.getAttributeValue(null, "pkg");
                        String cl = parser.getAttributeValue(null, "cl");
                        Provider p = lookupProviderLocked(new ComponentName(pkg, cl));
                        // if it wasn't uninstalled or something
                        if (p != null) {
                            loadedProviders.put(providerIndex, p);
                        }
                        providerIndex++;
                    }
                    else if ("h".equals(tag)) {
                        Host host = new Host();

                        // TODO: do we need to check that this package has the same signature
                        // as before?
                        host.packageName = parser.getAttributeValue(null, "pkg");
                        try {
                            host.uid = getUidForPackage(host.packageName);
                            host.hostId = Integer.parseInt(
                                    parser.getAttributeValue(null, "id"), 16);
                            mHosts.add(host);
                        } catch (PackageManager.NameNotFoundException ex) {
                            // Just ignore drop this entry, as if it has been uninstalled.
                            // We need to deal with this case because of safe mode, but there's
                            // a bug filed about it.
                        }
                    }
                    else if ("g".equals(tag)) {
                        GadgetId id = new GadgetId();
                        id.gadgetId = Integer.parseInt(parser.getAttributeValue(null, "id"), 16);
                        if (id.gadgetId >= mNextGadgetId) {
                            mNextGadgetId = id.gadgetId + 1;
                        }

                        String providerString = parser.getAttributeValue(null, "p");
                        if (providerString != null) {
                            // there's no provider if it hasn't been bound yet.
                            // maybe we don't have to save this, but it brings the system
                            // to the state it was in.
                            int pIndex = Integer.parseInt(providerString, 16);
                            id.provider = loadedProviders.get(pIndex);
                            if (false) {
                                Log.d(TAG, "bound gadgetId=" + id.gadgetId + " to provider "
                                        + pIndex + " which is " + id.provider);
                            }
                            if (id.provider == null) {
                                // This provider is gone.  We just let the host figure out
                                // that this happened when it fails to load it.
                                continue;
                            }
                        }

                        int hIndex = Integer.parseInt(parser.getAttributeValue(null, "h"), 16);
                        id.host = mHosts.get(hIndex);
                        if (id.host == null) {
                            // This host is gone.
                            continue;
                        }

                        if (id.provider != null) {
                            id.provider.instances.add(id);
                        }
                        id.host.instances.add(id);
                        mGadgetIds.add(id);
                    }
                }
            } while (type != XmlPullParser.END_DOCUMENT);
            success = true;
        } catch (NullPointerException e) {
            Log.w(TAG, "failed parsing " + file, e);
        } catch (NumberFormatException e) {
            Log.w(TAG, "failed parsing " + file, e);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "failed parsing " + file, e);
        } catch (IOException e) {
            Log.w(TAG, "failed parsing " + file, e);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "failed parsing " + file, e);
        }
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
        }

        if (success) {
            // delete any hosts that didn't manage to get connected (should happen)
            // if it matters, they'll be reconnected.
            final int N = mHosts.size();
            for (int i=0; i<N; i++) {
                pruneHostLocked(mHosts.get(i));
            }
        } else {
            // failed reading, clean up
            mGadgetIds.clear();
            mHosts.clear();
            final int N = mInstalledProviders.size();
            for (int i=0; i<N; i++) {
                mInstalledProviders.get(i).instances.clear();
            }
        }
    }

    File savedStateTempFile() {
        return new File("/data/system/" + SETTINGS_TMP_FILENAME);
        //return new File(mContext.getFilesDir(), SETTINGS_FILENAME);
    }

    File savedStateRealFile() {
        return new File("/data/system/" + SETTINGS_FILENAME);
        //return new File(mContext.getFilesDir(), SETTINGS_TMP_FILENAME);
    }

    BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            //Log.d(TAG, "received " + action);
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                sendInitialBroadcasts();
            } else {
                Uri uri = intent.getData();
                if (uri == null) {
                    return;
                }
                String pkgName = uri.getSchemeSpecificPart();
                if (pkgName == null) {
                    return;
                }
                
                if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                    synchronized (mGadgetIds) {
                        Bundle extras = intent.getExtras();
                        if (extras != null && extras.getBoolean(Intent.EXTRA_REPLACING, false)) {
                            // The package was just upgraded
                            updateProvidersForPackageLocked(pkgName);
                        } else {
                            // The package was just added
                            addProvidersForPackageLocked(pkgName);
                        }
                        saveStateLocked();
                    }
                }
                else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                    Bundle extras = intent.getExtras();
                    if (extras != null && extras.getBoolean(Intent.EXTRA_REPLACING, false)) {
                        // The package is being updated.  We'll receive a PACKAGE_ADDED shortly.
                    } else {
                        synchronized (mGadgetIds) {
                            removeProvidersForPackageLocked(pkgName);
                            saveStateLocked();
                        }
                    }
                }
            }
        }
    };

    // TODO: If there's a better way of matching an intent filter against the
    // packages for a given package, use that.
    void addProvidersForPackageLocked(String pkgName) {
        Intent intent = new Intent(GadgetManager.ACTION_GADGET_UPDATE);
        List<ResolveInfo> broadcastReceivers = mPackageManager.queryBroadcastReceivers(intent,
                PackageManager.GET_META_DATA);

        final int N = broadcastReceivers.size();
        for (int i=0; i<N; i++) {
            ResolveInfo ri = broadcastReceivers.get(i);
            ActivityInfo ai = ri.activityInfo;
            
            if (pkgName.equals(ai.packageName)) {
                addProviderLocked(ri);
            }
        }
    }

    // TODO: If there's a better way of matching an intent filter against the
    // packages for a given package, use that.
    void updateProvidersForPackageLocked(String pkgName) {
        HashSet<String> keep = new HashSet();
        Intent intent = new Intent(GadgetManager.ACTION_GADGET_UPDATE);
        List<ResolveInfo> broadcastReceivers = mPackageManager.queryBroadcastReceivers(intent,
                PackageManager.GET_META_DATA);

        // add the missing ones and collect which ones to keep
        int N = broadcastReceivers.size();
        for (int i=0; i<N; i++) {
            ResolveInfo ri = broadcastReceivers.get(i);
            ActivityInfo ai = ri.activityInfo;
            if (pkgName.equals(ai.packageName)) {
                ComponentName component = new ComponentName(ai.packageName, ai.name);
                Provider p = lookupProviderLocked(component);
                if (p == null) {
                    if (addProviderLocked(ri)) {
                        keep.add(ai.name);
                    }
                } else {
                    Provider parsed = parseProviderInfoXml(component, ri);
                    if (parsed != null) {
                        keep.add(ai.name);
                        // Use the new GadgetProviderInfo.
                        GadgetProviderInfo oldInfo = p.info;
                        p.info = parsed.info;
                        // If it's enabled
                        final int M = p.instances.size();
                        if (M > 0) {
                            int[] gadgetIds = getGadgetIds(p);
                            // Reschedule for the new updatePeriodMillis (don't worry about handling
                            // it specially if updatePeriodMillis didn't change because we just sent
                            // an update, and the next one will be updatePeriodMillis from now).
                            cancelBroadcasts(p);
                            registerForBroadcastsLocked(p, gadgetIds);
                            // If it's currently showing, call back with the new GadgetProviderInfo.
                            for (int j=0; j<M; j++) {
                                GadgetId id = p.instances.get(j);
                                if (id.host != null && id.host.callbacks != null) {
                                    try {
                                        id.host.callbacks.providerChanged(id.gadgetId, p.info);
                                    } catch (RemoteException ex) {
                                        // It failed; remove the callback. No need to prune because
                                        // we know that this host is still referenced by this
                                        // instance.
                                        id.host.callbacks = null;
                                    }
                                }
                            }
                            // Now that we've told the host, push out an update.
                            sendUpdateIntentLocked(p, gadgetIds);
                        }
                    }
                }
            }
        }

        // prune the ones we don't want to keep
        N = mInstalledProviders.size();
        for (int i=N-1; i>=0; i--) {
            Provider p = mInstalledProviders.get(i);
            if (pkgName.equals(p.info.provider.getPackageName())
                    && !keep.contains(p.info.provider.getClassName())) {
                removeProviderLocked(i, p);
            }
        }
    }

    void removeProvidersForPackageLocked(String pkgName) {
        int N = mInstalledProviders.size();
        for (int i=N-1; i>=0; i--) {
            Provider p = mInstalledProviders.get(i);
            if (pkgName.equals(p.info.provider.getPackageName())) {
                removeProviderLocked(i, p);
            }
        }

        // Delete the hosts for this package too
        //
        // By now, we have removed any gadgets that were in any hosts here,
        // so we don't need to worry about sending DISABLE broadcasts to them.
        N = mHosts.size();
        for (int i=N-1; i>=0; i--) {
            Host host = mHosts.get(i);
            if (pkgName.equals(host.packageName)) {
                deleteHostLocked(host);
            }
        }
    }
}

