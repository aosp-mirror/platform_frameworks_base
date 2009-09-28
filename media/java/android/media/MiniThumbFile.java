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

package android.media;

import android.graphics.Bitmap;
import android.media.ThumbnailUtil;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Hashtable;

/**
 * This class handles the mini-thumb file. A mini-thumb file consists
 * of blocks, indexed by id. Each block has BYTES_PER_MINTHUMB bytes in the
 * following format:
 *
 * 1 byte status (0 = empty, 1 = mini-thumb available)
 * 8 bytes magic (a magic number to match what's in the database)
 * 4 bytes data length (LEN)
 * LEN bytes jpeg data
 * (the remaining bytes are unused)
 *
 * @hide This file is shared between MediaStore and MediaProvider and should remained internal use
 *       only.
 */
public class MiniThumbFile {
    public static final int THUMBNAIL_TARGET_SIZE = 320;
    public static final int MINI_THUMB_TARGET_SIZE = 96;
    public static final int THUMBNAIL_MAX_NUM_PIXELS = 512 * 384;
    public static final int MINI_THUMB_MAX_NUM_PIXELS = 128 * 128;
    public static final int UNCONSTRAINED = -1;

    private static final String TAG = "MiniThumbFile";
    private static final int MINI_THUMB_DATA_FILE_VERSION = 3;
    public static final int BYTES_PER_MINTHUMB = 10000;
    private static final int HEADER_SIZE = 1 + 8 + 4;
    private Uri mUri;
    private RandomAccessFile mMiniThumbFile;
    private FileChannel mChannel;
    private static Hashtable<String, MiniThumbFile> sThumbFiles =
        new Hashtable<String, MiniThumbFile>();

    /**
     * We store different types of thumbnails in different files. To remain backward compatibility,
     * we should hashcode of content://media/external/images/media remains the same.
     */
    public static synchronized void reset() {
        sThumbFiles.clear();
    }

    public static synchronized MiniThumbFile instance(Uri uri) {
        String type = uri.getPathSegments().get(1);
        MiniThumbFile file = sThumbFiles.get(type);
        // Log.v(TAG, "get minithumbfile for type: "+type);
        if (file == null) {
            file = new MiniThumbFile(
                    Uri.parse("content://media/external/" + type + "/media"));
            sThumbFiles.put(type, file);
        }

        return file;
    }

    private String randomAccessFilePath(int version) {
        String directoryName =
                Environment.getExternalStorageDirectory().toString()
                + "/DCIM/.thumbnails";
        return directoryName + "/.thumbdata" + version + "-" + mUri.hashCode();
    }

    private void removeOldFile() {
        String oldPath = randomAccessFilePath(MINI_THUMB_DATA_FILE_VERSION - 1);
        File oldFile = new File(oldPath);
        if (oldFile.exists()) {
            try {
                oldFile.delete();
            } catch (SecurityException ex) {
                // ignore
            }
        }
    }

    private RandomAccessFile miniThumbDataFile() {
        if (mMiniThumbFile == null) {
            removeOldFile();
            String path = randomAccessFilePath(MINI_THUMB_DATA_FILE_VERSION);
            File directory = new File(path).getParentFile();
            if (!directory.isDirectory()) {
                if (!directory.mkdirs()) {
                    Log.e(TAG, "Unable to create .thumbnails directory "
                            + directory.toString());
                }
            }
            File f = new File(path);
            try {
                mMiniThumbFile = new RandomAccessFile(f, "rw");
            } catch (IOException ex) {
                // Open as read-only so we can at least read the existing
                // thumbnails.
                try {
                    mMiniThumbFile = new RandomAccessFile(f, "r");
                } catch (IOException ex2) {
                    // ignore exception
                }
            }
            mChannel = mMiniThumbFile.getChannel();
        }
        return mMiniThumbFile;
    }

    public MiniThumbFile(Uri uri) {
        mUri = uri;
    }

    public synchronized void deactivate() {
        if (mMiniThumbFile != null) {
            try {
                mMiniThumbFile.close();
                mMiniThumbFile = null;
            } catch (IOException ex) {
                // ignore exception
            }
        }
    }

