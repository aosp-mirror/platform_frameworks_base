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

package com.android.egg.landroid

import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment.Left
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import java.lang.Float.max
import java.lang.Float.min
import java.util.Calendar
import java.util.GregorianCalendar
import kotlin.math.absoluteValue
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class RandomSeedType {
    Fixed,
    Daily,
    Evergreen
}

const val TEST_UNIVERSE = false

val RANDOM_SEED_TYPE = RandomSeedType.Daily

const val FIXED_RANDOM_SEED = 5038L
const val DEFAULT_CAMERA_ZOOM = 0.25f
const val MIN_CAMERA_ZOOM = 250f / UNIVERSE_RANGE // 0.0025f
const val MAX_CAMERA_ZOOM = 5f
const val TOUCH_CAMERA_PAN = false
const val TOUCH_CAMERA_ZOOM = true
const val DYNAMIC_ZOOM = false // @@@ FIXME

fun dailySeed(): Long {
    val today = GregorianCalendar()
    return today.get(Calendar.YEAR) * 10_000L +
        today.get(Calendar.MONTH) * 100L +
        today.get(Calendar.DAY_OF_MONTH)
}

fun randomSeed(): Long {
    return when (RANDOM_SEED_TYPE) {
        RandomSeedType.Fixed -> FIXED_RANDOM_SEED
        RandomSeedType.Daily -> dailySeed()
        else -> Random.Default.nextLong().mod(10_000_000).toLong()
    }.absoluteValue
}

val DEBUG_TEXT = mutableStateOf("Hello Universe")
const val SHOW_DEBUG_TEXT = false

