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

package com.android.wallpaperbackup;

import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.WallpaperManager;
import android.app.backup.FullBackupDataOutput;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserHandle;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.content.PackageMonitor;
import com.android.wallpaperbackup.WallpaperBackupAgent;
import com.android.wallpaperbackup.utils.ContextWithServiceOverrides;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class WallpaperBackupAgentTest {
    private static final String SYSTEM_GENERATION = "system_gen";
    private static final String LOCK_GENERATION = "lock_gen";
    private static final String TEST_WALLPAPER_PACKAGE = "wallpaper_package";

    private static final int TEST_SYSTEM_WALLPAPER_ID = 1;
    private static final int TEST_LOCK_WALLPAPER_ID = 2;

    @Mock private FullBackupDataOutput mOutput;
    @Mock private WallpaperManager mWallpaperManager;
    @Mock private SharedPreferences mSharedPreferences;
    @Mock private SharedPreferences.Editor mSharedPreferenceEditor;
    @Mock private Context mMockContext;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private ContextWithServiceOverrides mContext;
    private IsolatedWallpaperBackupAgent mWallpaperBackupAgent;
    private ComponentName mWallpaperComponent;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mSharedPreferences.edit()).thenReturn(mSharedPreferenceEditor);
        when(mSharedPreferenceEditor.putInt(anyString(), anyInt()))
                .thenReturn(mSharedPreferenceEditor);
        doNothing().when(mSharedPreferenceEditor).apply();

        mContext = new ContextWithServiceOverrides(ApplicationProvider.getApplicationContext());
        mContext.injectSystemService(WallpaperManager.class, mWallpaperManager);
        mContext.setSharedPreferencesOverride(mSharedPreferences);

        mWallpaperBackupAgent = new IsolatedWallpaperBackupAgent(mTemporaryFolder.getRoot());
        mWallpaperBackupAgent.attach(mContext);
        mWallpaperBackupAgent.onCreate();

        mWallpaperComponent = new ComponentName(TEST_WALLPAPER_PACKAGE, "");
    }

    @Test
    public void testOnFullBackup_withNoChanges_onlyBacksUpEmptyFile() throws IOException {
        mockBackedUpState();
        mockCurrentWallpapers(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        assertThat(mWallpaperBackupAgent.mBackedUpFiles.size()).isEqualTo(1);
        assertThat(mWallpaperBackupAgent.mBackedUpFiles.get(0).getName()).isEqualTo("empty");
    }

    @Test
    public void testOnFullBackup_withOnlyChangedSystem_updatesTheSharedPreferences()
            throws IOException {
        mockSystemWallpaperReadyToBackUp();
        mockUnbackedUpState();
        mockCurrentWallpapers(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        verify(mSharedPreferenceEditor).putInt(eq(SYSTEM_GENERATION), eq(TEST_SYSTEM_WALLPAPER_ID));
    }

    @Test
    public void testOnFullBackup_withLockChangedToMatchSystem_updatesTheSharedPreferences()
            throws IOException {
        mockBackedUpState();
        mockSystemWallpaperReadyToBackUp();
        mockCurrentWallpapers(TEST_SYSTEM_WALLPAPER_ID, -1);

        mWallpaperBackupAgent.onFullBackup(mOutput);

        InOrder inOrder = inOrder(mSharedPreferenceEditor);
        inOrder.verify(mSharedPreferenceEditor)
                .putInt(eq(SYSTEM_GENERATION), eq(TEST_SYSTEM_WALLPAPER_ID));
        inOrder.verify(mSharedPreferenceEditor).apply();
        inOrder.verify(mSharedPreferenceEditor).putInt(eq(LOCK_GENERATION), eq(-1));
        inOrder.verify(mSharedPreferenceEditor).apply();
    }

    @Test
    public void updateWallpaperComponent_doesApplyLater() throws IOException {
        mWallpaperBackupAgent.mIsDeviceInRestore = true;

        mWallpaperBackupAgent.updateWallpaperComponent(mWallpaperComponent,
                /* applyToLock */ true);

        // Imitate wallpaper component installation.
        mWallpaperBackupAgent.mWallpaperPackageMonitor.onPackageAdded(TEST_WALLPAPER_PACKAGE,
                /* uid */0);

        verify(mWallpaperManager, times(1)).setWallpaperComponent(mWallpaperComponent);
        verify(mWallpaperManager, times(1)).clear(eq(FLAG_LOCK));
    }

    @Test
    public void updateWallpaperComponent_applyToLockFalse_doesApplyLaterOnlyToMainScreen()
            throws IOException {
        mWallpaperBackupAgent.mIsDeviceInRestore = true;

        mWallpaperBackupAgent.updateWallpaperComponent(mWallpaperComponent,
                /* applyToLock */ false);

        // Imitate wallpaper component installation.
        mWallpaperBackupAgent.mWallpaperPackageMonitor.onPackageAdded(TEST_WALLPAPER_PACKAGE,
                /* uid */0);

        verify(mWallpaperManager, times(1)).setWallpaperComponent(mWallpaperComponent);
        verify(mWallpaperManager, never()).clear(eq(FLAG_LOCK));
    }

    @Test
    public void updateWallpaperComponent_deviceNotInRestore_doesNotApply()
            throws IOException {
        mWallpaperBackupAgent.mIsDeviceInRestore = false;

        mWallpaperBackupAgent.updateWallpaperComponent(mWallpaperComponent,
                /* applyToLock */ true);

        // Imitate wallpaper component installation.
        mWallpaperBackupAgent.mWallpaperPackageMonitor.onPackageAdded(TEST_WALLPAPER_PACKAGE,
                /* uid */0);

        verify(mWallpaperManager, never()).setWallpaperComponent(mWallpaperComponent);
        verify(mWallpaperManager, never()).clear(eq(FLAG_LOCK));
    }

    @Test
    public void updateWallpaperComponent_differentPackageInstalled_doesNotApply()
            throws IOException {
        mWallpaperBackupAgent.mIsDeviceInRestore = false;

        mWallpaperBackupAgent.updateWallpaperComponent(mWallpaperComponent,
                /* applyToLock */ true);

        // Imitate "wrong" wallpaper component installation.
        mWallpaperBackupAgent.mWallpaperPackageMonitor.onPackageAdded(/* packageName */"",
                /* uid */0);

        verify(mWallpaperManager, never()).setWallpaperComponent(mWallpaperComponent);
        verify(mWallpaperManager, never()).clear(eq(FLAG_LOCK));
    }

    private void mockUnbackedUpState() {
        mockCurrentWallpapers(TEST_SYSTEM_WALLPAPER_ID, TEST_LOCK_WALLPAPER_ID);
        when(mSharedPreferences.getInt(eq(SYSTEM_GENERATION), eq(-1))).thenReturn(-1);
        when(mSharedPreferences.getInt(eq(LOCK_GENERATION), eq(-1))).thenReturn(-1);
    }

    private void mockBackedUpState() {
        when(mSharedPreferences.getInt(eq(SYSTEM_GENERATION), eq(-1)))
                .thenReturn(TEST_SYSTEM_WALLPAPER_ID);
        when(mSharedPreferences.getInt(eq(LOCK_GENERATION), eq(-1)))
                .thenReturn(TEST_LOCK_WALLPAPER_ID);
    }

    private void mockCurrentWallpapers(int systemWallpaperId, int lockWallpaperId) {
        when(mWallpaperManager.getWallpaperIdForUser(eq(FLAG_SYSTEM), eq(UserHandle.USER_SYSTEM)))
                .thenReturn(systemWallpaperId);
        when(mWallpaperManager.getWallpaperIdForUser(eq(FLAG_LOCK), eq(UserHandle.USER_SYSTEM)))
                .thenReturn(lockWallpaperId);
        when(mWallpaperManager.isWallpaperBackupEligible(eq(FLAG_SYSTEM))).thenReturn(true);
        when(mWallpaperManager.isWallpaperBackupEligible(eq(FLAG_LOCK))).thenReturn(true);
    }

    private void mockSystemWallpaperReadyToBackUp() throws IOException {
        // Create a system wallpaper file
        mTemporaryFolder.newFile("wallpaper_orig");
        // Create staging file to simulate he wallpaper being ready to back up
        new File(mContext.getFilesDir(), "wallpaper-stage").createNewFile();
    }

    private class IsolatedWallpaperBackupAgent extends WallpaperBackupAgent {
        File mWallpaperBaseDirectory;
        List<File> mBackedUpFiles = new ArrayList<>();
        PackageMonitor mWallpaperPackageMonitor;
        boolean mIsDeviceInRestore = false;

        IsolatedWallpaperBackupAgent(File wallpaperBaseDirectory) {
            mWallpaperBaseDirectory = wallpaperBaseDirectory;
        }

        @Override
        protected File getWallpaperDir() {
            return mWallpaperBaseDirectory;
        }

        @Override
        protected void backupFile(File file, FullBackupDataOutput data) {
            mBackedUpFiles.add(file);
        }

        @Override
        public SharedPreferences getSharedPreferences(File file, int mode) {
            return mSharedPreferences;
        }

        @Override
        boolean servicePackageExists(ComponentName comp) {
            return false;
        }

        @Override
        boolean isDeviceInRestore() {
            return mIsDeviceInRestore;
        }

        @Override
        PackageMonitor getWallpaperPackageMonitor(ComponentName componentName,
                boolean applyToLock) {
            mWallpaperPackageMonitor = super.getWallpaperPackageMonitor(componentName, applyToLock);
            return mWallpaperPackageMonitor;
        }

        @Override
        public Context getBaseContext() {
            return mMockContext;
        }
    }
}
