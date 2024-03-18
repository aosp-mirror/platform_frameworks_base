/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.Process.INVALID_UID;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.uri.GrantUri;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Represents the state of activity callers. Used by {@link ActivityRecord}.
 * @hide
 */
final class ActivityCallerState {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityCallerState" : TAG_ATM;

    // XML tags for CallerInfo
    private static final String ATTR_CALLER_UID = "caller_uid";
    private static final String ATTR_CALLER_PACKAGE = "caller_package";
    private static final String ATTR_CALLER_IS_SHARE_ENABLED = "caller_is_share_enabled";
    private static final String TAG_READABLE_CONTENT_URI = "readable_content_uri";
    private static final String TAG_WRITABLE_CONTENT_URI = "writable_content_uri";
    private static final String TAG_INACCESSIBLE_CONTENT_URI = "inaccessible_content_uri";
    private static final String ATTR_SOURCE_USER_ID = "source_user_id";
    private static final String ATTR_URI = "uri";
    private static final String ATTR_PREFIX = "prefix";

    // Map for storing CallerInfo instances
    private final WeakHashMap<IBinder, CallerInfo> mCallerTokenInfoMap = new WeakHashMap<>();

    final ActivityTaskManagerService mAtmService;

    ActivityCallerState(ActivityTaskManagerService service) {
        mAtmService = service;
    }

    CallerInfo getCallerInfoOrNull(IBinder callerToken) {
        return mCallerTokenInfoMap.getOrDefault(callerToken, null);
    }

    boolean hasCaller(IBinder callerToken) {
        return getCallerInfoOrNull(callerToken) != null;
    }

    int getUid(IBinder callerToken) {
        CallerInfo callerInfo = getCallerInfoOrNull(callerToken);
        return callerInfo != null ? callerInfo.mUid : INVALID_UID;
    }

    String getPackage(IBinder callerToken) {
        CallerInfo callerInfo = getCallerInfoOrNull(callerToken);
        return callerInfo != null ? callerInfo.mPackageName : null;
    }

    boolean isShareIdentityEnabled(IBinder callerToken) {
        CallerInfo callerInfo = getCallerInfoOrNull(callerToken);
        return callerInfo != null ? callerInfo.mIsShareIdentityEnabled : false;
    }

    void add(IBinder callerToken, CallerInfo callerInfo) {
        mCallerTokenInfoMap.put(callerToken, callerInfo);
    }

    void computeCallerInfo(IBinder callerToken, Intent intent, int callerUid,
            String callerPackageName, boolean isCallerShareIdentityEnabled) {
        final CallerInfo callerInfo = new CallerInfo(callerUid, callerPackageName,
                isCallerShareIdentityEnabled);
        mCallerTokenInfoMap.put(callerToken, callerInfo);

        final ArraySet<Uri> contentUris = getContentUrisFromIntent(intent);
        for (int i = contentUris.size() - 1; i >= 0; i--) {
            final Uri contentUri = contentUris.valueAt(i);

            final boolean hasRead = addContentUriIfUidHasPermission(contentUri, callerUid,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION, callerInfo.mReadableContentUris);

            final boolean hasWrite = addContentUriIfUidHasPermission(contentUri, callerUid,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION, callerInfo.mWritableContentUris);

            if (!hasRead && !hasWrite) {
                callerInfo.mInaccessibleContentUris.add(convertToGrantUri(contentUri,
                        /* modeFlags */ 0, callerUid));
            }
        }
    }

