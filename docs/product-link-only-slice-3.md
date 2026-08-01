# Link-only подписки, срез 3: QR-импорт и единая URL-политика

Спецификация на исполнение. База: `main` @ `ca26f187` (срезы 1 и 2 закрыты).

Читать до правок: `docs/product-link-only-slice-1.md`,
`docs/product-link-only-slice-2.md`, решение 1 из `docs/refactor/README.md`
(Advanced — диагностический интерфейс, не продуктовая навигация).

---

## Проблема

**1. Вход по ссылке живёт внутри state view и исчезает при ошибках.**
Кнопки «У меня есть ссылка на подписку» в разметке онбординга нет вовсе — она
существует только как recovery-действие
(`GetLineOnboardingDesign.recoveryActionFor:390`: `NoProfile` + шаг Providers →
`ImportSubscription`). На `Offline`, `AuthFailed`, `ImportFailed` та же
единственная кнопка становится «Повторить», и способ подключиться по ссылке
пропадает ровно тогда, когда он нужнее всего.

**2. QR в продуктовом пути не поддержан.** Сканер написан и работает
(`QrScannerActivity`, контракт `ScanQrCode`, `zxing-cpp` + CameraX уже в
`app/build.gradle.kts:118-122`, `CAMERA` и `uses-feature required="false"` в
манифесте), но подключён только к CMFA-шному `NewProfileActivity:32`.

**3. Три входа импорта — три разные политики URL.**

| вход | политика сегодня |
|---|---|
| ручной ввод (`GetLineOnboardingDesign:118`) | `ValidatorHttpUrl` — `http://` и любой хост |
| deep-link (`ExternalImportActivity:41`) | `requireSubscriptionUrl` |
| QR | политики нет |

## Принятое решение

Единая политика на всех трёх входах — `GetLineControlPlaneHostPolicy.requireSubscriptionUrl`:
https, без userInfo, хост getline-family кроме stage на prod; на e2e — только
`e2eAllowedHosts`.

Цена принята сознательно: **сторонняя (не getline) подписка в продуктовом пути
больше не импортируется.** Возможность остаётся в Advanced
(`NewProfileActivity`), который доступен по DEBUG-кнопке и семи тапам по бренду.

---

## Изменения

### 1. Блок «Подключиться другим способом»

`getlineui/src/main/res/layout/design_get_line_onboarding.xml` — после кнопки
Telegram (`:139-152`), перед email-блоком (`:155`):

```
TextView       get_line_onboarding_other_ways      (secondary, слабее CTA)
MaterialButton @+id/scan_qr                        get_line_onboarding_scan_qr
TextView       get_line_onboarding_qr_hint         (подпись под кнопкой)
MaterialButton @+id/enter_link                     get_line_onboarding_enter_link
```

**Не вешать на `providersVisible`.** Сейчас
`providersVisible = keepsEmailLoginChrome(state) && authStep == Providers`
(`GetLineOnboardingDesign:413-415`). `keepsEmailLoginChrome` (`:435-447`)
возвращает `true` для `NoProfile` / `AuthFailed` / email-OTP chrome, но
**`false` для `Offline` и `ImportFailed`** — ровно тех состояний, где §8 и
смысл среза требуют постоянный вход по ссылке/QR (recovery там уже «Повторить»).
Если посадить четыре view на `providersVisible`, блок пропадёт вместе с
Google/Email/Telegram, и починка state-view CTA будет фиктивной.

Отдельный binding + гейт:

```kotlin
// GetLineOnboardingDesign.applyAuthStepVisibility
//
// Offline/ImportFailed hide login chrome, including mid-email/OTP: e.g. user on
// EmailEntry taps send without network → setProductState(Offline)
// (GetLineOnboardingActivity:454) while authStep stays EmailEntry. Fields are
// already gone (keepsEmailLoginChrome = false); mis-tap into code entry is
// impossible. Pure `authStep == Providers` would still hide alternate import.
binding.alternateImportVisible =
    showsAlternateImport(state) &&
        (authStep == AuthStep.Providers || !keepsEmailLoginChrome(state))

/** States where QR + manual link must stay reachable. */
private fun showsAlternateImport(state: GetLineProductState): Boolean =
    when (state) {
        GetLineProductState.NoProfile,
        GetLineProductState.Offline,
        GetLineProductState.AuthFailed,
        GetLineProductState.ImportFailed,
        GetLineProductState.BackendUnavailable -> true
        else -> false
    }
```

