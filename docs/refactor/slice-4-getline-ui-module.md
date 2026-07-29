# Срез 4: `refactor/getline-ui-module`

Спецификация на исполнение. База: `1a5989d0`.

Читать сначала `docs/refactor/README.md` — решения 2, 3, 7 действуют и здесь меняются
(решение 3 закрывается этим срезом).

Это самый крупный срез из оставшихся. Он единственный, который реально уменьшает
конфликтную поверхность при upstream bump: продуктовый UI перестаёт жить внутри
форкнутого `:design` (90 R, 42 M, 34 A, 5 D — самая форкнутая часть дерева).

---

## Цель и главный критерий

Новый Gradle-модуль с продуктовым UI, **не зависящий от `:design`, `:core`, `:service`**.

Если `:getlineui` в итоге объявляет `implementation(project(":design"))` — срез провален,
даже если всё собирается и работает. Зависимость от форка через один хоп — это переноска
мебели, ровно то, о чём предупреждает раздел «Порядок и почему он такой» в README.
Разрешено: `:common` (решение 2), androidx, material, kotlin coroutines.

## Что переносится

Проверено: на эти файлы не ссылается ни один CMFA-экран. Перенос чистый, без развилок.

Kotlin (~1900 строк), из `design/src/main/java/pro/getline/vpn/design/`:

```
GetLineScreen.kt            (+ enum ToastDuration в том же файле)
GetLineHomeDesign.kt        1104
GetLineOnboardingDesign.kt   353
view/GetLineStateView.kt      90
view/GetLineConnectRingView.kt 185
model/GetLineProductState.kt (+ GetLineRecoveryAction)
model/GetLineTraffic.kt
model/GetLineImportStage.kt
util/TrafficFormat.kt
```

Layouts, из `design/src/main/res/layout/`:

```
design_get_line_home.xml
design_get_line_onboarding.xml
component_get_line_state.xml
item_get_line_server_group.xml       (инфлейтится из GetLineHomeDesign:584)
item_get_line_server_variant.xml     (:669)
```

Ресурсы: 132 строки `get_line_*` из `values/strings.xml`, 130 из `values-ru/strings.xml`
(других переводов у продукта нет — проверено по всем `values-*`), весь
`values/getline_brand.xml`, продуктовые стили `Widget.GetLine.*` и
`TextAppearance.GetLine.*` из `values/styles.xml`, `font/mulish.xml` + сами файлы шрифта.

## Что копируется, а не переносится

У этих есть живые потребители среди legacy CMFA-экранов (решение 7 — они остаются
до среза 5). Копия схлопнется обратно в один экземпляр после среза 5/6.

| Из `:design` | Строк | Зачем продукту |
|---|---|---|
| `ui/Surface.kt`, `ui/Insets.kt` | 14 + 6 | `GetLineScreen.surface`, биндится из XML |
| `util/Inserts.kt` | 35 | `setOnInsertsChangedListener` |
| `util/Theme.kt` → только `resolveThemedColor`, `resolveThemedBoolean` | 77 → ~20 | `GetLineActivity.applyTheme` |
| `util/I18n.kt` → только `toBytesString`, `toDateStr` | 82 → ~40 | Home: трафик и дата истечения |
| `util/Validator.kt` → только `ValidatorHttpUrl` | 29 | ручной ввод ссылки (срез 3) |
| `dialog/Input.kt` + `layout/dialog_text_field.xml` | 95 | `requestModelTextInput` |
| `drawable/ic_baseline_sync.xml`, `ic_outline_check_circle.xml` | — | делятся с `design_profiles`/`design_main` |
| тема `AppThemeDark` (+ `values-v23/v27/v29`), её цвета | — | ниже |

`ic_email`, `ic_google`, `ic_telegram` — только продуктовые, переносить.

Копировать **только используемое**. Тянуть `I18n.kt` или `Theme.kt` целиком, «чтоб не
разбирать» — не делать: остальное там про CMFA и в продуктовом модуле мёртвое.

### Тема

`AppThemeDark` → `AppThemeDarkBase` (`values/themes.xml:185–227`) с вариантами в
`values-v23/v27/v29`. Она уже почти целиком в GetLine-цветах и GetLine-текстовых стилях,
но её же используют legacy Activities, поэтому в `:design` она остаётся.

В `:getlineui` завести собственную тему под своим именем (например `GetLineTheme`) —
**не** `AppThemeDark`, иначе при живом `app → :design` два ресурса с одним именем
сольются по правилам merge, и какой победит, зависит от порядка модулей. Одинаковое имя
здесь — тихая мина, а не экономия.

