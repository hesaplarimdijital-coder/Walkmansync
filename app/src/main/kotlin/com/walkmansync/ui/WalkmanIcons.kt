package com.walkmansync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.walkmansync.core.model.AudioFormat
import desktopkit.components.DeskIcons
import desktopkit.theme.DeskTheme

// DeskIcons standardında çizgi simgeler: 16x16 ızgara, 1.6 çizgi kalınlığı, yuvarlak uçlar.
object WalkmanIcons {

    /** microSD kart: kesik köşe ve temas uçları. */
    val SdCard: ImageVector by lazy {
        DeskIcons.build(
            "SdCard",
            DeskIcons.stroke("M6.2 2h5.3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-7a1 1 0 0 1-1-1V4.7z"),
            DeskIcons.stroke("M6.2 5.4v2M8.2 5.4v2M10.2 5.4v2"),
        )
    }

    /** USB-C okuyucu (dongle): konektör, gövde ve kart yuvası. */
    val UsbCDongle: ImageVector by lazy {
        DeskIcons.build(
            "UsbCDongle",
            DeskIcons.stroke("M5.5,2H10.5A1.5,1.5 0 0 1 12,3.5A1.5,1.5 0 0 1 10.5,5H5.5A1.5,1.5 0 0 1 4,3.5A1.5,1.5 0 0 1 5.5,2Z"),
            DeskIcons.stroke("M8 5v2.5"),
            DeskIcons.stroke("M4.5,7.5H11.5A1,1 0 0 1 12.5,8.5V12.5A1,1 0 0 1 11.5,13.5H4.5A1,1 0 0 1 3.5,12.5V8.5A1,1 0 0 1 4.5,7.5Z"),
            DeskIcons.stroke("M6 10.5h4"),
        )
    }

    /** Walkman cihazı: gövde, ekran ve kontrol düğmesi. */
    val Walkman: ImageVector by lazy {
        DeskIcons.build(
            "Walkman",
            DeskIcons.stroke("M5,1.5H11A1.5,1.5 0 0 1 12.5,3V13A1.5,1.5 0 0 1 11,14.5H5A1.5,1.5 0 0 1 3.5,13V3A1.5,1.5 0 0 1 5,1.5Z"),
            DeskIcons.stroke("M5.7 4h4.6v3H5.7z"),
            DeskIcons.stroke("M6.5,11a1.5,1.5 0 1 0 3,0a1.5,1.5 0 1 0 -3,0Z"),
        )
    }

    /** Kaynaktan hedefe eşitleme (iki yönlü ok çifti). */
    val Sync: ImageVector by lazy {
        DeskIcons.build("Sync", DeskIcons.stroke("M3 5.5h9l-2.5-2.5M13 10.5H4l2.5 2.5"))
    }

    /** Aktarım: karta inen ok. */
    val Transfer: ImageVector by lazy {
        DeskIcons.build(
            "Transfer",
            DeskIcons.stroke("M8 2v6.5M5.3 6 8 8.7 10.7 6"),
            DeskIcons.stroke("M3,10.5H13A0.8,0.8 0 0 1 13.8,11.3V12.7A0.8,0.8 0 0 1 13,13.5H3A0.8,0.8 0 0 1 2.2,12.7V11.3A0.8,0.8 0 0 1 3,10.5Z"),
            DeskIcons.stroke("M11 12h.1"),
        )
    }

    /** Durum: yeni (artı). */
    val StatusNew: ImageVector by lazy {
        DeskIcons.build("StatusNew", DeskIcons.stroke("M2.5,8a5.5,5.5 0 1 0 11,0a5.5,5.5 0 1 0 -11,0Z"), DeskIcons.stroke("M8 5.5v5M5.5 8h5"))
    }

    /** Durum: atlandı (ileri ok). */
    val StatusSkipped: ImageVector by lazy {
        DeskIcons.build("StatusSkipped", DeskIcons.stroke("M2.5,8a5.5,5.5 0 1 0 11,0a5.5,5.5 0 1 0 -11,0Z"), DeskIcons.stroke("M5.5 8h5M8.5 6l2 2-2 2"))
    }

