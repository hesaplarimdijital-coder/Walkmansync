package com.walkmansync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import desktopkit.components.DeskCard
import desktopkit.theme.DeskTheme

/**
 * Ekranlardaki kart: kitin [DeskCard]'ı, ekranların düzeni değişmesin diye gapL iç boşlukla ve öğeleri arasında
 * gapS aralıkla (kitin örnek uygulamasındaki bölüm kartı gibi). Kitin [desktopkit.components.KeyValueRow]'u kendi
 * dikey boşluğunu getirir; art arda gelenler aralıksız bir Column'da toplanır.
 */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dimens = DeskTheme.dimens
    DeskCard(modifier, contentPadding = PaddingValues(dimens.gapL)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dimens.gapS), content = content)
    }
}
