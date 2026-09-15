# F5 — офлайн-район

Реализация: `offline/OfflineMapController.java`, `offline/OfflineAreaPanel.java`, `model/OfflineAreaPolicy.java`, `res/values/offline_area_strings.xml`.

Интеграционный результат от 15 сентября 2026: **PASS реальной загрузки/перезапуска** на OnePlus CPH2609, Android 15 / API 35 — Satellite, 2 km, SDK READY/100%, 30.3 MiB; после обновлений/перезапуска готовность сохранена. Компактная READY-панель показывает Change area/Manage и сведения о маршруте до раскрытия controls. На финальном APK `5028A341…` повтор 23:09 — **PASS Show area/coverage**: весь прямоугольник виден, `Coverage at map center · Available offline`. Исправлены двойной camera padding и ранний return, сохранявший устаревшую надпись. Полная F5-приёмка, включая airplane mode и весь lifecycle отказов, остаётся NOT RUN. Доказательства: [MAP_BETA_ACCEPTANCE.md](MAP_BETA_ACCEPTANCE.md), [экран 05](qa/map-beta/05-offline-area.png), [UI](qa/map-beta/offline-ready-ui.json).

Контроллер остаётся единственным владельцем MapLibre OfflineManager в `FiskentraApplication`. Старые `download(Location, style, radius)`, `pause()`, `resume()`, `deleteAll()` и скалярные поля Snapshot сохранены для совместимости. Все вызовы SDK и изменение состояния — на UI thread, сам SDK выполняет операции базы и загрузку асинхронно. Панель подписывается только на время нахождения в окне и удаляет ожидающий UI callback при закрытии.

## Контракт интеграции с картой

- `new OfflineAreaPanel(context, applicationController, host)` — содержимое существующей нижней панели. Host реализует выбор центра на существующей карте, дополнительное действие GPS, показ скачанного района, preview draft и закрытие.
- `setMapContext(latitude, longitude, zoom, styleId, cachedRoute)` задаёт текущий центр карты и контекст покрытия. GPS для выбора не нужен. Вызов `setDraftCenter(lat, lon)` подтверждает центр после перемещения/касания; `setImperial(boolean)` включает единицы пользователя.
- `host.previewDraft(Draft)` получает точные `north/east/south/west`, совпадающие с будущим SDK definition. `null` убирает черновик. `draft.bounds()` возвращает защитную копию.
- `controller.snapshot().readyArea` — последний подтверждённый SDK полный район. `areas` также включает временного кандидата, ошибки и удаляемый район. `Area.bounds()` и поля zoom взяты из фактического SDK definition, не из предположения о radius.
- `Area.coverage(styleId, lat, lon, zoom)` и `OfflineAreaPanel.coverageResource(...)` дают отдельные результаты: доступно, неполно, другой стиль, вне bounds, вне zoom. Панель явно подписывает проверяемую позицию как центр карты. Отрисовка контура и предупреждения карты — обязанности host.
- Сохранённая геометрия маршрута отображается отдельно от подложки. Строка про построение/перестроение через интернет постоянна; backtrack рассчитывается локально.

## Надёжность

Радиусы 2/5/10 км используют сферическую географию без искусственного ограничения cosine. SDK получает охватывающий прямоугольник и zoom 8–16. Области, выходящие за Web Mercator ±85.05112878° или пересекающие ±180°, отклоняются **до** создания ресурсов с объяснением в панели. Границы не обрезаются молча; разбиение через антимеридиан в этой версии не заявлено.

В SDK metadata v2 записываются имя, SDK ID, bounds, центр/радиус, стиль, zoom, timestamps, lifecycle state и ID заменяемого района. Неизвестные старые поля сохраняются; при обновлении metadata v1 её исходное содержимое сохраняется как `legacy_metadata` в той же атомарной записи SDK. Состояние READY из metadata не принимается за доказательство: после рестарта нужен `OfflineRegionStatus.isComplete()`.

Неопределённый required resource count показывает indeterminate progress и реальные bytes. Даже точное совпадение completed/required ограничено 99%, пока SDK не подтвердит complete. По завершении активная загрузка отключается.

Один готовый район плюс один временный кандидат. До подтверждения нового SDK complete **и успешной записи READY metadata** старый район сохраняется. После этого удаляется только конкретный прежний SDK ID. Ошибка удаления оставляет его с состоянием DELETING и Retry; новые кандидаты блокируются до разрешения существующего состояния. При нехватке места пользователь может явно удалить готовый район через диалог с именем и реальным объёмом. Панель предупреждает о потере офлайн-доступности; точки/поездки/GPX не затрагиваются.

Удаление предваряется устойчивой записью DELETING в SDK metadata. При ошибке этой записи ресурсы остаются, а Retry повторяет запись, а не обходит её. После рестарта DELETING завершается через SDK. Очередь metadata операций сериализуется. Ошибка записи READY не запускает бесконечные автоматические попытки: нужен явный Retry.

До старта проверяются реальные свободные bytes с резервом 64 MiB и приблизительный planning estimate (64 KiB на XYZ tile + 4 MiB для ресурсов стиля). Это **не** оценка поставщика: число реальных источников/размер тайлов неизвестны. Текст явно объясняет приблизительность. Максимальный размер одной загрузки ограничен 512 MiB; при заполнении диска/достижении бюджета загрузка останавливается, старый готовый район сохраняется. SDK tile-limit, сетевые ошибки, 401/403 и quota/429 показываются отдельно. Квота аккаунта не выводится из факта наличия ключа; её отказ обрабатывается по результату SDK.

## Проверка

- `scripts/Test-OfflineAreaPolicy.ps1`: **33 проверки PASS** — полюса, антимеридиан, точные bounds, стиль/zoom/покрытие, реальные ограничения прогресса, резерв диска и planning size.
- `scripts/Test-OfflineController.ps1`: **38 проверок PASS** — асинхронный SDK double с отказами создания, metadata, connection, disk pressure и delete; pause/resume, restart, замена, отсутствие READY по одной metadata, восстановление удаления. Тестовые классы из `tests/offline` не входят в Android APK.
- Контроллер и панель скомпилированы через `javac` с настоящими Android 35 / MapLibre 13.4.1 API; только app R/BuildConfig/статические helpers карты подставлены отдельно от незавершённых параллельных изменений.
- На этапе изолированного SDK-double реальный download/device UI были NOT RUN; это исторический scope создания подсистемы. Последующая интеграция подтвердила реальную загрузку/READY после restart, исправленные Show area/coverage повторены на финальном APK. **NOT RUN сейчас:** настоящий airplane mode, полный native lifecycle pause/retry/replace/delete с отказами, forced kill во время metadata commit, физическое заполнение хранилища. JVM SDK double не доказывает эти сценарии; [актуальная матрица T08/F5](MAP_BETA_ACCEPTANCE.md) отделяет их от выполненного download smoke.

Проверены официальные [OfflineRegion](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-region/index.html), [OfflineRegionStatus](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-region-status/index.html), [OfflineTilePyramidRegionDefinition](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-tile-pyramid-region-definition/index.html) и подписи установленной версии 13.4.1 через `javap`. Документация SDK подтверждает, что полного required bytes estimate API пока нет.
