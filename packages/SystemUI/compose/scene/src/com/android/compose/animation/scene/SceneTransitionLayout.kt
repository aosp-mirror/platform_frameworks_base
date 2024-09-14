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

import androidx.annotation.FloatRange
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/**
 * [SceneTransitionLayout] is a container that automatically animates its content whenever its state
 * changes.
 *
 * Note: You should use [androidx.compose.animation.AnimatedContent] instead of
 * [SceneTransitionLayout] if it fits your need. Use [SceneTransitionLayout] over AnimatedContent if
 * you need support for swipe gestures, shared elements or transitions defined declaratively outside
 * UI code.
 *
 * @param state the state of this layout.
 * @param swipeSourceDetector the edge detector used to detect which edge a swipe is started from,
 *   if any.
 * @param transitionInterceptionThreshold used during a scene transition. For the scene to be
 *   intercepted, the progress value must be above the threshold, and below (1 - threshold).
 * @param builder the configuration of the different scenes and overlays of this layout.
 */
@Composable
fun SceneTransitionLayout(
    state: SceneTransitionLayoutState,
    modifier: Modifier = Modifier,
    swipeSourceDetector: SwipeSourceDetector = DefaultEdgeDetector,
    swipeDetector: SwipeDetector = DefaultSwipeDetector,
    @FloatRange(from = 0.0, to = 0.5) transitionInterceptionThreshold: Float = 0.05f,
    builder: SceneTransitionLayoutScope.() -> Unit,
) {
    SceneTransitionLayoutForTesting(
        state,
        modifier,
        swipeSourceDetector,
        swipeDetector,
        transitionInterceptionThreshold,
        onLayoutImpl = null,
        builder,
    )
}

interface SceneTransitionLayoutScope {
    /**
     * Add a scene to this layout, identified by [key].
     *
     * You can configure [userActions] so that swiping on this layout or navigating back will
     * transition to a different scene.
     *
     * Important: scene order along the z-axis follows call order. Calling scene(A) followed by
     * scene(B) will mean that scene B renders after/above scene A.
     */
    fun scene(
        key: SceneKey,
        userActions: Map<UserAction, UserActionResult> = emptyMap(),
        content: @Composable ContentScope.() -> Unit,
    )

    /**
     * Add an overlay to this layout, identified by [key].
     *
     * Overlays are displayed above scenes and can be toggled using
     * [MutableSceneTransitionLayoutState.showOverlay] and
     * [MutableSceneTransitionLayoutState.hideOverlay].
     *
     * Overlays will have a maximum size that is the size of the layout without overlays, i.e. an
     * overlay can be fillMaxSize() to match the layout size but it won't make the layout bigger.
     *
     * By default overlays are centered in their layout but they can be aligned differently using
     * [alignment].
     *
     * If [isModal] is true (the default), then a protective layer will be added behind the overlay
     * to prevent swipes from reaching other scenes or overlays behind this one. Clicking this
     * protective layer will close the overlay.
     *
     * Important: overlays must be defined after all scenes. Overlay order along the z-axis follows
     * call order. Calling overlay(A) followed by overlay(B) will mean that overlay B renders
     * after/above overlay A.
     */
    fun overlay(
        key: OverlayKey,
        userActions: Map<UserAction, UserActionResult> =
            mapOf(Back to UserActionResult.HideOverlay(key)),
        alignment: Alignment = Alignment.Center,
        isModal: Boolean = true,
        content: @Composable ContentScope.() -> Unit,
    )
}

/**
 * A DSL marker to prevent people from nesting calls to Modifier.element() inside a MovableElement,
 * which is not supported.
 */
@DslMarker annotation class ElementDsl

/** A scope that can be used to query the target state of an element or scene. */
interface ElementStateScope {
    /**
     * Return the *target* size of [this] element in the given [scene], i.e. the size of the element
     * when idle, or `null` if the element is not composed and measured in that scene (yet).
     */
    fun ElementKey.targetSize(scene: SceneKey): IntSize?

    /**
     * Return the *target* offset of [this] element in the given [scene], i.e. the size of the
     * element when idle, or `null` if the element is not composed and placed in that scene (yet).
     */
    fun ElementKey.targetOffset(scene: SceneKey): Offset?

    /**
     * Return the *target* size of [this] scene, i.e. the size of the scene when idle, or `null` if
     * the scene was never composed.
     */
    fun SceneKey.targetSize(): IntSize?
}

@Stable
@ElementDsl
interface BaseContentScope : ElementStateScope {
    /** The key of this content. */
    val contentKey: ContentKey

