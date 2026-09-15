# Fiskentra 0.25 — приёмка карты

**Повторная проверка 15–16 сентября:** [MAP_RECHECK.md](MAP_RECHECK.md) — четыре дополнительных исправления, 453 автоматические проверки и новый APK `18809E97…`. Доказательства ниже сохраняют указанные исходные даты/сборки; полный полевой статус не изменён автоматически.

Дата среза: **15 сентября 2026**. Ветка `codex/field-map-v021`, HEAD базы `c83fbc64b173767da887f3dfebc94d396040dcc0`, проверяемые изменения находятся в рабочем дереве. `versionName 0.25-map-beta`, `versionCode 36`. [Описание реализации](MAP_BETA_IMPLEMENTATION.md).

**Полная приёмка: NOT RUN.** Полный составной сценарий не считается выполненным по одному проверенному подмножеству. PASS ниже относится только к указанному scope и доказательству; прежние дефекты Show area/coverage исправлены и повторены на финальном APK. Исходные отчёты 0.21–0.24 не подтверждают новую сборку. NOT RUN означает отсутствие полной фактической проверки; BLOCKED не используется для просто ещё не выполненной работы.

Локальные доказательства `qa/map-beta/` исключены из Git, поскольку часть содержит реальные данные устройства. В репозитории сохраняются воспроизводимые runners и эти документы; приватные скриншоты/архивы не публикуются.

## Итоговая матрица ТЗ

