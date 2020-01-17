/**
 * Copyright 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.app.appsearch;

import com.android.internal.infra.AndroidFuture;

/** {@hide} */
interface IAppSearchManager {
    /**
     * Sets the schema.
     *
     * @param schemaProto Serialized SchemaProto.
     * @param force Whether to apply the new schema even if it is incompatible. All incompatible
           documents will be deleted.
     * @param callback {@link AndroidFuture}&lt;{@link Void}&gt;. Will be completed with
     *     {@code null} upon successful completion of the setSchema call, or completed exceptionally
     *     if setSchema fails.
     */
    void setSchema(in byte[] schemaProto, boolean force, in AndroidFuture callback);
    void put(in byte[] documentBytes, in AndroidFuture callback);
}
