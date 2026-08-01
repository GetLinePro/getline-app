# Link-only подписки, срез 1: честная карточка без аккаунта

Спецификация на исполнение. База: `main` @ `6e4e993d`.

Читать до правок: `CLAUDE.md` (раздел про browser auth), `docs/refactor/README.md`
(решения 2 и 3 действуют — продуктовые модели в `:getlineui`, `core`/`service`
продуктовому коду закрыты).

---

## Проблема

Пользователь импортировал подписку по ссылке. Аккаунт-сессии нет, поэтому вкладка
«Подписка» уходит в `SubscriptionUiState.SignedOut` и рисует заглушку
«Управление подпиской недоступно» (`GetLineHomeDesign.kt:879`), хотя все данные
подписки лежат локально и VPN работает.

Одновременно внизу той же вкладки висит «Выйти из аккаунта»:
`shouldShowLogout()` (`GetLineHomeActivity.kt:1021`) возвращает `true` уже при
наличии `managedProfileUuid()`, без всякой сессии. Интерфейс предлагает выйти
оттуда, куда пользователь не входил.

Это ложь в двух местах сразу, и лечится она без единого обращения к account API.

## Что уже есть и переиспользуется

Ничего из перечисленного строить заново не нужно.

- **Локальные метаданные подписки.** `subscription-userinfo` читается в
  `core/src/main/golang/native/config/fetch.go:66`, доезжает до Kotlin как
  `FetchStatus.Action.SubscriptionInfo` и пишется в строку `Imported`
  (`ProfileProcessor.kt:68-71` при импорте, `:118-129` при обновлении).
- **Доступ к ним из продуктового кода.** `backend.subscriptions.snapshot()` уже
  возвращает `GetLineSubscriptionSnapshot(active, hasImported)`, где `active` —
  это `GetLineSubscriptionSummary(name, expire, upload, download, total)`,
  собранный напрямую из `Profile` (`CmfaGetLineBackend.kt:365-373`). Нового
  метода в `GetLineSubscriptionRepository` не нужно.
- **Флаг «профиль есть».** `SubscriptionUiState.SignedOut.hasImportedProfile`
  существует и заполняется (`GetLineHomeActivity.kt:840`), но в рендере
  игнорируется — обе ветки рисуют один текст. Это и есть шов.
- **Обновление профиля.** `requestConfigUpdate(id)` (`GetLineBackend.kt`,
  реализация `CmfaGetLineBackend.kt:316`) — тихий re-fetch без нотификаций.
- **Кнопка обновления и строка ошибки в карточке.** `refresh_subscription` и
  `subscription_transient_error` уже в `design_get_line_home.xml` (:604, :591).
- **Удаление профиля + остановка VPN.** `performLogout()`
  (`GetLineHomeActivity.kt:1034`) уже делает ровно нужную последовательность.

## Решение

`SignedOut` остаётся единственным состоянием без сессии. Нового
`SubscriptionUiState` не заводим — иначе `Ready` начнёт означать два разных
источника данных, и вместе с ним поедут портал оплаты и устройства.

---

## Изменения

### 1. `GetLineSubscriptionSummary` += `uuid`

`app/src/main/java/pro/getline/vpn/getline/GetLineBackend.kt:168`

```kotlin
data class GetLineSubscriptionSummary(
    val uuid: String,
    val name: String,
    val expire: Long,
    val upload: Long,
    val download: Long,
    val total: Long,
)
```

Заполнить в `CmfaGetLineBackend.toGetLineSummary()` (`:365`) из `uuid.toString()`.

**Зачем.** `snapshot().active` — это *активный* профиль, а не обязательно
*managed*. Через Advanced можно выбрать посторонний. Без uuid карточка описывала
бы один профиль, а «Удалить подписку» сносила другой. Карточку рисуем только при
`summary.uuid == sessionRepository.managedProfileUuid()`.

### 2. Маппер локальных данных

`app/src/main/java/pro/getline/vpn/getline/auth/SubscriptionUiModels.kt` — рядом с
`SubscriptionPresentation`, тем же стилем:

