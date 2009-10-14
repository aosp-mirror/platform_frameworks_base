/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.os;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.zip.GZIPInputStream;

/**
 * A single entry retrieved from an {@link IDropBox} implementation.
 * This may include a reference to a stream, so you must call
 * {@link #close()} when you are done using it.
 *
 * {@pending}
 */
public class DropBoxEntry implements Parcelable {
    private final String mTag;
    private final long mTimeMillis;

    private final String mText;
    private final ParcelFileDescriptor mFileDescriptor;
    private final int mFlags;

    /** Flag value: Entry's content was deleted to save space. */
    public static final int IS_EMPTY = 1;

    /** Flag value: Content is human-readable UTF-8 text (possibly compressed). */
    public static final int IS_TEXT = 2;

    /** Flag value: Content can been decompressed with {@link GZIPOutputStream}. */
    public static final int IS_GZIPPED = 4;

    /** Create a new DropBoxEntry with the specified contents. */
    public DropBoxEntry(String tag, long timeMillis, String text) {
        if (tag == null || text == null) throw new NullPointerException();
        mTag = tag;
        mTimeMillis = timeMillis;
        mText = text;
        mFileDescriptor = null;
        mFlags = IS_TEXT;
    }

    /** Create a new DropBoxEntry with the specified contents. */
    public DropBoxEntry(String tag, long millis, File data, int flags) throws IOException {
        if (tag == null) throw new NullPointerException();
        if (((flags & IS_EMPTY) != 0) != (data == null)) throw new IllegalArgumentException();

        mTag = tag;
        mTimeMillis = millis;
        mText = null;
        mFlags = flags;
        mFileDescriptor = data == null ? null :
                ParcelFileDescriptor.open(data, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Internal constructor for CREATOR.createFromParcel(). */
    private DropBoxEntry(String tag, long millis, Object value, int flags) {
        if (tag == null) throw new NullPointerException();
        if (((flags & IS_EMPTY) != 0) != (value == null)) throw new IllegalArgumentException();

        mTag = tag;
        mTimeMillis = millis;
        mFlags = flags;

        if (value == null) {
            mText = null;
            mFileDescriptor = null;
        } else if (value instanceof String) {
            if ((flags & IS_TEXT) == 0) throw new IllegalArgumentException();
            mText = (String) value;
            mFileDescriptor = null;
        } else if (value instanceof ParcelFileDescriptor) {
            mText = null;
            mFileDescriptor = (ParcelFileDescriptor) value;
        } else {
            throw new IllegalArgumentException();
        }
    }

    /** Close the input stream associated with this entry. */
    public synchronized void close() {
        try { if (mFileDescriptor != null) mFileDescriptor.close(); } catch (IOException e) { }
    }

    /** @return the tag originally attached to the entry. */
    public String getTag() { return mTag; }

    /** @return time when the entry was originally created. */
    public long getTimeMillis() { return mTimeMillis; }

    /** @return flags describing the content returned by @{link #getInputStream()}. */
    public int getFlags() { return mFlags & ~IS_GZIPPED; }  // getInputStream() decompresses.

    /**
     * @param maxLength of string to return (will truncate at this length).
     * @return the uncompressed text contents of the entry, null if the entry is not text.
     */
    public String getText(int maxLength) {
        if (mText != null) return mText.substring(0, Math.min(maxLength, mText.length()));
        if ((mFlags & IS_TEXT) == 0) return null;

        try {
            InputStream stream = getInputStream();
            if (stream == null) return null;
            char[] buf = new char[maxLength];
            InputStreamReader reader = new InputStreamReader(stream);
            return new String(buf, 0, Math.max(0, reader.read(buf)));
        } catch (IOException e) {
            return null;
        }
    }

    /** @return the uncompressed contents of the entry, or null if the contents were lost */
    public InputStream getInputStream() throws IOException {
        if (mText != null) return new ByteArrayInputStream(mText.getBytes("UTF8"));
        if (mFileDescriptor == null) return null;
        InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(mFileDescriptor);
        return (mFlags & IS_GZIPPED) != 0 ? new GZIPInputStream(is) : is;
    }

    public static final Parcelable.Creator<DropBoxEntry> CREATOR = new Parcelable.Creator() {
        public DropBoxEntry[] newArray(int size) { return new DropBoxEntry[size]; }
        public DropBoxEntry createFromParcel(Parcel in) {
            return new DropBoxEntry(
                    in.readString(), in.readLong(), in.readValue(null), in.readInt());
        }
    };

    public int describeContents() {
        return mFileDescriptor != null ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mTag);
        out.writeLong(mTimeMillis);
        if (mFileDescriptor != null) {
            out.writeValue(mFileDescriptor);
        } else {
            out.writeValue(mText);
        }
        out.writeInt(mFlags);
    }
}
