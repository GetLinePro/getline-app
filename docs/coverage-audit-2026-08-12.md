# Снимок покрытия перед следующими рефакторингами — 2026-08-12

Это разовый аудит, а не quality gate и не обещание поддерживать заданный процент.

Снимок нужен для двух вопросов:

1. какие части текущего поведения вообще исполняются существующими тестами;
2. стало ли после конкретного архитектурного выделения проще проверять поведение.

Он не отвечает на вопрос «можно ли безопасно выпускать приложение». Для этого
нужна карта критических сценариев ниже, а для browser/PWA/VPN lifecycle — ещё и
device/E2E-проверки.

## Решение о стоимости поддержки

- Coverage **не блокирует PR и релиз**.
- Общая цель вроде `80%` не вводится.
- JaCoCo/Kover и постоянная coverage-задача пока не добавляются в Gradle или CI.
- Полные HTML/XML-отчёты не хранятся в git: текущий комплект занимает около
  24 МБ и быстро устаревает.
- Этот документ не требуется обновлять после каждого PR.
- Повторный снимок имеет смысл только до и после заметного рефакторинга
  `auth`/`onboarding`/`import`/session/VPN repair либо при изменении тестовой
  стратегии.

Таким образом, текущая цена поддержки — один небольшой датированный документ.
Если повторные замеры окажутся полезны хотя бы дважды, автоматизацию можно
добавить отдельным решением. До этого строить инфраструктуру ради одной цифры
невыгодно.

## Точка снимка и метод

- Commit: `f4f3e9edfb6fe284b6485ac3d1f62e8cddc54d7c`
  (`fix(auth): fence cancelled browser callbacks`).
- Инструмент: JaCoCo 0.8.13, подключённый временным Gradle init-script вне
  репозитория.
- Продуктовый код и тесты ради замера не менялись.
- Выполнены существующие задачи:
  - `:app:testAlphaProdDebugUnitTest` — 521 тест, PASS;
  - `:app:testAlphaE2eDebugUnitTest` — 521 тест, PASS;
  - `:service:testAlphaDebugUnitTest` — 52 теста, PASS;
  - `:getlineui:testAlphaDebugUnitTest` — 6 тестов, PASS.
- Из знаменателя исключён генерируемый Android/data-binding/Room/KAIDL-код:
  `R`, `BuildConfig`, `Manifest`, binding-классы, `DataBinderMapperImpl`, Room
  `*_Impl`, KAIDL delegate/proxy.
- Для library-модулей учтено исполнение их классов всеми перечисленными
  unit-тестами, а не только тестами самого модуля.

`core-jvm` ниже означает только Kotlin/Java-обвязку `:core`. Go/Mihomo этим
инструментом не измеряется. Instrumented/device-тесты в снимок также не входят.

## Покрытие модулей

| Модуль | Line | Branch |
|---|---:|---:|
| `app` alpha/prod/debug | 2354 / 7408 (31.8%) | 1187 / 3904 (30.4%) |
| `app` alpha/e2e/debug | 2347 / 7408 (31.7%) | 1174 / 3904 (30.1%) |
| `service` | 315 / 2107 (15.0%) | 130 / 849 (15.3%) |
| `getlineui` | 108 / 1514 (7.1%) | 31 / 660 (4.7%) |
| `common` | 46 / 313 (14.7%) | 18 / 122 (14.8%) |
| `core-jvm` | 28 / 572 (4.9%) | 0 / 612 (0%) |
| `design` | 0 / 3206 (0%) | 0 / 637 (0%) |
| `hideapi` | 0 / 2 (0%) | ветвей нет |

Общие проценты модулей не являются приоритетами работ. В знаменателе много
унаследованного CMFA UI и платформенного glue, а стоимость отказа у разных строк
неравна. Полезная часть отчёта — конкретные красные и жёлтые зоны.

## Повторный снимок после #132–#134 — 2026-08-13

