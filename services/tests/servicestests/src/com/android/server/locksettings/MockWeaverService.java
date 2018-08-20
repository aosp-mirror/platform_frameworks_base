package com.android.server.locksettings;

import android.hardware.weaver.V1_0.IWeaver;
import android.hardware.weaver.V1_0.WeaverConfig;
import android.hardware.weaver.V1_0.WeaverReadResponse;
import android.hardware.weaver.V1_0.WeaverStatus;
import android.hidl.base.V1_0.DebugInfo;
import android.os.IHwBinder;
import android.os.IHwBinder.DeathRecipient;
import android.os.RemoteException;
import android.util.Pair;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;

public class MockWeaverService extends IWeaver.Stub {

    private static final int MAX_SLOTS = 8;
    private static final int KEY_LENGTH = 256 / 8;
    private static final int VALUE_LENGTH = 256 / 8;

    private Pair<ArrayList<Byte>, ArrayList<Byte>>[] slots = new Pair[MAX_SLOTS];
    @Override
    public void getConfig(getConfigCallback cb) throws RemoteException {
        WeaverConfig config = new WeaverConfig();
        config.keySize = KEY_LENGTH;
        config.valueSize = VALUE_LENGTH;
        config.slots = MAX_SLOTS;
        cb.onValues(WeaverStatus.OK, config);
    }

    @Override
    public int write(int slotId, ArrayList<Byte> key, ArrayList<Byte> value)
            throws RemoteException {
        if (slotId < 0 || slotId >= MAX_SLOTS) {
            throw new RuntimeException("Invalid slot id");
        }
        slots[slotId] = Pair.create((ArrayList<Byte>) key.clone(), (ArrayList<Byte>) value.clone());
        return WeaverStatus.OK;
    }

    @Override
    public void read(int slotId, ArrayList<Byte> key, readCallback cb) throws RemoteException {
        if (slotId < 0 || slotId >= MAX_SLOTS) {
            throw new RuntimeException("Invalid slot id");
        }

        WeaverReadResponse response = new WeaverReadResponse();
        if (key.equals(slots[slotId].first)) {
            response.value.addAll(slots[slotId].second);
            cb.onValues(WeaverStatus.OK, response);
        } else {
            cb.onValues(WeaverStatus.FAILED, response);
        }
    }
}
