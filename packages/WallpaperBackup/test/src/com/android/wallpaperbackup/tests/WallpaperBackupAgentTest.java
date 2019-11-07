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

package com.android.wallpaperbackup.tests;

import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.WallpaperManager;
import android.app.backup.FullBackupDataOutput;
import android.content.SharedPreferences;
import android.os.UserHandle;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.wallpaperbackup.WallpaperBackupAgent;
import com.android.wallpaperbackup.utils.ContextWithServiceOverrides;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
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

    @Mock private FullBackupDataOutput mOutput;
    @Mock private WallpaperManager mWallpaperManager;
    @Mock private SharedPreferences mSharedPreferences;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private ContextWithServiceOverrides mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = new ContextWithServiceOverrides(ApplicationProvider.getApplicationContext());
        mContext.injectSystemService(WallpaperManager.class, mWallpaperManager);
        mContext.setSharedPreferencesOverride(mSharedPreferences);
    }

    @Test
    public void testOnFullBackup_withNoChanges_onlyBacksUpEmptyFile() throws IOException {
        WallpaperBackupAgent wallpaperBackupAgent = new WallpaperBackupAgent();
        initialiseAgent(wallpaperBackupAgent);

        when(mWallpaperManager.getWallpaperIdForUser(eq(FLAG_SYSTEM), eq(UserHandle.USER_SYSTEM)))
                .thenReturn(1);
        when(mWallpaperManager.getWallpaperIdForUser(eq(FLAG_LOCK), eq(UserHandle.USER_SYSTEM)))
                .thenReturn(1);
        when(mSharedPreferences.getInt(eq(SYSTEM_GENERATION), eq(-1))).thenReturn(1);
        when(mSharedPreferences.getInt(eq(LOCK_GENERATION), eq(-1))).thenReturn(1);

        wallpaperBackupAgent.onFullBackup(mOutput);

        verify(mOutput); // Backup of empty file only
    }

    @Test
    public void testOnFullBackup_withOnlyChangedSystem_updatesTheSharedPreferences()
            throws IOException {
        // Create a system wallpaper file
        mTemporaryFolder.newFile("wallpaper_orig");
        // Create stageing file to simulate he wallpaper being ready to back up
        new File(mContext.getFilesDir(), "wallpaper-stage").createNewFile();

        WallpaperBackupAgent wallpaperBackupAgent =
                new IsolatedWallpaperBackupAgent(mTemporaryFolder.getRoot());
        initialiseAgent(wallpaperBackupAgent);

        SharedPreferences.Editor preferenceEditor = mock(SharedPreferences.Editor.class);

        when(mWallpaperManager.getWallpaperIdForUser(eq(FLAG_SYSTEM), eq(UserHandle.USER_SYSTEM)))
                .thenReturn(2);
        when(mWallpaperManager.getWallpaperIdForUser(eq(FLAG_LOCK), eq(UserHandle.USER_SYSTEM)))
                .thenReturn(1);
        when(mWallpaperManager.isWallpaperBackupEligible(eq(FLAG_SYSTEM))).thenReturn(true);
        when(mWallpaperManager.isWallpaperBackupEligible(eq(FLAG_LOCK))).thenReturn(true);
        when(mSharedPreferences.getInt(eq(SYSTEM_GENERATION), eq(-1))).thenReturn(1);
        when(mSharedPreferences.getInt(eq(LOCK_GENERATION), eq(-1))).thenReturn(1);
        when(mSharedPreferences.edit()).thenReturn(preferenceEditor);
        when(preferenceEditor.putInt(eq(SYSTEM_GENERATION), eq(2))).thenReturn(preferenceEditor);

        wallpaperBackupAgent.onFullBackup(mOutput);

        verify(preferenceEditor).putInt(eq(SYSTEM_GENERATION), eq(2));
    }

    private void initialiseAgent(WallpaperBackupAgent agent) {
        agent.attach(mContext);
        agent.onCreate();
    }

    private static class IsolatedWallpaperBackupAgent extends WallpaperBackupAgent {
        File mWallpaperBaseDirectory;
        List<File> mBackedUpFiles = new ArrayList();

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
    }
}
