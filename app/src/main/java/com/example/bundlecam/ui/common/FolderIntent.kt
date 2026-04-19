package com.example.bundlecam.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast

private const val TAG = "Recon/FolderIntent"

/**
 * Launch the system file browser rooted at [treeUri]. Hoisted out of the capture screen
 * so both the capture header and the bundle-preview screen can share one implementation.
 * Failure is user-visible (toast) but never fatal — e.g. a device without any DocumentsUI
 * activity shouldn't crash us.
 */
fun openFolderInSystemBrowser(context: Context, treeUri: Uri) {
    try {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(docUri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity handles folder-view intent", e)
        Toast.makeText(
            context,
            "No file browser app available to open this folder",
            Toast.LENGTH_SHORT,
        ).show()
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to open folder $treeUri", e)
        Toast.makeText(
            context,
            "Couldn't open folder: ${e.message}",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
