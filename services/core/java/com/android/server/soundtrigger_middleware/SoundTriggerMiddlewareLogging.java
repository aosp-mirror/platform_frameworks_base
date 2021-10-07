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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.permission.Identity;
import android.media.permission.IdentityContext;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Objects;

/**
 * An ISoundTriggerMiddlewareService decorator, which adds logging of all API calls (and
 * callbacks).
 *
 * All API methods should follow this structure:
 * <pre><code>
 * @Override
 * public @NonNull ReturnType someMethod(ArgType1 arg1, ArgType2 arg2) throws ExceptionType {
 *     try {
 *         ReturnType result = mDelegate.someMethod(arg1, arg2);
 *         logReturn("someMethod", result, arg1, arg2);
 *         return result;
 *     } catch (Exception e) {
 *         logException("someMethod", e, arg1, arg2);
 *         throw e;
 *     }
 * }
 * </code></pre>
 * The actual handling of these events is then done inside of {@link #logReturnWithObject(Object,
 * String, Object, Object[])}, {@link #logVoidReturnWithObject(Object, String, Object[])} and {@link
 * #logExceptionWithObject(Object, String, Exception, Object[])}.
 */
public class SoundTriggerMiddlewareLogging implements ISoundTriggerMiddlewareInternal, Dumpable {
    private static final String TAG = "SoundTriggerMiddlewareLogging";
    private final @NonNull ISoundTriggerMiddlewareInternal mDelegate;

    public SoundTriggerMiddlewareLogging(@NonNull ISoundTriggerMiddlewareInternal delegate) {
        mDelegate = delegate;
    }

    @Override
    public @NonNull
    SoundTriggerModuleDescriptor[] listModules() {
        try {
            SoundTriggerModuleDescriptor[] result = mDelegate.listModules();
            logReturn("listModules", result);
            return result;
        } catch (Exception e) {
            logException("listModules", e);
            throw e;
        }
    }

    @Override
    public @NonNull
    ISoundTriggerModule attach(int handle, ISoundTriggerCallback callback) {
        try {
            ModuleLogging result = new ModuleLogging(callback);
            result.attach(mDelegate.attach(handle, result.getCallbackWrapper()));
            logReturn("attach", result, handle, callback);
            return result;
        } catch (Exception e) {
            logException("attach", e, handle, callback);
            throw e;
        }
    }

    @Override
    public void setCaptureState(boolean active) throws RemoteException {
        try {
            mDelegate.setCaptureState(active);
            logVoidReturn("setCaptureState", active);
        } catch (Exception e) {
            logException("setCaptureState", e, active);
            throw e;
        }
    }

    @Override
    public IBinder asBinder() {
        throw new UnsupportedOperationException(
                "This implementation is not inteded to be used directly with Binder.");
    }

    // Override toString() in order to have the delegate's ID in it.
    @Override
    public String toString() {
        return mDelegate.toString();
    }

    private void logException(String methodName, Exception ex, Object... args) {
        logExceptionWithObject(this, IdentityContext.get(), methodName, ex, args);
    }

    private void logReturn(String methodName, Object retVal, Object... args) {
        logReturnWithObject(this, IdentityContext.get(), methodName, retVal, args);
    }

    private void logVoidReturn(String methodName, Object... args) {
        logVoidReturnWithObject(this, IdentityContext.get(), methodName, args);
    }

    private class ModuleLogging implements ISoundTriggerModule {
        private ISoundTriggerModule mDelegate;
        private final @NonNull CallbackLogging mCallbackWrapper;
        private final @NonNull Identity mOriginatorIdentity;

        ModuleLogging(@NonNull ISoundTriggerCallback callback) {
            mCallbackWrapper = new CallbackLogging(callback);
            mOriginatorIdentity = IdentityContext.getNonNull();
        }

        void attach(@NonNull ISoundTriggerModule delegate) {
            mDelegate = delegate;
        }

        ISoundTriggerCallback getCallbackWrapper() {
            return mCallbackWrapper;
        }

        @Override
        public int loadModel(SoundModel model) throws RemoteException {
            try {
                int result = mDelegate.loadModel(model);
                logReturn("loadModel", result, model);
                return result;
            } catch (Exception e) {
                logException("loadModel", e, model);
                throw e;
            }
        }