| ID | Статус полной строки | Что подтверждено | Что нужно до PASS |
|---|---|---|---|
| G1–G5 | NOT RUN | 54 PointLedger assertions с повторным открытием файла, 21 TrackRecording check; миграция и повтор после QA: 125 точек/9 архивов/фото без изменений; 20 UI-переходов без роста Activity/ViewRootImpl. [Регрессия](qa/map-beta/regression-summary.md), [миграция](qa/map-beta/data-migration-report.json), [после QA](qa/map-beta/final-data-integrity.json), [lifecycle](qa/map-beta/lifecycle/report.json). | Полный T12/post-GC retained анализ, физические Flic callbacks/экран off/GPS off/reconnect; Android crash/restart и очередь после offline/delete на тестовых данных. |
| F1 | NOT RUN | Реальная catch-карточка с фото/полями/действиями проверена на [01-catch-final.png](qa/map-beta/01-catch-final.png); scoped 200% повтор с доступными favorite/More/Navigate/Edit/Details и независимой прокруткой — PASS. [200%](qa/map-beta/font-200-fixed.png), [прокрутка](qa/map-beta/font-200-scrolled.png). | Все варианты point/unlocated/photo failure/sync error, selected ≠ target, edit/delete/Back/focus не пройдены полным сценарием. |
| F2 | NOT RUN | Scope/types OR/favorite AND/DST/empty/unlocated входят в 57 MapPolicies checks; trip migration/favorite — в ledger. Live filter изменил количество до 46, Reset восстановил 125: [экран 02](qa/map-beta/02-map-filters.png). [Policy](qa/map-beta/regression-MapPolicies.log). | Полная сверка map/list/clusters/search, две поездки/через полночь, restart/filter-hidden-selected, отдельная target при пустом фильтре. |
| F3 | NOT RUN | Policy gesture→FREE, north wrap/deadband/course hysteresis, invalid-state restore; 57 checks PASS после heading freshness fix. На телефоне после ручного pan и 7 s GPS-обновлений сохранился FREE без возврата камеры: [UI](qa/map-beta/camera-free-after-gps.json). [Policy](qa/map-beta/regression-MapPolicies.log). | Все padding/sheet, sensor loss, ориентация в движении и Activity recreation без camera snap. |
| F4 | NOT RUN | 10/50/150 м на широтах 0/52/80°, 29/31 с, reboot-origin, 75/50/25 м gates; durable unlocated records. На телефоне подтверждён Location off после 5 s и восстановление GPS. [Policy](qa/map-beta/regression-MapPolicies.log), [ledger](qa/map-beta/regression-PointLedger.log), [disabled UI](qa/map-beta/gps-disabled-ui.json). | Approximate/denied/location lost, реальный low-accuracy circle, reboot и Flic без GPS. |
| F5 | NOT RUN | 33 policy + 38 SDK orchestration checks; Satellite 2 km до SDK READY/100%, 30.3 MiB, сохранённая после обновлений/перезапуска. На финальном APK повтор 23:09 — PASS: прямоугольник виден, coverage Available offline. [Policy](qa/map-beta/regression-OfflineAreaPolicy.log), [controller](qa/map-beta/regression-OfflineController.log), [05](qa/map-beta/05-offline-area.png), [UI](qa/map-beta/offline-ready-ui.json). | Airplane mode, полная смена style/zoom/bounds, pause/retry/replace/delete и реальная ошибка storage не пройдены на телефоне. |
| F6 | NOT RUN | 45 backtrack checks: reverse snapshot, gaps, loop/parallel/ambiguous start, off-route/arrival, persistence. Финальный framing/layout повтор 23:09 — PASS: reverse geometry/Start видны, Offline и Locate разнесены. [Лог](qa/map-beta/regression-MapGuidanceSearch.log), [06](qa/map-beta/06-backtrack.png). | Реальное движение по сохранённой линии и рядом с ней, разрыв и продолжение, airplane mode, Android restore, голос/lifecycle; исходный GPX до/после. |
| F7 | NOT RUN | 37 fixtures + реальный Unicode HTTPS: HTTP 200, 9 пригодных результатов/attribution. Phone UI подтвердил поиск Gävle и preview без автоматического сохранения. [Подсистемный отчёт](MAP_BACKTRACK_SEARCH.md), [тесты](qa/map-beta/regression-MapGuidanceSearch.log), [снимок](qa/map-beta/07-place-search.png). | Offline My points, выбор озера/без bounds, явно сохранённая точка, уход во время запроса, ошибки/retry и полнота атрибуции в разных состояниях. |
| F8 | NOT RUN | На финальном APK `5028A341…` оба Window frame профиля при 60 Hz — PASS: standard p95 10.532985 ms, stress 9.858864 ms, >50 ms 0%, процессы сохранились. 17 проверок инструментов. Scoped 200%-font карточки/статусов/navigation на `3D0A746C…` — PASS. [Standard](qa/map-beta/performance/standard-report.json), [stress](qa/map-beta/performance/stress-report.json), [методика](MAP_BETA_F8_MEASUREMENTS.md), [200%](qa/map-beta/font-200-fixed.png). | Все 48 dp/контраст/TalkBack/ширины/overlays; native cluster/reload/geometry, local action latency, retained memory и батарея. |
| BUILD | PASS | Последние assembleDebug+lintDebug PASS; 40 s, lint 0 errors / 100 warnings. APK установлен, SHA-256 ниже. 16 JVM runners / 17 групп / 444 checks и 7 weather checks PASS в зафиксированном scope. [Build](qa/map-beta/final-build.log), [lint](app/build/reports/lint-results-debug.txt), [регрессия](qa/map-beta/regression-summary.md), [MapPolicies](qa/map-beta/regression-MapPolicies.log), [weather](qa/map-beta/weather-swipe.log). | Финальная сборка/checksum, повторы 05/06/07, weather и Window frame-профили подтверждены. Полная продуктовая приёмка не следует из успешной сборки. |

## Тесты T01–T12: scope и пробелы

