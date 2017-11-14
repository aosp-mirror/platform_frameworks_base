/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.display;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ParceledListSlice;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.BrightnessChangeEvent;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.RingBuffer;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;

import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Class that tracks recent brightness settings changes and stores
 * associated information such as light sensor readings.
 */
public class BrightnessTracker {

    static final String TAG = "BrightnessTracker";
    static final boolean DEBUG = false;

    private static final String EVENTS_FILE = "brightness_events.xml";
    private static final int MAX_EVENTS = 100;
    // Discard events when reading or writing that are older than this.
    private static final long MAX_EVENT_AGE = TimeUnit.DAYS.toMillis(30);
    // Time over which we keep lux sensor readings.
    private static final long LUX_EVENT_HORIZON = TimeUnit.SECONDS.toNanos(10);

    private static final String TAG_EVENTS = "events";
    private static final String TAG_EVENT = "event";
    private static final String ATTR_BRIGHTNESS = "brightness";
    private static final String ATTR_TIMESTAMP = "timestamp";
    private static final String ATTR_PACKAGE_NAME = "packageName";
    private static final String ATTR_USER = "user";
    private static final String ATTR_LUX = "lux";
    private static final String ATTR_LUX_TIMESTAMPS = "luxTimestamps";
    private static final String ATTR_BATTERY_LEVEL = "batteryLevel";
    private static final String ATTR_NIGHT_MODE = "nightMode";
    private static final String ATTR_COLOR_TEMPERATURE = "colorTemperature";
    private static final String ATTR_LAST_BRIGHTNESS = "lastBrightness";

    // Lock held while accessing mEvents, is held while writing events to flash.
    private final Object mEventsLock = new Object();
    @GuardedBy("mEventsLock")
    private RingBuffer<BrightnessChangeEvent> mEvents
            = new RingBuffer<>(BrightnessChangeEvent.class, MAX_EVENTS);
    @GuardedBy("mEventsLock")
    private boolean mEventsDirty;
    private final Runnable mEventsWriter = () -> writeEvents();
    private volatile boolean mWriteEventsScheduled;

    private UserManager mUserManager;
    private final Context mContext;
    private final ContentResolver mContentResolver;
    private Handler mBgHandler;
    // mSettingsObserver, mBroadcastReceiver and mSensorListener should only be used on
    // the mBgHandler thread.
    private SettingsObserver mSettingsObserver;
    private BroadcastReceiver mBroadcastReceiver;
    private SensorListener mSensorListener;

    // Lock held while collecting data related to brightness changes.
    private final Object mDataCollectionLock = new Object();
    @GuardedBy("mDataCollectionLock")
    private Deque<LightData> mLastSensorReadings = new ArrayDeque<>();
    @GuardedBy("mDataCollectionLock")
    private float mLastBatteryLevel = Float.NaN;
    @GuardedBy("mDataCollectionLock")
    private int mIgnoreBrightness = -1;
    @GuardedBy("mDataCollectionLock")
    private int mLastBrightness = -1;

    private final Injector mInjector;

    public BrightnessTracker(Context context, @Nullable Injector injector) {
        // Note this will be called very early in boot, other system
        // services may not be present.
        mContext = context;
        mContentResolver = context.getContentResolver();
        if (injector != null) {
            mInjector = injector;
        } else {
            mInjector = new Injector();
        }
    }

    /** Start listening for brightness slider events */
    public void start() {
        if (DEBUG) {
            Slog.d(TAG, "Start");
        }
        mBgHandler = mInjector.getBackgroundHandler();
        mUserManager = mContext.getSystemService(UserManager.class);

        mBgHandler.post(() -> backgroundStart());
    }

    private void backgroundStart() {
        readEvents();

        mLastBrightness = mInjector.getSystemIntForUser(mContentResolver,
                Settings.System.SCREEN_BRIGHTNESS, -1,
                UserHandle.USER_CURRENT);

        mSensorListener = new SensorListener();
        mInjector.registerSensorListener(mContext, mSensorListener);

        mSettingsObserver = new SettingsObserver(mBgHandler);
        mInjector.registerBrightnessObserver(mContentResolver, mSettingsObserver);

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SHUTDOWN);
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        mBroadcastReceiver = new Receiver();
        mInjector.registerReceiver(mContext, mBroadcastReceiver, intentFilter);

