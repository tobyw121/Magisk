package com.topjohnwu.magisk.core.utils

/** DenyList configuration, verified by reading the daemon after each batch. */
class DenyListStore(private val execute: (String) -> Reply) {
    data class Reply(val code: Int, val lines: List<String>, val errors: List<String> = emptyList())
    data class Target(val pkg: String, val process: String)
    data class Change(val entries: Set<Target>, val successful: Boolean, val diagnostics: List<String>)

    companion object {
        val operations = kotlinx.coroutines.sync.Mutex()
        fun covers(entries: Set<Target>, target: Target): Boolean = entries.any {
            it.pkg == target.pkg && if (target.pkg == "isolated") {
                target.process.startsWith(it.process)
            } else it.process == target.process
        }

        private fun quote(s: String): String {
            require(s.isNotEmpty() && '\u0000' !in s && '\n' !in s && '\r' !in s)
            return "'" + s.replace("'", "'\\''") + "'"
        }
    }

    fun read(): Set<Target> {
        val reply = execute("magisk --denylist ls")
        check(reply.code == 0) { "Cannot read DenyList: ${reply.errors.takeLast(5)}" }
        return reply.lines.filter { it.isNotEmpty() }.mapTo(linkedSetOf()) {
            val fields = it.split('|')
            check(fields.size == 2 && fields.all(String::isNotEmpty)) { "Invalid DenyList response" }
            Target(fields[0], fields[1])
        }
    }

    fun change(targets: Set<Target>, enabled: Boolean, removePackage: String? = null): Change {
        val before = read()
        val writes = if (enabled) {
            targets.filterNot { covers(before, it) }
        } else {
            before.filter { entry ->
                entry.pkg == removePackage || targets.any { target ->
                    entry.pkg == target.pkg && if (target.pkg == "isolated") {
                        entry.process.startsWith(target.process)
                    } else entry.process == target.process
                }
            }
        }
        val diagnostics = mutableListOf<String>()
        val action = if (enabled) "add" else "rm"
        for (entry in writes) {
            val command = "magisk --denylist $action ${quote(entry.pkg)} ${quote(entry.process)}"
            val result = execute(command)
            if (result.code != 0) diagnostics += "$command: ${(result.errors + result.lines).takeLast(5)}"
        }
        val after = read()
        val successful = if (enabled) targets.all { covers(after, it) } else {
            targets.none { covers(after, it) } &&
                after.none { entry -> entry.pkg == removePackage || targets.any {
                    it.pkg == "isolated" && entry.pkg == it.pkg && entry.process.startsWith(it.process)
                } }
        }
        return Change(after, successful, diagnostics)
    }
}
