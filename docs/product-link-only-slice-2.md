# Link-only подписки, срез 2: вход в аккаунт не подменяет подписку молча

Спецификация на исполнение. База: `main` @ `2de2eec4` (срез 1 закрыт в `193d7540`).

Читать до правок: `docs/product-link-only-slice-1.md` (состояние `SignedOut`,
`AccountAction`, link-only карточка), `CLAUDE.md` — раздел про browser auth.

---

## Проблема

`GetLineOnboardingActivity.importPreferredSubscription()` (`:580`) после успешного
логина **безусловно** импортирует предпочтительную подписку аккаунта. `reuseId`
берётся только при совпадении `subscription.id` с `rememberedSubscriptionId()`
(`:602-607`), а у link-only пользователя `subscriptionId` не записан никогда —
он ставится только на пост-логин пути. Значит `reuseId = null`, создаётся новый
профиль, `rememberManagedProfile` (`:681`) переводит привязку на него, а
импортированный по ссылке профиль остаётся в `ImportedDao` ничьим.

Пользователь этого не выбирал и об этом не узнаёт.

Срез 1 сделал проблему достижимой намеренно: кнопка «Войти для управления»
(`link_only_sign_in`) ведёт ровно в этот путь.

## Принятые решения

Не пересматривать без причины.

1. **«Оставить текущую подписку» сбрасывает сессию.** Две опции, не три.
   Смешанного состояния «сессия есть, активна чужая подписка» не существует ни
   секунды, persisted-флаг не нужен, вкладка «Подписка» остаётся link-only.
2. **Принадлежность определяется сравнением ссылок** — `managedProfileSource`
   против `subscription_link` каждого элемента полного списка
   `/api/subscriptions` после канонизации. Совпало — диалога нет.

---

## Изменения

### 1. Полный список за тот же round-trip

`app/src/main/java/pro/getline/vpn/getline/auth/GetLineSessionRepository.kt:126`

```kotlin
data class PreferredSubscriptionLoad(
    val preferred: SubscriptionItem,
    val all: List<SubscriptionItem>,
)

suspend fun loadPreferredSubscriptionWithList(
    provisionTrialIfEmpty: Boolean = false,
    onProvisioningTrial: suspend () -> Unit = {},
): PreferredSubscriptionLoad
```

`loadPreferredSubscription()` остаётся тонкой обёрткой (`.preferred`) — её зовут
`loadPreferredSubscriptionOrNull()` (`:145`) и путь ремонта в
`GetLineHomeActivity:415`, их трогать не нужно.

Внутри держать **последний** прочитанный `SubscriptionsResponse`: на trial-пути
список читается дважды (`:130-134`), в результат должен уйти второй.
Проверка `requireSubscriptionUrl(selected.subscriptionLink)` (`:138`) остаётся на
`preferred` — список для сравнения не валидируем, мы из него ничего не импортируем.

### 2. Канонизация и сравнение

Новый файл `app/src/main/java/pro/getline/vpn/getline/auth/SubscriptionLinkMatcher.kt`:

```kotlin
/**
 * Canonical form of a subscription URL for account-ownership comparison.
 * Deliberately conservative: a false "no match" costs one dialog, a false
 * "match" silently binds a profile to a foreign subscription.
 */
object SubscriptionLinkMatcher {
    fun canonical(url: String?): String?
    fun matchesAny(source: String?, items: List<SubscriptionItem>): Boolean
}
```

Правила `canonical`:

| вход | результат |
|---|---|
| `null` / пусто | `null` |
| scheme не `https` | `null` |
| непустой userInfo | `null` |
| хост | `GetLineControlPlaneHostPolicy.canonicalizeHost` — lowercase, хвостовые точки срезаны |
| порт | `443` и отсутствие порта → опустить; иной порт сохранить |
| path | один хвостовой `/` отбросить, остальное байт-в-байт |
| query | байт-в-байт, без сортировки и без перекодирования |
| fragment | отбросить — на сервер не уходит |

Итог: `https://host[:port]path[?query]`.

`matchesAny` = `canonical(source) != null && canonical(source) in items.mapNotNull { canonical(it.subscriptionLink) }`.

**Никаких «похожих» сравнений**: без префиксов, без подстрок, без вытаскивания
токена, без нормализации процентного кодирования. Пустой список → `false`.

