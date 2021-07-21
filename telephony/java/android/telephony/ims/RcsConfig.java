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

package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.provider.Telephony.SimInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.telephony.Rlog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * RCS config data and methods to process the config
 * @hide
 */
public final class RcsConfig {
    private static final String LOG_TAG = "RcsConfig";
    private static final boolean DBG = Build.IS_ENG;

    // Tag and attribute defined in RCC.07 A.2
    private static final String TAG_CHARACTERISTIC = "characteristic";
    private static final String TAG_PARM = "parm";
    private static final String ATTRIBUTE_TYPE = "type";
    private static final String ATTRIBUTE_NAME = "name";
    private static final String ATTRIBUTE_VALUE = "value";
    // Keyword for Rcs Volte single registration defined in RCC.07 A.1.6.2
    private static final String PARM_SINGLE_REGISTRATION = "rcsVolteSingleRegistration";

    /**
     * Characteristic of the RCS provisioning config
     */
    public static class Characteristic {
        private String mType;
        private final Map<String, String> mParms = new ArrayMap<>();
        private final Set<Characteristic> mSubs = new ArraySet<>();
        private final Characteristic mParent;

        private Characteristic(String type, Characteristic parent) {
            mType = type;
            mParent = parent;
        }

        private String getType() {
            return mType;
        }

        private Map<String, String> getParms() {
            return mParms;
        }

        private Set<Characteristic> getSubs() {
            return mSubs;
        }

        private Characteristic getParent() {
            return mParent;
        }

        private Characteristic getSubByType(String type) {
            if (TextUtils.equals(mType, type)) {
                return this;
            }
            Characteristic result = null;
            for (Characteristic sub : mSubs) {
                result = sub.getSubByType(type);
                if (result != null) {
                    break;
                }
            }
            return result;
        }

        private boolean hasSubByType(String type) {
            return getSubByType(type) != null;
        }

        private String getParmValue(String name) {
            String value = mParms.get(name);
            if (value == null) {
                for (Characteristic sub : mSubs) {
                    value = sub.getParmValue(name);
                    if (value != null) {
                        break;
                    }
                }
            }
            return value;
        }

        boolean hasParm(String name) {
            if (mParms.containsKey(name)) {
                return true;
            }

            for (Characteristic sub : mSubs) {
                if (sub.hasParm(name)) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("[" + mType + "]: ");
            if (DBG) {
                sb.append(mParms);
            }
            for (Characteristic sub : mSubs) {
                sb.append("\n");
                sb.append(sub.toString().replace("\n", "\n\t"));
            }
            return sb.toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Characteristic)) {
                return false;
            }

            Characteristic o = (Characteristic) obj;

