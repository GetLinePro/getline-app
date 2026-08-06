# CMFA Fork Feasibility Spike

## Questions

- Собирается ли upstream воспроизводимо на Linux?
- Работает ли наша подписка в собственной debug-сборке?
- Где находятся branding, onboarding, profile import и VPN lifecycle?
- Можно ли добавить GetLine flow локальными изменениями?
- Какие сетевые обращения делает исходный клиент?
- Как обновляется Mihomo?
- Можно ли убрать или отключить собственный app updater для Play-сборки?
- Насколько сложно менять applicationId, название и deep links?
- Можно ли поддерживать upstream без постоянных конфликтов?
- Нет ли блокеров для Google Play и F-Droid?
- Можно ли пройти RWP login flow, получить renewable native session и
  импортировать подписку без clipboard, Share Sheet, ручного закрытия браузера
  и custom authentication backend?

## Evidence

Заполнять командами, ссылками на файлы и результатами тестирования.

### On-device smoke test

- 2026-07-26: пользователь подтвердил успешный smoke test исходного клиента
  на физическом Android-устройстве.
- Сгенерированный ресурс
  `app/build/intermediates/incremental/alphaDebugAndroidTest/mergeAlphaDebugAndroidTestResources/merged.dir/values/values.xml`
  содержит `release_name` `v2.11.32` и `release_code` `211032` для alpha-варианта.

Проверенный факт: исходный клиент запускается и проходит выполненный пользователем
smoke test на реальном устройстве.

Предположение: протестированная сборка соответствует указанному выше alpha-варианту
CMFA `v2.11.32`; путь к ресурсу сам по себе не подтверждает идентичность
установленного APK.

### GetLine onboarding seams

Область анализа: `app`, `design`, `service`, `common`, корневые Gradle/settings,
Android manifests и непосредственно используемые API-модели `core`. Реализация
Mihomo/Go, generated/build/vendor-файлы и внутренности ядра исключены.

Карта текущего потока:

- launcher и deep links:
  `app/src/main/AndroidManifest.xml`, `MainActivity.kt`,
  `ExternalControlActivity.kt`, `common/constants/Intents.kt`;
- ручной импорт URL:
  `NewProfileActivity.kt`, `PropertiesActivity.kt`, `NewProfileDesign.kt`,
  `PropertiesDesign.kt` и соответствующие layouts;
- список, выбор и обновление профилей:
  `ProfilesActivity.kt`, `ProfilesDesign.kt`, `IProfileManager.kt`,
  `ProfileManager.kt`, `ProfileProcessor.kt`, `ProfileReceiver.kt`;
- хранение:
  Room-сущности и DAO `Pending`/`Imported`, файлы профиля в pending/imported
  directories и `ServiceStore.activeProfile`;
- запуск VPN:
  `app/util/Clash.kt`, `MainActivity.kt`, service manifest,
  `ClashService.kt`, `TunService.kt`, `ConfigurationModule.kt`;
- фиксированная граница ядра:
  `Clash.fetchAndValid`, `Clash.load`, `FetchStatus` и `TunnelState`.

Найденные швы:

1. Изолированный onboarding-экран может создавать URL-профиль через
   `IProfileManager.create`, а затем открывать штатный `PropertiesActivity`.
2. `PropertiesActivity` уже выполняет `patch` и `commit`; `ProfileProcessor`
   скачивает и валидирует конфигурацию через `Clash.fetchAndValid`, после чего
   переносит запись из `Pending` в `Imported`.
3. `PropertiesActivity` возвращает `RESULT_OK` только после успешного
   `patch`/`commit`; Back, отмена и закрытие после ошибки оставляют
   `RESULT_CANCELED`. UUID сохраняется у вызывающего onboarding-экрана, который
   после возврата дополнительно проверяет `queryByUUID(uuid).imported` перед
   `IProfileManager.setActive`.
4. Подключение можно делегировать существующему `startClashService()`, включая
   штатный запрос Android VPN permission. VPN lifecycle менять не требуется.
