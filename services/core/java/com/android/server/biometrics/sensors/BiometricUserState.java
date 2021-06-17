/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for managing biometrics per user across device reboots.
 * @hide
 */
public abstract class BiometricUserState<T extends BiometricAuthenticator.Identifier> {
    private static final String TAG = "UserState";

    private static final String TAG_INVALIDATION = "authenticatorIdInvalidation_tag";
    private static final String ATTR_INVALIDATION = "authenticatorIdInvalidation_attr";

    @GuardedBy("this")
    protected final ArrayList<T> mBiometrics = new ArrayList<>();
    protected boolean mInvalidationInProgress;
    protected final Context mContext;
    protected final File mFile;

    private final Runnable mWriteStateRunnable = this::doWriteStateInternal;

    /**
     * @return The tag for the biometrics. There may be multiple instances of a biometric within.
     */
    protected abstract String getBiometricsTag();

    /**
     * @return The resource for the name template, this is used to generate the default name.
     */
    protected abstract int getNameTemplateResource();

    /**
     * @return A copy of the list.
     */
    protected abstract ArrayList<T> getCopy(ArrayList<T> array);

    protected abstract void doWriteState(@NonNull TypedXmlSerializer serializer) throws Exception;

    /**
     * @Writes the cached data to persistent storage.
     */
    private void doWriteStateInternal() {
        AtomicFile destination = new AtomicFile(mFile);

        FileOutputStream out = null;

        try {
            out = destination.startWrite();
            TypedXmlSerializer serializer = Xml.resolveSerializer(out);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);

            // Store the authenticatorId
            serializer.startTag(null, TAG_INVALIDATION);
            serializer.attributeBoolean(null, ATTR_INVALIDATION, mInvalidationInProgress);
            serializer.endTag(null, TAG_INVALIDATION);

            // Do any additional serialization that subclasses may require
            doWriteState(serializer);

            serializer.endDocument();
            destination.finishWrite(out);
        } catch (Throwable t) {
            Slog.wtf(TAG, "Failed to write settings, restoring backup", t);
            destination.failWrite(out);
            throw new IllegalStateException("Failed to write to file: " + mFile.toString(), t);
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    /**
     * @return
     */
    protected abstract void parseBiometricsLocked(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException;


    public BiometricUserState(Context context, int userId, @NonNull String fileName) {
        mFile = getFileForUser(userId, fileName);
        mContext = context;
        synchronized (this) {
            readStateSyncLocked();
        }
    }

    public void setInvalidationInProgress(boolean invalidationInProgress) {
        synchronized (this) {
            mInvalidationInProgress = invalidationInProgress;
            scheduleWriteStateLocked();
        }
    }

    public boolean isInvalidationInProgress() {
        synchronized (this) {
            return mInvalidationInProgress;
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

    private File getFileForUser(int userId, @NonNull String fileName) {
        return new File(Environment.getUserSystemDirectory(userId), fileName);
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
            TypedXmlPullParser parser = Xml.resolvePullParser(in);
            parseStateLocked(parser);

        } catch (XmlPullParserException | IOException e) {
            throw new IllegalStateException("Failed parsing settings file: "
                    + mFile , e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    @GuardedBy("this")
    private void parseStateLocked(TypedXmlPullParser parser)
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
            } else if (tagName.equals(TAG_INVALIDATION)) {
                mInvalidationInProgress = parser.getAttributeBoolean(null, ATTR_INVALIDATION);
            }
        }
    }

}
