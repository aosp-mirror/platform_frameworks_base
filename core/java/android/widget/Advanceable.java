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

package android.widget;

/**
 * This interface can be implemented by any collection-type view which has a notion of
 * progressing through its set of children. The interface exists to give AppWidgetHosts a way of
 * taking responsibility for automatically advancing such collections.
 *
 * @hide
 */
public interface Advanceable {

    /**
     * Advances this collection, eg. shows the next view.
     */
    public void advance();

    /**
     * Called by the AppWidgetHost once before it begins to call advance(), allowing the
     * collection to do any required setup.
     */
    public void fyiWillBeAdvancedByHostKThx();
}