        @Override
        public int loadPhraseModel(PhraseSoundModel model) throws RemoteException {
            try {
                int result = mDelegate.loadPhraseModel(model);
                logReturn("loadPhraseModel", result, model);
                return result;
            } catch (Exception e) {
                logException("loadPhraseModel", e, model);
                throw e;
            }
        }

        @Override
        public void unloadModel(int modelHandle) throws RemoteException {
            try {
                mDelegate.unloadModel(modelHandle);
                logVoidReturn("unloadModel", modelHandle);
            } catch (Exception e) {
                logException("unloadModel", e, modelHandle);
                throw e;
            }
        }

        @Override
        public void startRecognition(int modelHandle, RecognitionConfig config)
                throws RemoteException {
            try {
                mDelegate.startRecognition(modelHandle, config);
                logVoidReturn("startRecognition", modelHandle, config);
            } catch (Exception e) {
                logException("startRecognition", e, modelHandle, config);
                throw e;
            }
        }

        @Override
        public void stopRecognition(int modelHandle) throws RemoteException {
            try {
                mDelegate.stopRecognition(modelHandle);
                logVoidReturn("stopRecognition", modelHandle);
            } catch (Exception e) {
                logException("stopRecognition", e, modelHandle);
                throw e;
            }
        }

        @Override
        public void forceRecognitionEvent(int modelHandle) throws RemoteException {
            try {
                mDelegate.forceRecognitionEvent(modelHandle);
                logVoidReturn("forceRecognitionEvent", modelHandle);
            } catch (Exception e) {
                logException("forceRecognitionEvent", e, modelHandle);
                throw e;
            }
        }

        @Override
        public void setModelParameter(int modelHandle, int modelParam, int value)
                throws RemoteException {
            try {
                mDelegate.setModelParameter(modelHandle, modelParam, value);
                logVoidReturn("setModelParameter", modelHandle, modelParam, value);
            } catch (Exception e) {
                logException("setModelParameter", e, modelHandle, modelParam, value);
                throw e;
            }
        }

        @Override
        public int getModelParameter(int modelHandle, int modelParam) throws RemoteException {
            try {
                int result = mDelegate.getModelParameter(modelHandle, modelParam);
                logReturn("getModelParameter", result, modelHandle, modelParam);
                return result;
            } catch (Exception e) {
                logException("getModelParameter", e, modelHandle, modelParam);
                throw e;
            }
        }

        @Override
        public ModelParameterRange queryModelParameterSupport(int modelHandle, int modelParam)
                throws RemoteException {
            try {
                ModelParameterRange result = mDelegate.queryModelParameterSupport(modelHandle,
                        modelParam);
                logReturn("queryModelParameterSupport", result, modelHandle, modelParam);
                return result;
            } catch (Exception e) {
                logException("queryModelParameterSupport", e, modelHandle, modelParam);
                throw e;
            }
        }

        @Override
        public void detach() throws RemoteException {
            try {
                mDelegate.detach();
                logVoidReturn("detach");
            } catch (Exception e) {
                logException("detach", e);
                throw e;
            }
        }

        @Override
        public IBinder asBinder() {
            return mDelegate.asBinder();
        }

        // Override toString() in order to have the delegate's ID in it.
        @Override
        public String toString() {
            return Objects.toString(mDelegate);
        }

        private void logException(String methodName, Exception ex, Object... args) {
            logExceptionWithObject(this, mOriginatorIdentity, methodName, ex, args);
        }

        private void logReturn(String methodName, Object retVal, Object... args) {
            logReturnWithObject(this, mOriginatorIdentity, methodName, retVal, args);
        }

        private void logVoidReturn(String methodName, Object... args) {
            logVoidReturnWithObject(this, mOriginatorIdentity, methodName, args);
        }

        private class CallbackLogging implements ISoundTriggerCallback {
            private final ISoundTriggerCallback mCallbackDelegate;

            private CallbackLogging(ISoundTriggerCallback delegate) {
                mCallbackDelegate = delegate;
            }

            @Override
            public void onRecognition(int modelHandle, RecognitionEvent event)
                    throws RemoteException {
                try {
                    mCallbackDelegate.onRecognition(modelHandle, event);
                    logVoidReturn("onRecognition", modelHandle, event);
                } catch (Exception e) {
                    logException("onRecognition", e, modelHandle, event);
                    throw e;
                }
            }

