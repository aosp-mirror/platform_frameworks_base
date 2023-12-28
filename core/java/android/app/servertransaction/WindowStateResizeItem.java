/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.servertransaction;

import static android.view.Display.INVALID_DISPLAY;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.ClientTransactionHandler;
import android.content.Context;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.Trace;
import android.util.MergedConfiguration;
import android.view.IWindow;
import android.view.InsetsState;
import android.window.ClientWindowFrames;

import java.util.Objects;

/**
 * Message to deliver window resize info.
 * @hide
 */
public class WindowStateResizeItem extends ClientTransactionItem {

    private IWindow mWindow;
    private ClientWindowFrames mFrames;
    private boolean mReportDraw;
    private MergedConfiguration mConfiguration;
    private InsetsState mInsetsState;
    private boolean mForceLayout;
    private boolean mAlwaysConsumeSystemBars;
    private int mDisplayId;
    private int mSyncSeqId;
    private boolean mDragResizing;

    @Override
    public void execute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER,
                mReportDraw ? "windowResizedReport" : "windowResized");
        if (mWindow instanceof ResizeListener listener) {
            listener.onExecutingWindowStateResizeItem();
        }
        try {
            mWindow.resized(mFrames, mReportDraw, mConfiguration, mInsetsState, mForceLayout,
                    mAlwaysConsumeSystemBars, mDisplayId, mSyncSeqId, mDragResizing);
        } catch (RemoteException e) {
            // Should be a local call.
            throw new RuntimeException(e);
        }
        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
    }

    @Nullable
    @Override
    public Context getContextToUpdate(@NonNull ClientTransactionHandler client) {
        // WindowStateResizeItem may update the global config with #mConfiguration.
        return ActivityThread.currentApplication();
    }

    // ObjectPoolItem implementation

    private WindowStateResizeItem() {}

    /** Obtains an instance initialized with provided params. */
    public static WindowStateResizeItem obtain(@NonNull IWindow window,
            @NonNull ClientWindowFrames frames, boolean reportDraw,
            @NonNull MergedConfiguration configuration, @NonNull InsetsState insetsState,
            boolean forceLayout, boolean alwaysConsumeSystemBars, int displayId, int syncSeqId,
            boolean dragResizing) {
        WindowStateResizeItem instance =
                ObjectPool.obtain(WindowStateResizeItem.class);
        if (instance == null) {
            instance = new WindowStateResizeItem();
        }
        instance.mWindow = requireNonNull(window);
        instance.mFrames = new ClientWindowFrames(frames);
        instance.mReportDraw = reportDraw;
        instance.mConfiguration = new MergedConfiguration(configuration);
        instance.mInsetsState = new InsetsState(insetsState, true /* copySources */);
        instance.mForceLayout = forceLayout;
        instance.mAlwaysConsumeSystemBars = alwaysConsumeSystemBars;
        instance.mDisplayId = displayId;
        instance.mSyncSeqId = syncSeqId;
        instance.mDragResizing = dragResizing;

        return instance;
    }

    @Override
    public void recycle() {
        mWindow = null;
        mFrames = null;
        mReportDraw = false;
        mConfiguration = null;
        mInsetsState = null;
        mForceLayout = false;
        mAlwaysConsumeSystemBars = false;
        mDisplayId = INVALID_DISPLAY;
        mSyncSeqId = -1;
        mDragResizing = false;
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mWindow.asBinder());
        dest.writeTypedObject(mFrames, flags);
        dest.writeBoolean(mReportDraw);
        dest.writeTypedObject(mConfiguration, flags);
        dest.writeTypedObject(mInsetsState, flags);
        dest.writeBoolean(mForceLayout);
        dest.writeBoolean(mAlwaysConsumeSystemBars);
        dest.writeInt(mDisplayId);
        dest.writeInt(mSyncSeqId);
        dest.writeBoolean(mDragResizing);
    }

    /** Reads from Parcel. */
    private WindowStateResizeItem(@NonNull Parcel in) {
        mWindow = IWindow.Stub.asInterface(in.readStrongBinder());
        mFrames = in.readTypedObject(ClientWindowFrames.CREATOR);
        mReportDraw = in.readBoolean();
        mConfiguration = in.readTypedObject(MergedConfiguration.CREATOR);
        mInsetsState = in.readTypedObject(InsetsState.CREATOR);
        mForceLayout = in.readBoolean();
        mAlwaysConsumeSystemBars = in.readBoolean();
        mDisplayId = in.readInt();
        mSyncSeqId = in.readInt();
        mDragResizing = in.readBoolean();
    }

    public static final @NonNull Creator<WindowStateResizeItem> CREATOR = new Creator<>() {
        public WindowStateResizeItem createFromParcel(@NonNull Parcel in) {
            return new WindowStateResizeItem(in);
        }

        public WindowStateResizeItem[] newArray(int size) {
            return new WindowStateResizeItem[size];
        }
    };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final WindowStateResizeItem other = (WindowStateResizeItem) o;
        return Objects.equals(mWindow, other.mWindow)
                && Objects.equals(mFrames, other.mFrames)
                && mReportDraw == other.mReportDraw
                && Objects.equals(mConfiguration, other.mConfiguration)
                && Objects.equals(mInsetsState, other.mInsetsState)
                && mForceLayout == other.mForceLayout
                && mAlwaysConsumeSystemBars == other.mAlwaysConsumeSystemBars
                && mDisplayId == other.mDisplayId
                && mSyncSeqId == other.mSyncSeqId
                && mDragResizing == other.mDragResizing;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(mWindow);
        result = 31 * result + Objects.hashCode(mFrames);
        result = 31 * result + (mReportDraw ? 1 : 0);
        result = 31 * result + Objects.hashCode(mConfiguration);
        result = 31 * result + Objects.hashCode(mInsetsState);
        result = 31 * result + (mForceLayout ? 1 : 0);
        result = 31 * result + (mAlwaysConsumeSystemBars ? 1 : 0);
        result = 31 * result + mDisplayId;
        result = 31 * result + mSyncSeqId;
        result = 31 * result + (mDragResizing ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "WindowStateResizeItem{window=" + mWindow
                + ", reportDrawn=" + mReportDraw
                + ", configuration=" + mConfiguration
                + "}";
    }

    /** The interface for IWindow to perform resize directly if possible. */
    public interface ResizeListener {
        /** Notifies that IWindow#resized is going to be called from WindowStateResizeItem. */
        void onExecutingWindowStateResizeItem();
    }
}