5. `MainActivity` остаётся канонической launcher activity. При отсутствии
   imported-профилей она маршрутизирует в onboarding; явный флаг
   «Расширенные настройки» обходит маршрутизацию и предотвращает цикл.
6. Существующий browsable route
   `clash://install-config?url=...` обрабатывается `ExternalControlActivity`, но
   создаёт pending-профиль и открывает `PropertiesActivity`; автоматический
   commit и выбор активного профиля он сейчас не делает.

Предварительный вывод: первый onboarding-срез выглядит локализуемым в новом
GetLine activity/design/layout, строках, регистрации activity в manifest и
небольшой маршрутизации в `MainActivity`. Изменения Gradle, базы профилей,
service lifecycle и core для него не нужны.

Неизвестно до продуктового решения:

- публичный Telegram bot URL;
- GetLine deep-link scheme/host и должен ли импорт из него требовать
  подтверждение перед сетевым запросом и активацией профиля.

### Existing GetLine web onboarding

2026-07-26 проверен публичный frontend `https://app.getline.pro/`:

- сайт отдаётся как адаптивная PWA с mobile viewport и standalone manifest;
- frontend содержит authentication, dashboard и installation-guide routes;
- публичный install flow получает `subscription_link` по временному install token;
- route `/deeplink?url=...` перенаправляет пользователя в VPN-приложение;
- сайт рассчитан на top-level открытие; CSP разрешает framing только самому сайту
  и Telegram, поэтому предпочтительнее внешний браузер или Custom Tab, а не
  встраивание кабинета во внутренний WebView.

Это позволяет рассматривать более узкую границу продукта: web-приложение
отвечает за кабинет, покупку и выдачу подписки, Android fork — за приём ссылки,
штатный импорт CMFA и VPN lifecycle. Для проверки интеграции нужно определить,
может ли installation guide формировать существующий
`clash://install-config?url=...` либо отдельный GetLine deep link, не раскрывая
subscription URL в логах и аналитике.

### Bounded onboarding prototype

Реализован первый ограниченный срез:

- `MainActivity` остаётся канонической launcher activity и перенаправляет в
  GetLine onboarding только при отсутствии imported-профилей;
- явный флаг «Расширенные настройки» открывает обычный `MainActivity` без
  redirect loop;
- «Войти в GetLine» открывает `https://app.getline.pro/` через Android Custom
  Tab;
- «У меня есть ссылка» создаёт штатный URL-профиль и открывает существующий
  `PropertiesActivity`;
- существующий `clash://install-config` остаётся внешним контрактом и
  перенаправляется в тот же completion flow;
- только `RESULT_OK` от `PropertiesActivity` с последующей проверкой
  `queryByUUID(uuid).imported` приводит к `setActive`;
- отмена, Back и ошибка commit не активируют профиль;
- автоматический запуск VPN не добавлен.

Для настоящего Custom Tab добавлена только
`androidx.browser:browser:1.8.0`. По официальному AAR metadata эта версия
совместима с текущим compileSdk 35 и не требует обновления AGP; более новые
1.9.0/1.10.0 требуют compileSdk 36 и AGP 8.9.1, поэтому не используются.

Проверено сборкой: `app:compileAlphaDebugKotlin` и `app:assembleAlphaRelease`
успешны; release lint/R8/package прошли; `app:testAlphaDebugUnitTest` имеет
статус `NO-SOURCE`. Merged release manifest сохраняет launcher alias на
`MainActivity` и browsable-фильтры `clash`/`clashmeta`.

Требует проверки на устройстве полный путь
`clean install → web login/install → clash:// callback → Properties commit →
active profile → штатное ручное подключение`.

Результат проверки на устройстве 2026-07-26:

- авторизация через Custom Tab успешна;
- для обычного пользовательского аккаунта открывается персональная страница;
- subscription URL доступен для ручного копирования;
- сайт автоматически не открывает `clash://install-config`, поэтому Custom Tab
  остаётся открыт, Android callback не вызывается и профиль автоматически не
  импортируется.

Вывод: authentication и получение subscription URL работают; незакрытый шов
находится между web installation flow и существующим Android deep link.
Сначала следует настроить web installation action на
`clash://install-config?url=<encoded subscription URL>`. Token/callback API нужен
только если безопасно сформировать такой переход на стороне сайта невозможно.

