/*
 * Copyright (C) 2007-2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.BadParcelableException;
import android.os.Parcel;
import android.util.Printer;
import android.util.Slog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * An array-like container that stores multiple instances of {@link InputMethodSubtype}.
 *
 * <p>This container is designed to reduce the risk of {@link TransactionTooLargeException}
 * when one or more instancess of {@link InputMethodInfo} are transferred through IPC.
 * Basically this class does following three tasks.</p>
 * <ul>
 * <li>Applying compression for the marshalled data</li>
 * <li>Lazily unmarshalling objects</li>
 * <li>Caching the marshalled data when appropriate</li>
 * </ul>
 *
 * @hide
 */
public class InputMethodSubtypeArray {
    private final static String TAG = "InputMethodSubtypeArray";

    /**
     * Create a new instance of {@link InputMethodSubtypeArray} from an existing list of
     * {@link InputMethodSubtype}.
     *
     * @param subtypes A list of {@link InputMethodSubtype} from which
     * {@link InputMethodSubtypeArray} will be created.
     */
    @UnsupportedAppUsage
    public InputMethodSubtypeArray(final List<InputMethodSubtype> subtypes) {
        if (subtypes == null) {
            mCount = 0;
            return;
        }
        mCount = subtypes.size();
        mInstance = subtypes.toArray(new InputMethodSubtype[mCount]);
    }

    /**
     * Unmarshall an instance of {@link InputMethodSubtypeArray} from a given {@link Parcel}
     * object.
     *
     * @param source A {@link Parcel} object from which {@link InputMethodSubtypeArray} will be
     * unmarshalled.
     */
    public InputMethodSubtypeArray(final Parcel source) {
        mCount = source.readInt();
        if (mCount < 0) {
            throw new BadParcelableException("mCount must be non-negative.");
        }
        if (mCount > 0) {
            mDecompressedSize = source.readInt();
            mCompressedData = source.createByteArray();
        }
    }

    /**
     * Marshall the instance into a given {@link Parcel} object.
     *
     * <p>This methods may take a bit additional time to compress data lazily when called
     * first time.</p>
     *
     * @param source A {@link Parcel} object to which {@link InputMethodSubtypeArray} will be
     * marshalled.
     */
    public void writeToParcel(final Parcel dest) {
        if (mCount == 0) {
            dest.writeInt(mCount);
            return;
        }

        byte[] compressedData = mCompressedData;
        int decompressedSize = mDecompressedSize;
        if (compressedData == null && decompressedSize == 0) {
            synchronized (mLockObject) {
                compressedData = mCompressedData;
                decompressedSize = mDecompressedSize;
                if (compressedData == null && decompressedSize == 0) {
                    final byte[] decompressedData = marshall(mInstance);
                    compressedData = compress(decompressedData);
                    if (compressedData == null) {
                        decompressedSize = -1;
                        Slog.i(TAG, "Failed to compress data.");
                    } else {
                        decompressedSize = decompressedData.length;
                    }
                    mDecompressedSize = decompressedSize;
                    mCompressedData = compressedData;
                }
            }
        }

        if (compressedData != null && decompressedSize > 0) {
            dest.writeInt(mCount);
            dest.writeInt(decompressedSize);
            dest.writeByteArray(compressedData);
        } else {
            Slog.i(TAG, "Unexpected state. Behaving as an empty array.");
            dest.writeInt(0);
        }
    }

    /**
     * Return {@link InputMethodSubtype} specified with the given index.
     *
     * <p>This methods may take a bit additional time to decompress data lazily when called
     * first time.</p>
     *
     * @param index The index of {@link InputMethodSubtype}.
     */
    public InputMethodSubtype get(final int index) {
        if (index < 0 || mCount <= index) {
            throw new ArrayIndexOutOfBoundsException();
        }
        InputMethodSubtype[] instance = mInstance;
        if (instance == null) {
            synchronized (mLockObject) {
                instance = mInstance;
                if (instance == null) {
                    final byte[] decompressedData =
                          decompress(mCompressedData, mDecompressedSize);
                    // Clear the compressed data until {@link #getMarshalled()} is called.
                    mCompressedData = null;
                    mDecompressedSize = 0;
                    if (decompressedData != null) {
                        instance = unmarshall(decompressedData);
                    } else {
                        Slog.e(TAG, "Failed to decompress data. Returns null as fallback.");
                        instance = new InputMethodSubtype[mCount];
                    }
                    mInstance = instance;
                }
            }
        }
        return instance[index];
    }

    /**
     * Return the number of {@link InputMethodSubtype} objects.
     */
    public int getCount() {
        return mCount;
    }

    private final Object mLockObject = new Object();
    private final int mCount;

    private volatile InputMethodSubtype[] mInstance;
    private volatile byte[] mCompressedData;
    private volatile int mDecompressedSize;

    void dump(@NonNull Printer pw, @NonNull String prefix) {
        final var innerPrefix = prefix + "  ";
        for (int i = 0; i < mCount; i++) {
            pw.println(prefix + "InputMethodSubtype #" + i + ":");
            final var subtype = get(i);
            if (subtype != null) {
                subtype.dump(pw, innerPrefix);
            } else {
                pw.println(innerPrefix + "missing subtype");
            }
        }
    }

    private static byte[] marshall(final InputMethodSubtype[] array) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.writeTypedArray(array, 0);
            return parcel.marshall();
        } finally {
            if (parcel != null) {
                parcel.recycle();
                parcel = null;
            }
        }
    }

    private static InputMethodSubtype[] unmarshall(final byte[] data) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            return parcel.createTypedArray(InputMethodSubtype.CREATOR);
        } finally {
            if (parcel != null) {
                parcel.recycle();
                parcel = null;
            }
        }
    }

    private static byte[] compress(final byte[] data) {
        try (final ByteArrayOutputStream resultStream = new ByteArrayOutputStream();
                final GZIPOutputStream zipper = new GZIPOutputStream(resultStream)) {
            zipper.write(data);
            zipper.finish();
            return resultStream.toByteArray();
        } catch(Exception e) {
            Slog.e(TAG, "Failed to compress the data.", e);
            return null;
        }
    }

    private static byte[] decompress(final byte[] data, final int expectedSize) {
        try (final ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
                final GZIPInputStream unzipper = new GZIPInputStream(inputStream)) {
            final byte [] result = new byte[expectedSize];
            int totalReadBytes = 0;
            while (totalReadBytes < result.length) {
                final int restBytes = result.length - totalReadBytes;
                final int readBytes = unzipper.read(result, totalReadBytes, restBytes);
                if (readBytes < 0) {
                    break;
                }
                totalReadBytes += readBytes;
            }
            if (expectedSize != totalReadBytes) {
                return null;
            }
            return result;
        } catch(Exception e) {
            Slog.e(TAG, "Failed to decompress the data.", e);
            return null;
        }
    }
}