        mInjector.scheduleIdleJob(mContext);
    }

    /** Stop listening for events */
    @VisibleForTesting
    void stop() {
        if (DEBUG) {
            Slog.d(TAG, "Stop");
        }
        mInjector.unregisterSensorListener(mContext, mSensorListener);
        mInjector.unregisterReceiver(mContext, mBroadcastReceiver);
        mInjector.unregisterBrightnessObserver(mContext, mSettingsObserver);
        mInjector.cancelIdleJob(mContext);
    }

    /**
     * @param userId userId to fetch data for.
     * @return List of recent {@link BrightnessChangeEvent}s
     */
    public ParceledListSlice<BrightnessChangeEvent> getEvents(int userId) {
        // TODO include apps from any managed profiles in the brightness information.
        BrightnessChangeEvent[] events;
        synchronized (mEventsLock) {
            events = mEvents.toArray();
        }
        ArrayList<BrightnessChangeEvent> out = new ArrayList<>(events.length);
        for (int i = 0; i < events.length; ++i) {
            if (events[i].userId == userId) {
                out.add(events[i]);
            }
        }
        return new ParceledListSlice<>(out);
    }

    /** Sets brightness without logging the brightness change event */
    public void setBrightness(int brightness, int userId) {
        synchronized (mDataCollectionLock) {
            mIgnoreBrightness = brightness;
        }
        mInjector.putSystemIntForUser(mContentResolver, Settings.System.SCREEN_BRIGHTNESS,
                brightness, userId);
    }

    public void persistEvents() {
        scheduleWriteEvents();
    }

    private void handleBrightnessChanged() {
        if (DEBUG) {
            Slog.d(TAG, "Brightness change");
        }
        final BrightnessChangeEvent event = new BrightnessChangeEvent();
        event.timeStamp = mInjector.currentTimeMillis();

        int brightness = mInjector.getSystemIntForUser(mContentResolver,
                Settings.System.SCREEN_BRIGHTNESS, -1,
                UserHandle.USER_CURRENT);

        synchronized (mDataCollectionLock) {
            int previousBrightness = mLastBrightness;
            mLastBrightness = brightness;

            if (brightness == -1 || brightness == mIgnoreBrightness) {
                // Notified of brightness change but no setting or self change so ignore.
                mIgnoreBrightness = -1;
                return;
            }

            final int readingCount = mLastSensorReadings.size();
            if (readingCount == 0) {
                // No sensor data so ignore this.
                return;
            }

            event.luxValues = new float[readingCount];
            event.luxTimestamps = new long[readingCount];

            int pos = 0;

            // Convert sensor timestamp in elapsed time nanos to current time millis.
            long currentTimeMillis = mInjector.currentTimeMillis();
            long elapsedTimeNanos = mInjector.elapsedRealtimeNanos();
            for (LightData reading : mLastSensorReadings) {
                event.luxValues[pos] = reading.lux;
                event.luxTimestamps[pos] = currentTimeMillis -
                        TimeUnit.NANOSECONDS.toMillis(elapsedTimeNanos - reading.timestamp);
                ++pos;
            }

            event.batteryLevel = mLastBatteryLevel;
            event.lastBrightness = previousBrightness;
        }

        event.brightness = brightness;

        try {
            final ActivityManager.StackInfo focusedStack = mInjector.getFocusedStack();
            event.userId = focusedStack.userId;
            event.packageName = focusedStack.topActivity.getPackageName();
        } catch (RemoteException e) {
            // Really shouldn't be possible.
        }

        event.nightMode = mInjector.getSecureIntForUser(mContentResolver,
                Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 0, UserHandle.USER_CURRENT)
                == 1;
        event.colorTemperature = mInjector.getSecureIntForUser(mContentResolver,
                Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
                0, UserHandle.USER_CURRENT);

        if (DEBUG) {
            Slog.d(TAG, "Event " + event.brightness + " " + event.packageName);
        }
        synchronized (mEventsLock) {
            mEventsDirty = true;
            mEvents.append(event);
        }
    }

    private void scheduleWriteEvents() {
        if (!mWriteEventsScheduled) {
            mBgHandler.post(mEventsWriter);
            mWriteEventsScheduled = true;
        }
    }

    private void writeEvents() {
        mWriteEventsScheduled = false;
        synchronized (mEventsLock) {
            if (!mEventsDirty) {
                // Nothing to write
                return;
            }

            final AtomicFile writeTo = mInjector.getFile();
            if (writeTo == null) {
                return;
            }
            if (mEvents.isEmpty()) {
                if (writeTo.exists()) {
                    writeTo.delete();
                }
                mEventsDirty = false;
            } else {
                FileOutputStream output = null;
                try {
                    output = writeTo.startWrite();
                    writeEventsLocked(output);
                    writeTo.finishWrite(output);
                    mEventsDirty = false;
                } catch (IOException e) {
                    writeTo.failWrite(output);
                    Slog.e(TAG, "Failed to write change mEvents.", e);
                }
            }
        }
    }

    private void readEvents() {
        synchronized (mEventsLock) {
            // Read might prune events so mark as dirty.
            mEventsDirty = true;
            mEvents.clear();
            final AtomicFile readFrom = mInjector.getFile();
            if (readFrom != null && readFrom.exists()) {
                FileInputStream input = null;
                try {
                    input = readFrom.openRead();
                    readEventsLocked(input);
                } catch (IOException e) {
                    readFrom.delete();
                    Slog.e(TAG, "Failed to read change mEvents.", e);
                } finally {
                    IoUtils.closeQuietly(input);
                }
            }
        }
    }

    @VisibleForTesting
    @GuardedBy("mEventsLock")
    void writeEventsLocked(OutputStream stream) throws IOException {
        XmlSerializer out = new FastXmlSerializer();
        out.setOutput(stream, StandardCharsets.UTF_8.name());
        out.startDocument(null, true);
        out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

        out.startTag(null, TAG_EVENTS);
        BrightnessChangeEvent[] toWrite = mEvents.toArray();
        // Clear events, code below will add back the ones that are still within the time window.
        mEvents.clear();
        if (DEBUG) {
            Slog.d(TAG, "Writing events " + toWrite.length);
        }
        final long timeCutOff = mInjector.currentTimeMillis() - MAX_EVENT_AGE;
        for (int i = 0; i < toWrite.length; ++i) {
            int userSerialNo = mInjector.getUserSerialNumber(mUserManager, toWrite[i].userId);
            if (userSerialNo != -1 && toWrite[i].timeStamp > timeCutOff) {
                mEvents.append(toWrite[i]);
                out.startTag(null, TAG_EVENT);
                out.attribute(null, ATTR_BRIGHTNESS, Integer.toString(toWrite[i].brightness));
                out.attribute(null, ATTR_TIMESTAMP, Long.toString(toWrite[i].timeStamp));
                out.attribute(null, ATTR_PACKAGE_NAME, toWrite[i].packageName);
                out.attribute(null, ATTR_USER, Integer.toString(userSerialNo));
                out.attribute(null, ATTR_BATTERY_LEVEL, Float.toString(toWrite[i].batteryLevel));
                out.attribute(null, ATTR_NIGHT_MODE, Boolean.toString(toWrite[i].nightMode));
                out.attribute(null, ATTR_COLOR_TEMPERATURE, Integer.toString(
                        toWrite[i].colorTemperature));
                out.attribute(null, ATTR_LAST_BRIGHTNESS,
                        Integer.toString(toWrite[i].lastBrightness));
                StringBuilder luxValues = new StringBuilder();
                StringBuilder luxTimestamps = new StringBuilder();
                for (int j = 0; j < toWrite[i].luxValues.length; ++j) {
                    if (j > 0) {
                        luxValues.append(',');
                        luxTimestamps.append(',');
                    }
                    luxValues.append(Float.toString(toWrite[i].luxValues[j]));
                    luxTimestamps.append(Long.toString(toWrite[i].luxTimestamps[j]));
                }
                out.attribute(null, ATTR_LUX, luxValues.toString());
                out.attribute(null, ATTR_LUX_TIMESTAMPS, luxTimestamps.toString());
                out.endTag(null, TAG_EVENT);
            }
        }
        out.endTag(null, TAG_EVENTS);
        out.endDocument();
        stream.flush();
    }

    @VisibleForTesting
    @GuardedBy("mEventsLock")
    void readEventsLocked(InputStream stream) throws IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }
            String tag = parser.getName();
            if (!TAG_EVENTS.equals(tag)) {
                throw new XmlPullParserException(
                        "Events not found in brightness tracker file " + tag);
            }

            final long timeCutOff = mInjector.currentTimeMillis() - MAX_EVENT_AGE;

            parser.next();
            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }
                tag = parser.getName();
                if (TAG_EVENT.equals(tag)) {
                    BrightnessChangeEvent event = new BrightnessChangeEvent();

                    String brightness = parser.getAttributeValue(null, ATTR_BRIGHTNESS);
                    event.brightness = Integer.parseInt(brightness);
                    String timestamp = parser.getAttributeValue(null, ATTR_TIMESTAMP);
                    event.timeStamp = Long.parseLong(timestamp);
                    event.packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
                    String user = parser.getAttributeValue(null, ATTR_USER);
                    event.userId = mInjector.getUserId(mUserManager, Integer.parseInt(user));
                    String batteryLevel = parser.getAttributeValue(null, ATTR_BATTERY_LEVEL);
                    event.batteryLevel = Float.parseFloat(batteryLevel);
                    String nightMode = parser.getAttributeValue(null, ATTR_NIGHT_MODE);
                    event.nightMode = Boolean.parseBoolean(nightMode);
                    String colorTemperature =
                            parser.getAttributeValue(null, ATTR_COLOR_TEMPERATURE);
                    event.colorTemperature = Integer.parseInt(colorTemperature);
                    String lastBrightness = parser.getAttributeValue(null, ATTR_LAST_BRIGHTNESS);
                    event.lastBrightness = Integer.parseInt(lastBrightness);

                    String luxValue = parser.getAttributeValue(null, ATTR_LUX);
                    String luxTimestamp = parser.getAttributeValue(null, ATTR_LUX_TIMESTAMPS);

                    String[] luxValues = luxValue.split(",");
                    String[] luxTimestamps = luxTimestamp.split(",");
                    if (luxValues.length != luxTimestamps.length) {
                        continue;
                    }
                    event.luxValues = new float[luxValues.length];
                    event.luxTimestamps = new long[luxValues.length];
                    for (int i = 0; i < luxValues.length; ++i) {
                        event.luxValues[i] = Float.parseFloat(luxValues[i]);
                        event.luxTimestamps[i] = Long.parseLong(luxTimestamps[i]);
                    }

                    if (DEBUG) {
                        Slog.i(TAG, "Read event " + event.brightness
                                + " " + event.packageName);
                    }

                    if (event.userId != -1 && event.timeStamp > timeCutOff
                            && event.luxValues.length > 0) {
                        mEvents.append(event);
                    }
                }
            }
        } catch (NullPointerException | NumberFormatException | XmlPullParserException
                | IOException e) {
            // Failed to parse something, just start with an empty event log.
            mEvents = new RingBuffer<>(BrightnessChangeEvent.class, MAX_EVENTS);
            Slog.e(TAG, "Failed to parse brightness event", e);
            // Re-throw so we will delete the bad file.
            throw new IOException("failed to parse file", e);
        }
    }

    public void dump(PrintWriter pw) {
        synchronized (mEventsLock) {
            pw.println("BrightnessTracker state:");
            pw.println("  mEvents.size=" + mEvents.size());
            pw.println("  mEventsDirty=" + mEventsDirty);
        }
        synchronized (mDataCollectionLock) {
            pw.println("  mLastSensorReadings.size=" + mLastSensorReadings.size());
        }
    }

    // Not allowed to keep the SensorEvent so used to copy the data we care about.
    private static class LightData {
        public float lux;
        // Time in elapsedRealtimeNanos
        public long timestamp;
    }

    private void recordSensorEvent(SensorEvent event) {
        long horizon = mInjector.elapsedRealtimeNanos() - LUX_EVENT_HORIZON;
        synchronized (mDataCollectionLock) {
            if (DEBUG) {
                Slog.v(TAG, "Sensor event " + event);
            }
            if (!mLastSensorReadings.isEmpty()
                    && event.timestamp < mLastSensorReadings.getLast().timestamp) {
                // Ignore event that came out of order.
                return;
            }
            LightData data = null;
            while (!mLastSensorReadings.isEmpty()
                    && mLastSensorReadings.getFirst().timestamp < horizon) {
                // Remove data that has fallen out of the window.
                data = mLastSensorReadings.removeFirst();
            }
            // We put back the last one we removed so we know how long
            // the first sensor reading was valid for.
            if (data != null) {
                mLastSensorReadings.addFirst(data);
            }

            data = new LightData();
            data.timestamp = event.timestamp;
            data.lux = event.values[0];
            mLastSensorReadings.addLast(data);
        }
    }

    private void batteryLevelChanged(int level, int scale) {
        synchronized (mDataCollectionLock) {
            mLastBatteryLevel = (float) level / (float) scale;
        }
    }

    private final class SensorListener implements SensorEventListener {
        @Override
        public void onSensorChanged(SensorEvent event) {
            recordSensorEvent(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    }

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (DEBUG) {
                Slog.v(TAG, "settings change " + uri);
            }
            // Self change is based on observer passed to notifyObserver, SettingsProvider
            // passes null so no changes are self changes.
            handleBrightnessChanged();
        }
    }

    private final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Slog.d(TAG, "Received " + intent.getAction());
            }
            String action = intent.getAction();
            if (Intent.ACTION_SHUTDOWN.equals(action)) {
                stop();
                scheduleWriteEvents();
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
                if (level != -1 && scale != 0) {
                    batteryLevelChanged(level, scale);
                }
            }
        }
    }

    @VisibleForTesting
    static class Injector {
        public void registerSensorListener(Context context,
                SensorEventListener sensorListener) {
            SensorManager sensorManager = context.getSystemService(SensorManager.class);
            Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            sensorManager.registerListener(sensorListener,
                    lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        public void unregisterSensorListener(Context context, SensorEventListener sensorListener) {
            SensorManager sensorManager = context.getSystemService(SensorManager.class);
            sensorManager.unregisterListener(sensorListener);
        }

        public void registerBrightnessObserver(ContentResolver resolver,
                ContentObserver settingsObserver) {
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS),
                    false, settingsObserver, UserHandle.USER_ALL);
        }

        public void unregisterBrightnessObserver(Context context,
                ContentObserver settingsObserver) {
            context.getContentResolver().unregisterContentObserver(settingsObserver);
        }

        public void registerReceiver(Context context,
                BroadcastReceiver receiver, IntentFilter filter) {
            context.registerReceiver(receiver, filter);
        }

        public void unregisterReceiver(Context context,
                BroadcastReceiver receiver) {
            context.unregisterReceiver(receiver);
        }

        public Handler getBackgroundHandler() {
            return BackgroundThread.getHandler();
        }

        public int getSystemIntForUser(ContentResolver resolver, String setting, int defaultValue,
                int userId) {
            return Settings.System.getIntForUser(resolver, setting, defaultValue, userId);
        }

        public void putSystemIntForUser(ContentResolver resolver, String setting, int value,
                int userId) {
            Settings.System.putIntForUser(resolver, setting, value, userId);
        }

        public int getSecureIntForUser(ContentResolver resolver, String setting, int defaultValue,
                int userId) {
            return Settings.Secure.getIntForUser(resolver, setting, defaultValue, userId);
        }

        public AtomicFile getFile() {
            return new AtomicFile(new File(Environment.getDataSystemDeDirectory(), EVENTS_FILE));
        }

        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        public long elapsedRealtimeNanos() {
            return SystemClock.elapsedRealtimeNanos();
        }

        public int getUserSerialNumber(UserManager userManager, int userId) {
            return userManager.getUserSerialNumber(userId);
        }

        public int getUserId(UserManager userManager, int userSerialNumber) {
            return userManager.getUserHandle(userSerialNumber);
        }

        public ActivityManager.StackInfo getFocusedStack() throws RemoteException {
            return ActivityManager.getService().getFocusedStackInfo();
        }

        public void scheduleIdleJob(Context context) {
            BrightnessIdleJob.scheduleJob(context);
        }

        public void cancelIdleJob(Context context) {
            BrightnessIdleJob.cancelJob(context);
        }
    }
}
