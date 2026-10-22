# desktop-kit API özeti (v0.1.3)

Kaynak: /tmp/desktop-kit (kit/src/main/java/desktopkit/**). İmzalar kaynaktan birebir; yalnızca public olanlar. Derleyici yok: burada olmayan bir şeyi UYDURMA.
Paketler: `desktopkit`, `desktopkit.shell`, `desktopkit.components`, `desktopkit.input`, `desktopkit.screens`, `desktopkit.settings`, `desktopkit.theme`. Kitin kaynak sınıfı: `desktopkit.R` (örn. `desktopkit.R.string.dk_settings`).

## 1. Koordinat ve gereksinimler
```kotlin
// settings.gradle.kts: dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
implementation("com.github.hesaplarimdijital-coder:desktop-kit:0.1.3")
implementation("androidx.activity:activity-compose:1.9.3")   // kitte yalnızca implementation: uygulama kendisi eklemeli
```
- minSdk 21 (kit), compileSdk 35, Java 17. Compose açık: `buildFeatures { compose = true }` + Kotlin Compose eklentisi (`org.jetbrains.kotlin.plugin.compose`, Kotlin 2.1.0). Kit AGP 8.7.3, Compose BOM 2024.12.01, navigation-compose 2.8.4 ile derlendi.
- Kit `api` olarak verir: compose ui/graphics/foundation/material3 ve navigation-compose. Yani uygulamada `androidx.compose.material3.*` ve `androidx.navigation.compose.*` ek bağımlılık olmadan kullanılabilir.
- Uygulamanın Activity'si `androidx.activity.ComponentActivity` olmalı.

## 2. Uygulama kökü
```kotlin
@Composable fun DesktopKitRoot(
    prefs: DeskPreferences, modifier: Modifier = Modifier, fonts: DeskFonts = DeskFonts.Plex,
    colors: (DeskColors) -> DeskColors = { it }, dimens: (DeskDimens) -> DeskDimens = { it },
    type: (InputMode, DeskFonts) -> DeskType = { mode, fonts -> DeskType.forMode(mode, fonts) },
    content: @Composable (WidthClass) -> Unit)
```
Tema + yoğunluk + `WindowInsets.safeDrawing` dolgusu (klavye dahil, tüketilir) + `LocalWidthClass` sağlar. İçerik arka planı `DeskTheme.colors.background`.

`desktopkit.settings`:
```kotlin
@Composable fun rememberDeskPreferences(accents: List<Accent> = Accent.presets, defaultTheme: ThemeMode = ThemeMode.System): DeskPreferences
class DeskPreferences(prefs: SharedPreferences, val accents: List<Accent> = Accent.presets, defaultTheme: ThemeMode = ThemeMode.System, defaultAccent: Accent = accents.first()) {
  var themeMode: ThemeMode      // private set; compose state
  var accent: Accent
  var density: DensityPreference
  var sidebarCollapsed: Boolean
  fun updateThemeMode(value: ThemeMode); fun updateAccent(value: Accent)
  fun updateDensity(value: DensityPreference); fun updateSidebarCollapsed(value: Boolean)
  companion: FILE = "desktopkit"; KEY_THEME="dk_theme"; KEY_ACCENT="dk_accent"; KEY_DENSITY="dk_density"; KEY_SIDEBAR_COLLAPSED="dk_sidebar_collapsed"
}
```
Kit kendi SharedPreferences dosyasını ("desktopkit") kullanır; uygulamanın kendi ayarları ayrı tutulmalı. Uygulamanın kullanacağı alanlar: `prefs.sidebarCollapsed` + `prefs::updateSidebarCollapsed` (DesktopShell'e), geri kalanı `AppearanceSection(prefs)`/`InputSection(prefs)` içinden.

Enumlar (`desktopkit.theme`): `ThemeMode { System, Light, Dark, Oled }`, `DensityPreference { Auto, Touch, Pointer }`, `InputMode { Touch, Pointer }`.
`Accent`: `Accent.Blue/Amber/Green/Coral`, `Accent.presets`, `Accent.custom(name: String, color: Color)`, `Accent.fromName(name: String?, default: Accent = Blue, extra: List<Accent> = emptyList())`.

Sample:
```kotlin
enableEdgeToEdge()
setContent {
    val prefs = rememberDeskPreferences(accents = Accent.presets + BrandAccent)
    DesktopKitRoot(prefs, type = { mode, fonts -> DeskType.forMode(mode, fonts, pointerStep = 1f) }) { widthClass ->
        DesktopShell(spec = spec(), widthClass = widthClass, sidebarCollapsed = prefs.sidebarCollapsed,
            onSidebarCollapsedChange = prefs::updateSidebarCollapsed) { route, _ -> /* ekran */ }
    }
}
```

## 3. Kabuk (`desktopkit.shell`)
```kotlin
@Immutable data class NavItem(val route: String, val label: String, val icon: ImageVector, val shortLabel: String = label)
@Immutable data class NavSection(val title: String?, val items: List<NavItem>)   // title null = boşluk
@Immutable class ShellSpec(val appTitle: String, val sections: List<NavSection>, val footer: NavItem? = null,
    bottomBar: List<String>? = null, rail: List<String>? = null, startRoute: String? = null) {
  val items: List<NavItem>; val startRoute: String; val bottomBarRoutes: List<String> /*en çok 4*/; val railRoutes: List<String> /*varsayılan ilk 7*/
  fun item(route: String?): NavItem?; fun shown(kind: NavKind): List<NavItem>; fun overflow(kind: NavKind): List<NavItem>
  companion { const val MORE_ROUTE = "dk_more" }  // rezerve; kendi rota adın yapma
}  // init: rota tekrarı / bilinmeyen rota require ile exception atar
enum class WidthClass(val minWidthDp: Int) { Compact(0), Medium(600), Expanded(840), Large(1200), ExtraLarge(1600);
  companion { fun fromWidth(widthDp: Float): WidthClass } }