**Гейт для входа поверх link-only.** Вход из Home по «Войти для управления»
открывает этот же экран с `EXTRA_LINK_ONLY_SIGN_IN`
(`GetLineOnboardingDesign.setLinkOnlySignIn`). Там подписка уже есть, и
предложение импортировать ещё одну — шум: ветка `NoProfile → ImportSubscription`
в этом режиме уже отключена. Блок «Подключиться другим способом» должен гаситься
тем же флагом:

```kotlin
binding.alternateImportVisible =
    !linkOnlySignIn &&
        showsAlternateImport(state) &&
        (authStep == AuthStep.Providers || !keepsEmailLoginChrome(state))
```

Итог по видимости:

| состояние | шаг | блок |
|---|---|---|
| Offline / ImportFailed / BackendUnavailable | любой | **виден** (chrome логина уже скрыт) |
| NoProfile / AuthFailed | Providers | **виден** |
| NoProfile / AuthFailed | EmailEntry / OtpEntry | **скрыт** (мис-тап из ввода) |
| email-OTP product states при живом chrome | Email/OTP | **скрыт** |

`BackendUnavailable` — тот же Retry-ряд, что offline. Login-кнопки
(Google/Email/Telegram) **не** расширяем на error-состояния — только alternate
import; chrome логина остаётся как есть.

Разметка на все четыре view:

- visibility: `@{alternateImportVisible ? View.VISIBLE : View.GONE}`
- на **обеих** кнопках (`scan_qr`, `enter_link`):
  `android:enabled="@{actionsEnabled}"` — как у Google/Email/Telegram
  (`design_get_line_onboarding.xml:114`, `:129`, `:144`).
  `actionsEnabled = state != Loading` (`GetLineOnboardingDesign:367`).
  Busy-guard в Activity (§2) остаётся, но без `enabled` кнопка выглядит живой
  во время Loading — недопустимо.

Стили: `Widget.GetLine.SecondaryTextButton` для обеих кнопок — блок должен
читаться слабее блока авторизации. Primary-стиль здесь не использовать.

Порядок обхода TalkBack (когда видны и провайдеры, и блок): Google → Email →
Telegram → заголовок → QR → подпись → ручной ввод → Help. На Offline /
ImportFailed провайдеров нет — порядок начинается с state view, затем блок.

`Request.ScanQrCode` — новый; ручной ввод переиспользует существующий
`Request.AddExistingSubscription` (`GetLineOnboardingDesign:34`).

**Убрать ветку `NoProfile → ImportSubscription` из `recoveryActionFor`** (`:390-395`):
вход по ссылке теперь постоянно на экране, дублировать его в state view незачем,
и главное — он перестаёт исчезать на ошибочных состояниях. Сам
`GetLineRecoveryAction.ImportSubscription` не удалять: его использует Home
(`GetLineHomeActivity:467`, `:1420`).

### 2. Сканер

`GetLineOnboardingActivity`:

```kotlin
private val scanLauncher = registerForActivityResult(ScanQrCode(), ::onQrScanned)
```

`ScanQrCode` и `QrScanResult` объявлены `internal` в модуле `app`
(`QrScannerActivity.kt:25-32`), онбординг в том же модуле — экспортировать ничего
не нужно.

Запуск не должен идти при `busy` — как остальные обработчики Request.

Разрешение камеры запрашивает сам `QrScannerActivity` (`:66`, `:86-91`).
Онбординг не запрашивает ничего и не проверяет `checkSelfPermission`.

**Обработка результата — прозой, потому что все ветки кроме `Success` без явного
кода становятся тупиком.** `NewProfileActivity:170-176` показывает на них тост —
для продуктового пути этого мало: пользователь остаётся на экране без понимания,
что делать дальше.

