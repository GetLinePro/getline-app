# Спайк: чем заменить bearer в перехватываемом deep link

Статус: **разведка в процессе** (2026-08-06, Xiaomi 24069PC21G / Android 14).
Код продукта не пишем.

Цель — снять неизвестность **на практике**, доказательствами: curl, устройство,
stage, минимальный прототип. Не «спека на ревью».

Итог спайка — одна строка: **go / no-go по каждому варианту и цена.**

Варианты, между которыми выбираем:

| | что это |
|---|---|
| **A. Гейт** | Telegram только на ступени Auth Tab, ниже — кнопка выключена |
| **B. App Link** | завершение по верифицированной HTTPS-ссылке вместо схемы |
| **C. Ref-сервис** | одноразовая ссылка на эдже вместо bearer (черновик в приложении) |
| **D. Ждать RWP** | вендор отращивает нативную ветку для Telegram |

---

## Порядок

Дешёвое раньше дорогого. U1 и U2 могут закрыть вопрос целиком.

---

## U1. Почему App Link не сработал из Custom Tab

**Факт:** 2026-08-06 на устройстве, при верифицированном `auth.getline.pro`
(`pm get-app-links` → `verified`), страница `auth-callback.html` **отрисовалась**,
Chrome показал диалог «сайт пытается открыть приложение». App Link навигацию
не перехватил. Причина неизвестна.

**Гипотезы:**
1. Chrome никогда не отдаёт App Link из Custom Tab.
2. Chrome не отдаёт App Link **приложению, которое этот Custom Tab открыло**
   (защита от пинг-понга).
3. Не отдаёт по цепочке серверных редиректов, но отдал бы по прямой навигации.

**Как проверить:**

```bash
# 1. сборка с принудительной ступенью Custom Tab
./gradlew :app:installAlphaProdDebug -PforceBrowserRung=customtab

# 2. состояние верификации ДО прогона
adb shell pm get-app-links pro.getline.vpn.alpha.debug

# 3. лог во время реального входа через Telegram
adb logcat -c && adb logcat | grep -iE "browser_auth|auth_tab|native_auth|IntentHandler|url_handler"
```

Разделяет гипотезы 1 и 2 — тот же URL, но Custom Tab **не от нашего приложения**:

```bash
# открыть тот же callback URL из стороннего приложения-открывашки
# либо просто в Chrome как обычную вкладку:
adb shell am start -a android.intent.action.VIEW \
  -n com.android.chrome/com.google.android.apps.chrome.Main \
  -d 'https://auth.getline.pro/'
```

**Что значит результат:**
- перехватывает вне нашего Custom Tab, не перехватывает внутри → гипотеза 2,
  вариант B мёртв для нижних ступеней;
- не перехватывает нигде → верификация или assetlinks сломаны, см. U5;
- перехватывает везде, кроме цепочки редиректов → гипотеза 3, есть шанс
  почистить цепочку.

**Блокирует:** B. Если B живой — C и A не нужны.

---

## U2. Что реально получает вредоносное приложение

Сейчас «критически небезопасно» — это теория. Проверяется за 20 минут и даёт
либо подтверждённый захват аккаунта, либо снижение оценки.

**Как проверить:** собрать пустое приложение, которое регистрирует ту же схему
и логирует то, что получило.

```bash
adb shell pm list packages | grep getline   # взять точный applicationId
```

`AndroidManifest.xml` подставного приложения:

```xml
<activity android:name=".Grab" android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="pro.getline.vpn.alpha.debug"
          android:path="/oauth2redirect"/>
  </intent-filter>
</activity>
```

`Grab.onCreate`: `Log.e("GRAB", intent.dataString ?: "null"); finish()`.

Прогнать реальный вход через Telegram на ступени Custom Tab.

**Что значит результат:**
- показан выбор из двух приложений, токен уходит только выбранному → пользователь
  видит атаку, оценка падает до Medium;
- подставное приложение получает `auth_token` без выбора → захват аккаунта
  подтверждён, High, вариант A обязателен немедленно;
- проверить отдельно: что будет, если подставное приложение назначить
  обработчиком по умолчанию (реальный сценарий после одного неверного тапа).

**Блокирует:** приоритет всего остального.

---

## U3. Точно ли токен всегда во фрагменте