enum class NavKind { BottomBar /*Compact*/, Rail /*Medium,Expanded*/, Sidebar /*Large,ExtraLarge: menü çubuğu+kenar çubuğu*/; companion { fun forWidth(widthClass: WidthClass): NavKind } }
typealias NavBadges = Map<String, String>        // rota -> rozet metni (kenar çubuğu)
enum class ShellTransitions { Default, None }
val LocalNavKind = compositionLocalOf { NavKind.Sidebar }
val LocalWidthClass = compositionLocalOf { WidthClass.ExtraLarge }   // kabuk dışında ExtraLarge
@Composable @ReadOnlyComposable fun currentWidthClass(): WidthClass

@Stable class ShellNavigator(private val navController: NavHostController) {
  fun navigate(route: String)      // popUpTo(start){saveState}, launchSingleTop, restoreState
  fun back(): Boolean              // navController.popBackStack()
}
@Composable fun DesktopShell(
    spec: ShellSpec, widthClass: WidthClass, modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    sidebarCollapsed: Boolean = false, onSidebarCollapsedChange: (Boolean) -> Unit = {},
    badges: NavBadges = emptyMap(), brand: (@Composable (Modifier) -> Unit)? = null,
    menus: List<MenuSpec> = emptyList(), onSearch: (() -> Unit)? = null, searchHint: String? = null,
    searchShortcut: String? = "Ctrl+K", bottomBar: @Composable (NavKind) -> Unit = {},
    hideNavigationWithKeyboard: Boolean = true, transitions: ShellTransitions = ShellTransitions.Default,
    content: @Composable (route: String, navigator: ShellNavigator) -> Unit)
```
- `bottomBar` slotu içeriğin altında şerit (oynatıcı/durum çubuğu/senkron ilerlemesi için); mevcut `NavKind` alır.
- Menü çubuğu YALNIZCA `NavKind.Sidebar`'da (genişlik >= 1200 dp) görünür. `onSearch` verilirse arama alanı/düğmesi çıkar.
- "Daha fazla" ekranı (`dk_more`) kabuk tarafından otomatik eklenir.
- Sayfa içi alt ekranları (ayrıntı vb.) uygulama kendi state'iyle yönetir; kabuk yalnızca üst düzey rotaları yönetir.

`CompactTopBar` (yalnızca `WidthClass.Compact`'ta etkili; aksi halde no-op):
```kotlin
enum class TopBarBackIcon { Back, Close }
@Immutable data class TopBarAction(val icon: ImageVector, val label: String, val onClick: () -> Unit, val enabled: Boolean = true, val primary: Boolean = false)
@Composable fun CompactTopBar(title: String? = null, onBack: (() -> Unit)? = null, backIcon: TopBarBackIcon = TopBarBackIcon.Back,
    actions: List<TopBarAction> = emptyList(), showSearch: Boolean = true)
```
En çok 3 simge (4+ eylemde ilk ikisi + "⋮"). `onBack` verilirse sistem Geri hareketi de onu çağırır (ayrıca BackHandler gerekmez; Compact dışında gerekir).
```kotlin
CompactTopBar(title = file.name, onBack = onClose,
    actions = listOf(TopBarAction(DeskIcons.OpenWindow, "Open", { toast("Open") }, primary = true),
                     TopBarAction(DeskIcons.Delete, "Delete", { toast("Delete") })))
