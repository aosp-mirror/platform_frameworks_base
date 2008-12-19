/*
 * Copyright (C) 2008 The Android Open Source Project
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
package android.im;

/**
 * @hide
 */
interface IImPlugin {
    /**
     * Notify the plugin the front door activity is created. This gives the plugin a chance to
     * start its own servics, etc.
     */
    void onStart();
    
    /**
     * Notify the plugin the front door activity is stopping.
     */
    void onStop();

    /**
     * Sign in to the service for the account passed in.
     *
     * @param account the account id for the accont to be signed into.
     */
    void signIn(long account);

    /**
     * Sign out of the service for the account passed in.
     *
     * @param account the account id for the accont to be signed out of.
     */
    void signOut(long account);

    /**
     * Returns the package name used to load the resources for the given provider name.
     *
     * @return The package name to load the resourcs for the given provider.
     */
    String getResourcePackageNameForProvider(String providerName);

    /**
     * Returns a map of branding resources for the given provider. The keys are defined
     * in {@link android.im.BrandingResourceIDs}. The values are the resource identifiers generated
     * by the aapt tool.
     *
     * @return The map of branding resources for the given provider.
     */
    Map getResourceMapForProvider(String providerName);

    /*
     * Returns a list of supported IM providers.
     *
     * @return a List of supported providers.
     */
    List getSupportedProviders();
}
