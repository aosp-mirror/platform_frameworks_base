/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui.model;

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.Shared.compareToIgnoreCaseNullable;
import static com.android.documentsui.model.DocumentInfo.getCursorInt;
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.annotation.IntDef;
import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.text.TextUtils;
import android.util.Log;

import com.android.documentsui.IconUtils;
import com.android.documentsui.R;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.ProtocolException;
import java.util.Objects;

/**
 * Representation of a {@link Root}.
 */
public class RootInfo implements Durable, Parcelable, Comparable<RootInfo> {

    private static final String TAG = "RootInfo";
    private static final int VERSION_INIT = 1;
    private static final int VERSION_DROP_TYPE = 2;

    // The values of these constants determine the sort order of various roots in the RootsFragment.
    @IntDef(flag = false, value = {
            TYPE_IMAGES,
            TYPE_VIDEO,
            TYPE_AUDIO,
            TYPE_RECENTS,
            TYPE_DOWNLOADS,
            TYPE_LOCAL,
            TYPE_MTP,
            TYPE_SD,
            TYPE_USB,
            TYPE_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RootType {}
    public static final int TYPE_IMAGES = 1;
    public static final int TYPE_VIDEO = 2;
    public static final int TYPE_AUDIO = 3;
    public static final int TYPE_RECENTS = 4;
    public static final int TYPE_DOWNLOADS = 5;
    public static final int TYPE_LOCAL = 6;
    public static final int TYPE_MTP = 7;
    public static final int TYPE_SD = 8;
    public static final int TYPE_USB = 9;
    public static final int TYPE_OTHER = 10;

    public String authority;
    public String rootId;
    public int flags;
    public int icon;
    public String title;
    public String summary;
    public String documentId;
    public long availableBytes;
    public String mimeTypes;

    /** Derived fields that aren't persisted */
    public String[] derivedMimeTypes;
    public int derivedIcon;
    public @RootType int derivedType;

    public RootInfo() {
        reset();
    }

    @Override
    public void reset() {
        authority = null;
        rootId = null;
        flags = 0;
        icon = 0;
        title = null;
        summary = null;
        documentId = null;
        availableBytes = -1;
        mimeTypes = null;

        derivedMimeTypes = null;
        derivedIcon = 0;
        derivedType = 0;
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        final int version = in.readInt();
        switch (version) {
            case VERSION_DROP_TYPE:
                authority = DurableUtils.readNullableString(in);
                rootId = DurableUtils.readNullableString(in);
                flags = in.readInt();
                icon = in.readInt();
                title = DurableUtils.readNullableString(in);
                summary = DurableUtils.readNullableString(in);
                documentId = DurableUtils.readNullableString(in);
                availableBytes = in.readLong();
                mimeTypes = DurableUtils.readNullableString(in);
                deriveFields();
                break;
            default:
                throw new ProtocolException("Unknown version " + version);
        }
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(VERSION_DROP_TYPE);
        DurableUtils.writeNullableString(out, authority);
        DurableUtils.writeNullableString(out, rootId);
        out.writeInt(flags);
        out.writeInt(icon);
        DurableUtils.writeNullableString(out, title);
        DurableUtils.writeNullableString(out, summary);
        DurableUtils.writeNullableString(out, documentId);
        out.writeLong(availableBytes);
        DurableUtils.writeNullableString(out, mimeTypes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        DurableUtils.writeToParcel(dest, this);
    }

    public static final Creator<RootInfo> CREATOR = new Creator<RootInfo>() {
        @Override
        public RootInfo createFromParcel(Parcel in) {
            final RootInfo root = new RootInfo();
            DurableUtils.readFromParcel(in, root);
            return root;
        }

        @Override
        public RootInfo[] newArray(int size) {
            return new RootInfo[size];
        }
    };

    public static RootInfo fromRootsCursor(String authority, Cursor cursor) {
        final RootInfo root = new RootInfo();
        root.authority = authority;
        root.rootId = getCursorString(cursor, Root.COLUMN_ROOT_ID);
        root.flags = getCursorInt(cursor, Root.COLUMN_FLAGS);
        root.icon = getCursorInt(cursor, Root.COLUMN_ICON);
        root.title = getCursorString(cursor, Root.COLUMN_TITLE);
        root.summary = getCursorString(cursor, Root.COLUMN_SUMMARY);
        root.documentId = getCursorString(cursor, Root.COLUMN_DOCUMENT_ID);
        root.availableBytes = getCursorLong(cursor, Root.COLUMN_AVAILABLE_BYTES);
        root.mimeTypes = getCursorString(cursor, Root.COLUMN_MIME_TYPES);
        root.deriveFields();
        return root;
    }

    private void deriveFields() {
        derivedMimeTypes = (mimeTypes != null) ? mimeTypes.split("\n") : null;

        if (isHome()) {
            derivedType = TYPE_LOCAL;
            derivedIcon = R.drawable.ic_root_documents;
        } else if (isMtp()) {
            derivedType = TYPE_MTP;
            derivedIcon = R.drawable.ic_usb_storage;
        } else if (isUsb()) {
            derivedType = TYPE_USB;
            derivedIcon = R.drawable.ic_usb_storage;
        } else if (isSd()) {
            derivedType = TYPE_SD;
            derivedIcon = R.drawable.ic_sd_storage;
        } else if (isExternalStorage()) {
            derivedType = TYPE_LOCAL;
            derivedIcon = R.drawable.ic_root_smartphone;
        } else if (isDownloads()) {
            derivedType = TYPE_DOWNLOADS;
            derivedIcon = R.drawable.ic_root_download;
        } else if (isImages()) {
            derivedType = TYPE_IMAGES;
            derivedIcon = R.drawable.ic_doc_image;
        } else if (isVideos()) {
            derivedType = TYPE_VIDEO;
            derivedIcon = R.drawable.ic_doc_video;
        } else if (isAudio()) {
            derivedType = TYPE_AUDIO;
            derivedIcon = R.drawable.ic_doc_audio;
        } else if (isRecents()) {
            derivedType = TYPE_RECENTS;
        } else {
            derivedType = TYPE_OTHER;
        }

        if (DEBUG) Log.d(TAG, "Finished deriving fields: " + this);
    }

    public Uri getUri() {
        return DocumentsContract.buildRootUri(authority, rootId);
    }

    public boolean isRecents() {
        return authority == null && rootId == null;
    }

    public boolean isHome() {
        // Note that "home" is the expected root id for the auto-created
        // user home directory on external storage. The "home" value should
        // match ExternalStorageProvider.ROOT_ID_HOME.
        return isExternalStorage() && "home".equals(rootId);
    }

    public boolean isExternalStorage() {
        return "com.android.externalstorage.documents".equals(authority);
    }

    public boolean isDownloads() {
        return "com.android.providers.downloads.documents".equals(authority);
    }

    public boolean isImages() {
        return "com.android.providers.media.documents".equals(authority)
                && "images_root".equals(rootId);
    }

    public boolean isVideos() {
        return "com.android.providers.media.documents".equals(authority)
                && "videos_root".equals(rootId);
    }

    public boolean isAudio() {
        return "com.android.providers.media.documents".equals(authority)
                && "audio_root".equals(rootId);
    }

    public boolean isMtp() {
        return "com.android.mtp.documents".equals(authority);
    }

    public boolean isLibrary() {
        return derivedType == TYPE_IMAGES
                || derivedType == TYPE_VIDEO
                || derivedType == TYPE_AUDIO
                || derivedType == TYPE_RECENTS;
    }

    public boolean hasSettings() {
        return (flags & Root.FLAG_HAS_SETTINGS) != 0;
    }

    public boolean supportsChildren() {
        return (flags & Root.FLAG_SUPPORTS_IS_CHILD) != 0;
    }

    public boolean supportsCreate() {
        return (flags & Root.FLAG_SUPPORTS_CREATE) != 0;
    }

    public boolean supportsRecents() {
        return (flags & Root.FLAG_SUPPORTS_RECENTS) != 0;
    }

    public boolean supportsSearch() {
        return (flags & Root.FLAG_SUPPORTS_SEARCH) != 0;
    }

    public boolean isAdvanced() {
        return (flags & Root.FLAG_ADVANCED) != 0;
    }

    public boolean isLocalOnly() {
        return (flags & Root.FLAG_LOCAL_ONLY) != 0;
    }

    public boolean isEmpty() {
        return (flags & Root.FLAG_EMPTY) != 0;
    }

    public boolean isSd() {
        return (flags & Root.FLAG_REMOVABLE_SD) != 0;
    }

    public boolean isUsb() {
        return (flags & Root.FLAG_REMOVABLE_USB) != 0;
    }

    public Drawable loadIcon(Context context) {
        if (derivedIcon != 0) {
            return context.getDrawable(derivedIcon);
        } else {
            return IconUtils.loadPackageIcon(context, authority, icon);
        }
    }

    public Drawable loadDrawerIcon(Context context) {
        if (derivedIcon != 0) {
            return IconUtils.applyTintColor(context, derivedIcon, R.color.item_root_icon);
        } else {
            return IconUtils.loadPackageIcon(context, authority, icon);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }

        if (this == o) {
            return true;
        }

        if (o instanceof RootInfo) {
            RootInfo other = (RootInfo) o;
            return Objects.equals(authority, other.authority)
                    && Objects.equals(rootId, other.rootId);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(authority, rootId);
    }

    @Override
    public int compareTo(RootInfo other) {
        // Sort by root type, then title, then summary.
        int score = derivedType - other.derivedType;
        if (score != 0) {
            return score;
        }

        score = compareToIgnoreCaseNullable(title, other.title);
        if (score != 0) {
            return score;
        }

        return compareToIgnoreCaseNullable(summary, other.summary);
    }

    @Override
    public String toString() {
        return "Root{"
                + "authority=" + authority
                + ", rootId=" + rootId
                + ", title=" + title
                + ", isUsb=" + isUsb()
                + ", isSd=" + isSd()
                + ", isMtp=" + isMtp()
                + "}";
    }

    public String getDirectoryString() {
        return !TextUtils.isEmpty(summary) ? summary : title;
    }
}
