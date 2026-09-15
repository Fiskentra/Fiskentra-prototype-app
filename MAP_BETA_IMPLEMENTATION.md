# Fiskentra 0.25 — реализация карты перед бета-тестом

Актуальная публикация: [0.25 / versionCode 37](RELEASE_NOTES_0.25.md), только английский интерфейс, тестовый телефон со шрифтом 100%. Описанные ниже beta-проверки сохраняют свою исходную конфигурацию версии 36.

Последний проход: [повторная проверка 15–16 сентября](MAP_RECHECK.md), включая исправления предпросмотра поиска, открытия событий без координат, привязки событий к поездке и компактной подсказки над панелью. Новый отдельный APK: `artifacts/Fiskentra-0.25-map-beta-recheck-debug.apk`.

Состояние документа: **15 сентября 2026, интеграция и приёмка продолжаются**. Основание — пользовательское `Fiskentra_Map_Codex_TZ.md` редакции 1.0 от 14 сентября 2026 и восемь приложенных мокапов. Фактические проверки и пробелы перечислены отдельно в [MAP_BETA_ACCEPTANCE.md](MAP_BETA_ACCEPTANCE.md). Наличие кода не означает PASS полевого сценария.

## База и сборочная конфигурация

- Рабочая копия: `map-v021`, ветка `codex/field-map-v021`.
- Фактический HEAD базы: `c83fbc64b173767da887f3dfebc94d396040dcc0`. Улучшения 0.25 пока находятся в рабочем дереве; этот hash **не является commit новой реализации**. Указанный в ТЗ `e5b32e27…` — исходная ссылка документа; разработка ведётся поверх фактического checkout с актуальными 0.24/Flic-исправлениями.
- На момент описанных beta-проверок: `versionName = 0.25-map-beta`, `versionCode = 36`; прежний установленный номер 35 не переиспользован. Финальный коммит 0.25 увеличивает versionCode до 37.
- Native Java/Android Views, Java source/target 17, minSdk 26, compile/target 35. Gradle wrapper 9.5.0, AGP 9.3.1, среда проверки JBR 21.0.11. MapLibre Android 13.4.1, Flic 2 SDK 2.0.1 сохранены.
- Новых runtime-зависимостей нет. JSON JAR 20240303 используется только существующими JVM harness, в APK не добавлен. MapLibre остаётся в TextureView-режиме прежнего профиля совместимости OPPO; фактически проверенный телефон сообщает OnePlus CPH2609, Android 15 / API 35.

Сохранены исходный `fiskentra_wordmark.png`, Forecast и его свайпы, нижние Map/Journal/Saved/Devices, CompassDial, Walking/Driving/Steps/ETA, существующая foreground-озвучка и Flic hold = старт/пауза/продолжение записи. Карта интерактивная; названия/числа из PNG не подставляются в пользовательские данные.

## G1–G5: данные и жизненный цикл

Подробности и обоснования атомарности — [DATA_BETA.md](DATA_BETA.md).

