/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.common.speech;

import android.app.SearchDialog;

/**
 * Utilities for voice recognition implementations.
 *
 * @see android.app.RecognitionService
 */
public class Recognition {

    /**
     * The extra key used in an intent to the speech recognizer for voice search. Not
     * generally to be used by developers. The {@link SearchDialog} uses this, for example,
     * to set a calling package for identification by a voice search API. If this extra
     * is set by anyone but the system process, it should be overridden by the voice search
     * implementation.
     */
    public final static String EXTRA_CALLING_PACKAGE = "calling_package";

    private Recognition() { }   // don't instantiate
}
