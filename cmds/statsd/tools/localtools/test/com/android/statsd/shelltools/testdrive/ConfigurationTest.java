/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.statsd.shelltools.testdrive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.internal.os.StatsdConfigProto;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.AtomsProto;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link TestDrive}
 */
public class ConfigurationTest {

    private StatsdConfigProto.AtomMatcher findAndRemoveAtomMatcherById(
            List<StatsdConfigProto.AtomMatcher> atomMatchers, long id) {
        int numMatches = 0;
        StatsdConfigProto.AtomMatcher match = null;
        for (StatsdConfigProto.AtomMatcher atomMatcher : atomMatchers) {
            if (id == atomMatcher.getId()) {
                ++numMatches;
                match = atomMatcher;
            }
        }
        if (numMatches == 1) {
            atomMatchers.remove(match);
            return match;
        }
        return null;  // Too many, or not found
    }

    private final TestDrive.Configuration mConfiguration = new TestDrive.Configuration();

    @Test
    public void testOnePushed() {
        final int atom = 90;
        assertFalse(TestDrive.Configuration.isPulledAtom(atom));
        mConfiguration.addAtom(atom);
        StatsdConfig config = mConfiguration.createConfig();

        //event_metric {
        //  id: 1111
        //  what: 1234567
        //}
        //atom_matcher {
        //  id: 1234567
        //  simple_atom_matcher {
        //    atom_id: 90
        //  }
        //}

        assertEquals(1, config.getEventMetricCount());
        assertEquals(0, config.getGaugeMetricCount());

        assertTrue(mConfiguration.isTrackedMetric(config.getEventMetric(0).getId()));

        final List<StatsdConfigProto.AtomMatcher> atomMatchers =
                new ArrayList<>(config.getAtomMatcherList());
        assertEquals(atom,
                findAndRemoveAtomMatcherById(atomMatchers, config.getEventMetric(0).getWhat())
                        .getSimpleAtomMatcher().getAtomId());
        assertEquals(0, atomMatchers.size());
    }

    @Test
    public void testOnePulled() {
        final int atom = 10022;
        assertTrue(TestDrive.Configuration.isPulledAtom(atom));
        mConfiguration.addAtom(atom);
        StatsdConfig config = mConfiguration.createConfig();

        //gauge_metric {
        //  id: 1111
        //  what: 1234567
        //  gauge_fields_filter {
        //    include_all: true
        //  }
        //  bucket: ONE_MINUTE
        //  sampling_type: FIRST_N_SAMPLES
        //  max_num_gauge_atoms_per_bucket: 100
        //  trigger_event: 1111111
        //}
        //atom_matcher {
        //  id: 1111111
        //  simple_atom_matcher {
        //    atom_id: 47
        //  }
        //}
        //atom_matcher {
        //  id: 1234567
        //  simple_atom_matcher {
        //    atom_id: 10022
        //  }
        //}

        assertEquals(0, config.getEventMetricCount());
        assertEquals(1, config.getGaugeMetricCount());

        assertTrue(mConfiguration.isTrackedMetric(config.getGaugeMetric(0).getId()));

        final StatsdConfigProto.GaugeMetric gaugeMetric = config.getGaugeMetric(0);
        assertTrue(gaugeMetric.getGaugeFieldsFilter().getIncludeAll());

        final List<StatsdConfigProto.AtomMatcher> atomMatchers =
                new ArrayList<>(config.getAtomMatcherList());
        assertEquals(atom,
                findAndRemoveAtomMatcherById(atomMatchers, gaugeMetric.getWhat())
                        .getSimpleAtomMatcher().getAtomId());
        assertEquals(AtomsProto.Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER,
                findAndRemoveAtomMatcherById(atomMatchers, gaugeMetric.getTriggerEvent())
                        .getSimpleAtomMatcher().getAtomId());
        assertEquals(0, atomMatchers.size());
    }