    boolean checkContentUriPermission(IBinder callerToken, GrantUri grantUri, int modeFlags) {
        if (!Intent.isAccessUriMode(modeFlags)) {
            throw new IllegalArgumentException("Mode flags are not access URI mode flags: "
                    + modeFlags);
        }

        final CallerInfo callerInfo = mCallerTokenInfoMap.getOrDefault(callerToken, null);
        if (callerInfo == null) {
            Slog.e(TAG, "Caller not found for checkContentUriPermission of: "
                    + grantUri.uri.toSafeString());
            return false;
        }

        if (callerInfo.mInaccessibleContentUris.contains(grantUri)) {
            return false;
        }

        final boolean readMet = callerInfo.mReadableContentUris.contains(grantUri);
        final boolean writeMet = callerInfo.mWritableContentUris.contains(grantUri);

        if (!readMet && !writeMet) {
            throw new IllegalArgumentException("The supplied URI wasn't passed at launch in"
                    + " #getData, #EXTRA_STREAM, nor #getClipData: " + grantUri.uri.toSafeString());
        }

        final boolean checkRead = (modeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        if (checkRead && !readMet) {
            return false;
        }

        final boolean checkWrite = (modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        if (checkWrite && !writeMet) {
            return false;
        }

        return true;
    }

    private boolean addContentUriIfUidHasPermission(Uri contentUri, int uid, int modeFlags,
            ArraySet<GrantUri> grantUris) {
        final GrantUri grantUri = convertToGrantUri(contentUri, modeFlags, uid);
        if (mAtmService.mUgmInternal.checkUriPermission(grantUri, uid,
                modeFlags, /* isFullAccessForContentUri */ true)) {
            grantUris.add(grantUri);
            return true;
        }
        return false;
    }

    private static GrantUri convertToGrantUri(Uri contentUri, int modeFlags, int uid) {
        return new GrantUri(ContentProvider.getUserIdFromUri(contentUri,
                UserHandle.getUserId(uid)), ContentProvider.getUriWithoutUserId(contentUri),
                modeFlags);
    }

    private static ArraySet<Uri> getContentUrisFromIntent(Intent intent) {
        final ArraySet<Uri> uris = new ArraySet<>();
        if (intent == null) return uris;

        // getData
        addUriIfContentUri(intent.getData(), uris);

        // EXTRA_STREAM
        if (intent.hasExtra(Intent.EXTRA_STREAM)) {
            final ArrayList<Uri> streams = tryToUnparcelArrayListExtraStreamsUri(intent);
            if (streams == null) {
                addUriIfContentUri(tryToUnparcelExtraStreamUri(intent), uris);
            } else {
                for (int i = streams.size() - 1; i >= 0; i--) {
                    addUriIfContentUri(streams.get(i), uris);
                }
            }
        }

        final ClipData clipData = intent.getClipData();
        if (clipData == null) return uris;

        for (int i = 0; i < clipData.getItemCount(); i++) {
            final ClipData.Item item = clipData.getItemAt(i);

            // getUri
            addUriIfContentUri(item.getUri(), uris);

            // getIntent
            uris.addAll(getContentUrisFromIntent(item.getIntent()));
        }
        return uris;
    }

    private static Uri tryToUnparcelExtraStreamUri(Intent intent) {
        try {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        } catch (BadParcelableException e) {
            // Even though the system "defuses" all the parsed Bundles, i.e. suppresses and logs
            // instances of {@link BadParcelableException}, we still want to be on the safer side
            // and catch the exception to ensure no breakages happen. If the unparcel fails, the
            // item is still preserved with the underlying parcel.
            Slog.w(TAG, "Failed to unparcel an URI in EXTRA_STREAM, returning null: " + e);
            return null;
        }
    }

    private static ArrayList<Uri> tryToUnparcelArrayListExtraStreamsUri(Intent intent) {
        try {
            return intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        } catch (BadParcelableException e) {
            // Even though the system "defuses" all the parsed Bundles, i.e. suppresses and logs
            // instances of {@link BadParcelableException}, we still want to be on the safer side
            // and catch the exception to ensure no breakages happen. If the unparcel fails, the
            // item is still preserved with the underlying parcel.
            Slog.w(TAG, "Failed to unparcel an ArrayList of URIs in EXTRA_STREAM, returning null: "
                    + e);
            return null;
        }
    }

    private static void addUriIfContentUri(Uri uri, ArraySet<Uri> uris) {
        if (uri != null && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            uris.add(uri);
        }
    }

    public static final class CallerInfo {
        final int mUid;
        final String mPackageName;
        final boolean mIsShareIdentityEnabled;
        final ArraySet<GrantUri> mReadableContentUris = new ArraySet<>();
        final ArraySet<GrantUri> mWritableContentUris = new ArraySet<>();
        final ArraySet<GrantUri> mInaccessibleContentUris = new ArraySet<>();

        CallerInfo(int uid, String packageName, boolean isShareIdentityEnabled) {
            mUid = uid;
            mPackageName = packageName;
            mIsShareIdentityEnabled = isShareIdentityEnabled;
        }

        public void saveToXml(TypedXmlSerializer out)
                throws IOException, XmlPullParserException {
            out.attributeInt(null, ATTR_CALLER_UID, mUid);
            if (mPackageName != null) {
                out.attribute(null, ATTR_CALLER_PACKAGE, mPackageName);
            }
            out.attributeBoolean(null, ATTR_CALLER_IS_SHARE_ENABLED, mIsShareIdentityEnabled);
            for (int i = mReadableContentUris.size() - 1; i >= 0; i--) {
                saveGrantUriToXml(out, mReadableContentUris.valueAt(i), TAG_READABLE_CONTENT_URI);
            }

            for (int i = mWritableContentUris.size() - 1; i >= 0; i--) {
                saveGrantUriToXml(out, mWritableContentUris.valueAt(i), TAG_WRITABLE_CONTENT_URI);
            }

            for (int i = mInaccessibleContentUris.size() - 1; i >= 0; i--) {
                saveGrantUriToXml(out, mInaccessibleContentUris.valueAt(i),
                        TAG_INACCESSIBLE_CONTENT_URI);
            }
        }

        public static CallerInfo restoreFromXml(TypedXmlPullParser in)
                throws IOException, XmlPullParserException {
            int uid = in.getAttributeInt(null, ATTR_CALLER_UID, 0);
            String packageName = in.getAttributeValue(null, ATTR_CALLER_PACKAGE);
            boolean isShareIdentityEnabled = in.getAttributeBoolean(null,
                    ATTR_CALLER_IS_SHARE_ENABLED, false);

            CallerInfo callerInfo = new CallerInfo(uid, packageName, isShareIdentityEnabled);
            final int outerDepth = in.getDepth();
            int event;
            while (((event = in.next()) != END_DOCUMENT)
                    && (event != END_TAG || in.getDepth() >= outerDepth)) {
                if (event == START_TAG) {
                    final String name = in.getName();
                    if (TAG_READABLE_CONTENT_URI.equals(name)) {
                        callerInfo.mReadableContentUris.add(restoreGrantUriFromXml(in));
                    } else if (TAG_WRITABLE_CONTENT_URI.equals(name)) {
                        callerInfo.mWritableContentUris.add(restoreGrantUriFromXml(in));
                    } else if (TAG_INACCESSIBLE_CONTENT_URI.equals(name)) {
                        callerInfo.mInaccessibleContentUris.add(restoreGrantUriFromXml(in));
                    } else {
                        Slog.w(TAG, "restoreActivity: unexpected name=" + name);
                        XmlUtils.skipCurrentTag(in);
                    }
                }
            }
            return callerInfo;
        }

        private void saveGrantUriToXml(TypedXmlSerializer out, GrantUri grantUri, String tag)
                throws IOException, XmlPullParserException {
            out.startTag(null, tag);
            out.attributeInt(null, ATTR_SOURCE_USER_ID, grantUri.sourceUserId);
            out.attribute(null, ATTR_URI, String.valueOf(grantUri.uri));
            out.attributeBoolean(null, ATTR_PREFIX, grantUri.prefix);
            out.endTag(null, tag);
        }

        private static GrantUri restoreGrantUriFromXml(TypedXmlPullParser in)
                throws IOException, XmlPullParserException {
            int sourceUserId = in.getAttributeInt(null, ATTR_SOURCE_USER_ID, 0);
            Uri uri = Uri.parse(in.getAttributeValue(null, ATTR_URI));
            boolean prefix = in.getAttributeBoolean(null, ATTR_PREFIX, false);
            return new GrantUri(sourceUserId, uri,
                    prefix ? Intent.FLAG_GRANT_PREFIX_URI_PERMISSION : 0);
        }
    }
}