            return TextUtils.equals(mType, o.mType) && mParms.equals(o.mParms)
                    && mSubs.equals(o.mSubs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mType, mParms, mSubs);
        }
    }

    private final Characteristic mRoot;
    private Characteristic mCurrent;
    private final byte[] mData;

    public RcsConfig(byte[] data) throws IllegalArgumentException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Empty data");
        }
        mRoot = new Characteristic(null, null);
        mCurrent = mRoot;
        mData = data;
        Characteristic current = mRoot;
        ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(inputStream, null);
            int eventType = xpp.getEventType();
            String tag = null;
            while (eventType != XmlPullParser.END_DOCUMENT && current != null) {
                if (eventType == XmlPullParser.START_TAG) {
                    tag = xpp.getName().trim().toLowerCase();
                    if (TAG_CHARACTERISTIC.equals(tag)) {
                        int count = xpp.getAttributeCount();
                        String type = null;
                        if (count > 0) {
                            for (int i = 0; i < count; i++) {
                                String name = xpp.getAttributeName(i).trim().toLowerCase();
                                if (ATTRIBUTE_TYPE.equals(name)) {
                                    type = xpp.getAttributeValue(xpp.getAttributeNamespace(i),
                                            name).trim().toLowerCase();
                                    break;
                                }
                            }
                        }
                        Characteristic next = new Characteristic(type, current);
                        current.getSubs().add(next);
                        current = next;
                    } else if (TAG_PARM.equals(tag)) {
                        int count = xpp.getAttributeCount();
                        String key = null;
                        String value = null;
                        if (count > 1) {
                            for (int i = 0; i < count; i++) {
                                String name = xpp.getAttributeName(i).trim().toLowerCase();
                                if (ATTRIBUTE_NAME.equals(name)) {
                                    key = xpp.getAttributeValue(xpp.getAttributeNamespace(i),
                                            name).trim().toLowerCase();
                                } else if (ATTRIBUTE_VALUE.equals(name)) {
                                    value = xpp.getAttributeValue(xpp.getAttributeNamespace(i),
                                            name).trim();
                                }
                            }
                        }
                        if (key != null && value != null) {
                            current.getParms().put(key, value);
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    tag = xpp.getName().trim().toLowerCase();
                    if (TAG_CHARACTERISTIC.equals(tag)) {
                        current = current.getParent();
                    }
                    tag = null;
                }
                eventType = xpp.next();
            }
        } catch (IOException | XmlPullParserException e) {
            throw new IllegalArgumentException(e);
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                loge("error to close input stream, skip.");
            }
        }
    }

    /**
     * Retrieve a String value of the config item with the tag
     *
     * @param tag The name of the config to retrieve.
     * @param defaultVal Value to return if the config does not exist.
     *
     * @return Returns the config value if it exists, or defaultVal.
     */
    public @Nullable String getString(@NonNull String tag, @Nullable String defaultVal) {
        String value = mCurrent.getParmValue(tag.trim().toLowerCase());
        return value != null ?  value : defaultVal;
    }

    /**
     * Retrieve a int value of the config item with the tag
     *
     * @param tag The name of the config to retrieve.
     * @param defaultVal Value to return if the config does not exist or not valid.
     *
     * @return Returns the config value if it exists and is a valid int, or defaultVal.
     */
    public int getInteger(@NonNull String tag, int defaultVal) {
        try {
            return Integer.parseInt(getString(tag, null));
        } catch (NumberFormatException e) {
            logd("error to getInteger for " + tag + " due to " + e);
        }
        return defaultVal;
    }

    /**
     * Retrieve a boolean value of the config item with the tag
     *
     * @param tag The name of the config to retrieve.
     * @param defaultVal Value to return if the config does not exist.
     *
     * @return Returns the config value if it exists, or defaultVal.
     */
    public boolean getBoolean(@NonNull String tag, boolean defaultVal) {
        String value = getString(tag, null);
        return value != null ? Boolean.parseBoolean(value) : defaultVal;
    }

    /**
     * Check whether the config item exists
     *
     * @param tag The name of the config to retrieve.
     *
     * @return Returns true if it exists, or false.
     */
    public boolean hasConfig(@NonNull String tag) {
        return mCurrent.hasParm(tag.trim().toLowerCase());
    }

    /**
     * Return the Characteristic with the given type
     */
    public @Nullable Characteristic getCharacteristic(@NonNull String type) {
        return mCurrent.getSubByType(type.trim().toLowerCase());
    }

    /**
     * Check whether the Characteristic with the given type exists
     */
    public boolean hasCharacteristic(@NonNull String type) {
        return mCurrent.getSubByType(type.trim().toLowerCase()) != null;
    }

    /**
     * Set current Characteristic to given Characteristic
     */
    public void setCurrentCharacteristic(@NonNull Characteristic current) {
        if (current != null) {
            mCurrent = current;
        }
    }

    /**
     * Move current Characteristic to parent layer
     */
    public boolean moveToParent() {
        if (mCurrent.getParent() == null) {
            return false;
        }
        mCurrent = mCurrent.getParent();
        return true;
    }

    /**
     * Move current Characteristic to the root
     */
    public void moveToRoot() {
        mCurrent = mRoot;
    }

    /**
     * Return root Characteristic
     */
    public @NonNull Characteristic getRoot() {
        return mRoot;
    }

    /**
     * Return current Characteristic
     */
    public @NonNull Characteristic getCurrentCharacteristic() {
        return mCurrent;
    }

    /**
     * Check whether Rcs Volte single registration is supported by the config.
     */
    public boolean isRcsVolteSingleRegistrationSupported(boolean isRoaming) {
        int val = getInteger(PARM_SINGLE_REGISTRATION, 1);
        return isRoaming ? val == 1 : val > 0;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("[RCS Config]");
        if (DBG) {
            sb.append("=== Root ===\n");
            sb.append(mRoot);
            sb.append("=== Current ===\n");
            sb.append(mCurrent);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof RcsConfig)) {
            return false;
        }

        RcsConfig other = (RcsConfig) obj;

        return mRoot.equals(other.mRoot) && mCurrent.equals(other.mCurrent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRoot, mCurrent);
    }

    /**
     * compress the gzip format data
     */
    public static @Nullable byte[] compressGzip(@NonNull byte[] data) {
        if (data == null || data.length == 0) {
            return data;
        }
        byte[] out = null;
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
            GZIPOutputStream gzipCompressingStream =
                    new GZIPOutputStream(outputStream);
            gzipCompressingStream.write(data);
            gzipCompressingStream.close();
            out = outputStream.toByteArray();
            outputStream.close();
        } catch (IOException e) {
            loge("Error to compressGzip due to " + e);
        }
        return out;
    }

    /**
     * decompress the gzip format data
     */
    public static @Nullable byte[] decompressGzip(@NonNull byte[] data) {
        if (data == null || data.length == 0) {
            return data;
        }
        byte[] out = null;
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            GZIPInputStream gzipDecompressingStream =
                    new GZIPInputStream(inputStream);
            byte[] buf = new byte[1024];
            int size = gzipDecompressingStream.read(buf);
            while (size >= 0) {
                outputStream.write(buf, 0, size);
                size = gzipDecompressingStream.read(buf);
            }
            gzipDecompressingStream.close();
            inputStream.close();
            out = outputStream.toByteArray();
            outputStream.close();
        } catch (IOException e) {
            loge("Error to decompressGzip due to " + e);
        }
        return out;
    }

    /**
     * save the config to siminfo db. It is only used internally.
     */
    public static void updateConfigForSub(@NonNull Context cxt, int subId,
            @NonNull byte[] config, boolean isCompressed) {
        //always store gzip compressed data
        byte[] data = isCompressed ? config : compressGzip(config);
        ContentValues values = new ContentValues();
        values.put(SimInfo.COLUMN_RCS_CONFIG, data);
        cxt.getContentResolver().update(SimInfo.CONTENT_URI, values,
                SimInfo.COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID + "=" + subId, null);
    }

    /**
     * load the config from siminfo db. It is only used internally.
     */
    public static @Nullable byte[] loadRcsConfigForSub(@NonNull Context cxt,
            int subId, boolean isCompressed) {

        byte[] data = null;

        Cursor cursor = cxt.getContentResolver().query(SimInfo.CONTENT_URI, null,
                SimInfo.COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID + "=" + subId, null, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                data = cursor.getBlob(cursor.getColumnIndexOrThrow(SimInfo.COLUMN_RCS_CONFIG));
            }
        } catch (Exception e) {
            loge("error to load rcs config for sub:" + subId + " due to " + e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return isCompressed ? data : decompressGzip(data);
    }

    private static void logd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private static void loge(String msg) {
        Rlog.e(LOG_TAG, msg);
    }
}
