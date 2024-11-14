/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.compose.animation.scene

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transformation.AnchoredSize
import com.android.compose.animation.scene.transformation.AnchoredTranslate
import com.android.compose.animation.scene.transformation.DrawScale
import com.android.compose.animation.scene.transformation.EdgeTranslate
import com.android.compose.animation.scene.transformation.Fade
import com.android.compose.animation.scene.transformation.OverscrollTranslate
import com.android.compose.animation.scene.transformation.ScaleSize
import com.android.compose.animation.scene.transformation.SharedElementTransformation
import com.android.compose.animation.scene.transformation.Transformation
import com.android.compose.animation.scene.transformation.TransformationMatcher
import com.android.compose.animation.scene.transformation.TransformationRange
import com.android.compose.animation.scene.transformation.Translate

internal fun transitionsImpl(builder: SceneTransitionsBuilder.() -> Unit): SceneTransitions {
    val impl = SceneTransitionsBuilderImpl().apply(builder)
    return SceneTransitions(
        impl.defaultSwipeSpec,
        impl.transitionSpecs,
        impl.transitionOverscrollSpecs,
        impl.interruptionHandler,
        impl.defaultOverscrollProgressConverter,
    )
}

private class SceneTransitionsBuilderImpl : SceneTransitionsBuilder {
    override var defaultSwipeSpec: SpringSpec<Float> = SceneTransitions.DefaultSwipeSpec
    override var interruptionHandler: InterruptionHandler = DefaultInterruptionHandler
    override var defaultOverscrollProgressConverter: ProgressConverter = ProgressConverter.Default

    val transitionSpecs = mutableListOf<TransitionSpecImpl>()
    val transitionOverscrollSpecs = mutableListOf<OverscrollSpecImpl>()

    override fun to(
        to: ContentKey,
        key: TransitionKey?,
        preview: (TransitionBuilder.() -> Unit)?,
        reversePreview: (TransitionBuilder.() -> Unit)?,
        builder: TransitionBuilder.() -> Unit,
    ) {
        transition(from = null, to = to, key = key, preview, reversePreview, builder)
    }

    override fun from(
        from: ContentKey,
        to: ContentKey?,
        key: TransitionKey?,
        preview: (TransitionBuilder.() -> Unit)?,
        reversePreview: (TransitionBuilder.() -> Unit)?,
        builder: TransitionBuilder.() -> Unit,
    ) {
        transition(from = from, to = to, key = key, preview, reversePreview, builder)
    }

    override fun overscroll(
        content: ContentKey,
        orientation: Orientation,
        builder: OverscrollBuilder.() -> Unit,
    ) {
        val impl = OverscrollBuilderImpl().apply(builder)
        check(impl.transformationMatchers.isNotEmpty()) {
            "This method does not allow empty transformations. " +
                "Use overscrollDisabled($content, $orientation) instead."
        }
        overscrollSpec(content, orientation, impl)
    }

    override fun overscrollDisabled(content: ContentKey, orientation: Orientation) {
        overscrollSpec(content, orientation, OverscrollBuilderImpl())
    }

    private fun overscrollSpec(
        content: ContentKey,
        orientation: Orientation,
        impl: OverscrollBuilderImpl,
    ): OverscrollSpec {
        val spec =
            OverscrollSpecImpl(
                content = content,
                orientation = orientation,
                transformationSpec =
                    TransformationSpecImpl(
                        progressSpec = snap(),
                        swipeSpec = null,
                        distance = impl.distance,
                        transformationMatchers = impl.transformationMatchers,
                    ),
                progressConverter = impl.progressConverter,
            )
        transitionOverscrollSpecs.add(spec)
        return spec
    }

    private fun transition(
        from: ContentKey?,
        to: ContentKey?,
        key: TransitionKey?,
        preview: (TransitionBuilder.() -> Unit)?,
        reversePreview: (TransitionBuilder.() -> Unit)?,
        builder: TransitionBuilder.() -> Unit,
    ): TransitionSpec {
        fun transformationSpec(
            transition: TransitionState.Transition,
            builder: TransitionBuilder.() -> Unit,
        ): TransformationSpecImpl {
            val impl = TransitionBuilderImpl(transition).apply(builder)
            return TransformationSpecImpl(
                progressSpec = impl.spec,
                swipeSpec = impl.swipeSpec,
                distance = impl.distance,
                transformationMatchers = impl.transformationMatchers,
            )
        }

        val spec =
            TransitionSpecImpl(
                key,
                from,
                to,
                previewTransformationSpec = preview?.let { { t -> transformationSpec(t, it) } },
                reversePreviewTransformationSpec =
                    reversePreview?.let { { t -> transformationSpec(t, it) } },
                transformationSpec = { t -> transformationSpec(t, builder) },
            )
        transitionSpecs.add(spec)
        return spec
    }
}

