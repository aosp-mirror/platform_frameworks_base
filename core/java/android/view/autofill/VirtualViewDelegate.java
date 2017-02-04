/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.view.autofill;

import android.view.View;
import android.view.ViewStructure;

/**
 * This class is the contract a client should implement to enable support of a
 * virtual view hierarchy rooted at a given view for auto-fill purposes.
 *
 * <p>The view hierarchy is typically created through the
 * {@link View#onProvideAutoFillVirtualStructure(android.view.ViewStructure, int)} call and client
 * add virtual children by calling {@link ViewStructure#newChild(int, int, int)} or
 * {@link ViewStructure#asyncNewChild(int, int, int)}, where the client provides the
 * {@code virtualId} of the children - the same {@code virtualId} is used in the methods of this
 * class.
 *
 * <p>Objects of this class are typically created by overriding
 * {@link View#getAutoFillVirtualViewDelegate()} and saving the passed callback, which must
 * be notified upon changes on the hierarchy.
 *
 * <p>The main use case of these API is to enable custom views that draws its content - such as
 * {@link android.webkit.WebView} providers - to support the AutoFill Framework:
 *
 * <ol>
 *   <li>Client populates the virtual hierarchy on
 * {@link View#onProvideAutoFillVirtualStructure(android.view.ViewStructure, int)}
 *   <li>Android System generates the proper {@link AutoFillId} - encapsulating the view and the
 * virtual child ids - and pass it to the {@link android.service.autofill.AutoFillService}.
 *   <li>The service uses the {@link AutoFillId} to populate the auto-fill {@link Dataset}s and pass
 *   it back to the Android System.
 *   <li>Android System uses the {@link AutoFillId} to find the proper custom view and calls
 *   {@link #autoFill(int, AutoFillValue)} on that view passing the virtual id.
 *   <li>This provider than finds the child in the hierarchy and auto-fills it.
 * </ol>
 *
 */
public abstract class VirtualViewDelegate {

    /**
     * Auto-fills a virtual view with the {@code value}.
     *
     * @param virtualId id identifying the virtual child inside the custom view.
     * @param value value to be auto-filled.
     */
    public abstract void autoFill(int virtualId, AutoFillValue value);
}
