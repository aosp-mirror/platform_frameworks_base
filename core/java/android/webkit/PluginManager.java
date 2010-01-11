/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.webkit;

import java.util.ArrayList;
import java.util.List;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.SystemProperties;
import android.util.Log;

/**
 * Class for managing the relationship between the {@link WebView} and installed
 * plugins in the system. You can find this class through
 * {@link PluginManager#getInstance}.
 * 
 * @hide pending API solidification
 */
public class PluginManager {

    /**
     * Service Action: A plugin wishes to be loaded in the WebView must provide
     * {@link android.content.IntentFilter IntentFilter} that accepts this
     * action in their AndroidManifest.xml.
     * <p>
     * TODO: we may change this to a new PLUGIN_ACTION if this is going to be
     * public.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String PLUGIN_ACTION = "android.webkit.PLUGIN";

    /**
     * A plugin wishes to be loaded in the WebView must provide this permission
     * in their AndroidManifest.xml.
     */
    public static final String PLUGIN_PERMISSION = "android.webkit.permission.PLUGIN";

    private static final String LOGTAG = "webkit";

    private static final String PLUGIN_TYPE = "type";
    private static final String TYPE_NATIVE = "native";

    private static PluginManager mInstance = null;

    private final Context mContext;

    private ArrayList<PackageInfo> mPackageInfoCache;

    // Only plugin matches one of the signatures in the list can be loaded
    // inside the WebView process
    private static final String SIGNATURE_1 = "308204c5308203ada003020102020900d7cb412f75f4887e300d06092a864886f70d010105050030819d310b3009060355040613025553311330110603550408130a43616c69666f726e69613111300f0603550407130853616e204a6f736531233021060355040a131a41646f62652053797374656d7320496e636f72706f7261746564311c301a060355040b1313496e666f726d6174696f6e2053797374656d73312330210603550403131a41646f62652053797374656d7320496e636f72706f7261746564301e170d3039313030313030323331345a170d3337303231363030323331345a30819d310b3009060355040613025553311330110603550408130a43616c69666f726e69613111300f0603550407130853616e204a6f736531233021060355040a131a41646f62652053797374656d7320496e636f72706f7261746564311c301a060355040b1313496e666f726d6174696f6e2053797374656d73312330210603550403131a41646f62652053797374656d7320496e636f72706f726174656430820120300d06092a864886f70d01010105000382010d0030820108028201010099724f3e05bbd78843794f357776e04b340e13cb1c9ccb3044865180d7d8fec8166c5bbd876da8b80aa71eb6ba3d4d3455c9a8de162d24a25c4c1cd04c9523affd06a279fc8f0d018f242486bdbb2dbfbf6fcb21ed567879091928b876f7ccebc7bccef157366ebe74e33ae1d7e9373091adab8327482154afc0693a549522f8c796dd84d16e24bb221f5dbb809ca56dd2b6e799c5fa06b6d9c5c09ada54ea4c5db1523a9794ed22a3889e5e05b29f8ee0a8d61efe07ae28f65dece2ff7edc5b1416d7c7aad7f0d35e8f4a4b964dbf50ae9aa6d620157770d974131b3e7e3abd6d163d65758e2f0822db9c88598b9db6263d963d13942c91fc5efe34fc1e06e3020103a382010630820102301d0603551d0e041604145af418e419a639e1657db960996364a37ef20d403081d20603551d230481ca3081c780145af418e419a639e1657db960996364a37ef20d40a181a3a481a030819d310b3009060355040613025553311330110603550408130a43616c69666f726e69613111300f0603550407130853616e204a6f736531233021060355040a131a41646f62652053797374656d7320496e636f72706f7261746564311c301a060355040b1313496e666f726d6174696f6e2053797374656d73312330210603550403131a41646f62652053797374656d7320496e636f72706f7261746564820900d7cb412f75f4887e300c0603551d13040530030101ff300d06092a864886f70d0101050500038201010076c2a11fe303359689c2ebc7b2c398eff8c3f9ad545cdbac75df63bf7b5395b6988d1842d6aa1556d595b5692e08224d667a4c9c438f05e74906c53dd8016dde7004068866f01846365efd146e9bfaa48c9ecf657f87b97c757da11f225c4a24177bf2d7188e6cce2a70a1e8a841a14471eb51457398b8a0addd8b6c8c1538ca8f1e40b4d8b960009ea22c188d28924813d2c0b4a4d334b7cf05507e1fcf0a06fe946c7ffc435e173af6fc3e3400643710acc806f830a14788291d46f2feed9fb5c70423ca747ed1572d752894ac1f19f93989766308579393fabb43649aa8806a313b1ab9a50922a44c2467b9062037f2da0d484d9ffd8fe628eeea629ba637";

    private static final Signature[] SIGNATURES = new Signature[] {
        new Signature(SIGNATURE_1)
    };

    private PluginManager(Context context) {
        mContext = context;
        mPackageInfoCache = new ArrayList<PackageInfo>();
    }

    public static synchronized PluginManager getInstance(Context context) {
        if (mInstance == null) {
            if (context == null) {
                throw new IllegalStateException(
                        "First call to PluginManager need a valid context.");
            }
            mInstance = new PluginManager(context.getApplicationContext());
        }
        return mInstance;
    }