| ID | Подтверждённая часть | Статус | Доказательство / оставшаяся проверка |
|---|---|---|---|
| T01 | Durable file commit/reopen, исходное время, duplicate callback, поздняя привязка, write failure | PASS | `Test-PointLedger.ps1`; [54 assertions](qa/map-beta/regression-PointLedger.log). Android kill/boot и реальный Flic capture — NOT RUN. |
| T02 | Offline delete до upload, in-flight upsert, retry/auth policy/409/zero-row response, tombstone и позднее enrichment | PASS | [PointLedger log](qa/map-beta/regression-PointLedger.log), `tests/PointLedgerTest.java`. Реальная backend/RLS и сеть устройства — NOT RUN. |
| T03 | Legacy migration/retry/atomic failure/all old fields и реальное обновление 0.24 | PASS | [Лог](qa/map-beta/regression-PointLedger.log), [реальное сравнение](qa/map-beta/data-migration-report.json), `qa/Compare-MapDataMigration.py`. Другие Android-устройства — NOT RUN. |
| T04 | OR/AND, DST 23/25 h, half-open dates, разные trip IDs, unknown type, unlocated и empty | PASS | [MapPolicies](qa/map-beta/regression-MapPolicies.log); live filter 46 → Reset 125 подтверждён, [02](qa/map-beta/02-map-filters.png). Полная сверка map/cluster/search и сценарий через полночь — NOT RUN. |
| T05 | Selected отдельно от target; close/delete/overlay/back/focus | NOT RUN | Есть code review, catch screenshots и scoped 200%-font PASS с независимой прокруткой body/footer. Полного воспроизводимого UI отчёта ещё нет; снимок не доказывает все действия. |
| T06 | Gesture→FREE, north wrap, heading/course hysteresis, freshness и безопасный restore | PASS | [57 MapPolicies checks](qa/map-beta/regression-MapPolicies.log); road UI с FOLLOW_HEADING/compass снят на предыдущем APK. После native pan и 7 s GPS сохранился FREE: [UI](qa/map-beta/camera-free-after-gps.json). Rotation, все padding и камера в физическом движении — NOT RUN. |
| T07 | Реальная meter geometry, monotonic age и разные operation gates | PASS | [Лог](qa/map-beta/regression-MapPolicies.log), [disabled UI](qa/map-beta/gps-disabled-ui.json). Approximate permission/denied/low-accuracy visual — NOT RUN. |
| T08 | Bounds/style/zoom/indeterminate/no-space/replacement/durable-delete/recovery | PASS | [33](qa/map-beta/regression-OfflineAreaPolicy.log) + [38](qa/map-beta/regression-OfflineController.log); native READY 2 km/30.3 MiB подтверждён. Airplane mode и весь аппаратный lifecycle отказов — NOT RUN. |
| T09 | Неизменный reverse snapshot, loop/parallel/start-midway/gap/off-route/arrival, файл сохранения | PASS | [45 backtrack checks](qa/map-beta/regression-MapGuidanceSearch.log). Полевой трек/голос/Android restart — NOT RUN. |
| T10 | Debounce/latest-wins/cancel/Unicode/malformed/403/429/timeout + live provider contract | PASS | [37 search checks](qa/map-beta/regression-MapGuidanceSearch.log), [live contract](MAP_BACKTRACK_SEARCH.md), [Gävle preview](qa/map-beta/07-place-search.png). Offline local search и весь набор selection/save/navigation actions — NOT RUN. |
| T11 | Fixture counts/IDs/coincident markers/segment boundaries; native renderer и оба frame профиля | NOT RUN | [17 checks](qa/map-beta/regression-MapDebugTools.log), [standard](qa/map-beta/performance/standard-report.json) и [stress](qa/map-beta/performance/stress-report.json) frame budgets PASS. Native cluster counts/max zoom/style reload/render-only simplification отдельно не подтверждены. |
| T12 | Совместная запись/Flic/GPS/filter/search/backtrack и отсутствие утечек | NOT RUN | [Отчёт 20 Map→Saved→Map](qa/map-beta/lifecycle/report.json): 164.295 s, PID 23451 непрерывен, 1 Activity / 1 ViewRootImpl / 214 Views на 0/10/20. Post-GC retained-object анализ, смешанный сценарий и rotation — NOT RUN. |

PASS pure harness не повышает автоматически соответствующую полную строку G/F до PASS. Новые changes после времени регрессии требуют повторить затронутые checks.

## Реальная миграция данных устройства CPH2609

Источник: [data-migration-report.json](qa/map-beta/data-migration-report.json), полученный сравнением private backup до обновления и состояния после установки поверх прежней версии. Фактический `Build.MANUFACTURER` этого телефона — **OnePlus**, модель CPH2609, Android 15 / API 35; прежнее рабочее обозначение «OPPO» относилось к профилю совместимости и не является отдельным проверенным устройством.

