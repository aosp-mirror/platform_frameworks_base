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

import com.android.internal.os.IDropBoxManagerService;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Enqueues chunks of data (from various sources -- application crashes, kernel
 * log records, etc.).  The queue is size bounded and will drop old data if the
 * enqueued data exceeds the maximum size.  You can think of this as a
 * persistent, system-wide, blob-oriented "logcat".
 *
 * <p>You can obtain an instance of this class by calling
 * {@link android.content.Context#getSystemService}
 * with {@link android.content.Context#DROPBOX_SERVICE}.
 *
 * <p>DropBoxManager entries are not sent anywhere directly, but other system
 * services and debugging tools may scan and upload entries for processing.
 */
public class DropBoxManager {
    private static final String TAG = "DropBoxManager";
    private final IDropBoxManagerService mService;

    /** Flag value: Entry's content was deleted to save space. */
    public static final int IS_EMPTY = 1;

    /** Flag value: Content is human-readable UTF-8 text (can be combined with IS_GZIPPED). */
    public static final int IS_TEXT = 2;

    /** Flag value: Content can be decompressed with {@link java.util.zip.GZIPOutputStream}. */
    public static final int IS_GZIPPED = 4;

    /** Flag value for serialization only: Value is a byte array, not a file descriptor */
    private static final int HAS_BYTE_ARRAY = 8;

    /**
     * Broadcast Action: This is broadcast when a new entry is added in the dropbox.
     * You must hold the {@link android.Manifest.permission#READ_LOGS} permission
     * in order to receive this broadcast.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     */
    public static final String ACTION_DROPBOX_ENTRY_ADDED =
        "android.intent.action.DROPBOX_ENTRY_ADDED";

    /**
     * Extra for {@link android.os.DropBoxManager#ACTION_DROPBOX_ENTRY_ADDED}:
     * string containing the dropbox tag.
     */
    public static final String EXTRA_TAG = "tag";

    /**
     * Extra for {@link android.os.DropBoxManager#ACTION_DROPBOX_ENTRY_ADDED}:
     * long integer value containing time (in milliseconds since January 1, 1970 00:00:00 UTC)
     * when the entry was created.
     */
    public static final String EXTRA_TIME = "time";

    /**
     * A single entry retrieved from the drop box.
     * This may include a reference to a stream, so you must call
     * {@link #close()} when you are done using it.
     */
    public static class Entry implements Parcelable, Closeable {
        private final String mTag;
        private final long mTimeMillis;

        private final byte[] mData;
        private final ParcelFileDescriptor mFileDescriptor;
        private final int mFlags;

        /** Create a new empty Entry with no contents. */
        public Entry(String tag, long millis) {
            if (tag == null) throw new NullPointerException("tag == null");

            mTag = tag;
            mTimeMillis = millis;
            mData = null;
            mFileDescriptor = null;
            mFlags = IS_EMPTY;
        }

        /** Create a new Entry with plain text contents. */
        public Entry(String tag, long millis, String text) {
            if (tag == null) throw new NullPointerException("tag == null");
            if (text == null) throw new NullPointerException("text == null");

            mTag = tag;
            mTimeMillis = millis;
            mData = text.getBytes();
            mFileDescriptor = null;
            mFlags = IS_TEXT;
        }

        /**
         * Create a new Entry with byte array contents.
         * The data array must not be modified after creating this entry.
         */
        public Entry(String tag, long millis, byte[] data, int flags) {
            if (tag == null) throw new NullPointerException("tag == null");
            if (((flags & IS_EMPTY) != 0) != (data == null)) {
                throw new IllegalArgumentException("Bad flags: " + flags);
            }

            mTag = tag;
            mTimeMillis = millis;
            mData = data;
            mFileDescriptor = null;
            mFlags = flags;
        }

        /**
         * Create a new Entry with streaming data contents.
         * Takes ownership of the ParcelFileDescriptor.
         */
        public Entry(String tag, long millis, ParcelFileDescriptor data, int flags) {
            if (tag == null) throw new NullPointerException("tag == null");
            if (((flags & IS_EMPTY) != 0) != (data == null)) {
                throw new IllegalArgumentException("Bad flags: " + flags);
            }

            mTag = tag;
            mTimeMillis = millis;
            mData = null;
            mFileDescriptor = data;
            mFlags = flags;
        }

        /**
         * Create a new Entry with the contents read from a file.
         * The file will be read when the entry's contents are requested.
         */
        public Entry(String tag, long millis, File data, int flags) throws IOException {
            if (tag == null) throw new NullPointerException("tag == null");
            if ((flags & IS_EMPTY) != 0) throw new IllegalArgumentException("Bad flags: " + flags);

            mTag = tag;
            mTimeMillis = millis;
            mData = null;
            mFileDescriptor = ParcelFileDescriptor.open(data, ParcelFileDescriptor.MODE_READ_ONLY);
            mFlags = flags;
        }

        /** Close the input stream associated with this entry. */
        public void close() {
            try { if (mFileDescriptor != null) mFileDescriptor.close(); } catch (IOException e) { }
        }

        /** @return the tag originally attached to the entry. */
        public String getTag() { return mTag; }

        /** @return time when the entry was originally created. */
        public long getTimeMillis() { return mTimeMillis; }

        /** @return flags describing the content returned by {@link #getInputStream()}. */
        public int getFlags() { return mFlags & ~IS_GZIPPED; }  // getInputStream() decompresses.

        /**
         * @param maxBytes of string to return (will truncate at this length).
         * @return the uncompressed text contents of the entry, null if the entry is not text.
         */
        public String getText(int maxBytes) {
            if ((mFlags & IS_TEXT) == 0) return null;
            if (mData != null) return new String(mData, 0, Math.min(maxBytes, mData.length));

            InputStream is = null;
            try {
                is = getInputStream();
                if (is == null) return null;
                byte[] buf = new byte[maxBytes];
                int readBytes = 0;
                int n = 0;
                while (n >= 0 && (readBytes += n) < maxBytes) {
                    n = is.read(buf, readBytes, maxBytes - readBytes);
                }
                return new String(buf, 0, readBytes);
            } catch (IOException e) {
                return null;
            } finally {
                try { if (is != null) is.close(); } catch (IOException e) {}
            }
        }

        /** @return the uncompressed contents of the entry, or null if the contents were lost */
        public InputStream getInputStream() throws IOException {
            InputStream is;
            if (mData != null) {
                is = new ByteArrayInputStream(mData);
            } else if (mFileDescriptor != null) {
                is = new ParcelFileDescriptor.AutoCloseInputStream(mFileDescriptor);
            } else {
                return null;
            }
            return (mFlags & IS_GZIPPED) != 0 ? new GZIPInputStream(is) : is;
        }

        public static final Parcelable.Creator<Entry> CREATOR = new Parcelable.Creator() {
            public Entry[] newArray(int size) { return new Entry[size]; }
            public Entry createFromParcel(Parcel in) {
                String tag = in.readString();
                long millis = in.readLong();
                int flags = in.readInt();
                if ((flags & HAS_BYTE_ARRAY) != 0) {
                    return new Entry(tag, millis, in.createByteArray(), flags & ~HAS_BYTE_ARRAY);
                } else {
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.CREATOR.createFromParcel(in);
                    return new Entry(tag, millis, pfd, flags);
                }
            }
        };

        public int describeContents() {
            return mFileDescriptor != null ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0;
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeString(mTag);
            out.writeLong(mTimeMillis);
            if (mFileDescriptor != null) {
                out.writeInt(mFlags & ~HAS_BYTE_ARRAY);  // Clear bit just to be safe
                mFileDescriptor.writeToParcel(out, flags);
            } else {
                out.writeInt(mFlags | HAS_BYTE_ARRAY);
                out.writeByteArray(mData);
            }
        }
    }

    /** {@hide} */
    public DropBoxManager(IDropBoxManagerService service) { mService = service; }

    /**
     * Create a dummy instance for testing.  All methods will fail unless
     * overridden with an appropriate mock implementation.  To obtain a
     * functional instance, use {@link android.content.Context#getSystemService}.
     */
    protected DropBoxManager() { mService = null; }

    /**
     * Stores human-readable text.  The data may be discarded eventually (or even
     * immediately) if space is limited, or ignored entirely if the tag has been
     * blocked (see {@link #isTagEnabled}).
     *
     * @param tag describing the type of entry being stored
     * @param data value to store
     */
    public void addText(String tag, String data) {
        try { mService.add(new Entry(tag, 0, data)); } catch (RemoteException e) {}
    }

    /**
     * Stores binary data, which may be ignored or discarded as with {@link #addText}.
     *
     * @param tag describing the type of entry being stored
     * @param data value to store
     * @param flags describing the data
     */
    public void addData(String tag, byte[] data, int flags) {
        if (data == null) throw new NullPointerException("data == null");
        try { mService.add(new Entry(tag, 0, data, flags)); } catch (RemoteException e) {}
    }

    /**
     * Stores the contents of a file, which may be ignored or discarded as with
     * {@link #addText}.
     *
     * @param tag describing the type of entry being stored
     * @param file to read from
     * @param flags describing the data
     * @throws IOException if the file can't be opened
     */
    public void addFile(String tag, File file, int flags) throws IOException {
        if (file == null) throw new NullPointerException("file == null");
        Entry entry = new Entry(tag, 0, file, flags);
        try {
            mService.add(entry);
        } catch (RemoteException e) {
            // ignore
        } finally {
            entry.close();
        }
    }

    /**
     * Checks any blacklists (set in system settings) to see whether a certain
     * tag is allowed.  Entries with disabled tags will be dropped immediately,
     * so you can save the work of actually constructing and sending the data.
     *
     * @param tag that would be used in {@link #addText} or {@link #addFile}
     * @return whether events with that tag would be accepted
     */
    public boolean isTagEnabled(String tag) {
        try { return mService.isTagEnabled(tag); } catch (RemoteException e) { return false; }
    }

    /**
     * Gets the next entry from the drop box <em>after</em> the specified time.
     * Requires <code>android.permission.READ_LOGS</code>.  You must always call
     * {@link Entry#close()} on the return value!
     *
     * @param tag of entry to look for, null for all tags
     * @param msec time of the last entry seen
     * @return the next entry, or null if there are no more entries
     */
    public Entry getNextEntry(String tag, long msec) {
        try { return mService.getNextEntry(tag, msec); } catch (RemoteException e) { return null; }
    }

    // TODO: It may be useful to have some sort of notification mechanism
    // when data is added to the dropbox, for demand-driven readers --
    // for now readers need to poll the dropbox to find new data.
}
