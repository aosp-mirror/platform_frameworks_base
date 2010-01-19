/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.common.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.DeviceAdmin;
import android.app.DeviceAdminInfo;
import android.app.DevicePolicyManager;
import android.app.IDevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.util.Log;
import android.util.Xml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Implementation of the device policy APIs.
 */
public class DevicePolicyManagerService extends IDevicePolicyManager.Stub {
    private static final String TAG = "DevicePolicyManagerService";
    
    private final Context mContext;

    int mActivePasswordMode = DevicePolicyManager.PASSWORD_MODE_UNSPECIFIED;
    int mActivePasswordLength = 0;
    int mFailedPasswordAttempts = 0;
    
    ActiveAdmin mActiveAdmin;
    
    static class ActiveAdmin {
        ActiveAdmin(DeviceAdminInfo _info) {
            info = _info;
        }
        
        final DeviceAdminInfo info;
        int getUid() { return info.getActivityInfo().applicationInfo.uid; }
        
        int passwordMode = DevicePolicyManager.PASSWORD_MODE_UNSPECIFIED;
        int minimumPasswordLength = 0;
        long maximumTimeToUnlock = 0;
    }
    
    /**
     * Instantiates the service.
     */
    public DevicePolicyManagerService(Context context) {
        mContext = context;
    }

    ActiveAdmin getActiveAdminForCallerLocked(ComponentName who) throws SecurityException {
        if (mActiveAdmin != null && mActiveAdmin.getUid() == Binder.getCallingPid()) {
            if (who != null) {
                if (!who.getPackageName().equals(mActiveAdmin.info.getActivityInfo().packageName)
                        || !who.getClassName().equals(mActiveAdmin.info.getActivityInfo().name)) {
                    throw new SecurityException("Current admin is not " + who);
                }
            }
            return mActiveAdmin;
        }
        throw new SecurityException("Current admin is not owned by uid " + Binder.getCallingUid());
    }
    
    
    void sendAdminCommandLocked(ActiveAdmin policy, String action) {
        Intent intent = new Intent(action);
        intent.setComponent(policy.info.getComponent());
        mContext.sendBroadcast(intent);
    }
    
    ComponentName getActiveAdminLocked() {
        if (mActiveAdmin != null) {
            return mActiveAdmin.info.getComponent();
        }
        return null;
    }
    
    void removeActiveAdminLocked(ComponentName adminReceiver) {
        ComponentName cur = getActiveAdminLocked();
        if (cur != null && cur.equals(adminReceiver)) {
            sendAdminCommandLocked(mActiveAdmin,
                    DeviceAdmin.ACTION_DEVICE_ADMIN_DISABLED);
            // XXX need to wait for it to complete.
            mActiveAdmin = null;
        }
    }
    
    public DeviceAdminInfo findAdmin(ComponentName adminName) {
        Intent resolveIntent = new Intent();
        resolveIntent.setComponent(adminName);
        List<ResolveInfo> infos = mContext.getPackageManager().queryBroadcastReceivers(
                resolveIntent, PackageManager.GET_META_DATA);
        if (infos == null || infos.size() <= 0) {
            throw new IllegalArgumentException("Unknown admin: " + adminName);
        }
        
        try {
            return new DeviceAdminInfo(mContext, infos.get(0));
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Bad device admin requested: " + adminName, e);
            return null;
        } catch (IOException e) {
            Log.w(TAG, "Bad device admin requested: " + adminName, e);
            return null;
        }
    }
    
    private static JournaledFile makeJournaledFile() {
        final String base = "/data/system/device_policies.xml";
        return new JournaledFile(new File(base), new File(base + ".tmp"));
    }

    private void saveSettingsLocked() {
        JournaledFile journal = makeJournaledFile();
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(journal.chooseForWrite(), false);
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, "utf-8");
            out.startDocument(null, true);

            out.startTag(null, "policies");
            
