# Срез 2: `refactor/decouple-product-activities`

Спецификация на исполнение. База: `0566e071` (после среза 1).

Срез делится на **2a** (Activity) и **2b** (Design). Делать по очереди, отдельными ветками.
2a самодостаточен и полезен сам по себе; 2b без 2a не имеет смысла.

---

## Цель

Продуктовые экраны перестают зависеть от CMFA UI-фреймворка: `BaseActivity`, `Design<*>`.
Это предпосылка для выноса в `:getline-ui` (срез 4) и удаления `:design` (срез 6).

## Non-goals

- `BaseActivity.kt` **не изменять и не удалять** — им пользуются ~20 legacy Activities.
- Ресурсы: `design.R.string.*`, `design.R.style.AppThemeDark`, layouts, databinding —
  остаются в `:design`. Переезжают в срезе 4.
- `UiStore` — остаётся. Уйдёт в срезе 4.
- `core`/`service`/`common`, namespace, Mihomo, import-flow, Advanced.
- Внешний вид и поведение (кроме одного явно принятого изменения, см. 2a пункт «Ловушки»).

## Стратегия

Продуктовая база **повторяет использованный API `BaseActivity` один-в-один**. Тогда тела
Activity почти не меняются: правится объявление класса и несколько override. Иначе придётся
трогать 2151 строку в двух файлах.

---

# 2a — `refactor/product-activity-base`

## Что реально используется из `BaseActivity`

Проверено грепом по обоим файлам. Полный список, ничего сверх него не переносить.

| Член | Home | Onboarding |
|---|---|---|
| `abstract suspend fun main()` | ✅ | ✅ |
| `setContentDesign(design)` | ✅ | ✅ |
| `design: D?` (сеттер → `setContentView(value.root)`) | ✅ | ✅ |
| `events: Channel<Event>` + `enum Event` (9 значений) | ✅ | ✅ |
| `activityStarted` | ✅ | — |
| `startActivityForResult(contract, input)` | ✅ (1) | ✅ (2) |
| `handleBackPressed()` + `OnBackPressedCallback` | ✅ | ✅ |
| `uiStore` | ✅ (`getLineShellTab`) | — |
| `CoroutineScope by MainScope()`, `cancel()` в `onDestroy` | ✅ | ✅ |
| `applyTheme()` | ✅ | ✅ |
| `Broadcasts.Observer` → `events` | ✅ | ✅ |

**Не используется — не переносить:** `defer()` / асинхронный `finish()`, `clashRunning`,
`shouldDisplayHomeAsUpEnabled()` (объявлен `open`, но `BaseActivity` его нигде не вызывает —
оба override мёртвые, удалить).

## Новый файл

`app/src/main/java/pro/getline/vpn/product/GetLineActivity.kt`, пакет `pro.getline.vpn.product`.

```kotlin
abstract class GetLineActivity<D : Design<*>> : AppCompatActivity(),
    CoroutineScope by MainScope(),
    Broadcasts.Observer
```

На 2a параметр остаётся `D : Design<*>` — снятие `Design` это 2b.

`Broadcasts` (`pro.getline.vpn.remote.Broadcasts`) импортирует только `common.*` —
использовать можно, гейт не нарушается. `Remote` (`pro.getline.vpn.remote.Remote`) тоже
чист от `core`/`service` в своих импортах.

## Ловушки

**1. `setContentDesign` — не просто `setContentView`.**
`BaseActivity.kt:76-83`: `suspendCoroutine { window.decorView.post { ... } }`.
`main()` стартует из `onCreate` до готовности декора. Скопировать дословно, включая `post`.

**2. `finish()` в `BaseActivity` асинхронный.**
`BaseActivity.kt:118-131`: `launch { defer(); super.finish() }`. `defer` ни одна продуктовая
Activity не ставит, но `super.finish()` всё равно уходит через корутину. Продуктовая база
делает **синхронный** `finish()`. Разница видима только по таймингу закрытия — проверить,
что выход из Home и назад из Onboarding работают как раньше.

**3. `onStopped(cause)` — принятое изменение поведения.**
Сейчас `BaseActivity.kt:171-179` показывает `showExceptionToast(ClashException(cause))` —
это тип из `core.bridge`. Home его перекрывает продуктовым тостом
(`GetLineHomeActivity.kt:1303-1310`), Onboarding — нет и наследует CMFA-вариант.

Решение: продуктовая база реализует **вариант Home** (`get_line_vpn_stopped`, `ToastDuration.Long`,
под `cause != null && activityStarted`). Override в Home удаляется.
Onboarding при этом получает продуктовый тост вместо CMFA-исключения — **это принятое
изменение, не регрессия**. `ClashException` в продуктовый код не тянуть ни при каких условиях.

**4. `onCreate` читает `uiStore.hideFromRecents`** (`BaseActivity.kt:94-96`) для
`setExcludeFromRecents` по всем `appTasks`. Перенести как есть.

**5. `applyTheme()`** тянет `design.R.style.AppThemeDark` и 4 `window.*Compat`. Оставить как
есть — тема переезжает в срезе 4. Комментарий про BootstrapTheme сохранить.