### RWP Private documentation check

Официальная документация RWP Private, проверенная 2026-07-26, подтверждает:

- в продукте заявлена «авторизация нативных приложений» через OAuth deep link;
- существует настраиваемый конфиг страницы подписки;
- installation guide имеет прямые browser/Telegram routes, включая мастер,
  выбор платформы и приложения;
- в changelog 6.0.0 указаны API конфигурации страницы подписки с полями `link`
  и `share`, installation share links и deeplink support.

При этом опубликованная документация не описывает схему native OAuth callback,
формат `link`/`share`, допустимые шаблонные переменные или способ добавить
собственное Android-приложение в installation guide. Описанный на странице
«Прямые ссылки» маршрут `/#/deeplink` является маршрутом web/Mini App и сам по
себе не доказывает возврат subscription URL в Android-приложение.

Поэтому ближайшая проверка не требует проектирования нового backend API:

1. В Admin UI найти конфиг страницы подписки/installation guide и проверить,
   можно ли добавить GetLine как Android app/action.
2. Если action принимает URL-шаблон, настроить существующий контракт
   `clash://install-config?url=<percent-encoded subscription URL>`.
3. Проверить переход из внешнего браузера и Custom Tab на реальном устройстве.
4. Если такой шаблон не поддерживается, запросить у поставщика RWP Private
   документированный native-app OAuth/deep-link contract и версию, в которой он
   доступен.

До проверки Admin UI и фактической версии развёрнутого RWP остаётся
предположением, что нужный action можно добавить только конфигурацией. Нельзя
считать наличие функции в актуальной документации доказательством, что она есть
в установленной версии `app.getline.pro`.

Проверка доступного Admin UI показала секцию «Инструкция по установке». Она
позволяет выбрать внешнюю ссылку либо встроенный guide Remnawave, показывать
subscription link/QR-код, включить кнопку «Поделиться» и выбрать вид инструкции.
В показанном интерфейсе нет настройки собственного VPN-приложения, URL-шаблона
или native callback. Внешняя «Ссылка на инструкцию» сама по себе является
статическим адресом и не доказывает, что в него передаётся install token или
subscription URL.

Следующий осмысленный Android-only срез — явный clipboard handoff: после
возврата из Custom Tab пользователь нажимает «Вставить скопированную ссылку», а
приложение передаёт её существующему URL-profile/`PropertiesActivity` flow.
Чтение clipboard должно происходить только по действию пользователя; это
сохраняет ручной fallback и не создаёт альтернативный механизм импорта.

### Native authentication discovery — RWP 6.7.8

**Goal.** Determine whether the Android client can authenticate through the
existing RWP login flow, obtain a renewable native session, read the customer
subscription, and import it without clipboard, Share Sheet, manual browser
closing, or a custom authentication backend.

**Environment**

| Item | Value |
| --- | --- |
| RWP | 6.7.8 |
| Production origin | `https://app.getline.pro` |
| Identity provider tested | Telegram OIDC |
| Testing | live GetLine installation |

Secrets, cookies, OAuth codes, tokens, and subscription URLs were masked and
are not recorded.

#### Result

**Proceed.**

The live RWP installation supports a complete handoff from its existing web
authentication session to renewable native access and refresh tokens.

Working server-side chain verified end to end:

```text
Telegram web login
→ web auth_token
→ device-key generation
→ device-key exchange
→ native access_token + refresh_token
→ authenticated subscriptions API
```

No custom authentication backend is required for the Telegram prototype.

#### Verified Telegram web login contract

**Start endpoint**

```http
GET /api/auth/telegram-oidc/start
    ?return_to=<url>
    &intent=login
```

The endpoint returns JSON rather than an HTTP redirect:

```json
{
  "auth_url": "https://oauth.telegram.org/auth?..."
}
```

The generated Telegram authorization URL contains:

- `client_id=<Telegram bot ID>`
- `redirect_uri=https://app.getline.pro/api/auth/telegram-login`
- `response_type=code`
- `scope=openid profile telegram:bot_access`
- `code_challenge_method=S256`
- `state=<server-generated state>`

