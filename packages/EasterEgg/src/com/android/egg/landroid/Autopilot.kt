/*
 * Copyright (C) 2024 The Android Open Source Project
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

import kotlin.math.min
import kotlin.math.sign

class Autopilot(val ship: Spacecraft, val universe: Universe) : Entity {
    val BRAKING_TIME = 5f
    val SIGHTSEEING_TIME = 15f
    val LAUNCH_THRUST_TIME = 5f
    val STRATEGY_MIN_TIME = 0.5f

    var enabled = false

    var target: Planet? = null

    var landingAltitude = 0f

    var nextStrategyTime = 0f

    var brakingDistance = 0f

    // used by rendering
    var leadingPos = Vec2.Zero
    var leadingVector = Vec2.Zero

    val telemetry: String
        get() =
            listOf(
                    "---- AUTOPILOT ENGAGED ----",
                    "TGT: " + (target?.name?.toUpperCase() ?: "SELECTING..."),
                    "EXE: $strategy" + if (debug.isNotEmpty()) " ($debug)" else "",
                )
                .joinToString("\n")

    private var strategy: String = "NONE"
    private var debug: String = ""

    override fun update(sim: Simulator, dt: Float) {
        if (!enabled) return

        if (sim.now < nextStrategyTime) {
            return
        }

        val currentStrategy = strategy

        if (ship.landing != null) {
            if (target != null) {
                strategy = "LANDED"
                debug = ""
                // we just got here. see the sights.
                target = null
                landingAltitude = 0f
                nextStrategyTime = sim.now + SIGHTSEEING_TIME
            } else {
                // full power until we blast off
                ship.thrust = Vec2.makeWithAngleMag(ship.angle, 1f)

                strategy = "LAUNCHING"
                debug = ""
                nextStrategyTime = sim.now + LAUNCH_THRUST_TIME
            }
        } else {
            // select new target

            if (target == null) {
                // testing: target the first planet
                //   target = universe.planets[0]

                // target the nearest unexplored planet
                target =
                    universe.planets
                        .sortedBy { (it.pos - ship.pos).mag() }
                        .firstOrNull { !it.explored }
                brakingDistance = 0f

                // if we've explored them all, pick one at random
                if (target == null) target = universe.planets.random()
            }

            target?.let { target -> // should be nonnull
                val shipV = ship.velocity
                val targetV = target.velocity
                val targetVector = (target.pos - ship.pos)
                val altitude = targetVector.mag() - target.radius

                landingAltitude = min(target.radius, 100f)

                // the following is in the moving reference frame of the target
                val relativeV: Vec2 = shipV - targetV
                val projection = relativeV.dot(targetVector / targetVector.mag())
                val relativeSpeed = relativeV.mag() * projection.sign
                val timeToTarget = if (relativeSpeed != 0f) altitude / relativeSpeed else 1_000f

                val newBrakingDistance =
                    BRAKING_TIME * if (relativeSpeed > 0) relativeSpeed else MAIN_ENGINE_ACCEL
                brakingDistance =
                    expSmooth(brakingDistance, newBrakingDistance, dt = sim.dt, speed = 5f)

                // We're going to aim at where the target will be, but we want to make sure to
                // compute
                leadingPos =
                    target.pos +
                        Vec2.makeWithAngleMag(
                            target.velocity.angle(),
                            min(altitude / 2, target.velocity.mag())
                        )
                leadingVector = leadingPos - ship.pos

                if (altitude < landingAltitude) {
                    strategy = "LANDING"
                    // Strategy: zero thrust, face away, prepare for landing

                    ship.angle = (ship.pos - target.pos).angle() // point away from ground
                    ship.thrust = Vec2.Zero
                } else {
                    if (relativeSpeed < 0 || altitude > brakingDistance) {
                        strategy = "CHASING"
                        // Strategy: Make tracks. We are either a long way away, or falling behind.
                        ship.angle = leadingVector.angle()

                        ship.thrust = Vec2.makeWithAngleMag(ship.angle, 1.0f)
                    } else {
                        strategy = "APPROACHING"
                        // Strategy: Just slow down. If we get caught in the gravity well, it will
                        // gradually start pulling us more in the direction of the planet, which
                        // will create a graceful deceleration
                        ship.angle = (-ship.velocity).angle()

                        // We want to bleed off velocity over time. Specifically, relativeSpeed px/s
                        // over timeToTarget seconds.
                        val decel = relativeSpeed / timeToTarget
                        val decelThrust =
                            decel / MAIN_ENGINE_ACCEL * 0.9f // not quite slowing down enough
                        ship.thrust = Vec2.makeWithAngleMag(ship.angle, decelThrust)
                    }
                }
                debug = ("DV=%.0f D=%.0f T%+.1f").format(relativeSpeed, altitude, timeToTarget)
            }
            if (strategy != currentStrategy) {
                nextStrategyTime = sim.now + STRATEGY_MIN_TIME
            }
        }
    }

    override fun postUpdate(sim: Simulator, dt: Float) {
        if (!enabled) return
    }
}
