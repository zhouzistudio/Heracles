package com.theohowie.heracles.bridge

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **把用户选的文件夹"搬进"App 私有目录，并把私有副本打回 zip**（2026-11-04）。
 *
 * ## 为什么必须复制（而不是继续直接读写用户选的目录）
 *
 * 用户选文件夹走 SAF，拿到的是 `content://` 授权，**不是文件系统权限**：
 *
 * | 打开方式 | 外部目录（`/sdcard/我的项目`） | 私有目录（`Android/data/<pkg>/files/…`） |
 * |---|---|---|
 * | Flutter / `java.io.File` | ✗ `EPERM` | ✓ |
 * | 沙箱（proot 里的 Linux） | ✗ 挂不进去 | ✓ |
 * | 终端 `run_command` | ✗（只认 POSIX 路径） | ✓ |
 *
 * 也就是说：只要工作区还是"用户选的那个外部目录"，沙箱和终端就**永远**用不上它 ——
 * 之前所有 `fs.*` 调用都得绕 `DocumentFile`，还得在文档里解释"为什么这里不行"。
 * 复制到私有目录后，工作区就是一个普通 POSIX 目录，三条链路一套代码。
 *
 * 代价（**必须如实告诉用户**，UI 里也写了）：
 * - 原文件夹之后**不再同步** —— 改的是副本；
 * - 卸载 App 会一起删掉副本 ⇒ 所以有 [exportZip]。
 *
 * ## 跳过哪些目录（用户 2026-11-04 定：跳过可再生成的大目录，保留 `.git`）
 *
 * [SKIP_DIRS] 里全是"跑一次命令就能重建"的产物目录。不跳的话，一个带
 * `node_modules` 的前端项目能有几万个文件、几百 MB，经 SAF 逐个 IPC 复制
 * 要好几分钟 —— 而这些东西拷过来也毫无价值。
 *
 * `.git` **不跳**：Git 面板和 GitHub 推送都靠它，跳过等于把版本历史扔掉。
 *
 * ## 上层契约（见 `MainActivity.handleImportWorkspace`）
 *
 * ```
 * 成功 → {ok:true, path:<私有副本绝对路径>, files, bytes, skippedDirs}
 * 失败 → {ok:false, error:"人话"}      ← 半成品目录一定已删除，不留残骸
 * ```
 */