RWP performs PKCE for the RWP-to-Telegram leg. The verifier and state are held
in browser cookies.

**Telegram callback**

Telegram returns to the fixed RWP callback:

```http
GET /api/auth/telegram-login?code=<code>&state=<state>
```

On successful login, RWP responds:

```http
HTTP 302
Location: https://app.getline.pro/#/login?auth_token=<token>&expires_in=86400
```

RWP also creates browser session cookies:

- `rw_session_token`
- `rw_refresh_token`

**`return_to` behavior**

A custom same-origin `return_to` was accepted by the start endpoint but was not
used after successful authentication.

Observed final redirect remained fixed:

```text
https://app.getline.pro/#/login?auth_token=...&expires_in=86400
```

Therefore, the Android implementation must treat that fixed URL as the
authentication completion URI.

#### Verified web-token behavior

The `auth_token` returned in the URL fragment is a usable Bearer token.

```http
GET /api/auth/me
Authorization: Bearer <auth_token>
```

Result: `HTTP 200`.

Observed response fields:

- `bot_scope_id`
- `customer_id`
- `first_name`
- `is_partner`
- `is_partner_admin`
- `partner_program_enabled`
- `role`
- `telegram_id`
- `username`

This confirms that the fragment contains a real RWP access token rather than an
opaque frontend-only value.

#### Verified web-to-native session handoff

**Generate device key**

Using the web `auth_token` as Bearer authentication:

```http
GET /api/auth/device-key/generate
Authorization: Bearer <web auth_token>
X-Requested-With: XMLHttpRequest
Accept: application/json
```

RWP returned a one-time `device_key`.

The key was successfully consumed by the exchange endpoint, which indirectly
confirms successful generation.

**Exchange device key**

```http
POST /api/auth/device-key/exchange
Origin: https://app.getline.pro
Referer: https://app.getline.pro/
X-Requested-With: XMLHttpRequest
Content-Type: application/json
Accept: application/json
```

```json
{
  "device_key": "<one-time key>"
}
```

The response contained:

- `access_token`
- `refresh_token`
- `expires_in`

Observed: `expires_in = 86400`.

The exact minimal required header set has not been reduced. The listed headers
are the set used during the successful live test.

**Read subscriptions**

The native `access_token` returned by device-key exchange was used for:

```http
GET /api/subscriptions
Authorization: Bearer <native access_token>
Accept: application/json
```

Result: `HTTP 200`.

Top-level response shape:

```json
{
  "autopay_available": "...",
  "subscriptions": "..."
}
```

The nested subscription DTO has not yet been documented.

#### Reconstructed Android flow

1. User presses “Sign in with Telegram”.
2. Android opens the RWP authentication flow in an Auth Tab.
3. Browser starts the Telegram OIDC login on `app.getline.pro`.
4. User completes Telegram authentication.
5. RWP redirects to
   `https://app.getline.pro/#/login?auth_token=<web token>&expires_in=86400`.
6. Auth Tab recognizes the configured HTTPS completion host/path, closes, and
   returns the resulting URI to Android.
7. Android extracts `auth_token` from the URI fragment.
8. Android immediately calls `GET /api/auth/device-key/generate`.
9. Android exchanges the returned one-time key via
   `POST /api/auth/device-key/exchange`.
10. Android receives and stores `access_token`, `refresh_token`, `expires_in`.
11. Android calls `GET /api/subscriptions`.
12. Android extracts the selected subscription URL.
13. Android imports the subscription through the existing CMFA
    `ProfileManager` / `PropertiesActivity` / `ProfileProcessor` path.
14. After successful import, Android activates the profile and starts the
    existing Clash service.

AndroidX Auth Tab supports an expected HTTPS redirect host and path, reports
successful completion through its activity result, and returns the result URI.
HTTPS completion additionally verifies ownership of the redirect domain.

#### Security properties

**Existing RWP behavior**

The intermediate web `auth_token` is returned in the URL fragment:

```text
#/login?auth_token=...
```

