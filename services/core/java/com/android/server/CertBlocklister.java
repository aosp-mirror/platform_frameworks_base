/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.FileUtils;
import android.provider.Settings;
import android.util.Slog;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * <p>CertBlocklister provides a simple mechanism for updating the platform denylists for SSL
 * certificate public keys and serial numbers.
 */
public class CertBlocklister extends Binder {

    private static final String TAG = "CertBlocklister";

    private static final String DENYLIST_ROOT = System.getenv("ANDROID_DATA") + "/misc/keychain/";

    /* For compatibility reasons, the name of these paths cannot be changed */
    public static final String PUBKEY_PATH = DENYLIST_ROOT + "pubkey_blacklist.txt";
    public static final String SERIAL_PATH = DENYLIST_ROOT + "serial_blacklist.txt";

    /* For compatibility reasons, the name of these keys cannot be changed */
    public static final String PUBKEY_BLOCKLIST_KEY = "pubkey_blacklist";
    public static final String SERIAL_BLOCKLIST_KEY = "serial_blacklist";

    private static class BlocklistObserver extends ContentObserver {

        private final String mKey;
        private final String mName;
        private final String mPath;
        private final File mTmpDir;
        private final ContentResolver mContentResolver;

        BlocklistObserver(String key, String name, String path, ContentResolver cr) {
            super(null);
            mKey = key;
            mName = name;
            mPath = path;
            mTmpDir = new File(mPath).getParentFile();
            mContentResolver = cr;
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            writeDenylist();
        }

        public String getValue() {
            return Settings.Secure.getStringForUser(
                mContentResolver, mKey, mContentResolver.getUserId());
        }

        private void writeDenylist() {
            new Thread("BlocklistUpdater") {
                public void run() {
                    synchronized (mTmpDir) {
                        String blocklist = getValue();
                        if (blocklist != null) {
                            Slog.i(TAG, "Certificate blocklist changed, updating...");
                            FileOutputStream out = null;
                            try {
                                // create a temporary file
                                File tmp = File.createTempFile("journal", "", mTmpDir);
                                // mark it -rw-r--r--
                                tmp.setReadable(true, false);
                                // write to it
                                out = new FileOutputStream(tmp);
                                out.write(blocklist.getBytes());
                                // sync to disk
                                FileUtils.sync(out);
                                // atomic rename
                                tmp.renameTo(new File(mPath));
                                Slog.i(TAG, "Certificate blocklist updated");
                            } catch (IOException e) {
                                Slog.e(TAG, "Failed to write blocklist", e);
                            } finally {
                                IoUtils.closeQuietly(out);
                            }
                        }
                    }
                }
            }.start();
        }
    }

    public CertBlocklister(Context context) {
        registerObservers(context.getContentResolver());
    }

    private BlocklistObserver buildPubkeyObserver(ContentResolver cr) {
        return new BlocklistObserver(PUBKEY_BLOCKLIST_KEY,
                    "pubkey",
                    PUBKEY_PATH,
                    cr);
    }

    private BlocklistObserver buildSerialObserver(ContentResolver cr) {
        return new BlocklistObserver(SERIAL_BLOCKLIST_KEY,
                    "serial",
                    SERIAL_PATH,
                    cr);
    }

    private void registerObservers(ContentResolver cr) {
        // set up the public key denylist observer
        cr.registerContentObserver(
                Settings.Secure.getUriFor(PUBKEY_BLOCKLIST_KEY),
                true,
                buildPubkeyObserver(cr)
        );

        // set up the serial number denylist observer
        cr.registerContentObserver(
                Settings.Secure.getUriFor(SERIAL_BLOCKLIST_KEY),
                true,
                buildSerialObserver(cr)
        );
    }
}
