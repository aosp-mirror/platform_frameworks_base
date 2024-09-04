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
package com.android.internal.widget.remotecompose.core.operations.layout.animation;

import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.ComponentMeasure;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;

import java.util.ArrayList;
import java.util.HashMap;

public class ParticleAnimation {
    HashMap<Integer, ArrayList<Particle>> mAllParticles = new HashMap<>();

    PaintBundle mPaint = new PaintBundle();
    public void animate(PaintContext context, Component component,
                        ComponentMeasure start, ComponentMeasure end,
                        float progress) {
        ArrayList<Particle> particles = mAllParticles.get(component.getComponentId());
        if (particles == null) {
            particles = new ArrayList<Particle>();
            for (int i = 0; i < 20; i++) {
                float x = (float) Math.random();
                float y = (float) Math.random();
                float radius = (float) Math.random();
                float r = 250f;
                float g = 250f;
                float b = 250f;
                particles.add(new Particle(x, y, radius, r, g, b));
            }
            mAllParticles.put(component.getComponentId(), particles);
        }
        context.save();
        context.savePaint();
        for (int i = 0; i < particles.size(); i++) {
            Particle particle = particles.get(i);
            mPaint.reset();
            mPaint.setColor(particle.r, particle.g, particle.b,
                    200 * (1 - progress));
            context.applyPaint(mPaint);
            float dx = start.getX() + component.getWidth() * particle.x;
            float dy = start.getY() + component.getHeight() * particle.y
                    + progress * 0.01f * component.getHeight();
            float dr = (component.getHeight() + 60) * 0.15f * particle.radius + (30 * progress);
            context.drawCircle(dx, dy, dr);
        }
        context.restorePaint();
        context.restore();
    }
}
