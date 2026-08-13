package com.qpt.powermonitor.core.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootManager {
    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        Shell.getShell().isRoot
    }

    suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        val result = Shell.cmd("cat '${path.escapeForShell()}'").exec()
        result.out.joinToString("\n").takeIf { result.isSuccess }
    }

    suspend fun writeFile(path: String, value: String): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("printf '%s' '${value.escapeForShell()}' > '${path.escapeForShell()}'").exec().isSuccess
    }

    suspend fun execute(command: String): List<String> = withContext(Dispatchers.IO) {
        Shell.cmd(command).exec().out
    }

    private fun String.escapeForShell(): String = replace("'", "'\\''")
}
