package com.topjohnwu.magisk.core.utils

import com.topjohnwu.magisk.core.utils.DenyListStore.Target
import org.junit.Assert.*
import org.junit.Test

class DenyListStoreTest {
    private class Daemon(var entries: Set<Target> = emptySet()) {
        var reject: Target? = null
        var failRead = false
        val commands = mutableListOf<String>()
        fun run(command: String): DenyListStore.Reply {
            commands += command
            if (command == "magisk --denylist ls") {
                return if (failRead) DenyListStore.Reply(1, emptyList()) else
                    DenyListStore.Reply(0, entries.map { "${it.pkg}|${it.process}" })
            }
            val match = Regex("magisk --denylist (add|rm) '([^']+)' '([^']+)'").matchEntire(command)!!
            val (_, action, pkg, proc) = match.groupValues
            val target = Target(pkg, proc)
            if (target == reject) return DenyListStore.Reply(1, emptyList(), listOf("denied"))
            entries = if (action == "add") entries + target else entries - target
            return DenyListStore.Reply(0, emptyList())
        }
    }

    @Test fun legacyIsolatedServiceDoesNotCoverWholeApp() {
        val prefix = Target("isolated", "com.example:")
        val legacy = Target("isolated", "com.example:OldService")
        val daemon = Daemon(setOf(legacy))
        assertFalse(DenyListStore.covers(daemon.entries, prefix))
        val result = DenyListStore(daemon::run).change(setOf(prefix), true)
        assertTrue(result.successful)
        assertTrue(result.entries.contains(prefix))
        assertTrue(DenyListStore.covers(result.entries, legacy))
    }

    @Test fun partialWriteFailureReturnsActualEntries() {
        val main = Target("com.example", "com.example")
        val service = Target("com.example", "com.example:worker")
        val daemon = Daemon().apply { reject = service }
        val result = DenyListStore(daemon::run).change(setOf(main, service), true)
        assertFalse(result.successful)
        assertEquals(setOf(main), result.entries)
        assertFalse(result.diagnostics.isEmpty())
    }

    @Test fun removalIncludesStaleProcessesAndLegacyIsolatedEntries() {
        val main = Target("com.example", "com.example")
        val stale = Target("com.example", "com.example:removed")
        val isolated = Target("isolated", "com.example:old")
        val other = Target("isolated", "com.other:worker")
        val daemon = Daemon(setOf(main, stale, isolated, other))
        val result = DenyListStore(daemon::run).change(setOf(main, Target("isolated", "com.example:")), false, "com.example")
        assertTrue(result.successful)
        assertEquals(setOf(other), result.entries)
    }

    @Test fun cannotRemoveOtherAppsBroadIsolatedPrefix() {
        val broad = Target("isolated", "com.")
        val daemon = Daemon(setOf(broad))
        val result = DenyListStore(daemon::run).change(setOf(Target("isolated", "com.example:")), false)
        assertFalse(result.successful)
        assertEquals(setOf(broad), result.entries)
    }

    @Test fun failedReadPreventsWrites() {
        val daemon = Daemon().apply { failRead = true }
        assertThrows(IllegalStateException::class.java) {
            DenyListStore(daemon::run).change(setOf(Target("com.example", "com.example")), true)
        }
        assertEquals(listOf("magisk --denylist ls"), daemon.commands)
    }

    @Test fun malformedDaemonOutputIsNotTreatedAsEmptyList() {
        val store = DenyListStore { DenyListStore.Reply(0, listOf("unexpected output")) }
        assertThrows(IllegalStateException::class.java) { store.read() }
    }

    @Test fun quotesShellMetacharacters() {
        val target = Target("com.example", "proc'\$(id)")
        val commands = mutableListOf<String>()
        var listed = false
        val store = DenyListStore { command ->
            commands += command
            if (command == "magisk --denylist ls") {
                DenyListStore.Reply(0, if (listed) listOf("${target.pkg}|${target.process}") else emptyList())
            } else {
                listed = true
                DenyListStore.Reply(0, emptyList())
            }
        }
        assertTrue(store.change(setOf(target), true).successful)
        assertEquals("magisk --denylist add 'com.example' 'proc'\\''\$(id)'", commands[1])
    }
}
