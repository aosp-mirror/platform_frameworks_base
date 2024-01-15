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

import android.util.ArraySet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.lerp
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.sqrt

const val UNIVERSE_RANGE = 200_000f

val NUM_PLANETS_RANGE = 1..10
val STAR_RADIUS_RANGE = (1_000f..8_000f)
val PLANET_RADIUS_RANGE = (50f..2_000f)
val PLANET_ORBIT_RANGE = (STAR_RADIUS_RANGE.endInclusive * 2f)..(UNIVERSE_RANGE * 0.75f)

const val GRAVITATION = 1e-2f
const val KEPLER_CONSTANT = 50f // * 4f * PIf * PIf / GRAVITATION

// m = d * r
const val PLANETARY_DENSITY = 2.5f
const val STELLAR_DENSITY = 0.5f

const val SPACECRAFT_MASS = 10f

const val CRAFT_SPEED_LIMIT = 5_000f
const val MAIN_ENGINE_ACCEL = 1000f // thrust effect, pixels per second squared
const val LAUNCH_MECO = 2f // how long to suspend gravity when launching

const val SCALED_THRUST = true

interface Removable {
    fun canBeRemoved(): Boolean
}

open class Planet(
    val orbitCenter: Vec2,
    radius: Float,
    pos: Vec2,
    var speed: Float,
    var color: Color = Color.White
) : Body() {
    var atmosphere = ""
    var description = ""
    var flora = ""
    var fauna = ""
    var explored = false
    private val orbitRadius: Float
    init {
        this.radius = radius
        this.pos = pos
        orbitRadius = pos.distance(orbitCenter)
        mass = 4 / 3 * PIf * radius.pow(3) * PLANETARY_DENSITY
    }

    override fun update(sim: Simulator, dt: Float) {
        val orbitAngle = (pos - orbitCenter).angle()
        // constant linear velocity
        velocity = Vec2.makeWithAngleMag(orbitAngle + PIf / 2f, speed)

        super.update(sim, dt)
    }

    override fun postUpdate(sim: Simulator, dt: Float) {
        // This is kind of like a constraint, but whatever.
        val orbitAngle = (pos - orbitCenter).angle()
        pos = orbitCenter + Vec2.makeWithAngleMag(orbitAngle, orbitRadius)
        super.postUpdate(sim, dt)
    }
}

enum class StarClass {
    O,
    B,
    A,
    F,
    G,
    K,
    M
}

fun starColor(cls: StarClass) =
    when (cls) {
        StarClass.O -> Color(0xFF6666FF)
        StarClass.B -> Color(0xFFCCCCFF)
        StarClass.A -> Color(0xFFEEEEFF)
        StarClass.F -> Color(0xFFFFFFFF)
        StarClass.G -> Color(0xFFFFFF66)
        StarClass.K -> Color(0xFFFFCC33)
        StarClass.M -> Color(0xFFFF8800)
    }

class Star(val cls: StarClass, radius: Float) :
    Planet(orbitCenter = Vec2.Zero, radius = radius, pos = Vec2.Zero, speed = 0f) {
    init {
        pos = Vec2.Zero
        mass = 4 / 3 * PIf * radius.pow(3) * STELLAR_DENSITY
        color = starColor(cls)
        collides = false
    }
    var anim = 0f
    override fun update(sim: Simulator, dt: Float) {
        anim += dt
    }
}

open class Universe(val namer: Namer, randomSeed: Long) : Simulator(randomSeed) {
    var latestDiscovery: Planet? = null
    lateinit var star: Star
    lateinit var ship: Spacecraft
    val planets: MutableList<Planet> = mutableListOf()
    var follow: Body? = null
    val ringfence = Container(UNIVERSE_RANGE)

