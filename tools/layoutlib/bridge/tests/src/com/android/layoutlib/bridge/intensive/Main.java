/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.intensive;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.RenderSession;
import com.android.ide.common.rendering.api.Result;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.ide.common.rendering.api.SessionParams.RenderingMode;
import com.android.ide.common.resources.FrameworkResources;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.ResourceRepository;
import com.android.ide.common.resources.ResourceResolver;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.io.FolderWrapper;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.intensive.setup.ConfigGenerator;
import com.android.layoutlib.bridge.intensive.setup.LayoutLibTestCallback;
import com.android.layoutlib.bridge.intensive.setup.LayoutPullParser;
import com.android.resources.Density;
import com.android.resources.Navigation;
import com.android.utils.ILogger;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;

import static org.junit.Assert.fail;

/**
 * This is a set of tests that loads all the framework resources and a project checked in this
 * test's resources. The main dependencies
 * are:
 * 1. Fonts directory.
 * 2. Framework Resources.
 * 3. App resources.
 * 4. build.prop file
 *
 * These are configured by two variables set in the system properties.
 *
 * 1. platform.dir: This is the directory for the current platform in the built SDK
 *     (.../sdk/platforms/android-<version>).
 *
 *     The fonts are platform.dir/data/fonts.
 *     The Framework resources are platform.dir/data/res.
 *     build.prop is at platform.dir/build.prop.
 *
 * 2. test_res.dir: This is the directory for the resources of the test. If not specified, this
 *     falls back to getClass().getProtectionDomain().getCodeSource().getLocation()
 *
 *     The app resources are at: test_res.dir/testApp/MyApplication/app/src/main/res
 */
public class Main {

    private static final String PLATFORM_DIR_PROPERTY = "platform.dir";
    private static final String RESOURCE_DIR_PROPERTY = "test_res.dir";

    private static final String PLATFORM_DIR;
    private static final String TEST_RES_DIR;
    /** Location of the app to test inside {@link #TEST_RES_DIR}*/
    private static final String APP_TEST_DIR = "/testApp/MyApplication";
    /** Location of the app's res dir inside {@link #TEST_RES_DIR}*/
    private static final String APP_TEST_RES = APP_TEST_DIR + "/src/main/res";

    private static LayoutLog sLayoutLibLog;
    private static FrameworkResources sFrameworkRepo;
    private static ResourceRepository sProjectResources;
    private static  ILogger sLogger;
    private static Bridge sBridge;

    static {
        // Test that System Properties are properly set.
        PLATFORM_DIR = getPlatformDir();
        if (PLATFORM_DIR == null) {
            fail(String.format("System Property %1$s not properly set. The value is %2$s",
                    PLATFORM_DIR_PROPERTY, System.getProperty(PLATFORM_DIR_PROPERTY)));
        }

        TEST_RES_DIR = getTestResDir();
        if (TEST_RES_DIR == null) {
            fail(String.format("System property %1$s.dir not properly set. The value is %2$s",
                    RESOURCE_DIR_PROPERTY, System.getProperty(RESOURCE_DIR_PROPERTY)));
        }
    }

    private static String getPlatformDir() {
        String platformDir = System.getProperty(PLATFORM_DIR_PROPERTY);
        if (platformDir != null && !platformDir.isEmpty() && new File(platformDir).isDirectory()) {
            return platformDir;
        }
        // System Property not set. Try to find the directory in the build directory.
        String androidHostOut = System.getenv("ANDROID_HOST_OUT");
        if (androidHostOut != null) {
            platformDir = getPlatformDirFromHostOut(new File(androidHostOut));
            if (platformDir != null) {
                return platformDir;
            }
        }
        String workingDirString = System.getProperty("user.dir");
        File workingDir = new File(workingDirString);
        // Test if workingDir is android checkout root.
        platformDir = getPlatformDirFromRoot(workingDir);
        if (platformDir != null) {
            return platformDir;
        }

        // Test if workingDir is platform/frameworks/base/tools/layoutlib/bridge.
        File currentDir = workingDir;
        if (currentDir.getName().equalsIgnoreCase("bridge")) {
            currentDir = currentDir.getParentFile();
        }
        // Test if currentDir is  platform/frameworks/base/tools/layoutlib. That is, root should be
        // workingDir/../../../../  (4 levels up)
        for (int i = 0; i < 4; i++) {
            if (currentDir != null) {
                currentDir = currentDir.getParentFile();
            }
        }
        return currentDir == null ? null : getPlatformDirFromRoot(currentDir);
    }