От этого зависит, возможен ли **серверный** обмен (тогда `auth-callback.html`
и whitelist уходят целиком) или обмен обязан быть в JS (тогда остаются).

Из документации — фрагмент (`docs/spikes/cmfa-feasibility.md:292`,
`AuthCallbackParser.parseHttpsWebToken`). Живьём не подтверждали.

```bash
# посмотреть Location от RWP с маркерной кукой, без выполнения JS
curl -sSD- -o /dev/null --cookie 'gl_native=1' \
  'https://app.getline.pro/api/auth/<telegram callback path>' | grep -i '^location'
```

Точный путь взять из логов реального входа (U1, шаг 3).

**Что значит результат:**
- `#/login?auth_token=` → серверный вариант невозможен, C остаётся с JS-страницей
  и whitelist;
- токен в query → C сильно дешевеет и **убирает** страницу и whitelist;
- есть недокументированный параметр вроде `app_redirect` → см. U5.

---

## U4. Сколько пользователей отрежет гейт

Гейт приемлем, только если «нет Auth Tab» — редкость.

```bash
# на каждом доступном устройстве
adb shell dumpsys package com.android.chrome | grep versionName
```

Плюс: какая минимальная версия Chrome поддерживает Auth Tab при нашей версии
`androidx.browser` (посмотреть в `gradle/libs.versions.toml` и в release notes
библиотеки).

**Что значит результат:** список прошивок/устройств без Auth Tab. Пусто или
экзотика → A приемлем не только на альфе.

---

## U5. Состояние верификации и вендор

Два независимых вопроса, оба дешёвые.

```bash
# наш пакет вообще есть в живом assetlinks?
curl -s https://auth.getline.pro/.well-known/assetlinks.json | jq .

# принудительная переверификация и результат
adb shell pm verify-app-links --re-verify pro.getline.vpn.alpha.debug
adb shell pm get-app-links pro.getline.vpn.alpha.debug
```

**Проверено 2026-08-06.** Живой файл содержит `pro.getline.vpn.alpha`,
`pro.getline.vpn.alpha.debug`, `pro.getline.vpn.alpha.e2e.debug`.
Пакет с устройства (`…alpha.debug`) на месте с debug-сертификатом, и
`pm get-app-links` показывал `verified`.

Значит **U1 не объясняется сломанным assetlinks** — верификация была настоящей,
а App Link из Custom Tab всё равно не сработал. Остаются гипотезы 1 и 2.

Отдельно: `pro.getline.vpn` (релизный, не-alpha) в файле **отсутствует** —
issue #17, на разведку не влияет.

Вендор: есть ли у RWP нативная ветка авторизации Telegram, есть ли канал связи и
какой у него срок ответа. Без ответа вариант D — не план, а состояние ожидания.

---

## U6. Прототип ref-сервиса — только если U1–U3 не закрыли вопрос

Не раньше. ~30 минут, локально, против stage.

Два хендлера, `map[ref] → {token, challenge, created_at}`, TTL 60 с.
**Redis не нужен:** один инстанс, минутный TTL, терять при рестарте нечего.

Измерить: строки кода, шаги деплоя, что происходит с входом при остановленном
сервисе.

---

## Форма ответа

Спайк закрыт, когда таблица заполнена:

| | вывод | доказательство |
|---|---|---|
| U1 App Link из Custom Tab | **no-go для B на Custom Tab.** Даже с Selection=Enabled + domain approved страница `auth-callback.html` отрисовывается в CCT; кнопка «Открыть приложение» → custom scheme. HTTPS App Link из CCT не перехватывает. System `VIEW` того же host **снаружи** CCT открывает приложение. | 2026-08-06 устройство: `pm get-app-links` → approved/Enabled; `./gradlew … -PforceBrowserRung=customtab`; лог `browser_auth_forced_rung rung=CustomTab` + `browser_auth_custom_tab package=com.android.chrome`; UI: страница + кнопка (не silent handoff). System VIEW `https://auth.getline.pro/` → GetLine. |
| U2 перехват подставным приложением | **Chooser, не silent.** Подставное `pro.spike.grab` на ту же схему → `ResolverActivity` (GetLine + SpikeGrab). Токен уходит только выбранному. Тихий захват — только после «Всегда» на malware. Оценка: **Medium** без default; **High** если default=malware. | `query-activities` — 2 handlers; `ResolverListAdapter` оба пакета; `am start` scheme → ResolverActivity; `GRAB: captured=…auth_token=…` при явном component; реальный Telegram: кнопка → выбор приложений. |
| U3 фрагмент или query | **Пока по контракту edge: fragment.** Caddy rewrite и `auth-callback.html` завязаны на `#/login?auth_token=`. Живой `Location` от RWP curl’ом не снят. | `Caddyfile.snippet` `header_down Location "^https://app\.getline\.pro/#/login"`; `auth-callback.html` читает `location.hash`. |
| U4 охват Auth Tab | На **этом** устройстве Auth Tab есть (Chrome 150 + category). Мульти-девайс список пуст. | `dumpsys`: CustomTabsService category `androidx.browser.auth.category.AuthTab`; `versionName=150.0.7871.186`; `androidx.browser` 1.10.0. |
| U5 assetlinks + вендор | **assetlinks OK.** Пакет alpha.debug в файле, SHA совпал, verified→approved. **Дополнительно:** изначально Selection был **Disabled** при verified — отдельная ловушка UX (не причина провала B в CCT: после Enable B всё равно мёртв). Вендор RWP native Telegram — **не опрошен**. | `curl assetlinks.json`; `pm get-app-links`; dumpsys Selection Disabled→Enabled. |
| **Решение** | **B no-go** для нижних ступеней (Custom Tab / external scheme). **A** остаётся дешёвым mitigation. **C** не закрыт U1–U3 (нужен только если A неприемлем). **D** — ожидание. | U1+U2 2026-08-06 |

### Прогон 2026-08-06 (факты)

- Устройство: Xiaomi 24069PC21G, Android 14, `pro.getline.vpn.alpha.debug`.
- Форс: `GETLINE_FORCE_BROWSER_RUNG=customtab`.
- До теста Selection для `auth.getline.pro` был **Disabled** (verified, но auto-open off). Включён вручную через `pm set-app-links-user-selection`.
- Реальный Telegram в CCT: страница handoff → кнопка «Открыть приложение» → chooser (из‑за SpikeGrab). Вход со **второго** раза (первый, вероятно, cancelled chooser / pending TTL — не доказано).
- «Со второго раза» **не опровергает** U1/U2: оба запуска в логе `browser_auth_forced_rung=CustomTab`.

---

# Приложение: черновик варианта C

Не часть разведки. Читать, только если U1–U3 вывели на C.

Это PKCE, применённый к вендорскому bearer нашими силами.

```
1. App    NativeAuthPkce.generate() → (verifier, challenge)
          открывает трамплин ?app_id=<pkg>&chal=<challenge>
2. Трамплин (JS)  ставит gl_app_id и gl_chal на .getline.pro
3. Telegram → RWP → редирект → auth.getline.pro/#/login?auth_token=…
4. auth-callback.html (JS)
          POST /native/stash {token, expires_in, challenge} → {ref}
          location.replace(pkg + ":/oauth2redirect?ref=" + ref)
5. App    POST /native/redeem {ref, verifier}
          сервис: S256(verifier) == challenge → удалить запись, вернуть токен
```

Перехватчик получает `ref`, который без `verifier` бесполезен.
`verifier` не покидает приложение иначе как по TLS на наш эдж.
`challenge` публичен по построению — его утечка безвредна.

**Чего вариант C не даёт:** не убирает `auth-callback.html`, `gl_app_id` и
whitelist (при токене во фрагменте); не убирает зависимость от RWP; не мешает
вредоносному приложению **сорвать** вход (отказ, не захват); добавляет
stateful-компонент на критический путь логина.

**Куда бить при ревью C:**
1. `/native/stash` неаутентифицирован. Проверка `verifier↔challenge` доказывает
   «тот же клиент, что начинал», но не «токен того самого пользователя» —
   фиксация сессии. Главный вопрос.
2. Неудачный redeem не должен удалять запись (иначе DoS мусорным verifier), но
   счётчик попыток открывает DoS с другой стороны.
3. Нужен ли rate limit на `/native/stash`.
4. Ветка `ref` не должна включаться на ступени Auth Tab вообще.
