# Срез 7b: `refactor/restore-app-service-namespaces`

Спецификация на исполнение. База: результат среза 7a.

Читать сначала `docs/refactor/README.md` и `slice-7a-restore-library-namespaces.md` —
техника та же, здесь описаны только отличия.

---

## Почему больше не отложен

7b откладывался «до релиза», потому что смена namespace у модулей с компонентами меняет
их `ComponentName` и ломает у установленных приложений ярлыки, плитку Quick Settings,
настройку always-on VPN и `componentEnabledSetting` для «скрыть иконку».

Установленной базы нет: приложение — альфа возрастом в считаные дни, пользователей двое
(автор и заказчик), переустановка приемлема. Ограничение снято, отсрочка не имеет смысла.

Остаток: **88 файлов**, отличающихся от upstream только на `package`/`import` —
`service` 49, `app` 39. После этого среза их должно остаться ноль.

## Ключевое отличие от 7a: продуктовый код остаётся в своём пакете

В `app` два разных множества файлов:

- ~39 upstream-файлов (`MainActivity`, `BaseActivity`, `ProfilesActivity`, `PropertiesActivity`,
  legacy `*SettingsActivity`, …) — их надо вернуть в `com.github.kr328.clash`;
- ~60 продуктовых (`GetLineHomeActivity`, `GetLineOnboardingActivity`, `getline/**`,
  `cmfa/**`, `product/**`) — upstream о них не знает, конфликтов они не дают.

Тащить продуктовый код в `com.github.kr328.clash.*` не нужно и вредно: это чужой пакет,
и граница «наше / CMFA» перестанет читаться по имени.

Gradle `namespace` определяет только пакет генерируемых `R` и `BuildConfig` и базу для
резолва относительных `android:name=".Foo"` в манифесте. Он **не обязывает** файлы модуля
лежать в этом пакете. Поэтому:

```
namespace app     = com.github.kr328.clash
upstream-файлы    → app/src/main/java/com/github/kr328/clash/**
продуктовые файлы → app/src/main/java/pro/getline/vpn/**   (остаются на месте)
```

Следствия, которые надо отработать:

- `R` и `BuildConfig` переезжают в `com.github.kr328.clash`. Продуктовому коду понадобятся
  явные импорты — как минимум `BuildConfig.DEBUG` в `GetLineOnboardingActivity:63` и
  `R.drawable.ic_toggle_*` в `MainActivity`. Проверить все обращения к `R.` и `BuildConfig.`
  в `app/src/main/java/pro/getline/vpn/`.
- В `AndroidManifest.xml` относительные имена (`.MainActivity`) теперь резолвятся в
  `com.github.kr328.clash.*`. Продуктовые Activity должны быть прописаны **полным именем**:
  `android:name="pro.getline.vpn.GetLineHomeActivity"`, то же для `GetLineOnboardingActivity`
  и `QrScannerActivity`. Пропустить — `ClassNotFoundException` при запуске, не при сборке.

Для `service` такого разделения нет: продуктового кода там не заводили, модуль переносится
целиком.

## Инверсия правила из 7a — прочитать внимательно

В срезе 7a две строки было **запрещено** менять, потому что они указывали на классы `app`,
который тогда не переименовывался. Теперь `app` переименовывается, и обе строки менять
**обязательно**:

| Файл | Сейчас | Должно стать |
|---|---|---|
| `common/…/constants/Components.kt` | `componentsPackageName = "pro.getline.vpn"` | `"com.github.kr328.clash"` |
| `design/…/store/UiStore.kt` | `ComponentName(this, "pro.getline.vpn.MainActivityAlias")` | `"com.github.kr328.clash.MainActivityAlias"` |

`Components` резолвит `MainActivity` и `PropertiesActivity` — обе upstream-классы, обе
переезжают. `MainActivityAlias` объявлен в манифесте `app` относительным именем, значит
тоже переедет.

Оба файла после правки совпадут с upstream и уйдут из дифа — проверить это отдельно.

Ошибка здесь не ловится сборкой: Advanced перестанет открываться, переключатель «скрыть
иконку» перестанет работать.

## Что не меняется

- **`applicationId`** — остаётся `pro.getline.vpn` (+ суффиксы флейворов).
- **ContentProvider authorities и permissions** в `service/src/main/AndroidManifest.xml`
  (`:51,62,67,75,84`) построены на `${applicationId}`, не на namespace. Останутся
  `pro.getline.vpn.*.files/status/settings` — трогать не нужно и нельзя.
- **`Intents.kt`** строит action'ы из runtime `packageName` — не затрагивается.
- **Строковые константы** `"pro.getline.vpn.extra.*"` в продуктовых Activity — ключи Intent
  extra, к namespace отношения не имеют.
- Механизм upstream `custom.application.id` не возвращать (в CI нет `local.properties`,
  сборка молча уехала бы на `com.github.metacubex.clash`).
- Гейт границы: `ID_PATTERN` тот же. Пути в `TARGETS` указывают на продуктовые файлы,
  которые остаются в `pro/getline/vpn/` — проверить, что скрипт по-прежнему их находит
  и по-прежнему падает на негативном тесте.

