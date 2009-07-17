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
package android.pim.vcard;

/**
 * Unlike VCardBuilderBase, this (and VCardDataBuilder) assumes
 * "each VCard entry should be correctly parsed and passed to each EntryHandler object",
 */
public interface EntryHandler {
    /**
     * Able to be use this method for showing performance log, etc.
     * TODO: better name?
     */
    public void onFinal();

    /**
     * The method called when one VCard entry is successfully created
     */
    public void onEntryCreated(final ContactStruct entry);
}
