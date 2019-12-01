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
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ParceledListSlice;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.AmbientBrightnessDayStats;
import android.hardware.display.BrightnessChangeEvent;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayedContentSample;
import android.hardware.display.DisplayedContentSamplingAttributes;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.RingBuffer;
import com.android.server.LocalServices;

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
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Class that tracks recent brightness settings changes and stores
 * associated information such as light sensor readings.
 */
public class BrightnessTracker {

    static final String TAG = "BrightnessTracker";
    static final boolean DEBUG = false;
    @VisibleForTesting
    static final boolean ENABLE_COLOR_SAMPLING = false;

    private static final String EVENTS_FILE = "brightness_events.xml";
    private static final String AMBIENT_BRIGHTNESS_STATS_FILE = "ambient_brightness_stats.xml";
    private static final int MAX_EVENTS = 100;
    // Discard events when reading or writing that are older than this.
    private static final long MAX_EVENT_AGE = TimeUnit.DAYS.toMillis(30);
    // Time over which we keep lux sensor readings.
    private static final long LUX_EVENT_HORIZON = TimeUnit.SECONDS.toNanos(10);

    private static final String TAG_EVENTS = "events";
    private static final String TAG_EVENT = "event";
    private static final String ATTR_NITS = "nits";
    private static final String ATTR_TIMESTAMP = "timestamp";
    private static final String ATTR_PACKAGE_NAME = "packageName";
    private static final String ATTR_USER = "user";
    private static final String ATTR_LUX = "lux";
    private static final String ATTR_LUX_TIMESTAMPS = "luxTimestamps";
    private static final String ATTR_BATTERY_LEVEL = "batteryLevel";
    private static final String ATTR_NIGHT_MODE = "nightMode";
    private static final String ATTR_COLOR_TEMPERATURE = "colorTemperature";
    private static final String ATTR_LAST_NITS = "lastNits";
    private static final String ATTR_DEFAULT_CONFIG = "defaultConfig";
    private static final String ATTR_POWER_SAVE = "powerSaveFactor";
    private static final String ATTR_USER_POINT = "userPoint";
    private static final String ATTR_COLOR_SAMPLE_DURATION = "colorSampleDuration";
    private static final String ATTR_COLOR_VALUE_BUCKETS = "colorValueBuckets";

    private static final int MSG_BACKGROUND_START = 0;
    private static final int MSG_BRIGHTNESS_CHANGED = 1;
    private static final int MSG_STOP_SENSOR_LISTENER = 2;
    private static final int MSG_START_SENSOR_LISTENER = 3;

    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    private static final long COLOR_SAMPLE_DURATION = TimeUnit.SECONDS.toSeconds(10);
    // Sample chanel 2 of HSV which is the Value component.
    private static final int COLOR_SAMPLE_COMPONENT_MASK = 0x1 << 2;

    // Lock held while accessing mEvents, is held while writing events to flash.
    private final Object mEventsLock = new Object();
    @GuardedBy("mEventsLock")
    private RingBuffer<BrightnessChangeEvent> mEvents
            = new RingBuffer<>(BrightnessChangeEvent.class, MAX_EVENTS);
    @GuardedBy("mEventsLock")
    private boolean mEventsDirty;

    private volatile boolean mWriteBrightnessTrackerStateScheduled;

    private AmbientBrightnessStatsTracker mAmbientBrightnessStatsTracker;

    private final UserManager mUserManager;
    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final Handler mBgHandler;

    // These members should only be accessed on the mBgHandler thread.
    private BroadcastReceiver mBroadcastReceiver;
    private SensorListener mSensorListener;
    private SettingsObserver mSettingsObserver;
    private DisplayListener mDisplayListener;
    private boolean mSensorRegistered;
    private boolean mColorSamplingEnabled;
    private int mNoFramesToSample;
    private float mFrameRate;
    // End of block of members that should only be accessed on the mBgHandler thread.

    private @UserIdInt int mCurrentUserId = UserHandle.USER_NULL;