| Объект | До → после | Результат |
|---|---|---|
| Точки | 125 → 125, missing 0 | PASS |
| ID/lat/lon/time/type/note/title/symbol/color/size/weather/catch details | 0 изменённых исходных полей по каждому ключу | PASS |
| Legacy point backup | Exact | PASS |
| Текущий трек | 1 → 1 координата, 0 → 0 разрывов, preference keys неизменны | PASS; это не доказательство длительной записи |
| Архивные поездки | 9 → 9, payload exact | PASS |
| Архивные маршруты | 132 → 132 координаты, 13 → 13 разрывов | PASS |
| Map preferences | Changed keys: [] | PASS на момент сравнения до performance QA |
| Фото | 1 → 1 файл, исходный hash exact; 1 catch-photo link | PASS |
| Legacy trip assignment | 78 без однозначной поездки | Оставлены Unassigned и видимы в All points; не назначены произвольно |

После device QA выполнено отдельное повторное сравнение: [final-data-integrity.json](qa/map-beta/final-data-integrity.json), **PASS**. Сохранены 125/125 исходных точек, по каждому исходному полю 0 изменений; 9/9 архивов с точным payload, 132/132 координаты, 13/13 разрывов, 1/1 исходное фото с точным SHA и прежней ссылкой. Число точек не выросло от debug fixtures. Текущий трек/разрывы и track preferences точны. Единственное изменённое map preference — `route_profile`, ожидаемо после проверки Walking/Driving; это не утверждение о неизменности абсолютно всех настроек. Визуальное восстановление обзора проверяется отдельно от сохранности данных.

## Полнота визуальных и UX-критериев

| Критерий | Статус / нужное доказательство |
|---|---|
| 01 — catch sheet с/без фото, обычная точка, unlocated, error sync, expanded/collapsed | NOT RUN всего набора; PASS реальной catch-карточки с фото/полями/действиями: [01-catch-final.png](qa/map-beta/01-catch-final.png), [200%](qa/map-beta/font-200-fixed.png), [независимая прокрутка](qa/map-beta/font-200-scrolled.png). Варианты без фото/unlocated/error sync и все действия отдельно не пройдены. |
| 02 — scope/type/favorite/date filter и согласованный счётчик | NOT RUN полного набора; PASS live filter до 46 и Reset до 125, [актуальный экран](qa/map-beta/02-map-filters.png). Сверка всех map/list/search/cluster и date/trip сценариев на устройстве не выполнена. |
| 03 — FOLLOW_HEADING и дорожная панель, независимый compass | PASS дорожного UI smoke на предыдущем APK `F1490AD6…`: Walking 14.3 km / 2 h 50 min → Driving 14.6 km / 22 min, ETA, правый поворот через 258 m и шаги с выездами с круговых перекрёстков. [Экран](qa/map-beta/03-camera-follow.png), [Driving UI](qa/map-beta/road-driving-ui.json), [Steps UI](qa/map-beta/road-steps-ui.json). Полное следование в движении/arrival/rerouting — NOT RUN. |
| 04 — 10/50/150 m accuracy, stale/permission/disabled, separate compass status | NOT RUN всего набора; PASS pure геометрии/age и GPS-off UI smoke после 5 s с восстановлением GPS: [04](qa/map-beta/04-gps-accuracy.png), [disabled UI](qa/map-beta/gps-disabled-ui.json). Реальный low-accuracy circle и permissions не проверены. |
| 05 — exact SDK rectangle, ready/candidate/progress/error, разные возможности offline | NOT RUN всей строки; PASS READY/framing/coverage на финальном `5028A341…`, 23:09: весь прямоугольник виден, SDK 2 km / 30.3 MiB готов, `Coverage at map center · Available offline`. [Экран](qa/map-beta/05-offline-area.png), [UI](qa/map-beta/offline-ready-ui.json). Прежние дефекты Show area/coverage закрыты этим повтором; airplane mode и все состояния lifecycle не пройдены. |
| 06 — backtrack/start flag/remaining known/gap/off-route | NOT RUN всего набора; PASS финальной раскладки/кадрирования 23:09: reverse geometry/Start видны, compact HUD/summary, Offline/Locate разнесены. [Экран](qa/map-beta/06-backtrack.png). Реальное движение, gap continuation и arrival не проверены. |
| 07 — My points/Places, attribution, preview/object bounds/empty/error | NOT RUN всего набора; PASS live Places search Gävle и preview без автоматического сохранения: [актуальный экран](qa/map-beta/07-place-search.png), [preview UI](qa/map-beta/search-preview-ui.json). Offline My points и все empty/error/save варианты не пройдены. |
| 08 — Outdoor/light basemap, contrasting geometry, компактная панель | NOT RUN финально. [00-map-first.png](qa/map-beta/00-map-first.png) — предварительный общий экран. |
| Логотип/Forecast/Map–Journal–Saved–Devices | PASS в проверенном scope: логотип/нижняя navigation на [01](qa/map-beta/01-catch-final.png), все подписи при [200%](qa/map-beta/font-200-fixed.png), header button и 7 swipe checks в [логе](qa/map-beta/weather-swipe.log). Временной scope сборок указан ниже. |
| Атрибуция видна при любой sheet/HUD/keyboard | NOT RUN; нужна проверка нижних inset/padding на устройстве. |
| 320–480 dp, шрифт до 200%, системные inset/gesture navigation/keyboard | NOT RUN всей строки; scoped 200%-font PASS на проверенном APK `3D0A746C…`/OnePlus CPH2609 после исправления clipping: Flic/GPS, navigation labels, favorite/More и Navigate/Edit/Details видны, body листается независимо от footer. [Первый экран](qa/map-beta/font-200-fixed.png), [прокрутка](qa/map-beta/font-200-scrolled.png), [bounds](qa/map-beta/font-200-fixed-ui.json). Исходный fontScale 1.1 восстановлен. Все ширины/другие overlays/insets/keyboard — NOT RUN. |
| Все интерактивные цели ≥48×48 dp, главные ≥56 dp | NOT RUN: размеры увеличены; нужна проверка фактических bounds и непересечения. |
| Контраст текста ≥4.5:1, значимой графики/крупного текста ≥3:1 | NOT RUN измерением; проверить пары на реальных подложках и overlays. |
| TalkBack names/state, selected description, visible list, focus/Back, декоративные элементы, отсутствие GPS-spam | NOT RUN TalkBack-сеансом. |
| Weather swipe — все 7 существующих проверок | PASS на финальном APK `5028A341…`: оба направления Map↔Forecast, карта не переключается от pan, header weather button. [7 PASS](qa/map-beta/weather-swipe.log), повтор 15 сентября 23:11:54 после последней сборки. |