Повтор выполнен той же версией JaCoCo, тем же временным init-script, набором
модулей, flavors и исключений на `origin/main` @ `1095e818` после выделения
browser auth (#132), VPN repair (#133) и logout (#134). Оба app-flavor запустили
по 568 тестов, `service` — 52, `getlineui` — 6; failures/errors — 0.

| Область | Line до | Line после | Branch до | Branch после |
|---|---:|---:|---:|---:|
| `app` alpha/prod/debug | 2354 / 7408 (31.8%) | 2701 / 7475 (36.1%) | 1187 / 3904 (30.4%) | 1385 / 3932 (35.2%) |
| `app` alpha/e2e/debug | 2347 / 7408 (31.7%) | 2694 / 7475 (36.0%) | 1174 / 3904 (30.1%) | 1372 / 3932 (34.9%) |

Это не улучшение только за счёт уменьшения знаменателя: в `app` стало на 67
измеряемых строк и 28 ветвей больше, при этом покрытых строк стало на 347, а
покрытых ветвей — на 198 больше. Остальные модули не менялись.

| Выделенный flow | Line | Branch |
|---|---:|---:|
| `BrowserAuthFlow` | 140 / 189 (74.1%) | 50 / 94 (53.2%) |
| `VpnRepairFlow` | 107 / 111 (96.4%) | 110 / 159 (69.2%) |
| `LogoutFlow` | 70 / 71 (98.6%) | 17 / 18 (94.4%) |
| **Вместе** | **317 / 371 (85.4%)** | **177 / 271 (65.3%)** |

`GetLineHomeActivity` и `GetLineOnboardingActivity` ожидаемо остались около 0%:
покрытым стало вынесенное поведение, а не Android lifecycle glue. Их измеряемый
непокрытый объём при этом уменьшился соответственно с 1021 до 863 и с 1084 до
936 строк. Исходный вопрос закрыт: опасные пути не просто переложены в новые
файлы — они действительно исполняются новыми тестами. Общий coverage gate или
следующий срез из этого результата не следует.

## Основные находки исходного снимка

### Красная зона: orchestration и реальные side effects

| Компонент | Line | Branch | Что это означает |
|---|---:|---:|---|
| `GetLineOnboardingActivity` | 0.5% | 0% | Большая часть auth/import/lifecycle orchestration не исполняется JVM-тестами |
| `NativeAuthCallbackActivity` | 0% | 0% | Доставка callback и Activity lifecycle проверяются только через вынесенные helpers |
| `GetLineHomeActivity` | 0% | 0% | Реальное применение repair/start/logout решений не покрыто |
| `GetLineOnboardingDesign` | 0% | 0% | UI-состояния и wiring событий не исполняются |
| `ProfileManager` / `ProfileProcessor` / `TunService` | 0% | 0% | Профиль, файловые side effects и реальный VPN lifecycle остаются за пределами unit-тестов |

Ноль здесь не означает, что нужно тестировать каждую Activity-строку. Он
показывает границу: чистые решения часто проверены, а их последовательность,
cleanup и lifecycle-владение — нет.

### Жёлтая зона: auth/session

Пакет `pro.getline.vpn.getline.auth` целиком имеет 73.0% line и 57.8% branch,
но среднее скрывает важные различия:

| Компонент | Line | Branch |
|---|---:|---:|
| `BrowserAuthLauncher` | 30.8% | 31.1% |
| `RwpGetLineAuthApi` | 49.3% | 44.5% |
| `GetLineSessionRepository` | 62.8% | 47.0% |
| `GetLineSessionStore` | 85.4% | 53.3% |
| `PendingNativeAuthStore` | 87.5% | 67.6% |
| `AuthCallbackParser` | 85.5% | 60.9% |

Хранилище pending auth и чистая callback-политика покрыты заметно лучше, чем
browser/platform orchestration и сетевой/session handoff.

### Import и repair: строки выглядят лучше ветвей

| Компонент | Line | Branch |
|---|---:|---:|
| `GetLineImportCoordinator` | 90.8% | 57.4% |
| `ExternalImportActivity` | 97.8% | 72.2% |
| `VpnConfigurationRepairPolicy` | 100% | 85.7% |
| `PrimaryConfigDownloader` | 97.6% | 80.2% |
| `PrimaryConfigRefresher` | 96.2% | 73.1% |

Это подтверждает исходную гипотезу: line coverage легко выглядит зелёным при
непроверенных развилках cancellation, supersession, cleanup и retry. При этом
repair policy покрыта хорошо, а исполняющий её Home/service glue — нет.

## Карта критических сценариев

| Риск | Что уже проверяется | Что снимок не доказывает |
|---|---|---|
| Cancel ↔ auth callback | Claim/cancel marker, поздний callback, победа callback над cancel в unit-тестах | Реальную dual delivery, Activity recreation и teardown в момент обмена кода |
| Process death во время auth | Pending native auth хранится вне Activity; есть парсер и gate-тесты | Возврат из браузера после смерти процесса на реальном Android |
| Process death во время import | Pending import/cleanup UUID и startup routing тестируются | Полную цепочку recreation → resume → commit/cleanup → Home |
| Import cancellation/supersession | Coordinator проверяет join, replacement, reset, waiter cancellation и terminal ownership | Связку UI Cancel, Activity job, coordinator и profile backend как одно поведение |
| Session persistence | Encrypted-store recovery, fail-closed, logout и binding persistence имеют Robolectric-тесты | Реальное обновление/переустановку, Android Keystore/OEM-сбои и одновременную смерть процессов |
| VPN startup/repair | Routing и repair decisions, downloader/refresher тестируются | Permission flow, service recreation, active-profile side effects, Always-on/OEM lifecycle |
| Callback host / PWA | Контракт и ручная regression matrix описаны в `spikes/android-auth/README.md` | Browser/WebAPK routing невозможно доказать JVM coverage-отчётом |

При добавлении тестов приоритет задаёт эта таблица, а не самый большой красный
файл. Мучительный тест — сигнал рассмотреть узкое архитектурное выделение, но не
автоматическое основание дробить Activity или вводить новый framework.

## Как читать branch coverage

Branch coverage полезнее line coverage как навигация, но это всё ещё
bytecode-метрика JaCoCo:

- Kotlin coroutines, nullability и компиляторные преобразования влияют на
  знаменатель;
- рефакторинг может изменить число branch без изменения пользовательского
  поведения;
- 100% branch означает, что каждая развилка была пройдена в обе стороны, но не
  означает покрытие сочетаний состояний.

Например, шесть boolean-входов дают 64 сочетания. Несколько тестов могут дать
100% branch, не проверив большинство сочетаний. Для дорогих сценариев нужны
таблицы состояний/переходов и явные race-тесты; процент остаётся только
указателем.