    // Lock held while collecting data related to brightness changes.
    private final Object mDataCollectionLock = new Object();
    @GuardedBy("mDataCollectionLock")
    private Deque<LightData> mLastSensorReadings = new ArrayDeque<>();
    @GuardedBy("mDataCollectionLock")
    private float mLastBatteryLevel = Float.NaN;
    @GuardedBy("mDataCollectionLock")
    private float mLastBrightness = -1;
    @GuardedBy("mDataCollectionLock")
    private boolean mStarted;

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
        mBgHandler = new TrackerHandler(mInjector.getBackgroundHandler().getLooper());
        mUserManager = mContext.getSystemService(UserManager.class);
    }

    /**
     * Start listening for brightness slider events
     *
     * @param initialBrightness the initial screen brightness
     */
    public void start(float initialBrightness) {
        if (DEBUG) {
            Slog.d(TAG, "Start");
        }
        mCurrentUserId = ActivityManager.getCurrentUser();
        mBgHandler.obtainMessage(MSG_BACKGROUND_START, (Float) initialBrightness).sendToTarget();
    }

    private void backgroundStart(float initialBrightness) {
        readEvents();
        readAmbientBrightnessStats();

        mSensorListener = new SensorListener();

        mSettingsObserver = new SettingsObserver(mBgHandler);
        mInjector.registerBrightnessModeObserver(mContentResolver, mSettingsObserver);
        startSensorListener();

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SHUTDOWN);
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mBroadcastReceiver = new Receiver();
        mInjector.registerReceiver(mContext, mBroadcastReceiver, intentFilter);

        mInjector.scheduleIdleJob(mContext);
        synchronized (mDataCollectionLock) {
            mLastBrightness = initialBrightness;
            mStarted = true;
        }
        enableColorSampling();
    }

    /** Stop listening for events */
    @VisibleForTesting
    void stop() {
        if (DEBUG) {
            Slog.d(TAG, "Stop");
        }
        mBgHandler.removeMessages(MSG_BACKGROUND_START);
        stopSensorListener();
        mInjector.unregisterSensorListener(mContext, mSensorListener);
        mInjector.unregisterBrightnessModeObserver(mContext, mSettingsObserver);
        mInjector.unregisterReceiver(mContext, mBroadcastReceiver);
        mInjector.cancelIdleJob(mContext);

        synchronized (mDataCollectionLock) {
            mStarted = false;
        }
        disableColorSampling();
    }

    public void onSwitchUser(@UserIdInt int newUserId) {
        if (DEBUG) {
            Slog.d(TAG, "Used id updated from " + mCurrentUserId + " to " + newUserId);
        }
        mCurrentUserId = newUserId;
    }

    /**
     * @param userId userId to fetch data for.
     * @param includePackage if false we will null out BrightnessChangeEvent.packageName
     * @return List of recent {@link BrightnessChangeEvent}s
     */
    public ParceledListSlice<BrightnessChangeEvent> getEvents(int userId, boolean includePackage) {
        BrightnessChangeEvent[] events;
        synchronized (mEventsLock) {
            events = mEvents.toArray();
        }
        int[] profiles = mInjector.getProfileIds(mUserManager, userId);
        Map<Integer, Boolean> toRedact = new HashMap<>();
        for (int i = 0; i < profiles.length; ++i) {
            int profileId = profiles[i];
            // Include slider interactions when a managed profile app is in the
            // foreground but always redact the package name.
            boolean redact = (!includePackage) || profileId != userId;
            toRedact.put(profiles[i], redact);
        }
        ArrayList<BrightnessChangeEvent> out = new ArrayList<>(events.length);
        for (int i = 0; i < events.length; ++i) {
            Boolean redact = toRedact.get(events[i].userId);
            if (redact != null) {
                if (!redact) {
                    out.add(events[i]);
                } else {
                    BrightnessChangeEvent event = new BrightnessChangeEvent((events[i]),
                            /* redactPackage */ true);
                    out.add(event);
                }
            }
        }
        return new ParceledListSlice<>(out);
    }

    public void persistBrightnessTrackerState() {
        scheduleWriteBrightnessTrackerState();
    }

    /**
     * Notify the BrightnessTracker that the user has changed the brightness of the display.
     */
    public void notifyBrightnessChanged(float brightness, boolean userInitiated,
            float powerBrightnessFactor, boolean isUserSetBrightness,
            boolean isDefaultBrightnessConfig) {
        if (DEBUG) {
            Slog.d(TAG, String.format("notifyBrightnessChanged(brightness=%f, userInitiated=%b)",
                        brightness, userInitiated));
        }
        Message m = mBgHandler.obtainMessage(MSG_BRIGHTNESS_CHANGED,
                userInitiated ? 1 : 0, 0 /*unused*/, new BrightnessChangeValues(brightness,
                        powerBrightnessFactor, isUserSetBrightness, isDefaultBrightnessConfig,
                        mInjector.currentTimeMillis()));
        m.sendToTarget();
    }

    private void handleBrightnessChanged(float brightness, boolean userInitiated,
            float powerBrightnessFactor, boolean isUserSetBrightness,
            boolean isDefaultBrightnessConfig, long timestamp) {
        BrightnessChangeEvent.Builder builder;

        synchronized (mDataCollectionLock) {
            if (!mStarted) {
                // Not currently gathering brightness change information
                return;
            }

            float previousBrightness = mLastBrightness;
            mLastBrightness = brightness;

            if (!userInitiated) {
                // We want to record what current brightness is so that we know what the user
                // changed it from, but if it wasn't user initiated then we don't want to record it
                // as a BrightnessChangeEvent.
                return;
            }

            builder = new BrightnessChangeEvent.Builder();
            builder.setBrightness(brightness);
            builder.setTimeStamp(timestamp);
            builder.setPowerBrightnessFactor(powerBrightnessFactor);
            builder.setUserBrightnessPoint(isUserSetBrightness);
            builder.setIsDefaultBrightnessConfig(isDefaultBrightnessConfig);

            final int readingCount = mLastSensorReadings.size();
            if (readingCount == 0) {
                // No sensor data so ignore this.
                return;
            }

            float[] luxValues = new float[readingCount];
            long[] luxTimestamps = new long[readingCount];

            int pos = 0;

            // Convert sensor timestamp in elapsed time nanos to current time millis.
            long currentTimeMillis = mInjector.currentTimeMillis();
            long elapsedTimeNanos = mInjector.elapsedRealtimeNanos();
            for (LightData reading : mLastSensorReadings) {
                luxValues[pos] = reading.lux;
                luxTimestamps[pos] = currentTimeMillis -
                        TimeUnit.NANOSECONDS.toMillis(elapsedTimeNanos - reading.timestamp);
                ++pos;
            }
            builder.setLuxValues(luxValues);
            builder.setLuxTimestamps(luxTimestamps);

            builder.setBatteryLevel(mLastBatteryLevel);
            builder.setLastBrightness(previousBrightness);
        }

        try {
            final ActivityManager.StackInfo focusedStack = mInjector.getFocusedStack();
            if (focusedStack != null && focusedStack.topActivity != null) {
                builder.setUserId(focusedStack.userId);
                builder.setPackageName(focusedStack.topActivity.getPackageName());
            } else {
                // Ignore the event because we can't determine user / package.
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring event due to null focusedStack.");
                }
                return;
            }
        } catch (RemoteException e) {
            // Really shouldn't be possible.
            return;
        }

        builder.setNightMode(mInjector.isNightDisplayActivated(mContext));
        builder.setColorTemperature(mInjector.getNightDisplayColorTemperature(mContext));

        if (mColorSamplingEnabled) {
            DisplayedContentSample sample = mInjector.sampleColor(mNoFramesToSample);
            if (sample != null && sample.getSampleComponent(
                    DisplayedContentSample.ColorComponent.CHANNEL2) != null) {
                float numMillis = (sample.getNumFrames() / mFrameRate) * 1000.0f;
                builder.setColorValues(
                        sample.getSampleComponent(DisplayedContentSample.ColorComponent.CHANNEL2),
                        Math.round(numMillis));
            }
        }

        BrightnessChangeEvent event = builder.build();
        if (DEBUG) {
            Slog.d(TAG, "Event " + event.brightness + " " + event.packageName);
        }
        synchronized (mEventsLock) {
            mEventsDirty = true;
            mEvents.append(event);
        }
    }

    private void startSensorListener() {
        if (!mSensorRegistered
                && mInjector.isInteractive(mContext)
                && mInjector.isBrightnessModeAutomatic(mContentResolver)) {
            mAmbientBrightnessStatsTracker.start();
            mSensorRegistered = true;
            mInjector.registerSensorListener(mContext, mSensorListener,
                    mInjector.getBackgroundHandler());
        }
    }

    private void stopSensorListener() {
        if (mSensorRegistered) {
            mAmbientBrightnessStatsTracker.stop();
            mInjector.unregisterSensorListener(mContext, mSensorListener);
            mSensorRegistered = false;
        }
    }

    private void scheduleWriteBrightnessTrackerState() {
        if (!mWriteBrightnessTrackerStateScheduled) {
            mBgHandler.post(() -> {
                mWriteBrightnessTrackerStateScheduled = false;
                writeEvents();
                writeAmbientBrightnessStats();
            });
            mWriteBrightnessTrackerStateScheduled = true;
        }
    }

    private void writeEvents() {
        synchronized (mEventsLock) {
            if (!mEventsDirty) {
                // Nothing to write
                return;
            }

            final AtomicFile writeTo = mInjector.getFile(EVENTS_FILE);
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

    private void writeAmbientBrightnessStats() {
        final AtomicFile writeTo = mInjector.getFile(AMBIENT_BRIGHTNESS_STATS_FILE);
        if (writeTo == null) {
            return;
        }
        FileOutputStream output = null;
        try {
            output = writeTo.startWrite();
            mAmbientBrightnessStatsTracker.writeStats(output);
            writeTo.finishWrite(output);
        } catch (IOException e) {
            writeTo.failWrite(output);
            Slog.e(TAG, "Failed to write ambient brightness stats.", e);
        }
    }

    private void readEvents() {
        synchronized (mEventsLock) {
            // Read might prune events so mark as dirty.
            mEventsDirty = true;
            mEvents.clear();
            final AtomicFile readFrom = mInjector.getFile(EVENTS_FILE);
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

    private void readAmbientBrightnessStats() {
        mAmbientBrightnessStatsTracker = new AmbientBrightnessStatsTracker(mUserManager, null);
        final AtomicFile readFrom = mInjector.getFile(AMBIENT_BRIGHTNESS_STATS_FILE);
        if (readFrom != null && readFrom.exists()) {
            FileInputStream input = null;
            try {
                input = readFrom.openRead();
                mAmbientBrightnessStatsTracker.readStats(input);
            } catch (IOException e) {
                readFrom.delete();
                Slog.e(TAG, "Failed to read ambient brightness stats.", e);
            } finally {
                IoUtils.closeQuietly(input);
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
                out.attribute(null, ATTR_NITS, Float.toString(toWrite[i].brightness));
                out.attribute(null, ATTR_TIMESTAMP, Long.toString(toWrite[i].timeStamp));
                out.attribute(null, ATTR_PACKAGE_NAME, toWrite[i].packageName);
                out.attribute(null, ATTR_USER, Integer.toString(userSerialNo));
                out.attribute(null, ATTR_BATTERY_LEVEL, Float.toString(toWrite[i].batteryLevel));
                out.attribute(null, ATTR_NIGHT_MODE, Boolean.toString(toWrite[i].nightMode));
                out.attribute(null, ATTR_COLOR_TEMPERATURE, Integer.toString(
                        toWrite[i].colorTemperature));
                out.attribute(null, ATTR_LAST_NITS,
                        Float.toString(toWrite[i].lastBrightness));
                out.attribute(null, ATTR_DEFAULT_CONFIG,
                        Boolean.toString(toWrite[i].isDefaultBrightnessConfig));
                out.attribute(null, ATTR_POWER_SAVE,
                        Float.toString(toWrite[i].powerBrightnessFactor));
                out.attribute(null, ATTR_USER_POINT,
                        Boolean.toString(toWrite[i].isUserSetBrightness));
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
                if (toWrite[i].colorValueBuckets != null
                        && toWrite[i].colorValueBuckets.length > 0) {
                    out.attribute(null, ATTR_COLOR_SAMPLE_DURATION,
                            Long.toString(toWrite[i].colorSampleDuration));
                    StringBuilder buckets = new StringBuilder();
                    for (int j = 0; j < toWrite[i].colorValueBuckets.length; ++j) {
                        if (j > 0) {
                            buckets.append(',');
                        }
                        buckets.append(Long.toString(toWrite[i].colorValueBuckets[j]));
                    }
                    out.attribute(null, ATTR_COLOR_VALUE_BUCKETS, buckets.toString());
                }
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
                    BrightnessChangeEvent.Builder builder = new BrightnessChangeEvent.Builder();

                    String brightness = parser.getAttributeValue(null, ATTR_NITS);
                    builder.setBrightness(Float.parseFloat(brightness));
                    String timestamp = parser.getAttributeValue(null, ATTR_TIMESTAMP);
                    builder.setTimeStamp(Long.parseLong(timestamp));
                    builder.setPackageName(parser.getAttributeValue(null, ATTR_PACKAGE_NAME));
                    String user = parser.getAttributeValue(null, ATTR_USER);
                    builder.setUserId(mInjector.getUserId(mUserManager, Integer.parseInt(user)));
                    String batteryLevel = parser.getAttributeValue(null, ATTR_BATTERY_LEVEL);
                    builder.setBatteryLevel(Float.parseFloat(batteryLevel));
                    String nightMode = parser.getAttributeValue(null, ATTR_NIGHT_MODE);
                    builder.setNightMode(Boolean.parseBoolean(nightMode));
                    String colorTemperature =
                            parser.getAttributeValue(null, ATTR_COLOR_TEMPERATURE);
                    builder.setColorTemperature(Integer.parseInt(colorTemperature));
                    String lastBrightness = parser.getAttributeValue(null, ATTR_LAST_NITS);
                    builder.setLastBrightness(Float.parseFloat(lastBrightness));

                    String luxValue = parser.getAttributeValue(null, ATTR_LUX);
                    String luxTimestamp = parser.getAttributeValue(null, ATTR_LUX_TIMESTAMPS);

                    String[] luxValuesStrings = luxValue.split(",");
                    String[] luxTimestampsStrings = luxTimestamp.split(",");
                    if (luxValuesStrings.length != luxTimestampsStrings.length) {
                        continue;
                    }
                    float[] luxValues = new float[luxValuesStrings.length];
                    long[] luxTimestamps = new long[luxValuesStrings.length];
                    for (int i = 0; i < luxValues.length; ++i) {
                        luxValues[i] = Float.parseFloat(luxValuesStrings[i]);
                        luxTimestamps[i] = Long.parseLong(luxTimestampsStrings[i]);
                    }
                    builder.setLuxValues(luxValues);
                    builder.setLuxTimestamps(luxTimestamps);

                    String defaultConfig = parser.getAttributeValue(null, ATTR_DEFAULT_CONFIG);
                    if (defaultConfig != null) {
                        builder.setIsDefaultBrightnessConfig(Boolean.parseBoolean(defaultConfig));
                    }
                    String powerSave = parser.getAttributeValue(null, ATTR_POWER_SAVE);
                    if (powerSave != null) {
                        builder.setPowerBrightnessFactor(Float.parseFloat(powerSave));
                    } else {
                        builder.setPowerBrightnessFactor(1.0f);
                    }
                    String userPoint = parser.getAttributeValue(null, ATTR_USER_POINT);
                    if (userPoint != null) {
                        builder.setUserBrightnessPoint(Boolean.parseBoolean(userPoint));
                    }

                    String colorSampleDurationString =
                            parser.getAttributeValue(null, ATTR_COLOR_SAMPLE_DURATION);
                    String colorValueBucketsString =
                            parser.getAttributeValue(null, ATTR_COLOR_VALUE_BUCKETS);
                    if (colorSampleDurationString != null && colorValueBucketsString != null) {
                        long colorSampleDuration = Long.parseLong(colorSampleDurationString);
                        String[] buckets = colorValueBucketsString.split(",");
                        long[] bucketValues = new long[buckets.length];
                        for (int i = 0; i < bucketValues.length; ++i) {
                            bucketValues[i] = Long.parseLong(buckets[i]);
                        }
                        builder.setColorValues(bucketValues, colorSampleDuration);
                    }

                    BrightnessChangeEvent event = builder.build();
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

    public void dump(final PrintWriter pw) {
        pw.println("BrightnessTracker state:");
        synchronized (mDataCollectionLock) {
            pw.println("  mStarted=" + mStarted);
            pw.println("  mLastBatteryLevel=" + mLastBatteryLevel);
            pw.println("  mLastBrightness=" + mLastBrightness);
            pw.println("  mLastSensorReadings.size=" + mLastSensorReadings.size());
            if (!mLastSensorReadings.isEmpty()) {
                pw.println("  mLastSensorReadings time span "
                        + mLastSensorReadings.peekFirst().timestamp + "->"
                        + mLastSensorReadings.peekLast().timestamp);
            }
        }
        synchronized (mEventsLock) {
            pw.println("  mEventsDirty=" + mEventsDirty);
            pw.println("  mEvents.size=" + mEvents.size());
            BrightnessChangeEvent[] events = mEvents.toArray();
            for (int i = 0; i < events.length; ++i) {
                pw.print("    " + FORMAT.format(new Date(events[i].timeStamp)));
                pw.print(", userId=" + events[i].userId);
                pw.print(", " + events[i].lastBrightness + "->" + events[i].brightness);
                pw.print(", isUserSetBrightness=" + events[i].isUserSetBrightness);
                pw.print(", powerBrightnessFactor=" + events[i].powerBrightnessFactor);
                pw.print(", isDefaultBrightnessConfig=" + events[i].isDefaultBrightnessConfig);
                pw.print(" {");
                for (int j = 0; j < events[i].luxValues.length; ++j){
                    if (j != 0) {
                        pw.print(", ");
                    }
                    pw.print("(" + events[i].luxValues[j] + "," + events[i].luxTimestamps[j] + ")");
                }
                pw.println("}");
            }
        }
        pw.println("  mWriteBrightnessTrackerStateScheduled="
                + mWriteBrightnessTrackerStateScheduled);
        mBgHandler.runWithScissors(() -> dumpLocal(pw), 1000);
        if (mAmbientBrightnessStatsTracker != null) {
            pw.println();
            mAmbientBrightnessStatsTracker.dump(pw);
        }
    }

    private void dumpLocal(PrintWriter pw) {
        pw.println("  mSensorRegistered=" + mSensorRegistered);
        pw.println("  mColorSamplingEnabled=" + mColorSamplingEnabled);
        pw.println("  mNoFramesToSample=" + mNoFramesToSample);
        pw.println("  mFrameRate=" + mFrameRate);
    }

    private void enableColorSampling() {
        if (!ENABLE_COLOR_SAMPLING
                || !mInjector.isBrightnessModeAutomatic(mContentResolver)
                || !mInjector.isInteractive(mContext)
                || mColorSamplingEnabled) {
            return;
        }

        mFrameRate = mInjector.getFrameRate(mContext);
        if (mFrameRate <= 0) {
            Slog.wtf(TAG, "Default display has a zero or negative framerate.");
            return;
        }
        mNoFramesToSample = (int) (mFrameRate * COLOR_SAMPLE_DURATION);

        DisplayedContentSamplingAttributes attributes = mInjector.getSamplingAttributes();
        if (DEBUG && attributes != null) {
            Slog.d(TAG, "Color sampling"
                    + " mask=0x" + Integer.toHexString(attributes.getComponentMask())
                    + " dataSpace=0x" + Integer.toHexString(attributes.getDataspace())
                    + " pixelFormat=0x" + Integer.toHexString(attributes.getPixelFormat()));
        }
        // Do we support sampling the Value component of HSV
        if (attributes != null && attributes.getPixelFormat() == PixelFormat.HSV_888
                && (attributes.getComponentMask() & COLOR_SAMPLE_COMPONENT_MASK) != 0) {

            mColorSamplingEnabled = mInjector.enableColorSampling(/* enable= */true,
                    mNoFramesToSample);
            if (DEBUG) {
                Slog.i(TAG, "turning on color sampling for "
                        + mNoFramesToSample + " frames, success=" + mColorSamplingEnabled);
            }
        }
        if (mColorSamplingEnabled && mDisplayListener == null) {
            mDisplayListener = new DisplayListener();
            mInjector.registerDisplayListener(mContext, mDisplayListener, mBgHandler);
        }
    }

    private void disableColorSampling() {
        if (!mColorSamplingEnabled) {
            return;
        }
        mInjector.enableColorSampling(/* enable= */ false, /* noFrames= */ 0);
        mColorSamplingEnabled = false;
        if (mDisplayListener != null) {
            mInjector.unRegisterDisplayListener(mContext, mDisplayListener);
            mDisplayListener = null;
        }
        if (DEBUG) {
            Slog.i(TAG, "turning off color sampling");
        }
    }

    private void updateColorSampling() {
        if (!mColorSamplingEnabled) {
            return;
        }
        float frameRate = mInjector.getFrameRate(mContext);
        if (frameRate != mFrameRate) {
            disableColorSampling();
            enableColorSampling();
        }
    }

    public ParceledListSlice<AmbientBrightnessDayStats> getAmbientBrightnessStats(int userId) {
        if (mAmbientBrightnessStatsTracker != null) {
            ArrayList<AmbientBrightnessDayStats> stats =
                    mAmbientBrightnessStatsTracker.getUserStats(userId);
            if (stats != null) {
                return new ParceledListSlice<>(stats);
            }
        }
        return ParceledListSlice.emptyList();
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

    private void recordAmbientBrightnessStats(SensorEvent event) {
        mAmbientBrightnessStatsTracker.add(mCurrentUserId, event.values[0]);
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
            recordAmbientBrightnessStats(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    }

    private final class DisplayListener implements DisplayManager.DisplayListener {

        @Override
        public void onDisplayAdded(int displayId) {
            // Ignore
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            // Ignore
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                updateColorSampling();
            }
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
            if (mInjector.isBrightnessModeAutomatic(mContentResolver)) {
                mBgHandler.obtainMessage(MSG_START_SENSOR_LISTENER).sendToTarget();
            } else {
                mBgHandler.obtainMessage(MSG_STOP_SENSOR_LISTENER).sendToTarget();
            }
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
                scheduleWriteBrightnessTrackerState();
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
                if (level != -1 && scale != 0) {
                    batteryLevelChanged(level, scale);
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mBgHandler.obtainMessage(MSG_STOP_SENSOR_LISTENER).sendToTarget();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mBgHandler.obtainMessage(MSG_START_SENSOR_LISTENER).sendToTarget();
            }
        }
    }

    private final class TrackerHandler extends Handler {
        public TrackerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_BACKGROUND_START:
                    backgroundStart((float)msg.obj /*initial brightness*/);
                    break;
                case MSG_BRIGHTNESS_CHANGED:
                    BrightnessChangeValues values = (BrightnessChangeValues) msg.obj;
                    boolean userInitiatedChange = (msg.arg1 == 1);
                    handleBrightnessChanged(values.brightness, userInitiatedChange,
                            values.powerBrightnessFactor, values.isUserSetBrightness,
                            values.isDefaultBrightnessConfig, values.timestamp);
                    break;
                case MSG_START_SENSOR_LISTENER:
                    startSensorListener();
                    enableColorSampling();
                    break;
                case MSG_STOP_SENSOR_LISTENER:
                    stopSensorListener();
                    disableColorSampling();
                    break;
            }
        }
    }

    private static class BrightnessChangeValues {
        final float brightness;
        final float powerBrightnessFactor;
        final boolean isUserSetBrightness;
        final boolean isDefaultBrightnessConfig;
        final long timestamp;

        BrightnessChangeValues(float brightness, float powerBrightnessFactor,
                boolean isUserSetBrightness, boolean isDefaultBrightnessConfig,
                long timestamp) {
            this.brightness = brightness;
            this.powerBrightnessFactor = powerBrightnessFactor;
            this.isUserSetBrightness = isUserSetBrightness;
            this.isDefaultBrightnessConfig = isDefaultBrightnessConfig;
            this.timestamp = timestamp;
        }
    }

    @VisibleForTesting
    static class Injector {
        public void registerSensorListener(Context context,
                SensorEventListener sensorListener, Handler handler) {
            SensorManager sensorManager = context.getSystemService(SensorManager.class);
            Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            sensorManager.registerListener(sensorListener,
                    lightSensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
        }

        public void unregisterSensorListener(Context context, SensorEventListener sensorListener) {
            SensorManager sensorManager = context.getSystemService(SensorManager.class);
            sensorManager.unregisterListener(sensorListener);
        }

        public void registerBrightnessModeObserver(ContentResolver resolver,
                ContentObserver settingsObserver) {
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, settingsObserver, UserHandle.USER_ALL);
        }

        public void unregisterBrightnessModeObserver(Context context,
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

        public boolean isBrightnessModeAutomatic(ContentResolver resolver) {
            return Settings.System.getIntForUser(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        }

        public int getSecureIntForUser(ContentResolver resolver, String setting, int defaultValue,
                int userId) {
            return Settings.Secure.getIntForUser(resolver, setting, defaultValue, userId);
        }

        public AtomicFile getFile(String filename) {
            return new AtomicFile(new File(Environment.getDataSystemDeDirectory(), filename));
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

        public int[] getProfileIds(UserManager userManager, int userId) {
            if (userManager != null) {
                return userManager.getProfileIds(userId, false);
            } else {
                return new int[]{userId};
            }
        }

        public ActivityManager.StackInfo getFocusedStack() throws RemoteException {
            return ActivityTaskManager.getService().getFocusedStackInfo();
        }

        public void scheduleIdleJob(Context context) {
            BrightnessIdleJob.scheduleJob(context);
        }

        public void cancelIdleJob(Context context) {
            BrightnessIdleJob.cancelJob(context);
        }

        public boolean isInteractive(Context context) {
            return context.getSystemService(PowerManager.class).isInteractive();
        }

        public int getNightDisplayColorTemperature(Context context) {
            return context.getSystemService(ColorDisplayManager.class)
                    .getNightDisplayColorTemperature();
        }

        public boolean isNightDisplayActivated(Context context) {
            return context.getSystemService(ColorDisplayManager.class).isNightDisplayActivated();
        }

        public DisplayedContentSample sampleColor(int noFramesToSample) {
            final DisplayManagerInternal displayManagerInternal =
                    LocalServices.getService(DisplayManagerInternal.class);
            return displayManagerInternal.getDisplayedContentSample(
                   Display.DEFAULT_DISPLAY, noFramesToSample, 0);
        }

        public float getFrameRate(Context context) {
            final DisplayManager displayManager = context.getSystemService(DisplayManager.class);
            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            return display.getRefreshRate();
        }

        public DisplayedContentSamplingAttributes getSamplingAttributes() {
            final DisplayManagerInternal displayManagerInternal =
                    LocalServices.getService(DisplayManagerInternal.class);
            return displayManagerInternal.getDisplayedContentSamplingAttributes(
                    Display.DEFAULT_DISPLAY);
        }

        public boolean enableColorSampling(boolean enable, int noFrames) {
            final DisplayManagerInternal displayManagerInternal =
                    LocalServices.getService(DisplayManagerInternal.class);
            return displayManagerInternal.setDisplayedContentSamplingEnabled(
                    Display.DEFAULT_DISPLAY, enable, COLOR_SAMPLE_COMPONENT_MASK, noFrames);
        }

        public void registerDisplayListener(Context context,
                DisplayManager.DisplayListener listener, Handler handler) {
            final DisplayManager displayManager = context.getSystemService(DisplayManager.class);
            displayManager.registerDisplayListener(listener, handler);
        }

        public void unRegisterDisplayListener(Context context,
                DisplayManager.DisplayListener listener) {
            final DisplayManager displayManager = context.getSystemService(DisplayManager.class);
            displayManager.unregisterDisplayListener(listener);
        }
    }
}
