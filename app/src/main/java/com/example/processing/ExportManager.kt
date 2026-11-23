package com.example.processing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ExportManager {
  private fun fileProviderAuthority(context: Context): String =
    "${context.packageName}.fileprovider"

  suspend fun writeFileToUri(
    context: Context,
    sourceFile: File,
    destinationUri: Uri,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      context.contentResolver.openOutputStream(destinationUri)?.use { output ->
        sourceFile.inputStream().use { input ->
          input.copyTo(output)
        }
      } ?: error("Unable to open destination output stream.")
      Unit
    }
  }

  fun sharePdf(context: Context, pdfFile: File, title: String) {
    val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), pdfFile)
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "application/pdf"
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_SUBJECT, title)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
      Intent.createChooser(intent, "Share Searchable PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }

  fun shareImages(context: Context, imageFiles: List<File>, title: String) {
    if (imageFiles.isEmpty()) return
    val uris = ArrayList<Uri>(imageFiles.size)
    for (file in imageFiles) {
      uris.add(FileProvider.getUriForFile(context, fileProviderAuthority(context), file))
    }

    val intent = if (uris.size == 1) {
      Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uris.first())
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    } else {
      Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/jpeg"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    }
    context.startActivity(
      Intent.createChooser(intent, "Export Images").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }

  /**
   * Saves scanned page images directly to the device Pictures/ClearScan album via MediaStore
   * without requiring legacy storage permissions on Android 10+.
   */
  suspend fun exportImagesToGallery(
    context: Context,
    imageFiles: List<File>,
    baseName: String,
  ): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
      val safeBase = baseName.replace(Regex("[^A-Za-z0-9_\\- ]"), "").trim().ifBlank { "ClearScan" }
      var savedCount = 0
      for ((idx, file) in imageFiles.withIndex()) {
        val displayName = "${safeBase}_page_${idx + 1}_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
          put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ClearScan")
            put(MediaStore.Images.Media.IS_PENDING, 1)
          }
        }
        val resolver = context.contentResolver
        val itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
          ?: error("Failed to create MediaStore entry")

        resolver.openOutputStream(itemUri)?.use { output ->
          file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Failed to write image stream")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val doneValues = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
          }
          resolver.update(itemUri, doneValues, null, null)
        }
        savedCount++
      }
      savedCount
    }
  }

  fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
  }
}