    fun initTest() {
        val systemName = "TEST SYSTEM"
        star =
            Star(
                    cls = StarClass.A,
                    radius = STAR_RADIUS_RANGE.endInclusive,
                )
                .apply { name = "TEST SYSTEM" }

        repeat(NUM_PLANETS_RANGE.last) {
            val thisPlanetFrac = it.toFloat() / (NUM_PLANETS_RANGE.last - 1)
            val radius =
                lerp(PLANET_RADIUS_RANGE.start, PLANET_RADIUS_RANGE.endInclusive, thisPlanetFrac)
            val orbitRadius =
                lerp(PLANET_ORBIT_RANGE.start, PLANET_ORBIT_RANGE.endInclusive, thisPlanetFrac)

            val period = sqrt(orbitRadius.pow(3f) / star.mass) * KEPLER_CONSTANT
            val speed = 2f * PIf * orbitRadius / period

            val p =
                Planet(
                    orbitCenter = star.pos,
                    radius = radius,
                    pos = star.pos + Vec2.makeWithAngleMag(thisPlanetFrac * PI2f, orbitRadius),
                    speed = speed,
                    color = Colors.Eigengrau4
                )
            android.util.Log.v(
                "Landroid",
                "created planet $p with period $period and vel $speed"
            )
            val num = it + 1
            p.description = "TEST PLANET #$num"
            p.atmosphere = "radius=$radius"
            p.flora = "mass=${p.mass}"
            p.fauna = "speed=$speed"
            planets.add(p)
            add(p)
        }

        planets.sortBy { it.pos.distance(star.pos) }
        planets.forEachIndexed { idx, planet -> planet.name = "$systemName ${idx + 1}" }
        add(star)

        ship = Spacecraft()

        // in the test universe, start the ship near the outermost planet
        ship.pos = planets.last().pos + Vec2(planets.first().radius * 1.5f, 0f)

        ship.angle = 0f
        add(ship)

        ringfence.add(ship)
        add(ringfence)

        follow = ship
    }

    fun initRandom() {
        val systemName = namer.nameSystem(rng)
        star =
            Star(
                cls = rng.choose(StarClass.values()),
                radius = rng.nextFloatInRange(STAR_RADIUS_RANGE)
            )
        star.name = systemName
        repeat(rng.nextInt(NUM_PLANETS_RANGE.first, NUM_PLANETS_RANGE.last + 1)) {
            val radius = rng.nextFloatInRange(PLANET_RADIUS_RANGE)
            val orbitRadius =
                lerp(
                    PLANET_ORBIT_RANGE.start,
                    PLANET_ORBIT_RANGE.endInclusive,
                    rng.nextFloat().pow(1f)
                )

            // Kepler's third law
            val period = sqrt(orbitRadius.pow(3f) / star.mass) * KEPLER_CONSTANT
            val speed = 2f * PIf * orbitRadius / period

            val p =
                Planet(
                    orbitCenter = star.pos,
                    radius = radius,
                    pos = star.pos + Vec2.makeWithAngleMag(rng.nextFloat() * PI2f, orbitRadius),
                    speed = speed,
                    color = Colors.Eigengrau4
                )
            android.util.Log.v(
                "Landroid",
                "created planet $p with period $period and vel $speed"
            )
            p.description = namer.describePlanet(rng)
            p.atmosphere = namer.describeAtmo(rng)
            p.flora = namer.describeLife(rng)
            p.fauna = namer.describeLife(rng)
            planets.add(p)
            add(p)
        }
        planets.sortBy { it.pos.distance(star.pos) }
        planets.forEachIndexed { idx, planet -> planet.name = "$systemName ${idx + 1}" }
        add(star)

        ship = Spacecraft()

        ship.pos =
            star.pos +
                Vec2.makeWithAngleMag(
                    rng.nextFloat() * PI2f,
                    rng.nextFloatInRange(PLANET_ORBIT_RANGE.start, PLANET_ORBIT_RANGE.endInclusive)
                )
        ship.angle = rng.nextFloat() * PI2f
        add(ship)

        ringfence.add(ship)
        add(ringfence)

        follow = ship
    }

