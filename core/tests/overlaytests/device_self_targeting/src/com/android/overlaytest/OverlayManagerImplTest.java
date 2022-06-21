/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.overlaytest;

import static android.content.Context.MODE_PRIVATE;

import static com.android.internal.content.om.OverlayManagerImpl.SELF_TARGET;

import static org.junit.Assert.assertThrows;

import android.annotation.NonNull;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.om.OverlayInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.FabricatedOverlayInternal;
import android.os.FabricatedOverlayInternalEntry;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.content.om.OverlayManagerImpl;
import com.android.overlaytest.self_targeting.R;

import com.google.common.truth.Expect;
import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * This test class verify the interfaces of {@link
 * com.android.internal.content.om.OverlayManagerImpl}.
 */
@RunWith(AndroidJUnit4.class)
public class OverlayManagerImplTest {
    private static final String TAG = "OverlayManagerImplTest";

    private static final String TARGET_COLOR_RES = "color/mycolor";
    private static final String TARGET_STRING_RES = "string/mystring";
    private static final String TARGET_DRAWABLE_RES = "drawable/mydrawable";

    private Context mContext;
    private OverlayManagerImpl mOverlayManagerImpl;
    private String mOverlayName;

    @Rule public TestName mTestName = new TestName();

    @Rule public Expect expect = Expect.create();

