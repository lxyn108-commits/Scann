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

  data class AutoStraighten(
    override val label: String,
    override val reason: String,
  ) : PageSuggestion(label, reason)

  data class EnableShadowRemoval(
    override val label: String,
    override val reason: String,
  ) : PageSuggestion(label, reason)

  data class RotatePage(
    override val label: String,
    override val reason: String,
    val quarterTurns: Int = 1,
  ) : PageSuggestion(label, reason)
}

enum class CameraCoachAction {
  NONE,
  ENABLE_TORCH,
}

data class CameraCoachHint(
  val message: String,
  val action: CameraCoachAction = CameraCoachAction.NONE,
)

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
  private val phoneRegex = Regex("(?:\\+?\\d{1,3}[\\s.-]?)?(?:\\(\\d{2,5}\\)|0\\d{2,4}|\\d{3,4})[\\s.-]?\\d{3,4}(?:[\\s.-]?\\d{3,5})?")
  private val dateLikeRegex = Regex("^\\d{4}[-/.]\\d{2}[-/.]\\d{2}$|^\\d{2}[-/.]\\d{2}[-/.]\\d{4}$")
  private val currencyRegex = Regex("(?:USD|EUR|IDR|Rp|\\$|€)\\s?[0-9][0-9.,]+")
  private val maskedOrNoiseChars = setOf('*', '|', '~', '^', '_', '[', ']', '{', '}', '<', '>')

  internal fun isCleanTitleCandidate(raw: String): Boolean {
    val clean = raw.replace(Regex("\\s+"), " ").trim()
    if (clean.length !in 5..40) return false
    if (clean.any { it in maskedOrNoiseChars }) return false
    if (clean.startsWith('-') || clean.endsWith('-') || clean.endsWith('—')) return false

    val nonSpace = clean.filterNot { it.isWhitespace() }
    if (nonSpace.isEmpty()) return false
    val letterCount = nonSpace.count { it.isLetter() }
    if (letterCount < 4) return false

    val letterRatio = letterCount.toFloat() / nonSpace.length.toFloat()
    if (letterRatio < 0.55f) return false

    val words = clean.split(' ').filter { token -> token.any { it.isLetter() } }
    if (words.isEmpty()) return false
    // Avoid OCR artifacts where a stray digit glues onto uppercase text (e.g. "1DLA MOND-")
    if (words.any { it.first().isDigit() && it.count(Char::isLetter) >= 2 && it.count(Char::isDigit) == 1 }) {
      return false
    }
    // Avoid fragmented single tiny OCR tokens (e.g., "Rec", "Telp") while keeping real titles/brands
    if (words.size == 1 && words[0].length < 6) return false
    return true
  }

  fun suggestTitles(pages: List<DraftPage>): List<String> {
    if (pages.isEmpty()) return emptyList()
    val dateStamp = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date())
    val candidates = linkedSetOf<String>()

    val firstPageLines = pages.firstOrNull()?.ocrResult?.lines.orEmpty()
    val scoredLines = firstPageLines.take(10).mapIndexedNotNull { idx, line ->
      val clean = line.text.replace(Regex("\\s+"), " ").trim()
      if (!isCleanTitleCandidate(clean)) return@mapIndexedNotNull null
      val wordCount = clean.split(' ').count { tok -> tok.count { it.isLetter() } >= 2 }
      val positionBonus = (10 - idx) * 2
      val wordBonus = (wordCount.coerceAtMost(4)) * 5
      val score = positionBonus + wordBonus
      clean to score
    }.sortedByDescending { it.second }

    for ((line, _) in scoredLines) {
      candidates.add(line)
      if (candidates.size >= 2) break
    }

    val combinedText = pages.joinToString("\n") { it.ocrResult?.formattedText.orEmpty() }
    val lower = combinedText.lowercase(Locale.ROOT)
    when {
      "invoice" in lower || "faktur" in lower -> candidates.add("Invoice • $dateStamp")
      "receipt" in lower || "struk" in lower || "total" in lower || "pembelian" in lower ->
        candidates.add("Struk • $dateStamp")
      "contract" in lower || "agreement" in lower || "perjanjian" in lower ->
        candidates.add("Kontrak • $dateStamp")
      "certificate" in lower || "sertifikat" in lower -> candidates.add("Sertifikat • $dateStamp")
      "resume" in lower || "curriculum vitae" in lower -> candidates.add("CV • $dateStamp")
    }

    candidates.add("Scan $dateStamp (${pages.size} hal)")
    return candidates.take(4)
  }

  fun suggestForPage(
    page: DraftPage,
    quality: DocumentImageProcessor.ImageQualityAnalysis?,
  ): List<PageSuggestion> {
    val suggestions = mutableListOf<PageSuggestion>()
    val lines = page.ocrResult?.lines.orEmpty()
    val wordCount = lines.sumOf { it.words.size }
    val lowerText = page.ocrResult?.formattedText.orEmpty().lowercase(Locale.ROOT)

    if (quality?.suggestedTrimRect != null) {
      if (page.quad.isFullImage) {
        suggestions.add(
          PageSuggestion.AutoTrimBorders(
            label = "Rapikan Tepi",
            reason = "Tepi meja terdeteksi di pinggir halaman",
            cropRect = quality.suggestedTrimRect,
          )
        )
      } else {
        suggestions.add(
          PageSuggestion.AutoStraighten(
            label = "Luruskan Ulang",
            reason = "Sudut dokumen bisa dipaskan lebih rapi",
          )
        )
      }
    }

    if (!page.removeShadowAndGlare) {
      suggestions.add(
        PageSuggestion.EnableShadowRemoval(
          label = "Hapus Bayangan",
          reason = "Hilangkan bayangan, lipatan & pantulan cahaya",
        )
      )
    }

    val quarterTurnsNeeded = detectNeededQuarterTurns(lines, wordCount)
    if (quarterTurnsNeeded != null) {
      val deg = quarterTurnsNeeded * 90
      suggestions.add(
        PageSuggestion.RotatePage(
          label = "Putar $deg°",
          reason = if (wordCount <= 4) "Teks sedikit — periksa orientasi" else "Teks terdeteksi miring $deg°",
          quarterTurns = quarterTurnsNeeded,
        )
      )
    }

    val isReceiptLike = "total" in lowerText || "struk" in lowerText ||
      "kembalian" in lowerText || "tunai" in lowerText || "nota" in lowerText

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
          label = "Warna Scanner",
          reason = "Putihkan kertas & perjelas teks",
          filter = EnhancementFilter.MAGIC_COLOR,
        )
      )
    } else if (isReceiptLike && page.filter != EnhancementFilter.CRISP_BW &&
      quality != null && quality.contrastSpread < 120f
    ) {
      suggestions.add(
        PageSuggestion.ApplyFilter(
          label = "Hitam Putih Struk",
          reason = "Optimalkan teks struk termal agar ekstra tajam",
          filter = EnhancementFilter.CRISP_BW,
        )
      )
    }

    return suggestions.take(4)
  }

  private fun detectNeededQuarterTurns(lines: List<com.example.domain.model.OcrLineBox>, wordCount: Int): Int? {
    if (lines.size >= 2) {
      val angles = lines.map { it.angleDegrees }.sorted()
      val medianAngle = angles[angles.size / 2]
      return when {
        medianAngle in -115f..-65f -> 1 // text tilted -90deg (left-to-bottom), rotate +90deg clockwise
        medianAngle in 65f..115f -> 3 // text tilted +90deg, rotate +270deg clockwise
        kotlin.math.abs(medianAngle) >= 155f -> 2 // upside-down text
        else -> null
      }
    }
    return if (wordCount in 0..4) 1 else null
  }

  fun suggestCameraCoaching(
    quad: com.example.domain.model.NormalizedQuad?,
    meanLuma: Float,
    isTorchOn: Boolean,
    edgeClipped: Boolean,
    stabilityProgress: Float,
    liveOcrLines: Int,
  ): CameraCoachHint? {
    return when {
      meanLuma < 74f && !isTorchOn -> CameraCoachHint(
        message = "Cahaya redup • Nyalakan senter",
        action = CameraCoachAction.ENABLE_TORCH,
      )
      edgeClipped -> CameraCoachHint(
        message = "Mundur sedikit agar 4 sudut terlihat",
      )
      quad != null && stabilityProgress in 0.25f..0.99f -> CameraCoachHint(
        message = "Tahan stabil untuk memindai otomatis…",
      )
      quad == null && liveOcrLines >= 3 -> CameraCoachHint(
        message = "Teks terbaca • Arahkan seluruh kertas ke dalam bingkai",
      )
      else -> null
    }
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

    phoneRegex.findAll(ocrText).forEach { match ->
      if (results.count { it.type == DocumentActionType.PHONE } >= 2) return@forEach
      val v = match.value.trim()
      val digits = v.filter { it.isDigit() }
      val looksLikeDate = dateLikeRegex.matches(v)
      val hasPhoneSignal = v.contains('(') || v.startsWith("+") || v.startsWith("0")
      if (!looksLikeDate && hasPhoneSignal && digits.length in 8..15 && seen.add("phone:$digits")) {
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
      "Invoice", "Receipt", "Faktur", "Struk", "Contract", "Report",
      "Certificate", "Agreement", "Statement", "Tax", "Order", "Resume",
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
        "dengan", "dari", "atau", "sudah", "dapat", "harga", "jumlah", "telp",
      )
      Regex("\\b[A-Za-z]{4,14}\\b").findAll(joined).forEach { m ->
        val word = m.value
        val lower = word.lowercase(Locale.ROOT)
        if (lower !in stopWords) {
          val cap = word.replaceFirstChar { it.uppercaseChar() }
          wordCounts[cap] = (wordCounts[cap] ?: 0) + 1
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