class WorkspaceImporter(
    private val context: Context,
    private val registry: WorkspaceRegistry,
) {

    companion object {
        /** 私有工作区根：`<externalFilesDir>/workspaces/<名字>-<hash4>`。 */
        const val ROOT_DIR = "workspaces"

        /** 导出 zip 落在 `<externalFilesDir>/exports/`（用户能在文件管理器里看到）。 */
        const val EXPORT_DIR = "exports"

        /**
         * 一次导入最多多少个文件。
         *
         * 20 000：源码项目通常几千；再多基本是"忘了跳过某个产物目录"。
         * 超限**明确拒绝**而不是拷到一半没空间 —— 那种失败用户更难理解。
         */
        private const val MAX_FILES = 20_000

        /** 一次导入最多多少字节（400MB，与上面同一条取舍）。 */
        private const val MAX_TOTAL_BYTES = 400L * 1024 * 1024

        /** 目录递归深度上限（防成环 / 病态深树）。 */
        private const val MAX_DEPTH = 24

        /** 每次复制读写用的缓冲（64KB：SAF 流的小块读会慢一个量级）。 */
        private const val BUF = 64 * 1024

        /**
         * 复制时**跳过**的目录名（小写比较）。
         *
         * 判据只有一条：**删掉它、重跑一次构建就能回来**。
         * 所以 `dist` / `target` **不跳** —— 有些项目把成品放那儿，删了就丢东西。
         */
        private val SKIP_DIRS = setOf(
            "node_modules",
            "build",
            ".gradle",
            ".dart_tool",
            "__pycache__",
            ".godot",
            ".venv",
            "venv",
            ".pytest_cache",
            ".mypy_cache",
            ".next",
            ".nuxt",
            ".cache",
            "pods",
        )

        /** 导出 zip 时排掉的 App 元数据目录（会话历史，不是用户的源码）。 */
        private const val APP_META_DIR = ".heracles"
    }

    // ------------------------------------------------------------ 取消

    /**
     * 取消标志。**进程级单例语义由调用方保证**（MainActivity 只建一个实例）。
     *
     * 为什么用 volatile 布尔而不是协程取消：复制是 `InputStream.copyTo` 的紧循环，
     * 中途没有挂起点，`ensureActive()` 检查不到（不是 suspend 调用）。
     * 每处理一个文件查一次这个标志，是最简单也最可靠的打断点。
     */
    @Volatile
    private var cancelled: Boolean = false

    fun cancel() {
        cancelled = true
    }

    // ------------------------------------------------------------ 复制进来

    /**
     * 把 SAF 授权的文件夹复制到私有目录。
     *
     * @param treeUri 选择器返回的 tree URI（唯一能读原文件夹的凭证）
     * @param displayName 用户/系统给的名字（用它做私有目录名前缀）
     * @param sourcePath 原文件夹的 POSIX 路径（只用于生成 hash 和记录来源）
     * @param onProgress `(已完成, 总数, 当前文件相对路径)`，**在 IO 线程回调**
     * @return 见类注释的契约
     */
    suspend fun importFolder(
        treeUri: String,
        displayName: String,
        sourcePath: String,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        cancelled = false

        val uri: Uri = runCatching { Uri.parse(treeUri) }.getOrNull()
            ?: return@withContext fail("这个位置没有可读取的授权")
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            ?: return@withContext fail("读不到这个文件夹的授权，请重新选一次")
        if (!root.isDirectory) return@withContext fail("所选位置不是一个文件夹")

        // ---- 1) 先走一遍树：统计文件、体积，顺便把"能读到的文件"列出来 ----
        val files = mutableListOf<SourceFile>()
        val walkState = WalkState()

        try {
            walkSourceForImport(root, "", 0, files, walkState)
        } catch (e: ImportCancelled) {
            return@withContext fail("已取消复制，没有登记工作区")
        } catch (e: ImportFailed) {
            return@withContext fail(e.message ?: "读取失败")
        }

        // ---- 2) 备好私有目录 ----
        val base = context.getExternalFilesDir(null)
            ?: return@withContext fail("外部私有目录不可用（存储被摘掉了？）")
        val rootDir = File(base, ROOT_DIR)
        if (!rootDir.isDirectory && !rootDir.mkdirs()) {
            return@withContext fail("建不出私有工作区目录")
        }

        // 空间检查：`usableSpace` 拿不到时返回 0，那就只能跳过检查（不假装知道）
        val usable = rootDir.usableSpace
        if (usable in 1 until (walkState.bytes * 2)) {
            return@withContext fail(
                "私有目录剩余空间不够（需要约 ${humanBytes(walkState.bytes)}，" +
                    "只剩 ${humanBytes(usable)}）"
            )
        }

        val dest = uniqueDir(rootDir, destName(displayName, sourcePath))
        if (!dest.isDirectory && !dest.mkdirs()) {
            return@withContext fail("建不出目录：${dest.absolutePath}")
        }

        // ---- 3) 逐个文件复制 ----
        var done = 0
        onProgress(0, files.size, "")
        try {
            for (f in files) {
                if (cancelled) throw ImportCancelled()
                val outFile = File(dest, f.rel)
                outFile.parentFile?.mkdirs()
                val input = context.contentResolver.openInputStream(f.uri)
                    ?: throw ImportFailed("读不到文件：${f.rel}")
                input.use { ins ->
                    BufferedOutputStream(outFile.outputStream(), BUF).use { outs ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            outs.write(buf, 0, n)
                        }
                    }
                }
                done++
                if (done % 4 == 0 || done == files.size) {
                    onProgress(done, files.size, f.rel)
                }
            }
        } catch (e: ImportCancelled) {
            deleteTree(dest)
            return@withContext fail("已取消复制，没有登记工作区")
        } catch (e: Throwable) {
            // 半成品一定删掉：宁可什么都没有，也不留一个"看起来能用、实际缺文件"的工作区
            deleteTree(dest)
            val why = (e as? ImportFailed)?.message ?: e.message ?: e.javaClass.simpleName
            return@withContext fail("复制中断：$why")
        }

        onProgress(files.size, files.size, "")
        return@withContext mapOf(
            "ok" to true,
            "path" to dest.absolutePath,
            "files" to files.size,
            "bytes" to walkState.bytes,
            "skippedDirs" to walkState.skippedDirs,
        )
    }

    // ------------------------------------------------------------ 导出 zip

    /**
     * 把工作区打包成 zip，落在 `<externalFilesDir>/exports/`。
     *
     * 为什么是 zip 而不是"同步回去"：私有副本和原文件夹之间做双向同步，
     * 冲突/删除/改名每一个都要人来做主，手机上做不平。**导出**是单向的、
     * 用户明确触发的一次动作，语义没有歧义。
     *
     * 两条读取路径都要支持（老工作区还是外部 SAF 目录）：
     * - 私有目录 → `java.io.File`
     * - 外部 SAF 目录 → `DocumentFile`（`registry.matchSaf` 命中时）
     *
     * 排除 `.heracles/`（会话历史）与 [SKIP_DIRS]：导的是"源码"，不是 App 的账本。
     */
    suspend fun exportZip(
        workspacePath: String,
        displayName: String,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        cancelled = false

        val base = context.getExternalFilesDir(null)
            ?: return@withContext fail("外部私有目录不可用（存储被摘掉了？）")
        val dir = File(base, EXPORT_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext fail("建不出导出目录")

        val entries = mutableListOf<SourceFile>()
        val safHit = registry.matchSaf(workspacePath)
        try {
            if (safHit != null) {
                val treeRoot = DocumentFile.fromTreeUri(
                    context,
                    Uri.parse(safHit.first.treeUri),
                ) ?: return@withContext fail("这个工作区的授权已失效，重新选一次吧")
                var cur = treeRoot
                for (seg in safHit.second.split('/').filter { it.isNotEmpty() }) {
                    cur = cur.findFile(seg) ?: return@withContext fail("找不到：$seg")
                }
                walkSafForZip(cur, "", 0, entries)
            } else {
                val root = File(workspacePath)
                if (!root.isDirectory) return@withContext fail("文件夹不存在：$workspacePath")
                walkFileForZip(root, "", 0, entries)
            }
        } catch (e: ImportFailed) {
            return@withContext fail(e.message ?: "读取失败")
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val prefix = destName(displayName.ifBlank { File(workspacePath).name }, workspacePath)
        val out = File(dir, "$prefix-$stamp.zip")

        var done = 0
        onProgress(0, entries.size, "")
        try {
            ZipOutputStream(BufferedOutputStream(out.outputStream(), BUF)).use { zos ->
                for (f in entries) {
                    if (cancelled) throw ImportCancelled()
                    val entry = ZipEntry(f.rel.replace('\\', '/'))
                    if (f.mtime > 0) entry.time = f.mtime
                    zos.putNextEntry(entry)
                    val src = f.file?.inputStream()
                        ?: f.uri?.let { context.contentResolver.openInputStream(it) }
                        ?: throw ImportFailed("读不到文件：${f.rel}")
                    src.use { ins ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            zos.write(buf, 0, n)
                        }
                    }
                    zos.closeEntry()
                    done++
                    if (done % 8 == 0 || done == entries.size) {
                        onProgress(done, entries.size, f.rel)
                    }
                }
            }
        } catch (e: ImportCancelled) {
            runCatching { out.delete() }
            return@withContext fail("已取消导出")
        } catch (e: Throwable) {
            runCatching { out.delete() }
            val why = (e as? ImportFailed)?.message ?: e.message ?: e.javaClass.simpleName
            return@withContext fail("导出中断：$why")
        }

        onProgress(entries.size, entries.size, "")
        return@withContext mapOf(
            "ok" to true,
            "zipPath" to out.absolutePath,
            "name" to out.name,
            "files" to entries.size,
            "bytes" to out.length(),
        )
    }

    // ------------------------------------------------------------ 内部工具

    /** 一个待复制的文件：本地文件与 SAF URI 二选一（导出时两种来源都可能）。 */
    private class SourceFile(
        val rel: String,
        val file: File? = null,
        val uri: Uri? = null,
        val mtime: Long = 0L,
    )

    private class ImportCancelled : Exception("已取消")

    private class ImportFailed(message: String) : Exception(message)

    /** 走树时累计的统计（用可变持有类，避免在递归里回传多个值）。 */
    private class WalkState {
        var bytes: Long = 0L
        var skippedDirs: Int = 0
    }

    /**
     * 递归列出要复制的文件（SAF 读）。
     *
     * 每次 `listFiles()` 都是**一条 IPC**，所以超限（[MAX_FILES] / [MAX_TOTAL_BYTES]）
     * 时立刻抛 [ImportFailed] 停手 —— 继续走完再报错只是让用户白等。
     */
    private fun walkSourceForImport(
        dir: DocumentFile,
        prefix: String,
        depth: Int,
        out: MutableList<SourceFile>,
        state: WalkState,
    ) {
        if (cancelled) throw ImportCancelled()
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrElse {
            throw ImportFailed("列不出目录：${prefix.ifEmpty { "根目录" }}（${it.message ?: "读取失败"}）")
        }
        for (child in children) {
            if (cancelled) throw ImportCancelled()
            val name = child.name ?: continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                if (SKIP_DIRS.contains(name.lowercase(Locale.ROOT))) {
                    state.skippedDirs++
                    continue
                }
                walkSourceForImport(child, rel, depth + 1, out, state)
            } else if (child.isFile) {
                out.add(SourceFile(rel, uri = child.uri, mtime = child.lastModified()))
                state.bytes += child.length()
                if (out.size > MAX_FILES) {
                    throw ImportFailed("文件太多（超过 $MAX_FILES 个），先清理一下再选")
                }
                if (state.bytes > MAX_TOTAL_BYTES) {
                    throw ImportFailed("体积太大（超过 400MB），先清理一下再选")
                }
            }
        }
    }

    private fun fail(reason: String): Map<String, Any?> = mapOf("ok" to false, "error" to reason)

    private fun walkFileForZip(
        dir: File,
        prefix: String,
        depth: Int,
        out: MutableList<SourceFile>,
    ) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            val name = child.name ?: continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                if (SKIP_DIRS.contains(name.lowercase(Locale.ROOT))) continue
                if (depth == 0 && name == APP_META_DIR) continue
                walkFileForZip(child, rel, depth + 1, out)
            } else if (child.isFile) {
                out.add(SourceFile(rel, file = child, mtime = child.lastModified()))
            }
        }
    }

    private fun walkSafForZip(
        dir: DocumentFile,
        prefix: String,
        depth: Int,
        out: MutableList<SourceFile>,
    ) {
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrElse {
            throw ImportFailed("列不出目录：$prefix（${it.message ?: "读取失败"}）")
        }
        for (child in children) {
            val name = child.name ?: continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                if (SKIP_DIRS.contains(name.lowercase(Locale.ROOT))) continue
                if (depth == 0 && name == APP_META_DIR) continue
                walkSafForZip(child, rel, depth + 1, out)
            } else if (child.isFile) {
                out.add(SourceFile(rel, uri = child.uri, mtime = child.lastModified()))
            }
        }
    }

    /**
     * 私有目录名：`<安全化后的显示名>-<来源路径 hash4>`。
     *
     * 带 hash 是必要的：两个不同文件夹可能同叫 `app`（`~/a/app` 与 `~/b/app`），
     * 只按名字建目录会**并成一份**，用户以为选的是另一个项目。
     */
    private fun destName(displayName: String, sourcePath: String): String {
        val cleaned = displayName.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-")
            .trim('-', '.')
            .take(40)
        val base = cleaned.ifBlank { "workspace" }
        val h = (sourcePath.hashCode().toLong() and 0xffffffffL)
            .toString(16)
            .padStart(8, '0')
            .takeLast(4)
        return "$base-$h"
    }

    /** 撞名时依次加 `-2`、`-3`（绝不覆盖已有副本：那是用户的数据）。 */
    private fun uniqueDir(parent: File, name: String): File {
        var candidate = File(parent, name)
        var i = 2
        while (candidate.exists() && i < 100) {
            candidate = File(parent, "$name-$i")
            i++
        }
        return candidate
    }

    private fun deleteTree(f: File) {
        runCatching {
            if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
            f.delete()
        }
    }

    private fun humanBytes(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fGB", n / 1024.0 / 1024 / 1024)
        n >= 1024L * 1024 -> String.format(Locale.US, "%.1fMB", n / 1024.0 / 1024)
        n >= 1024L -> String.format(Locale.US, "%.0fKB", n / 1024.0)
        else -> "${n}B"
    }
}
