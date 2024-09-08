/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.vibrator.persistence.ParsedVibration;
import android.os.vibrator.persistence.VibrationXmlParser;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Rational;
import android.util.Size;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Objects;
import java.util.Vector;
import java.util.concurrent.Executor;

/**
 * Media Utilities
 *
 * This class is hidden but public to allow CTS testing and verification
 * of the static methods and classes.
 *
 * @hide
 */
public class Utils {
    private static final String TAG = "Utils";

    public static final String VIBRATION_URI_PARAM = "vibration_uri";

    /**
     * Sorts distinct (non-intersecting) range array in ascending order.
     * @throws java.lang.IllegalArgumentException if ranges are not distinct
     */
    public static <T extends Comparable<? super T>> void sortDistinctRanges(Range<T>[] ranges) {
        Arrays.sort(ranges, new Comparator<Range<T>>() {
            @Override
            public int compare(Range<T> lhs, Range<T> rhs) {
                if (lhs.getUpper().compareTo(rhs.getLower()) < 0) {
                    return -1;
                } else if (lhs.getLower().compareTo(rhs.getUpper()) > 0) {
                    return 1;
                }
                throw new IllegalArgumentException(
                        "sample rate ranges must be distinct (" + lhs + " and " + rhs + ")");
            }
        });
    }

    /**
     * Returns the intersection of two sets of non-intersecting ranges
     * @param one a sorted set of non-intersecting ranges in ascending order
     * @param another another sorted set of non-intersecting ranges in ascending order
     * @return the intersection of the two sets, sorted in ascending order
     */
    public static <T extends Comparable<? super T>>
            Range<T>[] intersectSortedDistinctRanges(Range<T>[] one, Range<T>[] another) {
        int ix = 0;
        Vector<Range<T>> result = new Vector<Range<T>>();
        for (Range<T> range: another) {
            while (ix < one.length &&
                    one[ix].getUpper().compareTo(range.getLower()) < 0) {
                ++ix;
            }
            while (ix < one.length &&
                    one[ix].getUpper().compareTo(range.getUpper()) < 0) {
                result.add(range.intersect(one[ix]));
                ++ix;
            }
            if (ix == one.length) {
                break;
            }
            if (one[ix].getLower().compareTo(range.getUpper()) <= 0) {
                result.add(range.intersect(one[ix]));
            }
        }
        return result.toArray(new Range[result.size()]);
    }

    /**
     * Returns the index of the range that contains a value in a sorted array of distinct ranges.
     * @param ranges a sorted array of non-intersecting ranges in ascending order
     * @param value the value to search for
     * @return if the value is in one of the ranges, it returns the index of that range.  Otherwise,
     * the return value is {@code (-1-index)} for the {@code index} of the range that is
     * immediately following {@code value}.
     */
    public static <T extends Comparable<? super T>>
            int binarySearchDistinctRanges(Range<T>[] ranges, T value) {
        return Arrays.binarySearch(ranges, Range.create(value, value),
                new Comparator<Range<T>>() {
                    @Override
                    public int compare(Range<T> lhs, Range<T> rhs) {
                        if (lhs.getUpper().compareTo(rhs.getLower()) < 0) {
                            return -1;
                        } else if (lhs.getLower().compareTo(rhs.getUpper()) > 0) {
                            return 1;
                        }
                        return 0;
                    }
                });
    }

    /**
     * Returns greatest common divisor
     */
    static int gcd(int a, int b) {
        if (a == 0 && b == 0) {
            return 1;
        }
        if (b < 0) {
            b = -b;
        }
        if (a < 0) {
            a = -a;
        }
        while (a != 0) {
            int c = b % a;
            b = a;
            a = c;
        }
        return b;
    }

    /** Returns the equivalent factored range {@code newrange}, where for every
     * {@code e}: {@code newrange.contains(e)} implies that {@code range.contains(e * factor)},
     * and {@code !newrange.contains(e)} implies that {@code !range.contains(e * factor)}.
     */
    static Range<Integer>factorRange(Range<Integer> range, int factor) {
        if (factor == 1) {
            return range;
        }
        return Range.create(divUp(range.getLower(), factor), range.getUpper() / factor);
    }

