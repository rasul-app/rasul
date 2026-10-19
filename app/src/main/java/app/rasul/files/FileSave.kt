package app.rasul.files

import app.rasul.R
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** How copying a received file to a place the user picked ended. */
internal enum class SaveResult(val message: Int) {
    Saved(R.string.file_saved),

    /** The cached copy is gone or unreadable. */
    SourceMissing(R.string.file_gone),

    /** The picked place could not be opened for writing. */
    TargetUnavailable(R.string.file_save_failed),

    /** Writing stopped part way, usually for lack of space. */
    WriteFailed(R.string.file_save_err_write),
}

/**
 * Copies [source] into the stream [openTarget] returns. When writing stops part way ([SaveResult.WriteFailed]),
 * [discard] removes the document the user picked so no half-written file is left (its own failure is ignored). A save
 * that never started leaves the document alone: the picker may have returned an existing file the user chose to
 * overwrite. Blocking: call it off the main thread.
 */
internal fun saveCopy(source: File, discard: () -> Unit = {}, openTarget: () -> OutputStream?): SaveResult {
    val result = copyTo(source, openTarget)
    if (result == SaveResult.WriteFailed) runCatching { discard() }
    return result
}

private fun copyTo(source: File, openTarget: () -> OutputStream?): SaveResult {
    val input = try { source.inputStream() } catch (_: IOException) { return SaveResult.SourceMissing }
    input.use { from ->
        val target = try { openTarget() } catch (_: IOException) { null } catch (_: SecurityException) { null }
            ?: return SaveResult.TargetUnavailable
        try {
            target.use { to ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = try { from.read(buffer) } catch (_: IOException) { return SaveResult.SourceMissing }
                    if (n < 0) break
                    to.write(buffer, 0, n)
                }
                to.flush()
            }
        } catch (_: IOException) {
            return SaveResult.WriteFailed
        }
    }
    return SaveResult.Saved
}
