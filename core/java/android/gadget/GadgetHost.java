/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.gadget;

import android.content.Context;
import android.widget.RemoteViews;

/**
 * GadgetHost provides the interaction with the Gadget Service for apps,
 * like the home screen, that want to embed gadgets in their UI.
 */
public class GadgetHost {
    public GadgetHost(Context context, int hostId) {
    }

    /**
     * Start receiving onGadgetChanged calls for your gadgets.  Call this when your activity
     * becomes visible, i.e. from onStart() in your Activity.
     */
    public void startListening() {
    }

    /**
     * Stop receiving onGadgetChanged calls for your gadgets.  Call this when your activity is
     * no longer visible, i.e. from onStop() in your Activity.
     */
    public void stopListening() {
    }

    /**
     * Stop listening to changes for this gadget.
     */
    public void gadgetRemoved(int gadgetId) {
    }

    /**
     * Remove all records about gadget instances from the gadget manager.  Call this when
     * initializing your database, as it might be because of a data wipe.
     */
    public void clearGadgets() {
    }

    public final GadgetHostView createView(Context context, int gadgetId, GadgetInfo gadget) {
        GadgetHostView view = onCreateView(context, gadgetId, gadget);
        view.setGadget(gadgetId, gadget);
        view.updateGadget(null);
        return view;
    }

    /**
     * Called to create the GadgetHostView.  Override to return a custom subclass if you
     * need it.  {@more}
     */
    protected GadgetHostView onCreateView(Context context, int gadgetId, GadgetInfo gadget) {
        return new GadgetHostView(context);
    }
}