internal abstract class BaseTransitionBuilderImpl : BaseTransitionBuilder {
    val transformationMatchers = mutableListOf<TransformationMatcher>()
    private var range: TransformationRange? = null
    protected var reversed = false
    override var distance: UserActionDistance? = null

    override fun fractionRange(
        start: Float?,
        end: Float?,
        easing: Easing,
        builder: PropertyTransformationBuilder.() -> Unit,
    ) {
        range = TransformationRange(start, end, easing)
        builder()
        range = null
    }

    protected fun addTransformation(
        matcher: ElementMatcher,
        transformation: Transformation.Factory,
    ) {
        transformationMatchers.add(
            TransformationMatcher(
                matcher,
                transformation,
                range?.let { range ->
                    if (reversed) {
                        range.reversed()
                    } else {
                        range
                    }
                },
            )
        )
    }

    override fun fade(matcher: ElementMatcher) {
        addTransformation(matcher, Fade.Factory)
    }

    override fun translate(matcher: ElementMatcher, x: Dp, y: Dp) {
        addTransformation(matcher, Translate.Factory(x, y))
    }

    override fun translate(
        matcher: ElementMatcher,
        edge: Edge,
        startsOutsideLayoutBounds: Boolean,
    ) {
        addTransformation(matcher, EdgeTranslate.Factory(edge, startsOutsideLayoutBounds))
    }

    override fun anchoredTranslate(matcher: ElementMatcher, anchor: ElementKey) {
        addTransformation(matcher, AnchoredTranslate.Factory(anchor))
    }

    override fun scaleSize(matcher: ElementMatcher, width: Float, height: Float) {
        addTransformation(matcher, ScaleSize.Factory(width, height))
    }

    override fun scaleDraw(matcher: ElementMatcher, scaleX: Float, scaleY: Float, pivot: Offset) {
        addTransformation(matcher, DrawScale.Factory(scaleX, scaleY, pivot))
    }

    override fun anchoredSize(
        matcher: ElementMatcher,
        anchor: ElementKey,
        anchorWidth: Boolean,
        anchorHeight: Boolean,
    ) {
        addTransformation(matcher, AnchoredSize.Factory(anchor, anchorWidth, anchorHeight))
    }

    override fun transformation(matcher: ElementMatcher, transformation: Transformation.Factory) {
        check(range == null) { "Custom transformations can not be applied inside a range" }
        addTransformation(matcher, transformation)
    }
}

internal class TransitionBuilderImpl(override val transition: TransitionState.Transition) :
    BaseTransitionBuilderImpl(), TransitionBuilder {
    override var spec: AnimationSpec<Float> = spring(stiffness = Spring.StiffnessLow)
    override var swipeSpec: SpringSpec<Float>? = null
    override var distance: UserActionDistance? = null
    private val durationMillis: Int by lazy {
        val spec = spec
        if (spec !is DurationBasedAnimationSpec) {
            error("timestampRange {} can only be used with a DurationBasedAnimationSpec")
        }

        spec.vectorize(Float.VectorConverter).durationMillis
    }

    override fun reversed(builder: TransitionBuilder.() -> Unit) {
        reversed = true
        builder()
        reversed = false
    }

    override fun sharedElement(
        matcher: ElementMatcher,
        enabled: Boolean,
        elevateInContent: ContentKey?,
    ) {
        check(
            elevateInContent == null ||
                elevateInContent == transition.fromContent ||
                elevateInContent == transition.toContent
        ) {
            "elevateInContent (${elevateInContent?.debugName}) should be either fromContent " +
                "(${transition.fromContent.debugName}) or toContent " +
                "(${transition.toContent.debugName})"
        }

        addTransformation(
            matcher,
            SharedElementTransformation.Factory(matcher, enabled, elevateInContent),
        )
    }

    override fun timestampRange(
        startMillis: Int?,
        endMillis: Int?,
        easing: Easing,
        builder: PropertyTransformationBuilder.() -> Unit,
    ) {
        if (startMillis != null && (startMillis < 0 || startMillis > durationMillis)) {
            error("invalid start value: startMillis=$startMillis durationMillis=$durationMillis")
        }

        if (endMillis != null && (endMillis < 0 || endMillis > durationMillis)) {
            error("invalid end value: endMillis=$startMillis durationMillis=$durationMillis")
        }

        val start = startMillis?.let { it.toFloat() / durationMillis }
        val end = endMillis?.let { it.toFloat() / durationMillis }
        fractionRange(start, end, easing, builder)
    }
}

internal open class OverscrollBuilderImpl : BaseTransitionBuilderImpl(), OverscrollBuilder {
    override var progressConverter: ProgressConverter? = null

    override fun translate(
        matcher: ElementMatcher,
        x: OverscrollScope.() -> Float,
        y: OverscrollScope.() -> Float,
    ) {
        addTransformation(matcher, OverscrollTranslate.Factory(x, y))
    }
}
