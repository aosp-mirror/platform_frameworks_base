/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os;

// Mocked class for generation compilation tests purposes only.
public class IpcDataCache<Input, Output> {
    public static class Config {
        public Config(int max, String module, String api, String name) {
        }
    }

    /** Shadow method for generated code compilation tests purposes only.
     *
     * @param query - shadow parameter from IpcDataCache in Frameworks.
     * @return null
     */
    public Output query(Input query) {
        return null;
    }

    /** Shadow method for generated code compilation tests purposes only.
     *
     * @param key - shadow parameter from IpcDataCache in Frameworks;
     */
    public static void invalidateCache(String key) {
    }

    /** Shadow method for generated code compilation tests purposes only.
     *
     * @param query - shadow parameter from IpcDataCache in Frameworks;
     * @return null
     */
    public Output recompute(Input query) {
        return null;
    }

    /** Shadow method for generated code compilation tests purposes only.
     *
     * @param query - parameter equivalent to IpcDataCache in android framework.
     * @param query - shadow parameter from IpcDataCache in Frameworks;
     * @return false
     */
    public boolean bypass(Input query) {
        return false;
    }

    /** Shadow method for generated code compilation tests purposes only.
     *
     * @param module - parameter equivalent to IpcDataCache in android framework.
     * @param key - parameter equivalent to IpcDataCache in android framework.
     * @return module + key sttring
     */
    public static String createPropertyName(String module, String key) {
        return module + key;
    }

    public abstract static class QueryHandler<Input, Output> {
        /** Shadow method for generated code compilation tests purposes only.
         *
         * @param query - parameter equivalent to IpcDataCache.QueryHandler in android framework.
         * @return expected value
         */
        public abstract Output apply(Input query);
        /** Shadow method for generated code compilation tests purposes only.
         *
         * @param query - parameter equivalent to IpcDataCache.QueryHandler in android framework.
         */
        public boolean shouldBypassCache(Input query) {
            return false;
        }
    }

    public interface RemoteCall<Input, Output> {
        /** Shadow method for generated code compilation tests purposes only.
         *
         * @param query - parameter equivalent to IpcDataCache.RemoteCall in android framework.
         */
        Output apply(Input query);
    }

    public interface BypassCall<Input> {
        /** Shadow method for generated code compilation tests purposes only.
         *
         * @param query - parameter equivalent to IpcDataCache.BypassCall in android framework.
         */
        boolean apply(Input query);
    }

    public IpcDataCache(
            int maxEntries,
            String module,
            String api,
            String cacheName,
            QueryHandler<Input, Output> computer) {
    }

    public IpcDataCache(Config config, QueryHandler<Input, Output> computer) {
    }

    public IpcDataCache(Config config, RemoteCall<Input, Output> computer) {
    }

    public IpcDataCache(Config config, RemoteCall<Input, Output> computer,
            BypassCall<Input> bypassCall) {
    }

    /** Shadow method for generated code compilation tests purposes only.*/
    public void invalidateCache() {
    }


    /** Shadow method for generated code compilation tests purposes only.
     *
     * @param module - shadow parameter from IpcDataCache in Frameworks.
     * @param api - shadow parameter from IpcDataCache in Frameworks.
     */
    public static void invalidateCache(String module, String api) {
    }

}
