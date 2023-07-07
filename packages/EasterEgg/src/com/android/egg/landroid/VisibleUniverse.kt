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

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.util.lerp
import androidx.core.math.MathUtils.clamp
import java.lang.Float.max
import kotlin.math.sqrt

const val DRAW_ORBITS = true
const val DRAW_GRAVITATIONAL_FIELDS = true
const val DRAW_STAR_GRAVITATIONAL_FIELDS = true

val STAR_POINTS = android.os.Build.VERSION.SDK_INT.takeIf { it in 1..99 } ?: 31

/**
 * A zoomedDrawScope is one that is scaled, but remembers its zoom level, so you can correct for it
 * if you want to draw single-pixel lines. Which we do.
 */
interface ZoomedDrawScope : DrawScope {
    val zoom: Float
}

fun DrawScope.zoom(zoom: Float, block: ZoomedDrawScope.() -> Unit) {
    val ds =
        object : ZoomedDrawScope, DrawScope by this {
            override var zoom = zoom
        }
    ds.scale(zoom) { block(ds) }
}

class VisibleUniverse(namer: Namer, randomSeed: Long) : Universe(namer, randomSeed) {
    // Magic variable. Every time we update it, Compose will notice and redraw the universe.
    val triggerDraw = mutableStateOf(0L)

    fun simulateAndDrawFrame(nanos: Long) {
        // By writing this value, Compose will look for functions that read it (like drawZoomed).
        triggerDraw.value = nanos

        step(nanos)
    }
}

fun ZoomedDrawScope.drawUniverse(universe: VisibleUniverse) {
    with(universe) {
        triggerDraw.value // Please recompose when this value changes.

        //        star.drawZoomed(ds, zoom)
        //        planets.forEach { p ->
        //            p.drawZoomed(ds, zoom)
        //            if (p == follow) {
        //                drawCircle(Color.Red, 20f / zoom, p.pos)
        //            }
        //        }
        //
        //        ship.drawZoomed(ds, zoom)

        constraints.forEach {
            when (it) {
                is Landing -> drawLanding(it)
                is Container -> drawContainer(it)
            }
        }
        drawStar(star)
        entities.forEach {
            if (it === ship || it === star) return@forEach // draw the ship last
            when (it) {
                is Spacecraft -> drawSpacecraft(it)
                is Spark -> drawSpark(it)
                is Planet -> drawPlanet(it)
            }
        }
        drawSpacecraft(ship)
    }
}

fun ZoomedDrawScope.drawContainer(container: Container) {
    drawCircle(
        color = Color(0xFF800000),
        radius = container.radius,
        center = Vec2.Zero,
        style =
            Stroke(
                width = 1f / zoom,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f / zoom, 8f / zoom), 0f)
            )
    )
    //    val path = Path().apply {
    //        fillType = PathFillType.EvenOdd
    //        addOval(Rect(center = Vec2.Zero, radius = container.radius))
    //        addOval(Rect(center = Vec2.Zero, radius = container.radius + 10_000))
    //    }
    //    drawPath(
    //        path = path,
    //
    //    )
}

fun ZoomedDrawScope.drawGravitationalField(planet: Planet) {
    val rings = 8
    for (i in 0 until rings) {
        val force =
            lerp(
                200f,
                0.01f,
                i.toFloat() / rings
            ) // first rings at force = 1N, dropping off after that
        val r = sqrt(GRAVITATION * planet.mass * SPACECRAFT_MASS / force)
        drawCircle(
            color = Color(1f, 0f, 0f, lerp(0.5f, 0.1f, i.toFloat() / rings)),
            center = planet.pos,
            style = Stroke(2f / zoom),
            radius = r
        )
    }
}

fun ZoomedDrawScope.drawPlanet(planet: Planet) {
    with(planet) {
        if (DRAW_ORBITS)
            drawCircle(
                color = Color(0x8000FFFF),
                radius = pos.distance(orbitCenter),
                center = orbitCenter,
                style =
                    Stroke(
                        width = 1f / zoom,
                    )
            )

        if (DRAW_GRAVITATIONAL_FIELDS) {
            drawGravitationalField(this)
        }

        drawCircle(color = Colors.Eigengrau, radius = radius, center = pos)
        drawCircle(color = color, radius = radius, center = pos, style = Stroke(2f / zoom))
    }
}

fun ZoomedDrawScope.drawStar(star: Star) {
    translate(star.pos.x, star.pos.y) {
        drawCircle(color = star.color, radius = star.radius, center = Vec2.Zero)

        if (DRAW_STAR_GRAVITATIONAL_FIELDS) this@drawStar.drawGravitationalField(star)

        rotateRad(radians = star.anim / 23f * PI2f, pivot = Vec2.Zero) {
            drawPath(
                path =
                    createStar(
                        radius1 = star.radius + 80,
                        radius2 = star.radius + 250,
                        points = STAR_POINTS
                    ),
                color = star.color,
                style =
                    Stroke(
                        width = 3f / this@drawStar.zoom,
                        pathEffect = PathEffect.cornerPathEffect(radius = 200f)
                    )
            )
        }
        rotateRad(radians = star.anim / -19f * PI2f, pivot = Vec2.Zero) {
            drawPath(
                path =
                    createStar(
                        radius1 = star.radius + 20,
                        radius2 = star.radius + 200,
                        points = STAR_POINTS + 1
                    ),
                color = star.color,
                style =
                    Stroke(
                        width = 3f / this@drawStar.zoom,
                        pathEffect = PathEffect.cornerPathEffect(radius = 200f)
                    )
            )
        }
    }
}