    override fun updateAll(dt: Float, entities: ArraySet<Entity>) {
        // check for passing in front of the sun
        ship.transit = false

        (planets + star).forEach { planet ->
            val vector = planet.pos - ship.pos
            val d = vector.mag()
            if (d < planet.radius) {
                if (planet is Star) ship.transit = true
            } else if (
                now > ship.launchClock + LAUNCH_MECO
            ) { // within MECO sec of launch, no gravity at all
                // simulate gravity: $ f_g = G * m1 * m2 * 1/d^2 $
                ship.velocity =
                    ship.velocity +
                        Vec2.makeWithAngleMag(
                            vector.angle(),
                            GRAVITATION * (ship.mass * planet.mass) / d.pow(2)
                        ) * dt
            }
        }

        super.updateAll(dt, entities)
    }

    fun closestPlanet(): Planet {
        val bodiesByDist =
            (planets + star)
                .map { planet -> (planet.pos - ship.pos) to planet }
                .sortedBy { it.first.mag() }

        return bodiesByDist[0].second
    }

    override fun solveAll(dt: Float, constraints: ArraySet<Constraint>) {
        if (ship.landing == null) {
            val planet = closestPlanet()

            if (planet.collides) {
                val d = (ship.pos - planet.pos).mag() - ship.radius - planet.radius
                val a = (ship.pos - planet.pos).angle()

                if (d < 0) {
                    // landing, or impact?

                    // 1. relative speed
                    val vDiff = (ship.velocity - planet.velocity).mag()
                    // 2. landing angle
                    val aDiff = (ship.angle - a).absoluteValue

                    // landing criteria
                    if (aDiff < PIf / 4
                    //                        &&
                    //                        vDiff < 100f
                    ) {
                        val landing = Landing(ship, planet, a)
                        ship.landing = landing
                        ship.velocity = planet.velocity
                        add(landing)

                        planet.explored = true
                        latestDiscovery = planet
                    } else {
                        val impact = planet.pos + Vec2.makeWithAngleMag(a, planet.radius)
                        ship.pos =
                            planet.pos + Vec2.makeWithAngleMag(a, planet.radius + ship.radius - d)

                        //                        add(Spark(
                        //                            lifetime = 1f,
                        //                            style = Spark.Style.DOT,
                        //                            color = Color.Yellow,
                        //                            size = 10f
                        //                        ).apply {
                        //                            pos = impact
                        //                            opos = impact
                        //                            velocity = Vec2.Zero
                        //                        })
                        //
                        (1..10).forEach {
                            Spark(
                                    lifetime = rng.nextFloatInRange(0.5f, 2f),
                                    style = Spark.Style.DOT,
                                    color = Color.White,
                                    size = 1f
                                )
                                .apply {
                                    pos =
                                        impact +
                                            Vec2.makeWithAngleMag(
                                                rng.nextFloatInRange(0f, 2 * PIf),
                                                rng.nextFloatInRange(0.1f, 0.5f)
                                            )
                                    opos = pos
                                    velocity =
                                        ship.velocity * 0.8f +
                                            Vec2.makeWithAngleMag(
                                                //                                            a +
                                                // rng.nextFloatInRange(-PIf, PIf),
                                                rng.nextFloatInRange(0f, 2 * PIf),
                                                rng.nextFloatInRange(0.1f, 0.5f)
                                            )
                                    add(this)
                                }
                        }
                    }
                }
            }
        }

        super.solveAll(dt, constraints)
    }

    override fun postUpdateAll(dt: Float, entities: ArraySet<Entity>) {
        super.postUpdateAll(dt, entities)

        entities
            .filterIsInstance<Removable>()
            .filter(predicate = Removable::canBeRemoved)
            .filterIsInstance<Entity>()
            .forEach { remove(it) }
    }
}

class Landing(val ship: Spacecraft, val planet: Planet, val angle: Float) : Constraint {
    private val landingVector = Vec2.makeWithAngleMag(angle, ship.radius + planet.radius)
    override fun solve(sim: Simulator, dt: Float) {
        val desiredPos = planet.pos + landingVector
        ship.pos = (ship.pos * 0.5f) + (desiredPos * 0.5f) // @@@ FIXME
        ship.angle = angle
    }
}

