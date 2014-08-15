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

import static com.android.documentsui.model.DocumentInfo.getCursorInt;
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.DocumentsContract.Root;
import android.text.TextUtils;

import com.android.documentsui.IconUtils;
import com.android.documentsui.R;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Objects;

/**
 * Representation of a {@link Root}.
 */
public class RootInfo implements Durable, Parcelable {
    private static final int VERSION_INIT = 1;
    private static final int VERSION_DROP_TYPE = 2;

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
    public String derivedPackageName;
    public String[] derivedMimeTypes;
    public int derivedIcon;

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

        derivedPackageName = null;
        derivedMimeTypes = null;
        derivedIcon = 0;
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

        // TODO: remove these special case icons
        if (isExternalStorage()) {
            derivedIcon = R.drawable.ic_root_sdcard;
        } else if (isDownloads()) {
            derivedIcon = R.drawable.ic_root_download;
        } else if (isImages()) {
            derivedIcon = R.drawable.ic_doc_image;
        } else if (isVideos()) {
            derivedIcon = R.drawable.ic_doc_video;
        } else if (isAudio()) {
            derivedIcon = R.drawable.ic_doc_audio;
        }
    }

    public boolean isRecents() {
        return authority == null && rootId == null;
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

    @Override
    public String toString() {
        return "Root{authority=" + authority + ", rootId=" + rootId + ", title=" + title + "}";
    }

    public Drawable loadIcon(Context context) {
        if (derivedIcon != 0) {
            return context.getDrawable(derivedIcon);
        } else {
            return IconUtils.loadPackageIcon(context, authority, icon);
        }
    }

    public Drawable loadGridIcon(Context context) {
        if (derivedIcon != 0) {
            return IconUtils.applyTint(context, derivedIcon,
                    android.R.attr.textColorPrimaryInverse);
        } else {
            return IconUtils.loadPackageIcon(context, authority, icon);
        }
    }

    public Drawable loadToolbarIcon(Context context) {
        if (derivedIcon != 0) {
            return IconUtils.applyTint(context, derivedIcon,
                    android.R.attr.colorControlNormal);
        } else {
            return IconUtils.loadPackageIcon(context, authority, icon);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof RootInfo) {
            final RootInfo root = (RootInfo) o;
            return Objects.equals(authority, root.authority) && Objects.equals(rootId, root.rootId);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(authority, rootId);
    }

    public String getDirectoryString() {
        return !TextUtils.isEmpty(summary) ? summary : title;
    }
}
