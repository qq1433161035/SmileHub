package com.wbhub.app.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.wbhub.app.storage.AppStorage
import com.wbhub.app.tooling.ToolingDb
import com.wbhub.app.tooling.Workspace
import com.wbhub.app.tools.adb.AdbSession
import com.wbhub.app.tools.engines.ApkEngine
import com.wbhub.app.tools.engines.SigningKeys
import com.wbhub.app.tools.engines.Toolchain
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The APK and project tools offered to the model.
 *
 * Each entry maps a tool call onto a library-backed engine call and records
 * whatever file comes out as an artifact, so a later build can point at it.
 */
class ToolingTools(
    private val context: Context,
    private val workspace: Workspace,
    private val db: ToolingDb,
    private val apkEngine: ApkEngine,
    private val keys: SigningKeys,
    private val toolchain: Toolchain,
) {

    fun register(registry: ToolRegistry) {
        registry.register(
            ToolSpec(
                name = "toolchain_status",
                description = "Report whether the bundled aapt2/zipalign binaries are present and usable.",
                risk = ToolRisk.READ,
            ),
        ) {
            ToolResult(
                ok = true,
                summary = toolchain.describe(),
                text = buildString {
                    appendLine("engine: ${apkEngine.version() ?: "unavailable"}")
                    append("workspace: ${workspace.root.absolutePath}")
                },
            )
        }

        registry.register(
            ToolSpec(
                name = "project_import",
                description = "Import an APK into a new project workspace and read its manifest. " +
                    "Returns the project id and the metadata used by later steps.",
                risk = ToolRisk.WRITE,
                properties = mapOf(
                    "path" to stringProp("APK path on the device, absolute or relative to the workspace."),
                    "name" to stringProp("Optional project name; defaults to the package name."),
                ),
                required = listOf("path"),
            ),
            ::importProject,
        )

        registry.register(
            ToolSpec(
                name = "project_list",
                description = "List imported projects, newest first.",
                risk = ToolRisk.READ,
                properties = mapOf("limit" to intProp("How many projects to list. Defaults to 20.")),
            ),
        ) { args ->
            val limit = (args.int("limit") ?: 20).coerceIn(1, 100)
            val rows = db.projects(limit)
            if (rows.isEmpty()) {
                ToolResult(true, "还没有项目", "先用 project.import 导入一个 APK。")
            } else {
                val text = rows.joinToString("\n") { row ->
                    "${row.id}  ${row.status}  ${row.packageName.orEmpty()}  ${row.versionName.orEmpty()}  ${row.name}"
                }
                ToolResult(true, "共 ${rows.size} 个项目", text)
            }
        }

        registry.register(
            ToolSpec(
                name = "apk_info",
                description = "Read an APK's package name, versions, permissions, ABIs, dex/so listing and signing certificates.",
                risk = ToolRisk.READ,
                properties = mapOf(
                    "path" to stringProp("APK path, absolute or relative to the workspace."),
                    "raw" to boolProp("Return the full JSON instead of the short summary."),
                ),
                required = listOf("path"),
            ),
            ::apkInfo,
        )

        registry.register(
            ToolSpec(
                name = "apk_decode",
                description = "Decode an APK with apktool into a working directory (smali sources and, " +
                    "when resources=true, the full res/ tree). Editing happens in that directory.",
                risk = ToolRisk.WRITE,
                properties = mapOf(
                    "path" to stringProp("APK path."),
                    "project_id" to stringProp("Optional project id; the decode lands in its extract/ directory."),
                    "dest" to stringProp("Optional explicit output directory inside the workspace."),
                    "sources" to boolProp("Decode classes*.dex into smali. Defaults to true."),
                    "resources" to boolProp("Decode resources.arsc and res/ into editable XML. Defaults to true."),
                ),
                required = listOf("path"),
                timeoutSeconds = 600,
            ),
            ::decode,
        )

        registry.register(
            ToolSpec(
                name = "apk_build",
                description = "Rebuild an APK from an apktool-decoded directory. Resources are compiled " +
                    "with the bundled aapt2, smali is assembled into dex.",
                risk = ToolRisk.WRITE,
                properties = mapOf(
                    "dir" to stringProp("Decoded directory produced by apk.decode."),
                    "out" to stringProp("Optional output APK path inside the workspace."),
                    "project_id" to stringProp("Optional project id, used to place the output and record the artifact."),
                ),
                required = listOf("dir"),
                timeoutSeconds = 600,
            ),
            ::build,
        )

        registry.register(
            ToolSpec(
                name = "apk_align",
                description = "Align an APK with zipalign. page_size 4096 (default) or 16384 for 16 KB pages.",
                risk = ToolRisk.WRITE,
                properties = mapOf(
                    "path" to stringProp("APK path."),
                    "out" to stringProp("Optional output path."),
                    "page_size" to intProp("4096 or 16384. Defaults to 4096."),
                ),
                required = listOf("path"),
            ),
            ::align,
        )

        registry.register(
            ToolSpec(
                name = "apk_sign",
                description = "Sign an APK with the project's signing key (created on first use). " +
                    "Defaults to v2+v3.",
                risk = ToolRisk.SENSITIVE,
                properties = mapOf(
                    "path" to stringProp("APK path."),
                    "out" to stringProp("Optional output path."),
                    "keystore_id" to stringProp("Key id. Defaults to \"default\"."),
                    "v1" to boolProp("Enable the v1 (JAR) scheme. Defaults to false."),
                    "v2" to boolProp("Enable the v2 scheme. Defaults to true."),
                    "v3" to boolProp("Enable the v3 scheme. Defaults to true."),
                    "project_id" to stringProp("Optional project id, used to record the artifact."),
                ),
                required = listOf("path"),
                timeoutSeconds = 300,
            ),
            ::sign,
        )

        registry.register(
            ToolSpec(
                name = "apk_verify",
                description = "Verify an APK's v1/v2/v3 signatures and report certificate digests.",
                risk = ToolRisk.READ,
                properties = mapOf("path" to stringProp("APK path.")),
                required = listOf("path"),
            ),
            ::verify,
        )

        registry.register(
            ToolSpec(
                name = "package_info",
                description = "Query an installed package through PackageManager: version, UID, APK path " +
                    "and signing certificate digests. Use this instead of shell `pm`, which is denied " +
                    "for an app UID and misleadingly looks like the package is not installed.",
                risk = ToolRisk.READ,
                properties = mapOf("package" to stringProp("Package name, e.g. com.example.app.")),
                required = listOf("package"),
            ),
            ::packageInfo,
        )

        registry.register(
            ToolSpec(
                name = "app_list",
                description = "List installed apps with label, package, version, UID and system/user flag. " +
                    "Use filter to search by package or label.",
                risk = ToolRisk.READ,
                properties = mapOf(
                    "third_party" to boolProp("Only user-installed apps. Defaults to true."),
                    "filter" to stringProp("Optional keyword matched against package name and label."),
                    "limit" to intProp("Maximum rows. Defaults to 100."),
                ),
            ),
            ::appList,
        )

        registry.register(
            ToolSpec(
                name = "app_permissions",
                description = "List a package's declared permissions and whether each one is currently granted.",
                risk = ToolRisk.READ,
                properties = mapOf("package" to stringProp("Package name.")),
                required = listOf("package"),
            ),
            ::appPermissions,
        )

        registry.register(
            ToolSpec(
                name = "app_launch",
                description = "Launch an installed app by package, or one specific activity. Does not need root.",
                risk = ToolRisk.EXEC,
                properties = mapOf(
                    "package" to stringProp("Package name."),
                    "activity" to stringProp("Optional activity: .MainActivity or a full class name."),
                ),
                required = listOf("package"),
            ),
            ::appLaunch,
        )

        registry.register(
            ToolSpec(
                name = "app_stop",
                description = "Force-stop a package with root (am force-stop).",
                risk = ToolRisk.SENSITIVE,
                properties = mapOf("package" to stringProp("Package name.")),
                required = listOf("package"),
            ),
            ::appStop,
        )

        registry.register(
            ToolSpec(
                name = "app_grant",
                description = "Grant or revoke a runtime permission for another package with root " +
                    "(pm grant / pm revoke).",
                risk = ToolRisk.SENSITIVE,
                properties = mapOf(
                    "package" to stringProp("Package name."),
                    "permission" to stringProp("Permission name, e.g. android.permission.CAMERA."),
                    "grant" to boolProp("true grants, false revokes. Defaults to true."),
                ),
                required = listOf("package", "permission"),
            ),
            ::appGrant,
        )

        registry.register(
            ToolSpec(
                name = "app_debug",
                description = "Mark a package as the debug app with root (am set-debug-app) or clear it. " +
                    "The target must be debuggable; a rebuilt APK can be made debuggable in its manifest.",
                risk = ToolRisk.SENSITIVE,
                properties = mapOf(
                    "package" to stringProp("Package name."),
                    "clear" to boolProp("Clear the debug-app setting instead. Defaults to false."),
                    "wait" to boolProp("Wait for a debugger before the app runs. Defaults to true."),
                    "persistent" to boolProp("Keep the setting across reboots. Defaults to false."),
                ),
                required = listOf("package"),
            ),
            ::appDebug,
        )

        registry.register(
            ToolSpec(
                name = "logcat",
                description = "Read or clear logcat with root. Filter by package (PID), tag and level. " +
                    "Without root the log device is not readable.",
                risk = ToolRisk.READ,
                properties = mapOf(
                    "package" to stringProp("Optional package name; its PID is used as a filter."),
                    "lines" to intProp("Number of trailing lines. Defaults to 200."),
                    "level" to stringProp("V/D/I/W/E/F. Defaults to V."),
                    "tag" to stringProp("Optional log tag to filter."),
                    "clear" to boolProp("Clear the buffer first. Defaults to false."),
                ),
            ),
            ::logcat,
        )

        registry.register(
            ToolSpec(
                name = "adb_status",
                description = "Show whether the on-device ADB client is connected and which host:port it uses.",
                risk = ToolRisk.READ,
            ),
            ::adbStatus,
        )

        registry.register(
            ToolSpec(
                name = "adb_connect",
                description = "Connect to an adbd TCP endpoint from inside the phone (default 127.0.0.1:5555). " +
                    "Uses shell identity, so pm/am/logcat/dumpsys work without root. The phone must have " +
                    "wireless debugging enabled (use its port) or adbd in tcpip mode; a system dialog asks " +
                    "for authorization on the first connect.",
                risk = ToolRisk.EXEC,
                properties = mapOf(
                    "host" to stringProp("adbd host. Defaults to the last used value, then 127.0.0.1."),
                    "port" to intProp("adbd port. Defaults to the last used value, then 5555."),
                ),
                timeoutSeconds = 60,
            ),
            ::adbConnect,
        )

        registry.register(
            ToolSpec(
                name = "adb_shell",
                description = "Run a shell command over ADB on the device or another device reachable by adbd. " +
                    "Connects automatically with the saved endpoint.",
                risk = ToolRisk.EXEC,
                properties = mapOf(
                    "command" to stringProp("Shell command, e.g. pm list packages -3."),
                    "host" to stringProp("Optional adbd host override."),
                    "port" to intProp("Optional adbd port override."),
                    "timeout_seconds" to intProp("Timeout in seconds. Defaults to 60."),
                ),
                required = listOf("command"),
                timeoutSeconds = 120,
                maxOutputBytes = 120_000,
            ),
            ::adbShell,
        )

        registry.register(
            ToolSpec(
                name = "apk_install",
                description = "Install an APK with root (pm install -r). Compares the APK's certificate " +
                    "with the installed package first: a rebuilt APK signed with a different key cannot " +
                    "overwrite the original, so replace=true uninstalls it first (its data is lost). " +
                    "downgrade=true allows a lower versionCode.",
                risk = ToolRisk.SENSITIVE,
                properties = mapOf(
                    "path" to stringProp("APK path."),
                    "replace" to boolProp("Uninstall the installed package when signatures differ. Defaults to false."),
                    "downgrade" to boolProp("Allow a lower versionCode. Defaults to false."),
                ),
                required = listOf("path"),
                timeoutSeconds = 180,
            ),
            ::install,
        )

        registry.register(
            ToolSpec(
                name = "apk_uninstall",
                description = "Uninstall a package by name with root. Removes its data as well.",
                risk = ToolRisk.SENSITIVE,
                properties = mapOf("package" to stringProp("Package name, e.g. com.example.app.")),
                required = listOf("package"),
                timeoutSeconds = 60,
            ),
            ::uninstall,
        )

        registry.register(
            ToolSpec(
                name = "dex_list",
                description = "List classes and their methods in a .dex file or every classes*.dex of an APK. " +
                    "Use it to locate the class to decompile.",
                risk = ToolRisk.READ,
                properties = mapOf(
                    "path" to stringProp("A .dex file or an APK."),
                    "limit" to intProp("Maximum number of classes. Defaults to 100."),
                ),
                required = listOf("path"),
                timeoutSeconds = 180,
            ),
            ::dexList,
        )

        registry.register(
            ToolSpec(
                name = "dex_decompile",
                description = "Decompile a .dex or APK to Java source with jadx. Pass class_name to decompile " +
                    "a single class (faster, writes one .java); omit it to export every class.",
                risk = ToolRisk.WRITE,
                properties = mapOf(
                    "path" to stringProp("A .dex file or an APK."),
                    "class_name" to stringProp("Optional fully qualified class, e.g. com.foo.Bar."),
                    "out" to stringProp("Optional output directory inside the workspace."),
                ),
                required = listOf("path"),
                timeoutSeconds = 900,
            ),
            ::dexDecompile,
        )

        registry.register(
            ToolSpec(
                name = "so_info",
                description = "Inspect an ELF .so: architecture, loadable segments, sections, exported functions, " +
                    "imports and DT_NEEDED libraries.",
                risk = ToolRisk.READ,
                properties = mapOf("path" to stringProp(".so path.")),
                required = listOf("path"),
                timeoutSeconds = 120,
            ),
            ::soInfo,
        )

        registry.register(
            ToolSpec(
                name = "so_disassemble",
                description = "Disassemble an ELF .so with Capstone. Locate code by exported symbol name or by " +
                    "virtual address (offset) and return address, bytes, mnemonic and operands per line.",
                risk = ToolRisk.READ,
                properties = mapOf(
                    "path" to stringProp(".so path."),
                    "symbol" to stringProp("Optional exported symbol, e.g. Java_com_foo_Bar_baz."),
                    "offset" to stringProp("Optional virtual address in hex (0x...) when symbol is omitted."),
                    "count" to intProp("Instructions to disassemble. Defaults to 64, max 2000."),
                ),
                required = listOf("path"),
                timeoutSeconds = 180,
            ),
            ::soDisassemble,
        )
    }

    // ------------------------------------------------------------------ //
    // Implementations
    // ------------------------------------------------------------------ //

    private fun importProject(args: Map<String, Any?>): ToolResult {
        val source = workspace.resolve(args.str("path") ?: return ToolResult.fail("E_INPUT", "path is required"))
        if (!source.isFile) return ToolResult.fail("E_NOT_FOUND", "no such file: ${source.path}")

        val metadata = runCatching { apkEngine.info(source) }
            .getOrElse { return ToolResult.error("E_INPUT", "not a readable APK: ${it.message}") }
        val json = metadata.json

        val id = workspace.newProjectId()
        val dirs = workspace.project(id)
        val original = File(dirs.original, source.name)
        source.copyTo(original, overwrite = true)

        val artifactId = newArtifactId()
        val sha = workspace.sha256(original)
        db.insertArtifact(
            id = artifactId,
            projectId = id,
            kind = "APK",
            path = workspace.display(original),
            size = original.length(),
            sha256 = sha,
            origin = "IMPORT",
        )
        db.insertProject(
            id = id,
            name = args.str("name") ?: json.optString("package", source.nameWithoutExtension),
            packageName = json.optString("package"),
            versionName = json.optString("versionName"),
            sourceArtifact = artifactId,
            workspace = "projects/$id",
            metaJson = json.toString(),
        )

        return ToolResult(
            ok = true,
            summary = "已导入 ${json.optString("package")} → 项目 $id",
            text = buildString {
                append("project_id: $id\n")
                append("original: ${workspace.display(original)}\n")
                append("apk: ${json.toString(2)}")
            },
        )
    }

    private fun apkInfo(args: Map<String, Any?>): ToolResult {
        val file = workspace.resolve(args.str("path") ?: return ToolResult.fail("E_INPUT", "path is required"))
        if (!file.isFile) return ToolResult.fail("E_NOT_FOUND", "no such file: ${file.path}")
        val metadata = runCatching { apkEngine.info(file) }
            .getOrElse { return ToolResult.error("E_INPUT", "APK 解析失败：${it.message}") }
        val json = metadata.json
        val text = if (args.bool("raw") == true) {
            json.toString(2)
        } else {
            buildString {
                append("label: ${json.optString("label")}\n")
                append("package: ${json.optString("package")} ${json.optString("versionName")} (${json.optLong("versionCode")})\n")
                append("sdk: min ${json.optInt("minSdk")} / target ${json.optInt("targetSdk")}\n")
                append("abis: ${json.optJSONArray("abis")}\n")
                append("dex: ${json.optJSONArray("dexFiles")}\n")
                append("native libs: ${json.optJSONArray("nativeLibs")?.length() ?: 0}\n")
                append("permissions: ${json.optJSONArray("permissions")?.length() ?: 0}\n")
                append("signature: ${json.optJSONObject("signature")?.optJSONArray("certSha256")}")
            }
        }
        return ToolResult(true, "${json.optString("label").ifBlank { json.optString("package") }} ${json.optString("versionName")}", text)
    }

    private fun decode(args: Map<String, Any?>): ToolResult {
        val file = workspace.resolve(args.str("path") ?: return ToolResult.fail("E_INPUT", "path is required"))
        if (!file.isFile) return ToolResult.fail("E_NOT_FOUND", "no such file: ${file.path}")

        val projectId = args.str("project_id")
        if (projectId != null && db.project(projectId) == null) {
            return ToolResult.fail("E_NOT_FOUND", "no such project: $projectId")
        }
        val out = when {
            args.str("dest") != null -> workspace.requireWritable(workspace.resolve(args.str("dest")!!))
            projectId != null -> workspace.project(projectId).extract
            else -> workspace.requireWritable(
                File(workspace.root, "decoded/${file.nameWithoutExtension}-${System.currentTimeMillis()}"),
            )
        }
        val sources = args.bool("sources") ?: true
        val resources = args.bool("resources") ?: true

        return runCatching {
            val outcome = apkEngine.decode(file, out, sources, resources)
            projectId?.let { db.updateProjectStatus(it, "DECODED") }
            ToolResult(
                ok = true,
                summary = "解码完成 → ${workspace.display(out)}",
                text = "${outcome.text}\n目录: ${workspace.display(out)}",
                diagnostics = outcome.diagnostics,
            )
        }.getOrElse {
            ToolResult.error(
                code = "E_DECODE",
                message = "apktool 解码失败：${it.message}",
                detail = it.stackTraceToString().take(2_000),
            )
        }
    }

    private fun build(args: Map<String, Any?>): ToolResult {
        val dir = workspace.resolve(args.str("dir") ?: return ToolResult.fail("E_INPUT", "dir is required"))
        if (!dir.isDirectory) return ToolResult.fail("E_NOT_FOUND", "no such directory: ${dir.path}")

        val projectId = args.str("project_id") ?: projectIdOf(dir)
        val out = args.str("out")?.let { workspace.requireWritable(workspace.resolve(it)) }
            ?: workspace.requireWritable(
                if (projectId != null) {
                    File(workspace.project(projectId).build, "${dir.name}-unsigned.apk")
                } else {
                    File(dir.parentFile, "${dir.name}-unsigned.apk")
                },
            )

        return runCatching {
            val outcome = apkEngine.build(dir, out)
            projectId?.let { db.updateProjectStatus(it, "BUILT") }
            val artifact = recordArtifact(projectId, out, "APK", "BUILD")
            ToolResult(
                ok = true,
                summary = "构建成功 → ${workspace.display(out)}",
                text = outcome.text,
                artifacts = listOfNotNull(artifact),
                diagnostics = outcome.diagnostics,
            )
        }.getOrElse {
            ToolResult.error(
                code = "E_BUILD",
                message = "重打包失败：${it.message}",
                detail = it.stackTraceToString().take(2_000),
            )
        }
    }

    private fun align(args: Map<String, Any?>): ToolResult {
        val input = workspace.resolve(args.str("path") ?: return ToolResult.fail("E_INPUT", "path is required"))
        if (!input.isFile) return ToolResult.fail("E_NOT_FOUND", "no such file: ${input.path}")
        val pageSize = (args.int("page_size") ?: 4096).let { if (it >= 16_384) 16_384 else 4096 }
        val out = args.str("out")?.let { workspace.requireWritable(workspace.resolve(it)) }
            ?: workspace.requireWritable(File(input.parentFile, "${input.nameWithoutExtension}-aligned.apk"))

        return runCatching {
            val text = apkEngine.align(input, out, pageSize)
            val artifact = recordArtifact(projectIdOf(out), out, "APK", "ALIGN")
            ToolResult(true, "对齐完成 → ${workspace.display(out)}", text, listOfNotNull(artifact))
        }.getOrElse {
            ToolResult.error("E_TOOLCHAIN", "zipalign 失败：${it.message}")
        }
    }

    private fun sign(args: Map<String, Any?>): ToolResult {
        val input = workspace.resolve(args.str("path") ?: return ToolResult.fail("E_INPUT", "path is required"))
        if (!input.isFile) return ToolResult.fail("E_NOT_FOUND", "no such file: ${input.path}")

        val projectId = args.str("project_id") ?: projectIdOf(input)
        val out = args.str("out")?.let { workspace.requireWritable(workspace.resolve(it)) }
            ?: workspace.requireWritable(
                if (projectId != null) {
                    File(workspace.project(projectId).out, "${input.nameWithoutExtension}-signed.apk")
                } else {
                    File(input.parentFile, "${input.nameWithoutExtension}-signed.apk")
                },
            )
        val v1 = args.bool("v1") ?: false
        val v2 = args.bool("v2") ?: true
        val v3 = args.bool("v3") ?: true

        return runCatching {
            val key = keys.loadOrCreate(args.str("keystore_id") ?: SigningKeys.DEFAULT_ID)
            val text = apkEngine.sign(input, out, key, v1, v2, v3)
            projectId?.let { db.updateProjectStatus(it, "SIGNED") }
            val artifact = recordArtifact(projectId, out, "APK", "SIGN")
            val verify = runCatching { apkEngine.verify(out) }.getOrNull()
            ToolResult(
                ok = true,
                summary = "签名完成 → ${workspace.display(out)}",
                text = buildString {
                    append(text)
                    append("\ncert sha256: ")
                    append(key.certificate.encoded.sha256Hex())
                    if (verify != null) append("\nverify: ${verify.verified} (v1=${verify.v1}, v2=${verify.v2}, v3=${verify.v3})")
                },
                artifacts = listOfNotNull(artifact),
            )
        }.getOrElse {
            ToolResult.error("E_VERIFY", "签名失败：${it.message}", it.stackTraceToString().take(2_000))
        }
    }

    private fun verify(args: Map<String, Any?>): ToolResult {
        val file = workspace.resolve(args.str("path") ?: return ToolResult.fail("E_INPUT", "path is required"))
        if (!file.isFile) return ToolResult.fail("E_NOT_FOUND", "no such file: ${file.path}")
        return runCatching {
            val summary = apkEngine.verify(file)
            val text = buildString {
                append("verified: ${summary.verified}\n")
                append("v1=${summary.v1} v2=${summary.v2} v3=${summary.v3}\n")
                append("cert sha256: ${summary.certSha256.joinToString()}")
                if (summary.errors.isNotEmpty()) {
                    append("\nerrors:")
                    summary.errors.forEach { append("\n- $it") }
                }
            }
            ToolResult(summary.verified, if (summary.verified) "签名有效" else "签名校验未通过", text)
        }.getOrElse {
            ToolResult.error("E_VERIFY", "校验失败：${it.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(args: Map<String, Any?>): ToolResult {
        val pkg = args.str("package")?.trim().orEmpty()
        if (pkg.isEmpty() || !PACKAGE_NAME.matches(pkg)) {
            return ToolResult.fail("E_INPUT", "非法包名：$pkg")
        }
        val info = installedPackage(pkg)
            ?: return ToolResult(true, "$pkg 未安装", "installed: false\npackage: $pkg")
        return ToolResult(
            ok = true,
            summary = "已安装 $pkg ${info.versionName}",
            text = buildString {
                appendLine("installed: true")
                appendLine("package: $pkg")
                appendLine("versionName: ${info.versionName}")
                appendLine("versionCode: ${versionCodeOf(info)}")
                appendLine("uid: ${info.applicationInfo?.uid}")
                appendLine("apk: ${info.applicationInfo?.sourceDir}")
                append("cert sha256: ${installedCertificates(pkg).joinToString()}")
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun appList(args: Map<String, Any?>): ToolResult {
        val thirdParty = args.bool("third_party") ?: true
        val limit = (args.int("limit") ?: 100).coerceIn(1, 500)
        val filter = args.str("filter")?.lowercase()
        val pm = context.packageManager
        val packages = runCatching { pm.getInstalledPackages(0) }
            .getOrElse { return ToolResult.error("E_PACKAGE", "读取应用列表失败：${it.message}") }

        val rows = packages.asSequence()
            .filter { info ->
                val app = info.applicationInfo ?: return@filter false
                val flags = app.flags
                val thirdPartyMatch = !thirdParty || flags and ApplicationInfo.FLAG_SYSTEM == 0
                if (!thirdPartyMatch) return@filter false
                if (filter == null) return@filter true
                if (info.packageName.lowercase().contains(filter)) return@filter true
                runCatching { pm.getApplicationLabel(app).toString().lowercase() }
                    .getOrDefault("")
                    .contains(filter)
            }
            .sortedBy { it.packageName }
            .take(limit)
            .map { info ->
                val app = info.applicationInfo
                val flags = app?.flags ?: 0
                val kind = if (flags and ApplicationInfo.FLAG_SYSTEM != 0) "系统" else "用户"
                val label = app?.let {
                    runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault("")
                }.orEmpty()
                "${info.packageName}  ${info.versionName ?: "?"} (${versionCodeOf(info)})  uid=${app?.uid}  $kind  $label"
            }
            .toList()

        return if (rows.isEmpty()) {
            ToolResult(true, "没有匹配的应用", "filter=${filter.orEmpty()} third_party=$thirdParty")
        } else {
            ToolResult(
                true,
                "共 ${rows.size} 个应用",
                buildString {
                    appendLine("package  version (code)  uid  type  label")
                    rows.forEach { appendLine(it) }
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun appPermissions(args: Map<String, Any?>): ToolResult {
        val pkg = packageArg(args) ?: return ToolResult.fail("E_INPUT", "非法包名")
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()
            ?: return ToolResult.fail("E_NOT_FOUND", "$pkg 未安装")
        val requested = info.requestedPermissions.orEmpty().distinct().sorted()
        if (requested.isEmpty()) {
            return ToolResult(true, "$pkg 没有声明权限", "package: $pkg")
        }
        val granted = requested.filter { pm.checkPermission(it, pkg) == PackageManager.PERMISSION_GRANTED }
        val denied = requested - granted.toSet()
        return ToolResult(
            ok = true,
            summary = "$pkg：${granted.size} 已授予 / ${denied.size} 未授予",
            text = buildString {
                appendLine("package: $pkg")
                appendLine("已授予（${granted.size}）：")
                granted.forEach { appendLine("- $it") }
                appendLine("未授予（${denied.size}）：")
                denied.forEach { appendLine("- $it") }
            },
        )
    }

    private fun appLaunch(args: Map<String, Any?>): ToolResult {
        val pkg = packageArg(args) ?: return ToolResult.fail("E_INPUT", "非法包名")
        val activity = args.str("activity")

        // adb shell 能绕过后台启动限制，并且能回到前台核对，避免「返回 ok 但没启动」。
        if (AdbSession.connected()) {
            val component = activity?.let {
                when {
                    it.startsWith(".") -> pkg + it
                    it.contains("/") -> it
                    else -> "$pkg/$it"
                }
            }
            val start = component?.let { "am start -n ${shellQuote(it)}" }
                ?: "monkey -p ${shellQuote(pkg)} -c android.intent.category.LAUNCHER 1"
            val probe = "$start; sleep 1; dumpsys activity activities | grep -m1 mResumedActivity"
            val result = AdbSession.shell(context, probe, 20)
            if (result.ok) {
                val text = result.text
                val failed = text.contains("Error:") ||
                    text.contains("No activities found") ||
                    text.contains("monkey aborted")
                val resumed = text.lineSequence().firstOrNull { it.contains("mResumedActivity") }.orEmpty()
                if (!failed && resumed.contains(pkg)) {
                    return ToolResult(true, "已启动 $pkg（adb）", text.trim().take(4_000))
                }
                return ToolResult.error(
                    "E_LAUNCH",
                    if (failed) "启动失败：$pkg" else "启动后前台不是 $pkg",
                    text.take(4_000),
                )
            }
        }

        // 没有 adb 时退回 Intent；它受后台启动限制，可能被系统静默丢弃。
        val intent = if (activity != null) {
            val name = if (activity.startsWith(".")) pkg + activity else activity
            Intent().setComponent(ComponentName(pkg, name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            context.packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return ToolResult.fail("E_NO_LAUNCHER", "$pkg 没有可启动的 Activity")

        return runCatching {
            context.startActivity(intent)
            ToolResult(
                true,
                "已发送启动请求：$pkg",
                buildString {
                    appendLine("package: $pkg")
                    activity?.let { appendLine("activity: $it") }
                    appendLine("component: ${intent.component?.flattenToShortString().orEmpty()}")
                    append("注意：没有 adb 时受后台启动限制，系统可能丢弃该请求；连接 adb 后会自动核对前台。")
                },
            )
        }.getOrElse {
            ToolResult.error("E_LAUNCH", "启动失败：${it.message}")
        }
    }

    private fun appStop(args: Map<String, Any?>): ToolResult {
        val pkg = packageArg(args) ?: return ToolResult.fail("E_INPUT", "非法包名")
        val result = runPrivileged("am force-stop ${shellQuote(pkg)}")
        if (result.ok) return ToolResult(true, "已停止 $pkg", result.output.trim())
        if (rootUnavailable(result)) return rootRequired("app_stop")
        return ToolResult.error("E_STOP", "停止失败：$pkg", result.output.take(4_000))
    }

    private fun appGrant(args: Map<String, Any?>): ToolResult {
        val pkg = packageArg(args) ?: return ToolResult.fail("E_INPUT", "非法包名")
        val permission = args.str("permission")?.trim().orEmpty()
        if (!PERMISSION_NAME.matches(permission)) {
            return ToolResult.fail("E_INPUT", "非法权限名：$permission")
        }
        val grant = args.bool("grant") ?: true
        val verb = if (grant) "grant" else "revoke"
        val result = runPrivileged("pm $verb ${shellQuote(pkg)} ${shellQuote(permission)}")
        if (result.ok && !result.output.contains("Failure")) {
            return ToolResult(
                true,
                if (grant) "已授予 $pkg $permission" else "已撤销 $pkg $permission",
                result.output.trim(),
            )
        }
        if (rootUnavailable(result)) return rootRequired("app_grant")
        return ToolResult.error(
            "E_PERMISSION",
            "${if (grant) "授予" else "撤销"}失败：$permission",
            result.output.take(4_000),
        )
    }

    private fun appDebug(args: Map<String, Any?>): ToolResult {
        val pkg = packageArg(args) ?: return ToolResult.fail("E_INPUT", "非法包名")
        val clear = args.bool("clear") ?: false
        val command = if (clear) {
            "am clear-debug-app"
        } else {
            buildString {
                append("am set-debug-app")
                if (args.bool("wait") ?: true) append(" -w")
                if (args.bool("persistent") == true) append(" --persistent")
                append(' ').append(shellQuote(pkg))
            }
        }
        val result = runPrivileged(command)
        if (result.ok) {
            return ToolResult(
                true,
                if (clear) "已清除调试应用设置" else "已设置调试应用：$pkg",
                buildString {
                    appendLine(command)
                    appendLine()
                    appendLine("目标必须是 debuggable；重打包时可在 AndroidManifest 里加 android:debuggable=\"true\"。")
                    append("连接调试器（电脑端）：adb forward tcp:8700 jdwp:<pid>")
                },
            )
        }
        if (rootUnavailable(result)) return rootRequired("app_debug")
        return ToolResult.error("E_DEBUG", "设置调试应用失败", result.output.take(4_000))
    }

    private fun logcat(args: Map<String, Any?>): ToolResult {
        val clear = args.bool("clear") ?: false
        if (clear) {
            val cleared = runPrivileged("logcat -c")
            if (!cleared.ok) {
                if (rootUnavailable(cleared)) return rootRequired("logcat")
                return ToolResult.error("E_LOG", "清空日志失败", cleared.output.take(4_000))
            }
        }

        val lines = (args.int("lines") ?: 200).coerceIn(10, 5_000)
        val level = (args.str("level") ?: "V").uppercase().firstOrNull()
            ?.takeIf { it in "VDIWEF" } ?: 'V'
        val tag = args.str("tag")
        val pkg = packageArg(args)

        var pid: String? = null
        var note = ""
        if (pkg != null) {
            val probe = runPrivileged("pidof ${shellQuote(pkg)}")
            if (rootUnavailable(probe)) return rootRequired("logcat")
            pid = probe.output.trim().split(Regex("\\s+"))
                .firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
            if (pid == null) note = "未找到 $pkg 的运行进程，本次读取全量日志。\n"
        }

        val command = buildString {
            append("logcat -d -t ").append(lines).append(" -v threadtime")
            pid?.let { append(" --pid=").append(it) }
            tag?.let { append(" -s ").append(shellQuote(it)) }
            append(" *:").append(level)
        }
        val result = runPrivileged(command)
        if (rootUnavailable(result)) return rootRequired("logcat")
        if (!result.ok) {
            return ToolResult.error("E_LOG", "读取日志失败", result.output.take(4_000))
        }
        return ToolResult(
            true,
            "日志 $lines 行" + (pid?.let { "（pid $it）" } ?: ""),
            note + result.output.trim().take(120_000),
        )
    }

    private fun adbStatus(args: Map<String, Any?>): ToolResult {
        val endpoint = AdbSession.endpoint()
        return ToolResult(
            true,
            if (endpoint != null) "ADB 已连接 $endpoint" else "ADB 未连接",
            buildString {
                appendLine("connected: ${AdbSession.connected()}")
                appendLine("endpoint: ${endpoint ?: "-"}")
                appendLine("keys: ${File(AppStorage.root(context), "adb").absolutePath}")
                append("提示：先在设置页「无线调试配对」输入 6 位配对码；配对后 adb_connect 会自动发现端口。")
            },
        )
    }

    private fun adbConnect(args: Map<String, Any?>): ToolResult {
        val host = args.str("host")
        val port = args.int("port") ?: 0
        val result = AdbSession.connect(context, host, port)
        if (!result.ok) {
            return ToolResult.error("E_ADB", "ADB 连接失败", result.text)
        }
        return ToolResult(
            true,
            "ADB 已连接 ${AdbSession.endpoint()}",
            "device: ${result.text.trim()}",
        )
    }

    private fun adbShell(args: Map<String, Any?>): ToolResult {
        val command = args.str("command") ?: return ToolResult.fail("E_INPUT", "command is required")
        val host = args.str("host")
        val port = args.int("port") ?: 0
        val timeout = (args.int("timeout_seconds") ?: 60).coerceIn(1, 600).toLong()
        val result = AdbSession.shell(context, host, port, command, timeout)
        if (!result.ok) {
            return ToolResult.error("E_ADB", "adb shell 失败", result.text)
        }
        return ToolResult(true, "adb shell 完成", result.text.trim().take(120_000))
    }

    /** 系统级操作需要 root：su 不存在或被拒绝时统一返回这个提示。 */
    private fun rootRequired(tool: String): ToolResult = ToolResult.error(
        "E_ROOT",
        "「$tool」需要 adb shell 或 root",
        "当前既没有可用的 adb shell，也没有 root。先 adb_connect（无线调试端口），" +
            "或在 su 管理器给本应用授权 root 后重试。",
    )

    private fun packageArg(args: Map<String, Any?>): String? {
        val pkg = args.str("package")?.trim().orEmpty()
        return pkg.takeIf { PACKAGE_NAME.matches(it) }
    }

    private fun install(args: Map<String, Any?>): ToolResult {
        val file = workspace.resolve(args.str("path") ?: return ToolResult.fail("E_INPUT", "path is required"))
        if (!file.isFile) return ToolResult.fail("E_NOT_FOUND", "no such file: ${file.path}")
        val replace = args.bool("replace") ?: false
        val downgrade = args.bool("downgrade") ?: false

        val meta = runCatching { apkEngine.info(file) }
            .getOrElse { return ToolResult.error("E_INPUT", "APK 解析失败：${it.message}") }
        val pkg = meta.json.optString("package")
        if (pkg.isBlank()) return ToolResult.fail("E_INPUT", "APK 没有包名")

        val installed = installedPackage(pkg)
        val apkCerts = meta.json.optJSONObject("signature")
            ?.optJSONArray("certSha256")
            ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }
            .orEmpty()
        val installedCerts = if (installed != null) installedCertificates(pkg) else emptyList()
        val signatureMismatch = installedCerts.isNotEmpty() &&
            apkCerts.isNotEmpty() &&
            installedCerts.toSet() != apkCerts.toSet()

        val apkVersion = meta.json.optLong("versionCode", 0L)
        val installedVersion = installed?.let { versionCodeOf(it) } ?: 0L

        if (signatureMismatch && !replace) {
            return ToolResult.error(
                "E_SIGNATURE",
                "签名不一致，覆盖安装会失败",
                buildString {
                    appendLine("已安装 $pkg 的证书：${installedCerts.joinToString()}")
                    appendLine("新 APK 的证书：${apkCerts.joinToString()}")
                    appendLine("Android 不允许跨签名覆盖（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。")
                    append("传 replace=true 会先卸载已安装版本再安装，应用数据会丢失；或用原签名密钥重签。")
                },
            )
        }
        if (!downgrade && apkVersion in 1 until installedVersion) {
            return ToolResult.error(
                "E_DOWNGRADE",
                "版本降级被拒绝：已安装 $installedVersion，新 APK $apkVersion",
                "传 downgrade=true 允许降级（等价于 pm install -d）。",
            )
        }

        val notes = mutableListOf<String>()
        if (signatureMismatch && replace) {
            val removal = runPrivileged("pm uninstall ${shellQuote(pkg)}")
            if (!removal.ok || removal.output.contains("Failure")) {
                return ToolResult.error(
                    "E_UNINSTALL",
                    "卸载 $pkg 失败，未继续安装",
                    "exit=${removal.exit}\n${removal.output.take(4_000)}",
                )
            }
            notes += "已先卸载旧版本（应用数据已清除）"
        }

        val flags = if (downgrade) "-r -d" else "-r"
        val result = runPrivileged("pm install $flags ${shellQuote(file.absolutePath)}")
        if (!result.ok || result.output.contains("Failure")) {
            // No root: hand the APK to the system installer instead of leaving
            // the model with an opaque SecurityException from pm.
            if (rootUnavailable(result) && !signatureMismatch) {
                return openInstaller(file, pkg)
            }
            return ToolResult.error(
                "E_INSTALL",
                "安装失败：$pkg",
                buildString {
                    appendLine("exit=${result.exit}")
                    appendLine(result.output.take(4_000))
                    if (result.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
                        append("签名仍不一致：确认 replace=true 已实际卸载旧包，或改用原签名密钥重签。")
                    }
                    if (rootUnavailable(result)) {
                        append("当前应用没有 root 权限，静默安装不可用；授予 root 后会走 pm install。")
                    }
                },
            )
        }

        projectIdOf(file)?.let { db.updateProjectStatus(it, "INSTALLED") }
        return ToolResult(
            ok = true,
            summary = "安装成功：$pkg ${meta.json.optString("versionName")}",
            text = buildString {
                appendLine("package: $pkg")
                appendLine("version: ${meta.json.optString("versionName")} ($apkVersion)")
                if (notes.isNotEmpty()) appendLine("注意：${notes.joinToString("；")}")
                append(result.output.trim().take(2_000))
            },
        )
    }

    private fun uninstall(args: Map<String, Any?>): ToolResult {
        val pkg = args.str("package")?.trim().orEmpty()
        if (pkg.isEmpty()) return ToolResult.fail("E_INPUT", "package is required")
        if (!PACKAGE_NAME.matches(pkg)) return ToolResult.fail("E_INPUT", "非法包名：$pkg")
        val result = runPrivileged("pm uninstall ${shellQuote(pkg)}")
        if (result.ok && !result.output.contains("Failure")) {
            return ToolResult(true, "已卸载 $pkg", result.output.trim())
        }
        if (rootUnavailable(result)) {
            return openUninstaller(pkg)
        }
        return ToolResult.error(
            "E_UNINSTALL",
            "卸载失败：$pkg",
            "exit=${result.exit}\n${result.output.take(4_000)}",
        )
    }

    private data class RootResult(val exit: Int, val ok: Boolean, val output: String)

    /**
     * adb shell 已连接时优先走它：shell 身份可以执行 pm/am/logcat，
     * 不需要 root，也不受应用后台启动限制。未连接时才退回 su。
     */
    private fun runPrivileged(command: String, timeoutSeconds: Long = 120): RootResult {
        if (AdbSession.connected()) {
            val result = AdbSession.shell(context, command, timeoutSeconds)
            return RootResult(if (result.ok) 0 else 1, result.ok, result.text)
        }
        return runRoot(command, timeoutSeconds)
    }

    /** 静默安装需要 root：应用自身没有 INSTALL_PACKAGES 权限。 */
    private fun runRoot(command: String, timeoutSeconds: Long = 120): RootResult = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            RootResult(-1, false, output + "\n(timeout)")
        } else {
            val code = process.exitValue()
            RootResult(code, code == 0, output)
        }
    }.getOrElse {
        RootResult(-1, false, "无法执行 su：${it.message}")
    }

    private fun installedPackage(pkg: String): android.content.pm.PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun installedCertificates(pkg: String): List<String> {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            }
        }.getOrNull() ?: return emptyList()
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures?.map { it.toByteArray().sha256Hex() }.orEmpty()
    }

    private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /** 单引号包裹，路径里的空格和引号不会把 pm 的参数拆开。 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** su 不存在或被拒绝，而不是安装本身失败。 */
    private fun rootUnavailable(result: RootResult): Boolean {
        if (result.ok) return false
        val text = result.output.lowercase()
        return text.contains("su: not found") ||
            text.contains("su: inaccessible") ||
            text.contains("permission denied") ||
            text.contains("install_packages") ||
            text.contains("not allowed") ||
            text.contains("无法执行 su")
    }

    /**
     * 无 root 时的安装路径：把 APK 通过 FileProvider 交给系统安装器。
     *
     * 这不是静默安装，需要用户在屏幕上点确认；工具会明确说明，而不是伪装成成功。
     */
    private fun openInstaller(file: File, pkg: String): ToolResult = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        ToolResult(
            ok = true,
            summary = "已打开系统安装器：$pkg",
            text = buildString {
                appendLine("当前应用没有 root，无法静默安装。")
                appendLine("APK: ${file.absolutePath}")
                appendLine("请在系统安装界面确认；若提示“未知来源”，先允许本应用安装应用。")
            },
        )
    }.getOrElse {
        ToolResult.error("E_INSTALL", "无法调起系统安装器：${it.message}")
    }

    /** 无 root 时不能静默卸载，交给系统卸载界面。 */
    private fun openUninstaller(pkg: String): ToolResult = runCatching {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        ToolResult(
            ok = true,
            summary = "已打开系统卸载界面：$pkg",
            text = "当前应用没有 root，无法静默卸载，请在系统界面确认。",
        )
    }.getOrElse {
        ToolResult.error("E_UNINSTALL", "无法调起系统卸载界面：${it.message}")
    }

    private fun dexList(args: Map<String, Any?>): ToolResult {
        val file = workspace.resolve(args.str("path") ?: return ToolResult.fail("E_INPUT", "path is required"))
        if (!file.isFile) return ToolResult.fail("E_NOT_FOUND", "no such file: ${file.path}")
        val limit = (args.int("limit") ?: 100).coerceIn(1, 2000)
        return runCatching {
            ToolResult(true, "dex 类清单（最多 $limit 个类）", apkEngine.dexClasses(file, limit))
        }.getOrElse {
            ToolResult.error("E_DEX", "dex 解析失败：${it.message}", it.stackTraceToString().take(2_000))
        }
    }

    private fun dexDecompile(args: Map<String, Any?>): ToolResult {
        val file = workspace.resolve(args.str("path") ?: return ToolResult.fail("E_INPUT", "path is required"))
        if (!file.isFile) return ToolResult.fail("E_NOT_FOUND", "no such file: ${file.path}")
        val out = args.str("out")?.let { workspace.requireWritable(workspace.resolve(it)) }
            ?: workspace.requireWritable(
                File(workspace.root, "decompiled/${file.nameWithoutExtension}-${System.currentTimeMillis()}"),
            )
        val className = args.str("class_name")
        return runCatching {
            val outcome = apkEngine.decompile(file, out, className)
            ToolResult(
                ok = true,
                summary = "反编译完成 → ${workspace.display(out)}",
                text = "${outcome.text}\n目录: ${workspace.display(out)}",
                diagnostics = outcome.diagnostics,
            )
        }.getOrElse {
            ToolResult.error(
                code = "E_DECOMPILE",
                message = "jadx 反编译失败：${it.message}",
                detail = it.stackTraceToString().take(2_000),
            )
        }
    }

    private fun soInfo(args: Map<String, Any?>): ToolResult {
        val file = workspace.resolve(args.str("path") ?: return ToolResult.fail("E_INPUT", "path is required"))
        if (!file.isFile) return ToolResult.fail("E_NOT_FOUND", "no such file: ${file.path}")
        return runCatching {
            ToolResult(true, "ELF 结构：${file.name}", apkEngine.soInfo(file))
        }.getOrElse {
            ToolResult.error("E_ELF", "ELF 解析失败：${it.message}")
        }
    }

    private fun soDisassemble(args: Map<String, Any?>): ToolResult {
        val file = workspace.resolve(args.str("path") ?: return ToolResult.fail("E_INPUT", "path is required"))
        if (!file.isFile) return ToolResult.fail("E_NOT_FOUND", "no such file: ${file.path}")
        val symbol = args.str("symbol")
        val offset = args.str("offset")?.let { parseAddress(it) }
            ?: args.int("offset")?.toLong()
            ?: 0L
        if (symbol == null && offset <= 0) {
            return ToolResult.fail("E_INPUT", "symbol 或 offset 至少提供一个")
        }
        val count = (args.int("count") ?: 64).coerceIn(1, 2000)
        return runCatching {
            val outcome = apkEngine.soDisassemble(file, symbol, offset, count)
            ToolResult(
                ok = true,
                summary = "反汇编：${file.name}${symbol?.let { " $it" } ?: ""}",
                text = outcome.text,
                diagnostics = outcome.diagnostics,
            )
        }.getOrElse {
            ToolResult.error("E_DISASM", "反汇编失败：${it.message}", it.stackTraceToString().take(2_000))
        }
    }

    private fun parseAddress(text: String): Long? {
        val value = text.trim()
        return if (value.startsWith("0x", ignoreCase = true)) {
            value.substring(2).toLongOrNull(16)
        } else {
            value.toLongOrNull()
        }
    }

    // ------------------------------------------------------------------ //
    // Bookkeeping
    // ------------------------------------------------------------------ //

    private fun recordArtifact(projectId: String?, file: File, kind: String, origin: String): ToolArtifact? {
        if (!file.isFile) return null
        val id = newArtifactId()
        val sha = workspace.sha256(file)
        db.insertArtifact(
            id = id,
            projectId = projectId,
            kind = kind,
            path = workspace.display(file),
            size = file.length(),
            sha256 = sha,
            origin = origin,
        )
        return ToolArtifact(id, kind, workspace.display(file), sha, file.length())
    }

    /** A path inside `projects/{id}/...` names its project. */
    private fun projectIdOf(file: File): String? {
        val relative = runCatching { file.canonicalFile.relativeTo(workspace.root.canonicalFile).path }.getOrNull()
            ?: return null
        val parts = relative.split(File.separatorChar)
        return if (parts.size >= 2 && parts[0] == "projects") parts[1] else null
    }

    private fun newArtifactId(): String = "a" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun ByteArray.sha256Hex(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ADB_PREFS = "adb"
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        val PERMISSION_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

// ---------------------------------------------------------------------- //
// Argument helpers
// ---------------------------------------------------------------------- //

internal fun Map<String, Any?>.str(key: String): String? =
    (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

internal fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
    is Int -> value
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}

internal fun Map<String, Any?>.bool(key: String): Boolean? = when (val value = this[key]) {
    is Boolean -> value
    is String -> value.toBooleanStrictOrNull()
    else -> null
}