    /** Returns the equivalent factored range {@code newrange}, where for every
     * {@code e}: {@code newrange.contains(e)} implies that {@code range.contains(e * factor)},
     * and {@code !newrange.contains(e)} implies that {@code !range.contains(e * factor)}.
     */
    static Range<Long>factorRange(Range<Long> range, long factor) {
        if (factor == 1) {
            return range;
        }
        return Range.create(divUp(range.getLower(), factor), range.getUpper() / factor);
    }

    private static Rational scaleRatio(Rational ratio, int num, int den) {
        int common = gcd(num, den);
        num /= common;
        den /= common;
        return new Rational(
                (int)(ratio.getNumerator() * (double)num),     // saturate to int
                (int)(ratio.getDenominator() * (double)den));  // saturate to int
    }

    static Range<Rational> scaleRange(Range<Rational> range, int num, int den) {
        if (num == den) {
            return range;
        }
        return Range.create(
                scaleRatio(range.getLower(), num, den),
                scaleRatio(range.getUpper(), num, den));
    }

    static Range<Integer> alignRange(Range<Integer> range, int align) {
        return range.intersect(
                divUp(range.getLower(), align) * align,
                (range.getUpper() / align) * align);
    }

    static int divUp(int num, int den) {
        return (num + den - 1) / den;
    }

    static long divUp(long num, long den) {
        return (num + den - 1) / den;
    }

    /**
     * Returns least common multiple
     */
    private static long lcm(int a, int b) {
        if (a == 0 || b == 0) {
            throw new IllegalArgumentException("lce is not defined for zero arguments");
        }
        return (long)a * b / gcd(a, b);
    }

    static Range<Integer> intRangeFor(double v) {
        return Range.create((int)v, (int)Math.ceil(v));
    }

    static Range<Long> longRangeFor(double v) {
        return Range.create((long)v, (long)Math.ceil(v));
    }

    static Size parseSize(Object o, Size fallback) {
        if (o == null) {
            return fallback;
        }
        try {
            return Size.parseSize((String) o);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        }
        Log.w(TAG, "could not parse size '" + o + "'");
        return fallback;
    }

    static int parseIntSafely(Object o, int fallback) {
        if (o == null) {
            return fallback;
        }
        try {
            String s = (String)o;
            return Integer.parseInt(s);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        }
        Log.w(TAG, "could not parse integer '" + o + "'");
        return fallback;
    }

    static Range<Integer> parseIntRange(Object o, Range<Integer> fallback) {
        if (o == null) {
            return fallback;
        }
        try {
            String s = (String)o;
            int ix = s.indexOf('-');
            if (ix >= 0) {
                return Range.create(
                        Integer.parseInt(s.substring(0, ix), 10),
                        Integer.parseInt(s.substring(ix + 1), 10));
            }
            int value = Integer.parseInt(s);
            return Range.create(value, value);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        } catch (IllegalArgumentException e) {
        }
        Log.w(TAG, "could not parse integer range '" + o + "'");
        return fallback;
    }

    static Range<Long> parseLongRange(Object o, Range<Long> fallback) {
        if (o == null) {
            return fallback;
        }
        try {
            String s = (String)o;
            int ix = s.indexOf('-');
            if (ix >= 0) {
                return Range.create(
                        Long.parseLong(s.substring(0, ix), 10),
                        Long.parseLong(s.substring(ix + 1), 10));
            }
            long value = Long.parseLong(s);
            return Range.create(value, value);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        } catch (IllegalArgumentException e) {
        }
        Log.w(TAG, "could not parse long range '" + o + "'");
        return fallback;
    }

    static Range<Rational> parseRationalRange(Object o, Range<Rational> fallback) {
        if (o == null) {
            return fallback;
        }
        try {
            String s = (String)o;
            int ix = s.indexOf('-');
            if (ix >= 0) {
                return Range.create(
                        Rational.parseRational(s.substring(0, ix)),
                        Rational.parseRational(s.substring(ix + 1)));
            }
            Rational value = Rational.parseRational(s);
            return Range.create(value, value);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        } catch (IllegalArgumentException e) {
        }
        Log.w(TAG, "could not parse rational range '" + o + "'");
        return fallback;
    }