| Требование | Реализация и исходники | Что не следует считать доказанным |
|---|---|---|
| G1: событие до подтверждения сохранено устойчиво; исходное время и ID; отсутствие GPS; дедупликация; поздний fix; ошибка диска | `data/PointLedger.java`, `data/PointStore.java`, `model/CapturePolicy.java`, `service/FiskentraFlicService.java`, `flic/FiskentraFlic2Manager.java`. Single/double сохраняют запись до feedback. Без fix — запись без lat/lon с исходным UTC-временем; поздняя позиция требует явного присвоения. Receipt hold и переход записи сохраняются вместе, hold не создаёт точку. | JVM file reopen проверяет восстановление данных; 200 физических Flic-событий, замена батареи кнопки, BLE reconnect и Android kill при выключенном экране ещё требуют аппаратной сессии. |
| G2: согласованное локальное удаление/очередь; порядок upsert/delete; retry/auth/RLS; отсутствие восстановления поздними callbacks | В одном документе PointLedger находятся точки, revision, операция и tombstone. `backend/PointSyncQueue.java`, `backend/SupabasePointSync.java`, `backend/SyncResponsePolicy.java` отправляют операции по ID последовательно. Никогда не отправленная точка удаляется локально; in-flight upsert завершается до delete. 409 не считается успехом, 0 строк delete не доказывают право доступа. | Реальный отказ production RLS/потерянный HTTP-ответ не симулировались действиями над облачными данными пользователя. |
| G3: статус именно текущей revision и границы облачного сохранения | Ledger/sync status и `ui/PointSheet.java`: saved on device, pending, failure либо `Coordinates synced · details on this device`. Пустая очередь не является backup фото/треков/оформления. | Полный cloud backup/restore новых полей не реализован и не обещается. |
| G4: versioned migration, backup, идемпотентность, сохранение всех старых полей | `files/map-data-v1.json`: temporary file + fsync + atomic replace, Android fsync каталога. Старая SharedPreferences остаётся, точные исходные JSON — в `legacyBackup`. SavedPoint copy/with сохраняют eventId/tripId/favorite/revision/location metadata. Архивные маршруты/сегменты не преобразуются. | Миграция на OnePlus CPH2609 подтверждена; другие устройства и forced-kill Android во время перехода ещё не проверены. |
| G5: snapshot/revision, работа без View, worker/cancellation | Кешированные PointLedger/TrackStore snapshots; `FieldMapPanel` готовит фильтр/геометрию в worker и отбрасывает старые generations. Renderer/фото/search/backtrack имеют отдельные задачи и очистку listeners. Flic и запись остаются в сервисе. | 20 Map→Saved→Map пройдены, Objects/Activities/ViewRootImpl не выросли. Это не post-GC retained-object анализ; rotation и длительное сочетание сервисных/UI-сценариев ещё не проверены. |

TripId назначается в момент события. Legacy получает поездку только при одном совпадающем полуоткрытом интервале `[start,end)`; неоднозначные записи остаются Unassigned. Исправлена потеря сохранённого route при `FishingDayStore.stop()`; TrackStore сохраняет сегменты и кеширует чтение.

Реальная миграция CPH2609: **125/125 точек, 9/9 архивов, 132 архивные координаты/13 разрывов, 1 фото и ссылка на него сохранены точно**. 78 старых точек остались без однозначной привязки поездки. Это ожидаемое сохранение неопределённости, а не потеря точек. [Доказательство](qa/map-beta/data-migration-report.json).

Повтор после device QA — [final-data-integrity.json](qa/map-beta/final-data-integrity.json), **PASS**: те же 125 точек, 0 изменённых исходных полей, 9 точных архивов/132 координаты/13 разрывов, 1 исходное фото с точным SHA и прежней ссылкой. Debug fixtures не увеличили число точек; текущий трек и его preferences точны. Из map preferences изменён только `route_profile` после проверки Walking/Driving.

## F1–F4: карта, карточка, фильтр, камера и GPS