## F8.11: численные бюджеты

| Сценарий | Требование PASS | Статус сейчас | Команда/доказательство |
|---|---|---|---|
| Standard: 1 000 points + 10 000 track coordinates, 60 s pan/zoom при 60 Hz | p95 ≤32 ms; ≤5% frames >50 ms; no ANR/crash | PASS | Финальный APK: 60.7779 s, 3 471 samples, p95 10.532985 ms, max 24.815649 ms, >50 ms 0%; PID не изменился, в отфильтрованном runtime log ошибок нет. [Отчёт](qa/map-beta/performance/standard-report.json), [runtime](qa/map-beta/performance/standard-runtime.txt). Отдельный системный ANR/compositor trace не выполнялся. |
| Standard: warmed local filter и карточка | Visible completion p95 ≤300 ms | NOT RUN | Отдельный timing сценарий; frame collector action latency не измеряет. |
| Stress: 10 000 points + 50 000 coordinates, filter/pan/zoom | No ANR/crash/OOM; local action ≤1 s; видимая подготовка; frame measurements записаны | NOT RUN | Window budget PASS на финальном APK: 61.2916 s, 3 547 samples, p95 9.858864 ms, max 21.175578 ms, >50 ms 0%; PID не изменился. [Отчёт](qa/map-beta/performance/stress-report.json), [runtime](qa/map-beta/performance/stress-runtime.txt). Local-action timing и видимая подготовка ещё не измерены. |
| 20 Map→другой раздел→Map, смена style и rotation | Нет накопления listeners/map instances; retained memory не растёт монотонно после стабилизации/GC | NOT RUN | [Lifecycle report](qa/map-beta/lifecycle/report.json): 20/20 за 164.295 s, Activity/ViewRootImpl/Views стабильно 1/1/214. Post-GC heap/retained profile, смена style и rotation — NOT RUN. |
| Сопоставимая запись baseline/new по 2–4 h | Одинаковые телефон/маршрут/яркость/сеть/Flic; при ухудшении >15% — повтор и устранение подтверждённой регрессии | NOT RUN | Требуются две длительные записи. Короткий UI-профиль не является оценкой батареи. |

