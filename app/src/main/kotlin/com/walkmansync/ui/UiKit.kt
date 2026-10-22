package com.walkmansync.ui

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import desktopkit.components.DeskButton
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.MenuEntry
import desktopkit.components.MenuPopup
import desktopkit.theme.DeskTheme

// desktop-kit'te olmayan bileşenler Material3 ile, renkler DeskTheme belirteçlerinden: onay kutusu,
// ilerleme çubuğu, iletişim kutusu, metin alanı; ayrıca ekranlar arası ortak parçalar.

/** Uygulama kaynakları; yapılandırma değişince (dil, yazı boyutu) çağıran yeniden çizilir. */
@Composable
@ReadOnlyComposable
@Suppress("UNUSED_EXPRESSION")
fun appResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

/** Material3 onay kutusu, DeskTheme renklerinde. */
@Composable
fun DeskCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = DeskTheme.colors
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = colors.accent,
            uncheckedColor = colors.borderStrong,
            checkmarkColor = colors.onAccent,
        ),
    )
}

/** Üç durumlu onay kutusu: [state] 0 hiçbiri, 1 bazıları, 2 hepsi. */
@Composable
fun DeskTriCheckbox(state: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = DeskTheme.colors
    TriStateCheckbox(
        state = when (state) {
            0 -> ToggleableState.Off
            2 -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        },
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = colors.accent,
            uncheckedColor = colors.borderStrong,
            checkmarkColor = colors.onAccent,
        ),
    )
}

/** Material3 ilerleme çubuğu; [fraction] null ise belirsiz (dönen) çubuk. */
@Composable
fun DeskProgressBar(fraction: Float?, modifier: Modifier = Modifier, color: Color = DeskTheme.colors.accent) {
    val colors = DeskTheme.colors
    val barModifier = modifier.fillMaxWidth().height(6.dp)
    if (fraction == null) {
        LinearProgressIndicator(modifier = barModifier, color = color, trackColor = colors.surfaceRaised)
    } else {
        val value = fraction.coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { value }, modifier = barModifier, color = color, trackColor = colors.surfaceRaised)
    }
}

/** Material3 onay penceresi; düğmeler kit düğmeleridir. */
@Composable
fun DeskDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DeskTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { DeskButton(confirmLabel, onConfirm, primary = true) },
        dismissButton = { DeskButton(dismissLabel, onDismiss) },
        title = { Text(title, style = DeskTheme.type.title, color = colors.text) },
        text = { Text(text, style = DeskTheme.type.body, color = colors.textSecondary) },
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
    )
}

/** Tek satır metin süzgeci. */
@Composable
fun FilterTextField(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusControl)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = DeskTheme.type.body.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier,
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(dimens.controlHeight.coerceAtLeast(dimens.minTarget))
                    .background(colors.surface, shape)
                    .border(1.dp, colors.borderStrong, shape)
                    .padding(horizontal = dimens.gapM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(DeskIcons.Search, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(dimens.icon))
                Spacer(Modifier.width(dimens.gapS))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, style = DeskTheme.type.body, color = colors.textTertiary, maxLines = 1)
                    field()
                }
            }
        },
    )
}

/** Menü açan düğme; giriş listesi her açılışta üretilir. */
@Composable
fun MenuButton(text: String, entries: () -> List<MenuEntry>, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    var open by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    Box(modifier.onSizeChanged { height = it.height }) {
        DeskButton(text = text, onClick = { open = true }, icon = icon)
        MenuPopup(
            visible = open,
            onDismiss = { open = false },
            entries = if (open) entries() else emptyList(),
            offset = IntOffset(0, height + 2),
        )
    }
}

/** Kenarlıklı kart yüzeyi. */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusCard)
    Column(
        modifier = modifier
            .background(colors.surface, shape)
            .border(1.dp, colors.border, shape)
            .padding(dimens.gapL),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS),
        content = content,
    )
}

/** Etiket + değer satırı. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color = DeskTheme.colors.text,
    maxLines: Int = 2,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = DeskTheme.type.body,
            color = DeskTheme.colors.textTertiary,
            maxLines = 2,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            style = if (mono) DeskTheme.type.mono else DeskTheme.type.body,
            color = valueColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

enum class BannerTone { Info, Warning, Error, Success }

/** Bilgi/uyarı/hata şeridi; isteğe bağlı eylem düğmesi ve kapatma. */
@Composable
fun StatusBanner(
    tone: BannerTone,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    closeLabel: String? = null,
    onClose: (() -> Unit)? = null,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val color = when (tone) {
        BannerTone.Info -> colors.accentText
        BannerTone.Warning -> colors.warning
        BannerTone.Error -> colors.danger
        BannerTone.Success -> colors.success
    }
    val icon = when (tone) {
        BannerTone.Info -> DeskIcons.Info
        BannerTone.Warning -> WalkmanIcons.StatusConflict
        BannerTone.Error -> WalkmanIcons.StatusError
        BannerTone.Success -> WalkmanIcons.StatusDone
    }
    val shape = RoundedCornerShape(dimens.radiusControl)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), shape)
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(horizontal = dimens.gapM, vertical = dimens.gapS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.gapM),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(dimens.iconLarge))
        Text(text, style = DeskTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) DeskButton(actionLabel, onAction)
        if (closeLabel != null && onClose != null) DeskIconButton(DeskIcons.Close, closeLabel, onClose)
    }
}