if (currentWidthClass() != WidthClass.Compact) BackHandler(onBack = onClose)   // androidx.activity.compose.BackHandler
```
Alt düzey public parçalar (gerekmedikçe kullanma): `BottomNavBar(spec, currentRoute, onSelect, modifier)`, `NavRail(spec, currentRoute, onSelect, modifier, brand)`, `Sidebar(spec, currentRoute, onSelect, collapsed, onToggleCollapsed, badges, modifier)`, `PageTopBar(title, onSearch, modifier, onBack, backIcon, actions)`, `MenuBarChrome(title, menus: @Composable RowScope.() -> Unit, search, modifier, brand)`, `SearchField(onClick, shortcut: String?, modifier, hint: String? = null)`, `KeyCap(text: String, modifier = Modifier)`, `ShellLayout(navKind, topBar, navigation, player, content, modifier)`.

## 4. Bileşenler (`desktopkit.components`)
### Basics.kt
```kotlin
@Composable fun DeskIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, selected: Boolean = false, size: Dp = DeskTheme.dimens.minTarget.coerceAtLeast(DeskTheme.dimens.controlHeight),
    iconSize: Dp = DeskTheme.dimens.icon, tint: Color = if (selected) DeskTheme.colors.onSelection else DeskTheme.colors.textSecondary)
@Composable fun DeskButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = false, enabled: Boolean = true)
@Composable fun SectionCaption(text: String, modifier: Modifier = Modifier)
@Composable fun HorizontalRule(modifier: Modifier = Modifier)   // 1 dp, fillMaxWidth
@Composable fun VerticalRule(modifier: Modifier = Modifier)     // 1 dp genişlik (yükseklik modifier ile)
```
### Interaction.kt (public yardımcılar)
`data class InteractionState(val hovered: Boolean, val pressed: Boolean, val focused: Boolean)`; `@Composable fun InteractionSource.collectState(): InteractionState`; `@Composable fun stateBackground(state, selected: Boolean = false, rest: Color = Color.Transparent): Color`; `fun Modifier.deskClickable(interactionSource: MutableInteractionSource, onClick: () -> Unit, enabled: Boolean = true, role: Role? = Role.Button, onClickLabel: String? = null)`; `@Composable fun Modifier.focusRing(state: InteractionState, shape: Shape)`.
### Choice.kt
```kotlin
@Composable fun <T> SegmentedChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier)
@Composable fun <T> SwatchChoice(options: List<Triple<T, Color, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier)
@Composable fun SettingRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, control: @Composable () -> Unit)
```
```kotlin
SegmentedChoice(listOf(1 to "Grid", 2 to "List", 3 to "Columns"), selected = choice, onSelect = { choice = it })
```
### Slider.kt
`@Composable fun DeskSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, wheelStep: Float = 0.02f, keyStep: Float = 0.01f, onValueChangeFinished: (() -> Unit)? = null)` — değer 0..1. Sample: `DeskSlider(volume, { volume = it }, Modifier.width(280.dp))`.
### SplitPane.kt
```kotlin
class SplitState(initialStartWidth: Float) { var startWidthDp: Float; companion { val Saver } }
@Composable fun rememberSplitState(initialStartWidth: Dp): SplitState      // rememberSaveable
@Composable fun HorizontalSplitPane(state: SplitState, start: @Composable () -> Unit, end: @Composable () -> Unit, modifier: Modifier = Modifier,
    minStart: Dp = 240.dp, minEnd: Dp = 280.dp, dividerLabel: String = "")   // fillMaxSize; sürükle/ok tuşları
@Composable fun TwoPane(start: @Composable () -> Unit, end: @Composable () -> Unit, startWidth: Dp, modifier: Modifier = Modifier)   // sabit, ayırıcı çizgili
```
```kotlin
HorizontalSplitPane(state = rememberSplitState(360.dp), start = { FilesTable() },
    end = { MessageScreen(title = "Details", icon = DeskIcons.Info) }, dividerLabel = "Resize panes")
```
### DenseTable.kt
```kotlin
sealed interface ColumnWidth { data class Weight(val weight: Float) : ColumnWidth; data class Fixed(val width: Dp) : ColumnWidth }
@Immutable class TableColumn<T>(val title: String, val width: ColumnWidth, val alignEnd: Boolean = false, val mono: Boolean = false,
    val sortable: Boolean = true, val textStyle: TextStyle? = null, val value: (T) -> String)   // value SON parametre: trailing lambda
@Immutable data class TableSort(val column: Int, val ascending: Boolean)     // column = TableColumn dizini
@Composable fun <T> DenseTable(
    columns: List<TableColumn<T>>, rows: List<T>, key: (T) -> Any, onRowClick: (T, Int) -> Unit, modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(), selectedKey: Any? = null, sort: TableSort? = null,
    onSortChange: ((TableSort) -> Unit)? = null, rowMenu: ((T, Int) -> List<MenuEntry>)? = null, rowMenuLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 8.dp),
    selectedKeys: Set<Any>? = null, onSelectionChange: ((Set<Any>) -> Unit)? = null, toggleOnClick: Boolean = false)
