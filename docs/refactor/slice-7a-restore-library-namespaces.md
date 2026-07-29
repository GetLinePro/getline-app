# Срез 7a: `refactor/restore-library-namespaces`

Спецификация на исполнение. База: `6baad994`.

Читать сначала `docs/refactor/README.md`. Этот срез меняет порядок, записанный там —
обоснование ниже.

---

## Зачем и почему сейчас

Замер после среза 4: **218 из 237 файлов, изменённых относительно upstream, отличаются
ровно на строки `package` и `import`.** Содержательных правок в них нет. Это и есть главный
оставшийся источник конфликтов при каждом bump — переименование namespace, которое,
как установлено в README, изначально было не нужно.

Распределение: `design` 86, `service` 49, `app` 39, `core` 22, `common` 22.

README откладывал шаг 7 «на последний» с обоснованием «переименуем то, что потом удалим при
срезах 5/6». Но срез 5 отложен осознанно (решение 1 — Advanced нужен как диагностика), а 6
блокирован пятым. Экономия, которая ждёт бессрочно отложенного среза, не окупается никогда,
тогда как конфликты приходят с каждым bump. Блокировка снимается.

## Охват: только модули без компонентов манифеста

AGP резолвит `android:name=".Foo"` в манифесте по **namespace**, а не по `applicationId`
(проверено на собранном манифесте: `android:name="pro.getline.vpn.MainApplication"` при
`package="pro.getline.vpn.alpha"`). Поэтому смена namespace у модуля с компонентами меняет
их `ComponentName` у уже установленных приложений и ломает:

- ярлыки на рабочем столе и динамические shortcuts;
- плитку Quick Settings (`TileService`);
- системную настройку «always-on VPN» (`TunService`);
- состояние «скрыть иконку» — `componentEnabledSetting` привязан к `MainActivityAlias`.

Проверено, компоненты манифеста есть только в **`app`** и **`service`**. У `common`, `core`,
`design`, `hideapi`, `getlineui` — ни одного.

Отсюда охват этого среза:

| Модуль | Namespace после | Почему |
|---|---|---|
| `common` | `com.github.kr328.clash.common` | нет компонентов |
| `core` | `com.github.kr328.clash.core` | нет компонентов |
| `design` | `com.github.kr328.clash.design` | нет компонентов |
| `hideapi` | `com.github.kr328.clash.hideapi` | нет компонентов; исходников в нашем пакете нет вовсе (единственный файл — `android/app/ActivityThread.java`) |
| `app` | **не меняется** | компоненты |
| `service` | **не меняется** | компоненты |
| `getlineui` | **не меняется** | наш продуктовый модуль, upstream его не знает |

130 из 218 файлов (60%) уходят из дифа с нулевым риском для установленной базы.

Остаток (`app` 39, `service` 49) — отдельный срез **7b**, приуроченный к релизу, где потерю
ярлыков и always-on можно предупредить в changelog. Записать в README как отложенный.

## Non-goals

- **`applicationId` не трогать.** Остаётся `pro.getline.vpn` (+ суффиксы флейворов).
  Механизм upstream `custom.application.id` из `local.properties` **не возвращать**: в CI
  этого файла нет, и сборка молча уехала бы на `com.github.metacubex.clash`.
- `minSdk 23` / `targetSdk 36` — наши намеренно (androidx.browser Auth Tab), к upstream не
  возвращать.
- Не трогать `service` и `app` namespace — это 7b.
- Не менять поведение, не чинить ничего попутно. Срез строго механический.
- Не трогать `core/build.gradle.kts`: `com.github.kr328.golang` — внешний плагин, к нашему
  namespace отношения не имеет.

---

## Работа

### Корневой `build.gradle.kts`

Сейчас (`:44`):

```kotlin
namespace = if (name == "app") "pro.getline.vpn" else "pro.getline.vpn.$name"
```

Заменить на явную развилку по списку — не по «угадыванию», чтобы следующий добавленный
модуль не получил чужой namespace молча:

```kotlin
// Библиотеки CMFA держат upstream namespace: их файлы должны совпадать с upstream
// байт в байт, иначе каждый bump конфликтует на ~130 файлах.
// app и service остаются на pro.getline.vpn — смена namespace меняет ComponentName
// у установленных приложений (ярлыки, QS-плитка, always-on VPN). См. срез 7b.
val upstreamNamespaceModules = setOf("common", "core", "design", "hideapi")
namespace = when {
    name in upstreamNamespaceModules -> "com.github.kr328.clash.$name"
    name == "app" -> "pro.getline.vpn"
    else -> "pro.getline.vpn.$name"
}
```

### Перенос каталогов

Только через `git mv`, каталог за каталогом:

```
common/src/main/java/pro/getline/vpn/common  → common/src/main/java/com/github/kr328/clash/common
core/src/main/java/pro/getline/vpn/core      → core/src/main/java/com/github/kr328/clash/core
design/src/main/java/pro/getline/vpn/design  → design/src/main/java/com/github/kr328/clash/design
```

Плюс `src/foss/java`, `src/test`, `src/androidTest`, если в этих модулях есть — проверить
`find <module>/src -type d -name vpn`.

`git mv` обязателен: rename detection — единственное, за счёт чего файл исчезает из
`git diff -M upstream/main`. Скопировать и удалить — диф покажет D+A и цель среза не будет
достигнута, хотя сборка пройдёт.

### Правки в исходниках

1. `package`/`import` внутри перенесённых модулей.
2. `import` у потребителей: `app`, `service`, `getlineui`, их тесты. Здесь namespace
   собственного пакета не меняется — только импорты `pro.getline.vpn.{common,core,design}.*`.
3. Databinding в `design/src/main/res/layout/*.xml`:
   `<variable type="pro.getline.vpn.design.…">`, `<import type="…">` и полные имена
   custom View в тегах — все на новый пакет.

### JNI — критическая часть

`core/src/main/cpp/main.c` содержит **два независимых набора** ссылок на Java-пакет. Оба
отказывают **в рантайме**, а не при сборке:

1. **35 экспортируемых символов** `Java_pro_getline_vpn_core_bridge_Bridge_*` →
   `Java_com_github_kr328_clash_core_bridge_Bridge_*`. Пропуск — `UnsatisfiedLinkError`
   при первом вызове, компиляция и линковка проходят.
2. **5 строк `find_class`** (`main.c:554–559`):
   `"pro/getline/vpn/core/bridge/TunInterface"`, `FetchCallback`, `LogcatInterface`,
   `ClashException`, `Content` → `"com/github/kr328/clash/core/bridge/…"`.
   Пропуск — крэш при инициализации моста. Слеши, не точки.

Проверить, что после правки `grep -c "pro_getline\|pro/getline" core/src/main/cpp/` = 0.
Go-сторона (`core/src/main/golang`) ссылок на Java-пакеты не содержит — проверено, не трогать.

### Что выглядит как namespace, но менять НЕЛЬЗЯ

Две строки указывают на классы **`app`**, который в этом срезе не переименовывается:

- `common/…/constants/Components.kt:7` — `componentsPackageName = "pro.getline.vpn"`
  (`MAIN_ACTIVITY`, `PROPERTIES_ACTIVITY`);
- `design/…/store/UiStore.kt:87` — `ComponentName(this, "pro.getline.vpn.MainActivityAlias")`.

Файлы переезжают в upstream-пакет, но эти **значения** остаются нашими. Механическая замена
«всех вхождений `pro.getline.vpn`» здесь сломает запуск Advanced и переключатель «скрыть
иконку» — и снова только в рантайме. Оба файла и так содержат содержательный диф от upstream,
так что из дифа они не исчезнут — это ожидаемо.

Отдельно: строковые константы вида `"pro.getline.vpn.extra.*"` в `app` — это ключи Intent
extra, привязанные к `applicationId`, а не к namespace. Не трогать.

`Intents.kt` строит action'ы из runtime `packageName` (`common/util/Global.kt:5`) — не
затрагивается.

---

## Коммиты

Снизу вверх, каждый собирается:

1. `common` + импорты у всех потребителей
2. `core` + импорты + **JNI (оба набора)**
3. `design` + импорты + databinding в layouts
4. Корневой `build.gradle.kts` — развилка namespace