**Приватность.** Ни исходный, ни канонический URL не логируются никогда — ни в
logcat, ни в диагностику (GL-19 их и так режет регуляркой,
`DiagnosticReportBuilder.kt:141`, но полагаться на неё нельзя). Единственная
допустимая запись:

```
Log.i("link_match matched=$matched account_items=${items.size}")
```

### 3. Предикат «профиль пришёл по ссылке»

Чистая функция, отдельно от Activity — тестируется без Android:

```kotlin
// app/src/main/java/pro/getline/vpn/getline/auth/LinkOnlyBindingPolicy.kt
object LinkOnlyBindingPolicy {
    fun isLinkOnlyBinding(
        managedUuid: String?,
        managedSource: String?,
        rememberedSubscriptionId: String?,
    ): Boolean =
        !managedUuid.isNullOrBlank() &&
            !managedSource.isNullOrBlank() &&
            rememberedSubscriptionId.isNullOrBlank()
}
```

**Почему именно так.** `subscriptionId` пишется только через
`rememberSubscription(...)` на пост-логин пути (`GetLineOnboardingActivity:683`).
Привязка без него — это ровно «профиль импортирован по ссылке, аккаунта тогда не
было». Совпадает с `SessionSubscriptionConsistency.BindingWithoutSession`.

### 4. Развилка в `importPreferredSubscription`

Считать предикат **внутри** функции, а не пробрасывать параметром: она же
вызывается повторно по `RetryTarget.ImportPreferredSubscription` (`:833`, `:882`),
а `establishFromWebToken` (`GetLineSessionRepository:28`) пишет только токены и
`customerId` — привязку и `subscriptionId` не трогает. Значит после логина и на
ретрае предикат читается одинаково.

Порядок в функции: загрузить список → посчитать предикат → решить → импортировать.

```
linkOnly = LinkOnlyBindingPolicy.isLinkOnlyBinding(managedUuid, managedSource, rememberedSubscriptionId)

!linkOnly                                   → сегодняшний путь, без изменений
linkOnly && matchesAny(managedSource, all)  → сегодняшний путь + reuseId = managed uuid
linkOnly && !matchesAny(...)                → диалог
```

**Ветка совпадения важна не меньше развилки.** Сегодня `reuseId` вычисляется
только через равенство `subscription.id`, которого у link-only нет. Без второго
основания для reuse пользователь, импортировавший **собственную** ссылку и
вошедший в **свой** аккаунт, получил бы второй профиль с тем же содержимым и
осиротевший первый. Добавить к существующему выражению (`:602-607`) второе
условие: `reuseId = managedUuid`, когда сработал link-match.

### 5. Диалог

`GetLineOnboardingDesign` — по образцу `confirmLogout` из `GetLineHomeDesign:365`:

```kotlin
enum class MismatchChoice { UseAccount, KeepLinkOnly }

suspend fun confirmAccountMismatch(): MismatchChoice
```

- `setCancelable(false)`, back не закрывает. Нейтральной кнопки нет: обе ветки
  ведут в рабочее состояние, «отложить» здесь означало бы оставить пользователя
  с сессией и чужой активной подпиской — то самое смешанное состояние, которого
  мы избегаем по решению 1.
- positive → `UseAccount`, negative → `KeepLinkOnly`.
- Отмена корутины (Activity уничтожен во время диалога) — как в `confirmLogout`:
  диалог снимается, ничего не записывается. Сессия и профиль остаются как были,
  следующий запуск придёт на `RetryTarget.ImportPreferredSubscription`.

Строки, `values/` + `values-ru/`:

| ключ | RU |
|---|---|
| `get_line_account_mismatch_title` | Текущая подписка не найдена в этом аккаунте |
| `get_line_account_mismatch_message` | Подписка, добавленная по ссылке, не принадлежит этому аккаунту. Выберите, какую использовать на этом устройстве. |
| `get_line_account_mismatch_use_account` | Использовать подписку из аккаунта |
| `get_line_account_mismatch_keep_link` | Оставить текущую — выйти из аккаунта |

### 6. Ветка `UseAccount` — уборка старого профиля

Сегодняшний импорт плюс удаление осиротевшего профиля.

- До импорта запомнить `previousManagedUuid = sessionRepository.managedProfileUuid()`.
- В `onTerminal` → `ImportTerminal.Success` (`GetLineOnboardingActivity:680-690`),
  **после** `rememberManagedProfile` и `rememberSubscription`, если
  `previousManagedUuid != null && previousManagedUuid != result.id.value`:
  `runCatching { backend.subscriptions.deleteManaged(GetLineSubscriptionId(previousManagedUuid)) }`.

