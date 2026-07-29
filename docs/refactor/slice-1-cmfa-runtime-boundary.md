# Срез: `refactor/cmfa-runtime-boundary`

Спецификация на исполнение. Все решения ниже **приняты** — не пересматривать, не расширять.

База: ветка `spike/getline-feasibility`, коммит `a2b3d7ec`.

---

## Цель

Продуктовый GetLine-код не работает с CMFA runtime напрямую. CMFA-типы и вызовы
живут только в явно выделенном пакете-адаптере `pro.getline.vpn.cmfa`.

## Non-goals — не трогать

- `BaseActivity<D : Design<*>>` и наследование продуктовых Activity. Это следующий срез.
- Advanced: `openAdvanced()`, 7-tap hatch (`GetLineOnboardingDesign.onBrandTitleClicked`),
  `MainActivity.EXTRA_OPEN_ADVANCED`, DEBUG-кнопка. Остаются как есть, работают как есть.
- Legacy CMFA Activities (`ProfilesActivity`, `ProxyActivity`, `SettingsActivity`,
  `LogsActivity`, `ProvidersActivity`, `PropertiesActivity`, `*SettingsActivity`,
  `AccessControlActivity`, `FilesActivity`, `HelpActivity`, `LogcatActivity`,
  `MainActivity`). Им импортировать `core`/`service` **разрешено**.
- Import-flow, `PropertiesActivity`, `PropertiesDesign`.
- Namespace, Mihomo, `core/`, `service/`, `common/`, нативный слой.
- Любые изменения внешнего вида и поведения.

## Принятые решения

1. **`common` разрешён.** `pro.getline.vpn.common.*` (`ticker`, `log.Log`, `store.Store`,
   `compat.*`) остаётся доступным продуктовому коду. Причина: диф `common` от upstream —
   чистое переименование пакетов, риск конфликта при upstream bump ≈ 0. Гейт `common` не
   проверяет. Запрещены только `core` и `service`.
2. **Продуктовые модели живут в `design.model`.** Прецедент существует:
   `design/model/GetLineProductState.kt`, `GetLineRecoveryAction.kt`,
   `GetLineFetchStatusCopy.kt`. Новых Gradle-модулей в этом срезе **не создавать** —
   `:getline-ui` это срез 4.
3. **Новых интерфейсов минимум.** Расширяем существующий `GetLineVpnController`, а не
   заводим `GetLineVpnStatus`.

---

## Утечки, которые закрываем (полный список)

Проверено grep'ом, других нет.

| # | Где | Что течёт |
|---|-----|-----------|
| 1 | `design/GetLineHomeDesign.kt:11-12` | `core.model.Traffic`, `core.util.trafficTotal` |
| 2 | `app/GetLineHomeActivity.kt:40,273` | `util.withClash` → `IClashManager` |
| 3 | `app/GetLineHomeActivity.kt:544-545,630-631,666-667,674-675` | `ProxySort` **без импорта**, через `uiStore` |
| 4 | `getline/servers/VpnServerSelectionRepository.kt:3-5` | `ProxyGroup`, `ProxySort`, `IClashManager` |
| 5 | `getline/CmfaGetLineBackend.kt:14` | `service.model.Profile` |

Утечки 4 и 5 закрываются **переносом файлов в `pro.getline.vpn.cmfa`**, а не переписыванием.

---

## Коммит 1 — продуктовая модель трафика

Новый файл `design/src/main/java/pro/getline/vpn/design/model/GetLineTraffic.kt`:

```kotlin
package pro.getline.vpn.design.model

data class GetLineTraffic(
    val uploadedBytes: Long,
    val downloadedBytes: Long,
) {
    val totalBytes: Long get() = uploadedBytes + downloadedBytes

    companion object {
        val Zero = GetLineTraffic(0L, 0L)
    }
}
```

Форматирование переносится в `design` как продуктовая функция (например
`design/util/TrafficFormat.kt`).

### ⚠️ Ловушка — форматтер копировать дословно

Источник: `core/src/main/java/pro/getline/vpn/core/util/Traffic.kt`, приватная
`trafficString(scaled: Long)`. В ней целочисленное деление, а затем `data.toFloat() / 100`.
Выглядит как баг. **Это не баг для нас — это текущий вывод на экране.** AC требует
«UI не меняется», значит форматтер переносится побайтово, включая пороги
(`> 1024*1024*1024*100L` и т.д.) и строки `"%.2f GiB"` / `"$scaled Bytes"`.