            ActiveAdmin ap = mActiveAdmin;
            if (ap != null) {
                out.startTag(null, "admin");
                out.attribute(null, "name", ap.info.getComponent().flattenToString());
                if (ap.passwordMode != DevicePolicyManager.PASSWORD_MODE_UNSPECIFIED) {
                    out.startTag(null, "password-mode");
                    out.attribute(null, "value", Integer.toString(ap.passwordMode));
                    out.endTag(null, "password-mode");
                    if (ap.minimumPasswordLength > 0) {
                        out.startTag(null, "min-password-length");
                        out.attribute(null, "value", Integer.toString(ap.minimumPasswordLength));
                        out.endTag(null, "mn-password-length");
                    }
                }
                if (ap.maximumTimeToUnlock != DevicePolicyManager.PASSWORD_MODE_UNSPECIFIED) {
                    out.startTag(null, "max-time-to-unlock");
                    out.attribute(null, "value", Long.toString(ap.maximumTimeToUnlock));
                    out.endTag(null, "max-time-to-unlock");
                }
                out.endTag(null, "admin");
            }
            out.endTag(null, "policies");

            out.endDocument();
            stream.close();
            journal.commit();
        } catch (IOException e) {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException ex) {
                // Ignore
            }
            journal.rollback();
        }
    }

    private void loadSettingsLocked() {
        JournaledFile journal = makeJournaledFile();
        FileInputStream stream = null;
        File file = journal.chooseForRead();
        boolean success = false;
        try {
            stream = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            int type = parser.next();
            while (type != XmlPullParser.START_TAG) {
                type = parser.next();
            }
            String tag = parser.getName();
            if ("policies".equals(tag)) {
                ActiveAdmin ap = null;
                do {
                    type = parser.next();
                    if (type == XmlPullParser.START_TAG) {
                        tag = parser.getName();
                        if (ap == null) {
                            if ("admin".equals(tag)) {
                                DeviceAdminInfo dai = findAdmin(
                                        ComponentName.unflattenFromString(
                                                parser.getAttributeValue(null, "name")));
                                if (dai != null) {
                                    ap = new ActiveAdmin(dai);
                                }
                            }
                        } else if ("password-mode".equals(tag)) {
                            ap.passwordMode = Integer.parseInt(
                                    parser.getAttributeValue(null, "value"));
                        } else if ("min-password-length".equals(tag)) {
                            ap.minimumPasswordLength = Integer.parseInt(
                                    parser.getAttributeValue(null, "value"));
                        } else if ("max-time-to-unlock".equals(tag)) {
                            ap.maximumTimeToUnlock = Long.parseLong(
                                    parser.getAttributeValue(null, "value"));
                        }
                    } else if (type == XmlPullParser.END_TAG) {
                        tag = parser.getName();
                        if (ap != null && "admin".equals(tag)) {
                            mActiveAdmin = ap;
                            ap = null;
                        }
                    }
                } while (type != XmlPullParser.END_DOCUMENT);
                success = true;
            }
        } catch (NullPointerException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (NumberFormatException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (IOException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        }
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        if (!success) {
            Log.w(TAG, "No valid start tag found in policies file");
        }
    }

    public void systemReady() {
        synchronized (this) {
            loadSettingsLocked();
        }
    }
    
    public void setActiveAdmin(ComponentName adminReceiver) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        
        DeviceAdminInfo info = findAdmin(adminReceiver);
        if (info == null) {
            throw new IllegalArgumentException("Bad admin: " + adminReceiver);
        }
        synchronized (this) {
            long ident = Binder.clearCallingIdentity();
            try {
                ComponentName cur = getActiveAdminLocked();
                if (cur != null && cur.equals(adminReceiver)) {
                    throw new IllegalStateException("An admin is already set");
                }
                if (cur != null) {
                    removeActiveAdminLocked(adminReceiver);
                }
                mActiveAdmin = new ActiveAdmin(info);
                saveSettingsLocked();
                sendAdminCommandLocked(mActiveAdmin,
                        DeviceAdmin.ACTION_DEVICE_ADMIN_ENABLED);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    public ComponentName getActiveAdmin() {
        synchronized (this) {
            return getActiveAdminLocked();
        }
    }
    
    public void removeActiveAdmin(ComponentName adminReceiver) {
        synchronized (this) {
            if (mActiveAdmin == null || mActiveAdmin.getUid() != Binder.getCallingUid()) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.BIND_DEVICE_ADMIN, null);
            }
            long ident = Binder.clearCallingIdentity();
            try {
                removeActiveAdminLocked(adminReceiver);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    public void setPasswordMode(ComponentName who, int mode) {
        synchronized (this) {
            if (who == null) {
                throw new NullPointerException("ComponentName is null");
            }
            ActiveAdmin ap = getActiveAdminForCallerLocked(who);
            if (ap.passwordMode != mode) {
                ap.passwordMode = mode;
                saveSettingsLocked();
            }
        }
    }
    
    public int getPasswordMode() {
        synchronized (this) {
            return mActiveAdmin != null ? mActiveAdmin.passwordMode
                    : DevicePolicyManager.PASSWORD_MODE_UNSPECIFIED;
        }
    }
    
    public int getActivePasswordMode() {
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null);
            return mActivePasswordMode;
        }
    }
    
    public void setMinimumPasswordLength(ComponentName who, int length) {
        synchronized (this) {
            if (who == null) {
                throw new NullPointerException("ComponentName is null");
            }
            ActiveAdmin ap = getActiveAdminForCallerLocked(who);
            if (ap.minimumPasswordLength != length) {
                ap.minimumPasswordLength = length;
                saveSettingsLocked();
            }
        }
    }
    
    public int getMinimumPasswordLength() {
        synchronized (this) {
            return mActiveAdmin != null ? mActiveAdmin.minimumPasswordLength : 0;
        }
    }
    
    public int getActiveMinimumPasswordLength() {
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null);
            return mActivePasswordLength;
        }
    }
    
    public int getCurrentFailedPasswordAttempts() {
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null);
            return mFailedPasswordAttempts;
        }
    }
    
    public void setMaximumTimeToLock(ComponentName who, long timeMs) {
        synchronized (this) {
            if (who == null) {
                throw new NullPointerException("ComponentName is null");
            }
            ActiveAdmin ap = getActiveAdminForCallerLocked(who);
            if (ap.maximumTimeToUnlock != timeMs) {
                ap.maximumTimeToUnlock = timeMs;
                saveSettingsLocked();
            }
        }
    }
    
    public long getMaximumTimeToLock() {
        synchronized (this) {
            return mActiveAdmin != null ? mActiveAdmin.maximumTimeToUnlock : 0;
        }
    }
    
    public void wipeData(int flags) {
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null);
            long ident = Binder.clearCallingIdentity();
            try {
                Log.w(TAG, "*************** WIPE DATA HERE");
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    public void setActivePasswordState(int mode, int length) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        
        synchronized (this) {
            if (mActivePasswordMode != mode || mActivePasswordLength != length
                    || mFailedPasswordAttempts != 0) {
                long ident = Binder.clearCallingIdentity();
                try {
                    mActivePasswordMode = mode;
                    mActivePasswordLength = length;
                    mFailedPasswordAttempts = 0;
                    sendAdminCommandLocked(mActiveAdmin,
                            DeviceAdmin.ACTION_PASSWORD_CHANGED);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }
    
    public void reportFailedPasswordAttempt() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        
        synchronized (this) {
            long ident = Binder.clearCallingIdentity();
            try {
                mFailedPasswordAttempts++;
                sendAdminCommandLocked(mActiveAdmin,
                        DeviceAdmin.ACTION_PASSWORD_FAILED);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    public void reportSuccessfulPasswordAttempt() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        
        synchronized (this) {
            if (mFailedPasswordAttempts != 0) {
                long ident = Binder.clearCallingIdentity();
                try {
                    mFailedPasswordAttempts = 0;
                    sendAdminCommandLocked(mActiveAdmin,
                            DeviceAdmin.ACTION_PASSWORD_SUCCEEDED);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }
}
