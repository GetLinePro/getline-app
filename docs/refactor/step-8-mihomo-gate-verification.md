# Шаг 8: `chore/mihomo-gate-verification`

Спецификация на исполнение. База: `0566e071`.

**Независим от срезов 2–7.** Общих файлов с UI-работой нет — можно вести параллельно
другим исполнителем.

---

## Что уже сделано (не переделывать)

Проверено — инфраструктура патчей в рабочем состоянии:

- `core/patches/mihomo/0001-disable-ssh-outbound-no_ssh.patch` — patch трекается в родителе,
  submodule gitlink остаётся на upstream SHA (`e26714a1`).
- `scripts/apply-mihomo-patches.sh` — идемпотентен, с безопасным восстановлением после
  `submodule update --force` (сверяет остаточные untracked-файлы побайтово с ожидаемым
  результатом патча и отказывается работать при малейшем несовпадении).
- Все 4 workflow (`build-debug`, `build-pre-release`, `build-release`,
  `update-dependencies`) вызывают скрипт после `submodule update`.
- `core/build.gradle.kts` — task `applyMihomoPatches`, `outputs.upToDateWhen { false }`.
- `core/patches/mihomo/README.md` — назначение, порядок применения, шаги после bump.

## Настоящая проблема

**Гейт безопасности не имеет проверки результата.** Всё, что проверяется сейчас, — что патч
*приложился*. Если upstream отрефакторит SSH так, что патч приложится, но тег `no_ssh`
перестанет реально исключать SSH (новый путь импорта, новый файл, переезд символа), сборка
пройдёт зелёной и продукт поедет с SSH outbound.

`update-dependencies.yaml:16` делает `git submodule update --remote` и открывает PR —
то есть Mihomo может обновиться автоматически, а единственная защита это `git apply`.

README формулирует нужную проверку как ручную инструкцию:
```
3. Re-verify: `go list -tags 'foss,with_gvisor,cmfa,no_ssh' -deps` has no `metacubex/ssh`.
```
Автоматизировать её — содержание этого шага.

Вторая, меньшая: `git status` в корне **всегда** показывает `m core/src/foss/golang/clash`.
Продуктовая грязь неотличима от случайной локальной правки в submodule.

## Non-goals

- **`CMakeLists.txt` не минимизировать.** Он переписан целиком, и это обоснованно: upstream-версия
  вызывала `git submodule foreach` из неправильного `WORKING_DIRECTORY`, плюс добавлены
  `-ffile-prefix-map` (воспроизводимость) и генерируемый `getline_native_version.h`.
  Конфликт при bump — один файл, ~10 минут. Возврат к upstream-структуре ради «меньшего дифа»
  сломает работающее. Решение зафиксировать в комментарии, не в коде.
- Новые патчи, изменение `no_ssh`, апгрейд Mihomo — не в этом шаге.
- `.github/patch/*.patch` (патчи GOROOT) — не трогать.

---

## Работа

### 1. `scripts/verify-mihomo-gate.sh`

Стиль по образцу `scripts/apply-mihomo-patches.sh`: `#!/usr/bin/env bash`, шапка с причиной,
`set -euo pipefail`, `ROOT=` через `BASH_SOURCE`.

Три проверки, каждая с отдельным понятным сообщением и ненулевым выходом:

**A. Submodule на записанном gitlink.**
`git -C "$ROOT" ls-tree HEAD core/src/foss/golang/clash` → SHA; сверить с
`git -C "$CLASH" rev-parse HEAD`. Расхождение = кто-то двигал submodule вручную.

**B. Рабочее дерево submodule = ровно результат патчей, ничего сверх.**
Списки файлов уже есть в `apply-mihomo-patches.sh` (`SSH_GATE_TRACKED`, `SSH_GATE_NEW_FILES`) —
**вынести в общий источник**, а не копировать. Варианты: третий файл `scripts/mihomo-gate.lib.sh`
с `source`, либо `--verify` режим в существующем скрипте. Второе меньше, предпочесть его,
если не разрастётся.

Проверить:
- изменённые tracked-файлы == ровно `SSH_GATE_TRACKED`;
- untracked-файлы == ровно `SSH_GATE_NEW_FILES`;
- содержимое совпадает с тем, что патч даёт на HEAD (логика `build_expected_ssh_gate_tree`
  уже написана — переиспользовать).