Декодирование packed-значения (`scaleTraffic`) — в адаптер, не в design. См. коммит 2.

## Коммит 2 — CMFA-адаптер трафика

Расширить существующий контракт `app/getline/GetLineBackend.kt:102`:

```kotlin
interface GetLineVpnController {
    val running: Boolean

    fun start(): Intent?
    fun stop()

    /** null when the tunnel is not running or the query failed. */
    suspend fun querySession(): GetLineSession?
}

data class GetLineSession(
    val durationMs: Long?,
    val traffic: GetLineTraffic,
)
```

Реализация в адаптере: `withClash { querySessionDurationMs() to queryTrafficTotal() }`,
затем распаковка packed `Traffic` (`Long`) в `GetLineTraffic`:

```
uploadedBytes   = scaleTraffic(packed ushr 32)
downloadedBytes = scaleTraffic(packed and 0xFFFFFFFF)
```

`scaleTraffic` — приватная копия из `core/util/Traffic.kt:44-52`, дословно.

## Коммит 3 — убрать прямой доступ к Clash из Home

`app/GetLineHomeActivity.kt`:

- удалить `import pro.getline.vpn.util.withClash` (строка 40);
- строки 268-279 переписать на `backend.vpn.querySession()`;
- `import pro.getline.vpn.common.util.ticker` (строка 38) **остаётся** — см. решение 1.
  Тикер на строке 116 не трогаем, переезд в repository/ViewModel — не этот срез.

`design/GetLineHomeDesign.kt`:

- сигнатура `setSession(sessionDurationMs: Long?, traffic: Traffic)` (строка 447) →
  `setSession(sessionDurationMs: Long?, traffic: GetLineTraffic)`;
- поле `trafficTotalBits: Traffic` (строка 196) → `GetLineTraffic`, комментарий про
  packed-представление на строках 192-195 переписать (он перестаёт быть правдой);
- строка 467 `trafficTotalBits.trafficTotal()` → продуктовый форматтер из коммита 1;
- удалить импорты строк 11-12.

### ⚠️ `ProxySort` — утечка без импорта

`GetLineHomeActivity.kt:544-545` и ещё три места передают `uiStore.proxySort`
(тип `core.model.ProxySort`, см. `design/store/UiStore.kt:8,47`) и
`uiStore.proxyExcludeNotSelectable` в repository. `uiStore` наследуется от
`BaseActivity.kt:36`. **Импорта нет — Kotlin выводит тип.**

Фикс: убрать оба параметра из product-facing сигнатур
(`loadMainGroup`, `queryMainSelectedName`, `healthCheckMainGroup`).
Адаптер сам создаёт `UiStore` и читает `proxySort` / `proxyExcludeNotSelectable` внутри.
`BaseActivity` при этом не трогается.

Затронуто в `GetLineHomeActivity.kt`: строки 543-546, 629-632, 665-668, 673-676.

## Коммит 4 — вынести CMFA-реализации в `pro.getline.vpn.cmfa`

Разделить интерфейс и реализацию у server selection:

```
app/getline/servers/VpnServerSelectionRepository.kt   → интерфейс, без CMFA-типов
app/cmfa/servers/CmfaVpnServerSelectionRepository.kt   → реализация, ProxyGroup/ProxySort/IClashManager
```

`MainProxyGroupPolicy` и `queryMainProxyGroup` (расширение над `IClashManager`,
`VpnServerSelectionRepository.kt:174-186`) едут в `cmfa`. `ServerNameParser`,
`ServerGroupingPolicy`, `VpnServerUiModels`, `VpnServerStateHolder` — остаются
продуктовыми, CMFA-типов не содержат.

Перенести целиком:
```
app/getline/CmfaGetLineBackend.kt → app/cmfa/CmfaGetLineBackend.kt
```
Класс на три не дробить — шума больше, чем пользы.

Контракты (`GetLineBackend.kt` со всеми интерфейсами, `GetLineBackendProvider`) остаются
в `pro.getline.vpn.getline`.

Правки только `package` / `import` / места создания. Логику не менять.

## Коммит 5 — гейт

`scripts/check-product-boundary.sh`, стиль по образцу `scripts/apply-mihomo-patches.sh`
(`#!/usr/bin/env bash`, шапка-комментарий с причиной, `set -euo pipefail`).