```kotlin
/**
 * Presentation for a subscription imported by link (no account session).
 * Built from the local Imported row via [GetLineSubscriptionSummary] — the app
 * knows only what the subscription response itself carried.
 */
data class LinkOnlyPresentation(
    val expireAtEpochMillis: Long?,
    val trafficUsedBytes: Long?,
    val trafficLimitBytes: Long?,
)
```

Правила (единственное место, где 0 трактуется как «неизвестно»):

- `expire <= 0` → `expireAtEpochMillis = null` → строка срока
  `get_line_home_expire_unknown`. Ноль в `Imported.expire` — это «заголовок не
  пришёл», а не 01.01.1970.
- `total <= 0` → `trafficLimitBytes = null`, прогресс-бар скрыт
  (`trafficUsedFraction = null`). Не рисовать «0 Б» как лимит.
- `upload + download == 0 && total <= 0` → `trafficUsedBytes = null` → строка
  `get_line_home_traffic_unknown`. Отличить «нулевой расход» от «заголовка не
  было» невозможно, поэтому при полном отсутствии цифр показываем «неизвестно».
- `expire` в прошлом **скрывать нельзя** — это достоверный факт, показываем как
  есть. Плашку статуса при этом не рисуем: `isActive` из локальных данных
  вывести нечестно.
- Имя профиля не показываем. Заголовок карточки — фиксированный
  «Подписка добавлена по ссылке».

**Время обновления не показываем вообще.** `Profile.updatedAt` — это mtime
каталога (`ProfileManager.resolveUpdatedAt:217`), он говорит об обновлении
конфигурации, а не о свежести `subscription-userinfo`. Разводить эти два смысла
можно будет, когда появится явный маркер получения заголовка.

### 3. Состояние

`SubscriptionUiModels.kt`:

```kotlin
data class SignedOut(
    val hasImportedProfile: Boolean,
    val linkOnly: LinkOnlyPresentation? = null,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
) : SubscriptionUiState
```

`SubscriptionStateHolder.kt`:

- `applySignedOut(hasImportedProfile, linkOnly)` — добавить параметр.
- **новый** `beginLinkOnlyRefresh(): Boolean` — `false`, если `requestInFlight`;
  иначе `requestInFlight = true` и `copy(isRefreshing = true, refreshFailed = false)`.
  Существующий `beginRefresh()` для этого не годится: он переводит `SignedOut` в
  `Loading` (`:55-59`) и карточка пропадёт на весь запрос.
- **новый** `applyLinkOnlyRefreshResult(linkOnly, failed)` — снимает
  `requestInFlight`, кладёт свежий `linkOnly` (или `null`, если профиль исчез) и
  флаг ошибки.
- `onRequestCancelled()` (`:118`) сейчас чинит только `Ready`. Добавить ветку
  `SignedOut` → `copy(isRefreshing = false)`. Без этого отмена джобы при
  повороте/уходе с вкладки оставит крутилку навсегда: `requestInFlight`
  снимается, а `isRefreshing` — нет.
- `needsInitialLoad()` не трогать: `SignedOut` по-прежнему не тянет сеть.

### 4. Хост: сборка состояния

`GetLineHomeActivity.kt`

Единая точка вместо трёх нынешних вызовов `applySignedOut(hasImportedProfile = …)`
(`:839`, `:845`, `:875`, `:906`):

```kotlin
private suspend fun applySignedOutState() {
    val managed = sessionRepository.managedProfileUuid()
    val summary = if (managed == null) null else snapshotActiveSummary()
    val linkOnly = summary
        ?.takeIf { it.uuid == managed }
        ?.let { LinkOnlyPresentation.fromSummary(it) }
    subscriptionState.applySignedOut(
        hasImportedProfile = hasKnownImportedProfile,
        linkOnly = linkOnly,
    )
}
```

`snapshotActiveSummary()` — обёртка над `backend.subscriptions.snapshot()`,
возвращающая `null` на `GetLineBackendResult.Unavailable`. Побочный эффект
оставить прежним: `hasKnownImportedProfile` / `hasKnownActiveProfile`
обновляются, как в `refreshSnapshotFlags()` (`:527`).

