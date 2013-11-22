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

import android.content.Context;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.FileUtils;
import android.provider.Settings;
import android.util.Slog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import libcore.io.IoUtils;

/**
 * <p>CertBlacklister provides a simple mechanism for updating the platform blacklists for SSL
 * certificate public keys and serial numbers.
 */
public class CertBlacklister extends Binder {

    private static final String TAG = "CertBlacklister";

    private static final String BLACKLIST_ROOT = System.getenv("ANDROID_DATA") + "/misc/keychain/";

    public static final String PUBKEY_PATH = BLACKLIST_ROOT + "pubkey_blacklist.txt";
    public static final String SERIAL_PATH = BLACKLIST_ROOT + "serial_blacklist.txt";

    public static final String PUBKEY_BLACKLIST_KEY = "pubkey_blacklist";
    public static final String SERIAL_BLACKLIST_KEY = "serial_blacklist";

    private static class BlacklistObserver extends ContentObserver {

        private final String mKey;
        private final String mName;
        private final String mPath;
        private final File mTmpDir;
        private final ContentResolver mContentResolver;

        public BlacklistObserver(String key, String name, String path, ContentResolver cr) {
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
            writeBlacklist();
        }

        public String getValue() {
            return Settings.Secure.getString(mContentResolver, mKey);
        }

        private void writeBlacklist() {
            new Thread("BlacklistUpdater") {
                public void run() {
                    synchronized(mTmpDir) {
                        String blacklist = getValue();
                        if (blacklist != null) {
                            Slog.i(TAG, "Certificate blacklist changed, updating...");
                            FileOutputStream out = null;
                            try {
                                // create a temporary file
                                File tmp = File.createTempFile("journal", "", mTmpDir);
                                // mark it -rw-r--r--
                                tmp.setReadable(true, false);
                                // write to it
                                out = new FileOutputStream(tmp);
                                out.write(blacklist.getBytes());
                                // sync to disk
                                FileUtils.sync(out);
                                // atomic rename
                                tmp.renameTo(new File(mPath));
                                Slog.i(TAG, "Certificate blacklist updated");
                            } catch (IOException e) {
                                Slog.e(TAG, "Failed to write blacklist", e);
                            } finally {
                                IoUtils.closeQuietly(out);
                            }
                        }
                    }
                }
            }.start();
        }
    }

    public CertBlacklister(Context context) {
        registerObservers(context.getContentResolver());
    }

    private BlacklistObserver buildPubkeyObserver(ContentResolver cr) {
        return new BlacklistObserver(PUBKEY_BLACKLIST_KEY,
                    "pubkey",
                    PUBKEY_PATH,
                    cr);
    }

    private BlacklistObserver buildSerialObserver(ContentResolver cr) {
        return new BlacklistObserver(SERIAL_BLACKLIST_KEY,
                    "serial",
                    SERIAL_PATH,
                    cr);
    }

    private void registerObservers(ContentResolver cr) {
        // set up the public key blacklist observer
        cr.registerContentObserver(
            Settings.Secure.getUriFor(PUBKEY_BLACKLIST_KEY),
            true,
            buildPubkeyObserver(cr)
        );

        // set up the serial number blacklist observer
        cr.registerContentObserver(
            Settings.Secure.getUriFor(SERIAL_BLACKLIST_KEY),
            true,
            buildSerialObserver(cr)
        );
    }
}
