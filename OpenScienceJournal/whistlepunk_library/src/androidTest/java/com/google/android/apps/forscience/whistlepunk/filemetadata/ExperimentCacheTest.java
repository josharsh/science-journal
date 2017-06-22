/*
 *  Copyright 2017 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.android.apps.forscience.whistlepunk.filemetadata;

import android.test.InstrumentationTestCase;

import com.google.android.apps.forscience.whistlepunk.metadata.GoosciExperiment;
import com.google.android.apps.forscience.whistlepunk.metadata.GoosciUserMetadata;
import com.google.protobuf.nano.MessageNano;

import java.io.File;

/**
 * Tests for the ExperimentCache class.
 * Note: All experiments created should start with prefix "exp_" so that they can be cleaned up
 * automatically.
 */
public class ExperimentCacheTest extends InstrumentationTestCase {
    private int mFailureCount = 0;

    private ExperimentCache.FailureListener getFailureFailsListener() {
        return new ExperimentCache.FailureListener() {
            @Override
            public void onWriteFailed(Experiment experimentToWrite) {
                throw new RuntimeException("Expected success");
            }

            @Override
            public void onReadFailed(GoosciUserMetadata.ExperimentOverview experimentOverview) {
                throw new RuntimeException("Expected success");
            }

            @Override
            public void onNewerVersionDetected(
                    GoosciUserMetadata.ExperimentOverview experimentOverview) {
                throw new RuntimeException("Expected success");
            }
        };
    }

    private ExperimentCache.FailureListener getFailureExpectedListener() {
        return new ExperimentCache.FailureListener() {
            @Override
            public void onWriteFailed(Experiment experimentToWrite) {
                mFailureCount++;
            }

            @Override
            public void onReadFailed(
                    GoosciUserMetadata.ExperimentOverview localExperimentOverview) {
                mFailureCount++;
            }

            @Override
            public void onNewerVersionDetected(
                    GoosciUserMetadata.ExperimentOverview experimentOverview) {
                mFailureCount++;
            }
        };
    }

    public void setUp() {
        cleanUp();
    }

