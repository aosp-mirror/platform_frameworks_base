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
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
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
    private static final String TAG = "MiniThumbFile";
    private static final int MINI_THUMB_DATA_FILE_VERSION = 4;
    public static final int BYTES_PER_MINTHUMB = 10000;

    private static final int BYTES_PER_MINTHUMB_INDEX = 8;
    private FileChannel mIndexChannel;
    private RandomAccessFile mMiniThumbIndexFile;
    private final boolean debug = false;;

    private static final int HEADER_SIZE = 1 + 8 + 4;
    private Uri mUri;
    private RandomAccessFile mMiniThumbFile;
    private FileChannel mChannel;
    private ByteBuffer mBuffer;
    private static final Hashtable<String, MiniThumbFile> sThumbFiles =
        new Hashtable<String, MiniThumbFile>();

    /**
     * We store different types of thumbnails in different files. To remain backward compatibility,
     * we should hashcode of content://media/external/images/media remains the same.
     */
    public static synchronized void reset() {
        for (MiniThumbFile file : sThumbFiles.values()) {
            file.deactivate();
        }
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

    private String randomAccessIndexFilePath(int version) {
        String directoryName =
                Environment.getExternalStorageDirectory().toString()
                + "/DCIM/.thumbnails";
        return directoryName + "/.thumbindex" + version + "-" + mUri.hashCode();
    }

    private void removeOldIndexFile() {
        String oldPath = randomAccessIndexFilePath(MINI_THUMB_DATA_FILE_VERSION - 1);
        File oldFile = new File(oldPath);
        if (oldFile.exists()) {
            try {
                oldFile.delete();
            } catch (SecurityException ex) {
                // ignore
            }
        }
    }

    private RandomAccessFile miniThumbIndexFile() {
        if (mMiniThumbIndexFile == null) {
            removeOldIndexFile();
            String path = randomAccessIndexFilePath(MINI_THUMB_DATA_FILE_VERSION);
            File directory = new File(path).getParentFile();
            if (!directory.isDirectory()) {
                if (!directory.mkdirs()) {
                    Log.e(TAG, "Unable to create .thumbnails directory "
                            + directory.toString());
                }
            }
            File f = new File(path);

            try {
                mMiniThumbIndexFile = new RandomAccessFile(f, "rw");
            } catch (IOException ex) {
                // Open as read-only so we can at least read the existing
                // thumbnails.
                try {
                    mMiniThumbIndexFile = new RandomAccessFile(f, "r");
                } catch (IOException ex2) {
                    // ignore exception
                    Log.e(TAG, "miniThumbIndexFile open r exception: " + f);
                }
            }
            if (mMiniThumbIndexFile != null) {
                mIndexChannel = mMiniThumbIndexFile.getChannel();
            }
        }
        return mMiniThumbIndexFile;
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
            if (mMiniThumbFile != null) {
                mChannel = mMiniThumbFile.getChannel();
            }
        }
        return mMiniThumbFile;
    }



    public MiniThumbFile(Uri uri) {
        mUri = uri;
        mBuffer = ByteBuffer.allocateDirect(BYTES_PER_MINTHUMB);
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

    /**
    * Get the index of thumbnail, which is the real saving location.
    * @param id the raw id in Mediaprovider database.
    * @param create when you want to create a new thumbnail, set to true; when generally query 
    * thumbnail saved index, set to false.
    */
    private long getIndex(long id, boolean create){
        RandomAccessFile r = miniThumbIndexFile();
        ByteBuffer buf = ByteBuffer.allocateDirect(BYTES_PER_MINTHUMB_INDEX);

        if (r != null) {
            long pos = 0;
            //first 8 bytes are for saving next create thumbnail block number!
            //so if create set, then begin from 0, others begin from real index 
            // (id+1)*BYTES_PER_MINTHUMB_INDEX.
            if (!create) {
                pos = (id + 1) * BYTES_PER_MINTHUMB_INDEX;
            }

            FileLock lock = null;
            try {
                buf.clear();
                buf.limit(BYTES_PER_MINTHUMB_INDEX);

                lock = mIndexChannel.lock(pos, BYTES_PER_MINTHUMB_INDEX, false);
                //check that we can read the following 8 bytes
                //which is the index position of thumbnail.

                int read = mIndexChannel.read(buf, pos);

                if (read == BYTES_PER_MINTHUMB_INDEX) {
                    buf.position(0);
                    if (create) {
                        //first, write next index.
                        long now = buf.getLong();
                        buf.clear();
                        buf.position(0);
                        buf.putLong(++now);
                        buf.flip();
                        int write = mIndexChannel.write(buf, pos);

                        //second, write this id's index
                        if(BYTES_PER_MINTHUMB_INDEX == write) {
                            if (lock != null) lock.release();
                            pos = (id + 1) * BYTES_PER_MINTHUMB_INDEX;
                            lock = mIndexChannel.lock(pos, BYTES_PER_MINTHUMB_INDEX, false);
                            buf.flip();
                            write = mIndexChannel.write(buf, pos);
                            if(debug) Log.d(TAG, "getIndex with create. index: " + now + 
                                               "corresponding id: " + id + ", index is: " + pos);
                        }
                        return now;
                    } else {
                        long p = buf.getLong();
                        if(debug) Log.d(TAG, "getIndex with no create. index: " + p);
                        return p;
                    }
                } else if(-1 == read) {
                    //If the index file is empty, initialize first index to 0.
                    if(0 == r.length()){
                        buf.clear();
                        buf.position(0);
                        buf.putLong(0);
                        buf.flip();
                        int write = mIndexChannel.write(buf, 0);
                        if(debug) Log.d(TAG, "initialize first index");
                        if(BYTES_PER_MINTHUMB_INDEX == write) return 0;
                    }
                }
            } catch (IOException ex) {
                Log.e(TAG, "Got exception checking file index: ", ex);
            } catch (RuntimeException ex) {
                // Other NIO related exception like disk full, read only channel..etc
                Log.e(TAG, "Got exception when reading index, id = " + id +
		                 ", disk full or mount read-only? " + ex.getClass());
            } finally {
                try {
                    if (lock != null) lock.release();
                }
                catch (IOException ex) {
                    // ignore it.
                    Log.e(TAG, "release lock: ", ex);
                }
            }
        }
        return 0;
    }

    // Get the magic number for the specified id in the mini-thumb file.
    // Returns 0 if the magic is not available.
    public synchronized long getMagic(long id) {
        // check the mini thumb file for the right data.  Right is
        // defined as having the right magic number at the offset
        // reserved for this "id".
        RandomAccessFile r = miniThumbDataFile();

        if (r != null) {

            long pos = getIndex(id, false);
            if(pos < 0) return 0;

            pos *= BYTES_PER_MINTHUMB;

            FileLock lock = null;
            try {
                mBuffer.clear();
                mBuffer.limit(1 + 8);

                lock = mChannel.lock(pos, 1 + 8, true);
                // check that we can read the following 9 bytes
                // (1 for the "status" and 8 for the long)
                if (mChannel.read(mBuffer, pos) == 9) {
                    mBuffer.position(0);
                    if (mBuffer.get() == 1) {
                        return mBuffer.getLong();
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

    public synchronized void saveMiniThumbToFile(byte[] data, long id, long magic)
            throws IOException {
        RandomAccessFile r = miniThumbDataFile();
        if (r == null) return;


        long pos = getIndex(id, true);
        if(pos < 0) return;

        pos *= BYTES_PER_MINTHUMB;
        FileLock lock = null;
        try {
            if (data != null) {
                if (data.length > BYTES_PER_MINTHUMB - HEADER_SIZE) {
                    // not enough space to store it.
                    return;
                }
                mBuffer.clear();
                mBuffer.put((byte) 1);
                mBuffer.putLong(magic);
                mBuffer.putInt(data.length);
                mBuffer.put(data);
                mBuffer.flip();

                lock = mChannel.lock(pos, BYTES_PER_MINTHUMB, false);
                mChannel.write(mBuffer, pos);
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
    public synchronized byte [] getMiniThumbFromFile(long id, byte [] data) {
        RandomAccessFile r = miniThumbDataFile();
        if (r == null) return null;

        long pos = id * BYTES_PER_MINTHUMB;
        FileLock lock = null;
        try {
            mBuffer.clear();
            lock = mChannel.lock(pos, BYTES_PER_MINTHUMB, true);
            int size = mChannel.read(mBuffer, pos);
            if (size > 1 + 8 + 4) { // flag, magic, length
                mBuffer.position(0);
                byte flag = mBuffer.get();
                long magic = mBuffer.getLong();
                int length = mBuffer.getInt();

                if (size >= 1 + 8 + 4 + length && data.length >= length) {
                    mBuffer.get(data, 0, length);
                    return data;
                }
            }
        } catch (IOException ex) {
            Log.w(TAG, "got exception when reading thumbnail id=" + id + ", exception: " + ex);
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
