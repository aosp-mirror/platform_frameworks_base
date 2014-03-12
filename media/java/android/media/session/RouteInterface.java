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
package android.media.session;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.ResultReceiver;

/**
 * Routes can support multiple interfaces for MediaSessions to interact with. To
 * add a standard interface you should implement that interface's RouteInterface
 * Stub and register it with the session. The set of supported commands is
 * dependent on the specific interface's implementation.
 * <p>
 * A MediaInterface can be registered by calling TODO. Once added an interface
 * will be used by Sessions to decide how they communicate with a session and
 * cannot be removed, so all interfaces that you plan to support should be added
 * when the route is created.
 *
 * @see RouteTransportControls
 */
public final class RouteInterface {
    private static final String TAG = "MediaInterface";

    private static final String KEY_RESULT = "result";

    private final MediaController mController;
    private final String mIface;

    /**
     * @hide
     */
    RouteInterface(MediaController controller, String iface) {
        mController = controller;
        mIface = iface;
    }

    public void sendCommand(String command, Bundle params, ResultReceiver cb) {
        // TODO
    }

    public void addListener(EventListener listener) {
        addListener(listener, null);
    }

    public void addListener(EventListener listener, Handler handler) {
        // TODO See MediaController for add/remove pattern
    }

    public void removeListener(EventListener listener) {
        // TODO
    }

    // TODO decide on list of supported types
    private static Bundle writeResultToBundle(Object v) {
        Bundle b = new Bundle();
        if (v == null) {
            // Don't send anything if null
        } else if (v instanceof String) {
            b.putString(KEY_RESULT, (String) v);
        } else if (v instanceof Integer) {
            b.putInt(KEY_RESULT, (Integer) v);
        } else if (v instanceof Bundle) {
            // Must be before Parcelable
            b.putBundle(KEY_RESULT, (Bundle) v);
        } else if (v instanceof Parcelable) {
            b.putParcelable(KEY_RESULT, (Parcelable) v);
        } else if (v instanceof Short) {
            b.putShort(KEY_RESULT, (Short) v);
        } else if (v instanceof Long) {
            b.putLong(KEY_RESULT, (Long) v);
        } else if (v instanceof Float) {
            b.putFloat(KEY_RESULT, (Float) v);
        } else if (v instanceof Double) {
            b.putDouble(KEY_RESULT, (Double) v);
        } else if (v instanceof Boolean) {
            b.putBoolean(KEY_RESULT, (Boolean) v);
        } else if (v instanceof CharSequence) {
            // Must be after String
            b.putCharSequence(KEY_RESULT, (CharSequence) v);
        } else if (v instanceof boolean[]) {
            b.putBooleanArray(KEY_RESULT, (boolean[]) v);
        } else if (v instanceof byte[]) {
            b.putByteArray(KEY_RESULT, (byte[]) v);
        } else if (v instanceof String[]) {
            b.putStringArray(KEY_RESULT, (String[]) v);
        } else if (v instanceof CharSequence[]) {
            // Must be after String[] and before Object[]
            b.putCharSequenceArray(KEY_RESULT, (CharSequence[]) v);
        } else if (v instanceof IBinder) {
            b.putBinder(KEY_RESULT, (IBinder) v);
        } else if (v instanceof Parcelable[]) {
            b.putParcelableArray(KEY_RESULT, (Parcelable[]) v);
        } else if (v instanceof int[]) {
            b.putIntArray(KEY_RESULT, (int[]) v);
        } else if (v instanceof long[]) {
            b.putLongArray(KEY_RESULT, (long[]) v);
        } else if (v instanceof Byte) {
            b.putByte(KEY_RESULT, (Byte) v);
        }
        return b;
    }

    public abstract static class Stub {

        /**
         * The name of an interface should be a fully qualified name to prevent
         * namespace collisions. Example: "com.myproject.MyPlaybackInterface"
         *
         * @return The name of this interface
         */
        public abstract String getName();

        /**
         * This is called when a command is received that matches the interface
         * you registered. Commands can come from any app with a MediaController
         * reference to the session.
         *
         * @see MediaController
         * @see MediaSession
         * @param command The command or method to invoke.
         * @param args Any args that were included with the command. May be
         *            null.
         * @param cb The callback provided to send a response on. May be null.
         */
        public abstract void onCommand(String command, Bundle args, ResultReceiver cb);

        public final void sendEvent(MediaSession session, String event, Bundle extras) {
            // TODO
        }
    }

    /**
     * An EventListener can be registered by an app with TODO to handle events
     * sent by the session on a specific interface.
     */
    public static abstract class EventListener {
        /**
         * This is called when an event is received from the interface. Events
         * are sent by the session owner and will be delivered to all
         * controllers that are listening to the interface.
         *
         * @param event The event that occurred.
         * @param args Any extras that were included with the event. May be
         *            null.
         */
        public abstract void onEvent(String event, Bundle args);
    }

    private static final class EventHandler extends Handler {

        private final RouteInterface.EventListener mListener;

        public EventHandler(Looper looper, RouteInterface.EventListener cb) {
            super(looper, null, true);
            mListener = cb;
        }

        @Override
        public void handleMessage(Message msg) {
            mListener.onEvent((String) msg.obj, msg.getData());
        }

        public void postEvent(String event, Bundle args) {
            Message msg = obtainMessage(0, event);
            msg.setData(args);
            msg.sendToTarget();
        }
    }
}
