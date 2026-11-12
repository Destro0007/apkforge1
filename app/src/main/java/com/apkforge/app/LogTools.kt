package com.apkforge.app

object LogTools {
    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val stamp = Regex("""^﻿?\d{4}-\d{2}-\d{2}T[\d:.]+Z\s?""")
    private val runnerPath = Regex("""/home/runner/work/[^/\s]+/[^/\s]+/""")
    private val hit = Regex(
        """(^e: |\berror\b|FAILURE:|What went wrong|Execution failed|AAPT|Could not |Manifest merger|Duplicate class|Unresolved reference|cannot find symbol|FAILED)""",
        RegexOption.IGNORE_CASE
    )

    /** Shrinks a huge GitHub Actions log to the lines that explain why the build failed. */
    fun extractErrors(raw: String): String {
        val all = raw.lines().map { runnerPath.replace(stamp.replace(ansi.replace(it, ""), ""), "") }
        val start = all.indexOfFirst { it.contains("assembleDebug") }.coerceAtLeast(0)
        val lines = all.drop(start)

        val keep = java.util.TreeSet<Int>()
        for ((i, l) in lines.withIndex()) {
            if (hit.containsMatchIn(l)) {
                for (k in i..minOf(i + 2, lines.size - 1)) keep.add(k)
            }
        }
        val picked = if (keep.isEmpty()) lines.takeLast(60) else keep.map { lines[it] }
        val text = picked.filter { it.isNotBlank() }.take(90).joinToString("\n")
        return if (text.length > 7000) text.take(7000) else text
    }
}