@Composable
fun DebugText(text: MutableState<String>) {
    if (SHOW_DEBUG_TEXT) {
        Text(
            modifier = Modifier.fillMaxWidth().border(0.5.dp, color = Color.Yellow).padding(2.dp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 9.sp,
            color = Color.Yellow,
            text = text.value
        )
    }
}

@Composable
fun ColumnScope.ConsoleText(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    random: Random = Random.Default,
    text: String
) {
    AnimatedVisibility(
        modifier = modifier,
        visible = visible,
        enter =
            fadeIn(
                animationSpec =
                    tween(
                        durationMillis = 1000,
                        easing = flickerFadeEasing(random) * CubicBezierEasing(0f, 1f, 1f, 0f)
                    )
            )
    ) {
        Text(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = Color(0xFFFF8000),
            text = text
        )
    }
}

@Composable
fun Telemetry(universe: VisibleUniverse) {
    var topVisible by remember { mutableStateOf(false) }
    var bottomVisible by remember { mutableStateOf(false) }

    LaunchedEffect("blah") {
        delay(1000)
        bottomVisible = true
        delay(1000)
        topVisible = true
    }

    Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
        universe.triggerDraw.value // recompose on every frame
        val explored = universe.planets.filter { it.explored }

        AnimatedVisibility(modifier = Modifier, visible = topVisible, enter = flickerFadeIn) {
            Text(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = Colors.Console,
                modifier = Modifier.align(Left),
                text =
                    with(universe.star) {
                        "  STAR: $name (UDC-${universe.randomSeed % 100_000})\n" +
                            " CLASS: ${cls.name}\n" +
                            "RADIUS: ${radius.toInt()}\n" +
                            "  MASS: %.3g\n".format(mass) +
                            "BODIES: ${explored.size} / ${universe.planets.size}\n" +
                            "\n"
                    } +
                        explored
                            .map {
                                "  BODY: ${it.name}\n" +
                                    "  TYPE: ${it.description.capitalize()}\n" +
                                    "  ATMO: ${it.atmosphere.capitalize()}\n" +
                                    " FAUNA: ${it.fauna.capitalize()}\n" +
                                    " FLORA: ${it.flora.capitalize()}\n"
                            }
                            .joinToString("\n")

                // TODO: different colors, highlight latest discovery
                )
        }

        Spacer(modifier = Modifier.weight(1f))

        AnimatedVisibility(modifier = Modifier, visible = bottomVisible, enter = flickerFadeIn) {
            Text(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = Colors.Console,
                modifier = Modifier.align(Left),
                text =
                    with(universe.ship) {
                        val closest = universe.closestPlanet()
                        val distToClosest = (closest.pos - pos).mag().toInt()
                        listOfNotNull(
                                landing?.let { "LND: ${it.planet.name}" }
                                    ?: if (distToClosest < 10_000) {
                                        "ALT: $distToClosest"
                                    } else null,
                                if (thrust != Vec2.Zero) "THR: %.0f%%".format(thrust.mag() * 100f)
                                else null,
                                "POS: %s".format(pos.str("%+7.0f")),
                                "VEL: %.0f".format(velocity.mag())
                            )
                            .joinToString("\n")
                    }
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    private var foldState = mutableStateOf<FoldingFeature?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onWindowLayoutInfoChange()

        val universe = VisibleUniverse(namer = Namer(resources), randomSeed = randomSeed())

        if (TEST_UNIVERSE) {
            universe.initTest()
        } else {
            universe.initRandom()
        }

        setContent {
            Spaaaace(modifier = Modifier.fillMaxSize(), u = universe, foldState = foldState)
            DebugText(DEBUG_TEXT)

            val minRadius = 50.dp.toLocalPx()
            val maxRadius = 100.dp.toLocalPx()
            FlightStick(
                modifier = Modifier.fillMaxSize(),
                minRadius = minRadius,
                maxRadius = maxRadius,
                color = Color.Green
            ) { vec ->
                (universe.follow as? Spacecraft)?.let { ship ->
                    if (vec == Vec2.Zero) {
                        ship.thrust = Vec2.Zero
                    } else {
                        val a = vec.angle()
                        ship.angle = a

                        val m = vec.mag()
                        if (m < minRadius) {
                            // within this radius, just reorient
                            ship.thrust = Vec2.Zero
                        } else {
                            ship.thrust =
                                Vec2.makeWithAngleMag(
                                    a,
                                    lexp(minRadius, maxRadius, m).coerceIn(0f, 1f)
                                )
                        }
                    }
                }
            }
            Telemetry(universe)
        }
    }

    private fun onWindowLayoutInfoChange() {
        val windowInfoTracker = WindowInfoTracker.getOrCreate(this@MainActivity)

        lifecycleScope.launch(Dispatchers.Main) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                windowInfoTracker.windowLayoutInfo(this@MainActivity).collect { layoutInfo ->
                    foldState.value =
                        layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    Log.v("Landroid", "fold updated: $foldState")
                }
            }
        }
    }
}

@Preview(name = "phone", device = Devices.PHONE)
@Preview(name = "fold", device = Devices.FOLDABLE)
@Preview(name = "tablet", device = Devices.TABLET)
@Composable
fun MainActivityPreview() {
    val universe = VisibleUniverse(namer = Namer(Resources.getSystem()), randomSeed = randomSeed())

    universe.initTest()

    Spaaaace(modifier = Modifier.fillMaxSize(), universe)
    DebugText(DEBUG_TEXT)
    Telemetry(universe)
}

