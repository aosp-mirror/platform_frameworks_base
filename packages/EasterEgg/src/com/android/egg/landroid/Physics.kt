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
import kotlin.random.Random

// artificially speed up or slow down the simulation
const val TIME_SCALE = 1f // simulation seconds per wall clock second

// if it's been over 1 real second since our last timestep, don't simulate that elapsed time.
// this allows the simulation to "pause" when, for example, the activity pauses
const val MAX_VALID_DT = 1f

interface Entity {
    // Integrate.
    // Compute accelerations from forces, add accelerations to velocity, save old position,
    // add velocity to position.
    fun update(sim: Simulator, dt: Float)

    // Post-integration step, after constraints are satisfied.
    fun postUpdate(sim: Simulator, dt: Float)
}

interface Removable {
    fun canBeRemoved(): Boolean
}

class Fuse(var lifetime: Float) : Removable {
    fun update(dt: Float) {
        lifetime -= dt
    }
    override fun canBeRemoved(): Boolean {
        return lifetime < 0
    }
}

open class Body(var name: String = "Unknown") : Entity {
    var pos = Vec2.Zero
    var opos = Vec2.Zero
    var velocity = Vec2.Zero

    var mass = 0f
    var angle = 0f
    var radius = 0f

    var collides = true

    var omega: Float
        get() = angle - oangle
        set(value) {
            oangle = angle - value
        }

    var oangle = 0f

    override fun update(sim: Simulator, dt: Float) {
        if (dt <= 0) return

        // integrate velocity
        val vscaled = velocity * dt
        opos = pos
        pos += vscaled

        // integrate angular velocity
        //        val wscaled = omega * timescale
        //        oangle = angle
        //        angle = (angle + wscaled) % PI2f
    }

    override fun postUpdate(sim: Simulator, dt: Float) {
        if (dt <= 0) return
        velocity = (pos - opos) / dt
    }
}

interface Constraint {
    // Solve constraints. Pick up objects and put them where they are "supposed" to be.
    fun solve(sim: Simulator, dt: Float)
}

open class Container(val radius: Float) : Constraint {
    private val list = ArraySet<Body>()
    private val softness = 0.0f

    override fun toString(): String {
        return "Container($radius)"
    }

    fun add(p: Body) {
        list.add(p)
    }

    fun remove(p: Body) {
        list.remove(p)
    }

    override fun solve(sim: Simulator, dt: Float) {
        for (p in list) {
            if ((p.pos.mag() + p.radius) > radius) {
                p.pos =
                    p.pos * (softness) +
                        Vec2.makeWithAngleMag(p.pos.angle(), radius - p.radius) * (1f - softness)
            }
        }
    }
}

open class Simulator(val randomSeed: Long) {
    private var wallClockNanos: Long = 0L
    var now: Float = 0f
    var dt: Float = 0f
    val rng = Random(randomSeed)
    val entities = ArraySet<Entity>(1000)
    val constraints = ArraySet<Constraint>(100)

    fun add(e: Entity) = entities.add(e)
    fun remove(e: Entity) = entities.remove(e)
    fun add(c: Constraint) = constraints.add(c)
    fun remove(c: Constraint) = constraints.remove(c)

    open fun updateAll(dt: Float, entities: ArraySet<Entity>) {
        entities.forEach { it.update(this, dt) }
    }

    open fun solveAll(dt: Float, constraints: ArraySet<Constraint>) {
        constraints.forEach { it.solve(this, dt) }
    }

    open fun postUpdateAll(dt: Float, entities: ArraySet<Entity>) {
        entities.forEach { it.postUpdate(this, dt) }
    }

    fun step(nanos: Long) {
        val firstFrame = (wallClockNanos == 0L)

        dt = (nanos - wallClockNanos) / 1_000_000_000f * TIME_SCALE
        this.wallClockNanos = nanos

        // we start the simulation on the next frame
        if (firstFrame || dt > MAX_VALID_DT) return

        // simulation is running; we start accumulating simulation time
        this.now += dt

        val localEntities = ArraySet(entities)
        val localConstraints = ArraySet(constraints)

        // position-based dynamics approach:
        // 1. apply acceleration to velocity, save positions, apply velocity to position
        updateAll(dt, localEntities)

        // 2. solve all constraints
        solveAll(dt, localConstraints)

        // 3. compute new velocities from updated positions and saved positions
        postUpdateAll(dt, localEntities)
    }
}