            @Override
            public void onPhraseRecognition(int modelHandle, PhraseRecognitionEvent event)
                    throws RemoteException {
                try {
                    mCallbackDelegate.onPhraseRecognition(modelHandle, event);
                    logVoidReturn("onPhraseRecognition", modelHandle, event);
                } catch (Exception e) {
                    logException("onPhraseRecognition", e, modelHandle, event);
                    throw e;
                }
            }

            @Override
            public void onRecognitionAvailabilityChange(boolean available) throws RemoteException {
                try {
                    mCallbackDelegate.onRecognitionAvailabilityChange(available);
                    logVoidReturn("onRecognitionAvailabilityChange", available);
                } catch (Exception e) {
                    logException("onRecognitionAvailabilityChange", e, available);
                    throw e;
                }
            }

            @Override
            public void onModuleDied() throws RemoteException {
                try {
                    mCallbackDelegate.onModuleDied();
                    logVoidReturn("onModuleDied");
                } catch (Exception e) {
                    logException("onModuleDied", e);
                    throw e;
                }
            }

            private void logException(String methodName, Exception ex, Object... args) {
                logExceptionWithObject(this, mOriginatorIdentity, methodName, ex, args);
            }

            private void logVoidReturn(String methodName, Object... args) {
                logVoidReturnWithObject(this, mOriginatorIdentity, methodName, args);
            }

            @Override
            public IBinder asBinder() {
                return mCallbackDelegate.asBinder();
            }

            // Override toString() in order to have the delegate's ID in it.
            @Override
            public String toString() {
                return Objects.toString(mCallbackDelegate);
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Actual logging logic below.
    private static final int NUM_EVENTS_TO_DUMP = 64;
    private final static SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss:SSS");
    private final @NonNull LinkedList<Event> mLastEvents = new LinkedList<>();

    static private class Event {
        public final long timestamp = System.currentTimeMillis();
        public final String message;

        private Event(String message) {
            this.message = message;
        }
    }

    private static String printArgs(@NonNull Object[] args) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < args.length; ++i) {
            if (i > 0) {
                result.append(", ");
            }
            printObject(result, args[i]);
        }
        return result.toString();
    }

    private static void printObject(@NonNull StringBuilder builder, @Nullable Object obj) {
        ObjectPrinter.print(builder, obj, true, 16);
    }

    private static String printObject(@Nullable Object obj) {
        StringBuilder builder = new StringBuilder();
        printObject(builder, obj);
        return builder.toString();
    }

    private void logReturnWithObject(@NonNull Object object, @Nullable Identity originatorIdentity,
            String methodName,
            @Nullable Object retVal,
            @NonNull Object[] args) {
        final String message = String.format("%s[this=%s, client=%s](%s) -> %s", methodName,
                object,
                printObject(originatorIdentity),
                printArgs(args),
                printObject(retVal));
        Log.i(TAG, message);
        appendMessage(message);
    }

    private void logVoidReturnWithObject(@NonNull Object object,
            @Nullable Identity originatorIdentity, @NonNull String methodName,
            @NonNull Object[] args) {
        final String message = String.format("%s[this=%s, client=%s](%s)", methodName,
                object,
                printObject(originatorIdentity),
                printArgs(args));
        Log.i(TAG, message);
        appendMessage(message);
    }

    private void logExceptionWithObject(@NonNull Object object,
            @Nullable Identity originatorIdentity, @NonNull String methodName,
            @NonNull Exception ex,
            Object[] args) {
        final String message = String.format("%s[this=%s, client=%s](%s) threw", methodName,
                object,
                printObject(originatorIdentity),
                printArgs(args));
        Log.e(TAG, message, ex);
        appendMessage(message + " " + ex.toString());
    }

    private void appendMessage(@NonNull String message) {
        Event event = new Event(message);
        synchronized (mLastEvents) {
            if (mLastEvents.size() > NUM_EVENTS_TO_DUMP) {
                mLastEvents.remove();
            }
            mLastEvents.add(event);
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("=========================================");
        pw.println("Last events");
        pw.println("=========================================");
        synchronized (mLastEvents) {
            for (Event event : mLastEvents) {
                pw.print(DATE_FORMAT.format(new Date(event.timestamp)));
                pw.print('\t');
                pw.println(event.message);
            }
        }
        pw.println();

        if (mDelegate instanceof Dumpable) {
            ((Dumpable) mDelegate).dump(pw);
        }
    }
}