`applySignedOut` вызывается из suspend-контекста — все четыре нынешних места уже
внутри `launch`/suspend-функций, кроме `refreshSubscriptionUi(:906)`; там
завернуть в тот же `launch`, что и `paintSubscriptionState()`.

### 5. Обновление link-only

Кнопка та же (`refresh_subscription` → `Request.RefreshSubscription`). Ветка по
сессии в обработчике (`GetLineHomeActivity.kt:246`):

```
hasSession()  → refreshSubscriptionUi(force = true)   // как сейчас
иначе         → refreshLinkOnlySubscription()
```

```kotlin
private fun GetLineHomeDesign.refreshLinkOnlySubscription() {
    val managed = sessionRepository.managedProfileUuid() ?: return
    if (!subscriptionState.beginLinkOnlyRefresh()) return
    paintSubscriptionState()
    subscriptionLoadJob?.cancel()
    subscriptionLoadJob = launch {
        try {
            val updated = backend.subscriptions
                .requestConfigUpdate(GetLineSubscriptionId(managed))
            if (!isActive) return@launch
            val summary = snapshotActiveSummary()?.takeIf { it.uuid == managed }
            subscriptionState.applyLinkOnlyRefreshResult(
                linkOnly = summary?.let(LinkOnlyPresentation::fromSummary),
                failed = updated is GetLineBackendResult.Unavailable,
            )
            paintSubscriptionState()
        } finally {
            if (!isActive) subscriptionState.onRequestCancelled()
        }
    }
}
```

**Пути ошибок — прозой, потому что по диффу их не видно.**

- `requestConfigUpdate` возвращает `Unavailable` на *любом* сбое: таймаут 60 с,
  разрыв IPC, 401/404 от сервера подписки — всё сваливается в один catch
  (`CmfaGetLineBackend.kt:346-364`). Отличить отозванную ссылку от отсутствия
  сети в этом срезе нельзя, поэтому сообщение одно.
- При ошибке профиль **не удаляем**, `deleteManaged` не зовём, VPN не трогаем.
  Mihomo продолжает работать на последнем успешно записанном конфиге: новый
  конфиг пишется в `importedDir` только после удачного fetch
  (`ProfileProcessor.kt:110-116`), провал не портит существующий.
- Карточку пересобираем из `snapshot()` **в любом случае** — даже после провала.
  Цифры не изменятся, но так не бывает расхождения между показанным и лежащим на
  диске.
- `requestConfigUpdate` возвращает `Success` и когда профиля нет, и когда он
  типа `File` (`CmfaGetLineBackend.kt:322-326`) — это не доказательство, что
  fetch был. Поэтому решает не результат, а последующий `snapshot()`: если
  `active == null` или uuid не совпал с managed, кладём `linkOnly = null` и
  карточка честно исчезает, показывая старую заглушку со «Войти».
- Параллельное нажатие: второй тап отсекается `requestInFlight` внутри
  `beginLinkOnlyRefresh()`. Очередь отложенных force-обновлений
  (`pendingForceSubscriptionRefresh`) на этот путь не распространяется — она
  нужна только для возврата из портала, которого у link-only нет.

### 6. Кнопка внизу: два режима одной операции

`GetLineHomeActivity.kt:1021` — вместо `shouldShowLogout(): Boolean`:

```kotlin
enum class AccountAction { None, SignOut, RemoveSubscription }

private fun accountAction(): AccountAction = when {
    sessionRepository.hasSession() -> AccountAction.SignOut
    sessionRepository.managedProfileUuid() != null -> AccountAction.RemoveSubscription
    else -> AccountAction.None
}
```

`GetLineHomeDesign.setLogoutVisible(Boolean)` → `setAccountAction(AccountAction)`:
меняет `visibility`, `text` и `contentDescription` у существующей кнопки
`logout_account`. Новой кнопки в разметке нет.

