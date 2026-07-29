# Срез 3: `refactor/product-import-flow`

Спецификация на исполнение. База: `9517f574` (`refactor/product-design-base`).

Предпосылки: срезы 1, 2a, 2b закрыты. Читать сначала `docs/refactor/README.md` —
решения 2, 3, 7, 8 действуют.

---

## Проблема

Продуктовый импорт подписки ходит через CMFA-экран. `GetLineOnboardingActivity:451`:

```kotlin
val result = startActivityForResult(
    ActivityResultContracts.StartActivityForResult(),
    backend.navigation.editSubscription(id),
)
```

`editSubscription` — это `PropertiesActivity` с флагом `EXTRA_GET_LINE_IMPORT`
(`CmfaGetLineBackend.kt:353`). Продуктовый флоу зависит от чужого Activity, его
resultCode и четырёх Intent-extra.

Обратная сторона — `PropertiesActivity` (upstream-файл) переделан под продукт:
`autoCommit` (:45), продуктовый resultData (:122), `RESULT_GET_LINE_IMPORT_FAILED` (:133),
`useProductFetchVocabulary` (:34). То же в `PropertiesDesign` (:44, :182, :211) и
`GetLineFetchStatusCopy.kt` — продуктовый словарь прогресса, живущий в `design.model`
и импортирующий `core.model.FetchStatus`.

Это ровно тот класс правок, из-за которого upstream bump конфликтует: продуктовая
логика вшита в наследуемый файл, а не лежит рядом.

Гейт этот файл **исключает явно** (`scripts/check-product-boundary.sh:32`) — единственное
исключение в скрипте. Срез должен его снять.

### Что уже есть и переиспользуется

`CmfaGetLineSubscriptionRepository.reimportAndActivate` (:173) уже делает headless-импорт:
reuse-или-create → `patch` → `commit(uuid)` → проверка `imported` → `setActive`, с уборкой
осиротевшего UUID при падении и таймаутом `REIMPORT_TIMEOUT_MS = 60_000`. Он в бою на
ремонтной лестнице Home. Новый код пишется **не с нуля** — из него выделяется общая часть.

### Где на самом деле нужен UI

Проверено по всем входам `importSubscription`:

| Вход | `request` | Что делал PropertiesActivity |
|---|---|---|
| `intent.importRequest` (`ExternalControlActivity:37`) | draft с `source` | autoCommit → только прогресс |
| `imports` канал (`onNewIntent`) | draft с `source` | autoCommit → только прогресс |
| после логина (`:395`) | draft с `source` | autoCommit → только прогресс |
| `Request.AddExistingSubscription` (`:99`) | **null** | форма ввода URL + имени, затем Commit |

То есть UI от CMFA нужен **в одной ветке из четырёх** — «добавить существующую подписку»
(CTA на `NoProfile` в онбординге; из Home та же кнопка ведёт в онбординг,
`GetLineHomeActivity:221`). Остальные три — это прогресс-диалог, который продукт и так
переопределяет своим словарём.

## Non-goals

- **Не трогать `NewProfileActivity`, `ProfilesActivity`, `FilesActivity`.** Они ходят в
  `PropertiesActivity` штатным upstream-путём (`setUUID`, без GetLine-extra) — это
  Advanced, решение 7.
- **Не менять `reimportAndActivate` семантику.** Он активирует профиль; новый метод — нет.
- **Не удалять `PropertiesActivity`.** Из него уходит только продуктовая ветка.
- Не переносить файлы в новый модуль — это срез 4.
- Не менять `GetLineProductState`, набор состояний ошибок.

---

## Работа

### Коммит 1 — headless-импорт в продуктовом контракте

`app/.../getline/GetLineBackend.kt`:

```kotlin
/**
 * Create-or-reuse pending profile, patch from [draft], fetch and commit.
 * Does not activate — caller decides (see [activateIfImported]).
 * [onProgress] is best-effort UI feedback; drops intermediate stages under load.
 */
suspend fun importAndCommit(
    draft: GetLineSubscriptionDraft,
    reuseId: GetLineSubscriptionId? = null,
    onProgress: suspend (GetLineImportStage) -> Unit = {},
): GetLineBackendResult<GetLineSubscriptionId>
```

`GetLineImportStage` — в `design/.../design/model/` рядом с `GetLineFetchStatusCopy`
(решение 3: общая для Activity и Design модель может лежать только в `:design`):

```kotlin
enum class GetLineImportStage(@StringRes val label: Int) {
    LoadingConfig(R.string.get_line_fetch_loading_config),
    Checking(R.string.get_line_fetch_checking),
}
```

Только два значения — это всё, что `stageOf` реально возвращает сегодня.
`Updating`/`StartingVpn`/`Ready` из `GetLineFetchStatusCopy.Stage` помечены
«reserved for future» и не используются: **не переносить**, строки в `res` оставить.

Реализация в `cmfa/CmfaGetLineBackend.kt`. Общая часть с `reimportAndActivate`
выносится в приватную функцию, а `reimportAndActivate` переписывается как
«importAndCommit + setActive» — **две копии orphan-cleanup недопустимы**.