@Composable
fun FlightStick(
    modifier: Modifier,
    minRadius: Float = 0f,
    maxRadius: Float = 1000f,
    color: Color = Color.Green,
    onStickChanged: (vector: Vec2) -> Unit
) {
    val origin = remember { mutableStateOf(Vec2.Zero) }
    val target = remember { mutableStateOf(Vec2.Zero) }

    Box(
        modifier =
            modifier
                .pointerInput(Unit) {
                    forEachGesture {
                        awaitPointerEventScope {
                            // ACTION_DOWN
                            val down = awaitFirstDown(requireUnconsumed = false)
                            origin.value = down.position
                            target.value = down.position

                            do {
                                // ACTION_MOVE
                                val event: PointerEvent = awaitPointerEvent()
                                target.value = event.changes[0].position

                                onStickChanged(target.value - origin.value)
                            } while (
                                !event.changes.any { it.isConsumed } &&
                                    event.changes.count { it.pressed } == 1
                            )

                            // ACTION_UP / CANCEL
                            target.value = Vec2.Zero
                            origin.value = Vec2.Zero

                            onStickChanged(Vec2.Zero)
                        }
                    }
                }
                .drawBehind {
                    if (origin.value != Vec2.Zero) {
                        val delta = target.value - origin.value
                        val mag = min(maxRadius, delta.mag())
                        val r = max(minRadius, mag)
                        val a = delta.angle()
                        drawCircle(
                            color = color,
                            center = origin.value,
                            radius = r,
                            style =
                                Stroke(
                                    width = 2f,
                                    pathEffect =
                                        if (mag < minRadius)
                                            PathEffect.dashPathEffect(
                                                floatArrayOf(this.density * 1f, this.density * 2f)
                                            )
                                        else null
                                )
                        )
                        drawLine(
                            color = color,
                            start = origin.value,
                            end = origin.value + Vec2.makeWithAngleMag(a, mag),
                            strokeWidth = 2f
                        )
                    }
                }
    )
}

