# F8 — воспроизводимый профиль карты

Это методика и инструменты измерения. На этапе создания инструментов аппаратный прогон был NOT RUN; финальный повтор 15 сентября 23:12–23:14 на OnePlus CPH2609, Android 15 / API 35, при 60 Hz и APK `5028A341…` подтвердил **PASS обоих Window frame budgets**: standard 3 471 samples / 60.7779 s / p95 10.532985 ms; stress 3 547 samples / 61.2916 s / p95 9.858864 ms. В обоих >50 ms 0%, PID сохранился, invalid/overflow/dropped reports 0. [Standard report](qa/map-beta/performance/standard-report.json), [stress report](qa/map-beta/performance/stress-report.json). Повтор выполнен после заключительных UI/camera-правок; [прежние профили](qa/map-beta/performance-before-final-framing/summary.json) сохранены отдельно. Актуальный scope версии и непроверенные критерии — в [MAP_BETA_ACCEPTANCE.md](MAP_BETA_ACCEPTANCE.md); полный F8 не объявляется PASS по одним frame-профилям.

## Debug-наборы

`debug/MapDebugFixture.create("standard")`: 1 000 точек и 10 000 координат трека. `"stress"`: 10 000 точек и 50 000 координат. Генератор принимает только эти два значения и отклоняет вызов при `BuildConfig.DEBUG == false`.

Оба набора используют публичный центр Берлина (52.52, 13.405), фиксированный seed и UTC timestamp. У точек уникальные отрицательные IDs и `qa:` eventId, разные поддержанные типы, фиксированный tripId, избранное и совпадающие координаты. Трек содержит разрывы каждые 2 500 координат. Генератор не обращается к SharedPreferences, базе, GPS, фотографиям или сети. Контейнеры неизменяемы. Подготовка вызывается с worker; FieldMapPanel подставляет результат только в snapshot отображения и блокирует мутации синтетических точек.

Контракт интеграции MainActivity, с обязательной проверкой `BuildConfig.DEBUG`:

- `qa_map_dataset=standard|stress|off` → `fieldMap.setDebugDataset(...)`.
- `qa_map_profile=start|stop`, `qa_map_profile_label=<label>` → `MapFrameProfiler.start(label)` / `stop()`.
- `qa_map_zoom=<float>` → `fieldMap.debugZoomBy(delta)` / native map camera.
- В `onStop` и `onDestroy` остановить профилировщик, чтобы замер не продолжался в фоне. Хранить один экземпляр на Activity.
- После окончания сбросить fixture и keep-awake; личные данные в хранилище не меняются.

## Замер кадров

`MapFrameProfiler` подписывается на Android `Window.OnFrameMetricsAvailableListener`, читает `FrameMetrics.TOTAL_DURATION` и отдельно учитывает first draw. Collector работает на собственном HandlerThread; не инициирует перерисовки. Хранит максимум 24 000 samples, записывает p50/p95/max и долю кадров **строго** дольше 50 мс. p95 — nearest-rank. Потерянные callbacks — `dropped_reports`, а не число пропущенных кадров. Пустой/короткий набор, отсутствующая метрика, overflow или потерянные отчёты не дают PASS полного sample.

Результат сравнивается с 32 мс p95 / 5% >50 мс только при фактической частоте около 60 Hz. На 120 Hz сохраняются числа, но поле `standard_60hz_frame_budget` имеет NOT RUN. Не менять display refresh пользователя молча ради нужного результата.

Запись идёт только в `files/qa/map-profile.csv` (сырые ns) и `files/qa/map-profile.json` через AtomicFile вне UI thread. В JSON есть build version/code, модель, Android, refresh rate, длительность, количество samples, пропущенные отчёты и Java/native heap до/после. Git commit и dirty state добавляет desktop-скрипт. Координаты устройства в отчёты не записываются.

## Скрипт устройства

Из корня репозитория, когда Fiskentra уже находится на переднем плане:

```powershell
.\qa\Measure-MapPerformance.ps1 -Serial '<ADB serial>' -Datasets standard,stress -Seconds 60
```

Скрипт сам не запускается после сборки. Сначала необходимы интегрированные debug hooks и подключённое устройство. Он подставляет набор, даёт шесть секунд прогрева, получает свежие bounds MapLibre через UIAutomator, запускает измерение и выполняет 60 секунд центральных pan/swipe и native-camera zoom через debug intent. Камера масштабируется явно, поскольку double-tap по плотным маркерам может открыть карточку/кластер и изменить сценарий.

Перед **каждым** действием проверяется свежий foreground из `dumpsys window windows`; координаты свайпов остаются в средней части карты, вдали от свайпа прогноза и нижних действий. При переходе пользователя в другое приложение benchmark прекращает взаимодействие и не возвращает Fiskentra поверх него ради cleanup. На нормальном завершении отключаются fixture и keep-awake. При аварийном ADB disconnect cleanup выполняется позже вручную, а остановка измерения в Activity lifecycle остаётся обязательной.

Артефакты: `qa/map-performance/<timestamp>/` (уже исключено из Git общим правилом QA), JSON+CSV, gfxinfo framestats, meminfo, exit-info, ограниченный app runtime log. Для каждого набора записаны фактические число pan/zoom и длительность. Перед PASS проверить минимум 60 секунд фактического сценария, frame budgets и runtime/exit-info.

**Ограничения измерения:** Window frame duration не равна отдельному GPU/compositor presentation time. Meminfo до/после не является retained-object/heap dump исследованием утечек. Frame-скрипт не измеряет latency карточки/фильтра, полный lifecycle и 2–4-часовое сравнение батареи. Отдельный `qa/Measure-MapLifecycle.ps1` уже подтвердил 20 переходов; post-GC retained objects, rotation и батарея остаются NOT RUN. Не считать их проверенными по успешному панорамированию или одному стабильному Activity count. Для полного F8 нужны отдельные сценарии из ТЗ, включая взаимодействие Flic/записи.

## Проверка самих инструментов

- `scripts/Test-MapDebugTools.ps1`: **17 проверок PASS** — размеры, стабильные/уникальные IDs, повторы координат, поездка/избранное, 19 сегментных разрывов stress, p95 и точные граничные бюджеты, исключение first draw, dropped reports, empty/invalid/overflow.
- PowerShell parser `qa/Measure-MapPerformance.ps1`: **PASS**.
- Java profiler скомпилирован против настоящего Android 35 API: **PASS**.
- Реальный standard/stress Window-профиль после интеграции: **PASS в зафиксированном scope**, ссылки выше. Отдельно **PASS 20/20 Map→Saved→Map за 164.295 s**: один PID, 1 Activity / 1 ViewRootImpl / 214 Views на шагах 0/10/20. [Lifecycle report](qa/map-beta/lifecycle/report.json). Это не post-GC retained-object исследование; action latency, rotation/все style transitions и baseline/new battery 2–4 h остаются **NOT RUN**.

Основание API: [Android FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics) и [OnFrameMetricsAvailableListener](https://developer.android.com/reference/android/view/Window.OnFrameMetricsAvailableListener). Пороговые бюджеты определены ТЗ Fiskentra, не Android SDK.