Оба финальных прогона выполнены 15 сентября 23:12–23:14 на **OnePlus CPH2609, Android 15 / API 35, 60.000004 Hz**, `0.25-map-beta` / 36, APK `5028A341…`; zero invalid samples, zero overflow, zero dropped reports. Standard — 68 pan gestures / 17 native debug zooms; stress — 70 / 17. Это частота дисплея и длительности Android Window `TOTAL_DURATION`, не утверждение о постоянно показанных 60 FPS. Точки синтетические, публичный район Берлина; PointStore не наполнялся fixtures. [Raw standard CSV](qa/map-beta/performance/standard-frames.csv), [raw stress CSV](qa/map-beta/performance/stress-frames.csv).

Методика и ограничения Window FrameMetrics: [MAP_BETA_F8_MEASUREMENTS.md](MAP_BETA_F8_MEASUREMENTS.md). Непрерывность PID и отсутствие AndroidRuntime errors в собранном логе относятся к этим двум прогонам. Они не доказывают отсутствие всех ANR, GPU/compositor задержек, retained objects или расхода батареи; эти критерии не повышены до PASS.

Lifecycle samples на шагах 0/10/20: PSS **354 923 → 348 227 → 329 883 KiB**, Java heap allocated **6 601 → 7 477 → 7 521 KiB**, native heap allocated **96 078 → 121 344 → 112 631 KiB**. Уменьшение PSS и постоянное число Activity не доказывают отсутствие удерживаемых объектов. Явный GC не запрашивался; post-GC heap graph отсутствует. Старт и конец подтверждены на Map без overlay, own-app foreground guards true. [Полный отчёт](qa/map-beta/lifecycle/report.json).

Итоговый APK собран в 23:07 и установлен в 23:08; повторы 05/06 — 23:09, 07 — 23:11, weather — 23:11:54, оба frame-профиля — 23:12–23:14. Они относятся к финальному `5028A341…`. Lifecycle 20 переходов выполнен до заключительных UI-правок, road smoke — на `F1490AD6…`, 200%-font — на `3D0A746C…`; их scope не переносится автоматически на новый hash. [Прежние frame-профили](qa/map-beta/performance-before-final-framing/summary.json) сохранены отдельно.

После финального прогона synthetic dataset выключен, keep-awake false, GPS включён, fontScale восстановлен до 1.1, peak refresh до 120.00001, minimum refresh unset. [Протокол восстановления](qa/map-beta/restored-device-settings.json), [итоговый UI](qa/map-beta/final-idle-ui.json). На последнем UI нет активной цели ведения; `Loading map…` относится к загрузке подложки после возврата и не выдаётся за проверку полной готовности тайлов.

## Устройства и обязательный полевой сценарий