## Коммиты

1. `GetLineActivity` — продуктовая база (новый файл, никого не переключает)
2. `GetLineHomeActivity` → `GetLineActivity`; удалить override `onStopped` и `shouldDisplayHomeAsUpEnabled`
3. `GetLineOnboardingActivity` → `GetLineActivity`; удалить override `shouldDisplayHomeAsUpEnabled`
4. расширить гейт

## Гейт

В `scripts/check-product-boundary.sh`:
- добавить в `TARGETS`: `app/src/main/java/pro/getline/vpn/product/**`
- добавить в `ID_PATTERN`: `ClashException`, `BaseActivity`

## AC 2a

- [ ] `GetLineHomeActivity` и `GetLineOnboardingActivity` наследуют `GetLineActivity`, не `BaseActivity`
- [ ] `git diff` по `BaseActivity.kt` пуст; legacy Activities не изменены
- [ ] `scripts/check-product-boundary.sh` — ok; негативный тест (вставить `ClashException` в `GetLineActivity`) — падает
- [ ] `./gradlew :app:assembleAlphaDebug :app:testAlphaDebugUnitTest` — EXIT=0
- [ ] Устройство: Home открывается, табы переключаются, back из таба → Home, back с Home → выход;
      Onboarding: вход, email/OTP, кнопка «назад» из OTP; 7 тапов по бренду; VPN start/stop;
      тост при остановке VPN с причиной

---

# 2b — `refactor/product-design-base`

Только после 2a.

## Что реально используется из `Design<*>`

| Член | Где | Примечание |
|---|---|---|
| `abstract val root: View` | оба Design | |
| `requests: Channel<R>` | оба Design | `trySend` |
| `context` | оба Design | |
| `CoroutineScope by CoroutineScope(Dispatchers.Unconfined)` | оба | `cancel()` из базы Activity |
| `showToast(resId/message, duration)` | Home (4 места) + база Activity | |
| **`surface: Surface`** | **оба layout'а через databinding** | см. ловушку |

## ⚠️ Главная ловушка — `surface` используется из XML, не из Kotlin

Grep по `.kt` даёт ноль вхождений `surface`. Но:

```
design/src/main/res/layout/design_get_line_home.xml:79-80,104-105
design/src/main/res/layout/design_get_line_onboarding.xml:35-36,49-50
    android:paddingStart="@{self.surface.insets.start}"
    android:paddingTop="@{self.surface.insets.top + (int) @dimen/get_line_home_section_gap}"
    ...
```

`self` — databinding-переменная, ссылающаяся на Design. Значит продуктовая база **обязана**
предоставлять `surface` с тем же типом инсетов, иначе layout не скомпилируется, а если
подменить типом с другой формой — молча поедут отступы под статус-баром и навбаром.

Плюс `Design.init` (`Design.kt:50-59`) вешает `setOnInsertsChangedListener` на `decorView`
и обновляет `surface.insets`. Без этого инсеты останутся нулевыми — визуально контент
уедет под системные панели. Перенести целиком.

Решение на 2b: `Surface` и `setOnInsertsChangedListener` **переиспользовать из `:design`**
(`design.ui.Surface`, `design.util.setOnInsertsChangedListener`), не копировать. Они уедут
в срезе 4 вместе с layout'ами. Копирование сейчас создаст две реализации инсетов.

## Что копировать в продуктовый код

Мелкие extension'ы, дублирование дешевле связи:
- `design/util/Context.kt:12` `val Context.layoutInflater`
- `design/util/Context.kt:15` `val Context.root`

`ToastDuration` — завести продуктовый enum, маппить внутри базы.

## Новый файл

`design/src/main/java/pro/getline/vpn/design/GetLineScreen.kt` — пока в `:design`, потому что
там лежат layouts и генерируемые databinding-классы. Переедет в срезе 4.

```kotlin
abstract class GetLineScreen<R>(val context: Context) :
    CoroutineScope by CoroutineScope(Dispatchers.Unconfined)
```

`GetLineHomeDesign` и `GetLineOnboardingDesign` наследуют его вместо `Design<R>`.
Параметр `GetLineActivity<D>` меняется с `D : Design<*>` на `D : GetLineScreen<*>`.

## AC 2b

- [ ] Продуктовые Design не наследуют `Design<*>`
- [ ] Инсеты работают: контент не под статус-баром и не под навбаром, в обоих экранах,
      с включённой и выключенной навигацией жестами
- [ ] `Design.kt` не изменён; legacy Design'ы не изменены
- [ ] Гейт, сборка, тесты — как в 2a
- [ ] Устройство: полный прогон из AC 2a + визуальная сверка отступов со скриншотами до/после

## Что останется после 2b (для среза 4)

Продуктовый код всё ещё зависит от `:design` через: layouts + databinding, `design.R.string.*`,
`design.R.style.AppThemeDark`, `design.ui.Surface`, `design.store.UiStore`,
`design.model.GetLine*`, `design.util.*`. Это и есть содержание среза 4.
