/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.hugebackup;

import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * This is the backup/restore agent class for the BackupRestore sample
 * application.  This particular agent illustrates using the backup and
 * restore APIs directly, without taking advantage of any helper classes.
 */
public class HugeAgent extends BackupAgent {
    /**
     * We put a simple version number into the state files so that we can
     * tell properly how to read "old" versions if at some point we want
     * to change what data we back up and how we store the state blob.
     */
    static final int AGENT_VERSION = 1;

    /**
     * Pick an arbitrary string to use as the "key" under which the
     * data is backed up.  This key identifies different data records
     * within this one application's data set.  Since we only maintain
     * one piece of data we don't need to distinguish, so we just pick
     * some arbitrary tag to use.
     */
    static final String APP_DATA_KEY = "alldata";
    static final String HUGE_DATA_KEY = "colossus";

    /** The app's current data, read from the live disk file */
    boolean mAddMayo;
    boolean mAddTomato;
    int mFilling;

    /** The location of the application's persistent data file */
    File mDataFile;

    /** For convenience, we set up the File object for the app's data on creation */
    @Override
    public void onCreate() {
        mDataFile = new File(getFilesDir(), HugeBackupActivity.DATA_FILE_NAME);
    }

    /**
     * The set of data backed up by this application is very small: just
     * two booleans and an integer.  With such a simple dataset, it's
     * easiest to simply store a copy of the backed-up data as the state
     * blob describing the last dataset backed up.  The state file
     * contents can be anything; it is private to the agent class, and
     * is never stored off-device.
     *
     * <p>One thing that an application may wish to do is tag the state
     * blob contents with a version number.  This is so that if the
     * application is upgraded, the next time it attempts to do a backup,
     * it can detect that the last backup operation was performed by an
     * older version of the agent, and might therefore require different
     * handling.
     */
    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) throws IOException {
        // First, get the current data from the application's file.  This
        // may throw an IOException, but in that case something has gone
        // badly wrong with the app's data on disk, and we do not want
        // to back up garbage data.  If we just let the exception go, the
        // Backup Manager will handle it and simply skip the current
        // backup operation.
        synchronized (HugeBackupActivity.sDataLock) {
            RandomAccessFile file = new RandomAccessFile(mDataFile, "r");
            mFilling = file.readInt();
            mAddMayo = file.readBoolean();
            mAddTomato = file.readBoolean();
        }

        // If the new state file descriptor is null, this is the first time
        // a backup is being performed, so we know we have to write the
        // data.  If there <em>is</em> a previous state blob, we want to
        // double check whether the current data is actually different from
        // our last backup, so that we can avoid transmitting redundant
        // data to the storage backend.
        boolean doBackup = (oldState == null);
        if (!doBackup) {
            doBackup = compareStateFile(oldState);
        }

        // If we decided that we do in fact need to write our dataset, go
        // ahead and do that.  The way this agent backs up the data is to
        // flatten it into a single buffer, then write that to the backup
        // transport under the single key string.
        if (doBackup) {
            ByteArrayOutputStream bufStream = new ByteArrayOutputStream();

            // We use a DataOutputStream to write structured data into
            // the buffering stream
            DataOutputStream outWriter = new DataOutputStream(bufStream);
            outWriter.writeInt(mFilling);
            outWriter.writeBoolean(mAddMayo);
            outWriter.writeBoolean(mAddTomato);

            // Okay, we've flattened the data for transmission.  Pull it
            // out of the buffering stream object and send it off.
            byte[] buffer = bufStream.toByteArray();
            int len = buffer.length;
            data.writeEntityHeader(APP_DATA_KEY, len);
            data.writeEntityData(buffer, len);

            // ***** pathological behavior *****
            // Now, in order to incur deliberate too-much-data failures,
            // try to back up 20 MB of data besides what we already pushed.
            final int MEGABYTE = 1024*1024;
            final int NUM_MEGS = 20;
            buffer = new byte[MEGABYTE];
            data.writeEntityHeader(HUGE_DATA_KEY, NUM_MEGS * MEGABYTE);
            for (int i = 0; i < NUM_MEGS; i++) {
                data.writeEntityData(buffer, MEGABYTE);
            }
        }

        // Finally, in all cases, we need to write the new state blob
        writeStateFile(newState);
    }

    /**
     * Helper routine - read a previous state file and decide whether to
     * perform a backup based on its contents.
     *
     * @return <code>true</code> if the application's data has changed since
     *   the last backup operation; <code>false</code> otherwise.
     */
    boolean compareStateFile(ParcelFileDescriptor oldState) {
        FileInputStream instream = new FileInputStream(oldState.getFileDescriptor());
        DataInputStream in = new DataInputStream(instream);

        try {
            int stateVersion = in.readInt();
            if (stateVersion > AGENT_VERSION) {
                // Whoops; the last version of the app that backed up
                // data on this device was <em>newer</em> than the current
                // version -- the user has downgraded.  That's problematic.
                // In this implementation, we recover by simply rewriting
                // the backup.
                return true;
            }

            // The state data we store is just a mirror of the app's data;
            // read it from the state file then return 'true' if any of
            // it differs from the current data.
            int lastFilling = in.readInt();
            boolean lastMayo = in.readBoolean();
            boolean lastTomato = in.readBoolean();

            return (lastFilling != mFilling)
                    || (lastTomato != mAddTomato)
                    || (lastMayo != mAddMayo);
        } catch (IOException e) {
            // If something went wrong reading the state file, be safe
            // and back up the data again.
            return true;
        }
    }

    /**
     * Write out the new state file:  the version number, followed by the
     * three bits of data as we sent them off to the backup transport.
     */
    void writeStateFile(ParcelFileDescriptor stateFile) throws IOException {
        FileOutputStream outstream = new FileOutputStream(stateFile.getFileDescriptor());
        DataOutputStream out = new DataOutputStream(outstream);

        out.writeInt(AGENT_VERSION);
        out.writeInt(mFilling);
        out.writeBoolean(mAddMayo);
        out.writeBoolean(mAddTomato);
    }

    /**
     * This application does not do any "live" restores of its own data,
     * so the only time a restore will happen is when the application is
     * installed.  This means that the activity itself is not going to
     * be running while we change its data out from under it.  That, in
     * turn, means that there is no need to send out any sort of notification
     * of the new data:  we only need to read the data from the stream
     * provided here, build the application's new data file, and then
     * write our new backup state blob that will be consulted at the next
     * backup operation.
     *
     * <p>We don't bother checking the versionCode of the app who originated
     * the data because we have never revised the backup data format.  If
     * we had, the 'appVersionCode' parameter would tell us how we should
     * interpret the data we're about to read.
     */
    @Override
    public void onRestore(BackupDataInput data, int appVersionCode,
            ParcelFileDescriptor newState) throws IOException {
        // We should only see one entity in the data stream, but the safest
        // way to consume it is using a while() loop
        while (data.readNextHeader()) {
            String key = data.getKey();
            int dataSize = data.getDataSize();

            if (APP_DATA_KEY.equals(key)) {
                // It's our saved data, a flattened chunk of data all in
                // one buffer.  Use some handy structured I/O classes to
                // extract it.
                byte[] dataBuf = new byte[dataSize];
                data.readEntityData(dataBuf, 0, dataSize);
                ByteArrayInputStream baStream = new ByteArrayInputStream(dataBuf);
                DataInputStream in = new DataInputStream(baStream);

                mFilling = in.readInt();
                mAddMayo = in.readBoolean();
                mAddTomato = in.readBoolean();

                // Now we are ready to construct the app's data file based
                // on the data we are restoring from.
                synchronized (HugeBackupActivity.sDataLock) {
                    RandomAccessFile file = new RandomAccessFile(mDataFile, "rw");
                    file.setLength(0L);
                    file.writeInt(mFilling);
                    file.writeBoolean(mAddMayo);
                    file.writeBoolean(mAddTomato);
                }
            } else {
                // Curious!  This entity is data under a key we do not
                // understand how to process.  Just skip it.
                data.skipEntityData();
            }
        }

        // The last thing to do is write the state blob that describes the
        // app's data as restored from backup.
        writeStateFile(newState);
    }
}
