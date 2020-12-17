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
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Telephony.SimInfo;
import android.text.TextUtils;

import com.android.telephony.Rlog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * RCS config data and methods to process the config
 * @hide
 */
public final class RcsConfig implements Parcelable {
    private static final String LOG_TAG = "RcsConfig";
    private static final boolean DBG = Build.IS_ENG;

    private final HashMap<String, String> mValues = new HashMap<>();

    private RcsConfig(HashMap<String, String> values) {
        mValues.putAll(values);
    }

    public RcsConfig(byte[] data) throws IllegalArgumentException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Empty data");
        }
        ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(inputStream, null);
            int eventType = xpp.getEventType();
            String tag = null;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    tag = xpp.getName().trim();
                } else if (eventType == XmlPullParser.END_TAG) {
                    tag = null;
                } else if (eventType == XmlPullParser.TEXT) {
                    String value = xpp.getText().trim();
                    if (!TextUtils.isEmpty(tag) && !TextUtils.isEmpty(value)) {
                        mValues.put(tag, value);
                    }
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
        return mValues.containsKey(tag) ? mValues.get(tag) : defaultVal;
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
            return Integer.parseInt(mValues.get(tag));
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
        if (!mValues.containsKey(tag)) {
            return defaultVal;
        }
        return Boolean.parseBoolean(mValues.get(tag));
    }

    /**
     * Check whether the config item exists
     *
     * @param tag The name of the config to retrieve.
     *
     * @return Returns true if it exists, or false.
     */
    public boolean hasConfig(@NonNull String tag) {
        return mValues.containsKey(tag);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("[RCS Config]");
        if (DBG) {
            mValues.forEach((t, v) -> {
                sb.append("\n");
                sb.append(t);
                sb.append(" : ");
                sb.append(v);
            });
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof RcsConfig)) {
            return false;
        }

        RcsConfig other = (RcsConfig) obj;

        return mValues.equals(other.mValues);
    }

    @Override
    public int hashCode() {
        return mValues.hashCode();
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

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeMap(mValues);
    }

    /**
     * {@link Parcelable.Creator}
     *
     */
    public static final @NonNull Parcelable.Creator<RcsConfig>
            CREATOR = new Creator<RcsConfig>() {
                @Override
                public RcsConfig createFromParcel(Parcel in) {
                    HashMap<String, String> values = in.readHashMap(null);
                    return values == null ? null : new RcsConfig(values);
                }

                @Override
                public RcsConfig[] newArray(int size) {
                    return new RcsConfig[size];
                }
            };

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    private static void logd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private static void loge(String msg) {
        Rlog.e(LOG_TAG, msg);
    }
}