| Среда / сценарий | Статус | Причина / нужное действие |
|---|---|---|
| OnePlus CPH2609, Android 15 / API 35, 60 Hz | NOT RUN | Миграция и оба Window frame budgets PASS. Фактические manufacturer/model/OS/refresh записаны в performance JSON. Историческое название OPPO не считается вторым устройством. |
| Второй Android производитель/версия | NOT RUN | Второе устройство в этой сессии не проверено. |
| Третий Android производитель/версия | NOT RUN | Третье устройство в этой сессии не проверено. |
| Эмулятор minSdk API 26 | NOT RUN | Прогон API 26 ещё не выполнен. |
| Целевая API 35 на реальном устройстве | PASS | Android 15 / SDK 35 подтверждены обоими performance reports; полный набор аппаратных сценариев остаётся ниже. Это не заявление о тестировании иных/более новых ОС. |
| Другой район до поездки → download → restart → offline bounds/zoom/style | NOT RUN | Satellite 2 km/30.3 MiB READY после restart подтверждён. Airplane mode/другой район/все границы покрытия — NOT RUN. |
| ≥200 смешанных single/double/hold на тестовый комплект | NOT RUN | Нет аппаратного протокола числа/времени ожидаемых и полученных событий. |
| Экран off, GPS off/poor, сеть lost, BLE reconnect | NOT RUN | Отдельный 5 s GPS-off UI smoke выполнен с восстановлением настройки. Это не тест Flic capture/track при экране off или BLE reconnect. |
| Catch edit/favorite/current-trip filter и Journal agreement | NOT RUN | Требуется сверить реальные действия и counts, а не только screenshot. |
| Offline delete → process restart → reconnect → no resurrection | NOT RUN | Pure ledger tests PASS; Android/network сценарий выполнить на явно тестовых записях. |
| Backtrack gap/loop/parallel/off-route, запись продолжается | NOT RUN | Геометрические fixtures PASS; требуется реальное движение и GPX comparison. |
| Search, pan после Follow, TalkBack, 200%, weather swipes | NOT RUN | Places preview, pan→7 s GPS→FREE, scoped 200%-font карточки/статусов/navigation и 7 weather swipes — PASS в указанном выше scope. Полный search workflow, TalkBack и остальные 200%-overlays не проверены. |
| Длительная запись + экспорт GPX: counts/time/segments | NOT RUN | Требуется duration/battery/Flic сессия. |

## Сборка и воспроизводимость

Подтверждено **444 checks PASS в 16 скриптах / 17 группах**: исходный полный прогон завершён `2026-09-14T22:31:02Z` (435 checks, exit codes 0), затем MapPolicies повторён после heading freshness fix — 57 вместо 48, ещё 9 проверок. [Исходная таблица](qa/map-beta/regression-summary.md), [последний MapPolicies](qa/map-beta/regression-MapPolicies.log), [pure exits](qa/map-beta/regression-pure-exits.json), [SDK exits](qa/map-beta/regression-sdk-exits.json). Сборка APK проверена отдельно; успешный javac сам по себе её не доказывает.

| Группа | Checks | Статус / доказательство |
|---|---:|---|
| FieldNavigation / FlicPressPolicy / GPX | 18 / 6 / 12 | PASS, соответствующие `qa/map-beta/regression-*.log` |
| PointMetadata / SavedPointAppearance / TripStatistics | 10 / 9 / 14 | PASS |
| TrackPointPolicy / TrackRecording | 10 / 21 | PASS |
| RoadRoute / RoutingParser | 40 / 23 | PASS |
| PointLedger | 54 | PASS, реальное filesystem reopen |
| MapPolicies | 57 | PASS, повтор после heading freshness fix |
| Backtrack / PlaceSearch | 45 / 37 | PASS |
| OfflineAreaPolicy / OfflineController | 33 / 38 | PASS JVM/SDK-double; отдельно выполнен native download/restart smoke |
| MapDebugTools | 17 | PASS; отдельно оба device Window frame профиля PASS, compositor FPS не измерялся |

Последний `assembleDebug lintDebug` после framing/coverage fix завершился **PASS за 40 секунд**. [Build log](qa/map-beta/final-build.log) и lint report от 15 сентября 23:07 содержат **0 errors / 100 warnings**. [Актуальный разбор lint](qa/map-beta/lint-review.md) подтверждает: относительно прежних 99 добавлен один LogNotTimber на debug-вызов диагностики кадрирования. Документ 0.24 указывает 73 warnings, однако его XML не сохранён и точный diff от этого baseline не заявляется. Новый APK установлен на OnePlus CPH2609 в 23:08; device-повторы 05/06 в 23:09 — PASS в указанном scope.

Команда финальной сборки в проверенной локальной среде:

```powershell
$env:JAVA_HOME='C:\Users\Gebruiker\.jdks\jbr-21.0.11'
$env:GRADLE_USER_HOME='C:\Fiskentra\.gradle-codex'
$env:TEMP='C:\Fiskentra\.jtmp'
$env:TMP='C:\Fiskentra\.jtmp'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Fiskentra\.jtmp -Djava.net.preferIPv4Stack=true'
.\gradlew.bat assembleDebug lintDebug --no-daemon
```