    public void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        File rootDirectory = getInstrumentation().getContext().getFilesDir();
        for (File file : rootDirectory.listFiles()) {
            if (file.getName().startsWith("exp_")) {
                ExperimentCache.deleteRecursive(file);
            }
        }
        mFailureCount = 0;
    }

    public void testExperimentWriteRead() {
        Experiment experiment = Experiment.newExperiment(10, "exp_localId", 0);
        ExperimentCache cache = new ExperimentCache(getInstrumentation().getContext(),
                getFailureFailsListener(), 0);
        cache.createNewExperiment(experiment);
        cache.writeActiveExperimentFile();

        // Was it set correctly in the ExperimentCache?
        assertTrue(MessageNano.messageNanoEquals(
                cache.getActiveExperimentForTests().getExperimentProto(),
                experiment.getExperimentProto()));

        // Force a load, make sure that's equal too.
        cache.loadActiveExperimentFromFile(experiment.getExperimentOverview());
        assertTrue(MessageNano.messageNanoEquals(
                cache.getActiveExperimentForTests().getExperimentProto(),
                experiment.getExperimentProto()));

        // Clean up.
        cache.deleteExperiment("exp_localId");
        assertNull(cache.getActiveExperimentForTests());
    }

    public void testExperimentWithChanges() {
        Experiment experiment = Experiment.newExperiment(10, "exp_localId", 0);
        ExperimentCache cache = new ExperimentCache(getInstrumentation().getContext(),
                getFailureFailsListener(), 0);
        cache.createNewExperiment(experiment);
        assertTrue(cache.needsWrite());

        cache.writeActiveExperimentFile();
        assertFalse(cache.needsWrite());

        experiment.setTitle("Title");
        cache.updateExperiment(experiment);
        assertTrue(cache.needsWrite());
        cache.writeActiveExperimentFile();

        // Force a load, make sure that's got the new title.
        cache.loadActiveExperimentFromFile(experiment.getExperimentOverview());
        assertEquals("Title", cache.getActiveExperimentForTests().getTitle());

        // Clean up.
        cache.deleteExperiment("exp_localId");
        assertNull(cache.getActiveExperimentForTests());
    }

    public void testChangingExperimentWritesOldOne() {
        ExperimentCache cache = new ExperimentCache(getInstrumentation().getContext(),
                getFailureFailsListener(), 0);

        Experiment experiment = Experiment.newExperiment(10, "exp_localId", 0);
        cache.createNewExperiment(experiment);
        assertEquals(cache.getActiveExperimentForTests().getCreationTimeMs(), 10);
        experiment.setTitle("Title");
        cache.updateExperiment(experiment);

        Experiment second = Experiment.newExperiment(20, "exp_secondId", 0);
        cache.createNewExperiment(second);
        assertEquals(cache.getActiveExperimentForTests().getCreationTimeMs(), 20);

        cache.getExperiment(experiment.getExperimentOverview());
        assertEquals(cache.getActiveExperimentForTests().getCreationTimeMs(), 10);
        assertEquals(cache.getActiveExperimentForTests().getTitle(), "Title");
    }

    public void testUpgradeStartsWriteTimer() {
        ExperimentCache cache = new ExperimentCache(getInstrumentation().getContext(),
                getFailureFailsListener(), 0);
        GoosciExperiment.Experiment proto = new GoosciExperiment.Experiment();
        proto.version = 0;
        proto.minorVersion = 0;
        cache.upgradeExperimentVersionIfNeeded(proto, new GoosciUserMetadata.ExperimentOverview(),
                1, 1);
        assertEquals(1, proto.version);
        assertEquals(1, proto.minorVersion);
        assertTrue(cache.needsWrite());
    }

    public void testNoUpgradeDoesNotStartWriteTimer() {
        ExperimentCache cache = new ExperimentCache(getInstrumentation().getContext(),
                getFailureFailsListener(), 0);
        GoosciExperiment.Experiment proto = new GoosciExperiment.Experiment();
        proto.version = 1;
        proto.minorVersion = 1;
        cache.upgradeExperimentVersionIfNeeded(proto, new GoosciUserMetadata.ExperimentOverview(),
                1, 1);
        assertEquals(1, proto.version);
        assertEquals(1, proto.minorVersion);
        assertFalse(cache.needsWrite());
    }

    public void testVersionTooNewThrowsError() {
        ExperimentCache cache = new ExperimentCache(getInstrumentation().getContext(),
                getFailureExpectedListener());

        GoosciExperiment.Experiment proto = new GoosciExperiment.Experiment();
        proto.version = ExperimentCache.VERSION + 1;
        proto.minorVersion = ExperimentCache.MINOR_VERSION;
        cache.upgradeExperimentVersionIfNeeded(proto, new GoosciUserMetadata.ExperimentOverview(),
                ExperimentCache.VERSION, ExperimentCache.MINOR_VERSION);
        assertEquals(1, mFailureCount);
    }

    public void testOnlyUpgradesMinorVersion() {
        ExperimentCache cache = new ExperimentCache(getInstrumentation().getContext(),
                getFailureFailsListener(), 0);
        GoosciExperiment.Experiment proto = new GoosciExperiment.Experiment();
        proto.version = 1;
        proto.minorVersion = 0;
        cache.upgradeExperimentVersionIfNeeded(proto, new GoosciUserMetadata.ExperimentOverview(),
                1, 1);
        assertEquals(proto.version, 1);
        assertEquals(proto.minorVersion, 1);
    }

    public void testCantWriteNewerVersion() {
        ExperimentCache cache = new ExperimentCache(getInstrumentation().getContext(),
                getFailureExpectedListener());
        GoosciExperiment.Experiment proto = new GoosciExperiment.Experiment();
        proto.version = ExperimentCache.VERSION;
        proto.minorVersion = ExperimentCache.MINOR_VERSION + 1;
        GoosciUserMetadata.ExperimentOverview overview =
                new GoosciUserMetadata.ExperimentOverview();
        cache.upgradeExperimentVersionIfNeeded(proto, overview,
                ExperimentCache.VERSION, ExperimentCache.MINOR_VERSION);
        // Version should be unchanged -- don't upgrade minor version.
        assertEquals(proto.version, ExperimentCache.VERSION);
        assertEquals(proto.minorVersion, ExperimentCache.MINOR_VERSION + 1);

        // But no errors yet -- didn't try to save it.
        assertEquals(0, mFailureCount);

        Experiment experiment = Experiment.fromExperiment(proto, overview);
        cache.updateExperiment(experiment); // Set this one to active so we can try to write it.
        cache.writeActiveExperimentFile();
        assertEquals(1, mFailureCount);
    }
}