**Порядок обязателен, и по диффу он не читается.** Удаляем только после того, как
новая привязка уже записана в стор. Провал удаления оставит лишний профиль в
`ImportedDao` — неприятно, но безопасно, и Home этого профиля не увидит.
Обратный порядок (удалить раньше импорта) при сбое сети оставил бы пользователя
вообще без конфига и без ссылки, по которой его можно вернуть.

Зачем удалять вообще: у профиля мог остаться `interval > 0` из заголовка
`profile-update-interval` (`ProfileProcessor.kt:59-61`), и `ProfileWorker`
продолжал бы бессрочно ходить по чужой ссылке в фоне.

### 7. Ветка `KeepLinkOnly` — самое опасное место среза

**`sessionRepository.logout()` использовать нельзя.** Он зовёт
`GetLineSessionStore.clearAccountState()` (`:113-129`), который стирает
`KEY_PROFILE_UUID` и `KEY_PROFILE_SOURCE` — ровно ту привязку, которую
пользователь только что решил сохранить. Профиль остался бы на диске ничьим:
Home не смог бы его чинить и обновлять, а «Удалить подписку с устройства»
исчезло бы вместе с ним (`accountAction()` смотрит именно на
`managedProfileUuid()`), и снять его стало бы можно только через Advanced.

Нужен узкий сброс. `GetLineSessionStore`:

```kotlin
/**
 * Drop the account session but keep the link-only managed binding.
 * Used when the user declines to replace a link-imported subscription:
 * the profile stays theirs to refresh and remove.
 */
fun clearSessionKeepingBinding() {
    prefs.edit {
        remove(KEY_ACCESS_TOKEN)
        remove(KEY_REFRESH_TOKEN)
        remove(KEY_ACCESS_EXPIRES_AT)
        remove(KEY_CUSTOMER_ID)
        remove(KEY_SUBSCRIPTION_ID)
        // pending import keys: this login is abandoned
        remove(KEY_PENDING_IMPORT_NAME)
        remove(KEY_PENDING_IMPORT_SOURCE)
        remove(KEY_PENDING_IMPORT_TYPE)
        remove(KEY_PENDING_IMPORT_REUSE_UUID)
        remove(KEY_PENDING_IMPORT_SUBSCRIPTION_ID)
        remove(KEY_PENDING_IMPORT_INTERVAL)
    }
}
```

`KEY_PROFILE_UUID` и `KEY_PROFILE_SOURCE` намеренно не тронуты — это единственное
отличие от `clearAccountState()`, и весь смысл метода в нём.

`GetLineSessionRepository`: `fun discardSessionKeepingSubscription() = store.clearSessionKeepingBinding()`.

Дальше в ветке:

- `GetLineImportCoordinator.reset()` **не нужен** — импорт ещё не стартовал,
  развилка стоит до `importSubscription`.
- VPN не трогаем, профиль не трогаем, `deleteManaged` не зовём.
- `backend.navigation.openHome()`.

Home на `onStart` увидит «сессии нет + привязка есть» и через
`applySignedOutState()` нарисует link-only карточку среза 1. Отдельного кода для
этого не нужно.

### 8. Пути ошибок

- **API недоступно или 401 при чтении списка.** Сегодняшнее поведение сохраняется:
  исключение уходит в `applyLoginFailure` → `AuthFailed` с ретраем на
  `ImportPreferredSubscription`. Сессия остаётся, link-only профиль не тронут,
  развилка не показывается — мы не знаем состава аккаунта и не имеем права
  ничего решать. Менять нечего.
- **В аккаунте нет ни одной подписки со ссылкой** (даже после trial) — сегодняшнее
  `Protocol("No subscription with import URL")` → `AuthFailed`. Развилку показать
  не из чего: предлагать «использовать подписку из аккаунта» нечего, а молча
  выкидывать из аккаунта неверно. Оставляем как есть; известное ограничение.
- **Пользователь выбрал `UseAccount`, импорт провалился.** Сегодняшний путь:
  `ImportFailed`/`Offline` с ретраем. Старый профиль **ещё не удалён** (удаление
  живёт в Success-ветке), привязка не перезаписана — пользователь остаётся со
  своей link-only подпиской и живой сессией. Повторный ретрай снова покажет
  развилку только если совпадения по-прежнему нет.