| Финальный артефакт | Состояние |
|---|---|
| Exit codes/log финальных assembleDebug и lintDebug | PASS, [build 40 s](qa/map-beta/final-build.log); lint 0 errors / 100 warnings. После новых изменений APK потребуется повтор. |
| Финальный debug APK | [Fiskentra-0.25-map-beta-debug.apk](artifacts/Fiskentra-0.25-map-beta-debug.apk), 57 756 347 bytes; установлен на OnePlus CPH2609, Android 15 / API 35. Повторы Show area/coverage и backtrack framing пройдены. |
| VersionName/versionCode | 0.25-map-beta / 36 в исходниках |
| SHA-256 текущего APK | `5028A34117A2A03FE5B21EEC7005F614D7B7600BA734CE54FBA60AB78319C79D`; [checksum file](artifacts/Fiskentra-0.25-map-beta-debug.apk.sha256). Размер/checksum подтверждены после framing fix. |
| Commit новой реализации | Не создан; HEAD базы c83fbc64…, рабочее дерево изменено |
| Восемь финальных device screenshots + сравнения | NOT RUN полной приёмки всех вариантов; восемь состояний сняты, 01/02/05/06/07 пересняты, road smoke и scoped 200%-font повтор приложены. На финальном APK повтор 05/06 в 23:09 подтвердил исправленное framing/coverage. |

## Исправленные дефекты и оставшиеся зависимости

В процессе проверки устранены: гонка сохранения/удаления при позднем upsert, потеря архивного route, ложный READY по одному resource count, удаление старого района до durable READY нового, бесконечный metadata retry, обход durable deletion intent при Retry, поздний photo callback, heading freshness и сохранение временной QA-камеры. Clipping favorite/navigation при 200%-font исправлен и повторно проверен на проверенном APK `3D0A746C…`; body карточки прокручивается отдельно от доступных footer actions.

Первый performance запуск прервался из-за реального runtime crash: `Style.getLayer()` через `MapFeatureRenderer.visibility → setHeading → onDetachedFromWindow → MainActivity.render`. [Исходный stack](qa/map-beta/crash-buffer.txt). Старый Style теперь отбрасывается при загрузке/смене/закрытии; обращения требуют актуальный полностью загруженный Style. Ошибка guard отражала потерю foreground после crash; конфликт переменных PowerShell не подтвердился. После исправления и новой сборки оба 60-Hz профиля завершены, PID непрерывен, собранные AndroidRuntime logs без ошибок. Этот повтор подтверждает устранение данного воспроизведения; другие lifecycle/rotation сценарии проверяются отдельно.

Непроверенные аппаратные сценарии не названы известными багами и не скрыты под PASS. Подтверждённый новый FAIL при device/performance прогоне должен быть добавлен в соответствующую строку с доказательством и исправлением.

Закрытые дефекты финальной UI-сверки: Show area после раскрытия Manage давал чрезмерный обзор вместо сохранённого 2-km района; после исправления padding надпись coverage могла оставаться устаревшей из-за раннего return. На APK `5028A341…` в 23:09 повтор — PASS: прямоугольник виден и coverage Available offline, [UI](qa/map-beta/offline-ready-ui.json), [05](qa/map-beta/05-offline-area.png). Backtrack также повторён с видимой геометрией/Start и раздельными Offline/Locate, [06](qa/map-beta/06-backtrack.png). Это закрывает данные воспроизведения, а не весь полевой набор F5/F6.

[Сохранённый ADB-вывод](qa/map-beta/bounds-backtrack-framing.log) подтверждает offline rectangle 522 px и backtrack geometry 177 px по высоте, `centerInside=true`/`cornersVisible=true`. В заголовке файла указано происхождение: дословно сохранён ранее прочитанный output до перезаписи rolling buffer поисковым прогоном; это не новый device-запуск.

Отдельные launch-зависимости: договорённости/лимиты routing demo, права и quota MapTiler на распространение/offline/geocoding, production RLS/account ownership, полный photo/track backup/restore, актуальные требования магазина/target API. Эти системы не менялись как побочный эффект карты. Разрешение publish/релиз приложения этим отчётом не подменяется.