Маппинг `FetchStatus.Action → GetLineImportStage` переезжает в `cmfa/` (там же, где
остальная трансляция CMFA-типов). После этого `design.model.GetLineFetchStatusCopy`
удаляется целиком — его единственный потребитель, `PropertiesDesign`, уходит в коммите 4.
Диагностическую строку (`diagnosticLine`) сохранить: `Log.d` со стадией и progress/max
в cmfa-адаптере, тем же форматом.

**Ловушка.** `IFetchObserver.updateStatus(status)` — синхронный (`fun interface`,
`service/.../remote/IFetchObserver.kt:8`). `suspend`-колбэк из него не вызвать; так же не
вызвать `launch` на произвольном scope, иначе прогресс переживёт отмену импорта.
Использовать `Channel(CONFLATED)` внутри `coroutineScope` импорта: observer делает
`trySend`, отдельная корутина в том же scope раздаёт в `onProgress`, канал закрывается
в `finally`. Conflated — сознательно: стадии редкие, пропуск промежуточной безвреден,
блокировка Binder-потока — нет.

Тест `app/src/test/.../getline/GetLineFetchStatusCopyTest.kt` переезжает в
`app/src/test/.../cmfa/` вслед за маппингом (прецедент — `cmfa/servers/`), покрывая новый
маппинг на `GetLineImportStage`. Удалить его нельзя: это единственный тест на то, что
CMFA-словарь не протекает в UI.

### Коммит 2 — онбординг больше не запускает PropertiesActivity

`GetLineOnboardingActivity.importSubscription`: заменить пару
`startActivityForResult(editSubscription)` + `classifyImportResult` на

```kotlin
val imported = backend.subscriptions.importAndCommit(draft, reuseId) { stage ->
    design.setImportStage(stage)
}
```

Разбор результата:

- `Success` → ветка нынешнего `Confirmed`. `source`/`name` брать **из `draft`** —
  он и был источником для `PropertiesActivity`; отдельный `importResult.source` больше
  не нужен, как и вся реконструкция `activateDraft` (:474–485): draft уже полный.
  Ветку сохранить только в части `rememberManagedProfile` /
  `rememberSubscription` / `activateImportedProfile`.
- `Unavailable` → ветка нынешнего `Failed`. Признак offline вычислять на месте:
  `if (!hasValidatedInternetConnection()) Offline else ImportFailed` — ровно то, что
  сейчас делает `PropertiesActivity:138`, просто на стороне вызывающего.
- Ветка `Cancelled` в этом пути **исчезает**: headless-импорт нельзя отменить кнопкой
  «назад». Отмена остаётся только у ручного ввода ссылки — коммит 3.

`emailToRestore` / `restoreEmailAuth` работают как раньше — логика не меняется.
`RetryTarget.ImportSubscription` — тоже, `retry()` продолжает вызывать `importSubscription`.

`design.setImportStage(stage)` в `GetLineOnboardingDesign` — минимальный метод: подставить
строку стадии в подзаголовок состояния `Loading`. Не строить новый прогресс-диалог:
продуктовый экран уже показывает `GetLineProductState.Loading` со своим текстом.
Если по месту окажется, что подзаголовок туда не заводится дешевле чем за ~30 строк —
**принять потерю стадийности**, оставить голый `Loading` и записать это в отчёт как
изменение поведения. Стадии — не обязательство среза.

### Коммит 3 — ручной ввод ссылки без CMFA-формы

`Request.AddExistingSubscription` больше не идёт в `importSubscription(design)` с
`request = null`. Вместо этого — продуктовый ввод URL, затем тот же headless-путь.

Механика уже есть в `:design` и доступна продукту:
`context.requestModelTextInput(initial, title, hint, error, validator = ValidatorHttpUrl)`
(`design/util/Validator.kt:20`) — тот же диалог и тот же валидатор, что `PropertiesDesign.inputUrl`
использует внутри CMFA-формы. Обернуть в `suspend fun requestSubscriptionUrl(): String?`
на `GetLineOnboardingDesign`; отмена диалога → `null` → вернуться на providers
(`refreshEntryState`), что заменяет прежнюю ветку `Cancelled`.

Draft собирается на месте:

```kotlin
GetLineSubscriptionDraft(
    type = GetLineSubscriptionType.Url,
    name = getString(R.string.new_profile),
    source = url,
)
```

**Принятое изменение поведения:** имя подписки больше не запрашивается. Раньше
`PropertiesActivity` давал поле «name». Проверено: `GetLineSubscriptionSummary.name`
не читается ни одним продуктовым экраном — `snapshot()` используется в
`GetLineHomeActivity:475,512` только для `active != null`, `hasImported` и трафика.
Имя видно исключительно в Advanced. Зафиксировать в отчёте; если продукт позже
захочет именование — это отдельная задача, не возврат CMFA-формы.

`interval` — `0L`, как и сейчас в дефолтном драфте (`:433`).

### Коммит 4 — снять шов

Удалить:

- `GetLineNavigation.editSubscription`, `GetLineNavigation.classifyImportResult`
  и весь `sealed class GetLineImportResult` (`GetLineBackend.kt:129–130,171–183`);
- их реализации в `CmfaGetLineNavigation` (:353–379) и импорт `PropertiesActivity`
  в `CmfaGetLineBackend.kt:8`;
- в `PropertiesActivity`: `EXTRA_GET_LINE_IMPORT`, `EXTRA_GET_LINE_IMPORT_OFFLINE`,
  `EXTRA_GET_LINE_COMMITTED_SOURCE`, `EXTRA_GET_LINE_COMMITTED_NAME`,
  `RESULT_GET_LINE_IMPORT_FAILED`, блок `autoCommit` (:45–52), продуктовый `resultData`
  (:122–129), продуктовую ветку catch (:133–142), присвоение
  `useProductFetchVocabulary` (:34–35), импорт `hasValidatedInternetConnection`.
  `catch` возвращается к `showExceptionToast(e)`, `setResult(RESULT_OK)` — без данных;
- в `PropertiesDesign`: поле `useProductFetchVocabulary` (:44), ветку в `withProcessing`
  (:56–60), развилку в `applyFrom` (:182), метод `applyProductVocabulary` (:211–227);
- `design/model/GetLineFetchStatusCopy.kt`.

После этого `PropertiesActivity` и `PropertiesDesign` отличаются от upstream только
переименованием пакетов. Это прямой вклад в цель всей работы.

Снять исключение в `scripts/check-product-boundary.sh:31–32` (строки про
`GetLineFetchStatusCopy.kt`) — файла больше нет, исключений в гейте не остаётся.
Проверить, что после удаления `TARGETS` не пуст и гейт зелёный.

---

## AC

- [ ] `grep -rn "PropertiesActivity" app/src/main/java/pro/getline/vpn/{getline,cmfa,product}/` — пусто
- [ ] `grep -rn "GetLineImportResult\|classifyImportResult\|editSubscription" app design` — пусто
- [ ] `grep -rn "GET_LINE_IMPORT\|GET_LINE_COMMITTED\|useProductFetchVocabulary" app design` — пусто
- [ ] `git diff -M upstream/main -- '*PropertiesActivity.kt' '*PropertiesDesign.kt'` — только
      строки `package`/`import` с переименованием пакета, ни одной содержательной
- [ ] В `scripts/check-product-boundary.sh` нет ни одного `continue`-исключения;
      `./scripts/check-product-boundary.sh` → `product-boundary: ok`
- [ ] Негативный тест гейта: временно вписать `val s: FetchStatus? = null` в
      `GetLineOnboardingActivity` → гейт **падает**; вернуть. Показать вывод.
      (`FetchStatus` при этом должен попасть в `ID_PATTERN` — добавить его туда.)
- [ ] `./gradlew :app:assembleAlphaDebug` — EXIT=0
- [ ] `./gradlew :app:testAlphaDebugUnitTest` — EXIT=0, тест маппинга стадий существует
      в `app/src/test/.../cmfa/` и проходит
- [ ] `scripts/verify-mihomo-gate.sh` — не затронут, но прогнать
- [ ] `git status`: только `m core/src/foss/golang/clash`

## Ручная проверка на устройстве (обязательна, автотестами не покрыта)

Импорт — единственный путь, которым пользователь вообще попадает в продукт. Регрессия
здесь означает неустановимое приложение, поэтому все четыре входа проверяются вручную:

1. Чистая установка → логин (Google) → импорт после логина → Home с активным профилем.
2. Повторный логин тем же аккаунтом → профиль **переиспользован**, а не задублирован
   (проверить в Advanced → Profiles: одна запись). Это то, ради чего существует `reuseId`.
3. `NoProfile` → «добавить существующую подписку» → ввести валидный URL → импорт → Home.
4. То же, но нажать «отмена» в диалоге URL → возврат на список провайдеров, не залипание
   в `Loading`.
5. То же, но ввести заведомо мёртвый URL → `ImportFailed` с кнопкой Retry; Retry работает.
6. Выключить сеть, повторить → `Offline`, не `ImportFailed`.
7. Импорт из внешнего интента (`ExternalControlActivity`) — путь `importIntent`.
8. Мид-OTP детур: начать вход по email, дойти до OTP, нажать «добавить подписку»
   не завершив OTP, отменить → возврат к OTP-шагу (`restoreEmailAuth`).
9. Advanced → Profiles → New Profile / edit существующего → форма Properties работает
   как раньше, прогресс с **CMFA**-словарём (не продуктовым).

## Риск

Пункт 2 (`reuseId`) — самый тонкий. В нынешнем коде переиспользование обеспечивается
`createOrUpdatePending(draft, reuseId)` **до** запуска Activity. В новом — тем же
reuse-или-create внутри `importAndCommit`. Если исполнитель случайно построит путь
«createOrUpdatePending, затем importAndCommit с `reuseId = null`», профили начнут
плодиться при каждом логине, и на одном прогоне это не видно.
Явно проверить, что `createOrUpdatePending` из `importSubscription` **удалён**,
а не оставлен перед новым вызовом.
