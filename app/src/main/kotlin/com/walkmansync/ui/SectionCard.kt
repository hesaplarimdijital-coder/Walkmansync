package com.walkmansync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import desktopkit.components.DeskCard
import desktopkit.theme.DeskTheme

/**
 * Ekranlardaki kart: kitin [DeskCard]'ı, ekranların düzeni değişmesin diye her yanda gapL iç boşlukla ve öğeleri
 * arasında gapS aralıkla (kitin MIGRATION.md'sindeki "eski kartın görünümü"). Kitin
 * [desktopkit.components.KeyValueRow]'u kendi dikey boşluğunu getirir; art arda gelenler aralıksız bir Column'da
 * toplanır.
 */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dimens = DeskTheme.dimens
    DeskCard(
        modifier = modifier,
        contentPadding = PaddingValues(dimens.gapL),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS),
        content = content,
    )
}