| Критерии ТЗ | Что реализовано | Основные файлы / проверка |
|---|---|---|
| F1.1–F1.2: карточка над картой, корректное позиционирование и содержимое | Единый `MapSheet`, отдельная `PointSheet`, выбранный маркер остаётся над sheet через camera padding. Название/тип/время, реальные catch details, расстояние по прямой при свежем GPS, фото/ошибка и статус записи. Без координат доступно указание места. | `ui/MapSheet.java`, `ui/PointSheet.java`, `ui/FieldMapPanel.java`, `ui/MapTilerMapView.java`; T05/device. |
| F1.3–F1.4: Navigate/Edit/Details, favorite, More, удаление | Действия связаны с существующими редакторами и durable PointStore, статусами sync и явным delete. Selected и target независимы. Фото декодируется вне UI; недоступное фото не отменяет сведения. | `PointSheet`, `LocalCatchPhoto`, `MainActivity`, `PointLedger`; T02/T05. |
| F1.5–F1.7: collapsed/expanded, Back/focus, независимость ведения, удаление цели | Sheet ограничен доступной высотой и прокручивается; одна overlay-сессия POINT/FILTERS/SEARCH/OFFLINE. Закрытие восстанавливает обзор/focus, не останавливает запись/ведение. Удалённая цель прекращает ведение с объяснением. | `MapSheet`, `FieldMapPanel`; полная последовательность клавиатура/Back/TalkBack ещё требует device QA. |
| F2.1–F2.4: вход из слоёв, scope, типы, OR/AND | `MapFilterPanel`: All/Current/saved trip/date range, Catch/Waypoint/Camp/Hazard/Tackle/Other, quick catches, favorites и Reset. Один immutable predicate; пустой набор типов остаётся пустым. | `model/MapFilterPolicy.java`, `ui/MapFilterPanel.java`; T04, MapPolicies. |
| F2.5–F2.7: tripId, UTC/DST, favorite | Идентификатор поездки хранится, не выводится из одного календарного дня. Диапазон дат преобразуется из показанной local timezone в `[UTC start,next-day start)`. Favorite сохраняется локально; есть ручная смена tripId в Details. | PointLedger/SavedPoint/MapFilterPolicy; T03/T04. |
| F2.8–F2.9: согласованные counts, unlocated, target и empty | Один snapshot/predicate обслуживает список, map source, clusters и counts. Unlocated учитываются отдельно. Скрытая фильтром выбранная запись закрывает sheet; цель ведения остаётся отдельным слоем. Current trip без активной поездки не заменяется историей. | FieldMapPanel/MapFeatureRenderer/MapFilterPanel; T04/T05/T11. |
| F3.1–F3.2: Locate и ручной gesture | FREE/FOLLOW_POSITION/FOLLOW_HEADING; реальные жесты выключают follow. GPS после gesture обновляет маркер, а не принудительно возвращает обзор. Programmatic camera callbacks отделены. | `model/MapCameraPolicy.java`, MapTilerMapView; T06. |
| F3.3–F3.5: сглаживание, north wrap, course/sensor, stale/missing heading | Позиция анимируется отдельно, orientation использует shortest arc/deadband/hysteresis. GPS course выбирается по скорости; отсутствие пригодной ориентации не выдаётся за новый курс. Старый GPS не двигает камеру как свежий. | MapCameraPolicy, LocationQualityPolicy, FieldMapPanel sensor lifecycle; T06/T07 и свежие правки интеграции. |
| F3.6–F3.8: look-ahead padding, north reset, restore | В ведении GPS ниже центра свободной части карты; HUD/sheet/insets учтены. North возвращает FOLLOW_HEADING в FOLLOW_POSITION и сохраняет FREE. Camera position/mode восстанавливаются из prefs с валидацией координат. | MapTilerMapView; полная rotation/device-проверка ещё не проведена. |
| F4.1–F4.4: реальные метры, качество, age, monotonic freshness | Географический контур accuracy, проверка валидности, stale-marker и возраст; пороги good/fair/poor вынесены в policy. Свежесть 30 секунд использует monotonic clock; persisted old fix не становится свежим после reboot. | `model/LocationQualityPolicy.java`, MapTilerMapView/FieldMapPanel; 10/50/150 м × широты, 29/31 с в T07. |
| F4.5–F4.8: разные gates операций, recording, permission и compass | Track 75 м, guidance 50 м, arrival 25 м; stale не продвигает навигацию. Approximate, permission denied и disabled location различаются. Запись события работает без GPS. Магнитная ориентация отделена от точности положения. | LocationQualityPolicy/CapturePolicy/TrackPointPolicy/service; аппаратные permissions/GPS/compass ещё проверяются. |

Дорожный UI smoke на предыдущем APK `F1490AD6…` прошёл: Walking построил 14.3 km / 2 h 50 min, Driving — 14.6 km / 22 min; показаны ETA, следующий правый поворот через 258 m, название улицы и шаги с выездами с круговых перекрёстков. FOLLOW_HEADING и независимый compass видны на [03-camera-follow.png](qa/map-beta/03-camera-follow.png); [Driving UI](qa/map-beta/road-driving-ui.json), [Steps UI](qa/map-beta/road-steps-ui.json). Физическое прохождение маршрута, продвижение turns, arrival и rerouting этим smoke не проверены.

Выполнены отдельные UI-проверки: реальная catch-карточка с фото/полями/действиями — [01](qa/map-beta/01-catch-final.png); live filter до 46 и Reset до 125 — [02](qa/map-beta/02-map-filters.png); ручной pan с последующими 7 s GPS сохранил FREE без возврата камеры — [UI](qa/map-beta/camera-free-after-gps.json). Эти PASS относятся к указанным сценариям; полные составные F1/F2/F3 остаются NOT RUN согласно итоговой матрице.

## F5: полный lifecycle офлайн-района

Подробный контракт, меры против потери готовой карты и тесты — [MAP_BETA_F5.md](MAP_BETA_F5.md).