```
- Tablo SIRALAMAYI YAPMAZ: `sort` yalnızca başlıkta ok gösterir; başlığa tıklanınca `onSortChange(TableSort(index, ascending))` çağrılır (aktif kolonda yön tersler, yeni kolonda artan); `rows`'u uygulama sıralar.
- Hücre = yalnızca tek satır metin (`value(item): String`, ellipsis); özel hücre composable, simge, ilerleme çubuğu YOK. İlk kolon `bodyStrong`, diğerleri `body`, `mono=true` mono stil; `textStyle` hepsini ezer.
- Tek seçim: `selectedKey` + `onRowClick`. Çoklu seçim: `onSelectionChange` verilince açılır; `selectedKeys` uygulamada tutulur. Düz tık = o satırı seç + `onRowClick`; Ctrl/Meta+tık ekle/çıkar; Shift+tık aralık; Ctrl+A hepsi; Shift+Yukarı/Aşağı genişlet; `toggleOnClick=true` her dokunuş ekle/çıkar (`onRowClick` çağrılmaz). `key` değerleri `selectedKeys` içindeki ile aynı olmalı (Set<Any>). `key` LazyColumn anahtarıdır: tekil olmalı.
- `rowMenu(item, index)`: sağ tık/uzun basma/Menu tuşu; açılırken seçim güncellenmiş olur. Kendi `fillMaxSize()` kullanır: sınırlı yükseklik gerekir (verticalScroll/LazyColumn içine koyma).
```kotlin
DenseTable(
    columns = listOf(
        TableColumn<SampleFile>("Name", ColumnWidth.Weight(1f)) { it.name },
        TableColumn<SampleFile>("Size", ColumnWidth.Fixed(90.dp), alignEnd = true, mono = true) { it.size }),
    rows = rows, key = { it.name }, selectedKeys = selectedKeys, onSelectionChange = onSelectionChange,
    toggleOnClick = toggleOnClick, sort = sort, onSortChange = { sort = it }, onRowClick = { file, _ -> selected = file.name },
    rowMenu = { file, _ -> listOf(MenuEntry.Item("Open", { onOpen(file) }, shortcut = "Enter", icon = DeskIcons.OpenWindow),
        MenuEntry.Separator, MenuEntry.Item("Delete", {}, shortcut = "Del", icon = DeskIcons.Delete, destructive = true)) },
    rowMenuLabel = "More actions")
```
### Menus.kt
```kotlin
@Immutable sealed interface MenuEntry {
  data class Item(val label: String, val onClick: () -> Unit, val shortcut: String? = null, val icon: ImageVector? = null,
                  val enabled: Boolean = true, val checked: Boolean? = null, val destructive: Boolean = false) : MenuEntry
  data object Separator : MenuEntry }
class MenuSpec(val title: String, val entries: () -> List<MenuEntry>)
@Composable fun MenuPopup(visible: Boolean, onDismiss: () -> Unit, entries: List<MenuEntry>, offset: IntOffset = IntOffset.Zero, minWidth: Dp = 220.dp)
@Composable fun RowScope.MenuBarMenus(menus: List<MenuSpec>)
@Composable fun MenuTitle(text: String, open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onHover: () -> Unit = {})
@Composable fun rememberMenuState(): MenuState   // class MenuState { val offset: IntOffset?; val isOpen: Boolean; fun open(at: IntOffset); fun close() }
```
`checked != null` -> onay işaretli satır (true ise Check ikonu çıkar). Menü çubuğuna bağlamak için `DesktopShell(menus = ...)`; elle `MenuSpec("Dosya") { listOf(MenuEntry.Item(...)) }` de olur.
### ContextMenu.kt (`desktopkit.input`)
```kotlin
fun Modifier.contextMenuGesture(enabled: Boolean = true, onContextMenu: (Offset) -> Unit): Modifier
@Composable fun ContextMenuArea(entries: () -> List<MenuEntry>, modifier: Modifier = Modifier, enabled: Boolean = true,
    accessibilityLabel: String? = null, onOpen: (() -> Unit)? = null, content: @Composable () -> Unit)
```
### Tooltip.kt
`@Composable fun TooltipArea(text: String, modifier: Modifier = Modifier, shortcut: String? = null, delayMillis: Long = 550, content: @Composable () -> Unit)` — yalnızca fareyle. Sample: `TooltipArea("Play", shortcut = "Space") { DeskIconButton(DeskIcons.Play, "Play", {}) }`.

## 5. Hazır ekranlar ve ayarlar
`desktopkit.screens`:
```kotlin
@Composable fun PageHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {})
@Composable fun MessageScreen(title: String, icon: ImageVector, modifier: Modifier = Modifier, pageTitle: String? = null,
    description: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null)   // boş durum/hata/yakında
