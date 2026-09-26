package com.freeturn.app.domain.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterVersionTest {
    @Test fun comparesBetaPatchNumbers() {
        assertTrue(AppUpdater.isNewer("3.7.1-beta", "3.7.0-beta"))
        assertTrue(AppUpdater.isNewer("3.7.2-beta", "3.7.1-beta"))
        assertTrue(AppUpdater.isNewer("3.7.2", "3.7.1-beta"))
        assertFalse(AppUpdater.isNewer("3.7.0-beta", "3.7.1-beta"))
    }

    @Test fun stableReleaseSupersedesBetaAtSameNumber() {
        assertTrue(AppUpdater.isNewer("3.7.1", "3.7.1-beta"))
        assertFalse(AppUpdater.isNewer("3.7.1-beta", "3.7.1"))
        assertFalse(AppUpdater.isNewer("3.7.1-beta", "3.7.1-beta"))
    }
}
