/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel.component;

import java.util.Arrays;
import java.util.HashMap;

import com.android.powermodel.ComponentProfile;
import com.android.powermodel.ParseException;

public class CpuProfile extends ComponentProfile {
    public float suspendMa;
    public float idleMa;
    public float activeMa;
    public Cluster[] clusters;

    public static class Cluster {
        public int coreCount;
        public float onMa;
        public Frequency[] frequencies;
    }

    public static class Frequency {
        public int speedHz;
        public float onMa;
    }

    public static class Builder {
        private float mSuspendMa;
        private float mIdleMa;
        private float mActiveMa;
        private int[] mCoreCount;
        private HashMap<Integer,Float> mClusterOnPower = new HashMap<Integer,Float>();
        private HashMap<Integer,int[]> mCoreSpeeds = new HashMap<Integer,int[]>();
        private HashMap<Integer,float[]> mCorePower = new HashMap<Integer,float[]>();

        public Builder() {
        }

        public void setSuspendMa(float value) throws ParseException {
            mSuspendMa = value;
        }

        public void setIdleMa(float value) throws ParseException {
            mIdleMa = value;
        }

        public void setActiveMa(float value) throws ParseException {
            mActiveMa = value;
        }

        public void setCoreCount(int[] value) throws ParseException {
            mCoreCount = Arrays.copyOf(value, value.length);
        }

        public void setClusterPower(int cluster, float value) throws ParseException {
            mClusterOnPower.put(cluster, value);
        }

        public void setCoreSpeeds(int cluster, int[] value) throws ParseException {
            mCoreSpeeds.put(cluster, Arrays.copyOf(value, value.length));
            float[] power = mCorePower.get(cluster);
            if (power != null && value.length != power.length) {
                throw new ParseException("length of cpu.core_speeds.cluster" + cluster
                        + " (" + value.length + ") is different from length of"
                        + " cpu.core_power.cluster" + cluster + " (" + power.length + ")");
            }
            if (mCoreCount != null && cluster >= mCoreCount.length) {
                throw new ParseException("cluster " + cluster
                        + " in cpu.core_speeds.cluster" + cluster
                        + " is larger than the number of clusters specified in cpu.clusters.cores ("
                        + mCoreCount.length + ")");
            }
        }

        public void setCorePower(int cluster, float[] value) throws ParseException {
            mCorePower.put(cluster, Arrays.copyOf(value, value.length));
            int[] speeds = mCoreSpeeds.get(cluster);
            if (speeds != null && value.length != speeds.length) {
                throw new ParseException("length of cpu.core_power.cluster" + cluster
                        + " (" + value.length + ") is different from length of"
                        + " cpu.clusters.cores" + cluster + " (" + speeds.length + ")");
            }
            if (mCoreCount != null && cluster >= mCoreCount.length) {
                throw new ParseException("cluster " + cluster
                        + " in cpu.core_power.cluster" + cluster
                        + " is larger than the number of clusters specified in cpu.clusters.cores ("
                        + mCoreCount.length + ")");
            }
        }

        public CpuProfile build() throws ParseException {
            final CpuProfile result = new CpuProfile();

            // Validate cluster count

            // All null or none null
            // TODO

            // Same size
            // TODO

            // No gaps
            // TODO

            // Fill in values
            result.suspendMa = mSuspendMa;
            result.idleMa = mIdleMa;
            result.activeMa = mActiveMa;
            if (mCoreCount != null) {
                result.clusters = new Cluster[mCoreCount.length];
                for (int i = 0; i < result.clusters.length; i++) {
                    final Cluster cluster = result.clusters[i] = new Cluster();
                    cluster.coreCount = mCoreCount[i];
                    cluster.onMa = mClusterOnPower.get(i);
                    int[] speeds = mCoreSpeeds.get(i);
                    float[] power = mCorePower.get(i);
                    cluster.frequencies = new Frequency[speeds.length];
                    for (int j = 0; j < speeds.length; j++) {
                        final Frequency freq = cluster.frequencies[j] = new Frequency();
                        freq.speedHz = speeds[j];
                        freq.onMa = power[j];
                    }
                }
            }

            return result;
        }
    }
}

