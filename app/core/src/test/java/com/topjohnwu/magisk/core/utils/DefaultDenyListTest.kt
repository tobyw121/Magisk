package com.topjohnwu.magisk.core.utils

import org.junit.Assert.*
import org.junit.Test

class DefaultDenyListTest {
    @Test fun includesUserAndPreinstalledAppsWithApplicationUid() {
        assertTrue(DefaultDenyList.eligible("com.example.user", true, false))
        assertTrue(DefaultDenyList.eligible("com.example.preinstalled", true, false))
    }
    @Test fun doesNotReapplyDefaultsAfterManualRemoval() {
        val seen = setOf("com.example.user")
        assertFalse(DefaultDenyList.shouldSeed("com.example.user", true, false, seen))
        assertTrue(DefaultDenyList.shouldSeed("com.example.newapp", true, false, seen))
    }
    @Test fun excludesSystemUidProcesses() {
        assertFalse(DefaultDenyList.eligible("android", false, false))
        assertFalse(DefaultDenyList.eligible("com.android.systemui", false, false))
    }
    @Test fun excludesOriginalHiddenAndSharedUidManagers() {
        assertFalse(DefaultDenyList.eligible("com.topjohnwu.magisk", true, false))
        assertFalse(DefaultDenyList.eligible("abcdefgh.ijklmnop", true, true))
        assertFalse(DefaultDenyList.eligible("com.example.shared", true, true))
    }
}
