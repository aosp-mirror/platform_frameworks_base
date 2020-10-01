/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.locksettings;

import android.os.SystemProperties;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * A class that maintains a mapping of which password slots are used by alternate OS images when
 * dual-booting a device. Currently, slots can either be owned by the host OS or a live GSI.
 * This mapping is stored in /metadata/password_slots/slot_map using Java Properties.
 *
 * If a /metadata partition does not exist, GSIs are not supported, and PasswordSlotManager will
 * simply not persist the slot mapping.
 */
public class PasswordSlotManager {
    private static final String TAG = "PasswordSlotManager";

    private static final String GSI_RUNNING_PROP = "ro.gsid.image_running";
    private static final String SLOT_MAP_DIR = "/metadata/password_slots";

    // This maps each used password slot to the OS image that created it. Password slots are
    // integer keys/indices into secure storage. The OS image is recorded as a string. The factory
    // image is "host" and GSIs are "gsi<N>" where N >= 1.
    private Map<Integer, String> mSlotMap;

    // Cache the active slots until loadSlotMap() is called.
    private Set<Integer> mActiveSlots;

    public PasswordSlotManager() {
    }

    @VisibleForTesting
    protected String getSlotMapDir() {
        return SLOT_MAP_DIR;
    }

    @VisibleForTesting
    protected int getGsiImageNumber() {
        return SystemProperties.getInt(GSI_RUNNING_PROP, 0);
    }

    /**
     * Notify the manager of which slots are definitively in use by the current OS image.
     *
     * @throws RuntimeException
     */
    public void refreshActiveSlots(Set<Integer> activeSlots) throws RuntimeException {
        if (mSlotMap == null) {
            mActiveSlots = new HashSet<Integer>(activeSlots);
            return;
        }

        // Update which slots are owned by the current image.
        final HashSet<Integer> slotsToDelete = new HashSet<Integer>();
        for (Map.Entry<Integer, String> entry : mSlotMap.entrySet()) {
            // Delete possibly stale entries for the current image.
            if (entry.getValue().equals(getMode())) {
                slotsToDelete.add(entry.getKey());
            }
        }
        for (Integer slot : slotsToDelete) {
            mSlotMap.remove(slot);
        }

        // Add slots for the current image.
        for (Integer slot : activeSlots) {
            mSlotMap.put(slot, getMode());
        }

        saveSlotMap();
    }

    /**
     * Mark the given slot as in use by the current OS image.
     *
     * @throws RuntimeException
     */
    public void markSlotInUse(int slot) throws RuntimeException {
        ensureSlotMapLoaded();
        if (mSlotMap.containsKey(slot) && !mSlotMap.get(slot).equals(getMode())) {
            throw new IllegalStateException("password slot " + slot + " is not available");
        }
        mSlotMap.put(slot, getMode());
        saveSlotMap();
    }

    /**
     * Mark the given slot as no longer in use by the current OS image.
     *
     * @throws RuntimeException
     */
    public void markSlotDeleted(int slot) throws RuntimeException {
        ensureSlotMapLoaded();
        if (mSlotMap.containsKey(slot) && !mSlotMap.get(slot).equals(getMode())) {
            throw new IllegalStateException("password slot " + slot + " cannot be deleted");
        }
        mSlotMap.remove(slot);
        saveSlotMap();
    }

    /**
     * Return the set of slots used across all OS images.
     *
     * @return Integer set of all used slots.
     */
    public Set<Integer> getUsedSlots() {
        ensureSlotMapLoaded();
        return Collections.unmodifiableSet(mSlotMap.keySet());
    }

    private File getSlotMapFile() {
        return Paths.get(getSlotMapDir(), "slot_map").toFile();
    }

    private String getMode() {
        int gsiIndex = getGsiImageNumber();
        if (gsiIndex > 0) {
            return "gsi" + gsiIndex;
        }
        return "host";
    }

    @VisibleForTesting
    protected Map<Integer, String> loadSlotMap(InputStream stream) throws IOException {
        final HashMap<Integer, String> map = new HashMap<Integer, String>();
        final Properties props = new Properties();
        props.load(stream);
        for (String slotString : props.stringPropertyNames()) {
            final int slot = Integer.parseInt(slotString);
            final String owner = props.getProperty(slotString);
            map.put(slot, owner);
        }
        return map;
    }

    private Map<Integer, String> loadSlotMap() {
        // It's okay if the file doesn't exist.
        final File file = getSlotMapFile();
        if (file.exists()) {
            try (FileInputStream stream = new FileInputStream(file)) {
                return loadSlotMap(stream);
            } catch (Exception e) {
                Slog.e(TAG, "Could not load slot map file", e);
            }
        }
        return new HashMap<Integer, String>();
    }

    private void ensureSlotMapLoaded() {
        if (mSlotMap == null) {
            mSlotMap = loadSlotMap();
            if (mActiveSlots != null) {
                refreshActiveSlots(mActiveSlots);
                mActiveSlots = null;
            }
        }
    }

    @VisibleForTesting
    protected void saveSlotMap(OutputStream stream) throws IOException {
        if (mSlotMap == null) {
            return;
        }
        final Properties props = new Properties();
        for (Map.Entry<Integer, String> entry : mSlotMap.entrySet()) {
            props.setProperty(entry.getKey().toString(), entry.getValue());
        }
        props.store(stream, "");
    }

    private void saveSlotMap() {
        if (mSlotMap == null) {
            return;
        }
        if (!getSlotMapFile().getParentFile().exists()) {
            Slog.w(TAG, "Not saving slot map, " + getSlotMapDir() + " does not exist");
            return;
        }

        try (FileOutputStream fos = new FileOutputStream(getSlotMapFile())) {
            saveSlotMap(fos);
        } catch (IOException e) {
            Slog.e(TAG, "failed to save password slot map", e);
        }
    }
}