    // Get the magic number for the specified id in the mini-thumb file.
    // Returns 0 if the magic is not available.
    public long getMagic(long id) {
        // check the mini thumb file for the right data.  Right is
        // defined as having the right magic number at the offset
        // reserved for this "id".
        RandomAccessFile r = miniThumbDataFile();
        if (r != null) {
            long pos = id * BYTES_PER_MINTHUMB;
            FileLock lock = null;
            try {
                lock = mChannel.lock();
                // check that we can read the following 9 bytes
                // (1 for the "status" and 8 for the long)
                if (r.length() >= pos + 1 + 8) {
                    r.seek(pos);
                    if (r.readByte() == 1) {
                        long fileMagic = r.readLong();
                        return fileMagic;
                    }
                }
            } catch (IOException ex) {
                Log.v(TAG, "Got exception checking file magic: ", ex);
            } catch (RuntimeException ex) {
                // Other NIO related exception like disk full, read only channel..etc
                Log.e(TAG, "Got exception when reading magic, id = " + id +
                        ", disk full or mount read-only? " + ex.getClass());
            } finally {
                try {
                    if (lock != null) lock.release();
                }
                catch (IOException ex) {
                    // ignore it.
                }
            }
        }
        return 0;
    }

    public void saveMiniThumbToFile(Bitmap bitmap, long id, long magic)
            throws IOException {
        byte[] data = ThumbnailUtil.miniThumbData(bitmap);
        saveMiniThumbToFile(data, id, magic);
    }

    public void saveMiniThumbToFile(byte[] data, long id, long magic)
            throws IOException {
        RandomAccessFile r = miniThumbDataFile();
        if (r == null) return;

        long pos = id * BYTES_PER_MINTHUMB;
        FileLock lock = null;
        try {
            lock = mChannel.lock();
            if (data != null) {
                if (data.length > BYTES_PER_MINTHUMB - HEADER_SIZE) {
                    // not enough space to store it.
                    return;
                }
                r.seek(pos);
                r.writeByte(0);     // we have no data in this slot

                // if magic is 0 then leave it alone
                if (magic == 0) {
                    r.skipBytes(8);
                } else {
                    r.writeLong(magic);
                }
                r.writeInt(data.length);
                r.write(data);
                r.seek(pos);
                r.writeByte(1);  // we have data in this slot
            }
        } catch (IOException ex) {
            Log.e(TAG, "couldn't save mini thumbnail data for "
                    + id + "; ", ex);
            throw ex;
        } catch (RuntimeException ex) {
            // Other NIO related exception like disk full, read only channel..etc
            Log.e(TAG, "couldn't save mini thumbnail data for "
                    + id + "; disk full or mount read-only? " + ex.getClass());
        } finally {
            try {
                if (lock != null) lock.release();
            }
            catch (IOException ex) {
                // ignore it.
            }
        }
    }

    /**
     * Gallery app can use this method to retrieve mini-thumbnail. Full size
     * images share the same IDs with their corresponding thumbnails.
     *
     * @param id the ID of the image (same of full size image).
     * @param data the buffer to store mini-thumbnail.
     */
    public byte [] getMiniThumbFromFile(long id, byte [] data) {
        RandomAccessFile r = miniThumbDataFile();
        if (r == null) return null;

        long pos = id * BYTES_PER_MINTHUMB;
        FileLock lock = null;
        try {
            lock = mChannel.lock();
            r.seek(pos);
            if (r.readByte() == 1) {
                long magic = r.readLong();
                int length = r.readInt();
                int got = r.read(data, 0, length);
                if (got != length) return null;
                return data;
            } else {
                return null;
            }
        } catch (IOException ex) {
            Log.w(TAG, "got exception when reading thumbnail: " + ex);
            return null;
        } catch (RuntimeException ex) {
            // Other NIO related exception like disk full, read only channel..etc
            Log.e(TAG, "Got exception when reading thumbnail, id = " + id +
                    ", disk full or mount read-only? " + ex.getClass());
        } finally {
            try {
                if (lock != null) lock.release();
            }
            catch (IOException ex) {
                // ignore it.
            }
        }
        return null;
    }
}