class Spark(
    var lifetime: Float,
    collides: Boolean = false,
    mass: Float = 0f,
    val style: Style = Style.LINE,
    val color: Color = Color.Gray,
    val size: Float = 2f
) : Removable, Body() {
    enum class Style {
        LINE,
        LINE_ABSOLUTE,
        DOT,
        DOT_ABSOLUTE,
        RING
    }

    init {
        this.collides = collides
        this.mass = mass
    }
    override fun update(sim: Simulator, dt: Float) {
        super.update(sim, dt)
        lifetime -= dt
    }
    override fun canBeRemoved(): Boolean {
        return lifetime < 0
    }
}

const val TRACK_LENGTH = 10_000
const val SIMPLE_TRACK_DRAWING = true

class Track {
    val positions = ArrayDeque<Vec2>(TRACK_LENGTH)
    private val angles = ArrayDeque<Float>(TRACK_LENGTH)
    fun add(x: Float, y: Float, a: Float) {
        if (positions.size >= (TRACK_LENGTH - 1)) {
            positions.removeFirst()
            angles.removeFirst()
            positions.removeFirst()
            angles.removeFirst()
        }
        positions.addLast(Vec2(x, y))
        angles.addLast(a)
    }
}

class Spacecraft : Body() {
    var thrust = Vec2.Zero
    var launchClock = 0f

    var transit = false

    val track = Track()

    var landing: Landing? = null

    init {
        mass = SPACECRAFT_MASS
        radius = 12f
    }

    override fun update(sim: Simulator, dt: Float) {
        // check for thrusters
        val thrustMag = thrust.mag()
        if (thrustMag > 0) {
            var deltaV = MAIN_ENGINE_ACCEL * dt
            if (SCALED_THRUST) deltaV *= thrustMag.coerceIn(0f, 1f)

            if (landing == null) {
                // we are free in space, so we attempt to pivot toward the desired direction
                // NOTE: no longer required thanks to FlightStick
                // angle = thrust.angle()
            } else
                landing?.let { landing ->
                    if (launchClock == 0f) launchClock = sim.now + 1f /* @@@ TODO extract */

                    if (sim.now > launchClock) {
                        // first-stage to orbit has 1000x power
                        //                    deltaV *= 1000f
                        sim.remove(landing)
                        this.landing = null
                    } else {
                        deltaV = 0f
                    }
                }

            // this is it. impart thrust to the ship.
            // note that we always thrust in the forward direction
            velocity += Vec2.makeWithAngleMag(angle, deltaV)
        } else {
            if (launchClock != 0f) launchClock = 0f
        }

        // apply global speed limit
        if (velocity.mag() > CRAFT_SPEED_LIMIT)
            velocity = Vec2.makeWithAngleMag(velocity.angle(), CRAFT_SPEED_LIMIT)

        super.update(sim, dt)
    }

    override fun postUpdate(sim: Simulator, dt: Float) {
        super.postUpdate(sim, dt)

        // special effects all need to be added after the simulation step so they have
        // the correct position of the ship.
        track.add(pos.x, pos.y, angle)

        val mag = thrust.mag()
        if (sim.rng.nextFloat() < mag) {
            // exhaust
            sim.add(
                Spark(
                        lifetime = sim.rng.nextFloatInRange(0.5f, 1f),
                        collides = true,
                        mass = 1f,
                        style = Spark.Style.RING,
                        size = 3f,
                        color = Color(0x40FFFFFF)
                    )
                    .also { spark ->
                        spark.pos = pos
                        spark.opos = pos
                        spark.velocity =
                            velocity +
                                Vec2.makeWithAngleMag(
                                    angle + sim.rng.nextFloatInRange(-0.2f, 0.2f),
                                    -MAIN_ENGINE_ACCEL * mag * 10f * dt
                                )
                    }
            )
        }
    }
}
