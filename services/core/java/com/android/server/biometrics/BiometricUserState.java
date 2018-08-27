/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.biometrics;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for managing biometrics per user across device reboots.
 * @hide
 */
public abstract class BiometricUserState<T extends BiometricAuthenticator.Identifier> {
    private static final String TAG = "UserState";

    @GuardedBy("this")
    protected final ArrayList<T> mBiometrics = new ArrayList<>();
    protected final Context mContext;
    protected final File mFile;

    private final Runnable mWriteStateRunnable = new Runnable() {
        @Override
        public void run() {
            doWriteState();
        }
    };

    /**
     * @return The tag for the biometrics. There may be multiple instances of a biometric within.
     */
    protected abstract String getBiometricsTag();

    /**
     * @return The file where the biometric metadata should be stored.
     */
    protected abstract String getBiometricFile();

    /**
     * @return The resource for the name template, this is used to generate the default name.
     */
    protected abstract int getNameTemplateResource();

    /**
     * @return A copy of the list.
     */
    protected abstract ArrayList<T> getCopy(ArrayList<T> array);

    /**
     * @return Writes the cached data to persistent storage.
     */
    protected abstract void doWriteState();

    /**
     * @return
     */
    protected abstract void parseBiometricsLocked(XmlPullParser parser)
            throws IOException, XmlPullParserException;


    public BiometricUserState(Context context, int userId) {
        mFile = getFileForUser(userId);
        mContext = context;
        synchronized (this) {
            readStateSyncLocked();
        }
    }

    public void addBiometric(T identifier) {
        synchronized (this) {
            mBiometrics.add(identifier);
            scheduleWriteStateLocked();
        }
    }

    public void removeBiometric(int biometricId) {
        synchronized (this) {
            for (int i = 0; i < mBiometrics.size(); i++) {
                if (mBiometrics.get(i).getBiometricId() == biometricId) {
                    mBiometrics.remove(i);
                    scheduleWriteStateLocked();
                    break;
                }
            }
        }
    }

    public void renameBiometric(int biometricId, CharSequence name) {
        synchronized (this) {
            for (int i = 0; i < mBiometrics.size(); i++) {
                if (mBiometrics.get(i).getBiometricId() == biometricId) {
                    BiometricAuthenticator.Identifier identifier = mBiometrics.get(i);
                    identifier.setName(name);
                    scheduleWriteStateLocked();
                    break;
                }
            }
        }
    }

    public List<T> getBiometrics() {
        synchronized (this) {
            return getCopy(mBiometrics);
        }
    }

    /**
     * Finds a unique name for the given fingerprint
     * @return unique name
     */
    public String getUniqueName() {
        int guess = 1;
        while (true) {
            // Not the most efficient algorithm in the world, but there shouldn't be more than 10
            String name = mContext.getString(getNameTemplateResource(), guess);
            if (isUnique(name)) {
                return name;
            }
            guess++;
        }
    }

    private boolean isUnique(String name) {
        for (T identifier : mBiometrics) {
            if (identifier.getName().equals(name)) {
                return false;
            }
        }
        return true;
    }

    private File getFileForUser(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), getBiometricFile());
    }

    private void scheduleWriteStateLocked() {
        AsyncTask.execute(mWriteStateRunnable);
    }

    @GuardedBy("this")
    private void readStateSyncLocked() {
        FileInputStream in;
        if (!mFile.exists()) {
            return;
        }
        try {
            in = new FileInputStream(mFile);
        } catch (FileNotFoundException fnfe) {
            Slog.i(TAG, "No fingerprint state");
            return;
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            parseStateLocked(parser);

        } catch (XmlPullParserException | IOException e) {
            throw new IllegalStateException("Failed parsing settings file: "
                    + mFile , e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    @GuardedBy("this")
    private void parseStateLocked(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(getBiometricsTag())) {
                parseBiometricsLocked(parser);
            }
        }
    }

}