    /** The state of the [SceneTransitionLayout] in which this content is contained. */
    val layoutState: SceneTransitionLayoutState

    /**
     * Tag an element identified by [key].
     *
     * Tagging an element will allow you to reference that element when defining transitions, so
     * that the element can be transformed and animated when the content transitions in or out.
     *
     * Additionally, this [key] will be used to detect elements that are shared between contents to
     * automatically interpolate their size and offset. If you need to animate shared element values
     * (i.e. values associated to this element that change depending on which content it is composed
     * in), use [Element] instead.
     *
     * Note that shared elements tagged using this function will be duplicated in each content they
     * are part of, so any **internal** state (e.g. state created using `remember {
     * mutableStateOf(...) }`) will be lost. If you need to preserve internal state, you should use
     * [MovableElement] instead.
     *
     * @see Element
     * @see MovableElement
     */
    fun Modifier.element(key: ElementKey): Modifier

    /**
     * Create an element identified by [key].
     *
     * Similar to [element], this creates an element that will be automatically shared when present
     * in multiple contents and that can be transformed during transitions, the same way that
     * [element] does.
     *
     * The only difference with [element] is that the provided [ElementScope] allows you to
     * [animate element values][ElementScope.animateElementValueAsState] or specify its
     * [movable content][Element.movableContent] that will be "moved" and composed only once during
     * transitions (as opposed to [element] that duplicates shared elements) so that any internal
     * state is preserved during and after the transition.
     *
     * @see element
     * @see MovableElement
     */
    @Composable
    fun Element(
        key: ElementKey,
        modifier: Modifier,

        // TODO(b/317026105): As discussed in http://shortn/_gJVdltF8Si, remove the @Composable
        // scope here to make sure that callers specify the content in ElementScope.content {} or
        // ElementScope.movableContent {}.
        content: @Composable ElementScope<ElementContentScope>.() -> Unit,
    )

    /**
     * Create a *movable* element identified by [key].
     *
     * Similar to [Element], this creates an element that will be automatically shared when present
     * in multiple contents and that can be transformed during transitions, and you can also use the
     * provided [ElementScope] to [animate element values][ElementScope.animateElementValueAsState].
     *
     * The important difference with [element] and [Element] is that this element
     * [content][ElementScope.content] will be "moved" and composed only once during transitions, as
     * opposed to [element] and [Element] that duplicates shared elements, so that any internal
     * state is preserved during and after the transition.
     *
     * @see element
     * @see Element
     */
    @Composable
    fun MovableElement(
        key: MovableElementKey,
        modifier: Modifier,

        // TODO(b/317026105): As discussed in http://shortn/_gJVdltF8Si, remove the @Composable
        // scope here to make sure that callers specify the content in ElementScope.content {} or
        // ElementScope.movableContent {}.
        content: @Composable ElementScope<MovableElementContentScope>.() -> Unit,
    )

    /**
     * Adds a [NestedScrollConnection] to intercept scroll events not handled by the scrollable
     * component.
     *
     * @param leftBehavior when we should perform the overscroll animation at the left.
     * @param rightBehavior when we should perform the overscroll animation at the right.
     */
    fun Modifier.horizontalNestedScrollToScene(
        leftBehavior: NestedScrollBehavior = NestedScrollBehavior.Default,
        rightBehavior: NestedScrollBehavior = NestedScrollBehavior.Default,
        isExternalOverscrollGesture: () -> Boolean = { false },
    ): Modifier

    /**
     * Adds a [NestedScrollConnection] to intercept scroll events not handled by the scrollable
     * component.
     *
     * @param topBehavior when we should perform the overscroll animation at the top.
     * @param bottomBehavior when we should perform the overscroll animation at the bottom.
     */
    fun Modifier.verticalNestedScrollToScene(
        topBehavior: NestedScrollBehavior = NestedScrollBehavior.Default,
        bottomBehavior: NestedScrollBehavior = NestedScrollBehavior.Default,
        isExternalOverscrollGesture: () -> Boolean = { false },
    ): Modifier

    /**
     * Don't resize during transitions. This can for instance be used to make sure that scrollable
     * lists keep a constant size during transitions even if its elements are growing/shrinking.
     */
    fun Modifier.noResizeDuringTransitions(): Modifier
}

typealias SceneScope = ContentScope

