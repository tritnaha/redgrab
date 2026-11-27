package app.redgrab

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Clips kept inside the app: a folder in the app's own storage (files/clips), which no gallery,
 * file manager or computer on a cable can list or open.
 *
 * The rest of the app reaches its save folder through Android's document interface (the same
 * one a folder chosen with the system picker uses), so this is that interface over the private
 * folder: switching to private storage is a matter of pointing the save folder at [TREE].
 *
 * Android requires a provider of this kind to be "exported" under a permission only the system's
 * own Files app holds. It offers no roots, so it shows up in no picker and no browser; and no
 * other app can address it. Document IDs are paths under "clips" ("clips/abc-hd.mp4").
 */
class PrivateDocs : DocumentsProvider() {

    override fun onCreate() = true

    private fun base(): File = dir(context!!)

    /** The file for a document ID; anything that would leave the folder is refused. */
    private fun file(docId: String): File {
        val root = base()
        if (docId == ROOT_ID) return root
        if (!docId.startsWith("$ROOT_ID/")) throw FileNotFoundException("Not a document here")
        val f = File(root, docId.removePrefix("$ROOT_ID/"))
        val inside = f.canonicalPath.startsWith(root.canonicalPath + File.separator)
        if (!inside) throw FileNotFoundException("Not a document here")
        return f
    }

    private fun idOf(f: File): String {
        val root = base().canonicalPath
        val p = f.canonicalPath
        return if (p == root) ROOT_ID else ROOT_ID + "/" + p.removePrefix(root + File.separator)
    }

    private fun row(c: MatrixCursor, f: File) {
        val dirFlags = Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        val fileFlags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(f))
            .add(Document.COLUMN_DISPLAY_NAME, f.name)
            .add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else mime(f.name))
            .add(Document.COLUMN_SIZE, if (f.isDirectory) null else f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, if (f.isDirectory) dirFlags else fileFlags)
    }

    /** No roots: nothing here is offered to any picker or file browser. */
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val f = file(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        return MatrixCursor(projection ?: COLUMNS).also { row(it, f) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: COLUMNS)
        file(parentDocumentId).listFiles()?.forEach { row(c, it) }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun getDocumentType(documentId: String): String = file(documentId).let { if (it.isDirectory) Document.MIME_TYPE_DIR else mime(it.name) }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = file(documentId)
        if ('w' !in mode && !f.exists()) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentDocumentId)
        // The name is used as given: the app names its files itself and relies on getting that name.
        val name = displayName.replace('/', '_').replace('\\', '_').takeIf { it.isNotBlank() && it != "." && it != ".." } ?: throw FileNotFoundException("Bad name")
        val f = File(parent, name)
        if (mimeType == Document.MIME_TYPE_DIR) { if (!f.isDirectory && !f.mkdirs()) throw FileNotFoundException("Could not create $name") }
        else if (!f.exists() && !f.createNewFile()) throw FileNotFoundException("Could not create $name")
        return idOf(f)
    }

    override fun deleteDocument(documentId: String) {
        val f = file(documentId)
        if (documentId == ROOT_ID) throw FileNotFoundException("The folder itself stays")
        if (!f.deleteRecursively()) throw FileNotFoundException("Could not delete ${f.name}")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = file(documentId)
        val to = File(f.parentFile, displayName.replace('/', '_').replace('\\', '_'))
        if (to.exists() || !f.renameTo(to)) throw FileNotFoundException("Could not rename ${f.name}")
        return idOf(to)
    }

    companion object {
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".private"
        const val ROOT_ID = "clips"
        /** The save folder to use for "inside the app". */
        val TREE: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

        /** The private folder itself, for code that copies files in or out directly. */
        fun dir(ctx: Context): File = File(ctx.filesDir, ROOT_ID).apply { mkdirs() }

        fun isPrivate(uri: Uri?): Boolean = uri?.authority == AUTHORITY

        private fun mime(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