`performLogout(action)` — тело не меняется вообще. Режим влияет только на строки
подтверждения в `confirmLogout()`. Отдельная ветка не нужна: в режиме
`RemoveSubscription` токенов просто нет, и `sessionRepository.logout()` вычистит
только ключи биндинга (`GetLineSessionStore.clearAccountState`), а
`deleteManaged` и `openOnboarding` отработают ровно так же.

Это переименование операции, а не второй жизненный цикл профиля.

### 7. Рендер

`getlineui/.../GetLineHomeDesign.kt`

```kotlin
data class SignedOut(
    val hasImportedProfile: Boolean,
    val card: CardContent? = null,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
) : SubscriptionScreen
```

Ветка `is SubscriptionScreen.SignedOut` (`:879`):

- `card == null` → как сейчас: `applyCard(null, …)`, `applyAccountPortalUi(false, …)`,
  `renderMessage(signed_out_title / _explanation, action = SignIn)`.
- `card != null` → `subscriptionStateView.hide()`,
  `applyCard(card, isRefreshing, transientError = refreshFailed)`,
  `applyAccountPortalUi(visible = false, …)`, показать новый `link_only_block`.

Текст `subscription_transient_error` в link-only-ветке подменяется на
`get_line_subscription_link_only_refresh_failed` — TextView тот же.

`CardContent` для link-only:

| поле | значение |
|---|---|
| `title` | `get_line_subscription_link_only_title` |
| `isActive` / `statusText` | `false` / `null` — плашки нет |
| `daysLeft` | `null` |
| `devicesText` | `null` — строка скрыта |
| `expireText` / `trafficText` | из `LinkOnlyPresentation`, «неизвестно» по правилам §2 |
| `trafficUsedFraction` | `null`, если лимит неизвестен |

**Инвариант, который надо удержать:** `applyAccountPortalUi(visible = true)`
допустим только при наличии сессии. Сейчас `Ready`/`Empty`/`Failed` передают
`true` безусловно (`:851-878`), и это верно ровно потому, что хост не производит
эти состояния без сессии (`refreshSubscriptionUi:900`). Ветку `SignedOut`
трогать нельзя — портал в ней всегда скрыт.

### 8. Разметка

`getlineui/src/main/res/layout/design_get_line_home.xml`, между `subscription_card`
(:630) и `open_help` (:706) — блок по образцу `account_portal_block` (:634):

```
LinearLayout @+id/link_only_block  (visibility=gone)
├── TextView   — get_line_subscription_link_only_explanation
└── MaterialButton @+id/link_only_sign_in — get_line_subscription_link_only_sign_in
```

Клик → `request(Request.SignIn)` — тот же запрос, что у заглушки, хост уже умеет
его обрабатывать. Порядок обхода TalkBack: карточка → пояснение → «Войти для
управления» → «Помощь» → «Удалить подписку с устройства». Деструктивное
действие остаётся последним, как в комментарии к разметке (:717).

### 9. Строки

`getlineui/src/main/res/values/strings.xml` + `values-ru/strings.xml`:

| ключ | RU |
|---|---|
| `get_line_subscription_link_only_title` | Подписка добавлена по ссылке |
| `get_line_subscription_link_only_explanation` | Для управления оплатой и устройствами войдите в аккаунт владельца подписки. |
| `get_line_subscription_link_only_sign_in` | Войти для управления |
| `get_line_subscription_link_only_refresh_failed` | Не удалось обновить подписку. Используются последние сохранённые настройки. |
| `get_line_action_remove_subscription` | Удалить подписку с устройства |
| `get_line_remove_subscription_confirm_title` | Удалить подписку с этого устройства? |
| `get_line_remove_subscription_confirm_message` | VPN будет отключён, конфигурация удалена с устройства. Ссылку можно импортировать снова. |

Существующие `get_line_subscription_signed_out_title/_explanation` остаются — их
показывает ветка без карточки.

### 10. Диагностика