@Stable
@ElementDsl
interface ContentScope : BaseContentScope {
    /**
     * Animate some value at the content level.
     *
     * @param value the value of this shared value in the current content.
     * @param key the key of this shared value.
     * @param type the [SharedValueType] of this animated value.
     * @param canOverflow whether this value can overflow past the values it is interpolated
     *   between, for instance because the transition is animated using a bouncy spring.
     * @see animateContentIntAsState
     * @see animateContentFloatAsState
     * @see animateContentDpAsState
     * @see animateContentColorAsState
     */
    @Composable
    fun <T> animateContentValueAsState(
        value: T,
        key: ValueKey,
        type: SharedValueType<T, *>,
        canOverflow: Boolean,
    ): AnimatedState<T>
}

/**
 * The type of a shared value animated using [ElementScope.animateElementValueAsState] or
 * [ContentScope.animateContentValueAsState].
 */
@Stable
interface SharedValueType<T, Delta> {
    /** The unspecified value for this type. */
    val unspecifiedValue: T

    /**
     * The zero value of this type. It should be equal to what [diff(x, x)] returns for any value of
     * x.
     */
    val zeroDeltaValue: Delta

    /**
     * Return the linear interpolation of [a] and [b] at the given [progress], i.e. `a + (b - a) *
     * progress`.
     */
    fun lerp(a: T, b: T, progress: Float): T

    /** Return `a - b`. */
    fun diff(a: T, b: T): Delta

    /** Return `a + b * bWeight`. */
    fun addWeighted(a: T, b: Delta, bWeight: Float): T
}

@Stable
@ElementDsl
interface ElementScope<ContentScope> {
    /**
     * Animate some value associated to this element.
     *
     * @param value the value of this shared value in the current content.
     * @param key the key of this shared value.
     * @param type the [SharedValueType] of this animated value.
     * @param canOverflow whether this value can overflow past the values it is interpolated
     *   between, for instance because the transition is animated using a bouncy spring.
     * @see animateElementIntAsState
     * @see animateElementFloatAsState
     * @see animateElementDpAsState
     * @see animateElementColorAsState
     */
    @Composable
    fun <T> animateElementValueAsState(
        value: T,
        key: ValueKey,
        type: SharedValueType<T, *>,
        canOverflow: Boolean,
    ): AnimatedState<T>

    /**
     * The content of this element.
     *
     * Important: This must be called exactly once, after all calls to [animateElementValueAsState].
     */
    @Composable fun content(content: @Composable ContentScope.() -> Unit)
}

/**
 * The exact same scope as [androidx.compose.foundation.layout.BoxScope].
 *
 * We can't reuse BoxScope directly because of the @LayoutScopeMarker annotation on it, which would
 * prevent us from calling Modifier.element() and other methods of [ContentScope] inside any Box {}
 * in the [content][ElementScope.content] of a [ContentScope.Element] or a
 * [ContentScope.MovableElement].
 */
@Stable
@ElementDsl
interface ElementBoxScope {
    /** @see [androidx.compose.foundation.layout.BoxScope.align]. */
    @Stable fun Modifier.align(alignment: Alignment): Modifier

    /** @see [androidx.compose.foundation.layout.BoxScope.matchParentSize]. */
    @Stable fun Modifier.matchParentSize(): Modifier
}

/** The scope for "normal" (not movable) elements. */
@Stable @ElementDsl interface ElementContentScope : ContentScope, ElementBoxScope

/**
 * The scope for the content of movable elements.
 *
 * Note that it extends [BaseContentScope] and not [ContentScope] because movable elements should
 * not call [ContentScope.animateContentValueAsState], given that their content is not composed in
 * all scenes.
 */
@Stable @ElementDsl interface MovableElementContentScope : BaseContentScope, ElementBoxScope

/** An action performed by the user. */
sealed class UserAction {
    infix fun to(scene: SceneKey): Pair<UserAction, UserActionResult> {
        return this to UserActionResult(toScene = scene)
    }

    infix fun to(overlay: OverlayKey): Pair<UserAction, UserActionResult> {
        return this to UserActionResult(toOverlay = overlay)
    }

    /** Resolve this into a [Resolved] user action given [layoutDirection]. */
    internal abstract fun resolve(layoutDirection: LayoutDirection): Resolved

    /** A resolved [UserAction] that does not depend on the layout direction. */
    internal sealed class Resolved
}

/** The user navigated back, either using a gesture or by triggering a KEYCODE_BACK event. */
data object Back : UserAction() {
    override fun resolve(layoutDirection: LayoutDirection): Resolved = Resolved

    internal object Resolved : UserAction.Resolved()
}

