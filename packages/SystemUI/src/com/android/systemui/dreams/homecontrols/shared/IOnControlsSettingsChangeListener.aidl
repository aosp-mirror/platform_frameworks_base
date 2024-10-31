package com.android.systemui.dreams.homecontrols.shared;

import android.content.ComponentName;

oneway interface IOnControlsSettingsChangeListener {
    void onControlsSettingsChanged(in ComponentName panelComponent, boolean allowTrivialControlsOnLockscreen);
}