@Composable
fun Spaaaace(
    modifier: Modifier,
    u: VisibleUniverse,
    foldState: MutableState<FoldingFeature?> = mutableStateOf(null)
) {
    LaunchedEffect(u) {
        while (true) withInfiniteAnimationFrameNanos { frameTimeNanos ->
            u.simulateAndDrawFrame(frameTimeNanos)
        }
    }

    var cameraZoom by remember { mutableStateOf(1f) }
    var cameraOffset by remember { mutableStateOf(Offset.Zero) }

    val transformableState =
        rememberTransformableState { zoomChange, offsetChange, rotationChange ->
            if (TOUCH_CAMERA_PAN) cameraOffset += offsetChange / cameraZoom
            if (TOUCH_CAMERA_ZOOM)
                cameraZoom = clamp(cameraZoom * zoomChange, MIN_CAMERA_ZOOM, MAX_CAMERA_ZOOM)
        }

    var canvasModifier = modifier

    if (TOUCH_CAMERA_PAN || TOUCH_CAMERA_ZOOM) {
        canvasModifier = canvasModifier.transformable(transformableState)
    }

    val halfFolded = foldState.value?.let { it.state == FoldingFeature.State.HALF_OPENED } ?: false
    val horizontalFold =
        foldState.value?.let { it.orientation == FoldingFeature.Orientation.HORIZONTAL } ?: false

    val centerFracX: Float by
        animateFloatAsState(if (halfFolded && !horizontalFold) 0.25f else 0.5f, label = "centerX")
    val centerFracY: Float by
        animateFloatAsState(if (halfFolded && horizontalFold) 0.25f else 0.5f, label = "centerY")

    Canvas(modifier = canvasModifier) {
        drawRect(Colors.Eigengrau, Offset.Zero, size)

        val closest = u.closestPlanet()
        val distToNearestSurf = max(0f, (u.ship.pos - closest.pos).mag() - closest.radius * 1.2f)
        //        val normalizedDist = clamp(distToNearestSurf, 50f, 50_000f) / 50_000f
        if (DYNAMIC_ZOOM) {
            //            cameraZoom = lerp(0.1f, 5f, smooth(1f-normalizedDist))
            cameraZoom = clamp(500f / distToNearestSurf, MIN_CAMERA_ZOOM, MAX_CAMERA_ZOOM)
        } else if (!TOUCH_CAMERA_ZOOM) cameraZoom = DEFAULT_CAMERA_ZOOM
        if (!TOUCH_CAMERA_PAN) cameraOffset = (u.follow?.pos ?: Vec2.Zero) * -1f

        // cameraZoom: metersToPixels
        // visibleSpaceSizeMeters: meters
        // cameraOffset: meters ≈ vector pointing from ship to (0,0) (e.g. -pos)
        val visibleSpaceSizeMeters = size / cameraZoom // meters x meters
        val visibleSpaceRectMeters =
            Rect(
                -cameraOffset -
                    Offset(
                        visibleSpaceSizeMeters.width * centerFracX,
                        visibleSpaceSizeMeters.height * centerFracY
                    ),
                visibleSpaceSizeMeters
            )

        var gridStep = 1000f
        while (gridStep * cameraZoom < 32.dp.toPx()) gridStep *= 10

        DEBUG_TEXT.value =
            ("SIMULATION //\n" +
                // "normalizedDist=${normalizedDist} \n" +
                "entities: ${u.entities.size} // " +
                "zoom: ${"%.4f".format(cameraZoom)}x // " +
                "fps: ${"%3.0f".format(1f / u.dt)} " +
                "dt: ${u.dt}\n" +
                ((u.follow as? Spacecraft)?.let {
                    "ship: p=%s v=%7.2f a=%6.3f t=%s\n".format(
                        it.pos.str("%+7.1f"),
                        it.velocity.mag(),
                        it.angle,
                        it.thrust.str("%+5.2f")
                    )
                }
                    ?: "") +
                "star: '${u.star.name}' designation=UDC-${u.randomSeed % 100_000} " +
                "class=${u.star.cls.name} r=${u.star.radius.toInt()} m=${u.star.mass}\n" +
                "planets: ${u.planets.size}\n" +
                    u.planets.joinToString("\n") {
                        val range = (u.ship.pos - it.pos).mag()
                        val vorbit = sqrt(GRAVITATION * it.mass / range)
                        val vescape = sqrt(2 * GRAVITATION * it.mass / it.radius)
                        " * ${it.name}:\n" +
                                if (it.explored) {
                                    "   TYPE:  ${it.description.capitalize()}\n" +
                                            "   ATMO:  ${it.atmosphere.capitalize()}\n" +
                                            "   FAUNA: ${it.fauna.capitalize()}\n" +
                                            "   FLORA: ${it.flora.capitalize()}\n"
                                } else {
                                    "   (Unexplored)\n"
                                } +
                                "   orbit=${(it.pos - it.orbitCenter).mag().toInt()}" +
                                " radius=${it.radius.toInt()}" +
                                " mass=${"%g".format(it.mass)}" +
                                " vel=${(it.speed).toInt()}" +
                                " // range=${"%.0f".format(range)}" +
                                " vorbit=${vorbit.toInt()} vescape=${vescape.toInt()}"
                    })

        zoom(cameraZoom) {
            // All coordinates are space coordinates now.

            translate(
                -visibleSpaceRectMeters.center.x + size.width * 0.5f,
                -visibleSpaceRectMeters.center.y + size.height * 0.5f
            ) {
                // debug outer frame
                // drawRect(
                //     Colors.Eigengrau2,
                //     visibleSpaceRectMeters.topLeft,
                //     visibleSpaceRectMeters.size,
                //     style = Stroke(width = 10f / cameraZoom)
                // )

                var x = floor(visibleSpaceRectMeters.left / gridStep) * gridStep
                while (x < visibleSpaceRectMeters.right) {
                    drawLine(
                        color = Colors.Eigengrau2,
                        start = Offset(x, visibleSpaceRectMeters.top),
                        end = Offset(x, visibleSpaceRectMeters.bottom),
                        strokeWidth = (if ((x % (gridStep * 10) == 0f)) 3f else 1.5f) / cameraZoom
                    )
                    x += gridStep
                }

                var y = floor(visibleSpaceRectMeters.top / gridStep) * gridStep
                while (y < visibleSpaceRectMeters.bottom) {
                    drawLine(
                        color = Colors.Eigengrau2,
                        start = Offset(visibleSpaceRectMeters.left, y),
                        end = Offset(visibleSpaceRectMeters.right, y),
                        strokeWidth = (if ((y % (gridStep * 10) == 0f)) 3f else 1.5f) / cameraZoom
                    )
                    y += gridStep
                }

                this@zoom.drawUniverse(u)
            }
        }
    }
}
