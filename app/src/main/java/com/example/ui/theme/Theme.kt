package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class ClearScanExtendedColors(
  val ocrHighlight: Color,
  val ocrHighlightBorder: Color,
  val searchMatchHighlight: Color,
  val searchMatchBorder: Color,
  val viewfinderBackground: Color,
  val viewfinderOverlay: Color,
  val viewfinderReticle: Color,
  val successBadge: Color,
  val onSuccessBadge: Color,
)

val LocalClearScanColors = staticCompositionLocalOf {
  ClearScanExtendedColors(
    ocrHighlight = Color(0x33059669),
    ocrHighlightBorder = SignalEmerald,
    searchMatchHighlight = Color(0x66FBBF24),
    searchMatchBorder = BatchAmber,
    viewfinderBackground = ViewfinderCanvas,
    viewfinderOverlay = ViewfinderScrim,
    viewfinderReticle = ViewfinderGuide,
    successBadge = SignalEmerald,
    onSuccessBadge = PurePaper,
  )
}

private val LightColorScheme = lightColorScheme(
  primary = CobaltBlue,
  onPrimary = PurePaper,
  primaryContainer = CobaltContainer,
  onPrimaryContainer = OnCobaltContainer,
  secondary = SignalEmerald,
  onSecondary = PurePaper,
  secondaryContainer = EmeraldContainer,
  onSecondaryContainer = OnEmeraldContainer,
  tertiary = BatchAmber,
  onTertiary = PurePaper,
  tertiaryContainer = AmberContainer,
  onTertiaryContainer = OnAmberContainer,
  background = IvoryPaper,
  onBackground = InkSlate,
  surface = PurePaper,
  onSurface = InkSlate,
  surfaceVariant = WarmSurfaceVariant,
  onSurfaceVariant = MutedSlate,
  outline = HairlineBorder,
  outlineVariant = HairlineBorder,
  error = ErrorCrimson,
  onError = PurePaper,
  errorContainer = ErrorContainerLight,
)

private val DarkColorScheme = darkColorScheme(
  primary = SkyCobalt,
  onPrimary = ObsidianStudio,
  primaryContainer = DarkCobaltContainer,
  onPrimaryContainer = CobaltContainer,
  secondary = MintEmerald,
  onSecondary = ObsidianStudio,
  secondaryContainer = DarkEmeraldContainer,
  onSecondaryContainer = EmeraldContainer,
  tertiary = WarmGold,
  onTertiary = ObsidianStudio,
  tertiaryContainer = DarkAmberContainer,
  onTertiaryContainer = AmberContainer,
  background = ObsidianStudio,
  onBackground = SoftPaperText,
  surface = DarkSurface,
  onSurface = SoftPaperText,
  surfaceVariant = DarkSurfaceVariant,
  onSurfaceVariant = MutedDarkText,
  outline = DarkBorder,
  outlineVariant = DarkBorder,
  error = SoftCrimson,
  onError = ObsidianStudio,
)

val ClearScanShapes = Shapes(
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(22.dp),
  extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ClearScanTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  val extendedColors = if (darkTheme) {
    ClearScanExtendedColors(
      ocrHighlight = Color(0x4034D399),
      ocrHighlightBorder = MintEmerald,
      searchMatchHighlight = Color(0x66FBBF24),
      searchMatchBorder = WarmGold,
      viewfinderBackground = ViewfinderCanvas,
      viewfinderOverlay = ViewfinderScrim,
      viewfinderReticle = ViewfinderGuide,
      successBadge = MintEmerald,
      onSuccessBadge = ObsidianStudio,
    )
  } else {
    ClearScanExtendedColors(
      ocrHighlight = Color(0x33059669),
      ocrHighlightBorder = SignalEmerald,
      searchMatchHighlight = Color(0x66FBBF24),
      searchMatchBorder = BatchAmber,
      viewfinderBackground = ViewfinderCanvas,
      viewfinderOverlay = ViewfinderScrim,
      viewfinderReticle = ViewfinderGuide,
      successBadge = SignalEmerald,
      onSuccessBadge = PurePaper,
    )
  }

  CompositionLocalProvider(LocalClearScanColors provides extendedColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = ClearScanTypography,
      shapes = ClearScanShapes,
      content = content,
    )
  }
}
