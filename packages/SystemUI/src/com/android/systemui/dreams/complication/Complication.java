/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.complication;

import android.annotation.IntDef;
import android.view.View;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * {@link Complication} is an interface for defining a complication, a visual component rendered
 * above a dream. {@link Complication} instances encapsulate the logic for generating the view to be
 * shown, along with the supporting control/logic. The decision for including the
 * {@link Complication} is not the responsibility of the {@link Complication}. This is instead
 * handled by domain logic and invocations to add and remove the {@link Complication} through
 * {@link com.android.systemui.dreams.DreamOverlayStateController#addComplication(Complication)} and
 * {@link com.android.systemui.dreams.DreamOverlayStateController#removeComplication(Complication)}.
 * A {@link Complication} also does not represent a specific instance of the view. Instead, it
 * should be viewed as a provider, where view instances are requested from it. The associated
 * {@link ViewHolder} interface is requested for each view request. This object is retained for the
 * view's lifetime, providing a container for any associated logic. The complication rendering
 * system will consult this {@link ViewHolder} for {@link View} to show and
 * {@link ComplicationLayoutParams} to position the view. {@link ComplicationLayoutParams} allow for
 * specifying the sizing and position of the {@link Complication}.
 *
 * The following code sample exhibits the entities and lifecycle involved with a
 * {@link Complication}.
 *
 * <pre>{@code
 * // This component allows for the complication to generate a new ViewHolder for every request.
 * @Subcomponent
 * interface ExampleViewHolderComponent {
 *     @Subcomponent.Factory
 *     interface Factory {
 *         ExampleViewHolderComponent create();
 *     }
 *
 *     ExampleViewHolder getViewHolder();
 * }
 *
 * // An example entity that controls whether or not a complication should be included on dreams.
 * // Note how the complication is tracked by reference for removal.
 * public class ExampleComplicationProvider {
 *     private final DreamOverlayStateController mDreamOverlayStateController;
 *     private final ExampleComplication mComplication;
 *     @Inject
 *     public ExampleComplicationProvider(
 *             ExampleComplication complication,
 *             DreamOverlayStateController stateController) {
 *         mDreamOverlayStateController = stateController;
 *         mComplication = complication;
 *     }
 *
 *     public void onShowConditionsMet(boolean met) {
 *         if (met) {
 *             mDreamOverlayStateController.addComplication(mComplication);
 *         } else {
 *             mDreamOverlayStateController.removeComplication(mComplication);
 *         }
 *     }
 * }
 *
 * // An example complication. Note how a factory is created to supply a unique ViewHolder for each
 * // request. Also, there is no particular view instance members defined in the complication.
 * class ExampleComplication implements Complication {
 *     private final ExampleViewHolderComponent.Factory mFactory;
 *     @Inject
 *     public ExampleComplication(ExampleViewHolderComponent.Factory viewHolderComponentFactory) {
 *         mFactory = viewHolderComponentFactory;
 *     }
 *
 *     @Override
 *     public ViewHolder createView(ComplicationViewModel model) {
 *         return mFactory.create().getViewHolder();
 *     }
 * }
 *
 * // Not every ViewHolder needs to include a view controller. It is included here as an example of
 * // how such logic can be contained and associated with the ViewHolder lifecycle.
 * class ExampleViewController extends ViewController<FrameLayout> {
 *     protected ExampleViewController(FrameLayout view) {
 *         super(view);
 *     }
 *
 *     @Override
 *     protected void onViewAttached() { }
 *
 *     @Override
 *     protected void onViewDetached() { }
 * }
 *
 * // An example ViewHolder. This is the correct place to contain any value/logic associated with a
 * // particular instance of the ComplicationView.
 * class ExampleViewHolder implements Complication.ViewHolder {
 *     final FrameLayout mView;
 *     final ExampleViewController mController;
 *
 *     @Inject
 *     public ExampleViewHolder(Context context) {
 *         mView = new FrameLayout(context);
 *         mController = new ExampleViewController(mView);
 *     }
 *     @Override
 *     public View getView() {
 *         return mView;
 *     }
 *
 *     @Override
 *     public ComplicationLayoutParams getLayoutParams() {
 *         return new ComplicationLayoutParams(
 *                 200,
 *                 100,
 *                 ComplicationLayoutParams.POSITION_TOP | ComplicationLayoutParams.DIRECTION_END,
 *                 ComplicationLayoutParams.DIRECTION_DOWN,
 *                 4);
 *     }
 * }
 * }
 * </pre>
 */
public interface Complication {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "CATEGORY_" }, value = {
            CATEGORY_STANDARD,
            CATEGORY_SYSTEM,
    })

    @interface Category {}
    /**
     * {@code CATEGORY_STANDARD} indicates the complication is a normal component. Rules and
     * settings, such as hiding all complications, will apply to this complication.
     */
    int CATEGORY_STANDARD = 1 << 0;
    /**
     * {@code CATEGORY_SYSTEM} indicates complications driven by SystemUI. Usually, these are
     * core components that are not user controlled. These can potentially deviate from given
     * rule sets that would normally apply to {@code CATEGORY_STANDARD}.
     */
    int CATEGORY_SYSTEM = 1 << 1;

    /**
     * The type of dream complications which can be provided by a {@link Complication}.
     */
    @IntDef(prefix = {"COMPLICATION_TYPE_"}, flag = true, value = {
            COMPLICATION_TYPE_NONE,
            COMPLICATION_TYPE_TIME,
            COMPLICATION_TYPE_DATE,
            COMPLICATION_TYPE_WEATHER,
            COMPLICATION_TYPE_AIR_QUALITY,
            COMPLICATION_TYPE_CAST_INFO,
            COMPLICATION_TYPE_HOME_CONTROLS,
            COMPLICATION_TYPE_SMARTSPACE,
            COMPLICATION_TYPE_MEDIA_ENTRY
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ComplicationType {}

    int COMPLICATION_TYPE_NONE = 0;
    int COMPLICATION_TYPE_TIME = 1;
    int COMPLICATION_TYPE_DATE = 1 << 1;
    int COMPLICATION_TYPE_WEATHER = 1 << 2;
    int COMPLICATION_TYPE_AIR_QUALITY = 1 << 3;
    int COMPLICATION_TYPE_CAST_INFO = 1 << 4;
    int COMPLICATION_TYPE_HOME_CONTROLS = 1 << 5;
    int COMPLICATION_TYPE_SMARTSPACE = 1 << 6;
    int COMPLICATION_TYPE_MEDIA_ENTRY = 1 << 7;

    /**
     * The {@link Host} interface specifies a way a {@link Complication} to communicate with its
     * parent entity for information and actions.
     */
    interface Host {
        /**
         * Called to signal a {@link Complication} has requested to exit the dream.
         */
        void requestExitDream();
    }

    /**
     * The implementation of this interface is in charge of managing the visible state of
     * the shown complication.
     */
    interface VisibilityController {
        /**
         * Called to set the visibility of all shown and future complications. Changes in visibility
         * will always be animated.
         * @param visibility The desired future visibility.
         */
        void setVisibility(@View.Visibility int visibility);
    }

    /**
     * Returned through {@link Complication#createView(ComplicationViewModel)}, {@link ViewHolder}
     * is a container for a single {@link Complication} instance. The {@link Host} guarantees that
     * the {@link ViewHolder} will be retained for the lifetime of the {@link Complication}
     * instance's user. The view is responsible for providing the view that represents the
     * {@link Complication}. This object is the proper place to store any related entities, such as
     * a {@link com.android.systemui.util.ViewController} for the view.
     */
    interface ViewHolder {
        /**
         * Returns the {@link View} associated with the {@link ViewHolder}. This {@link View} should
         * be stable and generated once.
         * @return
         */
        View getView();

        /**
         * Returns the {@link Category} associated with the {@link Complication}. {@link Category}
         * is a grouping which helps define the relationship of the {@link Complication} to
         * System UI and the rest of the system. It is used for presentation and other decisions.
         */
        @Complication.Category
        default int getCategory() {
            return Complication.CATEGORY_STANDARD;
        }

        /**
         * Returns the {@link ComplicationLayoutParams} associated with this complication. The
         * values expressed here are treated as preference rather than requirement. The hosting
         * entity is free to modify/interpret the parameters as deemed fit.
         */
        ComplicationLayoutParams getLayoutParams();
    }

    /**
     * Generates a {@link ViewHolder} for the {@link Complication}. This captures both the view and
     * control logic for a single instance of the complication. The {@link Complication} may be
     * asked at any time to generate another view.
     * @param model The {@link ComplicationViewModel} associated with this particular
     *              {@link Complication} instance.
     * @return a {@link ViewHolder} for this {@link Complication} instance.
     */
    ViewHolder createView(ComplicationViewModel model);

    /**
     * Returns the types that must be present in order for this complication to participate on
     * the dream overlay. By default, this method returns
     * {@code Complication.COMPLICATION_TYPE_NONE} to indicate no types are required.
     * @return
     */
    @Complication.ComplicationType
    default int getRequiredTypeAvailability() {
        return Complication.COMPLICATION_TYPE_NONE;
    }
}