    static Pair<Size, Size> parseSizeRange(Object o) {
        if (o == null) {
            return null;
        }
        try {
            String s = (String)o;
            int ix = s.indexOf('-');
            if (ix >= 0) {
                return Pair.create(
                        Size.parseSize(s.substring(0, ix)),
                        Size.parseSize(s.substring(ix + 1)));
            }
            Size value = Size.parseSize(s);
            return Pair.create(value, value);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        } catch (IllegalArgumentException e) {
        }
        Log.w(TAG, "could not parse size range '" + o + "'");
        return null;
    }

    /**
     * Creates a unique file in the specified external storage with the desired name. If the name is
     * taken, the new file's name will have '(%d)' to avoid overwriting files.
     *
     * @param context {@link Context} to query the file name from.
     * @param subdirectory One of the directories specified in {@link android.os.Environment}
     * @param fileName desired name for the file.
     * @param mimeType MIME type of the file to create.
     * @return the File object in the storage, or null if an error occurs.
     */
    public static File getUniqueExternalFile(Context context, String subdirectory, String fileName,
            String mimeType) {
        File externalStorage = Environment.getExternalStoragePublicDirectory(subdirectory);
        // Make sure the storage subdirectory exists
        externalStorage.mkdirs();

        File outFile = null;
        try {
            // Ensure the file has a unique name, as to not override any existing file
            outFile = FileUtils.buildUniqueFile(externalStorage, mimeType, fileName);
        } catch (FileNotFoundException e) {
            // This might also be reached if the number of repeated files gets too high
            Log.e(TAG, "Unable to get a unique file name: " + e);
            return null;
        }
        return outFile;
    }

    /**
     * Returns a file's display name from its {@link android.content.ContentResolver.SCHEME_FILE}
     * or {@link android.content.ContentResolver.SCHEME_CONTENT} Uri. The display name of a file
     * includes its extension.
     *
     * @param context Context trying to resolve the file's display name.
     * @param uri Uri of the file.
     * @return the file's display name, or the uri's string if something fails or the uri isn't in
     *            the schemes specified above.
     */
    static String getFileDisplayNameFromUri(Context context, Uri uri) {
        String scheme = uri.getScheme();

        if (ContentResolver.SCHEME_FILE.equals(scheme)) {
            return uri.getLastPathSegment();
        } else if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
            // We need to query the ContentResolver to get the actual file name as the Uri masks it.
            // This means we want the name used for display purposes only.
            String[] proj = {
                    OpenableColumns.DISPLAY_NAME
            };
            try (Cursor cursor = context.getContentResolver().query(uri, proj, null, null, null)) {
                if (cursor != null && cursor.getCount() != 0) {
                    cursor.moveToFirst();
                    return cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            }
        }

        // This will only happen if the Uri isn't either SCHEME_CONTENT or SCHEME_FILE, so we assume
        // it already represents the file's name.
        return uri.toString();
    }

