/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.server.slice;

import android.content.ContentProvider;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.Xml.Encoding;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;
import com.android.server.slice.SliceProviderPermissions.SliceAuthority;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public class SlicePermissionManager implements DirtyTracker {

    private static final String TAG = "SlicePermissionManager";

    /**
     * The amount of time we'll cache a SliceProviderPermissions or SliceClientPermissions
     * in case they are used again.
     */
    private static final long PERMISSION_CACHE_PERIOD = 5 * DateUtils.MINUTE_IN_MILLIS;

    /**
     * The amount of time we delay flushing out permission changes to disk because they usually
     * come in short bursts.
     */
    private static final long WRITE_GRACE_PERIOD = 500;

    private static final String SLICE_DIR = "slice";

    // If/when this bumps again we'll need to write it out in the disk somewhere.
    // Currently we don't have a central file for this in version 2 and there is no
    // reason to add one until we actually have incompatible version bumps.
    // This does however block us from reading backups from P-DP1 which may contain
    // a very different XML format for perms.
    static final int DB_VERSION = 2;

    private static final String TAG_LIST = "slice-access-list";
    private final String ATT_VERSION = "version";

    private final File mSliceDir;
    private final Context mContext;
    private final Handler mHandler;
    private final ArrayMap<PkgUser, SliceProviderPermissions> mCachedProviders = new ArrayMap<>();
    private final ArrayMap<PkgUser, SliceClientPermissions> mCachedClients = new ArrayMap<>();
    private final ArraySet<Persistable> mDirty = new ArraySet<>();

    @VisibleForTesting
    SlicePermissionManager(Context context, Looper looper, File sliceDir) {
        mContext = context;
        mHandler = new H(looper);
        mSliceDir = sliceDir;
    }

    public SlicePermissionManager(Context context, Looper looper) {
        this(context, looper, new File(Environment.getDataDirectory(), "system/" + SLICE_DIR));
    }

    public void grantFullAccess(String pkg, int userId) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        SliceClientPermissions client = getClient(pkgUser);
        client.setHasFullAccess(true);
    }

    public void grantSliceAccess(String pkg, int userId, String providerPkg, int providerUser,
            Uri uri) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        PkgUser providerPkgUser = new PkgUser(providerPkg, providerUser);

        SliceClientPermissions client = getClient(pkgUser);
        client.grantUri(uri, providerPkgUser);

        SliceProviderPermissions provider = getProvider(providerPkgUser);
        provider.getOrCreateAuthority(ContentProvider.getUriWithoutUserId(uri).getAuthority())
                .addPkg(pkgUser);
    }

    public void revokeSliceAccess(String pkg, int userId, String providerPkg, int providerUser,
            Uri uri) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        PkgUser providerPkgUser = new PkgUser(providerPkg, providerUser);

        SliceClientPermissions client = getClient(pkgUser);
        client.revokeUri(uri, providerPkgUser);
    }

    public void removePkg(String pkg, int userId) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        SliceProviderPermissions provider = getProvider(pkgUser);

        for (SliceAuthority authority : provider.getAuthorities()) {
            for (PkgUser p : authority.getPkgs()) {
                getClient(p).removeAuthority(authority.getAuthority(), userId);
            }
        }
        SliceClientPermissions client = getClient(pkgUser);
        client.clear();
        mHandler.obtainMessage(H.MSG_REMOVE, pkgUser);
    }

    public String[] getAllPackagesGranted(String pkg) {
        ArraySet<String> ret = new ArraySet<>();
        for (SliceAuthority authority : getProvider(new PkgUser(pkg, 0)).getAuthorities()) {
            for (PkgUser pkgUser : authority.getPkgs()) {
                ret.add(pkgUser.mPkg);
            }
        }
        return ret.toArray(new String[ret.size()]);
    }

    public boolean hasFullAccess(String pkg, int userId) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        return getClient(pkgUser).hasFullAccess();
    }

    public boolean hasPermission(String pkg, int userId, Uri uri) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        SliceClientPermissions client = getClient(pkgUser);
        int providerUserId = ContentProvider.getUserIdFromUri(uri, userId);
        return client.hasFullAccess()
                || client.hasPermission(ContentProvider.getUriWithoutUserId(uri), providerUserId);
    }

    @Override
    public void onPersistableDirty(Persistable obj) {
        mHandler.removeMessages(H.MSG_PERSIST);
        mHandler.obtainMessage(H.MSG_ADD_DIRTY, obj).sendToTarget();
        mHandler.sendEmptyMessageDelayed(H.MSG_PERSIST, WRITE_GRACE_PERIOD);
    }

    public void writeBackup(XmlSerializer out) throws IOException, XmlPullParserException {
        synchronized (this) {
            out.startTag(null, TAG_LIST);
            out.attribute(null, ATT_VERSION, String.valueOf(DB_VERSION));

            // Don't do anything with changes from the backup, because there shouldn't be any.
            DirtyTracker tracker = obj -> { };
            if (mHandler.hasMessages(H.MSG_PERSIST)) {
                mHandler.removeMessages(H.MSG_PERSIST);
                handlePersist();
            }
            for (String file : new File(mSliceDir.getAbsolutePath()).list()) {
                try (ParserHolder parser = getParser(file)) {
                    Persistable p = null;
                    while (parser.parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                        if (parser.parser.getEventType() == XmlPullParser.START_TAG) {
                            if (SliceClientPermissions.TAG_CLIENT.equals(parser.parser.getName())) {
                                p = SliceClientPermissions.createFrom(parser.parser, tracker);
                            } else {
                                p = SliceProviderPermissions.createFrom(parser.parser, tracker);
                            }
                            break;
                        }
                        parser.parser.next();
                    }
                    if (p != null) {
                        p.writeTo(out);
                    } else {
                        Slog.w(TAG, "Invalid or empty slice permissions file: " + file);
                    }
                }
            }

            out.endTag(null, TAG_LIST);
        }
    }

    public void readRestore(XmlPullParser parser) throws IOException, XmlPullParserException {
        synchronized (this) {
            while ((parser.getEventType() != XmlPullParser.START_TAG
                    || !TAG_LIST.equals(parser.getName()))
                    && parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                parser.next();
            }
            int xmlVersion = XmlUtils.readIntAttribute(parser, ATT_VERSION, 0);
            if (xmlVersion < DB_VERSION) {
                // No conversion support right now.
                return;
            }
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG) {
                    if (SliceClientPermissions.TAG_CLIENT.equals(parser.getName())) {
                        SliceClientPermissions client = SliceClientPermissions.createFrom(parser,
                                this);
                        synchronized (mCachedClients) {
                            mCachedClients.put(client.getPkg(), client);
                        }
                        onPersistableDirty(client);
                        mHandler.sendMessageDelayed(
                                mHandler.obtainMessage(H.MSG_CLEAR_CLIENT, client.getPkg()),
                                PERMISSION_CACHE_PERIOD);
                    } else if (SliceProviderPermissions.TAG_PROVIDER.equals(parser.getName())) {
                        SliceProviderPermissions provider = SliceProviderPermissions.createFrom(
                                parser, this);
                        synchronized (mCachedProviders) {
                            mCachedProviders.put(provider.getPkg(), provider);
                        }
                        onPersistableDirty(provider);
                        mHandler.sendMessageDelayed(
                                mHandler.obtainMessage(H.MSG_CLEAR_PROVIDER, provider.getPkg()),
                                PERMISSION_CACHE_PERIOD);
                    } else {
                        parser.next();
                    }
                } else {
                    parser.next();
                }
            }
        }
    }

    private SliceClientPermissions getClient(PkgUser pkgUser) {
        SliceClientPermissions client;
        synchronized (mCachedClients) {
            client = mCachedClients.get(pkgUser);
        }
        if (client == null) {
            try (ParserHolder parser = getParser(SliceClientPermissions.getFileName(pkgUser))) {
                client = SliceClientPermissions.createFrom(parser.parser, this);
                synchronized (mCachedClients) {
                    mCachedClients.put(pkgUser, client);
                }
                mHandler.sendMessageDelayed(mHandler.obtainMessage(H.MSG_CLEAR_CLIENT, pkgUser),
                        PERMISSION_CACHE_PERIOD);
                return client;
            } catch (FileNotFoundException e) {
                // No client exists yet.
            } catch (IOException e) {
                Log.e(TAG, "Can't read client", e);
            } catch (XmlPullParserException e) {
                Log.e(TAG, "Can't read client", e);
            }
            // Can't read or no permissions exist, create a clean object.
            client = new SliceClientPermissions(pkgUser, this);
            synchronized (mCachedClients) {
                mCachedClients.put(pkgUser, client);
            }
        }
        return client;
    }

    private SliceProviderPermissions getProvider(PkgUser pkgUser) {
        SliceProviderPermissions provider;
        synchronized (mCachedProviders) {
            provider = mCachedProviders.get(pkgUser);
        }
        if (provider == null) {
            try (ParserHolder parser = getParser(SliceProviderPermissions.getFileName(pkgUser))) {
                provider = SliceProviderPermissions.createFrom(parser.parser, this);
                synchronized (mCachedProviders) {
                    mCachedProviders.put(pkgUser, provider);
                }
                mHandler.sendMessageDelayed(mHandler.obtainMessage(H.MSG_CLEAR_PROVIDER, pkgUser),
                        PERMISSION_CACHE_PERIOD);
                return provider;
            } catch (FileNotFoundException e) {
                // No provider exists yet.
            } catch (IOException e) {
                Log.e(TAG, "Can't read provider", e);
            } catch (XmlPullParserException e) {
                Log.e(TAG, "Can't read provider", e);
            }
            // Can't read or no permissions exist, create a clean object.
            provider = new SliceProviderPermissions(pkgUser, this);
            synchronized (mCachedProviders) {
                mCachedProviders.put(pkgUser, provider);
            }
        }
        return provider;
    }

    private ParserHolder getParser(String fileName)
            throws FileNotFoundException, XmlPullParserException {
        AtomicFile file = getFile(fileName);
        ParserHolder holder = new ParserHolder();
        holder.input = file.openRead();
        holder.parser = XmlPullParserFactory.newInstance().newPullParser();
        holder.parser.setInput(holder.input, Encoding.UTF_8.name());
        return holder;
    }

    private AtomicFile getFile(String fileName) {
        if (!mSliceDir.exists()) {
            mSliceDir.mkdir();
        }
        return new AtomicFile(new File(mSliceDir, fileName));
    }

    @VisibleForTesting
    void handlePersist() {
        synchronized (this) {
            for (Persistable persistable : mDirty) {
                AtomicFile file = getFile(persistable.getFileName());
                final FileOutputStream stream;
                try {
                    stream = file.startWrite();
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to save access file", e);
                    return;
                }

                try {
                    XmlSerializer out = XmlPullParserFactory.newInstance().newSerializer();
                    out.setOutput(stream, Encoding.UTF_8.name());

                    persistable.writeTo(out);

                    out.flush();
                    file.finishWrite(stream);
                } catch (IOException | XmlPullParserException | RuntimeException e) {
                    Slog.w(TAG, "Failed to save access file, restoring backup", e);
                    file.failWrite(stream);
                }
            }
            mDirty.clear();
        }
    }

    // use addPersistableDirty(); this is just for tests
    @VisibleForTesting
    void addDirtyImmediate(Persistable obj) {
        mDirty.add(obj);
    }

    private void handleRemove(PkgUser pkgUser) {
        getFile(SliceClientPermissions.getFileName(pkgUser)).delete();
        getFile(SliceProviderPermissions.getFileName(pkgUser)).delete();
        mDirty.remove(mCachedClients.remove(pkgUser));
        mDirty.remove(mCachedProviders.remove(pkgUser));
    }

    private final class H extends Handler {
        private static final int MSG_ADD_DIRTY = 1;
        private static final int MSG_PERSIST = 2;
        private static final int MSG_REMOVE = 3;
        private static final int MSG_CLEAR_CLIENT = 4;
        private static final int MSG_CLEAR_PROVIDER = 5;

        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ADD_DIRTY:
                    mDirty.add((Persistable) msg.obj);
                    break;
                case MSG_PERSIST:
                    handlePersist();
                    break;
                case MSG_REMOVE:
                    handleRemove((PkgUser) msg.obj);
                    break;
                case MSG_CLEAR_CLIENT:
                    synchronized (mCachedClients) {
                        mCachedClients.remove(msg.obj);
                    }
                    break;
                case MSG_CLEAR_PROVIDER:
                    synchronized (mCachedProviders) {
                        mCachedProviders.remove(msg.obj);
                    }
                    break;
            }
        }
    }

    public static class PkgUser {
        private static final String SEPARATOR = "@";
        private static final String FORMAT = "%s" + SEPARATOR + "%d";
        private final String mPkg;
        private final int mUserId;

        public PkgUser(String pkg, int userId) {
            mPkg = pkg;
            mUserId = userId;
        }

        public PkgUser(String pkgUserStr) throws IllegalArgumentException {
            try {
                String[] vals = pkgUserStr.split(SEPARATOR, 2);
                mPkg = vals[0];
                mUserId = Integer.parseInt(vals[1]);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        public String getPkg() {
            return mPkg;
        }

        public int getUserId() {
            return mUserId;
        }

        @Override
        public int hashCode() {
            return mPkg.hashCode() + mUserId;
        }

        @Override
        public boolean equals(Object obj) {
            if (!getClass().equals(obj != null ? obj.getClass() : null)) return false;
            PkgUser other = (PkgUser) obj;
            return Objects.equals(other.mPkg, mPkg) && other.mUserId == mUserId;
        }

        @Override
        public String toString() {
            return String.format(FORMAT, mPkg, mUserId);
        }
    }

    private class ParserHolder implements AutoCloseable {

        private InputStream input;
        private XmlPullParser parser;

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
