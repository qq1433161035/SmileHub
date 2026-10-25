package com.wbhub.app.tooling

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wbhub.app.storage.AppStorage
import java.io.File

/**
 * Project, artifact and operation bookkeeping.
 *
 * Plain SQLite rather than Room: the project already has no annotation
 * processing, and the schema is small enough that hand-written SQL stays
 * readable.
 */
class ToolingDb(context: Context) : SQLiteOpenHelper(
    context,
    File(AppStorage.database(context), NAME).absolutePath,
    null,
    VERSION,
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 主进程（工程页）与 :tools 会同时读写同一个库：WAL + busy_timeout
        // 让并发写退化成短暂等待，而不是直接 SQLITE_BUSY。
        runCatching { db.enableWriteAheadLogging() }
        runCatching { db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() } }
    }

    data class ProjectRow(
        val id: String,
        val name: String,
        val packageName: String?,
        val versionName: String?,
        val status: String,
        val workspace: String,
        val sourceArtifact: String?,
        val createdAt: Long,
        val updatedAt: Long,
    )

    data class ArtifactRow(
        val id: String,
        val projectId: String?,
        val kind: String,
        val path: String,
        val size: Long,
        val sha256: String,
        val origin: String,
        val createdAt: Long,
    )

    data class OperationRow(
        val id: Long,
        val tool: String,
        val status: String,
        val summary: String,
        val startedAt: Long,
        val outputRef: String?,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE projects (
                id              TEXT PRIMARY KEY,
                name            TEXT NOT NULL,
                package_name    TEXT,
                version_name    TEXT,
                version_code    INTEGER,
                status          TEXT NOT NULL DEFAULT 'NEW',
                source_artifact TEXT,
                workspace       TEXT NOT NULL,
                agent_session   TEXT,
                meta_json       TEXT NOT NULL DEFAULT '{}',
                created_at      INTEGER NOT NULL,
                updated_at      INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_projects_updated ON projects(updated_at DESC)")
        db.execSQL("CREATE INDEX idx_projects_pkg ON projects(package_name)")

        db.execSQL(
            """
            CREATE TABLE artifacts (
                id          TEXT PRIMARY KEY,
                project_id  TEXT REFERENCES projects(id) ON DELETE CASCADE,
                kind        TEXT NOT NULL,
                path        TEXT NOT NULL,
                size        INTEGER NOT NULL,
                sha256      TEXT NOT NULL,
                parent_id   TEXT,
                origin      TEXT NOT NULL,
                arch        TEXT,
                meta_json   TEXT NOT NULL DEFAULT '{}',
                created_at  INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_artifacts_project ON artifacts(project_id, kind)")
        db.execSQL("CREATE INDEX idx_artifacts_sha ON artifacts(sha256)")

        db.execSQL(
            """
            CREATE TABLE operations (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id  TEXT,
                session_id  TEXT,
                tool        TEXT NOT NULL,
                args_json   TEXT NOT NULL,
                risk        TEXT NOT NULL,
                approval    TEXT NOT NULL,
                status      TEXT NOT NULL,
                exit_code   INTEGER,
                summary     TEXT NOT NULL DEFAULT '',
                output_ref  TEXT,
                started_at  INTEGER NOT NULL,
                ended_at    INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_operations_project ON operations(project_id, started_at DESC)")

        db.execSQL(
            """
            CREATE TABLE build_diagnostics (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                operation_id INTEGER NOT NULL,
                stage        TEXT NOT NULL,
                severity     TEXT NOT NULL,
                file         TEXT,
                line         INTEGER,
                message      TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_build_diag_op ON build_diagnostics(operation_id, stage)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insertProject(
        id: String,
        name: String,
        packageName: String?,
        versionName: String?,
        sourceArtifact: String?,
        workspace: String,
        metaJson: String,
    ) {
        val now = System.currentTimeMillis()
        writableDatabase.insertOrThrow("projects", null, ContentValues().apply {
            put("id", id)
            put("name", name)
            put("package_name", packageName)
            put("version_name", versionName)
            put("status", "NEW")
            put("source_artifact", sourceArtifact)
            put("workspace", workspace)
            put("meta_json", metaJson)
            put("created_at", now)
            put("updated_at", now)
        })
    }

    fun updateProjectStatus(id: String, status: String) {
        writableDatabase.update(
            "projects",
            ContentValues().apply {
                put("status", status)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun project(id: String): ProjectRow? = readableDatabase.rawQuery(
        "SELECT id, name, package_name, version_name, status, workspace, source_artifact, created_at, updated_at " +
            "FROM projects WHERE id = ?",
        arrayOf(id),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toProject() else null }

    fun projects(limit: Int = 50): List<ProjectRow> = readableDatabase.rawQuery(
        "SELECT id, name, package_name, version_name, status, workspace, source_artifact, created_at, updated_at " +
            "FROM projects ORDER BY updated_at DESC LIMIT ?",
        arrayOf(limit.toString()),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toProject()) }
    }

    private fun android.database.Cursor.toProject() = ProjectRow(
        id = getString(0),
        name = getString(1),
        packageName = getString(2),
        versionName = getString(3),
        status = getString(4),
        workspace = getString(5),
        sourceArtifact = getString(6),
        createdAt = getLong(7),
        updatedAt = getLong(8),
    )

    /** Removes a project and everything that points at it. */
    fun deleteProject(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                "build_diagnostics",
                "operation_id IN (SELECT id FROM operations WHERE project_id = ?)",
                arrayOf(id),
            )
            db.delete("artifacts", "project_id = ?", arrayOf(id))
            db.delete("operations", "project_id = ?", arrayOf(id))
            db.delete("projects", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertArtifact(
        id: String,
        projectId: String?,
        kind: String,
        path: String,
        size: Long,
        sha256: String,
        origin: String,
        parentId: String? = null,
    ) {
        writableDatabase.insertOrThrow("artifacts", null, ContentValues().apply {
            put("id", id)
            put("project_id", projectId)
            put("kind", kind)
            put("path", path)
            put("size", size)
            put("sha256", sha256)
            put("parent_id", parentId)
            put("origin", origin)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun artifacts(projectId: String): List<ArtifactRow> = readableDatabase.rawQuery(
        "SELECT id, project_id, kind, path, size, sha256, origin, created_at " +
            "FROM artifacts WHERE project_id = ? ORDER BY created_at",
        arrayOf(projectId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ArtifactRow(
                        id = cursor.getString(0),
                        projectId = cursor.getString(1),
                        kind = cursor.getString(2),
                        path = cursor.getString(3),
                        size = cursor.getLong(4),
                        sha256 = cursor.getString(5),
                        origin = cursor.getString(6),
                        createdAt = cursor.getLong(7),
                    ),
                )
            }
        }
    }

    /** Recent operations for one project, newest first. */
    fun operations(projectId: String, limit: Int = 50): List<OperationRow> = readableDatabase.rawQuery(
        "SELECT id, tool, status, summary, started_at, output_ref FROM operations " +
            "WHERE project_id = ? ORDER BY started_at DESC LIMIT ?",
        arrayOf(projectId, limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    OperationRow(
                        id = cursor.getLong(0),
                        tool = cursor.getString(1),
                        status = cursor.getString(2),
                        summary = cursor.getString(3),
                        startedAt = cursor.getLong(4),
                        outputRef = cursor.getString(5),
                    ),
                )
            }
        }
    }

    fun startOperation(
        projectId: String?,
        sessionId: String?,
        tool: String,
        argsJson: String,
        risk: String,
        approval: String,
    ): Long = writableDatabase.insertOrThrow("operations", null, ContentValues().apply {
        put("project_id", projectId)
        put("session_id", sessionId)
        put("tool", tool)
        put("args_json", argsJson)
        put("risk", risk)
        put("approval", approval)
        put("status", "RUNNING")
        put("started_at", System.currentTimeMillis())
    })

    fun finishOperation(id: Long, status: String, exitCode: Int?, summary: String, outputRef: String?) {
        writableDatabase.update(
            "operations",
            ContentValues().apply {
                put("status", status)
                put("exit_code", exitCode)
                put("summary", summary)
                put("output_ref", outputRef)
                put("ended_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun insertDiagnostics(operationId: Long, items: List<com.wbhub.app.tools.ToolDiagnostic>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item ->
                db.insertOrThrow("build_diagnostics", null, ContentValues().apply {
                    put("operation_id", operationId)
                    put("stage", item.stage)
                    put("severity", item.severity)
                    put("file", item.file)
                    put("line", item.line)
                    put("message", item.message)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val NAME = "tooling.db"
        const val VERSION = 1
    }
}
