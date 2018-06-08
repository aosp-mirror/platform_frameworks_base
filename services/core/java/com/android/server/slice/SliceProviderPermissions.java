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

import android.annotation.NonNull;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.server.slice.DirtyTracker.Persistable;
import com.android.server.slice.SlicePermissionManager.PkgUser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

public class SliceProviderPermissions implements DirtyTracker, Persistable {

    private static final String TAG = "SliceProviderPermissions";

    static final String TAG_PROVIDER = "provider";
    private static final String TAG_AUTHORITY = "authority";
    private static final String TAG_PKG = "pkg";
    private static final String NAMESPACE = null;

    private static final String ATTR_PKG = "pkg";
    private static final String ATTR_AUTHORITY = "authority";

    private final PkgUser mPkg;
    private final ArrayMap<String, SliceAuthority> mAuths = new ArrayMap<>();
    private final DirtyTracker mTracker;

    public SliceProviderPermissions(@NonNull PkgUser pkg, @NonNull DirtyTracker tracker) {
        mPkg = pkg;
        mTracker = tracker;
    }

    public PkgUser getPkg() {
        return mPkg;
    }

    public synchronized Collection<SliceAuthority> getAuthorities() {
        return new ArrayList<>(mAuths.values());
    }

    public synchronized SliceAuthority getOrCreateAuthority(String authority) {
        SliceAuthority ret = mAuths.get(authority);
        if (ret == null) {
            ret = new SliceAuthority(authority, this);
            mAuths.put(authority, ret);
            onPersistableDirty(ret);
        }
        return ret;
    }

    @Override
    public void onPersistableDirty(Persistable obj) {
        mTracker.onPersistableDirty(this);
    }

    @Override
    public String getFileName() {
        return getFileName(mPkg);
    }

    public synchronized void writeTo(XmlSerializer out) throws IOException {
        out.startTag(NAMESPACE, TAG_PROVIDER);
        out.attribute(NAMESPACE, ATTR_PKG, mPkg.toString());

        final int N = mAuths.size();
        for (int i = 0; i < N; i++) {
            out.startTag(NAMESPACE, TAG_AUTHORITY);
            out.attribute(NAMESPACE, ATTR_AUTHORITY, mAuths.valueAt(i).mAuthority);

            mAuths.valueAt(i).writeTo(out);

            out.endTag(NAMESPACE, TAG_AUTHORITY);
        }

        out.endTag(NAMESPACE, TAG_PROVIDER);
    }

    public static SliceProviderPermissions createFrom(XmlPullParser parser, DirtyTracker tracker)
            throws XmlPullParserException, IOException {
        // Get to the beginning of the provider.
        while (parser.getEventType() != XmlPullParser.START_TAG
                || !TAG_PROVIDER.equals(parser.getName())) {
            parser.next();
        }
        int depth = parser.getDepth();
        PkgUser pkgUser = new PkgUser(parser.getAttributeValue(NAMESPACE, ATTR_PKG));
        SliceProviderPermissions provider = new SliceProviderPermissions(pkgUser, tracker);
        parser.next();

        while (parser.getDepth() > depth) {
            if (parser.getEventType() == XmlPullParser.START_TAG
                    && TAG_AUTHORITY.equals(parser.getName())) {
                try {
                    SliceAuthority authority = new SliceAuthority(
                            parser.getAttributeValue(NAMESPACE, ATTR_AUTHORITY), provider);
                    authority.readFrom(parser);
                    provider.mAuths.put(authority.getAuthority(), authority);
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Couldn't read PkgUser", e);
                }
            }

            parser.next();
        }
        return provider;
    }

    public static String getFileName(PkgUser pkg) {
        return String.format("provider_%s", pkg.toString());
    }

    public static class SliceAuthority implements Persistable {
        private final String mAuthority;
        private final DirtyTracker mTracker;
        private final ArraySet<PkgUser> mPkgs = new ArraySet<>();

        public SliceAuthority(String authority, DirtyTracker tracker) {
            mAuthority = authority;
            mTracker = tracker;
        }

        public String getAuthority() {
            return mAuthority;
        }

        public synchronized void addPkg(PkgUser pkg) {
            if (mPkgs.add(pkg)) {
                mTracker.onPersistableDirty(this);
            }
        }

        public synchronized void removePkg(PkgUser pkg) {
            if (mPkgs.remove(pkg)) {
                mTracker.onPersistableDirty(this);
            }
        }

        public synchronized Collection<PkgUser> getPkgs() {
            return new ArraySet<>(mPkgs);
        }

        @Override
        public String getFileName() {
            return null;
        }

        public synchronized void writeTo(XmlSerializer out) throws IOException {
            final int N = mPkgs.size();
            for (int i = 0; i < N; i++) {
                out.startTag(NAMESPACE, TAG_PKG);
                out.text(mPkgs.valueAt(i).toString());
                out.endTag(NAMESPACE, TAG_PKG);
            }
        }

        public synchronized void readFrom(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            parser.next();
            int depth = parser.getDepth();
            while (parser.getDepth() >= depth) {
                if (parser.getEventType() == XmlPullParser.START_TAG
                        && TAG_PKG.equals(parser.getName())) {
                    mPkgs.add(new PkgUser(parser.nextText()));
                }
                parser.next();
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (!getClass().equals(obj != null ? obj.getClass() : null)) return false;
            SliceAuthority other = (SliceAuthority) obj;
            return Objects.equals(mAuthority, other.mAuthority)
                    && Objects.equals(mPkgs, other.mPkgs);
        }

        @Override
        public String toString() {
            return String.format("(%s: %s)", mAuthority, mPkgs.toString());
        }
    }
}