Порядок 4-м, а не 1-м: до переноса файлов смена namespace ломает сборку. Либо ставить его
первым и держать сборку сломанной один коммит — не делать, каждый коммит должен собираться.
Если AGP потребует namespace до переноса — совмещать namespace модуля с его же коммитом
переноса, а корневой файл править по частям.

## AC

- [ ] **Главный:** ни одного файла в `common`, `core`, `design`, `hideapi`, отличающегося
      от upstream только на `package`/`import`:
      ```bash
      git diff -M --numstat upstream/main -- common core design hideapi \
        | grep '=>' | awk '$1==$2 && $1<=40' | wc -l    # ожидается 0
      ```
- [ ] Общий счётчик по репозиторию упал со 218 до ~88 (остаются `app` и `service` — 7b):
      ```bash
      git diff -M --numstat upstream/main | grep '=>' | awk '$1==$2 && $1<=40' | wc -l
      ```
- [ ] `git diff -M --stat upstream/main -- design` — показать до/после
- [ ] `grep -rn "pro_getline\|pro/getline" core/src/main/cpp/` — пусто
- [ ] `grep -rn "pro\.getline\.vpn" common/src core/src design/src` — ровно два вхождения:
      `Components.kt` и `UiStore.kt` (см. выше)
- [ ] `git log --diff-filter=R --summary` показывает переносы как rename, не как D+A
- [ ] `./gradlew :app:assembleAlphaDebug` — EXIT=0
- [ ] `./gradlew :app:testAlphaDebugUnitTest` — EXIT=0
- [ ] `./gradlew :app:assembleMetaRelease` (или `bundleMetaRelease`) — EXIT=0. Release
      минифицируется; JNI и databinding проверяются именно здесь, debug может скрыть проблему
- [ ] `./scripts/check-product-boundary.sh` → ok. `ID_PATTERN` не меняется (идентификаторы
      те же), но пути в `TARGETS` проверить — они указывают на `app`/`getlineui`, которые
      не переносятся
- [ ] `scripts/verify-mihomo-gate.sh` — прогнать
- [ ] `git status`: только `m core/src/foss/golang/clash`

## Ручная проверка на устройстве — обязательна, автотестами не покрыта

JNI и `find_class` **не проверяются сборкой**. Всё, что ниже, проверяет ровно их:

1. Установить поверх предыдущего билда (не чистая установка) и **подключить VPN**.
   Это единственная проверка обоих наборов JNI-ссылок сразу.
2. Трафик и таймер сессии тикают — `nativeQueryTrafficTotal`, `nativeQuerySessionDuration`.
3. Список серверов, health check, переключение узла — `nativeQueryGroup`,
   `nativeHealthCheck`, `nativePatchSelector`.
4. Импорт подписки (fetch + commit) — `FetchCallback` через `find_class`.
5. Логи (Advanced → Logs, при запущенном сервисе) — `LogcatInterface`.
6. Ошибка ядра, если воспроизводима, показывает текст, а не крэшит — `ClashException`.
7. Advanced открывается из 7 тапов по бренду — проверка `Components.MAIN_ACTIVITY`.
8. Advanced → Settings → «скрыть иконку», переключить туда и обратно — проверка
   `mainActivityAlias`. Иконка исчезает и возвращается.
9. Ярлык на рабочем столе, добавленный **до** обновления, продолжает работать; плитка
   Quick Settings на месте; always-on VPN не слетел. Это подтверждение, что охват среза
   выбран верно — если что-то из этого сломалось, значит переименован `app` или `service`.
10. Профили и настройки на месте после обновления.

## Риски

- **Отказы только в рантайме.** Сборка зелёная не значит ничего для JNI. Пункты 1–6 ручной
  проверки не сокращать.
- **Rename detection.** Если после среза `git diff -M upstream/main` не схлопнулся — почти
  наверняка файлы скопированы, а не перенесены `git mv`, либо содержимое отличается ещё
  чем-то помимо `package`/`import`. Разбираться до коммита 4, а не после.
- **Слепая замена.** `sed -i 's/pro\.getline\.vpn/com.github.kr328.clash/g'` по всему дереву
  сломает `Components.kt`, `UiStore.kt`, Intent-extra ключи и `applicationId`. Замену вести
  по модулям, с проверкой каждого совпадения.
