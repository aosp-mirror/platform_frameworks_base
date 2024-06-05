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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ClientTransactionHandler;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.util.MergedConfiguration;
import android.view.IWindow;
import android.view.InsetsState;
import android.window.ActivityWindowInfo;
import android.window.ClientWindowFrames;

import java.util.Objects;

/**
 * Message to deliver window resize info.
 * @hide
 */
public class WindowStateResizeItem extends WindowStateTransactionItem {

    private static final String TAG = "WindowStateResizeItem";

    private ClientWindowFrames mFrames;
    private boolean mReportDraw;
    private MergedConfiguration mConfiguration;
    private InsetsState mInsetsState;
    private boolean mForceLayout;
    private boolean mAlwaysConsumeSystemBars;
    private int mDisplayId;
    private int mSyncSeqId;
    private boolean mDragResizing;

    /** {@code null} if this is not an Activity window. */
    @Nullable
    private ActivityWindowInfo mActivityWindowInfo;

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull IWindow window,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER,
                mReportDraw ? "windowResizedReport" : "windowResized");
        try {
            window.resized(mFrames, mReportDraw, mConfiguration, mInsetsState, mForceLayout,
                    mAlwaysConsumeSystemBars, mDisplayId, mSyncSeqId, mDragResizing,
                    mActivityWindowInfo);
        } catch (RemoteException e) {
            // Should be a local call.
            // An exception could happen if the process is restarted. It is safe to ignore since
            // the window should no longer exist.
            Log.w(TAG, "The original window no longer exists in the new process", e);
        }
        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
    }

    // ObjectPoolItem implementation

    private WindowStateResizeItem() {}

    /** Obtains an instance initialized with provided params. */
    public static WindowStateResizeItem obtain(@NonNull IWindow window,
            @NonNull ClientWindowFrames frames, boolean reportDraw,
            @NonNull MergedConfiguration configuration, @NonNull InsetsState insetsState,
            boolean forceLayout, boolean alwaysConsumeSystemBars, int displayId, int syncSeqId,
            boolean dragResizing, @Nullable ActivityWindowInfo activityWindowInfo) {
        WindowStateResizeItem instance =
                ObjectPool.obtain(WindowStateResizeItem.class);
        if (instance == null) {
            instance = new WindowStateResizeItem();
        }
        instance.setWindow(window);
        instance.mFrames = new ClientWindowFrames(frames);
        instance.mReportDraw = reportDraw;
        instance.mConfiguration = new MergedConfiguration(configuration);
        instance.mInsetsState = new InsetsState(insetsState, true /* copySources */);
        instance.mForceLayout = forceLayout;
        instance.mAlwaysConsumeSystemBars = alwaysConsumeSystemBars;
        instance.mDisplayId = displayId;
        instance.mSyncSeqId = syncSeqId;
        instance.mDragResizing = dragResizing;
        instance.mActivityWindowInfo = activityWindowInfo != null
                ? new ActivityWindowInfo(activityWindowInfo)
                : null;

        return instance;
    }

    @Override
    public void recycle() {
        super.recycle();
        mFrames = null;
        mReportDraw = false;
        mConfiguration = null;
        mInsetsState = null;
        mForceLayout = false;
        mAlwaysConsumeSystemBars = false;
        mDisplayId = INVALID_DISPLAY;
        mSyncSeqId = -1;
        mDragResizing = false;
        mActivityWindowInfo = null;
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeTypedObject(mFrames, flags);
        dest.writeBoolean(mReportDraw);
        dest.writeTypedObject(mConfiguration, flags);
        dest.writeTypedObject(mInsetsState, flags);
        dest.writeBoolean(mForceLayout);
        dest.writeBoolean(mAlwaysConsumeSystemBars);
        dest.writeInt(mDisplayId);
        dest.writeInt(mSyncSeqId);
        dest.writeBoolean(mDragResizing);
        dest.writeTypedObject(mActivityWindowInfo, flags);
    }

    /** Reads from Parcel. */
    private WindowStateResizeItem(@NonNull Parcel in) {
        super(in);
        mFrames = in.readTypedObject(ClientWindowFrames.CREATOR);
        mReportDraw = in.readBoolean();
        mConfiguration = in.readTypedObject(MergedConfiguration.CREATOR);
        mInsetsState = in.readTypedObject(InsetsState.CREATOR);
        mForceLayout = in.readBoolean();
        mAlwaysConsumeSystemBars = in.readBoolean();
        mDisplayId = in.readInt();
        mSyncSeqId = in.readInt();
        mDragResizing = in.readBoolean();
        mActivityWindowInfo = in.readTypedObject(ActivityWindowInfo.CREATOR);
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
        if (!super.equals(o)) {
            return false;
        }
        final WindowStateResizeItem other = (WindowStateResizeItem) o;
        return Objects.equals(mFrames, other.mFrames)
                && mReportDraw == other.mReportDraw
                && Objects.equals(mConfiguration, other.mConfiguration)
                && Objects.equals(mInsetsState, other.mInsetsState)
                && mForceLayout == other.mForceLayout
                && mAlwaysConsumeSystemBars == other.mAlwaysConsumeSystemBars
                && mDisplayId == other.mDisplayId
                && mSyncSeqId == other.mSyncSeqId
                && mDragResizing == other.mDragResizing
                && Objects.equals(mActivityWindowInfo, other.mActivityWindowInfo);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + Objects.hashCode(mFrames);
        result = 31 * result + (mReportDraw ? 1 : 0);
        result = 31 * result + Objects.hashCode(mConfiguration);
        result = 31 * result + Objects.hashCode(mInsetsState);
        result = 31 * result + (mForceLayout ? 1 : 0);
        result = 31 * result + (mAlwaysConsumeSystemBars ? 1 : 0);
        result = 31 * result + mDisplayId;
        result = 31 * result + mSyncSeqId;
        result = 31 * result + (mDragResizing ? 1 : 0);
        result = 31 * result + Objects.hashCode(mActivityWindowInfo);
        return result;
    }

    @Override
    public String toString() {
        return "WindowStateResizeItem{" + super.toString()
                + ", reportDrawn=" + mReportDraw
                + ", configuration=" + mConfiguration
                + ", activityWindowInfo=" + mActivityWindowInfo
                + "}";
    }
}
