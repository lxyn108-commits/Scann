package com.example.processing

import com.example.domain.model.DraftPage
import com.example.domain.model.EnhancementFilter
import com.example.domain.model.NormalizedCropRect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ScanPreset(
  val label: String,
  val filter: EnhancementFilter,
  val titlePrefix: String,
) {
  DOCUMENT("Dokumen", EnhancementFilter.MAGIC_COLOR, "Dokumen"),
  RECEIPT("Kartu / Struk", EnhancementFilter.CRISP_BW, "Kartu"),
  NOTES("Catatan", EnhancementFilter.GRAYSCALE, "Catatan"),
  COLOR("Asli", EnhancementFilter.ORIGINAL, "Scan"),
}

sealed class PageSuggestion(
  open val label: String,
  open val reason: String,
) {
  data class ApplyFilter(
    override val label: String,
    override val reason: String,
    val filter: EnhancementFilter,
  ) : PageSuggestion(label, reason)

  data class AutoTrimBorders(
    override val label: String,
    override val reason: String,
    val cropRect: NormalizedCropRect,
  ) : PageSuggestion(label, reason)

  data class RotatePage(
    override val label: String,
    override val reason: String,
  ) : PageSuggestion(label, reason)
}

enum class DocumentActionType {
  EMAIL,
  URL,
  PHONE,
  AMOUNT,
}

data class DocumentActionSuggestion(
  val type: DocumentActionType,
  val displayLabel: String,
  val value: String,
)

object SmartScanAdvisor {
  private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
  private val urlRegex = Regex("https?://[^\\s)]+|www\\.[^\\s)]+")
  private val phoneRegex = Regex("(?:\\+?\\d{1,3}[\\s.-]?)?\\(?\\d{3,4}\\)?[\\s.-]\\d{3,4}[\\s.-]\\d{3,4}")
  private val currencyRegex = Regex("(?:USD|EUR|IDR|Rp|\\$|€)\\s?[0-9][0-9.,]+")

  fun suggestTitles(pages: List<DraftPage>): List<String> {
    if (pages.isEmpty()) return emptyList()
    val dateStamp = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())
    val candidates = linkedSetOf<String>()

    val firstPageLines = pages.firstOrNull()?.ocrResult?.lines.orEmpty()
    for (line in firstPageLines.take(6)) {
      val clean = line.text.replace(Regex("\\s+"), " ").trim()
      if (clean.length in 5..40 && clean.any { it.isLetter() }) {
        candidates.add(clean)
      }
      if (candidates.size >= 2) break
    }

    val combinedText = pages.joinToString("\n") { it.ocrResult?.formattedText.orEmpty() }
    val lower = combinedText.lowercase(Locale.ROOT)
    when {
      "invoice" in lower || "faktur" in lower -> candidates.add("Invoice • $dateStamp")
      "receipt" in lower || "struk" in lower || "total" in lower -> candidates.add("Receipt • $dateStamp")
      "contract" in lower || "agreement" in lower || "perjanjian" in lower -> candidates.add("Contract • $dateStamp")
      "certificate" in lower || "sertifikat" in lower -> candidates.add("Certificate • $dateStamp")
      "resume" in lower || "curriculum vitae" in lower -> candidates.add("Resume • $dateStamp")
    }