| результат | поведение |
|---|---|
| `Success(content)` | валидация политикой (§3), затем подтверждение (§4), затем общий импорт (§5) |
| `UserCanceled` | ничего; остаёмся на providers |
| `MissingPermission` | диалог `get_line_qr_no_camera_permission` с кнопкой «Ввести ссылку вручную» → открывает ручной ввод |
| `Error` | диалог `get_line_qr_scan_failed`, та же кнопка |

Один метод в дизайне на оба провала:

```kotlin
/** Scan failed: offer the manual path instead of leaving a dead end. */
suspend fun offerManualEntryAfterScanFailure(@StringRes message: Int): Boolean
```

`true` → Activity открывает ручной ввод. Это и есть требование «отказ в камере не
тупик»; отдельного `GetLineProductState` заводить не нужно.

### 3. Единая политика

Валидатор нельзя просто подменить внутри `:getlineui`: `Validator`
(`getlineui/util/Validator.kt`) живёт там, а `GetLineControlPlaneHostPolicy` — в
`:app`, и `getlineui` от `app` не зависит (и не должен, решение 3
`docs/refactor/README.md`).

Поэтому валидатор передаётся снаружи:

```kotlin
// GetLineOnboardingDesign
suspend fun requestSubscriptionUrl(validator: Validator): String?
```

Activity передаёт `{ GetLineControlPlaneHostPolicy.isAllowedSubscriptionUrl(it) }`.

Нужен предикатный вариант рядом с бросающим (`GetLineControlPlaneHostPolicy:146`):

```kotlin
fun isAllowedSubscriptionUrl(url: String?): Boolean
```

и `requireSubscriptionUrl` переписать через него. Поведение существующих
вызовов не меняется.

Текст ошибки в поле ввода: вместо `accept_http_content` — новый
`get_line_import_link_rejected` («Ссылка не похожа на подписку GetLine»).

QR проходит **ту же** проверку перед подтверждением. Отдельной, более мягкой
валидации для QR нет — это прямое требование фазы 1.

`ValidatorHttpUrl` после правки остаётся только у CMFA `PropertiesDesign:115` —
это Advanced, не трогаем.

### 4. Подтверждение — только для QR

```kotlin
suspend fun confirmSubscriptionImport(host: String): Boolean
```

Показывает **хост**, а не URL. Образец копирайта — `ExternalImportActivity`
(`external_import_confirmation_message`), там хост уже показывается
(`:44-48` через `canonicalizeHost`).

Почему хост: содержимое QR человеку не видно, а полный URL несёт токен подписки —
он не должен попадать ни на скриншот, ни в диалог, который пользователь кому-то
показывает.

**Почему для ручного ввода подтверждения нет.** Пользователь сам вставил ссылку и
видит её в поле — второй диалог не добавляет информации, только трение. «Один
import pipeline» из acceptance — про код и политику, а не про одинаковое число
экранов. Это решение, а не упущение: если захочется симметрии, добавлять надо
осознанно.

### 5. Один pipeline импорта

Оба входа сходятся в существующий `addExistingSubscription` →
`importSubscription` (`GetLineOnboardingActivity:187-210`). QR после валидации и
подтверждения строит ту же
`GetLineSubscriptionDraft(type = Url, name = new_profile, source = url)`.
Отдельной ветки импорта для QR нет — иначе разъедутся durable pending, супрессия
дублей в `GetLineImportCoordinator` и обработка терминалов.

### 6. Строки (`values/` + `values-ru/`)

| ключ | RU |
|---|---|
| `get_line_onboarding_other_ways` | Подключиться другим способом |
| `get_line_onboarding_scan_qr` | Сканировать QR-код |
| `get_line_onboarding_qr_hint` | Отсканируйте QR-код подписки с другого устройства или из личного кабинета. |
| `get_line_onboarding_enter_link` | Ввести ссылку вручную |
| `get_line_import_link_rejected` | Ссылка не похожа на подписку GetLine |
| `get_line_qr_no_camera_permission` | Нет доступа к камере. Ссылку можно ввести вручную. |
| `get_line_qr_scan_failed` | Не удалось прочитать QR-код. Ссылку можно ввести вручную. |
| `get_line_qr_confirm_title` | Импортировать подписку? |
| `get_line_qr_confirm_message` | Подписка будет загружена с %1$s. |
| `get_line_action_enter_link_manually` | Ввести ссылку вручную |

