/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.app;

import android.service.voice.VisualQueryAttentionResult;

/**
 * Allows sysui to notify users the assistant is ready to take a query without notifying the
 * assistant app.
 */
oneway interface IVisualQueryDetectionAttentionListener {
   /**
    * Called when attention signal is sent.
    */
   void onAttentionGained(in VisualQueryAttentionResult attentionResult);

   /**
    * Called when a attention signal is lost for a certain interaction intention.
    */
   void onAttentionLost(int interactionIntention);
}