---

## Работа

Техника та же, что в 7a: `git mv` (не копирование — иначе rename detection не сработает и
файлы не уйдут из дифа), правка `package`/`import`, затем корневой `build.gradle.kts`.

В корневом `build.gradle.kts` развилка, введённая в 7a, схлопывается обратно почти в
upstream-вид — единственным отличием остаётся `getlineui`:

```kotlin
namespace = when (name) {
    "getlineui" -> "pro.getline.vpn.getlineui"   // наш модуль, upstream его не знает
    "app" -> "com.github.kr328.clash"
    else -> "com.github.kr328.clash.$name"
}
```

### Коммиты

1. `service` — перенос, импорты у потребителей (`app`, `common`?), namespace модуля
2. `app` — перенос **только upstream-файлов**, продуктовые не трогать; полные имена
   продуктовых Activity в манифесте; импорты `R`/`BuildConfig` в продуктовом коде
3. `Components.kt` + `UiStore.kt` — значения на `com.github.kr328.clash`
4. Корневой `build.gradle.kts`

## AC

- [ ] **Главный:** ни одного файла во всём репозитории, отличающегося от upstream только
      на `package`/`import`:
      ```bash
      git diff -M --numstat upstream/main | grep '=>' | awk '$1==$2 && $1<=40' | wc -l   # 0
      ```
- [ ] `git diff -M --stat upstream/main` — показать общий диф до и после среза
- [ ] `Components.kt` и `UiStore.kt` ушли из дифа с upstream (`git diff upstream/main --
      common/src/.../Components.kt design/src/.../UiStore.kt` — пусто)
- [ ] `grep -rn "pro\.getline\.vpn" common/src core/src design/src service/src` — пусто
- [ ] `grep -rn "pro/getline\|pro\.getline" app/src/main/java/com/` — пусто
      (в upstream-файлах `app` не должно остаться ссылок на наш пакет, кроме импортов
      продуктовых классов там, где upstream-код их вызывает — перечислить такие места
      в отчёте)
- [ ] В `app/src/main/AndroidManifest.xml` продуктовые Activity прописаны полным именем
- [ ] `git log --diff-filter=R --summary` показывает переносы как rename, не D+A
- [ ] `./gradlew :app:assembleAlphaDebug` — EXIT=0
- [ ] `./gradlew :app:testAlphaDebugUnitTest` — EXIT=0
- [ ] `./gradlew :app:assembleMetaRelease` — EXIT=0 (минификация; databinding и JNI
      проверяются именно здесь)
- [ ] `./scripts/check-product-boundary.sh` → ok, плюс негативный тест — доказать, что
      скрипт всё ещё **падает** на внесённой утечке после смены путей
- [ ] `scripts/verify-mihomo-gate.sh` — прогнать
- [ ] `git status`: только `m core/src/foss/golang/clash`

## Ручная проверка на устройстве

**Ставить чистой установкой** — старый пакет удалить. Компоненты сменили имена, обновление
поверх оставит мусорные ярлыки и слетевшую always-on настройку; это ожидаемо и не является
дефектом (см. «Почему больше не отложен»).

1. Первый запуск: онбординг → вход → импорт подписки → Home.
2. Подключение VPN, трафик, таймер сессии — JNI-мост живой (7a его уже переименовал,
   но release-сборка здесь проверяется впервые после обоих срезов).
3. Список серверов, health check, переключение узла.
4. Advanced через 7 тапов по бренду — проверка `Components.MAIN_ACTIVITY`.
5. Advanced → Settings → «скрыть иконку» туда и обратно — проверка `mainActivityAlias`.
6. Ярлыки из динамических shortcuts (toggle/start/stop) — проверка `ExternalControlActivity`
   и `Components`.
7. Плитка Quick Settings добавляется и переключает VPN — `TileService` сменил имя.
8. Always-on VPN включается в системных настройках — `TunService` сменил имя.
9. Профили Advanced: создание, редактирование, удаление — `PropertiesActivity`,
   `ProfilesActivity`, `FilesProvider` authorities.
10. Перезагрузка устройства при включённом always-on — `RestartReceiver`.

Пункты 6–8 и 10 — именно те, что ломались бы при живой установленной базе. Здесь они
проверяются как обычная функциональность.

## Риски

- **Отказы в рантайме, не при сборке.** Продуктовые Activity с относительным именем в
  манифесте, `Components`/`UiStore` со старым значением, `TileService`/`TunService` в
  системных настройках — всё это компилируется. Ручную проверку не сокращать.
- **Слепая замена.** `sed` по всему дереву сломает `applicationId`, `${applicationId}`
  в манифесте `service`, Intent-extra ключи и продуктовые пакеты в `app`. Вести по модулям.
- **Продуктовые файлы в `app`.** Соблазн перенести их «заодно» в `com.github.kr328.clash`
  ради единообразия — не поддаваться: это ровно та граница, которую строили срезы 1–4.
