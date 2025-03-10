package com.android.systemui.dreams.homecontrols.shared;

import android.os.IRemoteCallback;
import com.android.systemui.dreams.homecontrols.shared.IOnControlsSettingsChangeListener;

oneway interface IHomeControlsRemoteProxy {
    void registerListenerForCurrentUser(in IOnControlsSettingsChangeListener callback);
    void unregisterListenerForCurrentUser(in IOnControlsSettingsChangeListener callback);
}