Because it is after `#`, the fragment is not included in the HTTP request sent
to Caddy or RWP.

However, it is still a ready-to-use Bearer token and is weaker than a
client-controlled native PKCE authorization code.

**Client requirements**

The Android client must:

- never log the complete callback URI;
- never send it to analytics or crash reporting;
- parse the token only in memory;
- exchange it for a native session immediately;
- discard the web token after device-key generation;
- store only the native refresh/access state;
- use a verified HTTPS Auth Tab completion rather than an unverified custom
  scheme;
- reject callbacks with an unexpected scheme, host, path, or fragment structure;
- avoid placing tokens in navigation arguments, saved instance state,
  notifications, or UI text.

AndroidX Auth Tab distinguishes successful redirect completion from cancellation
and exposes explicit verification-failure results for HTTPS redirect ownership.

#### Implementation status (Telegram onboarding slice)

Android client now implements Auth Tab → web token parse → device-key handoff →
native session store → `/api/subscriptions` → stock CMFA import/activate/VPN.

Deploy notes and optional trampoline live under `docs/spikes/android-auth/`.

**E2E foundation (channel × environment, S0 + S1):** stage mock +
`alphaE2eDebug` prove Google Auth Tab → native session → YAML import → Home
and force-stop persistence without production RWP Shop. Prefer current client
facts in:

- `docs/spikes/e2e-auth-session-contract.md` (HTTP contract Observed/Expected)
- `tools/e2e-mock/README.md` (deploy, runbooks, browser notes)

S2 OTP was not part of that close-out.

> **2026-08-06 note.** Production native PKCE start is unblocked. Whitelist:
> `pro.getline.vpn:/oauth2redirect`, `.alpha`, `.alpha.debug`; client callback
> `${APPLICATION_ID}:/oauth2redirect` (`getline://auth` not allowed). Target
> path and browser ladder are **#19**; matrix **#22**. Sections above still
> describe the **shipped** Auth Tab → web token → device-key path. Prefer
> `docs/spikes/android-auth/README.md` status table and
> `docs/external/native-auth-flow.md` for the migration target.

#### Unverified items

The following remain implementation or contract checks rather than feasibility
blockers:

- ~~Whether the target Android browser correctly returns the full fixed URI,
  including the fragment, through `AuthTabIntent.AuthResult.resultUri`.~~
  **Observed green** on Chrome 150 (emulator + physical device) for e2e/prod
  DAL paths; older Chrome (≤137 observed) can lack Auth Tab — see e2e-mock README.
- Required `assetlinks.json` configuration for the final **store** package and
  signing certificate (e2e package DAL is configured for stage). Relevant for
  any residual HTTPS completion; **not** required for package-id
  `${APPLICATION_ID}:/oauth2redirect` callback.
- ~~Browser compatibility and **Custom Tabs fallback** when Auth Tab is
  unsupported (**deferred**).~~ **Scheduled as #19** (capability ladder;
  Chrome not hard-required). Matrix #22.
- Full production nested schema of `/api/subscriptions` beyond fields the client
  parses (client selection rules: `e2e-auth-session-contract.md`).
- Live production behavior of `POST /api/auth/native/refresh` (client code path
  + mock stub documented; not claimed on first-login device path). Becomes
  first-class on #19 native exchange path.
- Refresh-token rotation semantics.
- Logout/revocation behavior.
- Device-key TTL and replay response (still applies to **email** handoff).
- Whether multiple active subscriptions can exist and how the client should
  choose among them (client prefers primary with link, else first with link).
- Whether the subscription URL remains stable across renewals and tariff changes.

## Decision

Proceed with CMFA as the foundation for a bounded GetLine onboarding prototype.

Native authentication discovery against live RWP 6.7.8: **Proceed.** Telegram
web login → web `auth_token` → device-key generate/exchange → native session →
`/api/subscriptions` works without a custom authentication backend. Remaining
items are implementation/contract checks, not feasibility blockers.

The final production-fork decision remains conditional on:

- branding and applicationId assessment;
- updater and network-call audit;
- Google Play and F-Droid distribution assessment;
- acceptable upstream maintenance cost.