    private void clearDir() throws IOException {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Path basePath = context.getDir(SELF_TARGET, MODE_PRIVATE).toPath();
        Files.walkFileTree(
                basePath,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        if (!file.toFile().delete()) {
                            Log.w(TAG, "Failed to delete file " + file);
                        }
                        return super.visitFile(file, attrs);
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                            throws IOException {
                        if (!dir.toFile().delete()) {
                            Log.w(TAG, "Failed to delete dir " + dir);
                        }
                        return super.postVisitDirectory(dir, exc);
                    }
                });
    }

    @Before
    public void setUp() throws IOException {
        clearDir();
        mOverlayName = mTestName.getMethodName();
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mOverlayManagerImpl = new OverlayManagerImpl(mContext);
    }

    @After
    public void tearDown() throws IOException {
        clearDir();
    }

    private <T> void addOverlayEntry(
            FabricatedOverlayInternal overlayInternal,
            @NonNull List<Pair<String, Pair<String, T>>> entryDefinitions) {
        List<FabricatedOverlayInternalEntry> entries = new ArrayList<>();
        for (Pair<String, Pair<String, T>> entryDefinition : entryDefinitions) {
            FabricatedOverlayInternalEntry internalEntry = new FabricatedOverlayInternalEntry();
            internalEntry.resourceName = entryDefinition.first;
            internalEntry.configuration = entryDefinition.second.first;
            if (entryDefinition.second.second instanceof ParcelFileDescriptor) {
                internalEntry.binaryData = (ParcelFileDescriptor) entryDefinition.second.second;
            } else if (entryDefinition.second.second instanceof String) {
                internalEntry.stringData = (String) entryDefinition.second.second;
                internalEntry.dataType = TypedValue.TYPE_STRING;
            } else {
                internalEntry.data = (int) entryDefinition.second.second;
                internalEntry.dataType = TypedValue.TYPE_INT_COLOR_ARGB8;
            }
            entries.add(internalEntry);
            overlayInternal.entries = entries;
        }
    }

    private <T> FabricatedOverlayInternal createOverlayWithName(
            @NonNull String overlayName,
            @NonNull String targetPackageName,
            @NonNull List<Pair<String, Pair<String, T>>> entryDefinitions) {
        final String packageName = mContext.getPackageName();
        FabricatedOverlayInternal overlayInternal = new FabricatedOverlayInternal();
        overlayInternal.overlayName = overlayName;
        overlayInternal.targetPackageName = targetPackageName;
        overlayInternal.packageName = packageName;

        addOverlayEntry(overlayInternal, entryDefinitions);

        return overlayInternal;
    }

    @Test
    public void registerOverlay_forAndroidPackage_shouldFail() {
        FabricatedOverlayInternal overlayInternal =
                createOverlayWithName(
                        mOverlayName,
                        "android",
                        List.of(Pair.create("color/white", Pair.create(null, Color.BLACK))));

        assertThrows(
                "Wrong target package name",
                IllegalArgumentException.class,
                () -> mOverlayManagerImpl.registerFabricatedOverlay(overlayInternal));
    }

    @Test
    public void getOverlayInfosForTarget_defaultShouldBeZero() {
        List<OverlayInfo> overlayInfos =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName());

        Truth.assertThat(overlayInfos.size()).isEqualTo(0);
    }

    @Test
    public void unregisterNonExistingOverlay_shouldBeOk() {
        mOverlayManagerImpl.unregisterFabricatedOverlay("NotExisting");
    }

    @Test
    public void registerOverlay_createColorOverlay_shouldBeSavedInAndLoadFromFile()
            throws IOException, PackageManager.NameNotFoundException {
        FabricatedOverlayInternal overlayInternal =
                createOverlayWithName(
                        mOverlayName,
                        mContext.getPackageName(),
                        List.of(Pair.create(TARGET_COLOR_RES, Pair.create(null, Color.WHITE))));

        mOverlayManagerImpl.registerFabricatedOverlay(overlayInternal);
        final List<OverlayInfo> overlayInfos =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName());

        final int firstNumberOfOverlays = overlayInfos.size();
        expect.that(firstNumberOfOverlays).isEqualTo(1);
        final OverlayInfo overlayInfo = overlayInfos.get(0);
        expect.that(overlayInfo).isNotNull();
        Truth.assertThat(expect.hasFailures()).isFalse();
        expect.that(overlayInfo.isFabricated()).isTrue();
        expect.that(overlayInfo.getOverlayName()).isEqualTo(mOverlayName);
        expect.that(overlayInfo.getPackageName()).isEqualTo(mContext.getPackageName());
        expect.that(overlayInfo.getTargetPackageName()).isEqualTo(mContext.getPackageName());
        expect.that(overlayInfo.getUserId()).isEqualTo(mContext.getUserId());
    }

    @Test
    public void registerOverlay_createStringOverlay_shouldBeSavedInAndLoadFromFile()
            throws IOException, PackageManager.NameNotFoundException {
        FabricatedOverlayInternal overlayInternal =
                createOverlayWithName(
                        mOverlayName,
                        mContext.getPackageName(),
                        List.of(Pair.create(TARGET_STRING_RES, Pair.create(null, "HELLO"))));

        mOverlayManagerImpl.registerFabricatedOverlay(overlayInternal);
        final List<OverlayInfo> overlayInfos =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName());

        final int firstNumberOfOverlays = overlayInfos.size();
        expect.that(firstNumberOfOverlays).isEqualTo(1);
        final OverlayInfo overlayInfo = overlayInfos.get(0);
        expect.that(overlayInfo).isNotNull();
        Truth.assertThat(expect.hasFailures()).isFalse();
        expect.that(overlayInfo.isFabricated()).isTrue();
        expect.that(overlayInfo.getOverlayName()).isEqualTo(mOverlayName);
        expect.that(overlayInfo.getPackageName()).isEqualTo(mContext.getPackageName());
        expect.that(overlayInfo.getTargetPackageName()).isEqualTo(mContext.getPackageName());
        expect.that(overlayInfo.getUserId()).isEqualTo(mContext.getUserId());
    }

    @Test
    public void registerOverlay_createFileOverlay_shouldBeSavedInAndLoadFromFile()
            throws IOException, PackageManager.NameNotFoundException {
        ParcelFileDescriptor parcelFileDescriptor = mContext.getResources()
                .openRawResourceFd(R.raw.overlay_drawable).getParcelFileDescriptor();
        FabricatedOverlayInternal overlayInternal =
                createOverlayWithName(
                        mOverlayName,
                        mContext.getPackageName(),
                        List.of(Pair.create(TARGET_DRAWABLE_RES,
                                            Pair.create(null, parcelFileDescriptor))));

        mOverlayManagerImpl.registerFabricatedOverlay(overlayInternal);
        final List<OverlayInfo> overlayInfos =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName());

        final int firstNumberOfOverlays = overlayInfos.size();
        expect.that(firstNumberOfOverlays).isEqualTo(1);
        final OverlayInfo overlayInfo = overlayInfos.get(0);
        expect.that(overlayInfo).isNotNull();
        Truth.assertThat(expect.hasFailures()).isFalse();
        expect.that(overlayInfo.isFabricated()).isTrue();
        expect.that(overlayInfo.getOverlayName()).isEqualTo(mOverlayName);
        expect.that(overlayInfo.getPackageName()).isEqualTo(mContext.getPackageName());
        expect.that(overlayInfo.getTargetPackageName()).isEqualTo(mContext.getPackageName());
        expect.that(overlayInfo.getUserId()).isEqualTo(mContext.getUserId());
    }

    @Test
    public void registerOverlay_notExistedResource_shouldFailWithoutSavingAnyFile()
            throws IOException {
        FabricatedOverlayInternal overlayInternal =
                createOverlayWithName(
                        mOverlayName,
                        mContext.getPackageName(),
                        List.of(Pair.create("color/not_existed", Pair.create(null, "HELLO"))));

        assertThrows(IOException.class,
                () -> mOverlayManagerImpl.registerFabricatedOverlay(overlayInternal));
        final List<OverlayInfo> overlayInfos =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName());
        final int firstNumberOfOverlays = overlayInfos.size();
        expect.that(firstNumberOfOverlays).isEqualTo(0);
        final int[] fileCounts = new int[1];
        Files.walkFileTree(
                mContext.getDir(SELF_TARGET, MODE_PRIVATE).toPath(),
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        fileCounts[0]++;
                        return super.visitFile(file, attrs);
                    }
                });
        expect.that(fileCounts[0]).isEqualTo(0);
    }

    @Test
    public void registerMultipleOverlays_shouldMatchTheNumberOfOverlays()
            throws IOException, PackageManager.NameNotFoundException {
        final String secondOverlayName = mOverlayName + "2nd";
        final int initNumberOfOverlays =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName()).size();

        FabricatedOverlayInternal overlayInternal =
                createOverlayWithName(
                        mOverlayName,
                        mContext.getPackageName(),
                        List.of(Pair.create(TARGET_COLOR_RES, Pair.create(null, Color.WHITE))));
        mOverlayManagerImpl.registerFabricatedOverlay(overlayInternal);
        final int firstNumberOfOverlays =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName()).size();
        overlayInternal =
                createOverlayWithName(
                        secondOverlayName,
                        mContext.getPackageName(),
                        List.of(Pair.create(TARGET_COLOR_RES, Pair.create(null, Color.WHITE))));
        mOverlayManagerImpl.registerFabricatedOverlay(overlayInternal);
        final int secondNumberOfOverlays =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName()).size();
        mOverlayManagerImpl.unregisterFabricatedOverlay(mOverlayName);
        mOverlayManagerImpl.unregisterFabricatedOverlay(secondOverlayName);
        final int finalNumberOfOverlays =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName()).size();

        expect.that(initNumberOfOverlays).isEqualTo(0);
        expect.that(firstNumberOfOverlays).isEqualTo(1);
        expect.that(secondNumberOfOverlays).isEqualTo(2);
        expect.that(finalNumberOfOverlays).isEqualTo(0);
    }

    @Test
    public void unregisterOverlay_withIllegalOverlayName_shouldFail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mOverlayManagerImpl.unregisterFabricatedOverlay("../../etc/password"));
    }

    @Test
    public void registerTheSameOverlay_shouldNotIncreaseTheNumberOfOverlays()
            throws IOException, PackageManager.NameNotFoundException {
        final int initNumberOfOverlays =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName()).size();

        FabricatedOverlayInternal overlayInternal =
                createOverlayWithName(
                        mOverlayName,
                        mContext.getPackageName(),
                        List.of(Pair.create(TARGET_COLOR_RES, Pair.create(null, Color.WHITE))));
        mOverlayManagerImpl.registerFabricatedOverlay(overlayInternal);
        final int firstNumberOfOverlays =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName()).size();
        overlayInternal =
                createOverlayWithName(
                        mOverlayName,
                        mContext.getPackageName(),
                        List.of(Pair.create(TARGET_COLOR_RES, Pair.create(null, Color.WHITE))));
        mOverlayManagerImpl.registerFabricatedOverlay(overlayInternal);
        final int secondNumberOfOverlays =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName()).size();
        mOverlayManagerImpl.unregisterFabricatedOverlay(mOverlayName);
        final int finalNumberOfOverlays =
                mOverlayManagerImpl.getOverlayInfosForTarget(mContext.getPackageName()).size();

        expect.that(initNumberOfOverlays).isEqualTo(0);
        expect.that(firstNumberOfOverlays).isEqualTo(1);
        expect.that(secondNumberOfOverlays).isEqualTo(1);
        expect.that(finalNumberOfOverlays).isEqualTo(0);
    }

    @Test
    public void registerOverlay_packageNotOwnedBySelf_shouldFail() {
        FabricatedOverlayInternal overlayInternal = new FabricatedOverlayInternal();
        overlayInternal.packageName = "com.android.systemui";
        overlayInternal.overlayName = mOverlayName;
        overlayInternal.targetOverlayable = "non-existed-target-overlayable";
        overlayInternal.targetPackageName = mContext.getPackageName();
        addOverlayEntry(
                overlayInternal,
                List.of(Pair.create("color/white", Pair.create(null, Color.BLACK))));

        assertThrows(
                "The context doesn't own the package",
                IllegalArgumentException.class,
                () -> mOverlayManagerImpl.registerFabricatedOverlay(overlayInternal));
    }

    @Test
    public void ensureBaseDir_forOtherPackage_shouldFail()
            throws PackageManager.NameNotFoundException {
        final Context fakeContext =
                mContext.createPackageContext("com.android.systemui", 0 /* flags */);
        final OverlayManagerImpl overlayManagerImpl = new OverlayManagerImpl(fakeContext);

        assertThrows(IllegalArgumentException.class, overlayManagerImpl::ensureBaseDir);
    }

    @Test
    public void newOverlayManagerImpl_forOtherUser_shouldFail() {
        Context fakeContext =
                new ContextWrapper(mContext) {
                    @Override
                    public UserHandle getUser() {
                        return UserHandle.of(100);
                    }

                    @Override
                    public int getUserId() {
                        return 100;
                    }
                };

        assertThrows(SecurityException.class, () -> new OverlayManagerImpl(fakeContext));
    }
}