| Критерии | Реализация |
|---|---|
| F5.1–F5.3 | `OfflineAreaPanel` встроена в map sheet: центр выбирается на карте без GPS, GPS дополнительный, радиусы 2/5/10 км, имя/стиль. Точные SDK bounds/zoom 8–16 сохраняются в metadata v2. Полюса/антимеридиан отклоняются заранее с объяснением, без молчаливого clipping. |
| F5.4 | DRAFT/DOWNLOADING/READY/PAUSED/ERROR/DELETING. Неизвестный required count — indeterminate + реальные bytes. READY/100% только по SDK complete. Restart перечитывает SDK status. Для READY введена компактная сводка: имя/style/radius/bytes, один статус, Change area/Manage; route и online-rerouting пояснения видны до раскрываемых controls. |
| F5.5–F5.6 | Один ready + временный candidate. Старый удаляется только после SDK complete и успешного durable READY нового. Есть reserve/приблизительный объём/лимит загрузки, обработка connection/quota/access/disk errors. Ручное удаление показывает имя/объём и потерю гарантии офлайн-доступности. |
| F5.7–F5.9 | Покрытие проверяет style/bounds/zoom. Удаляется выбранный управляемый SDK ID после записи намерения, recovery продолжает DELETING. Basemap, saved route geometry и online rerouting показаны отдельно; точки/поездки/GPX не удаляются. |

SDK doubles проверяют порядок операций и отказы. На OnePlus CPH2609 реально скачан Satellite-район 2 km, SDK READY/100%, 30.3 MiB; готовность сохранилась после обновлений/перезапуска. На финальном APK `5028A341…` повтор Show area от 15 сентября 23:09 — **PASS**: весь прямоугольник виден, compact READY-панель показывает `Coverage at map center · Available offline`. Исправлены избыточное camera padding и преждевременный выход из обновления coverage. [Снимок](qa/map-beta/05-offline-area.png), [UI](qa/map-beta/offline-ready-ui.json). Airplane mode, весь lifecycle ошибок/замены и другие районы остаются NOT RUN на устройстве; полная F5 не объявляется PASS.

## F6–F7: backtrack и геопоиск

Подробные подсистемные решения — [MAP_BACKTRACK_SEARCH.md](MAP_BACKTRACK_SEARCH.md).

| Критерии | Реализация |
|---|---|
| F6.1–F6.2 | `BacktrackRoute` создаёт immutable reverse snapshot текущего/сохранённого трека; требует пригодный сегмент из двух разных координат. Продолжающаяся запись не изменяет выбранный путь возврата. |
| F6.3–F6.5 | Начальный выбор участка при неоднозначности, bounded-window projection, segment/edge/fraction/along progress. Разрывы не соединяются и не входят в известную длину; продолжение требует подтверждённого положения у следующего сегмента. |
| F6.6–F6.9 | Панель remaining known distance, off-route/GPS/gap/stop/voice, флаг Start. Курс считается локально; дорожные turns/ETA из GPX не выдумываются. Отклонение/возврат/arrival требуют устойчивых пригодных fixes. |
| F6.10–F6.12 | Ведение и запись независимы; смена ROAD/BACKTRACK явная. `BacktrackStore` отдельно сохраняет большой snapshot и небольшой progress, после restore ждёт fresh fix. При отсутствии тайлов локальная нейтральная подложка оставляет geometry/GPS; voice ограничен lifecycle экрана. |
| F7.1–F7.3 | My points локально, с current/all scope и пагинацией; Places через `PlaceSearchProvider`/`MapTilerGeocoding`. Минимум 2 символа, debounce 300 мс, limit 10, timeout 10 с, cancellation/latest-wins. |
| F7.4–F7.6 | Валидируются GeoJSON lon/lat, bounds, type/context/attribution. Proximity — центр карты, а не обязательный скрытый GPS. Выбор создаёт preview; save/navigate отдельные действия; для озера/района выбирается конкретная цель. |
| F7.7–F7.10 | Раздельные offline/timeout/403/429/malformed/empty, Retry-After, локальная очищаемая история. Provider responses только в памяти; ключ через существующий local build config. Реальный запрос дал HTTP 200/9 пригодных результатов/attribution; телефонный поиск Gävle и preview без автосохранения подтверждены на [07](qa/map-beta/07-place-search.png). Полный набор offline/error/save UI-сценариев — NOT RUN. |