/** The user swiped on the container. */
data class Swipe(
    val direction: SwipeDirection,
    val pointerCount: Int = 1,
    val fromSource: SwipeSource? = null,
) : UserAction() {
    companion object {
        val Left = Swipe(SwipeDirection.Left)
        val Up = Swipe(SwipeDirection.Up)
        val Right = Swipe(SwipeDirection.Right)
        val Down = Swipe(SwipeDirection.Down)
        val Start = Swipe(SwipeDirection.Start)
        val End = Swipe(SwipeDirection.End)
    }

    override fun resolve(layoutDirection: LayoutDirection): UserAction.Resolved {
        return Resolved(
            direction = direction.resolve(layoutDirection),
            pointerCount = pointerCount,
            fromSource = fromSource?.resolve(layoutDirection),
        )
    }

    /** A resolved [Swipe] that does not depend on the layout direction. */
    internal data class Resolved(
        val direction: SwipeDirection.Resolved,
        val pointerCount: Int,
        val fromSource: SwipeSource.Resolved?,
    ) : UserAction.Resolved()
}

enum class SwipeDirection(internal val resolve: (LayoutDirection) -> Resolved) {
    Up(resolve = { Resolved.Up }),
    Down(resolve = { Resolved.Down }),
    Left(resolve = { Resolved.Left }),
    Right(resolve = { Resolved.Right }),
    Start(resolve = { if (it == LayoutDirection.Ltr) Resolved.Left else Resolved.Right }),
    End(resolve = { if (it == LayoutDirection.Ltr) Resolved.Right else Resolved.Left });

    /** A resolved [SwipeDirection] that does not depend on the layout direction. */
    internal enum class Resolved(val orientation: Orientation) {
        Up(Orientation.Vertical),
        Down(Orientation.Vertical),
        Left(Orientation.Horizontal),
        Right(Orientation.Horizontal),
    }
}

/**
 * The source of a Swipe.
 *
 * Important: This can be anything that can be returned by any [SwipeSourceDetector], but this must
 * implement [equals] and [hashCode]. Note that those can be trivially implemented using data
 * classes.
 */
interface SwipeSource {
    // Require equals() and hashCode() to be implemented.
    override fun equals(other: Any?): Boolean

    override fun hashCode(): Int

    /** Resolve this into a [Resolved] swipe source given [layoutDirection]. */
    fun resolve(layoutDirection: LayoutDirection): Resolved

    /** A resolved [SwipeSource] that does not depend on the layout direction. */
    interface Resolved {
        override fun equals(other: Any?): Boolean

        override fun hashCode(): Int
    }
}

interface SwipeSourceDetector {
    /**
     * Return the [SwipeSource] associated to [position] inside a layout of size [layoutSize], given
     * [density] and [orientation].
     */
    fun source(
        layoutSize: IntSize,
        position: IntOffset,
        density: Density,
        orientation: Orientation,
    ): SwipeSource.Resolved?
}

/** The result of performing a [UserAction]. */
sealed class UserActionResult(
    /** The key of the transition that should be used. */
    open val transitionKey: TransitionKey? = null,

    /**
     * If `true`, the swipe will be committed and we will settle to [toScene] if only if the user
     * swiped at least the swipe distance, i.e. the transition progress was already equal to or
     * bigger than 100% when the user released their finger. `
     */
    open val requiresFullDistanceSwipe: Boolean,
) {
    internal abstract fun toContent(currentScene: SceneKey): ContentKey

    data class ChangeScene
    internal constructor(
        /** The scene we should be transitioning to during the [UserAction]. */
        val toScene: SceneKey,
        override val transitionKey: TransitionKey? = null,
        override val requiresFullDistanceSwipe: Boolean = false,
    ) : UserActionResult(transitionKey, requiresFullDistanceSwipe) {
        override fun toContent(currentScene: SceneKey): ContentKey = toScene
    }

    /** A [UserActionResult] that shows [overlay]. */
    data class ShowOverlay(
        val overlay: OverlayKey,
        override val transitionKey: TransitionKey? = null,
        override val requiresFullDistanceSwipe: Boolean = false,
    ) : UserActionResult(transitionKey, requiresFullDistanceSwipe) {
        override fun toContent(currentScene: SceneKey): ContentKey = overlay
    }

    /** A [UserActionResult] that hides [overlay]. */
    data class HideOverlay(
        val overlay: OverlayKey,
        override val transitionKey: TransitionKey? = null,
        override val requiresFullDistanceSwipe: Boolean = false,
    ) : UserActionResult(transitionKey, requiresFullDistanceSwipe) {
        override fun toContent(currentScene: SceneKey): ContentKey = currentScene
    }

    /**
     * A [UserActionResult] that replaces the current overlay by [overlay].
     *
     * Note: This result can only be used for user actions of overlays and an exception will be
     * thrown if it is used for a scene.
     */
    data class ReplaceByOverlay(
        val overlay: OverlayKey,
        override val transitionKey: TransitionKey? = null,
        override val requiresFullDistanceSwipe: Boolean = false,
    ) : UserActionResult(transitionKey, requiresFullDistanceSwipe) {
        override fun toContent(currentScene: SceneKey): ContentKey = overlay
    }

    companion object {
        /** A [UserActionResult] that changes the current scene to [toScene]. */
        operator fun invoke(
            /** The scene we should be transitioning to during the [UserAction]. */
            toScene: SceneKey,

            /** The key of the transition that should be used. */
            transitionKey: TransitionKey? = null,

            /**
             * If `true`, the swipe will be committed if only if the user swiped at least the swipe
             * distance, i.e. the transition progress was already equal to or bigger than 100% when
             * the user released their finger.
             */
            requiresFullDistanceSwipe: Boolean = false,
        ): UserActionResult = ChangeScene(toScene, transitionKey, requiresFullDistanceSwipe)

        /** A [UserActionResult] that shows [toOverlay]. */
        operator fun invoke(
            /** The overlay we should be transitioning to during the [UserAction]. */
            toOverlay: OverlayKey,

            /** The key of the transition that should be used. */
            transitionKey: TransitionKey? = null,

            /**
             * If `true`, the swipe will be committed if only if the user swiped at least the swipe
             * distance, i.e. the transition progress was already equal to or bigger than 100% when
             * the user released their finger.
             */
            requiresFullDistanceSwipe: Boolean = false,
        ): UserActionResult = ShowOverlay(toOverlay, transitionKey, requiresFullDistanceSwipe)
    }
}

