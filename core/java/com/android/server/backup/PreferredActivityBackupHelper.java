/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.backup;

import android.app.AppGlobals;
import android.app.backup.BackupDataInputStream;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupHelper;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.org.bouncycastle.util.Arrays;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class PreferredActivityBackupHelper implements BackupHelper {
    private static final String TAG = "PreferredBackup";
    private static final boolean DEBUG = true;

    // current schema of the backup state blob
    private static final int STATE_VERSION = 1;

    // key under which the preferred-activity state blob is committed to backup
    private static final String KEY_PREFERRED = "preferred-activity";

    final Context mContext;

    public PreferredActivityBackupHelper(Context context) {
        mContext = context;
    }

    // The fds passed here are shared among all helpers, so we mustn't close them
    private void writeState(ParcelFileDescriptor stateFile, byte[] payload) {
        try {
            FileOutputStream fos = new FileOutputStream(stateFile.getFileDescriptor());

            // We explicitly don't close 'out' because we must not close the backing fd.
            // The FileOutputStream will not close it implicitly.
            @SuppressWarnings("resource")
            DataOutputStream out = new DataOutputStream(fos);

            out.writeInt(STATE_VERSION);
            if (payload == null) {
                out.writeInt(0);
            } else {
                out.writeInt(payload.length);
                out.write(payload);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Unable to write updated state", e);
        }
    }

    private byte[] readState(ParcelFileDescriptor oldStateFd) {
        FileInputStream fis = new FileInputStream(oldStateFd.getFileDescriptor());
        BufferedInputStream bis = new BufferedInputStream(fis);

        @SuppressWarnings("resource")
        DataInputStream in = new DataInputStream(bis);

        byte[] oldState = null;
        try {
            int version = in.readInt();
            if (version == STATE_VERSION) {
                int size = in.readInt();
                if (size > 0) {
                    if (size > 200*1024) {
                        Slog.w(TAG, "Suspiciously large state blog; ignoring.  N=" + size);
                    } else {
                        // size looks okay; make the return buffer and fill it
                        oldState = new byte[size];
                        in.read(oldState);
                    }
                }
            } else {
                Slog.w(TAG, "Prior state from unrecognized version " + version);
            }
        } catch (EOFException e) {
            // Empty file is expected on first backup,  so carry on. If the state
            // is truncated we just treat it the same way.
            oldState = null;
        } catch (Exception e) {
            Slog.w(TAG, "Error examing prior backup state " + e.getMessage());
            oldState = null;
        }

        return oldState;
    }

    @Override
    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        byte[] payload = null;
        try {
            byte[] oldPayload = readState(oldState);

            IPackageManager pm = AppGlobals.getPackageManager();
            byte[] newPayload = pm.getPreferredActivityBackup(UserHandle.USER_OWNER);
            if (!Arrays.areEqual(oldPayload, newPayload)) {
                if (DEBUG) {
                    Slog.i(TAG, "State has changed => writing new preferred app payload");
                }
                data.writeEntityHeader(KEY_PREFERRED, newPayload.length);
                data.writeEntityData(newPayload, newPayload.length);
            } else {
                if (DEBUG) {
                    Slog.i(TAG, "No change to state => not writing to wire");
                }
            }

            // Always need to re-record the state, even if nothing changed
            payload = newPayload;
        } catch (Exception e) {
            // On failures we'll wind up committing a zero-size state payload.  This is
            // a forward-safe situation because we know we commit the entire new payload
            // on prior-state mismatch.
            Slog.w(TAG, "Unable to record preferred activities", e);
        } finally {
            writeState(newState, payload);
        }
    }

    @Override
    public void restoreEntity(BackupDataInputStream data) {
        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            byte[] payload = new byte[data.size()];
            data.read(payload);
            if (DEBUG) {
                Slog.i(TAG, "Restoring preferred activities; size=" + payload.length);
            }
            pm.restorePreferredActivities(payload, UserHandle.USER_OWNER);
        } catch (Exception e) {
            Slog.e(TAG, "Exception reading restore data", e);
        }
    }

    @Override
    public void writeNewStateDescription(ParcelFileDescriptor newState) {
        writeState(newState, null);
    }

}