На финальном APK `5028A341…` backtrack UI повторён 15 сентября 23:09: reverse geometry/Start видны в доступном участке карты, HUD и summary компактны, Offline/Locate не перекрывают друг друга. [06-backtrack.png](qa/map-beta/06-backtrack.png). [Сохранённый вывод framing](qa/map-beta/bounds-backtrack-framing.log) подтверждает высоту геометрии 177 px, `centerInside=true`, `cornersVisible=true`; файл содержит дословно сохранённый ранее прочитанный ADB-вывод с provenance. Это PASS кадрирования/раскладки; физическое движение, gap continuation, offline и arrival остаются NOT RUN.

## F8: Outdoor, доступность, renderer и измерения

| Критерии | Реализация / граница доказательства |
|---|---|
| F8.1–F8.2 | Стили Outdoor/Satellite/Topo/Ocean и сохранённый выбор; контрастные casing линии/labels. Компактные panels, общий overlay, увеличенный текст доступен через прокрутку. Фактическая доля карты и длинные тексты требуют финального screenshot QA. |
| F8.3–F8.5 | Минимальные touch-targets расширены, основные действия 56 dp, content descriptions/state и альтернативный список точек. После исправления clipping scoped 200%-font повтор на проверенном APK `3D0A746C…` прошёл: Flic/GPS, подписи нижней navigation, favorite/More и Navigate/Edit/Details видны, body карточки прокручивается независимо от footer. [Первый экран](qa/map-beta/font-200-fixed.png), [прокрутка](qa/map-beta/font-200-scrolled.png), [UI bounds](qa/map-beta/font-200-fixed-ui.json). Исходный fontScale 1.1 восстановлен. Контраст 4.5:1/3:1, TalkBack и все ширины/overlays отдельно не проверены. |
| F8.6–F8.7 | `MapFeatureRenderer`: native GeoJsonSource + Circle/Symbol/Line layers для массовых points/clusters/track/road/backtrack. Подготовка snapshot/sprites вне UI, icon cache, generations. Источники пересоздаются при новом style. Canvas оставлен только для небольших динамических элементов, включая GPS accuracy, selected/target/preview и coverage. |
| F8.8–F8.9 | Stable point IDs, native cluster expansion; совпадающие координаты/max zoom открывают список. Selected отдельно от cluster. GeoJSON tiling/tolerance упрощает только отображение, исходные сегменты/statistics/export/backtrack неизменны. Оба Window frame профиля PASS; cluster/reload/export consistency отдельно требуют QA. |
| F8.10 | UI sensors/tasks/listeners закрываются по lifecycle; сервис записи независим. TextureView сохраняется, частота GPS ради UI не увеличена. [20 Map→Saved→Map](qa/map-beta/lifecycle/report.json) пройдены за 164.295 s: один PID, 1 Activity / 1 ViewRootImpl / 214 Views на шагах 0/10/20; PSS 354 923 → 348 227 → 329 883 KiB. Post-GC retained memory/длительная батарея не измерены. |
| F8.11 | Debug fixtures и `MapFrameProfiler`, [скрипт](qa/Measure-MapPerformance.ps1), JSON+raw CSV, модель/build/refresh/drops/heap. Synthetic points не попадают в PointStore; камера QA временно не сохраняется, off восстанавливает прежний обзор/filter/mode, generation отклоняет позднюю подготовку. На OnePlus CPH2609/API 35 при 60 Hz оба Window frame budgets PASS, подробности ниже. |

Подробные ограничения FrameMetrics, 60 Hz budget, cleanup и команды: [MAP_BETA_F8_MEASUREMENTS.md](MAP_BETA_F8_MEASUREMENTS.md).

Финальные 60-Hz прогоны 15 сентября 23:12–23:14 на APK `5028A341…`: [standard 1 000/10 000](qa/map-beta/performance/standard-report.json) — 60.7779 s, 3 471 samples, p95 **10.532985 ms**, max 24.815649 ms; [stress 10 000/50 000](qa/map-beta/performance/stress-report.json) — 61.2916 s, 3 547 samples, p95 **9.858864 ms**, max 21.175578 ms. В обоих >50 ms **0%**, invalid/overflow/dropped reports 0, PID непрерывен. Измерен Android Window `TOTAL_DURATION`; action latency, compositor FPS, post-GC retained memory и батарея не входят в этот PASS.