    /**
     * {@code ListenerList} is a helper class that delivers events to listeners.
     *
     * It is written to isolate the <strong>mechanics</strong> of event delivery from the
     * <strong>details</strong> of those events.
     *
     * The {@code ListenerList} is parameterized on the generic type {@code V}
     * of the object delivered by {@code notify()}.
     * This gives compile time type safety over run-time casting of a general {@code Object},
     * much like {@code HashMap&lt;String, Object&gt;} does not give type safety of the
     * stored {@code Object} value and may allow
     * permissive storage of {@code Object}s that are not expected by users of the
     * {@code HashMap}, later resulting in run-time cast exceptions that
     * could have been caught by replacing
     * {@code Object} with a more precise type to enforce a compile time contract.
     *
     * The {@code ListenerList} is implemented as a single method callback
     * - or a "listener" according to Android style guidelines.
     *
     * The {@code ListenerList} can be trivially extended by a suitable lambda to implement
     * a <strong> multiple method abstract class</strong> "callback",
     * in which the generic type {@code V} could be an {@code Object}
     * to encapsulate the details of the parameters of each callback method, and
     * {@code instanceof} could be used to disambiguate which callback method to use.
     * A {@link Bundle} could alternatively encapsulate those generic parameters,
     * perhaps more conveniently.
     * Again, this is a detail of the event, not the mechanics of the event delivery,
     * which this class is concerned with.
     *
     * For details on how to use this class to implement a <strong>single listener</strong>
     * {@code ListenerList}, see notes on {@link #add}.
     *
     * For details on how to optimize this class to implement
     * a listener based on {@link Handler}s
     * instead of {@link Executor}s, see{@link #ListenerList(boolean, boolean, boolean)}.
     *
     * This is a TestApi for CTS Unit Testing, not exposed for general Application use.
     * @hide
     *
     * @param <V> The class of the object returned to the listener.
     */
    public static class ListenerList<V> {
        /**
         * The Listener interface for callback.
         *
         * @param <V> The class of the object returned to the listener
         */
        public interface Listener<V> {
            /**
             * General event listener interface which is managed by the {@code ListenerList}.
             *
             * @param eventCode is an integer representing the event type. This is an
             *     implementation defined parameter.
             * @param info is the object returned to the listener.  It is expected
             *     that the listener makes a private copy of the {@code info} object before
             *     modification, as it is the same instance passed to all listeners.
             *     This is an implementation defined parameter that may be null.
             */
            void onEvent(int eventCode, @Nullable V info);
        }

        private interface ListenerWithCancellation<V> extends Listener<V> {
            void cancel();
        }

        /**
         * Default {@code ListenerList} constructor for {@link Executor} based implementation.
         *
         * TODO: consider adding a "name" for debugging if this is used for
         * multiple listener implementations.
         */
        public ListenerList() {
            this(true /* restrictSingleCallerOnEvent */,
                true /* clearCallingIdentity */,
                false /* forceRemoveConsistency*/);
        }

        /**
         * Specific {@code ListenerList} constructor for customization.
         *
         * See the internal notes for the corresponding private variables on the behavior of
         * the boolean configuration parameters.
         *
         * {@code ListenerList(true, true, false)} is the default and used for
         * {@link Executor} based notification implementation.
         *
         * {@code ListenerList(false, false, false)} may be used for as an optimization
         * where the {@link Executor} is actually a {@link Handler} post.
         *
         * @param restrictSingleCallerOnEvent whether the listener will only be called by
         *     a single thread at a time.
         * @param clearCallingIdentity whether the binder calling identity on
         *     {@link #notify} is cleared.
         * @param forceRemoveConsistency whether remove() guarantees no more callbacks to
         *     the listener immediately after the call.
         */
        public ListenerList(boolean restrictSingleCallerOnEvent,
                boolean clearCallingIdentity,
                boolean forceRemoveConsistency) {
            mRestrictSingleCallerOnEvent = restrictSingleCallerOnEvent;
            mClearCallingIdentity = clearCallingIdentity;
            mForceRemoveConsistency = forceRemoveConsistency;
        }

        /**
         * Adds a listener to the {@code ListenerList}.
         *
         * The {@code ListenerList} is most often used to hold {@code multiple} listeners.
         *
         * Per Android style, for a single method Listener interface, the add and remove
         * would be wrapped in "addSomeListener" or "removeSomeListener";
         * or a lambda implemented abstract class callback, wrapped in
         * "registerSomeCallback" or "unregisterSomeCallback".
         *
         * We allow a general {@code key} to be attached to add and remove that specific
         * listener.  It could be the {@code listener} object itself.
         *
         * For some implementations, there may be only a {@code single} listener permitted.
         *
         * Per Android style, for a single listener {@code ListenerList},
         * the naming of the wrapping call to {@link #add} would be
         * "setSomeListener" with a nullable listener, which would be null
         * to call {@link #remove}.
         *
         * In that case, the caller may use this {@link #add} with a single constant object for
         * the {@code key} to enforce only one Listener in the {@code ListenerList}.
         * Likewise on remove it would use that
         * same single constant object to remove the listener.
         * That {@code key} object could be the {@code ListenerList} itself for convenience.
         *
         * @param key is a unique object that is used to identify the listener
         *     when {@code remove()} is called. It can be the listener itself.
         * @param executor is used to execute the callback.
         * @param listener is the {@link AudioTrack.ListenerList.Listener}
         *     interface to be called upon {@link notify}.
         */
        public void add(
                @NonNull Object key, @NonNull Executor executor, @NonNull Listener<V> listener) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(executor);
            Objects.requireNonNull(listener);