@Composable fun OverflowScreen(entries: List<NavItem>, onSelect: (String) -> Unit, modifier: Modifier = Modifier)  // kabuk zaten kullanır
```
`PageHeader` başlığı YALNIZCA `NavKind.Sidebar`'da yazar (display stili); ray/telefonda sadece subtitle + actions (ikisi de yoksa telefonda yüksekliği 0). Telefon başlığı için `CompactTopBar(title=...)`.
```kotlin
PageHeader("Files", subtitle = "6 items") {
    DeskButton(if (selectMode) "Done" else "Select", { selectMode = !selectMode })
    DeskIconButton(DeskIcons.ListView, "List", {}, selected = true)
}
```
`desktopkit.settings`:
```kotlin
@Composable fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)  // kart, max 760 dp
@Composable fun AppearanceSection(prefs: DeskPreferences, accentLabel: @Composable (Accent) -> String = { presetAccentLabel(it) })
@Composable fun InputSection(prefs: DeskPreferences)
@Composable fun ShortcutListSection(registry: ShortcutRegistry, scopeLabel: @Composable (ShortcutScope) -> String? = { null })
```
```kotlin
"settings" -> LazyColumn(Modifier.fillMaxSize()) {
    item { PageHeader(stringResource(desktopkit.R.string.dk_settings)) }
    item { AppearanceSection(prefs) }; item { InputSection(prefs) }; item { ShortcutListSection(shortcuts) } }
```
Uygulamaya özel ayar satırı için `SettingsSection("Başlık") { SettingRow("Ad", subtitle = "…") { /* kontrol */ } }`.

## 6. Girdi (`desktopkit.input`)
- `InputModeTracker.kt`: `class InputModeState` (`var detected: InputMode`, `fun effective(preference: DensityPreference): InputMode`), `@Composable fun rememberInputModeState()`, `fun Modifier.trackInputMode(state)`. `DesktopKitRoot` bunları zaten kurar; uygulama dokunmaz. Mevcut modu okumak: `DeskTheme.inputMode`.
- Kısayollar:
```kotlin
@Immutable data class KeyChord(val key: Key, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false, val meta: Boolean = false) // label(resources: Resources? = null): String
@Immutable data class ShortcutGroup(val id: String, @StringRes val title: Int)
@JvmInline value class ShortcutScope(val name: String) { companion { val Global } }
@Immutable data class Shortcut(val id: String, val group: ShortcutGroup, @StringRes val label: Int, val chord: KeyChord, val scope: ShortcutScope = ShortcutScope.Global)
class ShortcutRegistry(val groups: List<ShortcutGroup>, val shortcuts: List<Shortcut>) {
  operator fun get(id: String): Shortcut; fun getOrNull(id: String): Shortcut?; fun chordLabel(id: String, resources: Resources? = null): String?
  fun inGroup(group: ShortcutGroup): List<Shortcut>
  fun match(event: androidx.compose.ui.input.key.KeyEvent, scopes: Set<ShortcutScope> = setOf(ShortcutScope.Global)): Shortcut?
  fun matchNative(event: android.view.KeyEvent, scopes: Set<ShortcutScope> = setOf(ShortcutScope.Global), allowPlainKeys: Boolean = false): Shortcut?
  fun menus(resources: Resources, perform: (String) -> Unit, enabled: (String) -> Boolean = { true }, extra: (ShortcutGroup) -> List<MenuEntry> = { emptyList() }): List<MenuSpec>
  fun conflicts(): List<Pair<Shortcut, Shortcut>>
  @RequiresApi(24) fun keyboardShortcutGroups(resources: Resources): List<KeyboardShortcutGroup> }
object CommonShortcuts { val ViewGroup; val HelpGroup; const val SEARCH="dk.search"; SETTINGS="dk.settings"; TOGGLE_SIDEBAR="dk.toggle_sidebar"; SHORTCUT_LIST="dk.shortcut_list"
  val Search (Ctrl+K), Settings (Ctrl+,), ToggleSidebar (Ctrl+B), ShortcutList (Ctrl+/); val all: List<Shortcut> }
```
Grup/kısayol başlıkları string RESOURCE id'dir (`R.string.x`), düz metin değil. Menü çubuğuna bağlama: `DesktopShell(menus = shortcuts.menus(context.resources, perform = { perform(it) }))`; menü = her `ShortcutGroup` bir `MenuSpec`. Tuş yönlendirme Activity'de:
```kotlin
private val FileGroup = ShortcutGroup("file", R.string.menu_file)
val shortcuts = ShortcutRegistry(groups = listOf(FileGroup, CommonShortcuts.ViewGroup, CommonShortcuts.HelpGroup),
    shortcuts = listOf(Shortcut("open", FileGroup, R.string.action_open, KeyChord(Key.O, ctrl = true)),
                       Shortcut("refresh", FileGroup, R.string.action_refresh, KeyChord(Key.F5))) + CommonShortcuts.all)