    @Test
    public void testOnePulledTwoPushed() {
        final int pulledAtom = 10022;
        assertTrue(TestDrive.Configuration.isPulledAtom(pulledAtom));
        mConfiguration.addAtom(pulledAtom);

        Integer[] pushedAtoms = new Integer[]{244, 245};
        for (int atom : pushedAtoms) {
            assertFalse(TestDrive.Configuration.isPulledAtom(atom));
            mConfiguration.addAtom(atom);
        }
        StatsdConfig config = mConfiguration.createConfig();

        //  event_metric {
        //    id: 1111
        //    what: 1234567
        //  }
        //  event_metric {
        //    id: 1112
        //    what: 1234568
        //  }
        //  gauge_metric {
        //    id: 1114
        //    what: 1234570
        //    gauge_fields_filter {
        //      include_all: true
        //    }
        //    bucket: ONE_MINUTE
        //    sampling_type: FIRST_N_SAMPLES
        //    max_num_gauge_atoms_per_bucket: 100
        //    trigger_event: 1111111
        //  }
        //  atom_matcher {
        //    id: 1111111
        //    simple_atom_matcher {
        //      atom_id: 47
        //    }
        //  }
        //  atom_matcher {
        //    id: 1234567
        //    simple_atom_matcher {
        //      atom_id: 244
        //    }
        //  }
        //  atom_matcher {
        //    id: 1234568
        //    simple_atom_matcher {
        //      atom_id: 245
        //    }
        //  }
        //  atom_matcher {
        //    id: 1234570
        //    simple_atom_matcher {
        //      atom_id: 10022
        //    }
        //  }

        assertEquals(2, config.getEventMetricCount());
        assertEquals(1, config.getGaugeMetricCount());

        final StatsdConfigProto.GaugeMetric gaugeMetric = config.getGaugeMetric(0);
        assertTrue(mConfiguration.isTrackedMetric(gaugeMetric.getId()));
        assertTrue(gaugeMetric.getGaugeFieldsFilter().getIncludeAll());
        for (StatsdConfigProto.EventMetric eventMetric : config.getEventMetricList()) {
            assertTrue(mConfiguration.isTrackedMetric(eventMetric.getId()));
        }

        final List<StatsdConfigProto.AtomMatcher> atomMatchers =
                new ArrayList<>(config.getAtomMatcherList());

        assertEquals(pulledAtom, findAndRemoveAtomMatcherById(atomMatchers, gaugeMetric.getWhat())
                .getSimpleAtomMatcher().getAtomId());
        assertEquals(AtomsProto.Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER,
                findAndRemoveAtomMatcherById(atomMatchers, gaugeMetric.getTriggerEvent())
                        .getSimpleAtomMatcher().getAtomId());

        Integer[] actualAtoms = new Integer[]{
                findAndRemoveAtomMatcherById(atomMatchers, config.getEventMetric(0).getWhat())
                        .getSimpleAtomMatcher().getAtomId(),
                findAndRemoveAtomMatcherById(atomMatchers, config.getEventMetric(1).getWhat())
                        .getSimpleAtomMatcher().getAtomId()};
        Arrays.sort(actualAtoms);
        assertArrayEquals(pushedAtoms, actualAtoms);

        assertEquals(0, atomMatchers.size());
    }

    @Test
    public void testOnePulledTwoPushedTogether() {
        mConfiguration.mOnePushedAtomEvent = true;  // Use one event grabbing all pushed atoms

        final int pulledAtom = 10022;
        assertTrue(TestDrive.Configuration.isPulledAtom(pulledAtom));
        mConfiguration.addAtom(pulledAtom);

        Integer[] pushedAtoms = new Integer[]{244, 245};
        for (int atom : pushedAtoms) {
            assertFalse(TestDrive.Configuration.isPulledAtom(atom));
            mConfiguration.addAtom(atom);
        }
        StatsdConfig config = mConfiguration.createConfig();

        //    event_metric {
        //      id: 1112
        //      what: 1234570
        //    }
        //    gauge_metric {
        //      id: 1111
        //      what: 1234567
        //      gauge_fields_filter {
        //        include_all: true
        //      }
        //      bucket: ONE_MINUTE
        //      sampling_type: FIRST_N_SAMPLES
        //      max_num_gauge_atoms_per_bucket: 100
        //      trigger_event: 1111111
        //    }
        //    atom_matcher {
        //      id: 1111111
        //      simple_atom_matcher {
        //        atom_id: 47
        //      }
        //    }
        //    atom_matcher {
        //      id: 1234567
        //      simple_atom_matcher {
        //        atom_id: 10022
        //      }
        //    }
        //    atom_matcher {
        //      id: 1234568
        //      simple_atom_matcher {
        //        atom_id: 244
        //      }
        //    }
        //    atom_matcher {
        //      id: 1234569
        //      simple_atom_matcher {
        //        atom_id: 245
        //      }
        //    }
        //    atom_matcher {
        //      id: 1234570
        //      combination {
        //        operation: OR
        //        matcher: 1234568
        //        matcher: 1234569
        //      }
        //    }

        assertEquals(1, config.getEventMetricCount());
        assertEquals(1, config.getGaugeMetricCount());

        final StatsdConfigProto.GaugeMetric gaugeMetric = config.getGaugeMetric(0);
        assertTrue(mConfiguration.isTrackedMetric(gaugeMetric.getId()));
        assertTrue(gaugeMetric.getGaugeFieldsFilter().getIncludeAll());

        StatsdConfigProto.EventMetric eventMetric = config.getEventMetric(0);
        assertTrue(mConfiguration.isTrackedMetric(eventMetric.getId()));

        final List<StatsdConfigProto.AtomMatcher> atomMatchers =
                new ArrayList<>(config.getAtomMatcherList());

        assertEquals(pulledAtom, findAndRemoveAtomMatcherById(atomMatchers, gaugeMetric.getWhat())
                .getSimpleAtomMatcher().getAtomId());
        assertEquals(AtomsProto.Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER,
                findAndRemoveAtomMatcherById(atomMatchers, gaugeMetric.getTriggerEvent())
                        .getSimpleAtomMatcher().getAtomId());

        StatsdConfigProto.AtomMatcher unionMatcher = findAndRemoveAtomMatcherById(atomMatchers,
                eventMetric.getWhat());
        assertNotNull(unionMatcher.getCombination());
        assertEquals(2, unionMatcher.getCombination().getMatcherCount());

        Integer[] actualAtoms = new Integer[]{
              findAndRemoveAtomMatcherById(atomMatchers,
                      unionMatcher.getCombination().getMatcher(0))
                      .getSimpleAtomMatcher().getAtomId(),
                findAndRemoveAtomMatcherById(atomMatchers,
                        unionMatcher.getCombination().getMatcher(1))
                        .getSimpleAtomMatcher().getAtomId()};
        Arrays.sort(actualAtoms);
        assertArrayEquals(pushedAtoms, actualAtoms);

        assertEquals(0, atomMatchers.size());
    }
}