    candidates.add("Scan $dateStamp (${pages.size}p)")
    return candidates.take(4)
  }

  fun suggestForPage(
    page: DraftPage,
    quality: DocumentImageProcessor.ImageQualityAnalysis?,
  ): List<PageSuggestion> {
    val suggestions = mutableListOf<PageSuggestion>()
    val wordCount = page.ocrResult?.lines?.sumOf { it.words.size } ?: 0

    if (page.quad.isFullImage && quality?.suggestedTrimRect != null) {
      suggestions.add(
        PageSuggestion.AutoTrimBorders(
          label = "Rapikan Tepi",
          reason = "Tepi gelap terdeteksi di sekitar halaman",
          cropRect = quality.suggestedTrimRect,
        )
      )
    }

    if (quality != null && (quality.contrastSpread < 95f || quality.meanLuminance < 135f) &&
      page.filter != EnhancementFilter.CRISP_BW
    ) {
      suggestions.add(
        PageSuggestion.ApplyFilter(
          label = "Coba Hitam Putih",
          reason = "Kontras rendah — pertajam teks",
          filter = EnhancementFilter.CRISP_BW,
        )
      )
    } else if (page.filter == EnhancementFilter.ORIGINAL) {
      suggestions.add(
        PageSuggestion.ApplyFilter(
          label = "Tingkatkan Warna",
          reason = "Putihkan kertas & perjelas teks",
          filter = EnhancementFilter.MAGIC_COLOR,
        )
      )
    }

    if (wordCount in 0..4) {
      suggestions.add(
        PageSuggestion.RotatePage(
          label = "Rotate 90°",
          reason = "Low text detected — check orientation",
        )
      )
    }

    return suggestions.take(3)
  }

  fun extractDocumentActions(ocrText: String): List<DocumentActionSuggestion> {
    if (ocrText.isBlank()) return emptyList()
    val results = mutableListOf<DocumentActionSuggestion>()
    val seen = hashSetOf<String>()

    emailRegex.findAll(ocrText).take(2).forEach { match ->
      val v = match.value.trim()
      if (seen.add("email:$v")) {
        results.add(DocumentActionSuggestion(DocumentActionType.EMAIL, v, v))
      }
    }

    urlRegex.findAll(ocrText).take(2).forEach { match ->
      val raw = match.value.trim().trimEnd('.', ',')
      val url = if (raw.startsWith("http")) raw else "https://$raw"
      if (seen.add("url:$url")) {
        results.add(DocumentActionSuggestion(DocumentActionType.URL, raw, url))
      }
    }

    phoneRegex.findAll(ocrText).take(2).forEach { match ->
      val v = match.value.trim()
      if (v.count { it.isDigit() } >= 7 && seen.add("phone:$v")) {
        results.add(DocumentActionSuggestion(DocumentActionType.PHONE, v, v))
      }
    }

    currencyRegex.findAll(ocrText).take(2).forEach { match ->
      val v = match.value.trim()
      if (seen.add("amt:$v")) {
        results.add(DocumentActionSuggestion(DocumentActionType.AMOUNT, v, v))
      }
    }

    return results.take(5)
  }

  fun suggestLibrarySearchTags(ocrTexts: List<String>): List<String> {
    if (ocrTexts.isEmpty()) return emptyList()
    val knownCategories = listOf(
      "Invoice", "Receipt", "Contract", "Report", "Certificate",
      "Agreement", "Statement", "Tax", "Order", "Resume",
    )
    val joined = ocrTexts.joinToString("\n")
    val matchedCategories = knownCategories.filter { cat ->
      joined.contains(cat, ignoreCase = true)
    }.toMutableList()

    if (matchedCategories.size < 4) {
      val wordCounts = HashMap<String, Int>()
      val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "your", "have",
        "page", "date", "total", "document", "clearscan", "yang", "dan", "untuk",
      )
      Regex("[A-Za-z]{4,14}").findAll(joined).forEach { m ->
        val word = m.value
        val lower = word.lowercase(Locale.ROOT)
        if (lower !in stopWords) {
          wordCounts[word.replaceFirstChar { it.uppercaseChar() }] =
            (wordCounts[word.replaceFirstChar { it.uppercaseChar() }] ?: 0) + 1
        }
      }
      wordCounts.entries
        .sortedByDescending { it.value }
        .map { it.key }
        .filter { candidate -> matchedCategories.none { it.equals(candidate, ignoreCase = true) } }
        .take(5 - matchedCategories.size)
        .forEach { matchedCategories.add(it) }
    }
    return matchedCategories.take(5)
  }
}