override fun dispatchKeyEvent(event: KeyEvent): Boolean =
    shortcuts.matchNative(event)?.let { perform(it.id) } == true || super.dispatchKeyEvent(event)
override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
    if (android.os.Build.VERSION.SDK_INT >= 24) data.addAll(shortcuts.keyboardShortcutGroups(resources)) }
```
(`Key` = `androidx.compose.ui.input.key.Key`; Activity'deki KeyEvent = `android.view.KeyEvent`.) Sample'da `perform` Activity alanı (`private var perform: (String) -> Boolean`) olup composition içinde atanıyor. `matchNative` Ctrl/Alt/Meta'sız tek tuşları ve F1-F12 dışını atlar (metin kutusu bozulmasın); tek tuşları (Space) sahibi ekranda `shortcuts.match(composeKeyEvent, scopes)` ile yakala. Menü/ipucu etiketi: `shortcuts.chordLabel("open", resources)`.

## 7. Tema (`desktopkit.theme`)
Erişim: `DeskTheme.colors` / `.type` / `.dimens` / `.inputMode` (hepsi @Composable getter; composable dışında/onClick içinde ÖNCE değişkene al). Ham local'ler: `LocalDeskColors`, `LocalDeskType`, `LocalDeskDimens`, `LocalInputMode`. Kök yoksa varsayılanlar koyu palet/Pointer. Material3 `MaterialTheme` da bu paletle eşlenir (M3 bileşenleri uyumlu görünür).
**DeskColors** (`data class`, hepsi `Color` olup `isDark: Boolean` hariç): `isDark`, `background` (pencere), `chrome` (menü/gezinme), `surface` (kart/panel), `surfaceRaised` (menü/tooltip/dialog), `hover`, `pressed`, `border`, `borderStrong`, `text`, `textSecondary`, `textTertiary`, `accent` (dolgu), `onAccent`, `accentText` (arka plan üstünde metin/ikon), `selection`, `onSelection`, `focusRing`, `success`, `warning`, `danger`, `brandSecondary`, `scrim`.
**DeskDimens** (Dp; `DeskDimens.Pointer` / `.Touch`, `forMode(mode)`): `minTarget` (24/48), `controlHeight` (28/44), `rowHeight` (32/56), `navItemHeight` (32/48), `icon` (16/22), `iconLarge` (20/26), `menuBarHeight` (40/48), `topBarHeight` (48/56), `bottomBarHeight` (56/72), `railWidth` (72/88), `sidebarWidth` (240/280), `sidebarCollapsedWidth` (56/72), `miniPlayerHeight` (64/72), `radiusSmall` (4/6), `radiusControl` (6/12), `radiusCard` (10/16), `radiusPill` (14/24), `gapXs` 4, `gapS` 8, `gapM` 12, `gapL` 16, `gapXl` 24, `pagePadding` (20/16).
**DeskType** (`TextStyle`; pointer / touch sp): `display` 22/26 SemiBold, `headline` 18/20 SemiBold, `title` 16/17 SemiBold, `body` 13/15, `bodyStrong` 13/15 Medium, `label` 12.5/14 Medium, `labelSmall` 11.5/13, `caption` 11/12, `mono` 11/12 (IBM Plex Mono). `DeskType.forMode(mode, fonts = DeskFonts.Plex, pointerStep = 0f, touchStep = 0f)`. Yazı: `DeskFonts.Plex` (varsayılan, pakete gömülü), `DeskFonts.System`.
```kotlin
Text("Volume", style = DeskTheme.type.body, color = DeskTheme.colors.textSecondary, modifier = Modifier.width(80.dp))
Modifier.padding(horizontal = DeskTheme.dimens.pagePadding)
```
Not: İlerleme çubuğu, anahtar (switch), onay kutusu, metin alanı, diyalog, Snackbar kitte YOK; Material3 (`LinearProgressIndicator`, `Switch`, `Checkbox`, `AlertDialog`) veya `BasicTextField` kullanılır, renkleri `DeskTheme.colors`'tan elle ver (sample'daki `FilterField` örnek: `BasicTextField(textStyle = DeskTheme.type.body.copy(color = colors.text), cursorBrush = SolidColor(colors.accent), decorationBox = {...})`, kutu `colors.surface` + `border(1.dp, colors.borderStrong, shape)`).

## 8. İkonlar (`desktopkit.components.DeskIcons`)
Tanım: `ImageVector.Builder(name, 16.dp, 16.dp, viewportWidth = 16f, viewportHeight = 16f)`; her parça SVG path string'i (`addPathNodes`). Stroke parçası: `fill = null`, `stroke = SolidColor(Color.Black)`, `strokeLineWidth = 1.6f`, `StrokeCap.Round`, `StrokeJoin.Round`. Fill parçası: `fill = SolidColor(Color.Black)`, stroke yok. Renk siyah sabit; gerçek renk `Icon(tint=...)` ile gelir. Çizim alanı 16x16, kenardan ~2 birim boşluk (içerik ≈ 2..14), çizgi ikonları ağırlıkta, dolu yalnız Play/Previous/Next/üç nokta/PictureInPicture iç dikdörtgeni. Daire, arc ile: merkez (cx,cy) yarıçap r -> `M{cx-r},{cy}a{r},{r} 0 1 0 {2r},0a{r},{r} 0 1 0 -{2r},0Z`; yuvarlak köşeli dikdörtgen: `M x+r,y H … A r,r 0 0 1 …Z` (köşe yarıçapı 1-1.5). Kendi ikonun (IconPart yapıcısı internal; sadece bunlarla):
```kotlin
fun build(name: String, vararg parts: IconPart): ImageVector
fun stroke(d: String): IconPart    // çizgi
fun fill(d: String): IconPart      // dolu
// uygulamada:
val Sync: ImageVector by lazy { DeskIcons.build("Sync", DeskIcons.stroke("M3 5.5h9l-2.5-2.5M13 10.5H4l2.5 2.5")) }
```
Kitin kendi tanımı (örnekler, birebir):
```kotlin
val Home: ImageVector by lazy { icon("Home", stroke("M3 7.5 8 3l5 4.5V13H9.5V9.5h-3V13H3z")) }
val Music: ImageVector by lazy { icon("Music", stroke("M6 12V4l7-1.5v8"), stroke("M3,12a1.5,1.5 0 1 0 3,0a1.5,1.5 0 1 0 -3,0Z"), stroke("M10,10.5a1.5,1.5 0 1 0 3,0a1.5,1.5 0 1 0 -3,0Z")) }
val Play: ImageVector by lazy { icon("Play", fill("M5 3.2v9.6L13 8z")) }
val Device: ImageVector by lazy { icon("Device", stroke("M4.5,2H11.5A1.5,1.5 0 0 1 13,3.5V12.5A1.5,1.5 0 0 1 11.5,14H4.5A1.5,1.5 0 0 1 3,12.5V3.5A1.5,1.5 0 0 1 4.5,2Z"), stroke("M7 12h2")) }
val MoreHorizontal: ImageVector by lazy { icon("MoreHorizontal", fill("M2.2,8a1.3,1.3 0 1 0 2.6,0a1.3,1.3 0 1 0 -2.6,0Z"), fill("M6.7,8a1.3,1.3 0 1 0 2.6,0a1.3,1.3 0 1 0 -2.6,0Z"), fill("M11.2,8a1.3,1.3 0 1 0 2.6,0a1.3,1.3 0 1 0 -2.6,0Z")) }
// private icon() ≡ yukarıdaki Builder + stroke(1.6f, Round, Round) / fill(Black) kuralı
```
Adlandırma: PascalCase `val` + aynı ada `name`, `by lazy`. Mevcut ikonlar (61): Home, Movies, Series, Music, Podcasts, Radio, Device, ExternalDrive, Network, Streams, TvLab, Convert, Downloads, Settings, Plus, Grid, ListView, ChevronDown, ChevronUp, ChevronLeft, ChevronRight, ArrowLeft, Check, Play, Resume, Forward, OpenWindow, Subtitle, Info, PlaylistAdd, Folder, Delete, Close, MoreHorizontal, MoreVertical, Shuffle, Previous, Next, Pause, Repeat, Queue, Artists, Albums, History, Equalizer, SmartList, FramePrevious, FrameNext, AudioTrack, Screenshot, Effects, PictureInPicture, Fullscreen, Search, Sort, Lyrics, Volume, Menu, Waveform, Keyboard, SidebarToggle. (Yok: Warning, Error, Refresh/Sync, Usb, SdCard, Copy, Cancel, Stop — gerekirse yukarıdaki standartta çiz; `Convert` çift ok, `Resume`/`Forward` döner ok olarak yakın.) Kullanım: `Icon`/`DeskIconButton`/`NavItem`/`MenuEntry.Item(icon=)`/`DeskButton(icon=)` ImageVector alır.

## 9. Metinler (Türkçe/İngilizce)
Kit kendi metinlerini `res/values` (EN) ve `res/values-tr` (TR) içinde taşır (`dk_` önekli: dk_more, dk_search, dk_search_hint, dk_settings, dk_back, dk_close, dk_more_options, dk_main_navigation, dk_sidebar, dk_sidebar_collapse/expand, dk_key_space, dk_menu_view, dk_menu_help, dk_toggle_sidebar, dk_shortcut_list, dk_settings_*, dk_theme_*, dk_accent_*, dk_density_*). Cihaz diline göre otomatik seçilir. Uygulama aynı adla kendi `strings.xml`'inde tanımlarsa uygulamanınki kazanır. Kitte genel (parametrik) çeviri API'si YOK: uygulamanın kendi metinleri kendi `R.string`'inde olmalı; `NavItem.label`, `TableColumn.title`, `MenuEntry.Item.label`, `DeskButton.text` düz `String` alır (`stringResource(R.string.x)` composable içinde ver; `ShortcutGroup/Shortcut` ise `@StringRes Int` ister).

## 10. Tuzaklar
- `internal` (kullanılamaz): `MenuItemRow`, `TooltipBubble`, `BelowAnchorPositionProvider`, `TableSelection`, `TopBarIconButton`, `TopBarOverflow`, `CompactTopBarHost`/`LocalCompactTopBarHost`, `splitTopBarActions`, `moreItem()`, `IconPart` yapıcısı (ikon için `DeskIcons.stroke/fill` kullan), `InputModeState.detected` setter'ı, `KeyChord.androidModifiers`. `private`: kitin üst düzey `stroke()/fill()/icon()` fonksiyonları.
- Zorunlu sarmalayıcı: tüm kit bileşenleri `DesktopKitRoot` (veya `DesktopKitTheme`) içinde olmalı; yoksa varsayılan koyu/Pointer tema gelir. `DesktopKitRoot` `safeDrawing` insets'i tüketir -> ekranlarda `imePadding()`/`systemBarsPadding()` ekleme (çift boşluk).
- Activity: `enableEdgeToEdge()` (`androidx.activity.enableEdgeToEdge`) `super.onCreate`'ten sonra; `ComponentActivity` + `setContent`. Manifest: `android:resizeableActivity="true"`, `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|density|keyboard|keyboardHidden|navigation|uiMode|fontScale"`, `android:windowSoftInputMode="adjustResize"` (DeX'te yeniden oluşturmayı önler; `uiMode` dahil olduğu için tema değişimi recreate etmez). Sample teması: `parent="@android:style/Theme.Material.NoActionBar"` + `windowBackground=@android:color/black`, `statusBarColor`/`navigationBarColor` = `@android:color/transparent`.
- `DenseTable`/`HorizontalSplitPane`/`MessageScreen` `fillMaxSize()` kullanır; sınırsız yükseklikli (verticalScroll, LazyColumn item) kapsayıcıya koyma. Ekran içeriğini `Column(Modifier.fillMaxSize()) { PageHeader(..); Box(Modifier.weight(1f)) { DenseTable(..) } }` kalıbıyla kur.
- `DenseTable` hücreleri yalnızca düz tek satır metin; zengin hücre gerekiyorsa tabloyu kullanma, kendi `LazyColumn` satırını yaz (`deskClickable`, `focusRing`, `stateBackground`, `collectState` public).
- Menü çubuğu ve `PageHeader` başlığı yalnızca >= 1200 dp (Sidebar) düzeninde; Compact'ta başlık `CompactTopBar` ile, Medium/Expanded'da rota etiketi `PageTopBar`'dan gelir. Her işlev menü çubuğuna güvenmesin (Ctrl+K vb. kısayol ve `TopBarAction`/`DeskButton` yedeği ver).
- `DeskTheme.colors` vb. `@Composable`: `onClick`/coroutine içinde çağırma, önce `val colors = DeskTheme.colors`.
- `"dk_more"` rotası rezerve; `ShellSpec` yinelenen rotada exception atar; `bottomBar` en çok 4 rota.
- Ekran durumu: kabuk NavHost kullanır, üst düzey rota değişince rota kompozisyonu `saveState/restoreState` ile korunur; ama alt ekran durumunu `rememberSaveable`/ViewModel'de tut. Kabuk `ShellNavigator`'ı dışarıya yalnızca `content` lambdası verir; Activity'den gezinmek için kendi `rememberNavController()` yarat, `DesktopShell(navController=…)`'e ver ve `ShellNavigator(navController)` sar (sample böyle yapıyor).
- `Accent.custom` rengi otomatik kontrast ayarlıdır; `AppearanceSection` özel vurgu için `accentLabel` ile ad verilmezse `accent.name` yazar.
- `DesktopKitRoot` `type`/`colors`/`dimens` parametreleri lambda alır (değer değil).
- `TooltipArea` dokunmatikte açılmaz; kritik bilgiyi yalnızca tooltip'e koyma.
- Kit hiç dosya/depolama/izin kodu içermez; tamamen arayüz.