Проверяемые файлы:
```
app/src/main/java/pro/getline/vpn/GetLineHomeActivity.kt
app/src/main/java/pro/getline/vpn/GetLineOnboardingActivity.kt
app/src/main/java/pro/getline/vpn/QrScannerActivity.kt
app/src/main/java/pro/getline/vpn/getline/**          (после переноса адаптеров)
design/src/main/java/pro/getline/vpn/design/GetLineHomeDesign.kt
design/src/main/java/pro/getline/vpn/design/GetLineOnboardingDesign.kt
design/src/main/java/pro/getline/vpn/design/model/GetLine*.kt
design/src/main/java/pro/getline/vpn/design/view/GetLine*.kt
```

Две проверки, обе обязательны:

1. **Импорты** — запрещено `^import pro\.getline\.vpn\.(core|service)\.`
2. **Голые идентификаторы** — запрещены `ProxySort`, `ProxyGroup`, `IClashManager`,
   `IProfileManager`, `TunnelState`, `withClash`, `trafficTotal`.
   Без этой проверки утечка `uiStore.proxySort` проходит гейт незамеченной.
   Список — не «на будущее», а ровно то, что течёт сейчас; расширять по мере надобности.

Разрешено: `pro.getline.vpn.common.*`, `pro.getline.vpn.cmfa.*`, всё вне списка файлов.

Скрипт печатает нарушения с `file:line` и завершается ненулевым кодом.
В Gradle не подключать — отдельный ручной/CI-вызов.

## Коммит 6 (опциональный, отдельно)

Удалить `openServerSelection()` и `openProfiles()` из `GetLineNavigation`
(`GetLineBackend.kt:112-113`) и их реализации (`CmfaGetLineBackend.kt:331-337`).
Проверено: ноль вызовов. `openAdvanced()` **не трогать**.

---

## Acceptance criteria

Функциональные:
- [ ] `scripts/check-product-boundary.sh` проходит (обе проверки).
- [ ] Гейт **ловит** искусственно возвращённый `uiStore.proxySort` в `GetLineHomeActivity` —
      проверить, временно вернув строку и убедившись, что скрипт падает. Без этого
      доказательства гейт не считается рабочим.
- [ ] `pro.getline.vpn.cmfa` содержит все классы, которым нужны CMFA-типы.
- [ ] Legacy Activities не изменены (`git diff` по ним пуст).

Сборка и поведение:
- [ ] `./gradlew :app:assembleAlphaDebug` проходит.
      Состояние сборки на входе неизвестно — **сначала собрать до правок** и зафиксировать
      baseline. Если сборка сломана изначально, остановиться и сообщить, не «чинить попутно».
- [ ] Ручная проверка на устройстве/эмуляторе:
      - трафик на Home-экране показывает **те же строки**, что до правок
        (сверить формат: `12.34 MiB`, `567 Bytes`);
      - таймер сессии работает;
      - выбор сервера, health check, список стран — без изменений;
      - Advanced: 7 тапов по бренду в онбординге открывают Advanced;
      - в DEBUG-сборке кнопка Advanced видна.
- [ ] Ничего не коммитить в `main`, ничего не пушить без запроса.

## Проверка на приёмке

```bash
# 1. нет прямых импортов
grep -rnE '^import pro\.getline\.vpn\.(core|service)\.' \
  app/src/main/java/pro/getline/vpn/getline \
  app/src/main/java/pro/getline/vpn/GetLineHomeActivity.kt \
  app/src/main/java/pro/getline/vpn/GetLineOnboardingActivity.kt \
  design/src/main/java/pro/getline/vpn/design/GetLineHomeDesign.kt

# 2. нет голых CMFA-идентификаторов
grep -rnE '\b(ProxySort|ProxyGroup|IClashManager|IProfileManager|withClash|trafficTotal)\b' \
  app/src/main/java/pro/getline/vpn/getline \
  app/src/main/java/pro/getline/vpn/GetLineHomeActivity.kt \
  design/src/main/java/pro/getline/vpn/design/GetLineHomeDesign.kt

# 3. Advanced жив
grep -rn 'openAdvanced\|onBrandTitleClicked\|EXTRA_OPEN_ADVANCED' app/src design/src

# 4. legacy не тронут
git diff --stat a2b3d7ec -- app/src/main/java/pro/getline/vpn/MainActivity.kt \
  app/src/main/java/pro/getline/vpn/ProxyActivity.kt \
  app/src/main/java/pro/getline/vpn/ProfilesActivity.kt \
  app/src/main/java/pro/getline/vpn/BaseActivity.kt
```

Пункты 1, 2, 4 → пусто. Пункт 3 → находки есть.