            // construct wrapper outside of lock.
            ListenerWithCancellation<V> listenerWithCancellation =
                    new ListenerWithCancellation<V>() {
                        private final Object mLock = new Object(); // our lock is per Listener.
                        private volatile boolean mCancelled = false; // atomic rmw not needed.

                        @Override
                        public void onEvent(int eventCode, V info) {
                            executor.execute(() -> {
                                // Note deep execution of locking and cancellation
                                // so this works after posting on different threads.
                                if (mRestrictSingleCallerOnEvent || mForceRemoveConsistency) {
                                    synchronized (mLock) {
                                        if (mCancelled) return;
                                        listener.onEvent(eventCode, info);
                                    }
                                } else {
                                    if (mCancelled) return;
                                    listener.onEvent(eventCode, info);
                                }
                            });
                        }

                        @Override
                        public void cancel() {
                            if (mForceRemoveConsistency) {
                                synchronized (mLock) {
                                    mCancelled = true;
                                }
                            } else {
                                mCancelled = true;
                            }
                        }
                    };

            synchronized (mListeners) {
                // TODO: consider an option to check the existence of the key
                // and throw an ISE if it exists.
                mListeners.put(key, listenerWithCancellation);  // replaces old value
            }
        }

        /**
         * Removes a listener from the {@code ListenerList}.
         *
         * @param key the unique object associated with the listener during {@link #add}.
         */
        public void remove(@NonNull Object key) {
            Objects.requireNonNull(key);

            ListenerWithCancellation<V> listener;
            synchronized (mListeners) {
                listener = mListeners.get(key);
                if (listener == null) { // TODO: consider an option to throw ISE Here.
                    return;
                }
                mListeners.remove(key);  // removes if exist
            }

            // cancel outside of lock
            listener.cancel();
        }