fun interface UserActionDistance {
    /**
     * Return the **absolute** distance of the user action given the size of the scene we are
     * animating from and the [orientation].
     *
     * Note: This function will be called for each drag event until it returns a value > 0f. This
     * for instance allows you to return 0f or a negative value until the first layout pass of a
     * scene, so that you can use the size and position of elements in the scene we are
     * transitioning to when computing this absolute distance.
     */
    fun UserActionDistanceScope.absoluteDistance(
        fromSceneSize: IntSize,
        orientation: Orientation,
    ): Float
}

interface UserActionDistanceScope : Density, ElementStateScope

/** The user action has a fixed [absoluteDistance]. */
class FixedDistance(private val distance: Dp) : UserActionDistance {
    override fun UserActionDistanceScope.absoluteDistance(
        fromSceneSize: IntSize,
        orientation: Orientation,
    ): Float = distance.toPx()
}

/**
 * An internal version of [SceneTransitionLayout] to be used for tests.
 *
 * Important: You should use this only in tests and if you need to access the underlying
 * [SceneTransitionLayoutImpl]. In other cases, you should use [SceneTransitionLayout].
 */
@Composable
internal fun SceneTransitionLayoutForTesting(
    state: SceneTransitionLayoutState,
    modifier: Modifier = Modifier,
    swipeSourceDetector: SwipeSourceDetector = DefaultEdgeDetector,
    swipeDetector: SwipeDetector = DefaultSwipeDetector,
    transitionInterceptionThreshold: Float = 0f,
    onLayoutImpl: ((SceneTransitionLayoutImpl) -> Unit)? = null,
    builder: SceneTransitionLayoutScope.() -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val animationScope = rememberCoroutineScope()
    val layoutImpl = remember {
        SceneTransitionLayoutImpl(
                state = state as MutableSceneTransitionLayoutStateImpl,
                density = density,
                layoutDirection = layoutDirection,
                swipeSourceDetector = swipeSourceDetector,
                transitionInterceptionThreshold = transitionInterceptionThreshold,
                builder = builder,
                animationScope = animationScope,
            )
            .also { onLayoutImpl?.invoke(it) }
    }

    // TODO(b/317014852): Move this into the SideEffect {} again once STLImpl.scenes is not a
    // SnapshotStateMap anymore.
    layoutImpl.updateContents(builder, layoutDirection)

    SideEffect {
        if (state != layoutImpl.state) {
            error(
                "This SceneTransitionLayout was bound to a different SceneTransitionLayoutState" +
                    " that was used when creating it, which is not supported"
            )
        }

        layoutImpl.density = density
        layoutImpl.layoutDirection = layoutDirection
        layoutImpl.swipeSourceDetector = swipeSourceDetector
        layoutImpl.transitionInterceptionThreshold = transitionInterceptionThreshold
    }

    layoutImpl.Content(modifier, swipeDetector)
}