Финальная сборка завершена 15 сентября 23:07, установлена в 23:08; повторы 05/06 — в 23:09, 07 — в 23:11, [7 weather checks PASS](qa/map-beta/weather-swipe.log) — в 23:11:54, оба frame-профиля — в 23:12–23:14. Все эти повторы относятся к APK `5028A341…`. Ранее выполненные [20 переходов](qa/map-beta/lifecycle/report.json) относятся к срезу до заключительных UI-правок; road smoke — к `F1490AD6…`, 200%-font — к `3D0A746C…`. [Предыдущие frame-профили](qa/map-beta/performance-before-final-framing/summary.json) сохранены отдельно.

После QA отключены synthetic dataset и keep-awake, восстановлены fontScale 1.1, GPS on и исходные refresh settings (peak 120.00001, minimum unset). [Настройки](qa/map-beta/restored-device-settings.json), [итоговый UI](qa/map-beta/final-idle-ui.json). В этом UI нет активной цели ведения; снимок сделан во время загрузки подложки и не используется как доказательство завершённой загрузки тайлов.

При первом запуске обнаружено падение из-за обращения к старому `Style.getLayer()` при отсоединении карты и обновлении heading. `MapFeatureRenderer.readyStyle()` теперь проверяет принадлежность текущей карте и полностью загруженный Style, переход loading сбрасывает источники. [Stack до исправления](qa/map-beta/crash-buffer.txt); оба последующих performance прогона завершены без этой ошибки. Скрипт требует свежую MapLibre UI, ждёт только временно пустой focus до 3 s и прекращает ввод при другом активном приложении; abort сохраняется отдельно от успешных измерений.

## Параметры и внешние границы

Локальный пример без секретов:

```properties
MAPTILER_API_KEY=<client-key-with-required-map-and-geocoding-access>
GEOCODING_URL=https://api.maptiler.com/geocoding/
ROUTING_URL=https://<approved-valhalla-host>/route
SUPABASE_URL=https://<existing-project>.supabase.co
SUPABASE_PUBLISHABLE_KEY=<existing-publishable-key>
```

`GEOCODING_URL` — новый optional build parameter. Остальные параметры уже существовали. Серверный secret в APK не добавляется, provider quota/права на распространение не выводятся из наличия ключа. Defaults/текущий routing demo описаны в [MAP_V023.md](MAP_V023.md); новый proxy, платный тариф, production schema/RLS и отправка провайдерам не выполнялись. Публикация приложения не входит в локальный результат.

## Проверяемый результат на момент документа

- **444 JVM checks / 16 runners — PASS**: [исходный полный прогон](qa/map-beta/regression-summary.md) содержит 435, [targeted MapPolicies rerun](qa/map-beta/regression-MapPolicies.log) после heading freshness fix — 57 вместо 48 (+9). Позднейшие fixes требуют повторить затронутые проверки.
- Реальная миграция старых данных CPH2609: PASS с [JSON сравнением](qa/map-beta/data-migration-report.json).
- Сняты восемь состояний карты, включая обновлённые 01/02/05/06/07; дорожный UI smoke и scoped 200%-font повтор подтверждены выше. Финальные повторные проверки Show area/coverage и backtrack framing от 23:09 — PASS; полный набор вариантов состояния — NOT RUN.
- 20 переходов и 7 weather checks — PASS в указанном выше scope. Полный retained-object анализ, action latency и полевые тесты остаются NOT RUN.
- Последние `assembleDebug lintDebug` после framing fix — [PASS за 40 s](qa/map-beta/final-build.log); lint **0 errors / 100 warnings**. [Разбор актуального lint](qa/map-beta/lint-review.md): относительно предыдущих 99 добавлен один LogNotTimber на debug-диагностику кадрирования; полный исходный XML-baseline 0.24 не сохранён.
- [Финальный debug APK](artifacts/Fiskentra-0.25-map-beta-debug.apk), установленный на OnePlus CPH2609: **57 756 347 bytes**, SHA-256 `5028A34117A2A03FE5B21EEC7005F614D7B7600BA734CE54FBA60AB78319C79D`, [checksum file](artifacts/Fiskentra-0.25-map-beta-debug.apk.sha256). Повторы Show area/coverage, backtrack framing, 7 weather checks и оба Window frame-профиля пройдены; полная продуктовая приёмка остаётся NOT RUN.
- Финальный commit новой реализации и полная приёмка карты ещё не завершены. Все оставшиеся критерии — в [матрице приёмки](MAP_BETA_ACCEPTANCE.md).