        /**
         * Notifies all listeners on the List.
         *
         * @param eventCode to pass to all listeners.
         * @param info to pass to all listeners. This is an implemention defined parameter
         *     which may be {@code null}.
         */
        public void notify(int eventCode, @Nullable V info) {
            Object[] listeners; // note we can't cast an object array to a listener array
            synchronized (mListeners) {
                if (mListeners.size() == 0) {
                    return;
                }
                listeners = mListeners.values().toArray(); // guarantees a copy.
            }

            // notify outside of lock.
            final Long identity = mClearCallingIdentity ? Binder.clearCallingIdentity() : null;
            try {
                for (Object object : listeners) {
                    final ListenerWithCancellation<V> listener =
                            (ListenerWithCancellation<V>) object;
                    listener.onEvent(eventCode, info);
                }
            } finally {
                if (identity != null) {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @GuardedBy("mListeners")
        private HashMap<Object, ListenerWithCancellation<V>> mListeners = new HashMap<>();

        // An Executor may run in multiple threads, whereas a Handler runs on a single Looper.
        // Should be true for an Executor to avoid concurrent calling into the same listener,
        // can be false for a Handler as a Handler forces single thread caller for each listener.
        private final boolean mRestrictSingleCallerOnEvent; // default true

        // An Executor may run in the calling thread, whereas a handler will post to the Looper.
        // Should be true for an Executor to prevent privilege escalation,
        // can be false for a Handler as its thread is not the calling binder thread.
        private final boolean mClearCallingIdentity; // default true

        // Guaranteeing no listener callbacks after removal requires taking the same lock for the
        // remove as the callback; this is a reversal in calling layers,
        // hence the risk of lock order inversion is great.
        //
        // Set to true only if you can control the caller's listen and remove methods and/or
        // the threading of the Executor used for each listener.
        // When set to false, we do not lock, but still do a best effort to cancel messages
        // on the fly.
        private final boolean mForceRemoveConsistency; // default false
    }

    /**
     * Convert a Bluetooth MAC address to an anonymized one when exposed to a non privileged app
     * Must match the implementation of BluetoothUtils.toAnonymizedAddress()
     * @param address MAC address to be anonymized
     * @return anonymized MAC address
     */
    public static @Nullable String anonymizeBluetoothAddress(@Nullable String address) {
        if (address == null) {
            return null;
        }
        if (address.length() != "AA:BB:CC:DD:EE:FF".length()) {
            return address;
        }
        return "XX:XX:XX:XX" + address.substring("XX:XX:XX:XX".length());
    }

    /**
     * Convert a Bluetooth MAC address to an anonymized one if the internal device type corresponds
     * to a Bluetooth.
     * @param deviceType the internal type of the audio device
     * @param address MAC address to be anonymized
     * @return anonymized MAC address
     */
    public static @Nullable String anonymizeBluetoothAddress(
            int deviceType, @Nullable String address) {
        if (!AudioSystem.isBluetoothDevice(deviceType)) {
            return address;
        }
        return anonymizeBluetoothAddress(address);
    }

    /**
     * Whether the device supports ringtone vibration settings.
     *
     * @param context the {@link Context}
     * @return {@code true} if the device supports ringtone vibration
     */
    public static boolean isRingtoneVibrationSettingsSupported(Context context) {
        final Resources res = context.getResources();
        return res != null && res.getBoolean(
                com.android.internal.R.bool.config_ringtoneVibrationSettingsSupported);
    }

    /**
     * Whether the given ringtone Uri has vibration Uri parameter
     *
     * @param ringtoneUri the ringtone Uri
     * @return {@code true} if the Uri has vibration parameter
     */
    public static boolean hasVibration(Uri ringtoneUri) {
        final String vibrationUriString = ringtoneUri.getQueryParameter(VIBRATION_URI_PARAM);
        return vibrationUriString != null;
    }

    /**
     * Gets the vibration Uri from given ringtone Uri
     *
     * @param ringtoneUri the ringtone Uri
     * @return parsed {@link Uri} of vibration parameter, {@code null} if the vibration parameter
     * is not found.
     */
    public static Uri getVibrationUri(Uri ringtoneUri) {
        final String vibrationUriString = ringtoneUri.getQueryParameter(VIBRATION_URI_PARAM);
        if (vibrationUriString == null) {
            return null;
        }
        return Uri.parse(vibrationUriString);
    }

    /**
     * Returns the parsed {@link VibrationEffect} from given vibration Uri.
     *
     * @param vibrator the vibrator to resolve the vibration file
     * @param vibrationUri the vibration file Uri to represent a vibration
     */
    @SuppressWarnings("FlaggedApi") // VibrationXmlParser is available internally as hidden APIs.
    public static VibrationEffect parseVibrationEffect(Vibrator vibrator, Uri vibrationUri) {
        if (vibrationUri == null) {
            Log.w(TAG, "The vibration Uri is null.");
            return null;
        }
        String filePath = vibrationUri.getPath();
        if (filePath == null) {
            Log.w(TAG, "The file path is null.");
            return null;
        }
        File vibrationFile = new File(filePath);
        if (vibrationFile.exists() && vibrationFile.canRead()) {
            try {
                FileInputStream fileInputStream = new FileInputStream(vibrationFile);
                ParsedVibration parsedVibration =
                        VibrationXmlParser.parseDocument(
                                new InputStreamReader(fileInputStream, StandardCharsets.UTF_8));
                return parsedVibration.resolve(vibrator);
            } catch (IOException e) {
                Log.e(TAG, "FileNotFoundException" + e);
            }
        } else {
            // File not found or cannot be read
            Log.w(TAG, "File exists:" + vibrationFile.exists()
                    + ", canRead:" + vibrationFile.canRead());
        }
        return null;
    }
}