`SessionSubscriptionConsistency.kt:16-19` — комментарий к `BindingWithoutSession`
называет состояние «unexpected right after browser login». Переформулировать:
это штатное установившееся состояние link-only подписки; аномалией остаётся
только сочетание «сессия есть, а биндинга нет» после логина.

`Log.w("subscription_ui inconsistent SignedOut while has_refresh=true")`
(`GetLineHomeActivity.kt:1005`) **оставить как есть** — он срабатывает при
`hasSession && SignedOut`, что по-прежнему противоречие.

---

## Не входит в срез

- Сопоставление импортированной ссылки с подписками аккаунта.
- Полный список `/api/subscriptions` вместо `preferred`.
- Хранение смешанного состояния (сессия + чужая активная подписка).
- Развилка при входе в посторонний аккаунт — она живёт в
  `GetLineOnboardingActivity:600-615`, где после логина импорт идёт безусловно.
- QR-кнопка в онбординге и единая URL-политика.
- Любой новый маркер свежести `subscription-userinfo`.
- Отдельное хранилище метаданных.

---

## Проверка

```
./gradlew :app:testAlphaProdDebugUnitTest :app:testAlphaE2eDebugUnitTest
```

Оба флейвора обязательны: срез трогает ветвление по `hasSession()`
(`CLAUDE.md`, раздел browser auth).

Новые тесты:

- `LinkOnlyPresentationTest` — `expire = 0` → `null`; `expire` в прошлом →
  показывается; `total = 0` → лимит и доля `null`; `upload = download = total = 0`
  → трафик неизвестен; `total > 0, used = 0` → доля `0f`.
- `SubscriptionStateHolderTest` (дополнить) — `beginLinkOnlyRefresh` сохраняет
  `linkOnly` и ставит `isRefreshing`; повторный вызов при `requestInFlight`
  возвращает `false`; `onRequestCancelled` снимает `isRefreshing` у `SignedOut`;
  `applyLinkOnlyRefreshResult(linkOnly = null)` убирает карточку.

Ручная проверка на устройстве:

1. Импорт по ссылке без логина → вкладка «Подписка» показывает карточку, снизу
   «Войти для управления», внизу «Удалить подписку с устройства».
2. «Выйти из аккаунта» и блок личного кабинета не видны нигде.
3. Обновление в самолётном режиме → карточка остаётся, появляется строка ошибки,
   VPN не рвётся, профиль на месте.
4. «Удалить подписку с устройства» → подтверждение с новым текстом → онбординг.
5. Логин аккаунтом → карточка API, «Выйти из аккаунта» вернулась. (Замена
   подписки здесь по-прежнему происходит молча — это срез 3.)

## Риски и что осталось незакрытым

- **Карточка может показывать устаревший трафик.** `ProfileProcessor.kt:118`
  пишет цифры только при непустом `subscription-userinfo`; сервер без заголовка
  оставит прошлые значения. Мы не показываем время обновления, поэтому ложной
  свежести нет, но и признать устаревание не можем. Принято сознательно.
- **Отдаёт ли прод-бэкенд `subscription-userinfo` по реальной ссылке — не
  проверено.** В e2e-моке отдаёт (`tools/e2e-mock/main.go:410`). Если в проде не
  отдаёт, карточка выродится в «Подписка добавлена по ссылке» + два «не
  передаются подпиской». Это всё равно лучше нынешней заглушки, но проверить
  стоит до мержа.
- **Молчаливая замена подписки при входе в чужой аккаунт остаётся.** Срез её не
  чинит и не ухудшает.
- **Орфан-профиль после замены остаётся.** Если сервер отдал
  `profile-update-interval`, `ProfileWorker` продолжит ходить по старой ссылке.
  Отдельный вопрос, к этому срезу не относится.

## Объём и релиз

Ориентировочно: ~250-350 строк продуктового кода, ~120 строк тестов, ~40 строк
разметки, 7 строковых ключей × 2 локали. Затронуто 8 файлов, ни одного
upstream-CMFA.

PR-лейбл: `release:patch`, тип коммита `fix` — это починка обещанного флоу
(`docs/release-policy.md`), несмотря на новые файлы.
