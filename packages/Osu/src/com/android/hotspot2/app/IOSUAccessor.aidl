package com.android.hotspot2.app;

import com.android.hotspot2.app.OSUData;

interface IOSUAccessor {
    List<OSUData> getOsuData();
    void selectOsu(int id);
}