    /**
     * Signal the WebCore thread to refresh its list of plugins. Use this if the
     * directory contents of one of the plugin directories has been modified and
     * needs its changes reflecting. May cause plugin load and/or unload.
     * 
     * @param reloadOpenPages Set to true to reload all open pages.
     */
    public void refreshPlugins(boolean reloadOpenPages) {
        BrowserFrame.sJavaBridge.obtainMessage(
                JWebCoreJavaBridge.REFRESH_PLUGINS, reloadOpenPages)
                .sendToTarget();
    }

    String[] getPluginDirectories() {

        ArrayList<String> directories = new ArrayList<String>();
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> plugins = pm.queryIntentServices(new Intent(
                PLUGIN_ACTION), PackageManager.GET_SERVICES
                | PackageManager.GET_META_DATA);

        synchronized(mPackageInfoCache) {

            // clear the list of existing packageInfo objects
            mPackageInfoCache.clear();

            for (ResolveInfo info : plugins) {

                // retrieve the plugin's service information
                ServiceInfo serviceInfo = info.serviceInfo;
                if (serviceInfo == null) {
                    Log.w(LOGTAG, "Ignore bad plugin");
                    continue;
                }

                // retrieve information from the plugin's manifest
                PackageInfo pkgInfo;
                try {
                    pkgInfo = pm.getPackageInfo(serviceInfo.packageName,
                                    PackageManager.GET_PERMISSIONS
                                    | PackageManager.GET_SIGNATURES);
                } catch (NameNotFoundException e) {
                    Log.w(LOGTAG, "Can't find plugin: " + serviceInfo.packageName);
                    continue;
                }
                if (pkgInfo == null) {
                    continue;
                }

                // check if their is a conflict in the lib directory names
                String directory = pkgInfo.applicationInfo.dataDir + "/lib";
                if (directories.contains(directory)) {
                    continue;
                }

                // check if the plugin has the required permissions
                String permissions[] = pkgInfo.requestedPermissions;
                if (permissions == null) {
                    continue;
                }
                boolean permissionOk = false;
                for (String permit : permissions) {
                    if (PLUGIN_PERMISSION.equals(permit)) {
                        permissionOk = true;
                        break;
                    }
                }
                if (!permissionOk) {
                    continue;
                }

                // check to ensure the plugin is properly signed
                Signature signatures[] = pkgInfo.signatures;
                if (signatures == null) {
                    continue;
                }
                if (SystemProperties.getBoolean("ro.secure", false)) {
                    boolean signatureMatch = false;
                    for (Signature signature : signatures) {
                        for (int i = 0; i < SIGNATURES.length; i++) {
                            if (SIGNATURES[i].equals(signature)) {
                                signatureMatch = true;
                                break;
                            }
                        }
                    }
                    if (!signatureMatch) {
                        continue;
                    }
                }

                // determine the type of plugin from the manifest
                if (serviceInfo.metaData == null) {
                    Log.e(LOGTAG, "The plugin '" + serviceInfo.name + "' has no type defined");
                    continue;
                }

                String pluginType = serviceInfo.metaData.getString(PLUGIN_TYPE);
                if (!TYPE_NATIVE.equals(pluginType)) {
                    Log.e(LOGTAG, "Unrecognized plugin type: " + pluginType);
                    continue;
                }

                try {
                    Class<?> cls = getPluginClass(serviceInfo.packageName, serviceInfo.name);

                    //TODO implement any requirements of the plugin class here!
                    boolean classFound = true;

                    if (!classFound) {
                        Log.e(LOGTAG, "The plugin's class' " + serviceInfo.name + "' does not extend the appropriate class.");
                        continue;
                    }

                } catch (NameNotFoundException e) {
                    Log.e(LOGTAG, "Can't find plugin: " + serviceInfo.packageName);
                    continue;
                } catch (ClassNotFoundException e) {
                    Log.e(LOGTAG, "Can't find plugin's class: " + serviceInfo.name);
                    continue;
                }

                // if all checks have passed then make the plugin available
                mPackageInfoCache.add(pkgInfo);
                directories.add(directory);
            }
        }

        return directories.toArray(new String[directories.size()]);
    }

    /* package */
    String getPluginsAPKName(String pluginLib) {

        // basic error checking on input params
        if (pluginLib == null || pluginLib.length() == 0) {
            return null;
        }

        // must be synchronized to ensure the consistency of the cache
        synchronized(mPackageInfoCache) {
            for (PackageInfo pkgInfo : mPackageInfoCache) {
                if (pluginLib.startsWith(pkgInfo.applicationInfo.dataDir)) {
                    return pkgInfo.packageName;
                }
            }
        }

        // if no apk was found then return null
        return null;
    }

    String getPluginSharedDataDirectory() {
        return mContext.getDir("plugins", 0).getPath();
    }

    /* package */
    Class<?> getPluginClass(String packageName, String className)
            throws NameNotFoundException, ClassNotFoundException {
        Context pluginContext = mContext.createPackageContext(packageName,
                Context.CONTEXT_INCLUDE_CODE |
                Context.CONTEXT_IGNORE_SECURITY);
        ClassLoader pluginCL = pluginContext.getClassLoader();
        return pluginCL.loadClass(className);
    }
}