    private static String getPlatformDirFromRoot(File root) {
        if (!root.isDirectory()) {
            return null;
        }
        File out = new File(root, "out");
        if (!out.isDirectory()) {
            return null;
        }
        File host = new File(out, "host");
        if (!host.isDirectory()) {
            return null;
        }
        File[] hosts = host.listFiles(new FileFilter() {
            @Override
            public boolean accept(File path) {
                return path.isDirectory() && (path.getName().startsWith("linux-") || path.getName()
                        .startsWith("darwin-"));
            }
        });
        for (File hostOut : hosts) {
            String platformDir = getPlatformDirFromHostOut(hostOut);
            if (platformDir != null) {
                return platformDir;
            }
        }
        return null;
    }

    private static String getPlatformDirFromHostOut(File out) {
        if (!out.isDirectory()) {
            return null;
        }
        File sdkDir = new File(out, "sdk");
        if (!sdkDir.isDirectory()) {
            return null;
        }
        File[] sdkDirs = sdkDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File path) {
                // We need to search for $TARGET_PRODUCT (usually, sdk_phone_armv7)
                return path.isDirectory() && path.getName().startsWith("sdk");
            }
        });
        for (File dir : sdkDirs) {
            String platformDir = getPlatformDirFromHostOutSdkSdk(dir);
            if (platformDir != null) {
                return platformDir;
            }
        }
        return null;
    }

    private static String getPlatformDirFromHostOutSdkSdk(File sdkDir) {
        File[] possibleSdks = sdkDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File path) {
                return path.isDirectory() && path.getName().contains("android-sdk");
            }
        });
        for (File possibleSdk : possibleSdks) {
            File platformsDir = new File(possibleSdk, "platforms");
            File[] platforms = platformsDir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File path) {
                    return path.isDirectory() && path.getName().startsWith("android-");
                }
            });
            if (platforms == null || platforms.length == 0) {
                continue;
            }
            Arrays.sort(platforms, new Comparator<File>() {
                // Codenames before ints. Higher APIs precede lower.
                @Override
                public int compare(File o1, File o2) {
                    final int MAX_VALUE = 1000;
                    String suffix1 = o1.getName().substring("android-".length());
                    String suffix2 = o2.getName().substring("android-".length());
                    int suff1, suff2;
                    try {
                        suff1 = Integer.parseInt(suffix1);
                    } catch (NumberFormatException e) {
                        suff1 = MAX_VALUE;
                    }
                    try {
                        suff2 = Integer.parseInt(suffix2);
                    } catch (NumberFormatException e) {
                        suff2 = MAX_VALUE;
                    }
                    if (suff1 != MAX_VALUE || suff2 != MAX_VALUE) {
                        return suff2 - suff1;
                    }
                    return suffix2.compareTo(suffix1);
                }
            });
            return platforms[0].getAbsolutePath();
        }
        return null;
    }

    private static String getTestResDir() {
        String resourceDir = System.getProperty(RESOURCE_DIR_PROPERTY);
        if (resourceDir != null && !resourceDir.isEmpty() && new File(resourceDir).isDirectory()) {
            return resourceDir;
        }
        // TEST_RES_DIR not explicitly set. Fallback to the class's source location.
        try {
            URL location = Main.class.getProtectionDomain().getCodeSource().getLocation();
            return new File(location.getPath()).exists() ? location.getPath() : null;
        } catch (NullPointerException e) {
            // Prevent a lot of null checks by just catching the exception.
            return null;
        }
    }
    /**
     * Initialize the bridge and the resource maps.
     */
    @BeforeClass
    public static void setUp() {
        File data_dir = new File(PLATFORM_DIR, "data");
        File res = new File(data_dir, "res");
        sFrameworkRepo = new FrameworkResources(new FolderWrapper(res));
        sFrameworkRepo.loadResources();
        sFrameworkRepo.loadPublicResources(getLogger());

        sProjectResources =
                new ResourceRepository(new FolderWrapper(TEST_RES_DIR + APP_TEST_RES), false) {
            @NonNull
            @Override
            protected ResourceItem createResourceItem(@NonNull String name) {
                return new ResourceItem(name);
            }
        };
        sProjectResources.loadResources();

        File fontLocation = new File(data_dir, "fonts");
        File buildProp = new File(PLATFORM_DIR, "build.prop");
        File attrs = new File(res, "values" + File.separator + "attrs.xml");
        sBridge = new Bridge();
        sBridge.init(ConfigGenerator.loadProperties(buildProp), fontLocation,
                ConfigGenerator.getEnumMap(attrs), getLayoutLog());
    }

    /** Test activity.xml */
    @Test
    public void testActivity() throws ClassNotFoundException {
        renderAndVerify("activity.xml", "activity.png");

    }

    /** Test allwidgets.xml */
    @Test
    public void testAllWidgets() throws ClassNotFoundException {
        renderAndVerify("allwidgets.xml", "allwidgets.png");
    }

    @Test
    public void testArrayCheck() throws ClassNotFoundException {
        renderAndVerify("array_check.xml", "array_check.png");
    }

    @AfterClass
    public static void tearDown() {
        sLayoutLibLog = null;
        sFrameworkRepo = null;
        sProjectResources = null;
        sLogger = null;
        sBridge = null;
    }

    /** Test expand_layout.xml */
    @Test
    public void testExpand() throws ClassNotFoundException {
        // Create the layout pull parser.
        LayoutPullParser parser = new LayoutPullParser(APP_TEST_RES + "/layout/" +
                "expand_vert_layout.xml");
        // Create LayoutLibCallback.
        LayoutLibTestCallback layoutLibCallback = new LayoutLibTestCallback(getLogger());
        layoutLibCallback.initResources();

        ConfigGenerator customConfigGenerator = new ConfigGenerator()
                .setScreenWidth(300)
                .setScreenHeight(20)
                .setDensity(Density.XHIGH)
                .setNavigation(Navigation.NONAV);

        SessionParams params = getSessionParams(parser, customConfigGenerator,
                layoutLibCallback, "Theme.Material.NoActionBar.Fullscreen", false,
                RenderingMode.V_SCROLL, 22);

        renderAndVerify(params, "expand_vert_layout.png");

        customConfigGenerator = new ConfigGenerator()
                .setScreenWidth(20)
                .setScreenHeight(300)
                .setDensity(Density.XHIGH)
                .setNavigation(Navigation.NONAV);
        parser = new LayoutPullParser(APP_TEST_RES + "/layout/" +
                "expand_horz_layout.xml");
        params = getSessionParams(parser, customConfigGenerator,
                layoutLibCallback, "Theme.Material.NoActionBar.Fullscreen", false,
                RenderingMode.H_SCROLL, 22);

        renderAndVerify(params, "expand_horz_layout.png");
    }

    /**
     * Create a new rendering session and test that rendering given layout on nexus 5
     * doesn't throw any exceptions and matches the provided image.
     */
    private void renderAndVerify(SessionParams params, String goldenFileName)
            throws ClassNotFoundException {
        // TODO: Set up action bar handler properly to test menu rendering.
        // Create session params.
        RenderSession session = sBridge.createSession(params);

        if (!session.getResult().isSuccess()) {
            getLogger().error(session.getResult().getException(),
                    session.getResult().getErrorMessage());
        }
        // Render the session with a timeout of 50s.
        Result renderResult = session.render(50000);
        if (!renderResult.isSuccess()) {
            getLogger().error(session.getResult().getException(),
                    session.getResult().getErrorMessage());
        }
        try {
            String goldenImagePath = APP_TEST_DIR + "/golden/" + goldenFileName;
            ImageUtils.requireSimilar(goldenImagePath, session.getImage());
        } catch (IOException e) {
            getLogger().error(e, e.getMessage());
        }
    }

    /**
     * Create a new rendering session and test that rendering given layout on nexus 5
     * doesn't throw any exceptions and matches the provided image.
     */
    private void renderAndVerify(String layoutFileName, String goldenFileName)
            throws ClassNotFoundException {
        // Create the layout pull parser.
        LayoutPullParser parser = new LayoutPullParser(APP_TEST_RES + "/layout/" + layoutFileName);
        // Create LayoutLibCallback.
        LayoutLibTestCallback layoutLibCallback = new LayoutLibTestCallback(getLogger());
        layoutLibCallback.initResources();
        // TODO: Set up action bar handler properly to test menu rendering.
        // Create session params.
        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "AppTheme", true, RenderingMode.NORMAL, 22);
        renderAndVerify(params, goldenFileName);
    }

    /**
     * Uses Theme.Material and Target sdk version as 22.
     */
    private SessionParams getSessionParams(LayoutPullParser layoutParser,
            ConfigGenerator configGenerator, LayoutLibTestCallback layoutLibCallback,
            String themeName, boolean isProjectTheme, RenderingMode renderingMode, int targetSdk) {
        FolderConfiguration config = configGenerator.getFolderConfig();
        ResourceResolver resourceResolver =
                ResourceResolver.create(sProjectResources.getConfiguredResources(config),
                        sFrameworkRepo.getConfiguredResources(config),
                        themeName, isProjectTheme);

        return new SessionParams(
                layoutParser,
                renderingMode,
                null /*used for caching*/,
                configGenerator.getHardwareConfig(),
                resourceResolver,
                layoutLibCallback,
                0,
                targetSdk,
                getLayoutLog());
    }

    private static LayoutLog getLayoutLog() {
        if (sLayoutLibLog == null) {
            sLayoutLibLog = new LayoutLog() {
                @Override
                public void warning(String tag, String message, Object data) {
                    System.out.println("Warning " + tag + ": " + message);
                    failWithMsg(message);
                }

                @Override
                public void fidelityWarning(@Nullable String tag, String message,
                        Throwable throwable, Object data) {

                    System.out.println("FidelityWarning " + tag + ": " + message);
                    if (throwable != null) {
                        throwable.printStackTrace();
                    }
                    failWithMsg(message == null ? "" : message);
                }

                @Override
                public void error(String tag, String message, Object data) {
                    System.out.println("Error " + tag + ": " + message);
                    failWithMsg(message);
                }

                @Override
                public void error(String tag, String message, Throwable throwable, Object data) {
                    System.out.println("Error " + tag + ": " + message);
                    if (throwable != null) {
                        throwable.printStackTrace();
                    }
                    failWithMsg(message);
                }
            };
        }
        return sLayoutLibLog;
    }

    private static ILogger getLogger() {
        if (sLogger == null) {
            sLogger = new ILogger() {
                @Override
                public void error(Throwable t, @Nullable String msgFormat, Object... args) {
                    if (t != null) {
                        t.printStackTrace();
                    }
                    failWithMsg(msgFormat == null ? "" : msgFormat, args);
                }

                @Override
                public void warning(@NonNull String msgFormat, Object... args) {
                    failWithMsg(msgFormat, args);
                }

                @Override
                public void info(@NonNull String msgFormat, Object... args) {
                    // pass.
                }

                @Override
                public void verbose(@NonNull String msgFormat, Object... args) {
                    // pass.
                }
            };
        }
        return sLogger;
    }

    private static void failWithMsg(@NonNull String msgFormat, Object... args) {
        fail(args == null ? "" : String.format(msgFormat, args));
    }
}
