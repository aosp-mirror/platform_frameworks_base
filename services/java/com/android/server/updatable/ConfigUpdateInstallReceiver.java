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

package com.android.server.updates;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.os.FileUtils;
import android.util.Base64;
import android.util.EventLog;
import android.util.Slog;

import com.android.server.EventLogTags;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;

import libcore.io.IoUtils;

public class ConfigUpdateInstallReceiver extends BroadcastReceiver {

    private static final String TAG = "ConfigUpdateInstallReceiver";

    private static final String EXTRA_CONTENT_PATH = "CONTENT_PATH";
    private static final String EXTRA_REQUIRED_HASH = "REQUIRED_HASH";
    private static final String EXTRA_SIGNATURE = "SIGNATURE";
    private static final String EXTRA_VERSION_NUMBER = "VERSION";

    private static final String UPDATE_CERTIFICATE_KEY = "config_update_certificate";

    private final File updateDir;
    private final File updateContent;
    private final File updateVersion;

    public ConfigUpdateInstallReceiver(String updateDir, String updateContentPath,
                                       String updateMetadataPath, String updateVersionPath) {
        this.updateDir = new File(updateDir);
        this.updateContent = new File(updateDir, updateContentPath);
        File updateMetadataDir = new File(updateDir, updateMetadataPath);
        this.updateVersion = new File(updateMetadataDir, updateVersionPath);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        new Thread() {
            @Override
            public void run() {
                try {
                    // get the certificate from Settings.Secure
                    X509Certificate cert = getCert(context.getContentResolver());
                    // get the content path from the extras
                    String altContent = getAltContent(intent);
                    // get the version from the extras
                    int altVersion = getVersionFromIntent(intent);
                    // get the previous value from the extras
                    String altRequiredHash = getRequiredHashFromIntent(intent);
                    // get the signature from the extras
                    String altSig = getSignatureFromIntent(intent);
                    // get the version currently being used
                    int currentVersion = getCurrentVersion();
                    // get the hash of the currently used value
                    String currentHash = getCurrentHash(getCurrentContent());
                    if (!verifyVersion(currentVersion, altVersion)) {
                        Slog.i(TAG, "Not installing, new version is <= current version");
                    } else if (!verifyPreviousHash(currentHash, altRequiredHash)) {
                        EventLog.writeEvent(EventLogTags.CONFIG_INSTALL_FAILED,
                                            "Current hash did not match required value");
                    } else if (!verifySignature(altContent, altVersion, altRequiredHash, altSig,
                               cert)) {
                        EventLog.writeEvent(EventLogTags.CONFIG_INSTALL_FAILED,
                                            "Signature did not verify");
                    } else {
                        // install the new content
                        Slog.i(TAG, "Found new update, installing...");
                        install(altContent, altVersion);
                        Slog.i(TAG, "Installation successful");
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Could not update content!", e);
                    // keep the error message <= 100 chars
                    String errMsg = e.toString();
                    if (errMsg.length() > 100) {
                        errMsg = errMsg.substring(0, 99);
                    }
                    EventLog.writeEvent(EventLogTags.CONFIG_INSTALL_FAILED, errMsg);
                }
            }
        }.start();
    }

    private X509Certificate getCert(ContentResolver cr) {
        // get the cert from settings
        String cert = Settings.Secure.getString(cr, UPDATE_CERTIFICATE_KEY);
        // convert it into a real certificate
        try {
            byte[] derCert = Base64.decode(cert.getBytes(), Base64.DEFAULT);
            InputStream istream = new ByteArrayInputStream(derCert);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(istream);
        } catch (CertificateException e) {
            throw new IllegalStateException("Got malformed certificate from settings, ignoring");
        }
    }

    private String getContentFromIntent(Intent i) {
        String extraValue = i.getStringExtra(EXTRA_CONTENT_PATH);
        if (extraValue == null) {
            throw new IllegalStateException("Missing required content path, ignoring.");
        }
        return extraValue;
    }

    private int getVersionFromIntent(Intent i) throws NumberFormatException {
        String extraValue = i.getStringExtra(EXTRA_VERSION_NUMBER);
        if (extraValue == null) {
            throw new IllegalStateException("Missing required version number, ignoring.");
        }
        return Integer.parseInt(extraValue.trim());
    }

    private String getRequiredHashFromIntent(Intent i) {
        String extraValue = i.getStringExtra(EXTRA_REQUIRED_HASH);
        if (extraValue == null) {
            throw new IllegalStateException("Missing required previous hash, ignoring.");
        }
        return extraValue.trim();
    }

    private String getSignatureFromIntent(Intent i) {
        String extraValue = i.getStringExtra(EXTRA_SIGNATURE);
        if (extraValue == null) {
            throw new IllegalStateException("Missing required signature, ignoring.");
        }
        return extraValue.trim();
    }

    private int getCurrentVersion() throws NumberFormatException {
        try {
            String strVersion = IoUtils.readFileAsString(updateVersion.getCanonicalPath()).trim();
            return Integer.parseInt(strVersion);
        } catch (IOException e) {
            Slog.i(TAG, "Couldn't find current metadata, assuming first update");
            return 0;
        }
    }

    private String getAltContent(Intent i) throws IOException {
        String contents = IoUtils.readFileAsString(getContentFromIntent(i));
        return contents.trim();
    }

    private String getCurrentContent() {
        try {
            return IoUtils.readFileAsString(updateContent.getCanonicalPath()).trim();
        } catch (IOException e) {
            Slog.i(TAG, "Failed to read current content, assuming first update!");
            return null;
        }
    }

    private static String getCurrentHash(String content) {
        if (content == null) {
            return "0";
        }
        try {
            MessageDigest dgst = MessageDigest.getInstance("SHA512");
            byte[] encoded = content.getBytes();
            byte[] fingerprint = dgst.digest(encoded);
            return IntegralToString.bytesToHexString(fingerprint, false);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private boolean verifyVersion(int current, int alternative) {
        return (current < alternative);
    }

    private boolean verifyPreviousHash(String current, String required) {
        // this is an optional value- if the required field is NONE then we ignore it
        if (required.equals("NONE")) {
            return true;
        }
        // otherwise, verify that we match correctly
        return current.equals(required);
    }

    private boolean verifySignature(String content, int version, String requiredPrevious,
                                   String signature, X509Certificate cert) throws Exception {
        Signature signer = Signature.getInstance("SHA512withRSA");
        signer.initVerify(cert);
        signer.update(content.getBytes());
        signer.update(Long.toString(version).getBytes());
        signer.update(requiredPrevious.getBytes());
        return signer.verify(Base64.decode(signature.getBytes(), Base64.DEFAULT));
    }

    private void writeUpdate(File dir, File file, String content) throws IOException {
        FileOutputStream out = null;
        File tmp = null;
        try {
            // create the temporary file
            tmp = File.createTempFile("journal", "", dir);
            // create the parents for the destination file
            File parent = file.getParentFile();
            parent.mkdirs();
            // check that they were created correctly
            if (!parent.exists()) {
                throw new IOException("Failed to create directory " + parent.getCanonicalPath());
            }
            // mark tmp -rw-r--r--
            tmp.setReadable(true, false);
            // write to it
            out = new FileOutputStream(tmp);
            out.write(content.getBytes());
            // sync to disk
            out.getFD().sync();
            // atomic rename
            if (!tmp.renameTo(file)) {
                throw new IOException("Failed to atomically rename " + file.getCanonicalPath());
            }
        } finally {
            if (tmp != null) {
                tmp.delete();
            }
            IoUtils.closeQuietly(out);
        }
    }

    private void install(String content, int version) throws IOException {
        writeUpdate(updateDir, updateContent, content);
        writeUpdate(updateDir, updateVersion, Long.toString(version));
    }
}
