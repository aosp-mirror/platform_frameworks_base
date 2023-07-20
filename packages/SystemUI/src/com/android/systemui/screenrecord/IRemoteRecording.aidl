package com.android.systemui.screenrecord;

import com.android.systemui.screenrecord.IRecordingCallback;

interface IRemoteRecording {
    void startRecording();
    void stopRecording();
    boolean isRecording();
    boolean isStarting();
    void addRecordingCallback(in IRecordingCallback callback);
    void removeRecordingCallback(in IRecordingCallback callback);
}