    /** Durum: çakışma / uyarı (ünlemli üçgen). */
    val StatusConflict: ImageVector by lazy {
        DeskIcons.build("StatusConflict", DeskIcons.stroke("M8 2.5 14 13H2z"), DeskIcons.stroke("M8 6.6v3M8 11.3h.1"))
    }

    /** Durum: hata (çarpılı daire). */
    val StatusError: ImageVector by lazy {
        DeskIcons.build("StatusError", DeskIcons.stroke("M2.5,8a5.5,5.5 0 1 0 11,0a5.5,5.5 0 1 0 -11,0Z"), DeskIcons.stroke("M5.8 5.8l4.4 4.4M10.2 5.8l-4.4 4.4"))
    }

    /** Durum: tamamlandı (onaylı daire). */
    val StatusDone: ImageVector by lazy {
        DeskIcons.build("StatusDone", DeskIcons.stroke("M2.5,8a5.5,5.5 0 1 0 11,0a5.5,5.5 0 1 0 -11,0Z"), DeskIcons.stroke("M5.3 8.2l2 2 3.5-4"))
    }
}

/** Fark/aktarım durumları ve simgeleri. */
enum class DiffStatus { New, Skipped, Conflict, Error, Done }

val DiffStatus.icon: ImageVector
    get() = when (this) {
        DiffStatus.New -> WalkmanIcons.StatusNew
        DiffStatus.Skipped -> WalkmanIcons.StatusSkipped
        DiffStatus.Conflict -> WalkmanIcons.StatusConflict
        DiffStatus.Error -> WalkmanIcons.StatusError
        DiffStatus.Done -> WalkmanIcons.StatusDone
    }

@Composable
fun DiffStatus.tint(): Color {
    val colors = DeskTheme.colors
    return when (this) {
        DiffStatus.New -> colors.accentText
        DiffStatus.Skipped -> colors.textTertiary
        DiffStatus.Conflict -> colors.warning
        DiffStatus.Error -> colors.danger
        DiffStatus.Done -> colors.success
    }
}

/** Durum simgesi, durumun renginde. [description] erişilebilirlik metnidir. */
@Composable
fun StatusGlyph(status: DiffStatus, description: String?, modifier: Modifier = Modifier, size: Dp = DeskTheme.dimens.icon) {
    Icon(
        imageVector = status.icon,
        contentDescription = description,
        tint = status.tint(),
        modifier = modifier.size(size),
    )
}

/** Rozette görünen kısa biçim adı (yerelleştirilmez; teknik kısaltmadır). */
fun AudioFormat.badgeLabel(): String = when (this) {
    AudioFormat.DSF, AudioFormat.DFF -> "DSD"
    AudioFormat.COVER -> "COVER"
    else -> name
}

private enum class BadgeClass { Lossless, HiRes, Lossy, Auxiliary }

private fun AudioFormat.badgeClass(): BadgeClass = when (this) {
    AudioFormat.FLAC, AudioFormat.ALAC, AudioFormat.WAV -> BadgeClass.Lossless
    AudioFormat.DSF, AudioFormat.DFF -> BadgeClass.HiRes
    AudioFormat.MP3, AudioFormat.M4A, AudioFormat.OGG, AudioFormat.OPUS, AudioFormat.AAC -> BadgeClass.Lossy
    AudioFormat.LRC, AudioFormat.M3U8, AudioFormat.COVER -> BadgeClass.Auxiliary
}

/** Ses biçimi rozeti: kayıpsız (yeşil), DSD (vurgu), kayıplı (gri), yardımcı dosya (sarı). */
@Composable
fun FormatBadge(format: AudioFormat, modifier: Modifier = Modifier) {
    val colors = DeskTheme.colors
    val tone = when (format.badgeClass()) {
        BadgeClass.Lossless -> colors.success
        BadgeClass.HiRes -> colors.accentText
        BadgeClass.Lossy -> colors.textSecondary
        BadgeClass.Auxiliary -> colors.warning
    }
    val shape = RoundedCornerShape(DeskTheme.dimens.radiusSmall)
    Box(
        modifier = modifier
            .background(tone.copy(alpha = 0.12f), shape)
            .border(1.dp, tone.copy(alpha = 0.55f), shape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = format.badgeLabel(),
            style = DeskTheme.type.caption,
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