Атрибуты `colorClashStopped`, `colorLogo` объявлены в `design/values/attrs.xml` и в
продуктовой теме не нужны — проверить и не тянуть.

`GetLineActivity.applyTheme` (`app/.../product/GetLineActivity.kt:181`) переключить на
новую тему. `BootstrapTheme` в манифесте (`AndroidManifest.xml:44`) — application-level,
общий с CMFA; не трогать в этом срезе.

## `UiStore` — отдельно и осторожно

Продукт читает из него ровно два поля:

- `hideFromRecents` (`GetLineActivity.kt:105`)
- `getLineShellTab` (`GetLineHomeActivity.kt:1275,1283`)

Сам `UiStore` тянет `core.model.ProxySort` (`store/UiStore.kt:8`) — то есть **это
действующая утечка CMFA в продукт**, которую нынешний гейт не ловит (тип приходит через
класс из `:design`, а не импортом `core`).

Завести `GetLineUiStore` в `:getlineui` поверх `common.store.Store` с этими двумя полями.
Значения обязаны остаться теми же, иначе у существующих пользователей слетит вкладка и
настройка «скрывать из недавних»:

```
SharedPreferences name: "ui"            // UiStore.PREFERENCE_NAME
key "hide_from_recents", default false
key "get_line_shell_tab", default "home"
```

Не мигрировать, не переименовывать, не заводить свой файл настроек. Тот же файл, те же
ключи, те же дефолты — два независимых читателя одного `SharedPreferences` здесь
корректны, потому что продукт и Advanced пишут разные ключи.

## Ловушка: `surface` живёт в XML

Grep по `.kt` даёт ноль вхождений `surface` в продуктовом коде. При этом
`design_get_line_home.xml:79-80,104-105` и `design_get_line_onboarding.xml:35-36,49-50`
биндят `@{self.surface.insets.start/top/end/bottom}` — отступы под системными панелями.

Снести `Surface`/`Insets` как «неиспользуемые» — либо не соберётся (в лучшем случае),
либо соберётся и молча поедет вёрстка под статус-баром. Проверять **визуально, со
скриншотами до/после**, на устройстве с вырезом или жестовой навигацией.

## Имя модуля

Корневой `build.gradle.kts:44` задаёт namespace всем модулям одинаково:

```kotlin
namespace = if (name == "app") "pro.getline.vpn" else "pro.getline.vpn.$name"
```

Поэтому модуль назвать **`getlineui`**, без дефиса: `getline-ui` дал бы невалидный
namespace `pro.getline.vpn.getline-ui`. Если дефис принципиален — переопределять
namespace в самом модуле и доказать сборкой, что корневой блок его не затирает;
по умолчанию — не усложнять.

`:getlineui` автоматически получит из корня flavors `alpha`/`meta`, dataBinding,
`consumerProguardFiles("consumer-rules.pro")` (файл создать, как в `:design`) и
`sourceSets` с `src/foss/java`. Собственный `build.gradle.kts` — только `plugins` и
`dependencies`, по образцу `design/build.gradle.kts`.

Databinding-классы сменят пакет: `design.databinding.DesignGetLineHomeBinding` →
`getlineui.databinding.DesignGetLineHomeBinding`. Это ожидаемо; `<data>`-блоки в layout
тоже правятся (`type="pro.getline.vpn.design.GetLineHomeDesign"` → новый пакет).

## Non-goals

- **Не удалять `app → :design`.** Legacy Activities остаются (решение 7), связь нужна.
  Это срез 6, и он блокирован срезом 5, а не этим.
- Не трогать legacy Designs, адаптеры, `Design.kt`, `BaseActivity.kt`.
- Не менять поведение экранов. Ни одной правки «раз уж всё равно трогаем».
- Не переносить продуктовые Activity из `app` — они там и остаются.
- Не чинить integer-деление в `TrafficFormat` (решение 4).

---

## Коммиты

1. Пустой модуль `:getlineui`: `settings.gradle.kts`, `build.gradle.kts`,
   `consumer-rules.pro`, `src/main/AndroidManifest.xml`. Собирается, пустой.
2. Скопированный фундамент: Surface/Insets/Inserts, две функции темы, две функции I18n,
   валидатор, диалог ввода + его layout, тема + цвета + шрифт + продуктовые стили,
   drawables.
