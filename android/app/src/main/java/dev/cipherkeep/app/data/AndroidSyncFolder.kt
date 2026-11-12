package dev.cipherkeep.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Strict SAF writer for opaque sync objects. No plaintext is interpreted. */
class AndroidSyncFolder(private val context: Context, private val tree: Uri) {
    private val resolver = context.contentResolver

    init {
        require(tree.scheme == "content") { "tree uri must be a content uri" }
    }

    fun probe(): Boolean = runCatching {
        val document = DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
            "application/octet-stream",
            "cipherkeep-probe",
        ) ?: return false
        resolver.openOutputStream(document, "wt")?.use { it.write(byteArrayOf(0)) }
        DocumentsContract.deleteDocument(resolver, document)
        true
    }.getOrDefault(false)

    fun write(vaultId: String, name: String, bytes: ByteArray): Boolean = runCatching {
        require(VALID_VAULT.matches(vaultId))
        require(VALID_NAME.matches(name))
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val temporary = DocumentsContract.createDocument(
            resolver,
            parent,
            "application/octet-stream",
            "$name.tmp",
        ) ?: return false
        resolver.openOutputStream(temporary, "wt")?.use { stream ->
            stream.write(bytes)
            stream.flush()
        } ?: return false
        val renamed = DocumentsContract.renameDocument(resolver, temporary, name) ?: temporary
        renamed != null
    }.getOrDefault(false)

    fun delete(vaultId: String, name: String): Boolean = runCatching {
        require(VALID_VAULT.matches(vaultId))
        require(VALID_NAME.matches(name))
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    return DocumentsContract.deleteDocument(resolver, uri)
                }
            }
        }
        false
    }.getOrDefault(false)

    private companion object {
        const val MAX_BYTES = 64 * 1024 * 1024
        val VALID_VAULT = Regex("^[a-f0-9]{32}$")
        val VALID_NAME = Regex("^(manifest-[a-f0-9]{32}\\.json|[a-f0-9]{32}-\\d+\\.blob)$")
    }
}