### 9. Тесты

Новые:

- `SubscriptionLinkMatcherTest` (Robolectric, как `GetLineControlPlaneHostPolicyTest`):
  хвостовой слэш; регистр хоста; точка в конце хоста; `:443` против без порта;
  различающийся fragment → совпадение; различающийся query → **нет** совпадения;
  `http://` → нет; userInfo → нет; пустой список → нет; несколько элементов, где
  совпадает не первый.
- `LinkOnlyBindingPolicyTest` — чистый, без Android: все четыре комбинации входов.

Дополнить:

- `SubscriptionLoadRepositoryTest` — `loadPreferredSubscriptionWithList` отдаёт
  весь список; на trial-пути список берётся из **второго** ответа.

Стор проверять через fallback-файл: `GetLineSessionStore` падает в
`FILE_NAME_FALLBACK` при недоступном keystore (`:151-154`), в Robolectric это
штатный путь. Проверить, что `clearSessionKeepingBinding()` стирает токены и
сохраняет `managedProfileUuid` / `managedProfileSource`.

```
./gradlew :app:testAlphaProdDebugUnitTest :app:testAlphaE2eDebugUnitTest
```

Оба флейвора обязательны — срез правит путь логина.

Ручная проверка:

1. Импорт по ссылке → «Войти для управления» → вход в **чужой** аккаунт →
   развилка. «Оставить текущую» → Home, карточка link-only на месте, VPN не
   рвался, внизу «Удалить подписку с устройства».
2. То же, но «Использовать подписку из аккаунта» → карточка API, «Выйти из
   аккаунта», старого профиля в Advanced → Профили нет.
3. Импорт **собственной** ссылки → вход в **свой** аккаунт → развилки нет,
   профиль переиспользован (в Advanced один профиль, не два).
4. Вход при выключенной сети → `AuthFailed` с ретраем, профиль и карточка целы.

## Не входит

- QR и единая URL-политика для ручного ввода — отдельный срез.
- Непрозрачный `subscription_id` вместо сравнения URL.
- Хранение смешанного состояния (закрыто решением 1).
- Серверный `/s/<token>`, pairing, device-сессии.

## Риски и известные ограничения

- **Сравнение по URL сломается, если RWP выдаёт разные ссылки на одну подписку**
  (например, при ротации токена). Пользователь увидит развилку на собственной
  подписке. Ошибка безопасная — лишний диалог, ничего не удаляется, — но
  заметная. Настоящее лечение: непрозрачный идентификатор подписки в обоих
  каналах, это отдельный контракт с бэкендом.
- **`managedProfileSource` хранит то, что ввёл пользователь.** Редиректы не
  учитываются: fetch ходит с `SameHostOnlyRedirect`
  (`core/src/main/golang/native/config/fetch.go:52`), но сохранённой остаётся
  исходная строка. Ссылка, приводящая редиректом к канонической, совпадения не
  даст.
- **Аккаунт с несколькими подписками.** Сравнение идёт по всему списку, и
  импортируется именно совпавший элемент, а не `preferred` — иначе профиль по
  вторичной ссылке молча переписывался бы на основную подписку. Ручной выбор
  подписки из списка в этот срез по-прежнему не входит: при отсутствии
  совпадения импортируется `preferred`.

## Отклонения при реализации

- **Решение 1 ослаблено на маршрутизации запуска.** Форсировать Onboarding при
  «сессия есть, привязка ещё link-only» нельзя: постоянный отказ (у аккаунта нет
  импортируемой подписки) и холодный старт без сети запирали бы рабочий VPN на
  экране с одним Retry. `MainActivity` пускает такого пользователя в Home;
  смешанное состояние может существовать до следующего входа в Onboarding.
- **Незавершённый durable-импорт приоритетнее managed-профиля** при выборе
  экрана запуска, иначе теряется удаление сироты после «использовать подписку из
  аккаунта». Если сети нет, а managed-профиль есть, Onboarding сразу уходит в
  Home, сохраняя pending.

## Объём и релиз

Ориентировочно: ~200-260 строк продуктового кода, ~120 строк тестов, 4 строковых
ключа × 2 локали. Затронуто 6 файлов, ни одного upstream-CMFA.

PR-лейбл: `release:patch`, тип коммита `fix` — починка обещанного поведения
(«вход в аккаунт не удаляет текущую подписку без подтверждения»).