Любой лишний изменённый или untracked файл = провал с перечислением.

**C. Тег реально исключает SSH.**
```bash
cd "$CLASH"
go list -tags 'foss,with_gvisor,cmfa,no_ssh' -deps ./... 2>/dev/null | grep -q 'metacubex/ssh'
```
Найдено → провал. Плюс обратная проверка, что тест вообще осмыслен: без `no_ssh`
пакет **должен** присутствовать. Если его нет и там — значит upstream переехал, проверка
выродилась в тавтологию, и это тоже провал с отдельным сообщением.

Требует Go в PATH. Если `go` отсутствует — не «пропустить молча», а выйти с ошибкой и
внятным текстом. Тихий skip воспроизводит ровно ту дыру, которую мы закрываем.

### 2. Подключить в CI

В `.github/workflows/build-debug.yaml` и `update-dependencies.yaml`, шагом
**после** `Apply Mihomo product patches` и **после** `Setup Go`:

```yaml
      - name: Verify Mihomo security gate
        run: ./scripts/verify-mihomo-gate.sh
```

Блокирующим (без `continue-on-error`). В `update-dependencies` — до создания PR, чтобы
сломанный bump не превращался в PR с зелёными галками.

`build-release` / `build-pre-release` — тоже добавить.

Порядок шагов важен: `Setup Go` в этих workflow идёт после `Setup Java`; проверку ставить
после него, иначе `go` не найдётся.

### 3. Runbook по upstream bump

В `core/patches/mihomo/README.md` раздел «Refresh after Mihomo bump» довести до
воспроизводимой процедуры:

```
1. git submodule update --remote --force
2. ./scripts/apply-mihomo-patches.sh        # упадёт, если патч не лёг → обновить патч
3. ./scripts/verify-mihomo-gate.sh          # упадёт, если гейт перестал работать
4. ./gradlew :app:assembleAlphaDebug
5. smoke на устройстве: подключение, трафик, выбор сервера
6. зафиксировать новый gitlink SHA в родителе
```

Плюс явный абзац: что делать, когда патч не применяется (обновить `.patch` против нового
дерева, не коммитить правки в submodule).

### 4. Комментарий про `CMakeLists.txt`

В шапку `core/src/main/cpp/CMakeLists.txt` — 3–4 строки: файл переписан относительно upstream
намеренно (сломанный `git submodule foreach`, воспроизводимые пути, генерируемый заголовок);
при upstream bump конфликт разрешается в пользу нашей версии. Чтобы следующий человек не
пытался «вернуть как в upstream».

---

## Коммиты

1. `scripts/verify-mihomo-gate.sh` + общий источник списков файлов
2. подключение в 4 workflow
3. runbook в README + комментарий в CMakeLists

## AC

- [ ] `./scripts/verify-mihomo-gate.sh` на чистом дереве после `apply-mihomo-patches.sh` — проходит
- [ ] Негативный тест A: `git -C core/src/foss/golang/clash checkout <другой SHA>` → падает
      с сообщением про gitlink. Вернуть обратно.
- [ ] Негативный тест B: создать лишний файл в submodule → падает с указанием этого файла.
      Удалить.
- [ ] Негативный тест C: временно снять `no_ssh` из `core/build.gradle.kts`… **нет** —
      проверка C читает теги из самого скрипта, не из Gradle. Правильный негативный тест:
      временно поменять список тегов внутри скрипта на вариант без `no_ssh` → должна
      сработать проверка «SSH найден». Вернуть.
- [ ] Все три негативных теста показаны в отчёте с фактическим выводом
- [ ] `./gradlew :app:assembleAlphaDebug` — EXIT=0, дерево submodule после прогона по-прежнему
      проходит `verify-mihomo-gate.sh`
- [ ] `git status` в корне после всех прогонов: только `m core/src/foss/golang/clash`,
      ничего нового
- [ ] Workflow-файлы: проверить синтаксис (`actionlint`, если доступен; иначе визуально —
      отступы и порядок относительно `Setup Go`)

## Не проверять и не чинить

Грязь `m core/src/foss/golang/clash` остаётся — она и есть результат патча. Задача шага не
убрать её, а сделать проверяемой.
