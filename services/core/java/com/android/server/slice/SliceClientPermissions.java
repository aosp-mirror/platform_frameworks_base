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
import android.content.ContentResolver;
import android.net.Uri;
import android.text.TextUtils;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class SliceClientPermissions implements DirtyTracker, Persistable {

    private static final String TAG = "SliceClientPermissions";

    static final String TAG_CLIENT = "client";
    private static final String TAG_AUTHORITY = "authority";
    private static final String TAG_PATH = "path";
    private static final String NAMESPACE = null;

    private static final String ATTR_PKG = "pkg";
    private static final String ATTR_AUTHORITY = "authority";
    private static final String ATTR_FULL_ACCESS = "fullAccess";

    private final PkgUser mPkg;
    // Keyed off (authority, userId) rather than the standard (pkg, userId)
    private final ArrayMap<PkgUser, SliceAuthority> mAuths = new ArrayMap<>();
    private final DirtyTracker mTracker;
    private boolean mHasFullAccess;

    public SliceClientPermissions(@NonNull PkgUser pkg, @NonNull DirtyTracker tracker) {
        mPkg = pkg;
        mTracker = tracker;
    }

    public PkgUser getPkg() {
        return mPkg;
    }

    public synchronized Collection<SliceAuthority> getAuthorities() {
        return new ArrayList<>(mAuths.values());
    }

    public synchronized SliceAuthority getOrCreateAuthority(PkgUser authority, PkgUser provider) {
        SliceAuthority ret = mAuths.get(authority);
        if (ret == null) {
            ret = new SliceAuthority(authority.getPkg(), provider, this);
            mAuths.put(authority, ret);
            onPersistableDirty(ret);
        }
        return ret;
    }

    public synchronized SliceAuthority getAuthority(PkgUser authority) {
        return mAuths.get(authority);
    }

    public boolean hasFullAccess() {
        return mHasFullAccess;
    }

    public void setHasFullAccess(boolean hasFullAccess) {
        if (mHasFullAccess == hasFullAccess) return;
        mHasFullAccess = hasFullAccess;
        mTracker.onPersistableDirty(this);
    }

    public void removeAuthority(String authority, int userId) {
        if (mAuths.remove(new PkgUser(authority, userId)) != null) {
            mTracker.onPersistableDirty(this);
        }
    }

    public synchronized boolean hasPermission(Uri uri, int userId) {
        if (!Objects.equals(ContentResolver.SCHEME_CONTENT, uri.getScheme())) return false;
        SliceAuthority authority = getAuthority(new PkgUser(uri.getAuthority(), userId));
        return authority != null && authority.hasPermission(uri.getPathSegments());
    }

    public void grantUri(Uri uri, PkgUser providerPkg) {
        SliceAuthority authority = getOrCreateAuthority(
                new PkgUser(uri.getAuthority(), providerPkg.getUserId()),
                providerPkg);
        authority.addPath(uri.getPathSegments());
    }

    public void revokeUri(Uri uri, PkgUser providerPkg) {
        SliceAuthority authority = getOrCreateAuthority(
                new PkgUser(uri.getAuthority(), providerPkg.getUserId()),
                providerPkg);
        authority.removePath(uri.getPathSegments());
    }

    public void clear() {
        if (!mHasFullAccess && mAuths.isEmpty()) return;
        mHasFullAccess = false;
        mAuths.clear();
        onPersistableDirty(this);
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
        out.startTag(NAMESPACE, TAG_CLIENT);
        out.attribute(NAMESPACE, ATTR_PKG, mPkg.toString());
        out.attribute(NAMESPACE, ATTR_FULL_ACCESS, mHasFullAccess ? "1" : "0");

        final int N = mAuths.size();
        for (int i = 0; i < N; i++) {
            out.startTag(NAMESPACE, TAG_AUTHORITY);
            out.attribute(NAMESPACE, ATTR_AUTHORITY, mAuths.valueAt(i).mAuthority);
            out.attribute(NAMESPACE, ATTR_PKG, mAuths.valueAt(i).mPkg.toString());

            mAuths.valueAt(i).writeTo(out);

            out.endTag(NAMESPACE, TAG_AUTHORITY);
        }

        out.endTag(NAMESPACE, TAG_CLIENT);
    }

    public static SliceClientPermissions createFrom(XmlPullParser parser, DirtyTracker tracker)
            throws XmlPullParserException, IOException {
        // Get to the beginning of the provider.
        while (parser.getEventType() != XmlPullParser.START_TAG
                || !TAG_CLIENT.equals(parser.getName())) {
            parser.next();
        }
        int depth = parser.getDepth();
        PkgUser pkgUser = new PkgUser(parser.getAttributeValue(NAMESPACE, ATTR_PKG));
        SliceClientPermissions provider = new SliceClientPermissions(pkgUser, tracker);
        String fullAccess = parser.getAttributeValue(NAMESPACE, ATTR_FULL_ACCESS);
        if (fullAccess == null) {
            fullAccess = "0";
        }
        provider.mHasFullAccess = Integer.parseInt(fullAccess) != 0;
        parser.next();

        while (parser.getDepth() > depth) {
            if (parser.getEventType() == XmlPullParser.START_TAG
                    && TAG_AUTHORITY.equals(parser.getName())) {
                try {
                    PkgUser pkg = new PkgUser(parser.getAttributeValue(NAMESPACE, ATTR_PKG));
                    SliceAuthority authority = new SliceAuthority(
                            parser.getAttributeValue(NAMESPACE, ATTR_AUTHORITY), pkg, provider);
                    authority.readFrom(parser);
                    provider.mAuths.put(new PkgUser(authority.getAuthority(), pkg.getUserId()),
                            authority);
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Couldn't read PkgUser", e);
                }
            }

            parser.next();
        }
        return provider;
    }

    public static String getFileName(PkgUser pkg) {
        return String.format("client_%s", pkg.toString());
    }

    public static class SliceAuthority implements Persistable {
        public static final String DELIMITER = "/";
        private final String mAuthority;
        private final DirtyTracker mTracker;
        private final PkgUser mPkg;
        private final ArraySet<String[]> mPaths = new ArraySet<>();

        public SliceAuthority(String authority, PkgUser pkg, DirtyTracker tracker) {
            mAuthority = authority;
            mPkg = pkg;
            mTracker = tracker;
        }

        public String getAuthority() {
            return mAuthority;
        }

        public PkgUser getPkg() {
            return mPkg;
        }

        void addPath(List<String> path) {
            String[] pathSegs = path.toArray(new String[path.size()]);
            for (int i = mPaths.size() - 1; i >= 0; i--) {
                String[] existing = mPaths.valueAt(i);
                if (isPathPrefixMatch(existing, pathSegs)) {
                    // Nothing to add here.
                    return;
                }
                if (isPathPrefixMatch(pathSegs, existing)) {
                    mPaths.removeAt(i);
                }
            }
            mPaths.add(pathSegs);
            mTracker.onPersistableDirty(this);
        }

        void removePath(List<String> path) {
            boolean changed = false;
            String[] pathSegs = path.toArray(new String[path.size()]);
            for (int i = mPaths.size() - 1; i >= 0; i--) {
                String[] existing = mPaths.valueAt(i);
                if (isPathPrefixMatch(pathSegs, existing)) {
                    changed = true;
                    mPaths.removeAt(i);
                }
            }
            if (changed) {
                mTracker.onPersistableDirty(this);
            }
        }

        public synchronized Collection<String[]> getPaths() {
            return new ArraySet<>(mPaths);
        }

        public boolean hasPermission(List<String> path) {
            for (String[] p : mPaths) {
                if (isPathPrefixMatch(p, path.toArray(new String[path.size()]))) {
                    return true;
                }
            }
            return false;
        }

        private boolean isPathPrefixMatch(String[] prefix, String[] path) {
            final int prefixSize = prefix.length;
            if (path.length < prefixSize) return false;

            for (int i = 0; i < prefixSize; i++) {
                if (!Objects.equals(path[i], prefix[i])) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public String getFileName() {
            return null;
        }

        public synchronized void writeTo(XmlSerializer out) throws IOException {
            final int N = mPaths.size();
            for (int i = 0; i < N; i++) {
                out.startTag(NAMESPACE, TAG_PATH);
                out.text(encodeSegments(mPaths.valueAt(i)));
                out.endTag(NAMESPACE, TAG_PATH);
            }
        }

        public synchronized void readFrom(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            parser.next();
            int depth = parser.getDepth();
            while (parser.getDepth() >= depth) {
                if (parser.getEventType() == XmlPullParser.START_TAG
                        && TAG_PATH.equals(parser.getName())) {
                    mPaths.add(decodeSegments(parser.nextText()));
                }
                parser.next();
            }
        }

        private String encodeSegments(String[] s) {
            String[] out = new String[s.length];
            for (int i = 0; i < s.length; i++) {
                out[i] = Uri.encode(s[i]);
            }
            return TextUtils.join(DELIMITER, out);
        }

        private String[] decodeSegments(String s) {
            String[] sets = s.split(DELIMITER, -1);
            for (int i = 0; i < sets.length; i++) {
                sets[i] = Uri.decode(sets[i]);
            }
            return sets;
        }

        /**
         * Only for testing, no deep equality of these are done normally.
         */
        @Override
        public boolean equals(Object obj) {
            if (!getClass().equals(obj != null ? obj.getClass() : null)) return false;
            SliceAuthority other = (SliceAuthority) obj;
            if (mPaths.size() != other.mPaths.size()) return false;
            ArrayList<String[]> p1 = new ArrayList<>(mPaths);
            ArrayList<String[]> p2 = new ArrayList<>(other.mPaths);
            p1.sort(Comparator.comparing(o -> TextUtils.join(",", o)));
            p2.sort(Comparator.comparing(o -> TextUtils.join(",", o)));
            for (int i = 0; i < p1.size(); i++) {
                String[] a1 = p1.get(i);
                String[] a2 = p2.get(i);
                if (a1.length != a2.length) return false;
                for (int j = 0; j < a1.length; j++) {
                    if (!Objects.equals(a1[j], a2[j])) return false;
                }
            }
            return Objects.equals(mAuthority, other.mAuthority)
                    && Objects.equals(mPkg, other.mPkg);
        }

        @Override
        public String toString() {
            return String.format("(%s, %s: %s)", mAuthority, mPkg.toString(), pathToString(mPaths));
        }

        private String pathToString(ArraySet<String[]> paths) {
            return TextUtils.join(", ", paths.stream().map(s -> TextUtils.join("/", s))
                    .collect(Collectors.toList()));
        }
    }
}