Существующий `get_line_add_existing_subscription` остаётся — его показывает Home
как recovery-действие.

### 7. Тесты

Автотестами покрывается только политика:

- `GetLineControlPlaneHostPolicyTest` дополнить `isAllowedSubscriptionUrl`:
  `http://` → false; stage-хост на prod → false; прод-хост на e2e → false;
  `https://sub.getline.pro/...` на prod → true; userInfo → false; мусор и пустая
  строка → false; хост с хвостовой точкой → как без неё.

Дизайн-тестов в репозитории нет, заводить их ради этого среза не стоит.
Сканер и камера юнит-тестами не покрываются — см. §8.

```
./gradlew :app:testAlphaProdDebugUnitTest :app:testAlphaE2eDebugUnitTest
```

### 8. Ручная проверка (обязательна — сканер тестами не покрыт)

1. QR с валидной подпиской → подтверждение с хостом → импорт → Home.
2. QR с `http://` или чужим хостом → отказ + предложение ручного ввода.
3. QR с мусором (не URL) → то же.
4. Отказ в разрешении камеры → диалог → «Ввести ссылку вручную» → ввод работает.
5. Ручной ввод чужого хоста → ошибка в поле, диалог не закрывается.
6. Блок «Подключиться другим способом»:
   - `NoProfile` / `AuthFailed` на Providers — виден; на email/OTP — **нет**.
   - `Offline` / `ImportFailed` / `BackendUnavailable` — виден **всегда**,
     включая сценарий «email-шаг → offline» (`authStep` остаётся EmailEntry).
   - Регрессия: не `providersVisible`; не чистый `authStep == Providers`.
   - Кнопки QR/link disabled при Loading (`actionsEnabled`).
7. Существующая прод-ссылка через deep-link импортируется как раньше.

## Не входит

- Серверный `/s/<opaque-token>` и короткоживущие QR-приглашения.
- QR, содержащий что-либо кроме subscription URL (device pairing).
- Генерация QR внутри приложения.
- Смягчение политики ради сторонних подписок.
- Дыра со смешанным состоянием после среза 2 — см. «Что осталось» ниже.

## Риски

- **Сторонние подписки.** Уже импортированный сторонний профиль продолжит
  работать и обновляться (политика применяется на входе импорта, не на fetch), но
  переимпортировать его в продуктовом пути станет нельзя. Обход — Advanced.
- **`isAllowedSubscriptionHost` на prod пускает любой `*.getline.pro` кроме
  stage** (`GetLineControlPlaneHostPolicy:120-136`) — это шире control-plane
  allowlist и таким было задумано, потому что хост выдаёт RWP. QR с
  неконтролируемого поддомена getline.pro пройдёт. Сузить можно только вместе с
  бэкендом.
- **Устройство без камеры.** `uses-feature` объявлен `required="false"`
  (манифест `:21-23`), значит установка на такие устройства разрешена и кнопка QR
  будет видна. Проверить руками, что `QrScannerActivity` там возвращает `Error`,
  а не падает; при необходимости скрывать кнопку по
  `packageManager.hasSystemFeature(FEATURE_CAMERA_ANY)`.

## Объём и релиз

Ориентировочно: ~180-220 строк продуктового кода, ~40 строк тестов, ~50 строк
разметки, 10 строковых ключей × 2 локали. Затронуто ~7 файлов, ни одного
upstream-CMFA.

PR-лейбл: `release:minor`, тип коммита `feat` — для пользователя это новый способ
подключения. Ужесточение URL-политики едет тем же PR и отдельного лейбла не
требует (`docs/release-policy.md`: ровно один лейбл, согласованный с типами
коммитов).
