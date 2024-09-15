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

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Stable

/**
 * A base class to create unique keys, associated to an [identity] that is used to check the
 * equality of two key instances.
 */
@Stable
sealed class Key(val debugName: String, val identity: Any) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (this.javaClass != other?.javaClass) return false
        return identity == (other as? Key)?.identity
    }

    override fun hashCode(): Int {
        return identity.hashCode()
    }

    override fun toString(): String {
        return "Key(debugName=$debugName)"
    }
}

/** The key for a content (scene or overlay). */
sealed class ContentKey(debugName: String, identity: Any) : Key(debugName, identity) {
    @VisibleForTesting
    // TODO(b/240432457): Make internal once PlatformComposeSceneTransitionLayoutTestsUtils can
    // access internal members.
    abstract val testTag: String
}

/** Key for a scene. */
class SceneKey(debugName: String, identity: Any = Object()) : ContentKey(debugName, identity) {
    override val testTag: String = "scene:$debugName"

    /** The unique [ElementKey] identifying this scene's root element. */
    val rootElementKey = ElementKey(debugName, identity)

    override fun toString(): String {
        return "SceneKey(debugName=$debugName)"
    }
}

/** Key for an overlay. */
class OverlayKey(debugName: String, identity: Any = Object()) : ContentKey(debugName, identity) {
    override val testTag: String = "overlay:$debugName"

    override fun toString(): String {
        return "OverlayKey(debugName=$debugName)"
    }
}

/** Key for an element. */
open class ElementKey(
    debugName: String,
    identity: Any = Object(),

    /**
     * The [ElementContentPicker] to use when deciding in which scene we should draw shared Elements
     * or compose MovableElements.
     */
    open val contentPicker: ElementContentPicker = DefaultElementContentPicker,

    /**
     * Whether we should place all copies of this element when it is shared.
     *
     * This should usually be false, but it can be useful when sharing a container that has a
     * different content in different scenes/overlays. That way the container will have the same
     * size and position in all scenes/overlays but all different contents will be placed and
     * visible on screen.
     */
    val placeAllCopies: Boolean = false,
) : Key(debugName, identity), ElementMatcher {
    @VisibleForTesting
    // TODO(b/240432457): Make internal once PlatformComposeSceneTransitionLayoutTestsUtils can
    // access internal members.
    val testTag: String = "element:$debugName"

    override fun matches(key: ElementKey, content: ContentKey): Boolean {
        return key == this
    }

    override fun toString(): String {
        return "ElementKey(debugName=$debugName)"
    }

    companion object {
        /** Matches any element whose [key identity][ElementKey.identity] matches [predicate]. */
        fun withIdentity(predicate: (Any) -> Boolean): ElementMatcher {
            return object : ElementMatcher {
                override fun matches(key: ElementKey, content: ContentKey): Boolean {
                    return predicate(key.identity)
                }
            }
        }
    }
}

/** Key for a movable element. */
class MovableElementKey(
    debugName: String,

    /**
     * The [StaticElementContentPicker] to use when deciding in which scene we should draw shared
     * Elements or compose MovableElements.
     *
     * @see DefaultElementContentPicker
     * @see MovableElementContentPicker
     */
    override val contentPicker: StaticElementContentPicker,
    identity: Any = Object(),
) : ElementKey(debugName, identity, contentPicker) {
    constructor(
        debugName: String,

        /** The exhaustive list of contents (scenes or overlays) that can contain this element. */
        contents: Set<ContentKey>,
        identity: Any = Object(),
    ) : this(debugName, MovableElementContentPicker(contents), identity)

    override fun toString(): String {
        return "MovableElementKey(debugName=$debugName)"
    }
}

/** Key for a shared value of an element. */
class ValueKey(debugName: String, identity: Any = Object()) : Key(debugName, identity) {
    override fun toString(): String {
        return "ValueKey(debugName=$debugName)"
    }
}

/**
 * Key for a transition. This can be used to specify which transition spec should be used when
 * starting the transition between two scenes.
 */
class TransitionKey(debugName: String, identity: Any = Object()) : Key(debugName, identity) {
    override fun toString(): String {
        return "TransitionKey(debugName=$debugName)"
    }

    companion object {
        /**
         * A special transition key indicating that the associated transition should be used for
         * Predictive Back gestures.
         *
         * Use this key when defining a transition that you want to be specifically triggered when
         * the user performs a Predictive Back gesture.
         */
        val PredictiveBack = TransitionKey("PredictiveBack")
    }
}