val spaceshipPath =
    Path().apply {
        parseSvgPathData(
            """
M11.853 0
C11.853 -4.418 8.374 -8 4.083 -8
L-5.5 -8
C-6.328 -8 -7 -7.328 -7 -6.5
C-7 -5.672 -6.328 -5 -5.5 -5
L-2.917 -5
C-1.26 -5 0.083 -3.657 0.083 -2
L0.083 2
C0.083 3.657 -1.26 5 -2.917 5
L-5.5 5
C-6.328 5 -7 5.672 -7 6.5
C-7 7.328 -6.328 8 -5.5 8
L4.083 8
C8.374 8 11.853 4.418 11.853 0
Z
"""
        )
    }
val thrustPath = createPolygon(-3f, 3).also { it.translate(Vec2(-4f, 0f)) }

fun ZoomedDrawScope.drawSpacecraft(ship: Spacecraft) {
    with(ship) {
        rotateRad(angle, pivot = pos) {
            translate(pos.x, pos.y) {
                //                drawPath(
                //                    path = createStar(200f, 100f, 3),
                //                    color = Color.White,
                //                    style = Stroke(width = 2f / zoom)
                //                )
                drawPath(path = spaceshipPath, color = Colors.Eigengrau) // fauxpaque
                drawPath(
                    path = spaceshipPath,
                    color = if (transit) Color.Black else Color.White,
                    style = Stroke(width = 2f / this@drawSpacecraft.zoom)
                )
                if (thrust != Vec2.Zero) {
                    drawPath(
                        path = thrustPath,
                        color = Color(0xFFFF8800),
                        style =
                            Stroke(
                                width = 2f / this@drawSpacecraft.zoom,
                                pathEffect = PathEffect.cornerPathEffect(radius = 1f)
                            )
                    )
                }
                //                drawRect(
                //                    topLeft = Offset(-1f, -1f),
                //                    size = Size(2f, 2f),
                //                    color = Color.Cyan,
                //                    style = Stroke(width = 2f / zoom)
                //                )
                //                drawLine(
                //                    start = Vec2.Zero,
                //                    end = Vec2(20f, 0f),
                //                    color = Color.Cyan,
                //                    strokeWidth = 2f / zoom
                //                )
            }
        }
        //        // DEBUG: draw velocity vector
        //        drawLine(
        //            start = pos,
        //            end = pos + velocity,
        //            color = Color.Red,
        //            strokeWidth = 3f / zoom
        //        )
        drawTrack(track)
    }
}

fun ZoomedDrawScope.drawLanding(landing: Landing) {
    val v = landing.planet.pos + Vec2.makeWithAngleMag(landing.angle, landing.planet.radius)
    drawLine(Color.Red, v + Vec2(-5f, -5f), v + Vec2(5f, 5f), strokeWidth = 1f / zoom)
    drawLine(Color.Red, v + Vec2(5f, -5f), v + Vec2(-5f, 5f), strokeWidth = 1f / zoom)
}

fun ZoomedDrawScope.drawSpark(spark: Spark) {
    with(spark) {
        if (lifetime < 0) return
        when (style) {
            Spark.Style.LINE ->
                if (opos != Vec2.Zero) drawLine(color, opos, pos, strokeWidth = size)
            Spark.Style.LINE_ABSOLUTE ->
                if (opos != Vec2.Zero) drawLine(color, opos, pos, strokeWidth = size / zoom)
            Spark.Style.DOT -> drawCircle(color, size, pos)
            Spark.Style.DOT_ABSOLUTE -> drawCircle(color, size, pos / zoom)
            Spark.Style.RING -> drawCircle(color, size, pos, style = Stroke(width = 1f / zoom))
        //                drawPoints(listOf(pos), PointMode.Points, color, strokeWidth = 2f/zoom)
        //            drawCircle(color, 2f/zoom, pos)
        }
        //        drawCircle(Color.Gray, center = pos, radius = 1.5f / zoom)
    }
}

fun ZoomedDrawScope.drawTrack(track: Track) {
    with(track) {
        if (SIMPLE_TRACK_DRAWING) {
            drawPoints(
                positions,
                pointMode = PointMode.Lines,
                color = Color.Green,
                strokeWidth = 1f / zoom
            )
            //            if (positions.size < 2) return
            //            drawPath(Path()
            //                .apply {
            //                    val p = positions[positions.size - 1]
            //                    moveTo(p.x, p.y)
            //                    positions.reversed().subList(1, positions.size).forEach { p ->
            //                        lineTo(p.x, p.y)
            //                    }
            //                },
            //                color = Color.Green, style = Stroke(1f/zoom))
        } else {
            if (positions.size < 2) return
            var prev: Vec2 = positions[positions.size - 1]
            var a = 0.5f
            positions.reversed().subList(1, positions.size).forEach { pos ->
                drawLine(Color(0f, 1f, 0f, a), prev, pos, strokeWidth = max(1f, 1f / zoom))
                prev = pos
                a = clamp((a - 1f / TRACK_LENGTH), 0f, 1f)
            }
        }
    }
}
