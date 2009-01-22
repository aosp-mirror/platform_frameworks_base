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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageItemInfo;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.gadget.GadgetManager;
import android.gadget.GadgetInfo;
import android.os.Binder;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import com.android.internal.gadget.IGadgetService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

class GadgetService extends IGadgetService.Stub
{
    private static final String TAG = "GadgetService";

    static class GadgetId {
        int gadgetId;
        String hostPackage;
        GadgetInfo info;
    }

    Context mContext;
    PackageManager mPackageManager;
    ArrayList<GadgetInfo> mInstalledProviders;
    int mNextGadgetId = 1;
    ArrayList<GadgetId> mGadgetIds = new ArrayList();

    GadgetService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mInstalledProviders = getGadgetList();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingPermission(android.Manifest.permission.DUMP)
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
                GadgetInfo info = mInstalledProviders.get(i);
                pw.println("  [" + i + "] provder=" + info.provider
                        + " min=(" + info.minWidth + "x" + info.minHeight + ")"
                        + " updatePeriodMillis=" + info.updatePeriodMillis
                        + " initialLayout=" + info.initialLayout);
            }

            N = mGadgetIds.size();
            pw.println("GadgetIds: (size=" + N + ")");
            for (int i=0; i<N; i++) {
                GadgetId id = mGadgetIds.get(i);
                pw.println("  [" + i + "] gadgetId=" + id.gadgetId + " host=" + id.hostPackage
                        + " provider=" + (id.info == null ? "null" : id.info.provider));
            }
        }
    }

    public int allocateGadgetId(String hostPackage) {
        synchronized (mGadgetIds) {
            // TODO: Check for pick permission
            int gadgetId = mNextGadgetId++;

            GadgetId id = new GadgetId();
            id.gadgetId = gadgetId;
            id.hostPackage = hostPackage;

            mGadgetIds.add(id);

            return gadgetId;
        }
    }

    public void deleteGadgetId(int gadgetId) {
        synchronized (mGadgetIds) {
            String callingPackage = getCallingPackage();
            final int N = mGadgetIds.size();
            for (int i=0; i<N; i++) {
                GadgetId id = mGadgetIds.get(i);
                if (canAccessGadgetId(id, callingPackage)) {
                    mGadgetIds.remove(i);
                    // TODO: Notify someone?
                    return;
                }
            }
        }
    }

    public void bindGadgetId(int gadgetId, ComponentName provider) {
        synchronized (mGadgetIds) {
            GadgetId id = lookupGadgetIdLocked(gadgetId);
            if (id == null) {
                throw new IllegalArgumentException("bad gadgetId"); // TODO: use a better exception
            }
            if (id.info != null) {
                throw new IllegalArgumentException("gadgetId " + gadgetId + " already bound to "
                        + id.info.provider);
            }
            GadgetInfo info = lookupGadgetInfoLocked(provider);
            if (info == null) {
                throw new IllegalArgumentException("not a gadget provider: " + provider);
            }

            id.info = info;
        }
    }

    public GadgetInfo getGadgetInfo(int gadgetId) {
        synchronized (mGadgetIds) {
            GadgetId id = lookupGadgetIdLocked(gadgetId);
            if (id != null) {
                return id.info;
            }
            return null;
        }
    }

    public List<GadgetInfo> getInstalledProviders() {
        synchronized (mGadgetIds) {
            return new ArrayList<GadgetInfo>(mInstalledProviders);
        }
    }

    boolean canAccessGadgetId(GadgetId id, String callingPackage) {
        if (id.hostPackage.equals(callingPackage)) {
            return true;
        }
        if (id.info != null && id.info.provider.getPackageName().equals(callingPackage)) {
            return true;
        }
        // TODO: Check for the pick permission
        //if (has permission) {
        //    return true;
        //}
        //return false;
        return true;
    }

    private GadgetId lookupGadgetIdLocked(int gadgetId) {
        String callingPackage = getCallingPackage();
        final int N = mGadgetIds.size();
        for (int i=0; i<N; i++) {
            GadgetId id = mGadgetIds.get(i);
            if (canAccessGadgetId(id, callingPackage)) {
                return id;
            }
        }
        return null;
    }

    GadgetInfo lookupGadgetInfoLocked(ComponentName provider) {
        final int N = mInstalledProviders.size();
        for (int i=0; i<N; i++) {
            GadgetInfo info = mInstalledProviders.get(i);
            if (info.provider.equals(provider)) {
                return info;
            }
        }
        return null;
    }

    ArrayList<GadgetInfo> getGadgetList() {
        PackageManager pm = mPackageManager;

        // TODO: We have these as different actions.  I wonder if it makes more sense to
        // have like a GADGET_ACTION, and then subcommands.  It's kind of arbitrary that
        // we look for GADGET_UPDATE_ACTION and not any of the other gadget actions.
        Intent intent = new Intent(GadgetManager.GADGET_UPDATE_ACTION);
        List<ResolveInfo> broadcastReceivers = pm.queryBroadcastReceivers(intent,
                PackageManager.GET_META_DATA);

        ArrayList<GadgetInfo> result = new ArrayList<GadgetInfo>();

        final int N = broadcastReceivers.size();
        for (int i=0; i<N; i++) {
            ResolveInfo ri = broadcastReceivers.get(i);
            ActivityInfo ai = ri.activityInfo;
            GadgetInfo gi = parseGadgetInfoXml(new ComponentName(ai.packageName, ai.name),
                    ri.activityInfo);
            if (gi != null) {
                result.add(gi);
            }
        }

        return result;
    }

    private GadgetInfo parseGadgetInfoXml(ComponentName component,
            PackageItemInfo packageItemInfo) {
        GadgetInfo gi = null;

        XmlResourceParser parser = null;
        try {
            parser = packageItemInfo.loadXmlMetaData(mPackageManager,
                    GadgetManager.GADGET_PROVIDER_META_DATA);
            if (parser == null) {
                Log.w(TAG, "No " + GadgetManager.GADGET_PROVIDER_META_DATA + " meta-data for "
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

            gi = new GadgetInfo();

            gi.provider = component;

            TypedArray sa = mContext.getResources().obtainAttributes(attrs,
                    com.android.internal.R.styleable.GadgetProviderInfo);
            gi.minWidth = sa.getDimensionPixelSize(
                    com.android.internal.R.styleable.GadgetProviderInfo_minWidth, 0);
            gi.minHeight = sa.getDimensionPixelSize(
                    com.android.internal.R.styleable.GadgetProviderInfo_minHeight, 0);
            gi.updatePeriodMillis = sa.getInt(
                    com.android.internal.R.styleable.GadgetProviderInfo_updatePeriodMillis, 0);
            gi.initialLayout = sa.getResourceId(
                    com.android.internal.R.styleable.GadgetProviderInfo_initialLayout, 0);
            sa.recycle();
        } catch (Exception e) {
            // Ok to catch Exception here, because anything going wrong because
            // of what a client process passes to us should not be fatal for the
            // system process.
            Log.w(TAG, "XML parsing failed for gadget provider '" + component + '\'', e);
        } finally {
            if (parser != null) parser.close();
        }
        return gi;
    }

    void sendEnabled(ComponentName provider) {
        Intent intent = new Intent(GadgetManager.GADGET_ENABLE_ACTION);
        intent.setComponent(provider);
        mContext.sendBroadcast(intent);
    }

    String getCallingPackage() {
        return mPackageManager.getNameForUid(getCallingUid());
    }
}

