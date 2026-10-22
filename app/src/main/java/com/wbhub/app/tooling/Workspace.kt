package com.wbhub.app.tooling

import android.content.Context
import com.wbhub.app.storage.AppStorage
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The on-disk layout tools work in.
 *
 * The root sits in the app's external files directory rather than shared
 * storage: decoding writes tens of thousands of small files, and that must not
 * depend on the all-files permission the shell-based agent asks for. Reads may
 * still point anywhere so a sample can be imported from Downloads; writes are
 * refused outside the workspace.
 */
class Workspace(context: Context) {

    class ProjectDirs(val id: String, val dir: File) {
        val original: File get() = File(dir, "original")
        val extract: File get() = File(dir, "extract")
        val build: File get() = File(dir, "build")
        val out: File get() = File(dir, "out")
        val reports: File get() = File(dir, "reports")
        val logs: File get() = File(dir, "logs")

        fun ensure(): ProjectDirs {
            listOf(dir, original, extract, build, out, reports, logs).forEach { it.mkdirs() }
            return this
        }
    }

    val root: File = AppStorage.workspace(context)

    init {
        File(root, "projects").mkdirs()
        File(root, "logs").mkdirs()
    }

    fun newProjectId(): String = "p" + UUID.randomUUID().toString().replace("-", "").take(12)

    fun project(id: String): ProjectDirs = ProjectDirs(id, File(root, "projects/$id")).ensure()

    /** Removes a project's decoded tree, builds and artifacts. */
    fun deleteProject(id: String) {
        runCatching { File(root, "projects/$id").deleteRecursively() }
    }

    /** A relative path is rooted at the workspace; an absolute one is honoured. */
    fun resolve(path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(root, path)
    }

    /** Path as the model and the database see it: relative to the workspace when possible. */
    fun display(file: File): String {
        val canonicalRoot = root.canonicalFile
        val canonical = file.canonicalFile
        return if (canonical.path.startsWith(canonicalRoot.path + File.separator)) {
            canonical.relativeTo(canonicalRoot).path
        } else {
            canonical.path
        }
    }

    /** Refuses writes that would land outside the workspace or follow a symlink out of it. */
    fun requireWritable(file: File): File {
        val canonicalRoot = root.canonicalFile
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(canonicalRoot.path + File.separator) && canonical != canonicalRoot) {
            throw IllegalArgumentException("refusing to write outside the workspace: ${file.path}")
        }
        return canonical
    }

    fun logFile(operationId: Long): File = File(root, "logs/op-$operationId.log")

    fun staging(): File = File(root, "staging").apply { mkdirs() }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