3. Перенос продуктового UI: Kotlin, layouts, строки, `getline_brand.xml`. Удаление
   оригиналов из `:design`. `app` получает `implementation(project(":getlineui"))`,
   импорты в трёх Activity правятся.
4. `GetLineUiStore`; `GetLineActivity`/`GetLineHomeActivity` перестают трогать `UiStore`.
5. Гейт: пути в `scripts/check-product-boundary.sh` переводятся на `getlineui`;
   `UiStore` добавляется в `ID_PATTERN` (после коммита 4 продукт его не касается).

Порядок важен: 3 не собирается без 2.

## AC

- [ ] `getlineui/build.gradle.kts` не содержит `":design"`, `":core"`, `":service"`
- [ ] `./gradlew :getlineui:dependencies --configuration alphaDebugCompileClasspath`
      не содержит `project :design`, `project :core`, `project :service`
- [ ] `grep -rn "GetLine" design/src/main/java design/src/main/res/layout` — пусто
      (кроме имён цветов/стилей `getline_*`, `Widget.GetLine.*`, оставшихся в теме `:design`)
- [ ] `grep -rn "get_line" design/src/main/res/values*/strings.xml` — пусто
- [ ] `grep -rn "design.store.UiStore\|design.util\|design.dialog" app/src/main/java/pro/getline/vpn/{GetLineHomeActivity.kt,GetLineOnboardingActivity.kt,product/}` — пусто
- [ ] `./scripts/check-product-boundary.sh` → ok, при этом `TARGETS` указывают на новые
      пути (проверить: временно сломать один путь → скрипт падает с
      `no product boundary targets found` или ловит нарушение, а не молча зеленеет)
- [ ] Негативный тест гейта: вписать `UiStore` в `GetLineHomeActivity` → гейт падает; вернуть
- [ ] `./gradlew :app:assembleAlphaDebug` — EXIT=0
- [ ] `./gradlew :app:testAlphaDebugUnitTest` — EXIT=0
- [ ] `scripts/verify-mihomo-gate.sh` — прогнать
- [ ] `git status`: только `m core/src/foss/golang/clash`
- [ ] Диф с upstream по `:design` сократился: показать
      `git diff -M --stat upstream/main -- design/` до и после

## Ручная проверка на устройстве (обязательна)

Срез переносит **весь** продуктовый UI. Автотестов на верстку нет — проверяется глазами.

1. Скриншоты **до и после**: Home (подключено и отключено), список серверов,
   вкладка подписки, онбординг (провайдеры / email / OTP). Сравнить попиксельно глазами.
2. Отступы под статус-баром и панелью навигации — на устройстве с вырезом **и** с
   жестовой навигацией. Это проверка `surface` (см. ловушку выше).
3. Смена вкладки → убить приложение → открыть: вкладка восстановилась. Это проверка
   `getLineShellTab` на старом ключе. Проверять **на уже установленном билде**,
   обновлением поверх, а не чистой установкой — иначе миграция ключа не проверена.
4. Настройка «скрывать из недавних» (Advanced) → продуктовый экран её соблюдает.
5. Кольцо подключения: анимация, состояния Connecting/Connected/Disconnected.
6. Трафик и дата истечения подписки — форматирование не изменилось (решение 4).
7. Ручной ввод ссылки: диалог URL, валидация мусорного ввода, отмена.
8. Тосты: остановка VPN → продуктовый тост, не CMFA-исключение.
9. Advanced (7 тапов по бренду / DEBUG-кнопка) → экраны CMFA открываются, тема не поехала.
   Это проверка того, что копирование темы не сломало `:design`.
10. Локаль ru: строки на месте, не `get_line_...` и не английские.

## Риски

- **Merge ресурсов.** Пока `app` зависит и от `:design`, и от `:getlineui`, одноимённые
  ресурсы сливаются молча. Отсюда требование разных имён темы. Если после сборки
  что-то в CMFA-экранах выглядит иначе — искать в первую очередь здесь, а не в коде.
- **Databinding и ProGuard.** Release-сборка минифицируется только в `app`
  (`build.gradle.kts:145`), но у нового модуля должен быть `consumer-rules.pro`.
  Если правил не нужно — файл всё равно создать пустым, иначе корневой
  `consumerProguardFiles` упадёт.
- **Объём.** ~1900 строк переезда + ~350 копирования + res. Дробить на пять коммитов —
  не формальность: откат «всего среза» одним ревертом на этом объёме бесполезен.
