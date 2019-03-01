/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view;

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.IBinder;
import android.os.Parcelable;
import android.util.Slog;

/**
 * An input channel specifies the file descriptors used to send input events to
 * a window in another process.  It is Parcelable so that it can be sent
 * to the process that is to receive events.  Only one thread should be reading
 * from an InputChannel at a time.
 * @hide
 */
public final class InputChannel implements Parcelable {
    private static final String TAG = "InputChannel";
    
    private static final boolean DEBUG = false;
    
    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Parcelable.Creator<InputChannel> CREATOR
            = new Parcelable.Creator<InputChannel>() {
        public InputChannel createFromParcel(Parcel source) {
            InputChannel result = new InputChannel();
            result.readFromParcel(source);
            return result;
        }
        
        public InputChannel[] newArray(int size) {
            return new InputChannel[size];
        }
    };
    
    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    private long mPtr; // used by native code

    private static native InputChannel[] nativeOpenInputChannelPair(String name);

    private native void nativeDispose(boolean finalized);
    private native void nativeTransferTo(InputChannel other);
    private native void nativeReadFromParcel(Parcel parcel);
    private native void nativeWriteToParcel(Parcel parcel);
    private native void nativeDup(InputChannel target);
    private native IBinder nativeGetToken();
    private native void nativeSetToken(IBinder token);

    private native String nativeGetName();

    /**
     * Creates an uninitialized input channel.
     * It can be initialized by reading from a Parcel or by transferring the state of
     * another input channel into this one.
     */
    @UnsupportedAppUsage
    public InputChannel() {
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            nativeDispose(true);
        } finally {
            super.finalize();
        }
    }
    
    /**
     * Creates a new input channel pair.  One channel should be provided to the input
     * dispatcher and the other to the application's input queue.
     * @param name The descriptive (non-unique) name of the channel pair.
     * @return A pair of input channels.  The first channel is designated as the
     * server channel and should be used to publish input events.  The second channel
     * is designated as the client channel and should be used to consume input events.
     */
    public static InputChannel[] openInputChannelPair(String name) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }

        if (DEBUG) {
            Slog.d(TAG, "Opening input channel pair '" + name + "'");
        }
        return nativeOpenInputChannelPair(name);
    }
    
    /**
     * Gets the name of the input channel.
     * @return The input channel name.
     */
    public String getName() {
        String name = nativeGetName();
        return name != null ? name : "uninitialized";
    }

    /**
     * Disposes the input channel.
     * Explicitly releases the reference this object is holding on the input channel.
     * When all references are released, the input channel will be closed.
     */
    public void dispose() {
        nativeDispose(false);
    }
    
    /**
     * Transfers ownership of the internal state of the input channel to another
     * instance and invalidates this instance.  This is used to pass an input channel
     * as an out parameter in a binder call.
     * @param other The other input channel instance.
     */
    public void transferTo(InputChannel outParameter) {
        if (outParameter == null) {
            throw new IllegalArgumentException("outParameter must not be null");
        }
        
        nativeTransferTo(outParameter);
    }

    /**
     * Duplicates the input channel.
     */
    public InputChannel dup() {
        InputChannel target = new InputChannel();
        nativeDup(target);
        return target;
    }

    @Override
    public int describeContents() {
        return Parcelable.CONTENTS_FILE_DESCRIPTOR;
    }

    public void readFromParcel(Parcel in) {
        if (in == null) {
            throw new IllegalArgumentException("in must not be null");
        }
        
        nativeReadFromParcel(in);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }
        
        nativeWriteToParcel(out);

        if ((flags & PARCELABLE_WRITE_RETURN_VALUE) != 0) {
            dispose();
        }
    }

    @Override
    public String toString() {
        return getName();
    }

    public IBinder getToken() {
        return nativeGetToken();
    }

    public void setToken(IBinder token) {
        nativeSetToken(token);
    }
}
